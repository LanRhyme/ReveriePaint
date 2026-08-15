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
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerHoverIcon
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
    tfState: TransformState,
    polyPoints: List<Offset> = emptyList(),
    onPolyPoint: (Offset) -> Unit = {},
    cropRect: androidx.compose.ui.geometry.Rect? = null,
    onCropRect: (androidx.compose.ui.geometry.Rect?) -> Unit = {},
    fillTolerance: Int = 24,
    gradientType: Int = 0,
    liquifyStrength: Float = 0.9f,
    liquifyMode: Int = 0,
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
    var liveShapeStart by remember { mutableStateOf<Offset?>(null) }
    var liveShapeEnd by remember { mutableStateOf<Offset?>(null) }

    // Instant feedback ring at a magic-wand / similar-color tap: the
    // selection computation runs on the render thread (~20-60ms), so a small
    // flash at the tap point tells the user the tap registered immediately
    var wandFlash by remember { mutableStateOf<Offset?>(null) }
    LaunchedEffect(wandFlash) {
        if (wandFlash != null) {
            kotlinx.coroutines.delay(450)
            wandFlash = null
        }
    }
    // Measure tool: start/end points (document coords), live distance shown
    var measureStart by remember { mutableStateOf<Offset?>(null) }
    var measureEnd by remember { mutableStateOf<Offset?>(null) }

    var pickerActive by remember { mutableStateOf(false) }
    var pickerScreenPos by remember { mutableStateOf(Offset.Zero) }
    var pickerInitialColor by remember { mutableStateOf(Color.White) }
    var pickerCurrentColor by remember { mutableStateOf(Color.White) }

    // Krita-style cursor hover and touch position tracking
    var cursorScreenPos by remember { mutableStateOf<Offset?>(null) }
    var isCursorHovering by remember { mutableStateOf(false) }
    var isCursorTouching by remember { mutableStateOf(false) }
    var livePressure by remember { mutableStateOf(1f) }

    // ---- Transform tool state (document coords, lifted for the panel) ----
    // Transformed corner points of the rubber band: 0-3 corners (TL,TR,BR,BL),
    // 4-7 edge midpoints
    fun tfTransform(p: Offset): Offset {
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

    fun tfHandles(): List<Offset> {
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
        return corners.map { tfTransform(it) } + mids.map { tfTransform(it) }
    }

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

    // Document size changes (crop / preset switch) must recompute the
    // viewport render size or the canvas renders at stale dimensions
    LaunchedEffect(vm.docWidth, vm.docHeight) {
        if (viewW > 0 && viewH > 0) {
            vm.setRenderViewport(viewW, viewH)
        }
    }

    val context = androidx.compose.ui.platform.LocalContext.current
    val view = androidx.compose.ui.platform.LocalView.current
    val checkerboardPaint = remember {
        val tileSize = 24
        val bmp = android.graphics.Bitmap.createBitmap(tileSize * 2, tileSize * 2, android.graphics.Bitmap.Config.ARGB_8888)
        val cv = android.graphics.Canvas(bmp)
        val p1 = android.graphics.Paint().apply { color = android.graphics.Color.WHITE }
        val p2 = android.graphics.Paint().apply { color = android.graphics.Color.rgb(228, 230, 235) }
        cv.drawRect(0f, 0f, tileSize.toFloat(), tileSize.toFloat(), p1)
        cv.drawRect(tileSize.toFloat(), 0f, (tileSize * 2).toFloat(), tileSize.toFloat(), p2)
        cv.drawRect(0f, tileSize.toFloat(), tileSize.toFloat(), (tileSize * 2).toFloat(), p2)
        cv.drawRect(tileSize.toFloat(), tileSize.toFloat(), (tileSize * 2).toFloat(), (tileSize * 2).toFloat(), p1)
        val shader = android.graphics.BitmapShader(bmp, android.graphics.Shader.TileMode.REPEAT, android.graphics.Shader.TileMode.REPEAT)
        android.graphics.Paint().apply { this.shader = shader }
    }
    val customPointerIcon = remember(context) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            val transparentBmp = android.graphics.Bitmap.createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888)
            val nullIcon = android.view.PointerIcon.create(transparentBmp, 0f, 0f)
            PointerIcon(nullIcon)
        } else {
            PointerIcon.Default
        }
    }

    LaunchedEffect(Unit) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            val transparentBmp = android.graphics.Bitmap.createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888)
            view.pointerIcon = android.view.PointerIcon.create(transparentBmp, 0f, 0f)
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
                .pointerHoverIcon(customPointerIcon)
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                            val change = event.changes.firstOrNull()
                            if (change != null) {
                                val isStylusOrMouse = change.type == PointerType.Stylus ||
                                        change.type == PointerType.Eraser ||
                                        change.type == PointerType.Mouse

                                if (vm.penOnlyMode && !isStylusOrMouse) {
                                    // Finger touches in pen-only mode must not move or trigger the cursor
                                    continue
                                }

                                cursorScreenPos = change.position
                                when (event.type) {
                                    PointerEventType.Enter, PointerEventType.Move -> {
                                        isCursorHovering = !change.pressed
                                        isCursorTouching = change.pressed
                                        if (change.pressed && change.pressure > 0f) {
                                            livePressure = vm.evaluatePressure(change.pressure)
                                        } else if (!change.pressed) {
                                            livePressure = 1f
                                        }
                                    }
                                    PointerEventType.Exit -> {
                                        isCursorHovering = false
                                        isCursorTouching = false
                                        cursorScreenPos = null
                                        livePressure = 1f
                                    }
                                    PointerEventType.Press -> {
                                        isCursorTouching = true
                                        isCursorHovering = false
                                        if (change.pressure > 0f) {
                                            livePressure = vm.evaluatePressure(change.pressure)
                                        }
                                    }
                                    PointerEventType.Release -> {
                                        isCursorTouching = false
                                        isCursorHovering = true
                                        livePressure = 1f
                                    }
                                }
                            }
                        }
                    }
                }
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
                        val pointClickTool =
                            tool == Tool.POLYGON || tool == Tool.POLYLINE || tool == Tool.PATH || tool == Tool.SELECT_POLYGON
                        val twoPointTool =
                            tool == Tool.GRADIENT || tool == Tool.SELECT_RECT ||
                                tool == Tool.SELECT_ELLIPSE
                        val trackSelectTool =
                            tool == Tool.SELECT_MAGNETIC
                        val stylus = down.type == androidx.compose.ui.input.pointer.PointerType.Stylus || down.type == androidx.compose.ui.input.pointer.PointerType.Eraser
                        var mode =
                            when {
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

                        if (shapeTool || twoPointTool) {
                            liveShapeStart = firstImage
                            liveShapeEnd = firstImage
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
                                            )
                                        )
                                    } else {
                                        tfState.reset(
                                            androidx.compose.ui.geometry.Rect(
                                                0f,
                                                0f,
                                                vm.docWidth.toFloat(),
                                                vm.docHeight.toFloat(),
                                            )
                                        )
                                    }
                                    vm.startTransformPreview()
                                }
                                if (tool == Tool.MOVE) {
                                    tfState.handle = 8 // Translate only
                                } else {
                                    val handles = tfHandles()
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
                                        tfState.handle = if (best >= 0) best else if (inBox) 8 else 9
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
                                measureStart = firstImage
                                measureEnd = firstImage
                            }

                            Tool.PICKER -> {
                                pickerActive = true
                                val targetOffset = Offset(-48.dp.toPx(), -48.dp.toPx())
                                val sampleScreenPos = down.position + targetOffset
                                pickerScreenPos = sampleScreenPos
                                val refHex = vm.brushColor
                                pickerInitialColor = parseColor(refHex)
                                val sampleImage = widgetToImage(
                                    sampleScreenPos,
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
                                val ix = sampleImage.x.toInt()
                                val iy = sampleImage.y.toInt()
                                if (ix in 0 until image.width && iy in 0 until image.height) {
                                    val pixel = image.getPixel(ix, iy)
                                    if (android.graphics.Color.alpha(pixel) > 0) {
                                        pickerCurrentColor = Color(pixel)
                                    }
                                }
                            }
                            Tool.MAGICWAND -> {
                                wandFlash = firstImage
                                vm.selectContiguous(firstImage.x.toInt(), firstImage.y.toInt())
                                tapReverted = true
                            }
                            Tool.SELECT_SIMILAR -> {
                                wandFlash = firstImage
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
                                            // Live fill preview while dragging
                                            // (throttled; the committed selection
                                            // replaces it). SELECT_RECT/ELLIPSE
                                            // only show the shape outline
                                            if (tool == Tool.LASSO || tool == Tool.SELECT_POLYGON) {
                                                val nowNs = System.nanoTime()
                                                if (nowNs - lastLassoPreviewNs > 45_000_000L) {
                                                    lastLassoPreviewNs = nowNs
                                                    vm.previewLasso(
                                                        lassoPoints.map { it.x.toInt() to it.y.toInt() }
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
                                                )
                                            )
                                        }

                                        shapeTool || twoPointTool -> {
                                            shapeEnd = imagePos
                                            liveShapeEnd = imagePos
                                        }

                                        tool == Tool.PICKER -> {
                                            val targetOffset = Offset(-48.dp.toPx(), -48.dp.toPx())
                                            val sampleScreenPos = point.position + targetOffset
                                            pickerScreenPos = sampleScreenPos
                                            val sampleImage = widgetToImage(
                                                sampleScreenPos,
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
                                            val ix = sampleImage.x.toInt()
                                            val iy = sampleImage.y.toInt()
                                            if (ix in 0 until image.width && iy in 0 until image.height) {
                                                val pixel = image.getPixel(ix, iy)
                                                if (android.graphics.Color.alpha(pixel) > 0) {
                                                    pickerCurrentColor = Color(pixel)
                                                }
                                            }
                                        }

                                        tool == Tool.SELECT_MAGNETIC ||
                                            tool == Tool.LASSO || tool == Tool.MAGICWAND -> {
                                            if (tool == Tool.SELECT_MAGNETIC) {
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
                                                val nowNs = System.nanoTime()
                                                if (nowNs - lastLassoPreviewNs > 45_000_000L) {
                                                    lastLassoPreviewNs = nowNs
                                                    vm.previewLasso(
                                                        lassoPoints.map { it.x.toInt() to it.y.toInt() }
                                                    )
                                                }
                                            }
                                        }

                                        tool == Tool.MEASURE -> {
                                            measureEnd = imagePos
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
                                                        val a1 = atan2(tfState.dragStart.y - c.y - tfState.startTy, tfState.dragStart.x - c.x - tfState.startTx)
                                                        val a2 = atan2(imagePos.y - c.y - tfState.startTy, imagePos.x - c.x - tfState.startTx)
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
                                                livePressure = smoothedPressure
                                                val startP = if (stylus) smoothedPressure.toDouble() else 1.0
                                                vm.touchStart(firstImage.x, firstImage.y, startP)
                                                strokeStarted = true
                                                vm.touchMove(imagePos.x, imagePos.y, startP)
                                            } else {
                                                if (stylus) {
                                                    val rawP = point.pressure.coerceIn(0f, 1f)
                                                    if (rawP > 0f) {
                                                        val mappedP = vm.evaluatePressure(rawP)
                                                        smoothedPressure = smoothedPressure * 0.6f + mappedP * 0.4f
                                                    }
                                                    livePressure = smoothedPressure
                                                    vm.touchMove(imagePos.x, imagePos.y, smoothedPressure.toDouble())
                                                } else {
                                                    livePressure = 1f
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

                        liveShapeStart = null
                        liveShapeEnd = null

                        if (!transformStarted) {
                            pendingTap?.let { tap ->
                                if (tool == Tool.TEXT) {
                                    onTextRequested(tap.x, tap.y)
                                }
                            }
                            when {
                                tool == Tool.MAGICWAND ||
                                    tool == Tool.SELECT_SIMILAR ||
                                    tool == Tool.FILL -> Unit

                                 tool == Tool.TRANSFORM -> {
                                     tfState.handle = -1
                                 }

                                 tool == Tool.CROP -> Unit

                                 tool == Tool.MEASURE -> Unit

                                 shapeTool -> {
                                     val kind =
                                         when (tool) {
                                             Tool.RECT -> 1
                                             Tool.ELLIPSE -> 2
                                             else -> 0
                                         }
                                     vm.drawShape(kind, firstImage.x, firstImage.y, shapeEnd.x, shapeEnd.y)
                                 }

                                 pointClickTool -> Unit

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
                                             tfState.bounds = androidx.compose.ui.geometry.Rect(
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
                                    val r = (pickerCurrentColor.red * 255).toInt().coerceIn(0, 255)
                                    val g = (pickerCurrentColor.green * 255).toInt().coerceIn(0, 255)
                                    val b = (pickerCurrentColor.blue * 255).toInt().coerceIn(0, 255)
                                    val hex = String.format("#%02X%02X%02X", r, g, b)
                                    vm.updateBrushColor(hex)
                                }

                                strokeStarted -> {
                                    vm.touchEnd()
                                }

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
                // Draw transparency checkerboard under the canvas image
                val nativeCanvas = drawContext.canvas.nativeCanvas
                nativeCanvas.drawRect(
                    -image.width / 2f,
                    -image.height / 2f,
                    image.width / 2f,
                    image.height / 2f,
                    checkerboardPaint
                )
                
                // Draw the actual canvas image over the checkerboard
                drawImage(image, topLeft = Offset(-image.width / 2f, -image.height / 2f))

                // Draw transform preview
                val previewBmp = vm.transformPreviewBitmap
                if ((tool == Tool.TRANSFORM || tool == Tool.MOVE) && tfState.active && previewBmp != null) {
                    val scX = if (vm.docWidth > 0) image.width.toFloat() / vm.docWidth else 1f
                    val scY = if (vm.docHeight > 0) image.height.toFloat() / vm.docHeight else 1f
                    if (tool == Tool.TRANSFORM && tfState.mode == TransformMode.DISTORT) {
                        // 3x3 Mesh Grid (9 cells) Piecewise Quad Warping on GPU
                        val nativeCanvas = drawContext.canvas.nativeCanvas
                        val aBmp = previewBmp.asAndroidBitmap()
                        val b = tfState.bounds
                        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG)

                        for (r in 0..2) {
                            for (c in 0..2) {
                                val sLeft = (b.left + b.width * (c / 3f)) * scX - image.width / 2f
                                val sRight = (b.left + b.width * ((c + 1) / 3f)) * scX - image.width / 2f
                                val sTop = (b.top + b.height * (r / 3f)) * scY - image.height / 2f
                                val sBottom = (b.top + b.height * ((r + 1) / 3f)) * scY - image.height / 2f

                                val srcQuad = floatArrayOf(
                                    sLeft, sTop,
                                    sRight, sTop,
                                    sRight, sBottom,
                                    sLeft, sBottom,
                                )

                                val pTL = tfState.meshPoints[r * 4 + c]
                                val pTR = tfState.meshPoints[r * 4 + (c + 1)]
                                val pBR = tfState.meshPoints[(r + 1) * 4 + (c + 1)]
                                val pBL = tfState.meshPoints[(r + 1) * 4 + c]

                                val dstQuad = floatArrayOf(
                                    pTL.x * scX - image.width / 2f, pTL.y * scY - image.height / 2f,
                                    pTR.x * scX - image.width / 2f, pTR.y * scY - image.height / 2f,
                                    pBR.x * scX - image.width / 2f, pBR.y * scY - image.height / 2f,
                                    pBL.x * scX - image.width / 2f, pBL.y * scY - image.height / 2f,
                                )

                                val m = android.graphics.Matrix()
                                if (m.setPolyToPoly(srcQuad, 0, dstQuad, 0, 4)) {
                                    nativeCanvas.save()
                                    val clipPath = android.graphics.Path().apply {
                                        moveTo(dstQuad[0], dstQuad[1])
                                        lineTo(dstQuad[2], dstQuad[3])
                                        lineTo(dstQuad[4], dstQuad[5])
                                        lineTo(dstQuad[6], dstQuad[7])
                                        close()
                                    }
                                    nativeCanvas.clipPath(clipPath)
                                    nativeCanvas.concat(m)
                                    nativeCanvas.drawBitmap(
                                        aBmp,
                                        null,
                                        android.graphics.RectF(-image.width / 2f, -image.height / 2f, image.width / 2f, image.height / 2f),
                                        p
                                    )
                                    nativeCanvas.restore()
                                }
                            }
                        }
                    } else if (tool == Tool.TRANSFORM && tfState.mode == TransformMode.PERSPECTIVE) {
                        // Projective / Perspective Matrix Mapping using Android nativeCanvas
                        val nativeCanvas = drawContext.canvas.nativeCanvas
                        val aBmp = previewBmp.asAndroidBitmap()
                        val b = tfState.bounds
                        val src = floatArrayOf(
                            b.left * scX - image.width / 2f, b.top * scY - image.height / 2f,
                            b.right * scX - image.width / 2f, b.top * scY - image.height / 2f,
                            b.right * scX - image.width / 2f, b.bottom * scY - image.height / 2f,
                            b.left * scX - image.width / 2f, b.bottom * scY - image.height / 2f,
                        )
                        val c0 = tfState.quadCorners[0]
                        val c1 = tfState.quadCorners[1]
                        val c2 = tfState.quadCorners[2]
                        val c3 = tfState.quadCorners[3]
                        val dst = floatArrayOf(
                            c0.x * scX - image.width / 2f, c0.y * scY - image.height / 2f,
                            c1.x * scX - image.width / 2f, c1.y * scY - image.height / 2f,
                            c2.x * scX - image.width / 2f, c2.y * scY - image.height / 2f,
                            c3.x * scX - image.width / 2f, c3.y * scY - image.height / 2f,
                        )
                        val m = android.graphics.Matrix()
                        if (m.setPolyToPoly(src, 0, dst, 0, 4)) {
                            nativeCanvas.save()
                            nativeCanvas.concat(m)
                            val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG)
                            nativeCanvas.drawBitmap(
                                aBmp,
                                null,
                                android.graphics.RectF(-image.width / 2f, -image.height / 2f, image.width / 2f, image.height / 2f),
                                p
                            )
                            nativeCanvas.restore()
                        }
                    } else {
                        // Standard / Free / Move Affine Transform
                        val c = tfState.bounds.center
                        withTransform({
                            translate(c.x * scX - image.width / 2f + tfState.tx * scX, c.y * scY - image.height / 2f + tfState.ty * scY)
                            rotate(tfState.rotation, pivot = Offset.Zero)
                            scale(tfState.scaleX, tfState.scaleY, pivot = Offset.Zero)
                            translate(-c.x * scX + image.width / 2f, -c.y * scY + image.height / 2f)
                        }) {
                            drawImage(
                                image = previewBmp,
                                dstSize = androidx.compose.ui.unit.IntSize(image.width, image.height),
                                dstOffset = androidx.compose.ui.unit.IntOffset((-image.width / 2f).toInt(), (-image.height / 2f).toInt()),
                                filterQuality = androidx.compose.ui.graphics.FilterQuality.High,
                            )
                        }
                    }
                }

                // Magic-wand tap flash: instant feedback ring in document
                // space (scaled into bitmap space like the preview path)
                wandFlash?.let { wf ->
                    val scX = if (vm.docWidth > 0) image.width.toFloat() / vm.docWidth else 1f
                    val scY = if (vm.docHeight > 0) image.height.toFloat() / vm.docHeight else 1f
                    drawCircle(
                        color = Color.White.copy(alpha = 0.85f),
                        radius = 6.dp.toPx(),
                        center = Offset(wf.x * scX - image.width / 2f, wf.y * scY - image.height / 2f),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3.dp.toPx()),
                    )
                }

                // Point-click shape preview (polygon/polyline/select)
                if (polyPoints.isNotEmpty() && (tool == Tool.POLYGON || tool == Tool.POLYLINE || tool == Tool.SELECT_POLYGON)) {
                    val scX = if (vm.docWidth > 0) image.width.toFloat() / vm.docWidth else 1f
                    val scY = if (vm.docHeight > 0) image.height.toFloat() / vm.docHeight else 1f
                    val pth = androidx.compose.ui.graphics.Path()
                    pth.moveTo(
                        polyPoints[0].x * scX - image.width / 2f,
                        polyPoints[0].y * scY - image.height / 2f,
                    )
                    for (i in 1 until polyPoints.size) {
                        pth.lineTo(
                            polyPoints[i].x * scX - image.width / 2f,
                            polyPoints[i].y * scY - image.height / 2f,
                        )
                    }
                    if (tool == Tool.POLYGON) {
                        pth.close()
                    }
                    drawPath(
                        pth,
                        color = Color.White,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx()),
                    )
                    polyPoints.forEach { pt ->
                        drawCircle(
                            color = Color.White,
                            radius = 3.dp.toPx(),
                            center = Offset(pt.x * scX - image.width / 2f, pt.y * scY - image.height / 2f),
                        )
                    }
                }

                // Measure tool: white line + distance/angle text
                if (tool == Tool.MEASURE && measureStart != null && measureEnd != null) {
                    val scX = if (vm.docWidth > 0) image.width.toFloat() / vm.docWidth else 1f
                    val scY = if (vm.docHeight > 0) image.height.toFloat() / vm.docHeight else 1f
                    val s = measureStart!!
                    val e = measureEnd!!
                    val p1 = Offset(s.x * scX - image.width / 2f, s.y * scY - image.height / 2f)
                    val p2 = Offset(e.x * scX - image.width / 2f, e.y * scY - image.height / 2f)
                    drawLine(Color.White, p1, p2, strokeWidth = 2.dp.toPx())
                    drawCircle(Color.White, radius = 3.dp.toPx(), center = p1)
                    drawCircle(Color.White, radius = 3.dp.toPx(), center = p2)
                    val dist = hypot(e.x - s.x, e.y - s.y)
                    val ang = Math.toDegrees(atan2((e.y - s.y).toDouble(), (e.x - s.x).toDouble())).toFloat()
                    val label =
                        "%.0f px  %.1f°".format(dist, ang)
                    drawContext.canvas.nativeCanvas.drawText(
                        label,
                        (p2.x + 8.dp.toPx()),
                        (p2.y - 8.dp.toPx()),
                        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                            color = android.graphics.Color.WHITE
                            textSize = 13.dp.toPx()
                            isFakeBoldText = true
                        },
                    )
                }

                // Crop tool preview: dim the outside, white frame
                cropRect?.let { cr ->
                    val scX = if (vm.docWidth > 0) image.width.toFloat() / vm.docWidth else 1f
                    val scY = if (vm.docHeight > 0) image.height.toFloat() / vm.docHeight else 1f
                    val bx = { v: Float -> v * scX - image.width / 2f }
                    val by2 = { v: Float -> v * scY - image.height / 2f }
                    val hole =
                        androidx.compose.ui.geometry.Rect(
                            bx(cr.left),
                            by2(cr.top),
                            bx(cr.right),
                            by2(cr.bottom),
                        )
                    drawContext.canvas.saveLayer(
                        androidx.compose.ui.geometry.Rect(0f, 0f, size.width.toFloat(), size.height.toFloat()),
                        androidx.compose.ui.graphics.Paint(),
                    )
                    drawRect(color = Color.Black.copy(alpha = 0.4f))
                    drawRect(
                        color = Color.White,
                        topLeft = hole.topLeft,
                        size = hole.size,
                        blendMode = androidx.compose.ui.graphics.BlendMode.Clear,
                    )
                    drawContext.canvas.restore()
                    drawRect(
                        color = Color.White,
                        topLeft = hole.topLeft,
                        size = hole.size,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx()),
                    )
                }

                // Transform tool rubber band (bitmap space, origin at the image centre)
                if (tool == Tool.TRANSFORM && tfState.active) {
                    val scX = if (vm.docWidth > 0) image.width.toFloat() / vm.docWidth else 1f
                    val scY = if (vm.docHeight > 0) image.height.toFloat() / vm.docHeight else 1f
                    val bx = { p: Offset -> Offset(p.x * scX - image.width / 2f, p.y * scY - image.height / 2f) }
                    val handles = tfHandles().map { bx(it) }
                    val currentScale = zoom * fitScale

                    if (tfState.mode == TransformMode.DISTORT) {
                        // 3x3 Mesh Grid (16 Handles + 4 horizontal lines + 4 vertical lines)
                        if (handles.size == 16) {
                            // 1. Draw horizontal grid lines
                            for (r in 0..3) {
                                val linePath = androidx.compose.ui.graphics.Path().apply {
                                    moveTo(handles[r * 4].x, handles[r * 4].y)
                                    for (c in 1..3) {
                                        lineTo(handles[r * 4 + c].x, handles[r * 4 + c].y)
                                    }
                                }
                                val isBorder = (r == 0 || r == 3)
                                drawPath(
                                    linePath,
                                    color = if (isBorder) Color(0xFF181B22) else Color(0x66181B22),
                                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = if (isBorder) 3.dp.toPx() / currentScale else 2.dp.toPx() / currentScale),
                                )
                                drawPath(
                                    linePath,
                                    color = if (isBorder) Morandi.accent else Color(0x88AAB3C2),
                                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = if (isBorder) 1.5.dp.toPx() / currentScale else 1.dp.toPx() / currentScale),
                                )
                            }
                            // 2. Draw vertical grid lines
                            for (c in 0..3) {
                                val linePath = androidx.compose.ui.graphics.Path().apply {
                                    moveTo(handles[c].x, handles[c].y)
                                    for (r in 1..3) {
                                        lineTo(handles[r * 4 + c].x, handles[r * 4 + c].y)
                                    }
                                }
                                val isBorder = (c == 0 || c == 3)
                                drawPath(
                                    linePath,
                                    color = if (isBorder) Color(0xFF181B22) else Color(0x66181B22),
                                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = if (isBorder) 3.dp.toPx() / currentScale else 2.dp.toPx() / currentScale),
                                )
                                drawPath(
                                    linePath,
                                    color = if (isBorder) Morandi.accent else Color(0x88AAB3C2),
                                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = if (isBorder) 1.5.dp.toPx() / currentScale else 1.dp.toPx() / currentScale),
                                )
                            }
                            // 3. Draw 16 Control Handles
                            handles.forEachIndexed { idx, h ->
                                val isCorner = (idx == 0 || idx == 3 || idx == 12 || idx == 15)
                                val hr = (if (isCorner) 9.dp.toPx() else 6.5.dp.toPx()) / currentScale
                                drawCircle(Color(0xFF22262E), radius = hr, center = h)
                                drawCircle(if (isCorner) Morandi.accent else Color(0xFFAAB3C2), radius = hr, center = h, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5.dp.toPx() / currentScale))
                                drawCircle(Color.White, radius = (if (isCorner) 3.dp.toPx() else 2.dp.toPx()) / currentScale, center = h)
                            }
                        }
                    } else if (tfState.mode == TransformMode.PERSPECTIVE) {
                        // 4-Point Quad Frame
                        if (handles.size == 4) {
                            val quadPath = androidx.compose.ui.graphics.Path().apply {
                                moveTo(handles[0].x, handles[0].y)
                                lineTo(handles[1].x, handles[1].y)
                                lineTo(handles[2].x, handles[2].y)
                                lineTo(handles[3].x, handles[3].y)
                                close()
                            }
                            drawPath(
                                quadPath,
                                color = Color(0xFF181B22),
                                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3.dp.toPx() / currentScale),
                            )
                            drawPath(
                                quadPath,
                                color = Morandi.accent,
                                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5.dp.toPx() / currentScale),
                            )
                            val handleRadius = 11.dp.toPx() / currentScale
                            handles.forEach { h ->
                                drawCircle(Color(0xFF22262E), radius = handleRadius, center = h)
                                drawCircle(Morandi.accent, radius = handleRadius, center = h, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.6.dp.toPx() / currentScale))
                                drawCircle(Color.White, radius = 3.dp.toPx() / currentScale, center = h)
                            }
                        }
                    } else {
                        // Standard / Free 8-Handle Bounding Box
                        val frame = androidx.compose.ui.graphics.Path()
                        if (handles.size >= 4) {
                            frame.moveTo(handles[0].x, handles[0].y)
                            for (i in 1..3) {
                                frame.lineTo(handles[i].x, handles[i].y)
                            }
                            frame.close()

                            // 1. High-contrast dual-layer bounding frame
                            drawPath(
                                frame,
                                color = Color(0xFF181B22),
                                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3.dp.toPx() / currentScale),
                            )
                            drawPath(
                                frame,
                                color = Color(0xFFAAB3C2),
                                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5.dp.toPx() / currentScale),
                            )

                            // 2. Center Pivot Indicator
                            val centerDoc = tfState.bounds.center + Offset(tfState.tx, tfState.ty)
                            val centerBmp = bx(centerDoc)
                            val cr = 5.dp.toPx() / currentScale
                            drawLine(Color(0xFF181B22), centerBmp - Offset(cr, 0f), centerBmp + Offset(cr, 0f), strokeWidth = 3.dp.toPx() / currentScale)
                            drawLine(Color(0xFF181B22), centerBmp - Offset(0f, cr), centerBmp + Offset(0f, cr), strokeWidth = 3.dp.toPx() / currentScale)
                            drawLine(Morandi.accent, centerBmp - Offset(cr, 0f), centerBmp + Offset(cr, 0f), strokeWidth = 1.5.dp.toPx() / currentScale)
                            drawLine(Morandi.accent, centerBmp - Offset(0f, cr), centerBmp + Offset(0f, cr), strokeWidth = 1.5.dp.toPx() / currentScale)

                            // 3. Huashijie Pro Style Vector Handle Badges
                            val handleRadius = 11.dp.toPx() / currentScale
                            val badgeStrokeW = 1.4.dp.toPx() / currentScale
                            val glyphSize = handleRadius * 0.52f
                            val glyphColor = Color.White
                            val glyphStroke = androidx.compose.ui.graphics.drawscope.Stroke(
                                width = 1.5.dp.toPx() / currentScale,
                                cap = androidx.compose.ui.graphics.StrokeCap.Round,
                                join = androidx.compose.ui.graphics.StrokeJoin.Round,
                            )

                            handles.forEachIndexed { i, h ->
                                drawCircle(Color(0xFF22262E), radius = handleRadius, center = h)
                                drawCircle(if (i == 1 || i == 3) Morandi.accent else Color(0xFF9098A6), radius = handleRadius, center = h, style = androidx.compose.ui.graphics.drawscope.Stroke(width = badgeStrokeW))
                                when (i) {
                                    0, 2 -> {
                                        drawLine(glyphColor, h - Offset(glyphSize, glyphSize), h + Offset(glyphSize, glyphSize), strokeWidth = glyphStroke.width, cap = glyphStroke.cap)
                                        val ah = glyphSize * 0.45f
                                        drawLine(glyphColor, h - Offset(glyphSize, glyphSize), h - Offset(glyphSize - ah, glyphSize), strokeWidth = glyphStroke.width, cap = glyphStroke.cap)
                                        drawLine(glyphColor, h - Offset(glyphSize, glyphSize), h - Offset(glyphSize, glyphSize - ah), strokeWidth = glyphStroke.width, cap = glyphStroke.cap)
                                        drawLine(glyphColor, h + Offset(glyphSize, glyphSize), h + Offset(glyphSize - ah, glyphSize), strokeWidth = glyphStroke.width, cap = glyphStroke.cap)
                                        drawLine(glyphColor, h + Offset(glyphSize, glyphSize), h + Offset(glyphSize, glyphSize - ah), strokeWidth = glyphStroke.width, cap = glyphStroke.cap)
                                    }
                                    1, 3 -> {
                                        val arcRect = androidx.compose.ui.geometry.Rect(h - Offset(glyphSize, glyphSize), h + Offset(glyphSize, glyphSize))
                                        drawArc(
                                            color = Morandi.accent,
                                            startAngle = 40f,
                                            sweepAngle = 260f,
                                            useCenter = false,
                                            topLeft = arcRect.topLeft,
                                            size = arcRect.size,
                                            style = glyphStroke,
                                        )
                                        val rad = Math.toRadians(300.0)
                                        val tip = h + Offset((glyphSize * kotlin.math.cos(rad)).toFloat(), (glyphSize * kotlin.math.sin(rad)).toFloat())
                                        drawLine(Morandi.accent, tip, tip + Offset(-glyphSize * 0.35f, -glyphSize * 0.2f), strokeWidth = glyphStroke.width, cap = glyphStroke.cap)
                                        drawLine(Morandi.accent, tip, tip + Offset(-glyphSize * 0.15f, glyphSize * 0.35f), strokeWidth = glyphStroke.width, cap = glyphStroke.cap)
                                    }
                                    4, 6 -> {
                                        drawLine(glyphColor, h - Offset(0f, glyphSize), h + Offset(0f, glyphSize), strokeWidth = glyphStroke.width, cap = glyphStroke.cap)
                                        val ah = glyphSize * 0.38f
                                        drawLine(glyphColor, h - Offset(0f, glyphSize), h - Offset(-ah, glyphSize - ah), strokeWidth = glyphStroke.width, cap = glyphStroke.cap)
                                        drawLine(glyphColor, h - Offset(0f, glyphSize), h - Offset(ah, glyphSize - ah), strokeWidth = glyphStroke.width, cap = glyphStroke.cap)
                                        drawLine(glyphColor, h + Offset(0f, glyphSize), h + Offset(-ah, glyphSize - ah), strokeWidth = glyphStroke.width, cap = glyphStroke.cap)
                                        drawLine(glyphColor, h + Offset(0f, glyphSize), h + Offset(ah, glyphSize - ah), strokeWidth = glyphStroke.width, cap = glyphStroke.cap)
                                    }
                                    5, 7 -> {
                                        drawLine(glyphColor, h - Offset(glyphSize, 0f), h + Offset(glyphSize, 0f), strokeWidth = glyphStroke.width, cap = glyphStroke.cap)
                                        val ah = glyphSize * 0.38f
                                        drawLine(glyphColor, h - Offset(glyphSize, 0f), h - Offset(glyphSize - ah, -ah), strokeWidth = glyphStroke.width, cap = glyphStroke.cap)
                                        drawLine(glyphColor, h - Offset(glyphSize, 0f), h - Offset(glyphSize - ah, ah), strokeWidth = glyphStroke.width, cap = glyphStroke.cap)
                                        drawLine(glyphColor, h + Offset(glyphSize, 0f), h + Offset(glyphSize - ah, -ah), strokeWidth = glyphStroke.width, cap = glyphStroke.cap)
                                        drawLine(glyphColor, h + Offset(glyphSize, 0f), h + Offset(glyphSize - ah, ah), strokeWidth = glyphStroke.width, cap = glyphStroke.cap)
                                    }
                                }
                            }
                        }
                    }
                }

                // Polygon / Polyline vertices preview
                if (polyPoints.isNotEmpty()) {
                    val scX = if (vm.docWidth > 0) image.width.toFloat() / vm.docWidth else 1f
                    val scY = if (vm.docHeight > 0) image.height.toFloat() / vm.docHeight else 1f
                    val bx = { p: Offset -> Offset(p.x * scX - image.width / 2f, p.y * scY - image.height / 2f) }
                    val mappedPts = polyPoints.map { bx(it) }
                    val currentScale = zoom * fitScale
                    val polyPath = androidx.compose.ui.graphics.Path()
                    polyPath.moveTo(mappedPts[0].x, mappedPts[0].y)
                    for (i in 1 until mappedPts.size) {
                        polyPath.lineTo(mappedPts[i].x, mappedPts[i].y)
                    }
                    if (tool == Tool.POLYGON && mappedPts.size >= 3) {
                        polyPath.close()
                    }
                    drawPath(
                        polyPath,
                        color = Color.White,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx() / currentScale),
                    )
                    mappedPts.forEach { pt ->
                        drawCircle(
                            color = Morandi.accent,
                            radius = 5.dp.toPx() / currentScale,
                            center = pt,
                        )
                        drawCircle(
                            color = Color.White,
                            radius = 3.dp.toPx() / currentScale,
                            center = pt,
                        )
                    }
                }

                // Live shape drawing preview (line, rect, ellipse, gradient)
                if (liveShapeStart != null && liveShapeEnd != null) {
                    val scX = if (vm.docWidth > 0) image.width.toFloat() / vm.docWidth else 1f
                    val scY = if (vm.docHeight > 0) image.height.toFloat() / vm.docHeight else 1f
                    val bx = { p: Offset -> Offset(p.x * scX - image.width / 2f, p.y * scY - image.height / 2f) }
                    val s = bx(liveShapeStart!!)
                    val e = bx(liveShapeEnd!!)
                    val currentScale = zoom * fitScale
                    val strokeStyle = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx() / currentScale)

                    when (tool) {
                        Tool.LINE -> drawLine(Color.White, s, e, strokeWidth = 2.dp.toPx() / currentScale)
                        Tool.RECT -> {
                            val r = androidx.compose.ui.geometry.Rect(minOf(s.x, e.x), minOf(s.y, e.y), maxOf(s.x, e.x), maxOf(s.y, e.y))
                            drawRect(Color.White, topLeft = r.topLeft, size = r.size, style = strokeStyle)
                        }
                        Tool.ELLIPSE -> {
                            val r = androidx.compose.ui.geometry.Rect(minOf(s.x, e.x), minOf(s.y, e.y), maxOf(s.x, e.x), maxOf(s.y, e.y))
                            drawOval(Color.White, topLeft = r.topLeft, size = r.size, style = strokeStyle)
                        }
                        Tool.GRADIENT -> {
                            drawLine(Color.White, s, e, strokeWidth = 2.dp.toPx() / currentScale)
                            drawCircle(Color.White, radius = 5.dp.toPx() / currentScale, center = s)
                            drawCircle(Morandi.accent, radius = 5.dp.toPx() / currentScale, center = e)
                        }
                        else -> Unit
                    }
                }

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

            // ---- 7. Custom Krita-Style Cursor Rendering ----
            val isEraser = tool == Tool.ERASER
            val cursorMode = if (isEraser) vm.eraserCursorMode else vm.brushCursorMode
            // 0: 不显示, 1: 绘画时显示, 2: 悬空显示, 3: 绘画和悬空显示
            val shouldShow = when (cursorMode) {
                1 -> isCursorTouching
                2 -> isCursorHovering
                3 -> isCursorTouching || isCursorHovering
                else -> false
            }
            if (shouldShow && cursorScreenPos != null && (tool == Tool.BRUSH || tool == Tool.ERASER)) {
                val curPos = cursorScreenPos!!
                val pressureScale = if (isCursorTouching) livePressure.coerceIn(0.08f, 1f) else 1f
                val brushRadiusScreen = (vm.brushSize * scale * 0.5f * pressureScale).toFloat().coerceAtLeast(2f)

                when (vm.cursorStyleMode) {
                    0 -> { // 圆形 (Brush Outline Ring - Krita dual-contrast circle)
                        drawCircle(
                            color = Color.Black.copy(alpha = 0.55f),
                            radius = brushRadiusScreen + 0.8f,
                            center = curPos,
                            style = Stroke(width = 1.6.dp.toPx())
                        )
                        drawCircle(
                            color = Color.White.copy(alpha = 0.95f),
                            radius = brushRadiusScreen,
                            center = curPos,
                            style = Stroke(width = 1.0.dp.toPx())
                        )
                    }
                    1 -> { // 十字准星 (Crosshair)
                        val len = 12.dp.toPx()
                        val gap = 3.5.dp.toPx()
                        // Black outline
                        drawLine(Color.Black.copy(alpha = 0.6f), Offset(curPos.x - len, curPos.y), Offset(curPos.x - gap, curPos.y), strokeWidth = 3.dp.toPx())
                        drawLine(Color.Black.copy(alpha = 0.6f), Offset(curPos.x + gap, curPos.y), Offset(curPos.x + len, curPos.y), strokeWidth = 3.dp.toPx())
                        drawLine(Color.Black.copy(alpha = 0.6f), Offset(curPos.x, curPos.y - len), Offset(curPos.x, curPos.y - gap), strokeWidth = 3.dp.toPx())
                        drawLine(Color.Black.copy(alpha = 0.6f), Offset(curPos.x, curPos.y + gap), Offset(curPos.x, curPos.y + len), strokeWidth = 3.dp.toPx())
                        // White foreground
                        drawLine(Color.White, Offset(curPos.x - len, curPos.y), Offset(curPos.x - gap, curPos.y), strokeWidth = 1.5.dp.toPx())
                        drawLine(Color.White, Offset(curPos.x + gap, curPos.y), Offset(curPos.x + len, curPos.y), strokeWidth = 1.5.dp.toPx())
                        drawLine(Color.White, Offset(curPos.x, curPos.y - len), Offset(curPos.x, curPos.y - gap), strokeWidth = 1.5.dp.toPx())
                        drawLine(Color.White, Offset(curPos.x, curPos.y + gap), Offset(curPos.x, curPos.y + len), strokeWidth = 1.5.dp.toPx())
                    }
                    2 -> { // 点 (Precise Dot)
                        drawCircle(Color.Black.copy(alpha = 0.6f), radius = 3.5.dp.toPx(), center = curPos)
                        drawCircle(Color.White, radius = 2.dp.toPx(), center = curPos)
                    }
                    3 -> {} // 无 (No Cursor)
                }
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
