/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import com.reverie.paint.model.*
import kotlinx.coroutines.delay
import com.reverie.paint.ui.painting.canvas.invalidateFromRender
import com.reverie.paint.core.PaintViewModel.RenderedFrontier

// 拆分自 PaintViewModel.kt: 同域实现集中于此 (AGENTS.md §6 单文件行数上限)

internal fun PaintViewModel.initPerformanceHintSession() {
    if (adpfDisabled || DeviceInfo.isHuaweiOrHonor) {
        return
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && hasAppContext() && renderThreadTid > 0 && performanceHintSession == null) {
        try {
            val phm = appContext.getSystemService(android.os.PerformanceHintManager::class.java)
            if (phm != null) {
                val targetNanos = 8_333_333L // 120Hz frame budget target
                performanceHintSession = phm.createHintSession(intArrayOf(renderThreadTid), targetNanos)
                android.util.Log.i("PaintViewModel", "ADPF PerformanceHintSession created for tid $renderThreadTid (target=${targetNanos}ns)")
            }
        } catch (t: Throwable) {
            adpfDisabled = true
            android.util.Log.w("PaintViewModel", "ADPF PerformanceHintSession init failed, disabling ADPF: ${t.message}")
        }
    }
}

internal fun PaintViewModel.reportRenderWorkDuration(durationNanos: Long) {
    if (adpfDisabled || DeviceInfo.isHuaweiOrHonor || durationNanos <= 0L) {
        return
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (performanceHintSession == null) {
            initPerformanceHintSession()
        }
        try {
            (performanceHintSession as? android.os.PerformanceHintManager.Session)?.reportActualWorkDuration(durationNanos)
        } catch (t: Throwable) {
            adpfDisabled = true
            closePerformanceHintSession()
            android.util.Log.w("PaintViewModel", "ADPF reportActualWorkDuration failed, disabling ADPF: ${t.message}")
        }
    }
}

internal fun PaintViewModel.closePerformanceHintSession() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        try {
            (performanceHintSession as? android.os.PerformanceHintManager.Session)?.close()
        } catch (_: Throwable) {}
        performanceHintSession = null
    }
}

internal fun PaintViewModel.startRenderThread() {
    val thread = HandlerThread("reverie-render", android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY)
    thread.start()
    renderThread = thread
    renderHandler = Handler(thread.looper)
    // Ensure priority is set to URGENT_DISPLAY and bind affinity to performance cores
    val h = renderHandler
    h?.post {
        renderThreadTid = android.os.Process.myTid()
        android.os.Process.setThreadPriority(
            android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY
        )
        try {
            if (!DeviceInfo.isHuaweiOrHonor) {
                ReverieCoreBridge.bindCurrentThreadToPerformanceCores()
            }
        } catch (t: Throwable) {
            android.util.Log.w("PaintViewModel", "Failed to bind render thread affinity: ${t.message}")
        }
        initPerformanceHintSession()
    }
}

/**
 * Run a C++ document operation on the render thread, then schedule a
 * display refresh and (optionally) run [after] on the main thread.
 * All core mutation must go through here so it never races with the
 * projection recomposition running on the render thread.
 */
internal fun PaintViewModel.runCore(
    render: Boolean = true,
    after: (() -> Unit)? = null,
    op: () -> Unit,
) {
    val h = renderHandler ?: run {
        if (after != null) mainHandler.post { after() }
        return
    }
    pendingCoreOps.incrementAndGet()
    h.post {
        pendingCoreOps.decrementPositive()
        try {
            op()
        } catch (t: Throwable) {
            android.util.Log.e("ReverieCore", "runCore op() threw, still dispatching after()", t)
        } finally {
            if (render) scheduleRender()
            if (after != null) mainHandler.post { after() }
        }
    }
}

internal fun PaintViewModel.isLayerBackendBusy(): Boolean = pendingLayerStructureOps.get() > 0

internal fun PaintViewModel.runLayerCore(
    render: Boolean = true,
    after: (() -> Unit)? = null,
    op: () -> Unit,
) {
    val h = renderHandler ?: run {
        if (after != null) mainHandler.post { after() }
        return
    }
    pendingLayerStructureOps.incrementAndGet()
    pendingCoreOps.incrementAndGet()
    h.post {
        pendingCoreOps.decrementPositive()
        try {
            op()
        } catch (t: Throwable) {
            android.util.Log.e("ReverieCore", "runLayerCore op() threw, still dispatching after()", t)
        } finally {
            if (render) scheduleRender()
            mainHandler.post {
                try {
                    after?.invoke()
                } finally {
                    pendingLayerStructureOps.decrementPositive()
                }
            }
        }
    }
}

internal fun PaintViewModel.resetRenderedFrontier() {
    currentRenderedFrontier = null
    lastE2ePipelineMs = 8L
}

internal fun PaintViewModel.updateRenderedFrontier(docX: Float, docY: Float, timeMs: Long) {
    if (docX.isFinite() && docY.isFinite()) {
        currentRenderedFrontier = RenderedFrontier(docX, docY, timeMs)
    }
}

internal fun PaintViewModel.queueStrokeMove(
    x: Float,
    y: Float,
    p: Double,
    inputEventTimeMs: Long = 0L,
    tiltX: Double = 0.0,
    tiltY: Double = 0.0,
    rotation: Double = 0.0,
) {
    val h = renderHandler ?: return
    lastQueuedInputEventTime = inputEventTimeMs
    lastQueuedUptime = android.os.SystemClock.uptimeMillis()
    if (x.isNaN() || y.isNaN()) return
    val safeP = if (p.isNaN() || p < 0.0) 1f else p.toFloat().coerceIn(0f, 1f)
    val safeTiltX = if (tiltX.isNaN()) 0f else tiltX.toFloat().coerceIn(-60f, 60f)
    val safeTiltY = if (tiltY.isNaN()) 0f else tiltY.toFloat().coerceIn(-60f, 60f)
    // 笔身旋转 (AXIS_RZ 换算结果) 必须原样进批: 这里曾硬编码 0f, 把上层已解析好的旋转值
    // 整条链路吞掉, Krita 的 Rotation 传感器因此恒为 0
    val safeRotation =
        com.reverie.paint.model.BarrelRotationPolicy.strokeComponent(rotation).toFloat()

    pendingSampleX = x.toDouble()
    pendingSampleY = y.toDouble()
    pendingSampleP = safeP.toDouble()
    synchronized(strokeBatchLock) {
        if (strokeBatchCount == 0) {
            // 本点是当前待消费批的首点, 记录入队时刻作为 queueWait 遥测锚点
            firstQueuedUptime = android.os.SystemClock.uptimeMillis()
        }
        if (strokeBatchCount < PaintViewModel.STROKE_BATCH_CAPACITY) {
            val o = strokeBatchCount * PaintViewModel.STROKE_SAMPLE_STRIDE
            strokeBatchCoords[o] = x
            strokeBatchCoords[o + 1] = y
            strokeBatchCoords[o + 2] = safeP
            strokeBatchCoords[o + 3] = safeTiltX
            strokeBatchCoords[o + 4] = safeTiltY
            strokeBatchCoords[o + 5] = safeRotation
            strokeBatchTimes[strokeBatchCount] = inputEventTimeMs
            strokeBatchCount++
        } else {
            // 队列满 (渲染线程被长任务阻塞): 覆盖最后一个样本而非静默
            // 丢弃新样本 —— drain 时笔画尾端仍是最新位置, 中段被直线化
            // 但不断线不缺墨 (此前满后直接 return, 新点整段丢失)。
            val o = (strokeBatchCount - 1) * PaintViewModel.STROKE_SAMPLE_STRIDE
            strokeBatchCoords[o] = x
            strokeBatchCoords[o + 1] = y
            strokeBatchCoords[o + 2] = safeP
            strokeBatchCoords[o + 3] = safeTiltX
            strokeBatchCoords[o + 4] = safeTiltY
            strokeBatchCoords[o + 5] = safeRotation
            strokeBatchTimes[strokeBatchCount - 1] = inputEventTimeMs
            if (++batchOverflowLogCounter % 60 == 1) {
                android.util.Log.w(
                    "ReveriePerf",
                    "StrokeBatch overflow: render thread backed up, overwriting tail (capacity=$PaintViewModel.STROKE_BATCH_CAPACITY)"
                )
            }
        }
    }
    if (!strokeBatchQueued) {
        strokeBatchQueued = true
        pendingCoreOps.incrementAndGet()
        h.post(strokeBatchRunnable) // 预建 Runnable 直接投递, 每样本零分配
    }
}

/**
 * 渲染线程上, 在提交 touchStrokeEnd 之前把还排在队列里的运笔补点切片循环同步排空 (防御性)。
 *
 * 切片化消费 (单次最多 16 点) 下, 若队列仍有积压点, 循环摘除已排队的 [strokeBatchRunnable]
 * (若尚未入队则先递增 [pendingCoreOps] 配平 runnable 内部的 decrement) 并同步执行,
 * 确保全部尾段采样在 touchStrokeEnd 提交前 100% 落入引擎。只允许在渲染线程调用。
 */
internal fun PaintViewModel.drainPendingStrokeBatchOnRenderThread() {
    if (strokeThrottleRenderScheduled) {
        renderHandler?.removeCallbacks(strokeThrottleRenderRunnable)
        strokeThrottleRenderScheduled = false
    }
    val h = renderHandler ?: return
    while (synchronized(strokeBatchLock) { strokeBatchCount > 0 }) {
        if (strokeBatchQueued) {
            h.removeCallbacks(strokeBatchRunnable)
        } else {
            pendingCoreOps.incrementAndGet()
        }
        strokeBatchRunnable.run()
    }
}

/** Drop undelivered stroke samples (cancel path: they must not reach the
 *  engine after touchStrokeCancel ran). */
internal fun PaintViewModel.clearPendingStrokeSamples() {
    if (strokeThrottleRenderScheduled) {
        renderHandler?.removeCallbacks(strokeThrottleRenderRunnable)
        strokeThrottleRenderScheduled = false
    }
    synchronized(strokeBatchLock) {
        strokeBatchCount = 0
    }
    // s12-F1: 取消 / 撤销 / 重做 / 离开页面都会让引擎关掉打开的笔触, 镜像门同步关掉,
    // 否则后续镜像批被引擎按过期丢弃而 Kotlin 仍以为在实时出墨, 抬笔也不重放。
    mirrorGate.abort()
    mirrorQueue.clear()
    resetRenderedFrontier()
}

/**
 * UI 线程: 追加一个镜像采样点。队列满时丢弃最新的这个点 (不覆盖已入队的点),
 * 也不在 UI 线程调用 JNI。
 */
internal fun PaintViewModel.queueMirrorSample(
    branchIndex: Int,
    x: Float,
    y: Float,
    pressure: Float,
    tiltX: Float,
    tiltY: Float,
    rotation: Float,
    timeSeconds: Float,
) {
    if (!mirrorGate.isQueueing) return
    if (branchIndex < 0 || branchIndex >= PaintViewModel.MIRROR_BRANCH_COUNT) return
    if (x.isNaN() || y.isNaN()) return
    val seq = mirrorGate.currentSeq
    if (seq <= 0) return
    val sc = mirrorPushScratch
    sc[0] = x
    sc[1] = y
    sc[2] = if (pressure.isNaN()) 1f else pressure.coerceIn(0f, 1f)
    // 与主笔 touchStart / queueStrokeMove 一致, 倾角夹到 ±60
    sc[3] = if (tiltX.isNaN()) 0f else tiltX.coerceIn(-60f, 60f)
    sc[4] = if (tiltY.isNaN()) 0f else tiltY.coerceIn(-60f, 60f)
    sc[5] = if (rotation.isNaN()) 0f else rotation
    sc[6] = if (timeSeconds.isNaN()) 0f else timeSeconds.coerceAtLeast(0f)
    if (!mirrorQueue.push(branchIndex, seq, sc) && ++mirrorOverflowLogCounter % 60 == 1) {
        android.util.Log.w(
            "ReverieCore",
            "MirrorBatch overflow: branch=$branchIndex dropped (capacity=$PaintViewModel.MIRROR_BRANCH_CAPACITY)"
        )
    }
}

/**
 * 渲染线程: 把引擎当前打开那一笔 ([engineMirrorSeq]) 的镜像点画进笔触。返回是否送出了被引擎
 * 接受的镜像墨 (调用方据此决定要不要 doRender)。
 *
 * - 只取本笔笔画号的点; 下一笔 (笔画号更大) 的点留在队列里, 更早的过期点丢弃。
 * - 本笔还在 PENDING 时, 送墨在 [MirrorLiveGate.withLock] 内执行, 与 UI 线程抬笔判定
 *   ([resolveMirrorLiveAtPenUp]) 互斥; 进入 LIVE 后不可能再回退, 其余分支在锁外送 (UI 不被整批阻塞)。
 * - 引擎拒绝 (本笔还没有任何实时镜像墨) -> FALLBACK, 丢弃本笔剩余镜像点, 抬笔走 replaySymmetricBranches。
 * - 引擎回报过期 (它已不在画这一笔) -> 丢弃本笔剩余镜像点, 状态不变。
 */
internal fun PaintViewModel.drainMirrorBatchesOnRenderThread(): Boolean {
    val seq = engineMirrorSeq
    if (seq <= 0) return false
    val st0 = mirrorGate.stateOf(seq)
    if (st0 != MirrorLiveGate.State.PENDING && st0 != MirrorLiveGate.State.LIVE) {
        if (st0 == MirrorLiveGate.State.FALLBACK) mirrorQueue.discardUpTo(seq)
        return false
    }
    var painted = false
    for (b in 0 until PaintViewModel.MIRROR_BRANCH_COUNT) {
        val dst = mirrorQueue.take(b, seq, mirrorDrainCoords[b]) ?: continue
        mirrorDrainCoords[b] = dst
        val n = dst.size / PaintViewModel.STROKE_SAMPLE_STRIDE_WITH_TIME
        val result: MirrorLiveGate.EngineResult? = when (mirrorGate.stateOf(seq)) {
            MirrorLiveGate.State.LIVE -> sendMirrorBatch(dst, n, b, seq).also { mirrorGate.onEngineResult(seq, it) }
            MirrorLiveGate.State.PENDING -> mirrorGate.withLock {
                // 锁内再看一次: UI 线程可能刚在抬笔时把本笔判成 FALLBACK
                when (mirrorGate.stateOf(seq)) {
                    MirrorLiveGate.State.PENDING, MirrorLiveGate.State.LIVE ->
                        sendMirrorBatch(dst, n, b, seq).also { mirrorGate.onEngineResult(seq, it) }
                    else -> null
                }
            }
            else -> null
        }
        when (result) {
            MirrorLiveGate.EngineResult.ACCEPTED -> painted = true
            MirrorLiveGate.EngineResult.REJECTED -> {
                if (mirrorGate.stateOf(seq) != MirrorLiveGate.State.LIVE) {
                    // 本笔还没有实时镜像墨: 抬笔重放 (镜像缓冲仍完整保存在 CanvasTouchView)
                    mirrorQueue.discardUpTo(seq)
                    return painted
                }
            }
            MirrorLiveGate.EngineResult.STALE, null -> {
                mirrorQueue.discardUpTo(seq)
                return painted
            }
        }
    }
    return painted
}

/** 渲染线程: 送一批镜像点并把 JNI 返回码映射成 [MirrorLiveGate.EngineResult]。 */
internal fun PaintViewModel.sendMirrorBatch(coords: FloatArray, count: Int, branch: Int, seq: Int): MirrorLiveGate.EngineResult {
    val r = try {
        ReverieCoreBridge.touchStrokeMirrorBatch(coords, count, branch, seq)
    } catch (_: Throwable) {
        PaintViewModel.MIRROR_BATCH_REJECTED
    }
    return when (r) {
        PaintViewModel.MIRROR_BATCH_ACCEPTED -> MirrorLiveGate.EngineResult.ACCEPTED
        PaintViewModel.MIRROR_BATCH_STALE -> MirrorLiveGate.EngineResult.STALE
        else -> MirrorLiveGate.EngineResult.REJECTED
    }
}

/**
 * UI 线程抬笔: 镜像是否已实时出墨。false 时 (含引擎还没来得及确认) 本笔停止入队并丢弃本笔
 * 还在队列里的镜像点, 调用方走 replaySymmetricBranches; 保证不会既实时又重放。
 */
internal fun PaintViewModel.resolveMirrorLiveAtPenUp(): Boolean {
    val live = mirrorGate.resolveAtPenUp()
    if (!live) mirrorQueue.discardUpTo(mirrorGate.currentSeq)
    return live
}

/** Arm the airbrush timer for the current stroke if the brush enables it.
 *  Also seeds the coalesced-sample fields so ticks fired before the first
 *  move record the stroke's true start point instead of stale values. */
internal fun PaintViewModel.startAirbrushIfNeeded(
    x: Float,
    y: Float,
    p: Double,
) {
    pendingSampleX = x.toDouble()
    pendingSampleY = y.toDouble()
    pendingSampleP = p
    if (!brushAirbrush) return
    val rate = brushAirbrushRate.coerceIn(10.0, 120.0)
    airbrushIntervalMs = (1000.0 / rate).toLong().coerceIn(8L, 100L)
    airbrushActive = true
    renderHandler?.postDelayed(airbrushRunnable, airbrushIntervalMs)
}

/** Disarm the airbrush timer; safe to call repeatedly. */
internal fun PaintViewModel.stopAirbrush() {
    airbrushActive = false
    renderHandler?.removeCallbacks(airbrushRunnable)
}

internal fun PaintViewModel.armStrokeStartKick() {
    renderHandler?.postDelayed(strokeStartKickRunnable, STROKE_START_KICK_MS)
}

internal fun PaintViewModel.disarmStrokeStartKick() {
    renderHandler?.removeCallbacks(strokeStartKickRunnable)
}

internal fun PaintViewModel.scheduleRender(immediate: Boolean = false) {
    val h = renderHandler ?: return
    if (strokeThrottleRenderScheduled) {
        h.removeCallbacks(strokeThrottleRenderRunnable)
        strokeThrottleRenderScheduled = false
    }
    if (immediate) {
        // touchEnd / undo / structural changes: render right away and
        // drop any pending throttled render (its 16ms-later run would
        // otherwise re-render the exact same state a second time)
        pendingRenderRunnable?.let { h.removeCallbacks(it) }
        pendingRenderRunnable = null
        renderScheduled = false
        renderFollowUpPending = true
        h.post { doRender() }
        return
    }
    // Post immediately. The old fixed +16ms delay existed because renders
    // fired per touch event; now they only fire after the engine actually
    // painted ink (strokeBatchRunnable gates on touchStrokeMoveBatch's
    // result) or once per user op via runCore, so at most ONE render is
    // ever pending (renderScheduled dedupe) and doRender's input-first
    // defer still keeps queued stroke ops ahead of rendering. The delay
    // was two full frames of constant pen latency on 120Hz panels.
    if (renderScheduled) return
    renderScheduled = true
    val r =
        Runnable {
            pendingRenderRunnable = null
            renderScheduled = false
            doRender()
        }
    pendingRenderRunnable = r
    val delay = if (renderScheduleMode == com.reverie.paint.model.RenderScheduleMode.POWER_SAVER && !realInkHeld()) 16L else 0L
    if (delay > 0L) {
        h.postDelayed(r, delay)
    } else {
        h.post(r)
    }
}

internal fun PaintViewModel.onLiquifyBitmapDrawn(bitmap: Bitmap) {
    if (!liquifyPresentation.isPending(liquifyPresentationGesture)) return
    if (liquifyPresentation.consume(liquifyPresentationGesture, bitmap)) LiquifyGpuPreview.clear()
}

internal fun PaintViewModel.doRender() {
    renderInFlight = true
    try {
        doRenderBody()
    } finally {
        renderInFlight = false
    }
}

internal fun PaintViewModel.doRenderBody() {
    renderScheduled = false
    renderFollowUpPending = false
    renderDeferCount = 0
    val rh = renderHandler
    val w = renderW
    val h = renderH
    if (w <= 0 || h <= 0) return
    // 文档与显示缓冲 1:1 时走 512 显示瓦片网格: 一帧只 lockPixels 脏区命中的那几张瓦片,
    // 其余瓦片的位图引用原封不动, HWUI 直接复用显存纹理。其余情况维持整图双缓冲。
    if (shouldRenderDisplayTiles(w, h)) {
        doRenderTiles(w, h)
        return
    }
    clearDisplayTileGrid()

    val sizeMismatch =
        backBuffer == null || backBuffer?.width != w || backBuffer?.height != h ||
            frontBuffer == null || frontBuffer?.width != w || frontBuffer?.height != h
    val wasInvalid = displayBufferInvalid
    val forceFull = sizeMismatch || wasInvalid
    if (sizeMismatch) {
        try {
            frontBuffer = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            backBuffer = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        } catch (oom: OutOfMemoryError) {
            android.util.Log.e("PaintViewModel", "OOM allocating front/back buffers ($w x $h)", oom)
            releaseReclaimableCaches(aggressive = true)
            System.gc()
            try {
                frontBuffer = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                backBuffer = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            } catch (retryOom: OutOfMemoryError) {
                android.util.Log.e("PaintViewModel", "OOM retry failed, aborting frame", retryOom)
                return
            }
        }
    }
    if (forceFull) {
        displayBufferInvalid = false
        frontMissingRect.setEmpty()
        backMissingRect.setEmpty()
        synchronized(largeBitmapDirtyRect) { largeBitmapDirtyRect.setEmpty() }
        hasWrittenRect = false
        // 全量重绘或缓冲重分配后旧脏区已无意义, 清掉免得 UI 侧拿它做局部失效
        renderDirtySnapshot = null
    }

    val front = frontBuffer
    val back = backBuffer ?: return

    // Synchronize all cumulative regions missing from backBuffer using frontBuffer
    if (!forceFull && !backMissingRect.isEmpty && front != null) {
        syncCanvas.setBitmap(back)
        syncCanvas.drawBitmap(front, backMissingRect, backMissingRect, syncPaint)
        backMissingRect.setEmpty()
    }

    // Phase 2B: 先取 AGSL 预览数据再渲染 —— 放在 renderToBuffer 之前, 免得
    // "无脏区 ⇒ 直接 return" 的分支把这帧的预览更新吞掉(GPU 模式下缓冲本来就不变)。
    pollLiquifyGpuPreview()
    // 干预实验(§4.16): 每渲染采样一次液化 apply 统计 —— 提高采样频率, 才能抓到
    // 拖动中每次 rebase/物化的单次耗时尖峰(原先只在 1s 定时器里取, 只能拿到"最后一次")。
    pollLiquifyStats()

    // C++ recompositeProjection() (newDocument / loadRevp / loadPng / 图层与滤镜操作)
    // 以及 undo()/redo() 会把 m_nativeProjectionValid 置为 true, 而 touchStrokeStart/Move/End
    // 直接向图层 paintDevice 落墨却不清除 m_nativeProjectionValid。回放或 Seek 静默重放期间
    // (render = false) 笔画闭合后 m_drawing == false, 若 m_nativeProjectionValid 仍为 true,
    // renderToBuffer(forceFull = true) 会判定 mustComposite == false 而跳过 compositeDirtyRegions,
    // 直接把空白 projection 拷入显示缓冲并清空 m_dirtyRect, 导致画布大面积空白/仅局部笔画脏区显现。
    // 在全量帧进入 renderToBuffer 前先调一次 beginRenderTiles 将 m_nativeProjectionValid 复位为 false,
    // 紧接着 renderToBuffer(forceFull = true) 的 bufReset 会执行 setDirtyFullFrame(0, 0, iw, ih),
    // 从而 100% 触发全画布 compositeDirtyRegions(proj)。
    if (forceFull) {
        ReverieCoreBridge.beginRenderTiles(forceFullDummyDirtyRects)
    }

    // renderToBuffer 是每帧最重的一步 (合成 + 像素转换)。标尺开启时按渲染路径分桶
    // 计时 (全量/增量/跳过), 关闭时整段只剩一次布尔判断 —— 分桶是为了回答
    // "到底走的是哪条路径、值不值得动它", 单看总耗时看不出来。
    val traceOn = PerfTrace.enabled
    val tEngineNs =
        if (traceOn) android.os.SystemClock.elapsedRealtimeNanos() else 0L
    val ok = ReverieCoreBridge.renderToBuffer(back, forceFull, renderDirty)
    if (traceOn) {
        PerfTrace.renderPath(
            when {
                !ok -> PerfTrace.PATH_SKIP
                forceFull -> PerfTrace.PATH_FULL
                else -> PerfTrace.PATH_INCR
            },
            android.os.SystemClock.elapsedRealtimeNanos() - tEngineNs,
            if (ok) renderDirty[2].toLong() * renderDirty[3].toLong() else 0L,
        )
        if (w != coreW || h != coreH) PerfTrace.renderScaled()
    }
    PerfTrace.tick("render.calls", 1000L)
    if (!ok) {
        if (forceFull) {
            displayBufferInvalid = true
        }
        if (ReverieCoreBridge.renderPendingDirty()) {
            renderFollowUpPending = true
            rh?.postDelayed({ doRender() }, 4L)
        } else if (liquifyPresentation.isPending(liquifyPresentationGesture)) {
            displayBitmap?.let { liquifyPresentation.publish(liquifyPresentationGesture, it) }
            com.reverie.paint.ui.painting.canvas.CanvasTouchView.activeTouchView?.invalidateFromRender()
        }
        if (referenceWindowOpen && referenceActiveTab == 1 && displayBitmap != null) {
            mainHandler.post {
                referenceCanvasRevision++
            }
        }
        return
    }

    if (renderDirty[2] > 0 && renderDirty[3] > 0) {
        val writtenLeft = renderDirty[0]
        val writtenTop = renderDirty[1]
        val writtenRight = renderDirty[0] + renderDirty[2]
        val writtenBottom = renderDirty[1] + renderDirty[3]
        if (frontMissingRect.isEmpty) {
            frontMissingRect.set(writtenLeft, writtenTop, writtenRight, writtenBottom)
        } else {
            frontMissingRect.union(writtenLeft, writtenTop, writtenRight, writtenBottom)
        }
        synchronized(largeBitmapDirtyRect) {
            if (largeBitmapDirtyRect.isEmpty) {
                largeBitmapDirtyRect.set(writtenLeft, writtenTop, writtenRight, writtenBottom)
            } else {
                largeBitmapDirtyRect.union(writtenLeft, writtenTop, writtenRight, writtenBottom)
            }
        }
        hasWrittenRect = true
        publishRenderDirtySnapshot()
    } else {
        hasWrittenRect = false
        renderDirtySnapshot = null
    }

    // Swap front and back buffers, and swap their missing tracking
    val rendered = back
    backBuffer = front
    frontBuffer = rendered
    displayBitmap = rendered
    val tempL = backMissingRect.left
    val tempT = backMissingRect.top
    val tempR = backMissingRect.right
    val tempB = backMissingRect.bottom
    backMissingRect.set(frontMissingRect)
    frontMissingRect.set(tempL, tempT, tempR, tempB)
    liquifyPresentation.publish(liquifyPresentationGesture, rendered)

    // 纹理重传代理量: 每翻转一次, 下一帧 HWUI 都要把整张 Bitmap 纹理重传一遍
    // (HWUI 不做局部纹理更新), 这是本项目最大的带宽开销
    if (traceOn) PerfTrace.renderFlip(w.toLong() * h * 4, w.toLong() * h)

    if (!DeviceInfo.isHuaweiOrHonor && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
        try {
            rendered.prepareToDraw()
        } catch (_: Throwable) {}
    }

    if (referenceWindowOpen && referenceActiveTab == 1) {
        mainHandler.post {
            referenceCanvasRevision++
        }
    }

    // Direct hardware invalidate from render thread (zero Handler hop, zero frame delay).
    // 走 invalidateFromRender: 视图侧能证明画面其他部分不受影响时只失效脏区,
    // 否则 (光标/预测笔迹/旋转/大脏区等) 自动回退为整屏重绘。
    // Compose 观察者只在回放页或参考窗口打开时通知。整图帧与瓦片帧共用这一出口。
    // 整图路径不呈现真墨, 但要回报"这一帧没留在前缓冲上", 抬笔后的追赶清除才不会悬着。
    tileRevision++
    realInkSink?.finishFrame(false)
    realInkSink?.onTileFrameEnd(tileRevision)
    notifyCanvasFramePublished()
}

/** 瓦片帧: 把本帧写入的文档矩形 `[l, t, r, b)` 发布给 UI 线程做局部失效。 */
internal fun PaintViewModel.publishRenderWrittenRect(l: Int, t: Int, r: Int, b: Int) {
    renderDirty[0] = l
    renderDirty[1] = t
    renderDirty[2] = r - l
    renderDirty[3] = b - t
    hasWrittenRect = true
    publishRenderDirtySnapshot()
}

/** 瓦片帧: 本帧没有写入任何像素。 */
internal fun PaintViewModel.clearRenderWrittenRect() {
    hasWrittenRect = false
    renderDirtySnapshot = null
}

/** 瓦片网格发布后整图 front/back 缓冲用不上了: 丢掉引用 (不 recycle, HWUI 可能还持有)。 */
internal fun PaintViewModel.releaseFullCanvasBuffers() {
    val showing = displayBitmap
    if (showing != null && (showing === frontBuffer || showing === backBuffer)) displayBitmap = null
    frontBuffer = null
    backBuffer = null
    frontMissingRect.setEmpty()
    backMissingRect.setEmpty()
    synchronized(largeBitmapDirtyRect) { largeBitmapDirtyRect.setEmpty() }
}

/** 让画布从渲染线程失效重绘 (视图未挂上时投递到主线程)。 */
internal fun PaintViewModel.invalidateCanvasFromRender() {
    val tv = com.reverie.paint.ui.painting.canvas.CanvasTouchView.activeTouchView
    if (tv != null) {
        tv.invalidateFromRender()
    } else {
        mainHandler.post(postedCanvasInvalidate)
    }
}

/** 笔画批次仍在排队或引擎任务未完成 (运笔中)。 */
internal fun PaintViewModel.isStrokeRenderActive(): Boolean = strokeBatchQueued || (pendingCoreOps.get() > 0)

/**
 * 新一帧像素已发布: 失效画布, 并通知参考窗口/回放页这类 Compose 观察者。与 doRender 尾部同语义。
 * [keptOnFrontBuffer] = 这一帧已由前缓冲真墨接住, 主画布不失效 (省掉一次 HWUI 纹理上传)。
 */
internal fun PaintViewModel.notifyCanvasFramePublished(keptOnFrontBuffer: Boolean = false) {
    if (!keptOnFrontBuffer) {
        invalidateCanvasFromRender()
        if (!isStrokeRenderActive()) {
            mainHandler.post(postedCanvasInvalidate)
        }
    }
    if (currentPage == Page.REPLAY) {
        mainHandler.post {
            displayRevision++
        }
    }
}

/** 把本帧写入区域发布给 UI 线程做局部失效 (双缓冲交替, 零分配) */
internal fun PaintViewModel.publishRenderDirtySnapshot() {
    val target = if (dirtySnapshotFlip) dirtySnapshotB else dirtySnapshotA
    dirtySnapshotFlip = !dirtySnapshotFlip
    target[0] = renderDirty[0]
    target[1] = renderDirty[1]
    target[2] = renderDirty[2]
    target[3] = renderDirty[3]
    renderDirtySnapshot = target
}

/** Mirror all native layer state into [layers] / [currentLayerIndex].
 * Must run on the main thread after any C++ layer mutation. */
/**
 * 把引擎里的图层状态镜像到 [layers] / [currentLayerIndex]。
 * 必须在每次 C++ 图层变更后的主线程上调用。
 *
 * P1b: 只从**引擎生成的只读快照**取数 (一次 JNI 调用)。旧的逐项 getter 路径
 * 与对应的 JNI 已退役; 快照取不到时保持上一次的镜像并限流告警, 不会清空面板。
 */
internal fun PaintViewModel.syncLayersFromNative() {
    val page = LayerSnapshotProbe.read()
    if (page != null) {
        if (page.layers.isEmpty() && currentPage == Page.PAINTING && layers.isNotEmpty()) {
            android.util.Log.w("ReverieCore", "syncLayersFromNative: empty snapshot while on PAINTING (kept ${layers.size} layers)")
            Breadcrumbs.record("Layer", "empty native snapshot on PAINTING, kept ${layers.size} layers")
        } else {
            layers = page.layers.map(LayerSnapshotProbe::toUiState)
        }
    } else {
        LayerSnapshotProbe.reportUnavailable()
    }

    val n = layers.size
    syncStoryboardTrackFromNative()
    currentLayerIndex = ReverieCoreBridge.currentLayerIndex()
    if (selectedLayerIndices.isNotEmpty()) {
        selectedLayerIndices = selectedLayerIndices.filter { it in 1 until n }.toSet()
    }

    if (activeTextLayers.isNotEmpty()) {
        val existingIds = layers.map { it.id }.toSet()
        val pruned = activeTextLayers.filterKeys { it in existingIds }
        if (pruned.size != activeTextLayers.size) {
            activeTextLayers = pruned
            if (editingTextLayerId != null && editingTextLayerId !in existingIds) {
                editingTextLayerId = null
                editingTextItemId = null
                isTypographyEditing = false
                typographySnapGuides = emptyList()
            }
        }
    }

    // 时间轴"选中轨道"与当前图层强制对齐 (修"创建帧错乱"):
    // selectedTrack 旧实现只在时间轴 tap 时写入且**永不重置** —— 用户在
    // 时间轴点过任意一行后, 去图层面板切了别的图层再点"新建帧"/落笔
    // 自动建帧, 帧会建到上次点过的那条轨道上 (selectedTrackIndex()
    // 优先读它)。UI 上选中轨道与当前图层本来就是同时设置 (轨道区 tap
    // 两处都写), 这里把图层面板改层 / undo 删层 / 切换文档等路径也
    // 收敛到同一状态, 帧的落轨依据从此只有 currentLayerIndex。
    if (anim.enabled && anim.selectedTrack != currentLayerIndex) {
        anim.selectedTrack = currentLayerIndex
    }
}
