package com.reverie.paint.ui.painting

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.reverie.paint.R
import com.reverie.paint.core.*
import com.reverie.paint.model.Tool
import com.reverie.paint.model.ToolGroup
import com.reverie.paint.ui.components.ReSlider
import com.reverie.paint.ui.components.noRippleClickable
import com.reverie.paint.ui.theme.Morandi
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.haze
import dev.chrisbanes.haze.hazeChild
import kotlin.math.min
import kotlin.math.roundToInt

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

    // Auto-hide action toast 1.0s after trigger
    LaunchedEffect(vm.actionToastRevision) {
        if (vm.actionToastRevision > 0L && vm.actionToastMessage != null) {
            kotlinx.coroutines.delay(1000)
            vm.clearActionToast()
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
        } else {
            if (tfState.active) {
                // Real-time auto-commit on tool switch
                when (tfState.mode) {
                    TransformMode.PERSPECTIVE -> {
                        val corners = tfState.quadCorners
                        if (corners.size == 4) {
                            vm.applyPerspectiveTransform(
                                corners[0].x.toDouble(),
                                corners[0].y.toDouble(),
                                corners[1].x.toDouble(),
                                corners[1].y.toDouble(),
                                corners[2].x.toDouble(),
                                corners[2].y.toDouble(),
                                corners[3].x.toDouble(),
                                corners[3].y.toDouble(),
                                tfState.bounds.left.toDouble(),
                                tfState.bounds.top.toDouble(),
                                tfState.bounds.width.toDouble(),
                                tfState.bounds.height.toDouble(),
                            )
                        }
                    }

                    TransformMode.DISTORT -> {
                        vm.applyWarpMeshTransform(
                            tfState.origMeshPoints,
                            tfState.meshPoints,
                            tfState.bounds.left.toDouble(),
                            tfState.bounds.top.toDouble(),
                            tfState.bounds.width.toDouble(),
                            tfState.bounds.height.toDouble(),
                        )
                    }

                    else -> {
                        val rad = Math.toRadians(tfState.rotation.toDouble())
                        val c = tfState.bounds.center
                        if (tfState.rotation != 0f || tfState.scaleX != 1f || tfState.scaleY != 1f || tfState.tx != 0f ||
                            tfState.ty != 0f
                        ) {
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
            Tool.LINE,
            Tool.RECT,
            Tool.ELLIPSE,
            Tool.POLYGON,
            Tool.POLYLINE,
            Tool.SELECT_POLYGON,
            Tool.PATH,
        )

    val hazeState = remember { HazeState() }

    Box(Modifier.fillMaxSize().background(Morandi.canvasBg)) {
        // ---- Canvas workspace
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(Color.Transparent)
                    .then(
                        if (vm.blurBackground) Modifier.haze(hazeState) else Modifier,
                    ),
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

        var showExitSaveDialog by remember { mutableStateOf(false) }
        var showDiscardConfirmDialog by remember { mutableStateOf(false) }

        val requestExit: () -> Unit = {
            if (vm.hasUnsavedChanges()) {
                showExitSaveDialog = true
            } else {
                vm.goHome()
            }
        }

        // Primary Exit Save Confirmation Dialog
        if (showExitSaveDialog) {
            val exitContext = androidx.compose.ui.platform.LocalContext.current
            ExitSaveDialog(
                vm = vm,
                onDiscard = {
                    showExitSaveDialog = false
                    showDiscardConfirmDialog = true
                },
                onSaveAndExit = {
                    showExitSaveDialog = false
                    vm.saveProject(vm.docName) {
                        android.widget.Toast
                            .makeText(exitContext, "工程已保存", android.widget.Toast.LENGTH_SHORT)
                            .show()
                        vm.goHome()
                    }
                },
                onDismiss = { showExitSaveDialog = false },
            )
        }

        if (showDiscardConfirmDialog) {
            DiscardConfirmDialog(
                onDiscard = {
                    showDiscardConfirmDialog = false
                    vm.goHome()
                },
                onDismiss = { showDiscardConfirmDialog = false },
            )
        }

        // BackHandler for Android system back button/gesture: close active panels first, then request exit
        androidx.activity.compose.BackHandler {
            when {
                showDiscardConfirmDialog -> showDiscardConfirmDialog = false
                showExitSaveDialog -> showExitSaveDialog = false
                brushPanelOpen -> brushPanelOpen = false
                layerPanelOpen -> layerPanelOpen = false
                colorPanelOpen -> colorPanelOpen = false
                settingsPanelOpen -> settingsPanelOpen = false
                moreToolsOpen -> moreToolsOpen = false
                selectionMenuOpen -> selectionMenuOpen = false
                selectionPanelOpen -> selectionPanelOpen = false
                selectionPropsOpen -> selectionPropsOpen = false
                tool != Tool.BRUSH -> tool = Tool.BRUSH
                else -> requestExit()
            }
        }

        // ---- Top bar ----
        TopBar(
            modifier = Modifier.align(Alignment.TopEnd),
            vm = vm,
            opacity = vm.uiOpacity,
            hazeState = hazeState,
            onBack = requestExit,
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
                offset =
                    androidx.compose.ui.unit
                        .IntOffset(0, 180),
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
                                .background(Morandi.border),
                        )
                        SelectionMenuItem("关闭") { selectionMenuOpen = false }
                    }
                }
            }
        }

        // ---- Left tool rail ----
        ToolRail(
            modifier =
                Modifier
                    .align(Alignment.TopStart)
                    .padding(top = 48.dp) // Gap from top bar
                    .fillMaxHeight(),
            vm = vm,
            hazeState = hazeState,
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
            enter =
                androidx.compose.animation.fadeIn(
                    androidx.compose.animation.core
                        .tween(200),
                ),
            exit =
                androidx.compose.animation.fadeOut(
                    androidx.compose.animation.core
                        .tween(200),
                ),
        ) {
            TransformPanel(
                vm = vm,
                tfState = tfState,
                hazeState = hazeState,
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
                            ),
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
            enter =
                androidx.compose.animation.fadeIn(
                    androidx.compose.animation.core
                        .tween(200),
                ),
            exit =
                androidx.compose.animation.fadeOut(
                    androidx.compose.animation.core
                        .tween(200),
                ),
        ) {
            ShapeToolPanel(
                vm = vm,
                tool = tool,
                vertexCount = polyPoints.size,
                strokeWidth = shapeStrokeWidth,
                filled = shapeFilled,
                hazeState = hazeState,
                onStrokeWidth = {
                    shapeStrokeWidth = it
                    vm.setShapeStrokeWidth(it.toDouble())
                },
                onFilled = {
                    shapeFilled = it
                    vm.setShapeFilled(it)
                },
                onFinish = {
                    if (polyPoints.isNotEmpty()) {
                        val pts = polyPoints.map { it.x.toInt() to it.y.toInt() }
                        when (tool) {
                            Tool.POLYGON -> {
                                vm.drawPolygon(pts, closed = true)
                            }

                            Tool.POLYLINE -> {
                                vm.drawPolygon(pts, closed = false)
                            }

                            Tool.SELECT_POLYGON -> {
                                vm.selectPolygon(pts)
                            }

                            Tool.PATH -> {
                                // Bézier path: smooth through the anchors with
                                // a Catmull-Rom spline, commit as a selection
                                // (Krita's path tool can convert to a selection)
                                val smooth = smoothPathPoints(pts)
                                if (smooth.size >= 3) vm.selectPolygon(smooth)
                            }

                            else -> {
                                Unit
                            }
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
            enter =
                androidx.compose.animation.fadeIn(
                    androidx.compose.animation.core
                        .tween(200),
                ),
            exit =
                androidx.compose.animation.fadeOut(
                    androidx.compose.animation.core
                        .tween(200),
                ),
        ) {
            cropRect?.let { cr ->
                CropPanel(
                    rect = cr,
                    vm = vm,
                    hazeState = hazeState,
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
            enter =
                androidx.compose.animation.fadeIn(
                    androidx.compose.animation.core
                        .tween(200),
                ),
            exit =
                androidx.compose.animation.fadeOut(
                    androidx.compose.animation.core
                        .tween(200),
                ),
        ) {
            GradientPanel(vm = vm, type = gradientType, onType = { gradientType = it }, hazeState = hazeState)
        }
        androidx.compose.animation.AnimatedVisibility(
            visible = tool == Tool.FILL,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp),
            enter =
                androidx.compose.animation.fadeIn(
                    androidx.compose.animation.core
                        .tween(200),
                ),
            exit =
                androidx.compose.animation.fadeOut(
                    androidx.compose.animation.core
                        .tween(200),
                ),
        ) {
            FillPanel(vm = vm, tolerance = fillTolerance, onTolerance = { fillTolerance = it }, hazeState = hazeState)
        }
        androidx.compose.animation.AnimatedVisibility(
            visible = tool == Tool.LIQUIFY,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp),
            enter =
                androidx.compose.animation.fadeIn(
                    androidx.compose.animation.core
                        .tween(200),
                ),
            exit =
                androidx.compose.animation.fadeOut(
                    androidx.compose.animation.core
                        .tween(200),
                ),
        ) {
            LiquifyPanel(
                vm = vm,
                strength = liquifyStrength,
                onStrength = { liquifyStrength = it },
                mode = liquifyMode,
                onMode = { liquifyMode = it },
                brushSize = liquifyBrushSize,
                hazeState = hazeState,
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
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .offset { IntOffset(selectionPanelOffsetX.roundToInt(), selectionPanelOffsetY.roundToInt()) }
                    .padding(bottom = 24.dp),
            enter =
                androidx.compose.animation.fadeIn(
                    androidx.compose.animation.core
                        .tween(200),
                ) +
                    androidx.compose.animation.slideInVertically(
                        androidx.compose.animation.core
                            .tween(200),
                        initialOffsetY = { it },
                    ),
            exit =
                androidx.compose.animation.fadeOut(
                    androidx.compose.animation.core
                        .tween(200),
                ) +
                    androidx.compose.animation.slideOutVertically(
                        androidx.compose.animation.core
                            .tween(200),
                        targetOffsetY = { it },
                    ),
        ) {
            SelectionFloatPanel(
                vm = vm,
                tool = tool,
                propsOpen = selectionPropsOpen,
                hazeState = hazeState,
                onToggleProps = { selectionPropsOpen = !selectionPropsOpen },
                onDrag = { dx, dy ->
                    selectionPanelOffsetX += dx
                    selectionPanelOffsetY += dy
                },
            )
        }

        // ---- Floating Color Picker layer-source bar (PaintWorld style) ----
        PickerLayerSourceBar(
            tool = tool,
            vm = vm,
            hazeState = hazeState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
        // ---- Action Toast (Undo/Redo, top-center, animated pill) ----
        androidx.compose.animation.AnimatedVisibility(
            visible = vm.actionToastMessage != null,
            enter =
                androidx.compose.animation.fadeIn(
                    androidx.compose.animation.core
                        .spring(stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow),
                ) +
                    androidx.compose.animation.slideInVertically(
                        androidx.compose.animation.core
                            .spring(stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow),
                    ) {
                        -it
                    },
            exit =
                androidx.compose.animation.fadeOut(
                    androidx.compose.animation.core
                        .tween(200),
                ) +
                    androidx.compose.animation.slideOutVertically(
                        androidx.compose.animation.core
                            .tween(200),
                    ) { -it },
            modifier =
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 56.dp)
                    .zIndex(25f),
        ) {
            val msg = vm.actionToastMessage ?: ""
            val iconRes = vm.actionToastIcon
            Box(
                modifier =
                    Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(Morandi.panelHi.copy(alpha = 0.94f))
                        .border(1.dp, Morandi.border, RoundedCornerShape(12.dp))
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (iconRes != null) {
                        Icon(
                            painter = painterResource(iconRes),
                            contentDescription = msg,
                            tint = Morandi.text,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    Text(
                        msg,
                        color = Morandi.text,
                        fontSize = 12.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                    )
                }
            }
        }

        // ---- Transform indicator (top-center, animated pill) ----
        androidx.compose.animation.AnimatedVisibility(
            visible = showIndicator && !vm.isFilterAdjustActive && (vm.actionToastMessage == null),
            enter =
                androidx.compose.animation.fadeIn(
                    androidx.compose.animation.core
                        .spring(stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow),
                ) +
                    androidx.compose.animation.slideInVertically(
                        androidx.compose.animation.core
                            .spring(stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow),
                    ) {
                        -it
                    },
            exit =
                androidx.compose.animation.fadeOut(
                    androidx.compose.animation.core
                        .tween(250),
                ) +
                    androidx.compose.animation.slideOutVertically(
                        androidx.compose.animation.core
                            .tween(250),
                    ) { -it },
            modifier =
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 56.dp)
                    .zIndex(20f),
        ) {
            val zoomPct = (zoom * fitScale * 100).toInt()
            val rotDeg = ((rotation % 360 + 360) % 360).toInt()
            Box(
                modifier =
                    Modifier
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
                        "缩放 $zoomPct%",
                        color = Morandi.text,
                        fontSize = 12.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                    )
                    Box(
                        Modifier
                            .size(3.dp)
                            .background(Morandi.border, CircleShape),
                    )
                    Text(
                        "旋转 $rotDeg°",
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
            modifier = Modifier.fillMaxSize().zIndex(10f),
        ) {
            BrushPanel(
                vm = vm,
                onClose = { brushPanelOpen = false },
                opacity = vm.popupPanelOpacity,
                hazeState = hazeState,
            )
        }
        AnimatedVisibility(
            visible = layerPanelOpen,
            enter = fadeIn(tween(300, easing = FastOutSlowInEasing)) + slideInVertically(tween(300, easing = FastOutSlowInEasing)) { -40 },
            exit = fadeOut(tween(200)) + slideOutVertically(tween(200)) { -40 },
            modifier = Modifier.fillMaxSize().zIndex(10f),
        ) {
            LayerPanel(
                vm = vm,
                onClose = { layerPanelOpen = false },
                opacity = vm.popupPanelOpacity,
                hazeState = hazeState,
            )
        }
        AnimatedVisibility(
            visible = settingsPanelOpen,
            enter = fadeIn(tween(300, easing = FastOutSlowInEasing)) + slideInVertically(tween(300, easing = FastOutSlowInEasing)) { -40 },
            exit = fadeOut(tween(200)) + slideOutVertically(tween(200)) { -40 },
            modifier = Modifier.fillMaxSize().zIndex(10f),
        ) {
            SettingsPanel(
                vm = vm,
                onClose = { settingsPanelOpen = false },
                opacity = vm.popupPanelOpacity,
                hazeState = hazeState,
            )
        }
        AnimatedVisibility(
            visible = colorPanelOpen,
            enter = fadeIn(tween(300, easing = FastOutSlowInEasing)) + slideInVertically(tween(300, easing = FastOutSlowInEasing)) { 40 },
            exit = fadeOut(tween(200)) + slideOutVertically(tween(200)) { 40 },
            modifier = Modifier.fillMaxSize().zIndex(10f),
        ) {
            ColorPanel(
                vm = vm,
                onClose = { colorPanelOpen = false },
                opacity = vm.popupPanelOpacity,
                hazeState = hazeState,
            )
        }
        AnimatedVisibility(
            visible = moreToolsOpen,
            enter =
                fadeIn(
                    tween(250, easing = FastOutSlowInEasing),
                ) + slideInHorizontally(tween(250, easing = FastOutSlowInEasing)) { -40 },
            exit = fadeOut(tween(180)) + slideOutHorizontally(tween(180)) { -40 },
            modifier = Modifier.fillMaxSize().zIndex(10f),
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
                hazeState = hazeState,
            )
        }

        // More Settings full-screen overlay (stays inside painting page, back returns to canvas)
        if (vm.moreSettingsOpen) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(Morandi.canvasBg)
                        .zIndex(500f),
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    // Top bar with back button (no bottom navigation bar here)
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .background(Morandi.panel)
                                .padding(horizontal = 4.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = { vm.closeMoreSettings() }) {
                            Icon(
                                painterResource(R.drawable.ic_arrow_left),
                                contentDescription = "返回画布",
                                tint = Morandi.text,
                            )
                        }
                        Text(
                            "更多设置",
                            color = Morandi.text,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Box(modifier = Modifier.weight(1f)) {
                        com.reverie.paint.ui.home.SettingsPageContent(
                            vm = vm,
                            onExit = { vm.closeMoreSettings() },
                        )
                    }
                }
            }
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

        // Blocking Loading & Saving Modal Overlay (prevents any clicks/interactions)
        androidx.compose.animation.AnimatedVisibility(
            visible = vm.isBlockingLoading,
            enter =
                androidx.compose.animation.fadeIn(
                    androidx.compose.animation.core
                        .tween(150),
                ) +
                    androidx.compose.animation.scaleIn(
                        androidx.compose.animation.core
                            .tween(150),
                        initialScale = 0.94f,
                    ),
            exit =
                androidx.compose.animation.fadeOut(
                    androidx.compose.animation.core
                        .tween(150),
                ) +
                    androidx.compose.animation.scaleOut(
                        androidx.compose.animation.core
                            .tween(150),
                        targetScale = 0.94f,
                    ),
            modifier = Modifier.zIndex(999f),
        ) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(
                            androidx.compose.ui.graphics.Color.Black
                                .copy(alpha = 0.45f),
                        ).clickable(
                            interactionSource =
                                remember {
                                    androidx.compose.foundation.interaction
                                        .MutableInteractionSource()
                                },
                            indication = null,
                            onClick = {},
                        ),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier =
                        Modifier
                            .clip(RoundedCornerShape(18.dp))
                            .background(Morandi.panelHi)
                            .border(1.dp, Morandi.border, RoundedCornerShape(18.dp))
                            .padding(horizontal = 28.dp, vertical = 22.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        androidx.compose.material3.CircularProgressIndicator(
                            color = Morandi.accent,
                            strokeWidth = 3.dp,
                            modifier = Modifier.size(36.dp),
                        )
                        Spacer(Modifier.height(14.dp))
                        Text(
                            text = vm.blockingLoadingMessage.ifBlank { "请稍候..." },
                            color = Morandi.text,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
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
            val x =
                0.5f * (
                    (2 * p1.first) +
                        (-p0.first + p2.first) * u +
                        (2 * p0.first - 5 * p1.first + 4 * p2.first - p3.first) * u2 +
                        (-p0.first + 3 * p1.first - 3 * p2.first + p3.first) * u3
                )
            val y =
                0.5f * (
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
