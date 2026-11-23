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
import com.reverie.paint.R
import com.reverie.paint.core.*
import com.reverie.paint.core.stylus.FrontBufferProbe
import com.reverie.paint.core.stylus.UniversalKalmanPredictor
import com.reverie.paint.model.*
import com.reverie.paint.ui.theme.parseColor
import android.graphics.PorterDuff
import com.reverie.paint.perf.FbGeomLog
import com.reverie.paint.perf.PerfHud
import kotlin.math.hypot

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
internal const val EVADE_ENTER_DP = 32f

/** 50% 面板避让：离开半径 = 40dp + 笔刷屏幕半径（32dp + 8dp 双向滞后）。 */
internal const val EVADE_EXIT_DP = 40f

/** 抬笔后保持 50% 的延续窗口，消除高频排线/点画时的 50% <-> 100% 闪烁。 */
internal const val POST_UP_CONTINUATION_MS = 80L

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
    internal var shapeCreatedOnCurrentTouch = false
    internal var gradientCreatedOnCurrentTouch = false
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
    internal val composeDecorRecorder by lazy(LazyThreadSafetyMode.NONE) { FrontBufferDecorRecorder(this) }
    internal var composeDecor: FrontBufferDecor? = null
    internal var localPressure = 1f
    internal var localCursorAngle = 0f
    internal var lastCursorMoveDocX = Float.NaN
    internal var lastCursorMoveDocY = Float.NaN

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
    internal var barrelRotationSupported: Boolean? = null
    internal var barrelRotationDeviceId: Int = -1

    /** 已打印过能力清单的设备 id (每台设备一次, 避免热路径刷屏) */
    internal var loggedStylusDeviceId: Int = Int.MIN_VALUE

    // ---- 笔画有效倾角: 硬件倾角直通 (手指/零倾角笔为 0, 与 Krita 无倾角输入一致) ----
    // touchTiltX/Y 来自 updateStylusSensors; strokeTiltX/Y 是笔画采样真正下发的倾角。
    // 单位: 度, 幅值 = 自垂直倾角 (AXIS_TILT 口径), 范围 [-60,60]。
    internal var strokeTiltX: Double = 0.0
    internal var strokeTiltY: Double = 0.0

    /** 最近一次真实输入压感 (原始, 未过校准): 抬笔收尾补点的"真实末端压感"来源 */
    internal var lastStrokeRawPressure: Float = Float.NaN

    // 文本交互状态
    internal var activeTextHandle: Int = -1
    internal var textDragStartDocPos: Offset = Offset.Zero
    internal var textDragStartCfg: TypographyConfig = TypographyConfig()
    internal var lastTextTapTimeMs: Long = 0L
    internal var lastTextTapDocPos: Offset = Offset.Zero
    internal var textTouchDownDocPos: Offset = Offset.Zero
    internal var textSelectedBeforeDown: Boolean = false
    internal var textDraggedDuringGesture: Boolean = false

    // 形状交互状态
    internal var lastShapeTapTimeMs: Long = 0L
    internal var lastShapeTapDocPos: Offset = Offset.Zero

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
    internal val transformWriterGuard = CanvasTransformWriterGuard()

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

    // STAMP 笔画级位图缓存引用 (单笔笔画期间复用, 避免在 120Hz/240Hz MOVE 热路径重复查表与分配 Key)
    internal var predictedPressure: Float = 1f
    internal val tempScalarPredPoint = FloatArray(3)

    // ---- 前缓冲 UI 裁剪快照 (Phase 1) ----
    // 所有面板的实测 bounds 由 Compose 侧 reportUiSlot 写入 vm.panelOcclusion;
    // 这里只在槽位版本变化/DOWN/arm/onSizeChanged 时构建不可变快照并 @Volatile 发布。
    // 渲染线程只读快照, 不接触任何可变 RectF。
    @Volatile
    internal var uiClipSnapshot: UiClipSnapshot = UiClipSnapshot.EMPTY
    internal var lastUiClipVersion: Int = -1

    // tracker.onChanged 在 UI 线程触发, 但 KDoc 禁止回调内同步重入 tracker;
    // 这里只置位并 post 一次, 同一帧内的多次槽位变化合并为一次快照重建。
    @Volatile
    internal var snapshotRebuildPending = false
    internal val snapshotRebuildRunnable = Runnable {
        snapshotRebuildPending = false
        rebuildUiExclusionSnapshot()
    }

    /** 正常抬笔后的 80ms 延续窗口；下一笔落点仍在 40dp 内则无缝保持 50%。 */
    internal val clearEvasionAfterUpRunnable = Runnable {
        if (!strokeStarted) {
            vm?.let { clearAllEvasionState(it) }
        }
    }

    internal val tipShaderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    // 物理触控采样环形缓冲区 (容量 64，热路径零分配)，用于提取已上屏真墨前沿并回填滞后空窗
    internal val touchHistoryX = FloatArray(64)
    internal val touchHistoryY = FloatArray(64)
    internal val touchHistoryTime = LongArray(64)
    internal var touchHistoryCount = 0
    internal var touchHistoryHead = 0

    // --- 低延迟预测光标与局部重绘状态 ---
    private var activePredictedTipScreenX = 0f
    private var activePredictedTipScreenY = 0f
    private var hasActivePredictedTip = false

    // 悬停运动前瞻 (1 帧 VSYNC 估算，空中挥笔如影随形)
    internal var lastHoverRawX = 0f
    internal var lastHoverRawY = 0f
    internal var lastHoverEventTimeMs = 0L

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
    internal val pixelScratch = IntArray(1)
    private val frontierPointScratch = FloatArray(2)
    @Volatile internal var lastInvalidateFromRenderUptimeNs = 0L
    internal var smoothedLeadMs = 20f

    /** 画笔颜色的解析缓存 (brushColor 是字符串, 每帧 parseColor 纯属浪费) */
    internal var cachedColorHex: String? = null
    internal var cachedColorInt: Int = android.graphics.Color.BLACK

    /** 压力曲线查表的量化缓存 (JNI 调用; 落笔期间压力连续但帧间变化很小) */
    internal var cachedPressureKey: Int = -1
    internal var cachedPressureFraction: Float = 1f

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

    internal val holeCursorPainter by lazy(LazyThreadSafetyMode.NONE) { HoldCursorPainter(density) }
    internal val holeCursorBox = IntArray(4)
    internal val holeCursorDstIn = Paint().apply {
        xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN)
        isFilterBitmap = false
    }

    // 上一次因光标压孔而失效的屏幕框 (noteHoldCursor 用来擦掉旧位置)
    internal var holeCursorBoxValid = false
    internal var holeCursorL = 0
    internal var holeCursorT = 0
    internal var holeCursorR = 0
    internal var holeCursorB = 0

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
    internal var strokeFbReady = false
    internal var strokeFbDecision = FrontBufferReadyGate.NOT_READY
    internal var strokeFbMask = 0

    internal val holdGuideSeg = FloatArray(DynamicGuidePainter.SEG_SIZE)
    internal var holdGuidePrevKind = DynamicGuidePainter.KIND_NONE
    internal val holdGuidePrevSeg = FloatArray(DynamicGuidePainter.SEG_SIZE)
    internal val holdGuidePrevBox = IntArray(4)
    internal val holdGuideBox = IntArray(4)
    internal val holdGuideScratch = FloatArray(4)

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
