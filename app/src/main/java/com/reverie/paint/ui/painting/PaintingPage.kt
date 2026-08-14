package com.reverie.paint.ui.painting

import androidx.compose.foundation.Image
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import com.reverie.paint.ui.components.ReSlider
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import com.reverie.paint.ui.components.noRippleClickable
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.ui.res.painterResource
import com.reverie.paint.R
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.model.Tool
import com.reverie.paint.model.ToolGroup
import com.reverie.paint.ui.theme.Morandi
import kotlin.math.min

/**
 * Painting page: full-bleed canvas with touch painting + gestures,
 * overlaid by the top bar, left tool rail and popup panels.
 *
 * 画世界 Pro style: left tool rail with vertical sliders, top operation
 * bar, dark grid workspace with a centered white canvas.
 */
@Composable
fun PaintingPage(vm: PaintViewModel) {
    var zoom by remember { mutableFloatStateOf(1f) }
    var rotation by remember { mutableFloatStateOf(0f) }
    var panX by remember { mutableFloatStateOf(0f) }
    var panY by remember { mutableFloatStateOf(0f) }
    var fitScale by remember { mutableFloatStateOf(1f) }

    var canvasW by remember { mutableStateOf(1) }
    var canvasH by remember { mutableStateOf(1) }

    // Popup panels
    var showIndicator by remember { mutableStateOf(false) }
    var indicatorTick by remember { mutableStateOf(0) }

    fun flashIndicator() {
        showIndicator = true
        indicatorTick++
    }

    // Auto-hide the transform indicator 1.2s after the last flash
    LaunchedEffect(indicatorTick) {
        if (indicatorTick > 0) {
            kotlinx.coroutines.delay(1200)
            showIndicator = false
        }
    }

    var textDialogPos by remember { mutableStateOf<Pair<Float, Float>?>(null) }
    var brushPanelOpen by remember { mutableStateOf(false) }
    var layerPanelOpen by remember { mutableStateOf(false) }
    var settingsPanelOpen by remember { mutableStateOf(false) }
    var colorPanelOpen by remember { mutableStateOf(false) }

    // Currently selected tool
    var tool by remember { mutableStateOf(Tool.BRUSH) }
    var moreToolsOpen by remember { mutableStateOf(false) }
    var selectionMenuOpen by remember { mutableStateOf(false) }
    var selectionPanelOpen by remember { mutableStateOf(false) }
    var selectionPropsOpen by remember { mutableStateOf(false) }
    var selectionPanelOffsetX by remember { mutableFloatStateOf(0f) }
    var selectionPanelOffsetY by remember { mutableFloatStateOf(0f) }
    val selectionTools =
        listOf(
            Tool.SELECT_RECT,
            Tool.SELECT_ELLIPSE,
            Tool.SELECT_POLYGON,
            Tool.SELECT_MAGNETIC,
            Tool.LASSO,
            Tool.MAGICWAND,
            Tool.SELECT_SIMILAR,
        )

    val tfState = remember { TransformState() }
    var cropRect by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    var gradientType by remember { mutableStateOf(0) }
    var fillTolerance by remember { mutableStateOf(24) }
    var liquifyStrength by remember { mutableStateOf(0.9f) }
    var liquifyMode by remember { mutableStateOf(0) }
    var liquifyBrushSize by remember { mutableStateOf(60f) }
    // Point-click shape tools share the canvas vertex list
    var polyPoints by remember { mutableStateOf<List<Offset>>(emptyList()) }
    // Clear transient tool state when switching tools, and activate tool states
    androidx.compose.runtime.LaunchedEffect(tool) {
        if (tool == Tool.TRANSFORM || tool == Tool.MOVE) {
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
        } else {
            if (tfState.active) {
                // Real-time auto-commit on tool switch
                when (tfState.mode) {
                    TransformMode.PERSPECTIVE -> {
                        val corners = tfState.quadCorners
                        if (corners.size == 4) {
                            vm.applyPerspectiveTransform(
                                corners[0].x.toDouble(), corners[0].y.toDouble(),
                                corners[1].x.toDouble(), corners[1].y.toDouble(),
                                corners[2].x.toDouble(), corners[2].y.toDouble(),
                                corners[3].x.toDouble(), corners[3].y.toDouble(),
                                tfState.bounds.left.toDouble(), tfState.bounds.top.toDouble(),
                                tfState.bounds.width.toDouble(), tfState.bounds.height.toDouble(),
                            )
                        }
                    }
                    TransformMode.DISTORT -> {
                        vm.applyWarpMeshTransform(
                            tfState.origMeshPoints,
                            tfState.meshPoints,
                            tfState.bounds.left.toDouble(), tfState.bounds.top.toDouble(),
                            tfState.bounds.width.toDouble(), tfState.bounds.height.toDouble(),
                        )
                    }
                    else -> {
                        val rad = Math.toRadians(tfState.rotation.toDouble())
                        val c = tfState.bounds.center
                        if (tfState.rotation != 0f || tfState.scaleX != 1f || tfState.scaleY != 1f || tfState.tx != 0f || tfState.ty != 0f) {
                            vm.applyTransform(
                                tfState.scaleX.toDouble(),
                                tfState.scaleY.toDouble(),
                                0.0,
                                0.0,
                                rad,
                                tfState.tx.toDouble(),
                                tfState.ty.toDouble(),
                                c.x.toDouble(),
                                c.y.toDouble(),
                            )
                        } else {
                            vm.cancelTransformPreview()
                        }
                    }
                }
            }
            tfState.active = false
        }
        if (tool != Tool.CROP) cropRect = null
        if (tool != Tool.POLYGON && tool != Tool.POLYLINE && tool != Tool.PATH && tool != Tool.SELECT_POLYGON) {
            polyPoints = emptyList()
        }
    }
    var shapeStrokeWidth by remember { mutableStateOf(4f) }
    var shapeFilled by remember { mutableStateOf(false) }
    val shapeTools =
        listOf(
            Tool.LINE, Tool.RECT, Tool.ELLIPSE, Tool.POLYGON, Tool.POLYLINE,
            Tool.SELECT_POLYGON, Tool.PATH,
        )

    Box(Modifier.fillMaxSize().background(Morandi.canvasBg)) {
        // ---- Canvas workspace
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Transparent)
        ) {
            CanvasView(
                vm = vm,
                modifier =
                    Modifier
                        .fillMaxSize()
                        .onSizeChanged {
                            canvasW = it.width
                            canvasH = it.height
                        },
                zoom = zoom,
                rotation = rotation,
                panX = panX,
                panY = panY,
                fitScale = fitScale,
                onFitScale = {
                    if (it != fitScale) {
                        android.util.Log.d("ReveriePaint", "fitScale $fitScale -> $it")
                    }
                    fitScale = it
                },
                onTransform = { z, r, px, py ->
                    if (z != zoom) {
                        android.util.Log.d("ReveriePaint", "zoom $zoom -> $z")
                    }
                    zoom = z
                    rotation = r
                    panX = px
                    panY = py
                    flashIndicator()
                },
                onTextRequested = { x, y -> textDialogPos = x to y },
                tool = tool,
                tfState = tfState,
                polyPoints = polyPoints,
                onPolyPoint = { polyPoints = polyPoints + it },
                cropRect = cropRect,
                onCropRect = { cropRect = it },
                fillTolerance = fillTolerance,
                gradientType = gradientType,
                liquifyStrength = liquifyStrength,
                liquifyMode = liquifyMode,
            )
        }

        // ---- Top bar ----
        TopBar(
            modifier = Modifier.align(Alignment.TopEnd),
            vm = vm,
            opacity = vm.uiOpacity,
            onBack = { vm.goHome() },
            onRotateCw = {
                rotation = (rotation + 90) % 360
                flashIndicator()
            },
            onRotateCcw = {
                rotation = (rotation - 90 + 360) % 360
                flashIndicator()
            },
            onZoomIn = {
                zoom = (zoom * 1.2f).coerceAtMost(16f)
                flashIndicator()
            },
            onZoomOut = {
                zoom = (zoom / 1.2f).coerceAtLeast(0.1f)
                flashIndicator()
            },
            onLayers = {
                layerPanelOpen = true
                brushPanelOpen = false
                settingsPanelOpen = false
                colorPanelOpen = false
                moreToolsOpen = false
            },
            onSettings = {
                settingsPanelOpen = true
                layerPanelOpen = false
                brushPanelOpen = false
                colorPanelOpen = false
                moreToolsOpen = false
            },
        )

        // ---- Selection operations menu (全选 / 反选 / 清除选区) ----
        if (selectionMenuOpen) {
            androidx.compose.ui.window.Popup(
                alignment = Alignment.TopEnd,
                offset = androidx.compose.ui.unit.IntOffset(0, 180),
            ) {
                Box(
                    modifier =
                        Modifier
                            .pointerHoverIcon(PointerIcon.Default)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Morandi.panel)
                            .border(
                                1.dp,
                                Morandi.border,
                                RoundedCornerShape(10.dp),
                            ),
                ) {
                    Column {
                        SelectionMenuItem("选中图层") { vm.selectAllAction() }
                        SelectionMenuItem("反选") { vm.invertSelectionAction() }
                        SelectionMenuItem("清除选区", danger = true) { vm.clearSelectionAction() }
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(Morandi.border)
                        )
                        SelectionMenuItem("关闭") { selectionMenuOpen = false }
                    }
                }
            }
        }

        // ---- Left tool rail ----
        ToolRail(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(top = 48.dp) // Gap from top bar
                .fillMaxHeight(),
            vm = vm,
            opacity = vm.uiOpacity.toDouble(),
            tool = tool,
            onTool = {
                tool = it
                vm.applyTool(it.id)
                // Auto-open the floating selection panel for selection tools
                if (it in selectionTools) {
                    selectionPanelOpen = true
                }
                moreToolsOpen = false
            },
            moreToolsOpen = moreToolsOpen,
            onToggleMoreTools = {
                brushPanelOpen = false
                colorPanelOpen = false
                layerPanelOpen = false
                settingsPanelOpen = false
                moreToolsOpen = !moreToolsOpen
            },
            brushSize = vm.brushSize,
            onBrushSize = { vm.updateBrushSize(it) },
            popupOpacity = vm.popupPanelOpacity,
            brushOpacity = vm.brushOpacity,
            onOpacity = { vm.updateBrushOpacity(it) },
            brushColor = vm.brushColor,
            onOpenBrush = {
                brushPanelOpen = true
                colorPanelOpen = false
                layerPanelOpen = false
                settingsPanelOpen = false
                moreToolsOpen = false
            },
            onOpenColor = {
                colorPanelOpen = true
                brushPanelOpen = false
                layerPanelOpen = false
                settingsPanelOpen = false
                moreToolsOpen = false
            },
        )

        // ---- Transform tool options panel ----
        androidx.compose.animation.AnimatedVisibility(
            visible = tool == Tool.TRANSFORM && tfState.active,
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 24.dp),
            enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(200)),
            exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(200)),
        ) {
            TransformPanel(
                vm = vm,
                tfState = tfState,
                onReset = {
                    vm.cancelTransformPreview()
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
                    }
                    vm.startTransformPreview()
                },
            )
        }

        // ---- Shape tools options panel (Krita tool-options style) ----
        androidx.compose.animation.AnimatedVisibility(
            visible = tool in shapeTools,
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 24.dp),
            enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(200)),
            exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(200)),
        ) {
            ShapeToolPanel(
                vm = vm,
                tool = tool,
                vertexCount = polyPoints.size,
                strokeWidth = shapeStrokeWidth,
                filled = shapeFilled,
                onStrokeWidth = { shapeStrokeWidth = it; vm.setShapeStrokeWidth(it.toDouble()) },
                onFilled = { shapeFilled = it; vm.setShapeFilled(it) },
                onFinish = {
                    if (polyPoints.isNotEmpty()) {
                        val pts = polyPoints.map { it.x.toInt() to it.y.toInt() }
                        when (tool) {
                            Tool.POLYGON -> vm.drawPolygon(pts, closed = true)
                            Tool.POLYLINE -> vm.drawPolygon(pts, closed = false)
                            Tool.SELECT_POLYGON -> vm.selectPolygon(pts)
                            Tool.PATH -> {
                                // Bézier path: smooth through the anchors with
                                // a Catmull-Rom spline, commit as a selection
                                // (Krita's path tool can convert to a selection)
                                val smooth = smoothPathPoints(pts)
                                if (smooth.size >= 3) vm.selectPolygon(smooth)
                            }
                            else -> Unit
                        }
                        polyPoints = emptyList()
                    }
                },
                onCancel = { polyPoints = emptyList() },
            )
        }

        // ---- Crop tool options panel ----
        androidx.compose.animation.AnimatedVisibility(
            visible = tool == Tool.CROP && cropRect != null,
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 24.dp),
            enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(200)),
            exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(200)),
        ) {
            cropRect?.let { cr ->
                CropPanel(
                    rect = cr,
                    onApply = {
                        vm.cropCanvas(
                            cr.left.toInt(),
                            cr.top.toInt(),
                            maxOf(1, cr.width.toInt()),
                            maxOf(1, cr.height.toInt()),
                        )
                        cropRect = null
                    },
                    onCancel = { cropRect = null },
                )
            }
        }

        // ---- Gradient / Fill / Liquify tool options ----
        androidx.compose.animation.AnimatedVisibility(
            visible = tool == Tool.GRADIENT,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp),
            enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(200)),
            exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(200)),
        ) {
            GradientPanel(vm = vm, type = gradientType, onType = { gradientType = it })
        }
        androidx.compose.animation.AnimatedVisibility(
            visible = tool == Tool.FILL,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp),
            enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(200)),
            exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(200)),
        ) {
            FillPanel(vm = vm, tolerance = fillTolerance, onTolerance = { fillTolerance = it })
        }
        androidx.compose.animation.AnimatedVisibility(
            visible = tool == Tool.LIQUIFY,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp),
            enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(200)),
            exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(200)),
        ) {
            LiquifyPanel(
                vm = vm,
                strength = liquifyStrength,
                onStrength = { liquifyStrength = it },
                mode = liquifyMode,
                onMode = { liquifyMode = it },
                brushSize = liquifyBrushSize,
                onBrushSize = {
                    liquifyBrushSize = it
                    vm.setLiquifyBrushSize(it.toDouble())
                },
            )
        }

        // ---- Floating selection panel (Krita tool-options style) ----
        // Context-sensitive: shown while a selection tool is active, sliding
        // in from the canvas edge; draggable so it never blocks the work
        androidx.compose.animation.AnimatedVisibility(
            visible = tool in selectionTools,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .offset { IntOffset(selectionPanelOffsetX.roundToInt(), selectionPanelOffsetY.roundToInt()) }
                .padding(bottom = 24.dp),
            enter =
                androidx.compose.animation.fadeIn(
                    androidx.compose.animation.core.tween(200),
                ) +
                    androidx.compose.animation.slideInVertically(
                        androidx.compose.animation.core.tween(200),
                        initialOffsetY = { it },
                    ),
            exit =
                androidx.compose.animation.fadeOut(
                    androidx.compose.animation.core.tween(200),
                ) +
                    androidx.compose.animation.slideOutVertically(
                        androidx.compose.animation.core.tween(200),
                        targetOffsetY = { it },
                    ),
        ) {
            SelectionFloatPanel(
                vm = vm,
                tool = tool,
                propsOpen = selectionPropsOpen,
                onToggleProps = { selectionPropsOpen = !selectionPropsOpen },
                onDrag = { dx, dy ->
                    selectionPanelOffsetX += dx
                    selectionPanelOffsetY += dy
                }
            )
        }

        // ---- Floating Color Picker layer-source bar (PaintWorld style) ----
        androidx.compose.animation.AnimatedVisibility(
            visible = tool == Tool.PICKER,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 24.dp),
            enter =
                androidx.compose.animation.fadeIn(
                    androidx.compose.animation.core.tween(200)
                ) +
                    androidx.compose.animation.slideInVertically(
                        androidx.compose.animation.core.tween(200),
                        initialOffsetY = { it / 2 }
                    ),
            exit =
                androidx.compose.animation.fadeOut(
                    androidx.compose.animation.core.tween(200)
                ) +
                    androidx.compose.animation.slideOutVertically(
                        androidx.compose.animation.core.tween(200),
                        targetOffsetY = { it / 2 }
                    )
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(Morandi.panel.copy(alpha = 0.94f))
                    .border(1.dp, Morandi.border, RoundedCornerShape(14.dp))
                    .padding(6.dp)
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val currentSelected = vm.pickerCurrentLayerOnly
                    // Current Layer button
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (currentSelected) Morandi.accent.copy(alpha = 0.22f) else Color.Transparent)
                            .clickable { vm.pickerCurrentLayerOnly = true }
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                painter = painterResource(R.drawable.ic_layerstack),
                                contentDescription = "当前图层",
                                tint = if (currentSelected) Morandi.accent else Morandi.icon,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.height(3.dp))
                            Text(
                                "当前图层",
                                fontSize = 11.sp,
                                color = if (currentSelected) Morandi.accent else Morandi.subText,
                                fontWeight = if (currentSelected) androidx.compose.ui.text.font.FontWeight.SemiBold else androidx.compose.ui.text.font.FontWeight.Normal
                            )
                        }
                    }

                    // All Layers button
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (!currentSelected) Morandi.accent.copy(alpha = 0.22f) else Color.Transparent)
                            .clickable { vm.pickerCurrentLayerOnly = false }
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                painter = painterResource(R.drawable.ic_layers),
                                contentDescription = "全部图层",
                                tint = if (!currentSelected) Morandi.accent else Morandi.icon,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.height(3.dp))
                            Text(
                                "全部图层",
                                fontSize = 11.sp,
                                color = if (!currentSelected) Morandi.accent else Morandi.subText,
                                fontWeight = if (!currentSelected) androidx.compose.ui.text.font.FontWeight.SemiBold else androidx.compose.ui.text.font.FontWeight.Normal
                            )
                        }
                    }
                }
            }
        }

        // ---- Transform indicator (top-center, animated pill) ----
        androidx.compose.animation.AnimatedVisibility(
            visible = showIndicator,
            enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.spring(stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow)) +
                    androidx.compose.animation.slideInVertically(androidx.compose.animation.core.spring(stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow)) { -it },
            exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(250)) +
                    androidx.compose.animation.slideOutVertically(androidx.compose.animation.core.tween(250)) { -it },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 56.dp)
                .zIndex(20f)
        ) {
            val zoomPct = (zoom * fitScale * 100).toInt()
            val rotDeg = ((rotation % 360 + 360) % 360).toInt()
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(Morandi.panelHi.copy(alpha = 0.94f))
                    .border(1.dp, Morandi.border, RoundedCornerShape(12.dp))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "缩放 ${zoomPct}%",
                        color = Morandi.text,
                        fontSize = 12.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                    )
                    Box(
                        Modifier
                            .size(3.dp)
                            .background(Morandi.border, CircleShape)
                    )
                    Text(
                        "旋转 ${rotDeg}°",
                        color = Morandi.text,
                        fontSize = 12.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                    )
                }
            }
        }

        // ---- Popup panels (topmost) ----
        AnimatedVisibility(
            visible = brushPanelOpen,
            enter = fadeIn(tween(300, easing = FastOutSlowInEasing)) + slideInVertically(tween(300, easing = FastOutSlowInEasing)) { 40 },
            exit = fadeOut(tween(200)) + slideOutVertically(tween(200)) { 40 },
            modifier = Modifier.fillMaxSize().zIndex(10f)
        ) {
            BrushPanel(
                vm = vm,
                onClose = { brushPanelOpen = false },
                opacity = vm.popupPanelOpacity,
            )
        }
        AnimatedVisibility(
            visible = layerPanelOpen,
            enter = fadeIn(tween(300, easing = FastOutSlowInEasing)) + slideInVertically(tween(300, easing = FastOutSlowInEasing)) { -40 },
            exit = fadeOut(tween(200)) + slideOutVertically(tween(200)) { -40 },
            modifier = Modifier.fillMaxSize().zIndex(10f)
        ) {
            LayerPanel(
                vm = vm,
                onClose = { layerPanelOpen = false },
                opacity = vm.popupPanelOpacity,
            )
        }
        AnimatedVisibility(
            visible = settingsPanelOpen,
            enter = fadeIn(tween(300, easing = FastOutSlowInEasing)) + slideInVertically(tween(300, easing = FastOutSlowInEasing)) { -40 },
            exit = fadeOut(tween(200)) + slideOutVertically(tween(200)) { -40 },
            modifier = Modifier.fillMaxSize().zIndex(10f)
        ) {
            SettingsPanel(
                vm = vm,
                onClose = { settingsPanelOpen = false },
                opacity = vm.popupPanelOpacity,
            )
        }
        AnimatedVisibility(
            visible = colorPanelOpen,
            enter = fadeIn(tween(300, easing = FastOutSlowInEasing)) + slideInVertically(tween(300, easing = FastOutSlowInEasing)) { 40 },
            exit = fadeOut(tween(200)) + slideOutVertically(tween(200)) { 40 },
            modifier = Modifier.fillMaxSize().zIndex(10f)
        ) {
            ColorPanel(
                vm = vm,
                onClose = { colorPanelOpen = false },
                opacity = vm.popupPanelOpacity,
            )
        }
        AnimatedVisibility(
            visible = moreToolsOpen,
            enter = fadeIn(tween(250, easing = FastOutSlowInEasing)) + slideInHorizontally(tween(250, easing = FastOutSlowInEasing)) { -40 },
            exit = fadeOut(tween(180)) + slideOutHorizontally(tween(180)) { -40 },
            modifier = Modifier.fillMaxSize().zIndex(10f)
        ) {
            AllToolsPanel(
                vm = vm,
                tool = tool,
                onTool = {
                    tool = it
                    vm.applyTool(it.id)
                    if (it in selectionTools) {
                        selectionPanelOpen = true
                    }
                    moreToolsOpen = false
                },
                onOpenBrush = {
                    brushPanelOpen = true
                    moreToolsOpen = false
                },
                onClose = { moreToolsOpen = false },
                opacity = vm.popupPanelOpacity,
            )
        }

        // Text tool input dialog
        textDialogPos?.let { (tx, ty) ->
            TextInputDialog(
                onConfirm = { txt, fontSize ->
                    if (txt.isNotBlank()) {
                        vm.drawText(tx, ty, txt, fontSize)
                    }
                    textDialogPos = null
                },
                onDismiss = { textDialogPos = null },
            )
        }
    }
}

/** Text input dialog for the text tool (MVP). */
@Composable
fun TextInputDialog(
    onConfirm: (String, Double) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    var fontSize by remember { mutableStateOf(48f) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("输入文字", color = Morandi.text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                androidx.compose.material3.OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    placeholder = { Text("在这里输入...", color = Morandi.subText) },
                    colors =
                        androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Morandi.accent,
                            unfocusedBorderColor = Morandi.border,
                            focusedContainerColor = Morandi.panel,
                            unfocusedContainerColor = Morandi.panel,
                            cursorColor = Morandi.accent,
                        ),
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("字号", color = Morandi.text, fontSize = 12.sp, modifier = Modifier.width(40.dp))
                    ReSlider(
                        value = ((fontSize - 8f) / 192f).coerceIn(0f, 1f),
                        onValue = { frac -> fontSize = 8f + frac * 192f },
                        modifier = Modifier.weight(1f),
                    )
                    Text("${fontSize.roundToInt()}", color = Morandi.text, fontSize = 12.sp, modifier = Modifier.width(36.dp))
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = { onConfirm(text, fontSize.toDouble()) }) {
                Text("确定", color = Morandi.accentHi)
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text("取消", color = Morandi.subText)
            }
        },
        containerColor = Morandi.panelHi,
    )
}

@Composable
private fun SelectionMenuItem(
    label: String,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .noRippleClickable {
                    onClick()
                }
                .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            color = if (danger) Color(0xFFB05552) else Morandi.text,
            fontSize = 13.sp,
        )
    }
}

// Floating selection panel docked at the bottom of the screen, in the style
// of Krita's tool options docker. Each selection tool exposes its own
// property set: the magic wand / similar-color tools get a tolerance slider
// plus the common feather/expand/contract/smooth modifiers, while simple
// lasso-style tools only get the common modifiers (like Krita, which has no
// tolerance on the lasso). No close button: switching tools hides it.
@Composable
private fun SelectionFloatPanel(
    modifier: Modifier = Modifier,
    vm: PaintViewModel,
    tool: Tool,
    propsOpen: Boolean,
    onToggleProps: () -> Unit,
    onDrag: (Float, Float) -> Unit,
) {
    ToolFloatPanel(modifier = modifier) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Row 1: Top Segmented Mode Selector (新建, 增加, 减去, 相交)
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF1B1E24))
                    .padding(2.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val modes = listOf(
                    0 to "新建",
                    1 to "增加",
                    2 to "减去",
                    3 to "相交",
                )
                modes.forEach { (modeVal, modeName) ->
                    val isSel = vm.selectionMode == modeVal
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isSel) Morandi.accent else Color.Transparent)
                            .pointerInput(modeVal) {
                                detectTapGestures { vm.updateSelectionMode(modeVal) }
                            }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = modeName,
                            fontSize = 12.sp,
                            fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                            color = if (isSel) Morandi.onAccent else Morandi.subText,
                        )
                    }
                }
            }

            // Row 2: Magic Wand / Similar Tolerance Slider
            if (tool == Tool.MAGICWAND || tool == Tool.SELECT_SIMILAR) {
                Box(modifier = Modifier.width(220.dp)) {
                    ToolFloatSlider(
                        label = "容差",
                        valueText = "${vm.selectionTolerance}",
                        range = 0f..255f,
                        value = vm.selectionTolerance.toFloat(),
                        onValue = { vm.updateSelectionTolerance(it.toInt()) },
                    )
                }
            }

            // Expandable Modifiers Drawer (羽化, 扩展, 收缩, 平滑)
            androidx.compose.animation.AnimatedVisibility(visible = propsOpen) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.width(220.dp).padding(vertical = 4.dp),
                ) {
                    ToolFloatSlider(label = "羽化", valueText = "8px", range = 0f..32f, value = 8f, onValue = { vm.featherSelection(it.toInt()) })
                    ToolFloatSlider(label = "扩展", valueText = "16px", range = 0f..64f, value = 16f, onValue = { vm.expandSelection(it.toInt()) })
                    ToolFloatSlider(label = "收缩", valueText = "8px", range = 0f..64f, value = 8f, onValue = { vm.contractSelection(it.toInt()) })
                    ToolFloatSlider(label = "平滑", valueText = "4px", range = 1f..16f, value = 4f, onValue = { vm.smoothSelection(it.toInt()) })
                }
            }

            // Row 3: Action Buttons (全选, 反选, 清除, 属性)
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SelectionActionItem(
                    iconRes = R.drawable.ic_layers,
                    label = "全选",
                    onClick = { vm.selectAllAction() },
                )
                SelectionActionItem(
                    iconRes = R.drawable.ic_refresh,
                    label = "反选",
                    onClick = { vm.invertSelectionAction() },
                )
                SelectionActionItem(
                    iconRes = R.drawable.ic_trash,
                    label = "清除",
                    danger = true,
                    onClick = { vm.clearSelectionAction() },
                )
                SelectionActionItem(
                    iconRes = R.drawable.ic_sliders,
                    label = if (propsOpen) "收起" else "属性",
                    active = propsOpen,
                    onClick = { onToggleProps() },
                )
            }
        }
    }
}

@Composable
private fun SelectionActionItem(
    iconRes: Int,
    label: String,
    primary: Boolean = false,
    danger: Boolean = false,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (pressed) 0.90f else 1f, spring(dampingRatio = 0.6f, stiffness = 500f), label = "btn_scale")

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
        modifier = Modifier
            .scale(scale)
            .clip(RoundedCornerShape(10.dp))
            .background(
                when {
                    primary -> Morandi.accent
                    danger -> Color(0x33C45656)
                    active -> Morandi.accent.copy(alpha = 0.25f)
                    else -> Color(0xFF262A33).copy(alpha = 0.7f)
                }
            )
            .border(
                1.dp,
                when {
                    primary -> Morandi.accent
                    danger -> Color(0x66C45656)
                    active -> Morandi.accent
                    else -> Color(0xFF383D48)
                },
                RoundedCornerShape(10.dp)
            )
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        tryAwaitRelease()
                        pressed = false
                    },
                    onTap = { onClick() }
                )
            }
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        androidx.compose.material3.Icon(
            painter = androidx.compose.ui.res.painterResource(id = iconRes),
            contentDescription = label,
            tint = when {
                primary -> Morandi.onAccent
                danger -> Color(0xFFF28B82)
                active -> Morandi.accent
                else -> Morandi.text
            },
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = label,
            fontSize = 10.sp,
            fontWeight = if (primary || active) FontWeight.Bold else FontWeight.Normal,
            color = when {
                primary -> Morandi.onAccent
                danger -> Color(0xFFF28B82)
                active -> Morandi.accent
                else -> Morandi.subText
            },
        )
    }
}


/** Catmull-Rom spline through the anchor points - Krita's path tool draws
 * Bézier curves through the clicked anchors; this produces an equivalent
 * smooth curve used to commit a path selection */
private fun smoothPathPoints(points: List<Pair<Int, Int>>): List<Pair<Int, Int>> {
    if (points.size < 3) return points
    val result = mutableListOf<Pair<Int, Int>>()
    for (i in 0 until points.size - 1) {
        val p0 = points[maxOf(0, i - 1)]
        val p1 = points[i]
        val p2 = points[i + 1]
        val p3 = points[minOf(points.size - 1, i + 2)]
        for (step in 0 until 16) {
            val u = step / 16f
            val u2 = u * u
            val u3 = u2 * u
            val x = 0.5f * (
                (2 * p1.first) +
                    (-p0.first + p2.first) * u +
                    (2 * p0.first - 5 * p1.first + 4 * p2.first - p3.first) * u2 +
                    (-p0.first + 3 * p1.first - 3 * p2.first + p3.first) * u3
                )
            val y = 0.5f * (
                (2 * p1.second) +
                    (-p0.second + p2.second) * u +
                    (2 * p0.second - 5 * p1.second + 4 * p2.second - p3.second) * u2 +
                    (-p0.second + 3 * p1.second - 3 * p2.second + p3.second) * u3
                )
            result += x.toInt() to y.toInt()
        }
    }
    result += points.last()
    return result.distinct()
}
