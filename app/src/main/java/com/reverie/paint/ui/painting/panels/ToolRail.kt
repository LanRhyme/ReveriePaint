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
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import kotlinx.coroutines.delay
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.layout.onSizeChanged
import com.reverie.paint.ui.theme.glassBorder
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import com.reverie.paint.R
import com.reverie.paint.model.Tool
import com.reverie.paint.model.ToolGroup
import com.reverie.paint.model.GuideMode
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
    canvasScale: Float = 1f,
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
    val upperShape = if (vm.leftHandMode) {
        RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp)
    } else {
        RoundedCornerShape(topEnd = 16.dp, bottomEnd = 16.dp)
    }
    val lowerShape = if (vm.leftHandMode) {
        RoundedCornerShape(topStart = 16.dp)
    } else {
        RoundedCornerShape(topEnd = 16.dp)
    }

    BoxWithConstraints(modifier = modifier.systemHoverIcon(context).fillMaxHeight().width(36.dp)) {
        val totalHeightDp = maxHeight
        val density = LocalDensity.current
        val totalHeightPx = with(density) { totalHeightDp.toPx() }
        val spacerHeight = if (totalHeightDp < 520.dp) 8.dp else 48.dp

        // 检测上方工具栏是否被底部面板挤压消失 (可用高度不足以容纳两个工具按钮)
        val remainingHeightPx = totalHeightPx - vm.railSliderPanelHeightPx - with(density) { spacerHeight.toPx() }
        LaunchedEffect(remainingHeightPx, vm.toolbarSqueezedWarningDismissed, mainTools.size) {
            if (vm.railSliderPanelHeightPx > 0 && mainTools.isNotEmpty() && !vm.toolbarSqueezedWarningDismissed) {
                if (remainingHeightPx < with(density) { 72.dp.toPx() }) {
                    delay(300)
                    val curRemaining = totalHeightPx - vm.railSliderPanelHeightPx - with(density) { spacerHeight.toPx() }
                    if (curRemaining < with(density) { 72.dp.toPx() } && !vm.toolbarSqueezedWarningDismissed) {
                        vm.showToolbarSqueezedDialog = true
                    }
                }
            }
        }

        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Bottom
        ) {
            // Upper panel
            val upperScrollState = rememberScrollState()
            val canScrollUp = upperScrollState.canScrollBackward
            val canScrollDown = upperScrollState.canScrollForward

            var showArrows by remember { mutableStateOf(false) }
            LaunchedEffect(upperScrollState.value, upperScrollState.maxValue, mainTools.size) {
                if (upperScrollState.maxValue > 0) {
                    showArrows = true
                    delay(2500)
                    showArrows = false
                } else {
                    showArrows = false
                }
            }

            val infiniteTransition = rememberInfiniteTransition(label = "toolRailScrollHint")
            val bounceOffset by infiniteTransition.animateFloat(
                initialValue = -1.8f,
                targetValue = 1.8f,
                animationSpec = infiniteRepeatable(
                    animation = tween(800, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "bounceOffset",
            )
            val arrowAlphaAnim by animateFloatAsState(
                targetValue = if (showArrows) 0.95f else 0f,
                animationSpec = tween(350),
                label = "arrowAlpha",
            )
            val dotAlphaAnim by animateFloatAsState(
                targetValue = if (!showArrows) 0.75f else 0f,
                animationSpec = tween(350),
                label = "dotAlpha",
            )
            val topAlpha by animateFloatAsState(
                targetValue = if (canScrollUp) 1f else 0f,
                animationSpec = tween(200),
                label = "topAlpha",
            )
            val bottomAlpha by animateFloatAsState(
                targetValue = if (canScrollDown) 1f else 0f,
                animationSpec = tween(200),
                label = "bottomAlpha",
            )

            Box(
                modifier = Modifier
                    .width(36.dp)
                    .weight(1f, fill = false)
                    .shadow(10.dp, upperShape, spotColor = Color.Black.copy(alpha = 0.5f))
                    .clip(upperShape)
                    .then(
                        if (vm.blurBackground && hazeState != null) {
                            Modifier.hazeChild(
                                state = hazeState,
                                style = Glass.barStyle(if (opacity >= 0.99) 0.90f else opacity.toFloat()),
                            )
                        } else {
                            Modifier.background(Morandi.panel.copy(alpha = opacity.toFloat()))
                        }
                    ),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .verticalScroll(upperScrollState),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    mainTools.forEach { t ->
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth()) {
                            ReIconButton(
                                toolIcon(t),
                                t.displayName,
                                modifier = Modifier.fillMaxWidth().height(32.dp),
                                onTap = {
                                    if (t == Tool.REFERENCE || t == Tool.SHORTCUT || t == Tool.QUICK_BRUSH || t == Tool.QUICK_COLOR || t == Tool.QUICK_LAYER || t == Tool.SYMMETRY || t == Tool.PERSPECTIVE) {
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
                                selected = when (t) {
                                    Tool.REFERENCE -> vm.referenceWindowOpen
                                    Tool.SHORTCUT -> vm.quickActionWindowOpen
                                    Tool.QUICK_BRUSH -> vm.quickBrushWindowOpen
                                    Tool.QUICK_COLOR -> vm.quickColorWindowOpen
                                    Tool.QUICK_LAYER -> vm.quickLayerWindowOpen
                                    Tool.SYMMETRY -> vm.drawingGuide.mode == GuideMode.SYMMETRY
                                    Tool.PERSPECTIVE -> vm.drawingGuide.mode == GuideMode.PERSPECTIVE
                                    else -> tool == t
                                },
                            )
                            if (tooltipTool == t) {
                                val tooltipOffsetPx = with(LocalDensity.current) { 48.dp.roundToPx() }
                                val popupAlpha = vm.popupPanelOpacity
                                val isLeftHand = vm.leftHandMode
                                Popup(
                                    alignment = if (isLeftHand) Alignment.CenterEnd else Alignment.CenterStart,
                                    offset = IntOffset(if (isLeftHand) -tooltipOffsetPx else tooltipOffsetPx, 0),
                                    properties = androidx.compose.ui.window.PopupProperties(
                                        focusable = false,
                                        dismissOnBackPress = false,
                                        dismissOnClickOutside = false,
                                    ),
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .noRippleClickable {
                                                tooltipTool = null
                                                if (t in listOf(Tool.BRUSH, Tool.ERASER, Tool.SMUDGE) && tool == t) {
                                                    onOpenBrush()
                                                } else {
                                                    onTool(t)
                                                }
                                            }
                                            .shadow(8.dp, RoundedCornerShape(8.dp), spotColor = Color.Black.copy(alpha = 0.25f))
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(Morandi.panel.copy(alpha = popupAlpha))
                                            .glassBorder(RoundedCornerShape(8.dp))
                                            .padding(horizontal = 10.dp, vertical = 5.dp)
                                    ) {
                                        Text(
                                            t.displayName,
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
                            contentDescription = stringResource(R.string.tool_rail_more_tools),
                            tint = moreToolsTint,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }

                // Top scroll hint (gradient + breathing arrow / dot)
                if (topAlpha > 0f) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(20.dp)
                            .align(Alignment.TopCenter)
                            .alpha(topAlpha)
                            .background(
                                Brush.verticalGradient(
                                    listOf(
                                        Morandi.panel.copy(alpha = (opacity * 0.95).toFloat()),
                                        Color.Transparent,
                                    )
                                )
                            ),
                        contentAlignment = Alignment.TopCenter,
                    ) {
                        if (arrowAlphaAnim > 0f) {
                            Icon(
                                painter = painterResource(R.drawable.ic_chevron),
                                contentDescription = null,
                                tint = Morandi.accent,
                                modifier = Modifier
                                    .padding(top = 2.dp)
                                    .size(11.dp)
                                    .offset(y = (-bounceOffset).dp)
                                    .rotate(-90f)
                                    .alpha(arrowAlphaAnim),
                            )
                        }
                        if (dotAlphaAnim > 0f) {
                            Box(
                                modifier = Modifier
                                    .padding(top = 4.dp)
                                    .size(4.dp)
                                    .clip(CircleShape)
                                    .background(Morandi.accent)
                                    .alpha(dotAlphaAnim),
                            )
                        }
                    }
                }

                // Bottom scroll hint (gradient + breathing arrow / dot)
                if (bottomAlpha > 0f) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(20.dp)
                            .align(Alignment.BottomCenter)
                            .alpha(bottomAlpha)
                            .background(
                                Brush.verticalGradient(
                                    listOf(
                                        Color.Transparent,
                                        Morandi.panel.copy(alpha = (opacity * 0.95).toFloat()),
                                    )
                                )
                            ),
                        contentAlignment = Alignment.BottomCenter,
                    ) {
                        if (arrowAlphaAnim > 0f) {
                            Icon(
                                painter = painterResource(R.drawable.ic_chevron),
                                contentDescription = null,
                                tint = Morandi.accent,
                                modifier = Modifier
                                    .padding(bottom = 2.dp)
                                    .size(11.dp)
                                    .offset(y = bounceOffset.dp)
                                    .rotate(90f)
                                    .alpha(arrowAlphaAnim),
                            )
                        }
                        if (dotAlphaAnim > 0f) {
                            Box(
                                modifier = Modifier
                                    .padding(bottom = 4.dp)
                                    .size(4.dp)
                                    .clip(CircleShape)
                                    .background(Morandi.accent)
                                    .alpha(dotAlphaAnim),
                            )
                        }
                    }
                }
            }
            
            Spacer(Modifier.height(spacerHeight))
            
            // Lower panel
            Column(
                modifier = Modifier
                    .width(36.dp)
                    .onSizeChanged { vm.railSliderPanelHeightPx = it.height.toFloat() }
                    .shadow(10.dp, lowerShape, spotColor = Color.Black.copy(alpha = 0.5f))
                    .clip(lowerShape)
                    .then(
                        if (vm.blurBackground && hazeState != null) {
                            Modifier.hazeChild(
                                state = hazeState,
                                style = Glass.barStyle(if (opacity >= 0.99) 0.90f else opacity.toFloat()),
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
                    )
                }
                Spacer(Modifier.height(5.dp))
                // Brush size: Krita top-bar style - always-visible value,
                // step buttons (+/-) that repeat while held, and the slider
                BrushSizeGroup(
                    vm = vm,
                    hazeState = hazeState,
                    brushSize = brushSize,
                    canvasScale = canvasScale,
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
        Tool.SHAPES -> R.drawable.ic_shapes
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
        Tool.CROP -> R.drawable.ic_crop
        Tool.MEASURE -> R.drawable.ic_canvas_resize
        Tool.TRANSFORM -> R.drawable.ic_move
        Tool.SELECT_SIMILAR -> R.drawable.ic_eye
        Tool.PATH -> R.drawable.ic_copy
        Tool.REFERENCE -> R.drawable.ic_reference
        Tool.SHORTCUT -> R.drawable.ic_shortcut
        Tool.QUICK_BRUSH -> R.drawable.ic_brush_quick
        Tool.QUICK_COLOR -> R.drawable.ic_palette
        Tool.QUICK_LAYER -> R.drawable.ic_layers
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
    canvasScale: Float = 1f,
    onBrushSize: (Double, Boolean) -> Unit,
) {
    val formattedValue = if (brushSize < 10.0) {
        String.format(java.util.Locale.US, "%.2f", brushSize)
    } else if (brushSize < 100.0) {
        if (brushSize % 1.0 == 0.0) "${brushSize.toInt()}" else String.format(java.util.Locale.US, "%.1f", brushSize)
    } else {
        "${kotlin.math.round(brushSize).toInt()}"
    }

    val minL = vm.brushMinSizeLimit.coerceAtLeast(0.5)
    val maxL = vm.effectiveBrushMaxSize
    val logMin = kotlin.math.ln(minL)
    val logMax = kotlin.math.ln(maxL)
    val range = (logMax - logMin).coerceAtLeast(1e-6)
    val current = brushSize.coerceIn(minL, maxL)
    val frac = ((kotlin.math.ln(current) - logMin) / range).toFloat().coerceIn(0f, 1f)

    val currentScreenRadiusPx = (brushSize * canvasScale * 0.5).toFloat()

    ReVerticalSlider(
        label = "S",
        title = stringResource(R.string.tool_rail_brush_size),
        iconRes = R.drawable.ic_brush,
        fraction = frac,
        onFraction = { f ->
            val raw = kotlin.math.exp(logMin + f.toDouble() * range).coerceIn(minL, maxL)
            onBrushSize(raw, false)
        },
        onRelease = { f ->
            val raw = kotlin.math.exp(logMin + f.toDouble() * range).coerceIn(minL, maxL)
            onBrushSize(raw, true)
        },
        trackWidth = 26,
        trackHeight = vm.quickSliderHeightDp,
        valueText = formattedValue,
        previewCircleRadiusPx = currentScreenRadiusPx,
        onStep = { increase ->
            val step = when {
                brushSize < 5.0 -> 0.1
                brushSize < 20.0 -> 0.5
                brushSize < 100.0 -> 1.0
                else -> 5.0
            }
            val newSize = if (increase) (brushSize + step).coerceAtMost(maxL) else (brushSize - step).coerceAtLeast(minL)
            onBrushSize(newSize, true)
        },
        quickChips = listOf(2.0, 10.0, 40.0, 120.0, 300.0)
            .filter { it in minL..maxL }
            .map { s ->
                val label = if (s < 10.0) "${s.toInt()}px" else "${s.toInt()}px"
                label to { onBrushSize(s, true) }
            },
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
        title = stringResource(R.string.tool_rail_opacity),
        iconRes = R.drawable.ic_eye,
        fraction = opacity.toFloat(),
        onFraction = { frac -> onOpacity(frac.toDouble(), false) },
        onRelease = { frac -> onOpacity(frac.toDouble(), true) },
        trackWidth = 26,
        trackHeight = vm.quickSliderHeightDp,
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
        title = stringResource(R.string.tool_rail_flow),
        iconRes = R.drawable.ic_gradient,
        fraction = flow.toFloat(),
        onFraction = { frac -> onFlow(frac.toDouble(), false) },
        onRelease = { frac -> onFlow(frac.toDouble(), true) },
        trackWidth = 26,
        trackHeight = vm.quickSliderHeightDp,
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
