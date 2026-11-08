/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import android.graphics.Bitmap
import android.os.SystemClock
import com.reverie.paint.model.DisplayTileGrid
import com.reverie.paint.model.TileDeltaLedger
import com.reverie.paint.perf.TilePerfLog
import java.util.concurrent.atomic.AtomicReferenceArray

// ==================================================================
// 512 显示瓦片网格的渲染帧 (PaintViewModel 的 partial 扩展文件)
//
// 与整图双缓冲路径 (PaintViewModel.doRender) 的三点不同:
//  1. lockPixels 的目标是瓦片 (512x512), 不是整幅画布 —— 未被命中的瓦片位图引用不变,
//     HWUI 直接复用显存里的旧纹理, 本帧纹理上传量 = 命中瓦片面积。
//  2. 命中集合按**离散脏区**逐个求, 不取脏区并集: 两个对角 dab 只上传那两块。
//  3. 没有整幅 front/back 同步: 换下来的旧瓦片进冷却区, 下一帧才允许被重写,
//     onDraw 交给过 HWUI 的位图不会被就地写 (COW); 没交给过的才就地写。
//
// 除 discardTileGridForDocumentSwitch 外, 本文件的函数都只在渲染线程上调用。
// ==================================================================

private val EMPTY_DIRTY = IntArray(0)

/**
 * 这一帧是否走瓦片路径 (判定规则见 [DisplayTileGrid.shouldUse])。
 *
 * [PaintViewModel.displayTilesUnavailableForDoc] 是 OOM 熔断: 这份文档的瓦片网格曾经分配
 * 失败就整份退回整图路径, 不再每帧重试 —— 否则渲染线程会在"分配 250MB → OOM → 回收 → 再分配"
 * 的循环里空转, 比整图路径还糟。
 */
internal fun PaintViewModel.shouldRenderDisplayTiles(w: Int, h: Int): Boolean =
    DisplayTileFlags.enabled &&
        !displayTilesUnavailableForDoc &&
        DisplayTileGrid.shouldUse(
            w,
            h,
            coreW,
            coreH,
            anim.isPlaying,
            currentPage == Page.REPLAY,
            DisplayTileFlags.budgetBytes,
        )

internal fun PaintViewModel.doRenderTiles(w: Int, h: Int) {
    val st = displayTiles
    val target = ensureDisplayTileGrid(w, h) ?: return
    val grid = target.grid
    val tiles = target.tiles

    pollLiquifyGpuPreview()
    pollLiquifyStats()

    val traceOn = PerfTrace.enabled
    val tStartNs = SystemClock.elapsedRealtimeNanos()

    // 帧准备: 等异步合并 + 合成脏区, 并把本帧脏区**快照**取走 (最多 DIRTY_CAP 块)。
    val dirtyRects = st.dirtyRects
    val dirtyCount = ReverieCoreBridge.beginRenderTiles(dirtyRects)
    val tAfterCompositeNs = SystemClock.elapsedRealtimeNanos()
    // JNI 只写了前 dirtyCount*4 项, 尾巴清零 —— 否则上帧残留会被当成真脏区传回去。
    if (dirtyCount < DisplayTileState.DIRTY_CAP) {
        java.util.Arrays.fill(dirtyRects, dirtyCount * 4, dirtyRects.size, 0)
    }

    st.promoteCooledSpares()

    // 命中集合 = 需要整块重写的瓦片 ∪ 本帧脏区覆盖到的瓦片 (逐块去重)。
    val needsFull = st.needsFullWrite
    val touched = st.touched
    val hitList = st.hitList
    val hitScratch = st.hitScratch
    java.util.Arrays.fill(touched, false)
    var hits = 0
    for (i in 0 until grid.tileCount) {
        if (needsFull[i]) {
            touched[i] = true
            hitList[hits++] = i
        }
    }
    for (d in 0 until dirtyCount) {
        val b = d * 4
        val n =
            grid.tilesIntersecting(
                dirtyRects[b],
                dirtyRects[b + 1],
                dirtyRects[b] + dirtyRects[b + 2],
                dirtyRects[b + 1] + dirtyRects[b + 3],
                hitScratch,
            )
        for (k in 0 until n) {
            val idx = grid.tileIndex(hitScratch[k * 2], hitScratch[k * 2 + 1])
            if (!touched[idx]) {
                touched[idx] = true
                hitList[hits++] = idx
            }
        }
    }

    PerfTrace.tick("render.calls", 1000L)
    if (hits == 0) {
        if (traceOn) PerfTrace.renderPath(PerfTrace.PATH_SKIP, 0L, 0L)
        onTileRenderNoWork()
        return
    }

    var writtenLeft = Int.MAX_VALUE
    var writtenTop = Int.MAX_VALUE
    var writtenRight = Int.MIN_VALUE
    var writtenBottom = Int.MIN_VALUE
    var wrotePixels = 0L
    var anyFull = false
    // 就地写期间 onDraw 恰好画了这张瓦片: HWUI 可能上传了写到一半的像素, 本帧要补一次主画布失效。
    var tornInPlace = false
    var inPlaceCount = 0
    var retryNeeded = false
    val written = st.written
    val cols = grid.cols
    val drawn = target.drawnByView
    val ledger = st.deltaLedger
    for (k in 0 until hits) {
        val idx = hitList[k]
        val tx = idx % cols
        val ty = idx / cols
        val tw = grid.tileWidth(tx)
        val th = grid.tileHeight(ty)
        val left = grid.tileLeft(tx)
        val top = grid.tileTop(ty)
        val old = tiles.get(idx)
        val validOld = old != null && !old.isRecycled && old.width == tw && old.height == th
        // 位图尺寸对不上 (角落瓦片/文档尺寸变了) 只能整块写。
        val full = needsFull[idx] || !validOld
        // 交给过 HWUI 的瓦片位图不就地改写, 取后备位图写好再换进去 (COW)。自换入以来没被
        // onDraw 画过的位图不在任何 DisplayList 里, 占住 (CAS 到 WRITING) 之后就地写。
        val inPlace =
            validOld && drawn.compareAndSet(idx, PublishedTiles.TILE_UNDRAWN, PublishedTiles.TILE_WRITING)
        if (inPlace) inPlaceCount++
        val dst = if (inPlace) old!! else st.obtainSpare(tw, th, idx)
        val spareVersion = if (inPlace) -1L else st.obtainedSpareVersion
        val prevVersion = ledger.version(idx)
        if (dst == null) {
            // OOM 获取后备位图失败: 标记整块重写并触发低优先级重试
            needsFull[idx] = true
            retryNeeded = true
            continue
        }
        val ok =
            try {
                if (full) {
                    ReverieCoreBridge.renderTileToBuffer(dst, left, top, EMPTY_DIRTY, written)
                } else {
                    // 就地写时 dst 里就是最新的完整像素; 换片时若本帧有某块脏区已完全盖住
                    // 整张瓦片, 也不必先整块拷贝旧像素。
                    if (!inPlace && !isTileCoveredByDirty(dirtyRects, dirtyCount, left, top, tw, th)) {
                        // 后备位图若是本瓦片几帧前的旧版本, 只补拷之后写过的区域 (逐像素同整块拷贝)。
                        val mode =
                            if (spareVersion >= 0) ledger.deltaSince(idx, spareVersion, deltaBox) else TileDeltaLedger.DELTA_FULL
                        when (mode) {
                            TileDeltaLedger.DELTA_NONE -> {}
                            TileDeltaLedger.DELTA_RECT -> copyTileRectInto(old!!, dst, deltaBox)
                            else -> copyTileInto(old!!, dst)
                        }
                    }
                    ReverieCoreBridge.renderTileToBuffer(dst, left, top, dirtyRects, written)
                }
            } catch (t: Throwable) {
                android.util.Log.e("PaintViewModel", "renderTileToBuffer failed at ($tx,$ty)", t)
                false
            }
        if (inPlace &&
            !drawn.compareAndSet(idx, PublishedTiles.TILE_WRITING, PublishedTiles.TILE_UNDRAWN)
        ) {
            // CAS 失败 = 写的过程中 onDraw 把它标成了已画: 标记保持 DRAWN, 下一帧起走 COW。
            tornInPlace = true
        }
        if (!ok) {
            // 本帧写失败 (JNI 失败/格式不符): 后备位图还回冷却区, 标记整块重写以防脏区丢失。
            if (!inPlace) st.retireToCooling(dst)
            // 就地写失败可能改过任意像素: 账本记成整块改写, 之后的 COW 一律整块拷贝。
            ledger.recordWrite(idx, 0, 0, tw, th)
            needsFull[idx] = true
            retryNeeded = true
            continue
        }
        if (!inPlace) {
            // 先清标记再换入: onDraw 是"先取位图再标记", 这样它拿到新位图之后的标记一定不会被这里覆盖。
            drawn.set(idx, PublishedTiles.TILE_UNDRAWN)
            tiles.set(idx, dst)
            if (old != null) {
                if (validOld) st.retireTileToCooling(old, idx, prevVersion) else st.retireToCooling(old)
            }
        }
        recordTileWrite(ledger, idx, full, left, top, tw, th, written, dirtyRects, dirtyCount)
        if (needsFull[idx]) anyFull = true
        needsFull[idx] = false
        val wl = left + written[0]
        val wt = top + written[1]
        val wr = wl + written[2]
        val wb = wt + written[3]
        if (wl < writtenLeft) writtenLeft = wl
        if (wt < writtenTop) writtenTop = wt
        if (wr > writtenRight) writtenRight = wr
        if (wb > writtenBottom) writtenBottom = wb
        wrotePixels += written[2].toLong() * written[3].toLong()
    }
    val tAfterTilesNs = SystemClock.elapsedRealtimeNanos()

    if (writtenRight <= writtenLeft || writtenBottom <= writtenTop) {
        // 命中了但一块都没写成 (JNI 失败/OOM): 当作无操作。
        clearRenderWrittenRect()
        onTileRenderNoWork()
        if (retryNeeded) {
            st.consecutiveRetryFailures++
            if (st.consecutiveRetryFailures <= 3) {
                scheduleRender(immediate = false)
            } else {
                android.util.Log.w(
                    "DisplayTiles",
                    "Tile render retry failed ${st.consecutiveRetryFailures} times consecutively, aborting retry spin",
                )
                st.needsFullWrite.fill(false)
                st.consecutiveRetryFailures = 0
            }
        } else {
            st.consecutiveRetryFailures = 0
        }
        return
    }
    publishRenderWrittenRect(writtenLeft, writtenTop, writtenRight, writtenBottom)
    if (traceOn) {
        PerfTrace.renderPath(
            if (anyFull) PerfTrace.PATH_FULL else PerfTrace.PATH_INCR,
            SystemClock.elapsedRealtimeNanos() - tStartNs,
            wrotePixels,
        )
        // 纹理重传代理量: 只算本帧真正重写的瓦片。
        PerfTrace.renderFlip(wrotePixels * 4L, wrotePixels)
    }

    // 暂存网格到这里才全部写完, 现在才交给 UI 线程。
    if (st.staging === target) {
        if (st.needsFullWrite.any { it }) {
            // 还有瓦片没写成 (OOM 等): 不发布半张网格, 下一帧接着写。
            scheduleRender(immediate = false)
            return
        }
        st.publishStaging()
        // 整图双缓冲在瓦片路径下用不上, 释放掉 (切回整图路径时会按需重分配)。
        releaseFullCanvasBuffers()
    }

    val tDiagPub0 = if (PerfTrace.enabled) SystemClock.elapsedRealtimeNanos() else 0L
    publishTileSnapshotIfNeeded(grid, tiles)
    tileRevision++
    // 前缓冲真墨: 运笔按住时把本帧离散脏区从瓦片烤好贴上前缓冲, 接住了就不失效主画布。
    val sink = realInkSink
    val published = st.published
    val kept =
        sink != null && published != null &&
            sink.presentTileFrame(
                published,
                dirtyRects,
                dirtyCount,
                writtenLeft,
                writtenTop,
                writtenRight,
                writtenBottom,
                anyFull,
            )
    val tDiagPresent1 = if (tDiagPub0 != 0L) SystemClock.elapsedRealtimeNanos() else 0L
    sink?.finishFrame(kept)
    sink?.onTileFrameEnd(tileRevision)
    if (LandDiag.enabled && LandDiag.armed) LandDiag.noteRender(inPlaceCount, tornInPlace)
    val tDiagPresent2 = if (tDiagPub0 != 0L) SystemClock.elapsedRealtimeNanos() else 0L
    notifyCanvasFramePublished(keptOnFrontBuffer = kept)
    if (tDiagPub0 != 0L) {
        val end = SystemClock.elapsedRealtimeNanos()
        val total = (end - tAfterTilesNs) / 1_000_000.0
        if (total >= 20.0) {
            PerfTrace.log(
                "tileTail pub=${(tDiagPresent1 - tDiagPub0) / 1_000_000.0}ms " +
                    "present=${(tDiagPresent2 - tDiagPresent1) / 1_000_000.0}ms " +
                    "notify=${(end - tDiagPresent2) / 1_000_000.0}ms total=${"%.1f".format(total)}ms kept=$kept",
            )
        }
    }
    // 被撕裂的那张瓦片在前缓冲上是完整的, 但主画布的纹理可能是半张: 补一次失效让 HWUI 重传。
    if (kept && tornInPlace) invalidateCanvasFromRender()
    if (retryNeeded) {
        st.consecutiveRetryFailures++
        if (st.consecutiveRetryFailures <= 3) {
            scheduleRender(immediate = false)
        } else {
            android.util.Log.w(
                "DisplayTiles",
                "Tile render retry failed ${st.consecutiveRetryFailures} times consecutively, aborting retry spin",
            )
            st.needsFullWrite.fill(false)
            st.consecutiveRetryFailures = 0
        }
    } else {
        st.consecutiveRetryFailures = 0
    }

    TilePerfLog.frame(
        tAfterCompositeNs - tStartNs,
        tAfterTilesNs - tAfterCompositeNs,
        SystemClock.elapsedRealtimeNanos() - tAfterTilesNs,
        hits,
        dirtyCount,
        wrotePixels,
        anyFull,
    )
    // 整屏重画的帧才值得进日志 (大画幅切显隐/撤销后的那一帧就在这里)
    val frameMs = (SystemClock.elapsedRealtimeNanos() - tStartNs) / 1_000_000L
    if (frameMs >= 60L) {
        PerfTrace.event(
            "tileFrame",
            "ms" to frameMs,
            "prepMs" to (tAfterCompositeNs - tStartNs) / 1_000_000L,
            "tilesMs" to (tAfterTilesNs - tAfterCompositeNs) / 1_000_000L,
            "hits" to hits.toLong(),
            "mp" to ((wrotePixels / 1_000_000L)),
        )
    }
}

/** 瓦片路径的"这一帧没有新像素"收尾 (与整图路径保持同样的重试/液化语义)。 */
private fun PaintViewModel.onTileRenderNoWork() {
    realInkSink?.onTileFrameEnd(tileRevision)
    if (ReverieCoreBridge.renderPendingDirty()) {
        renderFollowUpPending = true
        renderHandler?.postDelayed({ doRender() }, 4L)
    } else if (liquifyPresentation.isPending(liquifyPresentationGesture)) {
        val p = displayTiles.published
        val snap = if (p != null) composeTileSnapshot(p.grid, p.tiles) else displayBitmap
        snap?.let { liquifyPresentation.publish(liquifyPresentationGesture, it) }
        invalidateCanvasFromRender()
    }
    // 参考窗口打开且切到画布页时: 若快照尚未生成或落后于当前瓦片版本, 补拼一张完整快照
    if (referenceWindowOpen && referenceActiveTab == 1) {
        val p = displayTiles.published
        val st = displayTiles
        if (p != null && (displayBitmap == null || displayBitmap?.isRecycled == true || st.lastSnapshotTileRevision != tileRevision)) {
            publishTileSnapshotIfNeeded(p.grid, p.tiles, force = true)
        }
    }
}

/**
 * 保证瓦片网格与当前渲染尺寸一致, 返回这一帧要写的网格 (已发布的, 或重建中的暂存)。
 * 尺寸变了 / 显示缓冲作废了就整体重建, 并给所有瓦片打上"需要整块重写"标记。
 *
 * 旧瓦片位图**不 recycle**: HWUI 的 DisplayList 可能仍持有它们, 交给 GC 即可。
 */
private fun PaintViewModel.ensureDisplayTileGrid(w: Int, h: Int): PublishedTiles? {
    val st = displayTiles
    val existing = st.published
    // displayBufferInvalid 覆盖"尺寸没变但文档换了"的情况 (加载工程、新建画布、动画导出),
    // 那些路径只置标志不置尺寸 —— 不重建就会留着上一份文档的瓦片像素。
    if (existing != null && existing.grid.width == w && existing.grid.height == h && !displayBufferInvalid) {
        st.dropStaging()
        return existing
    }
    // 上一帧已经为同一尺寸开好暂存网格但还没写完: 接着写它, 不重复分配。
    val staged = st.staging
    if (staged != null && staged.grid.width == w && staged.grid.height == h && !displayBufferInvalid) {
        return staged
    }
    val grid = DisplayTileGrid(w, h)
    if (grid.isEmpty) return null
    // 大画布上一次分配失败常常只是瞬时压力 (加载峰值 / 旧文档还没回收): 先回收一轮再试一次,
    // 两次都失败才熔断。否则一次瞬时 OOM 会把这份文档永久钉在最差的整图路径上。
    var arr: AtomicReferenceArray<Bitmap?>? = null
    for (attempt in 0 until 2) {
        val a = AtomicReferenceArray<Bitmap?>(grid.tileCount)
        try {
            for (ty in 0 until grid.rows) {
                for (tx in 0 until grid.cols) {
                    a.set(
                        grid.tileIndex(tx, ty),
                        Bitmap.createBitmap(grid.tileWidth(tx), grid.tileHeight(ty), Bitmap.Config.ARGB_8888),
                    )
                }
            }
            arr = a
            break
        } catch (oom: OutOfMemoryError) {
            android.util.Log.e(
                "PaintViewModel",
                "OOM allocating display tile grid ($w x $h) attempt=${attempt + 1}",
                oom,
            )
            if (attempt == 0) releaseReclaimableCaches(aggressive = true)
        }
    }
    val allocated = arr
    if (allocated == null) {
        // 熔断: 这份文档从此走整图路径, 不再每帧尝试重分配 (见 shouldRenderDisplayTiles)。
        // 同时置 displayBufferInvalid, 让下一帧的整图路径做一次全量帧 (否则它手里只有旧文档像素)。
        displayTilesUnavailableForDoc = true
        displayBufferInvalid = true
        scheduleRender(immediate = true)
        return null
    }
    st.beginStaging(grid, allocated)
    displayBufferInvalid = false
    clearRenderWrittenRect()
    if (PerfTrace.enabled) {
        // 大画布上这行是"到底走了哪条路径、要多少显存"的唯一证据 (250MB 级文档尤其需要)。
        PerfTrace.log(
            "displayTiles grid=${grid.width}x${grid.height} tiles=${grid.tileCount} " +
                "bytes=${DisplayTileGrid.displayBytes(w, h) / (1024L * 1024L)}MB " +
                "budget=${DisplayTileFlags.budgetBytes / (1024L * 1024L)}MB",
        )
    }
    return st.staging
}

/** 切回整图路径 (缩放回退 / 动画播放 / 回放页) 或换文档时丢掉瓦片网格。只在渲染线程调用。 */
internal fun PaintViewModel.clearDisplayTileGrid() {
    val st = displayTiles
    if (!st.hasAnyGrid) return
    st.clear()
    // 瓦片帧已经取走了引擎脏区, 整图缓冲 (若还留着) 里的像素不再可信: 下次整图渲染强制全量。
    displayBufferInvalid = true
}

/**
 * UI 线程的"换文档前作废瓦片网格"入口 ([loadProject]/[startPainting] 进画面前调用)。
 *
 * 不在 UI 线程上改网格: 只登记一次作废请求 (onDraw 立刻不再画旧网格), 真正的清理投递到
 * 渲染线程, 与渲染帧串行。
 *
 * 同时把整图双缓冲也放掉: 那两份位图里装的是**上一份文档**的像素, 新文档的首帧还没上屏
 * 之前 onDraw 会照画不误 —— 这就是"加载时一直显示上一次打开的文件"的成因 (网格作废后
 * `drawCanvas` 会退到 `displayBitmap ?: docBitmap`)。释放后画布只画背景色, 加载遮罩和
 * [PaintViewModel.awaitingFirstFrameAfterLoad] 负责盖住这段空白窗口。
 */
internal fun PaintViewModel.discardTileGridForDocumentSwitch() {
    realInkSink?.onDocumentDiscarded()
    // 新文档的尺寸/图层结构都可能不同: 上一份文档的 OOM 熔断不该带过来。
    displayTilesUnavailableForDoc = false
    // 从这一刻起到新文档第一帧上屏, 画布不画任何文档像素 —— 旧网格已作废, 旧整图缓冲
    // 也即将被放掉, 中间任何一帧都不该再拿上一份文档的像素顶上 (见 documentSwitchPending)。
    documentSwitchPending = true
    val st = displayTiles
    val gen = st.requestDiscard()
    val rh = renderHandler
    if (rh == null) {
        // 渲染线程还没起来: 不可能有网格, 直接视为已处理。
        releaseFullCanvasBuffers()
        st.markDiscardHandled(gen)
        return
    }
    rh.post {
        clearDisplayTileGrid()
        releaseFullCanvasBuffers()
        // 整图路径下一帧必须重建成新文档的全量帧, 不能用 (已经放掉的) 旧缓冲做增量。
        displayBufferInvalid = true
        st.markDiscardHandled(gen)
    }
}

/** 本帧离散脏区里是否有一块已经完整覆盖整张瓦片 `[left, top, left + tw, top + th)`。 */
private fun isTileCoveredByDirty(
    rects: IntArray,
    count: Int,
    left: Int,
    top: Int,
    tw: Int,
    th: Int,
): Boolean {
    val right = left + tw
    val bottom = top + th
    for (d in 0 until count) {
        val b = d * 4
        val dl = rects[b]
        val dt = rects[b + 1]
        if (dl <= left && dt <= top && dl + rects[b + 2] >= right && dt + rects[b + 3] >= bottom) {
            return true
        }
    }
    return false
}

private val tileCopyCanvas = android.graphics.Canvas()
private val tileCopyPaint =
    android.graphics.Paint().apply {
        xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC)
    }
private val tileSnapshotPaint = android.graphics.Paint().apply {
    xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC)
}

private val deltaBox = IntArray(4)
private val deltaRect = android.graphics.Rect()

/** 只把 [src] 的 [box] (l,t,r,b, 瓦片局部) 区域 SRC 拷进 [dst] (同尺寸)。只在渲染线程调用。 */
private fun copyTileRectInto(src: Bitmap, dst: Bitmap, box: IntArray) {
    deltaRect.set(
        box[0].coerceIn(0, src.width),
        box[1].coerceIn(0, src.height),
        box[2].coerceIn(0, src.width),
        box[3].coerceIn(0, src.height),
    )
    if (deltaRect.isEmpty) return
    tileCopyCanvas.setBitmap(dst)
    tileCopyCanvas.drawBitmap(src, deltaRect, deltaRect, tileCopyPaint)
    tileCopyCanvas.setBitmap(null)
}

/**
 * 账本记下这次写入改过的区域 (瓦片局部): 整块写记整块; 增量写取 JNI 回报的写入框与
 * 本帧脏区∩瓦片的并集 (保守取大, 宁多拷不少拷)。
 */
private fun recordTileWrite(
    ledger: TileDeltaLedger,
    idx: Int,
    full: Boolean,
    left: Int,
    top: Int,
    tw: Int,
    th: Int,
    written: IntArray,
    rects: IntArray,
    count: Int,
) {
    if (full) {
        ledger.recordWrite(idx, 0, 0, tw, th)
        return
    }
    var l = written[0]
    var t = written[1]
    var r = written[0] + written[2]
    var b = written[1] + written[3]
    if (written[2] <= 0 || written[3] <= 0) {
        l = Int.MAX_VALUE
        t = Int.MAX_VALUE
        r = Int.MIN_VALUE
        b = Int.MIN_VALUE
    }
    for (d in 0 until count) {
        val o = d * 4
        val dl = maxOf(rects[o] - left, 0)
        val dt = maxOf(rects[o + 1] - top, 0)
        val dr = minOf(rects[o] + rects[o + 2] - left, tw)
        val db = minOf(rects[o + 1] + rects[o + 3] - top, th)
        if (dl >= dr || dt >= db) continue
        if (dl < l) l = dl
        if (dt < t) t = dt
        if (dr > r) r = dr
        if (db > b) b = db
    }
    if (l >= r || t >= b) {
        // 说不清改了哪里: 保守记整块。
        ledger.recordWrite(idx, 0, 0, tw, th)
    } else {
        ledger.recordWrite(idx, maxOf(l, 0), maxOf(t, 0), minOf(r, tw), minOf(b, th))
    }
}

/** 把 [src] 的像素整块 SRC 拷进 [dst] (同尺寸)。只在渲染线程调用。 */
private fun copyTileInto(src: Bitmap, dst: Bitmap) {
    tileCopyCanvas.setBitmap(dst)
    tileCopyCanvas.drawBitmap(src, 0f, 0f, tileCopyPaint)
    tileCopyCanvas.setBitmap(null)
}

/**
 * 参考窗口/液化上屏需要"一张整图", 瓦片路径下按需把瓦片拼进快照。
 * 只在那些功能真的开着时才拼; 运笔期间最多每 SNAPSHOT_MIN_MS 拼一次 ——
 * 整图快照是整幅画布的软件拼装, 每帧都做会把瓦片网格省下的带宽原样还回去。
 */
private fun PaintViewModel.publishTileSnapshotIfNeeded(
    grid: DisplayTileGrid,
    tiles: AtomicReferenceArray<Bitmap?>,
    force: Boolean = false,
) {
    val liquifyPending = liquifyPresentation.isPending(liquifyPresentationGesture)
    val refCanvasActive = referenceWindowOpen && referenceActiveTab == 1
    if (!refCanvasActive && !liquifyPending) return
    val st = displayTiles
    val now = SystemClock.uptimeMillis()
    if (!force && !liquifyPending && isStrokeRenderActive() && now - st.lastSnapshotMs < DisplayTileState.SNAPSHOT_MIN_MS) {
        return
    }
    st.lastSnapshotMs = now
    st.lastSnapshotTileRevision = tileRevision
    val snap = composeTileSnapshot(grid, tiles) ?: return
    displayBitmap = snap
    if (liquifyPending) liquifyPresentation.publish(liquifyPresentationGesture, snap)
    if (refCanvasActive) {
        mainHandler.post {
            referenceCanvasRevision++
        }
    }
}

private fun PaintViewModel.composeTileSnapshot(grid: DisplayTileGrid, tiles: AtomicReferenceArray<Bitmap?>): Bitmap? {
    val target = displayTiles.nextSnapshotTarget(grid.width, grid.height) ?: return null
    tileCopyCanvas.setBitmap(target)
    for (ty in 0 until grid.rows) {
        for (tx in 0 until grid.cols) {
            val b = tiles.get(grid.tileIndex(tx, ty)) ?: continue
            if (b.isRecycled) continue
            tileCopyCanvas.drawBitmap(b, grid.tileLeft(tx).toFloat(), grid.tileTop(ty).toFloat(), tileSnapshotPaint)
        }
    }
    tileCopyCanvas.setBitmap(null)
    return target
}
