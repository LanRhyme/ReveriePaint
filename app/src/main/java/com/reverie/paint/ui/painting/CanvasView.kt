package com.reverie.paint.ui.painting

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.model.Tool
import com.reverie.paint.ui.theme.Morandi
import kotlin.math.min

private enum class GestureMode { NONE, STROKE, PAN, TRANSFORM }

/**
 * The painting canvas - displays the composited document bitmap and routes
 * touch input to the C++ engine:
 *  - one finger: BRUSH/ERASER/SMUDGE paint (pressure), HAND pans, PICKER samples
 *  - two fingers: pinch zoom / rotate / pan (画世界 Pro style)
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
    tool: Tool,
) {
    var viewW by remember { mutableStateOf(1) }
    var viewH by remember { mutableStateOf(1) }
    var viewZoom by remember { mutableFloatStateOf(zoom) }
    var viewRotation by remember { mutableFloatStateOf(rotation) }
    var viewPanX by remember { mutableFloatStateOf(panX) }
    var viewPanY by remember { mutableFloatStateOf(panY) }

    Box(
        modifier =
            modifier
                .onSizeChanged {
                    viewW = it.width
                    viewH = it.height
                }.background(Morandi.canvasBg),
    ) {
        val bmp = vm.displayBitmap
        if (bmp != null) {
            LaunchedEffect(bmp.width, bmp.height, viewW, viewH) {
                if (viewW > 0 && viewH > 0) {
                    onFitScale(min(viewW.toFloat() / bmp.width, viewH.toFloat() / bmp.height))
                }
            }
            val scale = zoom * fitScale

            // Drop shadow behind the canvas
            Canvas(Modifier.fillMaxSize()) {
                val cw = bmp.width * scale
                val ch = bmp.height * scale
                drawRoundRect(
                    color = Morandi.canvasShadow,
                    topLeft =
                        Offset(
                            viewW / 2f - cw / 2f + panX + 10f,
                            viewH / 2f - ch / 2f + panY + 10f,
                        ),
                    size = Size(cw, ch),
                    cornerRadius = CornerRadius(4f, 4f),
                )
            }

            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                modifier =
                    Modifier
                        .graphicsLayer {
                            translationX = panX + viewW / 2f
                            translationY = panY + viewH / 2f
                            scaleX = scale
                            scaleY = scale
                            rotationZ = rotation
                            transformOrigin = TransformOrigin(0f, 0f)
                        }.pointerInput(bmp.width, bmp.height, fitScale, zoom, rotation, panX, panY, tool) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                val p0 = down.position
                                val img0 =
                                    widgetToImage(
                                        p0,
                                        viewW,
                                        viewH,
                                        panX,
                                        panY,
                                        zoom,
                                        fitScale,
                                        bmp.width,
                                        bmp.height,
                                    )
                                var mode =
                                    when (tool) {
                                        Tool.HAND -> {
                                            GestureMode.PAN
                                        }

                                        Tool.PICKER -> {
                                            vm.pickColor(img0.x, img0.y)
                                            GestureMode.NONE
                                        }

                                        Tool.BRUSH, Tool.ERASER, Tool.SMUDGE -> {
                                            vm.touchStart(img0.x, img0.y)
                                            GestureMode.STROKE
                                        }

                                        else -> {
                                            GestureMode.PAN
                                        }
                                    }

                                var startZoom = zoom
                                var startRotation = rotation
                                var startPanX = panX
                                var startPanY = panY
                                var lastCentroid = Offset.Zero

                                while (true) {
                                    val event = awaitPointerEvent()
                                    val pressed = event.changes.filter { it.pressed }
                                    if (pressed.isEmpty()) break

                                    if (pressed.size >= 2 && mode != GestureMode.TRANSFORM) {
                                        // Two fingers: cancel any partial stroke, start transform
                                        if (mode == GestureMode.STROKE) vm.touchCancel()
                                        mode = GestureMode.TRANSFORM
                                        startZoom = zoom
                                        startRotation = rotation
                                        startPanX = panX
                                        startPanY = panY
                                        lastCentroid = event.calculateCentroid()
                                    }

                                    when (mode) {
                                        GestureMode.STROKE -> {
                                            val c = pressed.first()
                                            val img =
                                                widgetToImage(
                                                    c.position,
                                                    viewW,
                                                    viewH,
                                                    panX,
                                                    panY,
                                                    zoom,
                                                    fitScale,
                                                    bmp.width,
                                                    bmp.height,
                                                )
                                            vm.touchMove(img.x, img.y)
                                            c.consume()
                                        }

                                        GestureMode.PAN -> {
                                            val delta = event.calculatePan()
                                            onTransform(zoom, rotation, panX + delta.x, panY + delta.y)
                                            event.changes.forEach { it.consume() }
                                        }

                                        GestureMode.TRANSFORM -> {
                                            val centroid = event.calculateCentroid()
                                            val z = event.calculateZoom()
                                            val r = event.calculateRotation()
                                            val pan = event.calculatePan()
                                            val newZoom = (startZoom * z).coerceIn(0.05f, 32f)
                                            // Compose's calculateRotation is cumulative per event; apply directly
                                            val newRot = (startRotation + r) % 360f
                                            val newPanX = startPanX + pan.x
                                            val newPanY = startPanY + pan.y
                                            onTransform(newZoom, newRot, newPanX, newPanY)
                                            event.changes.forEach { it.consume() }
                                        }

                                        GestureMode.NONE -> {}
                                    }
                                }

                                when (mode) {
                                    GestureMode.STROKE -> {
                                        vm.touchEnd()
                                    }

                                    else -> {}
                                }
                            }
                        },
            )
        }
    }
}

