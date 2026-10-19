/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.canvas

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.SurfaceView
import androidx.annotation.RequiresApi
import androidx.graphics.lowlatency.CanvasFrontBufferedRenderer
import androidx.graphics.surface.SurfaceControlCompat
import com.reverie.paint.core.stylus.FrontBufferProbe
import com.reverie.paint.model.CanvasPose
import com.reverie.paint.model.RealInkPresent
import com.reverie.paint.model.UiClipSnapshot
import java.util.concurrent.atomic.AtomicBoolean

/** 单包最大 dab 戳印数 (预分配, 热路径零分配)。 */
private const val MAX_STAMPS = 64

/** 真墨/预览包最多携带的 UI 避让矩形 (UiPanelSlot 21 项 + 3 项冗余)。 */
private const val MAX_UI_CLIPS = 24

/**
 * 前缓冲载荷 (传递给 CanvasFrontBufferedRenderer 渲染线程)。两种:
 *  - 预览包 ([ink] = false): #81 的笔尖前沿段 (折线) 或戳印预览, 每帧整层重画;
 *  - 真墨包 ([ink] = true): 渲染线程烤好的屏幕槽位图 + 本帧离散脏区, 只追加不整层清。
 *
 * [inFlight]: GL 线程绘制进行时为 true。复用槽位前必须 CAS 抢占, 绘制中的槽位禁止触碰 ——
 * 底层的 SkPath 非线程安全, 写与 GL 线程 `drawPath()` 并发必 SIGSEGV (PR #82 review)。
 */
class FrontBufferPathPacket(
    var path: Path? = null,
    var strokeWidth: Float = 0f,
    var color: Int = 0,
    var isClear: Boolean = false,
    val inFlight: AtomicBoolean = AtomicBoolean(false),
    // ---- STAMP 分级: 真实笔尖戳印预览 ----
    // UI 线程在 CAS 抢占成功后写入, GL 线程只读, happens-before 由 CAS 保证。
    // stampBitmap 为已按笔刷颜色着色的 tip 位图 (BrushTipStampCache 提供),
    // stampX/Y/Size/Alpha 为预分配 dab 点集 (位置像素, 尺寸像素, 透明度 0..1)。
    var stampBitmap: Bitmap? = null,
    val stampX: FloatArray = FloatArray(MAX_STAMPS),
    val stampY: FloatArray = FloatArray(MAX_STAMPS),
    val stampSize: FloatArray = FloatArray(MAX_STAMPS),
    val stampAlpha: FloatArray = FloatArray(MAX_STAMPS),
    var stampCount: Int = 0,
) {
    // ---- 真墨 (渲染线程 CAS 抢占后写入, GL 线程只读) ----
    var ink: Boolean = false

    /** 烤好的屏幕槽位图, 左上角对齐屏幕 `(slotOriginX, slotOriginY)`。 */
    var inkBitmap: Bitmap? = null

    /** 槽位图的占用标记; GPU 光栅化完这一包后由覆盖层归还 (置 false)。 */
    var inkSlotBusy: AtomicBoolean? = null
    var slotOriginX: Int = 0
    var slotOriginY: Int = 0

    /** 离散脏区: 屏幕矩形与对应的显示位图矩形 (`l, t, r, b`), 一一对应, 共 [rectCount] 块。 */
    val screenRects: IntArray = IntArray(RealInkPresent.MAX_DISCRETE_RECTS * 4)
    val bmpRects: IntArray = IntArray(RealInkPresent.MAX_DISCRETE_RECTS * 4)
    var rectCount: Int = 0

    /** 画布 pose 四边形在屏幕上的包围盒; 贴图矩形一律夹在其内。 */
    val canvasScreen: IntArray = IntArray(4)
    var pose: CanvasPose? = null
    var cursorX: Float = Float.NaN
    var cursorY: Float = Float.NaN
    var cursorR: Float = 0f
    var cursorStyle: Int = 0

    /** 对称辅助镜像光标 (前缓冲直出同步绘制) */
    val symCursorX: FloatArray = FloatArray(16)
    val symCursorY: FloatArray = FloatArray(16)
    var symCursorCount: Int = 0

    /** 这一笔会画光标/预测笔尖: 从首帧起维护备份层。 */
    var wantsExtras: Boolean = false

    /** UI 裁剪矩形 (l, t, r, b), 共 [uiClipCount] 项; 只在 GL 线程读取。 */
    val uiClipRects: FloatArray = FloatArray(MAX_UI_CLIPS * 4)
    var uiClipCount: Int = 0
}

/**
 * 轻量独立透明前缓冲层 (SurfaceView + CanvasFrontBufferedRenderer)。
 *
 * 运笔 (DOWN..UP) 期间两种用法二选一:
 *  - 按住真墨 ([RealInkController.held]): 渲染线程把 Krita 刚合成的脏区按屏幕分辨率烤好, 本层逐块
 *    SRC 贴到前缓冲, 旧墨留在缓冲里; 光标与预测笔尖也由本层画;
 *  - 未按住 (整图路径/对称辅助/设备不保留内容): #81 的笔尖前沿段预览, 每帧整层重画。
 *
 * 触摸事件 100% 穿透到底层 CanvasTouchView。
 */
class FrontBufferPreviewOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : SurfaceView(context, attrs) {

    companion object {
        private const val TAG = "FrontBufferOverlay"
        private const val POOL_SIZE = 8
        private const val POOL_MASK = POOL_SIZE - 1
    }

    private var frontRenderer: Any? = null // 保持引用以兼容 API < 29
    private var isRendererInitialized = false

    @Volatile
    private var hasContent = false

    @Volatile
    private var hasInkContent = false

    /**
     * 前缓冲渲染器是否真正可用。
     *
     * [CanvasTouchView] 用它做"回退空洞"判定: overlay 对象存在但渲染器初始化失败
     * (探针不满足 / 构造抛异常) 时, 前缓冲提交会静默 no-op, 此时必须让软件回退路径接管绘制,
     * 否则开启开关后反而完全没有预览。
     */
    val canRenderPreview: Boolean
        get() = isRendererInitialized

    /** 真墨控制器 (画布视图设置), 用来判断"按住期间不清屏"与读取预测笔尖。 */
    @Volatile
    internal var realInk: RealInkController? = null

    /** GL 线程: 一帧真墨光栅化完成 (或没有可提交的包) 后回调, 控制器据此补贴积压。 */
    @Volatile
    var onInkFrameDone: (() -> Unit)? = null

    // 热路径零分配：预分配 8 个槽位的路径包环形缓冲与全局单例 clearPacket
    private val packetPool = Array(POOL_SIZE) { FrontBufferPathPacket(Path()) }
    private var poolIndex = 0
    private val clearPacket = FrontBufferPathPacket(isClear = true)

    // 真墨包单独一池, 与预览包互不抢槽。
    private val inkPool = Array(POOL_SIZE) { FrontBufferPathPacket() }
    private var inkPoolIndex = 0

    // 真墨提交串行化: 同一时间只有一个包在 GL 回调里画 (activeRender), 最多排一个 (queuedPacket,
    // latest-wins)。已录制、等 GPU 光栅化完成的包按 FIFO 记在 gpuPending, 完成回调里归还槽位图。
    // 以上都只在 submitLock 下读写; 真正的 renderFrontBufferedLayer 调用放在锁外。
    private val submitLock = Any()
    private var activeRender: FrontBufferPathPacket? = null
    private var queuedPacket: FrontBufferPathPacket? = null
    private val gpuPending = arrayOfNulls<FrontBufferPathPacket>(POOL_SIZE)
    private var gpuPendingHead = 0
    private var gpuPendingCount = 0

    private val previewPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    // STAMP 分级: 戳印绘制 (位图缩放盖印, 预分配零分配)
    private val stampPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val stampDst = RectF()
    private val previewPathBounds = RectF()

    // ---- 以下只在 GL 线程使用 ----

    /**
     * 前缓冲内容的 CPU 备份层 (与屏幕 1:1)。只服务于"下一帧擦掉上一帧光标/预测圆点后把底下的墨
     * 补回来"; 这一笔不画光标也不画预测笔尖时完全不分配。
     */
    private var strokeLayer: Bitmap? = null
    private var strokeCanvas: Canvas? = null
    private var strokeBufferW = 0
    private var strokeBufferH = 0

    /** [strokeLayer] 上写过的屏幕范围 (并集), 清层时只清这一块。 */
    private val strokeLayerDirty = Rect()

    /** 新一笔开始 (UI 线程置位, GL 线程下一包前消费): 作废上一笔残留的光标账本。 */
    @Volatile
    private var strokeStartPending = false

    /** [clearPreview] 之后的下一包: 备份层账本与光标账本一起作废。 */
    @Volatile
    private var strokeResetPending = false

    private val screenInkPaint = Paint().apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC)
        isFilterBitmap = false
        isAntiAlias = false
    }
    private val poseMatrix = Matrix()
    private val inverseMatrix = Matrix()
    private val blitSrc = Rect()
    private val blitDst = RectF()
    private val blitClip = IntArray(4)

    private val tipOut = FloatArray(3)
    private val tipColor = IntArray(1)
    private val tipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private var lastTipValid = false
    private var lastTipX = 0f
    private var lastTipY = 0f
    private var lastTipHalf = 0f

    private var prevOverlayValid = false
    private val prevOverlay = RectF()
    private val cursorBlack = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.2f
        color = Color.BLACK
    }
    private val cursorWhite = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f
        color = Color.WHITE
    }
    private val cursorFillBlack = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val cursorFillWhite = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val density = resources.displayMetrics.density

    init {
        // 设为完全透明与顶层覆盖
        setZOrderOnTop(true)
        holder.setFormat(PixelFormat.TRANSLUCENT)
        isFocusable = false
        isClickable = false

        if (FrontBufferProbe.isSupported() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            initRenderer()
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun initRenderer() {
        try {
            val callback = object : CanvasFrontBufferedRenderer.Callback<FrontBufferPathPacket> {
                @RequiresApi(Build.VERSION_CODES.Q)
                override fun onDrawFrontBufferedLayer(
                    canvas: Canvas,
                    bufferWidth: Int,
                    bufferHeight: Int,
                    param: FrontBufferPathPacket,
                ) {
                    try {
                        when {
                            param.isClear -> {
                                canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                                clearStrokeLayerDirty()
                                prevOverlayValid = false
                            }
                            param.ink -> drawInkPacket(canvas, bufferWidth, bufferHeight, param)
                            else -> {
                                // #81 预览: 单缓冲清屏, 只画当前最新的前沿段。
                                canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                                prevOverlayValid = false
                                drawPreviewPacket(canvas, param)
                            }
                        }
                    } finally {
                        if (param.ink) {
                            // 录制完成, 槽位图要等 GPU 光栅化完才能还 (见 onFrontBufferedLayerRenderComplete);
                            // 先把排队的下一包提交上去, 不空等 fence。
                            pushGpuPending(param)
                            drainQueuedAfterRender()
                        } else {
                            // 预览包/清屏包录制完即可复用 (路径已拷进 DisplayList)。
                            param.inFlight.set(false)
                        }
                    }
                }

                override fun onDrawMultiBufferedLayer(
                    canvas: Canvas,
                    bufferWidth: Int,
                    bufferHeight: Int,
                    params: Collection<FrontBufferPathPacket>,
                ) {
                    try {
                        // 多缓冲层保持透明清空，正式墨迹由底层的 CanvasTouchView / Krita 负责
                        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                        clearStrokeLayerDirty()
                        prevOverlayValid = false
                    } finally {
                        // commit 静默点 (与前缓冲绘制同一串行 GL 线程, cancelPending 已执行):
                        // 所有 pending 包永不到达前缓冲回调, 在此批量释放 inFlight,
                        // 否则槽位泄漏为永久占用, 打几笔后池枯竭、预览永久消失。
                        for (p in packetPool) p.inFlight.set(false)
                        resetInkQueue()
                        // 系统要求重画 (尺寸/可见性变化) 时前缓冲会被隐藏: 若这时还按住真墨,
                        // 松开它并让主画布接管, 否则按住期间主画布不刷新就是一段空白。
                        val ctl = realInk
                        if (ctl != null && ctl.held) {
                            ctl.release()
                            targetTouchView?.postInvalidate()
                        }
                    }
                }

                override fun onFrontBufferedLayerRenderComplete(
                    frontBufferedLayerSurfaceControl: SurfaceControlCompat,
                    transaction: SurfaceControlCompat.Transaction,
                ) {
                    val done = synchronized(submitLock) { popGpuPendingLocked() }
                    if (done != null) {
                        releaseInkRef(done)
                        done.inFlight.set(false)
                    }
                    notifyInkFrameDone()
                }

                override fun onMultiBufferedLayerRenderComplete(
                    frontBufferedLayerSurfaceControl: SurfaceControlCompat,
                    multiBufferedLayerSurfaceControl: SurfaceControlCompat,
                    transaction: SurfaceControlCompat.Transaction,
                ) {
                    resetInkQueue()
                    notifyInkFrameDone()
                }
            }

            frontRenderer = CanvasFrontBufferedRenderer(this, callback)
            isRendererInitialized = true
            Log.d(TAG, "CanvasFrontBufferedRenderer initialized successfully")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to initialize CanvasFrontBufferedRenderer", t)
            frontRenderer = null
            isRendererInitialized = false
        }
    }

    /** #81 预览包的绘制 (戳印或折线)。GL 线程。 */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun drawPreviewPacket(canvas: Canvas, param: FrontBufferPathPacket) {
        // 2a. STAMP 分级: 逐 dab 盖印真实笔尖位图
        if (param.stampCount > 0) {
            val stampBmp = param.stampBitmap
            val stampN = param.stampCount.coerceAtMost(MAX_STAMPS)
            if (stampBmp != null && !stampBmp.isRecycled) {
                var minX = Float.MAX_VALUE
                var minY = Float.MAX_VALUE
                var maxX = -Float.MAX_VALUE
                var maxY = -Float.MAX_VALUE
                for (i in 0 until stampN) {
                    val s = param.stampSize[i]
                    if (s <= 0f) continue
                    val half = s / 2f
                    minX = minOf(minX, param.stampX[i] - half)
                    minY = minOf(minY, param.stampY[i] - half)
                    maxX = maxOf(maxX, param.stampX[i] + half)
                    maxY = maxOf(maxY, param.stampY[i] + half)
                }
                if (minX <= maxX) {
                    canvas.save()
                    // FILTER_BITMAP_FLAG 的双线性采样可能外溢 <1px, 裁剪边界外扩 1px。
                    clipOutUi(canvas, param, minX - 1f, minY - 1f, maxX + 1f, maxY + 1f)
                    for (i in 0 until stampN) {
                        val s = param.stampSize[i]
                        if (s <= 0f) continue
                        val cx = param.stampX[i]
                        val cy = param.stampY[i]
                        stampPaint.alpha =
                            (param.stampAlpha[i] * 255f).toInt().coerceIn(0, 255)
                        val half = s / 2f
                        stampDst.set(cx - half, cy - half, cx + half, cy + half)
                        canvas.drawBitmap(stampBmp, null, stampDst, stampPaint)
                    }
                    canvas.restore()
                }
            }
            return
        }

        // 2b. TIER_1/2: 绘制当前最新笔尖前沿段 (单色折线)
        val path = param.path ?: return
        path.computeBounds(previewPathBounds, true)
        val margin = param.strokeWidth.coerceAtLeast(1.5f) / 2f + 1f
        canvas.save()
        clipOutUi(
            canvas, param,
            previewPathBounds.left - margin, previewPathBounds.top - margin,
            previewPathBounds.right + margin, previewPathBounds.bottom + margin,
        )
        previewPaint.color = param.color
        previewPaint.strokeWidth = param.strokeWidth.coerceAtLeast(1.5f)
        canvas.drawPath(path, previewPaint)
        canvas.restore()
    }

    /**
     * 原子占用一个空闲槽位。绘制中的槽位跳过; 池空返回 null, 调用方丢帧
     * (预览为 best-effort, 下一触控事件 ~4ms 后即到, 丢帧无视觉影响, 绝不阻塞 UI 线程)。
     */
    private fun obtainPacket(): FrontBufferPathPacket? {
        for (i in 0 until POOL_SIZE) {
            val cand = packetPool[(poolIndex + i) and POOL_MASK]
            if (cand.inFlight.compareAndSet(false, true)) {
                poolIndex = (poolIndex + i + 1) and POOL_MASK
                return cand
            }
        }
        return null
    }

    /**
     * 投递最新前沿预览路径至前缓冲渲染线程 (热路径零分配)。
     */
    fun renderPreviewPath(path: Path, strokeWidth: Float, color: Int, clips: UiClipSnapshot) {
        if (!isRendererInitialized) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // 先确认渲染器可用再占槽: 否则占了槽却投递失败, inFlight 永久泄漏。
            @Suppress("UNCHECKED_CAST")
            val renderer = frontRenderer as? CanvasFrontBufferedRenderer<FrontBufferPathPacket>
                ?: return
            val packet = obtainPacket() ?: return // 无空闲槽位: 丢帧
            try {
                val targetPath = packet.path ?: Path().also { packet.path = it }
                targetPath.set(path)
                packet.strokeWidth = strokeWidth
                packet.color = color
                packet.isClear = false
                packet.stampCount = 0 // 复用槽位: 清除残留戳印
                packet.ink = false
                packet.uiClipCount = clips.copyInto(packet.uiClipRects, MAX_UI_CLIPS)
                hasContent = true
                renderer.renderFrontBufferedLayer(packet)
            } catch (t: Throwable) {
                // 提交失败: 立即释放槽位, 否则泄漏为永久 inFlight
                packet.uiClipCount = 0
                packet.inFlight.set(false)
                Log.w(TAG, "renderPreviewPath error: ${t.message}")
            }
        }
    }

    /**
     * 投递戳印预览: 沿 dab 点集盖印真实笔尖位图 (STAMP 分级, 水彩/纹理/绘画类)。
     * 热路径零分配: 点集拷入预分配槽位数组; 位图由 BrushTipStampCache 提供 (已着色)。
     */
    fun renderStampPreview(
        bitmap: Bitmap,
        xs: FloatArray,
        ys: FloatArray,
        sizes: FloatArray,
        alphas: FloatArray,
        count: Int,
        clips: UiClipSnapshot,
    ) {
        if (!isRendererInitialized) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // 先确认渲染器可用再占槽: 否则占了槽却投递失败, inFlight 永久泄漏。
            @Suppress("UNCHECKED_CAST")
            val renderer = frontRenderer as? CanvasFrontBufferedRenderer<FrontBufferPathPacket>
                ?: return
            val packet = obtainPacket() ?: return // 无空闲槽位: 丢帧
            try {
                val n = count.coerceAtMost(MAX_STAMPS)
                xs.copyInto(packet.stampX, 0, 0, n)
                ys.copyInto(packet.stampY, 0, 0, n)
                sizes.copyInto(packet.stampSize, 0, 0, n)
                alphas.copyInto(packet.stampAlpha, 0, 0, n)
                packet.stampBitmap = bitmap
                packet.stampCount = n
                packet.path?.rewind()
                packet.isClear = false
                packet.ink = false
                packet.uiClipCount = clips.copyInto(packet.uiClipRects, MAX_UI_CLIPS)
                hasContent = true
                renderer.renderFrontBufferedLayer(packet)
            } catch (t: Throwable) {
                // 提交失败: 立即释放槽位, 否则泄漏为永久 inFlight
                packet.uiClipCount = 0
                packet.inFlight.set(false)
                Log.w(TAG, "renderStampPreview error: ${t.message}")
            }
        }
    }

    // ======================= 真墨 (渲染线程调用) =======================

    private fun obtainInkPacket(): FrontBufferPathPacket? {
        for (i in 0 until POOL_SIZE) {
            val cand = inkPool[(inkPoolIndex + i) and POOL_MASK]
            if (cand.inFlight.compareAndSet(false, true)) {
                inkPoolIndex = (inkPoolIndex + i + 1) and POOL_MASK
                return cand
            }
        }
        return null
    }

    /**
     * 现在能不能再提交一个真墨包: 渲染器可用, 且有空闲包或可被最新一帧顶掉的排队包。
     * "GL 还在画上一帧"不算忙 —— 排队包会被顶掉 (latest-wins)。
     */
    fun isIdleForInk(): Boolean {
        if (!isRendererInitialized) return false
        synchronized(submitLock) {
            if (queuedPacket != null) return true
        }
        for (p in inkPool) {
            if (!p.inFlight.get()) return true
        }
        return false
    }

    /** 丢掉还在排队 (GL 尚未开始画) 的真墨包, 归还它的槽位图。返回 true = 确实丢了一个。 */
    fun discardQueuedInk(): Boolean {
        synchronized(submitLock) {
            if (queuedPacket == null) return false
            releaseQueuedLocked()
        }
        return true
    }

    /**
     * 取回还在排队的真墨包: 把它的位图矩形逐块抄进 [outBmpRects] 并返回块数 (没有排队包返回 0)。
     * 调用方把这些脏区并回积压一起重烤, 被顶掉的那一帧不会在按住期间留下断口。
     */
    fun reclaimQueuedInkRegions(outBmpRects: IntArray): Int {
        synchronized(submitLock) {
            val q = queuedPacket ?: return 0
            queuedPacket = null
            val n = minOf(q.rectCount, RealInkPresent.MAX_DISCRETE_RECTS, outBmpRects.size / 4)
            if (n > 0) System.arraycopy(q.bmpRects, 0, outBmpRects, 0, n * 4)
            releaseInkRef(q)
            q.inFlight.set(false)
            return n
        }
    }

    /**
     * 提交一帧真墨: [screenBitmap] 是烤好的紧凑槽位图 (左上角对齐屏幕 `slotOriginX, slotOriginY`),
     * [screenRects]/[bmpRects] 各 `count * 4` 个 int, 逐块一一对应。只拷进包的复用数组 (零分配)。
     *
     * 返回 false 时 [slotBusy] 的所有权仍在调用方; 返回 true 后由本层在 GPU 光栅化完成时归还。
     */
    fun renderInkScreenRegions(
        screenBitmap: Bitmap,
        screenRects: IntArray,
        bmpRects: IntArray,
        count: Int,
        pose: CanvasPose,
        cursorX: Float,
        cursorY: Float,
        cursorR: Float,
        cursorStyle: Int,
        wantsExtras: Boolean,
        uiClips: UiClipSnapshot,
        slotBusy: AtomicBoolean,
        slotOriginX: Int,
        slotOriginY: Int,
        canvasBounds: IntArray,
        symCursorX: FloatArray? = null,
        symCursorY: FloatArray? = null,
        symCursorCount: Int = 0,
    ): Boolean {
        if (!isRendererInitialized) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        if (screenBitmap.isRecycled) return false
        val n = minOf(count, RealInkPresent.MAX_DISCRETE_RECTS)
        if (n <= 0) return false
        val packet = obtainInkPacket() ?: run {
            if (!discardQueuedInk()) return false
            obtainInkPacket() ?: return false
        }
        try {
            packet.ink = true
            packet.isClear = false
            packet.rectCount = n
            System.arraycopy(screenRects, 0, packet.screenRects, 0, n * 4)
            System.arraycopy(bmpRects, 0, packet.bmpRects, 0, n * 4)
            System.arraycopy(canvasBounds, 0, packet.canvasScreen, 0, 4)
            packet.slotOriginX = slotOriginX
            packet.slotOriginY = slotOriginY
            packet.inkBitmap = screenBitmap
            packet.pose = pose
            packet.cursorX = cursorX
            packet.cursorY = cursorY
            packet.cursorR = cursorR
            packet.cursorStyle = cursorStyle
            val sc = if (symCursorX != null && symCursorY != null) minOf(symCursorCount, 16) else 0
            packet.symCursorCount = sc
            if (sc > 0 && symCursorX != null && symCursorY != null) {
                System.arraycopy(symCursorX, 0, packet.symCursorX, 0, sc)
                System.arraycopy(symCursorY, 0, packet.symCursorY, 0, sc)
            }
            packet.wantsExtras = wantsExtras
            // 每次提交无条件重置: 空快照也必须写 0, 不能残留上一包的裁剪矩形。
            packet.uiClipCount = uiClips.copyInto(packet.uiClipRects, MAX_UI_CLIPS)
            packet.inkSlotBusy = slotBusy
            return submitInkPacket(packet)
        } catch (t: Throwable) {
            packet.inkSlotBusy = null
            packet.inkBitmap = null
            packet.uiClipCount = 0
            packet.inFlight.set(false)
            Log.w(TAG, "renderInkScreenRegions error: ${t.message}")
            return false
        }
    }

    /** 串行提交: GL 空闲则立即提交, 否则顶掉旧排队包后排队。失败时回滚并返回 false。 */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun submitInkPacket(packet: FrontBufferPathPacket): Boolean {
        var submitNow = false
        synchronized(submitLock) {
            releaseQueuedLocked()
            if (activeRender == null) {
                activeRender = packet
                submitNow = true
            } else {
                queuedPacket = packet
            }
        }
        hasContent = true
        hasInkContent = true
        if (!submitNow) return true
        @Suppress("UNCHECKED_CAST")
        val renderer = frontRenderer as? CanvasFrontBufferedRenderer<FrontBufferPathPacket>
        val ok =
            try {
                renderer?.renderFrontBufferedLayer(packet)
                renderer != null
            } catch (t: Throwable) {
                Log.w(TAG, "ink submit error: ${t.message}")
                false
            }
        if (!ok) {
            synchronized(submitLock) { if (activeRender === packet) activeRender = null }
            packet.inkSlotBusy = null
            packet.inkBitmap = null
            packet.inFlight.set(false)
        }
        return ok
    }

    /** 当前真墨包录制完: 清 active 标记, 把排队的下一包 (最多一个) 提交上去。GL 线程。 */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun drainQueuedAfterRender() {
        val next = synchronized(submitLock) {
            activeRender = null
            val q = queuedPacket
            queuedPacket = null
            activeRender = q
            q
        }
        if (next == null) {
            notifyInkFrameDone()
            return
        }
        @Suppress("UNCHECKED_CAST")
        val renderer = frontRenderer as? CanvasFrontBufferedRenderer<FrontBufferPathPacket>
        val ok =
            try {
                renderer?.renderFrontBufferedLayer(next)
                renderer != null
            } catch (t: Throwable) {
                Log.w(TAG, "ink drain error: ${t.message}")
                false
            }
        if (!ok) {
            synchronized(submitLock) { if (activeRender === next) activeRender = null }
            releaseInkRef(next)
            next.inFlight.set(false)
            notifyInkFrameDone()
        }
    }

    private fun pushGpuPending(packet: FrontBufferPathPacket) {
        synchronized(submitLock) {
            if (gpuPendingCount == POOL_SIZE) {
                // 不应发生 (池只有 POOL_SIZE 个包); 防御: 归还最老的那个。
                popGpuPendingLocked()?.let {
                    releaseInkRef(it)
                    it.inFlight.set(false)
                }
            }
            gpuPending[(gpuPendingHead + gpuPendingCount) and POOL_MASK] = packet
            gpuPendingCount++
        }
    }

    private fun popGpuPendingLocked(): FrontBufferPathPacket? {
        if (gpuPendingCount == 0) return null
        val p = gpuPending[gpuPendingHead]
        gpuPending[gpuPendingHead] = null
        gpuPendingHead = (gpuPendingHead + 1) and POOL_MASK
        gpuPendingCount--
        return p
    }

    /** commit / 多缓冲提交后: 这些真墨包不会再回调, 全部归还。 */
    private fun resetInkQueue() {
        synchronized(submitLock) {
            activeRender = null
            releaseQueuedLocked()
            while (gpuPendingCount > 0) {
                popGpuPendingLocked()?.let {
                    releaseInkRef(it)
                    it.inFlight.set(false)
                }
            }
            gpuPendingHead = 0
        }
        for (p in inkPool) {
            releaseInkRef(p)
            p.inFlight.set(false)
        }
    }

    private fun notifyInkFrameDone() {
        val cb = onInkFrameDone ?: return
        try {
            cb()
        } catch (_: Throwable) {
        }
    }

    /** 丢掉排队的那个真墨包。调用方需持有 [submitLock]。 */
    private fun releaseQueuedLocked() {
        val q = queuedPacket ?: return
        queuedPacket = null
        releaseInkRef(q)
        q.inFlight.set(false)
    }

    /** 归还一个包的槽位图 (同一个包只归还一次, 重复调用是空操作)。 */
    private fun releaseInkRef(packet: FrontBufferPathPacket) {
        packet.inkBitmap = null
        val slot = packet.inkSlotBusy ?: return
        packet.inkSlotBusy = null
        slot.set(false)
    }

    // ======================= 真墨绘制 (GL 线程) =======================

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun drawInkPacket(canvas: Canvas, bufferWidth: Int, bufferHeight: Int, param: FrontBufferPathPacket) {
        // 缓冲尺寸变了: 前缓冲整层作废, 备份层也必须丢掉重来。
        if (bufferWidth != strokeBufferW || bufferHeight != strokeBufferH) {
            strokeBufferW = bufferWidth
            strokeBufferH = bufferHeight
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            strokeLayerDirty.setEmpty()
            prevOverlayValid = false
            strokeLayer = null
            strokeCanvas = null
        }
        val reset = strokeResetPending
        val start = strokeStartPending
        strokeResetPending = false
        strokeStartPending = false
        // 清屏之后的首包: 备份层是上一笔的, 作废 (只清记过账的区域)。
        if (reset) clearStrokeLayerDirty()
        if (reset || start) prevOverlayValid = false

        val willDrawCursor = param.cursorR > 0f && param.cursorX.isFinite() && param.cursorY.isFinite() &&
            param.cursorStyle != 4
        val willDrawTip = probePredictedTip()
        val needsBackup = param.wantsExtras || willDrawCursor || willDrawTip || prevOverlayValid
        val layer = if (needsBackup) ensureStrokeLayer(bufferWidth, bufferHeight) else null
        if (layer != null && prevOverlayValid) erasePrevOverlay(canvas, layer, param)

        val bmp = param.inkBitmap
        var inkL = Float.MAX_VALUE
        var inkT = Float.MAX_VALUE
        var inkR = -Float.MAX_VALUE
        var inkB = -Float.MAX_VALUE
        for (i in 0 until param.rectCount) {
            val s = i * 4
            val l = param.screenRects[s].toFloat()
            val t = param.screenRects[s + 1].toFloat()
            val r = param.screenRects[s + 2].toFloat()
            val b = param.screenRects[s + 3].toFloat()
            if (l < inkL) inkL = l
            if (t < inkT) inkT = t
            if (r > inkR) inkR = r
            if (b > inkB) inkB = b
        }
        if (bmp != null && !bmp.isRecycled) {
            val sc = strokeCanvas
            if (layer != null && sc != null) {
                blitInk(sc, param, bmp)
                for (i in 0 until param.rectCount) {
                    val s = i * 4
                    strokeLayerDirty.union(
                        param.screenRects[s],
                        param.screenRects[s + 1],
                        param.screenRects[s + 2],
                        param.screenRects[s + 3],
                    )
                }
            }
            canvas.save()
            if (inkL <= inkR) clipOutUi(canvas, param, inkL, inkT, inkR, inkB)
            blitInk(canvas, param, bmp)
            canvas.restore()
        }
        var overlayL = Float.MAX_VALUE
        var overlayT = Float.MAX_VALUE
        var overlayR = -Float.MAX_VALUE
        var overlayB = -Float.MAX_VALUE
        if (lastTipValid) {
            overlayL = lastTipX - lastTipHalf - 1f
            overlayT = lastTipY - lastTipHalf - 1f
            overlayR = lastTipX + lastTipHalf + 1f
            overlayB = lastTipY + lastTipHalf + 1f
        }
        if (willDrawCursor) {
            val half = maxOf(param.cursorR, 7f * density) + 2f
            overlayL = minOf(overlayL, param.cursorX - half)
            overlayT = minOf(overlayT, param.cursorY - half)
            overlayR = maxOf(overlayR, param.cursorX + half)
            overlayB = maxOf(overlayB, param.cursorY + half)
            for (i in 0 until param.symCursorCount) {
                val sx = param.symCursorX[i]
                val sy = param.symCursorY[i]
                if (sx.isFinite() && sy.isFinite()) {
                    overlayL = minOf(overlayL, sx - half)
                    overlayT = minOf(overlayT, sy - half)
                    overlayR = maxOf(overlayR, sx + half)
                    overlayB = maxOf(overlayB, sy + half)
                }
            }
        }
        canvas.save()
        if (overlayL <= overlayR) {
            clipOutUi(canvas, param, overlayL, overlayT, overlayR, overlayB)
        }
        if (lastTipValid) canvas.drawCircle(lastTipX, lastTipY, lastTipHalf, tipPaint)
        if (willDrawCursor) drawHoldCursor(canvas, param)
        canvas.restore()
        rememberOverlayBounds(param, willDrawCursor)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun clipOutUi(
        canvas: Canvas,
        param: FrontBufferPathPacket,
        l: Float,
        t: Float,
        r: Float,
        b: Float,
    ) {
        for (i in 0 until param.uiClipCount) {
            val o = i * 4
            // 包围盒预筛: 只对与当前包绘制范围相交的 UI 矩形调用 clipOutRect。
            if (r >= param.uiClipRects[o] && l <= param.uiClipRects[o + 2] &&
                b >= param.uiClipRects[o + 1] && t <= param.uiClipRects[o + 3]
            ) {
                canvas.clipOutRect(
                    param.uiClipRects[o],
                    param.uiClipRects[o + 1],
                    param.uiClipRects[o + 2],
                    param.uiClipRects[o + 3],
                )
            }
        }
    }

    /**
     * 把一包的每一块离散脏区分别 SRC 贴上去 (绝不并成包围盒: 斜线/拐弯的包围盒四角是空白,
     * 整块 SRC 会把空白替换成透明, 形成方条缺口)。未旋转且无镜像时轴对齐直通; 否则每块先在
     * pose 空间裁出位图矩形 (屏幕上的四边形), 再回到屏幕坐标贴。
     */
    private fun blitInk(canvas: Canvas, param: FrontBufferPathPacket, bitmap: Bitmap) {
        val pose = param.pose ?: return
        val axisAligned = isAxisAligned(pose)
        if (!axisAligned) {
            poseMatrix.reset()
            poseMatrix.preTranslate(pose.centerX, pose.centerY)
            poseMatrix.preRotate(pose.rotation)
            poseMatrix.preScale(pose.scale, pose.scale)
            poseMatrix.preScale(pose.signX, pose.signY)
            if (!poseMatrix.invert(inverseMatrix)) return
        }
        for (i in 0 until param.rectCount) {
            val s = i * 4
            if (!clipBlitRect(param, bitmap, s)) continue
            val cl = blitClip[0]
            val ct = blitClip[1]
            val cr = blitClip[2]
            val cb = blitClip[3]
            blitSrc.set(cl - param.slotOriginX, ct - param.slotOriginY, cr - param.slotOriginX, cb - param.slotOriginY)
            blitDst.set(cl.toFloat(), ct.toFloat(), cr.toFloat(), cb.toFloat())
            if (axisAligned) {
                canvas.drawBitmap(bitmap, blitSrc, blitDst, screenInkPaint)
                continue
            }
            val bl = param.bmpRects[s]
            val bt = param.bmpRects[s + 1]
            val br = param.bmpRects[s + 2]
            val bb = param.bmpRects[s + 3]
            if (br <= bl || bb <= bt) continue
            canvas.save()
            canvas.concat(poseMatrix)
            canvas.clipRect(bl - pose.halfW, bt - pose.halfH, br - pose.halfW, bb - pose.halfH)
            canvas.concat(inverseMatrix)
            canvas.clipRect(blitDst)
            canvas.drawBitmap(bitmap, blitSrc, blitDst, screenInkPaint)
            canvas.restore()
        }
    }

    private fun isAxisAligned(pose: CanvasPose): Boolean {
        val rot = pose.rotation % 360f
        val rotMod = if (rot < 0f) rot + 360f else rot
        if (rotMod > 0.05f && rotMod < 359.95f) return false
        return pose.signX > 0f && pose.signY > 0f
    }

    /**
     * 第 [s]/4 块屏幕矩形夹进「画布在屏幕上的包围盒」与「本帧烤过的槽位范围」, 结果写进
     * [blitClip]。交集为空返回 false: 槽位范围外的 src 落在位图外, 画布外没有文档像素,
     * 贴一块来源不明的像素比不贴更糟。
     */
    private fun clipBlitRect(param: FrontBufferPathPacket, bitmap: Bitmap, s: Int): Boolean {
        var l = param.screenRects[s]
        var t = param.screenRects[s + 1]
        var r = param.screenRects[s + 2]
        var b = param.screenRects[s + 3]
        val cs = param.canvasScreen
        if (cs[2] > cs[0] && cs[3] > cs[1]) {
            if (cs[0] > l) l = cs[0]
            if (cs[1] > t) t = cs[1]
            if (cs[2] < r) r = cs[2]
            if (cs[3] < b) b = cs[3]
        }
        if (param.slotOriginX > l) l = param.slotOriginX
        if (param.slotOriginY > t) t = param.slotOriginY
        val slotR = param.slotOriginX + bitmap.width
        val slotB = param.slotOriginY + bitmap.height
        if (slotR < r) r = slotR
        if (slotB < b) b = slotB
        blitClip[0] = l
        blitClip[1] = t
        blitClip[2] = r
        blitClip[3] = b
        return r > l && b > t
    }

    /** 查询这一帧要不要画预测笔尖 (结果落在 lastTip*, 供绘制与记账复用)。 */
    private fun probePredictedTip(): Boolean {
        val ctl = realInk
        if (ctl == null || !ctl.predictedTip(tipOut, tipColor)) {
            lastTipValid = false
            return false
        }
        lastTipValid = true
        lastTipX = tipOut[0]
        lastTipY = tipOut[1]
        lastTipHalf = tipOut[2] / 2f
        tipPaint.color = tipColor[0]
        return true
    }

    /**
     * 擦掉上一帧的光标/预测圆点: 只清那一小块, 再从备份层把墨补回来。
     * 备份层这一块不可信 (被清过/重建过) 时 [prevOverlayValid] 已为 false, 不会走到这里。
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun erasePrevOverlay(canvas: Canvas, layer: Bitmap, param: FrontBufferPathPacket) {
        val l = maxOf(0, prevOverlay.left.toInt())
        val t = maxOf(0, prevOverlay.top.toInt())
        val r = minOf(layer.width, kotlin.math.ceil(prevOverlay.right.toDouble()).toInt())
        val b = minOf(layer.height, kotlin.math.ceil(prevOverlay.bottom.toDouble()).toInt())
        if (r <= l || b <= t) return
        canvas.save()
        clipOutUi(canvas, param, l.toFloat(), t.toFloat(), r.toFloat(), b.toFloat())
        canvas.clipRect(l.toFloat(), t.toFloat(), r.toFloat(), b.toFloat())
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        blitSrc.set(l, t, r, b)
        blitDst.set(l.toFloat(), t.toFloat(), r.toFloat(), b.toFloat())
        canvas.drawBitmap(layer, blitSrc, blitDst, null)
        canvas.restore()
    }

    /** 记下这一帧光标/预测圆点占的屏幕范围, 下一帧只擦这一小块。 */
    private fun rememberOverlayBounds(param: FrontBufferPathPacket, cursorDrawn: Boolean) {
        var has = false
        if (cursorDrawn) {
            val half = maxOf(param.cursorR, 7f * density) + 2f
            prevOverlay.set(param.cursorX - half, param.cursorY - half, param.cursorX + half, param.cursorY + half)
            for (i in 0 until param.symCursorCount) {
                val sx = param.symCursorX[i]
                val sy = param.symCursorY[i]
                if (sx.isFinite() && sy.isFinite()) {
                    prevOverlay.union(sx - half, sy - half, sx + half, sy + half)
                }
            }
            has = true
        }
        if (lastTipValid) {
            val half = lastTipHalf + 2f
            val l = lastTipX - half
            val t = lastTipY - half
            val r = lastTipX + half
            val b = lastTipY + half
            if (has) prevOverlay.union(l, t, r, b) else prevOverlay.set(l, t, r, b)
            has = true
        }
        prevOverlayValid = has
    }

    private fun ensureStrokeLayer(w: Int, h: Int): Bitmap? {
        if (w <= 0 || h <= 0) return null
        val existing = strokeLayer
        if (existing != null && existing.width == w && existing.height == h) return existing
        val created =
            try {
                Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            } catch (_: OutOfMemoryError) {
                return null
            }
        strokeLayer = created
        strokeCanvas = Canvas(created)
        strokeLayerDirty.setEmpty()
        prevOverlayValid = false
        return created
    }

    private fun clearStrokeLayerDirty() {
        val sc = strokeCanvas ?: return
        if (strokeLayerDirty.isEmpty) return
        sc.save()
        sc.clipRect(strokeLayerDirty)
        sc.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        sc.restore()
        strokeLayerDirty.setEmpty()
    }

    private fun drawHoldCursor(canvas: Canvas, param: FrontBufferPathPacket) {
        val r = param.cursorR
        if (r <= 0f) return
        drawSingleCursor(canvas, param.cursorX, param.cursorY, r, param.cursorStyle)
        for (i in 0 until param.symCursorCount) {
            drawSingleCursor(canvas, param.symCursorX[i], param.symCursorY[i], r, param.cursorStyle)
        }
    }

    private fun drawSingleCursor(canvas: Canvas, x: Float, y: Float, r: Float, cursorStyle: Int) {
        if (!x.isFinite() || !y.isFinite()) return
        val d = density
        when (cursorStyle) {
            1 -> {
                val len = 7f * d
                canvas.drawLine(x - len, y, x + len, y, cursorBlack)
                canvas.drawLine(x, y - len, x, y + len, cursorBlack)
                canvas.drawLine(x - len, y, x + len, y, cursorWhite)
                canvas.drawLine(x, y - len, x, y + len, cursorWhite)
            }
            2 -> {
                canvas.drawCircle(x, y, 3f * d, cursorFillBlack)
                canvas.drawCircle(x, y, 1.8f * d, cursorFillWhite)
            }
            else -> {
                canvas.drawCircle(x, y, r + 0.8f, cursorBlack)
                canvas.drawCircle(x, y, r, cursorWhite)
                if (cursorStyle == 5) {
                    val len = 4.5f * d
                    canvas.drawLine(x - len, y, x + len, y, cursorBlack)
                    canvas.drawLine(x, y - len, x, y + len, cursorBlack)
                    canvas.drawLine(x - len, y, x + len, y, cursorWhite)
                    canvas.drawLine(x, y - len, x, y + len, cursorWhite)
                }
            }
        }
    }

    /** 落笔 (UI 线程): 新的一笔, 下一包前作废上一笔残留的光标账本。 */
    fun beginStroke() {
        strokeStartPending = true
    }

    /**
     * 抬笔或取消时清空前缓冲层。真墨按住期间不清 (前缓冲正被本笔持续更新)。
     *
     * 真墨内容只用 commit() 清: 先贴一个清屏包的话, 单缓冲 <1ms 就透明了, 主画布新帧却要等
     * 下一拍 VSYNC, 中间 1 帧漏出旧画布。commit() 隐藏前缓冲层、多缓冲层本来就是透明的,
     * 效果相同但没有真空期。#81 预览内容维持原来的"清屏包 + commit"。
     */
    fun clearPreview() {
        clearInternal(inkOnly = false)
    }

    /** 抬笔追赶完成后清掉真墨 (前缓冲上若是 #81 预览则不动)。 */
    internal fun clearInk() {
        clearInternal(inkOnly = true)
    }

    /** 前缓冲上是否还留着真墨。 */
    internal val hasInk: Boolean
        get() = hasInkContent

    private fun clearInternal(inkOnly: Boolean) {
        if (realInk?.held == true) return
        if (!isRendererInitialized) return
        if (inkOnly && !hasInkContent) return
        strokeResetPending = true
        if (!hasContent) return
        val ink = hasInkContent
        hasContent = false
        hasInkContent = false
        synchronized(submitLock) { releaseQueuedLocked() }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                @Suppress("UNCHECKED_CAST")
                val r = frontRenderer as? CanvasFrontBufferedRenderer<FrontBufferPathPacket>
                if (!ink) r?.renderFrontBufferedLayer(clearPacket)
                r?.commit()
            } catch (t: Throwable) {
                Log.w(TAG, "clearPreview error: ${t.message}")
            }
        }
    }

    /**
     * 释放前缓冲渲染器资源。
     */
    fun release() {
        hasContent = false
        hasInkContent = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                @Suppress("UNCHECKED_CAST")
                (frontRenderer as? CanvasFrontBufferedRenderer<FrontBufferPathPacket>)
                    ?.release(true)
            } catch (t: Throwable) {
                Log.w(TAG, "release error: ${t.message}")
            }
        }
        frontRenderer = null
        isRendererInitialized = false
        resetInkQueue()
    }

    var targetTouchView: CanvasTouchView? = null

    // 触摸与悬浮事件 100% 穿透并转发到底层 CanvasTouchView
    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        if (ev == null) return false
        val target = targetTouchView ?: return false
        return target.dispatchTouchEvent(ev)
    }

    override fun onTouchEvent(event: MotionEvent?): Boolean {
        if (event == null) return false
        val target = targetTouchView ?: return false
        return target.dispatchTouchEvent(event)
    }

    override fun onHoverEvent(event: MotionEvent?): Boolean {
        if (event == null) return false
        val target = targetTouchView ?: return false
        return target.dispatchHoverEvent(event)
    }

    override fun onGenericMotionEvent(event: MotionEvent?): Boolean {
        if (event == null) return false
        val target = targetTouchView ?: return false
        return target.dispatchGenericMotionEvent(event)
    }
}
