package com.reverie.paint.ui.painting

import androidx.compose.foundation.Image
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
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
            onLayers = { layerPanelOpen = true },
            onSettings = { settingsPanelOpen = true },
            onSelection = { selectionMenuOpen = true },
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
                        SelectionMenuItem("全选") { vm.selectAllAction() }
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
                moreToolsOpen = false
            },
            moreToolsOpen = moreToolsOpen,
            onToggleMoreTools = { moreToolsOpen = !moreToolsOpen },
            brushSize = vm.brushSize,
            onBrushSize = { vm.updateBrushSize(it) },
            popupOpacity = vm.popupPanelOpacity,
            brushOpacity = vm.brushOpacity,
            onOpacity = { vm.updateBrushOpacity(it) },
            brushColor = vm.brushColor,
            onOpenBrush = { brushPanelOpen = true },
            onOpenColor = { colorPanelOpen = true },
        )

        // ---- Floating selection panel (Procreate / Krita style) ----
        // Shown when a selection exists or a selection tool is active
        if (selectionPanelOpen || vm.hasSelection) {
            SelectionFloatPanel(
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 100.dp),
                vm = vm,
                propsOpen = selectionPropsOpen,
                onToggleProps = { selectionPropsOpen = !selectionPropsOpen },
            )
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

// Floating selection panel: merge mode, quick actions and advanced props.
// Mirrors Krita's selection tool options and Procreate's selection menu.
@Composable
private fun SelectionFloatPanel(
    modifier: Modifier = Modifier,
    vm: PaintViewModel,
    propsOpen: Boolean,
    onToggleProps: () -> Unit,
) {
    val modes =
        listOf(
            0 to "替换",
            1 to "加",
            2 to "减",
            3 to "相交",
        )
    Column(
        modifier =
            modifier
                .clip(RoundedCornerShape(12.dp))
                .background(Morandi.panelHi.copy(alpha = 0.94f))
                .border(1.dp, Morandi.border, RoundedCornerShape(12.dp))
                .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Row 1: merge mode selector
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            modes.forEach { (mode, label) ->
                val selected = vm.selectionMode == mode
                Box(
                    modifier =
                        Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (selected) Morandi.accent else Morandi.panel)
                            .noRippleClickable { vm.updateSelectionMode(mode) }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Text(
                        label,
                        color = if (selected) Morandi.onAccent else Morandi.text,
                        fontSize = 12.sp,
                    )
                }
            }
        }
        // Row 2: quick actions
        Row(
            modifier = Modifier.padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            SelectionActionChip("全选") { vm.selectAllAction() }
            SelectionActionChip("反选") { vm.invertSelectionAction() }
            SelectionActionChip("清除", danger = true) { vm.clearSelectionAction() }
            SelectionActionChip(if (propsOpen) "收起" else "属性") { onToggleProps() }
        }
        // Row 3: advanced props (feather / expand / contract / smooth)
        if (propsOpen) {
            SelectionPropSlider("羽化", 0..32) { vm.featherSelection(it) }
            SelectionPropSlider("扩展", 0..64) { vm.expandSelection(it) }
            SelectionPropSlider("收缩", 0..64) { vm.contractSelection(it) }
            SelectionPropSlider("平滑", 1..16) { vm.smoothSelection(it) }
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
                .clip(RoundedCornerShape(6.dp))
                .background(if (danger) Color(0x33B05552) else Morandi.panel)
                .noRippleClickable { onClick() }
                .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(
            label,
            color = if (danger) Color(0xFFB05552) else Morandi.text,
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun SelectionPropSlider(
    label: String,
    range: IntRange,
    onApply: (Int) -> Unit,
) {
    var value by remember { mutableStateOf(range.last / 2) }
    Row(
        modifier = Modifier.padding(top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(label, color = Morandi.subText, fontSize = 11.sp)
        ReSlider(
            value = (value - range.first).toFloat() / (range.last - range.first),
            onValue = {
                value = range.first + (it * (range.last - range.first)).toInt()
            },
            modifier = Modifier.width(110.dp),
        )
        Text("${value}px", color = Morandi.text, fontSize = 11.sp)
        SelectionActionChip("应用") { onApply(value) }
    }
}
