/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.reverie.paint.ui.painting.canvas

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.model.CanvasViewTransform
import com.reverie.paint.model.Point2D
import com.reverie.paint.model.QuickShapeGeometry
import com.reverie.paint.ui.painting.panels.QuickShapeTopBar
import com.reverie.paint.ui.theme.Morandi

/** Modal editor prevents document/tool changes under an unfinished brush stroke. */
@Composable
internal fun QuickShapeEditor(
    vm: PaintViewModel,
    zoom: State<Float>, rotation: State<Float>, panX: State<Float>, panY: State<Float>,
    fitScale: Float,
    modifier: Modifier = Modifier,
) {
    val shape = vm.activeQuickShape ?: return
    var reshapePointer by remember { mutableStateOf<PointerId?>(null) }
    val transform = remember { CanvasViewTransform() }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val scale by rememberUpdatedState(fitScale)
    val density = LocalDensity.current.density
    val outline = remember(shape) { QuickShapeGeometry.outline(shape) }
    val handles = remember(shape, vm.quickShapeBoxHandlesEnabled) {
        QuickShapeGeometry.handles(shape, vm.quickShapeBoxHandlesEnabled)
    }
    val path = remember { Path() }
    val border = remember { Path() }
    val borderPaint = remember {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE
            strokeCap = android.graphics.Paint.Cap.ROUND
            strokeJoin = android.graphics.Paint.Join.ROUND
        }
    }
    val point = remember { FloatArray(2) }
    fun updateTransform() = transform.update(size.width, size.height, panX.value, panY.value,
        zoom.value, scale, rotation.value, vm.renderW, vm.renderH, vm.docWidth, vm.docHeight,
        vm.viewFlipX, vm.viewFlipY)
    Box(modifier) {
        Canvas(Modifier.fillMaxSize().onSizeChanged { size = it }.pointerInput(vm) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                down.consume()
                updateTransform()
                val original = vm.activeQuickShape ?: return@awaitEachGesture
                transform.screenToDoc(down.position.x, down.position.y, point)
                val from = Point2D(point[0], point[1])
                var handle = -1
                var nearest = 24f * density
                QuickShapeGeometry.handles(original, vm.quickShapeBoxHandlesEnabled).forEachIndexed { i, p ->
                    transform.docToScreen(p.x, p.y, point)
                    val distance = (Offset(point[0], point[1]) - down.position).getDistance()
                    if (distance < nearest) { nearest = distance; handle = i }
                }
                val reshape = reshapePointer != null
                var cancelled = vm.quickShapeCommitting
                do {
                    val event = awaitPointerEvent()
                    if (event.changes.count { it.pressed && it.id != reshapePointer } > 1 ||
                        (reshape && reshapePointer == null)) cancelled = true
                    val change = event.changes.firstOrNull { it.id == down.id }
                    if (!cancelled && !vm.quickShapeCommitting && change != null &&
                        (change.pressed || change.previousPressed)) {
                        transform.screenToDoc(change.position.x, change.position.y, point)
                        vm.activeQuickShape = QuickShapeGeometry.drag(original, handle, from, Point2D(point[0], point[1]),
                            vm.quickShapeAngleSnapEnabled, reshape, vm.quickShapeBoxHandlesEnabled,
                            vm.quickShapeCurvedContourEnabled)
                    }
                    event.changes.forEach { it.consume() }
                } while (event.changes.any { it.pressed })
            }
        }) {
            updateTransform()
            path.reset()
            outline.forEachIndexed { i, p ->
                transform.docToScreen(p.x, p.y, point)
                if (i == 0) path.moveTo(point[0], point[1]) else path.lineTo(point[0], point[1])
            }
            // Color/width guide only: textures, pressure sensors and scatter are rendered on commit.
            val outlineColor = runCatching { Color(android.graphics.Color.parseColor(vm.brushColor)) }
                .getOrDefault(Morandi.accent)
                .copy(alpha = vm.brushOpacity.toFloat().coerceIn(0f, 1f))
            val outlineWidth = (vm.brushSize.toFloat() * zoom.value * fitScale).coerceAtLeast(1f)
            drawPath(
                path = path,
                color = outlineColor,
                style = Stroke(
                    width = outlineWidth,
                    cap = androidx.compose.ui.graphics.StrokeCap.Round,
                    join = androidx.compose.ui.graphics.StrokeJoin.Round,
                ),
            )
            // Outline the stroke boundary without filling its translucent interior with the border color.
            border.reset()
            borderPaint.strokeWidth = outlineWidth
            borderPaint.getFillPath(path.asAndroidPath(), border.asAndroidPath())
            drawPath(border, Morandi.panel, style = Stroke(width = 3f * density))
            drawPath(border, Morandi.accent, style = Stroke(width = density))
            handles.forEachIndexed { i, p ->
                transform.docToScreen(p.x, p.y, point)
                val pos = Offset(point[0], point[1])
                drawCircle(Morandi.panel, 8f * density, pos)
                drawCircle(Morandi.accent, 8f * density, pos, style = Stroke(2f * density))
                if (i == QuickShapeGeometry.rotationHandle(shape, vm.quickShapeBoxHandlesEnabled)) {
                    drawCircle(Morandi.accent, 3f * density, pos)
                }
            }
        }
        QuickShapeTopBar(vm, Modifier.align(Alignment.TopCenter).padding(top = 48.dp, start = 12.dp, end = 12.dp),
            reshapeHeld = reshapePointer != null, onReshapePointer = { reshapePointer = it })
    }
}
