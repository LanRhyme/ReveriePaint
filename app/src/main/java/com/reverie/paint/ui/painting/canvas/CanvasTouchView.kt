package com.reverie.paint.ui.painting.canvas

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import android.view.MotionEvent
import android.view.PointerIcon
import android.view.ScaleGestureDetector
import android.view.View
import androidx.compose.runtime.MutableState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import com.reverie.paint.R
import com.reverie.paint.core.*
import com.reverie.paint.model.Tool
import com.reverie.paint.model.ToolGroup
import com.reverie.paint.ui.theme.parseColor
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * 原生 Android View 触控层 (画世界 Pro / Krita 架构)
 *
 * 彻底将系统的 onHoverEvent (手写笔空中悬停) 与 onTouchEvent (物理接触绘制与双指手势)
 * 在系统硬件分发层完全隔离，直接使用 Android 原生 ScaleGestureDetector 实现 120Hz
 * 硬件级平滑缩放与旋转，彻底消除 Compose pointerInput 混合分发引起的卡顿、丢帧与连续撤销。
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
    var liquifyMode: Int = 0

    private val density = context.resources.displayMetrics.density

    // 绘制与手势状态
    private var strokeStarted = false
    private var isTransformActive = false
    private var isPinchMotion = false
    private var firstDocPos = Offset.Zero
    private var shapeEndDocPos = Offset.Zero
    private var previousSinglePos = Offset.Zero
    private var previousFocusX = 0f
    private var previousFocusY = 0f
    private var previousAngle = 0f
    private var touchDownTimeMs = 0L
    private var maxTouchPointers = 0
    private var maxTouchDisplacement = 0f
    private var downPosA = Offset.Zero
    private var downPosB = Offset.Zero
    private val lassoPoints = mutableListOf<Offset>()
    private var lastLassoPreviewNs = 0L
    private var liquifyPrevPos = Offset.Zero
    private var smoothedPressure = 0.8f

    // 长按吸色
    private var isLongPressPickerActive = false
    private val longPressRunnable = Runnable {
        val v = vm ?: return@Runnable
        if (v.longPressEyedropperEnabled && !strokeStarted && !isTransformActive && maxTouchPointers <= 1) {
            isLongPressPickerActive = true
            pickerActive?.value = true
            val refHex = v.brushColor
            pickerInitialColor?.value = parseColor(refHex)
            sampleColorAtScreenPos(previousSinglePos)
        }
    }

    private val systemNullPointer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        PointerIcon.getSystemIcon(context, PointerIcon.TYPE_NULL)
    } else null

    private val systemDefaultPointer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        PointerIcon.getSystemIcon(context, PointerIcon.TYPE_DEFAULT)
    } else null

    // 原生 ScaleGestureDetector (处理双指缩放与平移)
    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            isTransformActive = true
            isPinchMotion = true
            removeCallbacks(longPressRunnable)
            if (strokeStarted) {
                if (tool == Tool.LIQUIFY) vm?.liquifyCancel() else vm?.touchCancel()
                strokeStarted = false
            }
            previousFocusX = detector.focusX
            previousFocusY = detector.focusY
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val scaleFactor = detector.scaleFactor
            if (scaleFactor.isNaN() || scaleFactor.isInfinite()) return true

            val focusX = detector.focusX
            val focusY = detector.focusY
            val dx = focusX - previousFocusX
            val dy = focusY - previousFocusY
            previousFocusX = focusX
            previousFocusY = focusY

            val localZoom = (canvasZoom * scaleFactor).coerceIn(0.05f, 32f)
            var localPanX = canvasPanX + dx
            var localPanY = canvasPanY + dy

            // 围绕缩放中心点进行几何偏移补偿
            val centerX = viewW / 2f + canvasPanX
            val centerY = viewH / 2f + canvasPanY
            val fx = focusX - centerX
            val fy = focusY - centerY
            localPanX += fx * (1f - scaleFactor)
            localPanY += fy * (1f - scaleFactor)

            canvasZoom = localZoom
            canvasPanX = localPanX
            canvasPanY = localPanY
            onTransform?.invoke(canvasZoom, canvasRotation, canvasPanX, canvasPanY)
            return true
        }
    })

    init {
        isFocusable = true
        isFocusableInTouchMode = true
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
        if (bmp != null) {
            val ix = docPos.x.toInt()
            val iy = docPos.y.toInt()
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

    // -------------------------------------------------------------
    // 1. 悬停处理 (空中手写笔 / 鼠标) - 完全走 onHoverEvent / onGenericMotion
    // -------------------------------------------------------------
    override fun onHoverEvent(event: MotionEvent): Boolean {
        val v = vm ?: return super.onHoverEvent(event)
        val hideCursor = (tool == Tool.BRUSH || tool == Tool.ERASER || tool == Tool.SMUDGE || tool == Tool.LIQUIFY) &&
            v.cursorStyleMode != 4

        when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> {
                cursorScreenPos?.value = Offset(event.x, event.y)
                isCursorHovering?.value = true
                isCursorTouching?.value = false
                livePressure?.value = 1f
                if (hideCursor && systemNullPointer != null && pointerIcon != systemNullPointer) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        pointerIcon = systemNullPointer
                    }
                }
                return true
            }
            MotionEvent.ACTION_HOVER_EXIT -> {
                isCursorHovering?.value = false
                isCursorTouching?.value = false
                cursorScreenPos?.value = null
                if (systemDefaultPointer != null && pointerIcon != systemDefaultPointer) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        pointerIcon = systemDefaultPointer
                    }
                }
                return true
            }
        }
        return super.onHoverEvent(event)
    }

    // -------------------------------------------------------------
    // 2. 接触触控处理 (手写笔落笔 / 手指多点手势) - 完全走 onTouchEvent
    // -------------------------------------------------------------
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val v = vm ?: return super.onTouchEvent(event)
        val pointerCount = event.pointerCount
        val toolType = event.getToolType(0)
        val isStylus = toolType == MotionEvent.TOOL_TYPE_STYLUS || toolType == MotionEvent.TOOL_TYPE_ERASER
        val isDrawingTool = tool.group == ToolGroup.BRUSH || tool.group == ToolGroup.FILL || tool.group == ToolGroup.SHAPES
        val activeLayer = v.layers.firstOrNull { it.index == v.currentLayerIndex }

        // 多指手势处理 (双指缩放/旋转/平移)
        if (!isStylus && (pointerCount >= 2 || isTransformActive)) {
            removeCallbacks(longPressRunnable)
            scaleDetector.onTouchEvent(event)

            if (pointerCount >= 2) {
                maxTouchPointers = maxOf(maxTouchPointers, pointerCount)
                val x0 = event.getX(0)
                val y0 = event.getY(0)
                val x1 = event.getX(1)
                val y1 = event.getY(1)
                val currentAngle = Math.toDegrees(atan2((y1 - y0).toDouble(), (x1 - x0).toDouble())).toFloat()

                when (event.actionMasked) {
                    MotionEvent.ACTION_POINTER_DOWN -> {
                        previousAngle = currentAngle
                        previousFocusX = (x0 + x1) / 2f
                        previousFocusY = (y0 + y1) / 2f
                        downPosA = Offset(x0, y0)
                        downPosB = Offset(x1, y1)
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dRot = normalizeAngle(currentAngle - previousAngle).coerceIn(-15f, 15f)
                        previousAngle = currentAngle
                        if (v.canvasRotationEnabled && abs(dRot) > 0.05f) {
                            canvasRotation += dRot
                            onTransform?.invoke(canvasZoom, canvasRotation, canvasPanX, canvasPanY)
                        }
                        val distA = hypot(x0 - downPosA.x, y0 - downPosA.y)
                        val distB = hypot(x1 - downPosB.x, y1 - downPosB.y)
                        maxTouchDisplacement = maxOf(maxTouchDisplacement, distA, distB)
                        if (maxTouchDisplacement > 8f * density) {
                            isPinchMotion = true
                        }
                    }
                }
            }

            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                val durationMs = SystemClock.uptimeMillis() - touchDownTimeMs
                // 双指轻点撤销判定
                if (!isPinchMotion && maxTouchPointers == 2 && v.gestureTwoFingerUndo && durationMs < 300L && maxTouchDisplacement < 16f * density) {
                    v.undo()
                } else if (!isPinchMotion && maxTouchPointers >= 3 && v.gestureThreeFingerRedo && durationMs < 340L && maxTouchDisplacement < 20f * density) {
                    v.redo()
                }
                isTransformActive = false
                isPinchMotion = false
                maxTouchPointers = 0
                maxTouchDisplacement = 0f
            }
            return true
        }

        // 单指手势 / 手写笔绘制处理
        val screenPos = Offset(event.x, event.y)
        val docPos = screenToDoc(screenPos)
        val pressure = if (isStylus) event.pressure.coerceIn(0f, 1f) else 1f

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchDownTimeMs = SystemClock.uptimeMillis()
                maxTouchPointers = 1
                maxTouchDisplacement = 0f
                previousSinglePos = screenPos
                firstDocPos = docPos
                shapeEndDocPos = docPos
                downPosA = screenPos
                isLongPressPickerActive = false

                // 笔模式下手指单指平移
                if (v.penOnlyMode && !isStylus) {
                    return true
                }

                if (v.longPressEyedropperEnabled && (if (v.penOnlyMode) isStylus else true)) {
                    postDelayed(longPressRunnable, 450)
                }

                cursorScreenPos?.value = screenPos
                isCursorTouching?.value = true
                isCursorHovering?.value = false
                livePressure?.value = pressure

                if (activeLayer?.isGroup == true && isDrawingTool) {
                    v.showActionToast("图层组不可直接绘制，请选择组内图层", R.drawable.ic_folder)
                    return true
                }
                if (activeLayer?.locked == true && isDrawingTool) {
                    v.showActionToast("图层已锁定，无法编辑", R.drawable.ic_lock)
                    return true
                }

                when (tool) {
                    Tool.BRUSH, Tool.ERASER, Tool.SMUDGE -> {
                        smoothedPressure = pressure
                        v.touchStart(docPos.x, docPos.y, pressure.toDouble())
                        strokeStarted = true
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
                        sampleColorAtScreenPos(screenPos)
                    }
                    Tool.FILL -> {
                        v.floodFill(firstDocPos.x, firstDocPos.y, fillTolerance)
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
                    else -> Unit
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val deltaX = screenPos.x - previousSinglePos.x
                val deltaY = screenPos.y - previousSinglePos.y
                previousSinglePos = screenPos
                val moveDist = hypot(screenPos.x - downPosA.x, screenPos.y - downPosA.y)
                maxTouchDisplacement = maxOf(maxTouchDisplacement, moveDist)

                if (moveDist > 8f * density) {
                    removeCallbacks(longPressRunnable)
                }

                // 笔模式下手指单指平移
                if (v.penOnlyMode && !isStylus) {
                    canvasPanX += deltaX
                    canvasPanY += deltaY
                    onTransform?.invoke(canvasZoom, canvasRotation, canvasPanX, canvasPanY)
                    return true
                }

                if (isLongPressPickerActive) {
                    sampleColorAtScreenPos(screenPos)
                    return true
                }

                cursorScreenPos?.value = screenPos
                livePressure?.value = pressure

                when (tool) {
                    Tool.BRUSH, Tool.ERASER, Tool.SMUDGE -> {
                        if (!strokeStarted) {
                            v.touchStart(firstDocPos.x, firstDocPos.y, pressure.toDouble())
                            strokeStarted = true
                        }
                        for (i in 0 until event.historySize) {
                            val hScreen = Offset(event.getHistoricalX(i), event.getHistoricalY(i))
                            val hDoc = screenToDoc(hScreen)
                            val hP = if (isStylus) event.getHistoricalPressure(i).coerceIn(0f, 1f) else 1f
                            v.touchMove(hDoc.x, hDoc.y, hP.toDouble())
                        }
                        v.touchMove(docPos.x, docPos.y, pressure.toDouble())
                    }
                    Tool.LIQUIFY -> {
                        if (strokeStarted) {
                            v.liquify(liquifyPrevPos.x, liquifyPrevPos.y, docPos.x, docPos.y, liquifyMode, liquifyStrength.toDouble())
                            liquifyPrevPos = docPos
                        }
                    }
                    Tool.PICKER -> {
                        sampleColorAtScreenPos(screenPos)
                    }
                    Tool.LINE, Tool.RECT, Tool.ELLIPSE, Tool.GRADIENT, Tool.SELECT_RECT, Tool.SELECT_ELLIPSE -> {
                        shapeEndDocPos = docPos
                        liveShapeEnd?.value = docPos
                    }
                    Tool.LASSO -> {
                        lassoPoints.add(docPos)
                        val now = System.nanoTime()
                        if (now - lastLassoPreviewNs > 33_000_000L) {
                            lastLassoPreviewNs = now
                            val p = Path().apply {
                                if (lassoPoints.isNotEmpty()) {
                                    moveTo(lassoPoints[0].x, lassoPoints[0].y)
                                    for (j in 1 until lassoPoints.size) {
                                        lineTo(lassoPoints[j].x, lassoPoints[j].y)
                                    }
                                }
                            }
                            liveSelectionPath?.value = p
                        }
                    }
                    Tool.MEASURE -> {
                        measureEnd?.value = docPos
                    }
                    Tool.MOVE -> {
                        shapeEndDocPos = docPos
                    }
                    else -> Unit
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPressRunnable)
                isCursorTouching?.value = false
                isCursorHovering?.value = isStylus
                if (!isStylus) {
                    cursorScreenPos?.value = null
                }

                // 笔模式下手指单指平移结束
                if (v.penOnlyMode && !isStylus) {
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
                    return true
                }

                when (tool) {
                    Tool.BRUSH, Tool.ERASER, Tool.SMUDGE -> {
                        if (strokeStarted) {
                            if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
                                v.touchCancel()
                            } else {
                                v.touchEnd()
                            }
                            strokeStarted = false
                        }
                    }
                    Tool.LIQUIFY -> {
                        if (strokeStarted) {
                            if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
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
                    Tool.MOVE -> {
                        val dx = (shapeEndDocPos.x - firstDocPos.x).roundToInt()
                        val dy = (shapeEndDocPos.y - firstDocPos.y).roundToInt()
                        if (dx != 0 || dy != 0) {
                            v.moveLayerContent(dx, dy)
                        }
                    }
                    Tool.PICKER -> {
                        pickerActive?.value = false
                    }
                    else -> Unit
                }
                return true
            }
        }

        return super.onTouchEvent(event)
    }
}
