/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.canvas

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.core.PublishedTiles
import com.reverie.paint.core.RealInkSink
import com.reverie.paint.model.CanvasPose
import com.reverie.paint.model.RealInkPresent
import com.reverie.paint.perf.FbGeomLog
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 前缓冲真墨呈现 (续 #81)。
 *
 * #81 的前缓冲只画一段"假线"(路径/戳印预览), 真正的笔迹仍要等主画布整帧上屏。这里改为:
 * 运笔按住期间, 渲染线程每写完一帧显示瓦片, 就把本帧的离散脏区按画布 pose 烤成屏幕分辨率的
 * 槽位图, 交给 [FrontBufferPreviewOverlay] 用 SRC 逐块贴到前缓冲; 主画布这期间不再逐帧失效
 * (省掉 HWUI 纹理上传)。抬笔后主画布追平, 视图画完同一帧之后再清掉前缓冲。
 *
 * 线程分工:
 *  - UI 线程: [arm] / [release] / 光标与预测笔尖快照 / [clearIfViewCaughtUp];
 *  - 渲染线程: [RealInkSink] 的全部方法, 槽位图的烘焙;
 *  - GL 线程 (前缓冲渲染器): 只读快照 ([predictedTip]), 帧完成回调把积压重新投递到渲染线程。
 *
 * 只在瓦片路径上工作 (墨源是显示瓦片); 整图路径、对称辅助、设备不保留前缓冲内容时不按住,
 * 走主画布正常刷新 (前缓冲仍可画 #81 的假线)。
 */
internal class RealInkController(private val view: CanvasTouchView) : RealInkSink {
    /** 运笔按住且允许把真墨留在前缓冲。UI 线程落笔时置位, 抬笔/取消时 (任一线程) 清除。 */
    @Volatile
    var held: Boolean = false
        private set

    /** 位图→屏幕变换快照, UI 线程在视图变换变化时发布。 */
    @Volatile
    var pose: CanvasPose? = null

    /** 与主画布的放大插值开关同步。 */
    @Volatile
    var interpolate: Boolean = false

    // 按住期间光标不走视图失效, 由前缓冲随墨一起画。UI 线程写, 渲染线程打包时读。
    @Volatile var cursorX: Float = Float.NaN

    @Volatile var cursorY: Float = Float.NaN

    @Volatile var cursorR: Float = 0f

    @Volatile var cursorStyle: Int = 0

    // 真墨之上的单个预测笔尖圆点 (只锚一个点, 不进引擎)。UI 线程写, GL 线程读。
    @Volatile var tipX: Float = Float.NaN

    @Volatile var tipY: Float = Float.NaN

    @Volatile var tipWidth: Float = 0f

    @Volatile var tipColor: Int = 0

    /** 这一笔会不会画光标/预测笔尖: 会的话覆盖层从首帧起维护备份层, 擦旧光标时不留洞。 */
    @Volatile
    var strokeWantsOverlayExtras: Boolean = false
        private set

    /** 落笔时抓的 UI 避让区快照 (覆盖层置顶, 墨不能盖到 UI 上)。 */
    @Volatile
    var uiClips: Array<RectF> = emptyArray()
        private set

    // ---- 追赶清除: 抬笔后主画布画完同一帧才清前缓冲 ----
    private val epochLock = Any()
    private var epoch = 0
    private var clearEpoch = -1
    private var needsCatchUp = false

    // ---- 待贴离散脏区 (位图坐标 l, t, r, b) ----
    // 渲染线程追加/消费, UI 线程落笔时清空, 故加锁。贴不上就留到下一帧, 脏区不丢。
    private val pendingLock = Any()
    private val pendingRects = IntArray(RealInkPresent.MAX_DISCRETE_RECTS * 4)
    private var pendingCount = 0

    // 以下只在渲染线程使用 (热路径零分配)。
    private val snapshotRects = IntArray(RealInkPresent.MAX_DISCRETE_RECTS * 4)
    private val reclaimedRects = IntArray(RealInkPresent.MAX_DISCRETE_RECTS * 4)
    private val screenRects = IntArray(RealInkPresent.MAX_DISCRETE_RECTS * 4)
    private val bmpRects = IntArray(RealInkPresent.MAX_DISCRETE_RECTS * 4)
    private val screenBounds = IntArray(4)
    private val canvasBounds = IntArray(4)
    private val bakeSrc = IntArray(4)
    private val bakeSrcRect = Rect()
    private val bakeDstRect = RectF()
    private var tileHits = IntArray(0)
    private val bakeCanvas = Canvas()
    private val bakePaint = Paint()
    private val tileDrawer = ClampedTileDrawer()

    // 屏幕槽位图四缓冲轮换: 至多两张在 GPU 光栅化、一张排队、一张供渲染线程烘焙。
    // 尺寸按本帧脏区的屏幕包围盒紧凑分配 (最小 512, 按 256 取整), 不做整屏位图。
    private class InkSlot {
        var bitmap: Bitmap? = null
        val busy = AtomicBoolean(false)
    }

    private val slots = Array(SLOT_COUNT) { InkSlot() }

    // GL 帧完成回调最多排一个渲染线程任务。
    private val repostPending = AtomicBoolean(false)
    private val repostRunnable = Runnable {
        repostPending.set(false)
        val tiles = view.vm?.displayTiles?.published ?: return@Runnable
        presentPending(tiles)
    }
    private val frameDoneCallback: () -> Unit = {
        if (repostPending.compareAndSet(false, true)) {
            val h = view.vm?.renderHandler
            if (h == null) repostPending.set(false) else h.post(repostRunnable)
        }
    }
    private var strokeSeq = 0L

    // ================= UI 线程 =================

    /**
     * 落笔时决定这一笔要不要按住前缓冲。[allowed] 由调用方综合开关/对称/瓦片路径/设备能力给出。
     * 返回最终是否按住。
     */
    fun arm(allowed: Boolean, wantsExtras: Boolean, clips: Array<RectF>): Boolean {
        val overlay = view.frontBufferOverlay
        val hold = allowed && overlay != null && overlay.canRenderPreview
        FbGeomLog.strokeBegin(++strokeSeq, hold, view.width, view.height, pose)
        synchronized(pendingLock) { pendingCount = 0 }
        if (!hold) {
            held = false
            cursorR = 0f
            tipWidth = 0f
            return false
        }
        uiClips = clips
        strokeWantsOverlayExtras = wantsExtras
        tipWidth = 0f
        overlay!!.onInkFrameDone = frameDoneCallback
        overlay.beginStroke()
        held = true
        return true
    }

    /** 松开前缓冲 (抬笔、取消、让位给变换等)。任一线程可调。 */
    fun release() {
        held = false
        cursorR = 0f
        tipWidth = 0f
    }

    /**
     * 视图画完一帧之后调用 (UI 线程, drawCanvas 末尾): 这一帧已经包含追赶的像素, 等它真正
     * 提交之后再清前缓冲。纪元对不上说明渲染线程又贴了一帧, 本次作废。
     */
    fun clearIfViewCaughtUp() {
        if (held) return
        val e = synchronized(epochLock) {
            if (!needsCatchUp || clearEpoch != epoch) {
                -1
            } else {
                needsCatchUp = false
                clearEpoch = -1
                epoch
            }
        }
        if (e < 0) return
        // 前缓冲是单缓冲: 在 onDraw 录制期间立刻清, 覆盖层 <1ms 就透明了, 主画布这一帧却要等
        // 下一拍 VSYNC 才上屏, 中间漏出旧画布 (抬笔闪一下)。所以推迟到这一帧提交之后。
        val clearAction = Runnable {
            val current = synchronized(epochLock) { epoch }
            if (!held && current == e) view.frontBufferOverlay?.clearInk()
        }
        val vto = view.viewTreeObserver
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && vto.isAlive) {
            vto.registerFrameCommitCallback(clearAction)
        } else {
            view.postOnAnimation(clearAction)
        }
    }

    // ================= GL 线程 =================

    /** 预测笔尖: 返回 true 时 [out] = `x, y, 直径`, `colorOut[0]` = 颜色。零分配。 */
    fun predictedTip(out: FloatArray, colorOut: IntArray): Boolean {
        if (!held || view.isTransformActive || view.overlayPanelsOpen) return false
        val w = tipWidth
        val x = tipX
        val y = tipY
        if (w <= 0f || !x.isFinite() || !y.isFinite()) return false
        out[0] = x
        out[1] = y
        out[2] = w
        colorOut[0] = tipColor
        return true
    }

    // ================= 渲染线程 (RealInkSink) =================

    override fun wantsHold(): Boolean {
        if (!held || view.isTransformActive || view.overlayPanelsOpen) return false
        val overlay = view.frontBufferOverlay ?: return false
        if (!overlay.canRenderPreview) return false
        val p = pose ?: return false
        return p.bmpW > 0 && p.bmpH > 0
    }

    override fun presentTileFrame(
        tiles: PublishedTiles,
        rects: IntArray,
        count: Int,
        writtenLeft: Int,
        writtenTop: Int,
        writtenRight: Int,
        writtenBottom: Int,
        anyFull: Boolean,
    ): Boolean {
        if (!wantsHold()) {
            synchronized(pendingLock) { pendingCount = 0 }
            return false
        }
        if (anyFull) {
            // 网格重建/整块重写: 前缓冲上的墨与新瓦片不再同源, 这一笔剩下的部分交给主画布。
            release()
            synchronized(pendingLock) { pendingCount = 0 }
            return false
        }
        synchronized(pendingLock) {
            for (d in 0 until count) {
                val b = d * 4
                val l = maxOf(rects[b], writtenLeft)
                val t = maxOf(rects[b + 1], writtenTop)
                val r = minOf(rects[b] + rects[b + 2], writtenRight)
                val bt = minOf(rects[b + 1] + rects[b + 3], writtenBottom)
                pendingCount = RealInkPresent.appendDiscrete(pendingRects, pendingCount, l, t, r, bt)
            }
        }
        val presented = presentPending(tiles)
        val stillPending = synchronized(pendingLock) { pendingCount > 0 }
        return RealInkPresent.keepAfterPresent(wantsHold(), presented, stillPending)
    }

    override fun finishFrame(kept: Boolean) {
        synchronized(epochLock) {
            if (kept) {
                needsCatchUp = false
            } else if (view.frontBufferOverlay?.hasInk == true) {
                needsCatchUp = true
                clearEpoch = epoch
            }
        }
    }

    override fun onStrokeCommitted() {
        release()
        // 收尾之后未必还有新像素的帧 (没有新脏区就不会走到 finishFrame), 而按住期间主画布一直
        // 没失效: 这里主动登记一次追赶并失效画布, 视图画完这一帧再清前缓冲。
        if (view.frontBufferOverlay?.hasInk == true) {
            finishFrame(false)
            view.postInvalidate()
        }
    }

    /**
     * 把积压的每一块脏区各自烤进一张屏幕槽位图, 再交给覆盖层逐块 SRC 贴上前缓冲。
     * 全部成功才清空积压; 任何一步失败都保留积压并返回 false (调用方据此让主画布兜底)。
     */
    private fun presentPending(tiles: PublishedTiles): Boolean {
        if (!wantsHold()) return false
        val overlay = view.frontBufferOverlay ?: return false
        if (!overlay.isIdleForInk()) return false
        val p = pose ?: return false
        val grid = tiles.grid
        if (grid.width != p.bmpW || grid.height != p.bmpH) return false
        val viewW = view.width
        val viewH = view.height
        if (viewW <= 0 || viewH <= 0) return false
        if (synchronized(pendingLock) { pendingCount } <= 0) return false
        // 上一帧的包若还在排队 (GL 尚未取走), 把它的脏区逐块赎回并回积压, 一起重烤:
        // 否则它随后被最新一帧顶掉, 那一截墨既没上屏也不会再烤, 按住期间就是一段断口。
        val reclaimed = overlay.reclaimQueuedInkRegions(reclaimedRects)
        val snapCount = synchronized(pendingLock) {
            for (i in 0 until reclaimed) {
                val o = i * 4
                pendingCount =
                    RealInkPresent.appendDiscrete(
                        pendingRects,
                        pendingCount,
                        reclaimedRects[o],
                        reclaimedRects[o + 1],
                        reclaimedRects[o + 2],
                        reclaimedRects[o + 3],
                    )
            }
            System.arraycopy(pendingRects, 0, snapshotRects, 0, pendingCount * 4)
            pendingCount
        }
        if (snapCount <= 0) return false
        val srcW = p.bmpW
        val srcH = p.bmpH
        // 画布 pose 四边形的屏幕包围盒: 贴图矩形一律夹在它之内 (出圈处槽位里没有文档像素)。
        if (!RealInkPresent.bmpRectToScreenBounds(p, 0f, 0f, srcW.toFloat(), srcH.toFloat(), viewW, viewH, 0,
                canvasBounds)
        ) {
            synchronized(pendingLock) { pendingCount = 0 }
            return false
        }
        var baked = 0
        var clipped = 0
        for (i in 0 until snapCount) {
            val o = i * 4
            val bl = snapshotRects[o].coerceIn(0, srcW)
            val bt = snapshotRects[o + 1].coerceIn(0, srcH)
            val br = snapshotRects[o + 2].coerceIn(0, srcW)
            val bb = snapshotRects[o + 3].coerceIn(0, srcH)
            if (br <= bl || bb <= bt) continue
            if (!RealInkPresent.bmpRectToScreenBounds(
                    p, bl.toFloat(), bt.toFloat(), br.toFloat(), bb.toFloat(), viewW, viewH,
                    RealInkPresent.PAD_PX, screenBounds,
                )
            ) {
                continue
            }
            val cl = maxOf(screenBounds[0], canvasBounds[0])
            val ct = maxOf(screenBounds[1], canvasBounds[1])
            val cr = minOf(screenBounds[2], canvasBounds[2])
            val cb = minOf(screenBounds[3], canvasBounds[3])
            if (cr <= cl || cb <= ct) continue
            if (cl != screenBounds[0] || ct != screenBounds[1] || cr != screenBounds[2] || cb != screenBounds[3]) {
                clipped++
            }
            val d = baked * 4
            bmpRects[d] = bl
            bmpRects[d + 1] = bt
            bmpRects[d + 2] = br
            bmpRects[d + 3] = bb
            screenRects[d] = cl
            screenRects[d + 1] = ct
            screenRects[d + 2] = cr
            screenRects[d + 3] = cb
            baked++
        }
        if (baked <= 0) {
            // 整帧没有一块落在视口里: 没什么可贴, 丢掉积压免得越滚越大。
            synchronized(pendingLock) { pendingCount = 0 }
            return false
        }
        var minL = Int.MAX_VALUE
        var minT = Int.MAX_VALUE
        var maxR = Int.MIN_VALUE
        var maxB = Int.MIN_VALUE
        for (i in 0 until baked) {
            val d = i * 4
            if (screenRects[d] < minL) minL = screenRects[d]
            if (screenRects[d + 1] < minT) minT = screenRects[d + 1]
            if (screenRects[d + 2] > maxR) maxR = screenRects[d + 2]
            if (screenRects[d + 3] > maxB) maxB = screenRects[d + 3]
        }
        val reqW = (maxR - minL).coerceIn(1, viewW)
        val reqH = (maxB - minT).coerceIn(1, viewH)
        val slot = claimSlot() ?: run {
            // 槽位都还在被 GL 读: 顶掉还没开始画的排队包, 它的槽位随之归还。
            if (!overlay.discardQueuedInk()) return false
            claimSlot() ?: return false
        }
        val slotBitmap =
            try {
                ensureSlotBitmap(slot, reqW, reqH, viewW, viewH)
            } catch (_: OutOfMemoryError) {
                slot.busy.set(false)
                return false
            }
        bakeCanvas.setBitmap(slotBitmap)
        var allBaked = true
        for (i in 0 until baked) {
            val d = i * 4
            // 槽位图跨帧复用: 先把本块要贴的屏幕范围清成透明, 再按 pose 烤, 不把陈旧像素贴上屏。
            clearSlotRect(screenRects[d] - minL, screenRects[d + 1] - minT, screenRects[d + 2] - minL,
                screenRects[d + 3] - minT)
            if (!bakeRect(tiles, p, bmpRects[d], bmpRects[d + 1], bmpRects[d + 2], bmpRects[d + 3], minL, minT)) {
                allBaked = false
                break
            }
        }
        bakeCanvas.setBitmap(null)
        FbGeomLog.presentFrame(snapCount, baked, minL, minT, reqW, reqH, slotBitmap.width, slotBitmap.height,
            screenRects, bmpRects, canvasBounds, clipped)
        if (!allBaked) {
            // 部分贴会让已清空的脏区永久缺失 (按住期间主画布不兜底): 整帧放弃, 下一帧重来。
            slot.busy.set(false)
            return false
        }
        synchronized(epochLock) { epoch += 1 }
        val shown =
            overlay.renderInkScreenRegions(
                slotBitmap, screenRects, bmpRects, baked, p,
                cursorX, cursorY, cursorR, cursorStyle, strokeWantsOverlayExtras,
                uiClips, slot.busy, minL, minT, canvasBounds,
            )
        if (!shown) {
            slot.busy.set(false)
            return false
        }
        synchronized(pendingLock) { pendingCount = 0 }
        return true
    }

    /**
     * 把位图矩形 `[bl, bt, br, bb)` 按屏幕分辨率烤进槽位图 (原点对齐屏幕 `originX, originY`)。
     * 与 onDraw 同一套变换; 只画脏区外扩后的源子矩形相交的瓦片。不铺棋盘格: 没有文档像素的
     * 地方保持透明, SRC 之后由下层主画布透出。
     */
    private fun bakeRect(
        tiles: PublishedTiles,
        p: CanvasPose,
        bl: Int,
        bt: Int,
        br: Int,
        bb: Int,
        originX: Int,
        originY: Int,
    ): Boolean {
        if (!RealInkPresent.bakeSrcRect(bl, bt, br, bb, p.scale, p.bmpW, p.bmpH, bakeSrc)) return false
        bakeSrcRect.set(bakeSrc[0], bakeSrc[1], bakeSrc[2], bakeSrc[3])
        bakeDstRect.set(bakeSrc[0] - p.halfW, bakeSrc[1] - p.halfH, bakeSrc[2] - p.halfW, bakeSrc[3] - p.halfH)
        val c = bakeCanvas
        c.save()
        c.translate(p.centerX - originX, p.centerY - originY)
        c.rotate(p.rotation)
        c.scale(p.scale, p.scale)
        c.scale(p.signX, p.signY)
        c.clipRect(bakeDstRect)
        c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        val filter = interpolate
        bakePaint.isFilterBitmap = filter
        val grid = tiles.grid
        if (tileHits.size < grid.tileCount * 2) tileHits = IntArray(grid.tileCount * 2)
        val n = grid.tilesIntersecting(bakeSrc[0], bakeSrc[1], bakeSrc[2], bakeSrc[3], tileHits)
        var drew = false
        for (k in 0 until n) {
            val tx = tileHits[k * 2]
            val ty = tileHits[k * 2 + 1]
            val idx = grid.tileIndex(tx, ty)
            val bmp = tiles.tiles.get(idx) ?: continue
            if (bmp.isRecycled) continue
            tileDrawer.draw(c, idx, grid.tileCount, bmp, grid.tileLeft(tx) - p.halfW, grid.tileTop(ty) - p.halfH,
                filter)
            drew = true
        }
        c.restore()
        return drew
    }

    private fun claimSlot(): InkSlot? {
        for (s in slots) {
            if (s.busy.compareAndSet(false, true)) return s
        }
        return null
    }

    private fun clearSlotRect(l: Int, t: Int, r: Int, b: Int) {
        if (r <= l || b <= t) return
        bakeCanvas.save()
        bakeCanvas.clipRect(l.toFloat(), t.toFloat(), r.toFloat(), b.toFloat())
        bakeCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        bakeCanvas.restore()
    }

    private fun ensureSlotBitmap(slot: InkSlot, reqW: Int, reqH: Int, maxW: Int, maxH: Int): Bitmap {
        val existing = slot.bitmap
        if (existing != null && !existing.isRecycled &&
            existing.width >= reqW && existing.height >= reqH &&
            existing.width <= maxW && existing.height <= maxH &&
            (existing.width <= SLOT_SHRINK_PX || reqW > SLOT_MIN_PX) &&
            (existing.height <= SLOT_SHRINK_PX || reqH > SLOT_MIN_PX)
        ) {
            return existing
        }
        val w = ((reqW + 255) and -256).coerceAtLeast(SLOT_MIN_PX).coerceAtMost(maxW)
        val h = ((reqH + 255) and -256).coerceAtLeast(SLOT_MIN_PX).coerceAtMost(maxH)
        // 旧图不 recycle: 它可能刚被 GL 读完, 交给 GC。
        val created = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        slot.bitmap = created
        return created
    }

    private companion object {
        const val SLOT_COUNT = 4
        const val SLOT_MIN_PX = 512

        // 大笔画过之后小笔画会把超过这个尺寸的槽位图换回小图, 不长期占着大块内存。
        const val SLOT_SHRINK_PX = 768
    }
}
