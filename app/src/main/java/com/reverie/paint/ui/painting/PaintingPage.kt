package com.reverie.paint.ui.painting

import androidx.compose.foundation.Image
import androidx.compose.animation.AnimatedVisibility
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
    // Point-click shape tools share the canvas vertex list
    var polyPoints by remember { mutableStateOf<List<Offset>>(emptyList()) }
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
                            .clip(RoundedCornerShape(10.dp))
                            .background(Morandi.panelHi)
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
                    .padding(bottom = 90.dp),
            enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(200)),
            exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(200)),
        ) {
            TransformPanel(
                vm = vm,
                rotationDeg = tfState.rotation,
                scaleX = tfState.scaleX,
                scaleY = tfState.scaleY,
                onRotation = { tfState.rotation = it },
                onScaleX = { tfState.scaleX = it },
                onScaleY = { tfState.scaleY = it },
                onApply = {
                    val rad = Math.toRadians(tfState.rotation.toDouble())
                    vm.applyTransform(
                        tfState.scaleX.toDouble(),
                        tfState.scaleY.toDouble(),
                        0.0,
                        0.0,
                        rad,
                        tfState.tx.toDouble(),
                        tfState.ty.toDouble(),
                    )
                    tfState.active = false
                },
                onCancel = { tfState.active = false },
            )
        }

        // ---- Shape tools options panel (Krita tool-options style) ----
        androidx.compose.animation.AnimatedVisibility(
            visible = tool in shapeTools,
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 90.dp),
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
                    .padding(bottom = 90.dp),
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

        // ---- Transform indicator (bottom-center, 画世界 Pro style) ----
        if (showIndicator) {
            val zoomPct = (zoom * fitScale * 100).toInt()
            val rotDeg = ((rotation % 360 + 360) % 360).toInt()
            Box(
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 24.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Morandi.panelHi.copy(alpha = 0.92f))
                        .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Text(
                    "缩放 $zoomPct%  旋转 $rotDeg°",
                    color = Morandi.text,
                    fontSize = 12.sp,
                )
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
                onConfirm = { txt ->
                    if (txt.isNotBlank()) {
                        vm.drawText(tx, ty, txt, 48.0)
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
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("输入文字", color = Morandi.text) },
        text = {
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
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = { onConfirm(text) }) {
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
    val modes =
        listOf(
            0 to "替换",
            1 to "加",
            2 to "减",
            3 to "交",
        )
    Column(
        modifier =
            modifier
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        onDrag(dragAmount.x, dragAmount.y)
                    }
                }
                .padding(horizontal = 12.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Morandi.panelHi.copy(alpha = 0.85f))
                .border(1.dp, Morandi.border, RoundedCornerShape(14.dp))
                .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Row 1: merge mode (all selection tools)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            modes.forEach { (mode, label) ->
                val selected = vm.selectionMode == mode
                Box(
                    modifier =
                        Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (selected) Morandi.accent else Morandi.panel)
                            .noRippleClickable { vm.updateSelectionMode(mode) }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text(
                        label,
                        color = if (selected) Morandi.onAccent else Morandi.text,
                        fontSize = 13.sp,
                    )
                }
            }
        }
        // Row 2: tool-specific properties
        if (tool == Tool.MAGICWAND || tool == Tool.SELECT_SIMILAR) {
            // Tolerance slider (magic wand / similar color only, like Krita)
            SelectionPropSlider(
                "容差",
                range = 0..255,
                value = vm.selectionTolerance,
                onApply = { vm.updateSelectionTolerance(it) },
            )
        }
        // Common modifiers: feather / expand / contract / smooth
        if (propsOpen) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SelectionPropSlider("羽化", 0..32, 8) { vm.featherSelection(it) }
                SelectionPropSlider("扩展", 0..64, 16) { vm.expandSelection(it) }
                SelectionPropSlider("收缩", 0..64, 8) { vm.contractSelection(it) }
                SelectionPropSlider("平滑", 1..16, 4) { vm.smoothSelection(it) }
            }
        }
        // Row 3: quick actions
        Row(
            modifier = Modifier.padding(top = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SelectionActionChip("选中图层") { vm.selectAllAction() }
            SelectionActionChip("反选") { vm.invertSelectionAction() }
            SelectionActionChip("清除", danger = true) { vm.clearSelectionAction() }
            SelectionActionChip(if (propsOpen) "收起属性" else "高级属性") { onToggleProps() }
        }
    }
}

@Composable
private fun SelectionActionChip(
    label: String,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(if (danger) Color(0x33B05552) else Morandi.panel)
                .noRippleClickable { onClick() }
                .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            label,
            color = if (danger) Color(0xFFB05552) else Morandi.text,
            fontSize = 13.sp,
        )
    }
}

@Composable
private fun SelectionPropSlider(
    label: String,
    range: IntRange,
    initial: Int = range.last / 2,
    value: Int? = null,
    onApply: (Int) -> Unit,
) {
    var local by remember { mutableStateOf(initial) }
    val current = value ?: local
    Row(
        modifier = Modifier.padding(top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(label, color = Morandi.subText, fontSize = 11.sp)
        ReSlider(
            value = (current - range.first).toFloat() / (range.last - range.first),
            onValue = {
                val v = range.first + (it * (range.last - range.first)).toInt()
                if (value == null) local = v
                onApply(v)
            },
            modifier = Modifier.width(140.dp),
        )
        Text("$current", color = Morandi.text, fontSize = 11.sp)
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
