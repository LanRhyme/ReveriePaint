/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.canvas

import com.reverie.paint.core.FrontBufferFeatureFlags
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Build
import android.os.SystemClock
import android.view.MotionEvent
import android.view.PointerIcon
import android.view.View
import android.view.WindowManager
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
import com.reverie.paint.perf.FbGeomLog
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
internal const val MAX_VISIBLE_GRID_LINES = 6000
internal const val DYNAMIC_GUIDE_ABORT_FRAME = -1

// ---- 分格工具手势常量 ----
internal const val PANEL_DRAG_NONE = 0        // 无手势
internal const val PANEL_DRAG_HANDLE = 1      // 选中格角手柄拖形
internal const val PANEL_DRAG_EDGE = 2        // 共享边间隔拖动 (两格联动)
internal const val PANEL_DRAG_BODY = 3        // 格体拖移 (多选时整组)
internal const val PANEL_DRAG_BODY_PENDING = 4 // 未选中态压在格上: UP 无位移=点选, 位移=拖移
internal const val PANEL_DRAG_CREATE = 6      // 建格模式空白/缝隙拖拽: 贯穿切或建格
internal const val PANEL_DRAG_MARQUEE = 7     // 长按后拖动框选多选
internal const val PANEL_DRAG_CONSUMED = 8    // 双击进格已消费, 本手势不再处理
internal const val PANEL_DRAG_DIVIDE = 9      // 切分模式拖刀 (画布任意处起笔)
internal const val PANEL_DRAG_DIVIDE_PENDING = 10 // 切分模式压在格体: 等长按; slop 后转 DIVIDE
internal const val PANEL_DRAG_EDGE_MIDPOINT = 11 // 选中格边中点手柄拖动: 平行推拉整条边

/** 长按弹菜单延迟 (与系统长按观感一致) */
internal const val PANEL_LONG_PRESS_MS = 480L
/** 双击进格: 两次 DOWN 同一格体的最大间隔 */
internal const val PANEL_TAP_MS = 300L
/** 轻点判定 (屏幕 dp, 与 FILL/MAGICWAND 的 tap 阈值同族) */
internal const val PANEL_TAP_SLOP_DP = 12f
/** 建格最小拖划 (dp 屏幕距离): 小于此位移按"点空白"处理不建格 (tap slop 只管点击判定)。 */
internal const val PANEL_CREATE_MIN_DP = 24f
/** 共享边拖动的最小边长 (面积下限 8²=64px², 与引擎侧 MIN_PANEL_AREA 对齐) */
internal const val PANEL_MIN_EDGE = 8f
internal const val PANEL_MIN_AREA = 64f
/** 共享边判定: 角点重合容差 (doc px) */
internal const val PANEL_SHARED_EDGE_TOL = 1.5f
/** 吸附磁吸阈值 (dp 屏幕距离, 换算 doc): gutter 对齐与边框标尺共用 */
internal const val PANEL_SNAP_TOLERANCE_DP = 8f
/** 平行吸附角度容差 (弧度): 与目标方向差 < 2.5° 时吸附到精确平行 (角点拖形/共享边拖动共用) */
internal val PANEL_PARALLEL_SNAP_ANGLE_RAD = Math.toRadians(2.5).toFloat()
/** 切分线两端延伸兜底 (dp 屏幕距离, 换算 doc): 差一点没划到也能切 (见 segmentCutsQuad) */
internal const val PANEL_DIVIDE_EXTEND_DP = 12f

/**
 * 镜像缓冲的初始容量。实际分支数由当前对称配置决定 (旋转对称最多 35 支, 36 轴万花筒
 * 最多 71 支), 由 [ensureMirrorBranches] 按需扩容; 实时镜像另受
 * [PaintViewModel.MIRROR_BRANCH_COUNT] 限制, 超出的整笔改走抬笔重放。
 */
private const val INITIAL_MIRROR_BRANCH_CAPACITY = 8

internal const val SYMMETRY_SAMPLE_STRIDE = PaintViewModel.STROKE_SAMPLE_STRIDE_WITH_TIME

/** 等轴测辅助导引方向 (30°、150°、90° 垂直方向) */
internal val ISO_DIRS = arrayOf(
    Offset(0.8660254f, 0.5f),
    Offset(0.8660254f, -0.5f),
    Offset(0f, 1f),
)

/**
 * C3-2 · 场通路的文档像素预算上限(源纹理 + 场纹理各留一份, 4M px 文档 ≈ 16MB + 8MB)。
 *
 * 注: 真正生效的预算由 [fieldPathBudgetPx] 动态给出 —— 它再叠加"堆上限 / 每像素 32B"的
 * 安全约束(见 [MemoryBudget])。这里的常量只是历史硬上限, 不是最终值。
 */
internal const val FIELD_PATH_MAX_PX = 4L * 1024L * 1024L

/** C3-2 · 低内存设备的场通路预算(压到 1/4; 超预算直接走经典路径, 宁可慢也不逼近 OOM)。 */
internal const val FIELD_PATH_MAX_PX_LOW_RAM = 1L * 1024L * 1024L

/**
 * 经典路径"单帧多补点一次提交"的批缓冲步长: `(fx, fy, tx, ty, strength, mode)` ——
 * 与 JNI `liquifyDabs` 同序(见 [`LiquifyPath.packDab`], 布局由单测守门)。
 */
internal const val LIQUIFY_BATCH_STRIDE = LiquifyPath.DAB_STRIDE

/**
 * 引擎队列积压背压阈值: `pendingCoreOps` 超过它时, 本帧不再推进新的液化补点。
 *
 * 高压拖动时引擎每帧要跑多个补点 + 一次渲染; 若输入侧继续无条件推进, 任务队列会越积越长,
 * 用户观感就是"越拖越卡、抬笔后画面还在继续变形"(无响应)。宁可追赶慢一点, 也不让队列雪崩。
 */
internal const val LIQUIFY_ENGINE_BACKLOG_LIMIT = 8

/** B4 · 静止降频: 交互结束后多久把帧率请求降回 [IDLE_FRAME_RATE_HZ]。 */
internal const val IDLE_DOWNCLOCK_MS = 1500L

/** B4 · 静止时请求的帧率(Hz): 不画的时候没必要把屏幕钉在 144Hz 上。 */
internal const val IDLE_FRAME_RATE_HZ = 60f

/** 50% 面板避让：进入半径 = 32dp + 笔刷屏幕半径。 */
private const val EVADE_ENTER_DP = 32f

/** 50% 面板避让：离开半径 = 40dp + 笔刷屏幕半径（32dp + 8dp 双向滞后）。 */
private const val EVADE_EXIT_DP = 40f

/** 抬笔后保持 50% 的延续窗口，消除高频排线/点画时的 50% <-> 100% 闪烁。 */
private const val POST_UP_CONTINUATION_MS = 80L

/** Phase 5 · C3-2: 本地补点列表的步长(px, py, nx, ny, mode, strength, size)。 */
internal const val FIELD_DAB_STRIDE = 7

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

    /** 前缓冲真墨呈现 (续 #81), 渲染线程通过 [PaintViewModel.realInkSink] 回调它。 */
    internal val realInk = RealInkController(this)

    var vm: PaintViewModel? = null
        set(value) {
            val old = field
            if (old === value) return
            if (old != null && old.realInkSink === realInk) old.realInkSink = null
            old?.panelOcclusion?.onChanged = null
            field = value
            value?.realInkSink = realInk
            value?.panelOcclusion?.onChanged = { scheduleSnapshotRebuild() }
            // VM 换人后强制重建, 不沿用旧 VM 的槽位版本。
            lastUiClipVersion = -1
        }
    var tool: Tool = Tool.BRUSH
        set(value) {
            if (field != value) {
                field = value
                clearPredictionState(triggerInvalidate = true)
                if (strokeStarted) {
                    vm?.let { cancelActiveStroke(it) }
                    strokeStarted = false
                }
                if (value != Tool.PICKER && !isLongPressPickerActive) {
                    pickerActive?.value = false
                }
                if (value != Tool.PANEL) {
                    // 离开分格工具: 收长按菜单并作废任何进行中的分格手势, 切分模式复位回建格
                    panelInteraction?.dismissMenu()
                    cancelPanelGesture()
                    vm?.panelDivideMode = false
                }
                // 12 条强制复位路径之一：切换工具。
                vm?.let { clearAllEvasionState(it) }
            }
        }
    var tfState: TransformState? = null

    /** 分格工具手势 ↔ 覆盖层共享预览状态 (CanvasView remember 后注入; null = 未接分格 UI)。 */
    internal var panelInteraction: PanelInteractionState? = null
    var docBitmap: Bitmap? = null

    internal var quickShapeCandidate: QuickShapeResult? = null
    internal val quickShapeHold = Runnable {
        val v = vm
        val capture = v?.quickShapeCapture
        val holdDelayMs = v?.quickShapeHoldDelayMs?.toLong()?.coerceIn(150L, 1200L) ?: 450L
        if (v != null && capture != null && strokeStarted && v.quickShapeEnabled &&
            effTool() == Tool.BRUSH && capture.travelled >= 48f * density &&
            SystemClock.uptimeMillis() - capture.lastMovementMs >= holdDelayMs) {
            quickShapeCandidate = capture.recognize(v.quickShapeArcEnabled, v.quickShapeRelaxedEnabled,
                v.quickShapeQuadrilateralEnabled, v.quickShapeCurveEnabled)
            if (quickShapeCandidate != null) {
                performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                v.showActionToast(R.string.quick_shape_ready, R.drawable.ic_line)
            }
        }
    }

    internal var cachedDriver: com.reverie.paint.core.stylus.StylusDriver? = null

    // S Pen 悬停期间侧键状态追踪: 仅在按钮按下/释放边沿把 hover 事件喂给驱动层,
    // 避免每次悬停移动都走按钮状态机 (热路径零分配: 只做位比较, 无对象创建)。
    private var lastHoverButtonState = 0

    // 纸张摩擦音效的速度追踪 (文档坐标); lastSoundTimeMs == 0L 表示笔画尚无历史点
    internal var lastSoundDocPos = Offset.Zero
    internal var lastSoundTimeMs = 0L

    // 侧键按住=临时橡皮 (Samsung Notes 语义): 仅在笔接触的事件流中更新, 抬笔后由下一次落笔重判
    internal var tempEraseActive = false

    // 物理橡皮擦末端 (TOOL_TYPE_ERASER, 施德楼/Surface/Wacom EMR 笔尾): 倒转笔身时直接生效为橡皮
    internal var physicalEraserActive = false

    /** 绘画路径的生效工具: 侧键按住或物理橡皮擦末端时强制橡皮, 其余时刻跟随 UI 工具。 */
    internal fun effTool(): Tool = if (tempEraseActive || physicalEraserActive) Tool.ERASER else tool

    internal fun getOrCreateStylusDriver(): com.reverie.paint.core.stylus.StylusDriver? {
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
    internal var isPolyPointPendingOnTouch = false
    private var shapeCreatedOnCurrentTouch = false
    private var gradientCreatedOnCurrentTouch = false
    var onCropRect: ((Rect?) -> Unit)? = null

    var liveShapeStart: MutableState<Offset?>? = null
    var liveShapeEnd: MutableState<Offset?>? = null
    var livePressure: MutableState<Float>? = null
    var measureStart: MutableState<Offset?>? = null
    var measureEnd: MutableState<Offset?>? = null
    /** 测量工具当前激活的手柄: 0 表示起点, 1 表示终点, -1 表示新建或未命中手柄 */
    internal var activeMeasureHandle: Int = -1
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
    @Volatile
    var overlayPanelsOpen: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                // 12 条强制复位路径之一：打开任一模态/半模态覆盖面板。
                if (value) vm?.let { clearAllEvasionState(it) }
            }
        }
    var liquifyMode: Int = 0
    @Volatile
    var frontBufferOverlay: FrontBufferPreviewOverlay? = null
        set(value) {
            if (field !== value) {
                abortAndClearRealInk()
                vm?.let { clearAllEvasionState(it) }
            }
            field = value
            value?.realInk = realInk
        }

    // 滤镜实时调节与手势驱动
    @Volatile
    var filterSessionActive: Boolean = false
    var onFilterSlideDelta: ((Float) -> Unit)? = null
    var onFilterHoldingCompare: ((Boolean) -> Unit)? = null
    internal var isFilterComparing = false
    internal var filterTouchStartX = 0f
    internal var filterTouchStartY = 0f
    internal var isFilterDragging = false
    internal val filterLongPressRunnable = Runnable {
        if (filterSessionActive && !isFilterDragging && !isTransformActive && fingerCount <= 1) {
            isFilterComparing = true
            onFilterHoldingCompare?.invoke(true)
        }
    }

    internal val density = context.resources.displayMetrics.density

    // 3D 参考图层交互状态
    internal var touch3DStartX = 0f
    internal var touch3DStartY = 0f
    internal var touch3DIsPinch = false
    internal var touch3DPinchDist = 0f
    internal var touch3DMidX = 0f
    internal var touch3DMidY = 0f
    internal var touch3DStartState: com.reverie.paint.model.Model3DState? = null
    /** 骨骼模式下上一次点中骨骼的时间戳 (连点同一根骨头 = 归零该骨骼) */
    internal var lastBoneTapTime = 0L
    internal val canvas3DDrawer = com.reverie.paint.core.gl3d.Canvas3DDrawer()

    // 触控交互锁 (手指接触期间，阻止外部 Compose 状态回冲覆盖)
    var isInteracting = false

    /** 渲染线程会读它, 判断这一笔还能不能留在前缓冲。 */
    @Volatile var isTransformActive = false
    var isPinchMotion = false

    /**
     * 双指专用"旋转已成运动"标记。
     *
     * 只用于抑制双指轻点撤销 (及双指连续撤销), **不参与三指重做的门控** —— 三指重做继续
     * 只看 [isPinchMotion] (即改动前的三项旧判据), 因此三指路径逐点等价; 同时"双指落指
     * 阶段的抖动"也不会再把随后落下的第三指轻点重做挡掉。
     */
    internal var isTwoFingerRotation = false

    // 本地硬件光标状态 (0 Compose 开销)
    /** 局部失效时并入标尺块(debug 标尺专用; 正式版 [PerfHud.fillHudBounds] 恒 false)。 */
    internal val hudBoundsScratch = android.graphics.Rect()

    /** B4 · 静止降频: 当前是否已请求"面板最高帧率", 以及静止计时。 */
    internal var frameRateHigh = true
    internal val idleDownclockRunnable = Runnable { downclockWhenIdle() }

    // 热路径零分配 (AGENTS.md §4.4): 同 predictedScreenX/Y, 可空 Offset 拆双 Float + NaN。
    // 悬停/触控每事件赋值, 原 Offset? 写法每次装箱。
    internal var localCursorX: Float = Float.NaN
    internal var localCursorY: Float = Float.NaN
    internal val hasLocalCursorPos: Boolean
        get() = !localCursorX.isNaN() && !localCursorY.isNaN()

    internal fun setLocalCursorPos(x: Float, y: Float) {
        localCursorX = x
        localCursorY = y
    }

    internal fun clearLocalCursorPos() {
        localCursorX = Float.NaN
        localCursorY = Float.NaN
    }
    internal var localIsHovering = false
    internal var localIsTouching = false
        set(value) {
            field = value
            isCursorTouching?.value = value
        }

    val lastKnownCursorScreenPos: androidx.compose.ui.geometry.Offset?
        get() = if (hasLocalCursorPos && (localIsHovering || localIsTouching)) {
            androidx.compose.ui.geometry.Offset(localCursorX, localCursorY)
        } else null

    /** 运笔中 (孔洞掩码构建据此推迟到抬笔后)。 */
    internal val strokeInProgress: Boolean get() = localIsTouching

    /** H-1: 前缓冲孔洞掩码 (几何变化后在后台构建, 落笔只取现成的)。 */
    internal val holeMasks by lazy(LazyThreadSafetyMode.NONE) { FrontBufferHoleMaskBuilder(this) }

    // compose ([FrontBufferHolePolicy.MODE_COMPOSE]): 前缓冲重放的静态覆盖层 (像素网格 / 绘图辅助线),
    // 与窗口同一组绘制函数录成 Picture。只在覆盖层状态变了时重录 (空闲帧里, 不在落笔路径上)。
    private val composeDecorRecorder by lazy(LazyThreadSafetyMode.NONE) { FrontBufferDecorRecorder(this) }
    private var composeDecor: FrontBufferDecor? = null
    internal var localPressure = 1f
    internal var localCursorAngle = 0f
    private var lastCursorMoveDocX = Float.NaN
    private var lastCursorMoveDocY = Float.NaN

    internal fun updateCursorAngleFromDocMove(docX: Float, docY: Float, isDown: Boolean) {
        if (isDown) {
            lastCursorMoveDocX = docX
            lastCursorMoveDocY = docY
        } else if (!lastCursorMoveDocX.isNaN() && !lastCursorMoveDocY.isNaN()) {
            val dX = docX - lastCursorMoveDocX
            val dY = docY - lastCursorMoveDocY
            if (hypot(dX, dY) > 1.5f) {
                localCursorAngle = Math.toDegrees(kotlin.math.atan2(dY.toDouble(), dX.toDouble())).toFloat()
                lastCursorMoveDocX = docX
                lastCursorMoveDocY = docY
            }
        } else {
            lastCursorMoveDocX = docX
            lastCursorMoveDocY = docY
        }
    }

    // 硬件加速直出渲染 Paint
    var checkerboardPaint: Paint? = null
    internal val pixelGridPaint = Paint().apply {
        style = Paint.Style.STROKE
    }
    internal val directBitmapPaint = Paint().apply {
        isFilterBitmap = true
        isDither = true
    }

    // 光标绘制 Paint (超细精细发丝线条)
    internal val cursorPaintBlack = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.6f * density
        color = android.graphics.Color.argb(130, 0, 0, 0)
    }
    internal val cursorPaintWhite = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 0.9f * density
        color = android.graphics.Color.argb(240, 255, 255, 255)
    }
    internal val crosshairPaintBlack = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * density
        color = android.graphics.Color.argb(120, 0, 0, 0)
    }
    internal val crosshairPaintWhite = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 0.7f * density
        color = android.graphics.Color.argb(255, 255, 255, 255)
    }
    // 笔刷轮廓复用 Path 与专用 Paint (纯黑单层描边，不带白色)
    internal val brushOutlinePath = android.graphics.Path()
    internal val brushOutlinePaintBlack = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 0.4f * density
        color = android.graphics.Color.argb(190, 0, 0, 0)
    }

    // 动态辅助吸附导引线: 画笔在 DynamicGuidePainter 里 (前缓冲 GL 线程另持一个实例)
    internal val dynamicGuidePainter = DynamicGuidePainter(density)
    internal val dynamicGuideSeg = FloatArray(DynamicGuidePainter.SEG_SIZE)

    var drawingGuidePanelOpen = false
    internal var currentStrokeDocPos = Offset.Zero
    internal var isSymmetryUndoMacroOpen = false

    internal fun safeBeginSymmetryUndoMacro() {
        if (!isSymmetryUndoMacroOpen) {
            vm?.runCore(render = false) {
                ReverieCoreBridge.beginUndoMacro("Symmetry Stroke")
            }
            isSymmetryUndoMacroOpen = true
        }
    }

    internal fun safeEndSymmetryUndoMacro() {
        if (isSymmetryUndoMacroOpen) {
            vm?.runCore(render = false) {
                ReverieCoreBridge.endUndoMacro()
            }
            isSymmetryUndoMacroOpen = false
        }
    }

    // 绘制状态 (手写笔专属)
    internal var strokeTool: Tool? = null
    internal var strokeStarted = false
        set(value) {
            if (value && !field) strokeTool = effTool()
            field = value
            if (!value) strokeTool = null
            // 绘画态优先协议的唯一真源：面板据此决定是否被动避让。
            vm?.isStrokeSessionActive = value
        }
    /**
     * s13-C5: 本笔被笔画中的撤销/重做中止 ([abortActiveStrokeForHistory])。抬笔前的后续移动
     * 不再迟到起笔 (否则撤销后笔尖继续移动会画出一条新笔画); 落笔 / 抬笔时复位。
     */
    internal var strokeAbortedUntilUp = false

    /**
     * s13-C5: 顶栏撤销/重做在笔画进行中被点下 (UI 线程)。按"取消当前笔画"收掉这一笔 (主笔与
     * 镜像同时回滚, 关对称撤销宏), 与双指撤销手势在笔画中的处理一致; 之后调用方照常执行撤销/重做。
     * 返回是否真的中止了一笔。液化等非画笔工具不处理 (保持原行为)。
     */
    internal fun abortActiveStrokeForHistory(): Boolean {
        if (!strokeStarted) return false
        when (strokeTool) {
            Tool.BRUSH, Tool.ERASER, Tool.SMUDGE -> Unit
            else -> return false
        }
        val v = vm ?: return false
        removeCallbacks(quickShapeHold)
        isPendingLongPress = false
        longPressToken++
        cachedDriver?.feedbackManager?.setWritingHapticsEnabled(false)
        cachedDriver?.feedbackManager?.stopStrokeSound()
        v.quickShapeCapture = null
        quickShapeCandidate = null
        v.touchCancel()
        abortAndClearRealInk()
        clearAllEvasionState(v)
        strokeStarted = false
        strokeAbortedUntilUp = true
        safeEndSymmetryUndoMacro()
        resetMirrorBranches()
        assistLockedRay = null
        resetFisheyeAssist()
        clearPredictionState(triggerInvalidate = true)
        currentStrokeDocPos = Offset.Zero
        firstDocPos = Offset.Zero
        shapeEndDocPos = Offset.Zero
        return true
    }

    internal fun cancelActiveStroke(v: PaintViewModel) {
        firstDocPos = Offset.Zero
        shapeEndDocPos = Offset.Zero
        when (strokeTool) {
            Tool.BRUSH, Tool.ERASER, Tool.SMUDGE -> {
                cachedDriver?.feedbackManager?.setWritingHapticsEnabled(false)
                cachedDriver?.feedbackManager?.stopStrokeSound()
                v.quickShapeCapture = null
                quickShapeCandidate = null
                abortAndClearRealInk()
                v.touchCancel()
                resetMirrorBranches()
                safeEndSymmetryUndoMacro()
                clearPredictionState(triggerInvalidate = true)
                strokeStarted = false
            }
            Tool.LIQUIFY -> {
                removeCallbacks(liquifyFlushRunnable)
                liquifyFlushPosted = false
                liquifySession.reset()
                liquifyFieldGesture = false
                liquifyBasePolicy.reset()
                liquifyDrawRect = null
                v.liquifyCancel()
                strokeStarted = false
            }
            else -> {
                strokeStarted = false
            }
        }
    }
    internal var firstDocPos = Offset.Zero
    internal var shapeEndDocPos = Offset.Zero
    internal var previousSinglePos = Offset.Zero
    internal var priorStrokeScreenPos = Offset.Zero
    // 本帧已用于避让评估的屏幕包络（含历史/辅助点），供预测点并入后二次评估。
    internal var evasionEnvL = 0f
    internal var evasionEnvT = 0f
    internal var evasionEnvR = 0f
    internal var evasionEnvB = 0f
    internal var hasEvasionEnv = false
    internal val lassoPoints = mutableListOf<Offset>()
    internal var lastLassoPreviewNs = 0L
    // Phase 5 · C3-2 (docs/LIQUIFY-C3-FIELD-PLAN.md §3): 场通路的"拖动期零引擎解算"状态。
    // 拖动期不调 liquify()、不收 rebase、不物化 —— 位移只在 GPU 场里累加; 抬笔回读一次落盘。
    /** 本段手势是否走"GPU 场一次性落盘"。 */
    internal var liquifyFieldGesture = false

    /** 本段手势的补点(7 float/补点, 见 [FIELD_DAB_STRIDE]); 抬笔回读失败时用它重放给引擎。 */
    internal var liquifyDabBuf = FloatArray(0)
    internal var liquifyDabCount = 0

    /**
     * 经典路径的**批量提交**缓冲(复用, 每补点 [LIQUIFY_BATCH_STRIDE] 个 float)。
     *
     * 以前每个补点一次 `v.liquify()` = 一次 `runCore` post + 一次 `scheduleRender`; 大笔刷
     * 快拖时一帧 8~16 个补点就有 8~16 个任务压进引擎队列。现在一帧只 post 一次, 由引擎线程
     * 内循环调用 JNI(顺序与语义逐点不变), 队列长度与渲染调度次数都降到 1/8~1/16。
     */
    internal var liquifyBatchBuf = FloatArray(LIQUIFY_BATCH_STRIDE * 64)

    /** 受影响文档矩形(各补点影响圆的并集): 覆盖层绘制范围 + 抬笔回读范围都用它。 */
    internal var lqAffectedL = Float.MAX_VALUE
    internal var lqAffectedT = Float.MAX_VALUE
    internal var lqAffectedR = -Float.MAX_VALUE
    internal var lqAffectedB = -Float.MAX_VALUE

    /**
     * 相位 8: 上一次推给覆盖层的绘制矩形(整数, 文档坐标), null = 还没推过。
     *
     * 与推给引擎的"预览基座"矩形是**同一个值**(见 [LiquifyPath.previewRect]) —— 两者逐像素
     * 一致才不会有"被挖掉却没人补"(露背景线框)或"没被挖却在叠加"(深色描边)的差值环。
     */
    internal var liquifyDrawRect: IntArray? = null
    internal var liquifyWholeLayerRect: IntArray? = null

    /** 基座推进策略: 只跟到覆盖层"已上屏"的那一帧, 永远不领先(见 [LiquifyPreviewBasePolicy])。 */
    internal val liquifyBasePolicy = LiquifyPreviewBasePolicy()

    /** 读取"覆盖层已上屏矩形"的复用缓冲(热路径零分配)。 */
    internal val liquifyCommittedScratch = IntArray(4)

    // Phase 3A/3B: 液化交互态会话(latest-state-wins 状态机 + backlog 计数), 取代原先散落的
    // liquifyPrevPos / liquifyPendingTo / liquifyInputSinceFlush / liquifyMaxDabsPerFlush 字段。
    internal val liquifySession = LiquifyInteractionSession()
    internal var liquifyFlushPosted = false
    internal val liquifyFlushRunnable = Runnable { flushLiquifyPending() }
    internal var liquifyHoldTimeMs = 0L
    internal val liquifyHoldRunnable = object : Runnable {
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
    internal val isLiquifyGestureActive: Boolean
        get() = strokeStarted && effTool() == Tool.LIQUIFY

    /** Phase 6: 最近一次液化输入事件的时间戳(event.eventTime, uptime 基准), 用于延迟读数。 */
    internal var lastLiquifyEventTimeMs = 0L

    /** Phase 6: 本段液化手势是否有笔压输入(手写笔/橡皮端) —— 手指路径不参与压力调制。 */
    internal var liquifyPressureActive = false
    // Liquify V2 · Phase 2 (docs/LIQUIFY-V2-PLAN.md §4): 覆盖层局部失效所需的"光标环本帧位置"
    // (屏幕坐标)。环由本类 onDraw 画在同一张画布上, 因此局部重绘必须把环的前后位置一并失效,
    // 否则环的旧位置会留下残影。全部只在 UI 线程读写。
    internal var lqRingValid = false
    internal var lqRingCx = 0f
    internal var lqRingCy = 0f
    internal var lqRingR = 0f
    // docToScreen 的输出缓冲(必须是 FloatArray; 不能复用整型的 boundsScratch)
    internal val lqPointScratch = FloatArray(2)
    private val lqInvalidateRunnable = Runnable { scheduleLiquifyInvalidate() }
    internal var smoothedPressure = 0.8f

    // 手写笔传感器状态 (倾斜角与朝向)
    internal var touchTiltX: Double = 0.0
    internal var touchTiltY: Double = 0.0
    internal var touchRotation: Double = 0.0
    /** AXIS_RZ (笔身自转) 能力缓存: 设备 id + 是否上报; null = 未探测 */
    private var barrelRotationSupported: Boolean? = null
    private var barrelRotationDeviceId: Int = -1

    internal fun updateStylusSensors(
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
        // 笔身旋转先于倾角解析: 它与倾角无关, 不能因为倾角缺失就一起清零 (见 resolveTouchRotation)
        touchRotation = resolveTouchRotation(event, pointerIndex, historyPos)

        val tiltRad = if (historyPos >= 0) {
            event.getHistoricalAxisValue(MotionEvent.AXIS_TILT, pointerIndex, historyPos)
        } else {
            event.getAxisValue(MotionEvent.AXIS_TILT, pointerIndex)
        }
        if (!tiltRad.isFinite() || tiltRad < 0f) {
            touchTiltX = 0.0
            touchTiltY = 0.0
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
            return
        }
        val canvasRotRad = Math.toRadians(canvasRotation.toDouble())
        val docOrientationRad = orientationRad.toDouble() - canvasRotRad
        val tiltDeg = (tiltRad.toDouble() * (180.0 / Math.PI)).coerceIn(0.0, 60.0)
        touchTiltX = (Math.sin(docOrientationRad) * tiltDeg).coerceIn(-60.0, 60.0)
        touchTiltY = (-Math.cos(docOrientationRad) * tiltDeg).coerceIn(-60.0, 60.0)
    }

    /**
     * 笔身旋转 (Barrel Rotation) 解析 —— Krita 的 Rotation 传感器输入。
     *
     * 来源是通用旋转轴 AXIS_RZ(弧度), 与倾角方位角 AXIS_ORIENTATION 无关: 后者是笔尖指向的
     * 方位角, 拿它当自转会让带朝向动态的笔刷在画弧线/折线时 dab 剧烈扭曲撕裂 (历史结论, 勿改回)。
     * 换算口径与 Krita/Qt 官方修复一致, 见 [BarrelRotationPolicy]。
     *
     * 设备未上报 AXIS_RZ 时必须保持 0: 该轴缺省读数是 0 弧度, 换算后是 180°,
     * 会把 Rotation 动态顶到曲线末端 —— 无自转硬件的用户会突然看到笔刷变形。
     */
    private fun resolveTouchRotation(event: MotionEvent, pointerIndex: Int, historyPos: Int): Double {
        if (!deviceReportsBarrelRotation(event)) return 0.0
        val rz = if (historyPos >= 0) {
            event.getHistoricalAxisValue(MotionEvent.AXIS_RZ, pointerIndex, historyPos)
        } else {
            event.getAxisValue(MotionEvent.AXIS_RZ, pointerIndex)
        }
        return BarrelRotationPolicy.fromRadians(rz)
    }

    /** 已打印过能力清单的设备 id (每台设备一次, 避免热路径刷屏) */
    private var loggedStylusDeviceId: Int = Int.MIN_VALUE

    /**
     * 手写笔输入能力清单 —— 每台设备只打一条, 进 logcat 后会被「全景系统运行诊断」抓取。
     * 用来回答「为什么有些用户的倾斜/笔身旋转用不了」: 直接看该设备的 AXIS_TILT /
     * AXIS_ORIENTATION / AXIS_RZ 有没有实际量程, 而不是靠猜笔的型号。
     */
    internal fun logStylusAxesOnce(event: MotionEvent, pointerIndex: Int) {
        if (loggedStylusDeviceId == event.deviceId) return
        val device = event.device ?: return
        loggedStylusDeviceId = event.deviceId
        fun range(axis: Int): String = try {
            val r = device.getMotionRange(axis)
            if (r == null) "none" else "${r.min}..${r.max}"
        } catch (_: Exception) {
            "err"
        }
        val toolType = when (event.getToolType(pointerIndex)) {
            MotionEvent.TOOL_TYPE_STYLUS -> "stylus"
            MotionEvent.TOOL_TYPE_ERASER -> "eraser"
            MotionEvent.TOOL_TYPE_MOUSE -> "mouse"
            else -> "other(${event.getToolType(pointerIndex)})"
        }
        android.util.Log.i(
            "ReverieStylus",
            "stylus-axes device=${device.name} id=${event.deviceId} tool=$toolType " +
                "tilt=${range(MotionEvent.AXIS_TILT)} orientation=${range(MotionEvent.AXIS_ORIENTATION)} " +
                "rz=${range(MotionEvent.AXIS_RZ)} pressure=${range(MotionEvent.AXIS_PRESSURE)}",
        )
    }

    /** AXIS_RZ 是否有实际量程 (按设备缓存一次; getDevice() 为空时按不支持处理) */
    private fun deviceReportsBarrelRotation(event: MotionEvent): Boolean {
        val deviceId = event.deviceId
        val cached = barrelRotationSupported
        if (cached != null && barrelRotationDeviceId == deviceId) return cached
        val supported = try {
            event.device?.getMotionRange(MotionEvent.AXIS_RZ) != null
        } catch (_: Exception) {
            false
        }
        barrelRotationSupported = supported
        barrelRotationDeviceId = deviceId
        return supported
    }

    // ---- 笔画有效倾角: 硬件倾角直通 (手指/零倾角笔为 0, 与 Krita 无倾角输入一致) ----
    // touchTiltX/Y 来自 updateStylusSensors; strokeTiltX/Y 是笔画采样真正下发的倾角。
    // 单位: 度, 幅值 = 自垂直倾角 (AXIS_TILT 口径), 范围 [-60,60]。
    internal var strokeTiltX: Double = 0.0
    internal var strokeTiltY: Double = 0.0

    /** 最近一次真实输入压感 (原始, 未过校准): 抬笔收尾补点的"真实末端压感"来源 */
    internal var lastStrokeRawPressure: Float = Float.NaN

    /** 落笔: 倾角直通硬件值 (docX/docY 不再使用, 保留签名以免改动全部调用点) */
    @Suppress("UNUSED_PARAMETER")
    internal fun beginStrokeTilt(hwTiltX: Double, hwTiltY: Double, docX: Float, docY: Float) {
        strokeTiltX = StrokeTiltPolicy.component(hwTiltX)
        strokeTiltY = StrokeTiltPolicy.component(hwTiltY)
    }

    /** 每个采样点: 倾角直通硬件值; 必须在消费该点坐标的 touchMove 之前调用 */
    @Suppress("UNUSED_PARAMETER")
    internal fun resolveStrokeTilt(hwTiltX: Double, hwTiltY: Double, docX: Float, docY: Float) {
        strokeTiltX = StrokeTiltPolicy.component(hwTiltX)
        strokeTiltY = StrokeTiltPolicy.component(hwTiltY)
    }

    // 文本交互状态
    private var activeTextHandle: Int = -1
    private var textDragStartDocPos: Offset = Offset.Zero
    private var textDragStartCfg: TypographyConfig = TypographyConfig()
    private var lastTextTapTimeMs: Long = 0L
    private var lastTextTapDocPos: Offset = Offset.Zero
    private var textTouchDownDocPos: Offset = Offset.Zero
    private var textSelectedBeforeDown: Boolean = false
    private var textDraggedDuringGesture: Boolean = false

    // 形状交互状态
    private var lastShapeTapTimeMs: Long = 0L
    private var lastShapeTapDocPos: Offset = Offset.Zero

    // 多次操作套索状态
    internal var lastLassoTapTimeMs = 0L
    internal var lastLassoTapDocPos = Offset.Zero
    internal var justFinishedLassoInDown = false

    internal fun updateLiveSelectionPathFromPoints(points: List<Offset>, closed: Boolean = true) {
        if (points.size < 2) {
            liveSelectionPath?.value = null
            return
        }
        val v = vm
        val tiles = v?.displayTiles?.drawable()
        val bmp = if (tiles != null) null else (v?.displayBitmap ?: docBitmap)?.takeIf { !it.isRecycled }
        val bmpW = (tiles?.grid?.width ?: bmp?.width ?: v?.docWidth?.takeIf { it > 0 } ?: 1).toFloat()
        val bmpH = (tiles?.grid?.height ?: bmp?.height ?: v?.docHeight?.takeIf { it > 0 } ?: 1).toFloat()
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
    internal var prevCentroid = Offset.Zero
    internal var prevDistance = 1f
    internal var prevAngle = 0f
    internal var initialCentroid = Offset.Zero
    internal var initialDistance = 1f
    internal var initialAngle = 0f
    internal var lastTransformTimestamp = 0L
    internal var maxTouchPointers = 0
    internal var touchDownTimeMs = 0L
    internal var lastPos0 = Offset.Zero
    internal var lastPos1 = Offset.Zero

    // ---- 双指旋转 90° 倍数磁性吸附状态 ----
    internal val rotationSnapGesture = RotationSnapGesture()

    // 上一帧是否处于吸附区 (用于进入沿触发一次反馈; 判定带迟滞)
    internal var rotationSnapEngaged = false

    // 手势结束后的"收敛到精确 90° 倍数"动画 (围绕最后双指中心, 不改变缩放/旋转中心)
    internal var snapAnimator: android.animation.ValueAnimator? = null

    // 画布变换的单一写入者仲裁 (新手势 / snap 动画 / fit 动画 三者只有一个能写)
    private val transformWriterGuard = CanvasTransformWriterGuard()

    // 跨碎片延迟重置任务
    internal val resetTransformRunnable = Runnable {
        isTransformActive = false
        isPinchMotion = false
        isTwoFingerRotation = false
        isInteracting = false
        maxTouchPointers = 0
        lastPos0 = Offset.Zero
        lastPos1 = Offset.Zero
    }

    // 防抖撤销任务与 Procreate 风格连续撤销/重做
    internal var pendingUndoRunnable: Runnable? = null
    internal var isContinuousUndoing = false
    internal val editMenuSwipe = com.reverie.paint.model.ThreeFingerSwipe()
    internal val editMenuPointerIds = IntArray(3)
    internal var editMenuGestureActive = false
    internal var editMenuPointersReleased = false
    internal var touchAndSlideActive = false
    internal var touchAndSlideStartPos = Offset.Zero
    internal var lastTwoFingerTapTimeMs = 0L
    internal var lastThreeFingerTapTimeMs = 0L
    internal var singleFingerDownScreenPos = Offset.Zero
    internal var singleFingerDownDocPos = Offset.Zero
    internal var singleFingerDownTimeMs = 0L
    internal var lastSingleFingerTapTimeMs = 0L
    internal var lastSingleFingerTapScreenPos = Offset.Zero
    internal var lastSingleFingerTapDocPos = Offset.Zero
    internal var lastSingleFingerTapDrewStroke = false
    internal var isSingleFingerDoubleTapActive = false
    internal var continuousStepCount = 0

    internal val continuousUndoRunnable = object : Runnable {
        override fun run() {
            val v = vm ?: return
            if (!v.gestureContinuousUndo) return
            // 变换期间撤销被引擎拒绝 (见 isHistoryBlockedByTransform): 停止连撤, 别每 120ms 空转一次
            if (v.isHistoryBlockedByTransform) return
            // 多指误触保护: 液化手势进行中(含场通路的"零解算"阶段)第二指落下不得触发
            // 连续撤销，避免误吃形变
            if (maxTouchPointers == 2 && !isPinchMotion && !isTwoFingerRotation && isInteracting && !isLiquifyGestureActive) {
                isContinuousUndoing = true
                v.requestUndoBarShow()
                v.undo()
                continuousStepCount++
                // 阶梯缓动：首步留出 260ms 反应窗口防止误吞多步，随后进入 120ms 连撤
                val nextDelay = if (continuousStepCount == 1) 260L else 120L
                postDelayed(this, nextDelay)
            }
        }
    }

    internal val continuousRedoRunnable = object : Runnable {
        override fun run() {
            val v = vm ?: return
            if (!v.gestureContinuousUndo) return
            if (v.isHistoryBlockedByTransform) return
            if (maxTouchPointers >= 3 && !isPinchMotion && isInteracting && !isLiquifyGestureActive) {
                isContinuousUndoing = true
                v.requestUndoBarShow()
                v.redo()
                continuousStepCount++
                val nextDelay = if (continuousStepCount == 1) 260L else 120L
                postDelayed(this, nextDelay)
            }
        }
    }

    internal var threeFingerSlideActive = false
    internal var threeFingerSlideAxis = 0 // 0=None, 1=Size (Vertical), 2=Opacity (Horizontal)
    internal var threeFingerSlideStartX = 0f
    internal var threeFingerSlideStartY = 0f
    internal var threeFingerSlideInitialSize = 0.0
    internal var threeFingerSlideInitialOpacity = 0.0
    internal var lastHapticOpacityStep = -1
    internal var lastHapticSizeStep = -1

    internal val twoFingerLongPressRunnable = Runnable {
        val v = vm ?: return@Runnable
        val tapAction = v.gestureConfig.getActionForTrigger(com.reverie.paint.model.GestureTrigger.TWO_FINGER_TAP)
        if (tapAction != null && tapAction != com.reverie.paint.model.GestureAction.NONE) return@Runnable
        val longPressAction = v.gestureConfig.getActionForTrigger(com.reverie.paint.model.GestureTrigger.TWO_FINGER_LONG_PRESS)
        if (longPressAction != null && longPressAction != com.reverie.paint.model.GestureAction.NONE && maxTouchPointers == 2 && !isPinchMotion && !isTwoFingerRotation) {
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            v.dispatchGestureAction(longPressAction, anchor = initialCentroid, docPos = screenToDoc(initialCentroid))
            removeCallbacks(continuousUndoRunnable)
        }
    }

    internal val threeFingerLongPressRunnable = Runnable {
        val v = vm ?: return@Runnable
        val tapAction = v.gestureConfig.getActionForTrigger(com.reverie.paint.model.GestureTrigger.THREE_FINGER_TAP)
        if (tapAction != null && tapAction != com.reverie.paint.model.GestureAction.NONE) return@Runnable
        val longPressAction = v.gestureConfig.getActionForTrigger(com.reverie.paint.model.GestureTrigger.THREE_FINGER_LONG_PRESS)
        if (longPressAction != null && longPressAction != com.reverie.paint.model.GestureAction.NONE && !editMenuSwipe.moved && !threeFingerSlideActive) {
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            val anchor = Offset(threeFingerSlideStartX, threeFingerSlideStartY)
            v.dispatchGestureAction(longPressAction, anchor = anchor, docPos = screenToDoc(anchor))
            removeCallbacks(continuousRedoRunnable)
        }
    }

    // 空格键长按临时抓手平移状态 (Spacebar Hold-to-Pan)
    internal var spacePanStartPos = Offset.Zero
    internal var spacePanInitialPan = Offset.Zero
    internal var isSpaceDragging = false

    // R 键长按临时旋转状态 (Hold-to-Rotate)
    internal var rotateHoldStartPos = Offset.Zero
    internal var rotateHoldStartDownTime = 0L
    internal var rotateHoldPrevAngle = 0f
    internal var rotateHoldCumulativeRot = 0f
    internal var rotateHoldSnapped = false
    internal var isRotateHoldingDragging = false

    // 缩放工具手势状态 (Tool.ZOOM)
    internal var zoomToolStartPos = Offset.Zero
    internal var zoomToolStartDownTime = 0L
    internal var zoomToolStartCanvasZoom = 1f
    internal var zoomToolStartPanX = 0f
    internal var zoomToolStartPanY = 0f
    internal var isZoomToolDragging = false

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
                val hideCursor = (effTool() == Tool.BRUSH || effTool() == Tool.ERASER || effTool() == Tool.SMUDGE || effTool() == Tool.LIQUIFY) &&
                    (vm?.cursorStyleMode != 4)
                if (hideCursor && systemNullPointer != null) systemNullPointer else systemDefaultPointer
            }
            if (targetIcon != null && pointerIcon != targetIcon) {
                pointerIcon = targetIcon
            }
        }
        invalidate()
    }

    fun setRotateHolding(active: Boolean) {
        if (!active) {
            isRotateHoldingDragging = false
            isInteracting = false
        }
        invalidate()
    }

    // 画布平滑复位动画 (Procreate Smooth Reset Animation)
    internal var fitAnimator: android.animation.ValueAnimator? = null

    /**
     * 终止所有画布变换动画 (吸附收敛 / 满屏复位) 并失效其待执行帧, 返回新的写入者令牌。
     *
     * 新手势与任何新动画都必须先经此函数: 令牌递增后, 旧动画即使还有已入队的帧回调,
     * 也会在自检处**整帧丢弃** (而不是"少写一部分"), 因此同一帧内只有一个写入者能改画布
     * 变换, 且取消后不会留下延迟写入或半更新状态。
     */
    internal fun cancelCanvasTransformAnimators(): Int {
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
    internal fun settleRotationToSnap(pivotX: Float, pivotY: Float) {
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
    internal var isPendingLongPress = false
    internal var pendingDownDocPos = Offset.Zero
    internal var pendingDownScreenPos = Offset.Zero
    internal var pendingDownPressure = 1f
    internal var isLongPressPickerActive = false
    internal var pendingLongPressPointerId = -1
    internal var longPressPointerId = -1
    internal var longPressToken = 0L
    internal var activeLongPressToken = 0L

    internal fun dismissPickerOverlay() {
        if (isLongPressPickerActive || (tool != Tool.PICKER && pickerActive?.value == true)) {
            pickerActive?.value = false
            isLongPressPickerActive = false
            longPressPointerId = -1
            pendingLongPressPointerId = -1
            cachedDriver?.feedbackManager?.setWritingHapticsEnabled(false)
            cachedDriver?.feedbackManager?.stopStrokeSound()
        }
    }

    internal fun finishLongPressPicker(v: PaintViewModel) {
        if (!isLongPressPickerActive) return
        cachedDriver?.feedbackManager?.setWritingHapticsEnabled(false)
        cachedDriver?.feedbackManager?.stopStrokeSound()
        lastSingleFingerTapTimeMs = 0L
        lastSingleFingerTapDrewStroke = false
        longPressPointerId = -1
        pendingLongPressPointerId = -1
        val curCol = pickerCurrentColor?.value
        if (curCol != null && curCol.alpha > 0.05f) {
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
    }

    internal val longPressRunnable = Runnable {
        val v = vm ?: return@Runnable
        if (activeLongPressToken == longPressToken && isPendingLongPress && !isTransformActive && maxTouchPointers <= 1) {
            isPendingLongPress = false
            longPressPointerId = pendingLongPressPointerId
            // 12 条强制复位路径之一：长按吸色打断笔画。
            clearAllEvasionState(v)
            if (strokeStarted) {
                abortAndClearRealInk()
                v.touchCancel()
                strokeStarted = false
                safeEndSymmetryUndoMacro()
                resetMirrorBranches()
                clearPredictionState(triggerInvalidate = true)
            }
            cachedDriver?.feedbackManager?.setWritingHapticsEnabled(false)
            cachedDriver?.feedbackManager?.stopStrokeSound()
            val configuredAction = v.gestureConfig.getActionForTrigger(com.reverie.paint.model.GestureTrigger.SINGLE_FINGER_LONG_PRESS)
                ?: if (v.longPressEyedropperEnabled) com.reverie.paint.model.GestureAction.EYEDROPPER else com.reverie.paint.model.GestureAction.NONE

            if (configuredAction == com.reverie.paint.model.GestureAction.EYEDROPPER) {
                isLongPressPickerActive = true
                pickerActive?.value = true
                val refHex = v.brushColor
                pickerInitialColor?.value = parseColor(refHex)
                sampleColorAtScreenPos(pendingDownScreenPos)
            } else if (configuredAction != com.reverie.paint.model.GestureAction.NONE) {
                performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                v.dispatchGestureAction(configuredAction, anchor = pendingDownScreenPos, docPos = screenToDoc(pendingDownScreenPos))
            }
        }
    }

    // ---- 分格工具手势 (仅 Tool.PANEL 且单指时接管; 双指视口手势不受影响) ----
    // 约定与 TRANSFORM 一致: doc 阈值 = 18f * density / (canvasZoom * canvasFitScale)。
    // 预览全部写 [panelInteraction] (Compose 快照状态, 覆盖层即时重绘);
    // 引擎调用 (updatePanelShape/movePanelBy/createPanelAt) 只在抬笔发生 —— 拖动中不入撤销栈。

    // 拖动模式 (DOWN 判定优先级: 手柄 > 共享边 > 格体 > 空白), 取值见文件顶部 PANEL_DRAG_*
    internal var panelDragMode = PANEL_DRAG_NONE
    internal var panelDragStartDoc = Offset.Zero
    /** PANEL DOWN 的原始落点 (切分模式会把 panelDragStartDoc 吸附到边框, 距离判定用本值)。 */
    internal var panelDownRawDoc = Offset.Zero
    internal var panelDownScreenPos = Offset.Zero
    internal var panelDragCorner = -1
    internal var panelDragEdgeIndex = -1
    internal var panelDragSecondEdgeIndex = -1
    /**
     * 角点拖形的角度吸附目标方向集 (单位方向对, 模 180°): 其他格每条边方向 + 水平/垂直。
     * HANDLE DOWN 时构建一次 (拖形期间其他格不动, 缓存避免 MOVE 每帧重建), 见
     * [buildPanelCornerSnapDirs]。
     */
    internal var panelCornerSnapDirs: FloatArray = FloatArray(0)
    internal var panelDragIds: LongArray = longArrayOf()
    internal var panelDragStartQuads: List<PanelQuad> = emptyList()
    internal var panelPendingPanelId = -1L
    internal var panelLastTapTimeMs = 0L
    internal var panelLastTapPanelId = -1L
    internal var panelLongPressPending = false
    internal var panelLongPressPanelId = -1L
    internal var panelLongPressToken = 0L
    internal var panelActiveLongPressToken = 0L
    internal var panelMarqueeArmed = false

    /**
     * 长按 (复用 longPressRunnable 的 token 模式): 压在格上弹极简菜单。
     * 建格模式 (BODY/BODY_PENDING) 之后拖动转框选多选; 切分模式 (DIVIDE_PENDING)
     * 弹菜单后手势消费 (CONSUMED), 不再切分, 也不转框选。
     */
    internal val panelLongPressRunnable = Runnable {
        val st = panelInteraction ?: return@Runnable
        if (panelActiveLongPressToken == panelLongPressToken && panelLongPressPending &&
            (panelDragMode == PANEL_DRAG_BODY || panelDragMode == PANEL_DRAG_BODY_PENDING ||
                panelDragMode == PANEL_DRAG_DIVIDE_PENDING) &&
            !isTransformActive && maxTouchPointers <= 1
        ) {
            panelLongPressPending = false
            if (panelDragMode == PANEL_DRAG_DIVIDE_PENDING) {
                // 切分长按成功: 菜单保持, 本次手势不再切分 (MOVE/UP 对 CONSUMED 均直通)
                panelMarqueeArmed = false
                panelDragMode = PANEL_DRAG_CONSUMED
            } else {
                panelMarqueeArmed = true
            }
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            st.menuScreenPos = panelDownScreenPos
            st.menuPanelId = panelLongPressPanelId.takeIf { it >= 0 }
        }
    }

    // 边缘侧滑返回手势与起笔防误画缓冲 (仅针对手指触控，压感笔完全不受影响)
    internal var isPendingEdgeFinger = false
    internal var pendingEdgeScreenPos = Offset.Zero
    internal var pendingEdgeDocPos = Offset.Zero

    internal val flushEdgeFingerRunnable = Runnable {
        flushPendingEdgeFinger()
    }

    // ---- 笔尖前向超前预测 (OEM Reflection & Universal Kalman Motion Prediction) ----
    // 华为 官方笔迹预测引擎 (HwStrokeEstimate 反射包装)
    internal var huaweiPredictor: com.reverie.paint.core.stylus.HuaweiMotionPredictor? = null
    // 小米 官方笔迹预测引擎 (MiuiStrokeEstimate 反射包装)
    internal var xiaomiPredictor: com.reverie.paint.core.stylus.XiaomiMotionPredictor? = null

    internal val tempOemPoint = FloatArray(3)

    internal val universalPredictor = com.reverie.paint.core.stylus.UniversalKalmanPredictor(
        predictionTargetMs = 40.0f,
        maxSteps = 8
    )
    // 热路径零分配 (AGENTS.md §4.4): Offset 为 @JvmInline value class, 声明为可空类型
    // Offset? 后每次赋值都会在堆上装箱分配。拆成两个 Float + NaN 哨兵, 热路径零分配。
    internal var predictedScreenX: Float = Float.NaN
    internal var predictedScreenY: Float = Float.NaN
    /** 是否持有有效预测点 (替代原来的可空 Offset 判空) */
    internal val hasPredictedScreenPoint: Boolean
        get() = !predictedScreenX.isNaN() && !predictedScreenY.isNaN()

    internal fun setPredictedScreenPoint(x: Float, y: Float) {
        predictedScreenX = x
        predictedScreenY = y
    }

    internal fun clearPredictedScreenPoint() {
        predictedScreenX = Float.NaN
        predictedScreenY = Float.NaN
    }

    // STAMP 笔画级位图缓存引用 (单笔笔画期间复用, 避免在 120Hz/240Hz MOVE 热路径重复查表与分配 Key)
    internal var predictedPressure: Float = 1f
    internal val tempScalarPredPoint = FloatArray(3)

    // ---- 前缓冲 UI 裁剪快照 (Phase 1) ----
    // 所有面板的实测 bounds 由 Compose 侧 reportUiSlot 写入 vm.panelOcclusion;
    // 这里只在槽位版本变化/DOWN/arm/onSizeChanged 时构建不可变快照并 @Volatile 发布。
    // 渲染线程只读快照, 不接触任何可变 RectF。
    @Volatile
    internal var uiClipSnapshot: UiClipSnapshot = UiClipSnapshot.EMPTY
    private var lastUiClipVersion: Int = -1

    // tracker.onChanged 在 UI 线程触发, 但 KDoc 禁止回调内同步重入 tracker;
    // 这里只置位并 post 一次, 同一帧内的多次槽位变化合并为一次快照重建。
    @Volatile
    private var snapshotRebuildPending = false
    private val snapshotRebuildRunnable = Runnable {
        snapshotRebuildPending = false
        rebuildUiExclusionSnapshot()
    }

    private fun scheduleSnapshotRebuild() {
        if (snapshotRebuildPending) return
        snapshotRebuildPending = true
        if (!post(snapshotRebuildRunnable)) snapshotRebuildPending = false
    }

    /**
     * 重建 UI 裁剪快照。仅当 tracker.version 变化或 [force] 时真正构建;
     * 每笔 DOWN / arm / onSizeChanged 调用 (UI 线程, 非逐事件热路径)。
     */
    internal fun rebuildUiExclusionSnapshot(force: Boolean = false) {
        val v = vm
        if (v == null) {
            if (uiClipSnapshot !== UiClipSnapshot.EMPTY) {
                uiClipSnapshot = UiClipSnapshot.EMPTY
                lastUiClipVersion = -1
            }
            if (realInk.held) realInk.releaseAndCatchUp()
            return
        }
        val version = v.panelOcclusion.version
        if (!force && version == lastUiClipVersion) return
        // tracker 是裁剪状态的唯一真身。
        val snapshot = v.panelOcclusion.snapshot()
        lastUiClipVersion = version
        uiClipSnapshot = snapshot
        // tracker 可能因 owner 接管/移除而清掉渐隐位: 同步清掉 Compose 侧残留状态。
        for (i in UiPanelSlot.EVADABLE_ARRAY.indices) {
            val slot = UiPanelSlot.EVADABLE_ARRAY[i]
            if (!v.panelOcclusion.isEvaded(slot) && v.slotEvading[slot.ordinal].value) {
                v.slotEvading[slot.ordinal].value = false
            }
        }
        if (realInk.held) {
            val oldClips = realInk.uiClips
            if (snapshot != oldClips) {
                val delta = clipDelta(oldClips, snapshot)
                val inkBounds = realInk.publishedInkBounds
                ensureViewTransform()
                viewTransform.docToScreen(v.smoothedStrokeX, v.smoothedStrokeY, pointScratch)
                val headScreenX = pointScratch[0]
                val headScreenY = pointScratch[1]
                val headRadius = effectiveBrushScreenRadius(v, v.smoothedStrokePressure.toFloat())
                val headRect = ClipRect(
                    headScreenX - headRadius,
                    headScreenY - headRadius,
                    headScreenX + headRadius,
                    headScreenY + headRadius,
                )
                val action = clipChangeAction(delta, inkBounds, headRect)
                FbGeomLog.clipRebuild(realInk.currentStrokeSeq, version, delta.size, inkBounds != null, action == ClipChangeAction.REBAKE)
                when (action) {
                    ClipChangeAction.KEEP_UPDATE -> {
                        realInk.updateActiveUiClips(snapshot)
                    }
                    ClipChangeAction.REBAKE -> {
                        // 相交重烤: 保持按住, 把差集区域与已画墨的相交部分在原层按新 clips 重新烘焙 (plan 四.2/四.3)
                        realInk.updateClipsAndRebake(snapshot, delta)
                    }
                }
            } else {
                realInk.updateActiveUiClips(snapshot)
            }
        }
    }

    internal fun effectiveBrushScreenRadius(v: PaintViewModel, pressure: Float): Float {
        val scale = (canvasZoom * canvasFitScale).coerceAtLeast(0.001f)
        val size = if (effTool() == Tool.ERASER && tool != Tool.ERASER) {
            v.getToolEffectiveSize("eraser").toFloat()
        } else {
            v.brushSize.toFloat()
        }
        val pFrac = if (v.brushPressureEnabled) pressureFractionCached(pressure) else 1f
        return (size * scale * 0.5f * pFrac).coerceAtLeast(0.75f)
    }

    /**
     * 50% 双向滞后评估：对 9 个可避让槽位逐个独立判定。
     *
     * - 包络到面板本体距离 <= 32dp + brushR：进入 50%；
     * - 距离 > 40dp + brushR：立即恢复 100%，不等抬笔；
     * - 中间滞后带保持上一状态，防边界抖动。
     *
     * 240Hz `ACTION_MOVE` 热路径：纯标量运算，零分配。
     */
    internal fun evaluateUiEvasion50(
        v: PaintViewModel,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        brushRadiusPx: Float,
    ) {
        if (!left.isFinite() || !top.isFinite() || !right.isFinite() || !bottom.isFinite()) return
        if (!brushRadiusPx.isFinite() || brushRadiusPx < 0f) return
        if (!v.autoHidePanelsOnStroke) return
        val enterRadiusPx = EVADE_ENTER_DP * density + brushRadiusPx
        val exitRadiusPx = EVADE_EXIT_DP * density + brushRadiusPx
        for (i in UiPanelSlot.EVADABLE_ARRAY.indices) {
            val slot = UiPanelSlot.EVADABLE_ARRAY[i]
            val shouldEvade = v.panelOcclusion.evaluateSlotEvasion(
                slot, left, top, right, bottom, enterRadiusPx, exitRadiusPx,
            )
            v.setSlotEvading(slot, shouldEvade)
        }
    }

    /**
     * 立即清除全部避让状态（12 条强制复位路径共用）。
     *
     * 50% 渐隐只影响 Compose 透明度，绝不撤除 [uiClipSnapshot] 中的孔径裁剪。
     */
    internal fun clearAllEvasionState(v: PaintViewModel) {
        removeCallbacks(clearEvasionAfterUpRunnable)
        v.clearAllSlotEvasion()
    }

    /** 正常抬笔后的 80ms 延续窗口；下一笔落点仍在 40dp 内则无缝保持 50%。 */
    internal val clearEvasionAfterUpRunnable = Runnable {
        if (!strokeStarted) {
            vm?.let { clearAllEvasionState(it) }
        }
    }

    internal fun scheduleClearEvasionAfterUp() {
        removeCallbacks(clearEvasionAfterUpRunnable)
        postDelayed(clearEvasionAfterUpRunnable, POST_UP_CONTINUATION_MS)
    }

    internal val tipShaderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
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

    internal fun recordTouchSample(x: Float, y: Float, timeMs: Long) {
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
    internal var smoothedCursorPressure = -1f

    // 光标局部重绘脏区追踪 (避免无参 invalidate 全量整屏重绘)
    internal var prevCursorRectValid = false
    internal var prevCursorLeft = 0f
    internal var prevCursorTop = 0f
    internal var prevCursorRight = 0f
    internal var prevCursorBottom = 0f

    @Volatile internal var lastPredictionUptimeMs: Long = 0L
    internal var isWatchdogScheduled = false

    internal val predictionStagnationRunnable = object : Runnable {
        override fun run() {
            val now = android.os.SystemClock.uptimeMillis()
            val elapsed = now - lastPredictionUptimeMs
            if (elapsed >= 50L) {
                isWatchdogScheduled = false
                if (hasPredictedScreenPoint) {
                    clearPredictionState(triggerInvalidate = true)
                }
            } else {
                val nextDelay = (50L - elapsed).coerceAtLeast(16L)
                postDelayed(this, nextDelay)
            }
        }
    }

    internal fun abortAndClearRealInk() {
        realInk.release()
        realInk.cancelCatchUp()
        frontBufferOverlay?.clearInk()
    }

    internal fun clearPredictionState(triggerInvalidate: Boolean = false) {
        frontBufferOverlay?.clearPreview()
        if (isWatchdogScheduled) {
            removeCallbacks(predictionStagnationRunnable)
            isWatchdogScheduled = false
        }
        val had = hasPredictedScreenPoint
        clearPredictedScreenPoint()
        smoothedCursorPressure = -1f
        priorStrokeScreenPos = Offset.Zero
        smoothedLeadMs = 20f
        resetTouchHistory()
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
    internal fun invalidateCursor(newX: Float, newY: Float, cursorRadiusPx: Float = 0f) {
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

    internal fun invalidateCursor(newPos: Offset?, cursorRadiusPx: Float = 0f) {
        if (newPos != null) {
            invalidateCursor(newPos.x, newPos.y, cursorRadiusPx)
        } else {
            invalidateCursor(Float.NaN, Float.NaN, cursorRadiusPx)
        }
    }

    // 预分配多指触控索引缓冲区 (热路径零分配 §4)
    internal val fingerIndices = IntArray(16)
    internal var fingerCount = 0

    // ---- 对称与透视绘图辅助 (Drawing Assist) ----
    // 对称绘制采样缓冲: 主笔画分支 (primary) 与镜像分支 (mirrored), 扁平存 [x, y, pressure]
    internal var primarySamples = FloatArray(48)
    internal var primarySampleSize = 0
    internal val mirroredSamples = ArrayList<FloatArray>(INITIAL_MIRROR_BRANCH_CAPACITY)
    // 必须与 mirroredSamples 等长: 分支数不再是固定的 8 (36 轴万花筒 = 71 支),
    // 固定长度会在 resetMirrorBranches 里踩到 IntArray 边界 (1.6.2 线上 AIOOBE)。
    internal var mirroredSizes = IntArray(INITIAL_MIRROR_BRANCH_CAPACITY)
    /** 本笔是否允许实时镜像入队 (分支数超过引擎上限时置 false, 整笔改走抬笔重放)。 */
    internal var mirrorLiveQueueAllowed = true
    @Volatile internal var isSymmetryReplaying = false

    /**
     * 对称采样缓冲里的时间戳基准 (uptime 毫秒)。第一个采样点落在 [0, 1) 秒, 之后按
     * `eventTime` 差值换算 —— 重放时引擎就能按真实间隔推进, 而不是把整笔挤成 0 秒。
     */
    internal var symmetryStrokeStartTimeMs = 0L

    internal val mirroredDrawPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    internal val mirroredPointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    // 对称实时绘制笔迹压感查找表 (128阶，热路径零分配与零 JNI 锁开销)
    internal val symmetryPressureLut = FloatArray(129)
    internal var lastLutPresetIndex = -999
    internal var lastLutCurve = -1
    internal var lastLutEnabled = false
    internal var lastLutPressureSize = -1.0
    internal var lastLutGlobalCurvePoints: List<Offset>? = null

    // ---- 视图变换缓存与热路径零分配暂存区 (AGENTS.md §4) ----
    // 绘制覆盖层时每个点都要做一次 doc->screen, 旧实现每次现算三角函数; 缓存
    // 视图参数后整条笔迹共用一份变换, 并且只往复用数组里写结果, 全程零分配。
    internal val viewTransform = CanvasViewTransform()
    internal val pointScratch = FloatArray(2)
    internal val symScratchX = FloatArray(72)
    internal val symScratchY = FloatArray(72)
    internal val symEndScratchX = FloatArray(72)
    internal val symEndScratchY = FloatArray(72)
    internal val cursorSymScreenX = FloatArray(72)
    internal val cursorSymScreenY = FloatArray(72)
    internal val boundsScratch = IntArray(4)
    // 512 显示瓦片: 视口命中的瓦片列表 (复用) 与边缘 clamp 的着色器缓存
    internal var tileDrawScratch = IntArray(0)
    internal val clampedTiles = ClampedTileDrawer()
    internal var gridLinesBuffer = FloatArray(4096)
    internal val renderBoundsScratch = IntArray(4)
    private val pixelScratch = IntArray(1)
    private val frontierPointScratch = FloatArray(2)
    @Volatile internal var lastInvalidateFromRenderUptimeNs = 0L
    internal var smoothedLeadMs = 20f

    /** 画笔颜色的解析缓存 (brushColor 是字符串, 每帧 parseColor 纯属浪费) */
    private var cachedColorHex: String? = null
    private var cachedColorInt: Int = android.graphics.Color.BLACK

    /** 压力曲线查表的量化缓存 (JNI 调用; 落笔期间压力连续但帧间变化很小) */
    private var cachedPressureKey: Int = -1
    private var cachedPressureFraction: Float = 1f

    /** 局部失效开关: 任一安全条件不满足时自动回退全量重绘 */
    var partialInvalidateEnabled: Boolean = true

    internal sealed interface AssistRay {
        data class Horizontal(val y: Float) : AssistRay
        data class Vertical(val x: Float) : AssistRay
        data class VanishingPoint(val vp: Point2D, val dir: Offset) : AssistRay
        data class Direction(val dir: Offset) : AssistRay
    }
    internal var assistLockedRay: AssistRay? = null

    // 鱼眼吸附: 笔画开始时锁定选定的横/纵曲线或中心射线
    internal var assistFisheyeLocked = false
    internal var assistFisheyeFamily = 0 // 0 = 横向曲线, 1 = 纵向曲线, 2 = 中心射线
    internal var assistFisheyeA = 0.0

    internal var draggingGuideHandleIndex = -1 // -1: none, 100: symmetry center, 101: symmetry rotation, 200: 3D orbit, 0..N: VP index
    internal var lastOrbitDocPos = Offset.Zero

    internal val systemNullPointer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        PointerIcon.getSystemIcon(context, PointerIcon.TYPE_NULL)
    } else null

    private val systemDefaultPointer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        PointerIcon.getSystemIcon(context, PointerIcon.TYPE_DEFAULT)
    } else null

    internal val systemHandPointer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        PointerIcon.getSystemIcon(context, PointerIcon.TYPE_HAND)
    } else null

    internal val systemGrabPointer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        PointerIcon.getSystemIcon(context, PointerIcon.TYPE_GRAB)
    } else null

    internal val systemZoomInPointer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        PointerIcon.getSystemIcon(context, PointerIcon.TYPE_ZOOM_IN)
    } else null

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

        return v.panelOcclusion.hitTest(x, y)
    }

    // 系统手势侧滑返回排除区状态 (API 29+ Android 10: 绘画页面全高全局排除两侧返回手势)
    private val gestureExclusionRects = mutableListOf<android.graphics.Rect>()
    private val leftExclusionRect = android.graphics.Rect()
    private val rightExclusionRect = android.graphics.Rect()
    private var lastShouldExclude: Boolean? = null
    private var lastExclusionW: Int = 0
    private var lastExclusionH: Int = 0

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
        if (lastShouldExclude == shouldExclude && lastExclusionW == w && lastExclusionH == h) return
        lastShouldExclude = shouldExclude
        lastExclusionW = w
        lastExclusionH = h

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
        rebuildUiExclusionSnapshot(force = true)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        activeTouchView = this
        vm?.realInkSink = realInk
        // 重新挂载时 VM 通常未变 (setter 会提前返回), 这里补挂回 detach 时摘掉的回调。
        vm?.panelOcclusion?.onChanged = { scheduleSnapshotRebuild() }
        lastUiClipVersion = -1
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
        post { updateSystemGestureExclusion() }
    }

    internal var lastRefreshRateCheckTime: Long = 0L

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
        vm?.panelOcclusion?.onChanged = null
        holeMasks.shutdown()
        removeCallbacks(snapshotRebuildRunnable)
        removeCallbacks(clearEvasionAfterUpRunnable)
        snapshotRebuildPending = false
        // 12 条强制复位路径之一：视图卸载。
        vm?.clearAllSlotEvasion()
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
        dismissPickerOverlay()
        longPressToken++
        if (activeTouchView == this) activeTouchView = null
        abortAndClearRealInk()
        vm?.let { if (it.realInkSink === realInk) it.realInkSink = null }
        if (strokeStarted) {
            abortAndClearRealInk()
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
        huaweiPredictor?.destroy()
        huaweiPredictor = null
        xiaomiPredictor?.destroy()
        xiaomiPredictor = null
        clearPredictionState(triggerInvalidate = false)
        safeEndSymmetryUndoMacro()
        resetMirrorBranches()
        currentStrokeDocPos = Offset.Zero
    }

    override fun onResolvePointerIcon(event: MotionEvent, pointerIndex: Int): PointerIcon? {
        val v = vm ?: return super.onResolvePointerIcon(event, pointerIndex)
        val hideCursor = (effTool() == Tool.BRUSH || effTool() == Tool.ERASER || effTool() == Tool.SMUDGE || effTool() == Tool.LIQUIFY) &&
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
    override fun invalidate() {
        if (LandDiag.enabled && LandDiag.armed) LandDiag.noteInvalidate(LandDiag.shortCaller())
        super.invalidate()
    }

    override fun postInvalidate() {
        if (LandDiag.enabled && LandDiag.armed) LandDiag.noteInvalidate("post:" + LandDiag.shortCaller())
        super.postInvalidate()
    }

    override fun postInvalidateOnAnimation() {
        if (LandDiag.enabled && LandDiag.armed) LandDiag.noteInvalidate("postOnAnim:" + LandDiag.shortCaller())
        super.postInvalidateOnAnimation()
    }

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
            if (realInk.held && realInk.strokeHoles != null && com.reverie.paint.perf.FbGeomLog.timingEnabled) {
                val th = System.nanoTime()
                drawCanvas(canvas)
                com.reverie.paint.perf.FbGeomLog.holeWindowDraw(System.nanoTime() - th)
                return
            }
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

    // Support drawing huge bitmaps (> 100MB, e.g. 6000x6000 = 144MB, 8192x8192 = 256MB)
    // without triggering android.graphics.RecordingCanvas.throwIfCannotDraw
    internal class LargeBitmapTile(
        val bitmap: Bitmap,
        val canvas: Canvas,
        val sx: Int,
        val sy: Int,
        val width: Int,
        val height: Int,
    )
    internal var largeBitmapTiles: Array<LargeBitmapTile>? = null
    internal var largeBitmapLastGenId: Int = -1
    internal var largeBitmapCols: Int = 0
    internal var largeBitmapRows: Int = 0
    internal val largeBitmapTilePaint = Paint().apply {
        xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC)
    }
    internal val largeBitmapSrcRect = android.graphics.Rect()
    internal val largeBitmapDstRect = android.graphics.Rect()
    internal val largeBitmapDirtyScratch = android.graphics.Rect()

    private val holeCursorPainter by lazy(LazyThreadSafetyMode.NONE) { HoldCursorPainter(density) }
    private val holeCursorBox = IntArray(4)
    private val holeCursorDstIn = Paint().apply {
        xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN)
        isFilterBitmap = false
    }

    // 上一次因光标压孔而失效的屏幕框 (noteHoldCursor 用来擦掉旧位置)
    private var holeCursorBoxValid = false
    private var holeCursorL = 0
    private var holeCursorT = 0
    private var holeCursorR = 0
    private var holeCursorB = 0

    internal fun drawHoleCursor(canvas: Canvas) {
        if (!realInk.held) return
        val holes = realInk.strokeHoles ?: return
        val bmp = holes.bitmap ?: return
        val r = realInk.cursorR
        if (r <= 0f || bmp.isRecycled) return
        val b = holeCursorBox
        if (!HoldCursorPainter.bounds(realInk.cursorX, realInk.cursorY, holeCursorPainter.half(r), realInk.symCursorX,
                realInk.symCursorY, realInk.symCursorCount, b)
        ) {
            return
        }
        if (!holes.grid.anyExact(b[0], b[1], b[2], b[3])) return
        canvas.saveLayer(b[0].toFloat(), b[1].toFloat(), b[2].toFloat(), b[3].toFloat(), null)
        // 前缓冲在 UI 避让区外才画光标, 避让区里由下面的常规分支画; 这里同样避开, 免得叠两次。
        val clips = uiClipSnapshot
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            for (i in 0 until clips.count) canvas.clipOutRect(clips.left(i), clips.top(i), clips.right(i), clips.bottom(i))
        }
        holeCursorPainter.drawAll(canvas, realInk.cursorX, realInk.cursorY, r, realInk.cursorStyle, realInk.symCursorX,
            realInk.symCursorY, realInk.symCursorCount)
        canvas.drawBitmap(bmp, 0f, 0f, holeCursorDstIn)
        canvas.restore()
    }

    /**
     * 按住期间光标框压到孔洞 (或上一次压到) 时让窗口重画这两个框; 其余情况零额外工作。
     * 判定用 O(1) 的保守 [com.reverie.paint.model.HoleMaskGrid.anyInRect]。UI 线程。
     */
    private fun invalidateHoleCursor() {
        val holes = realInk.strokeHoles
        var hit = false
        val b = holeCursorBox
        if (holes != null && realInk.held && realInk.cursorR > 0f &&
            HoldCursorPainter.bounds(realInk.cursorX, realInk.cursorY, holeCursorPainter.half(realInk.cursorR),
                realInk.symCursorX, realInk.symCursorY, realInk.symCursorCount, b)
        ) {
            hit = holes.grid.anyInRect(b[0], b[1], b[2], b[3])
        }
        if (!hit && !holeCursorBoxValid) return
        var l = Int.MAX_VALUE
        var t = Int.MAX_VALUE
        var r = Int.MIN_VALUE
        var bt = Int.MIN_VALUE
        if (holeCursorBoxValid) {
            l = holeCursorL; t = holeCursorT; r = holeCursorR; bt = holeCursorB
        }
        if (hit) {
            l = minOf(l, b[0]); t = minOf(t, b[1]); r = maxOf(r, b[2]); bt = maxOf(bt, b[3])
            holeCursorL = b[0]; holeCursorT = b[1]; holeCursorR = b[2]; holeCursorB = b[3]
        }
        holeCursorBoxValid = hit
        postInvalidateOnAnimation(maxOf(l, 0), maxOf(t, 0), minOf(r, width), minOf(bt, height))
    }

    internal fun sampleColorAtScreenPos(screenPos: Offset) {
        val v = vm ?: return
        val samplePos = if (v.eyedropperOffsetEnabled) {
            screenPos + Offset(-48f * density, -48f * density)
        } else {
            screenPos
        }
        pickerScreenPos?.value = samplePos
        val docPos = screenToDoc(samplePos)

        // 当前图层模式：直接从底层当前图层的 PaintDevice 采样
        if (v.pickerSampleLayers == 0 || v.pickerCurrentLayerOnly) {
            val ix = docPos.x.toInt()
            val iy = docPos.y.toInt()
            val docW = if (v.docWidth > 0) v.docWidth else (v.displayBitmap?.width ?: 0)
            val docH = if (v.docHeight > 0) v.docHeight else (v.displayBitmap?.height ?: 0)
            if (ix in 0 until docW && iy in 0 until docH) {
                val hex = ReverieCoreBridge.pickColorAt(ix, iy, true)
                if (!hex.isNullOrEmpty()) {
                    try {
                        pickerCurrentColor?.value = Color(android.graphics.Color.parseColor(hex))
                    } catch (_: Throwable) {
                        pickerCurrentColor?.value = Color.Transparent
                    }
                } else {
                    pickerCurrentColor?.value = Color.Transparent
                }
            } else {
                pickerCurrentColor?.value = Color.Transparent
            }
            return
        }

        val tiles = v.displayTiles.drawable()
        if (tiles != null) {
            // 瓦片路径下没有整图: 直接从覆盖该像素的瓦片取色 (瓦片坐标 = 文档坐标)
            val ix = docPos.x.toInt()
            val iy = docPos.y.toInt()
            val idx = tiles.grid.tileIndexAt(ix, iy)
            val tile = if (idx >= 0) tiles.tiles.get(idx) else null
            if (tile != null && !tile.isRecycled) {
                val lx = ix - tiles.grid.tileLeft(idx % tiles.grid.cols)
                val ly = iy - tiles.grid.tileTop(idx / tiles.grid.cols)
                if (lx in 0 until tile.width && ly in 0 until tile.height) {
                    tile.getPixels(pixelScratch, 0, 1, lx, ly, 1, 1)
                    pickerCurrentColor?.value = Color(pixelScratch[0])
                }
            }
            return
        }
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

    // ---- 前缓冲真墨 (续 #81) ----

    /**
     * 落笔时决定这一笔是否把真墨留在前缓冲 (之后主画布不再逐帧失效, 见 [RealInkController])。
     *
     * 不按住的情形 (这一笔走主画布正常刷新, 前缓冲仍可画 #81 的前沿段): 前缓冲开关关闭或
     * API < 29、对称辅助 (镜像分支由 onDraw 实时绘制)、画布走整图路径 (墨源只有显示瓦片)、
     * 设备不保留前缓冲内容 (见 [FrontBufferProbe.realInkHoldAllowed])。
     */
    // ---- L-02: 前缓冲就绪门控 (每笔落笔评估一次并锁定) ----
    /** 本笔落笔时的就绪判定; 未就绪落下的笔整笔不按住真墨、也不画 #81 前沿预览。 */
    private var strokeFbReady = false
    private var strokeFbDecision = FrontBufferReadyGate.NOT_READY
    private var strokeFbMask = 0

    /** 落笔时的就绪门控快照写进 FbGeomLog (只在 PerfTrace 打开时调用)。 */
    private fun logFrontBufferReady(v: PaintViewModel, held: Boolean) {
        val fb = frontBufferOverlay
        val loc = IntArray(2)
        if (fb != null) fb.getLocationOnScreen(loc)
        val tiles = v.displayTiles.drawable()
        com.reverie.paint.perf.FbGeomLog.strokeReady(
            strokeSeq = realInk.currentStrokeSeq,
            held = held,
            decision = strokeFbDecision,
            mask = strokeFbMask,
            inferredMask = 0,
            gateEnabled = FrontBufferFeatureFlags.readyGate,
            msSinceQuietStart = -1L,
            msUntilFallback = 0L,
            surfaceX = loc[0],
            surfaceY = loc[1],
            surfaceW = fb?.width ?: -1,
            surfaceH = fb?.height ?: -1,
            viewW = width,
            viewH = height,
            stableFrames = 0,
            refreshHz = 0f,
            targetRefreshHz = 0f,
            renderW = v.renderW,
            renderH = v.renderH,
            gridW = tiles?.grid?.width ?: 0,
            gridH = tiles?.grid?.height ?: 0,
            tileGeneration = v.displayTiles.generation,
            bufferInvalid = v.displayBufferInvalid,
            zoom = canvasZoom,
            fitScale = canvasFitScale,
            panX = canvasPanX,
            panY = canvasPanY,
            rotation = canvasRotation,
            renderScheduled = v.renderScheduled,
            renderFollowUp = v.renderFollowUpPending,
            renderInFlight = v.renderInFlight,
            loading = v.isBlockingLoading,
        )
    }

    /**
     * 落笔时评估一次就绪门控并锁定到 [strokeFbReady]。
     * 真正必要的条件只有: 瓦片已是最终版 (tilesFinal, 不是加载中的低清), 不在加载。
     */
    private fun latchFrontBufferReady(v: PaintViewModel) {
        val tiles = v.displayTiles.drawable()
        val tilesFinal = FrontBufferReadyGate.tilesFinal(
            tiles?.grid?.width ?: 0, tiles?.grid?.height ?: 0, v.renderW, v.renderH, v.displayBufferInvalid,
        )
        val loading = v.isBlockingLoading
        val now = android.os.SystemClock.uptimeMillis()
        strokeFbMask = FrontBufferReadyGate.conditionMask(tilesFinal, loading)
        strokeFbDecision = FrontBufferReadyGate.decide(
            now, v.frontBufferQuietUntilMs, tilesFinal, loading, FrontBufferFeatureFlags.readyGate,
        )
        strokeFbReady = FrontBufferReadyGate.isReady(strokeFbDecision)
    }

    internal fun armRealInkHold(v: PaintViewModel, hasSymmetry: Boolean, x: Float, y: Float, pressure: Float) {
        // 透光台对位模式: 前缓冲置顶会盖住 ShiftTraceOverlay, 统一不按住真墨,
        // 走 #81 前沿预览 + 主画布实时刷新。透明/不透明画布行为一致。
        if (v.anim.shiftTraceActive) {
            abortAndClearRealInk()
            clearAllEvasionState(v)
            frontBufferOverlay?.clearPreview()
        }
        rebuildUiExclusionSnapshot()
        // 落笔时无条件强制刷新最新位姿，杜绝上一笔 held 期间微动 (<0.5px) 导致 pose 过期
        ensureViewTransform(forcePublishPose = true)
        latchFrontBufferReady(v)
        val has3DVisible = v.layers.any { it.visible && v.active3DLayers.containsKey(it.id) }
        val hasCheckerboard = checkerboardPaint != null
        var allowed = v.frontBufferPredictionEnabled && (!hasSymmetry || v.mirrorGate.isQueueing) &&
            v.displayTiles.drawable() != null && FrontBufferProbe.realInkHoldAllowed() &&
            strokeFbReady && !v.anim.shiftTraceActive &&
            RealInkHoldPolicy.bakeSupported(hasCheckerboard, has3DVisible)
        // compose: 覆盖层 (像素网格/绘图辅助线/动态导引线/光标) 由前缓冲按窗口同一顺序画进去 ——
        // 不挖孔、不建掩码、不让窗口重画、吸附线也按住。只有蚂蚁线可见或画布旋转/镜像时这一笔退回 H-min。
        val modeFlag = FrontBufferFeatureFlags.holeMode
        val composePose = realInk.pose
        val compose = allowed && composePose != null && composeApplies(v, composePose)
        if (compose) {
            realInk.setStrokeCompose(true, composeDecorFor(v, composePose!!))
        } else {
            realInk.setStrokeCompose(false, null)
        }
        // H-1: 覆盖层 (参考线/对称轴/像素网格/选区蚂蚁线...) 在前缓冲上挖孔, 由窗口画孔内。
        val holeMode = if (compose) FrontBufferHolePolicy.MODE_COMPOSE else FrontBufferHolePolicy.holeModeFor(modeFlag)
        var holeArm = if (compose) FrontBufferHolePolicy.ARM_COMPOSE else FrontBufferHolePolicy.ARM_NO_HOLES
        var holeMask: HoleMask? = null
        var holeCov = 0f
        if (!compose && allowed && holeMode != FrontBufferHolePolicy.MODE_OFF) {
            val pose = realInk.pose
            val other = holeOtherOverlayActive(v)
            val mask = if (pose != null && !other) holeMasks.readyFor(v, pose) else null
            val visible = when {
                mask != null -> !mask.isEmpty
                pose != null -> holeMasks.anyDecorVisible(v, pose)
                else -> true
            }
            holeCov = mask?.grid?.coverage ?: 0f
            holeArm = FrontBufferHolePolicy.decideArm(
                holeMode, visible, mask != null, holeCov, FrontBufferFeatureFlags.holeMaxCoverage, other,
            )
            if (holeArm == FrontBufferHolePolicy.ARM_NORMAL) allowed = false
            if (holeArm == FrontBufferHolePolicy.ARM_WITH_MASK) holeMask = mask
            // 掩码过期 (刚平移/缩放完还没建好): 这一笔走常规路径, 抬笔后自动补建。
            if (mask == null && visible && pose != null && !other) holeMasks.scheduleIfStale(v, pose)
        }
        realInk.setStrokeHoles(holeMask, holeMode)
        val t = effTool()
        val cursorMode = if (t == Tool.ERASER) v.eraserCursorMode else v.brushCursorMode
        val cursorWhileDrawing = (cursorMode == 1 || cursorMode == 3) && v.cursorStyleMode != 4
        // 吸附导引线由前缓冲画, 擦旧位置要靠备份层: 可能出现导引线的笔画从首帧起维护备份层。
        // compose 不需要备份层: 旧光标/导引线处按 pose 重烤瓦片恢复。预测笔尖 (关低延迟时没有) 不画。
        val extras = !compose && (cursorWhileDrawing || predictedTipEligible(v) ||
            FrontBufferHolePolicy.snapNeedsExtras(snapGuidePossible(v), FrontBufferFeatureFlags.snapHold))
        val armed = realInk.arm(allowed, extras, uiClipSnapshot)
        if (holeMode != FrontBufferHolePolicy.MODE_OFF) {
            com.reverie.paint.perf.FbGeomLog.holeArm(realInk.currentStrokeSeq, holeMode, holeArm, holeCov)
        }
        holeCursorBoxValid = false
        resetHeldGuide()
        if (com.reverie.paint.core.PerfTrace.enabled) logFrontBufferReady(v, armed)
        if (armed) {
            noteHoldCursor(x, y, pressure)
            if (prevCursorRectValid) {
                val l = floor(prevCursorLeft).toInt()
                val t = floor(prevCursorTop).toInt()
                val r = ceil(prevCursorRight).toInt()
                val b = ceil(prevCursorBottom).toInt()
                invalidate(l, t, r, b)
            }
        }
    }

    /**
     * 孔洞方案处理不了的覆盖层: 非笔刷类工具的工具覆盖层; 以及辅助绘图的动态吸附线 ——
     * 默认 ([FrontBufferFeatureFlags.snapHold] 关) 走常规路径保证严格同图; 开关打开时按住,
     * 由前缓冲在孔洞外画、窗口在孔洞内画 (见 [drawHeldGuide] / [invalidateHeldGuide])。
     */
    private fun holeOtherOverlayActive(v: PaintViewModel): Boolean {
        val t = effTool()
        if (t != Tool.BRUSH && t != Tool.ERASER && t != Tool.SMUDGE) return true
        val composePossible = realInk.pose?.let { composeApplies(v, it) } == true
        return FrontBufferHolePolicy.snapForcesNormal(
            snapPossible = snapGuidePossible(v),
            snapHoldEnabled = FrontBufferFeatureFlags.snapHold,
            isCompose = composePossible,
        )
    }

    /**
     * 当前状态下这一笔能否走 compose: 模式开着、画笔/橡皮/涂抹、没有可见蚂蚁线 (随时间流动, 两表面
     * 无法锁相)、画布轴对齐 (旋转/镜像时窗口的双线性采样前缓冲在 CPU 上复刻不出逐像素一致)。
     */
    internal fun composeApplies(v: PaintViewModel, pose: CanvasPose): Boolean {
        val t = effTool()
        val other = t != Tool.BRUSH && t != Tool.ERASER && t != Tool.SMUDGE
        return FrontBufferHolePolicy.composeEligible(
            FrontBufferFeatureFlags.holeMode,
            antsVisible(v),
            FrontBufferCompose.isAxisAligned(pose),
            other,
            FrontBufferFeatureFlags.composeAnts,
        )
    }

    /** 选区蚂蚁线此刻是否可见 (与 [FrontBufferDecorRecorder] 同一判据; 运笔时不可能正在框选)。 */
    private fun antsVisible(v: PaintViewModel): Boolean =
        v.selectionOutlinePath != null && CanvasDecorGeometry.selectionOutlineVisible(
            hasSelection = v.hasSelection,
            isTransform = tool == Tool.TRANSFORM,
            isSelecting = false,
            selectionMode = v.selectionMode,
        )

    /** compose 的静态覆盖层: 状态没变就用已录的那份 (几次引用比较), 变了才重录。 */
    internal fun composeDecorFor(v: PaintViewModel, pose: CanvasPose): FrontBufferDecor? {
        val tr = tool == Tool.TRANSFORM
        val antsPhase = if (antsVisible(v)) {
            (SystemClock.uptimeMillis() % 1200L).toFloat() / 1200f
        } else 0f
        if (composeDecorRecorder.isStale(v, pose, tr)) {
            composeDecor = composeDecorRecorder.record(v, pose, tr, forMask = false, antsPhase = antsPhase)
        }
        return composeDecor
    }

    internal val holdGuideSeg = FloatArray(DynamicGuidePainter.SEG_SIZE)
    internal var holdGuidePrevKind = DynamicGuidePainter.KIND_NONE
    internal val holdGuidePrevSeg = FloatArray(DynamicGuidePainter.SEG_SIZE)
    internal val holdGuidePrevBox = IntArray(4)
    internal val holdGuideBox = IntArray(4)
    internal val holdGuideScratch = FloatArray(4)

    /** 预测笔尖圆点只给纯色高不透明的一级预测笔刷 (半透明/水彩/纹理笔刷上画点会露馅)。 */
    private fun predictedTipEligible(v: PaintViewModel): Boolean =
        RealInkHoldPolicy.isRealInkTool(tool, effTool()) &&
            v.effectivePredictionTier == PaintViewModel.PredictionFidelityTier.TIER_1 &&
            v.brushOpacity >= 0.85 && (v.brushFlow <= 0.0 || v.brushFlow >= 0.85)

    /**
     * 按住期间的预测笔尖: 沿预测方向从当前触点外推, 最多 0.85 倍笔宽 (与真墨末端重叠衔接,
     * 不脱节成孤立点)。只写快照, 不进引擎, 零分配。
     */
    internal fun updatePredictedTip(v: PaintViewModel, rawX: Float, rawY: Float) {
        val hasSymmetry = v.drawingGuide.mode == GuideMode.SYMMETRY && v.drawingGuide.assistedDrawing
        if (hasSymmetry || !hasPredictedScreenPoint || !predictedTipEligible(v)) {
            realInk.tipWidth = 0f
            return
        }
        val scale = (canvasZoom * canvasFitScale).coerceAtLeast(0.001f)
        val pForWidth =
            if (predictedPressure.isFinite() && predictedPressure > 0.02f) predictedPressure else localPressure
        val pFrac = if (v.brushPressureEnabled) pressureFractionCached(pForWidth) else 1f
        val w = (v.brushSize.toFloat() * scale * pFrac).coerceAtLeast(1.5f)
        val dx = predictedScreenX - rawX
        val dy = predictedScreenY - rawY
        val dist = hypot(dx, dy)
        if (!dist.isFinite() || dist < 1.5f) {
            realInk.tipWidth = 0f
            return
        }
        val maxLead = maxOf(w * 0.85f, 6f * density).coerceAtMost(28f * density)
        val d = dist.coerceAtMost(maxLead)
        realInk.tipX = rawX + dx / dist * d
        realInk.tipY = rawY + dy / dist * d
        realInk.tipColor = (resolveBrushColorCached(v.brushColor) and 0x00FFFFFF) or (0xFF shl 24)
        realInk.tipWidth = w
    }

    /** 按住期间光标不走视图失效, 位置/半径记进快照, 由前缓冲随墨一起画。 */
    internal fun noteHoldCursor(screenX: Float, screenY: Float, pressure: Float) {
        val v = vm
        var cx = screenX
        var cy = screenY
        if (hasPredictedScreenPoint && predictedScreenX.isFinite() && predictedScreenY.isFinite()) {
            val dx = predictedScreenX - screenX
            val dy = predictedScreenY - screenY
            val dist = hypot(dx, dy)
            val maxLead = 24f * density
            if (dist in 0.5f..maxLead) {
                cx = screenX + dx * 0.65f
                cy = screenY + dy * 0.65f
            } else if (dist > maxLead) {
                val s = (maxLead * 0.65f) / dist
                cx = screenX + dx * s
                cy = screenY + dy * s
            }
        }
        realInk.cursorX = cx
        realInk.cursorY = cy
        if (!realInk.held || v == null) {
            realInk.cursorR = 0f
            realInk.symCursorCount = 0
            if (holeCursorBoxValid) invalidateHoleCursor()
            return
        }
        val t = effTool()
        val cursorMode = if (t == Tool.ERASER) v.eraserCursorMode else v.brushCursorMode
        val style = v.cursorStyleMode
        realInk.cursorStyle = style
        val showWhileDrawing = cursorMode == 1 || cursorMode == 3
        if (!showWhileDrawing || style == 4 || (t != Tool.BRUSH && t != Tool.ERASER && t != Tool.SMUDGE) ||
            dynamicGuideAbortsFrame(v)
        ) {
            realInk.cursorR = 0f
            realInk.symCursorCount = 0
            if (holeCursorBoxValid) invalidateHoleCursor()
            return
        }
        val scale = (canvasZoom * canvasFitScale).coerceAtLeast(0.001f)
        val size = if (t == Tool.ERASER && tool != Tool.ERASER) {
            v.getToolEffectiveSize("eraser").toFloat()
        } else {
            v.brushSize.toFloat()
        }
        val sample = if (pressure.isFinite()) pressure.coerceIn(0f, 1f) else 1f
        smoothedCursorPressure =
            if (smoothedCursorPressure < 0f) sample else smoothedCursorPressure * 0.65f + sample * 0.35f
        realInk.cursorR = CursorOutlineGeometry.outlineRadius(
            size, scale, pressureFractionCached(smoothedCursorPressure), v.cursorOutlineReflectsActualSize,
        )

        // 记入对称镜像分支光标位置 (由前缓冲随墨一起在 GL 线程直出)
        if (v.drawingGuide.mode == GuideMode.SYMMETRY && v.drawingGuide.assistedDrawing) {
            ensureViewTransform()
            viewTransform.screenToDoc(cx, cy, pointScratch)
            val count = v.drawingGuide.writeSymmetricPoints(
                pointScratch[0], pointScratch[1],
                v.docWidth, v.docHeight,
                symScratchX, symScratchY
            )
            for (i in 0 until count) {
                viewTransform.docToScreen(symScratchX[i], symScratchY[i], pointScratch)
                realInk.symCursorX[i] = pointScratch[0]
                realInk.symCursorY[i] = pointScratch[1]
            }
            realInk.symCursorCount = count
        } else {
            realInk.symCursorCount = 0
        }
        if (realInk.strokeHoles != null || holeCursorBoxValid) invalidateHoleCursor()
    }

    /** 刷新缓存的视图变换 (参数未变化时内部直接返回, 几乎零开销) */
    internal fun ensureViewTransform(forcePublishPose: Boolean = false) {
        val v = vm
        // 瓦片网格生效时位图空间就是网格尺寸 (displayBitmap 此时可能是旧整图或参考窗口快照)
        val grid = v?.displayTiles?.drawable()?.grid
        val bmp = if (grid != null) null else (v?.displayBitmap ?: docBitmap)
        val bmpW = grid?.width ?: bmp?.width ?: v?.docWidth ?: 1
        val bmpH = grid?.height ?: bmp?.height ?: v?.docHeight ?: 1
        val dw = v?.docWidth ?: bmpW
        val dh = v?.docHeight ?: bmpH
        val dirty = viewTransform.update(
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
        if (dirty || forcePublishPose || realInk.pose == null) publishCanvasPose(bmpW, bmpH, force = forcePublishPose)
    }

    /** 与 onDraw 同一套位图→屏幕变换的快照, 渲染线程烤真墨时只读它。 */
    private fun publishCanvasPose(bmpW: Int, bmpH: Int, force: Boolean = false) {
        val bw = maxOf(1, bmpW)
        val bh = maxOf(1, bmpH)
        val pose = CanvasPose(
            centerX = viewW / 2f + canvasPanX,
            centerY = viewH / 2f + canvasPanY,
            scale = (canvasZoom * canvasFitScale).coerceAtLeast(0.001f),
            rotation = canvasRotation,
            signX = if (canvasFlipX) -1f else 1f,
            signY = if (canvasFlipY) -1f else 1f,
            halfW = bw / 2f,
            halfH = bh / 2f,
            bmpW = bw,
            bmpH = bh,
        )
        if (force && !realInk.held) {
            realInk.pose = pose
            return
        }
        val old = realInk.pose
        if (pose != old) {
            // 按住期间 pose 变了 (如 Compose 的 viewW/viewH/翻转在运笔中写进来): 四角屏幕位移 >= 0.5px 或尺寸变化才释放 (plan 四.4).
            if (realInk.held) {
                if (poseNeedsRelease(old, pose)) {
                    FbGeomLog.poseRelease("pose_changed", realInk.currentStrokeSeq, android.os.SystemClock.uptimeMillis())
                    realInk.releaseAndCatchUp()
                    realInk.pose = pose
                }
            } else {
                realInk.pose = pose
            }
        }
    }

    internal fun screenToDoc(screenPos: Offset): Offset {
        ensureViewTransform()
        viewTransform.screenToDoc(screenPos.x, screenPos.y, pointScratch)
        return Offset(pointScratch[0], pointScratch[1])
    }

    internal fun docToScreen(docPos: Offset): Offset {
        ensureViewTransform()
        viewTransform.docToScreen(docPos.x, docPos.y, pointScratch)
        return Offset(pointScratch[0], pointScratch[1])
    }

    /** 视口四角反变换到位图坐标后的包围盒 (供按视口裁剪绘制) */
    internal fun visibleBitmapBounds(out: IntArray) {
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
    internal fun resolveBrushColorCached(hex: String): Int {
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
    internal fun pressureFractionCached(p: Float): Float {
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

    internal fun scheduleLiquifyInvalidate() {
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
        val hideCursor = (effTool() == Tool.BRUSH || effTool() == Tool.ERASER || effTool() == Tool.SMUDGE || effTool() == Tool.LIQUIFY) &&
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
        val hideCursor = (effTool() == Tool.BRUSH || effTool() == Tool.ERASER || effTool() == Tool.SMUDGE || effTool() == Tool.LIQUIFY) &&
            v.cursorStyleMode != 4

        when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER,
            MotionEvent.ACTION_HOVER_MOVE,
            MotionEvent.ACTION_BUTTON_PRESS,
            MotionEvent.ACTION_BUTTON_RELEASE -> {
                val predPos = calculatePredictedHoverPos(event.x, event.y, event.eventTime)
                setLocalCursorPos(predPos.x, predPos.y)
                vm?.lastPointerScreenPosition = Offset(event.x, event.y)
                val docPt = screenToDoc(predPos)
                val isEnter = event.actionMasked == MotionEvent.ACTION_HOVER_ENTER
                updateCursorAngleFromDocMove(docPt.x, docPt.y, isEnter)
                val overUi = isHoverOverUi(event.x, event.y)
                localIsHovering = !overUi
                localIsTouching = false
                localPressure = 1f
                physicalEraserActive = (event.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER)

                // 悬空侧键: 按下/释放边沿时把事件交给驱动层状态机
                val hoverButton = event.buttonState
                val isButtonAction = event.actionMasked == MotionEvent.ACTION_BUTTON_PRESS ||
                        event.actionMasked == MotionEvent.ACTION_BUTTON_RELEASE
                if (hoverButton != 0 || lastHoverButtonState != 0 || isButtonAction) {
                    getOrCreateStylusDriver()?.onStylusMotionEvent(event)
                }
                lastHoverButtonState = hoverButton

                val prevTempErase = tempEraseActive
                tempEraseActive = getOrCreateStylusDriver()?.isSideButtonEraseActive(event) == true
                if (prevTempErase != tempEraseActive) {
                    invalidate()
                } else {
                    invalidateCursor(predPos)
                }

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
                tempEraseActive = false
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
            val action = event.actionMasked
            if (action == MotionEvent.ACTION_HOVER_MOVE ||
                action == MotionEvent.ACTION_HOVER_ENTER ||
                action == MotionEvent.ACTION_BUTTON_PRESS ||
                action == MotionEvent.ACTION_BUTTON_RELEASE
            ) {
                val predPos = calculatePredictedHoverPos(event.x, event.y, event.eventTime)
                setLocalCursorPos(predPos.x, predPos.y)
                vm?.lastPointerScreenPosition = Offset(event.x, event.y)
                val overUi = isHoverOverUi(event.x, event.y)
                localIsHovering = !overUi
                localIsTouching = false
                // 与 onHoverEvent 一致: 悬空侧键边沿接入驱动层 (部分 ROM 从此路径派发悬停与按键边沿)
                val hoverButton = event.buttonState
                val isButtonAction = action == MotionEvent.ACTION_BUTTON_PRESS ||
                        action == MotionEvent.ACTION_BUTTON_RELEASE
                if (hoverButton != 0 || lastHoverButtonState != 0 || isButtonAction) {
                    getOrCreateStylusDriver()?.onStylusMotionEvent(event)
                }
                lastHoverButtonState = hoverButton

                physicalEraserActive = (event.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER)
                val prevTempErase = tempEraseActive
                tempEraseActive = getOrCreateStylusDriver()?.isSideButtonEraseActive(event) == true
                if (prevTempErase != tempEraseActive) {
                    invalidate()
                } else {
                    invalidateCursor(predPos)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    val v = vm
                    val hideCursor = (effTool() == Tool.BRUSH || effTool() == Tool.ERASER || effTool() == Tool.SMUDGE || effTool() == Tool.LIQUIFY) &&
                        (v?.cursorStyleMode != 4)
                    val targetIcon = if (v?.isSpacePanning == true || effTool() == Tool.PAN) {
                        if (isSpaceDragging) systemGrabPointer ?: systemHandPointer else systemHandPointer ?: systemDefaultPointer
                    } else if (effTool() == Tool.ZOOM) {
                        systemZoomInPointer ?: systemDefaultPointer
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
        // 触摸派发里任何未捕获异常都会一路抛到 Android 输入管线: 输入线程不持有可重抛的
        // Java 栈, ART 在 MessageQueue::raiseAndClearException -> Thread::AssertNoPendingException
        // 处直接 abort 整个进程 —— 1.6.5 遥测 7db7fd4204 (SM-X700, SIGABRT) 就是这条栈,
        // 现场只有"载入工程 -> 切工具 -> 载入预设"三条面包屑, 看不出是谁抛的。
        // 兜住: 记日志 + 写一条 handled 报告给遥测, 画笔不至于把整进程带走。
        return try {
            handleTouchEvent(event)
        } catch (t: Throwable) {
            android.util.Log.e("ReverieTouch", "onTouchEvent threw: ${t}", t)
            runCatching {
                com.reverie.paint.core.CrashHandler.recordHandledException(context, t, "CanvasTouchView.onTouchEvent")
            }
            true
        }
    }

    /**
     * View 默认触摸实现的类内桥接: 拆分到扩展文件的 [handleTouchEvent] 无法直接写 `super`
     * (Kotlin 扩展函数没有 super), 由这里原样转发, 语义与直接调用 super 完全一致。
     */
    internal fun superOnTouchEvent(event: MotionEvent): Boolean = super.onTouchEvent(event)

    /** 同上: 拆到扩展文件的 [drawCanvas] 首行需要回落到 View 默认 onDraw 清屏。 */
    internal fun superOnDraw(canvas: Canvas) = super.onDraw(canvas)

    // ---------------- Phase 5 · C3-2: 场通路(拖动期零引擎解算 + 抬笔一次性提交) ----------------

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

    internal fun handleShapeDown(docPos: Offset) {
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

                val isBoxShape = state.type == ShapeType.RECT ||
                    state.type == ShapeType.ROUNDED_RECT ||
                    state.type == ShapeType.ELLIPSE
                if (isBoxShape) {
                    val p2Init = if (state.keepAspect) {
                        val pt = ShapeGeometry.constrainAspect(Point2D(state.p1.x, state.p1.y), Point2D(state.p2.x, state.p2.y))
                        Point2D(pt.x, pt.y)
                    } else Point2D(state.p2.x, state.p2.y)
                    val corners = ShapeGeometry.getBoxCornersDoc(Point2D(state.p1.x, state.p1.y), p2Init, state.rotationDegrees)
                    state.dragAnchorDoc = when (hit) {
                        ShapeHandleId.CORNER_TL -> Offset(corners[2].x, corners[2].y)
                        ShapeHandleId.CORNER_TR -> Offset(corners[3].x, corners[3].y)
                        ShapeHandleId.CORNER_BR -> Offset(corners[0].x, corners[0].y)
                        ShapeHandleId.CORNER_BL -> Offset(corners[1].x, corners[1].y)
                        else -> Offset.Zero
                    }
                }
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
                    val targetDoc = if (targetType == ShapeType.LINE) {
                        val sp = snapPointToPanelBorders(Point2D(docPos.x, docPos.y))
                        Offset(sp.x, sp.y)
                    } else docPos
                    state.reset(targetType, targetDoc)
                    if (state.strokeWidth <= 0f) {
                        state.strokeWidth = v.brushSize.toFloat().coerceIn(1f, 100f)
                    }
                    state.activeHandle = if (targetType == ShapeType.LINE) ShapeHandleId.LINE_P2 else ShapeHandleId.CORNER_BR
                    state.dragStartDocPos = targetDoc
                    state.dragAnchorDoc = targetDoc
                    state.dragP1 = targetDoc
                    state.dragP2 = targetDoc
                    state.isCreatingNewNode = false
                    shapeCreatedOnCurrentTouch = true
                }
            }
        } else {
            val targetType = defaultShapeType(tool, state.type)
            val targetDoc = if (targetType == ShapeType.LINE) {
                val sp = snapPointToPanelBorders(Point2D(docPos.x, docPos.y))
                Offset(sp.x, sp.y)
            } else docPos
            state.reset(targetType, targetDoc)
            if (state.strokeWidth <= 0f) {
                state.strokeWidth = v.brushSize.toFloat().coerceIn(1f, 100f)
            }
            if (targetType == ShapeType.POLYLINE || targetType == ShapeType.POLYGON || targetType == ShapeType.BEZIER) {
                state.activeHandle = ShapeHandleId.NODE_ANCHOR_BASE + 0
                state.selectedNodeIndex = 0
                state.dragStartDocPos = targetDoc
                state.isCreatingNewNode = true
                shapeCreatedOnCurrentTouch = false
                lastShapeTapTimeMs = now
                lastShapeTapDocPos = targetDoc
            } else {
                state.activeHandle = if (targetType == ShapeType.LINE) ShapeHandleId.LINE_P2 else ShapeHandleId.CORNER_BR
                state.dragStartDocPos = targetDoc
                state.dragAnchorDoc = targetDoc
                state.dragP1 = targetDoc
                state.dragP2 = targetDoc
                state.isCreatingNewNode = false
                shapeCreatedOnCurrentTouch = true
            }
        }
    }

    internal fun handleShapeMove(docPos: Offset) {
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
                handle == ShapeHandleId.CORNER_TR || handle == ShapeHandleId.CORNER_BL -> {
                    val (newP1, newP2) = ShapeGeometry.resizeBox(
                        anchorDoc = Point2D(state.dragAnchorDoc.x, state.dragAnchorDoc.y),
                        touchDoc = Point2D(docPos.x, docPos.y),
                        rotationDeg = state.rotationDegrees,
                        keepAspect = state.keepAspect,
                    )
                    state.p1 = Offset(newP1.x, newP1.y)
                    state.p2 = Offset(newP2.x, newP2.y)
                }
                handle == ShapeHandleId.CORNER_RADIUS -> {
                    val center = (state.dragP1 + state.dragP2) / 2f
                    val localPt = if (abs(state.rotationDegrees) > 0.01f) {
                        ShapeGeometry.rotatePoint(Point2D(docPos.x, docPos.y), Point2D(center.x, center.y), -state.rotationDegrees)
                    } else Point2D(docPos.x, docPos.y)
                    val localDocPos = Offset(localPt.x, localPt.y)
                    val minDim = minOf(abs(state.p2.x - state.p1.x), abs(state.p2.y - state.p1.y))
                    val dist = hypot(localDocPos.x - state.p1.x, localDocPos.y - state.p1.y)
                    state.cornerRadius = dist.coerceIn(0f, minDim / 2f)
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

    internal fun handleShapeUp(docPos: Offset) {
        val v = vm ?: return
        if (v.shapeState.active && v.shapeState.type == ShapeType.LINE) {
            val sp = snapPointToPanelBorders(Point2D(docPos.x, docPos.y))
            v.shapeState.p2 = Offset(sp.x, sp.y)
        }
        v.shapeState.activeHandle = ShapeHandleId.NONE
        v.shapeState.dragStartDocPos = Offset.Zero
        v.shapeState.isCreatingNewNode = false
        shapeCreatedOnCurrentTouch = false
    }

    internal fun cancelPendingShapeGesture() {
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
        cancelPendingGradientGesture()

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
        val targetW = TypographyEngine.resolveTargetWidth(cfg)
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

    private fun hitTestText(
        cfg: TypographyConfig,
        docPos: Offset,
        density: Float,
        currentScale: Float,
        brushOpacity: Double = 1.0,
    ): Boolean {
        val hitDist = (28f * density) / maxOf(0.01f, currentScale)
        val paint = TypographyEngine.createTextPaint(cfg, brushOpacity)
        val targetW = TypographyEngine.resolveTargetWidth(cfg)
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

        return localPos.x in (left - hitDist)..(right + hitDist) && localPos.y in (top - hitDist)..(bottom + hitDist)
    }

    private fun findHitText(
        docPos: Offset,
        density: Float,
        currentScale: Float,
    ): Pair<PaintViewModel.LayerUiState, TypographyConfig>? {
        val v = vm ?: return null
        val curLayer = v.layers.getOrNull(v.currentLayerIndex)

        // 1. 优先检测当前选中的图层 (若是文字图层)
        if (curLayer != null && curLayer.visible && !curLayer.locked) {
            val items = v.activeTextLayers[curLayer.id]
            if (items != null) {
                for (cfg in items.asReversed()) {
                    if (hitTestText(cfg, docPos, density, currentScale, v.brushOpacity)) {
                        return curLayer to cfg
                    }
                }
            }
        }

        // 2. 从上至下扫描其他可见且未锁定的文字图层
        for (layer in v.layers.asReversed()) {
            if (layer.id == curLayer?.id) continue
            if (!layer.visible || layer.locked) continue
            val items = v.activeTextLayers[layer.id] ?: continue
            for (cfg in items.asReversed()) {
                if (hitTestText(cfg, docPos, density, currentScale, v.brushOpacity)) {
                    return layer to cfg
                }
            }
        }
        return null
    }

    internal fun handleTextDown(docPos: Offset) {
        val v = vm ?: return
        val currentScale = maxOf(0.01f, canvasZoom * canvasFitScale)

        textTouchDownDocPos = docPos
        textSelectedBeforeDown = v.isTypographyEditing
        textDraggedDuringGesture = false

        if (v.isTypographyEditing) {
            val hit = hitTestTextHandle(v.typographyConfig, docPos, density, currentScale)
            if (hit != -1) {
                activeTextHandle = hit
                textDragStartDocPos = docPos
                textDragStartCfg = v.typographyConfig
                v.typographySnapGuides = emptyList()
                return
            }

            // 点击外部：若当前文本有内容，自动提交；若为空文本则取消编辑
            if (v.typographyConfig.text.isNotBlank()) {
                v.commitTypographyToCanvas()
            } else {
                v.cancelTypographyEditing()
            }
        }

        // 检查是否点中了画布上现存的某个文字块
        val hitEntry = findHitText(docPos, density, currentScale)
        if (hitEntry != null) {
            val (hitLayer, hitCfg) = hitEntry
            v.startEditingTextLayer(hitLayer.id, hitCfg.id)
            activeTextHandle = -10
            textDragStartDocPos = docPos
            textDragStartCfg = v.typographyConfig
            v.typographySnapGuides = emptyList()
            lastTextTapTimeMs = 0L
            return
        }

        // 若之前正在编辑文字，点击画布空白处已在上面提交，此时仅取消选中，不新建文字
        if (textSelectedBeforeDown) {
            activeTextHandle = -1
            lastTextTapTimeMs = 0L
            return
        }

        // 之前未选中任何文字且点击空白处：
        // 若当前选中的图层已经是文字图层，则直接在当前图层追加新文字块；否则准备新建独立文字图层
        val curLayer = v.layers.getOrNull(v.currentLayerIndex)
        val targetLayerId = if (curLayer != null && v.activeTextLayers.containsKey(curLayer.id)) {
            curLayer.id
        } else null

        v.startNewTextItem(targetLayerId, docPos)
        textDragStartDocPos = docPos
        textDragStartCfg = v.typographyConfig
        v.typographySnapGuides = emptyList()
        activeTextHandle = -1
        lastTextTapTimeMs = 0L
        onTextRequested?.invoke(docPos.x, docPos.y)
    }

    internal fun handleTextMove(docPos: Offset) {
        val v = vm ?: return
        if (!v.isTypographyEditing || activeTextHandle == -1) return

        val currentScale = maxOf(0.01f, canvasZoom * canvasFitScale)
        val tapSlop = (8f * density) / currentScale
        if (!textDraggedDuringGesture) {
            val moveDist = hypot(docPos.x - textTouchDownDocPos.x, docPos.y - textTouchDownDocPos.y)
            if (moveDist <= tapSlop) return
            textDraggedDuringGesture = true
        }

        when (activeTextHandle) {
            -10 -> {
                val delta = docPos - textDragStartDocPos
                val rawLeft = textDragStartCfg.posX + delta.x
                val rawTop = textDragStartCfg.posY + delta.y

                if (textDragStartCfg.snapEnabled) {
                    val paint = TypographyEngine.createTextPaint(textDragStartCfg, v.brushOpacity)
                    val targetW = TypographyEngine.resolveTargetWidth(textDragStartCfg)
                    val layout = TypographyEngine.createLayout(textDragStartCfg, paint, targetW)
                    val textW = layout.width.toFloat()
                    val textH = layout.height.toFloat()

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
                val targetW = TypographyEngine.resolveTargetWidth(textDragStartCfg)
                val layout = TypographyEngine.createLayout(textDragStartCfg, paint, targetW)
                val cx = textDragStartCfg.posX + layout.width / 2f
                val cy = textDragStartCfg.posY + layout.height / 2f
                val angleRad = atan2(docPos.y - cy, docPos.x - cx)
                val deg = Math.toDegrees(angleRad.toDouble()).toFloat() + 90f
                v.typographyConfig = textDragStartCfg.copy(rotationDeg = deg)
            }
            -30 -> {
                val deltaX = docPos.x - textDragStartDocPos.x
                val deltaY = docPos.y - textDragStartDocPos.y
                val localDelta = if (abs(textDragStartCfg.rotationDeg) > 0.01f) {
                    ShapeGeometry.rotatePoint(Point2D(deltaX, deltaY), Point2D(0f, 0f), -textDragStartCfg.rotationDeg)
                } else Point2D(deltaX, deltaY)

                if (textDragStartCfg.isVertical) {
                    val baseH = if (textDragStartCfg.boxHeight > 0f) {
                        textDragStartCfg.boxHeight
                    } else {
                        val paint = TypographyEngine.createTextPaint(textDragStartCfg, v.brushOpacity)
                        val targetW = TypographyEngine.resolveTargetWidth(textDragStartCfg)
                        TypographyEngine.createLayout(textDragStartCfg, paint, targetW).height.toFloat()
                    }
                    val minH = maxOf(24f, textDragStartCfg.fontSize * 1.2f)
                    val newH = maxOf(minH, baseH + localDelta.y)
                    v.typographyConfig = textDragStartCfg.copy(boxHeight = newH)
                } else {
                    val minW = maxOf(24f, textDragStartCfg.fontSize * 1.1f)
                    val newW = maxOf(minW, textDragStartCfg.boxWidth + localDelta.x)
                    v.typographyConfig = textDragStartCfg.copy(boxWidth = newW)
                }
            }
        }
    }

    internal fun handleTextUp(docPos: Offset) {
        val v = vm ?: return
        val now = SystemClock.uptimeMillis()
        val currentScale = maxOf(0.01f, canvasZoom * canvasFitScale)
        val tapDist = (24f * density) / currentScale

        if (textDraggedDuringGesture) {
            lastTextTapTimeMs = 0L
        } else if (activeTextHandle == -10 && v.isTypographyEditing) {
            // 仅在选中状态下发生干净双击时才弹出输入法
            if (textSelectedBeforeDown &&
                now - lastTextTapTimeMs < 350L &&
                hypot(docPos.x - lastTextTapDocPos.x, docPos.y - lastTextTapDocPos.y) < tapDist
            ) {
                onTextRequested?.invoke(v.typographyConfig.posX, v.typographyConfig.posY)
                lastTextTapTimeMs = 0L
            } else {
                lastTextTapTimeMs = now
                lastTextTapDocPos = docPos
            }
        }

        activeTextHandle = -1
        textDraggedDuringGesture = false
        textDragStartDocPos = Offset.Zero
        v.typographySnapGuides = emptyList()
    }

    private fun cancelPendingGradientGesture() {
        val v = vm ?: return
        val state = v.gradientState
        if (state.active) {
            if (gradientCreatedOnCurrentTouch) {
                state.clear()
            } else if (state.activeHandle != GradientHandleId.NONE) {
                state.p1 = state.dragOriginalP1
                state.p2 = state.dragOriginalP2
                state.activeHandle = GradientHandleId.NONE
            }
        }
        gradientCreatedOnCurrentTouch = false
    }

    private fun hitTestGradientHandle(
        state: GradientState,
        docPos: Offset,
        density: Float,
        currentScale: Float,
    ): Int {
        val hitDist = (28f * density) / maxOf(0.01f, currentScale)
        val lineVec = state.p2 - state.p1
        val lineLen = hypot(lineVec.x, lineVec.y)

        // 1. 中间色标手柄 (仅非动态自定义色标且色标数 > 2 时允许拖动中间手柄)
        if (lineLen > 10f && !state.isDynamicFgBg && !state.isDynamicFgTrans && state.stops.size > 2) {
            for (i in 1 until state.stops.size - 1) {
                val s = state.stops[i]
                val stopDocPos = state.p1 + lineVec * s.position
                if (hypot(docPos.x - stopDocPos.x, docPos.y - stopDocPos.y) < hitDist) {
                    return GradientHandleId.STOP_BASE + i
                }
            }
        }

        // 2. 起点手柄
        if (hypot(docPos.x - state.p1.x, docPos.y - state.p1.y) < hitDist) {
            return GradientHandleId.START
        }

        // 3. 终点手柄
        if (hypot(docPos.x - state.p2.x, docPos.y - state.p2.y) < hitDist) {
            return GradientHandleId.END
        }

        // 4. 中心手柄及轴线拖拽
        val mid = (state.p1 + state.p2) / 2f
        if (hypot(docPos.x - mid.x, docPos.y - mid.y) < hitDist) {
            return GradientHandleId.MID_BODY
        }
        if (lineLen > 10f) {
            val d = ShapeGeometry.distanceToSegment(
                Point2D(docPos.x, docPos.y),
                Point2D(state.p1.x, state.p1.y),
                Point2D(state.p2.x, state.p2.y)
            )
            if (d < hitDist) {
                return GradientHandleId.MID_BODY
            }
        }

        return GradientHandleId.NONE
    }

    internal fun handleGradientDown(docPos: Offset) {
        val v = vm ?: return
        val state = v.gradientState
        val currentScale = maxOf(0.01f, canvasZoom * canvasFitScale)

        if (state.active) {
            val hit = hitTestGradientHandle(state, docPos, density, currentScale)
            if (hit != GradientHandleId.NONE) {
                state.activeHandle = hit
                state.dragStartDocPos = docPos
                state.dragOriginalP1 = state.p1
                state.dragOriginalP2 = state.p2
                gradientCreatedOnCurrentTouch = false
                return
            }
            // 未命中现有手柄：先自动提交前序渐变
            val len = hypot(state.p2.x - state.p1.x, state.p2.y - state.p1.y)
            if (len > 4f) {
                v.commitActiveGradient()
            }
        }

        // 新建渐变
        state.reset(docPos, docPos)
        state.activeHandle = GradientHandleId.END
        state.dragStartDocPos = docPos
        state.dragOriginalP1 = docPos
        state.dragOriginalP2 = docPos
        gradientCreatedOnCurrentTouch = true
    }

    internal fun handleGradientMove(docPos: Offset) {
        val v = vm ?: return
        val state = v.gradientState
        if (!state.active) return

        when (val handle = state.activeHandle) {
            GradientHandleId.START -> {
                state.p1 = docPos
            }
            GradientHandleId.END -> {
                state.p2 = docPos
            }
            GradientHandleId.MID_BODY -> {
                val delta = docPos - state.dragStartDocPos
                state.p1 = state.dragOriginalP1 + delta
                state.p2 = state.dragOriginalP2 + delta
            }
            else -> {
                if (handle >= GradientHandleId.STOP_BASE) {
                    val idx = handle - GradientHandleId.STOP_BASE
                    if (idx in 1 until state.stops.size - 1) {
                        val lineVec = state.p2 - state.p1
                        val lenSq = lineVec.x * lineVec.x + lineVec.y * lineVec.y
                        if (lenSq > 1f) {
                            val toPos = docPos - state.p1
                            val proj = (toPos.x * lineVec.x + toPos.y * lineVec.y) / lenSq
                            val newPos = proj.coerceIn(0.01f, 0.99f)
                            val stop = state.stops[idx]
                            state.stops[idx] = stop.copy(position = newPos)
                        }
                    }
                }
            }
        }
    }

    internal fun handleGradientUp(docPos: Offset) {
        val v = vm ?: return
        val state = v.gradientState
        if (!state.active) return

        state.activeHandle = GradientHandleId.NONE
        if (gradientCreatedOnCurrentTouch) {
            gradientCreatedOnCurrentTouch = false
            val len = hypot(state.p2.x - state.p1.x, state.p2.y - state.p1.y)
            if (len < 4f) {
                val defSpan = 150f / maxOf(0.1f, canvasZoom * canvasFitScale)
                state.p2 = state.p1 + Offset(defSpan, defSpan)
            }
        }
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
