package com.reverie.paint.ui.painting

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import com.reverie.paint.R
import com.reverie.paint.core.*
import com.reverie.paint.model.Tool
import com.reverie.paint.ui.theme.Morandi
import com.reverie.paint.ui.theme.parseColor
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * Full workspace canvas with one shared forward and inverse transform
 *
 * The pointer handler deliberately does not key on zoom/pan/rotation. Those
 * states change on every gesture event; keying on them cancels pointerInput
 * during the gesture and was the reason pinch/rotate stopped after one frame
 */
private enum class GestureMode { NONE, STROKE, PAN, MOVE, TRANSFORM }

internal fun tfTransform(
    tfState: TransformState,
    p: Offset,
): Offset {
    val c = tfState.bounds.center
    val dx = p.x - c.x
    val dy = p.y - c.y
    val sx = dx * tfState.scaleX
    val sy = dy * tfState.scaleY
    val rad = Math.toRadians(tfState.rotation.toDouble())
    val cos = kotlin.math.cos(rad).toFloat()
    val sin = kotlin.math.sin(rad).toFloat()
    val rx = sx * cos - sy * sin
    val ry = sx * sin + sy * cos
    return Offset(rx + c.x + tfState.tx, ry + c.y + tfState.ty)
}

internal fun tfHandles(tfState: TransformState): List<Offset> {
    if (tfState.mode == TransformMode.PERSPECTIVE) {
        return tfState.quadCorners
    }
    if (tfState.mode == TransformMode.DISTORT) {
        return tfState.meshPoints
    }
    val r = tfState.bounds
    val corners = listOf(r.topLeft, r.topRight, r.bottomRight, r.bottomLeft)
    val mids =
        listOf(
            Offset((r.left + r.right) / 2f, r.top),
            Offset(r.right, (r.top + r.bottom) / 2f),
            Offset((r.left + r.right) / 2f, r.bottom),
            Offset(r.left, (r.top + r.bottom) / 2f),
        )
    return corners.map { tfTransform(tfState, it) } + mids.map { tfTransform(tfState, it) }
}

internal suspend fun androidx.compose.ui.input.pointer.PointerInputScope.awaitCanvasGesture(
    image: android.graphics.Bitmap,
    bmp: android.graphics.Bitmap?,
    tool: Tool,
    vm: PaintViewModel,
    tfState: TransformState,
    viewW: () -> Int,
    viewH: () -> Int,
    latestZoom: () -> Float,
    latestRotation: () -> Float,
    latestPanX: () -> Float,
    latestPanY: () -> Float,
    latestFitScale: () -> Float,
    zoom: Float,
    fitScale: Float,
    liveShapeStart: androidx.compose.runtime.MutableState<Offset?>,
    liveShapeEnd: androidx.compose.runtime.MutableState<Offset?>,
    livePressure: androidx.compose.runtime.MutableState<Float>,
    measureStart: androidx.compose.runtime.MutableState<Offset?>,
    measureEnd: androidx.compose.runtime.MutableState<Offset?>,
    wandFlash: androidx.compose.runtime.MutableState<Offset?>,
    pickerActive: androidx.compose.runtime.MutableState<Boolean>,
    pickerScreenPos: androidx.compose.runtime.MutableState<Offset>,
    pickerInitialColor: androidx.compose.runtime.MutableState<Color>,
    pickerCurrentColor: androidx.compose.runtime.MutableState<Color>,
    liveSelectionPath: androidx.compose.runtime.MutableState<androidx.compose.ui.graphics.Path?>,
    cursorScreenPos: androidx.compose.runtime.MutableState<Offset?>,
    isCursorHovering: androidx.compose.runtime.MutableState<Boolean>,
    isCursorTouching: androidx.compose.runtime.MutableState<Boolean>,
    polyPoints: List<Offset>,
    onPolyPoint: (Offset) -> Unit,
    cropRect: androidx.compose.ui.geometry.Rect?,
    onCropRect: (androidx.compose.ui.geometry.Rect?) -> Unit,
    fillTolerance: Int,
    gradientType: Int,
    liquifyStrength: Float,
    liquifyMode: Int,
    onTransform: (zoom: Float, rotation: Float, panX: Float, panY: Float) -> Unit,
    onTextRequested: (x: Float, y: Float) -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val docW = image.width
        val docH = image.height

        var localZoom = latestZoom()
        var localRotation = latestRotation()
        var localPanX = latestPanX()
        var localPanY = latestPanY()
        val shapeTool = tool == Tool.LINE || tool == Tool.RECT || tool == Tool.ELLIPSE
        val pointClickTool =
            tool == Tool.POLYGON || tool == Tool.POLYLINE || tool == Tool.PATH || tool == Tool.SELECT_POLYGON
        val twoPointTool =
            tool == Tool.GRADIENT || tool == Tool.SELECT_RECT ||
                tool == Tool.SELECT_ELLIPSE
        val trackSelectTool =
            tool == Tool.SELECT_MAGNETIC
        val stylus =
            down.type == androidx.compose.ui.input.pointer.PointerType.Stylus ||
                down.type == androidx.compose.ui.input.pointer.PointerType.Eraser
        var mode =
            when {
                vm.isFilterAdjustActive -> GestureMode.PAN

                vm.penOnlyMode && !stylus && (tool == Tool.BRUSH || tool == Tool.ERASER) -> GestureMode.PAN

                tool == Tool.FILL || tool == Tool.TEXT ||
                    tool == Tool.MAGICWAND || tool == Tool.SELECT_SIMILAR ||
                    tool == Tool.POLYGON || tool == Tool.POLYLINE || tool == Tool.PATH || tool == Tool.SELECT_POLYGON -> GestureMode.NONE

                tool == Tool.MOVE -> GestureMode.STROKE

                else -> GestureMode.STROKE
            }
        var strokeStarted = false
        var transformStarted = false
        var smoothedPressure = 0.8f
        var shapeEnd = Offset.Zero
        var replaceCleared = false
        val lassoPoints = mutableListOf<Offset>()
        var magneticPrev: Offset? = null
        var gestureEnded = false
        var lastLassoPreviewNs = 0L
        var liquifyPrevious = Offset.Zero
        var previousSinglePoint = down.position

        val firstImage =
            widgetToImage(
                down.position,
                viewW(),
                viewH(),
                localPanX,
                localPanY,
                localZoom,
                latestFitScale(),
                localRotation,
                image.width,
                image.height,
                vm.docWidth,
                vm.docHeight,
            )
        shapeEnd = firstImage
        lassoPoints += firstImage
        magneticPrev = null
        liquifyPrevious = firstImage

        if (shapeTool || twoPointTool) {
            liveShapeStart.value = firstImage
            liveShapeEnd.value = firstImage
        }

        var pendingTap: Offset? = null
        var tapReverted = false
        when (tool) {
            Tool.TRANSFORM, Tool.MOVE -> {
                if (!tfState.active) {
                    val b = vm.contentBounds()
                    if (b != null && b[2] > 0 && b[3] > 0) {
                        tfState.reset(
                            androidx.compose.ui.geometry.Rect(
                                b[0].toFloat(),
                                b[1].toFloat(),
                                (b[0] + b[2]).toFloat(),
                                (b[1] + b[3]).toFloat(),
                            ),
                        )
                    } else {
                        tfState.reset(
                            androidx.compose.ui.geometry.Rect(
                                0f,
                                0f,
                                vm.docWidth.toFloat(),
                                vm.docHeight.toFloat(),
                            ),
                        )
                    }
                    vm.startTransformPreview()
                }
                if (tool == Tool.MOVE) {
                    tfState.handle = 8 // Translate only
                } else {
                    val handles = tfHandles(tfState)
                    val currentScale = zoom * fitScale
                    val hitThresholdDoc = (32.dp.toPx()) / maxOf(0.01f, currentScale)
                    var best = -1
                    var bestD = hitThresholdDoc
                    for (i in handles.indices) {
                        val d =
                            hypot(
                                handles[i].x - firstImage.x,
                                handles[i].y - firstImage.y,
                            )
                        if (d < bestD) {
                            bestD = d
                            best = i
                        }
                    }
                    if (tfState.mode == TransformMode.PERSPECTIVE) {
                        tfState.handle = if (best in 0..3) best else 8
                    } else if (tfState.mode == TransformMode.DISTORT) {
                        tfState.handle = if (best in 0..15) best else 99
                    } else {
                        val c = tfState.bounds.center
                        val dx = firstImage.x - c.x - tfState.tx
                        val dy = firstImage.y - c.y - tfState.ty
                        val rad = Math.toRadians(-tfState.rotation.toDouble())
                        val cosR = cos(rad).toFloat()
                        val sinR = sin(rad).toFloat()
                        val ux = (dx * cosR - dy * sinR) / tfState.scaleX
                        val uy = (dx * sinR + dy * cosR) / tfState.scaleY
                        val inBox =
                            ux >= -tfState.bounds.width / 2f &&
                                ux <= tfState.bounds.width / 2f &&
                                uy >= -tfState.bounds.height / 2f &&
                                uy <= tfState.bounds.height / 2f
                        tfState.handle =
                            if (best >= 0) {
                                best
                            } else if (inBox) {
                                8
                            } else {
                                9
                            }
                    }
                }
                tfState.dragStart = firstImage
                tfState.startScaleX = tfState.scaleX
                tfState.startScaleY = tfState.scaleY
                tfState.startRotation = tfState.rotation
                tfState.startTx = tfState.tx
                tfState.startTy = tfState.ty
                tfState.startQuadCorners = tfState.quadCorners
                tfState.startMeshPoints = tfState.meshPoints
            }

            Tool.POLYGON, Tool.POLYLINE, Tool.SELECT_POLYGON, Tool.PATH -> {
                onPolyPoint(firstImage)
            }

            Tool.CROP -> {
                onCropRect(null)
            }

            Tool.MEASURE -> {
                measureStart.value = firstImage
                measureEnd.value = firstImage
            }

            Tool.PICKER -> {
                pickerActive.value = true
                val targetOffset = Offset(-48.dp.toPx(), -48.dp.toPx())
                val sampleScreenPos = down.position + targetOffset
                pickerScreenPos.value = sampleScreenPos
                val refHex = vm.brushColor
                pickerInitialColor.value = parseColor(refHex)
                val sampleImage =
                    widgetToImage(
                        sampleScreenPos,
                        viewW(),
                        viewH(),
                        localPanX,
                        localPanY,
                        localZoom,
                        latestFitScale(),
                        localRotation,
                        image.width,
                        image.height,
                        vm.docWidth,
                        vm.docHeight,
                    )
                val ix = sampleImage.x.toInt()
                val iy = sampleImage.y.toInt()
                if (ix in 0 until image.width && iy in 0 until image.height) {
                    val pixel = image.getPixel(ix, iy)
                    if (android.graphics.Color.alpha(pixel) > 0) {
                        pickerCurrentColor.value = Color(pixel)
                    }
                }
            }

            Tool.MAGICWAND -> {
                wandFlash.value = firstImage
                vm.selectContiguous(firstImage.x.toInt(), firstImage.y.toInt())
                tapReverted = true
            }

            Tool.SELECT_SIMILAR -> {
                wandFlash.value = firstImage
                vm.selectSimilar(firstImage.x.toInt(), firstImage.y.toInt())
                tapReverted = true
            }

            Tool.FILL -> {
                vm.floodFill(firstImage.x, firstImage.y, fillTolerance)
                tapReverted = true
            }

            Tool.TEXT -> {
                pendingTap = firstImage
            }

            else -> {
                Unit
            }
        }

        val gestureStartNs = System.nanoTime()
        var maxFingerCount = 1
        val initialFingerPositions = mutableMapOf<androidx.compose.ui.input.pointer.PointerId, Offset>()
        initialFingerPositions[down.id] = down.position
        var maxFingerMovement = 0f
        val startZoom = localZoom
        val startRot = localRotation
        val startPanX = localPanX
        val startPanY = localPanY

        val canLongPressPick =
            vm.longPressEyedropperEnabled &&
                (if (vm.penOnlyMode) stylus else true) &&
                (
                    tool == Tool.BRUSH || tool == Tool.ERASER || tool == Tool.SMUDGE || tool == Tool.MOVE ||
                        mode == GestureMode.STROKE ||
                        mode == GestureMode.PAN
                )
        val eyedropperDelayMs =
            when (vm.eyedropperSensitivity) {
                1 -> 450L
                2 -> 360L
                3 -> 280L
                4 -> 220L
                5 -> 170L
                else -> 280L
            }
        val eyedropperMaxMovePx =
            when (vm.eyedropperSensitivity) {
                1 -> 1.5f
                2 -> 2.0f
                3 -> 2.8f
                4 -> 3.4f
                5 -> 4.0f
                else -> 2.8f
            }.dp.toPx()
        var isLongPressPickerActive = false

        fun sampleColorAtScreenPos(screenPos: Offset) {
            val samplePos =
                if (vm.eyedropperOffsetEnabled) {
                    val targetOffset = Offset(-48.dp.toPx(), -48.dp.toPx())
                    screenPos + targetOffset
                } else {
                    screenPos
                }
            pickerScreenPos.value = samplePos
            val sampleImage =
                widgetToImage(
                    samplePos,
                    viewW(),
                    viewH(),
                    localPanX,
                    localPanY,
                    localZoom,
                    latestFitScale(),
                    localRotation,
                    image.width,
                    image.height,
                    vm.docWidth,
                    vm.docHeight,
                )
            val ix = sampleImage.x.toInt()
            val iy = sampleImage.y.toInt()
            if (ix in 0 until image.width && iy in 0 until image.height) {
                val pixel = image.getPixel(ix, iy)
                if (android.graphics.Color.alpha(pixel) > 0) {
                    pickerCurrentColor.value = Color(pixel)
                }
            }
        }

        // Two-finger transform: per-event incremental deltas,
        // anchored at the two-finger centroid (Procreate style)
        var prevCentroid = Offset.Zero
        var prevDistance = 1f
        var prevAngle = 0f
        var transformMoved = false
        var initialCentroid = Offset.Zero
        var initialDistance = 1f
        var initialAngle = 0f

        while (true) {
            val event = awaitPointerEvent()
            val pressed = event.changes.filter { it.pressed }
            if (pressed.isEmpty()) {
                val gestureDurationMs = (System.nanoTime() - gestureStartNs) / 1_000_000L
                val tapMaxDistPx = 36.dp.toPx()

                val isTwoFingerTap =
                    (maxFingerCount == 2) && vm.gestureTwoFingerUndo && (gestureDurationMs < 360L) &&
                        (maxFingerMovement < tapMaxDistPx)
                val isThreeFingerTap =
                    (maxFingerCount >= 3) && vm.gestureThreeFingerRedo && (gestureDurationMs < 400L) &&
                        (maxFingerMovement < tapMaxDistPx * 1.3f)

                if (isLongPressPickerActive) {
                    val r = (pickerCurrentColor.value.red * 255).toInt().coerceIn(0, 255)
                    val g = (pickerCurrentColor.value.green * 255).toInt().coerceIn(0, 255)
                    val b = (pickerCurrentColor.value.blue * 255).toInt().coerceIn(0, 255)
                    val hex = String.format("#%02X%02X%02X", r, g, b)
                    vm.updateBrushColor(hex)
                    vm.showActionToast("已吸取颜色", R.drawable.ic_picker)
                    pickerActive.value = false
                    isLongPressPickerActive = false
                } else if (isTwoFingerTap) {
                    if (transformStarted) {
                        onTransform(startZoom, startRot, startPanX, startPanY)
                    }
                    if (strokeStarted) {
                        vm.touchCancel()
                        strokeStarted = false
                    }
                    vm.undo()
                } else if (isThreeFingerTap) {
                    if (transformStarted) {
                        onTransform(startZoom, startRot, startPanX, startPanY)
                    }
                    if (strokeStarted) {
                        vm.touchCancel()
                        strokeStarted = false
                    }
                    vm.redo()
                } else if (!transformStarted) {
                    pendingTap?.let { tap ->
                        if (tool == Tool.TEXT) {
                            onTextRequested(tap.x, tap.y)
                        }
                    }
                    when {
                        tool == Tool.MAGICWAND ||
                            tool == Tool.SELECT_SIMILAR ||
                            tool == Tool.FILL -> {
                            Unit
                        }

                        tool == Tool.TRANSFORM -> {
                            tfState.handle = -1
                        }

                        tool == Tool.CROP -> {
                            Unit
                        }

                        tool == Tool.MEASURE -> {
                            Unit
                        }

                        shapeTool -> {
                            val kind =
                                when (tool) {
                                    Tool.RECT -> 1
                                    Tool.ELLIPSE -> 2
                                    else -> 0
                                }
                            vm.drawShape(kind, firstImage.x, firstImage.y, shapeEnd.x, shapeEnd.y)
                        }

                        pointClickTool -> {
                            Unit
                        }

                        twoPointTool -> {
                            val x1 = firstImage.x.toInt()
                            val y1 = firstImage.y.toInt()
                            val x2 = shapeEnd.x.toInt()
                            val y2 = shapeEnd.y.toInt()
                            when (tool) {
                                Tool.GRADIENT -> vm.gradientFill(x1, y1, x2, y2, gradientType)
                                Tool.SELECT_RECT -> vm.selectShape(0, x1, y1, x2, y2)
                                Tool.SELECT_ELLIPSE -> vm.selectShape(1, x1, y1, x2, y2)
                                else -> Unit
                            }
                        }

                        trackSelectTool -> {
                            gestureEnded = true
                            if (lassoPoints.size >= 3) {
                                val points = lassoPoints.map { it.x.toInt() to it.y.toInt() }
                                vm.previewLassoSync(points)
                                vm.lassoSelect(points)
                            }
                        }

                        tool == Tool.MOVE -> {
                            val dx = tfState.tx.toInt()
                            val dy = tfState.ty.toInt()
                            tfState.tx = 0f
                            tfState.ty = 0f
                            vm.transformPreviewBitmap = null
                            if (dx != 0 || dy != 0) {
                                val b = vm.contentBounds()
                                if (b != null && b[2] > 0 && b[3] > 0) {
                                    tfState.bounds =
                                        androidx.compose.ui.geometry.Rect(
                                            b[0].toFloat(),
                                            b[1].toFloat(),
                                            (b[0] + b[2]).toFloat(),
                                            (b[1] + b[3]).toFloat(),
                                        )
                                }
                                vm.moveLayerContent(dx, dy)
                            } else {
                                vm.startTransformPreview()
                            }
                        }

                        tool == Tool.LASSO -> {
                            gestureEnded = true
                            if (lassoPoints.size >= 3) {
                                val points = lassoPoints.map { it.x.toInt() to it.y.toInt() }
                                vm.previewLassoSync(points)
                                vm.selectPolygon(points)
                            }
                        }

                        tool == Tool.PICKER -> {
                            val r = (pickerCurrentColor.value.red * 255).toInt().coerceIn(0, 255)
                            val g = (pickerCurrentColor.value.green * 255).toInt().coerceIn(0, 255)
                            val b = (pickerCurrentColor.value.blue * 255).toInt().coerceIn(0, 255)
                            val hex = String.format("#%02X%02X%02X", r, g, b)
                            vm.updateBrushColor(hex)
                        }

                        strokeStarted -> {
                            vm.touchEnd()
                        }

                        mode == GestureMode.STROKE -> {
                            vm.touchStart(
                                firstImage.x,
                                firstImage.y,
                                if (stylus) down.pressure.coerceIn(0f, 1f).toDouble() else 1.0,
                            )
                            vm.touchEnd()
                        }
                    }

                    pickerActive.value = false
                }
                break
            }

            maxFingerCount = maxOf(maxFingerCount, pressed.size)
            for (p in pressed) {
                val init = initialFingerPositions.getOrPut(p.id) { p.position }
                val dist = hypot(p.position.x - init.x, p.position.y - init.y)
                maxFingerMovement = maxOf(maxFingerMovement, dist)
            }

            if (pressed.size >= 2) {
                val pair = pressed.sortedBy { it.id.value }.take(2)
                val a = pair[0].position
                val b = pair[1].position
                val centroid = Offset((a.x + b.x) / 2f, (a.y + b.y) / 2f)
                val distance = hypot(b.x - a.x, b.y - a.y).coerceAtLeast(1f)
                val angle = angleDegrees(a, b)

                if (!transformStarted) {
                    pendingTap = null
                    if (strokeStarted) {
                        vm.touchCancel()
                        strokeStarted = false
                    }
                    if (tapReverted) {
                        // A second finger means zoom/pan, not a
                        // wand tap: revert the just-applied
                        // selection / fill (Krita's stroke-based
                        // selection is cancelled the same way)
                        tapReverted = false
                        vm.undo()
                    }
                    transformStarted = true
                    mode = GestureMode.TRANSFORM
                    prevCentroid = centroid
                    prevDistance = distance
                    prevAngle = angle
                    initialCentroid = centroid
                    initialDistance = distance
                    initialAngle = angle
                } else {
                    val distMoved = hypot(centroid.x - initialCentroid.x, centroid.y - initialCentroid.y)
                    val scaleRatio = distance / initialDistance
                    val angleDiff = kotlin.math.abs(normalizeAngle(angle - initialAngle))

                    // Deadzone check: do not emit onTransform until intentional movement occurs
                    if (!transformMoved) {
                        if (distMoved > 14f || kotlin.math.abs(scaleRatio - 1f) > 0.04f || angleDiff > 2.5f) {
                            transformMoved = true
                        }
                    }

                    if (transformMoved) {
                        // Exact incremental transform: the document
                        // point under the previous centroid lands
                        // exactly under the current centroid. The
                        // clamps only guard against degenerate
                        // events (a freshly landed second finger
                        // makes prevDistance tiny) - they are wide
                        // enough not to bind during normal pinches.
                        val k = (distance / prevDistance).coerceIn(0.2f, 5f)
                        val dRot = normalizeAngle(angle - prevAngle).coerceIn(-25f, 25f)

                        // Rotate the vector from the old center to
                        // the PREVIOUS centroid by dRot, scale by k,
                        // then place the new center so that point
                        // lands under the current centroid.
                        val centerX = viewW() / 2f + localPanX
                        val centerY = viewH() / 2f + localPanY
                        val vx = prevCentroid.x - centerX
                        val vy = prevCentroid.y - centerY
                        val radians = Math.toRadians(dRot.toDouble())
                        val cosR = cos(radians).toFloat()
                        val sinR = sin(radians).toFloat()
                        val rx = vx * cosR - vy * sinR
                        val ry = vx * sinR + vy * cosR

                        localZoom = (localZoom * k).coerceIn(0.05f, 32f)
                        localRotation += dRot
                        localPanX = centroid.x - k * rx - viewW() / 2f
                        localPanY = centroid.y - k * ry - viewH() / 2f

                        onTransform(localZoom, localRotation, localPanX, localPanY)
                    }
                    prevCentroid = centroid
                    prevDistance = distance
                    prevAngle = angle
                }
                pair.forEach { it.consume() }
                continue
            }

            // After a two-finger gesture, never turn the remaining
            // finger into a new stroke during the same gesture
            if (transformStarted) {
                event.changes.forEach { it.consume() }
                continue
            }

            val point = pressed.first()

            if (isLongPressPickerActive) {
                sampleColorAtScreenPos(point.position)
                point.consume()
                continue
            }

            if (canLongPressPick && !transformStarted && pressed.size == 1) {
                val moveDist = hypot(point.position.x - down.position.x, point.position.y - down.position.y)
                if (moveDist <= eyedropperMaxMovePx) {
                    val elapsedMs = (System.nanoTime() - gestureStartNs) / 1_000_000L
                    if (elapsedMs >= eyedropperDelayMs) {
                        isLongPressPickerActive = true
                        if (strokeStarted) {
                            vm.touchCancel()
                            strokeStarted = false
                        }
                        pendingTap = null
                        mode = GestureMode.NONE
                        pickerActive.value = true
                        val refHex = vm.brushColor
                        pickerInitialColor.value = parseColor(refHex)
                        sampleColorAtScreenPos(point.position)
                        point.consume()
                        continue
                    }
                }
                val delta = point.position - previousSinglePoint
                previousSinglePoint = point.position
                // A real drag (not a tap) must not fire the tap tool
                if (pendingTap != null && hypot(delta.x, delta.y) > 8f) {
                    pendingTap = null
                }
                val imagePos =
                    widgetToImage(
                        point.position,
                        viewW(),
                        viewH(),
                        localPanX,
                        localPanY,
                        localZoom,
                        latestFitScale(),
                        localRotation,
                        image.width,
                        image.height,
                        vm.docWidth,
                        vm.docHeight,
                    )

                when (mode) {
                    GestureMode.PAN -> {
                        localPanX += delta.x
                        localPanY += delta.y
                        onTransform(localZoom, localRotation, localPanX, localPanY)
                        point.consume()
                    }

                    GestureMode.MOVE -> {
                        // MOVE tool: track the finger's document
                        // delta; the whole layer shifts by the
                        // first->last offset on release.
                        if (!strokeStarted) {
                            strokeStarted = true
                        }
                        shapeEnd = imagePos
                        point.consume()
                    }

                    GestureMode.STROKE -> {
                        when {
                            // One-shot tools already ran on finger-
                            // down (Krita selects on primary action):
                            // any subsequent move/up of this gesture
                            // must do nothing - otherwise a small
                            // finger wiggle after a wand tap would
                            // fall into the painting branch and draw
                            // a brush dab over the fresh selection
                            tool == Tool.MAGICWAND ||
                                tool == Tool.SELECT_SIMILAR ||
                                tool == Tool.FILL -> {
                                // consumed below
                            }

                            // Selection tools: live path preview in real
                            // time (must come before the twoPointTool
                            // branch because SELECT_RECT/ELLIPSE are
                            // two-point tools). The preview path is
                            // drawn in canvas space (origin at the image
                            // centre), so document coords are shifted by
                            // (-docW/2, -docH/2) to match drawImage.
                            tool == Tool.SELECT_RECT ||
                                tool == Tool.SELECT_ELLIPSE ||
                                tool == Tool.SELECT_POLYGON ||
                                tool == Tool.LASSO ||
                                tool == Tool.SELECT_MAGNETIC -> {
                                // Replace mode: first real move
                                // clears the previous selection
                                // display (finger-down alone must
                                // not, or two-finger pan/zoom would
                                // wipe it too)
                                if (!replaceCleared && vm.selectionMode == 0) {
                                    vm.clearSelectionOverlayLocal()
                                    replaceCleared = true
                                }
                                shapeEnd = imagePos
                                // The preview path is drawn in the
                                // canvas-bitmap space (origin at the
                                // bitmap centre); the gesture coords
                                // are full-document space, so they
                                // are scaled into bitmap space first
                                val bmpW = bmp?.width ?: 0
                                val bmpH = bmp?.height ?: 0
                                val docW = vm.docWidth
                                val docH = vm.docHeight
                                val scX = if (docW > 0) bmpW.toFloat() / docW else 1f
                                val scY = if (docH > 0) bmpH.toFloat() / docH else 1f
                                val bx = { v: Float -> v * scX - bmpW / 2f }
                                val by = { v: Float -> v * scY - bmpH / 2f }
                                val pth =
                                    androidx.compose.ui.graphics
                                        .Path()
                                when (tool) {
                                    Tool.SELECT_RECT -> {
                                        pth.addRect(
                                            androidx.compose.ui.geometry.Rect(
                                                bx(minOf(firstImage.x, imagePos.x)),
                                                by(minOf(firstImage.y, imagePos.y)),
                                                bx(maxOf(firstImage.x, imagePos.x)),
                                                by(maxOf(firstImage.y, imagePos.y)),
                                            ),
                                        )
                                    }

                                    Tool.SELECT_ELLIPSE -> {
                                        pth.addOval(
                                            androidx.compose.ui.geometry.Rect(
                                                bx(minOf(firstImage.x, imagePos.x)),
                                                by(minOf(firstImage.y, imagePos.y)),
                                                bx(maxOf(firstImage.x, imagePos.x)),
                                                by(maxOf(firstImage.y, imagePos.y)),
                                            ),
                                        )
                                    }

                                    else -> {
                                        val pts = lassoPoints + imagePos
                                        if (pts.isNotEmpty()) {
                                            pth.moveTo(bx(pts[0].x), by(pts[0].y))
                                            for (i in 1 until pts.size) {
                                                pth.lineTo(bx(pts[i].x), by(pts[i].y))
                                            }
                                            pth.close()
                                        }
                                    }
                                }
                                liveSelectionPath.value = pth
                                if (lassoPoints.lastOrNull() != imagePos) {
                                    lassoPoints += imagePos
                                }
                                // Live fill preview while dragging
                                // (throttled; the committed selection
                                // replaces it). SELECT_RECT/ELLIPSE
                                // only show the shape outline
                                if (tool == Tool.LASSO || tool == Tool.SELECT_POLYGON) {
                                    val nowNs = System.nanoTime()
                                    if (nowNs - lastLassoPreviewNs > 45_000_000L) {
                                        lastLassoPreviewNs = nowNs
                                        vm.previewLasso(
                                            lassoPoints.map { it.x.toInt() to it.y.toInt() },
                                        )
                                    }
                                }
                            }

                            tool == Tool.CROP -> {
                                shapeEnd = imagePos
                                onCropRect(
                                    androidx.compose.ui.geometry.Rect(
                                        minOf(firstImage.x, imagePos.x),
                                        minOf(firstImage.y, imagePos.y),
                                        maxOf(firstImage.x, imagePos.x),
                                        maxOf(firstImage.y, imagePos.y),
                                    ),
                                )
                            }

                            shapeTool || twoPointTool -> {
                                shapeEnd = imagePos
                                liveShapeEnd.value = imagePos
                            }

                            tool == Tool.PICKER -> {
                                val targetOffset = Offset(-48.dp.toPx(), -48.dp.toPx())
                                val sampleScreenPos = point.position + targetOffset
                                pickerScreenPos.value = sampleScreenPos
                                val sampleImage =
                                    widgetToImage(
                                        sampleScreenPos,
                                        viewW(),
                                        viewH(),
                                        localPanX,
                                        localPanY,
                                        localZoom,
                                        latestFitScale(),
                                        localRotation,
                                        image.width,
                                        image.height,
                                        vm.docWidth,
                                        vm.docHeight,
                                    )
                                val ix = sampleImage.x.toInt()
                                val iy = sampleImage.y.toInt()
                                if (ix in 0 until image.width && iy in 0 until image.height) {
                                    val pixel = image.getPixel(ix, iy)
                                    if (android.graphics.Color.alpha(pixel) > 0) {
                                        pickerCurrentColor.value = Color(pixel)
                                    }
                                }
                            }

                            tool == Tool.SELECT_MAGNETIC ||
                                tool == Tool.LASSO || tool == Tool.MAGICWAND -> {
                                if (tool == Tool.SELECT_MAGNETIC) {
                                    val prev = magneticPrev ?: firstImage
                                    val cur = imagePos
                                    val dd =
                                        (cur.x - prev.x) * (cur.x - prev.x) +
                                            (cur.y - prev.y) * (cur.y - prev.y)
                                    if (dd > 100f) { // ~10px step
                                        magneticPrev = cur
                                        val seg =
                                            vm.magneticLassoSync(
                                                prev.x.toInt(),
                                                prev.y.toInt(),
                                                cur.x.toInt(),
                                                cur.y.toInt(),
                                                40,
                                            )
                                        if (!gestureEnded) {
                                            if (seg != null && seg.isNotEmpty()) {
                                                for (p in seg) {
                                                    if (lassoPoints.lastOrNull() !=
                                                        Offset(p.first.toFloat(), p.second.toFloat())
                                                    ) {
                                                        lassoPoints +=
                                                            Offset(p.first.toFloat(), p.second.toFloat())
                                                    }
                                                }
                                            } else if (lassoPoints.lastOrNull() != cur) {
                                                lassoPoints += cur
                                            }
                                            val bmpW2 = bmp?.width ?: 0
                                            val bmpH2 = bmp?.height ?: 0
                                            val dW2 = vm.docWidth
                                            val dH2 = vm.docHeight
                                            val sc2X = if (dW2 > 0) bmpW2.toFloat() / dW2 else 1f
                                            val sc2Y = if (dH2 > 0) bmpH2.toFloat() / dH2 else 1f
                                            val np =
                                                androidx.compose.ui.graphics
                                                    .Path()
                                            if (lassoPoints.isNotEmpty()) {
                                                np.moveTo(
                                                    lassoPoints[0].x * sc2X - bmpW2 / 2f,
                                                    lassoPoints[0].y * sc2Y - bmpH2 / 2f,
                                                )
                                                for (i in 1 until lassoPoints.size) {
                                                    np.lineTo(
                                                        lassoPoints[i].x * sc2X - bmpW2 / 2f,
                                                        lassoPoints[i].y * sc2Y - bmpH2 / 2f,
                                                    )
                                                }
                                                np.close()
                                            }
                                            liveSelectionPath.value = np
                                            val nowNs = System.nanoTime()
                                            if (nowNs - lastLassoPreviewNs > 45_000_000L) {
                                                lastLassoPreviewNs = nowNs
                                                vm.previewLasso(
                                                    lassoPoints.map { it.x.toInt() to it.y.toInt() },
                                                )
                                            }
                                        }
                                    }
                                } else if (lassoPoints.lastOrNull() != imagePos) {
                                    lassoPoints += imagePos
                                    val nowNs = System.nanoTime()
                                    if (nowNs - lastLassoPreviewNs > 45_000_000L) {
                                        lastLassoPreviewNs = nowNs
                                        vm.previewLasso(
                                            lassoPoints.map { it.x.toInt() to it.y.toInt() },
                                        )
                                    }
                                }
                            }

                            tool == Tool.MEASURE -> {
                                measureEnd.value = imagePos
                            }

                            tool == Tool.TRANSFORM || tool == Tool.MOVE -> {
                                shapeEnd = imagePos
                                if (tfState.active && tfState.handle >= 0) {
                                    val c = tfState.bounds.center
                                    when {
                                        // Distort (3x3 Mesh Grid) dragging
                                        tfState.mode == TransformMode.DISTORT -> {
                                            val delta = imagePos - tfState.dragStart
                                            if (tfState.handle in 0..15) {
                                                val newMesh = tfState.startMeshPoints.toMutableList()
                                                newMesh[tfState.handle] = tfState.startMeshPoints[tfState.handle] + delta
                                                tfState.meshPoints = newMesh
                                            } else {
                                                tfState.meshPoints = tfState.startMeshPoints.map { it + delta }
                                            }
                                        }

                                        // Perspective (4-Point Quad) dragging
                                        tfState.mode == TransformMode.PERSPECTIVE -> {
                                            val delta = imagePos - tfState.dragStart
                                            if (tfState.handle in 0..3) {
                                                val idx = tfState.handle
                                                val newCorners = tfState.startQuadCorners.toMutableList()
                                                newCorners[idx] = tfState.startQuadCorners[idx] + delta
                                                tfState.quadCorners = newCorners
                                            } else {
                                                // Translate all 4 corners
                                                tfState.quadCorners = tfState.startQuadCorners.map { it + delta }
                                            }
                                        }

                                        // Corner 1 (Top-Right) & Corner 3 (Bottom-Left) & Outside (9): Rotate
                                        tfState.handle == 1 || tfState.handle == 3 || tfState.handle == 9 -> {
                                            val a1 =
                                                atan2(
                                                    tfState.dragStart.y - c.y - tfState.startTy,
                                                    tfState.dragStart.x - c.x - tfState.startTx,
                                                )
                                            val a2 =
                                                atan2(
                                                    imagePos.y - c.y - tfState.startTy,
                                                    imagePos.x - c.x - tfState.startTx,
                                                )
                                            val d = Math.toDegrees((a2 - a1).toDouble()).toFloat()
                                            tfState.rotation = tfState.startRotation + d
                                        }

                                        // Corner 0 (Top-Left) & Corner 2 (Bottom-Right): Scale
                                        tfState.handle == 0 || tfState.handle == 2 -> {
                                            val rad = Math.toRadians(-tfState.startRotation.toDouble())
                                            val cosR = cos(rad).toFloat()
                                            val sinR = sin(rad).toFloat()
                                            val dx = imagePos.x - c.x - tfState.startTx
                                            val dy = imagePos.y - c.y - tfState.startTy
                                            val ux = dx * cosR - dy * sinR
                                            val uy = dx * sinR + dy * cosR

                                            val sdx = tfState.dragStart.x - c.x - tfState.startTx
                                            val sdy = tfState.dragStart.y - c.y - tfState.startTy
                                            val sux = sdx * cosR - sdy * sinR
                                            val suy = sdx * sinR + sdy * cosR

                                            val kx = if (kotlin.math.abs(sux) > 1f) ux / sux else 1f
                                            val ky = if (kotlin.math.abs(suy) > 1f) uy / suy else 1f

                                            if (tfState.mode == TransformMode.STANDARD) {
                                                // Standard mode: Proportional locked aspect ratio!
                                                val k = if (kotlin.math.abs(kx - 1f) > kotlin.math.abs(ky - 1f)) kx else ky
                                                tfState.scaleX = tfState.startScaleX * k
                                                tfState.scaleY = tfState.startScaleY * k
                                            } else {
                                                // Free mode: Independent scale
                                                tfState.scaleX = tfState.startScaleX * kx
                                                tfState.scaleY = tfState.startScaleY * ky
                                            }
                                        }

                                        // Edge 4 (Top) & Edge 6 (Bottom): Scale Y
                                        tfState.handle == 4 || tfState.handle == 6 -> {
                                            val rad = Math.toRadians(-tfState.startRotation.toDouble())
                                            val cosR = cos(rad).toFloat()
                                            val sinR = sin(rad).toFloat()
                                            val dy = imagePos.y - c.y - tfState.startTy
                                            val dx = imagePos.x - c.x - tfState.startTx
                                            val uy = dx * sinR + dy * cosR

                                            val sdy = tfState.dragStart.y - c.y - tfState.startTy
                                            val sdx = tfState.dragStart.x - c.x - tfState.startTx
                                            val suy = sdx * sinR + sdy * cosR

                                            val ky = if (kotlin.math.abs(suy) > 1f) uy / suy else 1f
                                            tfState.scaleY = tfState.startScaleY * ky
                                        }

                                        // Edge 5 (Right) & Edge 7 (Left): Scale X
                                        tfState.handle == 5 || tfState.handle == 7 -> {
                                            val rad = Math.toRadians(-tfState.startRotation.toDouble())
                                            val cosR = cos(rad).toFloat()
                                            val sinR = sin(rad).toFloat()
                                            val dx = imagePos.x - c.x - tfState.startTx
                                            val dy = imagePos.y - c.y - tfState.startTy
                                            val ux = dx * cosR - dy * sinR

                                            val sdx = tfState.dragStart.x - c.x - tfState.startTx
                                            val sdy = tfState.dragStart.y - c.y - tfState.startTy
                                            val sux = sdx * cosR - sdy * sinR

                                            val kx = if (kotlin.math.abs(sux) > 1f) ux / sux else 1f
                                            tfState.scaleX = tfState.startScaleX * kx
                                        }

                                        // Inside bounding box (8): Translate / Move
                                        tfState.handle == 8 -> {
                                            tfState.tx = tfState.startTx + (imagePos.x - tfState.dragStart.x)
                                            tfState.ty = tfState.startTy + (imagePos.y - tfState.dragStart.y)
                                        }
                                    }
                                }
                            }

                            tool == Tool.LIQUIFY -> {
                                if (!strokeStarted) {
                                    vm.touchStart(imagePos.x, imagePos.y)
                                    strokeStarted = true
                                }
                                vm.liquify(
                                    liquifyPrevious.x,
                                    liquifyPrevious.y,
                                    imagePos.x,
                                    imagePos.y,
                                    liquifyMode,
                                    liquifyStrength.toDouble(),
                                )
                                liquifyPrevious = imagePos
                            }

                            else -> {
                                if (!strokeStarted) {
                                    val downRaw = down.pressure.coerceIn(0f, 1f)
                                    val downP = if (stylus && downRaw > 0f) vm.evaluatePressure(downRaw) else 0.8f
                                    smoothedPressure = downP
                                    livePressure.value = smoothedPressure
                                    val startP = if (stylus) smoothedPressure.toDouble() else 1.0
                                    vm.touchStart(firstImage.x, firstImage.y, startP)
                                    strokeStarted = true
                                    vm.touchMove(imagePos.x, imagePos.y, startP)
                                } else {
                                    if (point.historical.isNotEmpty()) {
                                        for (h in point.historical) {
                                            val histPos =
                                                widgetToImage(
                                                    h.position,
                                                    viewW(),
                                                    viewH(),
                                                    localPanX,
                                                    localPanY,
                                                    localZoom,
                                                    latestFitScale(),
                                                    localRotation,
                                                    image.width,
                                                    image.height,
                                                    vm.docWidth,
                                                    vm.docHeight,
                                                )
                                            if (stylus) {
                                                vm.touchMove(histPos.x, histPos.y, smoothedPressure.toDouble())
                                            } else {
                                                vm.touchMove(histPos.x, histPos.y, 1.0)
                                            }
                                        }
                                    }
                                    if (stylus) {
                                        val rawP = point.pressure.coerceIn(0f, 1f)
                                        if (rawP > 0f) {
                                            val mappedP = vm.evaluatePressure(rawP)
                                            smoothedPressure = smoothedPressure * 0.6f + mappedP * 0.4f
                                        }
                                        livePressure.value = smoothedPressure
                                        vm.touchMove(imagePos.x, imagePos.y, smoothedPressure.toDouble())
                                    } else {
                                        livePressure.value = 1f
                                        vm.touchMove(imagePos.x, imagePos.y, 1.0)
                                    }
                                }
                            }
                        }
                        point.consume()
                    }

                    else -> {
                        point.consume()
                    }
                }
            }

            liveShapeStart.value = null
            liveShapeEnd.value = null
        }
    }
}
