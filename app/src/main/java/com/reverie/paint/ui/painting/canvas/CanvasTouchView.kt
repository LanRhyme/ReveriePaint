package com.reverie.paint.ui.painting.canvas

import android.content.Context
import android.graphics.Bitmap
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
import com.reverie.paint.model.Tool
import com.reverie.paint.model.ToolGroup
import com.reverie.paint.ui.theme.parseColor
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 原生 Android View 触控层 (连续多点解算 + 交互状态隔离)
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

    // 交互状态 (只要手指在屏幕上，就阻止外部 Compose update 回冲覆盖)
    var isInteracting = false
    var isTransformActive = false
    var isPinchMotion = false

    // 绘制状态
    private var strokeStarted = false
    private var firstDocPos = Offset.Zero
    private var shapeEndDocPos = Offset.Zero
    private var previousSinglePos = Offset.Zero
    private val lassoPoints = mutableListOf<Offset>()
    private var lastLassoPreviewNs = 0L
    private var liquifyPrevPos = Offset.Zero
    private var smoothedPressure = 0.8f

    // 多点变换持久状态
    private var prevCentroid = Offset.Zero
    private var prevDistance = 1f
    private var prevAngle = 0f
    private var initialCentroid = Offset.Zero
    private var initialDistance = 1f
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

    // 防抖撤销任务
    private var pendingUndoRunnable: Runnable? = null

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
    // 1. 悬停处理 (空中手写笔 / 鼠标)
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
            }
        }
        return false
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.isFromSource(android.view.InputDevice.SOURCE_CLASS_POINTER)) {
            if (event.actionMasked == MotionEvent.ACTION_HOVER_MOVE) {
                cursorScreenPos?.value = Offset(event.x, event.y)
                isCursorHovering?.value = true
                isCursorTouching?.value = false
            }
        }
        return super.onGenericMotionEvent(event)
    }

    // -------------------------------------------------------------
    // 2. 接触触控处理 (手写笔落笔 / 手指多点手势)
    // -------------------------------------------------------------
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val v = vm ?: return super.onTouchEvent(event)
        val pointerCount = event.pointerCount

        // 取消 pending 的撤销或会话重置
        pendingUndoRunnable?.let { removeCallbacks(it) }
        removeCallbacks(resetTransformRunnable)

        // 检查是否有手写笔参与触摸
        var stylusPointerIndex = -1
        for (i in 0 until pointerCount) {
            val type = event.getToolType(i)
            if (type == MotionEvent.TOOL_TYPE_STYLUS || type == MotionEvent.TOOL_TYPE_ERASER) {
                stylusPointerIndex = i
                break
            }
        }
        val isStylusTouch = stylusPointerIndex >= 0

        // 1. 手写笔落笔绘制逻辑
        if (isStylusTouch) {
            removeCallbacks(longPressRunnable)
            isTransformActive = false
            isPinchMotion = false
            isInteracting = true
            lastPos0 = Offset.Zero
            lastPos1 = Offset.Zero

            val x = event.getX(stylusPointerIndex)
            val y = event.getY(stylusPointerIndex)
            val screenPos = Offset(x, y)
            val docPos = screenToDoc(screenPos)
            val pressure = event.getPressure(stylusPointerIndex).coerceIn(0f, 1f)

            cursorScreenPos?.value = screenPos
            isCursorTouching?.value = true
            isCursorHovering?.value = false
            livePressure?.value = pressure

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    firstDocPos = docPos
                    shapeEndDocPos = docPos
                    handleToolDown(docPos, pressure, isStylus = true)
                }
                MotionEvent.ACTION_MOVE -> {
                    handleToolMove(event, stylusPointerIndex, docPos, pressure, isStylus = true)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handleToolUp(event, docPos, isCancel = (event.actionMasked == MotionEvent.ACTION_CANCEL))
                    isCursorTouching?.value = false
                    isCursorHovering?.value = true
                    isInteracting = false
                }
            }
            return true
        }

        // 2. 手指触控逻辑 (支持双指缩放/旋转、单指平移、单指绘制、轻点撤销)
        val nowMs = SystemClock.uptimeMillis()
        maxTouchPointers = maxOf(maxTouchPointers, pointerCount)
        isInteracting = true

        // 多指手势处理 (直接几何解算 + 碎片期单指补偿)
        if (pointerCount >= 2 || (isTransformActive && (nowMs - lastTransformTimestamp) < 150)) {
            removeCallbacks(longPressRunnable)
            if (strokeStarted) {
                if (tool == Tool.LIQUIFY) v.liquifyCancel() else v.touchCancel()
                strokeStarted = false
            }

            if (pointerCount >= 2) {
                val raw0 = Offset(event.getX(0), event.getY(0))
                val raw1 = Offset(event.getX(1), event.getY(1))

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
                    // 开始新会话
                    isTransformActive = true
                    isPinchMotion = false
                    prevCentroid = centroid
                    prevDistance = distance
                    prevAngle = angle
                    initialCentroid = centroid
                    initialDistance = distance
                    touchDownTimeMs = nowMs
                } else {
                    val distCentroidMoved = hypot(centroid.x - prevCentroid.x, centroid.y - prevCentroid.y)
                    // 若驱动复活指针引起位置巨大跳变，平滑重锚定
                    if (distCentroidMoved > 80f * density) {
                        prevCentroid = centroid
                        prevDistance = distance
                        prevAngle = angle
                    } else {
                        val k = (distance / prevDistance).coerceIn(0.7f, 1.4f)
                        val dRot = normalizeAngle(angle - prevAngle).coerceIn(-15f, 15f)
                        val dPanX = centroid.x - prevCentroid.x
                        val dPanY = centroid.y - prevCentroid.y

                        val totalMoved = hypot(centroid.x - initialCentroid.x, centroid.y - initialCentroid.y)
                        val scaleRatio = distance / initialDistance
                        if (totalMoved > 6f * density || abs(scaleRatio - 1f) > 0.02f) {
                            isPinchMotion = true
                        }

                        val localZoom = (canvasZoom * k).coerceIn(0.05f, 32f)
                        var localPanX = canvasPanX + dPanX
                        var localPanY = canvasPanY + dPanY

                        // 围绕缩放中心几何补偿
                        val centerX = viewW / 2f + canvasPanX
                        val centerY = viewH / 2f + canvasPanY
                        val fx = prevCentroid.x - centerX
                        val fy = prevCentroid.y - centerY
                        localPanX += fx * (1f - k)
                        localPanY += fy * (1f - k)

                        canvasZoom = localZoom
                        canvasPanX = localPanX
                        canvasPanY = localPanY

                        if (v.canvasRotationEnabled && abs(dRot) > 0.02f) {
                            canvasRotation += dRot
                        }

                        onTransform?.invoke(canvasZoom, canvasRotation, canvasPanX, canvasPanY)

                        prevCentroid = centroid
                        prevDistance = distance
                        prevAngle = angle
                    }
                }
                lastTransformTimestamp = nowMs
            } else if (pointerCount == 1 && isTransformActive) {
                // 驱动碎片期（单指短暂存活）：持续将存活手指位移转化为平移，确保 100% 零丢帧
                val cur = Offset(event.x, event.y)
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
                MotionEvent.ACTION_UP -> {
                    // 真实物理抬手：判定撤销
                    val durationMs = nowMs - touchDownTimeMs
                    isInteracting = false
                    if (!isPinchMotion && maxTouchPointers == 2 && v.gestureTwoFingerUndo && durationMs < 320L) {
                        val undoRunnable = Runnable {
                            v.undo()
                            isTransformActive = false
                            isPinchMotion = false
                            maxTouchPointers = 0
                            lastPos0 = Offset.Zero
                            lastPos1 = Offset.Zero
                        }
                        pendingUndoRunnable = undoRunnable
                        postDelayed(undoRunnable, 90)
                    } else if (!isPinchMotion && maxTouchPointers >= 3 && v.gestureThreeFingerRedo && durationMs < 360L) {
                        val redoRunnable = Runnable {
                            v.redo()
                            isTransformActive = false
                            isPinchMotion = false
                            maxTouchPointers = 0
                            lastPos0 = Offset.Zero
                            lastPos1 = Offset.Zero
                        }
                        pendingUndoRunnable = redoRunnable
                        postDelayed(redoRunnable, 90)
                    } else {
                        isTransformActive = false
                        isPinchMotion = false
                        maxTouchPointers = 0
                        lastPos0 = Offset.Zero
                        lastPos1 = Offset.Zero
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    // 驱动层 CANCEL：绝不触发撤销！保持 150ms 会话防止下一帧顿挫
                    postDelayed(resetTransformRunnable, 150)
                }
            }
            return true
        }

        // 单指处理 (常规模式)
        val screenPos = Offset(event.x, event.y)
        val docPos = screenToDoc(screenPos)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchDownTimeMs = nowMs
                maxTouchPointers = 1
                previousSinglePos = screenPos
                firstDocPos = docPos
                shapeEndDocPos = docPos
                isLongPressPickerActive = false

                // 笔模式下单指平移
                if (v.penOnlyMode) {
                    return true
                }

                if (v.longPressEyedropperEnabled) {
                    postDelayed(longPressRunnable, 450)
                }

                cursorScreenPos?.value = screenPos
                isCursorTouching?.value = true
                isCursorHovering?.value = false
                livePressure?.value = 1f

                handleToolDown(docPos, 1f, isStylus = false)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val deltaX = screenPos.x - previousSinglePos.x
                val deltaY = screenPos.y - previousSinglePos.y
                previousSinglePos = screenPos
                val moveDist = hypot(screenPos.x - firstDocPos.x, screenPos.y - firstDocPos.y)

                if (moveDist > 8f * density) {
                    removeCallbacks(longPressRunnable)
                }

                // 笔模式下单指平移
                if (v.penOnlyMode) {
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
                livePressure?.value = 1f

                handleToolMove(event, 0, docPos, 1f, isStylus = false)
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPressRunnable)
                isCursorTouching?.value = false
                isCursorHovering?.value = false
                cursorScreenPos?.value = null
                isInteracting = false

                // 笔模式下单指平移结束
                if (v.penOnlyMode) {
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

                handleToolUp(event, docPos, isCancel = (event.actionMasked == MotionEvent.ACTION_CANCEL))
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
        if (activeLayer?.locked == true && isDrawingTool) {
            v.showActionToast("图层已锁定，无法编辑", R.drawable.ic_lock)
            return
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
            else -> Unit
        }
    }

    private fun handleToolMove(event: MotionEvent, pointerIndex: Int, docPos: Offset, pressure: Float, isStylus: Boolean) {
        val v = vm ?: return

        when (tool) {
            Tool.BRUSH, Tool.ERASER, Tool.SMUDGE -> {
                if (!strokeStarted) {
                    v.touchStart(firstDocPos.x, firstDocPos.y, pressure.toDouble())
                    strokeStarted = true
                }
                for (i in 0 until event.historySize) {
                    val hScreen = Offset(event.getHistoricalX(pointerIndex, i), event.getHistoricalY(pointerIndex, i))
                    val hDoc = screenToDoc(hScreen)
                    val hP = if (isStylus) event.getHistoricalPressure(pointerIndex, i).coerceIn(0f, 1f) else 1f
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
                sampleColorAtScreenPos(previousSinglePos)
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
    }

    private fun handleToolUp(event: MotionEvent, docPos: Offset, isCancel: Boolean) {
        val v = vm ?: return

        when (tool) {
            Tool.BRUSH, Tool.ERASER, Tool.SMUDGE -> {
                if (strokeStarted) {
                    if (isCancel) {
                        v.touchCancel()
                    } else {
                        v.touchEnd()
                    }
                    strokeStarted = false
                }
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
    }
}
