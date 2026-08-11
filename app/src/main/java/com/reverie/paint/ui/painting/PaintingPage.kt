package com.reverie.paint.ui.painting

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.model.Tool
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
    var brushPanelOpen by remember { mutableStateOf(false) }
    var layerPanelOpen by remember { mutableStateOf(false) }
    var settingsPanelOpen by remember { mutableStateOf(false) }
    var colorPanelOpen by remember { mutableStateOf(false) }

    // Currently selected tool
    var tool by remember { mutableStateOf(Tool.BRUSH) }

    Box(Modifier.fillMaxSize().background(Morandi.bg)) {
        // ---- Canvas (full bleed, behind rails) ----
        CanvasView(
            vm = vm,
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(top = 56.dp, start = 60.dp)
                    .onSizeChanged {
                        canvasW = it.width
                        canvasH = it.height
                    },
            zoom = zoom,
            rotation = rotation,
            panX = panX,
            panY = panY,
            fitScale = fitScale,
            onFitScale = { fitScale = it },
            onTransform = { z, r, px, py ->
                zoom = z
                rotation = r
                panX = px
                panY = py
            },
            tool = tool,
        )

        // ---- Top bar ----
        TopBar(
            vm = vm,
            onBack = { vm.goHome() },
            onRotateCw = { rotation = (rotation + 90) % 360 },
            onRotateCcw = { rotation = (rotation - 90 + 360) % 360 },
            onZoomIn = { zoom = (zoom * 1.2f).coerceAtMost(16f) },
            onZoomOut = { zoom = (zoom / 1.2f).coerceAtLeast(0.1f) },
            onLayers = { layerPanelOpen = true },
            onSettings = { settingsPanelOpen = true },
        )

        // ---- Left tool rail ----
        ToolRail(
            tool = tool,
            onTool = {
                tool = it
                vm.applyTool(it.id)
            },
            brushSize = vm.brushSize,
            onBrushSize = { vm.updateBrushSize(it) },
            opacity = vm.brushOpacity,
            onOpacity = { vm.updateBrushOpacity(it) },
            brushColor = vm.brushColor,
            onOpenBrush = { brushPanelOpen = true },
            onOpenColor = { colorPanelOpen = true },
        )

        // ---- Popup panels (topmost) ----
        if (brushPanelOpen) {
            BrushPanel(
                vm = vm,
                onClose = { brushPanelOpen = false },
                modifier = Modifier.fillMaxSize().zIndex(10f),
            )
        }
        if (layerPanelOpen) {
            LayerPanel(
                vm = vm,
                onClose = { layerPanelOpen = false },
                modifier = Modifier.fillMaxSize().zIndex(10f),
            )
        }
        if (settingsPanelOpen) {
            SettingsPanel(
                vm = vm,
                onClose = { settingsPanelOpen = false },
                modifier = Modifier.fillMaxSize().zIndex(10f),
            )
        }
        if (colorPanelOpen) {
            ColorPanel(
                vm = vm,
                onClose = { colorPanelOpen = false },
                modifier = Modifier.fillMaxSize().zIndex(10f),
            )
        }
    }
}

/** Coordinate conversion: widget -> document space (accounts for pan/zoom/rotation). */
fun widgetToImage(
    p: Offset,
    canvasW: Int,
    canvasH: Int,
    panX: Float,
    panY: Float,
    zoom: Float,
    fitScale: Float,
    docW: Int,
    docH: Int,
): Offset {
    val scale = zoom * fitScale
    // widget center is the image center (before rotation)
    val dx = p.x - panX - canvasW / 2f
    val dy = p.y - panY - canvasH / 2f
    return Offset(dx / scale + docW / 2f, dy / scale + docH / 2f)
}
