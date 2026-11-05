/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.canvas

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ComposeShader
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.View
import android.view.SurfaceView
import androidx.annotation.RequiresApi
import androidx.graphics.lowlatency.CanvasFrontBufferedRenderer
import androidx.graphics.surface.SurfaceControlCompat
import com.reverie.paint.core.stylus.FrontBufferProbe
import com.reverie.paint.model.CanvasPose
import com.reverie.paint.model.FrontBufferCompose
import com.reverie.paint.model.FrontBufferHolePolicy
import com.reverie.paint.model.FrontBufferSurfaceGate
import com.reverie.paint.model.RealInkPresent
import com.reverie.paint.model.UiClipSnapshot
import com.reverie.paint.perf.FbGeomLog
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
    var rebake: Boolean = false

    /** 烤好的屏幕槽位图, 左上角对齐屏幕 `(slotOriginX, slotOriginY)`。 */
    var inkBitmap: Bitmap? = null

    /** 槽位图的占用标记; GPU 光栅化完这一包后由覆盖层归还 (置 false)。 */
    var inkSlotBusy: AtomicBoolean? = null
    var slotOriginX: Int = 0
    var slotOriginY: Int = 0

    /** 离散脏区: 屏幕矩形与对应的显示位图矩形 (`l, t, r, b`), 一一对应, 共 [rectCount] 块。 */
    val screenRects: IntArray = IntArray(FrontBufferCompose.MAX_PACKET_RECTS * 4)
    val bmpRects: IntArray = IntArray(FrontBufferCompose.MAX_PACKET_RECTS * 4)
    var rectCount: Int = 0

    /** 前 [inkCount] 块是墨脏区 (其余是 compose 包的光标/导引线足迹)。赎回排队包时只赎墨脏区。 */
    var inkCount: Int = 0

    /**
     * compose 包 ([FrontBufferHolePolicy.MODE_COMPOSE]): 第 i 块从槽位图 `packXY[2i], packXY[2i+1]` 处
     * SRC 贴到屏幕矩形 i, 再在全部矩形的并集内按窗口同一顺序重画覆盖层 (像素网格 → 导引线 → 光标 →
     * 绘图辅助线)。不用备份层, 不挖孔。
     */
    var compose: Boolean = false
    val packXY: IntArray = IntArray(FrontBufferCompose.MAX_PACKET_RECTS * 2)

    /** compose: 文档完整覆盖的整像素屏幕矩形 (`l, t, r, b`); 静态覆盖层只画在这里面。 */
    val docInner: IntArray = IntArray(4)

    /** compose: 落笔时录的静态覆盖层 (null = 没有)。 */
    internal var decor: FrontBufferDecor? = null

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

    /** H-1: 这一笔的孔洞掩码 (null = 无孔洞, 与旧路径逐字节相同)。 */
    internal var holeMask: HoleMask? = null

    /** 第 i 块离散脏区命中孔洞时第 i 位为 1; 只有这些块走掩码贴图。 */
    var holeHitBits: Int = 0

    /** 动态吸附导引线 ([DynamicGuidePainter.KIND_NONE] = 不画), 屏幕坐标段见 [DynamicGuidePainter.SEG_SIZE]。 */
    var guideKind: Int = DynamicGuidePainter.KIND_NONE
    val guideSeg: FloatArray = FloatArray(DynamicGuidePainter.SEG_SIZE)
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
        private const val HOLE_SHADER_CACHE = 4
    }

    private var frontRenderer: Any? = null // 保持引用以兼容 API < 29
    private var isRendererInitialized = false

    /** [release] 是否已执行; detach 与 Compose onRelease 都会调, 必须可重入。 */
    @Volatile
    private var released = false

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

    // 热路径零分配：全局单例 clearPacket
    private val clearPacket = FrontBufferPathPacket(isClear = true)

    // 真墨包单独一池。
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

    /**
     * 前缓冲内容代次: 每次前缓冲被整层清掉 (commit / 多缓冲重画) 自增。compose 的渲染线程据此把
     * "前缓冲上此刻画着的光标/导引线" 账本作废 (清掉之后上面什么都没有)。
     */
    @Volatile
    internal var frontGeneration: Int = 0
        private set

    // ---- compose (GL 线程, 零分配) ----
    private val composeRegion = android.graphics.Region()
    private val composePath = Path()
    private val composeSrc = Rect()
    private val composeDst = RectF()
    private val composeBounds = Rect()
    private val rebakeHolesScratch = FloatArray(FrontBufferCompose.MAX_PACKET_RECTS * MAX_UI_CLIPS * 4)

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

    // ---- 动态吸附导引线 (GL 线程; 上一包画在前缓冲上的位置, 下一包先按足迹从备份层拷回) ----
    private val guidePainter = DynamicGuidePainter(resources.displayMetrics.density)
    private var prevGuideKind = DynamicGuidePainter.KIND_NONE
    private val prevGuideSeg = FloatArray(DynamicGuidePainter.SEG_SIZE)
    private val guideRestorePaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.SQUARE
        isAntiAlias = false
        isFilterBitmap = false
        xfermode = android.graphics.PorterDuffXfermode(PorterDuff.Mode.SRC)
    }
    private var guideRestoreFor: Bitmap? = null

    private fun invalidatePrevOverlay() {
        prevOverlayValid = false
        prevGuideKind = DynamicGuidePainter.KIND_NONE
        noteGuideOnScreen(false)
    }

    /**
     * 前缓冲上此刻是否画着导引线 (GL 线程写, UI 线程 onDraw 读)。为 true 时窗口即使已不按住也只画
     * 孔内那一半 ([FrontBufferHolePolicy.windowGuideHolesOnly]); 擦掉后通知窗口补画完整导引线。
     */
    @Volatile
    internal var guideOnScreen = false
        private set

    private fun noteGuideOnScreen(now: Boolean) {
        val was = guideOnScreen
        guideOnScreen = now
        if (FrontBufferHolePolicy.guideClearedNeedsRedraw(was, now, realInk?.held == true)) {
            realInk?.onFrontGuideCleared()
        }
    }
    private val density = resources.displayMetrics.density
    private val cursorPainter = HoldCursorPainter(density)

    // ---- H-1 孔洞 (GL 线程) ----
    // 命中孔洞的脏区一次画完 "墨 × (1 - 掩码)" (ComposeShader DST_OUT + SRC): 单次合成, 前缓冲上
    // 不存在"先贴墨再挖洞"的中间态 (单缓冲直接扫描输出, 两步写会被扫到半帧)。着色器按槽位图缓存。
    private val holeInkPaint = Paint().apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC)
        isFilterBitmap = false
        isAntiAlias = false
    }
    private val holeDstOutPaint = Paint().apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
        isFilterBitmap = false
        isAntiAlias = false
    }
    private var holeMaskBitmap: Bitmap? = null
    private var holeMaskShader: BitmapShader? = null
    private val holeSlotBitmaps = arrayOfNulls<Bitmap>(HOLE_SHADER_CACHE)
    private val holeSlotShaders = arrayOfNulls<BitmapShader>(HOLE_SHADER_CACHE)
    private val holeComposeShaders = arrayOfNulls<ComposeShader>(HOLE_SHADER_CACHE)
    private var holeCacheNext = 0
    private val holeLocalMatrix = Matrix()
    private val holeCursorBox = IntArray(4)

    // ---- 前缓冲表面几何 (UI 线程写; 渲染线程逐帧只读) ----
    @Volatile private var surfaceValid = false
    @Volatile private var surfaceW = 0
    @Volatile private var surfaceH = 0
    private val geomLocSelf = IntArray(2)
    private val geomLocTarget = IntArray(2)

    private val surfaceGeometryCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {
            // 尺寸要等 surfaceChanged; androidx 也是那时才分配缓冲。
        }

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            onSurfaceGeometry(valid = true, w = width, h = height)
        }

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            onSurfaceGeometry(valid = false, w = 0, h = 0)
        }
    }

    private fun onSurfaceGeometry(valid: Boolean, w: Int, h: Int) {
        val ctl = realInk
        val release = FrontBufferSurfaceGate.surfaceEventReleases(ctl?.held == true, surfaceW, surfaceH, valid, w, h)
        surfaceW = w
        surfaceH = h
        surfaceValid = valid
        if (!valid) {
            // 缓冲连同上面的墨已被 androidx 销毁, 账本作废 (没有可清的东西了)。
            hasContent = false
            hasInkContent = false
        }
        if (release && ctl != null) {
            // 旧缓冲 (含本笔已上屏的增量墨) 已丢弃: 松开, 窗口全量接管这一笔, 与关低延迟同图。
            if (ctl.held) {
                Log.i(TAG, "ev=surface_changed_release seq=${ctl.currentStrokeSeq}")
                ctl.releaseAndCatchUp()
            } else {
                ctl.release()
            }
            targetTouchView?.postInvalidate()
        }
        if (valid && ctl != null && w > 0 && h > 0) {
            ctl.onSurfaceChanged(w, h)
        }
    }

    /**
     * 落笔 (UI 线程): 前缓冲表面已就绪且与 [target] 完全重合, 真墨才能按住。零分配, 每笔一次。
     */
    internal fun bufferGeometryMatches(target: View): Boolean {
        if (!isRendererInitialized) return false
        getLocationOnScreen(geomLocSelf)
        target.getLocationOnScreen(geomLocTarget)
        return FrontBufferSurfaceGate.geometryMatches(
            surfaceValid, surfaceW, surfaceH,
            width, height, geomLocSelf[0], geomLocSelf[1],
            target.width, target.height, geomLocTarget[0], geomLocTarget[1],
        )
    }

    /** 按住期间 (任一线程, 逐帧): 缓冲仍有效且尺寸未变。只读几个 volatile 字段。 */
    internal val bufferStillValid: Boolean
        get() = FrontBufferSurfaceGate.stillValid(surfaceValid, surfaceW, surfaceH, width, height)

    init {
        // 设为完全透明与顶层覆盖
        setZOrderOnTop(true)
        holder.setFormat(PixelFormat.TRANSLUCENT)
        isFocusable = false
        isClickable = false

        initRendererIfNeeded()
    }

    /**
     * 按需初始化渲染器: 构造时一次, 从窗口 detach 后被重新 attach 时再一次。
     * 已初始化时直接返回, 因此可以安全重复调用。
     */
    private fun initRendererIfNeeded() {
        if (isRendererInitialized) return
        if (!FrontBufferProbe.isSupported() || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        initRenderer()
        // 必须排在 androidx 渲染器自己的回调之后注册 (回调按注册顺序执行): 走到这里时
        // 渲染器已按同一 (w, h) 建好/重建缓冲, 我们记下的就是缓冲的真实几何。
        if (isRendererInitialized) {
            holder.addCallback(surfaceGeometryCallback)
            val sf = holder.surface
            if (sf != null && sf.isValid) {
                val f = holder.surfaceFrame
                onSurfaceGeometry(valid = true, w = f.width(), h = f.height())
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // release() 之后又挂回窗口 (Compose 复用 / 页面回退): 重建渲染器, 否则真墨永久失效。
        if (released) {
            released = false
            initRendererIfNeeded()
        }
    }

    override fun onDetachedFromWindow() {
        // SurfaceView 的 surface 在 detach 之后才由系统销毁。若把 renderer 的释放留给
        // Compose onRelease, 它可能在 surfaceDestroyed 之后才跑, 最后一笔事务就会打到已经
        // 销毁的 SurfaceControl 上 —— 系统 libgui 空指针 (线上 d8f74afe88 / 7f68bfcd44 /
        // f047d7d9a2 / 8d5df3d6e2)。这里提前释放, 让收尾发生在 Surface 还有效的时候。
        release()
        super.onDetachedFromWindow()
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
                                invalidatePrevOverlay()
                            }
                            param.ink -> drawInkPacket(canvas, bufferWidth, bufferHeight, param)
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
                        frontGeneration++
                        clearStrokeLayerDirty()
                        invalidatePrevOverlay()
                    } finally {
                        resetInkQueue()
                        // 系统要求重画 (尺寸/可见性变化) 时前缓冲会被隐藏: 若这时还按住真墨,
                        // 立即在当前 GL 线程释放以阻断写墨，并将追赶排队到 UI 线程调度
                        val ctl = realInk
                        if (ctl != null && ctl.held) {
                            val seq = ctl.currentStrokeSeq
                            Log.i(TAG, "ev=multi_buffered_redraw_release seq=$seq")
                            ctl.release()
                            targetTouchView?.let { tv ->
                                tv.post {
                                    if (!ctl.held && ctl.currentStrokeSeq == seq) {
                                        ctl.armCatchUp()
                                    }
                                    tv.invalidate()
                                }
                            }
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
            Log.d(
                TAG,
                "CanvasFrontBufferedRenderer initialized successfully " +
                    "(graphics-core ${com.reverie.paint.BuildConfig.GRAPHICS_CORE_VERSION})",
            )
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to initialize CanvasFrontBufferedRenderer", t)
            frontRenderer = null
            isRendererInitialized = false
            noteGuideOnScreen(false)
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
     * 取回还在排队的真墨包: 把它的墨脏区位图矩形逐块抄进 [outBmpRects] 并返回块数; 没有排队包返回 -1
     * (有排队包但只有足迹时返回 0)。调用方把这些脏区并回积压一起重烤, 被顶掉的那一帧不会在按住期间
     * 留下断口; compose 据返回值 >= 0 得知那一包没上屏, 把光标/导引线账本回退到它之前。
     */
    fun reclaimQueuedInkRegions(outBmpRects: IntArray): Int {
        synchronized(submitLock) {
            val q = queuedPacket ?: return -1
            queuedPacket = null
            val n = minOf(q.inkCount, RealInkPresent.MAX_DISCRETE_RECTS, outBmpRects.size / 4)
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
    internal fun renderInkScreenRegions(
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
        holes: HoleMask? = null,
        holeHitBits: Int = 0,
        guideKind: Int = DynamicGuidePainter.KIND_NONE,
        guideSeg: FloatArray? = null,
        rebake: Boolean = false,
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
            packet.rebake = rebake
            packet.isClear = false
            packet.compose = false
            packet.decor = null
            packet.rectCount = n
            packet.inkCount = n
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
            packet.holeMask = if (holes?.bitmap != null) holes else null
            packet.holeHitBits = if (packet.holeMask != null) holeHitBits else 0
            if (guideSeg != null && (guideKind == DynamicGuidePainter.KIND_LINE || guideKind == DynamicGuidePainter.KIND_LINE_VP)) {
                packet.guideKind = guideKind
                System.arraycopy(guideSeg, 0, packet.guideSeg, 0, DynamicGuidePainter.SEG_SIZE)
            } else {
                packet.guideKind = DynamicGuidePainter.KIND_NONE
            }
            packet.inkSlotBusy = slotBusy
            return submitInkPacket(packet)
        } catch (t: Throwable) {
            packet.inkSlotBusy = null
            packet.inkBitmap = null
            packet.holeMask = null
            packet.holeHitBits = 0
            packet.guideKind = DynamicGuidePainter.KIND_NONE
            packet.uiClipCount = 0
            packet.inFlight.set(false)
            Log.w(TAG, "renderInkScreenRegions error: ${t.message}")
            return false
        }
    }

    /**
     * 提交一个 compose 包 (见 [FrontBufferPathPacket.compose])。[screenRects]/[bmpRects] 共 [count] 块,
     * 前 [inkCount] 块是墨脏区 (可以为 0: 抬笔收尾包只擦光标/导引线), 其余是足迹; [packXY] 是每块在
     * [screenBitmap] 里的左上角。光标/导引线是这一包要画的新状态 (cursorR <= 0 / KIND_NONE = 不画)。
     *
     * 返回 false 时 [slotBusy] 的所有权仍在调用方。
     */
    internal fun renderComposeRegions(
        screenBitmap: Bitmap,
        screenRects: IntArray,
        bmpRects: IntArray,
        packXY: IntArray,
        inkCount: Int,
        count: Int,
        pose: CanvasPose,
        cursorX: Float,
        cursorY: Float,
        cursorR: Float,
        cursorStyle: Int,
        symCursorX: FloatArray,
        symCursorY: FloatArray,
        symCursorCount: Int,
        guideKind: Int,
        guideSeg: FloatArray,
        uiClips: UiClipSnapshot,
        slotBusy: AtomicBoolean,
        canvasBounds: IntArray,
        docInner: IntArray,
        decor: FrontBufferDecor?,
        rebake: Boolean = false,
    ): Boolean {
        if (!isRendererInitialized) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        if (screenBitmap.isRecycled) return false
        val n = minOf(count, FrontBufferCompose.MAX_PACKET_RECTS)
        if (n <= 0) return false
        val packet = obtainInkPacket() ?: run {
            if (!discardQueuedInk()) return false
            obtainInkPacket() ?: return false
        }
        try {
            packet.ink = true
            packet.rebake = rebake
            packet.isClear = false
            packet.compose = true
            packet.rectCount = n
            packet.inkCount = inkCount.coerceIn(0, n)
            System.arraycopy(screenRects, 0, packet.screenRects, 0, n * 4)
            System.arraycopy(bmpRects, 0, packet.bmpRects, 0, n * 4)
            System.arraycopy(packXY, 0, packet.packXY, 0, n * 2)
            System.arraycopy(canvasBounds, 0, packet.canvasScreen, 0, 4)
            System.arraycopy(docInner, 0, packet.docInner, 0, 4)
            packet.slotOriginX = 0
            packet.slotOriginY = 0
            packet.inkBitmap = screenBitmap
            packet.pose = pose
            packet.cursorX = cursorX
            packet.cursorY = cursorY
            packet.cursorR = cursorR
            packet.cursorStyle = cursorStyle
            val sc = symCursorCount.coerceIn(0, 16)
            packet.symCursorCount = sc
            if (sc > 0) {
                System.arraycopy(symCursorX, 0, packet.symCursorX, 0, sc)
                System.arraycopy(symCursorY, 0, packet.symCursorY, 0, sc)
            }
            packet.wantsExtras = false
            packet.uiClipCount = uiClips.copyInto(packet.uiClipRects, MAX_UI_CLIPS)
            packet.holeMask = null
            packet.holeHitBits = 0
            if (guideKind == DynamicGuidePainter.KIND_LINE || guideKind == DynamicGuidePainter.KIND_LINE_VP) {
                packet.guideKind = guideKind
                System.arraycopy(guideSeg, 0, packet.guideSeg, 0, DynamicGuidePainter.SEG_SIZE)
            } else {
                packet.guideKind = DynamicGuidePainter.KIND_NONE
            }
            packet.decor = decor
            packet.inkSlotBusy = slotBusy
            return submitInkPacket(packet)
        } catch (t: Throwable) {
            packet.inkSlotBusy = null
            packet.inkBitmap = null
            packet.decor = null
            packet.compose = false
            packet.guideKind = DynamicGuidePainter.KIND_NONE
            packet.uiClipCount = 0
            packet.inFlight.set(false)
            Log.w(TAG, "renderComposeRegions error: ${t.message}")
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
        packet.decor = null
        packet.holeMask = null
        packet.holeHitBits = 0
        val slot = packet.inkSlotBusy ?: return
        packet.inkSlotBusy = null
        slot.set(false)
    }

    // ======================= 真墨绘制 (GL 线程) =======================

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun drawInkPacket(canvas: Canvas, bufferWidth: Int, bufferHeight: Int, param: FrontBufferPathPacket) {
        if (param.compose) {
            if (!FbGeomLog.timingEnabled) {
                drawComposePacket(canvas, bufferWidth, bufferHeight, param)
                return
            }
            val t0 = System.nanoTime()
            drawComposePacket(canvas, bufferWidth, bufferHeight, param)
            FbGeomLog.frontPresented(System.nanoTime() - t0)
            return
        }
        if (param.holeMask == null || !FbGeomLog.timingEnabled) {
            drawInkPacketImpl(canvas, bufferWidth, bufferHeight, param)
            return
        }
        val t0 = System.nanoTime()
        drawInkPacketImpl(canvas, bufferWidth, bufferHeight, param)
        FbGeomLog.frontPresented(System.nanoTime() - t0)
    }

    private fun drawInkPacketImpl(canvas: Canvas, bufferWidth: Int, bufferHeight: Int, param: FrontBufferPathPacket) {
        // 缓冲尺寸变了: 前缓冲整层作废, 备份层也必须丢掉重来。
        if (bufferWidth != strokeBufferW || bufferHeight != strokeBufferH) {
            strokeBufferW = bufferWidth
            strokeBufferH = bufferHeight
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            strokeLayerDirty.setEmpty()
            invalidatePrevOverlay()
            strokeLayer = null
            strokeCanvas = null
        }
        val reset = strokeResetPending
        val start = strokeStartPending
        strokeResetPending = false
        strokeStartPending = false
        // 清屏之后的首包: 备份层是上一笔的, 作废 (只清记过账的区域)。
        if (reset) clearStrokeLayerDirty()
        if (reset || start) invalidatePrevOverlay()

        val willDrawCursor = param.cursorR > 0f && param.cursorX.isFinite() && param.cursorY.isFinite() &&
            param.cursorStyle != 4
        val willDrawTip = probePredictedTip()
        val willDrawGuide = param.guideKind == DynamicGuidePainter.KIND_LINE ||
            param.guideKind == DynamicGuidePainter.KIND_LINE_VP
        val needsBackup = param.wantsExtras || willDrawCursor || willDrawTip || prevOverlayValid ||
            willDrawGuide || prevGuideKind != DynamicGuidePainter.KIND_NONE ||
            (param.holeMask != null && FbGeomLog.identityCheckEnabled)
        val layer = if (needsBackup) ensureStrokeLayer(bufferWidth, bufferHeight) else null
        if (layer != null && prevOverlayValid) erasePrevOverlay(canvas, layer, param)
        if (layer != null && prevGuideKind != DynamicGuidePainter.KIND_NONE) erasePrevGuide(canvas, layer, param)

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
                if (param.rebake) {
                    clearRebakeHoles(sc, param)
                }
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
            if (param.rebake) {
                clearRebakeHoles(canvas, param)
            }
            canvas.save()
            if (inkL <= inkR) clipOutUi(canvas, param, inkL, inkT, inkR, inkB)
            blitInk(canvas, param, bmp)
            canvas.restore()
            val hm = param.holeMask
            if (hm != null && layer != null) FbGeomLog.identityCheck(layer, hm.grid, param.screenRects, param.rectCount)
        }
        // 动态吸附导引线: 窗口里画在墨之上、光标之下 (CanvasTouchView 第 2.5 段), 这里同序。
        // 只画孔洞外与 UI 避让区外 (孔内/避让区内由窗口画), 不进备份层 (下一包从备份层按足迹拷回)。
        if (willDrawGuide && layer != null) {
            drawGuide(canvas, param, bufferWidth, bufferHeight)
            prevGuideKind = param.guideKind
            System.arraycopy(param.guideSeg, 0, prevGuideSeg, 0, DynamicGuidePainter.SEG_SIZE)
            noteGuideOnScreen(true)
        } else {
            prevGuideKind = DynamicGuidePainter.KIND_NONE
            noteGuideOnScreen(false)
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

    /**
     * compose 包 (GL 线程): 每块 SRC 贴烤好的 "棋盘格 + 瓦片" (文档外的足迹部分是透明), 然后只在
     * 这些矩形的并集里按窗口同一顺序、同一函数、同一变换重画覆盖层 —— 像素网格 (窗口 drawCanvas 里
     * 瓦片之后) → 动态导引线 (第 2.5 段) → 光标 (第 3 段) → CanvasOverlay (蚂蚁线不在 compose 里,
     * 绘图辅助线)。矩形外前缓冲上的像素不动: 它们是更早的包画的, 状态没变 (光标/导引线变了的新旧
     * 足迹都在这一包的矩形里)。全程 UI 避让区 clipOut (避让区内由窗口画)。
     */
    private fun drawComposePacket(canvas: Canvas, bufferWidth: Int, bufferHeight: Int, param: FrontBufferPathPacket) {
        if (bufferWidth != strokeBufferW || bufferHeight != strokeBufferH) {
            strokeBufferW = bufferWidth
            strokeBufferH = bufferHeight
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            strokeLayerDirty.setEmpty()
            invalidatePrevOverlay()
            strokeLayer = null
            strokeCanvas = null
        }
        val reset = strokeResetPending
        strokeResetPending = false
        strokeStartPending = false
        if (reset) clearStrokeLayerDirty()
        // 模式按笔画定 (落笔时), compose 包之前不会有同一笔的 H-min 包; H-min 的光标账本在这里作废。
        prevOverlayValid = false
        prevGuideKind = DynamicGuidePainter.KIND_NONE
        lastTipValid = false

        val bmp = param.inkBitmap
        val n = param.rectCount
        if (bmp == null || bmp.isRecycled || n <= 0) return
        val sr = param.screenRects
        var l = Int.MAX_VALUE
        var t = Int.MAX_VALUE
        var r = Int.MIN_VALUE
        var b = Int.MIN_VALUE
        for (i in 0 until n) {
            val s = i * 4
            if (sr[s] < l) l = sr[s]
            if (sr[s + 1] < t) t = sr[s + 1]
            if (sr[s + 2] > r) r = sr[s + 2]
            if (sr[s + 3] > b) b = sr[s + 3]
        }
        if (param.rebake) {
            clearRebakeHoles(canvas, param)
        }
        canvas.save()
        clipOutUi(canvas, param, l.toFloat(), t.toFloat(), r.toFloat(), b.toFloat())
        for (i in 0 until n) {
            val s = i * 4
            val w = sr[s + 2] - sr[s]
            val h = sr[s + 3] - sr[s + 1]
            if (w <= 0 || h <= 0) continue
            val px = param.packXY[i * 2]
            val py = param.packXY[i * 2 + 1]
            composeSrc.set(px, py, px + w, py + h)
            composeDst.set(sr[s].toFloat(), sr[s + 1].toFloat(), sr[s + 2].toFloat(), sr[s + 3].toFloat())
            canvas.drawBitmap(bmp, composeSrc, composeDst, screenInkPaint)
        }
        // 覆盖层只画在这一包更新过的像素里 (整数边的矩形并集, AA 裁剪在整数边上没有部分覆盖)。
        val region = composeRegion
        region.setEmpty()
        for (i in 0 until n) {
            val s = i * 4
            if (sr[s + 2] > sr[s] && sr[s + 3] > sr[s + 1]) {
                region.op(sr[s], sr[s + 1], sr[s + 2], sr[s + 3], android.graphics.Region.Op.UNION)
            }
        }
        val guideOn = param.guideKind == DynamicGuidePainter.KIND_LINE ||
            param.guideKind == DynamicGuidePainter.KIND_LINE_VP
        if (!region.isEmpty) {
            if (region.isRect) {
                region.getBounds(composeBounds)
                canvas.clipRect(composeBounds)
            } else {
                composePath.rewind()
                region.getBoundaryPath(composePath)
                canvas.clipPath(composePath)
            }
            val decor = param.decor
            val di = param.docInner
            val docOk = di[2] > di[0] && di[3] > di[1]
            val under = decor?.under
            if (under != null && docOk) {
                canvas.save()
                canvas.clipRect(di[0], di[1], di[2], di[3])
                canvas.drawPicture(under)
                canvas.restore()
            }
            if (guideOn) guidePainter.draw(canvas, param.guideKind, param.guideSeg)
            if (param.cursorR > 0f && param.cursorX.isFinite() && param.cursorY.isFinite() && param.cursorStyle != 4) {
                cursorPainter.drawAll(canvas, param.cursorX, param.cursorY, param.cursorR, param.cursorStyle,
                    param.symCursorX, param.symCursorY, param.symCursorCount)
            }
            val over = decor?.over
            if (over != null && docOk) {
                canvas.save()
                canvas.clipRect(di[0], di[1], di[2], di[3])
                canvas.drawPicture(over)
                canvas.restore()
            }
        }
        canvas.restore()
        noteGuideOnScreen(guideOn)
        if (FbGeomLog.identityCheckEnabled) {
            FbGeomLog.composeIdentityCheck(bmp, param.packXY, sr, n, param.docInner)
        }
    }

    /**
     * 重烤包清孔 (GL 线程, 零分配):
     * 在 clipOutUi 与 SRC blit 之前, 对每个 (包矩形 ∩ 当前 uiClips) 执行 CLEAR 清除旧墨。
     */
    private fun clearRebakeHoles(canvas: Canvas, param: FrontBufferPathPacket) {
        val holeCount = computeRebakeHoles(
            param.screenRects,
            param.rectCount,
            param.uiClipRects,
            param.uiClipCount,
            rebakeHolesScratch,
        )
        for (h in 0 until holeCount) {
            val o = h * 4
            canvas.save()
            canvas.clipRect(
                rebakeHolesScratch[o],
                rebakeHolesScratch[o + 1],
                rebakeHolesScratch[o + 2],
                rebakeHolesScratch[o + 3],
            )
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            canvas.restore()
        }
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
            val holePaint = if (param.holeHitBits and (1 shl i) != 0) holeInkPaintFor(param, bitmap) else null
            if (axisAligned) {
                if (holePaint != null) {
                    canvas.drawRect(blitDst, holePaint)
                } else {
                    canvas.drawBitmap(bitmap, blitSrc, blitDst, screenInkPaint)
                }
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
            if (holePaint != null) {
                canvas.drawRect(blitDst, holePaint)
            } else {
                canvas.drawBitmap(bitmap, blitSrc, blitDst, screenInkPaint)
            }
            canvas.restore()
        }
    }

    /**
     * 命中孔洞那几块的贴图 Paint: 着色 = 槽位图 (平移到屏幕原点) DST_OUT 掩码, SRC 写入。
     * 屏幕坐标与槽位像素整数对齐、不过滤, 非孔洞像素与 drawBitmap 逐字节相同, 孔洞像素写 0。
     * 掩码/槽位图不变时复用着色器 (槽位四缓冲轮换, 缓存 4 份), 热路径只改局部矩阵。
     */
    private fun holeInkPaintFor(param: FrontBufferPathPacket, bitmap: Bitmap): Paint? {
        val mask = param.holeMask?.bitmap ?: return null
        if (mask.isRecycled) return null
        if (holeMaskBitmap !== mask) {
            holeMaskBitmap = mask
            holeMaskShader = BitmapShader(mask, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            for (k in 0 until HOLE_SHADER_CACHE) {
                holeSlotBitmaps[k] = null
                holeSlotShaders[k] = null
                holeComposeShaders[k] = null
            }
        }
        var idx = -1
        for (k in 0 until HOLE_SHADER_CACHE) {
            if (holeSlotBitmaps[k] === bitmap) {
                idx = k
                break
            }
        }
        if (idx < 0) {
            idx = holeCacheNext
            holeCacheNext = (holeCacheNext + 1) % HOLE_SHADER_CACHE
            val ink = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            holeSlotBitmaps[idx] = bitmap
            holeSlotShaders[idx] = ink
            holeComposeShaders[idx] = ComposeShader(ink, holeMaskShader!!, PorterDuff.Mode.DST_OUT)
        }
        holeLocalMatrix.setTranslate(param.slotOriginX.toFloat(), param.slotOriginY.toFloat())
        holeSlotShaders[idx]!!.setLocalMatrix(holeLocalMatrix)
        holeInkPaint.shader = holeComposeShaders[idx]
        return holeInkPaint
    }

    private fun isAxisAligned(pose: CanvasPose): Boolean = FrontBufferCompose.isAxisAligned(pose)

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
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun drawGuide(canvas: Canvas, param: FrontBufferPathPacket, w: Int, h: Int) {
        canvas.save()
        clipOutUi(canvas, param, 0f, 0f, w.toFloat(), h.toFloat())
        val mask = param.holeMask?.bitmap
        if (mask != null && !mask.isRecycled) {
            guidePainter.drawMasked(canvas, param.guideKind, param.guideSeg, mask, PorterDuff.Mode.DST_OUT)
        } else {
            guidePainter.draw(canvas, param.guideKind, param.guideSeg)
        }
        canvas.restore()
    }

    /**
     * 擦掉上一包的导引线: 沿它的足迹 (比最粗的光晕再宽 2px, 方头, 无抗锯齿) 从备份层 SRC 拷回;
     * 灭点圆按方框拷回。备份层孔内本就是 0, UI 避让区照旧避开。只碰足迹像素, 不整屏拷。
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun erasePrevGuide(canvas: Canvas, layer: Bitmap, param: FrontBufferPathPacket) {
        if (guideRestoreFor !== layer) {
            guideRestoreFor = layer
            guideRestorePaint.shader = BitmapShader(layer, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        }
        val seg = prevGuideSeg
        canvas.save()
        clipOutUi(canvas, param, 0f, 0f, layer.width.toFloat(), layer.height.toFloat())
        guideRestorePaint.style = Paint.Style.STROKE
        guideRestorePaint.strokeWidth = guidePainter.linePad * 2f
        canvas.drawLine(seg[0], seg[1], seg[2], seg[3], guideRestorePaint)
        if (prevGuideKind == DynamicGuidePainter.KIND_LINE_VP) {
            val hh = guidePainter.vpHalf
            guideRestorePaint.style = Paint.Style.FILL
            canvas.drawRect(seg[4] - hh, seg[5] - hh, seg[4] + hh, seg[5] + hh, guideRestorePaint)
        }
        canvas.restore()
        prevGuideKind = DynamicGuidePainter.KIND_NONE
    }

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
        invalidatePrevOverlay()
        return created
    }

    /** 内存告警时释放备份层 (不在运笔/追赶时)。只置空引用, 避免在 UI 线程 recycle GL 线程独占位图。 */
    fun trimStrokeLayer() {
        if (realInk?.held == true || realInk?.catchUpPending == true) return
        strokeLayer = null
        strokeCanvas = null
        strokeLayerDirty.setEmpty()
        invalidatePrevOverlay()
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
        val mask = param.holeMask
        val maskBmp = mask?.bitmap
        if (mask != null && maskBmp != null && !maskBmp.isRecycled &&
            HoldCursorPainter.bounds(param.cursorX, param.cursorY, cursorPainter.half(r), param.symCursorX,
                param.symCursorY, param.symCursorCount, holeCursorBox) &&
            mask.grid.anyExact(holeCursorBox[0], holeCursorBox[1], holeCursorBox[2], holeCursorBox[3])
        ) {
            // H-3: 光标压到孔洞 —— 离屏层里画完再挖掉孔洞, 一次合成上屏 (孔洞内的光标由主窗口画)。
            val b = holeCursorBox
            canvas.saveLayer(b[0].toFloat(), b[1].toFloat(), b[2].toFloat(), b[3].toFloat(), null)
            cursorPainter.drawAll(canvas, param.cursorX, param.cursorY, r, param.cursorStyle,
                param.symCursorX, param.symCursorY, param.symCursorCount)
            canvas.drawBitmap(maskBmp, 0f, 0f, holeDstOutPaint)
            canvas.restore()
            return
        }
        cursorPainter.drawAll(canvas, param.cursorX, param.cursorY, r, param.cursorStyle,
            param.symCursorX, param.symCursorY, param.symCursorCount)
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
        if (realInk?.catchUpPending == true) {
            // 追赶尚未完成, 保护真墨不被提前清空 (只清预览类内容)
            if (inkOnly || hasInkContent) return
        }
        strokeResetPending = true
        if (!hasContent) return
        val ink = hasInkContent
        hasContent = false
        hasInkContent = false
        synchronized(submitLock) { releaseQueuedLocked() }
        frontGeneration++
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
     *
     * 可重入: `onDetachedFromWindow` 与 Compose `onRelease` 都会调用, 先到先执行。
     * 之后若视图重新 attach, [onAttachedToWindow] 会重建渲染器。
     */
    fun release() {
        if (released) return
        released = true
        // 松开可能还按住的真墨: 渲染器即将销毁, 留着 held=true 会让下一笔误以为前缓冲还在
        realInk?.cancelCatchUp()
        realInk?.release()
        holder.removeCallback(surfaceGeometryCallback)
        surfaceValid = false
        surfaceW = 0
        surfaceH = 0
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
        noteGuideOnScreen(false)
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
