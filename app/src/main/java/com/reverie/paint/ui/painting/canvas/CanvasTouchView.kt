/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.canvas

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Build
import android.os.SystemClock
import android.view.MotionEvent
import android.view.PointerIcon
import android.view.View
import androidx.compose.runtime.MutableState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import com.reverie.paint.BuildConfig
import com.reverie.paint.R
import com.reverie.paint.core.*
import com.reverie.paint.core.stylus.FrontBufferProbe
import com.reverie.paint.core.stylus.UniversalKalmanPredictor
import com.reverie.paint.model.*
import com.reverie.paint.ui.theme.parseColor
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.view.WindowManager
import com.oplusos.vfxsdk.forecast.MotionPredictor as OplusMotionPredictor
import com.oplusos.vfxsdk.forecast.TouchPointInfo as OplusTouchPointInfo
import com.reverie.paint.perf.PerfHud
import com.reverie.paint.ui.painting.brush.BrushTipDecoder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/** 单帧最多绘制的像素网格线条数, 超过则跳过网格 (防极端缩放下的掉帧) */
private const val MAX_VISIBLE_GRID_LINES = 6000

/** 对称绘制最多需要的镜像分支数 (径向对称 7 个 + 主笔迹) */
private const val MAX_MIRROR_BRANCHES = 8

/**
 * C3-2 · 场通路的文档像素预算上限(源纹理 + 场纹理各留一份, 4M px 文档 ≈ 16MB + 8MB)。
 *
 * 注: 真正生效的预算由 [fieldPathBudgetPx] 动态给出 —— 它再叠加"堆上限 / 每像素 32B"的
 * 安全约束(见 [MemoryBudget])。这里的常量只是历史硬上限, 不是最终值。
 */
private const val FIELD_PATH_MAX_PX = 4L * 1024L * 1024L

/** C3-2 · 低内存设备的场通路预算(压到 1/4; 超预算直接走经典路径, 宁可慢也不逼近 OOM)。 */
private const val FIELD_PATH_MAX_PX_LOW_RAM = 1L * 1024L * 1024L

/**
 * 经典路径"单帧多补点一次提交"的批缓冲步长: `(fx, fy, tx, ty, strength, mode)` ——
 * 与 JNI `liquifyDabs` 同序(见 [`LiquifyPath.packDab`], 布局由单测守门)。
 */
private const val LIQUIFY_BATCH_STRIDE = LiquifyPath.DAB_STRIDE

/**
 * 引擎队列积压背压阈值: `pendingCoreOps` 超过它时, 本帧不再推进新的液化补点。
 *
 * 高压拖动时引擎每帧要跑多个补点 + 一次渲染; 若输入侧继续无条件推进, 任务队列会越积越长,
 * 用户观感就是"越拖越卡、抬笔后画面还在继续变形"(无响应)。宁可追赶慢一点, 也不让队列雪崩。
 */
private const val LIQUIFY_ENGINE_BACKLOG_LIMIT = 8

/** B4 · 静止降频: 交互结束后多久把帧率请求降回 [IDLE_FRAME_RATE_HZ]。 */
private const val IDLE_DOWNCLOCK_MS = 1500L

/** B4 · 静止时请求的帧率(Hz): 不画的时候没必要把屏幕钉在 144Hz 上。 */
private const val IDLE_FRAME_RATE_HZ = 60f

/** Phase 5 · C3-2: 本地补点列表的步长(px, py, nx, ny, mode, strength, size)。 */
private const val FIELD_DAB_STRIDE = 7


/**
 * 画世界 / Procreate 架构原生触控引擎 (CanvasTouchView)
 *
 * 核心架构设计：
 * 1. 硬件级光标渲染：在 View.onDraw 中直接通过 GPU Canvas 绘制笔刷光标环，彻底消除 Compose 每秒 480 次重组开销；
 * 2. 手/笔职责分流 (Huashijie Model)：手写笔落笔负责 100% 绘画，手指负责 100% 画布手势导航 (单指平移 / 双指缩放旋转 / 双指轻点撤销 / 长按吸色)；
 * 3. 悬停抗干扰：悬停事件由硬件独立驱动，绝不独占事件分发，手指手势与空中悬停 100% 并发无阻碍；
 * 4. 连续几何变换：跨碎片会话保持 + 两指近邻欧氏距离配对，0 延迟、0 门槛满帧响应。
 */
class CanvasTouchView(context: Context) : View(context) {

    var vm: PaintViewModel? = null
    var tool: Tool = Tool.BRUSH
        set(value) {
            if (field != value) {
                field = value
                clearPredictionState(triggerInvalidate = true)
            }
        }
    var tfState: TransformState? = null
    var docBitmap: Bitmap? = null

    private var quickShapeCandidate: QuickShapeResult? = null
    private val quickShapeHold = Runnable {
        val v = vm
        val capture = v?.quickShapeCapture
        if (v != null && capture != null && strokeStarted && v.quickShapeEnabled &&
            effTool() == Tool.BRUSH && capture.travelled >= 48f * density &&
            SystemClock.uptimeMillis() - capture.lastMovementMs >= 650L) {
            quickShapeCandidate = capture.recognize(v.quickShapeArcEnabled, v.quickShapeRelaxedEnabled,
                v.quickShapeQuadrilateralEnabled, v.quickShapeCurveEnabled)
            if (quickShapeCandidate != null) {
                performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                v.showActionToast(R.string.quick_shape_ready, R.drawable.ic_line)
            }
        }
    }

    private var cachedDriver: com.reverie.paint.core.stylus.StylusDriver? = null

    // S Pen 悬停期间侧键状态追踪: 仅在按钮按下/释放边沿把 hover 事件喂给驱动层,
    // 避免每次悬停移动都走按钮状态机 (热路径零分配: 只做位比较, 无对象创建)。
    private var lastHoverButtonState = 0

    // 纸张摩擦音效的速度追踪 (文档坐标); lastSoundTimeMs == 0L 表示笔画尚无历史点
    private var lastSoundDocPos = Offset.Zero
    private var lastSoundTimeMs = 0L

    // 侧键按住=临时橡皮 (Samsung Notes 语义): 仅在笔接触的事件流中更新, 抬笔后由下一次落笔重判
    private var tempEraseActive = false

    // 物理橡皮擦末端 (TOOL_TYPE_ERASER, 施德楼/Surface/Wacom EMR 笔尾): 倒转笔身时直接生效为橡皮
    private var physicalEraserActive = false

    /** 绘画路径的生效工具: 侧键按住或物理橡皮擦末端时强制橡皮, 其余时刻跟随 UI 工具。 */
    private fun effTool(): Tool = if (tempEraseActive || physicalEraserActive) Tool.ERASER else tool

    private fun getOrCreateStylusDriver(): com.reverie.paint.core.stylus.StylusDriver? {
        val cached = cachedDriver
        if (cached != null) return cached
        val v = vm ?: return null
        val driver = v.getOrCreateStylusDriver(context)
        cachedDriver = driver
        return driver
    }

    var viewW: Int = 1
    var viewH: Int = 1
    var canvasZoom: Float = 1f
    var canvasRotation: Float = 0f
    var canvasPanX: Float = 0f
    var canvasPanY: Float = 0f
    var canvasFitScale: Float = 1f
    /**
     * 视图翻转 (仅镜像显示, 不触碰像素)。与 canvasZoom/Rotation/Pan 同属视图状态:
     * 开启时位图绕画布中心镜像绘制, 坐标反算走 [CanvasViewTransform] 的 signX/signY,
     * 于是"看到哪就画到哪"依旧成立 —— 而完全翻转要逐层镜像像素, 图层多时会卡。
     */
    var canvasFlipX: Boolean = false
    var canvasFlipY: Boolean = false

    var onTransform: ((zoom: Float, rotation: Float, panX: Float, panY: Float) -> Unit)? = null

    /** 旋转进入 90° 倍数吸附区时回调 (视觉反馈: 高亮角度 HUD), 参数为当前吸附后角度 */
    var onRotationSnap: ((rotation: Float) -> Unit)? = null
    var onTextRequested: ((x: Float, y: Float) -> Unit)? = null
    var onPolyPoint: ((Offset) -> Unit)? = null
    var onPolyPopPoint: (() -> Unit)? = null
    private var isPolyPointPendingOnTouch = false
    private var shapeCreatedOnCurrentTouch = false
    var onCropRect: ((Rect?) -> Unit)? = null

    var liveShapeStart: MutableState<Offset?>? = null
    var liveShapeEnd: MutableState<Offset?>? = null
    var livePressure: MutableState<Float>? = null
    var measureStart: MutableState<Offset?>? = null
    var measureEnd: MutableState<Offset?>? = null
    /** 测量工具当前激活的手柄: 0 表示起点, 1 表示终点, -1 表示新建或未命中手柄 */
    private var activeMeasureHandle: Int = -1
    var wandFlash: MutableState<Offset?>? = null
    var pickerActive: MutableState<Boolean>? = null
    var pickerScreenPos: MutableState<Offset>? = null
    var pickerInitialColor: MutableState<Color>? = null
    var pickerCurrentColor: MutableState<Color>? = null
    var liveSelectionPath: MutableState<Path?>? = null
    var cursorScreenPos: MutableState<Offset?>? = null
    var isCursorHovering: MutableState<Boolean>? = null
    var isCursorTouching: MutableState<Boolean>? = null

    var fillTolerance: Int = 24
    var gradientType: Int = 0
    var liquifyStrength: Float = 0.9f
    var liquifyHardness: Float = 0.5f
    var liquifyBrushSize: Float = 60f

    /** True while any full-screen overlay panel is open (see isHoverOverUi). */
    @Volatile var overlayPanelsOpen: Boolean = false
    var liquifyMode: Int = 0
    var frontBufferOverlay: FrontBufferPreviewOverlay? = null

    // 滤镜实时调节与手势驱动
    var filterSessionActive: Boolean = false
    var onFilterSlideDelta: ((Float) -> Unit)? = null
    var onFilterHoldingCompare: ((Boolean) -> Unit)? = null
    private var isFilterComparing = false
    private var filterTouchStartX = 0f
    private var filterTouchStartY = 0f
    private var isFilterDragging = false
    private val filterLongPressRunnable = Runnable {
        if (filterSessionActive && !isFilterDragging && !isTransformActive && fingerCount <= 1) {
            isFilterComparing = true
            onFilterHoldingCompare?.invoke(true)
        }
    }

    private val density = context.resources.displayMetrics.density

    // 触控交互锁 (手指接触期间，阻止外部 Compose 状态回冲覆盖)
    var isInteracting = false
    var isTransformActive = false
    var isPinchMotion = false

    /**
     * 双指专用"旋转已成运动"标记。
     *
     * 只用于抑制双指轻点撤销 (及双指连续撤销), **不参与三指重做的门控** —— 三指重做继续
     * 只看 [isPinchMotion] (即改动前的三项旧判据), 因此三指路径逐点等价; 同时"双指落指
     * 阶段的抖动"也不会再把随后落下的第三指轻点重做挡掉。
     */
    private var isTwoFingerRotation = false

    // 本地硬件光标状态 (0 Compose 开销)
    /** 局部失效时并入标尺块(debug 标尺专用; 正式版 [PerfHud.fillHudBounds] 恒 false)。 */
    private val hudBoundsScratch = android.graphics.Rect()

    /** B4 · 静止降频: 当前是否已请求"面板最高帧率", 以及静止计时。 */
    private var frameRateHigh = true
    private val idleDownclockRunnable = Runnable { downclockWhenIdle() }

    // 热路径零分配 (AGENTS.md §4.4): 同 predictedScreenX/Y, 可空 Offset 拆双 Float + NaN。
    // 悬停/触控每事件赋值, 原 Offset? 写法每次装箱。
    private var localCursorX: Float = Float.NaN
    private var localCursorY: Float = Float.NaN
    private val hasLocalCursorPos: Boolean
        get() = !localCursorX.isNaN() && !localCursorY.isNaN()

    private fun setLocalCursorPos(x: Float, y: Float) {
        localCursorX = x
        localCursorY = y
    }

    private fun clearLocalCursorPos() {
        localCursorX = Float.NaN
        localCursorY = Float.NaN
    }
    private var localIsHovering = false
    private var localIsTouching = false
    private var localPressure = 1f

    // 硬件加速直出渲染 Paint
    var checkerboardPaint: Paint? = null
    private val pixelGridPaint = Paint().apply {
        style = Paint.Style.STROKE
    }
    private val directBitmapPaint = Paint().apply {
        isFilterBitmap = true
        isDither = true
    }

    // 光标绘制 Paint (超细精细发丝线条)
    private val cursorPaintBlack = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.6f * density
        color = android.graphics.Color.argb(130, 0, 0, 0)
    }
    private val cursorPaintWhite = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 0.9f * density
        color = android.graphics.Color.argb(240, 255, 255, 255)
    }
    private val crosshairPaintBlack = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * density
        color = android.graphics.Color.argb(120, 0, 0, 0)
    }
    private val crosshairPaintWhite = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 0.7f * density
        color = android.graphics.Color.argb(255, 255, 255, 255)
    }

    // 动态辅助吸附导引线 Paint
    private val dynamicGuideGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.5f * density
        color = android.graphics.Color.argb(70, 0x5A, 0x6E, 0x8A) // Morandi misty blue glow
    }
    private val dynamicGuideLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.4f * density
        color = android.graphics.Color.argb(220, 0x78, 0x88, 0x9F) // Morandi accentHi crisp line
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(6f * density, 4f * density), 0f)
    }
    private val vpTargetGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
        color = android.graphics.Color.argb(140, 0x5A, 0x6E, 0x8A)
    }
    private val vpTargetCorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.8f * density
        color = android.graphics.Color.argb(255, 0x78, 0x88, 0x9F)
    }
    private val vpTargetCenterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = android.graphics.Color.WHITE
    }

    var drawingGuidePanelOpen = false
    private var currentStrokeDocPos = Offset.Zero
    private var isSymmetryUndoMacroOpen = false

    private fun safeBeginSymmetryUndoMacro() {
        if (!isSymmetryUndoMacroOpen) {
            vm?.runCore(render = false) {
                ReverieCoreBridge.beginUndoMacro("Symmetry Stroke")
            }
            isSymmetryUndoMacroOpen = true
        }
    }

    private fun safeEndSymmetryUndoMacro() {
        if (isSymmetryUndoMacroOpen) {
            vm?.runCore(render = false) {
                ReverieCoreBridge.endUndoMacro()
            }
            isSymmetryUndoMacroOpen = false
        }
    }

    // 绘制状态 (手写笔专属)
    private var strokeStarted = false
    private var firstDocPos = Offset.Zero
    private var shapeEndDocPos = Offset.Zero
    private var previousSinglePos = Offset.Zero
    private var priorStrokeScreenPos = Offset.Zero
    private val lassoPoints = mutableListOf<Offset>()
    private var lastLassoPreviewNs = 0L
    // Phase 5 · C3-2 (docs/LIQUIFY-C3-FIELD-PLAN.md §3): 场通路的"拖动期零引擎解算"状态。
    // 拖动期不调 liquify()、不收 rebase、不物化 —— 位移只在 GPU 场里累加; 抬笔回读一次落盘。
    /** 本段手势是否走"GPU 场一次性落盘"。 */
    private var liquifyFieldGesture = false

    /** 本段手势的补点(7 float/补点, 见 [FIELD_DAB_STRIDE]); 抬笔回读失败时用它重放给引擎。 */
    private var liquifyDabBuf = FloatArray(0)
    private var liquifyDabCount = 0

    /**
     * 经典路径的**批量提交**缓冲(复用, 每补点 [LIQUIFY_BATCH_STRIDE] 个 float)。
     *
     * 以前每个补点一次 `v.liquify()` = 一次 `runCore` post + 一次 `scheduleRender`; 大笔刷
     * 快拖时一帧 8~16 个补点就有 8~16 个任务压进引擎队列。现在一帧只 post 一次, 由引擎线程
     * 内循环调用 JNI(顺序与语义逐点不变), 队列长度与渲染调度次数都降到 1/8~1/16。
     */
    private var liquifyBatchBuf = FloatArray(LIQUIFY_BATCH_STRIDE * 64)

    /** 受影响文档矩形(各补点影响圆的并集): 覆盖层绘制范围 + 抬笔回读范围都用它。 */
    private var lqAffectedL = Float.MAX_VALUE
    private var lqAffectedT = Float.MAX_VALUE
    private var lqAffectedR = -Float.MAX_VALUE
    private var lqAffectedB = -Float.MAX_VALUE

    /**
     * 相位 8: 上一次推给覆盖层的绘制矩形(整数, 文档坐标), null = 还没推过。
     *
     * 与推给引擎的"预览基座"矩形是**同一个值**(见 [LiquifyPath.previewRect]) —— 两者逐像素
     * 一致才不会有"被挖掉却没人补"(露背景线框)或"没被挖却在叠加"(深色描边)的差值环。
     */
    private var liquifyDrawRect: IntArray? = null
    private var liquifyWholeLayerRect: IntArray? = null

    /** 基座推进策略: 只跟到覆盖层"已上屏"的那一帧, 永远不领先(见 [LiquifyPreviewBasePolicy])。 */
    private val liquifyBasePolicy = LiquifyPreviewBasePolicy()

    /** 读取"覆盖层已上屏矩形"的复用缓冲(热路径零分配)。 */
    private val liquifyCommittedScratch = IntArray(4)

    // Phase 3A/3B: 液化交互态会话(latest-state-wins 状态机 + backlog 计数), 取代原先散落的
    // liquifyPrevPos / liquifyPendingTo / liquifyInputSinceFlush / liquifyMaxDabsPerFlush 字段。
    private val liquifySession = LiquifyInteractionSession()
    private var liquifyFlushPosted = false
    private val liquifyFlushRunnable = Runnable { flushLiquifyPending() }
    private var liquifyHoldTimeMs = 0L
    private val liquifyHoldRunnable = object : Runnable {
        override fun run() {
            val v = vm ?: return
            if (!strokeStarted || effTool() != Tool.LIQUIFY || liquifyMode !in 1..4 ||
                !isAttachedToWindow || !hasWindowFocus()) return
            val now = android.os.SystemClock.uptimeMillis()
            val dt = (now - liquifyHoldTimeMs).coerceIn(0L, 40L)
            liquifyHoldTimeMs = now
            if (!liquifySession.hasPending && (lastLiquifyEventTimeMs == 0L || now - lastLiquifyEventTimeMs >= 40L) &&
                v.pendingCoreOps.get() <= LIQUIFY_ENGINE_BACKLOG_LIMIT && dt > 0L && liquifyPressureFactor() > 0f) {
                liquifyFlushNow(v, forceFull = false)
                val x = liquifySession.renderedX
                val y = liquifySession.renderedY
                val strength = liquifyStrength * liquifyPressureFactor() * dt / 1000f
                if (liquifyFieldGesture) {
                    recordLiquifyFieldDab(x, y, x, y, liquifyMode, strength)
                    v.recordLiquifyDab(x, y, x, y, liquifyMode, strength)
                    LiquifyGlesPreview.pushDab(x, y, x, y, liquifyMode, strength, liquifyBrushSize)
                    LiquifyGlesPreview.publishDabs()
                    val rect = liquifyWholeLayerRect ?: LiquifyPath.previewRect(
                        lqAffectedL, lqAffectedT, lqAffectedR, lqAffectedB, v.docWidth, v.docHeight,
                    )
                    if (rect != null) {
                        liquifyDrawRect = rect
                        LiquifyGlesPreview.pushDrawRect(
                            rect[0].toFloat(), rect[1].toFloat(), rect[2].toFloat(), rect[3].toFloat(),
                        )
                    }
                    postInvalidateOnAnimation()
                } else v.liquify(x, y, x, y, liquifyMode, strength.toDouble())
            }
            postOnAnimation(this)
        }
    }

    // Phase 6(手感/误触): 液化手势是否进行中 —— 多指误触保护与端到端延迟推导都靠它
    private val isLiquifyGestureActive: Boolean
        get() = strokeStarted && effTool() == Tool.LIQUIFY

    /** Phase 6: 最近一次液化输入事件的时间戳(event.eventTime, uptime 基准), 用于延迟读数。 */
    private var lastLiquifyEventTimeMs = 0L

    /** Phase 6: 本段液化手势是否有笔压输入(手写笔/橡皮端) —— 手指路径不参与压力调制。 */
    private var liquifyPressureActive = false
    // Liquify V2 · Phase 2 (docs/LIQUIFY-V2-PLAN.md §4): 覆盖层局部失效所需的"光标环本帧位置"
    // (屏幕坐标)。环由本类 onDraw 画在同一张画布上, 因此局部重绘必须把环的前后位置一并失效,
    // 否则环的旧位置会留下残影。全部只在 UI 线程读写。
    private var lqRingValid = false
    private var lqRingCx = 0f
    private var lqRingCy = 0f
    private var lqRingR = 0f
    // docToScreen 的输出缓冲(必须是 FloatArray; 不能复用整型的 boundsScratch)
    private val lqPointScratch = FloatArray(2)
    private val lqInvalidateRunnable = Runnable { scheduleLiquifyInvalidate() }
    private var smoothedPressure = 0.8f

    // 手写笔传感器状态 (倾斜角与朝向)
    private var touchTiltX: Double = 0.0
    private var touchTiltY: Double = 0.0
    private var touchRotation: Double = 0.0

    private fun updateStylusSensors(
        event: MotionEvent,
        pointerIndex: Int,
        isStylus: Boolean,
        historyPos: Int = -1,
    ) {
        if (!isStylus) {
            touchTiltX = 0.0
            touchTiltY = 0.0
            touchRotation = 0.0
            return
        }
        val tiltRad = if (historyPos >= 0) {
            event.getHistoricalAxisValue(MotionEvent.AXIS_TILT, pointerIndex, historyPos)
        } else {
            event.getAxisValue(MotionEvent.AXIS_TILT, pointerIndex)
        }
        if (tiltRad.isNaN() || tiltRad <= 0.0001f) {
            touchTiltX = 0.0
            touchTiltY = 0.0
            touchRotation = 0.0
            return
        }
        val orientationRad = if (historyPos >= 0) {
            event.getHistoricalAxisValue(MotionEvent.AXIS_ORIENTATION, pointerIndex, historyPos)
        } else {
            event.getAxisValue(MotionEvent.AXIS_ORIENTATION, pointerIndex)
        }
        if (orientationRad.isNaN()) {
            touchTiltX = 0.0
            touchTiltY = 0.0
            touchRotation = 0.0
            return
        }
        val canvasRotRad = Math.toRadians(canvasRotation.toDouble())
        val docOrientationRad = orientationRad.toDouble() - canvasRotRad
        val tiltDeg = (tiltRad.toDouble() * (180.0 / Math.PI)).coerceIn(0.0, 60.0)
        touchTiltX = (Math.sin(docOrientationRad) * tiltDeg).coerceIn(-60.0, 60.0)
        touchTiltY = (-Math.cos(docOrientationRad) * tiltDeg).coerceIn(-60.0, 60.0)
        // Android 手写笔（OnePlus Stylo、Apple Pencil、S-Pen 等）均无笔轴自转（Barrel Rotation）传感器；
        // AXIS_ORIENTATION 代表的是笔身在屏幕上的方位角（指向方向），绝不能作为 Krita 笔轴自转注入，
        // 否则将导致带自转或动态朝向计算的笔刷在画弧线、折线时 dab 剧烈扭曲撕裂。此处固定为 0.0。
        touchRotation = 0.0
    }

    // 文本交互状态
    private var activeTextHandle: Int = -1
    private var textDragStartDocPos: Offset = Offset.Zero
    private var textDragStartCfg: TypographyConfig = TypographyConfig()
    private var lastTextTapTimeMs: Long = 0L
    private var lastTextTapDocPos: Offset = Offset.Zero

    // 形状交互状态
    private var lastShapeTapTimeMs: Long = 0L
    private var lastShapeTapDocPos: Offset = Offset.Zero

    // 多次操作套索状态
    private var lastLassoTapTimeMs = 0L
    private var lastLassoTapDocPos = Offset.Zero
    private var justFinishedLassoInDown = false

    private fun updateLiveSelectionPathFromPoints(points: List<Offset>, closed: Boolean = true) {
        if (points.size < 2) {
            liveSelectionPath?.value = null
            return
        }
        val v = vm
        val bmp = v?.displayBitmap ?: docBitmap
        val bmpW = (bmp?.width ?: v?.renderW?.takeIf { it > 0 } ?: v?.docWidth ?: 1).toFloat()
        val bmpH = (bmp?.height ?: v?.renderH?.takeIf { it > 0 } ?: v?.docHeight ?: 1).toFloat()
        val docW = (if (v != null && v.docWidth > 0) v.docWidth else bmpW.toInt()).toFloat()
        val docH = (if (v != null && v.docHeight > 0) v.docHeight else bmpH.toInt()).toFloat()
        val scX = bmpW / docW
        val scY = bmpH / docH
        val halfW = bmpW / 2f
        val halfH = bmpH / 2f
        val p = Path().apply {
            moveTo(points[0].x * scX - halfW, points[0].y * scY - halfH)
            for (j in 1 until points.size) {
                lineTo(points[j].x * scX - halfW, points[j].y * scY - halfH)
            }
            if (closed) {
                close()
            }
        }
        liveSelectionPath?.value = p
    }
    private var prevCentroid = Offset.Zero
    private var prevDistance = 1f
    private var prevAngle = 0f
    private var initialCentroid = Offset.Zero
    private var initialDistance = 1f
    private var initialAngle = 0f
    private var lastTransformTimestamp = 0L
    private var maxTouchPointers = 0
    private var touchDownTimeMs = 0L
    private var lastPos0 = Offset.Zero
    private var lastPos1 = Offset.Zero

    // ---- 双指旋转 90° 倍数磁性吸附状态 ----
    private val rotationSnapGesture = RotationSnapGesture()

    // 上一帧是否处于吸附区 (用于进入沿触发一次反馈; 判定带迟滞)
    private var rotationSnapEngaged = false

    // 手势结束后的"收敛到精确 90° 倍数"动画 (围绕最后双指中心, 不改变缩放/旋转中心)
    private var snapAnimator: android.animation.ValueAnimator? = null

    // 画布变换的单一写入者仲裁 (新手势 / snap 动画 / fit 动画 三者只有一个能写)
    private val transformWriterGuard = CanvasTransformWriterGuard()

    // 跨碎片延迟重置任务
    private val resetTransformRunnable = Runnable {
        isTransformActive = false
        isPinchMotion = false
        isTwoFingerRotation = false
        isInteracting = false
        maxTouchPointers = 0
        lastPos0 = Offset.Zero
        lastPos1 = Offset.Zero
    }

    // 防抖撤销任务与 Procreate 风格连续撤销/重做
    private var pendingUndoRunnable: Runnable? = null
    private var isContinuousUndoing = false
    private val editMenuSwipe = com.reverie.paint.model.ThreeFingerSwipe()
    private val editMenuPointerIds = IntArray(3)
    private var editMenuGestureActive = false
    private var editMenuPointersReleased = false

    private val continuousUndoRunnable = object : Runnable {
        override fun run() {
            val v = vm ?: return
            // 多指误触保护: 液化手势进行中(含场通路的"零解算"阶段)第二指落下**不得**触发
            // 连续撤销 —— 真机上这是"液化中手指一不小心碰到屏幕, 形变被连续撤销吃掉"的根源。
            if (maxTouchPointers == 2 && !isPinchMotion && !isTwoFingerRotation && isInteracting && !isLiquifyGestureActive) {
                isContinuousUndoing = true
                v.undo()
                postDelayed(this, 110L)
            }
        }
    }

    private val continuousRedoRunnable = object : Runnable {
        override fun run() {
            val v = vm ?: return
            if (maxTouchPointers >= 3 && !isPinchMotion && isInteracting && !isLiquifyGestureActive) {
                isContinuousUndoing = true
                v.redo()
                postDelayed(this, 110L)
            }
        }
    }

    // 空格键长按临时抓手平移状态 (Spacebar Hold-to-Pan)
    private var spacePanStartPos = Offset.Zero
    private var spacePanInitialPan = Offset.Zero
    private var isSpaceDragging = false

    /**
     * 供全屏覆盖层 (速创形状编辑器) 在编辑期间驱动画布平移/缩放。
     * 编辑器是 zIndex 最高的全屏 overlay, 双指事件到不了 CanvasTouchView;
     * 由编辑器算好新变换后回写, 编辑期间画布不再被锁死。
     */
    fun applyViewTransform(zoom: Float, rotation: Float, panX: Float, panY: Float) {
        if (!zoom.isFinite() || !rotation.isFinite() || !panX.isFinite() || !panY.isFinite()) return
        cancelCanvasTransformAnimators()
        canvasZoom = zoom.coerceIn(0.02f, 128f)
        canvasRotation = rotation
        canvasPanX = panX
        canvasPanY = panY
        onTransform?.invoke(canvasZoom, canvasRotation, canvasPanX, canvasPanY)
        invalidate()
    }

    fun setSpacePanning(active: Boolean) {
        if (!active) {
            isSpaceDragging = false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val targetIcon = if (active) {
                if (isSpaceDragging) systemGrabPointer ?: systemHandPointer else systemHandPointer ?: systemDefaultPointer
            } else {
                val tool = this.tool
                val hideCursor = (tool == Tool.BRUSH || tool == Tool.ERASER || tool == Tool.SMUDGE || tool == Tool.LIQUIFY) &&
                    (vm?.cursorStyleMode != 4)
                if (hideCursor && systemNullPointer != null) systemNullPointer else systemDefaultPointer
            }
            if (targetIcon != null && pointerIcon != targetIcon) {
                pointerIcon = targetIcon
            }
        }
        invalidate()
    }

    // 画布平滑复位动画 (Procreate Smooth Reset Animation)
    private var fitAnimator: android.animation.ValueAnimator? = null

    /**
     * 终止所有画布变换动画 (吸附收敛 / 满屏复位) 并失效其待执行帧, 返回新的写入者令牌。
     *
     * 新手势与任何新动画都必须先经此函数: 令牌递增后, 旧动画即使还有已入队的帧回调,
     * 也会在自检处**整帧丢弃** (而不是"少写一部分"), 因此同一帧内只有一个写入者能改画布
     * 变换, 且取消后不会留下延迟写入或半更新状态。
     */
    private fun cancelCanvasTransformAnimators(): Int {
        fitAnimator?.cancel()
        fitAnimator = null
        snapAnimator?.cancel()
        snapAnimator = null
        return transformWriterGuard.invalidateAll()
    }

    internal fun animateFitCanvas() {
        val writerToken = cancelCanvasTransformAnimators()
        isInteracting = false
        isTransformActive = false
        val startZoom = canvasZoom
        val startRot = canvasRotation
        val startPanX = canvasPanX
        val startPanY = canvasPanY
        val dRot = RotationSnap.shortestDelta(startRot, 0f)

        val animator = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 240L
            interpolator = android.view.animation.DecelerateInterpolator(1.8f)
            addUpdateListener { anim ->
                // 令牌失效 = 新手势或新动画已接管, 本帧整帧丢弃
                if (!transformWriterGuard.isActive(writerToken)) return@addUpdateListener
                val f = anim.animatedFraction
                canvasZoom = startZoom + (1f - startZoom) * f
                canvasRotation = startRot + dRot * f
                canvasPanX = startPanX + (0f - startPanX) * f
                canvasPanY = startPanY + (0f - startPanY) * f
                onTransform?.invoke(canvasZoom, canvasRotation, canvasPanX, canvasPanY)
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (transformWriterGuard.isActive(writerToken)) {
                        canvasZoom = 1f
                        canvasRotation = 0f
                        canvasPanX = 0f
                        canvasPanY = 0f
                        onTransform?.invoke(1f, 0f, 0f, 0f)
                        invalidate()
                    }
                }
            })
        }
        fitAnimator = animator
        animator.start()
    }

    /**
     * 双指松开后, 若当前处于 90° 倍数吸附区, 围绕最后一次双指中心把角度平滑收敛到**精确倍数**。
     *
     * 收敛过程实时用旋转增量补偿平移, 使 [pivotX]/[pivotY] 对应的画布内容在屏幕上保持不动 ——
     * 即"吸附后旋转中心不变、内容不滑移", 且缩放比例全程不变。角差超过吸附区则不收敛,
     * 避免自由旋转时突然被拽走。
     */
    private fun settleRotationToSnap(pivotX: Float, pivotY: Float) {
        val v = vm ?: return
        if (!rotationSnapGesture.isSnapped) return
        val threshold = v.canvasRotationSnapDegrees
        if (!v.canvasRotationEnabled || threshold <= 0f) return
        // 仅在吸附区内才收敛 (isWithinThreshold 已折叠角差, 天然处理 360°/0° 等价)
        if (!RotationSnap.isWithinThreshold(rotationSnapGesture.rawDegrees, threshold)) return

        // 确定要写画布变换了: 立刻终止其它动画并取得独占写入权
        val writerToken = cancelCanvasTransformAnimators()

        val target = RotationSnap.nearestMultiple(rotationSnapGesture.rawDegrees)
        val dSettle = RotationSnap.shortestDelta(canvasRotation, target)
        if (abs(dSettle) < 0.02f) {
            if (canvasRotation != target) {
                canvasRotation = target
                onTransform?.invoke(canvasZoom, canvasRotation, canvasPanX, canvasPanY)
                invalidate()
            }
            return
        }

        val startRot = canvasRotation
        val startPanX = canvasPanX
        val startPanY = canvasPanY
        val c0x = viewW / 2f + startPanX
        val c0y = viewH / 2f + startPanY
        // 旋转中心 (双指中心) 相对旧画面中心的向量
        val vx = pivotX - c0x
        val vy = pivotY - c0y

        val animator = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 150L
            interpolator = android.view.animation.DecelerateInterpolator(2f)
            addUpdateListener { anim ->
                // 令牌失效 = 新手势或新动画已接管, 本帧整帧丢弃
                if (!transformWriterGuard.isActive(writerToken)) return@addUpdateListener
                val dTheta = dSettle * anim.animatedFraction
                val rad = Math.toRadians(dTheta.toDouble())
                val cosR = kotlin.math.cos(rad).toFloat()
                val sinR = kotlin.math.sin(rad).toFloat()
                canvasRotation = startRot + dTheta
                // 围绕 pivot 反向补偿平移: 缩放不变 (k=1), 内容不动
                canvasPanX = pivotX - (vx * cosR - vy * sinR) - viewW / 2f
                canvasPanY = pivotY - (vx * sinR + vy * cosR) - viewH / 2f
                onTransform?.invoke(canvasZoom, canvasRotation, canvasPanX, canvasPanY)
                invalidate()
            }
        }
        snapAnimator = animator
        animator.start()
    }

    // 长按吸色状态机 (按住不动延迟取色；移动立即画线；调出吸色后可随意移动取色)
    private var isPendingLongPress = false
    private var pendingDownDocPos = Offset.Zero
    private var pendingDownScreenPos = Offset.Zero
    private var pendingDownPressure = 1f
    private var isLongPressPickerActive = false
    private var longPressToken = 0L
    private var activeLongPressToken = 0L

    private val longPressRunnable = Runnable {
        val v = vm ?: return@Runnable
        if (activeLongPressToken == longPressToken && isPendingLongPress && !isTransformActive && maxTouchPointers <= 1) {
            isPendingLongPress = false
            if (strokeStarted) {
                v.touchCancel()
                strokeStarted = false
                clearPredictionState(triggerInvalidate = true)
            }
            isLongPressPickerActive = true
            pickerActive?.value = true
            val refHex = v.brushColor
            pickerInitialColor?.value = parseColor(refHex)
            sampleColorAtScreenPos(pendingDownScreenPos)
        }
    }

    // 边缘侧滑返回手势与起笔防误画缓冲 (仅针对手指触控，压感笔完全不受影响)
    private var isPendingEdgeFinger = false
    private var pendingEdgeScreenPos = Offset.Zero
    private var pendingEdgeDocPos = Offset.Zero

    private val flushEdgeFingerRunnable = Runnable {
        flushPendingEdgeFinger()
    }

    private fun flushPendingEdgeFinger() {
        if (!isPendingEdgeFinger) return
        isPendingEdgeFinger = false
        val v = vm ?: return
        val canEyedrop = v.longPressEyedropperEnabled &&
            (tool == Tool.BRUSH || tool == Tool.ERASER || tool == Tool.SMUDGE || tool == Tool.LIQUIFY)
        if (canEyedrop) {
            isPendingLongPress = true
            activeLongPressToken = longPressToken
            pendingDownDocPos = pendingEdgeDocPos
            pendingDownScreenPos = pendingEdgeScreenPos
            pendingDownPressure = 1f
            val delayMs = (520L - (v.eyedropperSensitivity - 1) * 70L).coerceIn(200L, 600L)
            postDelayed(longPressRunnable, delayMs)
            if (!v.penOnlyMode) {
                setLocalCursorPos(pendingEdgeScreenPos.x, pendingEdgeScreenPos.y)
                localIsTouching = true
                localIsHovering = false
                localPressure = 1f
                invalidate()
            }
        } else if (!v.penOnlyMode) {
            isPendingLongPress = false
            setLocalCursorPos(pendingEdgeScreenPos.x, pendingEdgeScreenPos.y)
            localIsTouching = true
            localIsHovering = false
            localPressure = 1f
            invalidate()
            handleToolDown(pendingEdgeScreenPos, pendingEdgeDocPos, 1f, isStylus = false)
        }
    }

    // ---- 笔尖前向超前预测 (OEM Hardware & Universal Kalman Motion Prediction) ----
    private var oplusPredictor: OplusMotionPredictor? = null
    // vivo/iQOO 官方笔迹预测引擎 (penengine-simplify SDK)
    private var vivoPredictor: com.vivo.penengine.impl.VivoAlgorithmManagerImpl? = null
    // 华为 官方笔迹预测引擎 (HwStrokeEstimate 反射包装)
    private var huaweiPredictor: com.reverie.paint.core.stylus.HuaweiMotionPredictor? = null
    // 小米 官方笔迹预测引擎 (MiuiStrokeEstimate 反射包装)
    private var xiaomiPredictor: com.reverie.paint.core.stylus.XiaomiMotionPredictor? = null

    // Android 原生系统级运动预测器 (androidx.input:input-motionprediction, 适用于华为、三星、小米、各品牌手写笔通用硬件层)
    private var systemMotionPredictor: androidx.input.motionprediction.MotionEventPredictor? = null
    private val tempOemPoint = FloatArray(3)

    private val universalPredictor = com.reverie.paint.core.stylus.UniversalKalmanPredictor(
        predictionTargetMs = 40.0f,
        maxSteps = 8
    )
    // 热路径零分配 (AGENTS.md §4.4): Offset 为 @JvmInline value class, 声明为可空类型
    // Offset? 后每次赋值都会在堆上装箱分配。拆成两个 Float + NaN 哨兵, 热路径零分配。
    private var predictedScreenX: Float = Float.NaN
    private var predictedScreenY: Float = Float.NaN
    /** 是否持有有效预测点 (替代原来的可空 Offset 判空) */
    private val hasPredictedScreenPoint: Boolean
        get() = !predictedScreenX.isNaN() && !predictedScreenY.isNaN()

    private fun setPredictedScreenPoint(x: Float, y: Float) {
        predictedScreenX = x
        predictedScreenY = y
    }

    private fun clearPredictedScreenPoint() {
        predictedScreenX = Float.NaN
        predictedScreenY = Float.NaN
    }

    // STAMP 笔画级位图缓存引用 (单笔笔画期间复用, 避免在 120Hz/240Hz MOVE 热路径重复查表与分配 Key)
    private var activeStrokeStampBitmap: android.graphics.Bitmap? = null
    private var predictedPressure: Float = 1f
    private var hasDrawnPrediction: Boolean = false
    private val cachedTouchPointInfo = OplusTouchPointInfo()
    private val tempScalarPredPoint = FloatArray(3)
    private val previewStrokePath = android.graphics.Path()
    private var previewStrokeWidth: Float = 0f
    private var previewStrokeColor: Int = 0
    private var previewTipEndX: Float = 0f
    private var previewTipEndY: Float = 0f
    // 本帧预览包络起点 (回填段起点, 屏幕坐标): 与 previewTipEndX/Y 共同构成
    // UI 避让包络矩形, 由 computePreviewStrokePath 每帧刷新。
    private var previewBackfillStartX: Float = 0f
    private var previewBackfillStartY: Float = 0f

    // ---- STAMP 分级: 真实笔尖戳印预览 (水彩/纹理与排线/绘画类) ----
    // tip 位图缓存 (按 tip 文件+颜色), dab 点集预分配 (热路径零分配)。
    private val stampCache by lazy { BrushTipStampCache(context) }
    private val previewStampX = FloatArray(64)
    private val previewStampY = FloatArray(64)
    private val previewStampSize = FloatArray(64)
    private val previewStampAlpha = FloatArray(64)
    private var previewStampCount = 0
    private var previewStampBitmap: Bitmap? = null
    private var previewUsedStamp = false
    private val stampPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val stampDstRect = android.graphics.RectF()
    // dab 弧长排布顶点收集
    private val stampVX = FloatArray(128)
    private val stampVY = FloatArray(128)

    // ---- 前缓冲 UI 避让注册表 (PR #82 review 问题 3) ----
    // setZOrderOnTop(true) 把前缓冲层置于整个 Window 之上, 预览假线可能画到
    // Compose 控件表面。仅判触点端点不够: 高速运笔一帧可跨过 56dp 顶栏,
    // 线段穿过 UI 区而两端都在外面。此处用包络矩形做保守相交判定, 命中即整帧跳过。
    // 不可变数组 + @Volatile 发布: 布局/浮窗/左右手切换时 UI 线程重建, 热路径只读。
    // 几何与 isHoverOverUi 同源 (见该函数注释), 改一处记得同步另一处。
    @Volatile
    private var uiExclusionRects: Array<android.graphics.RectF> = emptyArray()

    /**
     * 重建 UI 避让注册表。每笔 DOWN 时 + onSizeChanged 时调用 (UI 线程, 非逐事件热路径)。
     */
    private fun rebuildUiExclusionRects() {
        val v = vm
        val d = density
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) {
            uiExclusionRects = emptyArray()
            return
        }
        val list = ArrayList<android.graphics.RectF>(6)
        // 顶部操作栏 (380dp x 56dp): 左右手镜像 —— 左手模式顶栏在左上角,
        // 原 isHoverOverUi 只判了右侧, 此处补上 (PR #82 review 问题 3)。
        val isLeft = v?.leftHandMode == true
        val barW = 380f * d
        val barH = 56f * d
        if (isLeft) {
            list.add(android.graphics.RectF(0f, 0f, barW, barH))
        } else {
            list.add(android.graphics.RectF(w - barW, 0f, w, barH))
        }
        // 快捷工具栏 (宽 56dp, 全高): 左手模式下位于屏幕右侧 (PaintingPage.kt ToolRail)
        val railLeft = if (isLeft) (w - 56f * d).coerceAtLeast(0f) else 0f
        val railRight = if (isLeft) w else 56f * d
        list.add(android.graphics.RectF(railLeft, 0f, railRight, h))
        if (v != null) {
            // 参考浮窗 (若打开)
            if (v.referenceWindowOpen) {
                val rw = v.referenceWindowWidth * d
                val rh = v.referenceWindowHeight * d
                list.add(android.graphics.RectF(v.referenceWindowX, v.referenceWindowY, v.referenceWindowX + rw, v.referenceWindowY + rh))
            }
            // 快捷操作浮窗 (若打开)
            if (v.quickActionWindowOpen) {
                val qw = if (v.quickActionCollapsed) 90f * d else 380f * d
                val qh = if (v.quickActionCollapsed) 50f * d else 380f * d
                val qx = if (v.quickActionWindowX >= 0f) v.quickActionWindowX else ((w - qw) / 2f).coerceAtLeast(0f)
                val qy = if (v.quickActionWindowY >= 0f) v.quickActionWindowY else ((h - qh) / 2f).coerceAtLeast(0f)
                list.add(android.graphics.RectF(qx, qy, qx + qw, qy + qh))
            }
            // 快捷笔刷浮窗 (若打开)
            if (v.quickBrushWindowOpen) {
                val isVert = v.quickBrushOrientation == "vertical"
                val favCount = v.favoriteBrushNames.size
                val visibleCount = if (favCount == 0) 1 else favCount.coerceAtMost(v.quickBrushMaxLength)
                val listDim = if (favCount == 0) 130f else (visibleCount * 43f - 1f)
                val bw = when {
                    v.quickBrushCollapsed -> 90f * d
                    isVert -> 56f * d
                    else -> (70f + listDim + 36f) * d
                }
                val bh = when {
                    v.quickBrushCollapsed -> 50f * d
                    isVert -> (28f + (if (favCount == 0) 48f else (visibleCount * 43f - 1f)) + 36f) * d
                    else -> 56f * d
                }
                val bx = if (v.quickBrushWindowX >= 0f) v.quickBrushWindowX else ((w - bw) / 2f).coerceAtLeast(0f)
                val by = if (v.quickBrushWindowY >= 0f) v.quickBrushWindowY else ((h - bh) / 2f).coerceAtLeast(0f)
                list.add(android.graphics.RectF(bx, by, bx + bw, by + bh))
            }
        }
        uiExclusionRects = list.toTypedArray()
    }

    /**
     * 本帧预览包络 (回填起点 → 当前点 → 笔尖终点, 外扩笔宽/8dp 裕度覆盖二次曲线隆起)
     * 是否触碰任一 UI 避让区。包络相交是线段相交的保守上界: 漏判为零, 误判至多跳过一帧
     * 预览 (下一触控事件 ~4ms 后即到), 宁可少画, 不画到 UI 脸上。
     */
    private fun previewEnvelopeHitsUi(curX: Float, curY: Float): Boolean {
        val rects = uiExclusionRects
        if (rects.isEmpty()) return false
        val margin = maxOf(previewStrokeWidth, 8f * density)
        val minX = minOf(previewBackfillStartX, curX, previewTipEndX) - margin
        val maxX = maxOf(previewBackfillStartX, curX, previewTipEndX) + margin
        val minY = minOf(previewBackfillStartY, curY, previewTipEndY) - margin
        val maxY = maxOf(previewBackfillStartY, curY, previewTipEndY) + margin
        for (r in rects) {
            if (maxX >= r.left && minX <= r.right && maxY >= r.top && minY <= r.bottom) return true
        }
        return false
    }
    private val tipShaderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    // 物理触控采样环形缓冲区 (容量 64，热路径零分配)，用于提取已上屏真墨前沿并回填滞后空窗
    private val touchHistoryX = FloatArray(64)
    private val touchHistoryY = FloatArray(64)
    private val touchHistoryTime = LongArray(64)
    private var touchHistoryCount = 0
    private var touchHistoryHead = 0

    private fun recordTouchSample(x: Float, y: Float, timeMs: Long) {
        if (!x.isFinite() || !y.isFinite()) return
        touchHistoryX[touchHistoryHead] = x
        touchHistoryY[touchHistoryHead] = y
        touchHistoryTime[touchHistoryHead] = timeMs
        touchHistoryHead = (touchHistoryHead + 1) % 64
        if (touchHistoryCount < 64) touchHistoryCount++
    }

    private fun resetTouchHistory() {
        touchHistoryCount = 0
        touchHistoryHead = 0
    }

    // --- 低延迟预测光标与局部重绘状态 ---
    private var activePredictedTipScreenX = 0f
    private var activePredictedTipScreenY = 0f
    private var hasActivePredictedTip = false

    // 悬停运动前瞻 (1 帧 VSYNC 估算，空中挥笔如影随形)
    private var lastHoverRawX = 0f
    private var lastHoverRawY = 0f
    private var lastHoverEventTimeMs = 0L

    // 光标压力平滑滤波 (EMA 阻尼 alpha = 0.35，防止瞬时物理压力噪点导致光标圆环呼吸抖动)
    private var smoothedCursorPressure = -1f

    // 光标局部重绘脏区追踪 (避免无参 invalidate 全量整屏重绘)
    private var prevCursorRectValid = false
    private var prevCursorLeft = 0f
    private var prevCursorTop = 0f
    private var prevCursorRight = 0f
    private var prevCursorBottom = 0f

    @Volatile private var lastPredictionUptimeMs: Long = 0L
    private var isWatchdogScheduled = false

    private val predictionStagnationRunnable = object : Runnable {
        override fun run() {
            val now = android.os.SystemClock.uptimeMillis()
            val elapsed = now - lastPredictionUptimeMs
            if (elapsed >= 50L) {
                isWatchdogScheduled = false
                if (hasPredictedScreenPoint || hasDrawnPrediction) {
                    clearPredictionState(triggerInvalidate = true)
                }
            } else {
                val nextDelay = (50L - elapsed).coerceAtLeast(16L)
                postDelayed(this, nextDelay)
            }
        }
    }

    private fun clearPredictionState(triggerInvalidate: Boolean = false) {
        frontBufferOverlay?.clearPreview()
        if (isWatchdogScheduled) {
            removeCallbacks(predictionStagnationRunnable)
            isWatchdogScheduled = false
        }
        val had = hasPredictedScreenPoint || hasDrawnPrediction
        clearPredictedScreenPoint()
        hasDrawnPrediction = false
        hasActivePredictedTip = false
        activePredictedTipScreenX = 0f
        activePredictedTipScreenY = 0f
        smoothedCursorPressure = -1f
        priorStrokeScreenPos = Offset.Zero
        smoothedLeadMs = 20f
        activeStrokeStampBitmap = null
        resetTouchHistory()
        try {
            oplusPredictor?.reset()
        } catch (_: Throwable) {}
        try {
            huaweiPredictor?.reset()
        } catch (_: Throwable) {}
        try {
            xiaomiPredictor?.reset()
        } catch (_: Throwable) {}
        universalPredictor.reset()
        if (triggerInvalidate && had) {
            postInvalidateOnAnimation()
        }
    }

    /**
     * 计算笔尖前瞻超前预测段与历史空窗回填路径。
     *
     * 回填锚点 = 屏幕上真实可见的墨迹末端 (渲染前沿 - 呈现延迟); 前向段仅在预测
     * 通过门控时追加, 预测被抑制时仍保留回填段 (避免慢速/急转时预览整体消失)。
     * 写入 [previewStrokeWidth]、[previewStrokeColor]、[previewTipEndX]、
     * [previewTipEndY]，并填充 [outPath]; 两段都不可用时返回 false。
     */
    private fun computePreviewStrokePath(curPos: Offset, outPath: android.graphics.Path): Boolean {
        val v = vm ?: return false
        val fidelityTier = v.effectivePredictionTier
        if (fidelityTier == PaintViewModel.PredictionFidelityTier.NONE) return false
        if (!localIsTouching || tool != Tool.BRUSH) return false

        val scale = (canvasZoom * canvasFitScale).coerceAtLeast(0.001f)
        val cursorBrushSize = v.brushSize.toFloat()
        // 笔压口径: 预测可用时用预测压力 (前瞻段), 预测被抑制时退回当前实际压力,
        // 避免用过期 predictedPressure 画出宽度突变的回填段
        val pressureForWidth = if (hasPredictedScreenPoint) predictedPressure else localPressure
        val pFrac = if (v.brushPressureEnabled) pressureFractionCached(pressureForWidth) else 1f
        val actualStrokeWidth = (cursorBrushSize * scale * pFrac).coerceAtLeast(1.5f)

        val screenDiag = kotlin.math.hypot(width.toFloat(), height.toFloat())

        // ---- 1. 回填空窗锚点: 屏幕上真实可见的墨迹末端 ----
        // 引擎前沿 (currentRenderedFrontier) 只是"已渲染", 还要再过一个呈现周期
        // (presentWait + 合成尾) 才上屏; 锚到渲染前沿会在 [可见末端 → 渲染前沿]
        // 之间留下可见断裂。锚点取 (渲染前沿时间 - 呈现延迟), 无前沿信息时退回
        // (最新样本 - 全链路延迟) 估计; 两者都偏保守 (宁可轻微重叠, 不留缝)。
        var backfillStartStep = 0
        val historyCount = touchHistoryCount
        if (historyCount > 1) {
            var estimatedStep = 0
            val lagWindowMs = v.effectivePipelineDelayMs.coerceIn(20L, 80L)
            val latestIdx = (touchHistoryHead - 1 + 64) % 64
            val targetFrontierTime = touchHistoryTime[latestIdx] - lagWindowMs
            for (step in 1 until historyCount) {
                val idx = (touchHistoryHead - 1 - step + 128) % 64
                if (touchHistoryTime[idx] >= targetFrontierTime) {
                    estimatedStep = step
                } else {
                    break
                }
            }
            // +1: 再深一个样本, 偏向重叠而非留缝 (估计误差一律倒向"多画一点")
            backfillStartStep = (estimatedStep + 1).coerceAtMost(historyCount - 1)

            val frontier = v.currentRenderedFrontier
            if (frontier != null && !frontier.docX.isNaN() && !frontier.docY.isNaN() && frontier.timeMs > 0L) {
                // 误差度量 (诊断): 估计前沿 vs 引擎渲染前沿的屏幕距离
                ensureViewTransform()
                viewTransform.docToScreen(frontier.docX, frontier.docY, frontierPointScratch)
                val estIdx = (touchHistoryHead - 1 - estimatedStep + 128) % 64
                PerfTrace.recordFrontierError(
                    kotlin.math.hypot(
                        touchHistoryX[estIdx] - frontierPointScratch[0],
                        touchHistoryY[estIdx] - frontierPointScratch[1]
                    )
                )

                // 可见末端时间 = 渲染前沿时间 - 呈现延迟, 取时间上最接近的样本再深一个样本;
                // 上限 estimatedStep + 5 防止前沿过期 (渲染线程被占) 时回填过深
                val anchorTimeMs = frontier.timeMs - v.presentLagEstimateMs
                var matchedStep = -1
                var bestDt = Long.MAX_VALUE
                for (step in 1 until historyCount) {
                    val idx = (touchHistoryHead - 1 - step + 128) % 64
                    val dt = kotlin.math.abs(touchHistoryTime[idx] - anchorTimeMs)
                    if (dt < bestDt) {
                        bestDt = dt
                        matchedStep = step
                    }
                }
                if (matchedStep > 0 && bestDt <= 30L) {
                    backfillStartStep = (matchedStep + 1)
                        .coerceAtMost(historyCount - 1)
                        .coerceAtMost(estimatedStep + 5)
                }
            }
        }

        // ---- 2. 前向延伸段 (仅预测可用且通过门控时) ----
        // 预测被抑制 (慢速/急转/看门狗) 时不再整段清空预览: 回填段照画, 只少画前瞻尾。
        var endX = curPos.x
        var endY = curPos.y
        var hasExtension = false
        if (hasPredictedScreenPoint) {
            val dx = predictedScreenX - curPos.x
            val dy = predictedScreenY - curPos.y
            val dist = kotlin.math.hypot(dx, dy)
            val maxAllowedDiag = if (screenDiag > 0f) screenDiag / 6f else 600f
            // 动态上限联动笔宽：大笔刷自适应拉伸，避免出现短于笔宽的圆钝团块
            val maxDistPx = (68f * density).coerceAtLeast(actualStrokeWidth * 1.8f).coerceAtMost(maxAllowedDiag)
            val minDistPx = 2.0f * density
            val wildDiag = screenDiag > 0f && dist > maxAllowedDiag

            if (dist in minDistPx..(maxDistPx * 2.5f) && !wildDiag && predictedPressure > 0.05f) {
                val prevPos = priorStrokeScreenPos
                var angleOk = true
                if (prevPos != Offset.Zero && prevPos != curPos) {
                    val v1x = curPos.x - prevPos.x
                    val v1y = curPos.y - prevPos.y
                    val len1 = kotlin.math.hypot(v1x, v1y)
                    if (len1 > 1.5f && dist > 1.5f) {
                        val dot = (v1x * dx + v1y * dy) / (len1 * dist)
                        if (dot < 0.55f) { // 急转弯或大幅变向时抑制外推
                            angleOk = false
                        }
                    }
                }
                if (angleOk) {
                    val clampDist = dist.coerceAtMost(maxDistPx)
                    endX = curPos.x + (dx / dist) * clampDist
                    endY = curPos.y + (dy / dist) * clampDist
                    hasExtension = true
                }
            }
        }

        // 回填段与前向段都不可用 → 无预览
        if (backfillStartStep <= 0 && !hasExtension) return false

        val baseColor = resolveBrushColorCached(v.brushColor)
        val baseAlpha = (v.brushOpacity * (if (v.brushFlow > 0.0) v.brushFlow else 1.0)).coerceIn(0.05, 1.0).toFloat()

        // ---- 2.5 STAMP 分级: 真实笔尖戳印 (水彩/纹理与排线/绘画类) ----
        // tip 不可解码 (如 GIH 动画笔尖) 时回落 TIER_2 发丝线, 而非直接无预览。
        previewUsedStamp = false
        if (fidelityTier == PaintViewModel.PredictionFidelityTier.STAMP) {
            if (computeStampDabs(
                    v, backfillStartStep, hasExtension, endX, endY, curPos,
                    actualStrokeWidth, baseColor, baseAlpha
                )
            ) {
                previewUsedStamp = true
                return true
            }
            // 回落: 走下方 TIER_2 发丝线逻辑
        }

        // ---- 3. 样式: 预览颜色/宽度对齐真墨, 消除"颜色浅"与笔触断裂的观感差 ----
        // TIER_1 (纯色勾线类): 全宽全透明度 —— 预览即真墨观感, 衔接处无灰边;
        //   重叠区仅一个呈现周期 (同色叠加不加深, 半透明笔刷至多瞬时轻微加深)
        // TIER_2 (铅笔/纹理/低流量): 发丝级导引线 —— 真机结论: 加粗/提透明会放大
        //   与真墨的形状/颜色差异, "对不上的错误反馈"比"无预览"更伤跟手感
        val finalAlpha: Int
        val finalStrokeWidth: Float
        if (fidelityTier == PaintViewModel.PredictionFidelityTier.TIER_1) {
            finalAlpha = (baseAlpha * 255f).toInt().coerceIn(30, 255)
            finalStrokeWidth = actualStrokeWidth
        } else {
            finalAlpha = (baseAlpha * 0.35f * 255f).toInt().coerceIn(20, 190)
            finalStrokeWidth = (actualStrokeWidth * 0.35f).coerceIn(1.5f * density, 4.0f * density)
        }
        val finalColorWithAlpha = (baseColor and 0x00FFFFFF) or (finalAlpha shl 24)

        outPath.rewind()
        var pathStarted = false
        // UI 避让包络起点: 默认当前点, 有回填段时取回填起点
        var segX0 = curPos.x
        var segY0 = curPos.y
        if (backfillStartStep > 0) {
            val startIdx = (touchHistoryHead - 1 - backfillStartStep + 128) % 64
            val fx = touchHistoryX[startIdx]
            val fy = touchHistoryY[startIdx]
            if (kotlin.math.hypot(curPos.x - fx, curPos.y - fy) < (screenDiag / 4f)) {
                segX0 = fx
                segY0 = fy
                outPath.moveTo(fx, fy)
                for (step in (backfillStartStep - 1) downTo 0) {
                    val idx = (touchHistoryHead - 1 - step + 128) % 64
                    outPath.lineTo(touchHistoryX[idx], touchHistoryY[idx])
                }
                outPath.lineTo(curPos.x, curPos.y)
                pathStarted = true
            }
        }
        if (!pathStarted) {
            outPath.moveTo(curPos.x, curPos.y)
        }

        if (hasExtension) {
            val prevPos = priorStrokeScreenPos
            if (prevPos != Offset.Zero && prevPos != curPos) {
                val ctrlX = curPos.x + (curPos.x - prevPos.x) * 0.5f
                val ctrlY = curPos.y + (curPos.y - prevPos.y) * 0.5f
                outPath.quadTo(ctrlX, ctrlY, endX, endY)
            } else {
                outPath.lineTo(endX, endY)
            }
        }

        previewStrokeWidth = finalStrokeWidth
        previewStrokeColor = finalColorWithAlpha
        previewTipEndX = endX
        previewTipEndY = endY
        previewBackfillStartX = segX0
        previewBackfillStartY = segY0
        return true
    }

    /**
     * STAMP 分级 dab 点位计算: 沿回填段+前向段按 brushSpacing 弧长排布,
     * 逐点盖印真实笔尖位图 (BrushTipStampCache, 已按笔刷颜色着色)。
     *
     * tip 不可解码 (如 GIH 动画笔尖) → false, 调用方回落 TIER_2 发丝线。
     * 热路径零分配: 顶点与 dab 点集全部预分配数组。
     */
    private fun computeStampDabs(
        v: PaintViewModel,
        backfillStartStep: Int,
        hasExtension: Boolean,
        endX: Float,
        endY: Float,
        curPos: Offset,
        dabDiameterPx: Float,
        baseColor: Int,
        baseAlpha: Float,
    ): Boolean {
        val tipAsset = v.brushTipAsset
        if (tipAsset.isBlank()) return false
        val bmp = activeStrokeStampBitmap ?: try {
            stampCache.get(tipAsset, baseColor)?.also { activeStrokeStampBitmap = it }
        } catch (_: Throwable) {
            null
        } ?: return false

        // 1. 收集顶点: 回填段历史点 (旧→新) → 当前点 → 预测延伸点
        var vc = 0
        if (backfillStartStep > 0) {
            var step = backfillStartStep
            while (step >= 0 && vc < 127) {
                val idx = (touchHistoryHead - 1 - step + 128) % 64
                stampVX[vc] = touchHistoryX[idx]
                stampVY[vc] = touchHistoryY[idx]
                vc++
                step--
            }
        }
        if (vc == 0 || stampVX[vc - 1] != curPos.x || stampVY[vc - 1] != curPos.y) {
            if (vc < 127) {
                stampVX[vc] = curPos.x
                stampVY[vc] = curPos.y
                vc++
            }
        }
        if (hasExtension && vc < 128) {
            stampVX[vc] = endX
            stampVY[vc] = endY
            vc++
        }
        if (vc == 0) return false

        // 2. 弧长排布 dab: 间距 = brushSpacing × 笔宽 (Krita 语义), 钳制防爆量
        val spacingPx = (v.brushSpacing.toFloat() * dabDiameterPx)
            .coerceIn(dabDiameterPx * 0.12f, dabDiameterPx * 1.5f)
            .coerceAtLeast(2f)
        var count = 0
        // 首 dab 落在起点
        previewStampX[0] = stampVX[0]
        previewStampY[0] = stampVY[0]
        previewStampSize[0] = dabDiameterPx
        previewStampAlpha[0] = baseAlpha
        count = 1
        var acc = 0f
        var i = 1
        while (i < vc && count < 64) {
            val ex = stampVX[i]
            val ey = stampVY[i]
            var sx = previewStampX[count - 1]
            var sy = previewStampY[count - 1]
            var segLeft = kotlin.math.hypot(ex - sx, ey - sy)
            while (acc + segLeft >= spacingPx && count < 64) {
                val need = spacingPx - acc
                val t = if (segLeft > 0f) need / segLeft else 0f
                sx += (ex - sx) * t
                sy += (ey - sy) * t
                previewStampX[count] = sx
                previewStampY[count] = sy
                previewStampSize[count] = dabDiameterPx
                previewStampAlpha[count] = baseAlpha
                count++
                segLeft = kotlin.math.hypot(ex - sx, ey - sy)
                acc = 0f
            }
            acc += segLeft
            i++
        }
        if (count == 0) return false

        previewStampBitmap = bmp
        previewStampCount = count
        previewTipEndX = endX
        previewTipEndY = endY
        previewBackfillStartX = stampVX[0]
        previewBackfillStartY = stampVY[0]
        previewStrokeWidth = dabDiameterPx
        return true
    }

    /**
     * 计算悬空状态手写笔 1 帧 VSYNC 极轻量前向外推位置，补偿显示呈现延迟。
     */
    private fun calculatePredictedHoverPos(rawX: Float, rawY: Float, eventTimeMs: Long): Offset {
        val dt = if (lastHoverEventTimeMs > 0L) eventTimeMs - lastHoverEventTimeMs else 0L
        var predX = rawX
        var predY = rawY

        if (vm?.frontBufferPredictionEnabled == true && dt in 2L..100L) {
            val vx = (rawX - lastHoverRawX) / dt
            val vy = (rawY - lastHoverRawY) / dt
            val speed = hypot(vx, vy)
            // 仅在真实移动时前瞻，静止不产生漂移 (最低阈值 0.05px/ms)
            if (speed > 0.05f) {
                val reportRateMs = universalPredictor.avgReportRateMs.coerceIn(6f, 20f)
                var leadDx = vx * reportRateMs
                var leadDy = vy * reportRateMs
                val leadDist = hypot(leadDx, leadDy)
                val maxLeadPx = 25f * density
                if (leadDist > maxLeadPx) {
                    val scale = maxLeadPx / leadDist
                    leadDx *= scale
                    leadDy *= scale
                }
                predX += leadDx
                predY += leadDy
            }
        }

        lastHoverRawX = rawX
        lastHoverRawY = rawY
        lastHoverEventTimeMs = eventTimeMs

        val w = width.toFloat()
        val h = height.toFloat()
        val clampedX = if (w > 0f) predX.coerceIn(0f, w) else predX
        val clampedY = if (h > 0f) predY.coerceIn(0f, h) else predY
        return Offset(clampedX, clampedY)
    }

    /**
     * 针对光标移动进行局部脏区失效重绘，避免整屏 4K 全量重绘。
     * 自动计算 (旧光标包围盒 ∪ 新光标包围盒) 的最小并集矩形。
     * 标量 Float 重载消除在 120Hz/240Hz 运笔热路径上的 Offset? 装箱分配。
     */
    private fun invalidateCursor(newX: Float, newY: Float, cursorRadiusPx: Float = 0f) {
        val viewW = width
        val viewH = height
        if (viewW <= 0 || viewH <= 0) {
            invalidate()
            return
        }

        val v = vm
        // 对称模式、绘图变换交互中或视口缩放平移交互中走全量刷新
        if (v != null && v.drawingGuide.mode == GuideMode.SYMMETRY && v.drawingGuide.assistedDrawing) {
            invalidate()
            return
        }
        if (isInteracting || isTransformActive) {
            invalidate()
            return
        }

        val effectiveR = if (cursorRadiusPx > 0f) {
            cursorRadiusPx
        } else {
            val bSize = v?.brushSize?.toFloat() ?: 20f
            val scale = (canvasZoom * canvasFitScale).coerceAtLeast(0.001f)
            (bSize * scale * 0.5f).coerceIn(8f * density, 60f * density)
        }
        val margin = 4f * density

        var dirtyL = Float.MAX_VALUE
        var dirtyT = Float.MAX_VALUE
        var dirtyR = -Float.MAX_VALUE
        var dirtyB = -Float.MAX_VALUE

        if (prevCursorRectValid) {
            dirtyL = minOf(dirtyL, prevCursorLeft)
            dirtyT = minOf(dirtyT, prevCursorTop)
            dirtyR = maxOf(dirtyR, prevCursorRight)
            dirtyB = maxOf(dirtyB, prevCursorBottom)
        }

        val hasNewPos = !newX.isNaN() && !newY.isNaN()
        if (hasNewPos) {
            val newL = newX - effectiveR - margin
            val newT = newY - effectiveR - margin
            val newR = newX + effectiveR + margin
            val newB = newY + effectiveR + margin

            dirtyL = minOf(dirtyL, newL)
            dirtyT = minOf(dirtyT, newT)
            dirtyR = maxOf(dirtyR, newR)
            dirtyB = maxOf(dirtyB, newB)

            prevCursorLeft = newL
            prevCursorTop = newT
            prevCursorRight = newR
            prevCursorBottom = newB
            prevCursorRectValid = true
        } else {
            prevCursorRectValid = false
        }

        if (dirtyR <= dirtyL || dirtyB <= dirtyT) {
            return
        }

        val l = dirtyL.toInt().coerceIn(0, viewW)
        val t = dirtyT.toInt().coerceIn(0, viewH)
        val r = dirtyR.toInt().coerceIn(0, viewW)
        val b = dirtyB.toInt().coerceIn(0, viewH)
        if (r <= l || b <= t) {
            return
        }

        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            invalidate(l, t, r, b)
        } else {
            postInvalidate(l, t, r, b)
        }
    }

    private fun invalidateCursor(newPos: Offset?, cursorRadiusPx: Float = 0f) {
        if (newPos != null) {
            invalidateCursor(newPos.x, newPos.y, cursorRadiusPx)
        } else {
            invalidateCursor(Float.NaN, Float.NaN, cursorRadiusPx)
        }
    }

    // 预分配多指触控索引缓冲区 (热路径零分配 §4)
    private val fingerIndices = IntArray(16)
    private var fingerCount = 0

    // ---- 对称与透视绘图辅助 (Drawing Assist) ----
    // 镜像笔迹采样缓冲: 每个分支一段扁平的 [x, y, pressure] 三元组, 容量按需翻倍。
    // 上游 1.3.3 用 List<List<SymStrokeSample>> (每个采样点一个对象); 这里保留本分支的
    // 零分配版 —— 对称绘制最多 8 个分支, 逐点 new 等于每帧几十次分配加列表扩容。
    private val mirroredSamples = ArrayList<FloatArray>(MAX_MIRROR_BRANCHES)
    private val mirroredSizes = IntArray(MAX_MIRROR_BRANCHES)

    /** 是否有任一分支已累积采样点 (绘制 / 回放 / 局部失效判定的共同前提) */
    private fun mirrorBranchHasSamples(): Boolean {
        for (i in 0 until mirroredSamples.size) {
            if (mirroredSizes[i] > 0) return true
        }
        return false
    }

    private fun resetMirrorBranches() {
        for (i in 0 until mirroredSamples.size) mirroredSizes[i] = 0
    }

    /** 按当前分支数准备缓冲; 多余的数组保留复用, 下一笔不再重新分配 */
    private fun ensureMirrorBranches(count: Int) {
        while (mirroredSamples.size > count) {
            mirroredSamples.removeAt(mirroredSamples.size - 1)
        }
        while (mirroredSamples.size < count) {
            mirroredSamples.add(FloatArray(0))
        }
        resetMirrorBranches()
    }

    /**
     * 追加一个镜像采样点。缓冲以 3 个 float 为一组存 [x, y, pressure],
     * 扩容按需翻倍 (初始 16 个点), 热路径上不产生任何对象。
     */
    private fun appendMirrorSample(branchIndex: Int, x: Float, y: Float, pressure: Double) {
        if (branchIndex < 0 || branchIndex >= mirroredSamples.size) return
        val used = mirroredSizes[branchIndex]
        val need = (used + 1) * 3
        var buf = mirroredSamples[branchIndex]
        if (buf.size < need) {
            val grown = FloatArray(maxOf(need, buf.size * 2, 48))
            System.arraycopy(buf, 0, grown, 0, used * 3)
            mirroredSamples[branchIndex] = grown
            buf = grown
        }
        val base = used * 3
        buf[base] = x
        buf[base + 1] = y
        buf[base + 2] = pressure.toFloat()
        mirroredSizes[branchIndex] = used + 1
    }
    private val mirroredDrawPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val mirroredPointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    // 对称实时绘制笔迹压感查找表 (128阶，热路径零分配与零 JNI 锁开销)
    private val symmetryPressureLut = FloatArray(129)
    private var lastLutPresetIndex = -999
    private var lastLutCurve = -1
    private var lastLutEnabled = false
    private var lastLutPressureSize = -1.0
    private var lastLutGlobalCurvePoints: List<Offset>? = null

    private fun updateSymmetryPressureLut(v: PaintViewModel) {
        val currentPreset = v.brushPresetIndex
        val currentCurve = v.brushPressureCurve
        val currentEnabled = v.brushPressureEnabled
        val currentSize = v.brushPressureSize
        val currentGlobal = v.pressureControlPoints

        if (currentPreset == lastLutPresetIndex &&
            currentCurve == lastLutCurve &&
            currentEnabled == lastLutEnabled &&
            currentSize == lastLutPressureSize &&
            currentGlobal === lastLutGlobalCurvePoints
        ) {
            return
        }

        lastLutPresetIndex = currentPreset
        lastLutCurve = currentCurve
        lastLutEnabled = currentEnabled
        lastLutPressureSize = currentSize
        lastLutGlobalCurvePoints = currentGlobal

        for (k in 0..128) {
            val frac = v.computeStrokePressureFraction(k / 128.0)
            symmetryPressureLut[k] = frac.coerceIn(0.01f, 1f)
        }
    }

    // ---- 视图变换缓存与热路径零分配暂存区 (AGENTS.md §4) ----
    // 绘制覆盖层时每个点都要做一次 doc->screen, 旧实现每次现算三角函数; 缓存
    // 视图参数后整条笔迹共用一份变换, 并且只往复用数组里写结果, 全程零分配。
    private val viewTransform = CanvasViewTransform()
    private val pointScratch = FloatArray(2)
    private val boundsScratch = IntArray(4)
    private val renderBoundsScratch = IntArray(4)
    private val pixelScratch = IntArray(1)
    private val frontierPointScratch = FloatArray(2)
    @Volatile private var lastInvalidateFromRenderUptimeNs = 0L
    private var smoothedLeadMs = 20f

    /** 画笔颜色的解析缓存 (brushColor 是字符串, 每帧 parseColor 纯属浪费) */
    private var cachedColorHex: String? = null
    private var cachedColorInt: Int = android.graphics.Color.BLACK

    /** 压力曲线查表的量化缓存 (JNI 调用; 落笔期间压力连续但帧间变化很小) */
    private var cachedPressureKey: Int = -1
    private var cachedPressureFraction: Float = 1f

    /** 局部失效开关: 任一安全条件不满足时自动回退全量重绘 */
    var partialInvalidateEnabled: Boolean = true

    private fun computeAllSymmetricPoints(docPt: Point2D): List<Point2D> {
        val v = vm ?: return emptyList()
        return v.drawingGuide.computeSymmetricPoints(docPt, v.docWidth, v.docHeight)
    }

    private sealed interface AssistRay {
        data class Horizontal(val y: Float) : AssistRay
        data class Vertical(val x: Float) : AssistRay
        data class VanishingPoint(val vp: Point2D, val dir: Offset) : AssistRay
    }
    private var assistLockedRay: AssistRay? = null

    private fun applyAssistedDrawing(firstPt: Offset, currentPt: Offset): Offset {
        val v = vm ?: return currentPt
        val guide = v.drawingGuide
        if (!guide.assistedDrawing) return currentPt
        return when (guide.mode) {
            GuideMode.GRID_2D -> {
                val dx = abs(currentPt.x - firstPt.x)
                val dy = abs(currentPt.y - firstPt.y)
                if (dx > dy) Offset(currentPt.x, firstPt.y) else Offset(firstPt.x, currentPt.y)
            }
            GuideMode.ISOMETRIC -> {
                val dx = currentPt.x - firstPt.x
                val dy = currentPt.y - firstPt.y
                val dist = hypot(dx, dy)
                if (dist < 4f) return currentPt
                val angDeg = (atan2(dy, dx) * 180f / PI.toFloat() + 360f) % 360f
                val isoAngles = floatArrayOf(30f, 90f, 150f, 210f, 270f, 330f)
                val nearest = isoAngles.minByOrNull { abs((angDeg - it + 540f) % 360f - 180f) } ?: angDeg
                val rad = nearest * PI.toFloat() / 180f
                Offset(firstPt.x + dist * cos(rad), firstPt.y + dist * sin(rad))
            }
            GuideMode.PERSPECTIVE -> {
                val vps = if (guide.perspectiveVanishingPoints.isEmpty()) {
                    listOf(Point2D(v.docWidth * 0.5f, vm?.docHeight?.toFloat()?.times(0.35f) ?: 350f))
                } else guide.perspectiveVanishingPoints

                val dx = currentPt.x - firstPt.x
                val dy = currentPt.y - firstPt.y
                val dist = hypot(dx, dy)
                if (dist < 4f) return currentPt

                // 若已锁定射线，直接沿该锁定方向投影
                val locked = assistLockedRay
                if (locked != null) {
                    return when (locked) {
                        is AssistRay.Horizontal -> Offset(currentPt.x, locked.y)
                        is AssistRay.Vertical -> Offset(locked.x, currentPt.y)
                        is AssistRay.VanishingPoint -> {
                            val dot = dx * locked.dir.x + dy * locked.dir.y
                            Offset(firstPt.x + locked.dir.x * dot, firstPt.y + locked.dir.y * dot)
                        }
                    }
                }

                // 未锁定时寻找最佳候选射线
                var bestCandidate = Offset(currentPt.x, firstPt.y)
                var bestRay: AssistRay = AssistRay.Horizontal(firstPt.y)
                var minError = abs(dy)

                val vertError = abs(dx)
                if (vertError < minError) {
                    minError = vertError
                    bestCandidate = Offset(firstPt.x, currentPt.y)
                    bestRay = AssistRay.Vertical(firstPt.x)
                }

                for (vp in vps) {
                    val vRayX = vp.x - firstPt.x
                    val vRayY = vp.y - firstPt.y
                    val vLen = hypot(vRayX, vRayY)
                    if (vLen > 0.001f) {
                        val nx = vRayX / vLen
                        val ny = vRayY / vLen
                        val dot = dx * nx + dy * ny
                        val err = abs(dx * (-ny) + dy * nx)
                        if (err < minError) {
                            minError = err
                            bestCandidate = Offset(firstPt.x + nx * dot, firstPt.y + ny * dot)
                            bestRay = AssistRay.VanishingPoint(vp, Offset(nx, ny))
                        }
                    }
                }

                // 运笔超过 8px 判定阈值即刻锁定方向，避免运笔中途跳变
                if (dist >= 8f) {
                    assistLockedRay = bestRay
                }

                bestCandidate
            }
            else -> currentPt
        }
    }

    private var draggingGuideHandleIndex = -1 // -1: none, 100: symmetry center, 101: symmetry rotation, 0..N: VP index

    private fun checkHitGuideHandle(screenPos: Offset): Boolean {
        val v = vm ?: return false
        if (!drawingGuidePanelOpen && !v.drawingGuidePanelOpen) return false
        val guide = v.drawingGuide
        if (guide.mode == GuideMode.PERSPECTIVE) {
            val vps = guide.perspectiveVanishingPoints
            var hitVp = -1
            for (i in vps.indices) {
                val vpScreen = docToScreen(Offset(vps[i].x, vps[i].y))
                if (hypot(screenPos.x - vpScreen.x, screenPos.y - vpScreen.y) < 48f * density) {
                    hitVp = i
                    break
                }
            }
            if (hitVp != -1) {
                draggingGuideHandleIndex = hitVp
                isPendingLongPress = false
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
        } else if (guide.mode == GuideMode.SYMMETRY) {
            val cx = v.docWidth * guide.symmetryCenterX
            val cy = v.docHeight * guide.symmetryCenterY
            val symScreen = docToScreen(Offset(cx, cy))
            // 1. 中心平移控制柄
            if (hypot(screenPos.x - symScreen.x, screenPos.y - symScreen.y) < 36f * density) {
                draggingGuideHandleIndex = 100
                isPendingLongPress = false
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            // 2. 旋转控制柄 (沿对称主轴分布)
            val rotRad = (guide.symmetryRotationDeg % 360f) * (PI.toFloat() / 180f)
            val rotHandleDist = minOf(v.docWidth, v.docHeight) * 0.35f
            val rx = -sin(rotRad) * rotHandleDist
            val ry = cos(rotRad) * rotHandleDist
            val rotScreen = docToScreen(Offset(cx + rx, cy + ry))
            if (hypot(screenPos.x - rotScreen.x, screenPos.y - rotScreen.y) < 36f * density) {
                draggingGuideHandleIndex = 101
                isPendingLongPress = false
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
        }
        return false
    }

    private fun handleGuideHandleDrag(docPos: Offset): Boolean {
        val v = vm ?: return false
        val g = v.drawingGuide
        return when (draggingGuideHandleIndex) {
            100 -> {
                val newX = if (g.symmetryType == SymmetryType.HORIZONTAL && g.symmetryRotationDeg == 0f) g.symmetryCenterX else (docPos.x / v.docWidth).coerceIn(0.05f, 0.95f)
                val newY = if (g.symmetryType == SymmetryType.VERTICAL && g.symmetryRotationDeg == 0f) g.symmetryCenterY else (docPos.y / v.docHeight).coerceIn(0.05f, 0.95f)
                v.drawingGuide = g.copy(symmetryCenterX = newX, symmetryCenterY = newY)
                invalidate()
                true
            }
            101 -> {
                val cx = v.docWidth * g.symmetryCenterX
                val cy = v.docHeight * g.symmetryCenterY
                val dx = docPos.x - cx
                val dy = docPos.y - cy
                val angRad = atan2(-dx, dy)
                var deg = (angRad * 180f / PI.toFloat() + 360f) % 180f
                for (snapDeg in floatArrayOf(0f, 45f, 90f, 135f, 180f)) {
                    if (abs(deg - snapDeg) <= 3.5f) {
                        deg = if (snapDeg == 180f) 0f else snapDeg
                        break
                    }
                }
                v.drawingGuide = g.copy(symmetryRotationDeg = deg)
                invalidate()
                true
            }
            in 0 until g.perspectiveVanishingPoints.size -> {
                val pts = g.perspectiveVanishingPoints.toMutableList()
                pts[draggingGuideHandleIndex] = Point2D(docPos.x, docPos.y)
                v.drawingGuide = g.copy(perspectiveVanishingPoints = pts)
                invalidate()
                true
            }
            else -> false
        }
    }


    private val systemNullPointer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        PointerIcon.getSystemIcon(context, PointerIcon.TYPE_NULL)
    } else null

    private val systemDefaultPointer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        PointerIcon.getSystemIcon(context, PointerIcon.TYPE_DEFAULT)
    } else null

    private val systemHandPointer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        PointerIcon.getSystemIcon(context, PointerIcon.TYPE_HAND)
    } else null

    private val systemGrabPointer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        PointerIcon.getSystemIcon(context, PointerIcon.TYPE_GRAB)
    } else null

    fun clearMeasure() {
        measureStart?.value = null
        measureEnd?.value = null
    }

    companion object {
        @Volatile
        var activeTouchView: CanvasTouchView? = null
    }

    init {
        activeTouchView = this
        setWillNotDraw(false)
        isFocusable = true
        isFocusableInTouchMode = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && systemNullPointer != null) {
            pointerIcon = systemNullPointer
        }
    }

    private fun isHoverOverUi(x: Float, y: Float): Boolean {
        val v = vm ?: return false
        if (v.brushStudioOpen || v.moreSettingsOpen) return true
        // Any full-screen overlay panel (brush/layers/color/settings/more)
        // must restore the system pointer; PaintingPage mirrors its local
        // panel booleans into this field via CanvasView's update block.
        if (overlayPanelsOpen) return true

        val d = density
        val isLeft = v.leftHandMode
        // 顶部操作栏区域 (宽约 380dp，高约 56dp)
        if (isLeft) {
            if (y <= 56f * d && x <= 380f * d) return true
        } else {
            if (y <= 56f * d && x >= width - 380f * d) return true
        }

        // 快捷工具栏 (宽 56dp): 左手模式下位于右侧
        if (isLeft) {
            if (x >= width - 56f * d) return true
        } else {
            if (x <= 56f * d) return true
        }

        // 参考浮窗区域 (若打开)
        if (v.referenceWindowOpen) {
            val rx = v.referenceWindowX
            val ry = v.referenceWindowY
            val rw = v.referenceWindowWidth * d
            val rh = v.referenceWindowHeight * d
            if (x >= rx && x <= rx + rw && y >= ry && y <= ry + rh) {
                return true
            }
        }

        // 快捷操作浮窗区域 (若打开)
        if (v.quickActionWindowOpen) {
            val qw = if (v.quickActionWindowWidth > 0f) {
                v.quickActionWindowWidth
            } else {
                if (v.quickActionCollapsed) 72f * d
                else when (v.quickActionsConfig.layoutMode) {
                    QuickActionLayoutMode.COLUMN -> 56f * d
                    QuickActionLayoutMode.ROW -> 240f * d
                    QuickActionLayoutMode.GRID_2 -> 110f * d
                    QuickActionLayoutMode.GRID_3 -> 160f * d
                }
            }
            val qh = if (v.quickActionWindowHeight > 0f) {
                v.quickActionWindowHeight
            } else {
                if (v.quickActionCollapsed) 44f * d
                else when (v.quickActionsConfig.layoutMode) {
                    QuickActionLayoutMode.COLUMN -> 280f * d
                    QuickActionLayoutMode.ROW -> 76f * d
                    QuickActionLayoutMode.GRID_2, QuickActionLayoutMode.GRID_3 -> 160f * d
                }
            }
            val qx = if (v.quickActionWindowX >= 0f) v.quickActionWindowX else ((width - qw) / 2f).coerceAtLeast(0f)
            val qy = if (v.quickActionWindowY >= 0f) v.quickActionWindowY else ((height - qh) / 2f).coerceAtLeast(0f)
            if (x >= qx && x <= qx + qw && y >= qy && y <= qy + qh) {
                return true
            }
        }

        // 快捷笔刷浮窗区域 (若打开)
        if (v.quickBrushWindowOpen) {
            val bw = if (v.quickBrushWindowWidth > 0f) {
                v.quickBrushWindowWidth
            } else {
                val isVert = v.quickBrushOrientation == "vertical"
                val favCount = v.favoriteBrushNames.size
                val visibleCount = if (favCount == 0) 1 else favCount.coerceAtMost(v.quickBrushMaxLength)
                val listDim = if (favCount == 0) 130f else (visibleCount * 43f - 1f)
                when {
                    v.quickBrushCollapsed -> 90f * d
                    isVert -> 56f * d
                    else -> (70f + listDim + 36f) * d
                }
            }
            val bh = if (v.quickBrushWindowHeight > 0f) {
                v.quickBrushWindowHeight
            } else {
                val isVert = v.quickBrushOrientation == "vertical"
                val favCount = v.favoriteBrushNames.size
                val visibleCount = if (favCount == 0) 1 else favCount.coerceAtMost(v.quickBrushMaxLength)
                when {
                    v.quickBrushCollapsed -> 50f * d
                    isVert -> (28f + (if (favCount == 0) 48f else (visibleCount * 43f - 1f)) + 36f) * d
                    else -> 56f * d
                }
            }
            val bx = if (v.quickBrushWindowX >= 0f) v.quickBrushWindowX else ((width - bw) / 2f).coerceAtLeast(0f)
            val by = if (v.quickBrushWindowY >= 0f) v.quickBrushWindowY else ((height - bh) / 2f).coerceAtLeast(0f)
            if (x >= bx && x <= bx + bw && y >= by && y <= by + bh) {
                return true
            }
        }

        // 快捷颜色浮窗区域 (若打开)
        if (v.quickColorWindowOpen) {
            val cw = if (v.quickColorWindowWidth > 0f) v.quickColorWindowWidth
                     else if (v.quickColorCollapsed) 48f * d else 212f * d
            val ch = if (v.quickColorWindowHeight > 0f) v.quickColorWindowHeight
                     else if (v.quickColorCollapsed) 48f * d else 270f * d
            val cx = if (v.quickColorWindowX >= 0f) v.quickColorWindowX else ((width - cw) / 2f).coerceAtLeast(0f)
            val cy = if (v.quickColorWindowY >= 0f) v.quickColorWindowY else ((height - ch) / 2f).coerceAtLeast(0f)
            if (x >= cx && x <= cx + cw && y >= cy && y <= cy + ch) {
                return true
            }
        }

        // 快捷图层浮窗区域 (若打开)
        if (v.quickLayerWindowOpen) {
            val lw = if (v.quickLayerWindowWidth > 0f) v.quickLayerWindowWidth
                     else if (v.quickLayerCollapsed) 48f * d else 156f * d
            val lh = if (v.quickLayerWindowHeight > 0f) v.quickLayerWindowHeight
                     else if (v.quickLayerCollapsed) 48f * d else 320f * d
            val lx = if (v.quickLayerWindowX >= 0f) v.quickLayerWindowX else ((width - lw) / 2f).coerceAtLeast(0f)
            val ly = if (v.quickLayerWindowY >= 0f) v.quickLayerWindowY else ((height - lh) / 2f).coerceAtLeast(0f)
            if (x >= lx && x <= lx + lw && y >= ly && y <= ly + lh) {
                return true
            }
        }

        return false
    }

    // 系统手势侧滑返回排除区状态 (API 29+ Android 10: 绘画页面全高全局排除两侧返回手势)
    private val gestureExclusionRects = mutableListOf<android.graphics.Rect>()
    private val leftExclusionRect = android.graphics.Rect()
    private val rightExclusionRect = android.graphics.Rect()

    fun updateSystemGestureExclusion() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return

        val v = vm
        val allowEdgeBack = v?.allowEdgeBackGesture ?: true
        val backKeyAction = v?.backKeyAction ?: BackKeyAction.OPEN_SETTINGS

        // 仅在用户关闭边缘侧滑返回、或返回键行为设为无行为且无浮层打开时，
        // 全高度排除左右边缘返回手势以防误触；反之释放排除区，让系统原生侧滑正常响应
        val shouldExclude = (!allowEdgeBack || backKeyAction == BackKeyAction.NONE) && !overlayPanelsOpen

        if (shouldExclude) {
            val edgeWidth = (48 * density).toInt() // 覆盖系统边缘手势感应区 (约 48dp)
            leftExclusionRect.set(0, 0, edgeWidth, h)
            rightExclusionRect.set((w - edgeWidth).coerceAtLeast(0), 0, w, h)

            gestureExclusionRects.clear()
            gestureExclusionRects.add(leftExclusionRect)
            gestureExclusionRects.add(rightExclusionRect)
            systemGestureExclusionRects = gestureExclusionRects
        } else {
            gestureExclusionRects.clear()
            systemGestureExclusionRects = emptyList()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) {
            universalPredictor.setScreenDiagonal(kotlin.math.hypot(w.toFloat(), h.toFloat()))
        }
        updateSystemGestureExclusion()
        rebuildUiExclusionRects()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        activeTouchView = this
        getOrCreateStylusDriver()?.syncSettings()
        applyHighRefreshRateAndUnbuffered()
        val maxFps = if (Build.VERSION.SDK_INT >= 30) {
            val d = try { display } catch (_: Throwable) { null }
            d?.supportedModes?.maxOfOrNull { it.refreshRate } ?: 144f
        } else 144f
        val dm = resources.displayMetrics
        val screenDiag = kotlin.math.hypot(dm.widthPixels.toFloat(), dm.heightPixels.toFloat())
        universalPredictor.setScreenDiagonal(screenDiag)
        universalPredictor.setRefreshRate(maxFps)
        if (oplusPredictor == null && com.reverie.paint.core.stylus.OppoOcsStylusClient.isDeviceSupported()) {
            try {
                val p = OplusMotionPredictor()
                if (p.isValid) {
                    p.setRefreshRate(maxFps)
                    p.setDpi(dm.xdpi, dm.ydpi)
                    oplusPredictor = p
                    android.util.Log.i("ReveriePerf", "OplusMotionPredictor initialized successfully! maxFps=$maxFps dpi=${dm.xdpi},${dm.ydpi}")
                } else {
                    android.util.Log.w("ReveriePerf", "OplusMotionPredictor is not valid")
                    p.destroy()
                }
            } catch (t: Throwable) {
                android.util.Log.e("ReveriePerf", "Failed to init OplusMotionPredictor", t)
            }
        }
        if (vivoPredictor == null &&
            com.reverie.paint.core.stylus.VivoStylusAdapter.isVivoDeviceSupported()
        ) {
            try {
                // SDK 内部自检 Build.BRAND=="vivo" && isTablet(), 非平板/非 vivo 时自动退化为
                // 返回当前点 (预测管线无输出); 算法参数 cfg 由 SDK 从 assets 拷贝到 cacheDir
                val algo = com.vivo.penengine.impl.VivoAlgorithmManagerImpl(context.applicationContext)
                if (algo.isEstimateEnable) {
                    vivoPredictor = algo
                    android.util.Log.i("ReveriePerf", "VivoAlgorithmManagerImpl initialized")
                } else {
                    algo.release()
                    vivoPredictor = null
                    android.util.Log.i("ReveriePerf", "VivoAlgorithmManagerImpl estimate disabled on this device")
                }
            } catch (t: Throwable) {
                android.util.Log.e("ReveriePerf", "Failed to init VivoAlgorithmManagerImpl", t)
                vivoPredictor = null
            }
        }
        if (huaweiPredictor == null && com.reverie.paint.core.stylus.HuaweiMotionPredictor.isHuaweiDeviceSupported()) {
            try {
                val hp = com.reverie.paint.core.stylus.HuaweiMotionPredictor(context.applicationContext)
                if (hp.isValid) {
                    huaweiPredictor = hp
                    android.util.Log.i("ReveriePerf", "HuaweiMotionPredictor initialized")
                }
            } catch (t: Throwable) {
                android.util.Log.w("ReveriePerf", "Failed to init HuaweiMotionPredictor: ${t.message}")
            }
        }
        if (xiaomiPredictor == null && com.reverie.paint.core.stylus.XiaomiMotionPredictor.isXiaomiDeviceSupported()) {
            try {
                val xp = com.reverie.paint.core.stylus.XiaomiMotionPredictor(context.applicationContext)
                if (xp.isValid) {
                    xiaomiPredictor = xp
                    android.util.Log.i("ReveriePerf", "XiaomiMotionPredictor initialized")
                }
            } catch (t: Throwable) {
                android.util.Log.w("ReveriePerf", "Failed to init XiaomiMotionPredictor: ${t.message}")
            }
        }
        if (systemMotionPredictor == null) {
            try {
                systemMotionPredictor = androidx.input.motionprediction.MotionEventPredictor.newInstance(this)
                android.util.Log.i("ReveriePerf", "MotionEventPredictor initialized successfully")
            } catch (t: Throwable) {
                android.util.Log.w("ReveriePerf", "Failed to init MotionEventPredictor: ${t.message}")
                systemMotionPredictor = null
            }
        }
        post { updateSystemGestureExclusion() }
    }

    fun applyHighRefreshRateAndUnbuffered() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                requestUnbufferedDispatch(android.view.InputDevice.SOURCE_STYLUS)
                requestUnbufferedDispatch(android.view.InputDevice.SOURCE_TOUCHSCREEN)
            } catch (_: Throwable) {}
        }
        val maxFps = if (Build.VERSION.SDK_INT >= 30) {
            val d = try { display } catch (_: Throwable) { null }
            d?.supportedModes?.maxOfOrNull { it.refreshRate } ?: 144f
        } else 144f
        universalPredictor.setRefreshRate(maxFps)
        oplusPredictor?.let { p ->
            try {
                if (p.isValid) p.setRefreshRate(maxFps)
            } catch (_: Throwable) {}
        }
        // B4: 交互期请求面板最高帧率; 静止 1.5s 后由 [downclockWhenIdle] 降回 60Hz
        frameRateHigh = true
        requestMaxFrameRate()
    }

    /** B4: 请求"面板最高帧率"(交互期用)。拿不到就静默放弃, 不影响绘制。 */
    private fun requestMaxFrameRate() {
        if (Build.VERSION.SDK_INT < 34) return
        val maxFps = try {
            display?.supportedModes?.maxOfOrNull { it.refreshRate } ?: 144f
        } catch (_: Throwable) {
            144f
        }
        applyFrameRateHint(maxFps)
    }

    /** B4: 通过 `View.setFrameRate`(API 34+) 给系统一个帧率提示(反射调用, 失败即忽略)。 */
    private fun applyFrameRateHint(fps: Float) {
        if (Build.VERSION.SDK_INT < 34) return
        try {
            val method = View::class.java.getMethod("setFrameRate", java.lang.Float.TYPE, java.lang.Integer.TYPE)
            method.invoke(this, fps, 1) // 1 = Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE
        } catch (_: Throwable) {}
    }

    /**
     * B4 · 静止降频: 一有真实输入就把帧率请求提到最高, 并重置 1.5s 的静止计时; 计时到点若确实
     * 没有触摸/悬停/动画, 就把请求降回 60Hz —— 只是"别让看画把屏幕钉在 144Hz 上", 不影响任何绘制。
     */
    private fun markInteractionFrameRate() {
        if (!frameRateHigh) {
            frameRateHigh = true
            requestMaxFrameRate()
        }
        removeCallbacks(idleDownclockRunnable)
        postDelayed(idleDownclockRunnable, IDLE_DOWNCLOCK_MS)
    }

    private fun downclockWhenIdle() {
        if (!frameRateHigh) return
        if (localIsTouching || localIsHovering || isInteracting) return
        val v = vm
        if (v != null && v.anim.isPlaying) return
        frameRateHigh = false
        applyFrameRateHint(IDLE_FRAME_RATE_HZ)
    }

    private var lastRefreshRateCheckTime: Long = 0L

    fun checkAndRestoreHighRefreshRate() {
        val now = SystemClock.uptimeMillis()
        if (now - lastRefreshRateCheckTime < 3000L) return
        lastRefreshRateCheckTime = now
        val d = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try { display } catch (_: Throwable) { null }
        } else {
            @Suppress("DEPRECATION")
            (context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)?.defaultDisplay
        }
        val modes = d?.supportedModes ?: return
        val maxFps = modes.maxOfOrNull { it.refreshRate } ?: return
        val curFps = d.refreshRate
        if (maxFps > 60f && curFps < maxFps - 5f) {
            (context as? android.app.Activity)?.let { act ->
                com.reverie.paint.MainActivity.applyHighRefreshRate(act)
            }
            applyHighRefreshRateAndUnbuffered()
        }
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus) {
            applyHighRefreshRateAndUnbuffered()
            checkAndRestoreHighRefreshRate()
            // B4: 静止计时也从这里起算(进页面后一直不动也要降频)
            markInteractionFrameRate()
        }
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible) {
            applyHighRefreshRateAndUnbuffered()
            checkAndRestoreHighRefreshRate()
            markInteractionFrameRate()
        }
    }

    override fun onDetachedFromWindow() {
        releaseLargeBitmapTiles()
        removeCallbacks(quickShapeHold)
        vm?.quickShapeCapture = null
        vm?.cancelQuickShape()
        removeCallbacks(liquifyHoldRunnable)
        super.onDetachedFromWindow()
        removeCallbacks(continuousUndoRunnable)
        removeCallbacks(continuousRedoRunnable)
        editMenuGestureActive = false
        isContinuousUndoing = false
        removeCallbacks(longPressRunnable)
        isPendingLongPress = false
        removeCallbacks(flushEdgeFingerRunnable)
        isPendingEdgeFinger = false
        isLongPressPickerActive = false
        longPressToken++
        if (activeTouchView == this) activeTouchView = null
        if (strokeStarted) {
            vm?.touchCancel()
            strokeStarted = false
        }
        // 离开绘画页: 喷枪是挂在渲染线程上的自续定时链, 不停掉会让页面之外
        // 仍周期渲染并保持笔画事务开启; 未投递的笔画样本与起笔 kick 一并丢弃
        vm?.stopAirbrush()
        vm?.disarmStrokeStartKick()
        // Keeping a QuickShape draft queues the normal smoothing/taper finish; do not drop it.
        if (vm?.isQuickShapeEditing != true) vm?.clearPendingStrokeSamples()
        cachedDriver?.feedbackManager?.setWritingHapticsEnabled(false)
        cachedDriver?.feedbackManager?.stopStrokeSound()
        cachedDriver = null
        cancelCanvasTransformAnimators()
        oplusPredictor?.destroy()
        oplusPredictor = null
        try {
            vivoPredictor?.release()
        } catch (_: Throwable) {}
        vivoPredictor = null
        huaweiPredictor?.destroy()
        huaweiPredictor = null
        xiaomiPredictor?.destroy()
        xiaomiPredictor = null
        systemMotionPredictor = null
        clearPredictionState(triggerInvalidate = false)
        safeEndSymmetryUndoMacro()
        resetMirrorBranches()
        currentStrokeDocPos = Offset.Zero
    }

    override fun onResolvePointerIcon(event: MotionEvent, pointerIndex: Int): PointerIcon? {
        val v = vm ?: return super.onResolvePointerIcon(event, pointerIndex)
        val hideCursor = (tool == Tool.BRUSH || tool == Tool.ERASER || tool == Tool.SMUDGE || tool == Tool.LIQUIFY) &&
            v.cursorStyleMode != 4
        val overUi = isHoverOverUi(event.x, event.y)
        return if (!overUi && hideCursor && systemNullPointer != null) {
            systemNullPointer
        } else {
            systemDefaultPointer ?: super.onResolvePointerIcon(event, pointerIndex)
        }
    }

    /**
     * onDraw 薄壳: 只在性能标尺开启时计时并叠加标尺, 关闭时直接转发 (零额外开销)。
     * 标尺本体在 debug 专属源集里 ([PerfHud]), 正式版是空实现。
     */
    override fun onDraw(canvas: Canvas) {
        val tInvalidate = lastInvalidateFromRenderUptimeNs
        if (tInvalidate > 0L) {
            lastInvalidateFromRenderUptimeNs = 0L
            val dt = SystemClock.elapsedRealtimeNanos() - tInvalidate
            if (dt > 0L) {
                PerfTrace.recordPresentWait(dt)
            }
        }
        if (!PerfHud.enabled) {
            drawCanvas(canvas)
            return
        }
        PerfTrace.frameTick()
        val t0 = SystemClock.elapsedRealtimeNanos()
        try {
            drawCanvas(canvas)
        } finally {
            PerfHud.recordDraw(SystemClock.elapsedRealtimeNanos() - t0)
            PerfHud.draw(canvas, this)
        }
    }

    // Support drawing huge bitmaps (> 100MB, e.g. 8192x8192 = 256MB)
    // without triggering android.graphics.RecordingCanvas.throwIfCannotDraw
    private var largeBitmapTiles: Array<Bitmap>? = null
    private var largeBitmapLastGenId: Int = -1
    private var largeBitmapCols: Int = 0
    private var largeBitmapRows: Int = 0
    private val largeBitmapSrcRect = android.graphics.Rect()
    private val largeBitmapDstRect = android.graphics.Rect()

    private fun drawLargeBitmapTiled(
        canvas: Canvas,
        bmp: Bitmap,
        left: Float,
        top: Float,
        paint: Paint,
    ) {
        val maxTileDim = 4096
        val cols = (bmp.width + maxTileDim - 1) / maxTileDim
        val rows = (bmp.height + maxTileDim - 1) / maxTileDim
        val totalTiles = cols * rows

        val tilesNeedRealloc = largeBitmapTiles == null ||
            largeBitmapTiles?.size != totalTiles ||
            largeBitmapCols != cols ||
            largeBitmapRows != rows

        if (tilesNeedRealloc) {
            largeBitmapTiles?.forEach { it.recycle() }
            try {
                val newTiles = Array(totalTiles) { idx ->
                    val c = idx % cols
                    val r = idx / cols
                    val tw = minOf(maxTileDim, bmp.width - c * maxTileDim)
                    val th = minOf(maxTileDim, bmp.height - r * maxTileDim)
                    Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
                }
                largeBitmapTiles = newTiles
                largeBitmapCols = cols
                largeBitmapRows = rows
                largeBitmapLastGenId = -1
            } catch (t: Throwable) {
                android.util.Log.e("CanvasTouchView", "Failed to allocate large bitmap tiles", t)
                largeBitmapTiles?.forEach { it.recycle() }
                largeBitmapTiles = null
                return
            }
        }

        val tiles = largeBitmapTiles ?: return
        if (largeBitmapLastGenId != bmp.generationId) {
            for (r in 0 until rows) {
                for (c in 0 until cols) {
                    val idx = r * cols + c
                    val tile = tiles[idx]
                    val sx = c * maxTileDim
                    val sy = r * maxTileDim
                    val tw = tile.width
                    val th = tile.height
                    largeBitmapSrcRect.set(sx, sy, sx + tw, sy + th)
                    largeBitmapDstRect.set(0, 0, tw, th)
                    val tileCanvas = Canvas(tile)
                    tileCanvas.drawBitmap(bmp, largeBitmapSrcRect, largeBitmapDstRect, null)
                }
            }
            largeBitmapLastGenId = bmp.generationId
        }

        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val idx = r * cols + c
                val tile = tiles[idx]
                val tx = left + c * maxTileDim
                val ty = top + r * maxTileDim
                canvas.drawBitmap(tile, tx, ty, paint)
            }
        }
    }

    private fun releaseLargeBitmapTiles() {
        largeBitmapTiles?.forEach { it.recycle() }
        largeBitmapTiles = null
        largeBitmapLastGenId = -1
    }

    private fun drawCanvas(canvas: Canvas) {
        super.onDraw(canvas)
        val v = vm ?: return

        // =========================================================================
        // 1. 硬件加速直出 Krita 渲染画布 (消除 Compose 重组调度延迟)
        // 统一单点呈现：阴影、透明棋盘格、位图与像素网格硬件加速直出
        // =========================================================================
        val bmp = v.displayBitmap ?: docBitmap
        if (bmp != null && !bmp.isRecycled && bmp.width > 0 && bmp.height > 0) {
            val imgW = bmp.width.toFloat()
            val imgH = bmp.height.toFloat()
            val scale = (canvasZoom * canvasFitScale).coerceAtLeast(0.001f)
            val centerX = viewW / 2f + canvasPanX
            val centerY = viewH / 2f + canvasPanY

            canvas.save()
            canvas.translate(centerX, centerY)
            canvas.rotate(canvasRotation)
            canvas.scale(scale, scale)
            // 视图翻转: 在当前原点(=画布中心)上做轴镜像, 与 CanvasViewTransform 的
            // signX/signY 完全同源 —— 位图、棋盘格、像素网格一起镜像, 而缩放/旋转
            // 手势的语义不变 (镜像只作用于显示, 不改任何一层的像素)。
            if (canvasFlipX || canvasFlipY) {
                canvas.scale(
                    if (canvasFlipX) -1f else 1f,
                    if (canvasFlipY) -1f else 1f,
                )
            }

            // 绘制透明棋盘格
            checkerboardPaint?.let { cb ->
                canvas.drawRect(-imgW / 2f, -imgH / 2f, imgW / 2f, imgH / 2f, cb)
            }

            // 绘制真实画布像素
            directBitmapPaint.isFilterBitmap = v.magnificationInterpolation
            directBitmapPaint.isAntiAlias = v.magnificationInterpolation
            if (bmp.byteCount > 100 * 1024 * 1024) {
                drawLargeBitmapTiled(canvas, bmp, -imgW / 2f, -imgH / 2f, directBitmapPaint)
            } else {
                if (largeBitmapTiles != null) {
                    releaseLargeBitmapTiles()
                }
                canvas.drawBitmap(bmp, -imgW / 2f, -imgH / 2f, directBitmapPaint)
            }
            v.onLiquifyBitmapDrawn(bmp)

            // 像素级网格高倍率缩放展示 (scale >= 4.0)
            if (v.pixelGridEnabled && scale >= 4f) {
                val halfW = imgW / 2f
                val halfH = imgH / 2f
                val gridAlpha = ((scale - 4f) / 4f).coerceIn(0f, 1f) * 0.15f
                if (gridAlpha > 0.01f) {
                    pixelGridPaint.color =
                        android.graphics.Color.argb((gridAlpha * 255).toInt(), 255, 255, 255)
                    pixelGridPaint.strokeWidth = 1f / scale
                    // 只画视口内可见的网格线: 整幅遍历在 4x 放大的大画幅上每帧要发
                    // 上万条 drawLine, 而屏幕上真正看得见的只有几百条 —— 这是高
                    // 倍率下最贵的一段绘制 (对应"渲染路径分离"的按视口裁剪)。
                    ensureViewTransform()
                    visibleBitmapBounds(boundsScratch)
                    val gx0 = boundsScratch[0].coerceIn(0, bmp.width)
                    val gx1 = boundsScratch[2].coerceIn(0, bmp.width)
                    val gy0 = boundsScratch[1].coerceIn(0, bmp.height)
                    val gy1 = boundsScratch[3].coerceIn(0, bmp.height)
                    // 兜底: 视口已经把整幅包进来时线条仍可能过万, 宁可不画网格也不掉帧
                    if ((gx1 - gx0) + (gy1 - gy0) <= MAX_VISIBLE_GRID_LINES) {
                        for (gx in gx0..gx1) {
                            canvas.drawLine(gx - halfW, -halfH, gx - halfW, halfH, pixelGridPaint)
                        }
                        for (gy in gy0..gy1) {
                            canvas.drawLine(-halfW, gy - halfH, halfW, gy - halfH, pixelGridPaint)
                        }
                    }
                }
            }

            canvas.restore()
        }

        // Phase 2B: AGSL 形变预览覆盖层。引擎在"主机侧绘制"模式下不生成也不叠加 CPU 预览,
        // 由这里在显示分辨率上做位移采样 —— 因此缩放/旋转/平移都自动跟随, 且不用改画布位图。
        // 开关关闭时 active 恒为 false, 这段在正式使用中不会执行。
        // VSYNC 绑定: 一帧内可能"只暂存了状态、还没提交", 因此以 requested 为门,
        // 让 draw() 自己在帧内完成"提交(=纹理上传) + 绘制"。
        if (LiquifyGpuPreview.requested || LiquifyGpuPreview.active) {
            ensureViewTransform()
            if (LiquifyGlesPreview.requested) {
                // Phase 5 · C2: 本次手势由 GLES 覆盖层画 —— 这里**不画 AGSL、也不上传纹理**,
                // 只把"文档 → 屏幕"的仿射喂给它(源裁剪/位移网格由引擎线程喂, 见
                // PaintViewModel.pollLiquifyGpuPreview), 出图在它自己的渲染线程上。
                // 两者同时打开时 GLES 优先, 免得同一帧被画两遍。
                LiquifyGlesPreview.pushAffine(viewTransform)
            } else {
                // Phase 3 · Commit 1b 埋点: 覆盖层"提交(纹理构建/上传) + 绘制"在 UI 线程的实际耗时。
                // draw p95 只含绘制命令录制、不含纹理上传与 GPU, 所以要用这一项才能判断覆盖层贵不贵。
                val lqOverlayT0 = System.nanoTime()
                LiquifyGpuPreview.draw(canvas, viewTransform)
                PerfTrace.liquifyOverlay(System.nanoTime() - lqOverlayT0)
            }
        }

        // 标尺的液化网格可视化(debug 专属; release 侧 PerfHud 为恒 false 的空实现, 不进这个分支):
        // 把 Krita 网格的"原始点 → 位移后点"画成箭头, 用于在实现 Preview 之前确认
        // 网格几何、位移方向与文档→屏幕映射与最终结果一致。
        if (PerfHud.gridOverlayEnabled) {
            ensureViewTransform()
            PerfHud.drawLiquifyGrid(canvas, viewTransform)
        }

        // =========================================================================
        // 1.5 笔尖前向超前预测 (双系统分派)
        // - 前缓冲预测开启: 前沿孪生预览 [P_frontier -> P_cur -> P_pred], 前缓冲直出,
        //   不可用时在此软件回退绘制 (回退空洞见 FrontBufferProbe.softwareFallbackRequired)
        // - 前缓冲预测关闭: 上游原版 OEM 硬件预测渐隐尾线 (仅 OPPO 硬件预测器有效)
        // =========================================================================
        var drewPrediction = hasDrawnPrediction
        if (v.frontBufferPredictionEnabled) {
            if (FrontBufferProbe.softwareFallbackRequired(frontBufferOverlay != null, frontBufferOverlay?.canRenderPreview == true)) {
                val fidelityTier = v.effectivePredictionTier
                val isDrawingTool = tool == Tool.BRUSH
                var fallbackDrew = false
                // 不再要求 predPt != null: 预测被抑制时回填段照画 (修断裂/慢速无预览)
                if (fidelityTier != PaintViewModel.PredictionFidelityTier.NONE &&
                    localIsTouching && isDrawingTool && hasLocalCursorPos) {
                    try {
                        // 局部非空 Offset 为内联值类, 无堆分配
                        if (computePreviewStrokePath(Offset(localCursorX, localCursorY), previewStrokePath)) {
                            if (previewUsedStamp && previewStampBitmap != null && previewStampCount > 0) {
                                // STAMP 分级软件回退: onDraw 内逐 dab 盖印 (与前缓冲同数据源)
                                val bmp = previewStampBitmap!!
                                for (i in 0 until previewStampCount) {
                                    val s = previewStampSize[i]
                                    if (s <= 0f) continue
                                    stampPaint.alpha =
                                        (previewStampAlpha[i] * 255f).toInt().coerceIn(0, 255)
                                    val half = s / 2f
                                    stampDstRect.set(
                                        previewStampX[i] - half, previewStampY[i] - half,
                                        previewStampX[i] + half, previewStampY[i] + half
                                    )
                                    canvas.drawBitmap(bmp, null, stampDstRect, stampPaint)
                                }
                            } else {
                                tipShaderPaint.shader = null
                                tipShaderPaint.color = previewStrokeColor
                                tipShaderPaint.strokeWidth = previewStrokeWidth
                                canvas.drawPath(previewStrokePath, tipShaderPaint)
                            }
                            fallbackDrew = true
                            activePredictedTipScreenX = previewTipEndX
                            activePredictedTipScreenY = previewTipEndY
                            hasActivePredictedTip = true
                        }
                    } catch (_: Throwable) {}
                }
                drewPrediction = fallbackDrew
                hasDrawnPrediction = fallbackDrew
                if (!fallbackDrew) {
                    hasActivePredictedTip = false
                }
            }
        } else if (v.stylusStrokePredictionEnabled && v.isCurrentBrushPredictionEligible) {
            // 上游原版: OEM 硬件预测尾线 (实时预测未来 15~20ms 笔尖切线, 微羽化渐隐)
            val isDrawingTool = tool == Tool.BRUSH || tool == Tool.ERASER
            if (localIsTouching && isDrawingTool && hasPredictedScreenPoint && hasLocalCursorPos) {
                try {
                    val dx = predictedScreenX - localCursorX
                    val dy = predictedScreenY - localCursorY
                    val dist = hypot(dx, dy)
                    val maxDistPx = 14f * density
                    val minDistPx = 2.5f * density
                    if (dist in minDistPx..(maxDistPx * 3.5f) && predictedPressure > 0.05f) {
                        val prevPos = previousSinglePos
                        var angleOk = true
                        if (prevPos != Offset.Zero) {
                            val v1x = localCursorX - prevPos.x
                            val v1y = localCursorY - prevPos.y
                            val len1 = hypot(v1x, v1y)
                            if (len1 > 1.5f) {
                                val dot = (v1x * dx + v1y * dy) / (len1 * dist)
                                if (dot < 0.55f) { // 急转弯或大幅变向时抑制直线外推
                                    angleOk = false
                                }
                            }
                        }

                        if (angleOk) {
                            val clampDist = dist.coerceAtMost(maxDistPx)
                            val endX = localCursorX + (dx / dist) * clampDist
                            val endY = localCursorY + (dy / dist) * clampDist

                            val scale = (canvasZoom * canvasFitScale).coerceAtLeast(0.001f)
                            val cursorBrushSize = when {
                                effTool() == Tool.LIQUIFY -> liquifyBrushSize
                                effTool() == Tool.ERASER && tool != Tool.ERASER -> v.getToolEffectiveSize("eraser").toFloat()
                                else -> v.brushSize.toFloat()
                            }
                            val pFrac = if (v.brushPressureEnabled) pressureFractionCached(predictedPressure) else 1f
                            val strokeWidth = (cursorBrushSize * scale * pFrac).coerceAtLeast(1.5f)

                            val isEraser = effTool() == Tool.ERASER
                            val baseColor = if (isEraser) {
                                android.graphics.Color.WHITE
                            } else {
                                resolveBrushColorCached(v.brushColor)
                            }
                            val baseAlpha = (if (isEraser) 0.8 else (v.brushOpacity * (if (v.brushFlow > 0.0) v.brushFlow else 1.0))).coerceIn(0.05, 1.0).toFloat()

                            val startColor = android.graphics.Color.argb(
                                (baseAlpha * 0.55f * 255).toInt().coerceIn(0, 255),
                                android.graphics.Color.red(baseColor),
                                android.graphics.Color.green(baseColor),
                                android.graphics.Color.blue(baseColor)
                            )
                            val endColor = android.graphics.Color.argb(
                                0, // 终点彻底渐隐至 0% 透明度，彻底消除圆形粗钝 Cap 假线感
                                android.graphics.Color.red(baseColor),
                                android.graphics.Color.green(baseColor),
                                android.graphics.Color.blue(baseColor)
                            )
                            tipShaderPaint.strokeWidth = strokeWidth
                            tipShaderPaint.shader = android.graphics.LinearGradient(
                                localCursorX, localCursorY, endX, endY,
                                startColor, endColor,
                                android.graphics.Shader.TileMode.CLAMP
                            )
                            canvas.drawLine(localCursorX, localCursorY, endX, endY, tipShaderPaint)
                        }
                    }
                } catch (_: Throwable) {}
            }
        }

        // =========================================================================
        // 2. 绘画中实时镜像笔迹绘制 (120Hz 零延迟 GPU Canvas 渲染)
        // =========================================================================
        if (v.drawingGuide.mode == GuideMode.SYMMETRY && v.drawingGuide.assistedDrawing && localIsTouching && mirrorBranchHasSamples()) {
            try {
                ensureViewTransform()
                updateSymmetryPressureLut(v)
                // 工具/颜色口径取上游 1.3.3: 橡皮与涂抹用固定灰, 其余按笔刷色 × 不透明度
                when (effTool()) {
                    Tool.ERASER -> mirroredDrawPaint.color = android.graphics.Color.argb(140, 240, 240, 245)
                    Tool.SMUDGE -> mirroredDrawPaint.color = android.graphics.Color.argb(100, 180, 180, 190)
                    else -> {
                        val baseColor = resolveBrushColorCached(v.brushColor)
                        val alpha = (v.brushOpacity.coerceIn(0.0, 1.0) * 255.0).toInt().coerceIn(1, 255)
                        mirroredDrawPaint.color = (baseColor and 0x00FFFFFF) or (alpha shl 24)
                    }
                }
                mirroredPointPaint.color = mirroredDrawPaint.color
                // 宽口径取上游 1.3.3 的压感查找表(对称笔迹跟随压感), 数据仍走本分支的
                // 零分配扁平缓冲: 一个分支一段 [x, y, pressure], 逐段只读不分配。
                val baseStrokeWidth =
                    (v.brushSize.toFloat() * viewTransform.currentScale).coerceAtLeast(1.5f)
                for (b in 0 until mirroredSamples.size) {
                    val buf = mirroredSamples[b]
                    val n = mirroredSizes[b]
                    if (n < 1) continue
                    viewTransform.docToScreen(buf[0], buf[1], pointScratch)
                    if (n == 1) {
                        val lutIdx = (buf[2].coerceIn(0f, 1f) * 128f + 0.5f).toInt().coerceIn(0, 128)
                        val r = (baseStrokeWidth * symmetryPressureLut[lutIdx] * 0.5f)
                            .coerceAtLeast(0.75f)
                        canvas.drawCircle(pointScratch[0], pointScratch[1], r, mirroredPointPaint)
                        continue
                    }
                    var prevX = pointScratch[0]
                    var prevY = pointScratch[1]
                    var prevP = buf[2]
                    for (i in 1 until n) {
                        val base = i * 3
                        viewTransform.docToScreen(buf[base], buf[base + 1], pointScratch)
                        val curP = buf[base + 2]
                        val avgP = (prevP + curP) * 0.5f
                        val lutIdx = (avgP.coerceIn(0f, 1f) * 128f + 0.5f).toInt().coerceIn(0, 128)
                        mirroredDrawPaint.strokeWidth =
                            (baseStrokeWidth * symmetryPressureLut[lutIdx]).coerceAtLeast(1.5f)
                        canvas.drawLine(prevX, prevY, pointScratch[0], pointScratch[1], mirroredDrawPaint)
                        prevX = pointScratch[0]
                        prevY = pointScratch[1]
                        prevP = curP
                    }
                }
            } catch (_: Exception) {}
        }

        // =========================================================================
        // 2.5 绘画中辅助吸附动态导引线 (透视灭点射线 / 等轴测 / 2D 网格高亮)
        // =========================================================================
        val guide = v.drawingGuide
        // 注意: 不能复用上面 1.5 段那支 isDrawingTool(它不含 SMUDGE) —— 两者语义不同,
        // 合并上游 1.3.3 时曾因此产生重复声明。
        val isGuideAssistTool = tool == Tool.BRUSH || tool == Tool.ERASER || tool == Tool.SMUDGE
        if (guide.mode != GuideMode.OFF && guide.assistedDrawing && localIsTouching && strokeStarted && isGuideAssistTool) {
            when (guide.mode) {
                GuideMode.PERSPECTIVE -> {
                    val dx = currentStrokeDocPos.x - firstDocPos.x
                    val dy = currentStrokeDocPos.y - firstDocPos.y
                    val dist = hypot(dx, dy)
                    if (dist >= 6f) {
                        val vps = if (guide.perspectiveVanishingPoints.isEmpty()) {
                            listOf(Point2D(v.docWidth * 0.5f, v.docHeight * 0.35f))
                        } else guide.perspectiveVanishingPoints

                        var bestType = 0 // 0: horizontal, 1: vertical, 2: vp
                        var minError = abs(dy)
                        var bestVp: Point2D? = null

                        val vertError = abs(dx)
                        if (vertError < minError) {
                            minError = vertError
                            bestType = 1
                        }

                        for (vp in vps) {
                            val vRayX = vp.x - firstDocPos.x
                            val vRayY = vp.y - firstDocPos.y
                            val vLen = hypot(vRayX, vRayY)
                            if (vLen > 0.001f) {
                                val nx = vRayX / vLen
                                val ny = vRayY / vLen
                                val dot = dx * nx + dy * ny
                                val err = abs(dx * (-ny) + dy * nx)
                                if (err < minError) {
                                    minError = err
                                    bestType = 2
                                    bestVp = vp
                                }
                            }
                        }

                        val firstScreen = docToScreen(firstDocPos)
                        when (bestType) {
                            0 -> {
                                val y = firstScreen.y
                                canvas.drawLine(-200f, y, viewW + 200f, y, dynamicGuideGlowPaint)
                                canvas.drawLine(-200f, y, viewW + 200f, y, dynamicGuideLinePaint)
                            }
                            1 -> {
                                val x = firstScreen.x
                                canvas.drawLine(x, -200f, x, viewH + 200f, dynamicGuideGlowPaint)
                                canvas.drawLine(x, -200f, x, viewH + 200f, dynamicGuideLinePaint)
                            }
                            2 -> {
                                if (bestVp != null) {
                                    val vpScreen = docToScreen(Offset(bestVp.x, bestVp.y))
                                    var vx = firstScreen.x - vpScreen.x
                                    var vy = firstScreen.y - vpScreen.y
                                    var vLen = hypot(vx, vy)
                                    if (vLen <= 0.001f) {
                                        val curScreen = docToScreen(currentStrokeDocPos)
                                        vx = curScreen.x - vpScreen.x
                                        vy = curScreen.y - vpScreen.y
                                        vLen = hypot(vx, vy)
                                    }
                                    if (vLen > 0.001f) {
                                        val nx = vx / vLen
                                        val ny = vy / vLen
                                        val maxExt = maxOf(viewW, viewH) * 3f
                                        val startX = vpScreen.x - nx * maxExt
                                        val startY = vpScreen.y - ny * maxExt
                                        val endX = vpScreen.x + nx * maxExt
                                        val endY = vpScreen.y + ny * maxExt
                                        canvas.drawLine(startX, startY, endX, endY, dynamicGuideGlowPaint)
                                        canvas.drawLine(startX, startY, endX, endY, dynamicGuideLinePaint)

                                        // 命中灭点高亮动效光环
                                        canvas.drawCircle(vpScreen.x, vpScreen.y, 14f * density, vpTargetGlowPaint)
                                        canvas.drawCircle(vpScreen.x, vpScreen.y, 7f * density, vpTargetCorePaint)
                                        canvas.drawCircle(vpScreen.x, vpScreen.y, 3.5f * density, vpTargetCenterPaint)
                                    }
                                }
                            }
                        }
                    }
                }
                GuideMode.ISOMETRIC -> {
                    val dx = currentStrokeDocPos.x - firstDocPos.x
                    val dy = currentStrokeDocPos.y - firstDocPos.y
                    val dist = hypot(dx, dy)
                    if (dist >= 6f) {
                        val angDeg = (atan2(dy, dx) * 180f / PI.toFloat() + 360f) % 360f
                        val isoAngles = floatArrayOf(30f, 90f, 150f, 210f, 270f, 330f)
                        val nearest = isoAngles.minByOrNull { abs((angDeg - it + 540f) % 360f - 180f) } ?: angDeg
                        val rad = nearest * PI.toFloat() / 180f
                        val nx = cos(rad)
                        val ny = sin(rad)
                        val firstScreen = docToScreen(firstDocPos)
                        val maxExt = maxOf(viewW, viewH) * 2.5f
                        val startX = firstScreen.x - nx * maxExt
                        val startY = firstScreen.y - ny * maxExt
                        val endX = firstScreen.x + nx * maxExt
                        val endY = firstScreen.y + ny * maxExt
                        canvas.drawLine(startX, startY, endX, endY, dynamicGuideGlowPaint)
                        canvas.drawLine(startX, startY, endX, endY, dynamicGuideLinePaint)
                    }
                }
                GuideMode.GRID_2D -> {
                    val dx = currentStrokeDocPos.x - firstDocPos.x
                    val dy = currentStrokeDocPos.y - firstDocPos.y
                    val dist = hypot(dx, dy)
                    if (dist >= 6f) {
                        val firstScreen = docToScreen(firstDocPos)
                        if (abs(dx) > abs(dy)) {
                            val y = firstScreen.y
                            canvas.drawLine(-200f, y, viewW + 200f, y, dynamicGuideGlowPaint)
                            canvas.drawLine(-200f, y, viewW + 200f, y, dynamicGuideLinePaint)
                        } else {
                            val x = firstScreen.x
                            canvas.drawLine(x, -200f, x, viewH + 200f, dynamicGuideGlowPaint)
                            canvas.drawLine(x, -200f, x, viewH + 200f, dynamicGuideLinePaint)
                        }
                    }
                }
                else -> Unit
            }
        }

        // =========================================================================
        // 3. 光标及对称参考线绘制 (Cursor & Guides)
        // 依据用户的光标模式设置独立渲染
        // =========================================================================
        if (v.brushStudioOpen || v.moreSettingsOpen || overlayPanelsOpen) return
        if (!hasLocalCursorPos) return
        // 局部非空 Offset 为内联值类, 无堆分配
        val pos = Offset(localCursorX, localCursorY)
        val isEraser = effTool() == Tool.ERASER
        val cursorMode = if (isEraser) v.eraserCursorMode else v.brushCursorMode
        // 0: 不显示, 1: 绘画时显示, 2: 悬空显示, 3: 绘画和悬空显示
        val shouldShow = when (cursorMode) {
            1 -> localIsTouching
            2 -> localIsHovering
            3 -> localIsTouching || localIsHovering
            else -> false
        }
        val isDrawTool = effTool() == Tool.BRUSH || effTool() == Tool.ERASER || effTool() == Tool.SMUDGE || effTool() == Tool.LIQUIFY
        // Phase 2: 记录本帧光标环的屏幕位置与半径 —— 下一帧做局部失效时要把环的"旧位置"也覆盖掉
        lqRingValid = false
        if (shouldShow && isDrawTool && v.cursorStyleMode != 4) {
            // 绘画中且前缓冲预测生效时，光标圆心自动对齐至预测延伸笔尖
            val effectiveCursorPos = if (v.frontBufferPredictionEnabled && localIsTouching && drewPrediction && hasActivePredictedTip) {
                Offset(activePredictedTipScreenX, activePredictedTipScreenY)
            } else {
                pos
            }

            val scale = (canvasZoom * canvasFitScale).coerceAtLeast(0.001f)
            val cursorBrushSize = when {
                effTool() == Tool.LIQUIFY -> liquifyBrushSize
                effTool() == Tool.ERASER && tool != Tool.ERASER -> v.getToolEffectiveSize("eraser").toFloat()
                else -> v.brushSize.toFloat()
            }
            val pressureFraction = if (localIsTouching) {
                if (v.frontBufferPredictionEnabled) {
                    // 前缓冲预测: 预测压力 + EMA 阻尼 (防光标环呼吸抖动)
                    val targetPressure = if (drewPrediction && predictedPressure.isFinite()) predictedPressure else localPressure
                    smoothedCursorPressure = if (smoothedCursorPressure < 0f) {
                        targetPressure
                    } else {
                        smoothedCursorPressure * 0.65f + targetPressure * 0.35f
                    }
                    pressureFractionCached(smoothedCursorPressure)
                } else {
                    // 上游原版: 直接使用当前实际压力, 无平滑
                    smoothedCursorPressure = -1f
                    pressureFractionCached(localPressure)
                }
            } else {
                smoothedCursorPressure = -1f
                1f
            }
            val brushRadiusScreen = (cursorBrushSize * scale * 0.5f * pressureFraction).coerceAtLeast(2f)
            // 半径取"环半径"与"十字/点光标尺寸"的较大者: 后三种样式画的是小十字/圆点, 半径只有
            // 几个 dp, 但同样需要被覆盖
            lqRingValid = true
            lqRingCx = effectiveCursorPos.x
            lqRingCy = effectiveCursorPos.y
            lqRingR = if (brushRadiusScreen > 8f * density) brushRadiusScreen else 8f * density

            prevCursorLeft = effectiveCursorPos.x - lqRingR - 4f
            prevCursorTop = effectiveCursorPos.y - lqRingR - 4f
            prevCursorRight = effectiveCursorPos.x + lqRingR + 4f
            prevCursorBottom = effectiveCursorPos.y + lqRingR + 4f
            prevCursorRectValid = true

            when (v.cursorStyleMode) {
                0 -> { // 双对比圆环
                    canvas.drawCircle(effectiveCursorPos.x, effectiveCursorPos.y, brushRadiusScreen + 0.8f, cursorPaintBlack)
                    canvas.drawCircle(effectiveCursorPos.x, effectiveCursorPos.y, brushRadiusScreen, cursorPaintWhite)
                }
                1 -> { // 十字准星 (极细发丝相交线)
                    val len = 7f * density
                    canvas.drawLine(effectiveCursorPos.x - len, effectiveCursorPos.y, effectiveCursorPos.x + len, effectiveCursorPos.y, crosshairPaintBlack)
                    canvas.drawLine(effectiveCursorPos.x, effectiveCursorPos.y - len, effectiveCursorPos.x, effectiveCursorPos.y + len, crosshairPaintBlack)
                    canvas.drawLine(effectiveCursorPos.x - len, effectiveCursorPos.y, effectiveCursorPos.x + len, effectiveCursorPos.y, crosshairPaintWhite)
                    canvas.drawLine(effectiveCursorPos.x, effectiveCursorPos.y - len, effectiveCursorPos.x, effectiveCursorPos.y + len, crosshairPaintWhite)
                }
                2 -> { // 精确点
                    canvas.drawCircle(effectiveCursorPos.x, effectiveCursorPos.y, 3f * density, cursorPaintBlack)
                    canvas.drawCircle(effectiveCursorPos.x, effectiveCursorPos.y, 1.8f * density, cursorPaintWhite)
                }
                5 -> { // 圆 + 十字
                    canvas.drawCircle(effectiveCursorPos.x, effectiveCursorPos.y, brushRadiusScreen + 0.8f, cursorPaintBlack)
                    canvas.drawCircle(effectiveCursorPos.x, effectiveCursorPos.y, brushRadiusScreen, cursorPaintWhite)
                    val len = 4.5f * density
                    canvas.drawLine(effectiveCursorPos.x - len, effectiveCursorPos.y, effectiveCursorPos.x + len, effectiveCursorPos.y, crosshairPaintBlack)
                    canvas.drawLine(effectiveCursorPos.x, effectiveCursorPos.y - len, effectiveCursorPos.x, effectiveCursorPos.y + len, crosshairPaintBlack)
                    canvas.drawLine(effectiveCursorPos.x - len, effectiveCursorPos.y, effectiveCursorPos.x + len, effectiveCursorPos.y, crosshairPaintWhite)
                    canvas.drawLine(effectiveCursorPos.x, effectiveCursorPos.y - len, effectiveCursorPos.x, effectiveCursorPos.y + len, crosshairPaintWhite)
                }
            }

            // 对称辅助光标镜像绘制 (支持垂直/水平/四象限/径向多分支)
            if (v.drawingGuide.mode == GuideMode.SYMMETRY && v.drawingGuide.assistedDrawing) {
                val docPt = screenToDoc(effectiveCursorPos)
                val symPts = computeAllSymmetricPoints(Point2D(docPt.x, docPt.y))
                for (symPt in symPts) {
                    val symScreen = docToScreen(Offset(symPt.x, symPt.y))
                    canvas.drawCircle(symScreen.x, symScreen.y, brushRadiusScreen + 0.8f, cursorPaintBlack)
                    canvas.drawCircle(symScreen.x, symScreen.y, brushRadiusScreen, cursorPaintWhite)
                }
            }
        }
    }

    private fun sampleColorAtScreenPos(screenPos: Offset) {
        val v = vm ?: return
        val samplePos = if (v.eyedropperOffsetEnabled) {
            screenPos + Offset(-48f * density, -48f * density)
        } else {
            screenPos
        }
        pickerScreenPos?.value = samplePos
        val docPos = screenToDoc(samplePos)
        val bmp = v.displayBitmap ?: docBitmap
        if (bmp != null && bmp.width > 0 && bmp.height > 0) {
            val docW = if (v.docWidth > 0) v.docWidth else bmp.width
            val docH = if (v.docHeight > 0) v.docHeight else bmp.height
            val ix = (docPos.x * (bmp.width.toFloat() / docW)).toInt()
            val iy = (docPos.y * (bmp.height.toFloat() / docH)).toInt()
            if (ix in 0 until bmp.width && iy in 0 until bmp.height) {
                // getPixel 每个采样点都是一趟 JNI; 改用复用数组 + getPixels,
                // 拖动吸色期间每个采样点省一次跨语言调用且零分配
                bmp.getPixels(pixelScratch, 0, 1, ix, iy, 1, 1)
                pickerCurrentColor?.value = Color(pixelScratch[0])
            }
        }
    }

    /** 刷新缓存的视图变换 (参数未变化时内部直接返回, 几乎零开销) */
    private fun ensureViewTransform() {
        val v = vm
        val bmp = v?.displayBitmap ?: docBitmap
        val bmpW = bmp?.width ?: v?.docWidth ?: 1
        val bmpH = bmp?.height ?: v?.docHeight ?: 1
        val dw = v?.docWidth ?: bmpW
        val dh = v?.docHeight ?: bmpH
        viewTransform.update(
            viewW,
            viewH,
            canvasPanX,
            canvasPanY,
            canvasZoom,
            canvasFitScale,
            canvasRotation,
            bmpW,
            bmpH,
            dw,
            dh,
            canvasFlipX,
            canvasFlipY,
        )
    }

    private fun screenToDoc(screenPos: Offset): Offset {
        ensureViewTransform()
        viewTransform.screenToDoc(screenPos.x, screenPos.y, pointScratch)
        return Offset(pointScratch[0], pointScratch[1])
    }

    private fun docToScreen(docPos: Offset): Offset {
        ensureViewTransform()
        viewTransform.docToScreen(docPos.x, docPos.y, pointScratch)
        return Offset(pointScratch[0], pointScratch[1])
    }

    /** 视口四角反变换到位图坐标后的包围盒 (供按视口裁剪绘制) */
    private fun visibleBitmapBounds(out: IntArray) {
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (i in 0 until 4) {
            val sx = if (i == 0 || i == 2) 0f else viewW.toFloat()
            val sy = if (i < 2) 0f else viewH.toFloat()
            viewTransform.screenToBitmap(sx, sy, pointScratch)
            if (pointScratch[0] < minX) minX = pointScratch[0]
            if (pointScratch[0] > maxX) maxX = pointScratch[0]
            if (pointScratch[1] < minY) minY = pointScratch[1]
            if (pointScratch[1] > maxY) maxY = pointScratch[1]
        }
        out[0] = floor(minX).toInt()
        out[1] = floor(minY).toInt()
        out[2] = ceil(maxX).toInt()
        out[3] = ceil(maxY).toInt()
    }

    /** 画笔颜色字符串 -> 颜色值的解析缓存 (brushColor 每帧都可能被读取) */
    private fun resolveBrushColorCached(hex: String): Int {
        val cached = cachedColorHex
        if (cached === hex || cached == hex) return cachedColorInt
        val parsed =
            try {
                android.graphics.Color.parseColor(hex)
            } catch (_: Throwable) {
                android.graphics.Color.BLACK
            }
        cachedColorHex = hex
        cachedColorInt = parsed
        return parsed
    }

    /**
     * 压力曲线的量化缓存。压力曲线由引擎侧提供 (JNI), 落笔期间压力逐帧连续
     * 变化但幅度很小, 按 1/256 量化后绝大多数帧直接命中缓存。
     */
    private fun pressureFractionCached(p: Float): Float {
        val key = (p.coerceIn(0f, 1f) * 256f).toInt()
        if (key == cachedPressureKey) return cachedPressureFraction
        val frac =
            try {
                ReverieCoreBridge.brushPressureFraction(p)
            } catch (_: Throwable) {
                1f
            }
        cachedPressureKey = key
        cachedPressureFraction = frac
        return frac
    }

    /**
     * 渲染线程写完一帧后调用: 只让真正变化的区域重绘 (配合渲染路径分离,
     * 位图不变的区域不再重绘, 省下大画幅下每帧的整屏 GPU 填充)。
     *
     * 任何"会在脏区之外重绘"的覆盖层处于活动状态时 (光标 / 预测笔迹 /
     * 镜像笔迹 / 像素网格 / 画布旋转 / 变换会话) 一律回退全量重绘 ——
     * 宁可多画一次, 也不允许出现边缘残影。
     */
    fun invalidateFromRender() {
        lastInvalidateFromRenderUptimeNs = SystemClock.elapsedRealtimeNanos()
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            postInvalidate()
            return
        }
        val v = vm
        val snap = v?.renderDirtySnapshot
        if (!partialInvalidateEnabled || v == null || snap == null) {
            postInvalidate()
            return
        }
        val dw = snap[2]
        val dh = snap[3]
        if (dw <= 0 || dh <= 0 || !canPartialInvalidate(v, dw, dh)) {
            postInvalidate()
            return
        }
        ensureViewTransform()
        viewTransform.bitmapRectToScreenBounds(
            snap[0].toFloat(),
            snap[1].toFloat(),
            (snap[0] + dw).toFloat(),
            (snap[1] + dh).toFloat(),
            renderBoundsScratch,
        )
        var left = renderBoundsScratch[0].coerceAtLeast(0)
        var top = renderBoundsScratch[1].coerceAtLeast(0)
        var right = renderBoundsScratch[2].coerceAtMost(viewW)
        var bottom = renderBoundsScratch[3].coerceAtMost(viewH)
        if (right <= left || bottom <= top) {
            postInvalidate()
            return
        }
        // 标尺(debug)必须整块重绘: 它的行数会随数据出现/消失, 只刷新损坏区会留下两代文本
        // 拼接的残迹。标尺关闭时这里是纯读一次布尔(见 PerfHud.fillHudBounds)。
        if (PerfHud.fillHudBounds(hudBoundsScratch)) {
            if (hudBoundsScratch.left < left) left = hudBoundsScratch.left
            if (hudBoundsScratch.top < top) top = hudBoundsScratch.top
            if (hudBoundsScratch.right > right) right = hudBoundsScratch.right
            if (hudBoundsScratch.bottom > bottom) bottom = hudBoundsScratch.bottom
        }
        postInvalidate(left, top, right, bottom)
    }

    /** 局部失效的安全条件, 任一条不满足就整屏重绘 */
    private fun canPartialInvalidate(v: PaintViewModel, dirtyW: Int, dirtyH: Int): Boolean {
        // 回放与动画播放按帧整体切换画面, 只失效引擎回报的脏区会留下上一帧残影
        if (v.currentPage == Page.REPLAY || v.anim.isPlaying) return false
        if (!viewTransform.isAxisAligned) return false
        if (v.pixelGridEnabled && viewTransform.currentScale >= 4f) return false
        if (v.brushStudioOpen || v.moreSettingsOpen || overlayPanelsOpen) return false
        if (hasLocalCursorPos || localIsTouching || localIsHovering) return false
        if (hasPredictedScreenPoint) return false
        if (isTransformActive || isInteracting) return false
        val guide = v.drawingGuide
        if (guide.mode == GuideMode.SYMMETRY && guide.assistedDrawing && mirrorBranchHasSamples()) return false
        val viewArea = viewW.toLong() * viewH.toLong()
        if (viewArea <= 0L) return false
        // 脏区超过视口一半时就省不下什么了, 整屏一次画完更划算
        return dirtyW.toLong() * dirtyH.toLong() * 2L <= viewArea
    }

    /**
     * Liquify V2 · Phase 2: 覆盖层有新位移场时调用 (可能来自引擎线程)。
     *
     * 原来这里的调用方是无条件 `postInvalidate()` 整屏重绘 —— 这是"每个 dab 都整屏"的来源
     * (见 docs/RENDER-OPTIMIZATION.md §4.10)。现在改成: 回到 UI 线程, 用
     * **本帧文档脏区(覆盖层自己算出) ∪ 光标环前后位置** 算一个最小重绘矩形;
     * 任一安全条件不满足时自行整屏回退 —— 宁可多画一次, 不允许边缘残影。
     *
     * 这条路径只服务 AGSL 覆盖层(默认关闭), 其余渲染路径完全不受影响。
     */
    fun onLiquifyPreviewUpdated() {
        if (android.os.Looper.myLooper() === android.os.Looper.getMainLooper()) {
            scheduleLiquifyInvalidate()
        } else {
            post(lqInvalidateRunnable)
        }
    }

    /**
     * Phase 2: 局部失效的安全条件。与 [canPartialInvalidate] 的唯一区别是**不再因光标活动而整屏**
     * —— 液化手势期间光标环必然活动且 `isInteracting` 恒为 true, 若沿用旧条件该优化等于不存在。
     * 改为把环的前后位置并进失效矩形; 其余"会在脏区之外重绘"的覆盖层仍一律整屏。
     */
    private fun canLiquifyPartialInvalidate(v: PaintViewModel): Boolean {
        if (v.currentPage == Page.REPLAY || v.anim.isPlaying) return false
        if (!viewTransform.isAxisAligned) return false
        if (v.pixelGridEnabled) return false
        if (v.brushStudioOpen || v.moreSettingsOpen || overlayPanelsOpen) return false
        if (isTransformActive || isPinchMotion || maxTouchPointers >= 2) return false
        val guide = v.drawingGuide
        // 对称镜像会在光标之外多画几个环, 不在失效矩形内 ⇒ 整屏
        if (guide.mode == GuideMode.SYMMETRY && guide.assistedDrawing) return false
        return viewW > 0 && viewH > 0
    }

    private fun scheduleLiquifyInvalidate() {
        val v = vm
        // Phase 5 · C3-2: 场通路的失效由**补点**驱动(见 invalidateLiquifyFieldDab): 那条路径没有网格,
        // "前后两帧位移场差分"根本不成立, 照旧算只会退化成每帧整屏重绘。这里直接让路。
        if (LiquifyGlesPreview.requested && LiquifyGlesPreview.fieldArmed) return
        if (!partialInvalidateEnabled || v == null || !canLiquifyPartialInvalidate(v)) {
            postInvalidate()
            return
        }
        // 覆盖层初判: 不可比(rebase/首帧)或没有脏区信息 → 整屏
        if (LiquifyGpuPreview.overlayDirtyFull) {
            postInvalidate()
            return
        }
        ensureViewTransform()
        var left = Float.MAX_VALUE
        var top = Float.MAX_VALUE
        var right = -Float.MAX_VALUE
        var bottom = -Float.MAX_VALUE

        // 1) 本帧文档脏区 -> 屏幕包围盒(8px 余量, 与 LiquifyGpuPreview.draw 的绘制余量一致)
        if (LiquifyGpuPreview.overlayDirtyValid) {
            val dw = LiquifyGpuPreview.overlayDirtyW
            val dh = LiquifyGpuPreview.overlayDirtyH
            if (dw > 0 && dh > 0) {
                val x0 = LiquifyGpuPreview.overlayDirtyX.toFloat()
                val y0 = LiquifyGpuPreview.overlayDirtyY.toFloat()
                val x1 = x0 + dw
                val y1 = y0 + dh
                for (i in 0 until 4) {
                    viewTransform.docToScreen(
                        if (i == 0 || i == 2) x0 else x1,
                        if (i < 2) y0 else y1,
                        lqPointScratch,
                    )
                    val sx = lqPointScratch[0]
                    val sy = lqPointScratch[1]
                    if (sx < left) left = sx
                    if (sx > right) right = sx
                    if (sy < top) top = sy
                    if (sy > bottom) bottom = sy
                }
                left -= 8f
                top -= 8f
                right += 8f
                bottom += 8f
            }
        }

        // 2) 光标环: 旧位置与当前位置都要重绘
        if (lqRingValid) {
            left = minOf(left, lqRingCx - lqRingR - 2f)
            top = minOf(top, lqRingCy - lqRingR - 2f)
            right = maxOf(right, lqRingCx + lqRingR + 2f)
            bottom = maxOf(bottom, lqRingCy + lqRingR + 2f)
        }
        if (hasLocalCursorPos) {
            val r = if (lqRingValid) lqRingR else 0f
            left = minOf(left, localCursorX - r - 2f)
            top = minOf(top, localCursorY - r - 2f)
            right = maxOf(right, localCursorX + r + 2f)
            bottom = maxOf(bottom, localCursorY + r + 2f)
        }
        if (right <= left || bottom <= top) {
            // 这一帧位移场没变、环也没动: 连重绘都不需要
            return
        }
        // 标尺(debug)并进失效区 —— 与 [invalidateFromRender] 同理; 放在"无需重绘"判断之后,
        // 免得标尺把"一帧都不需要重绘"的情况变成每帧都重绘。
        if (PerfHud.fillHudBounds(hudBoundsScratch)) {
            if (hudBoundsScratch.left < left) left = hudBoundsScratch.left.toFloat()
            if (hudBoundsScratch.top < top) top = hudBoundsScratch.top.toFloat()
            if (hudBoundsScratch.right > right) right = hudBoundsScratch.right.toFloat()
            if (hudBoundsScratch.bottom > bottom) bottom = hudBoundsScratch.bottom.toFloat()
        }
        val l = left.toInt().coerceIn(0, viewW)
        val t = top.toInt().coerceIn(0, viewH)
        val r = right.toInt().coerceIn(0, viewW)
        val b = bottom.toInt().coerceIn(0, viewH)
        if (r <= l || b <= t) {
            postInvalidate()
            return
        }
        postInvalidate(l, t, r, b)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        // B4: 真实输入 ⇒ 帧率请求立刻提到最高(静止 1.5s 后自动降回 60Hz)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_MOVE,
            MotionEvent.ACTION_POINTER_DOWN,
            -> markInteractionFrameRate()
            else -> Unit
        }
        return super.dispatchTouchEvent(event)
    }

    fun onDirectHover(localX: Float, localY: Float, actionMasked: Int) {
        val v = vm ?: return
        val hideCursor = (tool == Tool.BRUSH || tool == Tool.ERASER || tool == Tool.SMUDGE || tool == Tool.LIQUIFY) &&
            v.cursorStyleMode != 4

        when (actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> {
                val predPos = calculatePredictedHoverPos(localX, localY, android.os.SystemClock.uptimeMillis())
                setLocalCursorPos(predPos.x, predPos.y)
                val overUi = isHoverOverUi(localX, localY)
                localIsHovering = !overUi
                localIsTouching = false
                localPressure = 1f
                invalidateCursor(predPos)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    val targetIcon = if (!overUi && hideCursor && systemNullPointer != null) {
                        systemNullPointer
                    } else {
                        systemDefaultPointer
                    }
                    if (targetIcon != null && pointerIcon != targetIcon) {
                        pointerIcon = targetIcon
                    }
                }
            }
            MotionEvent.ACTION_HOVER_EXIT -> {
                lastHoverEventTimeMs = 0L
                localIsHovering = false
                localIsTouching = false
                clearLocalCursorPos()
                invalidateCursor(null)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    if (systemDefaultPointer != null && pointerIcon != systemDefaultPointer) {
                        pointerIcon = systemDefaultPointer
                    }
                }
            }
        }
    }

    public override fun dispatchHoverEvent(event: MotionEvent): Boolean {
        if (isInteracting || isTransformActive) {
            setLocalCursorPos(event.x, event.y)
            invalidate()
            return true
        }
        return super.dispatchHoverEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (isInteracting || isTransformActive) {
            if (event.actionMasked == MotionEvent.ACTION_HOVER_MOVE) {
                setLocalCursorPos(event.x, event.y)
                invalidate()
                return true
            }
        }
        return super.dispatchGenericMotionEvent(event)
    }

    // -------------------------------------------------------------
    // 1. 悬停处理 (空中手写笔 / 鼠标) - 原生硬件级重绘，极速低延迟
    // -------------------------------------------------------------
    override fun onHoverEvent(event: MotionEvent): Boolean {
        val v = vm ?: return super.onHoverEvent(event)
        val hideCursor = (tool == Tool.BRUSH || tool == Tool.ERASER || tool == Tool.SMUDGE || tool == Tool.LIQUIFY) &&
            v.cursorStyleMode != 4

        when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> {
                val predPos = calculatePredictedHoverPos(event.x, event.y, event.eventTime)
                setLocalCursorPos(predPos.x, predPos.y)
                vm?.lastPointerScreenPosition = Offset(event.x, event.y)
                val overUi = isHoverOverUi(event.x, event.y)
                localIsHovering = !overUi
                localIsTouching = false
                localPressure = 1f
                physicalEraserActive = (event.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER)
                invalidateCursor(predPos)

                // S Pen 悬空侧键: 按下/释放边沿时把事件交给驱动层状态机
                // (悬停事件默认只更新光标, 从未到达 SamsungStylusAdapter, 导致悬空侧键动作失效)
                val hoverButton = event.buttonState
                if (hoverButton != 0 || lastHoverButtonState != 0) {
                    getOrCreateStylusDriver()?.onStylusMotionEvent(event)
                }
                lastHoverButtonState = hoverButton

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    val targetIcon = if (!overUi && hideCursor && systemNullPointer != null) {
                        systemNullPointer
                    } else {
                        systemDefaultPointer
                    }
                    if (targetIcon != null && pointerIcon != targetIcon) {
                        pointerIcon = targetIcon
                    }
                }
                return true
            }
            MotionEvent.ACTION_HOVER_EXIT -> {
                // 侧键仍被追踪为按下时笔已离开悬停场: 通知驱动层冲销挂起的按压
                lastHoverEventTimeMs = 0L
                if (lastHoverButtonState != 0) {
                    getOrCreateStylusDriver()?.onStylusHoverExited()
                    lastHoverButtonState = 0
                }
                physicalEraserActive = false
                localIsHovering = false
                localIsTouching = false
                clearLocalCursorPos()
                invalidateCursor(null)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    if (systemDefaultPointer != null && pointerIcon != systemDefaultPointer) {
                        pointerIcon = systemDefaultPointer
                    }
                }
                return true
            }
        }
        return super.onHoverEvent(event)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        val driver = getOrCreateStylusDriver()
        if (driver?.onGenericMotionEvent(event) == true) {
            return true
        }
        if (event.isFromSource(android.view.InputDevice.SOURCE_CLASS_POINTER)) {
            if (event.actionMasked == MotionEvent.ACTION_HOVER_MOVE) {
                val predPos = calculatePredictedHoverPos(event.x, event.y, event.eventTime)
                setLocalCursorPos(predPos.x, predPos.y)
                vm?.lastPointerScreenPosition = Offset(event.x, event.y)
                val overUi = isHoverOverUi(event.x, event.y)
                localIsHovering = !overUi
                localIsTouching = false
                invalidateCursor(predPos)

                // 与 onHoverEvent 一致: 悬空侧键边沿接入驱动层 (部分 ROM 从此路径派发悬停)
                val hoverButton = event.buttonState
                if (hoverButton != 0 || lastHoverButtonState != 0) {
                    getOrCreateStylusDriver()?.onStylusMotionEvent(event)
                }
                lastHoverButtonState = hoverButton
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    val v = vm
                    val hideCursor = (tool == Tool.BRUSH || tool == Tool.ERASER || tool == Tool.SMUDGE || tool == Tool.LIQUIFY) &&
                        (v?.cursorStyleMode != 4)
                    val targetIcon = if (v?.isSpacePanning == true) {
                        if (isSpaceDragging) systemGrabPointer ?: systemHandPointer else systemHandPointer ?: systemDefaultPointer
                    } else if (!overUi && hideCursor && systemNullPointer != null) {
                        systemNullPointer
                    } else {
                        systemDefaultPointer
                    }
                    if (targetIcon != null && pointerIcon != targetIcon) {
                        pointerIcon = targetIcon
                    }
                }
                return true
            }
        }
        return super.onGenericMotionEvent(event)
    }

    // -------------------------------------------------------------
    // 2. 接触触控处理 (手写笔落笔绘画 vs 手指画布导航)
    // -------------------------------------------------------------
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val v = vm ?: return super.onTouchEvent(event)
        if (v.isQuickShapeEditing) return true
        if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            removeCallbacks(quickShapeHold)
            quickShapeCandidate = null
            v.quickShapeCapture = null
        }
        if (event.actionMasked == MotionEvent.ACTION_DOWN && editMenuGestureActive) {
            removeCallbacks(continuousRedoRunnable)
            editMenuGestureActive = false
            isContinuousUndoing = false
            isTransformActive = false
            isPinchMotion = false
            maxTouchPointers = 0
        }

        // 空格键长按临时抓手平移 (Spacebar Hold-to-Pan)
        if (v.isSpacePanning) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // 空格抓手平移同样写画布变换, 且此分支在统一取消点之前 return:
                    // 这里先终止动画并失效待执行帧, 避免与旧动画同帧竞争
                    cancelCanvasTransformAnimators()
                    spacePanStartPos = Offset(event.x, event.y)
                    spacePanInitialPan = Offset(canvasPanX, canvasPanY)
                    isSpaceDragging = true
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && systemGrabPointer != null) {
                        pointerIcon = systemGrabPointer
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (isSpaceDragging) {
                        val dx = event.x - spacePanStartPos.x
                        val dy = event.y - spacePanStartPos.y
                        canvasPanX = spacePanInitialPan.x + dx
                        canvasPanY = spacePanInitialPan.y + dy
                        onTransform?.invoke(canvasZoom, canvasRotation, canvasPanX, canvasPanY)
                        invalidate()
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    isSpaceDragging = false
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && systemHandPointer != null) {
                        pointerIcon = systemHandPointer
                    }
                }
            }
            return true
        }
        // 任何新的接触 (新手势第一个触点 / 新落下的触点) 都可能成为画布变换的新写入者:
        // 先终止吸附收敛与满屏复位动画并失效其待执行帧, 避免与手势同帧竞争画布变换
        val touchMask = event.actionMasked
        if (touchMask == MotionEvent.ACTION_DOWN || touchMask == MotionEvent.ACTION_POINTER_DOWN) {
            cancelCanvasTransformAnimators()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val mask = event.actionMasked
            if (mask == MotionEvent.ACTION_DOWN || mask == MotionEvent.ACTION_POINTER_DOWN) {
                try {
                    requestUnbufferedDispatch(event)
                } catch (_: Throwable) {}
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    try {
                        requestUnbufferedDispatch(android.view.InputDevice.SOURCE_STYLUS)
                        requestUnbufferedDispatch(android.view.InputDevice.SOURCE_TOUCHSCREEN)
                    } catch (_: Throwable) {}
                }
            }
        }
        val pointerCount = event.pointerCount

        // 取消 pending 的撤销或会话重置
        pendingUndoRunnable?.let { removeCallbacks(it) }
        removeCallbacks(resetTransformRunnable)

        // 分离手写笔 Pointer 与手指 Pointer (零堆分配)
        fingerCount = 0
        var stylusPointerIndex = -1

        for (i in 0 until pointerCount) {
            val toolType = event.getToolType(i)
            if (toolType == MotionEvent.TOOL_TYPE_STYLUS || toolType == MotionEvent.TOOL_TYPE_ERASER || toolType == MotionEvent.TOOL_TYPE_MOUSE) {
                stylusPointerIndex = i
            } else {
                if (fingerCount < fingerIndices.size) {
                    fingerIndices[fingerCount++] = i
                }
            }
        }

        // 软开关：若已开启“禁用画布触控”，拦截一切手指交互，专供手写笔与鼠标操作
        if (v.isCanvasTouchDisabled) {
            if (stylusPointerIndex < 0) {
                // 纯手指接触：静默丢弃所有事件（无反馈、无弹窗、不作画、不手势）
                if (strokeStarted) {
                    cachedDriver?.feedbackManager?.setWritingHapticsEnabled(false)
                    cachedDriver?.feedbackManager?.stopStrokeSound()
                    v.touchCancel()
                    strokeStarted = false
                    safeEndSymmetryUndoMacro()
                    resetMirrorBranches()
                    clearPredictionState(triggerInvalidate = true)
                }
                cancelPendingShapeGesture()
                isTransformActive = false
                isContinuousUndoing = false
                return true
            }
            // 存在手写笔 Pointer 时，强制清除手指计数，避免手掌贴屏引发视口晃动或多指手势
            fingerCount = 0
        }

        // 手写笔触控判定：存在手写笔 Pointer 且未处于双指画布手势导航中
        // (只要当前未处于双指手势，任何手指接触均视为手掌接触，优先保证手写笔落笔防误触)
        val isStylusTouch = !editMenuGestureActive && stylusPointerIndex >= 0 && (!isTransformActive || fingerCount < 2)

        // 侧键按住=临时橡皮 (Samsung Notes 语义) 或 物理橡皮尾接触: 每个笔接触事件重判, 纯手指路径清残留
        if (isStylusTouch) {
            tempEraseActive = getOrCreateStylusDriver()?.isSideButtonEraseActive(event) == true
            physicalEraserActive = (event.getToolType(stylusPointerIndex) == MotionEvent.TOOL_TYPE_ERASER)
        } else if (fingerCount > 0) {
            tempEraseActive = false
            physicalEraserActive = false
        }

        // =========================================================
        // 滤镜调节模式手势交互 (单指/笔横划调节参数，长按对比原图；双指保留视口变换)
        // =========================================================
        if (filterSessionActive) {
            if (fingerCount >= 2 || isTransformActive) {
                removeCallbacks(filterLongPressRunnable)
                if (isFilterComparing) {
                    isFilterComparing = false
                    onFilterHoldingCompare?.invoke(false)
                }
            } else {
                val pIdx = if (isStylusTouch) stylusPointerIndex else (if (fingerCount > 0) fingerIndices[0] else 0)
                val curX = event.getX(pIdx)
                val curY = event.getY(pIdx)
                val nowMs = SystemClock.uptimeMillis()

                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        touchDownTimeMs = nowMs
                        filterTouchStartX = curX
                        filterTouchStartY = curY
                        previousSinglePos = Offset(curX, curY)
                        isFilterDragging = false
                        isFilterComparing = false
                        removeCallbacks(filterLongPressRunnable)
                        postDelayed(filterLongPressRunnable, 300)
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = curX - previousSinglePos.x
                        val totalDist = hypot(curX - filterTouchStartX, curY - filterTouchStartY)
                        val touchSlop = 8f * density
                        if (totalDist > touchSlop) {
                            if (!isFilterDragging) {
                                isFilterDragging = true
                                removeCallbacks(filterLongPressRunnable)
                                if (isFilterComparing) {
                                    isFilterComparing = false
                                    onFilterHoldingCompare?.invoke(false)
                                }
                            }
                            previousSinglePos = Offset(curX, curY)
                            val deltaRatio = dx / (viewW.toFloat().coerceAtLeast(200f))
                            onFilterSlideDelta?.invoke(deltaRatio)
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        removeCallbacks(filterLongPressRunnable)
                        if (isFilterComparing) {
                            isFilterComparing = false
                            onFilterHoldingCompare?.invoke(false)
                        }
                        isFilterDragging = false
                    }
                }
                return true
            }
        }

        // =========================================================
        // A. 手写笔交互流程：100% 负责笔刷绘制与图层编辑
        // =========================================================
        if (isStylusTouch) {
            val driver = getOrCreateStylusDriver()
            driver?.onStylusMotionEvent(event)

            val x = event.getX(stylusPointerIndex)
            val y = event.getY(stylusPointerIndex)
            val screenPos = Offset(x, y)
            val docPos = screenToDoc(screenPos)
            val rawPressure = event.getPressure(stylusPointerIndex)
            val pressure = if (rawPressure.isNaN()) 1f else rawPressure.coerceIn(0f, 1f)

            setLocalCursorPos(screenPos.x, screenPos.y)
            vm?.lastPointerScreenPosition = screenPos
            localIsTouching = true
            localIsHovering = false
            localPressure = pressure
            // 前缓冲 UI 避让注册表: 每笔重建 (布局/浮窗/左右手状态快照, 非逐事件热路径)
            rebuildUiExclusionRects()

            val hideCursor = (tool == Tool.BRUSH || tool == Tool.ERASER || tool == Tool.SMUDGE || tool == Tool.LIQUIFY) &&
                v.cursorStyleMode != 4
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                if (hideCursor && systemNullPointer != null && pointerIcon != systemNullPointer) {
                    pointerIcon = systemNullPointer
                }
            }

            val canEyedrop = v.longPressEyedropperEnabled &&
                (tool == Tool.BRUSH || tool == Tool.ERASER || tool == Tool.SMUDGE || tool == Tool.LIQUIFY)

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                    // 若是多指接触，只有当刚按下的指针就是手写笔自身时才触发落笔起画；
                    // 手写笔画画时手掌压在屏幕上产生的 ACTION_POINTER_DOWN 直接忽略，不打断笔画
                    if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN && event.actionIndex != stylusPointerIndex) {
                        return true
                    }
                    clearPredictionState(triggerInvalidate = false)
                    removeCallbacks(longPressRunnable)
                    longPressToken++
                    isTransformActive = false
                    isPinchMotion = false
                    isInteracting = true
                    lastPos0 = Offset.Zero
                    lastPos1 = Offset.Zero
                    previousSinglePos = screenPos
                    priorStrokeScreenPos = screenPos
                    val downP = pressure.coerceIn(0.01f, 1f)
                    if (v.frontBufferPredictionEnabled && tool == Tool.BRUSH && screenPos.x.isFinite() && screenPos.y.isFinite()) {
                        universalPredictor.addPoint(screenPos.x, screenPos.y, downP, event.eventTime)
                        recordTouchSample(screenPos.x, screenPos.y, event.eventTime)
                    }
                    firstDocPos = docPos
                    currentStrokeDocPos = docPos
                    shapeEndDocPos = docPos
                    isLongPressPickerActive = false

                    if (checkHitGuideHandle(screenPos)) {
                        isPendingLongPress = false
                        parent?.requestDisallowInterceptTouchEvent(true)
                        invalidate()
                        return true
                    }

                    // 笔尖接触瞬间立即启动绘图，彻底消除长按判定位移容差带来的起笔延迟
                    updateStylusSensors(event, stylusPointerIndex, isStylus = true)
                    handleToolDown(screenPos, docPos, pressure, isStylus = true, touchTiltX, touchTiltY, touchRotation)
                    invalidate()

                    if (canEyedrop) {
                        isPendingLongPress = true
                        activeLongPressToken = longPressToken
                        pendingDownDocPos = docPos
                        pendingDownScreenPos = screenPos
                        pendingDownPressure = pressure
                        val delayMs = (520L - (v.eyedropperSensitivity - 1) * 70L).coerceIn(200L, 600L)
                        postDelayed(longPressRunnable, delayMs)
                    } else {
                        isPendingLongPress = false
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (draggingGuideHandleIndex != -1) {
                        if (handleGuideHandleDrag(docPos)) return true
                    }

                    if (isLongPressPickerActive) {
                        sampleColorAtScreenPos(screenPos)
                        return true
                    }

                    if (isPendingLongPress) {
                        val moveSlopPx = (1.5f + (v.eyedropperSensitivity.coerceIn(1, 5) - 3) * 0.3f).coerceIn(0.6f, 2.5f) * density
                        val moveDist = hypot(screenPos.x - pendingDownScreenPos.x, screenPos.y - pendingDownScreenPos.y)
                        if (moveDist > moveSlopPx) {
                            removeCallbacks(longPressRunnable)
                            longPressToken++
                            isPendingLongPress = false
                        }
                    }
                    currentStrokeDocPos = docPos
                    priorStrokeScreenPos = previousSinglePos
                    handleToolMove(event, stylusPointerIndex, docPos, pressure, isStylus = true)
                    previousSinglePos = screenPos
                    setLocalCursorPos(screenPos.x, screenPos.y)
                    localIsTouching = true
                    // 仅在非笔刷工具、光标跟随模式、绘图辅助生效或预测延伸激活时在 UI 线程重绘
                    val isDrawingTool = tool == Tool.BRUSH || tool == Tool.ERASER || tool == Tool.SMUDGE
                    if (!isDrawingTool) {
                        invalidate()
                    } else {
                        val isEraser = tool == Tool.ERASER
                        val cursorMode = if (isEraser) v.eraserCursorMode else v.brushCursorMode
                        val hasAssist = v.drawingGuide.mode != GuideMode.OFF && v.drawingGuide.assistedDrawing
                        val predictionVisualActive = if (v.frontBufferPredictionEnabled) {
                            hasPredictedScreenPoint || hasDrawnPrediction
                        } else {
                            v.isCurrentBrushPredictionEligible && hasPredictedScreenPoint
                        }
                        if (cursorMode == 1 || cursorMode == 3 || hasAssist || predictionVisualActive) {
                            invalidate()
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                    // 若是多指接触，只有当手写笔自身抬起时才执行抬笔收尾；
                    // 手掌或非手写笔触控点抬起不影响正在进行的手写笔绘制
                    if (event.actionMasked == MotionEvent.ACTION_POINTER_UP && event.actionIndex != stylusPointerIndex) {
                        return true
                    }
                    if (draggingGuideHandleIndex != -1) {
                        draggingGuideHandleIndex = -1
                        isInteracting = false
                        invalidate()
                        return true
                    }

                    clearPredictionState(triggerInvalidate = true)
                    cachedDriver?.feedbackManager?.setWritingHapticsEnabled(false)
                    removeCallbacks(longPressRunnable)
                    longPressToken++
                    isPendingLongPress = false
                    if (isLongPressPickerActive) {
                        val curCol = pickerCurrentColor?.value
                        if (curCol != null) {
                            val r = (curCol.red * 255).toInt().coerceIn(0, 255)
                            val g = (curCol.green * 255).toInt().coerceIn(0, 255)
                            val b = (curCol.blue * 255).toInt().coerceIn(0, 255)
                            val hex = String.format("#%02X%02X%02X", r, g, b)
                            v.updateBrushColor(hex)
                            v.showActionToast(context.getString(R.string.canvas_toast_color_picked), R.drawable.ic_picker)
                        }
                        pickerActive?.value = false
                        isLongPressPickerActive = false
                        localIsTouching = false
                        localIsHovering = true
                        isInteracting = false
                        invalidate()
                        return true
                    }

                    handleToolUp(event, docPos, isCancel = (event.actionMasked == MotionEvent.ACTION_CANCEL))
                    localIsTouching = false
                    localIsHovering = true
                    isInteracting = false
                    currentStrokeDocPos = Offset.Zero
                    invalidate()
                }
            }
            return true
        }

        // =========================================================
        // B. 手指交互流程：画世界 / Procreate 模式 (100% 画布手势导航)
        // =========================================================
        val nowMs = System.currentTimeMillis()
        val numFingers = fingerCount
        maxTouchPointers = maxOf(maxTouchPointers, numFingers)
        isInteracting = true

        // Reserve a fresh three-finger gesture before viewport navigation sees it.
        // Keep ownership through staggered pointer-up events: neither redo nor a stray stroke
        // may run after a swipe. Existing two-finger navigation remains untouched.
        if (!editMenuGestureActive && v.gestureThreeFingerEditMenu && numFingers == 3 &&
            event.actionMasked == MotionEvent.ACTION_POINTER_DOWN && !isPinchMotion &&
            !isContinuousUndoing && !filterSessionActive && !isLiquifyGestureActive &&
            !v.anim.shiftTraceActive && tool != Tool.TRANSFORM && tool != Tool.CROP
        ) {
            editMenuGestureActive = true
            editMenuPointersReleased = false
            editMenuSwipe.begin(event.eventTime, density)
            for (i in 0..2) {
                val index = fingerIndices[i]
                editMenuPointerIds[i] = event.getPointerId(index)
                editMenuSwipe.setStart(i, event.getX(index), event.getY(index))
            }
            removeCallbacks(longPressRunnable)
            longPressToken++
            isPendingLongPress = false
            removeCallbacks(continuousUndoRunnable)
            removeCallbacks(continuousRedoRunnable)
            if (strokeStarted) {
                v.touchCancel()
                strokeStarted = false
                safeEndSymmetryUndoMacro()
                resetMirrorBranches()
                clearPredictionState(triggerInvalidate = true)
            }
            cancelPendingShapeGesture()
            if (v.gestureThreeFingerRedo) postDelayed(continuousRedoRunnable, 420L)
        }
        if (editMenuGestureActive) {
            if (numFingers > 3 || stylusPointerIndex >= 0 || !v.gestureThreeFingerEditMenu ||
                event.actionMasked == MotionEvent.ACTION_CANCEL ||
                (editMenuPointersReleased && event.actionMasked == MotionEvent.ACTION_POINTER_DOWN)
            ) editMenuSwipe.reject()
            if (event.actionMasked == MotionEvent.ACTION_MOVE || event.actionMasked == MotionEvent.ACTION_POINTER_UP) {
                for (i in 0..2) {
                    val index = event.findPointerIndex(editMenuPointerIds[i])
                    if (index < 0 && !editMenuPointersReleased) editMenuSwipe.reject()
                    else if (index < 0) continue
                    else editMenuSwipe.update(i, event.getX(index), event.getY(index))
                }
                // After the first lift, remaining fingers may still move: suppress tap redo,
                // but never recognize a new swipe from fewer than three fingers.
                if (!editMenuPointersReleased && numFingers == 3) editMenuSwipe.evaluate(event.eventTime)
            }
            if (editMenuSwipe.moved || editMenuSwipe.rejected || event.actionMasked == MotionEvent.ACTION_POINTER_UP) {
                removeCallbacks(continuousRedoRunnable)
            }
            if (event.actionMasked == MotionEvent.ACTION_POINTER_UP) editMenuPointersReleased = true
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                removeCallbacks(continuousRedoRunnable)
                if (event.actionMasked == MotionEvent.ACTION_UP && !isContinuousUndoing) {
                    if (editMenuSwipe.swiped && !editMenuSwipe.rejected) v.requestUiCommand("open_edit_menu")
                    else if (editMenuSwipe.isTap(event.eventTime) && v.gestureThreeFingerRedo) v.redo()
                }
                editMenuGestureActive = false
                isContinuousUndoing = false
                isTransformActive = false
                isInteracting = false
                isPinchMotion = false
                maxTouchPointers = 0
                lastPos0 = Offset.Zero
                lastPos1 = Offset.Zero
                invalidate()
            }
            return true
        }

        // 1. 多指手势 (双指捏合缩放/旋转/平移 + 碎片期融合)
        if (numFingers >= 2 || (isTransformActive && (nowMs - lastTransformTimestamp) < 150)) {
            removeCallbacks(longPressRunnable)
            longPressToken++
            isPendingLongPress = false
            if (isPendingEdgeFinger) {
                removeCallbacks(flushEdgeFingerRunnable)
                isPendingEdgeFinger = false
            }

            if (strokeStarted) {
                cachedDriver?.feedbackManager?.setWritingHapticsEnabled(false)
                cachedDriver?.feedbackManager?.stopStrokeSound()
                v.touchCancel()
                strokeStarted = false
                safeEndSymmetryUndoMacro()
                resetMirrorBranches()
                clearPredictionState(triggerInvalidate = true)
            }
            cancelPendingShapeGesture()

            val isShiftTraceAlign = v.anim.shiftTraceActive && v.anim.shiftTraceGestureMode == ShiftTraceGestureMode.ALIGN_FRAME

            if (numFingers >= 2) {
                val idx0 = fingerIndices[0]
                val idx1 = fingerIndices[1]
                val raw0 = Offset(event.getX(idx0), event.getY(idx0))
                val raw1 = Offset(event.getX(idx1), event.getY(idx1))

                val (p0, p1) = if (lastPos0 != Offset.Zero && lastPos1 != Offset.Zero) {
                    val d00_11 = hypot(raw0.x - lastPos0.x, raw0.y - lastPos0.y) + hypot(raw1.x - lastPos1.x, raw1.y - lastPos1.y)
                    val d01_10 = hypot(raw0.x - lastPos1.x, raw0.y - lastPos1.y) + hypot(raw1.x - lastPos0.x, raw1.y - lastPos0.y)
                    if (d01_10 < d00_11) Pair(raw1, raw0) else Pair(raw0, raw1)
                } else {
                    Pair(raw0, raw1)
                }
                lastPos0 = p0
                lastPos1 = p1

                val centroid = Offset((p0.x + p1.x) / 2f, (p0.y + p1.y) / 2f)
                val distance = hypot(p1.x - p0.x, p1.y - p0.y).coerceAtLeast(1f)
                val angle = Math.toDegrees(atan2((p1.y - p0.y).toDouble(), (p1.x - p0.x).toDouble())).toFloat()

                val isNewGesture = !isTransformActive
                val isStaleFragment = (nowMs - lastTransformTimestamp) >= 150

                if (isNewGesture || isStaleFragment) {
                    isTransformActive = true
                    if (isNewGesture) {
                        isPinchMotion = false
                        isTwoFingerRotation = false
                        initialCentroid = centroid
                        initialDistance = distance
                        initialAngle = angle
                        touchDownTimeMs = nowMs
                        onTransform?.invoke(canvasZoom, canvasRotation, canvasPanX, canvasPanY)
                    }
                    isContinuousUndoing = false
                    prevCentroid = centroid
                    prevDistance = distance
                    prevAngle = angle
                    // 新一段画布手势开始: 终止吸附收敛与满屏复位动画并失效其待执行帧, 同时把
                    // 当前生效角交给吸附状态机作为本段手势起点, 避免残留动画与新手势互相拉扯
                    cancelCanvasTransformAnimators()
                    if (isNewGesture) {
                        rotationSnapGesture.begin(canvasRotation)
                        rotationSnapEngaged = false
                    }

                    removeCallbacks(continuousUndoRunnable)
                    removeCallbacks(continuousRedoRunnable)
                    if (!isShiftTraceAlign && isNewGesture) {
                        if (numFingers == 2 && v.gestureTwoFingerUndo) {
                            postDelayed(continuousUndoRunnable, 420L)
                        } else if (numFingers >= 3 && v.gestureThreeFingerRedo) {
                            postDelayed(continuousRedoRunnable, 420L)
                        }
                    }
                } else {
                    val distCentroidMoved = hypot(centroid.x - prevCentroid.x, centroid.y - prevCentroid.y)
                    if (distCentroidMoved > 80f * density) {
                        prevCentroid = centroid
                        prevDistance = distance
                        prevAngle = angle
                    } else if (isShiftTraceAlign) {
                        isPinchMotion = true
                        val target = v.anim.shiftTraceTarget
                        val curTf = if (target == ShiftTraceTarget.PREV) v.anim.shiftTracePrevTransform else v.anim.shiftTraceNextTransform

                        val viewScale = (canvasZoom * canvasFitScale).coerceAtLeast(0.001f)
                        val bmpW = (v.renderW.takeIf { it > 0 } ?: v.docWidth.coerceAtLeast(1)).toFloat()
                        val docW = v.docWidth.coerceAtLeast(1).toFloat()
                        val docToBmpRatio = bmpW / docW

                        val dScreenX = centroid.x - prevCentroid.x
                        val dScreenY = centroid.y - prevCentroid.y
                        val viewRad = -Math.toRadians(canvasRotation.toDouble())
                        val cosV = kotlin.math.cos(viewRad).toFloat()
                        val sinV = kotlin.math.sin(viewRad).toFloat()
                        val unrotDx = (dScreenX * cosV - dScreenY * sinV) / viewScale
                        val unrotDy = (dScreenX * sinV + dScreenY * cosV) / viewScale
                        val docDx = unrotDx / docToBmpRatio
                        val docDy = unrotDy / docToBmpRatio

                        val k = (distance / prevDistance).coerceIn(0.7f, 1.4f)
                        val dRot = normalizeAngle(angle - prevAngle).coerceIn(-15f, 15f)

                        val newScale = (curTf.scale * k).coerceIn(0.05f, 20f)
                        val newRot = normalizeAngle(curTf.rotation + dRot)
                        val newTrans = curTf.translation + Offset(docDx, docDy)

                        val updated = ShiftTransform(
                            translation = newTrans,
                            rotation = newRot,
                            scale = newScale,
                        )
                        if (target == ShiftTraceTarget.PREV) {
                            v.anim.shiftTracePrevTransform = updated
                        } else {
                            v.anim.shiftTraceNextTransform = updated
                        }
                        prevCentroid = centroid
                        prevDistance = distance
                        prevAngle = angle
                        invalidate()
                    } else {
                        val k = (distance / prevDistance).coerceIn(0.7f, 1.4f)
                        val isLocked = v.isViewTransformLocked
                        val snapThreshold = if (v.canvasRotationEnabled && !isLocked) v.canvasRotationSnapDegrees else 0f
                        // 用实际生效角增量补偿平移，吸附时仍以双指中心为旋转中心。
                        val dRot: Float
                        if (v.canvasRotationEnabled && !isLocked) {
                            val rawDelta = normalizeAngle(angle - prevAngle).coerceIn(-15f, 15f)
                            // 退化输入防御: 非有限增量按 0 处理, 不得把 NaN 累积进吸附状态与画布角度
                            val safeDelta = if (rawDelta.isFinite()) rawDelta else 0f
                            // 模型负责旋转意图、精确锁定和脱离吸附时的连续衔接。
                            val snapped = rotationSnapGesture.update(safeDelta, snapThreshold)
                            dRot = snapped - canvasRotation
                            canvasRotation = snapped
                            val engaged = rotationSnapGesture.isSnapped
                            if (engaged && !rotationSnapEngaged) {
                                // 进入吸附区沿: 轻微触觉反馈 + 上层高亮角度 HUD (视觉反馈)
                                performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                                onRotationSnap?.invoke(canvasRotation)
                            }
                            rotationSnapEngaged = engaged
                        } else {
                            dRot = 0f
                            rotationSnapGesture.begin(canvasRotation)
                            rotationSnapEngaged = false
                        }
                        val rad = Math.toRadians(dRot.toDouble())
                        val cosR = kotlin.math.cos(rad).toFloat()
                        val sinR = kotlin.math.sin(rad).toFloat()

                        val totalMoved = hypot(centroid.x - initialCentroid.x, centroid.y - initialCentroid.y)
                        // 捏合判定统一用"手指真实移动了多少像素"衡量, 三个判据同量纲。
                        // 旧写法用比例(缩放 2%)与绝对角度(2°), 两者的实际灵敏度都随
                        // 手指间距反比放大: 间距 40px 时 0.8px 的抖动即判成捏合, 于是
                        // 手指并拢的双指/三指轻点几乎必然被吞掉 (三指重做手指靠近就失效)。
                        // 间距张开时新旧阈值量级相当, 手感不变。
                        val spreadMoved = abs(distance - initialDistance) * 0.5f
                        val angleDiff = abs(normalizeAngle(angle - initialAngle))
                        // 门控判据 (isPinchMotion) 保持改动前的三项旧判据逐点不变, 其中旋转项仍是旧
                        // 公式 `角差 × 指距 × 0.5` —— 三指重做 (maxTouchPointers >= 3) 与三指手势
                        // 消费的正是这个标记, 因此三指路径行为不发生任何漂移。
                        val legacyArcMoved = Math.toRadians(angleDiff.toDouble()).toFloat() * initialDistance * 0.5f
                        val isMotion = TwoFingerGesturePolicy.isMotion(
                            centroidMovedPx = totalMoved,
                            spreadMovedPx = spreadMoved,
                            rotationArcPx = legacyArcMoved,
                            moveTolerancePx = 6f * density,
                            spreadTolerancePx = 3f * density,
                            arcTolerancePx = 3f * density,
                        )
                        if (isMotion) {
                            isPinchMotion = true
                            removeCallbacks(continuousUndoRunnable)
                            removeCallbacks(continuousRedoRunnable)
                        }
                        // 双指专用修正: 旧公式在双指并拢时几乎失敏 —— 间距 40px 时可转 20° 以上仍被
                        // 判成"轻点", 双指轻点撤销会吞掉旋转手势 (既多触发一次撤销, 又让旋转结束后
                        // 的 90° 吸附收敛不执行)。这里给旋转弧长加参考半径下限, 结果只写入独立的
                        // isTwoFingerRotation (仅抑制双指撤销, 不动 isPinchMotion); 判据内部对 >= 3 指
                        // 仍返回旧公式, 双保险保证三指等价。
                        if (
                            TwoFingerGesturePolicy.isStrictTwoFingerRotation(
                                angleDiffDegrees = angleDiff,
                                spacingPx = initialDistance,
                                density = density,
                                arcTolerancePx = 3f * density,
                                fingerCount = numFingers,
                            )
                        ) {
                            isTwoFingerRotation = true
                            removeCallbacks(continuousUndoRunnable)
                        }

                        // 围绕双指中心 (prevCentroid) 几何旋转与缩放补偿，保证手指标定点完全不动
                        val vx = prevCentroid.x - (viewW / 2f + canvasPanX)
                        val vy = prevCentroid.y - (viewH / 2f + canvasPanY)

                        val targetZoom = if (isLocked) canvasZoom else (canvasZoom * k).coerceIn(0.02f, 128f)
                        val actualK = if (canvasZoom > 0.0001f) targetZoom / canvasZoom else 1f

                        val vRotX = actualK * (vx * cosR - vy * sinR)
                        val vRotY = actualK * (vx * sinR + vy * cosR)

                        canvasZoom = targetZoom
                        canvasPanX = centroid.x - vRotX - viewW / 2f
                        canvasPanY = centroid.y - vRotY - viewH / 2f

                        onTransform?.invoke(canvasZoom, canvasRotation, canvasPanX, canvasPanY)
                        invalidate()

                        prevCentroid = centroid
                        prevDistance = distance
                        prevAngle = angle
                    }
                }
                lastTransformTimestamp = nowMs
            } else if (numFingers == 1 && isTransformActive) {
                // 驱动碎片期（单指短暂存活）：持续补偿平移，零丢帧
                removeCallbacks(continuousUndoRunnable)
                removeCallbacks(continuousRedoRunnable)
                val idx0 = fingerIndices[0]
                val cur = Offset(event.getX(idx0), event.getY(idx0))
                val dist0 = hypot(cur.x - lastPos0.x, cur.y - lastPos0.y)
                val dist1 = hypot(cur.x - lastPos1.x, cur.y - lastPos1.y)

                if (isShiftTraceAlign) {
                    val (dx, dy) = if (dist0 < dist1 && dist0 < 60f * density) {
                        val d = Pair(cur.x - lastPos0.x, cur.y - lastPos0.y)
                        lastPos0 = cur
                        prevCentroid = prevCentroid + Offset(d.first / 2f, d.second / 2f)
                        d
                    } else if (dist1 <= dist0 && dist1 < 60f * density) {
                        val d = Pair(cur.x - lastPos1.x, cur.y - lastPos1.y)
                        lastPos1 = cur
                        prevCentroid = prevCentroid + Offset(d.first / 2f, d.second / 2f)
                        d
                    } else {
                        lastPos0 = cur
                        Pair(0f, 0f)
                    }
                    if (hypot(dx, dy) > 0.2f) {
                        val target = v.anim.shiftTraceTarget
                        val curTf = if (target == ShiftTraceTarget.PREV) v.anim.shiftTracePrevTransform else v.anim.shiftTraceNextTransform
                        val viewScale = (canvasZoom * canvasFitScale).coerceAtLeast(0.001f)
                        val bmpW = (v.renderW.takeIf { it > 0 } ?: v.docWidth.coerceAtLeast(1)).toFloat()
                        val docW = v.docWidth.coerceAtLeast(1).toFloat()
                        val docToBmpRatio = bmpW / docW
                        val viewRad = -Math.toRadians(canvasRotation.toDouble())
                        val cosV = kotlin.math.cos(viewRad).toFloat()
                        val sinV = kotlin.math.sin(viewRad).toFloat()
                        val unrotDx = (dx * cosV - dy * sinV) / viewScale
                        val unrotDy = (dx * sinV + dy * cosV) / viewScale
                        val docDx = unrotDx / docToBmpRatio
                        val docDy = unrotDy / docToBmpRatio
                        val updated = curTf.copy(translation = curTf.translation + Offset(docDx, docDy))
                        if (target == ShiftTraceTarget.PREV) {
                            v.anim.shiftTracePrevTransform = updated
                        } else {
                            v.anim.shiftTraceNextTransform = updated
                        }
                        invalidate()
                    }
                    lastTransformTimestamp = nowMs
                } else if (dist0 < dist1 && dist0 < 60f * density) {
                    val dx = cur.x - lastPos0.x
                    val dy = cur.y - lastPos0.y
                    lastPos0 = cur
                    prevCentroid = prevCentroid + Offset(dx / 2f, dy / 2f)
                    canvasPanX += dx
                    canvasPanY += dy
                    if (hypot(dx, dy) > 2f) isPinchMotion = true
                    lastTransformTimestamp = nowMs
                    onTransform?.invoke(canvasZoom, canvasRotation, canvasPanX, canvasPanY)
                    invalidate()
                } else if (dist1 <= dist0 && dist1 < 60f * density) {
                    val dx = cur.x - lastPos1.x
                    val dy = cur.y - lastPos1.y
                    lastPos1 = cur
                    prevCentroid = prevCentroid + Offset(dx / 2f, dy / 2f)
                    canvasPanX += dx
                    canvasPanY += dy
                    if (hypot(dx, dy) > 2f) isPinchMotion = true
                    lastTransformTimestamp = nowMs
                    onTransform?.invoke(canvasZoom, canvasRotation, canvasPanX, canvasPanY)
                    invalidate()
                } else {
                    lastPos0 = cur
                    lastTransformTimestamp = nowMs
                }
            }

            when (event.actionMasked) {
                MotionEvent.ACTION_POINTER_UP -> {
                    removeCallbacks(continuousUndoRunnable)
                    removeCallbacks(continuousRedoRunnable)
                }
                MotionEvent.ACTION_UP -> {
                    removeCallbacks(continuousUndoRunnable)
                    removeCallbacks(continuousRedoRunnable)
                    val durationMs = nowMs - touchDownTimeMs
                    isInteracting = false

                    // Procreate Quick-Pinch to Fit Canvas (高门槛防误触 + 平滑复位动画)
                    val isQuickPinchFit = v.gestureQuickPinchFit &&
                        maxTouchPointers == 2 &&
                        durationMs in 60L..250L &&
                        initialDistance > 130f * density &&
                        prevDistance < initialDistance * 0.45f &&
                        (initialDistance - prevDistance) / durationMs > 0.60f * density

                    if (isShiftTraceAlign) {
                        // 透光台对位手势完成：不触发满屏复位或历史撤销
                    } else if (isQuickPinchFit) {
                        animateFitCanvas()
                        v.showActionToast(context.getString(R.string.canvas_toast_fit_reset), R.drawable.ic_refresh)
                    } else if (!isContinuousUndoing && !isPinchMotion && !isTwoFingerRotation && !filterSessionActive && maxTouchPointers == 2 && v.gestureTwoFingerUndo && durationMs < 360L) {
                        v.undo()
                    } else if (!isContinuousUndoing && !isPinchMotion && !filterSessionActive && maxTouchPointers >= 3 && v.gestureThreeFingerRedo && durationMs < 380L) {
                        v.redo()
                    } else if (rotationSnapGesture.isActive && !v.isViewTransformLocked) {
                        // 正常画布手势结束: 若停在 90° 倍数吸附区内, 平滑收敛到精确倍数
                        settleRotationToSnap(prevCentroid.x, prevCentroid.y)
                    }

                    isContinuousUndoing = false
                    isTransformActive = false
                    isPinchMotion = false
                    isTwoFingerRotation = false
                    maxTouchPointers = 0
                    lastPos0 = Offset.Zero
                    lastPos1 = Offset.Zero
                    invalidate()
                }
                MotionEvent.ACTION_CANCEL -> {
                    removeCallbacks(continuousUndoRunnable)
                    removeCallbacks(continuousRedoRunnable)
                    isContinuousUndoing = false
                    if (strokeStarted) {
                        cachedDriver?.feedbackManager?.setWritingHapticsEnabled(false)
                        cachedDriver?.feedbackManager?.stopStrokeSound()
                        v.touchCancel()
                        strokeStarted = false
                        safeEndSymmetryUndoMacro()
                        resetMirrorBranches()
                        clearPredictionState(triggerInvalidate = true)
                    }
                    cancelPendingShapeGesture()
                    postDelayed(resetTransformRunnable, 150)
                    invalidate()
                }
            }
            return true
        }

        // 2. 单指手势 (根据 vm.penOnlyMode 切换手指平移 vs 手指作画)
        val singleFingerIdx = if (fingerCount > 0) fingerIndices[0] else 0
        val screenPos = Offset(event.getX(singleFingerIdx), event.getY(singleFingerIdx))
        val docPos = screenToDoc(screenPos)
        val isDrawingTool = tool == Tool.BRUSH || tool == Tool.ERASER || tool == Tool.SMUDGE || tool == Tool.LIQUIFY
        val canEyedrop = v.longPressEyedropperEnabled && isDrawingTool
        val isPenOnlyPan = v.penOnlyMode && v.penModeSingleFingerPanEnabled && (
            tool.group == ToolGroup.BRUSH ||
            tool.group == ToolGroup.SELECTION ||
            tool.group == ToolGroup.SHAPES ||
            tool.group == ToolGroup.FILL ||
            tool == Tool.LIQUIFY ||
            tool == Tool.PICKER ||
            tool == Tool.MEASURE ||
            tool == Tool.TEXT
        )

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchDownTimeMs = nowMs
                maxTouchPointers = 1
                previousSinglePos = screenPos
                priorStrokeScreenPos = screenPos
                clearPredictionState(triggerInvalidate = false)
                if (v.frontBufferPredictionEnabled && tool == Tool.BRUSH && screenPos.x.isFinite() && screenPos.y.isFinite()) {
                    universalPredictor.addPoint(screenPos.x, screenPos.y, 1f, event.eventTime)
                    recordTouchSample(screenPos.x, screenPos.y, event.eventTime)
                }
                firstDocPos = docPos
                currentStrokeDocPos = docPos
                shapeEndDocPos = docPos
                isLongPressPickerActive = false
                removeCallbacks(longPressRunnable)
                longPressToken++
                if (isPendingEdgeFinger) {
                    removeCallbacks(flushEdgeFingerRunnable)
                    isPendingEdgeFinger = false
                }

                if (checkHitGuideHandle(screenPos)) {
                    return true
                }

                val curW = if (width > 0) width else viewW
                val edgeThreshold = (32 * density)
                val isEdgeTouch = screenPos.x <= edgeThreshold || (curW > 0 && screenPos.x >= curW - edgeThreshold)
                val isEdgeBackAllowed = (v.allowEdgeBackGesture && v.backKeyAction != BackKeyAction.NONE) || overlayPanelsOpen

                if (isEdgeTouch && isEdgeBackAllowed && !isPenOnlyPan && !v.penOnlyMode) {
                    isPendingEdgeFinger = true
                    pendingEdgeScreenPos = screenPos
                    pendingEdgeDocPos = docPos
                    postDelayed(flushEdgeFingerRunnable, 180L)
                    return true
                }

                if (canEyedrop) {
                    isPendingLongPress = true
                    activeLongPressToken = longPressToken
                    pendingDownDocPos = docPos
                    pendingDownScreenPos = screenPos
                    pendingDownPressure = 1f
                    val delayMs = (520L - (v.eyedropperSensitivity - 1) * 70L).coerceIn(200L, 600L)
                    postDelayed(longPressRunnable, delayMs)
                    if (!v.penOnlyMode) {
                        setLocalCursorPos(screenPos.x, screenPos.y)
                        localIsTouching = true
                        localIsHovering = false
                        localPressure = 1f
                        invalidate()
                    }
                } else if (!isPenOnlyPan) {
                    if (v.penOnlyMode) {
                        // 笔模式下且未开启单指平移：忽略单指触控输入，防止手掌误触作画
                        return true
                    }
                    isPendingLongPress = false
                    setLocalCursorPos(screenPos.x, screenPos.y)
                    localIsTouching = true
                    localIsHovering = false
                    localPressure = 1f
                    invalidate()
                    handleToolDown(screenPos, docPos, 1f, isStylus = false)
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val deltaX = screenPos.x - previousSinglePos.x
                val deltaY = screenPos.y - previousSinglePos.y
                priorStrokeScreenPos = previousSinglePos
                previousSinglePos = screenPos

                if (draggingGuideHandleIndex != -1) {
                    if (handleGuideHandleDrag(docPos)) return true
                }

                if (isLongPressPickerActive) {
                    sampleColorAtScreenPos(screenPos)
                    return true
                }

                if (isPendingEdgeFinger) {
                    val dx = screenPos.x - pendingEdgeScreenPos.x
                    val dy = screenPos.y - pendingEdgeScreenPos.y
                    val dist = hypot(dx, dy)
                    val isVerticalMove = abs(dy) > abs(dx) * 1.2f && dist > (8 * density)
                    val isDeepMove = dist > (36 * density)
                    if (isVerticalMove || isDeepMove) {
                        removeCallbacks(flushEdgeFingerRunnable)
                        val downScreen = pendingEdgeScreenPos
                        val downDoc = pendingEdgeDocPos
                        isPendingEdgeFinger = false
                        if (!v.penOnlyMode) {
                            setLocalCursorPos(screenPos.x, screenPos.y)
                            localIsTouching = true
                            localIsHovering = false
                            localPressure = 1f
                            handleToolDown(downScreen, downDoc, 1f, isStylus = false)
                            handleToolMove(event, 0, docPos, 1f, isStylus = false)
                            invalidate()
                            return true
                        }
                    } else {
                        // 仍在边缘侧滑判定区中，暂缓落笔，避免抢占系统侧滑或在边缘误画
                        return true
                    }
                }

                if (isPendingLongPress) {
                    val moveSlopPx = (1.5f + (v.eyedropperSensitivity.coerceIn(1, 5) - 3) * 0.3f).coerceIn(0.6f, 2.5f) * density
                    val moveDist = hypot(screenPos.x - pendingDownScreenPos.x, screenPos.y - pendingDownScreenPos.y)
                    if (moveDist > moveSlopPx) {
                        removeCallbacks(longPressRunnable)
                        longPressToken++
                        isPendingLongPress = false
                        if (!v.penOnlyMode) {
                            setLocalCursorPos(screenPos.x, screenPos.y)
                            localIsTouching = true
                            handleToolDown(pendingDownScreenPos, pendingDownDocPos, pendingDownPressure, isStylus = false)
                            handleToolMove(event, 0, docPos, 1f, isStylus = false)
                            invalidate()
                            return true
                        }
                    } else {
                        // 处于长按吸色等待期，位移未超容差，防止手指微动误触发平移或画线
                        return true
                    }
                }

                val isShiftTraceAlign = v.anim.shiftTraceActive && v.anim.shiftTraceGestureMode == ShiftTraceGestureMode.ALIGN_FRAME
                if (isPenOnlyPan) {
                    if (isShiftTraceAlign) {
                        val target = v.anim.shiftTraceTarget
                        val curTf = if (target == ShiftTraceTarget.PREV) v.anim.shiftTracePrevTransform else v.anim.shiftTraceNextTransform
                        val viewScale = (canvasZoom * canvasFitScale).coerceAtLeast(0.001f)
                        val bmpW = (v.renderW.takeIf { it > 0 } ?: v.docWidth.coerceAtLeast(1)).toFloat()
                        val docW = v.docWidth.coerceAtLeast(1).toFloat()
                        val docToBmpRatio = bmpW / docW
                        val viewRad = -Math.toRadians(canvasRotation.toDouble())
                        val cosV = kotlin.math.cos(viewRad).toFloat()
                        val sinV = kotlin.math.sin(viewRad).toFloat()
                        val unrotDx = (deltaX * cosV - deltaY * sinV) / viewScale
                        val unrotDy = (deltaX * sinV + deltaY * cosV) / viewScale
                        val docDx = unrotDx / docToBmpRatio
                        val docDy = unrotDy / docToBmpRatio
                        val updated = curTf.copy(translation = curTf.translation + Offset(docDx, docDy))
                        if (target == ShiftTraceTarget.PREV) {
                            v.anim.shiftTracePrevTransform = updated
                        } else {
                            v.anim.shiftTraceNextTransform = updated
                        }
                        invalidate()
                        return true
                    } else {
                        // 笔模式开启且处于绘图工具：单指丝滑平移画布
                        canvasPanX += deltaX
                        canvasPanY += deltaY
                        onTransform?.invoke(canvasZoom, canvasRotation, canvasPanX, canvasPanY)
                        invalidate()
                        return true
                    }
                } else {
                    if (v.penOnlyMode) {
                        // 笔模式下且未开启单指平移：忽略单指移动
                        return true
                    }
                    setLocalCursorPos(screenPos.x, screenPos.y)
                    localIsTouching = true
                    handleToolMove(event, 0, docPos, 1f, isStylus = false)
                    invalidate()
                    return true
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                clearPredictionState(triggerInvalidate = true)
                removeCallbacks(longPressRunnable)
                longPressToken++
                removeCallbacks(flushEdgeFingerRunnable)
                isInteracting = false

                if (draggingGuideHandleIndex != -1) {
                    draggingGuideHandleIndex = -1
                    return true
                }

                if (isLongPressPickerActive) {
                    val curCol = pickerCurrentColor?.value
                    if (curCol != null) {
                        val r = (curCol.red * 255).toInt().coerceIn(0, 255)
                        val g = (curCol.green * 255).toInt().coerceIn(0, 255)
                        val b = (curCol.blue * 255).toInt().coerceIn(0, 255)
                        val hex = String.format("#%02X%02X%02X", r, g, b)
                        v.updateBrushColor(hex)
                        v.showActionToast(context.getString(R.string.canvas_toast_color_picked), R.drawable.ic_picker)
                    }
                    pickerActive?.value = false
                    isLongPressPickerActive = false
                    localIsTouching = false
                    clearLocalCursorPos()
                    invalidate()
                    return true
                }

                if (isPendingEdgeFinger) {
                    val downScreen = pendingEdgeScreenPos
                    val downDoc = pendingEdgeDocPos
                    isPendingEdgeFinger = false
                    if (event.actionMasked != MotionEvent.ACTION_CANCEL && !v.penOnlyMode) {
                        handleToolDown(downScreen, downDoc, 1f, isStylus = false)
                        handleToolUp(event, downDoc, isCancel = false)
                    }
                    localIsTouching = false
                    clearLocalCursorPos()
                    invalidate()
                    return true
                }

                if (isPendingLongPress) {
                    isPendingLongPress = false
                    if (!v.penOnlyMode && event.actionMasked != MotionEvent.ACTION_CANCEL) {
                        handleToolDown(pendingDownScreenPos, pendingDownDocPos, pendingDownPressure, isStylus = false)
                        handleToolUp(event, pendingDownDocPos, isCancel = false)
                    }
                    localIsTouching = false
                    clearLocalCursorPos()
                    invalidate()
                    return true
                }

                if (!isPenOnlyPan) {
                    if (v.penOnlyMode) {
                        localIsTouching = false
                        clearLocalCursorPos()
                        invalidate()
                        return true
                    }
                    handleToolUp(event, docPos, isCancel = (event.actionMasked == MotionEvent.ACTION_CANCEL))
                    localIsTouching = false
                    clearLocalCursorPos()
                    invalidate()
                }
                return true
            }
        }

        return super.onTouchEvent(event)
    }

    private fun handleToolDown(
        screenPos: Offset,
        docPos: Offset,
        pressure: Float,
        isStylus: Boolean,
        tiltX: Double = 0.0,
        tiltY: Double = 0.0,
        rotation: Double = 0.0,
    ) {
        val v = vm ?: return
        removeCallbacks(quickShapeHold)
        quickShapeCandidate = null
        v.quickShapeCapture = null
        val activeLayer = v.layers.firstOrNull { it.index == v.currentLayerIndex }
        val t = effTool()
        val isDrawingTool = t.group == ToolGroup.BRUSH || t.group == ToolGroup.FILL || t.group == ToolGroup.SHAPES

        if (activeLayer?.isGroup == true && isDrawingTool) {
            v.showActionToast(context.getString(R.string.canvas_toast_group_not_drawable), R.drawable.ic_folder)
            return
        }
        if ((activeLayer?.nodeType == 3) && isDrawingTool) {
            v.showActionToast(context.getString(R.string.canvas_toast_filter_not_drawable), R.drawable.ic_image_adjust)
            return
        }
        if (activeLayer?.locked == true && (isDrawingTool || tool == Tool.LIQUIFY)) {
            v.showActionToast(context.getString(R.string.canvas_toast_layer_locked), R.drawable.ic_lock)
            return
        }
        if (v.isLayerEffectivelyHidden(v.currentLayerIndex) && (isDrawingTool || tool == Tool.LIQUIFY)) {
            v.showActionToast(context.getString(R.string.canvas_toast_layer_hidden), R.drawable.ic_eye_off)
            return
        }

        when (effTool()) {
            Tool.BRUSH, Tool.ERASER, Tool.SMUDGE -> {
                val hasSymmetry = v.drawingGuide.mode == GuideMode.SYMMETRY && v.drawingGuide.assistedDrawing
                assistLockedRay = null
                if (hasSymmetry) {
                    safeBeginSymmetryUndoMacro()
                }
                smoothedPressure = pressure
                currentStrokeDocPos = docPos
                if (isStylus) {
                    getOrCreateStylusDriver()?.feedbackManager?.setWritingHapticsEnabled(true, isEraser = (effTool() == Tool.ERASER))
                }
                // Native stroke stays untouched until a deliberate hold followed by pen-up.
                if (v.quickShapeEnabled && effTool() == Tool.BRUSH && !v.brushAirbrush &&
                    !(v.drawingGuide.mode != GuideMode.OFF && v.drawingGuide.assistedDrawing) && !v.anim.isPlaying) {
                    v.quickShapeCapture = QuickShapeStrokeCapture().also {
                        it.begin(screenPos.x, screenPos.y, SystemClock.uptimeMillis())
                    }
                }
                val overrideTool = if (effTool() == Tool.ERASER && tool != Tool.ERASER) "eraser" else null
                strokeStarted = v.touchStart(docPos.x, docPos.y, pressure.toDouble(), tiltX, tiltY, rotation, toolOverride = overrideTool)
                if (strokeStarted) {
                    // 纸张摩擦音效: 落笔起振 (橡皮稍收音量, 附带初始落笔压感)
                    getOrCreateStylusDriver()?.feedbackManager?.startStrokeSound(effTool() == Tool.ERASER, pressure.toFloat())
                    lastSoundTimeMs = 0L
                }
                val isAssist = hasSymmetry || (v.drawingGuide.mode != GuideMode.OFF && v.drawingGuide.assistedDrawing)
                val assistedScreen = if (isAssist) docToScreen(docPos) else screenPos

                if (hasSymmetry) {
                    updateSymmetryPressureLut(v)
                    val symPts = computeAllSymmetricPoints(Point2D(v.smoothedStrokeX, v.smoothedStrokeY))
                    ensureMirrorBranches(symPts.size)
                    for (idx in symPts.indices) {
                        appendMirrorSample(idx, symPts[idx].x, symPts[idx].y, v.smoothedStrokePressure)
                    }
                } else {
                    resetMirrorBranches()
                }
            }
            Tool.LIQUIFY -> {
                liquifyFlushPosted = false
                removeCallbacks(liquifyFlushRunnable)
                // Phase 3A 实验开关: `setprop debug.reverie.lqcoalesce <n>` 指定每帧最多推进的
                // 补点数(0 = 不限)。默认值见 LiquifyPath.DEFAULT_MAX_DABS_PER_FLUSH:
                //   **旧默认 2 是 test16"小笔刷连续拖动串珠状断触"的主根因** —— 场路径每帧
                //   最多推进 2 个补点, 快速拖动时手指远跑在形变前面, 且被丢掉的中间补点让
                //   GPU 场上的核变稀疏。现在统一为 24 步/帧(≈1.9 万 px/s 跟随能力, 远超手速)。
                // 应用内覆盖(debug 设置页)优先于 property —— 无数据线时靠它切换
                val ov = PerfTrace.liquifyCoalesceOverride
                val prop = if (ov >= 0) ov else PerfTrace.debugPropInt("debug.reverie.lqcoalesce", -1)
                val maxDabs = when {
                    // 1) property 最高优先(无数据线时也能靠构建档位兜底)
                    prop >= 0 -> prop
                    // 2) 构建期档位: 3 = 对照"不做调度合并"(全量推进), 其余走默认上限
                    BuildConfig.LQ_TEST_PROFILE == 3 -> 0
                    else -> LiquifyPath.DEFAULT_MAX_DABS_PER_FLUSH
                }
                // 交互态会话: 以落笔点为已渲染基准, 后续 MOVE 只提交"最新位置"
                liquifySession.begin(docPos.x, docPos.y, maxDabs)
                // Phase 6: 压力初值(笔输入才有意义; 手指路径由 updateLiquifyPressure 跳过)
                smoothedPressure = if (isStylus) pressure.coerceIn(0f, 1f) else 1f
                lastLiquifyEventTimeMs = 0L
                liquifyPressureActive = isStylus
                v.setLiquifyBrushSize(liquifyBrushSize.toDouble())
                v.configureLiquifyProfile(liquifyHardness)
                v.liquifyBegin()
                LiquifyGlesPreview.configureProfile(liquifyHardness)
                // Phase 5 · C3-2: 能走场通路就走 —— 引擎只交一份"未形变的源像素", 拖动期零解算。
                // 判定失败(超预算/非 8bit BGRA/覆盖层不在)自动退回下面的逐 dab 路径。
                liquifyFieldGesture = beginLiquifyFieldGesture(v)
                if (!liquifyFieldGesture) v.liquifyUseEnginePreview()
                strokeStarted = true
                liquifyHoldTimeMs = android.os.SystemClock.uptimeMillis()
                if (liquifyMode in 1..4) postOnAnimation(liquifyHoldRunnable)
            }
            Tool.PICKER -> {
                pickerActive?.value = true
                val refHex = v.brushColor
                pickerInitialColor?.value = parseColor(refHex)
                sampleColorAtScreenPos(previousSinglePos)
            }
            Tool.FILL -> {
                v.floodFill(docPos.x, docPos.y, fillTolerance)
            }
            Tool.MAGICWAND -> {
                wandFlash?.value = docPos
                v.selectContiguous(docPos.x.toInt(), docPos.y.toInt())
            }
            Tool.SELECT_SIMILAR -> {
                wandFlash?.value = docPos
                v.selectSimilar(docPos.x.toInt(), docPos.y.toInt())
            }
            Tool.SELECT_POLYGON -> {
                onPolyPoint?.invoke(docPos)
                isPolyPointPendingOnTouch = true
            }
            Tool.SHAPES, Tool.LINE, Tool.RECT, Tool.ELLIPSE, Tool.POLYGON, Tool.POLYLINE, Tool.PATH -> {
                handleShapeDown(docPos)
            }
            Tool.TEXT -> {
                handleTextDown(docPos)
            }
            Tool.GRADIENT -> {
                liveShapeStart?.value = docPos
                liveShapeEnd?.value = docPos
            }
            Tool.SELECT_RECT, Tool.SELECT_ELLIPSE -> {
                if (v.selectionMode == 0) {
                    v.clearSelectionOverlayLocal()
                }
                liveShapeStart?.value = docPos
                liveShapeEnd?.value = docPos
            }
            Tool.LASSO -> {
                if (v.selectionMode == 0 && v.lassoMultiPoints.isEmpty()) {
                    v.clearSelectionOverlayLocal()
                }
                val subMode = v.lassoSubMode
                if (subMode == LassoSubMode.FREEHAND) {
                    lassoPoints.clear()
                    lassoPoints.add(docPos)
                } else {
                    val now = android.os.SystemClock.uptimeMillis()
                    val currentScale = maxOf(0.01f, canvasZoom * canvasFitScale)
                    val bmp = v.displayBitmap ?: docBitmap
                    val bmpW = (bmp?.width ?: v.renderW.takeIf { it > 0 } ?: v.docWidth).toFloat()
                    val docW = (if (v.docWidth > 0) v.docWidth else bmpW.toInt()).toFloat()
                    val bmpPerDoc = (bmpW / docW).coerceAtLeast(0.001f)
                    val snapDistThreshold = (24f * density) / (currentScale * bmpPerDoc)

                    // 双击闭合 (至少已有3个点时双击直接闭合选区)
                    if (v.lassoMultiPoints.size >= 3 && now - lastLassoTapTimeMs < 350L &&
                        hypot(docPos.x - lastLassoTapDocPos.x, docPos.y - lastLassoTapDocPos.y) < snapDistThreshold
                    ) {
                        justFinishedLassoInDown = true
                        lastLassoTapTimeMs = 0L
                        v.finishLassoMulti()
                        liveSelectionPath?.value = null
                        lassoPoints.clear()
                        return
                    }
                    lastLassoTapTimeMs = now
                    lastLassoTapDocPos = docPos

                    // 点击起点附近闭合
                    if (v.lassoMultiPoints.size >= 3) {
                        val startPt = v.lassoMultiPoints.first()
                        val distToStart = hypot(docPos.x - startPt.first, docPos.y - startPt.second)
                        if (distToStart <= snapDistThreshold) {
                            justFinishedLassoInDown = true
                            lastLassoTapTimeMs = 0L
                            v.finishLassoMulti()
                            liveSelectionPath?.value = null
                            lassoPoints.clear()
                            return
                        }
                    }

                    lassoPoints.clear()
                    lassoPoints.add(docPos)
                }
            }
            Tool.MEASURE -> {
                val s = measureStart?.value
                val e = measureEnd?.value
                val currentScale = (canvasZoom * canvasFitScale).coerceAtLeast(0.001f)
                val hitThresholdDoc = (28f * density) / currentScale
                if (s != null && e != null) {
                    val dStart = hypot(docPos.x - s.x, docPos.y - s.y)
                    val dEnd = hypot(docPos.x - e.x, docPos.y - e.y)
                    when {
                        dStart <= hitThresholdDoc && dStart <= dEnd -> {
                            activeMeasureHandle = 0
                            measureStart?.value = docPos
                        }
                        dEnd <= hitThresholdDoc -> {
                            activeMeasureHandle = 1
                            measureEnd?.value = docPos
                        }
                        else -> {
                            activeMeasureHandle = -1
                            measureStart?.value = docPos
                            measureEnd?.value = docPos
                        }
                    }
                } else {
                    activeMeasureHandle = -1
                    measureStart?.value = docPos
                    measureEnd?.value = docPos
                }
            }
            Tool.TRANSFORM -> {
                val state = tfState
                if (state != null) {
                    if (!state.active) {
                        val b = v.contentBounds()
                        if (b != null && b[2] > 0 && b[3] > 0) {
                            state.reset(
                                Rect(
                                    b[0].toFloat(),
                                    b[1].toFloat(),
                                    (b[0] + b[2]).toFloat(),
                                    (b[1] + b[3]).toFloat(),
                                )
                            )
                        } else {
                            state.reset(
                                Rect(
                                    0f,
                                    0f,
                                    v.docWidth.toFloat(),
                                    v.docHeight.toFloat(),
                                )
                            )
                        }
                        v.startTransformPreview()
                    }
                    val handles = tfHandles(state)
                    val currentScale = canvasZoom * canvasFitScale
                    val baseThresholdDoc = (18f * density) / maxOf(0.01f, currentScale)

                    if (state.mode == TransformMode.PERSPECTIVE) {
                        var best = -1
                        var bestD = baseThresholdDoc
                        for (i in handles.indices) {
                            val d = hypot(handles[i].x - docPos.x, handles[i].y - docPos.y)
                            if (d < bestD) {
                                bestD = d
                                best = i
                            }
                        }
                        state.handle = if (best in 0..3) best else 8
                    } else if (state.mode == TransformMode.DISTORT) {
                        var best = -1
                        var baseD = baseThresholdDoc
                        for (i in handles.indices) {
                            val d = hypot(handles[i].x - docPos.x, handles[i].y - docPos.y)
                            if (d < baseD) {
                                baseD = d
                                best = i
                            }
                        }
                        state.handle = if (best in 0..15) best else 99
                    } else {
                        val c = state.bounds.center
                        val dx = docPos.x - c.x - state.tx
                        val dy = docPos.y - c.y - state.ty
                        val rad = Math.toRadians(-state.rotation.toDouble())
                        val cosR = cos(rad).toFloat()
                        val sinR = sin(rad).toFloat()
                        val ux = (dx * cosR - dy * sinR) / state.scaleX
                        val uy = (dx * sinR + dy * cosR) / state.scaleY

                        val halfW = state.bounds.width / 2f
                        val halfH = state.bounds.height / 2f
                        val inBox = ux >= -halfW && ux <= halfW && uy >= -halfH && uy <= halfH

                        val maxHandleRadius = minOf(halfW, halfH) * 0.4f
                        val hitThresholdDoc = minOf(baseThresholdDoc, maxOf(1f, maxHandleRadius))

                        var best = -1
                        var bestD = hitThresholdDoc
                        for (i in handles.indices) {
                            val d = hypot(handles[i].x - docPos.x, handles[i].y - docPos.y)
                            if (d < bestD) {
                                bestD = d
                                best = i
                            }
                        }

                        // 框内核心平移区保护：落点在矩形中央安全区优先判定为平移，杜绝误触缩放手柄
                        val inInnerSafetyZone = inBox && halfW > 0f && halfH > 0f &&
                            (abs(ux) < halfW * 0.65f && abs(uy) < halfH * 0.65f)

                        state.handle = when {
                            inInnerSafetyZone -> 8
                            best >= 0 -> best
                            inBox -> 8
                            else -> 9
                        }
                    }
                    state.dragStart = docPos
                    state.startScaleX = state.scaleX
                    state.startScaleY = state.scaleY
                    state.startRotation = state.rotation
                    state.startTx = state.tx
                    state.startTy = state.ty
                    state.startQuadCorners = state.quadCorners.toList()
                    state.startMeshPoints = state.meshPoints.toList()
                }
            }
            else -> Unit
        }
    }

    private fun handleToolMove(event: MotionEvent, pointerIndex: Int, docPos: Offset, pressure: Float, isStylus: Boolean) {
        val v = vm ?: return

        if (draggingGuideHandleIndex != -1) {
            if (handleGuideHandleDrag(docPos)) return
        }

        when (effTool()) {
            Tool.BRUSH, Tool.ERASER, Tool.SMUDGE -> {
                val hasSymmetry = (v.drawingGuide.mode == GuideMode.SYMMETRY && v.drawingGuide.assistedDrawing)
                if (!strokeStarted) {
                    val startDoc = if (firstDocPos != Offset.Zero) firstDocPos else docPos
                    firstDocPos = startDoc
                    updateStylusSensors(event, pointerIndex, isStylus, historyPos = -1)
                    if (hasSymmetry) {
                        safeBeginSymmetryUndoMacro()
                    }
                    val overrideTool = if (effTool() == Tool.ERASER && tool != Tool.ERASER) "eraser" else null
                    strokeStarted = v.touchStart(startDoc.x, startDoc.y, pressure.toDouble(), touchTiltX, touchTiltY, touchRotation, toolOverride = overrideTool)
                    if (strokeStarted) {
                        if (isStylus) {
                            getOrCreateStylusDriver()?.feedbackManager?.setWritingHapticsEnabled(true, isEraser = (effTool() == Tool.ERASER))
                        }
                        // 迟到的笔画起点 (首帧被历史点吞掉): 补启摩擦音效
                        getOrCreateStylusDriver()?.feedbackManager?.startStrokeSound(effTool() == Tool.ERASER, pressure.toFloat())
                        lastSoundTimeMs = 0L
                        if (hasSymmetry) {
                            updateSymmetryPressureLut(v)
                            val symPts = computeAllSymmetricPoints(Point2D(v.smoothedStrokeX, v.smoothedStrokeY))
                            ensureMirrorBranches(symPts.size)
                            for (idx in symPts.indices) {
                                appendMirrorSample(idx, symPts[idx].x, symPts[idx].y, v.smoothedStrokePressure)
                            }
                        }
                    }
                }
                if (!strokeStarted) return

                v.quickShapeCapture?.let { capture ->
                    if (capture.moved(event.getX(pointerIndex), event.getY(pointerIndex),
                            SystemClock.uptimeMillis(), 5f * density)) {
                        quickShapeCandidate = null
                        removeCallbacks(quickShapeHold)
                        postDelayed(quickShapeHold, 650L)
                    }
                }
                currentStrokeDocPos = docPos

                val effectiveDocPos = applyAssistedDrawing(firstDocPos, docPos)
                val isAssist = (v.drawingGuide.mode != GuideMode.OFF && v.drawingGuide.assistedDrawing)

                for (i in 0 until event.historySize) {
                    val hScreen = Offset(event.getHistoricalX(pointerIndex, i), event.getHistoricalY(pointerIndex, i))
                    val hDoc = screenToDoc(hScreen)
                    val hAssisted = applyAssistedDrawing(firstDocPos, hDoc)
                    val hP = if (isStylus) {
                        val hp = event.getHistoricalPressure(pointerIndex, i)
                        if (hp.isNaN()) 1f else hp.coerceIn(0f, 1f)
                    } else 1f
                    val hTime = event.getHistoricalEventTime(i)
                    updateStylusSensors(event, pointerIndex, isStylus, historyPos = i)
                    v.touchMove(hAssisted.x, hAssisted.y, hP.toDouble(), hTime, touchTiltX, touchTiltY, touchRotation)
                    if (hasSymmetry) {
                        val symPts = computeAllSymmetricPoints(Point2D(v.smoothedStrokeX, v.smoothedStrokeY))
                        for (idx in symPts.indices) {
                            appendMirrorSample(idx, symPts[idx].x, symPts[idx].y, v.smoothedStrokePressure)
                        }
                    }
                }

                updateStylusSensors(event, pointerIndex, isStylus, historyPos = -1)
                v.touchMove(effectiveDocPos.x, effectiveDocPos.y, pressure.toDouble(), event.eventTime, touchTiltX, touchTiltY, touchRotation)

                // 纸张摩擦音效: 按文档坐标瞬时速度与压感动态调制增益与共振 (零分配, 单次 volatile 写)
                val soundDt = event.eventTime - lastSoundTimeMs
                if (lastSoundTimeMs != 0L && soundDt > 0) {
                    val sdx = effectiveDocPos.x - lastSoundDocPos.x
                    val sdy = effectiveDocPos.y - lastSoundDocPos.y
                    val speedPxPerMs = kotlin.math.sqrt(sdx * sdx + sdy * sdy) / soundDt
                    getOrCreateStylusDriver()?.feedbackManager?.updateStrokeSound(speedPxPerMs, pressure.toFloat())
                }
                lastSoundDocPos = effectiveDocPos
                lastSoundTimeMs = event.eventTime

                if (hasSymmetry) {
                    val symPts = computeAllSymmetricPoints(Point2D(v.smoothedStrokeX, v.smoothedStrokeY))
                    for (idx in symPts.indices) {
                        appendMirrorSample(idx, symPts[idx].x, symPts[idx].y, v.smoothedStrokePressure)
                    }
                }

                // Dual-track replacement: immediately clear old prediction upon real event reception
                clearPredictedScreenPoint()

                // 双系统分派:
                // - 前缓冲预测开启 (默认): 卡尔曼训练 + 空窗回填 + 前缓冲直出 (本次低延迟技术)
                // - 前缓冲预测关闭: 上游原版"超低延迟笔迹预测" (OEM 硬件预测器渐隐尾线, 仅 OPPO 有效)
                val frontBufferPrediction = v.frontBufferPredictionEnabled && tool == Tool.BRUSH
                val fidelityTier = if (frontBufferPrediction) v.effectivePredictionTier else PaintViewModel.PredictionFidelityTier.NONE

                if (frontBufferPrediction && fidelityTier != PaintViewModel.PredictionFidelityTier.NONE) {
                    // 1. 通用 4 阶卡尔曼滤波器与物理采样训练（前向几何预测时间窗与后台渲染耗时彻底解耦）
                    // 几何外推时间窗上限严格锁定为 MAX_PREDICTION_MS = 32ms (约 1~2 帧)，外推至 2.0 帧呈现前瞻，紧贴笔尖真实落点，防止高速运笔过冲断裂
                    val frameTimeMs = universalPredictor.avgReportRateMs.coerceIn(4f, 16.67f)
                    val rawLeadMs = (frameTimeMs * 2.0f).coerceIn(16f, UniversalKalmanPredictor.MAX_PREDICTION_MS)
                    smoothedLeadMs = if (smoothedLeadMs <= 0f) rawLeadMs else (smoothedLeadMs * 0.8f + rawLeadMs * 0.2f)
                    universalPredictor.setPredictionTargetMs(smoothedLeadMs)
                    if (pointerIndex in 0 until event.pointerCount) {
                        val histCount = event.historySize
                        for (i in 0 until histCount) {
                            val hx = event.getHistoricalX(pointerIndex, i)
                            val hy = event.getHistoricalY(pointerIndex, i)
                            val rawHp = if (isStylus) event.getHistoricalPressure(pointerIndex, i) else 1f
                            val hp = if (rawHp.isFinite()) rawHp.coerceIn(0.01f, 1f) else 1f
                            val ht = event.getHistoricalEventTime(i)
                            if (hx.isFinite() && hy.isFinite()) {
                                universalPredictor.addPoint(hx, hy, hp, ht)
                                recordTouchSample(hx, hy, ht)
                            }
                        }
                        val curX = event.getX(pointerIndex)
                        val curY = event.getY(pointerIndex)
                        val rawP = if (isStylus) pressure else 1f
                        val curP = if (rawP.isFinite()) rawP.coerceIn(0.01f, 1f) else 1f
                        val curT = event.eventTime
                        if (curX.isFinite() && curY.isFinite()) {
                            universalPredictor.addPoint(curX, curY, curP, curT)
                            recordTouchSample(curX, curY, curT)
                            priorStrokeScreenPos = Offset(universalPredictor.prevPosX, universalPredictor.prevPosY)
                        }
                    }

                    // 2. 笔尖前向超前预测计算 (分级放行：TIER_1/2/STAMP 放行，NONE 已在外层拦截)
                    var predictionObtained = false
                    val op = oplusPredictor
                    if (isStylus && op != null && op.isValid) {
                        try {
                            for (i in 0 until event.historySize) {
                                cachedTouchPointInfo.x = event.getHistoricalX(pointerIndex, i)
                                cachedTouchPointInfo.y = event.getHistoricalY(pointerIndex, i)
                                cachedTouchPointInfo.pressure = if (isStylus) event.getHistoricalPressure(pointerIndex, i).coerceIn(0f, 1f) else 1f
                                cachedTouchPointInfo.axisTilt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) event.getHistoricalAxisValue(MotionEvent.AXIS_TILT, pointerIndex, i) else 0f
                                cachedTouchPointInfo.timestamp = event.getHistoricalEventTime(i)
                                op.pushTouchPoint(cachedTouchPointInfo)
                            }
                            cachedTouchPointInfo.x = event.getX(pointerIndex)
                            cachedTouchPointInfo.y = event.getY(pointerIndex)
                            cachedTouchPointInfo.pressure = pressure
                            cachedTouchPointInfo.axisTilt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) event.getAxisValue(MotionEvent.AXIS_TILT, pointerIndex) else 0f
                            cachedTouchPointInfo.timestamp = event.eventTime
                            op.pushTouchPoint(cachedTouchPointInfo)

                            val pred = op.predictTouchPoint()
                            if (pred != null) {
                                setPredictedScreenPoint(pred.x, pred.y)
                                predictedPressure = pred.pressure.coerceIn(0.01f, 1f)
                                predictionObtained = true
                            }
                        } catch (_: Throwable) {}
                    }

                    val vp = vivoPredictor
                    if (!predictionObtained && vp != null && vp.isEstimateEnable) {
                        try {
                            val pred = if (pointerIndex == 0) vp.computeEstimatePoint(event) else null
                            if (pred != null && (pred.x != 0f || pred.y != 0f) && (pred.x != event.x || pred.y != event.y)) {
                                setPredictedScreenPoint(pred.x, pred.y)
                                predictedPressure = pressure.coerceIn(0.01f, 1f)
                                predictionObtained = true
                            }
                        } catch (_: Throwable) {}
                    }

                    val hp = huaweiPredictor
                    if (!predictionObtained && hp != null && hp.isValid) {
                        try {
                            if (hp.predictPoint(event, pointerIndex, tempOemPoint)) {
                                val hx = tempOemPoint[0]
                                val hy = tempOemPoint[1]
                                val hpVal = tempOemPoint[2]
                                if (hx.isFinite() && hy.isFinite() && (hx != 0f || hy != 0f) &&
                                    (hx != event.getX(pointerIndex) || hy != event.getY(pointerIndex))) {
                                    setPredictedScreenPoint(hx, hy)
                                    predictedPressure = if (hpVal.isFinite()) hpVal.coerceIn(0.01f, 1f) else 1f
                                    predictionObtained = true
                                }
                            }
                        } catch (_: Throwable) {}
                    }

                    val xp = xiaomiPredictor
                    if (!predictionObtained && xp != null && xp.isValid) {
                        try {
                            if (xp.predictPoint(event, pointerIndex, tempOemPoint)) {
                                val xx = tempOemPoint[0]
                                val xy = tempOemPoint[1]
                                val xpVal = tempOemPoint[2]
                                if (xx.isFinite() && xy.isFinite() && (xx != 0f || xy != 0f) &&
                                    (xx != event.getX(pointerIndex) || xy != event.getY(pointerIndex))) {
                                    setPredictedScreenPoint(xx, xy)
                                    predictedPressure = if (xpVal.isFinite()) xpVal.coerceIn(0.01f, 1f) else 1f
                                    predictionObtained = true
                                }
                            }
                        } catch (_: Throwable) {}
                    }

                    // Android 原生系统级运动预测器 (适用于华为、三星、小米、各品牌手写笔通用硬件层)
                    val smp = systemMotionPredictor
                    if (!predictionObtained && smp != null) {
                        try {
                            smp.record(event)
                            val predEvent = smp.predict()
                            if (predEvent != null) {
                                try {
                                    if (pointerIndex in 0 until predEvent.pointerCount) {
                                        val px = predEvent.getX(pointerIndex)
                                        val py = predEvent.getY(pointerIndex)
                                        val pp = predEvent.getPressure(pointerIndex)
                                        if (px.isFinite() && py.isFinite() && (px != 0f || py != 0f) && (px != event.getX(pointerIndex) || py != event.getY(pointerIndex))) {
                                            setPredictedScreenPoint(px, py)
                                            predictedPressure = if (pp.isFinite()) pp.coerceIn(0.01f, 1f) else 1f
                                            predictionObtained = true
                                        }
                                    }
                                } finally {
                                    predEvent.recycle()
                                }
                            }
                        } catch (_: Throwable) {}
                    }

                    // 通用 4 阶卡尔曼前向预测（纯 Kotlin 几何外推兜底）
                    if (!predictionObtained) {
                        try {
                            if (pointerIndex in 0 until event.pointerCount) {
                                val curX = event.getX(pointerIndex)
                                val curY = event.getY(pointerIndex)
                                if (curX.isFinite() && curY.isFinite()) {

                                    val hasPred = universalPredictor.predictTouchPointScalar(tempScalarPredPoint)
                                    if (hasPred) {
                                        val predX = tempScalarPredPoint[0]
                                        val predY = tempScalarPredPoint[1]
                                        val predP = tempScalarPredPoint[2]
                                        val curPos = Offset(curX, curY)
                                        val dx = predX - curPos.x
                                        val dy = predY - curPos.y
                                        val dist = kotlin.math.hypot(dx, dy)
                                        val curDiag = if (width > 0 && height > 0) {
                                            kotlin.math.hypot(width.toFloat(), height.toFloat())
                                        } else {
                                            universalPredictor.screenDiagonal
                                        }
                                        val wildDiag = curDiag > 0f && dist > (curDiag / 6f)

                                        // 门控检查：笔迹真实移动方向与预测方向的锐角偏差 (cos(theta) >= 0.55)
                                        var angleOk = true
                                        val prevPos = priorStrokeScreenPos
                                        if (prevPos != Offset.Zero && prevPos != curPos) {
                                            val v1x = curPos.x - prevPos.x
                                            val v1y = curPos.y - prevPos.y
                                            val len1 = kotlin.math.hypot(v1x, v1y)
                                            if (len1 > 1.5f && dist > 1.5f) {
                                                val dot = (v1x * dx + v1y * dy) / (len1 * dist)
                                                if (dot < 0.55f) {
                                                    angleOk = false
                                                }
                                            }
                                        }

                                        if (!wildDiag && angleOk) {
                                            setPredictedScreenPoint(predX, predY)
                                            predictedPressure = if (predP.isFinite()) predP.coerceIn(0.01f, 1f) else 1f
                                            predictionObtained = true
                                        } else {
                                            clearPredictedScreenPoint()
                                        }
                                    } else {
                                        clearPredictedScreenPoint()
                                    }
                                } else {
                                    clearPredictedScreenPoint()
                                }
                            } else {
                                clearPredictedScreenPoint()
                            }
                        } catch (_: Throwable) {
                            clearPredictedScreenPoint()
                        }
                    }

                    // 预览活动打点: 预测成功或回填段绘制都算"活跃", 看门狗据此判定真正停滞。
                    // 回填段独立于预测工作, 慢速/急转时预测被抑制但预览仍在刷新, 不能清。
                    lastPredictionUptimeMs = android.os.SystemClock.uptimeMillis()
                    if (!isWatchdogScheduled) {
                        isWatchdogScheduled = true
                        postDelayed(predictionStagnationRunnable, 50L)
                    }
                    if (!predictionObtained) {
                        clearPredictedScreenPoint()
                    }

                    // 3. 前缓冲直出: 捕获到移动事件并计算好最新笔尖前沿后立即直出, 绕过 VSYNC。
                    // 门控与 onDraw 软件回退互补 (canRenderPreview 为 false 时由软件回退接管,
                    // 见 FrontBufferProbe.softwareFallbackRequired): 渲染器初始化失败时不能在此
                    // 置 drew=true, 否则直出与回退两边都不画。
                    if (frontBufferOverlay?.canRenderPreview == true && pointerIndex in 0 until event.pointerCount) {
                        val curX = event.getX(pointerIndex)
                        val curY = event.getY(pointerIndex)
                        val curPos = Offset(curX, curY)
                        var drew = false
                        // 不再要求 hasPredictedScreenPoint: 预测被抑制时回填段照画
                        if (computePreviewStrokePath(curPos, previewStrokePath)) {
                            // 前缓冲层位于窗口 z 序最顶层 (setZOrderOnTop): 笔尖或前瞻端点落在
                            // 工具栏/浮窗等 UI 区域上时跳过绘制, 避免预览墨迹盖在 UI 之上。
                            // 端点判定不够: 高速运笔一帧可跨过顶栏, 包络相交做保守整段判定
                            // (左手模式顶栏已纳入注册表, 见 rebuildUiExclusionRects)。
                            val overUi = isHoverOverUi(curX, curY) ||
                                isHoverOverUi(previewTipEndX, previewTipEndY) ||
                                previewEnvelopeHitsUi(curX, curY)
                            if (!overUi) {
                                if (previewUsedStamp && previewStampBitmap != null && previewStampCount > 0) {
                                    // STAMP 分级: 真实笔尖戳印直出 (水彩/纹理/绘画类)
                                    frontBufferOverlay?.renderStampPreview(
                                        previewStampBitmap!!,
                                        previewStampX, previewStampY,
                                        previewStampSize, previewStampAlpha,
                                        previewStampCount
                                    )
                                } else {
                                    frontBufferOverlay?.renderPreviewPath(previewStrokePath, previewStrokeWidth, previewStrokeColor)
                                }
                                drew = true
                                activePredictedTipScreenX = previewTipEndX
                                activePredictedTipScreenY = previewTipEndY
                                hasActivePredictedTip = true
                            }
                        }
                        if (!drew) {
                            if (hasActivePredictedTip || hasDrawnPrediction) {
                                frontBufferOverlay?.clearPreview()
                            }
                            hasActivePredictedTip = false
                        }
                        hasDrawnPrediction = drew
                        // 前缓冲直出绕过 onDraw: 必须显式失效光标区域, 否则 onDraw 里的
                        // 光标环得不到重绘调度, 跟随标记置位也"看似"不跟随
                        // (handleToolMove 全程零 invalidate, PR 原生问题)。
                        // 软件回退路径在 onDraw 内绘制, 自带重绘, 不受影响。
                        // 局部失效 (非全屏), 120Hz 下开销可忽略。
                        invalidateCursor(
                            if (drew) activePredictedTipScreenX
                            else if (hasLocalCursorPos) localCursorX
                            else Float.NaN,
                            if (drew) activePredictedTipScreenY
                            else if (hasLocalCursorPos) localCursorY
                            else Float.NaN
                        )
                    }
                } else if (!v.frontBufferPredictionEnabled && isStylus && v.isCurrentBrushPredictionEligible) {
                    // OEM 硬件前向预测尾线 (抵消 144Hz 屏幕 1~2 帧物理上屏延迟)
                    val vp = vivoPredictor
                    if (vp != null && vp.isEstimateEnable) {
                        try {
                            val pred = if (pointerIndex == 0) vp.computeEstimatePoint(event) else null
                            if (pred != null && (pred.x != 0f || pred.y != 0f) && (pred.x != event.x || pred.y != event.y)) {
                                setPredictedScreenPoint(pred.x, pred.y)
                                predictedPressure = pressure.coerceIn(0.01f, 1f)
                            } else {
                                clearPredictedScreenPoint()
                            }
                        } catch (_: Throwable) {
                            clearPredictedScreenPoint()
                        }
                    } else {
                        val hp = huaweiPredictor
                        if (hp != null && hp.isValid) {
                            try {
                                if (hp.predictPoint(event, pointerIndex, tempOemPoint)) {
                                    val hx = tempOemPoint[0]
                                    val hy = tempOemPoint[1]
                                    val hpVal = tempOemPoint[2]
                                    if (hx.isFinite() && hy.isFinite() && (hx != 0f || hy != 0f) &&
                                        (hx != event.getX(pointerIndex) || hy != event.getY(pointerIndex))) {
                                        setPredictedScreenPoint(hx, hy)
                                        predictedPressure = if (hpVal.isFinite()) hpVal.coerceIn(0.01f, 1f) else 1f
                                    } else {
                                        clearPredictedScreenPoint()
                                    }
                                } else {
                                    clearPredictedScreenPoint()
                                }
                            } catch (_: Throwable) {
                                clearPredictedScreenPoint()
                            }
                        } else {
                            val xp = xiaomiPredictor
                            if (xp != null && xp.isValid) {
                                try {
                                    if (xp.predictPoint(event, pointerIndex, tempOemPoint)) {
                                        val xx = tempOemPoint[0]
                                        val xy = tempOemPoint[1]
                                        val xpVal = tempOemPoint[2]
                                        if (xx.isFinite() && xy.isFinite() && (xx != 0f || xy != 0f) &&
                                            (xx != event.getX(pointerIndex) || xy != event.getY(pointerIndex))) {
                                            setPredictedScreenPoint(xx, xy)
                                            predictedPressure = if (xpVal.isFinite()) xpVal.coerceIn(0.01f, 1f) else 1f
                                        } else {
                                            clearPredictedScreenPoint()
                                        }
                                    } else {
                                        clearPredictedScreenPoint()
                                    }
                                } catch (_: Throwable) {
                                    clearPredictedScreenPoint()
                                }
                            } else {
                                val op = oplusPredictor
                                if (op != null && op.isValid) {
                                    try {
                                        for (i in 0 until event.historySize) {
                                            cachedTouchPointInfo.x = event.getHistoricalX(pointerIndex, i)
                                            cachedTouchPointInfo.y = event.getHistoricalY(pointerIndex, i)
                                            cachedTouchPointInfo.pressure = if (isStylus) event.getHistoricalPressure(pointerIndex, i).coerceIn(0f, 1f) else 1f
                                            cachedTouchPointInfo.axisTilt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) event.getHistoricalAxisValue(MotionEvent.AXIS_TILT, pointerIndex, i) else 0f
                                            cachedTouchPointInfo.timestamp = event.getHistoricalEventTime(i)
                                            op.pushTouchPoint(cachedTouchPointInfo)
                                        }
                                        cachedTouchPointInfo.x = event.getX(pointerIndex)
                                        cachedTouchPointInfo.y = event.getY(pointerIndex)
                                        cachedTouchPointInfo.pressure = pressure
                                        cachedTouchPointInfo.axisTilt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) event.getAxisValue(MotionEvent.AXIS_TILT, pointerIndex) else 0f
                                        cachedTouchPointInfo.timestamp = event.eventTime
                                        op.pushTouchPoint(cachedTouchPointInfo)

                                        val pred = op.predictTouchPoint()
                                        if (pred != null) {
                                            setPredictedScreenPoint(pred.x, pred.y)
                                            predictedPressure = pred.pressure.coerceIn(0.01f, 1f)
                                        } else {
                                            clearPredictedScreenPoint()
                                        }
                                    } catch (_: Throwable) {
                                        clearPredictedScreenPoint()
                                    }
                                } else {
                                    val smp = systemMotionPredictor
                                    if (smp != null) {
                                        try {
                                            smp.record(event)
                                            val predEvent = smp.predict()
                                            if (predEvent != null) {
                                                try {
                                                    if (pointerIndex in 0 until predEvent.pointerCount) {
                                                        val px = predEvent.getX(pointerIndex)
                                                        val py = predEvent.getY(pointerIndex)
                                                        val pp = predEvent.getPressure(pointerIndex)
                                                        if (px.isFinite() && py.isFinite() && (px != 0f || py != 0f) && (px != event.getX(pointerIndex) || py != event.getY(pointerIndex))) {
                                                            setPredictedScreenPoint(px, py)
                                                            predictedPressure = if (pp.isFinite()) pp.coerceIn(0.01f, 1f) else 1f
                                                        } else {
                                                            clearPredictedScreenPoint()
                                                        }
                                                    } else {
                                                        clearPredictedScreenPoint()
                                                    }
                                                } finally {
                                                    predEvent.recycle()
                                                }
                                            } else {
                                                clearPredictedScreenPoint()
                                            }
                                        } catch (_: Throwable) {
                                            clearPredictedScreenPoint()
                                        }
                                    } else {
                                        clearPredictedScreenPoint()
                                    }
                                }
                            }
                        }
                    }
                    if (frontBufferOverlay != null) {
                        if (hasActivePredictedTip || hasDrawnPrediction) {
                            frontBufferOverlay?.clearPreview()
                        }
                        hasActivePredictedTip = false
                        hasDrawnPrediction = false
                    }
                } else {
                    clearPredictedScreenPoint()
                    if (frontBufferOverlay != null) {
                        if (hasActivePredictedTip || hasDrawnPrediction) {
                            frontBufferOverlay?.clearPreview()
                        }
                        hasActivePredictedTip = false
                        hasDrawnPrediction = false
                    }
                }
            }
            Tool.LIQUIFY -> {
                if (strokeStarted) {
                    // Phase 6(手感, 对标 CSP/SAI2 的笔压响应): 强度跟随笔压 —— 只做平滑,
                    // 不做曲线; 手指输入固定不调制(避免触摸压力噪声让手感发抖)。
                    updateLiquifyPressure(event, pointerIndex)
                    lastLiquifyEventTimeMs = event.eventTime
                    if (liquifySession.coalescing) {
                        // Preserve coalesced history; only GPU/engine dispatch is frame-batched.
                        for (i in 0 until event.historySize) {
                            val historical = screenToDoc(Offset(event.getHistoricalX(pointerIndex, i),
                                event.getHistoricalY(pointerIndex, i)))
                            val pressure = if (liquifyPressureActive)
                                0.55f + 0.45f * event.getHistoricalPressure(pointerIndex, i).coerceIn(0f, 1f)
                            else 1f
                            liquifySession.submitTarget(historical.x, historical.y, pressure)
                        }
                        liquifySession.submitTarget(docPos.x, docPos.y, liquifyPressureFactor())
                        if (!liquifyFlushPosted) {
                            liquifyFlushPosted = true
                            postOnAnimation(liquifyFlushRunnable)
                        }
                    } else {
                        // 历史点(coalesced)必须一起消费: 原先只取当帧最终点, 手快时
                        // 一次事件跨几十像素, 形变搭接不上就会留下断口
                        for (i in 0 until event.historySize) {
                            liquifyAlongPath(v, screenToDoc(Offset(event.getHistoricalX(pointerIndex, i), event.getHistoricalY(pointerIndex, i))))
                        }
                        liquifyAlongPath(v, docPos)
                    }
                }
            }
            Tool.PICKER -> {
                sampleColorAtScreenPos(previousSinglePos)
            }
            Tool.SHAPES, Tool.LINE, Tool.RECT, Tool.ELLIPSE, Tool.POLYGON, Tool.POLYLINE, Tool.PATH -> {
                handleShapeMove(docPos)
            }
            Tool.TEXT -> {
                handleTextMove(docPos)
            }
            Tool.GRADIENT, Tool.SELECT_RECT, Tool.SELECT_ELLIPSE -> {
                shapeEndDocPos = docPos
                liveShapeEnd?.value = docPos
            }
            Tool.LASSO -> {
                if (justFinishedLassoInDown) return
                val subMode = v.lassoSubMode
                val lastPt = lassoPoints.lastOrNull()
                val minMove = 3f
                if (lastPt == null || hypot(docPos.x - lastPt.x, docPos.y - lastPt.y) >= minMove) {
                    lassoPoints.add(docPos)
                }
                val now = System.nanoTime()
                if (now - lastLassoPreviewNs > 16_000_000L) {
                    lastLassoPreviewNs = now
                    if (subMode == LassoSubMode.FREEHAND) {
                        updateLiveSelectionPathFromPoints(lassoPoints, closed = true)
                    } else if (subMode == LassoSubMode.POLYLINE) {
                        val preview = v.lassoMultiPoints.map { Offset(it.first.toFloat(), it.second.toFloat()) } + docPos
                        updateLiveSelectionPathFromPoints(preview, closed = preview.size >= 3)
                    } else {
                        val preview = v.lassoMultiPoints.map { Offset(it.first.toFloat(), it.second.toFloat()) } + lassoPoints
                        updateLiveSelectionPathFromPoints(preview, closed = preview.size >= 3)
                    }
                }
            }
            Tool.MEASURE -> {
                when (activeMeasureHandle) {
                    0 -> measureStart?.value = docPos
                    else -> measureEnd?.value = docPos
                }
            }
            Tool.TRANSFORM -> {
                shapeEndDocPos = docPos
                val state = tfState
                if (state != null && state.active && state.handle >= 0) {
                    val c = state.bounds.center
                    val imagePos = docPos
                    when {
                        state.mode == TransformMode.DISTORT -> {
                            val delta = imagePos - state.dragStart
                            if (state.handle in 0..15) {
                                val newMesh = state.startMeshPoints.toMutableList()
                                newMesh[state.handle] = state.startMeshPoints[state.handle] + delta
                                state.meshPoints = newMesh
                            } else {
                                state.meshPoints = state.startMeshPoints.map { it + delta }
                            }
                        }
                        state.mode == TransformMode.PERSPECTIVE -> {
                            val delta = imagePos - state.dragStart
                            if (state.handle in 0..3) {
                                val idx = state.handle
                                val newCorners = state.startQuadCorners.toMutableList()
                                newCorners[idx] = state.startQuadCorners[idx] + delta
                                state.quadCorners = newCorners
                            } else {
                                state.quadCorners = state.startQuadCorners.map { it + delta }
                            }
                        }
                        state.handle == 1 || state.handle == 3 || state.handle == 9 -> {
                            val a1 = atan2(state.dragStart.y - c.y - state.startTy, state.dragStart.x - c.x - state.startTx)
                            val a2 = atan2(imagePos.y - c.y - state.startTy, imagePos.x - c.x - state.startTx)
                            val d = Math.toDegrees((a2 - a1).toDouble()).toFloat()
                            state.rotation = state.startRotation + d
                        }
                        state.handle == 0 || state.handle == 2 -> {
                            val rad = Math.toRadians(-state.startRotation.toDouble())
                            val cosR = cos(rad).toFloat()
                            val sinR = sin(rad).toFloat()
                            val dx = imagePos.x - c.x - state.startTx
                            val dy = imagePos.y - c.y - state.startTy
                            val ux = dx * cosR - dy * sinR
                            val uy = dx * sinR + dy * cosR

                            val sdx = state.dragStart.x - c.x - state.startTx
                            val sdy = state.dragStart.y - c.y - state.startTy
                            val sux = sdx * cosR - sdy * sinR
                            val suy = sdx * sinR + sdy * cosR

                            val kx = if (abs(sux) > 1f) ux / sux else 1f
                            val ky = if (abs(suy) > 1f) uy / suy else 1f

                            if (state.mode == TransformMode.STANDARD) {
                                val k = if (abs(kx - 1f) > abs(ky - 1f)) kx else ky
                                state.scaleX = state.startScaleX * k
                                state.scaleY = state.startScaleY * k
                            } else {
                                state.scaleX = state.startScaleX * kx
                                state.scaleY = state.startScaleY * ky
                            }
                        }
                        state.handle == 4 || state.handle == 6 -> {
                            val rad = Math.toRadians(-state.startRotation.toDouble())
                            val cosR = cos(rad).toFloat()
                            val sinR = sin(rad).toFloat()
                            val dy = imagePos.y - c.y - state.startTy
                            val dx = imagePos.x - c.x - state.startTx
                            val uy = dx * sinR + dy * cosR

                            val sdy = state.dragStart.y - c.y - state.startTy
                            val sdx = state.dragStart.x - c.x - state.startTx
                            val suy = sdx * sinR + sdy * cosR

                            val ky = if (abs(suy) > 1f) uy / suy else 1f
                            state.scaleY = state.startScaleY * ky
                        }
                        state.handle == 5 || state.handle == 7 -> {
                            val rad = Math.toRadians(-state.startRotation.toDouble())
                            val cosR = cos(rad).toFloat()
                            val sinR = sin(rad).toFloat()
                            val dx = imagePos.x - c.x - state.startTx
                            val dy = imagePos.y - c.y - state.startTy
                            val ux = dx * cosR - dy * sinR

                            val sdx = state.dragStart.x - c.x - state.startTx
                            val sdy = state.dragStart.y - c.y - state.startTy
                            val sux = sdx * cosR - sdy * sinR

                            val kx = if (abs(sux) > 1f) ux / sux else 1f
                            state.scaleX = state.startScaleX * kx
                        }
                        state.handle == 8 -> {
                            state.tx = state.startTx + (imagePos.x - state.dragStart.x)
                            state.ty = state.startTy + (imagePos.y - state.dragStart.y)
                        }
                    }
                }
            }
            else -> Unit
        }
    }


    /**
     * 液化沿路径推进: 位移大于笔刷影响半径时拆成多个补点, 让相邻形变搭接,
     * 消除快速拖动时的断线。强度按 [LiquifyPath.substepStrengthScale] 折算,
     * 保证细分前后总形变量一致 (引擎侧幅度曲线对每个 dab 有固定底)。
     *
     * 关闭合并(latest-state-wins)时的逐点路径: 每个输入点立即**全量**推进, 与历史行为一致。
     */
    private fun liquifyAlongPath(v: PaintViewModel, to: Offset) {
        liquifySession.submitTarget(to.x, to.y, liquifyPressureFactor())
        liquifyFlushNow(v, forceFull = true)
    }

    /**
     * Phase 3A/3B: 把会话里待推进的位移段提交给引擎 —— 液化的**唯一 JNI 提交点**。
     *
     * 调度全在 [LiquifyInteractionSession] 里(纯逻辑), 这里只按它的推进计划跑 JNI 循环:
     * 步长与强度折算始终按"整段"口径, 因此分帧只改节奏、不改总量; 抬笔 forceFull 精确落点。
     *
     * @param forceFull true = 抬笔补齐 / 关闭合并的逐点路径(不受每帧补点上限约束)
     */
    /** Runs when HWUI consumes a preview buffer, even while the pen is stationary. */
    fun onLiquifyFramePresented(timestamp: Long) {
        if (!liquifyFieldGesture || liquifyWholeLayerRect != null) return
        val v = vm ?: return
        if (LiquifyGlesPreview.copyPresentedDrawRect(timestamp, liquifyCommittedScratch) &&
            liquifyBasePolicy.shouldAdvance(liquifyCommittedScratch, 1)) {
            v.liquifyPreviewBase(liquifyCommittedScratch[0], liquifyCommittedScratch[1],
                liquifyCommittedScratch[2], liquifyCommittedScratch[3])
        }
    }

    private fun liquifyFlushNow(v: PaintViewModel, forceFull: Boolean) {
        if (liquifyFieldGesture && (!LiquifyGlesPreview.requested || LiquifyGlesPreview.failed ||
            !LiquifyGlesPreview.alive || LiquifyGlesPreview.dabsDropped > 0L)) {
            // Source preparation and GL allocation complete asynchronously. If
            // either fails, recover now instead of leaving the whole drag invisible
            // until pen-up. Native has not received any of these recorded dabs yet.
            liquifyFieldGesture = false
            v.liquifyUseEnginePreview()
            v.replayLiquifyFieldDabs(liquifyDabBuf, liquifyDabCount, FIELD_DAB_STRIDE)
            liquifyDabCount = 0
            liquifyWholeLayerRect = null
            liquifyBasePolicy.reset()
            liquifyDrawRect = null
        }
        var batchCount = 0
        var totalSteps = 0
        var consumedInputs = 0
        var dirtyL = Float.POSITIVE_INFINITY
        var dirtyT = Float.POSITIVE_INFINITY
        var dirtyR = Float.NEGATIVE_INFINITY
        var dirtyB = Float.NEGATIVE_INFINITY
        var budget = if (forceFull || !liquifySession.coalescing) Int.MAX_VALUE
            else liquifySession.maxDabsPerFlush
        while (budget > 0 && liquifySession.prepareFlush(
                liquifyBrushSize, liquifyMode, forceFull, budget, professional = true,
            )) {
            val steps = liquifySession.planSteps
            val strength = liquifyStrength * liquifySession.planStrengthScale * liquifySession.planPressureFactor
            val stepX = liquifySession.planStepX
            val stepY = liquifySession.planStepY
            var px = liquifySession.planStartX
            var py = liquifySession.planStartY
            for (i in 0 until steps) {
                val nx = px + stepX
                val ny = py + stepY
                if (strength <= 0f) {
                    px = nx
                    py = ny
                    continue
                }
                if (liquifyFieldGesture) {
                    // Phase 5 · C3-2: 场通路 —— **拖动期一个 dab 都不进引擎**。真机实测(200px 笔刷)
                    // 原本每秒要 1.07s 的原生工作: 逐 dab 网格形变 550ms + rebase 物化 517ms, 全在这里消失。
                    // 参数只记进本地列表: 抬笔回读若失败, 就靠这份列表重放给引擎, 形变一点不丢。
                    recordLiquifyFieldDab(px, py, nx, ny, liquifyMode, strength)
                    val radius = liquifySupportRadius()
                    dirtyL = minOf(dirtyL, minOf(px, nx) - radius)
                    dirtyT = minOf(dirtyT, minOf(py, ny) - radius)
                    dirtyR = maxOf(dirtyR, maxOf(px, nx) + radius)
                    dirtyB = maxOf(dirtyB, maxOf(py, ny) + radius)
                    // 录制流必须与"逐 dab 提交"完全一致 —— 回放走的是经典路径
                    v.recordLiquifyDab(px, py, nx, ny, liquifyMode, strength)
                } else {
                    batchCount = appendLiquifyBatch(px, py, nx, ny, strength, batchCount)
                }
                // Phase 5 · C3: 同一个补点再推一份给 GLES 常驻位移场 (docs/LIQUIFY-C3-FIELD-PLAN.md §2.2)。
                // 这里**不新增任何 JNI** —— 参数本来就在手上; 场通路下这是唯一的消费者(引擎那边一个都不收),
                // 场未 armed(开关关/由别的路径画)时 pushDab 只是一次 volatile 读, 不进热路径。
                // Each dab composes a bounded inverse map; a whole-segment additive kernel is not equivalent.
                LiquifyGlesPreview.pushDab(px, py, nx, ny, liquifyMode, strength, liquifyBrushSize)
                px = nx
                py = ny
            }
            liquifySession.advanceFlush(steps)
            totalSteps += steps
            consumedInputs += liquifySession.lastFlushInputs
            budget -= steps
        }
        if (totalSteps == 0) return
        LiquifyGlesPreview.publishDabs()
        // 相位 8(背景色线框闪烁修复): 覆盖层的绘制矩形与引擎的"预览基座"矩形必须是**同一个整数
        // 矩形**, 且基座只能跟在覆盖层**已上屏**的帧后面推进 ——
        //   · 两者不一致 ⇒ 差值那圈要么被挖掉却没人补(露画布背景 = 背景色线框), 要么没被挖却在
        //     叠加(半透明内容叠两次 = 深色描边);
        //   · 基座领先 ⇒ 挖掉的那圈在覆盖层画上去之前就是背景 ⇒ 拖拽时每帧闪一次线框。
        if (liquifyFieldGesture) {
            val rect = liquifyWholeLayerRect ?: LiquifyPath.previewRect(
                lqAffectedL, lqAffectedT, lqAffectedR, lqAffectedB, v.docWidth, v.docHeight,
            )
            if (rect != null && (liquifyDrawRect == null || !liquifyDrawRect.contentEquals(rect))) {
                liquifyDrawRect = rect
                // 普通单层由同一 GPU 帧完整替换；复杂场景在 HWUI 消费对应帧后推进底图。
                LiquifyGlesPreview.pushDrawRect(
                    rect[0].toFloat(), rect[1].toFloat(), rect[2].toFloat(), rect[3].toFloat(),
                )
            }
        }

        if (dirtyL.isFinite()) invalidateLiquifyFieldDab(
            (dirtyL + dirtyR) * 0.5f, (dirtyT + dirtyB) * 0.5f,
            maxOf(dirtyR - dirtyL, dirtyB - dirtyT) * 0.5f,
        )
        if (batchCount > 0) {
            // 一次 runCore + 一次 JNI 提交全部补点(native 内按序循环): 顺序与逐点路径完全一致,
            // 但 Handler 消息、渲染调度与跨语言边界都只发生一次。
            v.liquifyBatch(liquifyBatchBuf, batchCount)
        }
        // 合并倍率: 本帧覆盖的输入事件数 / 实际补点数
        PerfTrace.liquifySchedule(consumedInputs, totalSteps)
        // backlog 指标: 滞后事件数 + 仍未提交的补点数(见 PerfTrace.liquifyFlow)
        PerfTrace.liquifyFlow(liquifySession.lag, liquifySession.backlogDabs)
        // Phase 6(量化验收): 端到端输入延迟 —— 事件时间 → 本次提交进入引擎的滞后。
        // 目标: 中端机高强度液化 P95 ≤ 2 帧(33ms); 峰值 > 100ms 即"被肉眼感知的掉帧"。
        val lagMs = if (lastLiquifyEventTimeMs > 0L) {
            (android.os.SystemClock.uptimeMillis() - lastLiquifyEventTimeMs).coerceAtLeast(0L)
        } else {
            0L
        }
        PerfTrace.liquifyLatency(lagMs, if (liquifyPressureActive) smoothedPressure else 0f)
    }

    /**
     * 液化强度的手写笔压力系数(0..1)，手指保持固定强度。
     *
     * 只对**笔**输入生效(与 CSP/SAI2 在触摸屏上的口径一致: 手指 pressure 在多数机型上是
     * 1.0 或触摸面积噪声, 参与调制会让强度乱跳)。`setprop debug.reverie.lqpressure 0` 关闭。
     */
    private fun liquifyPressureFactor(): Float {
        if (!liquifyPressureActive) return 1f
        if (PerfTrace.debugPropInt("debug.reverie.lqpressure", 1) == 0) return 1f
        return smoothedPressure.coerceIn(0f, 1f)
    }

    // ---------------- Phase 5 · C3-2: 场通路(拖动期零引擎解算 + 抬笔一次性提交) ----------------

    /**
     * 尝试进入"场通路"。前置: 本段手势由 GLES 覆盖层画、且场已 armed。
     *
     * 成功 ⇒ 引擎只准备一份"未形变的源像素"(整篇文档, 每段手势一次), 之后的 dab 全部只喂 GPU 场;
     * 失败(超预算 / 非 8bit BGRA / 文档尺寸未知)返回 false, 调用方原样走经典逐 dab 路径。
     */
    private fun beginLiquifyFieldGesture(v: PaintViewModel): Boolean {
        if (!LiquifyGlesPreview.requested || !LiquifyGlesPreview.fieldArmed) return false
        val dw = v.docWidth
        val dh = v.docHeight
        if (dw <= 0 || dh <= 0) return false
        // Keep small brushes precise on large documents. The sparse native field
        // can retain fine nodes; a full GPU texture must otherwise downsample them.
        if (!com.reverie.paint.model.LiquifyProfessional.supportsFullField(dw, dh, liquifyBrushSize) ||
            LiquifyGlesPreview.fieldRes > com.reverie.paint.model.LiquifyProfessional.fieldStep(liquifyBrushSize)) {
            return false
        }
        // 稳定性硬预算: 源纹理 + 场纹理都是"整篇文档"级别的大块内存(CPU/GPU 各留一份),
        // 这里同时叠加"堆安全预算"(堆上限 / 每像素 32B, 见 [MemoryBudget]) —— 低内存设备压到
        // 1/4; 超预算直接回经典路径, 宁可慢一点, 也不能一次手势把进程推到 OOM 边缘。
        val budget = fieldPathBudgetPx()
        val sceneBudget = minOf(budget, Runtime.getRuntime().maxMemory() / 48L)
        if (sceneBudget <= 0L || dw.toLong() * dh.toLong() > sceneBudget) return false
        LiquifyGlesPreview.configureFieldResolution(dw, dh, liquifyBrushSize)
        if (!v.liquifyFieldSource(0, 0, dw, dh)) return false
        // A single ordinary layer can be presented entirely by GLES. Its underlay
        // is drawn in the same GPU buffer, so transparent holes cannot reveal old paint.
        val only = v.layers.lastOrNull { it.visible }
        liquifyWholeLayerRect = if (only != null && only.index == v.currentLayerIndex &&
            only.visible && only.depth == 0 && only.nodeType == 0 && !only.isGroup && !only.isStrokeLayer &&
            !only.clipped && !only.alphaLocked && only.opacity == 1.0 &&
            only.blendMode == "normal" && !v.hasSelection && !v.anim.enabled && !v.pixelGridEnabled)
            intArrayOf(0, 0, dw, dh) else null
        val sceneBitmap = v.displayBitmap ?: docBitmap
        if (sceneBitmap == null) liquifyWholeLayerRect = null
        LiquifyGlesPreview.configureOpaqueScene(
            if (liquifyWholeLayerRect != null) sceneBitmap?.width ?: 0 else 0,
            if (liquifyWholeLayerRect != null) sceneBitmap?.height ?: 0 else 0)
        liquifyDabCount = 0
        liquifyBasePolicy.reset()
        liquifyDrawRect = null
        lqAffectedL = Float.MAX_VALUE
        lqAffectedT = Float.MAX_VALUE
        lqAffectedR = -Float.MAX_VALUE
        lqAffectedB = -Float.MAX_VALUE
        return true
    }

    /**
     * 记一个补点(场通路): 追加进本地列表 + 扩大受影响矩形 + 把绘制/失效范围同步给覆盖层。
     *
     * 受影响矩形 = 各补点**影响圆**的并集 —— 场的位移只在圆内非零, 所以"绘制范围""失效范围"
     * "抬笔回读范围"是同一个矩形, 一次算清三处都用它。
     */
    private fun recordLiquifyFieldDab(
        px: Float,
        py: Float,
        nx: Float,
        ny: Float,
        mode: Int,
        strength: Float,
    ) {
        if ((liquifyDabCount + 1) * FIELD_DAB_STRIDE > liquifyDabBuf.size) {
            val grown = FloatArray(maxOf(liquifyDabBuf.size * 2, FIELD_DAB_STRIDE * 256))
            System.arraycopy(liquifyDabBuf, 0, grown, 0, liquifyDabBuf.size)
            liquifyDabBuf = grown
        }
        val b = liquifyDabCount * FIELD_DAB_STRIDE
        liquifyDabBuf[b] = px
        liquifyDabBuf[b + 1] = py
        liquifyDabBuf[b + 2] = nx
        liquifyDabBuf[b + 3] = ny
        liquifyDabBuf[b + 4] = mode.toFloat()
        liquifyDabBuf[b + 5] = strength
        liquifyDabBuf[b + 6] = liquifyBrushSize
        liquifyDabCount++
        val r = liquifySupportRadius()
        lqAffectedL = minOf(lqAffectedL, minOf(px, nx) - r)
        lqAffectedT = minOf(lqAffectedT, minOf(py, ny) - r)
        lqAffectedR = maxOf(lqAffectedR, maxOf(px, nx) + r)
        lqAffectedB = maxOf(lqAffectedB, maxOf(py, ny) + r)
        // 绘制矩形不在这里推: 它必须与引擎的"预览基座"是同一个**整数**矩形, 由 liquifyFlushNow
        // 每帧统一推一次(浮点矩形与基座矩形差出一圈就会露背景或叠两次)。
    }

    /** 预览基座的推进门槛(文档像素): 影响半径的 1/8, 夹到 [4, 64]。 */
    private fun liquifySupportRadius(): Float = liquifyBrushSize.coerceAtLeast(8f) * .5f +
        com.reverie.paint.model.LiquifyProfessional.fieldStep(liquifyBrushSize) + 2f

    private fun liquifyBaseAdvanceStepPx(): Int =
        (liquifySupportRadius() / 8f).toInt().coerceIn(4, 64)

    /**
     * 场通路的局部失效: 位移只在本 dab 的影响圆里变化, 所以只失效**该圆** ∪ 光标环前后位置。
     * 判据与 [scheduleLiquifyInvalidate] 同源(任一安全条件不满足就整屏), 只是脏区来源换成补点。
     */
    private fun invalidateLiquifyFieldDab(cx: Float, cy: Float, radius: Float) {
        val v = vm ?: return
        if (!partialInvalidateEnabled || !canLiquifyPartialInvalidate(v)) {
            postInvalidate()
            return
        }
        ensureViewTransform()
        var left = Float.MAX_VALUE
        var top = Float.MAX_VALUE
        var right = -Float.MAX_VALUE
        var bottom = -Float.MAX_VALUE
        for (i in 0 until 4) {
            viewTransform.docToScreen(
                if (i == 0 || i == 2) cx - radius else cx + radius,
                if (i < 2) cy - radius else cy + radius,
                lqPointScratch,
            )
            val sx = lqPointScratch[0]
            val sy = lqPointScratch[1]
            if (sx < left) left = sx
            if (sx > right) right = sx
            if (sy < top) top = sy
            if (sy > bottom) bottom = sy
        }
        left -= 8f
        top -= 8f
        right += 8f
        bottom += 8f
        if (lqRingValid) {
            left = minOf(left, lqRingCx - lqRingR - 2f)
            top = minOf(top, lqRingCy - lqRingR - 2f)
            right = maxOf(right, lqRingCx + lqRingR + 2f)
            bottom = maxOf(bottom, lqRingCy + lqRingR + 2f)
        }
        if (hasLocalCursorPos) {
            val r = if (lqRingValid) lqRingR else 0f
            left = minOf(left, localCursorX - r - 2f)
            top = minOf(top, localCursorY - r - 2f)
            right = maxOf(right, localCursorX + r + 2f)
            bottom = maxOf(bottom, localCursorY + r + 2f)
        }
        val l = left.toInt().coerceIn(0, viewW)
        val t = top.toInt().coerceIn(0, viewH)
        val rr = right.toInt().coerceIn(0, viewW)
        val bb = bottom.toInt().coerceIn(0, viewH)
        if (rr <= l || bb <= t) return
        postInvalidate(l, t, rr, bb)
    }

    /**
     * 抬笔提交(场通路): 回读覆盖层的形变结果 → 一次性写回图层。
     *
     * 任何一步失败(覆盖层不在 / 回读超时 / 范围为空)都**回退经典路径**: 把记录下来的补点按序
     * 重放给引擎再 materialize ⇒ 形变一点不丢, 只是慢一些(而且重放走引擎线程, 不挡 UI)。
     */
    private fun commitLiquifyField(v: PaintViewModel) {
        liquifyFieldGesture = false
        val rect = fieldCommitRect(v)
        if (liquifyDabCount > 0) {
            // 回读 + 写回 + 收口整段都在引擎线程上跑(见该方法的注释), UI 线程不阻塞
            // A rejected readback rectangle still has dabs to replay. Ending the
            // native transaction directly would discard the entire GPU gesture.
            v.liquifyFieldEndFromOverlay(
                rect ?: intArrayOf(0, 0, 0, 0), liquifyDabBuf, liquifyDabCount, FIELD_DAB_STRIDE,
            )
        } else {
            // 没有有效范围(纯点按): 走经典收口
            v.liquifyEnd()
        }
        liquifyDabCount = 0
        liquifyBasePolicy.reset()
        liquifyDrawRect = null
    }

    /**
     * 受影响矩形 → 文档整数矩形(夹到文档内; 空 ⇒ null = 回退经典路径)。
     *
     * 也会挡住"回读矩形过大": 回读要一次性分配 `w*h*4` 字节并过一次 JNI, 超预算就回退
     * **重放补点** —— 那条路是流式的, 不额外吃大块内存(体积换稳定性)。
     */
    private fun fieldCommitRect(v: PaintViewModel): IntArray? {
        if (liquifyDabCount <= 0 || lqAffectedR <= lqAffectedL || lqAffectedB <= lqAffectedT) return null
        val x0 = floor(lqAffectedL).toInt().coerceAtLeast(0)
        val y0 = floor(lqAffectedT).toInt().coerceAtLeast(0)
        val x1 = ceil(lqAffectedR).toInt().coerceAtMost(v.docWidth)
        val y1 = ceil(lqAffectedB).toInt().coerceAtMost(v.docHeight)
        if (x1 <= x0 || y1 <= y0) return null
        val w = x1 - x0
        val h = y1 - y0
        val budget = fieldPathBudgetPx()
        if (budget <= 0L || w.toLong() * h.toLong() > budget) return null
        // 回读一次要三份同尺寸缓冲(Java 数组 / Direct 缓冲 / FBO 纹理) —— 再卡一道字节预算
        if (!MemoryBudget.commitBytesWithinBudget(w, h)) return null
        return intArrayOf(x0, y0, w, h)
    }

    /** C3-2: 低内存设备判定(用系统自己的分级, 而不是猜总内存)。 */
    private fun isLowRamDevice(): Boolean = try {
        val am = context.getSystemService(android.content.Context.ACTIVITY_SERVICE)
            as? android.app.ActivityManager
        am?.isLowRamDevice == true
    } catch (_: Throwable) {
        false
    }

    /**
     * 场通路当前生效的文档像素预算(动态)。
     *
     * 取较小值: 历史硬上限(4M px) 与 [MemoryBudget] 的堆安全预算(堆上限 / 每像素 32B)。
     * `0` = 这台设备上不可用 ⇒ 调用方一律回退经典逐 dab 路径。
     *
     * 为什么不能只看 `isLowRamDevice`: 它只覆盖 Android Go 级别的设备; 普通 4~6GB 手机上
     * Java 堆上限也可能只有 192~256MB, 而此时场通路(源/场/回读各一份)照样能把进程推到 OOM。
     */
    private fun fieldPathBudgetPx(): Long {
        val maxHeap = try {
            Runtime.getRuntime().maxMemory()
        } catch (_: Throwable) {
            -1L
        }
        val lowRam = isLowRamDevice()
        val hard = if (lowRam) FIELD_PATH_MAX_PX_LOW_RAM else FIELD_PATH_MAX_PX
        return minOf(hard, MemoryBudget.fieldPathBudgetPx(maxHeap, lowRam)).coerceAtLeast(0L)
    }

    /**
     * 追加一个补点到批量提交缓冲(按需扩容, 复用), 返回新的补点计数。
     *
     * 缓冲只增不减: 一段手势内的补点数有界(移动距离 / 笔刷半径), 不增长就意味着不会反复分配。
     */
    private fun appendLiquifyBatch(
        px: Float,
        py: Float,
        nx: Float,
        ny: Float,
        strength: Float,
        count: Int,
    ): Int {
        if ((count + 1) * LIQUIFY_BATCH_STRIDE > liquifyBatchBuf.size) {
            val grown = FloatArray(maxOf(liquifyBatchBuf.size * 2, LIQUIFY_BATCH_STRIDE * 64))
            System.arraycopy(liquifyBatchBuf, 0, grown, 0, liquifyBatchBuf.size)
            liquifyBatchBuf = grown
        }
        // 打包走 model 层的纯函数(与 JNI liquifyDabs 同序), 布局由 LiquifyModeConsistencyTest 守门
        return LiquifyPath.packDab(liquifyBatchBuf, count, px, py, nx, ny, strength, liquifyMode)
    }

    /**
     * Phase 3B: latest-state-wins 的"一帧推进"。
     *
     * 每帧最多推进 [LiquifyInteractionSession.maxDabsPerFlush] 个补点, 方向永远指向**最新**位置;
     * 没追完下一帧继续。与"逐个历史点立即处理"的差别只有两点: ①同一帧内多个输入事件被合并成
     * 一段(中间位置丢弃); ②单帧阻塞时间有上界 —— 不会再出现"一个事件里 10 个历史点 × 每个
     * 41ms"这种排队。
     */
    private fun flushLiquifyPending() {
        liquifyFlushPosted = false
        val v = vm ?: return
        // Phase 6(稳定性 v2): 分帧物化推进 —— 每次最多消费 4ms 的落盘量, 把此前
        // 单次 126ms 的 rebase 尖峰摊到各帧。放在背压判断**之前**: 停手后即使不再新增
        // 补点(或引擎忙), 已积压的物化也必须继续推进, 否则会留下"半物化"的陈旧像素。
        if (!liquifyFieldGesture) v.tickLiquifyMaterialize()
        val materializePending = v.liquifyMaterializePending
        // 引擎背压: 队列里还积压着上一次推进(阈值见 LIQUIFY_ENGINE_BACKLOG_LIMIT)时,
        // 本帧**不再新增补点** —— 会话保留"最新位置", 下一帧继续追。
        // 高压拖动下这是把"队列雪崩 / 抬笔后画面还在继续变形"掐掉的关键一步。
        if (v.pendingCoreOps.get() > LIQUIFY_ENGINE_BACKLOG_LIMIT) {
            if ((liquifySession.hasPending || materializePending) && !liquifyFlushPosted) {
                liquifyFlushPosted = true
                postOnAnimation(liquifyFlushRunnable)
            }
            return
        }
        liquifyFlushNow(v, forceFull = false)
        if ((liquifySession.hasPending || materializePending) && !liquifyFlushPosted) {
            // 还没追上最新位置(或还有待落盘的行带): 下一帧继续推进
            liquifyFlushPosted = true
            postOnAnimation(liquifyFlushRunnable)
        }
    }

    /**
     * Phase 6: 液化笔压平滑(指数滑动, 系数 0.35 与笔刷路径同族)。
     *
     * 手指/鼠标输入直接跳过 —— Android 的手指 pressure 在多数机型上是 1.0 或噪声,
     * 参与调制只会让强度随触摸面积乱跳(这正是 SAI2/CSP 在触摸屏上的做法: 只认笔压)。
     */
    private fun updateLiquifyPressure(event: MotionEvent, pointerIndex: Int) {
        val tt = event.getToolType(pointerIndex)
        if (tt != MotionEvent.TOOL_TYPE_STYLUS && tt != MotionEvent.TOOL_TYPE_ERASER) return
        val p = event.getPressure(pointerIndex).coerceIn(0f, 1f)
        val dt = if (lastLiquifyEventTimeMs > 0L) {
            (event.eventTime - lastLiquifyEventTimeMs).coerceIn(0L, 100L).toFloat()
        } else 0f
        val alpha = if (!liquifyPressureActive) 1f else 1f - kotlin.math.exp(-dt / 19.35f)
        smoothedPressure += (p - smoothedPressure) * alpha
        smoothedPressure = smoothedPressure.coerceIn(0f, 1f)
        liquifyPressureActive = true
    }

    private fun handleToolUp(event: MotionEvent, docPos: Offset, isCancel: Boolean) {
        removeCallbacks(quickShapeHold)
        removeCallbacks(liquifyHoldRunnable)
        val v = vm ?: return

        if (draggingGuideHandleIndex != -1) {
            draggingGuideHandleIndex = -1
            return
        }

        when (effTool()) {
            Tool.BRUSH, Tool.ERASER, Tool.SMUDGE -> {
                val hasSymmetry = v.drawingGuide.mode == GuideMode.SYMMETRY && v.drawingGuide.assistedDrawing
                if (strokeStarted) {
                    cachedDriver?.feedbackManager?.setWritingHapticsEnabled(false)
                    cachedDriver?.feedbackManager?.stopStrokeSound()
                    val candidate = quickShapeCandidate
                    val capture = v.quickShapeCapture
                    v.quickShapeCapture = null
                    quickShapeCandidate = null
                    if (!isCancel && v.quickShapeEnabled && candidate != null && capture != null && !capture.overflowed) {
                        v.beginQuickShape(candidate, capture.snapshot())
                        resetMirrorBranches()
                        safeEndSymmetryUndoMacro()
                    } else if (isCancel) {
                        v.touchCancel()
                        resetMirrorBranches()
                        safeEndSymmetryUndoMacro()
                    } else {
                        val hasBranchesToReplay = hasSymmetry && mirrorBranchHasSamples()
                        // 仅当没有镜像分支重放时主笔才立即全量渲染；有分支则在镜像分支执行完毕后统一渲染
                        v.touchEnd(render = !hasBranchesToReplay)
                        if (hasBranchesToReplay) {
                            val isEraserStroke = (effTool() == Tool.ERASER)
                            v.replaySymmetricBranches(
                                mirroredSamples,
                                mirroredSizes,
                                mirroredSamples.size,
                                isEraser = isEraserStroke,
                                onComplete = {
                                    resetMirrorBranches()
                                    isSymmetryUndoMacroOpen = false
                                    invalidate()
                                }
                            )
                        } else {
                            resetMirrorBranches()
                            safeEndSymmetryUndoMacro()
                        }
                    }
                    strokeStarted = false
                } else {
                    resetMirrorBranches()
                    safeEndSymmetryUndoMacro()
                }
                assistLockedRay = null
                clearPredictionState(triggerInvalidate = true)
                currentStrokeDocPos = Offset.Zero
            }
            Tool.LIQUIFY -> {
                if (strokeStarted) {
                    // Phase 3B: 抬笔要把没追完的剩余段一次性补齐(此时按常规补点规则覆盖整段,
                    // 不丢形变), 再提交事务
                    if (liquifySession.coalescing) {
                        removeCallbacks(liquifyFlushRunnable)
                        liquifyFlushPosted = false
                        liquifyFlushNow(v, forceFull = true)
                    }
                    if (com.reverie.paint.BuildConfig.DEBUG) android.util.Log.i(
                        "ReverieLiquify",
                        "end doc=${v.docWidth}x${v.docHeight} brush=$liquifyBrushSize " +
                            "field=$liquifyFieldGesture gles=${LiquifyGlesPreview.requested} " +
                            "inputs=${liquifySession.inputCount} dabs=${liquifySession.dabCount} " +
                            "frames=${LiquifyGlesPreview.renderedFrames} " +
                            "uploads=${LiquifyGlesPreview.sourceUploadCount} dropped=${LiquifyGlesPreview.dabsDropped}",
                    )
                    liquifySession.reset()
                    // 多指误触保护: 液化手势收到 CANCEL(典型来源 = 第二指落下被系统判成
                    // 手势接管)时**一律走提交** —— 提交后用户还能撤销, 而取消会直接丢掉
                    // 整段形变(真机表现为"画到一半手指碰一下, 形变没了")。
                    if (isCancel && !isLiquifyGestureActive) {
                        liquifyFieldGesture = false
                        liquifyBasePolicy.reset()
                        liquifyDrawRect = null
                        v.liquifyCancel()
                    } else if (liquifyFieldGesture) {
                        // Phase 5 · C3-2: 拖动期一个 dab 都没进引擎 —— 现在把 GPU 算好的结果
                        // 一次性写回图层(失败则自动重放补点)
                        commitLiquifyField(v)
                    } else {
                        v.liquifyEnd()
                    }
                    strokeStarted = false
                }
            }
            Tool.SHAPES, Tool.LINE, Tool.RECT, Tool.ELLIPSE, Tool.POLYGON, Tool.POLYLINE, Tool.PATH -> {
                handleShapeUp(docPos)
            }
            Tool.SELECT_POLYGON -> {
                isPolyPointPendingOnTouch = false
            }
            Tool.TEXT -> {
                handleTextUp(docPos)
            }
            Tool.GRADIENT -> {
                liveShapeStart?.value = null
                liveShapeEnd?.value = null
                v.gradientFill(
                    firstDocPos.x.toInt(), firstDocPos.y.toInt(),
                    shapeEndDocPos.x.toInt(), shapeEndDocPos.y.toInt(),
                    v.gradientType, v.gradientRepeat, v.gradientReverse
                )
            }
            Tool.SELECT_RECT -> {
                liveShapeStart?.value = null
                liveShapeEnd?.value = null
                v.selectShape(0, firstDocPos.x.toInt(), firstDocPos.y.toInt(), shapeEndDocPos.x.toInt(), shapeEndDocPos.y.toInt())
            }
            Tool.SELECT_ELLIPSE -> {
                liveShapeStart?.value = null
                liveShapeEnd?.value = null
                v.selectShape(1, firstDocPos.x.toInt(), firstDocPos.y.toInt(), shapeEndDocPos.x.toInt(), shapeEndDocPos.y.toInt())
            }
            Tool.LASSO -> {
                val subMode = v.lassoSubMode
                liveSelectionPath?.value = null
                if (justFinishedLassoInDown) {
                    justFinishedLassoInDown = false
                    lassoPoints.clear()
                    return
                }
                if (isCancel) {
                    lassoPoints.clear()
                    return
                }
                if (subMode == LassoSubMode.FREEHAND) {
                    if (lassoPoints.size >= 3) {
                        val points = lassoPoints.map { it.x.toInt() to it.y.toInt() }
                        v.lassoSelect(points)
                    }
                    lassoPoints.clear()
                } else {
                    val bmp = v.displayBitmap ?: docBitmap
                    val bmpW = (bmp?.width ?: v.renderW.takeIf { it > 0 } ?: v.docWidth).toFloat()
                    val docW = (if (v.docWidth > 0) v.docWidth else bmpW.toInt()).toFloat()
                    val bmpPerDoc = (bmpW / docW).coerceAtLeast(0.001f)
                    val currentScale = maxOf(0.01f, canvasZoom * canvasFitScale)
                    val currentDocScale = maxOf(0.001f, currentScale * bmpPerDoc)
                    val dragDist = hypot(docPos.x - firstDocPos.x, docPos.y - firstDocPos.y)
                    val isTap = dragDist < (8f * density) / currentDocScale && lassoPoints.size <= 3
                    val snapDistThreshold = (24f * density) / currentDocScale

                    // 1. 若已有 >= 3 个点，且本次抬手位置落在起点吸附阈值内，直接闭合提交（不重复追加起点）
                    if (v.lassoMultiPoints.size >= 3) {
                        val startPt = v.lassoMultiPoints.first()
                        if (hypot(docPos.x - startPt.first, docPos.y - startPt.second) <= snapDistThreshold) {
                            v.finishLassoMulti()
                            lassoPoints.clear()
                            return
                        }
                    }

                    // 2. 将本次点击或拖拽段加入点列表
                    if (subMode == LassoSubMode.POLYLINE || isTap) {
                        v.lassoMultiPoints = v.lassoMultiPoints + (docPos.x.toInt() to docPos.y.toInt())
                        v.lassoSegmentCounts.add(1)
                    } else {
                        val pts = lassoPoints.map { it.x.toInt() to it.y.toInt() }
                        v.lassoMultiPoints = v.lassoMultiPoints + pts
                        v.lassoSegmentCounts.add(pts.size)
                    }

                    // 3. 混合模式下：若刚画完的自由笔画本身形成闭环（终点靠近初始起点），直接闭合提交
                    if (v.lassoMultiPoints.size >= 3) {
                        val startPt = v.lassoMultiPoints.first()
                        if (hypot(docPos.x - startPt.first, docPos.y - startPt.second) <= snapDistThreshold) {
                            v.finishLassoMulti()
                        }
                    }
                    lassoPoints.clear()
                }
            }
            Tool.MEASURE -> {
                activeMeasureHandle = -1
            }
            Tool.TRANSFORM -> {
                tfState?.handle = -1
            }
            Tool.PICKER -> {
                pickerActive?.value = false
                val curCol = pickerCurrentColor?.value
                if (curCol != null) {
                    val r = (curCol.red * 255).toInt().coerceIn(0, 255)
                    val g = (curCol.green * 255).toInt().coerceIn(0, 255)
                    val b = (curCol.blue * 255).toInt().coerceIn(0, 255)
                    val hex = String.format("#%02X%02X%02X", r, g, b)
                    v.updateBrushColor(hex)
                    v.showActionToast(context.getString(R.string.canvas_toast_color_picked), R.drawable.ic_picker)
                }
                if (v.isTemporaryPicker) {
                    v.restorePreviousTool()
                }
            }
            else -> Unit
        }
    }

    private fun isShapeTool(t: Tool): Boolean =
        t == Tool.SHAPES || t == Tool.LINE || t == Tool.RECT ||
        t == Tool.ELLIPSE || t == Tool.POLYGON || t == Tool.POLYLINE ||
        t == Tool.PATH || t.group == ToolGroup.SHAPES

    private fun defaultShapeType(t: Tool, fallback: ShapeType): ShapeType =
        when (t) {
            Tool.LINE -> ShapeType.LINE
            Tool.RECT -> ShapeType.RECT
            Tool.ELLIPSE -> ShapeType.ELLIPSE
            Tool.POLYGON -> ShapeType.POLYGON
            Tool.POLYLINE -> ShapeType.POLYLINE
            Tool.PATH -> ShapeType.BEZIER
            else -> fallback
        }

    private fun hitTestShapeHandle(
        state: ShapeState,
        docPos: Offset,
        density: Float,
        currentScale: Float,
    ): Int {
        val hitDist = (28f * density) / maxOf(0.01f, currentScale)
        when (state.type) {
            ShapeType.LINE -> {
                if (hypot(docPos.x - state.p1.x, docPos.y - state.p1.y) < hitDist) return ShapeHandleId.LINE_P1
                if (hypot(docPos.x - state.p2.x, docPos.y - state.p2.y) < hitDist) return ShapeHandleId.LINE_P2
                val mid = (state.p1 + state.p2) / 2f
                if (hypot(docPos.x - mid.x, docPos.y - mid.y) < hitDist) return ShapeHandleId.TRANSLATE_BODY
                val d = ShapeGeometry.distanceToSegment(Point2D(docPos.x, docPos.y), Point2D(state.p1.x, state.p1.y), Point2D(state.p2.x, state.p2.y))
                if (d < hitDist) return ShapeHandleId.TRANSLATE_BODY
            }
            ShapeType.RECT, ShapeType.ROUNDED_RECT, ShapeType.ELLIPSE -> {
                val p2 = if (state.keepAspect) {
                    val pt = ShapeGeometry.constrainAspect(Point2D(state.p1.x, state.p1.y), Point2D(state.p2.x, state.p2.y))
                    Offset(pt.x, pt.y)
                } else state.p2
                val (tl, br) = ShapeGeometry.normalizeRect(Point2D(state.p1.x, state.p1.y), Point2D(p2.x, p2.y))
                val tr = Offset(br.x, tl.y)
                val bl = Offset(tl.x, br.y)
                val center = Offset((tl.x + br.x) / 2f, (tl.y + br.y) / 2f)

                val localPos = if (abs(state.rotationDegrees) > 0.01f) {
                    val rotated = ShapeGeometry.rotatePoint(Point2D(docPos.x, docPos.y), Point2D(center.x, center.y), -state.rotationDegrees)
                    Offset(rotated.x, rotated.y)
                } else docPos

                val rotPos = Offset(center.x, tl.y - (28f * density) / maxOf(0.01f, currentScale))
                if (hypot(localPos.x - rotPos.x, localPos.y - rotPos.y) < hitDist) return ShapeHandleId.ROTATE

                if (state.type == ShapeType.ROUNDED_RECT) {
                    val cr = state.cornerRadius.coerceIn(0f, minOf(br.x - tl.x, br.y - tl.y) / 2f)
                    val crPos = Offset(tl.x + cr, tl.y + cr)
                    if (hypot(localPos.x - crPos.x, localPos.y - crPos.y) < hitDist) return ShapeHandleId.CORNER_RADIUS
                }

                if (hypot(localPos.x - tl.x, localPos.y - tl.y) < hitDist) return ShapeHandleId.CORNER_TL
                if (hypot(localPos.x - tr.x, localPos.y - tr.y) < hitDist) return ShapeHandleId.CORNER_TR
                if (hypot(localPos.x - br.x, localPos.y - br.y) < hitDist) return ShapeHandleId.CORNER_BR
                if (hypot(localPos.x - bl.x, localPos.y - bl.y) < hitDist) return ShapeHandleId.CORNER_BL

                if (localPos.x in tl.x..br.x && localPos.y in tl.y..br.y) return ShapeHandleId.TRANSLATE_BODY
            }
            ShapeType.REGULAR_POLYGON -> {
                val center = (state.p1 + state.p2) / 2f
                val radius = hypot(state.p2.x - state.p1.x, state.p2.y - state.p1.y) / 2f
                val baseAngle = (-PI / 2.0).toFloat() + Math.toRadians(state.rotationDegrees.toDouble()).toFloat()
                val topH = Offset(center.x + radius * cos(baseAngle), center.y + radius * sin(baseAngle))
                if (hypot(docPos.x - topH.x, docPos.y - topH.y) < hitDist) return ShapeHandleId.STAR_OUTER
                if (hypot(docPos.x - center.x, docPos.y - center.y) < hitDist) return ShapeHandleId.TRANSLATE_BODY
                if (hypot(docPos.x - center.x, docPos.y - center.y) < radius) return ShapeHandleId.TRANSLATE_BODY
            }
            ShapeType.STAR -> {
                val center = (state.p1 + state.p2) / 2f
                val outerR = hypot(state.p2.x - state.p1.x, state.p2.y - state.p1.y) / 2f
                val innerR = outerR * state.starInnerRatio
                val baseAngle = (-PI / 2.0).toFloat() + Math.toRadians(state.rotationDegrees.toDouble()).toFloat()
                val outerH = Offset(center.x + outerR * cos(baseAngle), center.y + outerR * sin(baseAngle))
                if (hypot(docPos.x - outerH.x, docPos.y - outerH.y) < hitDist) return ShapeHandleId.STAR_OUTER
                val angleStep = (PI / state.starPoints).toFloat()
                val innerAngle = baseAngle + angleStep
                val innerH = Offset(
                    center.x + innerR * cos(innerAngle),
                    center.y + innerR * sin(innerAngle)
                )
                if (hypot(docPos.x - innerH.x, docPos.y - innerH.y) < hitDist) return ShapeHandleId.STAR_INNER
                if (hypot(docPos.x - center.x, docPos.y - center.y) < hitDist) return ShapeHandleId.TRANSLATE_BODY
                if (hypot(docPos.x - center.x, docPos.y - center.y) < outerR) return ShapeHandleId.TRANSLATE_BODY
            }
            ShapeType.POLYLINE, ShapeType.POLYGON -> {
                for (i in state.nodes.indices) {
                    val n = state.nodes[i]
                    if (hypot(docPos.x - n.pos.x, docPos.y - n.pos.y) < hitDist) {
                        state.selectedNodeIndex = i
                        return ShapeHandleId.NODE_ANCHOR_BASE + i
                    }
                }
            }
            ShapeType.BEZIER -> {
                val selIdx = state.selectedNodeIndex
                if (selIdx in 0 until state.nodes.size) {
                    val n = state.nodes[selIdx]
                    if (hypot(docPos.x - n.cpIn.x, docPos.y - n.cpIn.y) < hitDist) {
                        return ShapeHandleId.NODE_CP_IN_BASE + selIdx
                    }
                    if (hypot(docPos.x - n.cpOut.x, docPos.y - n.cpOut.y) < hitDist) {
                        return ShapeHandleId.NODE_CP_OUT_BASE + selIdx
                    }
                }
                for (i in state.nodes.indices) {
                    val n = state.nodes[i]
                    if (hypot(docPos.x - n.pos.x, docPos.y - n.pos.y) < hitDist) {
                        state.selectedNodeIndex = i
                        return ShapeHandleId.NODE_ANCHOR_BASE + i
                    }
                }
            }
        }
        return ShapeHandleId.NONE
    }

    private fun handleShapeDown(docPos: Offset) {
        val v = vm ?: return
        val state = v.shapeState
        val currentScale = maxOf(0.01f, canvasZoom * canvasFitScale)
        val hitDist = (28f * density) / currentScale
        val now = SystemClock.uptimeMillis()

        if (state.active) {
            val hit = hitTestShapeHandle(state, docPos, density, currentScale)
            if (hit != ShapeHandleId.NONE) {
                state.activeHandle = hit
                state.dragStartDocPos = docPos
                state.dragP1 = state.p1
                state.dragP2 = state.p2
                state.dragRotation = state.rotationDegrees
                state.dragCornerRadius = state.cornerRadius
                state.dragStarInnerRatio = state.starInnerRatio
                state.isCreatingNewNode = false
                shapeCreatedOnCurrentTouch = false
            } else {
                if (state.type == ShapeType.POLYLINE || state.type == ShapeType.POLYGON || state.type == ShapeType.BEZIER) {
                    if (state.nodes.size >= 3) {
                        val firstPos = state.nodes.first().pos
                        val isNearFirst = hypot(docPos.x - firstPos.x, docPos.y - firstPos.y) < hitDist
                        val isDoubleTap = now - lastShapeTapTimeMs < 350L &&
                            hypot(docPos.x - lastShapeTapDocPos.x, docPos.y - lastShapeTapDocPos.y) < hitDist
                        if (isNearFirst || isDoubleTap) {
                            lastShapeTapTimeMs = 0L
                            state.closed = true
                            v.commitActiveShape()
                            return
                        }
                    }
                    lastShapeTapTimeMs = now
                    lastShapeTapDocPos = docPos

                    state.nodes.add(ShapeNode(Point2D(docPos.x, docPos.y)))
                    val newIdx = state.nodes.size - 1
                    state.selectedNodeIndex = newIdx
                    state.activeHandle = ShapeHandleId.NODE_ANCHOR_BASE + newIdx
                    state.dragStartDocPos = docPos
                    state.isCreatingNewNode = true
                    shapeCreatedOnCurrentTouch = false
                } else {
                    // 连续绘制保障：若当前有尺寸有效的前序形状，自动提交上屏
                    val shapeDist = hypot(state.p2.x - state.p1.x, state.p2.y - state.p1.y)
                    if (shapeDist > 4f) {
                        v.commitActiveShape()
                    }

                    val targetType = defaultShapeType(tool, state.type)
                    state.reset(targetType, docPos)
                    if (state.strokeWidth <= 0f) {
                        state.strokeWidth = v.brushSize.toFloat().coerceIn(1f, 100f)
                    }
                    state.activeHandle = if (targetType == ShapeType.LINE) ShapeHandleId.LINE_P2 else ShapeHandleId.CORNER_BR
                    state.dragStartDocPos = docPos
                    state.dragP1 = docPos
                    state.dragP2 = docPos
                    state.isCreatingNewNode = false
                    shapeCreatedOnCurrentTouch = true
                }
            }
        } else {
            val targetType = defaultShapeType(tool, state.type)
            state.reset(targetType, docPos)
            if (state.strokeWidth <= 0f) {
                state.strokeWidth = v.brushSize.toFloat().coerceIn(1f, 100f)
            }
            if (targetType == ShapeType.POLYLINE || targetType == ShapeType.POLYGON || targetType == ShapeType.BEZIER) {
                state.activeHandle = ShapeHandleId.NODE_ANCHOR_BASE + 0
                state.selectedNodeIndex = 0
                state.dragStartDocPos = docPos
                state.isCreatingNewNode = true
                shapeCreatedOnCurrentTouch = false
                lastShapeTapTimeMs = now
                lastShapeTapDocPos = docPos
            } else {
                state.activeHandle = if (targetType == ShapeType.LINE) ShapeHandleId.LINE_P2 else ShapeHandleId.CORNER_BR
                state.dragStartDocPos = docPos
                state.dragP1 = docPos
                state.dragP2 = docPos
                state.isCreatingNewNode = false
                shapeCreatedOnCurrentTouch = true
            }
        }
    }

    private fun handleShapeMove(docPos: Offset) {
        val v = vm ?: return
        val state = v.shapeState
        if (state.active && state.activeHandle != ShapeHandleId.NONE) {
            val handle = state.activeHandle
            when {
                handle == ShapeHandleId.TRANSLATE_BODY -> {
                    val delta = docPos - state.dragStartDocPos
                    state.p1 = state.dragP1 + delta
                    state.p2 = state.dragP2 + delta
                }
                handle == ShapeHandleId.LINE_P1 -> {
                    state.p1 = docPos
                }
                handle == ShapeHandleId.LINE_P2 -> {
                    state.p2 = docPos
                }
                handle == ShapeHandleId.ROTATE -> {
                    val center = (state.p1 + state.p2) / 2f
                    val angleRad = atan2(docPos.y - center.y, docPos.x - center.x)
                    state.rotationDegrees = Math.toDegrees(angleRad.toDouble()).toFloat() + 90f
                }
                handle == ShapeHandleId.CORNER_BR || handle == ShapeHandleId.CORNER_TL ||
                handle == ShapeHandleId.CORNER_TR || handle == ShapeHandleId.CORNER_BL ||
                handle == ShapeHandleId.CORNER_RADIUS -> {
                    val center = (state.dragP1 + state.dragP2) / 2f
                    val localPt = if (abs(state.rotationDegrees) > 0.01f) {
                        ShapeGeometry.rotatePoint(Point2D(docPos.x, docPos.y), Point2D(center.x, center.y), -state.rotationDegrees)
                    } else Point2D(docPos.x, docPos.y)
                    val localDocPos = Offset(localPt.x, localPt.y)

                    when (handle) {
                        ShapeHandleId.CORNER_BR -> {
                            state.p2 = localDocPos
                        }
                        ShapeHandleId.CORNER_TL -> {
                            state.p1 = localDocPos
                        }
                        ShapeHandleId.CORNER_TR -> {
                            state.p1 = Offset(state.p1.x, localDocPos.y)
                            state.p2 = Offset(localDocPos.x, state.p2.y)
                        }
                        ShapeHandleId.CORNER_BL -> {
                            state.p1 = Offset(localDocPos.x, state.p1.y)
                            state.p2 = Offset(state.p2.x, localDocPos.y)
                        }
                        ShapeHandleId.CORNER_RADIUS -> {
                            val minDim = minOf(abs(state.p2.x - state.p1.x), abs(state.p2.y - state.p1.y))
                            val dist = hypot(localDocPos.x - state.p1.x, localDocPos.y - state.p1.y)
                            state.cornerRadius = dist.coerceIn(0f, minDim / 2f)
                        }
                    }
                }
                handle == ShapeHandleId.STAR_OUTER -> {
                    val center = (state.p1 + state.p2) / 2f
                    val r = hypot(docPos.x - center.x, docPos.y - center.y)
                    state.p1 = center - Offset(r, r)
                    state.p2 = center + Offset(r, r)
                    val angleRad = atan2(docPos.y - center.y, docPos.x - center.x)
                    state.rotationDegrees = Math.toDegrees(angleRad.toDouble()).toFloat() + 90f
                }
                handle == ShapeHandleId.STAR_INNER -> {
                    val center = (state.p1 + state.p2) / 2f
                    val outerR = hypot(state.p2.x - state.p1.x, state.p2.y - state.p1.y) / 2f
                    val curR = hypot(docPos.x - center.x, docPos.y - center.y)
                    if (outerR > 1f) state.starInnerRatio = (curR / outerR).coerceIn(0.1f, 0.9f)
                }
                handle >= ShapeHandleId.NODE_CP_OUT_BASE -> {
                    val idx = handle - ShapeHandleId.NODE_CP_OUT_BASE
                    if (idx in 0 until state.nodes.size) {
                        val old = state.nodes[idx]
                        state.nodes[idx] = ShapeNode(old.pos, old.cpIn, Point2D(docPos.x, docPos.y))
                    }
                }
                handle >= ShapeHandleId.NODE_CP_IN_BASE -> {
                    val idx = handle - ShapeHandleId.NODE_CP_IN_BASE
                    if (idx in 0 until state.nodes.size) {
                        val old = state.nodes[idx]
                        state.nodes[idx] = ShapeNode(old.pos, Point2D(docPos.x, docPos.y), old.cpOut)
                    }
                }
                handle >= ShapeHandleId.NODE_ANCHOR_BASE -> {
                    val idx = handle - ShapeHandleId.NODE_ANCHOR_BASE
                    if (idx in 0 until state.nodes.size) {
                        if (state.isCreatingNewNode && state.type == ShapeType.BEZIER) {
                            val center = state.dragStartDocPos
                            val deltaX = docPos.x - center.x
                            val deltaY = docPos.y - center.y
                            state.nodes[idx] = ShapeNode(
                                Point2D(center.x, center.y),
                                Point2D(center.x - deltaX, center.y - deltaY),
                                Point2D(center.x + deltaX, center.y + deltaY),
                            )
                        } else {
                            val old = state.nodes[idx]
                            val delta = docPos - Offset(old.pos.x, old.pos.y)
                            state.nodes[idx] = ShapeNode(
                                Point2D(docPos.x, docPos.y),
                                Point2D(old.cpIn.x + delta.x, old.cpIn.y + delta.y),
                                Point2D(old.cpOut.x + delta.x, old.cpOut.y + delta.y)
                            )
                        }
                    }
                }
            }
        }
    }

    private fun handleShapeUp(docPos: Offset) {
        val v = vm ?: return
        v.shapeState.activeHandle = ShapeHandleId.NONE
        v.shapeState.dragStartDocPos = Offset.Zero
        v.shapeState.isCreatingNewNode = false
        shapeCreatedOnCurrentTouch = false
    }

    private fun cancelPendingShapeGesture() {
        val v = vm ?: return
        val state = v.shapeState
        lastShapeTapTimeMs = 0L

        if (state.active) {
            if (state.isCreatingNewNode) {
                if (state.nodes.size <= 1) {
                    state.clear()
                } else {
                    state.nodes.removeAt(state.nodes.size - 1)
                    state.selectedNodeIndex = state.nodes.size - 1
                    state.activeHandle = ShapeHandleId.NONE
                    state.dragStartDocPos = Offset.Zero
                    state.isCreatingNewNode = false
                }
            } else if (shapeCreatedOnCurrentTouch) {
                state.clear()
            } else if (state.activeHandle != ShapeHandleId.NONE) {
                state.p1 = state.dragP1
                state.p2 = state.dragP2
                state.rotationDegrees = state.dragRotation
                state.cornerRadius = state.dragCornerRadius
                state.starInnerRatio = state.dragStarInnerRatio
                state.activeHandle = ShapeHandleId.NONE
                state.dragStartDocPos = Offset.Zero
            }
        }

        shapeCreatedOnCurrentTouch = false

        if (isPolyPointPendingOnTouch) {
            onPolyPopPoint?.invoke()
            isPolyPointPendingOnTouch = false
        }

        liveShapeStart?.value = null
        liveShapeEnd?.value = null
        if (lassoPoints.isNotEmpty()) {
            lassoPoints.clear()
            liveSelectionPath?.value = null
        }

        invalidate()
    }

    private fun hitTestTextHandle(
        cfg: TypographyConfig,
        docPos: Offset,
        density: Float,
        currentScale: Float,
    ): Int {
        val hitDist = (28f * density) / maxOf(0.01f, currentScale)
        val v = vm ?: return -1
        val paint = TypographyEngine.createTextPaint(cfg, v.brushOpacity)
        val targetW = cfg.boxWidth.toInt().coerceAtLeast(60)
        val layout = TypographyEngine.createLayout(cfg, paint, targetW)
        val w = layout.width.toFloat()
        val h = layout.height.toFloat()

        val left = cfg.posX
        val top = cfg.posY
        val right = left + w
        val bottom = top + h
        val cx = left + w / 2f
        val cy = top + h / 2f

        val localPt = if (abs(cfg.rotationDeg) > 0.01f) {
            ShapeGeometry.rotatePoint(Point2D(docPos.x, docPos.y), Point2D(cx, cy), -cfg.rotationDeg)
        } else Point2D(docPos.x, docPos.y)
        val localPos = Offset(localPt.x, localPt.y)

        // 1. 顶部旋转手柄
        val rotPos = Offset(cx, top - (24f * density) / maxOf(0.01f, currentScale))
        if (hypot(localPos.x - rotPos.x, localPos.y - rotPos.y) < hitDist) {
            return -20
        }

        // 2. 右下角尺寸缩放手柄
        if (hypot(localPos.x - right, localPos.y - bottom) < hitDist) {
            return -30
        }

        // 3. 文本框内部拖拽平移
        if (localPos.x in (left - hitDist)..(right + hitDist) && localPos.y in (top - hitDist)..(bottom + hitDist)) {
            return -10
        }

        return -1
    }

    private fun handleTextDown(docPos: Offset) {
        val v = vm ?: return
        val currentScale = maxOf(0.01f, canvasZoom * canvasFitScale)
        val hitDist = (28f * density) / currentScale
        val now = SystemClock.uptimeMillis()

        if (v.isTypographyEditing) {
            val hit = hitTestTextHandle(v.typographyConfig, docPos, density, currentScale)
            if (hit == -10 && now - lastTextTapTimeMs < 350L &&
                hypot(docPos.x - lastTextTapDocPos.x, docPos.y - lastTextTapDocPos.y) < hitDist
            ) {
                onTextRequested?.invoke(v.typographyConfig.posX, v.typographyConfig.posY)
                lastTextTapTimeMs = 0L
                return
            }
            lastTextTapTimeMs = now
            lastTextTapDocPos = docPos

            if (hit != -1) {
                activeTextHandle = hit
                textDragStartDocPos = docPos
                textDragStartCfg = v.typographyConfig
                v.typographySnapGuides = emptyList()
                return
            }

            // 点击外部：若当前文本有内容，自动提交
            if (v.typographyConfig.text.isNotBlank()) {
                v.commitTypographyToCanvas()
            }
        }

        val initFontSize = (v.brushSize * 2.0).toFloat().coerceIn(24f, 160f)
        v.typographyConfig = v.typographyConfig.copy(
            text = "",
            posX = docPos.x,
            posY = docPos.y,
            fontSize = initFontSize,
            boxWidth = 400f,
            rotationDeg = 0f,
            textColor = v.brushColor,
        )
        v.isTypographyEditing = true
        v.typographySnapGuides = emptyList()
        activeTextHandle = -1
        lastTextTapTimeMs = now
        lastTextTapDocPos = docPos
        onTextRequested?.invoke(docPos.x, docPos.y)
    }

    private fun handleTextMove(docPos: Offset) {
        val v = vm ?: return
        if (!v.isTypographyEditing || activeTextHandle == -1) return

        when (activeTextHandle) {
            -10 -> {
                val delta = docPos - textDragStartDocPos
                val rawLeft = textDragStartCfg.posX + delta.x
                val rawTop = textDragStartCfg.posY + delta.y

                if (textDragStartCfg.snapEnabled) {
                    val paint = TypographyEngine.createTextPaint(textDragStartCfg, v.brushOpacity)
                    val targetW = textDragStartCfg.boxWidth.toInt().coerceAtLeast(60)
                    val layout = TypographyEngine.createLayout(textDragStartCfg, paint, targetW)
                    val textW = layout.width.toFloat()
                    val textH = layout.height.toFloat()

                    val currentScale = maxOf(0.01f, canvasZoom * canvasFitScale)
                    val snapThreshold = (16f * density) / currentScale
                    val margin = minOf(v.docWidth, v.docHeight) * 0.05f
                    val snapResult = com.reverie.paint.model.TypographySnapHelper.calculateSnap(
                        boxLeft = rawLeft,
                        boxTop = rawTop,
                        boxWidth = textW,
                        boxHeight = textH,
                        canvasWidth = v.docWidth,
                        canvasHeight = v.docHeight,
                        threshold = snapThreshold,
                        safeMargin = margin,
                    )
                    v.typographyConfig = textDragStartCfg.copy(
                        posX = snapResult.snappedLeft,
                        posY = snapResult.snappedTop,
                    )
                    v.typographySnapGuides = snapResult.guides
                } else {
                    v.typographyConfig = textDragStartCfg.copy(
                        posX = rawLeft,
                        posY = rawTop,
                    )
                    v.typographySnapGuides = emptyList()
                }
            }
            -20 -> {
                val paint = TypographyEngine.createTextPaint(textDragStartCfg, v.brushOpacity)
                val layout = TypographyEngine.createLayout(textDragStartCfg, paint, textDragStartCfg.boxWidth.toInt())
                val cx = textDragStartCfg.posX + layout.width / 2f
                val cy = textDragStartCfg.posY + layout.height / 2f
                val angleRad = atan2(docPos.y - cy, docPos.x - cx)
                val deg = Math.toDegrees(angleRad.toDouble()).toFloat() + 90f
                v.typographyConfig = textDragStartCfg.copy(rotationDeg = deg)
            }
            -30 -> {
                val dx = docPos.x - textDragStartCfg.posX
                val newW = maxOf(80f, dx)
                val ratio = (newW / maxOf(80f, textDragStartCfg.boxWidth)).coerceIn(0.4f, 4.0f)
                val newSize = (textDragStartCfg.fontSize * ratio).coerceIn(12f, 240f)
                v.typographyConfig = textDragStartCfg.copy(boxWidth = newW, fontSize = newSize)
            }
        }
    }

    private fun handleTextUp(docPos: Offset) {
        activeTextHandle = -1
        textDragStartDocPos = Offset.Zero
        vm?.typographySnapGuides = emptyList()
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        if (event != null && getOrCreateStylusDriver()?.onStylusKeyEvent(event) == true) {
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        if (event != null && getOrCreateStylusDriver()?.onStylusKeyEvent(event) == true) {
            return true
        }
        return super.onKeyUp(keyCode, event)
    }
}
