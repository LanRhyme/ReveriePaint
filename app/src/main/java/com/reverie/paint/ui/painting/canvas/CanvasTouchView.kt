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
import com.reverie.paint.model.Tool
import com.reverie.paint.model.ToolGroup
import com.reverie.paint.ui.theme.parseColor
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt

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

    // 防抖撤销任务
    private var pendingUndoRunnable: Runnable? = null

    // 长按吸色状态机 (按住不动延迟取色；移动立即画线；调出吸色后可随意移动取色)
    private var isPendingLongPress = false
    private var pendingDownDocPos = Offset.Zero
    private var pendingDownScreenPos = Offset.Zero
    private var pendingDownPressure = 1f
    private var isLongPressPickerActive = false

    private val longPressRunnable = Runnable {
        val v = vm ?: return@Runnable
        if (isPendingLongPress && !isTransformActive && maxTouchPointers <= 1) {
            isPendingLongPress = false
            isLongPressPickerActive = true
            pickerActive?.value = true
            val refHex = v.brushColor
            pickerInitialColor?.value = parseColor(refHex)
            sampleColorAtScreenPos(pendingDownScreenPos)
        }
    }

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

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        activeTouchView = this
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        if (activeTouchView == this) activeTouchView = null
    }

    override fun onResolvePointerIcon(event: MotionEvent, pointerIndex: Int): PointerIcon? {
        val v = vm ?: return super.onResolvePointerIcon(event, pointerIndex)
        val hideCursor = (tool == Tool.BRUSH || tool == Tool.ERASER || tool == Tool.SMUDGE || tool == Tool.LIQUIFY) &&
            v.cursorStyleMode != 4
        return if (hideCursor && systemNullPointer != null) {
            systemNullPointer
        } else {
            super.onResolvePointerIcon(event, pointerIndex)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val v = vm ?: return
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
        if (shouldShow && isDrawTool && v.cursorStyleMode != 4) {
            val scale = (canvasZoom * canvasFitScale).coerceAtLeast(0.001f)
            val pressureScale = if (localIsTouching) localPressure.coerceIn(0.08f, 1f) else 1f
            val cursorBrushSize = if (tool == Tool.LIQUIFY) liquifyBrushSize else v.brushSize.toFloat()
            val brushRadiusScreen = (cursorBrushSize * scale * 0.5f * pressureScale).coerceAtLeast(2f)

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

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        return super.dispatchTouchEvent(event)
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
                localIsHovering = true
                localIsTouching = false
                localPressure = 1f
                invalidate()

                if (hideCursor && systemNullPointer != null && pointerIcon != systemNullPointer) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        pointerIcon = systemNullPointer
                    }
                }
                return true
            }
            MotionEvent.ACTION_HOVER_EXIT -> {
                localIsHovering = false
                localIsTouching = false
                localCursorPos = null
                invalidate()

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

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.isFromSource(android.view.InputDevice.SOURCE_CLASS_POINTER)) {
            if (event.actionMasked == MotionEvent.ACTION_HOVER_MOVE) {
                localCursorPos = Offset(event.x, event.y)
                localIsHovering = true
                localIsTouching = false
                invalidate()
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

        // 检查是否有手写笔接触屏幕
        var stylusPointerIndex = -1
        for (i in 0 until pointerCount) {
            val type = event.getToolType(i)
            if (type == MotionEvent.TOOL_TYPE_STYLUS || type == MotionEvent.TOOL_TYPE_ERASER) {
                stylusPointerIndex = i
                break
            }
        }
        val isStylusTouch = stylusPointerIndex >= 0

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
            if (hideCursor && systemNullPointer != null && pointerIcon != systemNullPointer) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    pointerIcon = systemNullPointer
                }
            }

            val canEyedrop = v.longPressEyedropperEnabled &&
                (tool == Tool.BRUSH || tool == Tool.ERASER || tool == Tool.SMUDGE || tool == Tool.LIQUIFY)

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    removeCallbacks(longPressRunnable)
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
                        val moveSlopPx = (1.5f - (v.eyedropperSensitivity.coerceIn(1, 5) - 3) * 0.3f).coerceIn(0.6f, 2.5f) * density
                        val moveDist = hypot(screenPos.x - pendingDownScreenPos.x, screenPos.y - pendingDownScreenPos.y)
                        if (moveDist > moveSlopPx) {
                            removeCallbacks(longPressRunnable)
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
        val nowMs = SystemClock.uptimeMillis()
        maxTouchPointers = maxOf(maxTouchPointers, pointerCount)
        isInteracting = true

        // 1. 多指手势 (双指捏合缩放/旋转/平移 + 碎片期融合)
        if (pointerCount >= 2 || (isTransformActive && (nowMs - lastTransformTimestamp) < 150)) {
            removeCallbacks(longPressRunnable)
            isPendingLongPress = false

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
                    isTransformActive = true
                    isPinchMotion = false
                    prevCentroid = centroid
                    prevDistance = distance
                    prevAngle = angle
                    initialCentroid = centroid
                    initialDistance = distance
                    initialAngle = angle
                    touchDownTimeMs = nowMs
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
                        }

                        // 围绕双指中心 (prevCentroid) 几何旋转与缩放补偿，保证手指标定点完全不动
                        val vx = prevCentroid.x - (viewW / 2f + canvasPanX)
                        val vy = prevCentroid.y - (viewH / 2f + canvasPanY)

                        val vRotX = k * (vx * cosR - vy * sinR)
                        val vRotY = k * (vx * sinR + vy * cosR)

                        val localZoom = (canvasZoom * k).coerceIn(0.05f, 32f)
                        val localPanX = centroid.x - vRotX - viewW / 2f
                        val localPanY = centroid.y - vRotY - viewH / 2f

                        canvasZoom = localZoom
                        canvasPanX = localPanX
                        canvasPanY = localPanY

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
            } else if (pointerCount == 1 && isTransformActive) {
                // 驱动碎片期（单指短暂存活）：持续补偿平移，零丢帧
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
                    postDelayed(resetTransformRunnable, 150)
                }
            }
            return true
        }

        // 2. 单指手势 (根据 vm.penOnlyMode 切换手指平移 vs 手指作画)
        val screenPos = Offset(event.x, event.y)
        val docPos = screenToDoc(screenPos)
        val canEyedrop = v.longPressEyedropperEnabled && !v.penOnlyMode &&
            (tool == Tool.BRUSH || tool == Tool.ERASER || tool == Tool.SMUDGE || tool == Tool.LIQUIFY)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchDownTimeMs = nowMs
                maxTouchPointers = 1
                previousSinglePos = screenPos
                firstDocPos = docPos
                shapeEndDocPos = docPos
                isLongPressPickerActive = false

                if (canEyedrop) {
                    isPendingLongPress = true
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
                } else if (!v.penOnlyMode) {
                    // 笔模式关闭：手指直接作画
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

                if (isLongPressPickerActive) {
                    sampleColorAtScreenPos(screenPos)
                    return true
                }

                if (v.penOnlyMode) {
                    // 笔模式开启：单指丝滑平移画布
                    canvasPanX += deltaX
                    canvasPanY += deltaY
                    onTransform?.invoke(canvasZoom, canvasRotation, canvasPanX, canvasPanY)
                    return true
                } else {
                    if (isPendingLongPress) {
                        val moveSlopPx = (1.5f - (v.eyedropperSensitivity.coerceIn(1, 5) - 3) * 0.3f).coerceIn(0.6f, 2.5f) * density
                        val moveDist = hypot(screenPos.x - pendingDownScreenPos.x, screenPos.y - pendingDownScreenPos.y)
                        if (moveDist > moveSlopPx) {
                            removeCallbacks(longPressRunnable)
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
                isInteracting = false

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

                if (!v.penOnlyMode) {
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
