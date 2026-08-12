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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.model.Tool
import com.reverie.paint.ui.theme.Morandi
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

private enum class GestureMode { NONE, STROKE, PAN, TRANSFORM }

/**
 * Full workspace canvas with one shared forward and inverse transform
 *
 * The pointer handler deliberately does not key on zoom/pan/rotation. Those
 * states change on every gesture event; keying on them cancels pointerInput
 * during the gesture and was the reason pinch/rotate stopped after one frame
 */
@Composable
fun CanvasView(
    vm: PaintViewModel,
    modifier: Modifier = Modifier,
    zoom: Float,
    rotation: Float,
    panX: Float,
    panY: Float,
    fitScale: Float,
    onFitScale: (Float) -> Unit,
    onTransform: (zoom: Float, rotation: Float, panX: Float, panY: Float) -> Unit,
    onTextRequested: (x: Float, y: Float) -> Unit = { _, _ -> },
    tool: Tool,
) {
    var viewW by remember { mutableStateOf(1) }
    var viewH by remember { mutableStateOf(1) }
    val bmp = vm.displayBitmap
    val imageBitmap = bmp?.asImageBitmap()
    val latestZoom by rememberUpdatedState(zoom)
    val latestRotation by rememberUpdatedState(rotation)
    val latestPanX by rememberUpdatedState(panX)
    val latestPanY by rememberUpdatedState(panY)
    val latestFitScale by rememberUpdatedState(fitScale)

    LaunchedEffect(bmp?.width, bmp?.height, viewW, viewH) {
        if (bmp != null && viewW > 0 && viewH > 0) {
            onFitScale(min(viewW.toFloat() / bmp.width, viewH.toFloat() / bmp.height) * 0.88f)
        }
    }

    Box(
        modifier =
            modifier
                .onSizeChanged {
                    viewW = it.width
                    viewH = it.height
                }.background(Morandi.canvasBg)
                // Only stable document/tool identity belongs in the key
                .pointerInput(tool, bmp?.width, bmp?.height) {
                    val image = bmp ?: return@pointerInput
                    if (latestFitScale <= 0f) return@pointerInput

                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val docW = image.width
                        val docH = image.height
                        var localZoom = latestZoom
                        var localRotation = latestRotation
                        var localPanX = latestPanX
                        var localPanY = latestPanY
                        var mode =
                            when (tool) {
                                Tool.HAND -> GestureMode.PAN
                                Tool.PICKER, Tool.FILL, Tool.TEXT -> GestureMode.NONE
                                else -> GestureMode.STROKE
                            }
                        var strokeStarted = false
                        var transformStarted = false
                        val shapeTool = tool == Tool.LINE || tool == Tool.RECT || tool == Tool.ELLIPSE
                        var shapeEnd = Offset.Zero
                        val lassoPoints = mutableListOf<Offset>()
                        var liquifyPrevious = Offset.Zero
                        var previousSinglePoint = down.position

                        val firstImage =
                            widgetToImage(
                                down.position,
                                viewW,
                                viewH,
                                localPanX,
                                localPanY,
                                localZoom,
                                latestFitScale,
                                localRotation,
                                docW,
                                docH,
                            )
                        shapeEnd = firstImage
                        lassoPoints += firstImage
                        liquifyPrevious = firstImage

                        when (tool) {
                            Tool.PICKER -> vm.pickColor(firstImage.x, firstImage.y)
                            Tool.FILL -> vm.floodFill(firstImage.x, firstImage.y)
                            Tool.TEXT -> onTextRequested(firstImage.x, firstImage.y)
                            else -> Unit
                        }

                        // Two-finger transform: per-event incremental deltas,
                        // anchored at the two-finger centroid (Procreate style)
                        var prevCentroid = Offset.Zero
                        var prevDistance = 1f
                        var prevAngle = 0f

                        while (true) {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.filter { it.pressed }
                            if (pressed.isEmpty()) break

                            if (pressed.size >= 2) {
                                val pair = pressed.sortedBy { it.id.value }.take(2)
                                val a = pair[0].position
                                val b = pair[1].position
                                val centroid = Offset((a.x + b.x) / 2f, (a.y + b.y) / 2f)
                                val distance = hypot(b.x - a.x, b.y - a.y).coerceAtLeast(1f)
                                val angle = angleDegrees(a, b)

                                if (!transformStarted) {
                                    if (strokeStarted) {
                                        vm.touchCancel()
                                        strokeStarted = false
                                    }
                                    transformStarted = true
                                    mode = GestureMode.TRANSFORM
                                    prevCentroid = centroid
                                    prevDistance = distance
                                    prevAngle = angle
                                } else {
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
                                    val centerX = viewW / 2f + localPanX
                                    val centerY = viewH / 2f + localPanY
                                    val vx = prevCentroid.x - centerX
                                    val vy = prevCentroid.y - centerY
                                    val radians = Math.toRadians(dRot.toDouble())
                                    val cosR = cos(radians).toFloat()
                                    val sinR = sin(radians).toFloat()
                                    val rx = vx * cosR - vy * sinR
                                    val ry = vx * sinR + vy * cosR

                                    localZoom = (localZoom * k).coerceIn(0.05f, 32f)
                                    localRotation += dRot
                                    localPanX = centroid.x - k * rx - viewW / 2f
                                    localPanY = centroid.y - k * ry - viewH / 2f

                                    prevCentroid = centroid
                                    prevDistance = distance
                                    prevAngle = angle
                                    onTransform(localZoom, localRotation, localPanX, localPanY)
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
                            val delta = point.position - previousSinglePoint
                            previousSinglePoint = point.position
                            val imagePos =
                                widgetToImage(
                                    point.position,
                                    viewW,
                                    viewH,
                                    localPanX,
                                    localPanY,
                                    localZoom,
                                    latestFitScale,
                                    localRotation,
                                    docW,
                                    docH,
                                )

                            when (mode) {
                                GestureMode.PAN -> {
                                    localPanX += delta.x
                                    localPanY += delta.y
                                    onTransform(localZoom, localRotation, localPanX, localPanY)
                                    point.consume()
                                }

                                GestureMode.STROKE -> {
                                    when {
                                        shapeTool -> {
                                            shapeEnd = imagePos
                                        }

                                        tool == Tool.LASSO || tool == Tool.MAGICWAND -> {
                                            if (lassoPoints.lastOrNull() != imagePos) lassoPoints += imagePos
                                        }

                                        tool == Tool.LIQUIFY -> {
                                            if (!strokeStarted) {
                                                vm.touchStart(imagePos.x, imagePos.y)
                                                strokeStarted = true
                                            }
                                            vm.liquify(liquifyPrevious.x, liquifyPrevious.y, imagePos.x, imagePos.y)
                                            liquifyPrevious = imagePos
                                        }

                                        else -> {
                                            if (!strokeStarted) {
                                                // Start at the finger-DOWN position (not the first
                                                // move), so the stroke's beginning is not cut off
                                                vm.touchStart(firstImage.x, firstImage.y)
                                                strokeStarted = true
                                                vm.touchMove(imagePos.x, imagePos.y)
                                            } else {
                                                vm.touchMove(imagePos.x, imagePos.y)
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

                        if (!transformStarted) {
                            when {
                                shapeTool -> {
                                    val kind =
                                        when (tool) {
                                            Tool.RECT -> 1
                                            Tool.ELLIPSE -> 2
                                            else -> 0
                                        }
                                    vm.drawShape(kind, firstImage.x, firstImage.y, shapeEnd.x, shapeEnd.y)
                                }

                                tool == Tool.LASSO || tool == Tool.MAGICWAND -> {
                                    if (lassoPoints.size >= 3) {
                                        val points = lassoPoints.map { it.x.toInt() to it.y.toInt() }
                                        if (tool == Tool.LASSO) vm.lassoFill(points) else vm.lassoClear(points)
                                    }
                                }

                                strokeStarted -> {
                                    vm.touchEnd()
                                }

                                // A pure tap on a painting tool (no movement)
                                // still commits a single dab at the tap point
                                mode == GestureMode.STROKE &&
                                    (tool == Tool.BRUSH || tool == Tool.ERASER || tool == Tool.SMUDGE) -> {
                                    vm.touchStart(firstImage.x, firstImage.y)
                                    vm.touchEnd()
                                }
                            }
                        }
                    }
                },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val image = imageBitmap ?: return@Canvas
            val scale = (zoom * fitScale).coerceAtLeast(0.001f)
            val center = Offset(size.width / 2f + panX, size.height / 2f + panY)
            withTransform({
                translate(center.x + 8f, center.y + 8f)
                rotate(rotation)
                scale(scale, scale)
            }) {
                drawRect(
                    Morandi.canvasShadow,
                    topLeft = Offset(-image.width / 2f, -image.height / 2f),
                    size =
                        androidx.compose.ui.geometry
                            .Size(image.width.toFloat(), image.height.toFloat()),
                )
            }
            withTransform({
                translate(center.x, center.y)
                rotate(rotation)
                scale(scale, scale)
            }) {
                drawImage(image, topLeft = Offset(-image.width / 2f, -image.height / 2f))
            }
        }
    }
}

private fun angleDegrees(
    a: Offset,
    b: Offset,
): Float = Math.toDegrees(atan2((b.y - a.y).toDouble(), (b.x - a.x).toDouble())).toFloat()

private fun normalizeAngle(value: Float): Float {
    var result = value % 360f
    if (result > 180f) result -= 360f
    if (result < -180f) result += 360f
    return result
}

/** Convert workspace coordinates into document coordinates using the inverse view transform. */
fun widgetToImage(
    p: Offset,
    canvasW: Int,
    canvasH: Int,
    panX: Float,
    panY: Float,
    zoom: Float,
    fitScale: Float,
    rotation: Float,
    docW: Int,
    docH: Int,
): Offset {
    val scale = (zoom * fitScale).coerceAtLeast(0.001f)
    val dx = p.x - (canvasW / 2f + panX)
    val dy = p.y - (canvasH / 2f + panY)
    val radians = Math.toRadians((-rotation).toDouble())
    val cosR = cos(radians).toFloat()
    val sinR = sin(radians).toFloat()
    val unrotatedX = dx * cosR - dy * sinR
    val unrotatedY = dx * sinR + dy * cosR
    return Offset(unrotatedX / scale + docW / 2f, unrotatedY / scale + docH / 2f)
}
