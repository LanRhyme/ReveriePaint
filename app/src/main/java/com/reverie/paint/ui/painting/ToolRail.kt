package com.reverie.paint.ui.painting

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import kotlin.math.roundToInt
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
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
import androidx.compose.ui.window.Popup
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import kotlinx.coroutines.delay
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import com.reverie.paint.R
import com.reverie.paint.model.Tool
import com.reverie.paint.model.ToolGroup
import com.reverie.paint.ui.components.ReIconButton
import com.reverie.paint.ui.components.ReVerticalSlider
import com.reverie.paint.ui.theme.Morandi
import com.reverie.paint.ui.theme.parseColor
import com.reverie.paint.core.*
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeChild
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint

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
    onBrushSize: (Double) -> Unit,
    opacity: Double,
    popupOpacity: Float = 1f,
    brushOpacity: Double,
    onOpacity: (Double) -> Unit,
    brushColor: String,
    onOpenBrush: () -> Unit,
    onOpenColor: () -> Unit,
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

    val upperShape = RoundedCornerShape(topEnd = 16.dp, bottomEnd = 16.dp)
    val lowerShape = RoundedCornerShape(topEnd = 16.dp)

    Box(modifier = modifier.pointerHoverIcon(PointerIcon.Default).fillMaxHeight().width(36.dp)) {
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
                    .then(
                        if (vm.blurBackground && hazeState != null) {
                            Modifier.hazeChild(
                                state = hazeState,
                                style = HazeStyle(
                                    backgroundColor = Morandi.panel.copy(alpha = opacity.toFloat().coerceIn(0.05f, 0.98f)),
                                    tint = HazeTint(Morandi.panel.copy(alpha = opacity.toFloat().coerceIn(0.05f, 0.98f))),
                                    blurRadius = 24.dp,
                                    noiseFactor = 0.05f
                                )
                            )
                        } else {
                            Modifier.background(Morandi.panel.copy(alpha = opacity.toFloat()))
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
                                tooltipTool = t
                                if (t in listOf(Tool.BRUSH, Tool.ERASER, Tool.SMUDGE) && tool == t) {
                                    onOpenBrush()
                                } else {
                                    onTool(t)
                                }
                            },
                            selected = tool == t,
                        )
                        if (tooltipTool == t) {
                            Popup(alignment = Alignment.CenterEnd, offset = IntOffset(110, 0)) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(Morandi.panelHi)
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Text(t.label, color = Morandi.text, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
                
                // More tools button
                val isMoreToolsActive = moreToolsOpen || tool in moreTools
                val moreToolsTint by androidx.compose.animation.animateColorAsState(if (isMoreToolsActive) Morandi.accent else Morandi.icon, androidx.compose.animation.core.tween(200))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(32.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onToggleMoreTools() },
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
                    .then(
                        if (vm.blurBackground && hazeState != null) {
                            Modifier.hazeChild(
                                state = hazeState,
                                style = HazeStyle(
                                    backgroundColor = Morandi.panel.copy(alpha = opacity.toFloat().coerceIn(0.05f, 0.98f)),
                                    tint = HazeTint(Morandi.panel.copy(alpha = opacity.toFloat().coerceIn(0.05f, 0.98f))),
                                    blurRadius = 24.dp,
                                    noiseFactor = 0.05f
                                )
                            )
                        } else {
                            Modifier.background(Morandi.panel.copy(alpha = opacity.toFloat()))
                        }
                    )
                    .padding(top = 4.dp, bottom = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(32.dp)
                        .clickable(onClick = onOpenColor),
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
                    brushSize = brushSize,
                    onBrushSize = onBrushSize,
                )
                Spacer(Modifier.height(8.dp))
                if (vm.quickSliderMode == 1) {
                    FlowGroup(
                        vm = vm,
                        flow = vm.brushFlow,
                        onFlow = { vm.updateBrushFlow(it) },
                    )
                } else {
                    OpacityGroup(
                        vm = vm,
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
        Tool.MEASURE -> R.drawable.ic_reference
        Tool.TRANSFORM -> R.drawable.ic_move
        Tool.SELECT_MAGNETIC -> R.drawable.ic_lock
        Tool.SELECT_SIMILAR -> R.drawable.ic_eye
        Tool.PATH -> R.drawable.ic_copy
    }

/** Vertical brush-size control: value + logarithmic slider, like Krita's
 * top bar. The slider is logarithmic (1..500), so small sizes get fine
 * resolution and large sizes coarse resolution - exactly Krita's
 * KisLogarithmicSliderSpinBox mapping: value = 500^fraction.
 */
@Composable
private fun BrushSizeGroup(
    vm: PaintViewModel,
    brushSize: Double,
    onBrushSize: (Double) -> Unit,
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
        fraction = (kotlin.math.ln(brushSize.coerceAtLeast(1.0)) / kotlin.math.ln(500.0)).toFloat().coerceIn(0f, 1f),
        onFraction = { onBrushSize(kotlin.math.exp(kotlin.math.ln(500.0) * it)) },
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
            onBrushSize(newSize)
        },
        presets = vm.brushSizePresets,
        onSelectPreset = { onBrushSize(it) },
        onSavePreset = { idx -> vm.saveSizePreset(brushSize, idx) },
        onDeletePreset = { idx -> vm.removeSizePreset(idx) },
        formatPreset = { p ->
            if (p < 10.0) String.format(java.util.Locale.US, "%.1f", p) else "${p.toInt()}"
        }
    )
}

/** Vertical opacity control: value + slider, 0..1 linear. */
@Composable
private fun OpacityGroup(
    vm: PaintViewModel,
    opacity: Double,
    onOpacity: (Double) -> Unit,
) {
    val pct = opacity * 100.0
    val formattedValue = if (pct < 10.0) {
        String.format(java.util.Locale.US, "%.1f%%", pct)
    } else {
        "${pct.roundToInt()}%"
    }

    ReVerticalSlider(
        label = "O",
        fraction = opacity.toFloat(),
        onFraction = { onOpacity(it.toDouble()) },
        trackWidth = 26,
        trackHeight = 175,
        valueText = formattedValue,
        onStep = { increase ->
            val step = 0.01
            val newOpacity = if (increase) (opacity + step).coerceAtMost(1.0) else (opacity - step).coerceAtLeast(0.01)
            onOpacity(newOpacity)
        },
        presets = vm.brushOpacityPresets,
        onSelectPreset = { onOpacity(it) },
        onSavePreset = { idx -> vm.saveOpacityPreset(opacity, idx) },
        onDeletePreset = { idx -> vm.removeOpacityPreset(idx) },
        formatPreset = { p -> "${(p * 100).roundToInt()}%" }
    )
}

/** Vertical flow control: value + slider, 0..1 linear. */
@Composable
private fun FlowGroup(
    vm: PaintViewModel,
    flow: Double,
    onFlow: (Double) -> Unit,
) {
    val pct = flow * 100.0
    val formattedValue = if (pct < 10.0) {
        String.format(java.util.Locale.US, "%.1f%%", pct)
    } else {
        "${pct.roundToInt()}%"
    }

    ReVerticalSlider(
        label = "F",
        fraction = flow.toFloat(),
        onFraction = { onFlow(it.toDouble()) },
        trackWidth = 26,
        trackHeight = 175,
        valueText = formattedValue,
        onStep = { increase ->
            val step = 0.01
            val newFlow = if (increase) (flow + step).coerceAtMost(1.0) else (flow - step).coerceAtLeast(0.01)
            onFlow(newFlow)
        },
        presets = vm.brushFlowPresets,
        onSelectPreset = { onFlow(it) },
        onSavePreset = { idx -> vm.saveFlowPreset(flow, idx) },
        onDeletePreset = { idx -> vm.removeFlowPreset(idx) },
        formatPreset = { p -> "${(p * 100).roundToInt()}%" }
    )
}
