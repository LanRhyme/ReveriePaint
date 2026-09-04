/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.panels

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import kotlin.math.roundToInt
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import kotlinx.coroutines.delay
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import com.reverie.paint.R
import com.reverie.paint.model.Tool
import com.reverie.paint.model.ToolGroup
import com.reverie.paint.ui.components.liquidHighlight
import com.reverie.paint.ui.components.liquidLean
import com.reverie.paint.ui.components.pressScale
import com.reverie.paint.ui.components.ReIconButton
import com.reverie.paint.ui.components.ReVerticalSlider
import com.reverie.paint.ui.components.noRippleClickable
import com.reverie.paint.ui.theme.Morandi
import com.reverie.paint.ui.theme.systemHoverIcon
import com.reverie.paint.ui.theme.parseColor
import com.reverie.paint.core.*
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeChild
import com.reverie.paint.ui.theme.Glass

@Composable
fun ToolRail(
    modifier: Modifier = Modifier,
    vm: PaintViewModel,
    hazeState: HazeState? = null,
    tool: Tool,
    onTool: (Tool) -> Unit,
    moreToolsOpen: Boolean = false,
    onToggleMoreTools: () -> Unit = {},
    brushSize: Double,
    onBrushSize: (Double, Boolean) -> Unit,
    opacity: Double,
    popupOpacity: Float = 1f,
    brushOpacity: Double,
    onOpacity: (Double, Boolean) -> Unit,
    brushColor: String,
    onOpenBrush: () -> Unit,
    onOpenColor: () -> Unit,
    onColorDropStart: ((Offset) -> Unit)? = null,
    onColorDropMove: ((Offset) -> Unit)? = null,
    onColorDropEnd: ((Offset) -> Unit)? = null,
    onColorDropCancel: (() -> Unit)? = null,
) {
    val mainTools = vm.pinnedTools
    val moreTools = Tool.entries.filter { it !in mainTools }
    val groupedTools = ToolGroup.entries.map { g -> g to moreTools.filter { it.group == g } }
        .filter { it.second.isNotEmpty() }
        
    var showCustomizeDialog by remember { mutableStateOf(false) }

    var tooltipTool by remember { mutableStateOf<Tool?>(null) }
    LaunchedEffect(tooltipTool) {
        if (tooltipTool != null) {
            delay(1500)
            tooltipTool = null
        }
    }

    val context = androidx.compose.ui.platform.LocalContext.current
    val upperShape = RoundedCornerShape(topEnd = 16.dp, bottomEnd = 16.dp)
    val lowerShape = RoundedCornerShape(topEnd = 16.dp)

    Box(modifier = modifier.systemHoverIcon(context).fillMaxHeight().width(36.dp)) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Bottom
        ) {
            // Upper panel
            Column(
                modifier = Modifier
                    .width(36.dp)
                    .weight(1f, fill = false)
                    .clip(upperShape)
                    .background(Morandi.panel.copy(alpha = opacity.toFloat()))
                    .then(
                        if (vm.blurBackground && hazeState != null) {
                            Modifier.hazeChild(
                                state = hazeState,
                                style = Glass.barStyle(opacity.toFloat()),
                            )
                        } else {
                            Modifier
                        }
                    )
                    .padding(vertical = 4.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                mainTools.forEach { t ->
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth()) {
                        ReIconButton(
                            toolIcon(t),
                            t.label,
                            modifier = Modifier.fillMaxWidth().height(32.dp),
                            onTap = {
                                if (t == Tool.REFERENCE) {
                                    tooltipTool = null
                                    onTool(t)
                                } else if (t in listOf(Tool.BRUSH, Tool.ERASER, Tool.SMUDGE) && tool == t) {
                                    tooltipTool = null
                                    onOpenBrush()
                                } else if (tool == t) {
                                    tooltipTool = null
                                } else {
                                    tooltipTool = t
                                    onTool(t)
                                }
                            },
                            selected = if (t == Tool.REFERENCE) vm.referenceWindowOpen else tool == t,
                        )
                        if (tooltipTool == t) {
                            val tooltipOffsetPx = with(LocalDensity.current) { 48.dp.roundToPx() }
                            val popupAlpha = vm.popupPanelOpacity
                            Popup(
                                alignment = Alignment.CenterStart,
                                offset = IntOffset(tooltipOffsetPx, 0)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .shadow(8.dp, RoundedCornerShape(8.dp), spotColor = Color.Black.copy(alpha = 0.25f))
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Morandi.panel.copy(alpha = popupAlpha))
                                        .border(1.dp, Morandi.border.copy(alpha = popupAlpha), RoundedCornerShape(8.dp))
                                        .padding(horizontal = 10.dp, vertical = 5.dp)
                                ) {
                                    Text(
                                        t.label,
                                        color = Morandi.text,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }
                    }
                }
                
                // More tools button
                val isMoreToolsActive = moreToolsOpen || tool in moreTools
                val moreToolsTint by androidx.compose.animation.animateColorAsState(if (isMoreToolsActive) Morandi.accent else Morandi.icon, androidx.compose.animation.core.tween(200))
                val moreSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(32.dp)
                        .pressScale(moreSource, pressedScale = 0.92f)
                        .liquidLean(moreSource, maxOffset = 4.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .liquidHighlight(moreSource, Color.White, radius = 22.dp)
                        .clickable(interactionSource = moreSource, indication = null) { onToggleMoreTools() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_menu), // More tools icon
                        contentDescription = "更多工具",
                        tint = moreToolsTint,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            
            Spacer(Modifier.height(48.dp))
            
            // Lower panel
            Column(
                modifier = Modifier
                    .width(36.dp)
                    .clip(lowerShape)
                    .background(Morandi.panel.copy(alpha = opacity.toFloat()))
                    .then(
                        if (vm.blurBackground && hazeState != null) {
                            Modifier.hazeChild(
                                state = hazeState,
                                style = Glass.barStyle(opacity.toFloat()),
                            )
                        } else {
                            Modifier
                        }
                    )
                    .padding(top = 4.dp, bottom = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(32.dp)
                        .tapOrDragGesture(
                            onTap = onOpenColor,
                            onDragStart = onColorDropStart,
                            onDragMove = onColorDropMove,
                            onDragEnd = onColorDropEnd,
                            onDragCancel = onColorDropCancel,
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(parseColor(brushColor))
                            .border(1.5.dp, Morandi.border, CircleShape)
                    )
                }
                Spacer(Modifier.height(5.dp))
                // Brush size: Krita top-bar style - always-visible value,
                // step buttons (+/-) that repeat while held, and the slider
                BrushSizeGroup(
                    vm = vm,
                    hazeState = hazeState,
                    brushSize = brushSize,
                    onBrushSize = onBrushSize,
                )
                Spacer(Modifier.height(8.dp))
                if (vm.quickSliderMode == 1) {
                    FlowGroup(
                        vm = vm,
                        hazeState = hazeState,
                        flow = vm.brushFlow,
                        onFlow = { flow, commit -> vm.updateBrushFlow(flow, commit) },
                    )
                } else {
                    OpacityGroup(
                        vm = vm,
                        hazeState = hazeState,
                        opacity = brushOpacity,
                        onOpacity = onOpacity,
                    )
                }
            }
        }
    }
}

@DrawableRes
fun toolIcon(tool: Tool): Int =
    when (tool) {
        Tool.BRUSH -> R.drawable.ic_brush
        Tool.ERASER -> R.drawable.ic_eraser
        Tool.PICKER -> R.drawable.ic_picker
        Tool.FILL -> R.drawable.ic_fill
        Tool.LASSO -> R.drawable.ic_lasso
        Tool.MAGICWAND -> R.drawable.ic_magicwand
        Tool.LINE -> R.drawable.ic_minus
        Tool.RECT -> R.drawable.ic_rect
        Tool.ELLIPSE -> R.drawable.ic_ellipse
        Tool.TEXT -> R.drawable.ic_text
        Tool.SMUDGE -> R.drawable.ic_smudge
        Tool.LIQUIFY -> R.drawable.ic_liquify
        Tool.GRADIENT -> R.drawable.ic_gradient
        Tool.POLYGON -> R.drawable.ic_triangle
        Tool.POLYLINE -> R.drawable.ic_line
        Tool.SELECT_RECT -> R.drawable.ic_select_rect
        Tool.SELECT_ELLIPSE -> R.drawable.ic_circle
        Tool.SELECT_POLYGON -> R.drawable.ic_polyline
        Tool.MOVE -> R.drawable.ic_hand
        Tool.CROP -> R.drawable.ic_crop
        Tool.MEASURE -> R.drawable.ic_canvas_resize
        Tool.TRANSFORM -> R.drawable.ic_move
        Tool.SELECT_SIMILAR -> R.drawable.ic_eye
        Tool.PATH -> R.drawable.ic_copy
        Tool.REFERENCE -> R.drawable.ic_reference
        Tool.SYMMETRY -> R.drawable.ic_flip_horizontal
        Tool.PERSPECTIVE -> R.drawable.ic_grid
    }

/** Vertical brush-size control: value + logarithmic slider, like Krita's
 * top bar. The slider is logarithmic (1..500), so small sizes get fine
 * resolution and large sizes coarse resolution - exactly Krita's
 * KisLogarithmicSliderSpinBox mapping: value = 500^fraction.
 */
@Composable
private fun BrushSizeGroup(
    vm: PaintViewModel,
    hazeState: HazeState? = null,
    brushSize: Double,
    onBrushSize: (Double, Boolean) -> Unit,
) {
    val formattedValue = if (brushSize < 10.0) {
        String.format(java.util.Locale.US, "%.2f", brushSize)
    } else if (brushSize < 100.0) {
        if (brushSize % 1.0 == 0.0) "${brushSize.toInt()}" else String.format(java.util.Locale.US, "%.1f", brushSize)
    } else {
        "${kotlin.math.round(brushSize).toInt()}"
    }

    ReVerticalSlider(
        label = "S",
        title = "笔刷大小",
        iconRes = R.drawable.ic_brush,
        fraction = (kotlin.math.ln(brushSize.coerceAtLeast(1.0)) / kotlin.math.ln(500.0)).toFloat().coerceIn(0f, 1f),
        onFraction = { frac -> onBrushSize(kotlin.math.exp(kotlin.math.ln(500.0) * frac.toDouble()), false) },
        onRelease = { frac -> onBrushSize(kotlin.math.exp(kotlin.math.ln(500.0) * frac.toDouble()), true) },
        trackWidth = 26,
        trackHeight = 175,
        valueText = formattedValue,
        onStep = { increase ->
            val step = when {
                brushSize < 5.0 -> 0.1
                brushSize < 20.0 -> 0.5
                brushSize < 100.0 -> 1.0
                else -> 5.0
            }
            val newSize = if (increase) (brushSize + step).coerceAtMost(500.0) else (brushSize - step).coerceAtLeast(1.0)
            onBrushSize(newSize, true)
        },
        quickChips = listOf(
            "2px" to { onBrushSize(2.0, true) },
            "10px" to { onBrushSize(10.0, true) },
            "40px" to { onBrushSize(40.0, true) },
            "120px" to { onBrushSize(120.0, true) },
        ),
        presets = vm.brushSizePresets,
        onSelectPreset = { onBrushSize(it, true) },
        onSavePreset = { idx -> vm.saveSizePreset(brushSize, idx) },
        onDeletePreset = { idx -> vm.removeSizePreset(idx) },
        formatPreset = { p ->
            if (p < 10.0) String.format(java.util.Locale.US, "%.1f", p) else "${p.toInt()}"
        },
        vm = vm,
        hazeState = hazeState,
    )
}

/** Vertical opacity control: value + slider, 0..1 linear. */
@Composable
private fun OpacityGroup(
    vm: PaintViewModel,
    hazeState: HazeState? = null,
    opacity: Double,
    onOpacity: (Double, Boolean) -> Unit,
) {
    val pct = opacity * 100.0
    val formattedValue = if (pct < 10.0) {
        String.format(java.util.Locale.US, "%.1f%%", pct)
    } else {
        "${pct.roundToInt()}%"
    }

    ReVerticalSlider(
        label = "O",
        title = "不透明度",
        iconRes = R.drawable.ic_eye,
        fraction = opacity.toFloat(),
        onFraction = { frac -> onOpacity(frac.toDouble(), false) },
        onRelease = { frac -> onOpacity(frac.toDouble(), true) },
        trackWidth = 26,
        trackHeight = 175,
        valueText = formattedValue,
        onStep = { increase ->
            val step = 0.01
            val newOpacity = if (increase) (opacity + step).coerceAtMost(1.0) else (opacity - step).coerceAtLeast(0.01)
            onOpacity(newOpacity, true)
        },
        quickChips = listOf(
            "25%" to { onOpacity(0.25, true) },
            "50%" to { onOpacity(0.50, true) },
            "75%" to { onOpacity(0.75, true) },
            "100%" to { onOpacity(1.00, true) },
        ),
        presets = vm.brushOpacityPresets,
        onSelectPreset = { onOpacity(it, true) },
        onSavePreset = { idx -> vm.saveOpacityPreset(opacity, idx) },
        onDeletePreset = { idx -> vm.removeOpacityPreset(idx) },
        formatPreset = { p -> "${(p * 100).roundToInt()}%" },
        vm = vm,
        hazeState = hazeState,
    )
}

/** Vertical flow control: value + slider, 0..1 linear. */
@Composable
private fun FlowGroup(
    vm: PaintViewModel,
    hazeState: HazeState? = null,
    flow: Double,
    onFlow: (Double, Boolean) -> Unit,
) {
    val pct = flow * 100.0
    val formattedValue = if (pct < 10.0) {
        String.format(java.util.Locale.US, "%.1f%%", pct)
    } else {
        "${pct.roundToInt()}%"
    }

    ReVerticalSlider(
        label = "F",
        title = "画笔流量",
        iconRes = R.drawable.ic_gradient,
        fraction = flow.toFloat(),
        onFraction = { frac -> onFlow(frac.toDouble(), false) },
        onRelease = { frac -> onFlow(frac.toDouble(), true) },
        trackWidth = 26,
        trackHeight = 175,
        valueText = formattedValue,
        onStep = { increase ->
            val step = 0.01
            val newFlow = if (increase) (flow + step).coerceAtMost(1.0) else (flow - step).coerceAtLeast(0.01)
            onFlow(newFlow, true)
        },
        quickChips = listOf(
            "25%" to { onFlow(0.25, true) },
            "50%" to { onFlow(0.50, true) },
            "75%" to { onFlow(0.75, true) },
            "100%" to { onFlow(1.00, true) },
        ),
        presets = vm.brushFlowPresets,
        onSelectPreset = { onFlow(it, true) },
        onSavePreset = { idx -> vm.saveFlowPreset(flow, idx) },
        onDeletePreset = { idx -> vm.removeFlowPreset(idx) },
        formatPreset = { p -> "${(p * 100).roundToInt()}%" },
        vm = vm,
        hazeState = hazeState,
    )
}
