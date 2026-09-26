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
    var tfState: TransformState? = null
    var docBitmap: Bitmap? = null

    private var cachedDriver: com.reverie.paint.core.stylus.StylusDriver? = null

    // S Pen 悬停期间侧键状态追踪: 仅在按钮按下/释放边沿把 hover 事件喂给驱动层,
    // 避免每次悬停移动都走按钮状态机 (热路径零分配: 只做位比较, 无对象创建)。
    private var lastHoverButtonState = 0

    // 纸张摩擦音效的速度追踪 (文档坐标); lastSoundTimeMs == 0L 表示笔画尚无历史点
    private var lastSoundDocPos = Offset.Zero
    private var lastSoundTimeMs = 0L

    // 侧键按住=临时橡皮 (Samsung Notes 语义): 仅在笔接触的事件流中更新, 抬笔后由下一次落笔重判
    private var tempEraseActive = false

    /** 绘画路径的生效工具: 侧键按住时强制橡皮, 其余时刻跟随 UI 工具。 */
    private fun effTool(): Tool = if (tempEraseActive) Tool.ERASER else tool

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

    var onTransform: ((zoom: Float, rotation: Float, panX: Float, panY: Float) -> Unit)? = null
    var onTextRequested: ((x: Float, y: Float) -> Unit)? = null
    var onPolyPoint: ((Offset) -> Unit)? = null
    var onCropRect: ((Rect?) -> Unit)? = null

    var liveShapeStart: MutableState<Offset?>? = null
    var liveShapeEnd: MutableState<Offset?>? = null
    var livePressure: MutableState<Float>? = null
    var measureStart: MutableState<Offset?>? = null
    var measureEnd: MutableState<Offset?>? = null
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
    var liquifyBrushSize: Float = 60f

    /** True while any full-screen overlay panel is open (see isHoverOverUi). */
    @Volatile var overlayPanelsOpen: Boolean = false
    var liquifyMode: Int = 0

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

    // 本地硬件光标状态 (0 Compose 开销)
    private var localCursorPos: Offset? = null
    private var localIsHovering = false
    private var localIsTouching = false
    private var localPressure = 1f

    // 硬件加速直出渲染 Paint
    var checkerboardPaint: Paint? = null
    private val shadowPaint = Paint().apply {
        color = android.graphics.Color.argb(0x80, 0, 0, 0)
    }
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

    // 绘制状态 (手写笔专属)
    private var strokeStarted = false
    private var firstDocPos = Offset.Zero
    private var shapeEndDocPos = Offset.Zero
    private var previousSinglePos = Offset.Zero
    private val lassoPoints = mutableListOf<Offset>()
    private var lastLassoPreviewNs = 0L
    // Phase 3A/3B: 液化交互态会话(latest-state-wins 状态机 + backlog 计数), 取代原先散落的
    // liquifyPrevPos / liquifyPendingTo / liquifyInputSinceFlush / liquifyMaxDabsPerFlush 字段。
    private val liquifySession = LiquifyInteractionSession()
    private var liquifyFlushPosted = false
    private val liquifyFlushRunnable = Runnable { flushLiquifyPending() }
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
        val docW = docBitmap?.width ?: vm?.docWidth ?: 1
        val docH = docBitmap?.height ?: vm?.docHeight ?: 1
        val halfW = docW / 2f
        val halfH = docH / 2f
        val p = Path().apply {
            moveTo(points[0].x - halfW, points[0].y - halfH)
            for (j in 1 until points.size) {
                lineTo(points[j].x - halfW, points[j].y - halfH)
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

    // 跨碎片延迟重置任务
    private val resetTransformRunnable = Runnable {
        isTransformActive = false
        isPinchMotion = false
        isInteracting = false
        maxTouchPointers = 0
        lastPos0 = Offset.Zero
        lastPos1 = Offset.Zero
    }

    // 防抖撤销任务与 Procreate 风格连续撤销/重做
    private var pendingUndoRunnable: Runnable? = null
    private var isContinuousUndoing = false

    private val continuousUndoRunnable = object : Runnable {
        override fun run() {
            val v = vm ?: return
            if (maxTouchPointers == 2 && !isPinchMotion && isInteracting) {
                isContinuousUndoing = true
                v.undo()
                postDelayed(this, 110L)
            }
        }
    }

    private val continuousRedoRunnable = object : Runnable {
        override fun run() {
            val v = vm ?: return
            if (maxTouchPointers >= 3 && !isPinchMotion && isInteracting) {
                isContinuousUndoing = true
                v.redo()
                postDelayed(this, 110L)
            }
        }
    }

    // 画布平滑复位动画 (Procreate Smooth Reset Animation)
    private var fitAnimator: android.animation.ValueAnimator? = null

    private fun animateFitCanvas() {
        fitAnimator?.cancel()
        val startZoom = canvasZoom
        val startRot = canvasRotation
        val startPanX = canvasPanX
        val startPanY = canvasPanY

        val animator = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 240L
            interpolator = android.view.animation.DecelerateInterpolator(1.8f)
            addUpdateListener { anim ->
                val f = anim.animatedFraction
                canvasZoom = startZoom + (1f - startZoom) * f
                canvasRotation = startRot + (0f - startRot) * f
                canvasPanX = startPanX + (0f - startPanX) * f
                canvasPanY = startPanY + (0f - startPanY) * f
                onTransform?.invoke(canvasZoom, canvasRotation, canvasPanX, canvasPanY)
                invalidate()
            }
        }
        fitAnimator = animator
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
            }
            isLongPressPickerActive = true
            pickerActive?.value = true
            val refHex = v.brushColor
            pickerInitialColor?.value = parseColor(refHex)
            sampleColorAtScreenPos(pendingDownScreenPos)
        }
    }

    // ---- 硬件笔尖前向超前预测 (OEM Hardware Motion Prediction) ----
    private var oplusPredictor: OplusMotionPredictor? = null
    private var androidMotionPredictor: Any? = null
    private var predictedScreenPoint: Offset? = null
    private var predictedPressure: Float = 1f
    private val cachedTouchPointInfo = OplusTouchPointInfo()
    private val tipShaderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    // 预分配多指触控索引缓冲区 (热路径零分配 §4)
    private val fingerIndices = IntArray(16)
    private var fingerCount = 0

    // ---- 对称与透视绘图辅助 (Drawing Assist) ----
    // 镜像笔迹采样缓冲: 每个分支一段扁平的 [x, y, pressure] 三元组, 容量按需翻倍。
    // 旧实现每个采样点 new 一个 SymStrokeSample 再 add 进 MutableList —— 对称绘制
    // 最多 7 个分支, 等于每帧几十次对象分配加列表扩容; 现在整条笔迹零分配。
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

    // ---- 视图变换缓存与热路径零分配暂存区 (AGENTS.md §4) ----
    // 绘制覆盖层时每个点都要做一次 doc->screen, 旧实现每次现算三角函数; 缓存
    // 视图参数后整条笔迹共用一份变换, 并且只往复用数组里写结果, 全程零分配。
    private val viewTransform = CanvasViewTransform()
    private val pointScratch = FloatArray(2)
    private val boundsScratch = IntArray(4)
    private val pixelScratch = IntArray(1)

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
        val guide = v.drawingGuide
        if (guide.mode != GuideMode.SYMMETRY) return emptyList()
        val cx = v.docWidth * guide.symmetryCenterX
        val cy = v.docHeight * guide.symmetryCenterY
        return when (guide.symmetryType) {
            SymmetryType.VERTICAL -> listOf(Point2D(2f * cx - docPt.x, docPt.y))
            SymmetryType.HORIZONTAL -> listOf(Point2D(docPt.x, 2f * cy - docPt.y))
            SymmetryType.QUADRANT -> listOf(
                Point2D(2f * cx - docPt.x, docPt.y),
                Point2D(docPt.x, 2f * cy - docPt.y),
                Point2D(2f * cx - docPt.x, 2f * cy - docPt.y),
            )
            SymmetryType.RADIAL -> {
                val dx = docPt.x - cx
                val dy = docPt.y - cy
                val r = hypot(dx, dy)
                val baseAngle = atan2(dy, dx)
                val branches = ArrayList<Point2D>(7)
                for (k in 1..7) {
                    val ang = baseAngle + k * (2f * PI.toFloat() / 8f)
                    branches.add(Point2D(cx + r * cos(ang), cy + r * sin(ang)))
                }
                branches
            }
        }
    }

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

                // Candidate 1: horizontal horizon line
                var bestCandidate = Offset(currentPt.x, firstPt.y)
                var minError = abs(dy)

                // Candidate 2: vertical wall line (for 3-point perspective)
                val vertError = abs(dx)
                if (vertError < minError) {
                    minError = vertError
                    bestCandidate = Offset(firstPt.x, currentPt.y)
                }

                // Candidate rays to each vanishing point
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
                        }
                    }
                }
                bestCandidate
            }
            else -> currentPt
        }
    }

    private var draggingGuideHandleIndex = -1 // -1: none, 100: symmetry center, 0..N: VP index


    private val systemNullPointer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        PointerIcon.getSystemIcon(context, PointerIcon.TYPE_NULL)
    } else null

    private val systemDefaultPointer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        PointerIcon.getSystemIcon(context, PointerIcon.TYPE_DEFAULT)
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

        val d = density
        // 顶部操作栏区域 (右上角，宽约 380dp，高约 56dp)
        if (y <= 56f * d && x >= width - 380f * d) return true

        // 左侧快捷工具栏 (宽 56dp)
        if (x <= 56f * d) return true

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

        return false
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
        if (oplusPredictor == null) {
            try {
                val p = OplusMotionPredictor()
                if (p.isValid) {
                    p.setRefreshRate(maxFps)
                    val dm = resources.displayMetrics
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
        if (oplusPredictor == null && Build.VERSION.SDK_INT >= 34 && androidMotionPredictor == null) {
            try {
                androidMotionPredictor = android.view.MotionPredictor(context)
            } catch (_: Throwable) {}
        }
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
        oplusPredictor?.let { p ->
            try {
                if (p.isValid) p.setRefreshRate(maxFps)
            } catch (_: Throwable) {}
        }
        if (Build.VERSION.SDK_INT >= 34) {
            try {
                val method = View::class.java.getMethod("setFrameRate", java.lang.Float.TYPE, java.lang.Integer.TYPE)
                method.invoke(this, maxFps, 1) // 1 = Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE
            } catch (_: Throwable) {}
        }
    }

    fun checkAndRestoreHighRefreshRate() {
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
        }
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible) {
            applyHighRefreshRateAndUnbuffered()
            checkAndRestoreHighRefreshRate()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(longPressRunnable)
        isPendingLongPress = false
        isLongPressPickerActive = false
        longPressToken++
        if (activeTouchView == this) activeTouchView = null
        // 离开绘画页: 喷枪是挂在渲染线程上的自续定时链, 不停掉会让页面之外
        // 仍周期渲染并保持笔画事务开启; 未投递的笔画样本与起笔 kick 一并丢弃
        vm?.stopAirbrush()
        vm?.disarmStrokeStartKick()
        vm?.clearPendingStrokeSamples()
        cachedDriver?.feedbackManager?.setWritingHapticsEnabled(false)
        cachedDriver?.feedbackManager?.stopStrokeSound()
        cachedDriver = null
        oplusPredictor?.destroy()
        oplusPredictor = null
        androidMotionPredictor = null
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

            // 绘制底座投影
            canvas.save()
            canvas.translate(centerX + 8f, centerY + 8f)
            canvas.rotate(canvasRotation)
            canvas.scale(scale, scale)
            canvas.drawRect(-imgW / 2f, -imgH / 2f, imgW / 2f, imgH / 2f, shadowPaint)
            canvas.restore()

            canvas.save()
            canvas.translate(centerX, centerY)
            canvas.rotate(canvasRotation)
            canvas.scale(scale, scale)

            // 绘制透明棋盘格
            checkerboardPaint?.let { cb ->
                canvas.drawRect(-imgW / 2f, -imgH / 2f, imgW / 2f, imgH / 2f, cb)
            }

            // 绘制真实画布像素
            directBitmapPaint.isFilterBitmap = v.magnificationInterpolation
            directBitmapPaint.isAntiAlias = v.magnificationInterpolation
            canvas.drawBitmap(bmp, -imgW / 2f, -imgH / 2f, directBitmapPaint)

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
            LiquifyGpuPreview.draw(canvas, viewTransform)
        }

        // 标尺的液化网格可视化(debug 专属; release 侧 PerfHud 为恒 false 的空实现, 不进这个分支):
        // 把 Krita 网格的"原始点 → 位移后点"画成箭头, 用于在实现 Preview 之前确认
        // 网格几何、位移方向与文档→屏幕映射与最终结果一致。
        if (PerfHud.gridOverlayEnabled) {
            ensureViewTransform()
            PerfHud.drawLiquifyGrid(canvas, viewTransform)
        }

        // =========================================================================
        // 1.5 硬件笔尖前向超前预测延伸 (OEM Hardware Stroke Prediction)
        // 实时预测未来 15~20ms 笔尖切线，微羽化延伸消除 144Hz 屏幕 1~2 帧物理上屏延迟
        // 遵循画世界 Pro 规范：自动排斥带材质/颗粒/低透明度笔刷，限制极短微切线并平滑渐隐
        // =========================================================================
        val predPt = predictedScreenPoint
        val curPos = localCursorPos
        val isDrawingTool = tool == Tool.BRUSH || tool == Tool.ERASER
        if (v.isCurrentBrushPredictionEligible && localIsTouching && isDrawingTool && predPt != null && curPos != null) {
            try {
                val dx = predPt.x - curPos.x
                val dy = predPt.y - curPos.y
                val dist = hypot(dx, dy)
                val maxDistPx = 14f * density
                val minDistPx = 2.5f * density
                if (dist in minDistPx..(maxDistPx * 3.5f) && predictedPressure > 0.05f) {
                    val prevPos = previousSinglePos
                    var angleOk = true
                    if (prevPos != Offset.Zero) {
                        val v1x = curPos.x - prevPos.x
                        val v1y = curPos.y - prevPos.y
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
                        val endX = curPos.x + (dx / dist) * clampDist
                        val endY = curPos.y + (dy / dist) * clampDist

                        val scale = (canvasZoom * canvasFitScale).coerceAtLeast(0.001f)
                        val cursorBrushSize = v.brushSize.toFloat()
                        val pFrac = if (v.brushPressureEnabled) pressureFractionCached(predictedPressure) else 1f
                        val strokeWidth = (cursorBrushSize * scale * pFrac).coerceAtLeast(1.5f)

                        val isEraser = tool == Tool.ERASER
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
                            curPos.x, curPos.y, endX, endY,
                            startColor, endColor,
                            android.graphics.Shader.TileMode.CLAMP
                        )
                        canvas.drawLine(curPos.x, curPos.y, endX, endY, tipShaderPaint)
                    }
                }
            } catch (_: Throwable) {}
        }

        // =========================================================================
        // 2. 绘画中实时镜像笔迹绘制 (120Hz 零延迟 GPU Canvas 渲染)
        // =========================================================================
        if (v.drawingGuide.mode == GuideMode.SYMMETRY && v.drawingGuide.assistedDrawing && localIsTouching && mirrorBranchHasSamples()) {
            try {
                ensureViewTransform()
                mirroredDrawPaint.color = resolveBrushColorCached(v.brushColor)
                mirroredDrawPaint.strokeWidth =
                    (v.brushSize.toFloat() * viewTransform.currentScale).coerceAtLeast(1.5f)
                // 整条笔迹共用一份视图变换, 逐点只读扁平缓冲并写复用数组
                // (旧实现每个点现算三角函数 + 每段重复变换前一点)
                for (b in 0 until mirroredSamples.size) {
                    val buf = mirroredSamples[b]
                    val n = mirroredSizes[b]
                    if (n < 2) continue
                    viewTransform.docToScreen(buf[0], buf[1], pointScratch)
                    var prevX = pointScratch[0]
                    var prevY = pointScratch[1]
                    for (i in 1 until n) {
                        val base = i * 3
                        viewTransform.docToScreen(buf[base], buf[base + 1], pointScratch)
                        canvas.drawLine(prevX, prevY, pointScratch[0], pointScratch[1], mirroredDrawPaint)
                        prevX = pointScratch[0]
                        prevY = pointScratch[1]
                    }
                }
            } catch (_: Exception) {}
        }

        // =========================================================================
        // 3. 光标及对称参考线绘制 (Cursor & Guides)
        // 依据用户的光标模式设置独立渲染
        // =========================================================================
        if (v.brushStudioOpen || v.moreSettingsOpen || overlayPanelsOpen) return
        val pos = localCursorPos ?: return
        val isEraser = tool == Tool.ERASER
        val cursorMode = if (isEraser) v.eraserCursorMode else v.brushCursorMode
        // 0: 不显示, 1: 绘画时显示, 2: 悬空显示, 3: 绘画和悬空显示
        val shouldShow = when (cursorMode) {
            1 -> localIsTouching
            2 -> localIsHovering
            3 -> localIsTouching || localIsHovering
            else -> false
        }
        val isDrawTool = tool == Tool.BRUSH || tool == Tool.ERASER || tool == Tool.SMUDGE || tool == Tool.LIQUIFY
        // Phase 2: 记录本帧光标环的屏幕位置与半径 —— 下一帧做局部失效时要把环的"旧位置"也覆盖掉
        lqRingValid = false
        if (shouldShow && isDrawTool && v.cursorStyleMode != 4) {
            val scale = (canvasZoom * canvasFitScale).coerceAtLeast(0.001f)
            val cursorBrushSize = if (tool == Tool.LIQUIFY) liquifyBrushSize else v.brushSize.toFloat()
            val pressureFraction =
                if (localIsTouching) {
                    pressureFractionCached(localPressure)
                } else 1f
            val brushRadiusScreen = (cursorBrushSize * scale * 0.5f * pressureFraction).coerceAtLeast(2f)
            // 半径取"环半径"与"十字/点光标尺寸"的较大者: 后三种样式画的是小十字/圆点, 半径只有
            // 几个 dp, 但同样需要被覆盖
            lqRingValid = true
            lqRingCx = pos.x
            lqRingCy = pos.y
            lqRingR = if (brushRadiusScreen > 8f * density) brushRadiusScreen else 8f * density

            when (v.cursorStyleMode) {
                0 -> { // 双对比圆环
                    canvas.drawCircle(pos.x, pos.y, brushRadiusScreen + 0.8f, cursorPaintBlack)
                    canvas.drawCircle(pos.x, pos.y, brushRadiusScreen, cursorPaintWhite)
                }
                1 -> { // 十字准星 (极细发丝相交线)
                    val len = 7f * density
                    canvas.drawLine(pos.x - len, pos.y, pos.x + len, pos.y, crosshairPaintBlack)
                    canvas.drawLine(pos.x, pos.y - len, pos.x, pos.y + len, crosshairPaintBlack)
                    canvas.drawLine(pos.x - len, pos.y, pos.x + len, pos.y, crosshairPaintWhite)
                    canvas.drawLine(pos.x, pos.y - len, pos.x, pos.y + len, crosshairPaintWhite)
                }
                2 -> { // 精确点
                    canvas.drawCircle(pos.x, pos.y, 3f * density, cursorPaintBlack)
                    canvas.drawCircle(pos.x, pos.y, 1.8f * density, cursorPaintWhite)
                }
                5 -> { // 圆 + 十字
                    canvas.drawCircle(pos.x, pos.y, brushRadiusScreen + 0.8f, cursorPaintBlack)
                    canvas.drawCircle(pos.x, pos.y, brushRadiusScreen, cursorPaintWhite)
                    val len = 4.5f * density
                    canvas.drawLine(pos.x - len, pos.y, pos.x + len, pos.y, crosshairPaintBlack)
                    canvas.drawLine(pos.x, pos.y - len, pos.x, pos.y + len, crosshairPaintBlack)
                    canvas.drawLine(pos.x - len, pos.y, pos.x + len, pos.y, crosshairPaintWhite)
                    canvas.drawLine(pos.x, pos.y - len, pos.x, pos.y + len, crosshairPaintWhite)
                }
            }

            // 对称辅助光标镜像绘制 (支持垂直/水平/四象限/径向多分支)
            if (v.drawingGuide.mode == GuideMode.SYMMETRY && v.drawingGuide.assistedDrawing) {
                val docPt = screenToDoc(pos)
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
            boundsScratch,
        )
        val left = boundsScratch[0].coerceAtLeast(0)
        val top = boundsScratch[1].coerceAtLeast(0)
        val right = boundsScratch[2].coerceAtMost(viewW)
        val bottom = boundsScratch[3].coerceAtMost(viewH)
        if (right <= left || bottom <= top) {
            postInvalidate()
            return
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
        if (localCursorPos != null || localIsTouching || localIsHovering) return false
        if (predictedScreenPoint != null) return false
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
        val cur = localCursorPos
        if (cur != null) {
            val r = if (lqRingValid) lqRingR else 0f
            left = minOf(left, cur.x - r - 2f)
            top = minOf(top, cur.y - r - 2f)
            right = maxOf(right, cur.x + r + 2f)
            bottom = maxOf(bottom, cur.y + r + 2f)
        }
        if (right <= left || bottom <= top) {
            // 这一帧位移场没变、环也没动: 连重绘都不需要
            return
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
        return super.dispatchTouchEvent(event)
    }

    fun onDirectHover(localX: Float, localY: Float, actionMasked: Int) {
        val v = vm ?: return
        val hideCursor = (tool == Tool.BRUSH || tool == Tool.ERASER || tool == Tool.SMUDGE || tool == Tool.LIQUIFY) &&
            v.cursorStyleMode != 4

        when (actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> {
                localCursorPos = Offset(localX, localY)
                val overUi = isHoverOverUi(localX, localY)
                localIsHovering = !overUi
                localIsTouching = false
                localPressure = 1f
                invalidate()

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
                localIsHovering = false
                localIsTouching = false
                localCursorPos = null
                invalidate()

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    if (systemDefaultPointer != null && pointerIcon != systemDefaultPointer) {
                        pointerIcon = systemDefaultPointer
                    }
                }
            }
        }
    }

    override fun dispatchHoverEvent(event: MotionEvent): Boolean {
        if (isInteracting || isTransformActive) {
            localCursorPos = Offset(event.x, event.y)
            invalidate()
            return true
        }
        return super.dispatchHoverEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (isInteracting || isTransformActive) {
            if (event.actionMasked == MotionEvent.ACTION_HOVER_MOVE) {
                localCursorPos = Offset(event.x, event.y)
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
                localCursorPos = Offset(event.x, event.y)
                val overUi = isHoverOverUi(event.x, event.y)
                localIsHovering = !overUi
                localIsTouching = false
                localPressure = 1f
                invalidate()

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
                if (lastHoverButtonState != 0) {
                    getOrCreateStylusDriver()?.onStylusHoverExited()
                    lastHoverButtonState = 0
                }
                localIsHovering = false
                localIsTouching = false
                localCursorPos = null
                invalidate()

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
                localCursorPos = Offset(event.x, event.y)
                val overUi = isHoverOverUi(event.x, event.y)
                localIsHovering = !overUi
                localIsTouching = false
                invalidate()

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
        }
        return super.onGenericMotionEvent(event)
    }

    // -------------------------------------------------------------
    // 2. 接触触控处理 (手写笔落笔绘画 vs 手指画布导航)
    // -------------------------------------------------------------
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val v = vm ?: return super.onTouchEvent(event)
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
                checkAndRestoreHighRefreshRate()
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
            if (toolType == MotionEvent.TOOL_TYPE_STYLUS || toolType == MotionEvent.TOOL_TYPE_ERASER) {
                stylusPointerIndex = i
            } else {
                if (fingerCount < fingerIndices.size) {
                    fingerIndices[fingerCount++] = i
                }
            }
        }

        // 手写笔触控判定：存在手写笔 Pointer 且没有 2 根及以上手指在做手势导航
        val isStylusTouch = stylusPointerIndex >= 0 && fingerCount < 2

        // 侧键按住=临时橡皮 (Samsung Notes 语义): 每个笔接触事件重判, 纯手指路径清残留
        if (isStylusTouch) {
            tempEraseActive = getOrCreateStylusDriver()?.isSideButtonEraseActive(event) == true
        } else if (fingerCount > 0) {
            tempEraseActive = false
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
            val pressure = event.getPressure(stylusPointerIndex).coerceIn(0f, 1f)

            localCursorPos = screenPos
            localIsTouching = true
            localIsHovering = false
            localPressure = pressure

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
                MotionEvent.ACTION_DOWN -> {
                    try {
                        oplusPredictor?.reset()
                    } catch (_: Throwable) {}
                    predictedScreenPoint = null
                    removeCallbacks(longPressRunnable)
                    longPressToken++
                    isTransformActive = false
                    isPinchMotion = false
                    isInteracting = true
                    lastPos0 = Offset.Zero
                    lastPos1 = Offset.Zero
                    previousSinglePos = screenPos
                    firstDocPos = docPos
                    shapeEndDocPos = docPos
                    isLongPressPickerActive = false

                    // 笔尖接触瞬间立即启动绘图，彻底消除长按判定位移容差带来的起笔延迟
                    handleToolDown(screenPos, docPos, pressure, isStylus = true)

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
                    handleToolMove(event, stylusPointerIndex, docPos, pressure, isStylus = true)
                    previousSinglePos = screenPos
                    localCursorPos = screenPos
                    localIsTouching = true
                    // 仅在非笔刷工具、光标跟随模式或激活笔尖硬件预测时在 UI 线程重绘
                    val isDrawingTool = tool == Tool.BRUSH || tool == Tool.ERASER || tool == Tool.SMUDGE
                    if (!isDrawingTool) {
                        invalidate()
                    } else {
                        val isEraser = tool == Tool.ERASER
                        val cursorMode = if (isEraser) v.eraserCursorMode else v.brushCursorMode
                        if (cursorMode == 1 || cursorMode == 3 || (v.isCurrentBrushPredictionEligible && predictedScreenPoint != null)) {
                            invalidate()
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    predictedScreenPoint = null
                    try {
                        oplusPredictor?.reset()
                    } catch (_: Throwable) {}
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

        // 1. 多指手势 (双指捏合缩放/旋转/平移 + 碎片期融合)
        if (numFingers >= 2 || (isTransformActive && (nowMs - lastTransformTimestamp) < 150)) {
            removeCallbacks(longPressRunnable)
            longPressToken++
            isPendingLongPress = false

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

                if (!isTransformActive || (nowMs - lastTransformTimestamp) >= 150) {
                    isTransformActive = true
                    isPinchMotion = false
                    isContinuousUndoing = false
                    prevCentroid = centroid
                    prevDistance = distance
                    prevAngle = angle
                    initialCentroid = centroid
                    initialDistance = distance
                    initialAngle = angle
                    touchDownTimeMs = nowMs

                    removeCallbacks(continuousUndoRunnable)
                    removeCallbacks(continuousRedoRunnable)
                    if (!isShiftTraceAlign) {
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
                        val dRot = if (v.canvasRotationEnabled) normalizeAngle(angle - prevAngle).coerceIn(-15f, 15f) else 0f
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
                        val arcMoved = Math.toRadians(angleDiff.toDouble()).toFloat() * initialDistance * 0.5f
                        if (totalMoved > 6f * density || spreadMoved > 3f * density || arcMoved > 3f * density) {
                            isPinchMotion = true
                            removeCallbacks(continuousUndoRunnable)
                            removeCallbacks(continuousRedoRunnable)
                        }

                        // 围绕双指中心 (prevCentroid) 几何旋转与缩放补偿，保证手指标定点完全不动
                        val vx = prevCentroid.x - (viewW / 2f + canvasPanX)
                        val vy = prevCentroid.y - (viewH / 2f + canvasPanY)

                        val targetZoom = (canvasZoom * k).coerceIn(0.02f, 128f)
                        val actualK = if (canvasZoom > 0.0001f) targetZoom / canvasZoom else 1f

                        val vRotX = actualK * (vx * cosR - vy * sinR)
                        val vRotY = actualK * (vx * sinR + vy * cosR)

                        canvasZoom = targetZoom
                        canvasPanX = centroid.x - vRotX - viewW / 2f
                        canvasPanY = centroid.y - vRotY - viewH / 2f

                        if (v.canvasRotationEnabled && abs(dRot) > 0.01f) {
                            canvasRotation += dRot
                        }

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
                    } else if (!isContinuousUndoing && !isPinchMotion && !filterSessionActive && maxTouchPointers == 2 && v.gestureTwoFingerUndo && durationMs < 360L) {
                        v.undo()
                    } else if (!isContinuousUndoing && !isPinchMotion && !filterSessionActive && maxTouchPointers >= 3 && v.gestureThreeFingerRedo && durationMs < 380L) {
                        v.redo()
                    }

                    isContinuousUndoing = false
                    isTransformActive = false
                    isPinchMotion = false
                    maxTouchPointers = 0
                    lastPos0 = Offset.Zero
                    lastPos1 = Offset.Zero
                    invalidate()
                }
                MotionEvent.ACTION_CANCEL -> {
                    removeCallbacks(continuousUndoRunnable)
                    removeCallbacks(continuousRedoRunnable)
                    isContinuousUndoing = false
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
        val canEyedrop = v.longPressEyedropperEnabled && !v.penOnlyMode && isDrawingTool
        val isPenOnlyPan = v.penOnlyMode && (
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
                firstDocPos = docPos
                shapeEndDocPos = docPos
                isLongPressPickerActive = false
                removeCallbacks(longPressRunnable)
                longPressToken++

                // Check if user is touching a Drawing Guide handle (Perspective Vanishing Point or Symmetry Center/Line)
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
                    if (hypot(screenPos.x - symScreen.x, screenPos.y - symScreen.y) < 48f * density) {
                        draggingGuideHandleIndex = 100
                        isPendingLongPress = false
                        parent?.requestDisallowInterceptTouchEvent(true)
                        return true
                    }
                    if (guide.symmetryType == SymmetryType.VERTICAL && abs(screenPos.x - symScreen.x) < 32f * density) {
                        draggingGuideHandleIndex = 100
                        isPendingLongPress = false
                        parent?.requestDisallowInterceptTouchEvent(true)
                        return true
                    } else if (guide.symmetryType == SymmetryType.HORIZONTAL && abs(screenPos.y - symScreen.y) < 32f * density) {
                        draggingGuideHandleIndex = 100
                        isPendingLongPress = false
                        parent?.requestDisallowInterceptTouchEvent(true)
                        return true
                    }
                }

                if (canEyedrop) {
                    isPendingLongPress = true
                    activeLongPressToken = longPressToken
                    pendingDownDocPos = docPos
                    pendingDownScreenPos = screenPos
                    pendingDownPressure = 1f
                    val delayMs = (520L - (v.eyedropperSensitivity - 1) * 70L).coerceIn(200L, 600L)
                    postDelayed(longPressRunnable, delayMs)
                    localCursorPos = screenPos
                    localIsTouching = true
                    localIsHovering = false
                    localPressure = 1f
                    invalidate()
                } else if (!isPenOnlyPan) {
                    isPendingLongPress = false
                    localCursorPos = screenPos
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
                previousSinglePos = screenPos

                if (draggingGuideHandleIndex != -1) {
                    val g = v.drawingGuide
                    if (draggingGuideHandleIndex == 100) {
                        val newX = (docPos.x / v.docWidth).coerceIn(0.05f, 0.95f)
                        val newY = (docPos.y / v.docHeight).coerceIn(0.05f, 0.95f)
                        v.drawingGuide = g.copy(symmetryCenterX = newX, symmetryCenterY = newY)
                        invalidate()
                        return true
                    } else if (draggingGuideHandleIndex in 0 until g.perspectiveVanishingPoints.size) {
                        val pts = g.perspectiveVanishingPoints.toMutableList()
                        pts[draggingGuideHandleIndex] = Point2D(docPos.x, docPos.y)
                        v.drawingGuide = g.copy(perspectiveVanishingPoints = pts)
                        invalidate()
                        return true
                    }
                }

                if (isLongPressPickerActive) {
                    sampleColorAtScreenPos(screenPos)
                    return true
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
                    if (isPendingLongPress) {
                        val moveSlopPx = (1.5f + (v.eyedropperSensitivity.coerceIn(1, 5) - 3) * 0.3f).coerceIn(0.6f, 2.5f) * density
                        val moveDist = hypot(screenPos.x - pendingDownScreenPos.x, screenPos.y - pendingDownScreenPos.y)
                        if (moveDist > moveSlopPx) {
                            removeCallbacks(longPressRunnable)
                            longPressToken++
                            isPendingLongPress = false
                            localCursorPos = screenPos
                            localIsTouching = true
                            invalidate()
                            handleToolDown(pendingDownScreenPos, pendingDownDocPos, pendingDownPressure, isStylus = false)
                            handleToolMove(event, 0, docPos, 1f, isStylus = false)
                        }
                    } else {
                        localCursorPos = screenPos
                        localIsTouching = true
                        invalidate()
                        handleToolMove(event, 0, docPos, 1f, isStylus = false)
                    }
                    return true
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPressRunnable)
                longPressToken++
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
                    localCursorPos = null
                    invalidate()
                    return true
                }

                if (!isPenOnlyPan) {
                    if (isPendingLongPress) {
                        isPendingLongPress = false
                        handleToolDown(pendingDownScreenPos, pendingDownDocPos, pendingDownPressure, isStylus = false)
                        handleToolUp(event, pendingDownDocPos, isCancel = (event.actionMasked == MotionEvent.ACTION_CANCEL))
                    } else {
                        handleToolUp(event, docPos, isCancel = (event.actionMasked == MotionEvent.ACTION_CANCEL))
                    }
                    localIsTouching = false
                    localCursorPos = null
                    invalidate()
                }
                return true
            }
        }

        return super.onTouchEvent(event)
    }

    private fun handleToolDown(screenPos: Offset, docPos: Offset, pressure: Float, isStylus: Boolean) {
        val v = vm ?: return
        val activeLayer = v.layers.firstOrNull { it.index == v.currentLayerIndex }
        val t = effTool()
        val isDrawingTool = t.group == ToolGroup.BRUSH || t.group == ToolGroup.FILL || t.group == ToolGroup.SHAPES

        if (activeLayer?.isGroup == true && isDrawingTool) {
            v.showActionToast(context.getString(R.string.canvas_toast_group_not_drawable), R.drawable.ic_folder)
            return
        }
        if ((activeLayer?.nodeType == 3 || activeLayer?.name?.contains("滤镜") == true) && isDrawingTool) {
            v.showActionToast(context.getString(R.string.canvas_toast_filter_not_drawable), R.drawable.ic_image_adjust)
            return
        }
        if (activeLayer?.locked == true && (isDrawingTool || tool == Tool.LIQUIFY)) {
            v.showActionToast(context.getString(R.string.canvas_toast_layer_locked), R.drawable.ic_lock)
            return
        }

        when (effTool()) {
            Tool.BRUSH, Tool.ERASER, Tool.SMUDGE -> {
                val hasSymmetry = v.drawingGuide.mode == GuideMode.SYMMETRY && v.drawingGuide.assistedDrawing
                if (hasSymmetry) {
                    v.runCore(render = false) {
                        ReverieCoreBridge.beginUndoMacro("Symmetry Stroke")
                    }
                }
                smoothedPressure = pressure
                if (isStylus) {
                    getOrCreateStylusDriver()?.feedbackManager?.setWritingHapticsEnabled(true, isEraser = (effTool() == Tool.ERASER))
                }
                strokeStarted = v.touchStart(docPos.x, docPos.y, pressure.toDouble())
                if (strokeStarted) {
                    // 纸张摩擦音效: 落笔起振 (橡皮稍收音量)
                    getOrCreateStylusDriver()?.feedbackManager?.startStrokeSound(effTool() == Tool.ERASER)
                    lastSoundTimeMs = 0L
                }
                val isAssist = hasSymmetry || (v.drawingGuide.mode != GuideMode.OFF && v.drawingGuide.assistedDrawing)
                val assistedScreen = if (isAssist) docToScreen(docPos) else screenPos

                if (hasSymmetry) {
                    val symPts = computeAllSymmetricPoints(Point2D(docPos.x, docPos.y))
                    ensureMirrorBranches(symPts.size)
                    for (idx in symPts.indices) {
                        appendMirrorSample(idx, symPts[idx].x, symPts[idx].y, pressure.toDouble())
                    }
                } else {
                    resetMirrorBranches()
                }
            }
            Tool.LIQUIFY -> {
                liquifyFlushPosted = false
                removeCallbacks(liquifyFlushRunnable)
                // Phase 3A 实验开关: `setprop debug.reverie.lqcoalesce <n>` 指定每帧最多推进的
                // 补点数(0 = 关闭)。AGSL 预览本来就"不阻塞引擎", 因此它默认按 2 步/帧跑;
                // 引擎侧路径(无预览/CPU 预览)默认关闭, 保持既有行为, 需要时用 property 打开。
                // 应用内覆盖(debug 设置页)优先于 property —— 无数据线时靠它切换
                val ov = PerfTrace.liquifyCoalesceOverride
                val prop = if (ov >= 0) ov else PerfTrace.debugPropInt("debug.reverie.lqcoalesce", -1)
                val maxDabs = when {
                    // 1) property 最高优先(无数据线时也能靠构建档位兜底)
                    prop >= 0 -> prop
                    // 2) 构建期档位: 3 = 对照"不做调度合并", 1/2 = 默认 2 步/帧
                    BuildConfig.LQ_TEST_PROFILE == 3 -> 0
                    BuildConfig.LQ_TEST_PROFILE == 1 || BuildConfig.LQ_TEST_PROFILE == 2 ->
                        LiquifyPath.DEFAULT_MAX_DABS_PER_FLUSH
                    // 3) AGSL 预览(引擎侧本来就不阻塞)默认开启; 其它路径保持逐点处理
                    LiquifyGpuPreview.requested -> LiquifyPath.DEFAULT_MAX_DABS_PER_FLUSH
                    else -> 0
                }
                // 交互态会话: 以落笔点为已渲染基准, 后续 MOVE 只提交"最新位置"
                liquifySession.begin(docPos.x, docPos.y, maxDabs)
                v.liquifyBegin()
                strokeStarted = true
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
                    val snapDistThreshold = (24f * density) / currentScale

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
                measureStart?.value = docPos
                measureEnd?.value = docPos
            }
            Tool.TRANSFORM, Tool.MOVE -> {
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
                    if (tool == Tool.MOVE) {
                        state.handle = 8 // Translate only
                    } else {
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
                            var bestD = baseThresholdDoc
                            for (i in handles.indices) {
                                val d = hypot(handles[i].x - docPos.x, handles[i].y - docPos.y)
                                if (d < bestD) {
                                    bestD = d
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

        if (draggingGuideHandleIndex == 100) {
            val newX = (docPos.x / v.docWidth).coerceIn(0.05f, 0.95f)
            val newY = (docPos.y / v.docHeight).coerceIn(0.05f, 0.95f)
            v.drawingGuide = v.drawingGuide.copy(symmetryCenterX = newX, symmetryCenterY = newY)
            invalidate()
            return
        } else if (draggingGuideHandleIndex in 0 until v.drawingGuide.perspectiveVanishingPoints.size) {
            val pts = v.drawingGuide.perspectiveVanishingPoints.toMutableList()
            pts[draggingGuideHandleIndex] = Point2D(docPos.x, docPos.y)
            v.drawingGuide = v.drawingGuide.copy(perspectiveVanishingPoints = pts)
            invalidate()
            return
        }

        when (effTool()) {
            Tool.BRUSH, Tool.ERASER, Tool.SMUDGE -> {
                if (!strokeStarted) {
                    strokeStarted = v.touchStart(firstDocPos.x, firstDocPos.y, pressure.toDouble())
                    if (strokeStarted && isStylus) {
                        getOrCreateStylusDriver()?.feedbackManager?.setWritingHapticsEnabled(true, isEraser = (effTool() == Tool.ERASER))
                    }
                    if (strokeStarted) {
                        // 迟到的笔画起点 (首帧被历史点吞掉): 补启摩擦音效
                        getOrCreateStylusDriver()?.feedbackManager?.startStrokeSound(effTool() == Tool.ERASER)
                        lastSoundTimeMs = 0L
                    }
                }
                if (!strokeStarted) return

                val effectiveDocPos = applyAssistedDrawing(firstDocPos, docPos)
                val isAssist = (v.drawingGuide.mode != GuideMode.OFF && v.drawingGuide.assistedDrawing)
                val hasSymmetry = (v.drawingGuide.mode == GuideMode.SYMMETRY && v.drawingGuide.assistedDrawing)

                for (i in 0 until event.historySize) {
                    val hScreen = Offset(event.getHistoricalX(pointerIndex, i), event.getHistoricalY(pointerIndex, i))
                    val hDoc = screenToDoc(hScreen)
                    val hAssisted = applyAssistedDrawing(firstDocPos, hDoc)
                    val hP = if (isStylus) event.getHistoricalPressure(pointerIndex, i).coerceIn(0f, 1f) else 1f
                    val hTime = event.getHistoricalEventTime(i)
                    v.touchMove(hAssisted.x, hAssisted.y, hP.toDouble(), hTime)
                    if (hasSymmetry) {
                        val symPts = computeAllSymmetricPoints(Point2D(hAssisted.x, hAssisted.y))
                        for (idx in symPts.indices) {
                            appendMirrorSample(idx, symPts[idx].x, symPts[idx].y, hP.toDouble())
                        }
                    }
                }

                v.touchMove(effectiveDocPos.x, effectiveDocPos.y, pressure.toDouble(), event.eventTime)

                // 纸张摩擦音效: 按文档坐标瞬时速度调制增益 (零分配, 单次 volatile 写)
                val soundDt = event.eventTime - lastSoundTimeMs
                if (lastSoundTimeMs != 0L && soundDt > 0) {
                    val sdx = effectiveDocPos.x - lastSoundDocPos.x
                    val sdy = effectiveDocPos.y - lastSoundDocPos.y
                    val speedPxPerMs = kotlin.math.sqrt(sdx * sdx + sdy * sdy) / soundDt
                    getOrCreateStylusDriver()?.feedbackManager?.updateStrokeSound(speedPxPerMs)
                }
                lastSoundDocPos = effectiveDocPos
                lastSoundTimeMs = event.eventTime

                if (hasSymmetry) {
                    val symPts = computeAllSymmetricPoints(Point2D(effectiveDocPos.x, effectiveDocPos.y))
                    for (idx in symPts.indices) {
                        appendMirrorSample(idx, symPts[idx].x, symPts[idx].y, pressure.toDouble())
                    }
                }

                // OEM 硬件前向预测计算 (抵消 144Hz 屏幕 1~2 帧约 14~20ms 物理显示上屏延迟)
                if (isStylus && v.isCurrentBrushPredictionEligible) {
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
                                predictedScreenPoint = Offset(pred.x, pred.y)
                                predictedPressure = pred.pressure.coerceIn(0.01f, 1f)
                            } else {
                                predictedScreenPoint = null
                            }
                        } catch (_: Throwable) {
                            predictedScreenPoint = null
                        }
                    } else if (Build.VERSION.SDK_INT >= 34 && androidMotionPredictor != null) {
                        val amp = androidMotionPredictor as? android.view.MotionPredictor
                        if (amp != null) {
                            try {
                                amp.record(event)
                                val predEvent = amp.predict(15_000_000L)
                                if (predEvent != null) {
                                    predictedScreenPoint = Offset(predEvent.x, predEvent.y)
                                    predictedPressure = predEvent.pressure.coerceIn(0.01f, 1f)
                                    predEvent.recycle()
                                } else {
                                    predictedScreenPoint = null
                                }
                            } catch (_: Throwable) {
                                predictedScreenPoint = null
                            }
                        }
                    } else {
                        predictedScreenPoint = null
                    }
                } else {
                    predictedScreenPoint = null
                }
            }
            Tool.LIQUIFY -> {
                if (strokeStarted) {
                    if (liquifySession.coalescing) {
                        // Phase 3B: latest-state-wins —— 只留"最新位置", 中间状态(含历史点)全丢,
                        // 由下一帧统一推进。避免"一个事件里多个历史点 → 队列里堆多次 apply"。
                        liquifySession.submitTarget(docPos.x, docPos.y)
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
                measureEnd?.value = docPos
            }
            Tool.TRANSFORM, Tool.MOVE -> {
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

    /** 回放一条镜像分支: [buf] 为扁平的 [x, y, pressure] 三元组, 共 [count] 个点 */
    private fun replaySymmetricBranch(buf: FloatArray, count: Int) {
        val v = vm ?: return
        if (count < 2 || buf.size < count * 3) return

        v.touchStart(buf[0], buf[1], buf[2].toDouble())
        for (i in 1 until count) {
            val base = i * 3
            v.touchMove(buf[base], buf[base + 1], buf[base + 2].toDouble())
        }
        v.touchEnd()
    }

    /**
     * 液化沿路径推进: 位移大于笔刷影响半径时拆成多个补点, 让相邻形变搭接,
     * 消除快速拖动时的断线。强度按 [LiquifyPath.substepStrengthScale] 折算,
     * 保证细分前后总形变量一致 (引擎侧幅度曲线对每个 dab 有固定底)。
     *
     * 关闭合并(latest-state-wins)时的逐点路径: 每个输入点立即**全量**推进, 与历史行为一致。
     */
    private fun liquifyAlongPath(v: PaintViewModel, to: Offset) {
        liquifySession.submitTarget(to.x, to.y)
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
    private fun liquifyFlushNow(v: PaintViewModel, forceFull: Boolean) {
        if (!liquifySession.prepareFlush(liquifyBrushSize, liquifyMode, forceFull)) return
        val steps = liquifySession.planSteps
        if (steps <= 0) return
        val strength = liquifyStrength * liquifySession.planStrengthScale
        val stepX = liquifySession.planStepX
        val stepY = liquifySession.planStepY
        var px = liquifySession.planStartX
        var py = liquifySession.planStartY
        for (i in 0 until steps) {
            val nx = px + stepX
            val ny = py + stepY
            v.liquify(px, py, nx, ny, liquifyMode, strength.toDouble())
            px = nx
            py = ny
        }
        liquifySession.advanceFlush(steps)
        // 合并倍率: 本帧覆盖的输入事件数 / 实际补点数
        PerfTrace.liquifySchedule(liquifySession.lastFlushInputs, steps)
        // backlog 指标: 滞后事件数 + 仍未提交的补点数(见 PerfTrace.liquifyFlow)
        PerfTrace.liquifyFlow(liquifySession.lag, liquifySession.backlogDabs)
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
        liquifyFlushNow(v, forceFull = false)
        if (liquifySession.hasPending && !liquifyFlushPosted) {
            // 还没追上最新位置: 下一帧继续朝最新位置推进
            liquifyFlushPosted = true
            postOnAnimation(liquifyFlushRunnable)
        }
    }

    private fun handleToolUp(event: MotionEvent, docPos: Offset, isCancel: Boolean) {
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
                    if (isCancel) {
                        v.touchCancel()
                        if (hasSymmetry) {
                            v.runCore(render = false) {
                                ReverieCoreBridge.endUndoMacro()
                            }
                        }
                    } else {
                        v.touchEnd()
                        if (hasSymmetry && mirrorBranchHasSamples()) {
                            // 回放期间只读缓冲 (抬笔后不再追加采样), 无需再拷贝一份
                            for (b in 0 until mirroredSamples.size) {
                                replaySymmetricBranch(mirroredSamples[b], mirroredSizes[b])
                            }
                            v.runCore(render = false) {
                                ReverieCoreBridge.endUndoMacro()
                            }
                        }
                    }
                    strokeStarted = false
                }
                resetMirrorBranches()
                predictedScreenPoint = null
                try {
                    oplusPredictor?.reset()
                } catch (_: Throwable) {}
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
                    liquifySession.reset()
                    if (isCancel) {
                        v.liquifyCancel()
                    } else {
                        v.liquifyEnd()
                    }
                    strokeStarted = false
                }
            }
            Tool.SHAPES, Tool.LINE, Tool.RECT, Tool.ELLIPSE, Tool.POLYGON, Tool.POLYLINE, Tool.PATH -> {
                handleShapeUp(docPos)
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
                    val dragDist = hypot(docPos.x - firstDocPos.x, docPos.y - firstDocPos.y)
                    val isTap = dragDist < 8f * density && lassoPoints.size <= 3

                    val currentScale = maxOf(0.01f, canvasZoom * canvasFitScale)
                    val snapDistThreshold = (24f * density) / currentScale

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
            Tool.TRANSFORM -> {
                tfState?.handle = -1
            }
            Tool.MOVE -> {
                val state = tfState
                if (state != null) {
                    val dx = state.tx.toInt()
                    val dy = state.ty.toInt()
                    state.tx = 0f
                    state.ty = 0f
                    v.transformPreviewBitmap = null
                    if (dx != 0 || dy != 0) {
                        val b = v.contentBounds()
                        if (b != null && b[2] > 0 && b[3] > 0) {
                            state.bounds = Rect(
                                b[0].toFloat(),
                                b[1].toFloat(),
                                (b[0] + b[2]).toFloat(),
                                (b[1] + b[3]).toFloat(),
                            )
                        }
                        v.moveLayerContent(dx, dy)
                    } else {
                        v.startTransformPreview()
                    }
                }
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
                lastShapeTapTimeMs = now
                lastShapeTapDocPos = docPos
            } else {
                state.activeHandle = if (targetType == ShapeType.LINE) ShapeHandleId.LINE_P2 else ShapeHandleId.CORNER_BR
                state.dragStartDocPos = docPos
                state.dragP1 = docPos
                state.dragP2 = docPos
                state.isCreatingNewNode = false
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
                v.typographyConfig = textDragStartCfg.copy(
                    posX = textDragStartCfg.posX + delta.x,
                    posY = textDragStartCfg.posY + delta.y,
                )
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
