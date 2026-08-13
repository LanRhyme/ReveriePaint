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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
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
