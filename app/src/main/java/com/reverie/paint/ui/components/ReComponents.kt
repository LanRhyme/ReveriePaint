/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import kotlin.math.abs
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.offset
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeChild
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.ui.theme.Glass
import com.reverie.paint.ui.theme.Motion
import com.reverie.paint.ui.theme.Theme
import com.reverie.paint.ui.theme.glassBorder
import androidx.compose.foundation.interaction.MutableInteractionSource
import kotlin.math.roundToInt

fun Modifier.noRippleClickable(onClick: () -> Unit): Modifier = composed {
    clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
        onClick()
    }
}

/**
 * Shared component library (画世界 Pro / Procreate style).
 *
 * Rules for every component:
 *  - read ALL colors from Theme.current - never hardcode
 *  - min 44dp touch targets
 *  - reusable: panels/buttons/sliders are built here, pages only compose
 */

// ---------- design tokens ----------
object Dimens {
    val touch = 44.dp
    val radius = 12.dp
    val radiusSm = 9.dp
    val icon = 20.dp
    val iconLg = 24.dp
    val barHeight = 56.dp
}

// ---------- icon button (top bar, rails) ----------
@Composable
fun ReIconButton(
    @DrawableRes icon: Int,
    desc: String,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    size: androidx.compose.ui.unit.Dp = 32.dp,
) {
    val colors = Theme.current
    val interaction = remember { MutableInteractionSource() }
    val tintColor by animateColorAsState(
        if (selected) colors.accent else colors.icon,
        spring(dampingRatio = 0.90f, stiffness = 500f)
    )

    Box(
        modifier = modifier
            .defaultMinSize(minWidth = size, minHeight = size)
            .pressScale(interaction, pressedScale = 0.82f)
            .liquidLean(interaction, maxOffset = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .liquidHighlight(interaction, Color.White, radius = 20.dp)
            .clickable(interactionSource = interaction, indication = null) { onTap() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = desc,
            tint = tintColor,
            modifier = Modifier.size(Dimens.icon),
        )
    }
}

// ---------- primary / secondary text button ----------
@Composable
fun ReButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = true,
) {
    val colors = Theme.current
    val interaction = remember { MutableInteractionSource() }
    val buttonShape = RoundedCornerShape(Dimens.radius)
    Box(
        modifier =
            modifier
                .then(if (primary) Modifier.pressGrow(interaction, growFraction = 0.03f) else Modifier.pressScale(interaction, pressedScale = 0.97f))
                .clip(buttonShape)
                .liquidHighlight(interaction, Color.White, radius = 40.dp)
                .background(if (primary) colors.accent else colors.panelHi)
                .then(if (!primary) Modifier.glassBorder(buttonShape) else Modifier)
                .clickable(interactionSource = interaction, indication = null) { onClick() }
                .padding(horizontal = 18.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = if (primary) colors.onAccent else colors.text,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

// ---------- Fine-tuning & Preset Bookmarks Floating Popup ----------
@Composable
fun SliderFineTunePopup(
    title: String,
    iconRes: Int = com.reverie.paint.R.drawable.ic_brush,
    valueText: String,
    fraction: Float,
    onFraction: (Float) -> Unit,
    onStep: (Boolean) -> Unit,
    quickChips: List<Pair<String, () -> Unit>> = emptyList(),
    presets: List<Double?>,
    onSelectPreset: (Double) -> Unit,
    onSavePreset: (Int) -> Unit,
    onDeletePreset: (Int) -> Unit,
    formatPreset: (Double) -> String,
    vm: PaintViewModel? = null,
    hazeState: HazeState? = null,
    onDismiss: () -> Unit,
) {
    val colors = Theme.current
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val popupAlpha = vm?.popupPanelOpacity ?: 0.94f
    val popupOffsetPx = with(density) { 52.dp.roundToPx() }

    val visibleState = remember { MutableTransitionState(false) }
    LaunchedEffect(Unit) {
        visibleState.targetState = true
    }

    LaunchedEffect(visibleState.currentState, visibleState.targetState) {
        if (!visibleState.currentState && !visibleState.targetState) {
            onDismiss()
        }
    }

    val leftInteraction = remember { MutableInteractionSource() }
    val rightInteraction = remember { MutableInteractionSource() }
    val addInteraction = remember { MutableInteractionSource() }

    Popup(
        alignment = Alignment.CenterStart,
        offset = androidx.compose.ui.unit.IntOffset(popupOffsetPx, 0),
        onDismissRequest = { visibleState.targetState = false },
        properties = androidx.compose.ui.window.PopupProperties(focusable = true),
    ) {
        AnimatedVisibility(
            visibleState = visibleState,
            enter =
                fadeIn(Motion.enterSpring()) +
                    slideInHorizontally(Motion.enterSpring()) { -it / 2 } +
                    scaleIn(initialScale = 0.92f, animationSpec = Motion.enterSpring()),
            exit = fadeOut(tween(160, easing = FastOutLinearInEasing)) +
                   slideOutHorizontally(tween(160, easing = FastOutLinearInEasing)) { -it / 2 } +
                   scaleOut(targetScale = 0.92f, animationSpec = tween(160, easing = FastOutLinearInEasing))
        ) {
            Box(
                modifier = Modifier
                    .width(224.dp)
                    .shadow(20.dp, RoundedCornerShape(18.dp), spotColor = Color.Black.copy(alpha = 0.3f))
                    .clip(RoundedCornerShape(18.dp))
                    .then(
                        if (vm?.blurBackground == true && hazeState != null) {
                            Modifier.hazeChild(
                                state = hazeState,
                                style = Glass.popupStyle(popupAlpha),
                            )
                        } else {
                            Modifier.background(colors.panel.copy(alpha = popupAlpha))
                        }
                    )
                    .glassBorder(RoundedCornerShape(18.dp))
                    .padding(14.dp)
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // Header (Icon + Title + Animated Value Chip)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(
                                painter = painterResource(iconRes),
                                contentDescription = title,
                                tint = colors.accent,
                                modifier = Modifier.size(16.dp),
                            )
                            Text(
                                text = title,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = colors.text,
                            )
                        }

                        // Value Pill
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(colors.panelHi)
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = valueText,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = colors.accent,
                            )
                        }
                    }

                    // Micro Adjustment Stepper + Horizontal Mini Slider
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Left step button (<)
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .pressScale(leftInteraction, pressedScale = 0.90f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(colors.panelHi)
                                .border(0.5.dp, colors.border, RoundedCornerShape(8.dp))
                                .clickable(
                                    interactionSource = leftInteraction,
                                    indication = null
                                ) {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onStep(false)
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                painterResource(com.reverie.paint.R.drawable.ic_chevron),
                                contentDescription = "微调减少",
                                tint = colors.text,
                                modifier = Modifier
                                    .size(14.dp)
                                    .rotate(90f),
                            )
                        }

                        // Horizontal Fine Slider
                        Box(modifier = Modifier.weight(1f)) {
                            ReSlider(
                                value = fraction,
                                onValue = onFraction,
                                height = 16,
                            )
                        }

                        // Right step button (>)
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .pressScale(rightInteraction, pressedScale = 0.90f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(colors.panelHi)
                                .border(0.5.dp, colors.border, RoundedCornerShape(8.dp))
                                .clickable(
                                    interactionSource = rightInteraction,
                                    indication = null
                                ) {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onStep(true)
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                painterResource(com.reverie.paint.R.drawable.ic_chevron),
                                contentDescription = "微调增加",
                                tint = colors.text,
                                modifier = Modifier
                                    .size(14.dp)
                                    .rotate(-90f),
                            )
                        }
                    }

                    // Presets Section Divider
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(0.5.dp)
                            .background(colors.border.copy(alpha = 0.5f))
                    )

                    // Presets Header Row (常用预设 + 存入当前)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "常用预设",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.subText,
                        )

                        Row(
                            modifier = Modifier
                                .pressScale(addInteraction, pressedScale = 0.90f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(colors.panelHi)
                                .clickable(
                                    interactionSource = addInteraction,
                                    indication = null
                                ) {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onSavePreset(-1)
                                }
                                .padding(horizontal = 6.dp, vertical = 2.5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            Icon(
                                painter = painterResource(com.reverie.paint.R.drawable.ic_plus),
                                contentDescription = "保存预设",
                                tint = colors.accent,
                                modifier = Modifier.size(11.dp),
                            )
                            Text(
                                text = "存入当前",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = colors.accent,
                            )
                        }
                    }

                    // 3x3 Presets Grid
                    Column(
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        for (row in 0 until 3) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                for (col in 0 until 3) {
                                    val idx = row * 3 + col
                                    val presetVal = presets.getOrNull(idx)

                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .aspectRatio(1.2f)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(if (presetVal != null) colors.panelHi else colors.panelHi.copy(alpha = 0.35f))
                                            .border(
                                                0.5.dp,
                                                if (presetVal != null) colors.border else colors.border.copy(alpha = 0.35f),
                                                RoundedCornerShape(8.dp)
                                            )
                                            .pointerInput(idx, presetVal) {
                                                detectTapGestures(
                                                    onTap = {
                                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                        if (presetVal != null) {
                                                            onSelectPreset(presetVal)
                                                        } else {
                                                            onSavePreset(idx)
                                                        }
                                                    },
                                                    onLongPress = {
                                                        if (presetVal != null) {
                                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                            onDeletePreset(idx)
                                                        }
                                                    }
                                                )
                                            },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        if (presetVal != null) {
                                            Text(
                                                text = formatPreset(presetVal),
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = colors.text,
                                                maxLines = 1,
                                            )
                                        } else {
                                            Icon(
                                                painter = painterResource(com.reverie.paint.R.drawable.ic_plus),
                                                contentDescription = "添加预设",
                                                tint = colors.subText.copy(alpha = 0.3f),
                                                modifier = Modifier.size(11.dp),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------- vertical capsule slider (brush size / opacity) ----------
@Composable
fun ReVerticalSlider(
    label: String,
    fraction: Float,
    onFraction: (Float) -> Unit,
    modifier: Modifier = Modifier,
    trackWidth: Int = 26,
    trackHeight: Int = 175,
    title: String = "数值微调",
    iconRes: Int = com.reverie.paint.R.drawable.ic_brush,
    valueText: String,
    onStep: ((Boolean) -> Unit)? = null,
    quickChips: List<Pair<String, () -> Unit>> = emptyList(),
    presets: List<Double?> = emptyList(),
    onSelectPreset: ((Double) -> Unit)? = null,
    onSavePreset: ((Int) -> Unit)? = null,
    onDeletePreset: ((Int) -> Unit)? = null,
    formatPreset: (Double) -> String = { "${it.toInt()}" },
    vm: PaintViewModel? = null,
    hazeState: HazeState? = null,
) {
    val colors = Theme.current
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    var localFraction by remember(fraction) { mutableFloatStateOf(fraction) }
    var trackPx by remember { mutableIntStateOf(1) }
    var isDragging by remember { mutableStateOf(false) }
    var showPopup by remember { mutableStateOf(false) }
    var lastHapticStep by remember { mutableIntStateOf((fraction * 100).toInt()) }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier,
    ) {
        // Label above slider
        if (label.isNotEmpty()) {
            Text(
                text = label,
                color = colors.subText,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(4.dp))
        }

        Box(
            modifier = Modifier.height(trackHeight.dp),
            contentAlignment = Alignment.BottomCenter,
        ) {
            // Fine-tune & Preset Popup
            if (showPopup && onStep != null) {
                SliderFineTunePopup(
                    title = title,
                    iconRes = iconRes,
                    valueText = valueText,
                    fraction = localFraction,
                    onFraction = { frac ->
                        localFraction = frac
                        onFraction(frac)
                    },
                    onStep = onStep,
                    quickChips = quickChips,
                    presets = presets,
                    onSelectPreset = { p ->
                        onSelectPreset?.invoke(p)
                    },
                    onSavePreset = { idx ->
                        onSavePreset?.invoke(idx)
                    },
                    onDeletePreset = { idx ->
                        onDeletePreset?.invoke(idx)
                    },
                    formatPreset = formatPreset,
                    vm = vm,
                    hazeState = hazeState,
                    onDismiss = { showPopup = false },
                )
            }

            Box(
                modifier =
                    Modifier
                        .width((trackWidth + 6).dp)
                        .height(trackHeight.dp)
                        .onSizeChanged { trackPx = it.height }
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onTap = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    showPopup = !showPopup
                                }
                            )
                        }
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = {
                                    isDragging = true
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                },
                                onDragEnd = { isDragging = false },
                                onDragCancel = { isDragging = false }
                            ) { change, _ ->
                                val value = 1f - (change.position.y / trackPx.toFloat()).coerceIn(0f, 1f)
                                localFraction = value
                                onFraction(value)
                                val curStep = (value * 50).toInt()
                                if (curStep != lastHapticStep) {
                                    lastHapticStep = curStep
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }
                                change.consume()
                            }
                        },
                contentAlignment = Alignment.BottomCenter,
            ) {
                // Live Tooltip while dragging
                if (isDragging) {
                    val tooltipOffsetPx = with(density) { (trackWidth + 20).dp.roundToPx() }
                    val popupAlpha = vm?.popupPanelOpacity ?: 0.94f
                    Popup(
                        alignment = Alignment.CenterStart,
                        offset = androidx.compose.ui.unit.IntOffset(tooltipOffsetPx, 0)
                    ) {
                        Box(
                            modifier = Modifier
                                .shadow(8.dp, RoundedCornerShape(8.dp), spotColor = Color.Black.copy(alpha = 0.25f))
                                .clip(RoundedCornerShape(8.dp))
                                .background(colors.panel.copy(alpha = popupAlpha))
                                .border(1.dp, colors.border.copy(alpha = popupAlpha), RoundedCornerShape(8.dp))
                                .padding(horizontal = 10.dp, vertical = 5.dp)
                        ) {
                            Text(
                                valueText,
                                color = colors.text,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }

                val capsuleRadius = (trackWidth / 2).dp
                val capsuleGrow by animateFloatAsState(if (isDragging) 1.08f else 1f, Motion.springSnap, label = "capsuleGrow")

                // Track Background & Outlined Border
                Box(
                    modifier = Modifier
                        .width(trackWidth.dp)
                        .fillMaxHeight()
                        .graphicsLayer {
                            scaleX = capsuleGrow
                            scaleY = 1f + (capsuleGrow - 1f) * 0.4f
                            transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 1f)
                        }
                        .clip(RoundedCornerShape(capsuleRadius))
                        .background(colors.panel.copy(alpha = 0.55f))
                        .border(1.5.dp, colors.border, RoundedCornerShape(capsuleRadius))
                ) {
                    // Active progress fill level (FLAT TOP, seamless with indicator bar!)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .fillMaxHeight(localFraction.coerceIn(0f, 1f))
                            .align(Alignment.BottomCenter)
                            .background(colors.accent.copy(alpha = 0.30f))
                    )
                }

                // Dynamic Indicator Line: Spans full width, floats clearly ON TOP without being clipped!
                val indicatorHeight by animateDpAsState(
                    targetValue = if (isDragging) 6.dp else 3.dp,
                    animationSpec = spring(dampingRatio = 0.65f, stiffness = 500f),
                    label = "ind_h"
                )
                val indicatorWidth by animateDpAsState(
                    targetValue = if (isDragging) (trackWidth + 4).dp else trackWidth.dp,
                    animationSpec = spring(dampingRatio = 0.65f, stiffness = 500f),
                    label = "ind_w"
                )
                val indicatorAlpha by animateFloatAsState(
                    targetValue = if (isDragging) 0.85f else 1.0f,
                    animationSpec = spring(dampingRatio = 0.65f, stiffness = 500f),
                    label = "ind_alpha"
                )

                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = (localFraction.coerceIn(0f, 1f) * (trackHeight - 4)).dp)
                        .width(indicatorWidth)
                        .height(indicatorHeight)
                        .shadow(
                            elevation = if (isDragging) 6.dp else 1.dp,
                            shape = RoundedCornerShape(indicatorHeight / 2),
                            spotColor = colors.accent.copy(alpha = 0.5f),
                        )
                        .clip(RoundedCornerShape(indicatorHeight / 2))
                        .background(colors.accent.copy(alpha = indicatorAlpha))
                        .border(0.5.dp, colors.panel, RoundedCornerShape(indicatorHeight / 2))
                )
            }
        }
    }
}

// ---------- horizontal slider (panels) ----------
@Composable
fun ReSlider(
    value: Float,
    onValue: (Float) -> Unit,
    modifier: Modifier = Modifier,
    height: Int = 20,
    onRelease: (() -> Unit)? = null,
) {
    val colors = Theme.current
    var interacting by remember { mutableStateOf(false) }
    val trackScale by animateFloatAsState(if (interacting) 1.14f else 1f, Motion.springSnap, label = "sliderTrackScale")
    val glowAlpha by animateFloatAsState(if (interacting) 0.38f else 0f, Motion.springSnap, label = "sliderGlow")
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .height(height.dp)
                .graphicsLayer { scaleY = trackScale }
                .clip(RoundedCornerShape((height / 2).dp))
                .background(colors.panelHi)
                .pointerInput(Unit) {
                    // 单一手势源：按下即定位，拖动持续跟随（避免 tap/drag 双检测器
                    // 互相取消导致 interacting 提前复位）
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        interacting = true
                        val w0 = size.width.toFloat()
                        if (w0 > 0f) onValue((down.position.x / w0).coerceIn(0f, 1f))
                        var dragged = false
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: break
                            if (!change.pressed) break
                            val dxTotal = abs(change.position.x - down.position.x)
                            if (!dragged && dxTotal > viewConfiguration.touchSlop) dragged = true
                            if (dragged) {
                                change.consume()
                                val w = size.width.toFloat()
                                if (w > 0f) {
                                    onValue((change.position.x / w).coerceIn(0f, 1f))
                                    // 拖动步进触感
                                }
                            }
                        }
                        if (!dragged) onRelease?.invoke()
                        else onRelease?.invoke()
                        interacting = false
                    }
                },
    ) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            val fillW = size.width * value.coerceIn(0f, 1f)
            drawRoundRect(
                color = colors.accent,
                size = androidx.compose.ui.geometry.Size(fillW, size.height),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2f, size.height / 2f),
            )
            // 拖动时填充端的柔光晕（无实心指示点）
            if (glowAlpha > 0.01f && fillW > 1f) {
                val c = androidx.compose.ui.geometry.Offset(fillW, size.height / 2f)
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Color.White.copy(alpha = glowAlpha), Color.Transparent),
                        center = c,
                        radius = size.height * 1.4f,
                    ),
                    radius = size.height * 1.4f,
                    center = c,
                )
            }
        }
    }
}

// ---------- toggle switch ----------
@Composable
fun ReSwitch(
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Theme.current
    val trackColor by animateColorAsState(
        if (checked) colors.accent else colors.panelHi,
        spring(dampingRatio = 0.90f, stiffness = 500f)
    )
    val thumbProgress by animateFloatAsState(if (checked) 1f else 0f, Motion.springSnap, label = "switchThumb")
    Box(
        modifier =
            modifier
                .size(48.dp, 28.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(trackColor)
                .clickable { onChecked(!checked) }
                .padding(3.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier.offset(x = 20.dp * thumbProgress).size(22.dp).clip(CircleShape).background(colors.onAccent),
        )
    }
}

// ---------- color dot / swatch ----------
@Composable
fun ReColorDot(
    color: Color,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    size: Int = 40,
) {
    val colors = Theme.current
    val dotInteraction = remember { MutableInteractionSource() }
    val selectedScale by animateFloatAsState(if (selected) 1.06f else 1f, Motion.springSnap, label = "dotSel")
    Box(
        modifier =
            modifier
                .size(size.dp)
                .scale(selectedScale)
                .pressScale(dotInteraction, pressedScale = 0.88f)
                .clip(RoundedCornerShape((size / 4).dp))
                .liquidHighlight(dotInteraction, Color.White, radius = (size * 0.7f).dp)
                .background(if (selected) colors.accentHi else Color.Transparent)
                .clickable(interactionSource = dotInteraction, indication = null) { onTap() }
                .padding(3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.fillMaxSize().clip(RoundedCornerShape(((size - 6) / 4).dp)).background(color),
        )
    }
}

// ---------- section title inside a panel ----------
@Composable
fun ReSectionTitle(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text,
        color = Theme.current.subText,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        modifier = modifier.padding(top = 10.dp, bottom = 4.dp),
    )
}

// ---------- bottom-sheet panel with full-screen scrim ----------
@Composable
fun RePanel(
    title: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    opacity: Float = 0.95f,
    content: @Composable () -> Unit,
) {
    val colors = Theme.current
    // Note: In a real app, you'd manage the visibility state outside to animate out before removing from composition.
    // For this MVP, we animate in when composed.
    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(colors.scrim)
                .clickable(onClick = onClose),
    ) {
        AnimatedVisibility(
            visible = true,
            enter = slideInVertically(initialOffsetY = { it }, animationSpec = Motion.enterSpring()),
            exit = slideOutVertically(targetOffsetY = { it }, animationSpec = tween(200)),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .background(
                            color = colors.panel.copy(alpha = opacity),
                            shape = RoundedCornerShape(topStart = Dimens.radius * 2, topEnd = Dimens.radius * 2),
                        ).padding(bottom = 12.dp)
                        .clickable(enabled = false) {}, // consume clicks inside panel
            ) {
                // drag handle
                Box(
                    Modifier
                        .padding(top = 8.dp, bottom = 2.dp)
                        .align(Alignment.CenterHorizontally)
                        .width(36.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(colors.border),
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        title,
                        color = colors.text,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    ReIconButton(
                        icon = com.reverie.paint.R.drawable.ic_x,
                        desc = "关闭",
                        onTap = onClose,
                    )
                }
                content()
            }
        }
    }
}

// ---------- small labeled value row (settings style) ----------
@Composable
fun ReSettingRow(
    label: String,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit,
) {
    val colors = Theme.current
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .height(48.dp)
                .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = colors.text, fontSize = 14.sp)
        trailing()
    }
}

// ---------- generic chip (preset selection) ----------
@Composable
fun ReChip(
    text: String,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
) {
    val colors = Theme.current
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(Dimens.radiusSm)
    Box(
        modifier =
            modifier
                .pressScale(interaction, pressedScale = 0.93f)
                .liquidLean(interaction, maxOffset = 3.dp)
                .clip(shape)
                .background(colors.panelHi)
                .then(if (selected) Modifier.border(1.dp, colors.accent.copy(alpha = 0.55f), shape).liquidSheen(trigger = selected) else Modifier)
                .clickable(interactionSource = interaction, indication = null) { onTap() }
                .padding(horizontal = 14.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = if (selected) colors.accent else colors.text,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
        )
    }
}

// ---------- modal text field (replaces the ad-hoc text dialog) ----------
@Composable
fun ReTextInput(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    val colors = Theme.current
    androidx.compose.material3.OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        placeholder = { Text(placeholder, color = colors.subText) },
        colors =
            androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                focusedBorderColor = colors.accent,
                unfocusedBorderColor = colors.border,
                focusedContainerColor = colors.panel,
                unfocusedContainerColor = colors.panel,
                cursorColor = colors.accent,
                focusedTextColor = colors.text,
                unfocusedTextColor = colors.text,
            ),
        modifier = modifier.fillMaxWidth(),
    )
}

// ---------- menu item (dropdown style panels) ----------
@Composable
fun ReMenuItem(
    @DrawableRes icon: Int,
    label: String,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
    iconColor: Color = Theme.current.icon,
    textColor: Color = Theme.current.text,
) {
    val colors = Theme.current
    val interaction = remember { MutableInteractionSource() }
    val isPressed by interaction.collectIsPressedAsState()
    val cardBg = if (isPressed) colors.panelHi.copy(alpha = 0.95f) else colors.panelHi.copy(alpha = 0.6f)
    val cardBorder = if (isPressed) colors.accent.copy(alpha = 0.3f) else colors.border.copy(alpha = 0.4f)

    Column(
        modifier = modifier
            .pressScale(interaction, pressedScale = 0.93f)
            .liquidLean(interaction, maxOffset = 3.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(cardBg)
            .border(1.dp, cardBorder, RoundedCornerShape(10.dp))
            .clickable(interactionSource = interaction, indication = null) { onTap() }
            .padding(vertical = 10.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = label,
            tint = if (isPressed) colors.accent else iconColor,
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.height(6.dp))
        Text(
            label,
            color = if (isPressed) colors.accent else textColor,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            maxLines = 1
        )
    }
}

