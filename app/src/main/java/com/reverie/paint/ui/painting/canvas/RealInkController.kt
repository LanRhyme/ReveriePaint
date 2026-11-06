/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.canvas

import com.reverie.paint.core.FrontBufferFeatureFlags
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.SystemClock
import com.reverie.paint.core.LandDiag
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.core.PerfTrace
import com.reverie.paint.core.PublishedTiles
import com.reverie.paint.core.RealInkSink
import com.reverie.paint.core.ReverieCoreBridge
import com.reverie.paint.model.CanvasPose
import com.reverie.paint.model.FrontBufferCompose
import com.reverie.paint.model.FrontBufferHolePolicy
import com.reverie.paint.model.RealInkHoldPolicy
import com.reverie.paint.model.RealInkPresent
import com.reverie.paint.model.SettledTilePolicy
import com.reverie.paint.model.UiClipSnapshot
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

    // 对称镜像分支光标 (UI 线程写, 渲染线程打包时读)
    @Volatile var symCursorCount: Int = 0
    val symCursorX: FloatArray = FloatArray(16)
    val symCursorY: FloatArray = FloatArray(16)

    // 真墨之上的单个预测笔尖圆点 (只锚一个点, 不进引擎)。UI 线程写, GL 线程读。
    @Volatile var tipX: Float = Float.NaN

    @Volatile var tipY: Float = Float.NaN

    @Volatile var tipWidth: Float = 0f

    @Volatile var tipColor: Int = 0

    /** 这一笔会不会画光标/预测笔尖: 会的话覆盖层从首帧起维护备份层, 擦旧光标时不留洞。 */
    @Volatile
    var strokeWantsOverlayExtras: Boolean = false
        private set

    /** L-01: 动态吸附导引线快照 (UI 线程每次 MOVE 写, GL 线程读)。 */
    val guideSnapshot = DynamicGuideSnapshot()

    /** 渲染线程读 [guideSnapshot] 的暂存 (零分配)。 */
    private val guideReadSeg = FloatArray(DynamicGuidePainter.SEG_SIZE)

    /**
     * H-1: 这一笔的孔洞掩码 (落笔时由视图按 [FrontBufferHolePolicy.decideArm] 给出, 笔画期间不变)。
     * null = 当前没有覆盖层, 与无孔洞路径逐字节相同。
     */
    @Volatile
    var strokeHoles: HoleMask? = null
        private set

    @Volatile
    var holeMode: Int = FrontBufferHolePolicy.MODE_OFF
        private set

    // 渲染线程: 本帧命中孔洞的屏幕并集 (与面板孔径交集合并成一次窗口失效)。
    private val invalidateBounds = IntArray(4)

    /** 落笔判定前由视图调用 (UI 线程)。 */
    fun setStrokeHoles(mask: HoleMask?, mode: Int) {
        strokeHoles = mask
        holeMode = mode
    }

    /**
     * compose 笔画 ([FrontBufferHolePolicy.MODE_COMPOSE]): 覆盖层画进前缓冲。落笔时由视图设定, 整笔不变,
     * 抬笔后保留到下一次落笔 (窗口据此决定导引线怎么画, 见 [FrontBufferHolePolicy.windowGuideHolesOnly])。
     */
    @Volatile
    var composeStroke: Boolean = false
        private set

    /** compose 笔画的静态覆盖层 (落笔时录好, 不可变)。 */
    @Volatile
    var composeDecor: FrontBufferDecor? = null
        private set

    /** 当前已上屏真墨的屏幕外接矩形（打包为 Long 存储，零分配发布，UI 侧按需解包）。arm 时置空。 */
    @Volatile
    private var publishedInkBoundsPacked: Long = 0L

    val publishedInkBounds: ClipRect?
        get() = unpackInkBounds(publishedInkBoundsPacked)

    /** 渲染线程累计真墨包围盒（本地 Int，零分配）。 */
    private var inkBoundL = Int.MAX_VALUE
    private var inkBoundT = Int.MAX_VALUE
    private var inkBoundR = Int.MIN_VALUE
    private var inkBoundB = Int.MIN_VALUE

    /** 重烤包标记（UI 线程设置，渲染线程消费并重置）。 */
    @Volatile
    private var rebakePending: Boolean = false

    /** updateClipsAndRebake 专用 scratch 数组（UI 线程复用，零分配，不与渲染线程竞态）。 */
    private val rebakeBmpScratch = IntArray(4)

    fun setStrokeCompose(on: Boolean, decor: FrontBufferDecor?) {
        composeDecor = if (on) decor else null
        composeStroke = on
    }

    /** compose: 前缓冲上 (或某一包要画成) 的光标 + 导引线状态。只在渲染线程使用。 */
    private class OverlayState {
        var cursorOn = false
        var x = 0f
        var y = 0f
        var r = 0f
        var style = 0
        var symCount = 0
        val symX = FloatArray(16)
        val symY = FloatArray(16)
        var guideKind = DynamicGuidePainter.KIND_NONE
        val seg = FloatArray(DynamicGuidePainter.SEG_SIZE)

        fun clear() {
            cursorOn = false
            symCount = 0
            guideKind = DynamicGuidePainter.KIND_NONE
        }

        fun copyFrom(o: OverlayState) {
            cursorOn = o.cursorOn
            x = o.x
            y = o.y
            r = o.r
            style = o.style
            symCount = o.symCount
            System.arraycopy(o.symX, 0, symX, 0, o.symCount)
            System.arraycopy(o.symY, 0, symY, 0, o.symCount)
            guideKind = o.guideKind
            System.arraycopy(o.seg, 0, seg, 0, seg.size)
        }

        fun sameCursor(o: OverlayState): Boolean {
            if (cursorOn != o.cursorOn) return false
            if (!cursorOn) return true
            if (x != o.x || y != o.y || r != o.r || style != o.style || symCount != o.symCount) return false
            for (i in 0 until symCount) if (symX[i] != o.symX[i] || symY[i] != o.symY[i]) return false
            return true
        }
    }

    // compose 账本 (渲染线程): shown = 最近一次成功提交的包画成的状态, base = 那一包之前的状态。
    // 那一包若还在排队就被赎回 (没上屏), shown 退回 base; 否则它已上屏或必将上屏, base 前进到 shown。
    private val shownState = OverlayState()
    private val baseState = OverlayState()
    private val nextState = OverlayState()
    private var shownGen = Int.MIN_VALUE
    private val packXY = IntArray(FrontBufferCompose.MAX_PACKET_RECTS * 2)
    private val packWH = IntArray(2)
    private val docInner = IntArray(4)
    private val chunkScratch = FloatArray(4)
    private val bmpScratch = IntArray(4)
    private val density = view.resources.displayMetrics.density
    private val footCursor = HoldCursorPainter(density)
    private val footGuide = DynamicGuidePainter(density)

    /** 落笔时抓的 UI 避让区快照 (不可变; 覆盖层置顶, 墨不能盖到 UI 上)。 */
    @Volatile
    var uiClips: UiClipSnapshot = UiClipSnapshot.EMPTY
        private set

    // ---- 抬笔追赶: 单调递增修订号门控 ----
    @Volatile var catchUpArmed = false
    @Volatile var catchUpTargetRev = Long.MAX_VALUE

    val catchUpPending: Boolean get() = catchUpArmed
    override val holding: Boolean get() = held

    // ---- 待贴离散脏区 (位图坐标 l, t, r, b) ----
    // 渲染线程追加/消费, UI 线程落笔时清空, 故加锁。贴不上就留到下一帧, 脏区不丢。
    private val pendingLock = Any()
    private val pendingRects = IntArray(RealInkPresent.MAX_DISCRETE_RECTS * 4)
    private var pendingCount = 0

    // 以下只在渲染线程使用 (热路径零分配)。
    private val snapshotRects = IntArray(RealInkPresent.MAX_DISCRETE_RECTS * 4)
    private val reclaimedRects = IntArray(RealInkPresent.MAX_DISCRETE_RECTS * 4)
    private val screenRects = IntArray(FrontBufferCompose.MAX_PACKET_RECTS * 4)
    private val bmpRects = IntArray(FrontBufferCompose.MAX_PACKET_RECTS * 4)
    private val screenBounds = IntArray(4)
    private val canvasBounds = IntArray(4)
    /** 本帧脏区与面板孔径交集的屏幕包围盒（渲染线程复用，零分配）。 */
    private val clipOverlapBounds = IntArray(4)
    private val bakeSrc = IntArray(4)
    private val bakeSrcRect = Rect()
    private val bakeDstRect = RectF()
    private var tileHits = IntArray(0)
    private val bakeCanvas = Canvas()
    private val bakePaint = Paint()
    private val tileDrawer = ClampedTileDrawer()

    // ---- 渐进刷新已落定瓦片 (P5 / D3) ----
    private var tileLastWriteFrame = IntArray(0)
    private var tileRefreshedAtFrame = IntArray(0)
    private var strokeFrameNo = 0
    private var renderStrokeSeq = -1L
    private var lastMidStrokeRefreshMs = 0L
    private var settledHits = IntArray(0)
    private var settledIndices = IntArray(0)

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
    @Volatile
    private var strokeSeq = 0L

    /** 最近一次 [arm] 分配的笔画序号 (UI 线程读, 日志对齐用)。 */
    val currentStrokeSeq: Long get() = strokeSeq

    fun armCatchUp() {
        if (view.frontBufferOverlay?.hasInk == true) {
            catchUpArmed = true
            catchUpTargetRev = Long.MAX_VALUE
            view.vm?.scheduleRender()
        }
    }

    fun cancelCatchUp() {
        catchUpArmed = false
        catchUpTargetRev = Long.MAX_VALUE
    }

    override fun onTileFrameEnd(rev: Long) {
        if (catchUpArmed && !held && !ReverieCoreBridge.renderPendingDirty()) {
            catchUpTargetRev = rev
            view.vm?.invalidateCanvasFromRender()
        }
    }

    override fun onDocumentDiscarded() {
        release()
        cancelCatchUp()
        view.frontBufferOverlay?.clearInk()
    }

    // ================= UI 线程 =================

    /**
     * 落笔时决定这一笔要不要按住前缓冲。[allowed] 由调用方综合开关/对称/瓦片路径/设备能力给出。
     * 返回最终是否按住。
     */
    fun arm(allowed: Boolean, wantsExtras: Boolean, clips: UiClipSnapshot): Boolean {
        val overlay = view.frontBufferOverlay
        // 缓冲几何门控: 表面 (androidx 据它分配前缓冲 HardwareBuffer) 未建好或与画布 View 不重合时
        // 这一笔不按住 —— 包会被静默丢弃/贴错位置, 见 FrontBufferSurfaceGate。
        val hold = allowed && overlay != null && overlay.canRenderPreview && overlay.bufferGeometryMatches(view)
        val seq = synchronized(pendingLock) {
            pendingCount = 0
            ++strokeSeq
        }
        FbGeomLog.strokeBegin(seq, hold, view.width, view.height, pose)
        guideSnapshot.clear()
        inkBoundL = Int.MAX_VALUE
        inkBoundT = Int.MAX_VALUE
        inkBoundR = Int.MIN_VALUE
        inkBoundB = Int.MIN_VALUE
        publishedInkBoundsPacked = 0L
        rebakePending = false
        if (hold) {
            LandDiag.endHold()
            LandDiag.beginHold()
        } else {
            LandDiag.endHold()
        }
        if (!hold) {
            held = false
            strokeHoles = null
            synchronized(pendingLock) {
                uiClips = UiClipSnapshot.EMPTY
            }
            cursorR = 0f
            tipWidth = 0f
            symCursorCount = 0
            armCatchUp()
            return false
        }
        synchronized(pendingLock) {
            uiClips = clips
        }
        strokeWantsOverlayExtras = wantsExtras
        tipWidth = 0f
        symCursorCount = 0
        overlay!!.onInkFrameDone = frameDoneCallback
        overlay.beginStroke()
        cancelCatchUp()
        held = true
        return true
    }

    /** 笔画中途 UI 槽位变化时更新裁剪快照 (UI 线程; 与 pendingLock 互斥同步)。 */
    fun updateActiveUiClips(clips: UiClipSnapshot) {
        if (held) {
            synchronized(pendingLock) {
                uiClips = clips
            }
        }
    }

    /** 笔画中途 UI 槽位变化且与已画墨相交时, 更新裁剪快照并在原层把相交差集重新烘焙 (UI 线程). */
    fun updateClipsAndRebake(snapshot: UiClipSnapshot, deltaRects: List<ClipRect>) {
        if (!held) return
        val bounds = publishedInkBounds ?: return
        val p = pose ?: return
        var anyIntersect = false
        synchronized(pendingLock) {
            uiClips = snapshot
            for (d in deltaRects) {
                val inter = d.intersect(bounds) ?: continue
                if (inter.isEmpty) continue
                val l = kotlin.math.floor(inter.left).toInt()
                val t = kotlin.math.floor(inter.top).toInt()
                val r = kotlin.math.ceil(inter.right).toInt()
                val b = kotlin.math.ceil(inter.bottom).toInt()
                if (FrontBufferCompose.screenToBmpRect(p, l, t, r, b, rebakeBmpScratch)) {
                    pendingCount = RealInkPresent.appendDiscrete(
                        pendingRects,
                        pendingCount,
                        rebakeBmpScratch[0],
                        rebakeBmpScratch[1],
                        rebakeBmpScratch[2],
                        rebakeBmpScratch[3],
                    )
                    anyIntersect = true
                }
            }
            if (anyIntersect) {
                rebakePending = true
            }
        }
        if (anyIntersect) {
            for (d in deltaRects) {
                val inter = d.intersect(bounds) ?: continue
                if (!inter.isEmpty) {
                    view.invalidate(
                        kotlin.math.floor(inter.left).toInt(),
                        kotlin.math.floor(inter.top).toInt(),
                        kotlin.math.ceil(inter.right).toInt(),
                        kotlin.math.ceil(inter.bottom).toInt(),
                    )
                }
            }
            view.vm?.scheduleRender()
        }
    }

    /** 松开前缓冲 (抬笔、取消、让位给变换等)。任一线程可调。 */
    fun release() {
        held = false
        LandDiag.endHold()
        synchronized(pendingLock) {
            uiClips = UiClipSnapshot.EMPTY
        }
        cursorR = 0f
        tipWidth = 0f
        symCursorCount = 0
    }

    /** 前缓冲上的导引线已擦掉 (GL/任一线程): 窗口补画一帧完整导引线, 见 [FrontBufferHolePolicy.windowGuideHolesOnly]。 */
    fun onFrontGuideCleared() {
        view.postInvalidateOnAnimation()
    }

    /** 中途释放并登记一次追赶: 主画布下一帧画完后清前缓冲, 避免停顿期间旧墨留在面板上。 */
    fun releaseAndCatchUp() {
        release()
        if (view.frontBufferOverlay?.hasInk == true) {
            armCatchUp()
        }
    }



    /**
     * 视图画完一帧之后调用 (UI 线程, drawCanvas 末尾): 这一帧已经包含追赶的像素, 等它真正
     * 提交之后再清前缓冲。drawnRev 达到或超过目标版本说明窗口已追平。
     */
    fun clearIfViewCaughtUp(drawnRev: Long) {
        if (!RealInkHoldPolicy.shouldClearAfterDraw(held, catchUpArmed, drawnRev, catchUpTargetRev)) return
        val seq = strokeSeq
        cancelCatchUp()
        // 前缓冲是单缓冲: 在 onDraw 录制期间立刻清, 覆盖层 <1ms 就透明了, 主画布这一帧却要等
        // 下一拍 VSYNC 才上屏, 中间漏出旧画布 (抬笔闪一下)。所以推迟到这一帧提交之后。
        val extra = FrontBufferFeatureFlags.clearDelayFrames
        val clearAction = object : Runnable {
            var commits = 0

            override fun run() {
                if (held || strokeSeq != seq) return
                commits++
                // H-4: 窗口帧提交之后再多等 [extra] 个 VSYNC (默认 0 = 原行为)。postOnAnimation 不依赖
                // 视图再画一帧, 空闲时也会按时触发。
                if (FrontBufferHolePolicy.clearAfterCommit(commits, extra)) {
                    view.frontBufferOverlay?.clearInk()
                } else {
                    view.postOnAnimation(this)
                }
            }
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
        // H-3 严格同一性: 预测圆点是窗口里不存在的像素, 默认不画 (debug.reverie.fb_strict=0 恢复)。
        if (FrontBufferFeatureFlags.strict) return false
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

    fun wantsHoldRejectReason(): Int {
        if (!held) return REJECT_NOT_HELD
        if (view.isTransformActive) return REJECT_TRANSFORM
        if (view.overlayPanelsOpen) return REJECT_PANELS
        if (view.filterSessionActive) return REJECT_FILTER
        val vm = view.vm
        if (vm != null && vm.anim.shiftTraceActive) return REJECT_SHIFT_TRACE
        if (vm != null && vm.drawingGuide.mode == com.reverie.paint.model.GuideMode.SYMMETRY && vm.drawingGuide.assistedDrawing) {
            if (vm.mirrorGate.state == com.reverie.paint.core.MirrorLiveGate.State.FALLBACK) return REJECT_MIRROR_FALLBACK
        }
        val overlay = view.frontBufferOverlay ?: return REJECT_NO_OVERLAY
        if (!overlay.canRenderPreview) return REJECT_CANNOT_PREVIEW
        if (!overlay.bufferStillValid) return REJECT_BUFFER_INVALID
        val p = pose ?: return REJECT_POSE_INVALID
        if (p.bmpW <= 0 || p.bmpH <= 0) return REJECT_POSE_INVALID
        return REJECT_NONE
    }

    override fun wantsHold(): Boolean = wantsHoldRejectReason() == REJECT_NONE

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
        val rejectCode = wantsHoldRejectReason()
        if (rejectCode != REJECT_NONE) {
            if (RealInkHoldPolicy.shouldReleaseOnReject(held, wantsHold = false)) {
                android.util.Log.i(
                    "RealInkController",
                    "ev=hold_reject reason=${rejectReasonToString(rejectCode)} seq=$currentStrokeSeq held=$held",
                )
                releaseAndCatchUp()
            }
            synchronized(pendingLock) { pendingCount = 0 }
            return false
        }
        if (anyFull) {
            // 网格重建/整块重写: 前缓冲上的墨与新瓦片不再同源, 这一笔剩下的部分交给主画布。
            releaseAndCatchUp()
            synchronized(pendingLock) { pendingCount = 0 }
            return false
        }
        // 放大画布 + 大笔刷时, 每帧脏区在**屏幕**上的覆盖面积随 zoom² 增长: 烤一次要几十到
        // 一两百毫秒, 帧率掉到个位数 (用户感受: 明显卡顿)。超过阈值就别占这个便宜 —— 前缓冲
        // 让开, 主画布按瓦片贴 (那一路只要几毫秒), 顺滑优先于最后一个像素的延迟。
        if (count > 0) {
            var l = Int.MAX_VALUE
            var t = Int.MAX_VALUE
            var r = Int.MIN_VALUE
            var b = Int.MIN_VALUE
            for (i in 0 until count) {
                val o = i * 4
                val rl = maxOf(rects[o], writtenLeft)
                val rt = maxOf(rects[o + 1], writtenTop)
                val rr = minOf(rects[o] + rects[o + 2], writtenRight)
                val rb = minOf(rects[o + 1] + rects[o + 3], writtenBottom)
                if (rr <= rl || rb <= rt) continue
                if (rl < l) l = rl
                if (rt < t) t = rt
                if (rr > r) r = rr
                if (rb > b) b = rb
            }
            if (r > l && b > t) {
                val area = (r - l).toLong() * (b - t)
                if (area > FrontBufferFeatureFlags.maxInkAreaPx) {
                    PerfTrace.tick("inkSkipBigArea")
                    releaseAndCatchUp()
                    synchronized(pendingLock) { pendingCount = 0 }
                    return false
                }
            }
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
        if (presented) {
            updateSettledTilesAndMaybeRefresh(
                tiles,
                rects,
                count,
                writtenLeft,
                writtenTop,
                writtenRight,
                writtenBottom,
            )
        }
        val stillPending = synchronized(pendingLock) { pendingCount > 0 }
        return RealInkPresent.keepAfterPresent(wantsHold(), presented, stillPending)
    }

    private fun updateSettledTilesAndMaybeRefresh(
        tiles: PublishedTiles,
        rects: IntArray,
        count: Int,
        writtenLeft: Int,
        writtenTop: Int,
        writtenRight: Int,
        writtenBottom: Int,
    ) {
        val grid = tiles.grid
        val tileCount = grid.tileCount
        if (tileCount <= 0) return
        if (tileLastWriteFrame.size != tileCount) {
            tileLastWriteFrame = IntArray(tileCount)
            tileRefreshedAtFrame = IntArray(tileCount)
        }
        if (renderStrokeSeq != strokeSeq) {
            renderStrokeSeq = strokeSeq
            strokeFrameNo = 0
            tileLastWriteFrame.fill(0)
            tileRefreshedAtFrame.fill(0)
            lastMidStrokeRefreshMs = 0L
        }
        strokeFrameNo++

        if (settledHits.size < tileCount * 2) {
            settledHits = IntArray(tileCount * 2)
        }
        for (d in 0 until count) {
            val b = d * 4
            val l = maxOf(rects[b], writtenLeft)
            val t = maxOf(rects[b + 1], writtenTop)
            val r = minOf(rects[b] + rects[b + 2], writtenRight)
            val bt = minOf(rects[b + 1] + rects[b + 3], writtenBottom)
            if (r > l && bt > t) {
                val n = grid.tilesIntersecting(l, t, r, bt, settledHits)
                for (k in 0 until n) {
                    val tx = settledHits[k * 2]
                    val ty = settledHits[k * 2 + 1]
                    val idx = grid.tileIndex(tx, ty)
                    if (idx in 0 until tileCount) {
                        tileLastWriteFrame[idx] = strokeFrameNo
                    }
                }
            }
        }

        val midRefreshMs = FrontBufferFeatureFlags.midStrokeRefreshMs
        if (midRefreshMs > 0L) {
            val nowMs = SystemClock.uptimeMillis()
            if (nowMs - lastMidStrokeRefreshMs >= midRefreshMs) {
                val tipCenterX = (writtenLeft + writtenRight) / 2
                val tipCenterY = (writtenTop + writtenBottom) / 2
                val tipCol = if (grid.tileSize > 0) (tipCenterX / grid.tileSize).coerceIn(0, grid.cols - 1) else 0
                val tipRow = if (grid.tileSize > 0) (tipCenterY / grid.tileSize).coerceIn(0, grid.rows - 1) else 0
                if (settledIndices.size < tileCount) {
                    settledIndices = IntArray(tileCount)
                }
                val settledCount = SettledTilePolicy.findSettledTiles(
                    tileLastWriteFrame,
                    tileRefreshedAtFrame,
                    strokeFrameNo,
                    tipCol,
                    tipRow,
                    grid.cols,
                    settledIndices,
                )
                if (settledCount > 0) {
                    for (i in 0 until settledCount) {
                        val idx = settledIndices[i]
                        tileRefreshedAtFrame[idx] = strokeFrameNo
                    }
                    lastMidStrokeRefreshMs = nowMs
                    view.vm?.invalidateCanvasFromRender()
                }
            }
        }
    }

    override fun finishFrame(kept: Boolean) {
        if (!kept && view.frontBufferOverlay?.hasInk == true) {
            catchUpArmed = true
            catchUpTargetRev = Long.MAX_VALUE
        }
    }

    override fun onStrokeCommitted(strokeSeq: Long) {
        if (!shouldCommitStroke(this.strokeSeq, strokeSeq)) return
        // compose: 抬笔收尾包 —— 积压里剩下的墨 + 擦掉前缓冲上的光标/导引线 (静态覆盖层照画), 与关低延迟
        // 时抬笔那一帧同图; 之后窗口追平、清前缓冲照旧。尽力而为: 贴不上 (GL 忙) 时由追赶清除在 1~2 帧内兜底。
        if (held && composeStroke) {
            val tiles = view.vm?.displayTiles?.published
            if (tiles != null) presentPending(tiles, final = true)
        }
        FbGeomLog.holeStrokeEnd(this.strokeSeq)
        release()
        // B3: 删 shrinkSlotsWhenIdle()
        // 收尾之后主动登记一次追赶
        armCatchUp()
    }

    /**
     * 把积压的每一块脏区各自烤进一张屏幕槽位图, 再交给覆盖层逐块 SRC 贴上前缓冲。
     * 全部成功才清空积压; 任何一步失败都保留积压并返回 false (调用方据此让主画布兜底)。
     */
    private fun presentPending(tiles: PublishedTiles, final: Boolean = false): Boolean {
        val rejectCode = wantsHoldRejectReason()
        if (rejectCode != REJECT_NONE) {
            if (RealInkHoldPolicy.shouldReleaseOnReject(held, wantsHold = false)) {
                android.util.Log.i(
                    "RealInkController",
                    "ev=present_pending_reject reason=${rejectReasonToString(rejectCode)} seq=$currentStrokeSeq held=$held",
                )
                releaseAndCatchUp()
            }
            return false
        }
        val overlay = view.frontBufferOverlay ?: return false
        if (!overlay.isIdleForInk()) return false
        val p = pose ?: return false
        val grid = tiles.grid
        if (grid.width != p.bmpW || grid.height != p.bmpH) return false
        val viewW = view.width
        val viewH = view.height
        if (viewW <= 0 || viewH <= 0) return false
        val compose = composeStroke
        val finalCompose = final && compose
        if (!finalCompose && synchronized(pendingLock) { pendingCount } <= 0) return false
        // 上一帧的包若还在排队 (GL 尚未取走), 把它的脏区逐块赎回并回积压, 一起重烤:
        // 否则它随后被最新一帧顶掉, 那一截墨既没上屏也不会再烤, 按住期间就是一段断口。
        val reclaimed = overlay.reclaimQueuedInkRegions(reclaimedRects)
        var seq = 0L
        var isRebake = false
        var clipsSnapshot = UiClipSnapshot.EMPTY
        val snapCount = synchronized(pendingLock) {
            clipsSnapshot = uiClips
            seq = strokeSeq
            isRebake = rebakePending
            rebakePending = false
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
            val n = pendingCount
            System.arraycopy(pendingRects, 0, snapshotRects, 0, n * 4)
            // 锁内取快照即消费: 烘焙期间 UI/渲染线程新追加的矩形留在 pendingRects,
            // 不会被本帧无条件清零吞掉。失败时再用 returnPending 把本次快照还回去。
            pendingCount = 0
            n
        }
        if (seq != strokeSeq) {
            if (isRebake) rebakePending = true
            return false
        }
        if (snapCount <= 0 && !finalCompose) {
            if (isRebake) rebakePending = true
            return false
        }
        val srcW = p.bmpW
        val srcH = p.bmpH
        // 画布 pose 四边形的屏幕包围盒: 贴图矩形一律夹在它之内 (出圈处槽位里没有文档像素)。
        val docOnScreen = RealInkPresent.bmpRectToScreenBounds(p, 0f, 0f, srcW.toFloat(), srcH.toFloat(), viewW, viewH,
            0, canvasBounds)
        if (!docOnScreen && !finalCompose) {
            // 整张画布都不在视口: 本次快照没有可贴区域, 直接丢弃。
            if (isRebake) rebakePending = true
            return false
        }
        var baked = 0
        var clipped = 0
        for (i in 0 until (if (docOnScreen) snapCount else 0)) {
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
        if (baked <= 0 && !finalCompose) {
            // 整帧没有一块落在视口里: 没什么可贴, 丢弃本次快照免得越滚越大。
            if (isRebake) rebakePending = true
            return false
        }
        if (compose) {
            return presentCompose(tiles, p, overlay, clipsSnapshot, viewW, viewH, snapCount, seq, baked, reclaimed,
                finalCompose, clipped, isRebake)
        }
        // H-1/H-2: 逐块判定是否命中孔洞 (积分图 O(1) 排除, 只扫边界块; 零分配)。没有掩码时整段跳过。
        val holes = strokeHoles
        var holeHitBits = 0
        if (holes != null) {
            val t0 = if (FbGeomLog.timingEnabled) System.nanoTime() else 0L
            val g = holes.grid
            var hitArea = 0L
            for (i in 0 until baked) {
                val d = i * 4
                if (g.anyExact(screenRects[d], screenRects[d + 1], screenRects[d + 2], screenRects[d + 3])) {
                    holeHitBits = holeHitBits or (1 shl i)
                    hitArea += (screenRects[d + 2] - screenRects[d]).toLong() * (screenRects[d + 3] - screenRects[d + 1])
                }
            }
            val decision = FrontBufferHolePolicy.decidePacket(holeMode, holeHitBits != 0)
            FbGeomLog.holePacket(seq, baked, Integer.bitCount(holeHitBits), hitArea,
                if (t0 != 0L) System.nanoTime() - t0 else 0L, decision)
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
            if (!overlay.discardQueuedInk()) {
                if (isRebake) rebakePending = true
                returnPending(snapCount, seq)
                return false
            }
            claimSlot() ?: run {
                if (isRebake) rebakePending = true
                returnPending(snapCount, seq)
                return false
            }
        }
        val slotBitmap =
            try {
                ensureSlotBitmap(slot, reqW, reqH, viewW, viewH)
            } catch (_: OutOfMemoryError) {
                slot.busy.set(false)
                if (isRebake) rebakePending = true
                returnPending(snapCount, seq)
                return false
            }
        bakeCanvas.setBitmap(slotBitmap)
        var allBaked = true
        for (i in 0 until baked) {
            val d = i * 4
            // 槽位图跨帧复用: 本块要贴的屏幕范围必须先清成透明再按 pose 烤, 不把陈旧像素贴上屏;
            // 轴对齐且烘焙矩形完整盖住时由 bakeRect 内的裁剪清屏一次完成 (省掉重复的整块 CLEAR)。
            if (!bakeRect(tiles, p, bmpRects[d], bmpRects[d + 1], bmpRects[d + 2], bmpRects[d + 3], minL, minT,
                    screenRects[d], screenRects[d + 1], screenRects[d + 2], screenRects[d + 3])
            ) {
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
            if (isRebake) rebakePending = true
            returnPending(snapCount, seq)
            return false
        }
        if (seq != strokeSeq) {
            slot.busy.set(false)
            return false
        }
        if (!held) {
            slot.busy.set(false)
            return false
        }
        val shown =
            overlay.renderInkScreenRegions(
                slotBitmap, screenRects, bmpRects, baked, p,
                cursorX, cursorY, cursorR, cursorStyle, strokeWantsOverlayExtras,
                clipsSnapshot, slot.busy, minL, minT, canvasBounds,
                symCursorX, symCursorY, symCursorCount,
                holes, holeHitBits,
                if (FrontBufferHolePolicy.frontDrawsGuide(FrontBufferFeatureFlags.snapHold)) {
                    guideSnapshot.read(guideReadSeg)
                } else {
                    DynamicGuidePainter.KIND_NONE
                },
                guideReadSeg,
                rebake = isRebake,
            )
        if (!shown) {
            slot.busy.set(false)
            if (isRebake) rebakePending = true
            returnPending(snapCount, seq)
            return false
        }
        if (baked > 0 || isRebake) {
            val pad = kotlin.math.ceil(cursorR.coerceAtLeast(0.75f)).toInt()
            val packL = minL - pad
            val packT = minT - pad
            val packR = maxR + pad
            val packB = maxB + pad
            if (packL < inkBoundL) inkBoundL = packL
            if (packT < inkBoundT) inkBoundT = packT
            if (packR > inkBoundR) inkBoundR = packR
            if (packB > inkBoundB) inkBoundB = packB
            publishedInkBoundsPacked = packInkBounds(inkBoundL, inkBoundT, inkBoundR, inkBoundB)
        }
        // 孔径外的新墨已由前缓冲 4~8ms 直出；若本帧脏区侵入了面板/阴影孔径，
        // 孔径内前缓冲已被 clipOut 为全透明，必须唤醒主窗口 HWUI 在该交集区域
        // 重绘 CanvasTouchView，让真实圆角、软阴影与 50% 面板原生合成在新墨之上。
        // 未侵入时不触发任何主画布失效，PublishedTiles 保持 100% TILE_UNDRAWN。
        // H-min: 命中孔洞的块同理 —— 前缓冲先上屏 (上面已提交), 再让窗口在命中区重画 (孔洞内的
        // 墨与覆盖层由窗口合成)。两类区域并成一个矩形, 每帧至多一次 postInvalidateOnAnimation
        // (同一 VSYNC 内多次调用由 Choreographer 合并)。都未命中时不触发任何主画布失效。
        val inv = invalidateBounds
        inv[0] = Int.MAX_VALUE
        inv[1] = Int.MAX_VALUE
        inv[2] = Int.MIN_VALUE
        inv[3] = Int.MIN_VALUE
        var needInvalidate = false
        if (!clipsSnapshot.isEmpty && (baked > 0 || isRebake)) {
            clipOverlapBounds[0] = Int.MAX_VALUE
            clipOverlapBounds[1] = Int.MAX_VALUE
            clipOverlapBounds[2] = Int.MIN_VALUE
            clipOverlapBounds[3] = Int.MIN_VALUE
            var hasClipOverlap = false
            for (i in 0 until baked) {
                val d = i * 4
                if (clipsSnapshot.accumulateIntersection(
                        screenRects[d],
                        screenRects[d + 1],
                        screenRects[d + 2],
                        screenRects[d + 3],
                        clipOverlapBounds,
                    )
                ) {
                    hasClipOverlap = true
                }
            }
            if (hasClipOverlap) {
                RealInkPresent.unionInto(inv, clipOverlapBounds[0], clipOverlapBounds[1], clipOverlapBounds[2],
                    clipOverlapBounds[3])
                needInvalidate = true
            }
        }
        if (holeHitBits != 0) {
            for (i in 0 until baked) {
                if (holeHitBits and (1 shl i) == 0) continue
                val d = i * 4
                RealInkPresent.unionInto(inv, screenRects[d], screenRects[d + 1], screenRects[d + 2], screenRects[d + 3])
            }
            needInvalidate = true
            FbGeomLog.holeRedrawRequested()
        }
        if (needInvalidate) {
            view.postInvalidateOnAnimation(
                inv[0].coerceAtLeast(0),
                inv[1].coerceAtLeast(0),
                inv[2].coerceAtMost(viewW),
                inv[3].coerceAtMost(viewH),
            )
        }
        return true
    }

    /**
     * compose 包 ([FrontBufferHolePolicy.MODE_COMPOSE]): 墨脏区 (前 [baked] 块, 已在 screenRects/bmpRects)
     * 之外再加光标/导引线变化的新旧足迹; 全部按 pose 重烤成 "棋盘格 + 瓦片" (文档外透明) 紧凑打包进
     * 一张槽位图, 覆盖层在 GL 线程按窗口同一顺序重画 ([FrontBufferPreviewOverlay.renderComposeRegions])。
     * 没有备份层、不挖孔、不让窗口重画。[final] = 抬笔收尾包: 不画光标/导引线 (只擦)。
     */
    private fun presentCompose(
        tiles: PublishedTiles,
        p: CanvasPose,
        overlay: FrontBufferPreviewOverlay,
        clipsSnapshot: UiClipSnapshot,
        viewW: Int,
        viewH: Int,
        snapCount: Int,
        seq: Long,
        baked: Int,
        reclaimed: Int,
        final: Boolean,
        clipped: Int,
        rebake: Boolean = false,
    ): Boolean {
        val t0 = if (FbGeomLog.timingEnabled) System.nanoTime() else 0L
        val decor = composeDecor
        if (decor != null && decor.pose != p && !final) {
            // 落笔录的静态覆盖层与当前 pose 不一致 (笔画中视图变了): 宁可交还窗口也不画错位。
            releaseAndCatchUp()
            return false
        }
        val gen = overlay.frontGeneration
        if (gen != shownGen) {
            shownGen = gen
            shownState.clear()
            baseState.clear()
        }
        if (reclaimed >= 0) shownState.copyFrom(baseState) else baseState.copyFrom(shownState)
        val next = nextState
        next.clear()
        if (!final) {
            val r = cursorR
            val x = cursorX
            val y = cursorY
            val st = cursorStyle
            if (r > 0f && x.isFinite() && y.isFinite() && st != 4) {
                next.cursorOn = true
                next.x = x
                next.y = y
                next.r = r
                next.style = st
                val sc = symCursorCount.coerceIn(0, 16)
                System.arraycopy(symCursorX, 0, next.symX, 0, sc)
                System.arraycopy(symCursorY, 0, next.symY, 0, sc)
                next.symCount = sc
            }
            next.guideKind = guideSnapshot.read(next.seg)
        }
        val shown = shownState
        var n = baked
        val cap = FrontBufferCompose.MAX_PACKET_RECTS
        if (!next.sameCursor(shown)) {
            // 按 tip 并新旧足迹 (对称时 2 块而非 4), half+1 盖住 2.2px 环的 AA (half() 已含 +2)。
            val half = footCursor.half(maxOf(shown.r, next.r)) + 1f
            n = FrontBufferCompose.appendCursorTransitionBoxes(
                shown.x, shown.y, shown.cursorOn, shown.symX, shown.symY, shown.symCount,
                next.x, next.y, next.cursorOn, next.symX, next.symY, next.symCount,
                half, viewW, viewH, screenRects, n, cap,
            )
        }
        if (!FrontBufferCompose.sameGuide(next.guideKind, next.seg, shown.guideKind, shown.seg,
                DynamicGuidePainter.KIND_LINE, DynamicGuidePainter.KIND_LINE_VP)
        ) {
            n = appendGuideFootprint(shown, n, cap, viewW, viewH)
            n = appendGuideFootprint(next, n, cap, viewW, viewH)
        }
        // 足迹已在墨脏区内 → 丢; 与墨相交 → 并进墨块。对称下常把光标足迹吃进两笔镜像脏区。
        n = FrontBufferCompose.dropContainedAfter(screenRects, baked, n)
        n = FrontBufferCompose.absorbOverlappingAfter(screenRects, baked, n)
        if (n <= 0) {
            // 收尾包: 没有墨也没有要擦的光标/导引线。
            baseState.copyFrom(shownState)
            return true
        }
        val docOk = FrontBufferCompose.isAxisAligned(p) && FrontBufferCompose.docInnerRect(p, viewW, viewH, docInner)
        if (!docOk) {
            docInner[0] = 0; docInner[1] = 0; docInner[2] = 0; docInner[3] = 0
        }
        var bakePx = 0L
        var minL = Int.MAX_VALUE
        var minT = Int.MAX_VALUE
        var maxR = Int.MIN_VALUE
        var maxB = Int.MIN_VALUE
        var maxRectW = 0
        for (i in 0 until n) {
            val d = i * 4
            // 吸收可能撑大墨块: 全部按屏幕矩形反算源, 保证盖住。
            FrontBufferCompose.screenToBmpRect(p, screenRects[d], screenRects[d + 1], screenRects[d + 2],
                screenRects[d + 3], bmpScratch)
            System.arraycopy(bmpScratch, 0, bmpRects, d, 4)
            val w = screenRects[d + 2] - screenRects[d]
            if (w > maxRectW) maxRectW = w
            bakePx += w.toLong() * (screenRects[d + 3] - screenRects[d + 1])
            if (screenRects[d] < minL) minL = screenRects[d]
            if (screenRects[d + 1] < minT) minT = screenRects[d + 1]
            if (screenRects[d + 2] > maxR) maxR = screenRects[d + 2]
            if (screenRects[d + 3] > maxB) maxB = screenRects[d + 3]
        }
        // 槽位图怎么排: 屏幕包围盒原样 (少块/聚在一起时) 或货架紧凑打包 (光标与远处的墨/长导引线),
        // 取 HWUI 要上传的槽位图面积更小者。
        val bboxW = (maxR - minL).coerceIn(1, viewW)
        val bboxH = (maxB - minT).coerceIn(1, viewH)
        var reqW = bboxW
        var reqH = bboxH
        var shelves = false
        val shelfW = if (maxRectW <= SLOT_MIN_PX) SLOT_MIN_PX.coerceAtMost(viewW) else viewW
        if (n > 1 && FrontBufferCompose.packShelves(screenRects, n, shelfW, viewH, packXY, packWH) &&
            slotArea(packWH[0], packWH[1], viewW, viewH) < slotArea(bboxW, bboxH, viewW, viewH)
        ) {
            shelves = true
            reqW = packWH[0]
            reqH = packWH[1]
        }
        if (!shelves) {
            for (i in 0 until n) {
                packXY[i * 2] = screenRects[i * 4] - minL
                packXY[i * 2 + 1] = screenRects[i * 4 + 1] - minT
            }
        }
        val slot = claimSlot() ?: run {
            if (!overlay.discardQueuedInk()) {
                if (rebake) rebakePending = true
                returnPending(snapCount, seq)
                return false
            }
            claimSlot() ?: run {
                if (rebake) rebakePending = true
                returnPending(snapCount, seq)
                return false
            }
        }
        val slotBitmap =
            try {
                ensureSlotBitmap(slot, reqW, reqH, viewW, viewH)
            } catch (_: OutOfMemoryError) {
                slot.busy.set(false)
                if (rebake) rebakePending = true
                returnPending(snapCount, seq)
                return false
            }
        bakeCanvas.setBitmap(slotBitmap)
        // 吸收后墨块可能带上光标边距: 一律按足迹容忍 (无瓦片也算成功, 棋盘格/透明已铺)。
        // 原先 "墨块无瓦片 → 整包失败" 在吸收撑大后会误伤对称笔。
        var inkCovered = baked <= 0
        for (i in 0 until n) {
            val d = i * 4
            val px = packXY[i * 2]
            val py = packXY[i * 2 + 1]
            val ox = screenRects[d] - px
            val oy = screenRects[d + 1] - py
            if (bmpRects[d + 2] > bmpRects[d] && bmpRects[d + 3] > bmpRects[d + 1]) {
                val drew = bakeRect(tiles, p, bmpRects[d], bmpRects[d + 1], bmpRects[d + 2], bmpRects[d + 3], ox, oy,
                    screenRects[d], screenRects[d + 1], screenRects[d + 2], screenRects[d + 3], clipScreen = true)
                if (i < baked && drew) inkCovered = true
            } else {
                clearSlotRect(px, py, px + screenRects[d + 2] - screenRects[d],
                    py + screenRects[d + 3] - screenRects[d + 1])
            }
        }
        bakeCanvas.setBitmap(null)
        FbGeomLog.presentFrame(snapCount, baked, minL, minT, reqW, reqH, slotBitmap.width, slotBitmap.height,
            screenRects, bmpRects, canvasBounds, clipped)
        if (baked > 0 && !inkCovered) {
            // 墨脏区一块瓦片都没画上: 与旧逻辑一样整包放弃, 避免空洞。
            slot.busy.set(false)
            if (rebake) rebakePending = true
            returnPending(snapCount, seq)
            return false
        }
        if (seq != strokeSeq) {
            slot.busy.set(false)
            return false
        }
        if (!held) {
            slot.busy.set(false)
            return false
        }
        val ok = overlay.renderComposeRegions(
            slotBitmap, screenRects, bmpRects, packXY, baked, n, p,
            next.x, next.y, if (next.cursorOn) next.r else 0f, next.style,
            next.symX, next.symY, if (next.cursorOn) next.symCount else 0,
            next.guideKind, next.seg,
            clipsSnapshot, slot.busy, canvasBounds, docInner, decor?.takeIf { it.pose == p },
            rebake = rebake,
        )
        if (!ok) {
            slot.busy.set(false)
            if (rebake) rebakePending = true
            returnPending(snapCount, seq)
            return false
        }
        if (LandDiag.enabled && baked > 0) {
            LandDiag.noteCompose(
                final, baked, n, screenRects, bmpRects, packXY,
                slotBitmap.width, slotBitmap.height,
            )
            if (LandDiag.takeShot(final)) {
                saveLandSlot(slotBitmap, screenRects, packXY)
            }
        }
        baseState.copyFrom(shownState)
        shownState.copyFrom(next)
        if (baked > 0 || rebake) {
            val pad = kotlin.math.ceil(if (next.cursorOn) next.r.coerceAtLeast(0.75f) else 1f).toInt()
            val packL = minL - pad
            val packT = minT - pad
            val packR = maxR + pad
            val packB = maxB + pad
            if (packL < inkBoundL) inkBoundL = packL
            if (packT < inkBoundT) inkBoundT = packT
            if (packR > inkBoundR) inkBoundR = packR
            if (packB > inkBoundB) inkBoundB = packB
            publishedInkBoundsPacked = packInkBounds(inkBoundL, inkBoundT, inkBoundR, inkBoundB)
        }
        FbGeomLog.holePacket(seq, n, 0, 0L, 0L, FrontBufferHolePolicy.PACKET_PLAIN)
        if (t0 != 0L) {
            FbGeomLog.composePacket(seq, n - baked, bakePx, slotBitmap.width.toLong() * slotBitmap.height,
                System.nanoTime() - t0)
        }
        // 与 H-min 相同: 新墨侵入 UI 避让区 (前缓冲在那里 clipOut) 时让窗口在交集处重画。没侵入不失效。
        if (!clipsSnapshot.isEmpty && (baked > 0 || rebake)) {
            clipOverlapBounds[0] = Int.MAX_VALUE
            clipOverlapBounds[1] = Int.MAX_VALUE
            clipOverlapBounds[2] = Int.MIN_VALUE
            clipOverlapBounds[3] = Int.MIN_VALUE
            var hasClipOverlap = false
            for (i in 0 until baked) {
                val d = i * 4
                if (clipsSnapshot.accumulateIntersection(screenRects[d], screenRects[d + 1], screenRects[d + 2],
                        screenRects[d + 3], clipOverlapBounds)
                ) {
                    hasClipOverlap = true
                }
            }
            if (hasClipOverlap) {
                view.postInvalidateOnAnimation(
                    clipOverlapBounds[0].coerceAtLeast(0),
                    clipOverlapBounds[1].coerceAtLeast(0),
                    clipOverlapBounds[2].coerceAtMost(viewW),
                    clipOverlapBounds[3].coerceAtMost(viewH),
                )
            }
        }
        return true
    }

    private fun appendGuideFootprint(s: OverlayState, count: Int, cap: Int, viewW: Int, viewH: Int): Int {
        val k = s.guideKind
        if (k != DynamicGuidePainter.KIND_LINE && k != DynamicGuidePainter.KIND_LINE_VP) return count
        return FrontBufferCompose.appendGuideChunks(s.seg, 0, k == DynamicGuidePainter.KIND_LINE_VP,
            footGuide.linePad, footGuide.vpHalf, viewW, viewH, screenRects, count, cap, chunkScratch)
    }

    private fun slotArea(reqW: Int, reqH: Int, maxW: Int, maxH: Int): Long {
        val w = ((reqW + 255) and -256).coerceAtLeast(SLOT_MIN_PX).coerceAtMost(maxW)
        val h = ((reqH + 255) and -256).coerceAtLeast(SLOT_MIN_PX).coerceAtMost(maxH)
        return w.toLong() * h
    }

    /**
     * 本帧烘焙/提交失败: 把锁内消费掉的快照矩形逐块归还 pendingRects。
     * 必须在锁内 append, 与渲染线程的新增脏区合并; 已满 8 块时 appendDiscrete 会自动并块。
     * [seq] 是本帧快照对应的笔画代次; 若期间已换笔, 旧笔的矩形直接丢弃, 不污染新笔。
     */
    private fun returnPending(count: Int, seq: Long) {
        if (count <= 0) return
        synchronized(pendingLock) {
            if (seq != strokeSeq) return
            for (i in 0 until count) {
                val o = i * 4
                pendingCount =
                    RealInkPresent.appendDiscrete(
                        pendingRects,
                        pendingCount,
                        snapshotRects[o],
                        snapshotRects[o + 1],
                        snapshotRects[o + 2],
                        snapshotRects[o + 3],
                    )
            }
        }
    }

    /**
     * 把位图矩形 `[bl, bt, br, bb)` 按屏幕分辨率烤进槽位图 (原点对齐屏幕 `originX, originY`)。
     * 与 onDraw 同一套变换; 只画脏区外扩后的源子矩形相交的瓦片。文档矩形内先铺与主画布同源的
     * 棋盘格 (H-5), 文档外保持透明, SRC 之后由下层主画布透出。
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
        sl: Int,
        st: Int,
        sr: Int,
        sb: Int,
        clipScreen: Boolean = false,
    ): Boolean {
        if (!RealInkPresent.bakeSrcRect(bl, bt, br, bb, p.scale, p.bmpW, p.bmpH, bakeSrc)) {
            if (clipScreen) clearSlotRect(sl - originX, st - originY, sr - originX, sb - originY)
            return false
        }
        if (!RealInkPresent.bakeCoversScreenRect(p, bakeSrc[0].toFloat(), bakeSrc[1].toFloat(), bakeSrc[2].toFloat(),
                bakeSrc[3].toFloat(), sl, st, sr, sb)
        ) {
            clearSlotRect(sl - originX, st - originY, sr - originX, sb - originY)
        }
        bakeSrcRect.set(bakeSrc[0], bakeSrc[1], bakeSrc[2], bakeSrc[3])
        bakeDstRect.set(bakeSrc[0] - p.halfW, bakeSrc[1] - p.halfH, bakeSrc[2] - p.halfW, bakeSrc[3] - p.halfH)
        val c = bakeCanvas
        c.save()
        // compose 紧凑打包时相邻块在槽位图里紧挨着: 烘焙的源外扩 (滤波余量) 不能画进邻块。
        if (clipScreen) {
            c.clipRect((sl - originX).toFloat(), (st - originY).toFloat(), (sr - originX).toFloat(),
                (sb - originY).toFloat())
        }
        c.translate(p.centerX - originX, p.centerY - originY)
        c.rotate(p.rotation)
        c.scale(p.scale, p.scale)
        c.scale(p.signX, p.signY)
        c.clipRect(bakeDstRect)
        // H-5: 透明文档时先按主画布同源的棋盘格 (同一 Paint/着色器, 同一文档坐标变换) 以 SRC 铺底,
        // 再叠瓦片 —— 槽位里的文档像素与主窗口"棋盘格 + 瓦片"逐像素同构且不透明, 前缓冲 SRC
        // 贴上去不会透出主窗口里的旧墨。拿不到棋盘格时退回 CLEAR (原行为)。
        val checker = checkerBakePaint()
        if (checker != null) {
            c.drawRect(bakeDstRect, checker)
        } else {
            c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        }
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

    private var checkerSource: Paint? = null
    private var checkerBake: Paint? = null

    /** 主画布棋盘格 Paint 的 SRC 副本 (着色器同一实例; 源换了才重建, 渲染线程独占)。 */
    private fun checkerBakePaint(): Paint? {
        val src = view.checkerboardPaint ?: return null
        if (src !== checkerSource) {
            checkerSource = src
            checkerBake = Paint(src).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC) }
        }
        return checkerBake
    }

    private fun claimSlot(): InkSlot? {
        for (s in slots) {
            if (s.busy.compareAndSet(false, true)) return s
        }
        return null
    }

    private fun saveLandSlot(bmp: Bitmap, screenRects: IntArray, packXY: IntArray) {
        if (!LandDiag.enabled) return
        val w = (screenRects[2] - screenRects[0]).coerceAtLeast(1)
        val h = (screenRects[3] - screenRects[1]).coerceAtLeast(1)
        val x = packXY[0].coerceIn(0, (bmp.width - 1).coerceAtLeast(0))
        val y = packXY[1].coerceIn(0, (bmp.height - 1).coerceAtLeast(0))
        val cw = w.coerceAtMost(bmp.width - x)
        val ch = h.coerceAtMost(bmp.height - y)
        if (cw <= 0 || ch <= 0) return
        val dir = view.context.getExternalFilesDir(null) ?: return
        val file = java.io.File(dir, "slot-${SystemClock.uptimeMillis()}.png")
        val crop = Bitmap.createBitmap(bmp, x, y, cw, ch)
        try {
            java.io.FileOutputStream(file).use { out ->
                crop.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            android.util.Log.i("LandDiag", "ev=slot file=${file.absolutePath} ${cw}x$ch")
        } catch (t: Throwable) {
            android.util.Log.i("LandDiag", "ev=slot fail ${t.javaClass.simpleName}")
        } finally {
            crop.recycle()
        }
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
        // 增量只增不减 (grow-only): 笔画中 (held) 禁止缩小重分配, 只要尺寸足够直接复用 (plan 四.7)
        if (existing != null && !existing.isRecycled &&
            existing.width >= reqW && existing.height >= reqH &&
            existing.width <= maxW && existing.height <= maxH
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

    fun onSurfaceChanged(w: Int, h: Int) {
        if (!held && w > 0 && h > 0) {
            val (targetW, targetH) = sensiblePreallocateSize(w, h, SLOT_MIN_PX, 2 * SLOT_MIN_PX)
            val vm = view.vm
            if (vm != null) {
                vm.runCore(render = false) {
                    preallocateSlots(targetW, targetH)
                }
            } else {
                preallocateSlots(targetW, targetH)
            }
        }
    }

    fun preallocateSlots(targetW: Int, targetH: Int) {
        val w = ((targetW + 255) and -256).coerceAtLeast(SLOT_MIN_PX)
        val h = ((targetH + 255) and -256).coerceAtLeast(SLOT_MIN_PX)
        for (slot in slots) {
            if (slot.busy.get()) continue
            val existing = slot.bitmap
            if (existing == null || existing.isRecycled || existing.width < w || existing.height < h) {
                try {
                    slot.bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                } catch (_: OutOfMemoryError) {
                    break
                }
            }
        }
    }

    fun shrinkSlotsWhenIdle() {
        if (held) return
        val threshold = 2 * SLOT_MIN_PX
        for (slot in slots) {
            if (slot.busy.get()) continue
            val b = slot.bitmap ?: continue
            if (shouldShrinkSlot(held = false, isBusy = false, width = b.width, height = b.height, threshold = threshold)) {
                slot.bitmap = null
            }
        }
    }

    override fun trimMemory() {
        val vm = view.vm
        if (vm != null) {
            vm.runCore(render = false) {
                if (!held && !catchUpArmed) {
                    shrinkSlotsWhenIdle()
                    view.post {
                        if (!held && !catchUpArmed) {
                            view.frontBufferOverlay?.trimStrokeLayer()
                        }
                    }
                }
            }
        } else {
            if (!held && !catchUpArmed) {
                shrinkSlotsWhenIdle()
                view.frontBufferOverlay?.trimStrokeLayer()
            }
        }
    }

    companion object {
        const val SLOT_COUNT = 4
        const val SLOT_MIN_PX = 512

        const val REJECT_NONE = 0
        const val REJECT_NOT_HELD = 1
        const val REJECT_TRANSFORM = 2
        const val REJECT_PANELS = 3
        const val REJECT_FILTER = 4
        const val REJECT_SHIFT_TRACE = 5
        const val REJECT_MIRROR_FALLBACK = 6
        const val REJECT_NO_OVERLAY = 7
        const val REJECT_CANNOT_PREVIEW = 8
        const val REJECT_BUFFER_INVALID = 9
        const val REJECT_POSE_INVALID = 10

        fun rejectReasonToString(reason: Int): String = when (reason) {
            REJECT_NOT_HELD -> "not_held"
            REJECT_TRANSFORM -> "transform_active"
            REJECT_PANELS -> "panels_open"
            REJECT_FILTER -> "filter_session"
            REJECT_SHIFT_TRACE -> "shift_trace"
            REJECT_MIRROR_FALLBACK -> "mirror_fallback"
            REJECT_NO_OVERLAY -> "no_overlay"
            REJECT_CANNOT_PREVIEW -> "cannot_preview"
            REJECT_BUFFER_INVALID -> "buffer_invalid"
            REJECT_POSE_INVALID -> "pose_invalid"
            else -> "unknown"
        }
    }
}
