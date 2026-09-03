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
import com.reverie.paint.model.*
import com.reverie.paint.ui.theme.parseColor
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

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
    private var liquifyPrevPos = Offset.Zero
    private var smoothedPressure = 0.8f

    // 手指多点手势状态
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
            isLongPressPickerActive = true
            pickerActive?.value = true
            val refHex = v.brushColor
            pickerInitialColor?.value = parseColor(refHex)
            sampleColorAtScreenPos(pendingDownScreenPos)
        }
    }



    // ---- 触控预测 (Hermite Motion Predictor) ----
    private var histPos0 = Offset.Zero
    private var histPos1 = Offset.Zero
    private var histTime0 = 0L
    private var histTime1 = 0L

    private fun updateMotionPrediction(screenPos: Offset, nowMs: Long): Offset {
        val v = vm
        if (v?.motionPredictorEnabled != true) return screenPos
        if (histTime0 == 0L) {
            histPos0 = screenPos
            histTime0 = nowMs
            return screenPos
        }
        val dt = (nowMs - histTime0).coerceIn(1L, 50L)
        histPos1 = histPos0
        histTime1 = histTime0
        histPos0 = screenPos
        histTime0 = nowMs

        val vx = (histPos0.x - histPos1.x) / dt.toFloat()
        val vy = (histPos0.y - histPos1.y) / dt.toFloat()
        val predDt = 14f // 预测前推约 14ms (1~2 帧)
        val maxDist = 24f * density
        val predDx = (vx * predDt).coerceIn(-maxDist, maxDist)
        val predDy = (vy * predDt).coerceIn(-maxDist, maxDist)
        return Offset(screenPos.x + predDx, screenPos.y + predDy)
    }

    // ---- 对称与透视绘图辅助 (Drawing Assist) ----
    private val mirroredBranches = mutableListOf<MutableList<Point2D>>()
    private val mirroredDrawPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

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
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(longPressRunnable)
        isPendingLongPress = false
        isLongPressPickerActive = false
        longPressToken++
        if (activeTouchView == this) activeTouchView = null
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

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val v = vm ?: return
        val pos = localCursorPos ?: return
        if (v.brushStudioOpen || v.moreSettingsOpen || overlayPanelsOpen) return
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
        if (shouldShow && isDrawTool && v.cursorStyleMode != 4) {
            val scale = (canvasZoom * canvasFitScale).coerceAtLeast(0.001f)
            // Krita-aligned: the outline always shows the FULL brush diameter.
            // The engine maps pressure through each preset's own nonlinear
            // SizeSensor curve, so shrinking the ring by raw linear pressure
            // made it far larger than the actual dab at light pressure.
            val cursorBrushSize = if (tool == Tool.LIQUIFY) liquifyBrushSize else v.brushSize.toFloat()
            // 与引擎同源：按当前预设 SizeSensor 压感曲线求直径比例，环随实时压感缩放。
            // 用本 View 触摸事件直接维护的 localPressure/localIsTouching（5727344 之前
            // 已验证会实时更新），不用外部 MutableState（曾因无人写入导致环恒定）。
            val pressureFraction =
                if (localIsTouching) {
                    ReverieCoreBridge.brushPressureFraction(localPressure)
                } else 1f
            val brushRadiusScreen = (cursorBrushSize * scale * 0.5f * pressureFraction).coerceAtLeast(2f)

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

            // 绘画中实时镜像笔迹绘制 (120Hz 零延迟 GPU Canvas 渲染)
            if (v.drawingGuide.mode == GuideMode.SYMMETRY && v.drawingGuide.assistedDrawing && localIsTouching && mirroredBranches.isNotEmpty()) {
                try {
                    mirroredDrawPaint.color = android.graphics.Color.parseColor(v.brushColor)
                    mirroredDrawPaint.strokeWidth = (v.brushSize.toFloat() * scale).coerceAtLeast(1.5f)
                    for (branch in mirroredBranches) {
                        for (i in 1 until branch.size) {
                            val p1 = docToScreen(Offset(branch[i - 1].x, branch[i - 1].y))
                            val p2 = docToScreen(Offset(branch[i].x, branch[i].y))
                            canvas.drawLine(p1.x, p1.y, p2.x, p2.y, mirroredDrawPaint)
                        }
                    }
                } catch (_: Exception) {}
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
        val bmp = docBitmap
        if (bmp != null && bmp.width > 0 && bmp.height > 0) {
            val docW = if (v.docWidth > 0) v.docWidth else bmp.width
            val docH = if (v.docHeight > 0) v.docHeight else bmp.height
            val ix = (docPos.x * (bmp.width.toFloat() / docW)).toInt()
            val iy = (docPos.y * (bmp.height.toFloat() / docH)).toInt()
            if (ix in 0 until bmp.width && iy in 0 until bmp.height) {
                val pixel = bmp.getPixel(ix, iy)
                pickerCurrentColor?.value = Color(pixel)
            }
        }
    }

    private fun screenToDoc(screenPos: Offset): Offset {
        val bmp = docBitmap
        val bmpW = bmp?.width ?: vm?.docWidth ?: 1
        val bmpH = bmp?.height ?: vm?.docHeight ?: 1
        return widgetToImage(
            screenPos,
            viewW,
            viewH,
            canvasPanX,
            canvasPanY,
            canvasZoom,
            canvasFitScale,
            canvasRotation,
            bmpW,
            bmpH,
            vm?.docWidth ?: bmpW,
            vm?.docHeight ?: bmpH
        )
    }

    private fun docToScreen(docPos: Offset): Offset {
        val bmp = docBitmap
        val bmpW = bmp?.width ?: vm?.docWidth ?: 1
        val bmpH = bmp?.height ?: vm?.docHeight ?: 1
        val dw = vm?.docWidth ?: bmpW
        val dh = vm?.docHeight ?: bmpH
        return imageToWidget(
            docPos,
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
        if (event.isFromSource(android.view.InputDevice.SOURCE_CLASS_POINTER)) {
            if (event.actionMasked == MotionEvent.ACTION_HOVER_MOVE) {
                localCursorPos = Offset(event.x, event.y)
                val overUi = isHoverOverUi(event.x, event.y)
                localIsHovering = !overUi
                localIsTouching = false
                invalidate()
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
        val pointerCount = event.pointerCount

        // 取消 pending 的撤销或会话重置
        pendingUndoRunnable?.let { removeCallbacks(it) }
        removeCallbacks(resetTransformRunnable)

        // 分离手写笔 Pointer 与手指 Pointer
        val fingerPointers = mutableListOf<Int>()
        var stylusPointerIndex = -1

        for (i in 0 until pointerCount) {
            val toolType = event.getToolType(i)
            if (toolType == MotionEvent.TOOL_TYPE_STYLUS || toolType == MotionEvent.TOOL_TYPE_ERASER) {
                stylusPointerIndex = i
            } else {
                fingerPointers.add(i)
            }
        }

        // 手写笔触控判定：存在手写笔 Pointer 且没有 2 根及以上手指在做手势导航
        val isStylusTouch = stylusPointerIndex >= 0 && fingerPointers.size < 2

        // =========================================================
        // A. 手写笔交互流程：100% 负责笔刷绘制与图层编辑
        // =========================================================
        if (isStylusTouch) {
            val x = event.getX(stylusPointerIndex)
            val y = event.getY(stylusPointerIndex)
            val screenPos = Offset(x, y)
            val docPos = screenToDoc(screenPos)
            val pressure = event.getPressure(stylusPointerIndex).coerceIn(0f, 1f)

            localCursorPos = screenPos
            localIsTouching = true
            localIsHovering = false
            localPressure = pressure
            invalidate()

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
                        handleToolDown(docPos, pressure, isStylus = true)
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
                            handleToolDown(pendingDownDocPos, pendingDownPressure, isStylus = true)
                            handleToolMove(event, stylusPointerIndex, docPos, pressure, isStylus = true)
                        }
                    } else {
                        handleToolMove(event, stylusPointerIndex, docPos, pressure, isStylus = true)
                    }
                    previousSinglePos = screenPos
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    removeCallbacks(longPressRunnable)
                    longPressToken++
                    if (isLongPressPickerActive) {
                        val curCol = pickerCurrentColor?.value
                        if (curCol != null) {
                            val r = (curCol.red * 255).toInt().coerceIn(0, 255)
                            val g = (curCol.green * 255).toInt().coerceIn(0, 255)
                            val b = (curCol.blue * 255).toInt().coerceIn(0, 255)
                            val hex = String.format("#%02X%02X%02X", r, g, b)
                            v.updateBrushColor(hex)
                            v.showActionToast("已吸取颜色", R.drawable.ic_picker)
                        }
                        pickerActive?.value = false
                        isLongPressPickerActive = false
                        localIsTouching = false
                        localIsHovering = true
                        isInteracting = false
                        invalidate()
                        return true
                    }

                    if (isPendingLongPress) {
                        isPendingLongPress = false
                        handleToolDown(pendingDownDocPos, pendingDownPressure, isStylus = true)
                        handleToolUp(event, pendingDownDocPos, isCancel = (event.actionMasked == MotionEvent.ACTION_CANCEL))
                    } else {
                        handleToolUp(event, docPos, isCancel = (event.actionMasked == MotionEvent.ACTION_CANCEL))
                    }
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
        val numFingers = fingerPointers.size
        maxTouchPointers = maxOf(maxTouchPointers, numFingers)
        isInteracting = true

        // 1. 多指手势 (双指捏合缩放/旋转/平移 + 碎片期融合)
        if (numFingers >= 2 || (isTransformActive && (nowMs - lastTransformTimestamp) < 150)) {
            removeCallbacks(longPressRunnable)
            longPressToken++
            isPendingLongPress = false

            if (numFingers >= 2) {
                val idx0 = fingerPointers[0]
                val idx1 = fingerPointers[1]
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
                    if (numFingers == 2 && v.gestureTwoFingerUndo) {
                        postDelayed(continuousUndoRunnable, 420L)
                    } else if (numFingers >= 3 && v.gestureThreeFingerRedo) {
                        postDelayed(continuousRedoRunnable, 420L)
                    }
                } else {
                    val distCentroidMoved = hypot(centroid.x - prevCentroid.x, centroid.y - prevCentroid.y)
                    if (distCentroidMoved > 80f * density) {
                        prevCentroid = centroid
                        prevDistance = distance
                        prevAngle = angle
                    } else {
                        val k = (distance / prevDistance).coerceIn(0.7f, 1.4f)
                        val dRot = if (v.canvasRotationEnabled) normalizeAngle(angle - prevAngle).coerceIn(-15f, 15f) else 0f
                        val rad = Math.toRadians(dRot.toDouble())
                        val cosR = kotlin.math.cos(rad).toFloat()
                        val sinR = kotlin.math.sin(rad).toFloat()

                        val totalMoved = hypot(centroid.x - initialCentroid.x, centroid.y - initialCentroid.y)
                        val scaleRatio = distance / initialDistance
                        val angleDiff = abs(normalizeAngle(angle - initialAngle))
                        if (totalMoved > 6f * density || abs(scaleRatio - 1f) > 0.02f || angleDiff > 2f) {
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
                val idx0 = fingerPointers[0]
                val cur = Offset(event.getX(idx0), event.getY(idx0))
                val dist0 = hypot(cur.x - lastPos0.x, cur.y - lastPos0.y)
                val dist1 = hypot(cur.x - lastPos1.x, cur.y - lastPos1.y)

                if (dist0 < dist1 && dist0 < 60f * density) {
                    val dx = cur.x - lastPos0.x
                    val dy = cur.y - lastPos0.y
                    lastPos0 = cur
                    prevCentroid = prevCentroid + Offset(dx / 2f, dy / 2f)
                    canvasPanX += dx
                    canvasPanY += dy
                    if (hypot(dx, dy) > 2f) isPinchMotion = true
                    lastTransformTimestamp = nowMs
                    onTransform?.invoke(canvasZoom, canvasRotation, canvasPanX, canvasPanY)
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

                    if (isQuickPinchFit) {
                        animateFitCanvas()
                        v.showActionToast("画布已平滑满屏复位", R.drawable.ic_refresh)
                    } else if (!isContinuousUndoing && !isPinchMotion && maxTouchPointers == 2 && v.gestureTwoFingerUndo && durationMs < 360L) {
                        v.undo()
                    } else if (!isContinuousUndoing && !isPinchMotion && maxTouchPointers >= 3 && v.gestureThreeFingerRedo && durationMs < 380L) {
                        v.redo()
                    }

                    isContinuousUndoing = false
                    isTransformActive = false
                    isPinchMotion = false
                    maxTouchPointers = 0
                    lastPos0 = Offset.Zero
                    lastPos1 = Offset.Zero
                }
                MotionEvent.ACTION_CANCEL -> {
                    removeCallbacks(continuousUndoRunnable)
                    removeCallbacks(continuousRedoRunnable)
                    isContinuousUndoing = false
                    postDelayed(resetTransformRunnable, 150)
                }
            }
            return true
        }

        // 2. 单指手势 (根据 vm.penOnlyMode 切换手指平移 vs 手指作画)
        val singleFingerIdx = if (fingerPointers.isNotEmpty()) fingerPointers[0] else 0
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
                    handleToolDown(docPos, 1f, isStylus = false)
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

                if (isPenOnlyPan) {
                    // 笔模式开启且处于绘图工具：单指丝滑平移画布
                    canvasPanX += deltaX
                    canvasPanY += deltaY
                    onTransform?.invoke(canvasZoom, canvasRotation, canvasPanX, canvasPanY)
                    return true
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
                            handleToolDown(pendingDownDocPos, pendingDownPressure, isStylus = false)
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
                        v.showActionToast("已吸取颜色", R.drawable.ic_picker)
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
                        handleToolDown(pendingDownDocPos, pendingDownPressure, isStylus = false)
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

    private fun handleToolDown(docPos: Offset, pressure: Float, isStylus: Boolean) {
        val v = vm ?: return
        val activeLayer = v.layers.firstOrNull { it.index == v.currentLayerIndex }
        val isDrawingTool = tool.group == ToolGroup.BRUSH || tool.group == ToolGroup.FILL || tool.group == ToolGroup.SHAPES

        if (activeLayer?.isGroup == true && isDrawingTool) {
            v.showActionToast("图层组不可直接绘制，请选择组内图层", R.drawable.ic_folder)
            return
        }
        if (activeLayer?.locked == true && (isDrawingTool || tool == Tool.LIQUIFY)) {
            v.showActionToast("图层已锁定，无法编辑", R.drawable.ic_lock)
            return
        }

        when (tool) {
            Tool.BRUSH, Tool.ERASER, Tool.SMUDGE -> {
                smoothedPressure = pressure
                strokeStarted = v.touchStart(docPos.x, docPos.y, pressure.toDouble())

                mirroredBranches.clear()
                val symPts = computeAllSymmetricPoints(Point2D(docPos.x, docPos.y))
                for (symPt in symPts) {
                    val branch = mutableListOf<Point2D>()
                    branch.add(symPt)
                    mirroredBranches.add(branch)
                }
            }
            Tool.LIQUIFY -> {
                liquifyPrevPos = docPos
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
            Tool.POLYGON, Tool.POLYLINE, Tool.SELECT_POLYGON, Tool.PATH -> {
                onPolyPoint?.invoke(docPos)
            }
            Tool.TEXT -> {
                onTextRequested?.invoke(docPos.x, docPos.y)
            }
            Tool.LINE, Tool.RECT, Tool.ELLIPSE, Tool.GRADIENT, Tool.SELECT_RECT, Tool.SELECT_ELLIPSE -> {
                liveShapeStart?.value = docPos
                liveShapeEnd?.value = docPos
            }
            Tool.LASSO -> {
                lassoPoints.clear()
                lassoPoints.add(docPos)
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
                        val hitThresholdDoc = (32f * density) / maxOf(0.01f, currentScale)
                        var best = -1
                        var bestD = hitThresholdDoc
                        for (i in handles.indices) {
                            val d = hypot(handles[i].x - docPos.x, handles[i].y - docPos.y)
                            if (d < bestD) {
                                bestD = d
                                best = i
                            }
                        }
                        if (state.mode == TransformMode.PERSPECTIVE) {
                            state.handle = if (best in 0..3) best else 8
                        } else if (state.mode == TransformMode.DISTORT) {
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
                            val inBox =
                                ux >= -state.bounds.width / 2f &&
                                ux <= state.bounds.width / 2f &&
                                uy >= -state.bounds.height / 2f &&
                                uy <= state.bounds.height / 2f
                            state.handle = if (best >= 0) best else if (inBox) 8 else 9
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

        when (tool) {
            Tool.BRUSH, Tool.ERASER, Tool.SMUDGE -> {
                if (!strokeStarted) {
                    // First MOVE may arrive when DOWN was rejected (locked
                    // layer switched mid-gesture): retry the guarded start;
                    // still refused → swallow the gesture (no phantom move).
                    strokeStarted = v.touchStart(firstDocPos.x, firstDocPos.y, pressure.toDouble())
                }
                if (!strokeStarted) return

                val effectiveDocPos = applyAssistedDrawing(firstDocPos, docPos)

                for (i in 0 until event.historySize) {
                    val hScreen = Offset(event.getHistoricalX(pointerIndex, i), event.getHistoricalY(pointerIndex, i))
                    val hDoc = screenToDoc(hScreen)
                    val hAssisted = applyAssistedDrawing(firstDocPos, hDoc)
                    val hP = if (isStylus) event.getHistoricalPressure(pointerIndex, i).coerceIn(0f, 1f) else 1f
                    v.touchMove(hAssisted.x, hAssisted.y, hP.toDouble())
                    val symPts = computeAllSymmetricPoints(Point2D(hAssisted.x, hAssisted.y))
                    for (idx in symPts.indices) {
                        if (idx < mirroredBranches.size) mirroredBranches[idx].add(symPts[idx])
                    }
                }

                v.touchMove(effectiveDocPos.x, effectiveDocPos.y, pressure.toDouble())
                val symPts = computeAllSymmetricPoints(Point2D(effectiveDocPos.x, effectiveDocPos.y))
                for (idx in symPts.indices) {
                    if (idx < mirroredBranches.size) mirroredBranches[idx].add(symPts[idx])
                }
            }
            Tool.LIQUIFY -> {
                if (strokeStarted) {
                    v.liquify(liquifyPrevPos.x, liquifyPrevPos.y, docPos.x, docPos.y, liquifyMode, liquifyStrength.toDouble())
                    liquifyPrevPos = docPos
                }
            }
            Tool.PICKER -> {
                sampleColorAtScreenPos(previousSinglePos)
            }
            Tool.LINE, Tool.RECT, Tool.ELLIPSE, Tool.GRADIENT, Tool.SELECT_RECT, Tool.SELECT_ELLIPSE -> {
                shapeEndDocPos = docPos
                liveShapeEnd?.value = docPos
            }
            Tool.LASSO -> {
                lassoPoints.add(docPos)
                val now = System.nanoTime()
                if (now - lastLassoPreviewNs > 16_000_000L) {
                    lastLassoPreviewNs = now
                    val docW = docBitmap?.width ?: vm?.docWidth ?: 1
                    val docH = docBitmap?.height ?: vm?.docHeight ?: 1
                    val halfW = docW / 2f
                    val halfH = docH / 2f
                    val p = Path().apply {
                        if (lassoPoints.size >= 2) {
                            moveTo(lassoPoints[0].x - halfW, lassoPoints[0].y - halfH)
                            for (j in 1 until lassoPoints.size) {
                                lineTo(lassoPoints[j].x - halfW, lassoPoints[j].y - halfH)
                            }
                            close()
                        }
                    }
                    liveSelectionPath?.value = p
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

    private fun bakeSymmetricBranch(branch: List<Point2D>) {
        val v = vm ?: return
        if (branch.size < 2) return

        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = Float.MIN_VALUE
        var maxY = Float.MIN_VALUE
        for (pt in branch) {
            if (pt.x < minX) minX = pt.x
            if (pt.x > maxX) maxX = pt.x
            if (pt.y < minY) minY = pt.y
            if (pt.y > maxY) maxY = pt.y
        }

        val strokeW = (v.brushSize.toFloat()).coerceAtLeast(1f)
        val pad = strokeW + 16f
        val left = (minX - pad).toInt().coerceIn(0, maxOf(0, v.docWidth - 1))
        val top = (minY - pad).toInt().coerceIn(0, maxOf(0, v.docHeight - 1))
        val right = (maxX + pad).toInt().coerceIn(left + 1, v.docWidth)
        val bottom = (maxY + pad).toInt().coerceIn(top + 1, v.docHeight)
        val bw = right - left
        val bh = bottom - top
        if (bw <= 0 || bh <= 0) return

        val bmp = android.graphics.Bitmap.createBitmap(bw, bh, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE
            strokeCap = android.graphics.Paint.Cap.ROUND
            strokeJoin = android.graphics.Paint.Join.ROUND
            this.strokeWidth = strokeW
            val parsedColor = try {
                android.graphics.Color.parseColor(v.brushColor)
            } catch (_: Throwable) {
                android.graphics.Color.BLACK
            }
            color = parsedColor
            alpha = (v.brushOpacity * 255).toInt().coerceIn(0, 255)
        }

        val path = android.graphics.Path()
        path.moveTo(branch[0].x - left, branch[0].y - top)
        for (i in 1 until branch.size) {
            val prev = branch[i - 1]
            val cur = branch[i]
            val midX = (prev.x + cur.x) / 2f - left
            val midY = (prev.y + cur.y) / 2f - top
            path.quadTo(prev.x - left, prev.y - top, midX, midY)
        }
        path.lineTo(branch.last().x - left, branch.last().y - top)
        canvas.drawPath(path, paint)

        v.runCore {
            ReverieCoreBridge.stampBitmap(left, top, bmp)
        }
    }

    private fun handleToolUp(event: MotionEvent, docPos: Offset, isCancel: Boolean) {
        val v = vm ?: return

        if (draggingGuideHandleIndex != -1) {
            draggingGuideHandleIndex = -1
            return
        }

        when (tool) {
            Tool.BRUSH, Tool.ERASER, Tool.SMUDGE -> {
                if (strokeStarted) {
                    if (isCancel) {
                        v.touchCancel()
                    } else {
                        v.touchEnd()
                        if (v.drawingGuide.mode == GuideMode.SYMMETRY && v.drawingGuide.assistedDrawing) {
                            for (branch in mirroredBranches) {
                                if (branch.size >= 2) {
                                    bakeSymmetricBranch(branch)
                                }
                            }
                        }
                    }
                    strokeStarted = false
                }
                mirroredBranches.clear()
            }
            Tool.LIQUIFY -> {
                if (strokeStarted) {
                    if (isCancel) {
                        v.liquifyCancel()
                    } else {
                        v.liquifyEnd()
                    }
                    strokeStarted = false
                }
            }
            Tool.LINE, Tool.RECT, Tool.ELLIPSE -> {
                liveShapeStart?.value = null
                liveShapeEnd?.value = null
                val kind = when (tool) {
                    Tool.RECT -> 1
                    Tool.ELLIPSE -> 2
                    else -> 0
                }
                var ex = shapeEndDocPos.x
                var ey = shapeEndDocPos.y
                if (v.shapeKeepAspect && (kind == 1 || kind == 2)) {
                    val maxDim = maxOf(abs(ex - firstDocPos.x), abs(ey - firstDocPos.y))
                    ex = if (ex >= firstDocPos.x) firstDocPos.x + maxDim else firstDocPos.x - maxDim
                    ey = if (ey >= firstDocPos.y) firstDocPos.y + maxDim else firstDocPos.y - maxDim
                }
                v.drawShape(kind, firstDocPos.x, firstDocPos.y, ex, ey)
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
                liveSelectionPath?.value = null
                if (lassoPoints.size >= 3) {
                    val points = lassoPoints.map { it.x.toInt() to it.y.toInt() }
                    v.selectPolygon(points)
                }
                lassoPoints.clear()
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
            }
            else -> Unit
        }
    }
}
