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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.model.Tool
import com.reverie.paint.ui.theme.Morandi
import com.reverie.paint.ui.theme.parseColor
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

private enum class GestureMode { NONE, STROKE, PAN, MOVE, TRANSFORM }

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
    val viewportReported by remember { mutableStateOf(false) }
    val bmp = vm.displayBitmap
    val imageBitmap = bmp?.asImageBitmap()
    val latestZoom by rememberUpdatedState(zoom)
    val latestRotation by rememberUpdatedState(rotation)
    val latestPanX by rememberUpdatedState(panX)
    val latestPanY by rememberUpdatedState(panY)
    val latestFitScale by rememberUpdatedState(fitScale)

    // Live selection preview path (updated while dragging a selection tool)
    var liveSelectionPath by remember { mutableStateOf<androidx.compose.ui.graphics.Path?>(null) }
    var pickerActive by remember { mutableStateOf(false) }
    var pickerScreenPos by remember { mutableStateOf(Offset.Zero) }
    var pickerInitialColor by remember { mutableStateOf(Color.White) }
    var pickerCurrentColor by remember { mutableStateOf(Color.White) }

    // Clear the preview once the committed overlay is ready (no blink), and
    // whenever the active tool is no longer a selection tool
    LaunchedEffect(tool, vm.selectionOverlayBitmap) {
        val selTools =
            setOf(
                Tool.SELECT_RECT,
                Tool.SELECT_ELLIPSE,
                Tool.SELECT_POLYGON,
                Tool.SELECT_MAGNETIC,
                Tool.LASSO,
                Tool.MAGICWAND,
            )
        if (tool !in selTools || vm.selectionOverlayBitmap != null) {
            liveSelectionPath = null
        }
    }

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
                    vm.setRenderViewport(it.width, it.height)
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
                        val shapeTool = tool == Tool.LINE || tool == Tool.RECT || tool == Tool.ELLIPSE
                        val trackShapeTool =
                            tool == Tool.POLYGON || tool == Tool.POLYLINE || tool == Tool.PATH
                        val twoPointTool =
                            tool == Tool.GRADIENT || tool == Tool.SELECT_RECT ||
                                tool == Tool.SELECT_ELLIPSE || tool == Tool.CROP
                        val trackSelectTool =
                            tool == Tool.SELECT_POLYGON || tool == Tool.SELECT_MAGNETIC
                        var mode =
                            when (tool) {
                                Tool.MOVE -> GestureMode.MOVE
                                Tool.PICKER, Tool.FILL, Tool.TEXT,
                                Tool.MAGICWAND, Tool.SELECT_SIMILAR -> GestureMode.NONE
                                else -> GestureMode.STROKE // Includes MEASURE, TRANSFORM, DYNA, etc.
                            }
                        var strokeStarted = false
                        var transformStarted = false
                        // Pressure: only a stylus reports meaningful force.
                        // Touch screens report arbitrary small values for
                        // fingers, which would shrink strokes into dotted
                        // lines - so fingers always paint at full width.
                        val stylus = down.type == PointerType.Stylus
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
                                viewW,
                                viewH,
                                localPanX,
                                localPanY,
                                localZoom,
                                latestFitScale,
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

                        // Krita's selection tools select on the primary action
                        // (finger-down): the magic wand / similar-color / fill
                        // fire immediately here, and a second finger landing
                        // mid-gesture (zoom/pan) reverts them with an undo.
                        // Text keeps the release-confirmed tap because a modal
                        // dialog must not pop up during a transform.
                        var pendingTap: Offset? = null
                        var tapReverted = false
                        when (tool) {
                            Tool.PICKER -> {
                                pickerActive = true
                                pickerScreenPos = down.position
                                val refHex = vm.brushColor
                                pickerInitialColor = parseColor(refHex)
                                val hex = vm.pickColor(firstImage.x, firstImage.y)
                                pickerCurrentColor = hex?.let { parseColor(it) } ?: pickerInitialColor
                            }
                            Tool.MAGICWAND -> {
                                vm.selectContiguous(firstImage.x.toInt(), firstImage.y.toInt())
                                tapReverted = true
                            }
                            Tool.SELECT_SIMILAR -> {
                                vm.selectSimilar(firstImage.x.toInt(), firstImage.y.toInt())
                                tapReverted = true
                            }
                            Tool.FILL -> {
                                vm.floodFill(firstImage.x, firstImage.y)
                                tapReverted = true
                            }
                            Tool.TEXT -> {
                                pendingTap = firstImage
                            }
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
                            // A real drag (not a tap) must not fire the tap tool
                            if (pendingTap != null && hypot(delta.x, delta.y) > 8f) {
                                pendingTap = null
                            }
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
                                            val pth = androidx.compose.ui.graphics.Path()
                                            when (tool) {
                                                Tool.SELECT_RECT -> {
                                                    pth.addRect(
                                                        androidx.compose.ui.geometry.Rect(
                                                            bx(minOf(firstImage.x, imagePos.x)),
                                                            by(minOf(firstImage.y, imagePos.y)),
                                                            bx(maxOf(firstImage.x, imagePos.x)),
                                                            by(maxOf(firstImage.y, imagePos.y)),
                                                        )
                                                    )
                                                }

                                                Tool.SELECT_ELLIPSE -> {
                                                    pth.addOval(
                                                        androidx.compose.ui.geometry.Rect(
                                                            bx(minOf(firstImage.x, imagePos.x)),
                                                            by(minOf(firstImage.y, imagePos.y)),
                                                            bx(maxOf(firstImage.x, imagePos.x)),
                                                            by(maxOf(firstImage.y, imagePos.y)),
                                                        )
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
                                            liveSelectionPath = pth
                                            if (lassoPoints.lastOrNull() != imagePos) {
                                                lassoPoints += imagePos
                                            }
                                        }

                                        shapeTool || twoPointTool -> {
                                            shapeEnd = imagePos
                                        }

                                        tool == Tool.PICKER -> {
                                            pickerScreenPos = point.position
                                            val hex = vm.pickColor(imagePos.x, imagePos.y)
                                            if (hex != null) {
                                                pickerCurrentColor = parseColor(hex)
                                            }
                                        }

                                        trackShapeTool || trackSelectTool ||
                                            tool == Tool.LASSO || tool == Tool.MAGICWAND -> {
                                            if (tool == Tool.SELECT_MAGNETIC) {
                                                // Magnetic lasso: snap each segment to the
                                                // strongest nearby edge. Krita computes each
                                                // segment synchronously as the pointer moves
                                                // (KisToolSelectMagnetic), so we do the same:
                                                // the preview path stays continuous and the
                                                // committed selection always matches what was
                                                // shown (no late-async "change after done")
                                                val prev = magneticPrev ?: firstImage
                                                val cur = imagePos
                                                val dd = (cur.x - prev.x) * (cur.x - prev.x) +
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
                                                            // Fallback: keep the path continuous
                                                            lassoPoints += cur
                                                        }
                                                        val bmpW2 = bmp?.width ?: 0
                                                        val bmpH2 = bmp?.height ?: 0
                                                        val dW2 = vm.docWidth
                                                        val dH2 = vm.docHeight
                                                        val sc2X = if (dW2 > 0) bmpW2.toFloat() / dW2 else 1f
                                                        val sc2Y = if (dH2 > 0) bmpH2.toFloat() / dH2 else 1f
                                                        val np = androidx.compose.ui.graphics.Path()
                                                        if (lassoPoints.isNotEmpty()) {
                                                            np.moveTo(lassoPoints[0].x * sc2X - bmpW2 / 2f, lassoPoints[0].y * sc2Y - bmpH2 / 2f)
                                                            for (i in 1 until lassoPoints.size) {
                                                                np.lineTo(lassoPoints[i].x * sc2X - bmpW2 / 2f, lassoPoints[i].y * sc2Y - bmpH2 / 2f)
                                                            }
                                                            np.close()
                                                        }
                                                        liveSelectionPath = np
                                                        val nowNs = System.nanoTime()
                                                        if (nowNs - lastLassoPreviewNs > 45_000_000L) {
                                                            lastLassoPreviewNs = nowNs
                                                            vm.previewLasso(
                                                                lassoPoints.map { it.x.toInt() to it.y.toInt() }
                                                            )
                                                        }
                                                    }
                                                }
                                            } else if (lassoPoints.lastOrNull() != imagePos) {
                                                lassoPoints += imagePos
                                                // Live selection preview: fill the
                                                // polygon into the overlay while the
                                                // finger moves (throttled); the
                                                // committed selection replaces it
                                                val nowNs = System.nanoTime()
                                                if (nowNs - lastLassoPreviewNs > 45_000_000L) {
                                                    lastLassoPreviewNs = nowNs
                                                    vm.previewLasso(
                                                        lassoPoints.map { it.x.toInt() to it.y.toInt() }
                                                    )
                                                }
                                            }
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
                                                val downP = down.pressure.coerceIn(0f, 1f)
                                                smoothedPressure = if (stylus && downP > 0f) downP else 0.8f
                                                val startP = if (stylus) smoothedPressure.toDouble() else 1.0
                                                vm.touchStart(firstImage.x, firstImage.y, startP)
                                                strokeStarted = true
                                                vm.touchMove(imagePos.x, imagePos.y, startP)
                                            } else {
                                                if (stylus) {
                                                    val rawP = point.pressure.coerceIn(0f, 1f)
                                                    if (rawP > 0f) {
                                                        smoothedPressure = smoothedPressure * 0.6f + rawP * 0.4f
                                                    }
                                                    vm.touchMove(imagePos.x, imagePos.y, smoothedPressure.toDouble())
                                                } else {
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

                        // NOTE: the live preview stays visible after release;
                        // it is cleared only once the C++ selection overlay is
                        // ready, so there is no blink between the preview and
                        // the committed selection
                        if (!transformStarted) {
                            pendingTap?.let { tap ->
                                if (tool == Tool.TEXT) {
                                    onTextRequested(tap.x, tap.y)
                                }
                            }
                            when {
                                // One-shot tools already applied on finger-down
                                tool == Tool.MAGICWAND ||
                                    tool == Tool.SELECT_SIMILAR ||
                                    tool == Tool.FILL -> Unit

                                shapeTool -> {
                                    val kind =
                                        when (tool) {
                                            Tool.RECT -> 1
                                            Tool.ELLIPSE -> 2
                                            else -> 0
                                        }
                                    vm.drawShape(kind, firstImage.x, firstImage.y, shapeEnd.x, shapeEnd.y)
                                }

                                trackShapeTool -> {
                                    val points = lassoPoints.map { it.x.toInt() to it.y.toInt() }
                                    if (points.size >= 2) {
                                        val closed =
                                            tool == Tool.POLYGON
                                        vm.drawPolygon(points, closed = closed)
                                    }
                                }

                                twoPointTool -> {
                                    val x1 = firstImage.x.toInt()
                                    val y1 = firstImage.y.toInt()
                                    val x2 = shapeEnd.x.toInt()
                                    val y2 = shapeEnd.y.toInt()
                                    when (tool) {
                                        Tool.GRADIENT -> vm.gradientFill(x1, y1, x2, y2)
                                        Tool.SELECT_RECT -> vm.selectShape(0, x1, y1, x2, y2)
                                        Tool.SELECT_ELLIPSE -> vm.selectShape(1, x1, y1, x2, y2)
                                        Tool.CROP -> {
                                            val cx = minOf(x1, x2)
                                            val cy = minOf(y1, y2)
                                            val cw = maxOf(1, kotlin.math.abs(x2 - x1))
                                            val ch = maxOf(1, kotlin.math.abs(y2 - y1))
                                            vm.cropCanvas(cx, cy, cw, ch)
                                        }
                                        else -> Unit
                                    }
                                }

                                trackSelectTool -> {
                                    gestureEnded = true
                                    if (lassoPoints.size >= 3) {
                                        val points = lassoPoints.map { it.x.toInt() to it.y.toInt() }
                                        if (tool == Tool.SELECT_POLYGON) {
                                            // Freeze the preview at the exact final
                                            // path before committing so the
                                            // preview -> selection transition has
                                            // no visible jump
                                            vm.previewLassoSync(points)
                                            vm.selectPolygon(points)
                                        } else {
                                            vm.previewLassoSync(points)
                                            vm.lassoSelect(points)
                                        }
                                    }
                                }

                                tool == Tool.MOVE -> {
                                    val dx = (shapeEnd.x - firstImage.x).toInt()
                                    val dy = (shapeEnd.y - firstImage.y).toInt()
                                    if (dx != 0 || dy != 0) vm.moveLayerContent(dx, dy)
                                }

                                tool == Tool.LASSO || tool == Tool.SELECT_MAGNETIC -> {
                                    gestureEnded = true
                                    if (lassoPoints.size >= 3) {
                                        val points = lassoPoints.map { it.x.toInt() to it.y.toInt() }
                                        vm.previewLassoSync(points)
                                        vm.selectPolygon(points)
                                    }
                                }

                                strokeStarted -> {
                                    vm.touchEnd()
                                }

                                // A pure tap on a painting tool (no movement)
                                // still commits a single dab at the tap point
                                mode == GestureMode.STROKE -> {
                                    vm.touchStart(firstImage.x, firstImage.y, if (stylus) down.pressure.coerceIn(0f, 1f).toDouble() else 1.0)
                                    vm.touchEnd()
                                }
                            }

                            pickerActive = false
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
                rotate(rotation, pivot = Offset.Zero)
                scale(scale, scale, pivot = Offset.Zero)
            }) {
                drawRect(
                    Morandi.canvasShadow,
                    topLeft = Offset(-image.width / 2f, -image.height / 2f),
                    size = androidx.compose.ui.geometry.Size(image.width.toFloat(), image.height.toFloat()),
                )
            }
            withTransform({
                translate(center.x, center.y)
                rotate(rotation, pivot = Offset.Zero)
                scale(scale, scale, pivot = Offset.Zero)
            }) {
                // Draw a grid on the paper background to make transparency obvious
                drawRect(
                    Color.White,
                    topLeft = Offset(-image.width / 2f, -image.height / 2f),
                    size = androidx.compose.ui.geometry.Size(image.width.toFloat(), image.height.toFloat())
                )
                
                // Draw the actual canvas image over the white paper
                drawImage(image, topLeft = Offset(-image.width / 2f, -image.height / 2f))

                val selBmp = vm.selectionOverlayBitmap?.asImageBitmap()
                if (selBmp != null || liveSelectionPath != null) {
                    val paint = androidx.compose.ui.graphics.Paint().apply {
                        colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(
                            Morandi.accent.copy(alpha = 0.35f)
                        )
                    }
                    val bounds = androidx.compose.ui.geometry.Rect(
                        -image.width / 2f, -image.height / 2f,
                        image.width / 2f, image.height / 2f
                    )
                    drawContext.canvas.saveLayer(bounds, paint)

                    if (selBmp != null) {
                        // The selection overlay is full-document resolution;
                        // scale it into the (viewport-sized) canvas image
                        drawImage(
                            image = selBmp,
                            topLeft = Offset(-image.width / 2f, -image.height / 2f),
                        )
                    }

                    liveSelectionPath?.let { livePath ->
                        // The preview path is a pure visual outline: the live
                        // fill overlay already reflects the merged result (the
                        // C++ preview merge runs the same combine as the
                        // committed path), so drawing the outline with a
                        // subtract/intersect blend mode would carve a hole in
                        // that merged fill and visibly differ from the final
                        // selection - keep it SrcOver on top of the fill
                        drawPath(
                            path = livePath,
                            color = Color.White,
                            style = androidx.compose.ui.graphics.drawscope.Stroke(
                                width = 2.dp.toPx(),
                            ),
                            blendMode = androidx.compose.ui.graphics.BlendMode.SrcOver
                        )
                    }

                    drawContext.canvas.restore()
                }
            }

            // Draw PaintWorld-style Color Loupe when picker is active
            if (pickerActive) {
                val loupeCenter = pickerScreenPos + Offset(0f, -80.dp.toPx())
                val outerRadius = 45.dp.toPx()
                val innerRadius = 28.dp.toPx()
                val ringThickness = outerRadius - innerRadius
                val ringRadius = (outerRadius + innerRadius) / 2f

                // Outer drop shadow
                drawCircle(
                    color = Color.Black.copy(alpha = 0.35f),
                    radius = outerRadius + 4.dp.toPx(),
                    center = loupeCenter
                )

                // Top half ring: Reference / Previous color
                drawArc(
                    color = pickerInitialColor,
                    startAngle = 180f,
                    sweepAngle = 180f,
                    useCenter = false,
                    topLeft = Offset(loupeCenter.x - ringRadius, loupeCenter.y - ringRadius),
                    size = Size(ringRadius * 2, ringRadius * 2),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = ringThickness)
                )

                // Bottom half ring: Current sampled color
                drawArc(
                    color = pickerCurrentColor,
                    startAngle = 0f,
                    sweepAngle = 180f,
                    useCenter = false,
                    topLeft = Offset(loupeCenter.x - ringRadius, loupeCenter.y - ringRadius),
                    size = Size(ringRadius * 2, ringRadius * 2),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = ringThickness)
                )

                // Outer border line
                drawCircle(
                    color = Color.Black.copy(alpha = 0.5f),
                    radius = outerRadius,
                    center = loupeCenter,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5.dp.toPx())
                )
                // Inner border line
                drawCircle(
                    color = Color.Black.copy(alpha = 0.5f),
                    radius = innerRadius,
                    center = loupeCenter,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5.dp.toPx())
                )

                // Center crosshair inside the loupe
                val crosshairInner = 6.dp.toPx()
                drawLine(
                    color = Color.Black.copy(alpha = 0.7f),
                    start = Offset(loupeCenter.x - crosshairInner, loupeCenter.y),
                    end = Offset(loupeCenter.x + crosshairInner, loupeCenter.y),
                    strokeWidth = 1.5.dp.toPx()
                )
                drawLine(
                    color = Color.Black.copy(alpha = 0.7f),
                    start = Offset(loupeCenter.x, loupeCenter.y - crosshairInner),
                    end = Offset(loupeCenter.x, loupeCenter.y + crosshairInner),
                    strokeWidth = 1.5.dp.toPx()
                )

                // Crosshair at the target touch point on the canvas
                val crossLen = 14.dp.toPx()
                drawLine(
                    color = Color.Black.copy(alpha = 0.5f),
                    start = Offset(pickerScreenPos.x - crossLen, pickerScreenPos.y),
                    end = Offset(pickerScreenPos.x + crossLen, pickerScreenPos.y),
                    strokeWidth = 3.dp.toPx()
                )
                drawLine(
                    color = Color.White,
                    start = Offset(pickerScreenPos.x - crossLen, pickerScreenPos.y),
                    end = Offset(pickerScreenPos.x + crossLen, pickerScreenPos.y),
                    strokeWidth = 1.5.dp.toPx()
                )
                drawLine(
                    color = Color.Black.copy(alpha = 0.5f),
                    start = Offset(pickerScreenPos.x, pickerScreenPos.y - crossLen),
                    end = Offset(pickerScreenPos.x, pickerScreenPos.y + crossLen),
                    strokeWidth = 3.dp.toPx()
                )
                drawLine(
                    color = Color.White,
                    start = Offset(pickerScreenPos.x, pickerScreenPos.y - crossLen),
                    end = Offset(pickerScreenPos.x, pickerScreenPos.y + crossLen),
                    strokeWidth = 1.5.dp.toPx()
                )
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
    bmpW: Int,
    bmpH: Int,
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
    // Bitmap (viewport) coordinates: the canvas bitmap is bmpW x bmpH and is
    // drawn centred at the widget origin, so the inverse of the draw
    // transform lands on bitmap pixels
    val bx = unrotatedX / scale + bmpW / 2f
    val by = unrotatedY / scale + bmpH / 2f
    // Bitmap -> document space: the C++ core works in full document
    // coordinates (1080x1920 etc.), while the render viewport is downscaled
    return Offset(bx * (docW.toFloat() / bmpW), by * (docH.toFloat() / bmpH))
}
