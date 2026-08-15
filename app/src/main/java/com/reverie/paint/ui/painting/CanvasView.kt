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
    val rev = vm.displayRevision
    val imageBitmap = remember(bmp, rev) { bmp?.asImageBitmap() }
    val latestZoom by rememberUpdatedState(zoom)
    val latestRotation by rememberUpdatedState(rotation)
    val latestPanX by rememberUpdatedState(panX)
    val latestPanY by rememberUpdatedState(panY)
    val latestFitScale by rememberUpdatedState(fitScale)

    // Live selection preview path (updated while dragging a selection tool)
    val liveSelectionPath = remember { mutableStateOf<androidx.compose.ui.graphics.Path?>(null) }
    val liveShapeStart = remember { mutableStateOf<Offset?>(null) }
    val liveShapeEnd = remember { mutableStateOf<Offset?>(null) }

    // Instant feedback ring at a magic-wand / similar-color tap: the
    // selection computation runs on the render thread (~20-60ms), so a small
    // flash at the tap point tells the user the tap registered immediately
    val wandFlash = remember { mutableStateOf<Offset?>(null) }
    LaunchedEffect(wandFlash.value) {
        if (wandFlash.value != null) {
            kotlinx.coroutines.delay(450)
            wandFlash.value = null
        }
    }
    // Measure tool: start/end points (document coords), live distance shown
    val measureStart = remember { mutableStateOf<Offset?>(null) }
    val measureEnd = remember { mutableStateOf<Offset?>(null) }

    val pickerActive = remember { mutableStateOf(false) }
    val pickerScreenPos = remember { mutableStateOf(Offset.Zero) }
    val pickerInitialColor = remember { mutableStateOf(Color.White) }
    val pickerCurrentColor = remember { mutableStateOf(Color.White) }

    // Krita-style cursor hover and touch position tracking
    val cursorScreenPos = remember { mutableStateOf<Offset?>(null) }
    val isCursorHovering = remember { mutableStateOf(false) }
    val isCursorTouching = remember { mutableStateOf(false) }
    val livePressure = remember { mutableStateOf(1f) }

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
            liveSelectionPath.value = null
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
    val checkerboardPaint =
        remember {
            val tileSize = 24
            val bmp = android.graphics.Bitmap.createBitmap(tileSize * 2, tileSize * 2, android.graphics.Bitmap.Config.ARGB_8888)
            val cv = android.graphics.Canvas(bmp)
            val p1 = android.graphics.Paint().apply { color = android.graphics.Color.WHITE }
            val p2 = android.graphics.Paint().apply { color = android.graphics.Color.rgb(228, 230, 235) }
            cv.drawRect(0f, 0f, tileSize.toFloat(), tileSize.toFloat(), p1)
            cv.drawRect(tileSize.toFloat(), 0f, (tileSize * 2).toFloat(), tileSize.toFloat(), p2)
            cv.drawRect(0f, tileSize.toFloat(), tileSize.toFloat(), (tileSize * 2).toFloat(), p2)
            cv.drawRect(tileSize.toFloat(), tileSize.toFloat(), (tileSize * 2).toFloat(), (tileSize * 2).toFloat(), p1)
            val shader =
                android.graphics.BitmapShader(
                    bmp,
                    android.graphics.Shader.TileMode.REPEAT,
                    android.graphics.Shader.TileMode.REPEAT,
                )
            android.graphics.Paint().apply { this.shader = shader }
        }
    val customPointerIcon =
        remember(context) {
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
                                val isStylusOrMouse =
                                    change.type == PointerType.Stylus ||
                                        change.type == PointerType.Eraser ||
                                        change.type == PointerType.Mouse

                                if (vm.penOnlyMode && !isStylusOrMouse) {
                                    // Finger touches in pen-only mode must not move or trigger the cursor
                                    continue
                                }

                                cursorScreenPos.value = change.position
                                when (event.type) {
                                    PointerEventType.Enter, PointerEventType.Move -> {
                                        isCursorHovering.value = !change.pressed
                                        isCursorTouching.value = change.pressed
                                        if (change.pressed && change.pressure > 0f) {
                                            livePressure.value = vm.evaluatePressure(change.pressure)
                                        } else if (!change.pressed) {
                                            livePressure.value = 1f
                                        }
                                    }

                                    PointerEventType.Exit -> {
                                        isCursorHovering.value = false
                                        isCursorTouching.value = false
                                        cursorScreenPos.value = null
                                        livePressure.value = 1f
                                    }

                                    PointerEventType.Press -> {
                                        isCursorTouching.value = true
                                        isCursorHovering.value = false
                                        if (change.pressure > 0f) {
                                            livePressure.value = vm.evaluatePressure(change.pressure)
                                        }
                                    }

                                    PointerEventType.Release -> {
                                        isCursorTouching.value = false
                                        if (isStylusOrMouse) {
                                            // 触控笔/鼠标：抬起后进入悬停态，指针继续跟随
                                            isCursorHovering.value = true
                                        } else {
                                            // 手指：无 hover 概念，离开屏幕即隐藏指针
                                            isCursorHovering.value = false
                                            cursorScreenPos.value = null
                                        }
                                        livePressure.value = 1f
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
                    awaitCanvasGesture(
                        image = image,
                        bmp = bmp,
                        tool = tool,
                        vm = vm,
                        tfState = tfState,
                        viewW = { viewW },
                        viewH = { viewH },
                        latestZoom = { latestZoom },
                        latestRotation = { latestRotation },
                        latestPanX = { latestPanX },
                        latestPanY = { latestPanY },
                        latestFitScale = { latestFitScale },
                        zoom = zoom,
                        fitScale = fitScale,
                        liveShapeStart = liveShapeStart,
                        liveShapeEnd = liveShapeEnd,
                        livePressure = livePressure,
                        measureStart = measureStart,
                        measureEnd = measureEnd,
                        wandFlash = wandFlash,
                        pickerActive = pickerActive,
                        pickerScreenPos = pickerScreenPos,
                        pickerInitialColor = pickerInitialColor,
                        pickerCurrentColor = pickerCurrentColor,
                        liveSelectionPath = liveSelectionPath,
                        cursorScreenPos = cursorScreenPos,
                        isCursorHovering = isCursorHovering,
                        isCursorTouching = isCursorTouching,
                        polyPoints = polyPoints,
                        onPolyPoint = onPolyPoint,
                        cropRect = cropRect,
                        onCropRect = onCropRect,
                        fillTolerance = fillTolerance,
                        gradientType = gradientType,
                        liquifyStrength = liquifyStrength,
                        liquifyMode = liquifyMode,
                        onTransform = onTransform,
                        onTextRequested = onTextRequested,
                    )
                },
    ) {
        CanvasOverlay(
            imageBitmap = imageBitmap,
            vm = vm,
            zoom = zoom,
            rotation = rotation,
            panX = panX,
            panY = panY,
            fitScale = fitScale,
            tool = tool,
            tfState = tfState,
            polyPoints = polyPoints,
            cropRect = cropRect,
            liveShapeStart = liveShapeStart,
            liveShapeEnd = liveShapeEnd,
            measureStart = measureStart,
            measureEnd = measureEnd,
            pickerActive = pickerActive,
            pickerScreenPos = pickerScreenPos,
            pickerInitialColor = pickerInitialColor,
            pickerCurrentColor = pickerCurrentColor,
            cursorScreenPos = cursorScreenPos,
            isCursorHovering = isCursorHovering,
            isCursorTouching = isCursorTouching,
            livePressure = livePressure,
            wandFlash = wandFlash,
            liveSelectionPath = liveSelectionPath,
            checkerboardPaint = checkerboardPaint,
        )
    }
}

internal fun angleDegrees(
    a: Offset,
    b: Offset,
): Float = Math.toDegrees(atan2((b.y - a.y).toDouble(), (b.x - a.x).toDouble())).toFloat()

internal fun normalizeAngle(value: Float): Float {
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
