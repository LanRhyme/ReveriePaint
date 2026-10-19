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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MenuDefaults
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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reverie.paint.R
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import com.reverie.paint.ui.theme.Morandi
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
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.offset
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeChild
import dev.chrisbanes.haze.hazeEffect
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.model.UiPanelSlot
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
    tint: Color? = null,
    iconSize: androidx.compose.ui.unit.Dp = Dimens.icon,
) {
    val colors = Theme.current
    val interaction = remember { MutableInteractionSource() }
    val tintColor by animateColorAsState(
        tint ?: if (selected) colors.accent else colors.icon,
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
            modifier = Modifier.size(iconSize),
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
    val isLeftHand = vm?.leftHandMode == true
    val popupOffsetPx = with(density) {
        if (isLeftHand) -52.dp.roundToPx() else 52.dp.roundToPx()
    }

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
        alignment = if (isLeftHand) Alignment.CenterEnd else Alignment.CenterStart,
        offset = androidx.compose.ui.unit.IntOffset(popupOffsetPx, 0),
        onDismissRequest = { visibleState.targetState = false },
        properties = androidx.compose.ui.window.PopupProperties(focusable = true),
    ) {
        AnimatedVisibility(
            visibleState = visibleState,
            enter =
                fadeIn(Motion.enterSpring()) +
                    slideInHorizontally(Motion.enterSpring()) { if (isLeftHand) it / 2 else -it / 2 } +
                    scaleIn(initialScale = 0.92f, animationSpec = Motion.enterSpring()),
            exit = fadeOut(tween(160, easing = FastOutLinearInEasing)) +
                   slideOutHorizontally(tween(160, easing = FastOutLinearInEasing)) { if (isLeftHand) it / 2 else -it / 2 } +
                   scaleOut(targetScale = 0.92f, animationSpec = tween(160, easing = FastOutLinearInEasing))
        ) {
            Box(
                modifier = Modifier
                    .width(224.dp)
                    .shadow(20.dp, RoundedCornerShape(18.dp), spotColor = Color.Black.copy(alpha = 0.3f))
                    .clip(RoundedCornerShape(18.dp))
                    .background(colors.panel.copy(alpha = popupAlpha))
                    .then(
                        if (vm?.blurBackground == true && hazeState != null) {
                            Modifier.hazeChild(
                                state = hazeState,
                                style = Glass.popupStyle(popupAlpha),
                            )
                        } else {
                            Modifier
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
                                tint = colors.subText,
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
                                color = colors.text,
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
                                contentDescription = stringResource(R.string.slider_fine_step_down),
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
                                contentDescription = stringResource(R.string.slider_fine_step_up),
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
                            text = stringResource(R.string.slider_common_presets),
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
                                contentDescription = stringResource(R.string.slider_save_preset),
                                tint = colors.accent,
                                modifier = Modifier.size(11.dp),
                            )
                            Text(
                                text = stringResource(R.string.slider_save_current),
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
                                                contentDescription = stringResource(R.string.slider_add_preset),
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
    onRelease: ((Float) -> Unit)? = null,
    modifier: Modifier = Modifier,
    trackWidth: Int = 26,
    trackHeight: Int = 175,
    title: String = stringResource(R.string.slider_fine_tune),
    iconRes: Int = com.reverie.paint.R.drawable.ic_brush,
    valueText: String,
    previewCircleRadiusPx: Float? = null,
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
    var dragSpeed by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(dragSpeed) {
        if (dragSpeed > 0.001f) {
            kotlinx.coroutines.delay(45)
            dragSpeed = 0f
        }
    }

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
            // Fine-tune & Preset Popup (Click-to-open detailed popover)
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
                                    dragSpeed = 0f
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                },
                                onDragEnd = {
                                    isDragging = false
                                    dragSpeed = 0f
                                    onRelease?.invoke(localFraction)
                                },
                                onDragCancel = {
                                    isDragging = false
                                    dragSpeed = 0f
                                    onRelease?.invoke(localFraction)
                                }
                            ) { change, dragAmount ->
                                val speed = (kotlin.math.abs(dragAmount.y) / density.density).coerceIn(0f, 15f)
                                if (speed > dragSpeed) {
                                    dragSpeed = speed
                                } else {
                                    dragSpeed = dragSpeed * 0.6f + speed * 0.4f
                                }
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
                val capsuleRadius = (trackWidth / 2).dp
                val capsuleGrow by animateFloatAsState(
                    targetValue = if (isDragging) 1.04f else 1f,
                    animationSpec = spring(dampingRatio = 0.88f, stiffness = 420f),
                    label = "capsuleGrow"
                )

                // Track Background (打孔嵌入式毛玻璃：独立0.6x毛玻璃轨道与下凹雕刻光影)
                val panelAlpha = vm?.uiOpacity ?: 1f
                val grooveAlpha = (panelAlpha * 0.60f).coerceIn(0.08f, 0.90f)
                Box(
                    modifier = Modifier
                        .width(trackWidth.dp)
                        .fillMaxHeight()
                        .graphicsLayer {
                            scaleX = capsuleGrow
                            scaleY = 1f
                            transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 0.5f)
                        }
                        .clip(RoundedCornerShape(capsuleRadius))
                        .background(colors.panel.copy(alpha = grooveAlpha))
                        .border(
                            0.5.dp,
                            Brush.verticalGradient(
                                listOf(
                                    Color.Black.copy(alpha = (0.35f * panelAlpha).coerceIn(0.10f, 0.45f)),
                                    Color.White.copy(alpha = (0.12f * panelAlpha).coerceIn(0.04f, 0.18f)),
                                )
                            ),
                            RoundedCornerShape(capsuleRadius)
                        )
                ) {
                    // Active progress fill level
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .fillMaxHeight(localFraction.coerceIn(0f, 1f))
                            .align(Alignment.BottomCenter)
                            .background(colors.accent.copy(alpha = (0.22f * panelAlpha).coerceIn(0.10f, 0.26f)))
                    )
                }

                // 动量微拉伸：拖拽时沿滑动轴微幅伸长 0~1.8dp，横向微压缩保持流体体积感
                val dynamicStretch by animateFloatAsState(
                    targetValue = if (isDragging) (dragSpeed * 0.16f).coerceIn(0f, 1.8f) else 0f,
                    animationSpec = spring(dampingRatio = 0.88f, stiffness = 420f),
                    label = "ind_stretch"
                )

                // Dynamic Indicator Line (Spring animations with momentum stretch & squash)
                val indicatorHeight by animateDpAsState(
                    targetValue = if (isDragging) (8.0f + dynamicStretch).dp else 3.dp,
                    animationSpec = spring(dampingRatio = 0.88f, stiffness = 420f),
                    label = "ind_h"
                )
                val indicatorWidth by animateDpAsState(
                    targetValue = if (isDragging) ((trackWidth - 0.5f) - dynamicStretch * 0.5f).dp else (trackWidth - 2f).dp,
                    animationSpec = spring(dampingRatio = 0.88f, stiffness = 420f),
                    label = "ind_w"
                )
                val indicatorAlpha by animateFloatAsState(
                    targetValue = if (isDragging) 0.68f else 0.78f,
                    animationSpec = spring(dampingRatio = 0.88f, stiffness = 420f),
                    label = "ind_alpha"
                )

                val travelPx = with(density) { (trackHeight.dp - indicatorHeight).toPx() }
                val indicatorColor = androidx.compose.ui.graphics.lerp(colors.text, colors.accent, 0.45f)

                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .graphicsLayer {
                            translationY = -(localFraction.coerceIn(0f, 1f) * travelPx)
                        }
                        .width(indicatorWidth)
                        .height(indicatorHeight)
                        .shadow(
                            elevation = if (isDragging) 3.5.dp else 1.5.dp,
                            shape = RoundedCornerShape(indicatorHeight / 2),
                            spotColor = Color.Black.copy(alpha = 0.22f),
                        )
                        .clip(RoundedCornerShape(indicatorHeight / 2))
                        .background(indicatorColor.copy(alpha = indicatorAlpha))
                )

                // Live floating tooltip (Fixed cleanly at Center-Start/Center-End of the slider, no jumping/jittering)
                if (isDragging) {
                    val isLeftHand = vm?.leftHandMode == true
                    val tooltipOffsetPx = with(density) {
                        if (isLeftHand) -(trackWidth + 18).dp.roundToPx() else (trackWidth + 18).dp.roundToPx()
                    }
                    val popupAlpha = vm?.popupPanelOpacity ?: 0.94f
                    Popup(
                        alignment = if (isLeftHand) Alignment.CenterEnd else Alignment.CenterStart,
                        offset = androidx.compose.ui.unit.IntOffset(tooltipOffsetPx, 0),
                        properties = androidx.compose.ui.window.PopupProperties(
                            focusable = false,
                            dismissOnBackPress = false,
                            dismissOnClickOutside = false,
                        )
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .shadow(8.dp, RoundedCornerShape(8.dp), spotColor = Color.Black.copy(alpha = 0.25f))
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(colors.panel.copy(alpha = popupAlpha))
                                    .glassBorder(RoundedCornerShape(8.dp))
                                    .padding(horizontal = 10.dp, vertical = 5.dp)
                            ) {
                                Text(
                                    valueText,
                                    color = colors.text,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                            }

                            if (previewCircleRadiusPx != null && previewCircleRadiusPx > 0f) {
                                val safeRadius = previewCircleRadiusPx.coerceIn(2.5f, 100f)
                                val canvasBoxSize = with(density) { ((safeRadius + 4f) * 2f).toDp() }
                                Box(
                                    modifier = Modifier
                                        .shadow(8.dp, CircleShape, spotColor = Color.Black.copy(alpha = 0.2f))
                                        .clip(CircleShape)
                                        .background(colors.panel.copy(alpha = (popupAlpha * 0.75f).coerceIn(0.2f, 0.95f)))
                                        .glassBorder(CircleShape)
                                        .size(canvasBoxSize),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                                        val c = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height / 2f)
                                        // Faint theme accent tint inside
                                        drawCircle(colors.accent.copy(alpha = 0.12f), radius = safeRadius, center = c)
                                        // Outer black stroke for contrast on bright canvas / background
                                        drawCircle(
                                            Color.Black.copy(alpha = 0.6f),
                                            radius = safeRadius + 0.8f,
                                            center = c,
                                            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.6f)
                                        )
                                        // Inner white stroke for contrast on dark background
                                        drawCircle(
                                            Color.White,
                                            radius = safeRadius,
                                            center = c,
                                            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.2f)
                                        )
                                        if (safeRadius >= 10f) {
                                            // Center precision dot
                                            drawCircle(Color.Black.copy(alpha = 0.6f), radius = 2.2f, center = c)
                                            drawCircle(Color.White, radius = 1.2f, center = c)
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

/**
 * 水平滑动条 (用于悬浮模式停靠在上下边缘时的笔刷大小与不透明度调节)
 */
@Composable
fun ReHorizontalSlider(
    label: String,
    fraction: Float,
    onFraction: (Float) -> Unit,
    onRelease: ((Float) -> Unit)? = null,
    modifier: Modifier = Modifier,
    trackWidth: Int = 175,
    trackHeight: Int = 26,
    title: String = stringResource(R.string.slider_fine_tune),
    iconRes: Int = com.reverie.paint.R.drawable.ic_brush,
    valueText: String,
    previewCircleRadiusPx: Float? = null,
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
    var dragSpeed by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(dragSpeed) {
        if (dragSpeed > 0.001f) {
            kotlinx.coroutines.delay(45)
            dragSpeed = 0f
        }
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier,
    ) {
        if (label.isNotEmpty()) {
            Text(
                text = label,
                color = colors.subText,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.width(4.dp))
        }

        Box(
            modifier = Modifier.width(trackWidth.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
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
                    onSelectPreset = { p -> onSelectPreset?.invoke(p) },
                    onSavePreset = { idx -> onSavePreset?.invoke(idx) },
                    onDeletePreset = { idx -> onDeletePreset?.invoke(idx) },
                    formatPreset = formatPreset,
                    vm = vm,
                    hazeState = hazeState,
                    onDismiss = { showPopup = false },
                )
            }

            Box(
                modifier = Modifier
                    .width(trackWidth.dp)
                    .height((trackHeight + 6).dp)
                    .onSizeChanged { trackPx = it.width }
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
                                dragSpeed = 0f
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            },
                            onDragEnd = {
                                isDragging = false
                                dragSpeed = 0f
                                onRelease?.invoke(localFraction)
                            },
                            onDragCancel = {
                                isDragging = false
                                dragSpeed = 0f
                                onRelease?.invoke(localFraction)
                            }
                        ) { change, dragAmount ->
                            val speed = (kotlin.math.abs(dragAmount.x) / density.density).coerceIn(0f, 15f)
                            if (speed > dragSpeed) {
                                dragSpeed = speed
                            } else {
                                dragSpeed = dragSpeed * 0.6f + speed * 0.4f
                            }
                            val value = (change.position.x / trackPx.toFloat()).coerceIn(0f, 1f)
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
                contentAlignment = Alignment.CenterStart,
            ) {
                val capsuleRadius = (trackHeight / 2).dp
                val capsuleGrow by animateFloatAsState(
                    targetValue = if (isDragging) 1.04f else 1f,
                    animationSpec = spring(dampingRatio = 0.88f, stiffness = 420f),
                    label = "capsuleGrowH"
                )

                val panelAlpha = vm?.uiOpacity ?: 1f
                val grooveAlpha = (panelAlpha * 0.60f).coerceIn(0.08f, 0.90f)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(trackHeight.dp)
                        .graphicsLayer {
                            scaleY = capsuleGrow
                            scaleX = 1f
                            transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 0.5f)
                        }
                        .clip(RoundedCornerShape(capsuleRadius))
                        .background(colors.panel.copy(alpha = grooveAlpha))
                        .border(
                            0.5.dp,
                            Brush.horizontalGradient(
                                listOf(
                                    Color.Black.copy(alpha = (0.35f * panelAlpha).coerceIn(0.10f, 0.45f)),
                                    Color.White.copy(alpha = (0.12f * panelAlpha).coerceIn(0.04f, 0.18f)),
                                )
                            ),
                            RoundedCornerShape(capsuleRadius)
                        )
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(localFraction.coerceIn(0f, 1f))
                            .align(Alignment.CenterStart)
                            .background(colors.accent.copy(alpha = (0.22f * panelAlpha).coerceIn(0.10f, 0.26f)))
                    )
                }

                // 动量微拉伸：拖拽时沿水平轴微幅伸长 0~1.8dp，纵向微压缩保持流体体积感
                val dynamicStretch by animateFloatAsState(
                    targetValue = if (isDragging) (dragSpeed * 0.16f).coerceIn(0f, 1.8f) else 0f,
                    animationSpec = spring(dampingRatio = 0.88f, stiffness = 420f),
                    label = "ind_stretch_h"
                )

                // Dynamic Indicator Line (Spring animations with momentum stretch & squash)
                val indicatorWidth by animateDpAsState(
                    targetValue = if (isDragging) (8.0f + dynamicStretch).dp else 3.dp,
                    animationSpec = spring(dampingRatio = 0.88f, stiffness = 420f),
                    label = "ind_w_h"
                )
                val indicatorHeight by animateDpAsState(
                    targetValue = if (isDragging) ((trackHeight - 0.5f) - dynamicStretch * 0.5f).dp else (trackHeight - 2f).dp,
                    animationSpec = spring(dampingRatio = 0.88f, stiffness = 420f),
                    label = "ind_h_h"
                )
                val indicatorAlpha by animateFloatAsState(
                    targetValue = if (isDragging) 0.68f else 0.78f,
                    animationSpec = spring(dampingRatio = 0.88f, stiffness = 420f),
                    label = "ind_alpha_h"
                )

                val travelPx = with(density) { (trackWidth.dp - indicatorWidth).toPx() }
                val indicatorColor = androidx.compose.ui.graphics.lerp(colors.text, colors.accent, 0.45f)

                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .graphicsLayer {
                            translationX = localFraction.coerceIn(0f, 1f) * travelPx
                        }
                        .width(indicatorWidth)
                        .height(indicatorHeight)
                        .shadow(
                            elevation = if (isDragging) 3.5.dp else 1.5.dp,
                            shape = RoundedCornerShape(indicatorWidth / 2),
                            spotColor = Color.Black.copy(alpha = 0.22f),
                        )
                        .clip(RoundedCornerShape(indicatorWidth / 2))
                        .background(indicatorColor.copy(alpha = indicatorAlpha))
                )

                if (isDragging) {
                    val popupAlpha = vm?.popupPanelOpacity ?: 0.94f
                    val tooltipOffsetYPx = with(density) { -(trackHeight + 28).dp.roundToPx() }
                    Popup(
                        alignment = Alignment.TopCenter,
                        offset = androidx.compose.ui.unit.IntOffset(0, tooltipOffsetYPx),
                        properties = androidx.compose.ui.window.PopupProperties(
                            focusable = false,
                            dismissOnBackPress = false,
                            dismissOnClickOutside = false,
                        )
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .shadow(8.dp, RoundedCornerShape(8.dp), spotColor = Color.Black.copy(alpha = 0.25f))
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(colors.panel.copy(alpha = popupAlpha))
                                    .glassBorder(RoundedCornerShape(8.dp))
                                    .padding(horizontal = 10.dp, vertical = 5.dp)
                            ) {
                                Text(
                                    valueText,
                                    color = colors.text,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                            }

                            if (previewCircleRadiusPx != null && previewCircleRadiusPx > 0f) {
                                val safeRadius = previewCircleRadiusPx.coerceIn(2.5f, 100f)
                                val canvasBoxSize = with(density) { ((safeRadius + 4f) * 2f).toDp() }
                                Box(
                                    modifier = Modifier
                                        .shadow(8.dp, CircleShape, spotColor = Color.Black.copy(alpha = 0.2f))
                                        .clip(CircleShape)
                                        .background(colors.panel.copy(alpha = (popupAlpha * 0.75f).coerceIn(0.2f, 0.95f)))
                                        .glassBorder(CircleShape)
                                        .size(canvasBoxSize),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                                        val c = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height / 2f)
                                        drawCircle(colors.accent.copy(alpha = 0.12f), radius = safeRadius, center = c)
                                        drawCircle(
                                            Color.Black.copy(alpha = 0.6f),
                                            radius = safeRadius + 0.8f,
                                            center = c,
                                            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.6f)
                                        )
                                        drawCircle(
                                            Color.White,
                                            radius = safeRadius,
                                            center = c,
                                            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.2f)
                                        )
                                        if (safeRadius >= 10f) {
                                            drawCircle(Color.Black.copy(alpha = 0.6f), radius = 2.2f, center = c)
                                            drawCircle(Color.White, radius = 1.2f, center = c)
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
    val currentOnValue by rememberUpdatedState(onValue)
    val currentOnRelease by rememberUpdatedState(onRelease)
    var interacting by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val trackScale by animateFloatAsState(if (interacting) 1.14f else 1f, Motion.springSnap, label = "sliderTrackScale")
    val glowAlpha by animateFloatAsState(if (interacting) 0.20f else 0f, Motion.springSnap, label = "sliderGlow")
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .height(height.dp)
                .graphicsLayer { scaleY = trackScale }
                .clip(RoundedCornerShape((height / 2).dp))
                .background(colors.panelHi)
                .pointerInput(Unit) {
                    // 支持与纵向滚动容器（LazyColumn/verticalScroll）完美协作的手势识别器：
                    // 1. 触摸按下不立即修改数值，防页面滚动时手指落点误触改值
                    // 2. 纵向位移优先判定为容器滚动，主动放弃并让渡事件
                    // 3. 横向位移超过 touchSlop 且占优时，才消费事件并开始拖动滑块
                    // 4. 原地轻点（Tap）直接定位并触发 onRelease
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val w0 = size.width.toFloat()
                        if (w0 <= 0f) return@awaitEachGesture

                        val touchSlop = viewConfiguration.touchSlop
                        var isDragging = false
                        var isScrollingVertically = false

                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull() ?: break
                                if (!change.pressed) {
                                    // 手指抬起 (Up)
                                    if (!isScrollingVertically) {
                                        if (!isDragging) {
                                            // 原地点击滑块
                                            val tapVal = (change.position.x / w0).coerceIn(0f, 1f)
                                            currentOnValue(tapVal)
                                        }
                                        currentOnRelease?.invoke()
                                    }
                                    break
                                }

                                if (isScrollingVertically) {
                                    // 判定为列表滚动，不拦截、不改值
                                    break
                                }

                                val dx = change.position.x - down.position.x
                                val dy = change.position.y - down.position.y
                                val absDx = kotlin.math.abs(dx)
                                val absDy = kotlin.math.abs(dy)

                                if (!isDragging) {
                                    if (absDy > touchSlop && absDy > absDx) {
                                        // 纵向滚动优先，退出手势让渡给外层可滚动容器
                                        isScrollingVertically = true
                                        break
                                    } else if (absDx > touchSlop && absDx >= absDy) {
                                        // 横向拖拽生效
                                        isDragging = true
                                        interacting = true
                                    }
                                }

                                if (isDragging) {
                                    change.consume()
                                    val curVal = (change.position.x / w0).coerceIn(0f, 1f)
                                    dragFraction = curVal
                                    currentOnValue(curVal)
                                }
                            }
                        } finally {
                            dragFraction = null
                            interacting = false
                        }
                    }
                },
    ) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            val currentFraction = (dragFraction ?: value).coerceIn(0f, 1f)
            val fillW = size.width * currentFraction
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
                        radius = size.height * 1.05f,
                    ),
                    radius = size.height * 1.05f,
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
    enabled: Boolean = true,
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
                .graphicsLayer { alpha = if (enabled) 1f else 0.4f }
                .size(48.dp, 28.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(trackColor)
                .clickable(enabled = enabled) { onChecked(!checked) }
                .padding(3.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .offset { IntOffset((20.dp.toPx() * thumbProgress).roundToInt(), 0) }
                .size(22.dp)
                .shadow(2.dp, CircleShape, spotColor = Color.Black.copy(alpha = 0.35f))
                .clip(CircleShape)
                .background(colors.onAccent),
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
                        desc = stringResource(R.string.common_close),
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
                .liquidHighlight(interaction, Color.White, radius = 26.dp)
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

// ---------- glass text button (dialog actions) ----------
/**
 * 对话框文字按钮：玻璃胶囊 + 按压光效。替换全部 material3 TextButton/Button。
 * - 默认幽灵态：panelHi 半透玻璃底 + glassBorder，文字色由调用点传入保留原语义
 * - primary=true：accent 实心胶囊（主操作）
 * - containerColor/contentColor 可覆盖（危险操作红底等）
 */
@Composable
fun ReTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: Int? = null,
    primary: Boolean = false,
    containerColor: Color? = null,
    contentColor: Color? = null,
    textColor: Color = Theme.current.accent,
    fontSize: androidx.compose.ui.unit.TextUnit = 14.sp,
    fontWeight: FontWeight? = FontWeight.Medium,
    enabled: Boolean = true,
) {
    val colors = Theme.current
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(18.dp)
    val bg = when {
        containerColor != null -> containerColor
        primary -> colors.accent
        else -> colors.panelHi.copy(alpha = if (enabled) 0.55f else 0.3f)
    }
    val fg = when {
        contentColor != null -> contentColor
        primary -> colors.onAccent
        else -> textColor
    }
    Box(
        modifier =
            modifier
                .graphicsLayer { alpha = if (enabled) 1f else 0.4f }
                .pressScale(interaction, pressedScale = 0.94f)
                .clip(shape)
                .liquidHighlight(interaction, Color.White, radius = 30.dp)
                .background(bg)
                .then(if (!primary && containerColor == null && enabled) Modifier.glassBorder(shape) else Modifier)
                .clickable(interactionSource = interaction, indication = null, enabled = enabled) { onClick() }
                .padding(horizontal = 16.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (icon != null) {
                Icon(
                    painter = painterResource(icon),
                    contentDescription = null,
                    tint = fg,
                    modifier = Modifier.size(16.dp),
                )
            }
            Text(
                text,
                color = fg,
                fontSize = fontSize,
                fontWeight = fontWeight,
            )
        }
    }
}

// ---------- floating action button (glass circle) ----------
/** 圆形玻璃 FAB：accent 实心 + 高光缘 + pressGrow 光效，替换 material3 FloatingActionButton */
@Composable
fun ReFab(
    icon: Int,
    desc: String,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
    sizeDp: androidx.compose.ui.unit.Dp = 56.dp,
) {
    val colors = Theme.current
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier =
            modifier
                .pressGrow(interaction, growFraction = 0.06f)
                .size(sizeDp)
                .shadow(12.dp, CircleShape, spotColor = Color.Black.copy(alpha = 0.35f))
                .clip(CircleShape)
                .liquidHighlight(interaction, Color.White, radius = sizeDp * 0.7f)
                .background(colors.accent)
                .border(1.dp, Color.White.copy(alpha = 0.25f), CircleShape)
                .clickable(interactionSource = interaction, indication = null) { onTap() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = desc,
            tint = colors.onAccent,
            modifier = Modifier.size(22.dp),
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

    Column(
        modifier = modifier
            .pressScale(interaction, pressedScale = 0.93f)
            .liquidLean(interaction, maxOffset = 3.dp)
            .clip(RoundedCornerShape(10.dp))
            .liquidHighlight(interaction, Color.White, radius = 30.dp)
            .background(cardBg)
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

// ---------- Pin Button & Drag Handle for Floating Companion Panels ----------

@Composable
fun PinButton(
    isPinned: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Theme.current
    Box(
        modifier = modifier
            .size(22.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(if (isPinned) colors.accent.copy(alpha = 0.22f) else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(12.dp)) {
            val tint = if (isPinned) colors.accent else colors.subText
            val path = Path().apply {
                moveTo(size.width * 0.3f, 0f)
                lineTo(size.width * 0.7f, 0f)
                lineTo(size.width * 0.6f, size.height * 0.45f)
                lineTo(size.width * 0.85f, size.height * 0.55f)
                lineTo(size.width * 0.55f, size.height * 0.55f)
                lineTo(size.width * 0.5f, size.height)
                lineTo(size.width * 0.45f, size.height * 0.55f)
                lineTo(size.width * 0.15f, size.height * 0.55f)
                lineTo(size.width * 0.4f, size.height * 0.45f)
                close()
            }
            drawPath(path, color = tint)
        }
    }
}

@Composable
fun DragPillHandle(
    onDrag: (Offset) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Theme.current
    Box(
        modifier = modifier
            .size(60.dp, 16.dp)
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    onDrag(dragAmount)
                }
            },
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp, 3.5.dp)
                .clip(CircleShape)
                .background(colors.subText.copy(alpha = 0.45f))
        )
    }
}

@Composable
fun PanelCloseButton(
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Theme.current
    Box(
        modifier = modifier
            .size(22.dp)
            .clip(CircleShape)
            .clickable(onClick = onClose),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "✕",
            color = colors.subText,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

// ---------- ReDropdownMenu & ReDropdownMenuItem (无框工作室美学通用下拉菜单) ----------
@Composable
fun ReDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = Theme.current
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        shape = shape,
        containerColor = colors.panelHi,
        tonalElevation = 0.dp,
        shadowElevation = 14.dp,
        border = null,
        modifier = modifier
            .widthIn(min = 160.dp)
            .padding(vertical = 4.dp),
        content = content,
    )
}

@Composable
fun ReDropdownMenuItem(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: Any? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    isDestructive: Boolean = false,
    selected: Boolean = false,
    textColor: Color? = null,
    iconColor: Color? = null,
    fontSize: androidx.compose.ui.unit.TextUnit = 13.5.sp,
) {
    val colors = Theme.current
    val effectiveTextColor = when {
        textColor != null -> textColor
        isDestructive -> Color(0xFFFF5252)
        selected -> colors.accent
        else -> colors.text
    }
    val effectiveIconColor = when {
        iconColor != null -> iconColor
        isDestructive -> Color(0xFFFF5252)
        selected -> colors.accent
        else -> colors.icon
    }

    val computedLeadingIcon: (@Composable () -> Unit)? = when {
        leadingIcon != null -> leadingIcon
        icon is Int -> {
            {
                Icon(
                    painter = painterResource(icon),
                    contentDescription = null,
                    tint = effectiveIconColor,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        icon is androidx.compose.ui.graphics.vector.ImageVector -> {
            {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = effectiveIconColor,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        else -> null
    }

    DropdownMenuItem(
        text = {
            Text(
                text = text,
                color = effectiveTextColor,
                fontSize = fontSize,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            )
        },
        onClick = onClick,
        leadingIcon = computedLeadingIcon,
        trailingIcon = trailingIcon,
        colors = MenuDefaults.itemColors(
            textColor = effectiveTextColor,
            leadingIconColor = effectiveIconColor,
        ),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
        modifier = modifier
            .height(40.dp)
            .padding(horizontal = 6.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(10.dp)),
    )
}

/** 50% 避让唯一的过渡规格：进入/离开均 70ms，避免两套动画相位差。 */
private val EvadeEnterSpec = tween<Float>(durationMillis = 70, easing = FastOutSlowInEasing)
private val EvadeExitSpec = tween<Float>(durationMillis = 70, easing = FastOutLinearInEasing)

/**
 * 把面板的 root 实测边界登记到 [PaintViewModel.panelOcclusion]。
 *
 * ownerToken 只在 Modifier.Node 生命周期内申请与清除, 调用点不得传入, 避免父组件
 * 每次重组都换 token。必须挂在面板最外层视觉容器上, 且在 `.shadow` / `.clip` /
 * `.background` / `.hazeChild` 之前, 保证 bounds 覆盖阴影与背景整体。
 *
 * 阴影外扩 dp 在此按当前 density 换算为像素后登记，供前缓冲孔径裁剪使用；
 * 32dp/40dp 避让距离判定仍只使用面板本体 bounds。
 */
internal fun Modifier.reportUiSlot(vm: PaintViewModel?, slot: UiPanelSlot): Modifier =
    if (vm == null) {
        this
    } else {
        composed {
            val density = LocalDensity.current.density
            this.then(UiSlotElement(vm, slot, density))
        }
    }

/**
 * 可避让面板的外壳背景、毛玻璃交叉淡化与 100% 轮廓修饰符。
 *
 * 必须在 `.reportUiSlot(vm, slot).shadow(elevation, shape).clip(shape)` 之后调用：
 * - 外层 `.shadow` 不受本修饰符影响，保持 100% 阴影；
 * - `EVADING` 态卸载 `hazeChild` 实时模糊，通过 `drawWithContent` 将面板底色
 *   平滑过渡到 50%；
 * - `drawWithContent` 在内容之上以 100% 不透明度绘制 1.dp 面板边框，保留清晰轮廓。
 *
 * 与 [evadablePanelContent] 共享 [PaintViewModel.slotEvadeAlpha] 同一个 Animatable。
 */
internal fun Modifier.evadablePanelSurface(
    vm: PaintViewModel,
    slot: UiPanelSlot,
    shape: Shape,
    baseOpacity: Float,
    hazeState: HazeState?,
    isBarStyle: Boolean = false,
    panelColor: Color = Morandi.panel,
    drawBorder: Boolean = true,
): Modifier = composed {
    if (!slot.evadable) return@composed this
    val isEvading = vm.slotEvading[slot.ordinal].value
    val evadeAlpha = vm.slotEvadeAlpha[slot.ordinal]
    // 每个槽位只允许这一处驱动动画；content 修饰符只读取同一个 Animatable。
    LaunchedEffect(isEvading) {
        evadeAlpha.animateTo(
            targetValue = if (isEvading) 0.5f else 1f,
            animationSpec = if (isEvading) EvadeEnterSpec else EvadeExitSpec,
        )
    }
    val borderStrokePx = with(LocalDensity.current) { 1.dp.toPx() }
    val borderColor = Morandi.border
    val useHaze = vm.blurBackground && hazeState != null && !isEvading
    val hazeModifier = if (useHaze) {
        val style = if (isBarStyle) {
            Glass.barStyle(if (baseOpacity >= 0.99f) 0.92f else baseOpacity)
        } else {
            Glass.popupStyle(if (baseOpacity >= 0.99f) 0.90f else baseOpacity)
        }
        Modifier.hazeEffect(state = hazeState!!, style = style)
    } else {
        Modifier
    }
    this
        .then(hazeModifier)
        .drawWithContent {
            val factor = evadeAlpha.value
            if (!useHaze || factor < 0.999f) {
                val bgAlpha = if (!useHaze) {
                    (baseOpacity * factor).coerceIn(0f, 1f)
                } else {
                    // 从 EVADING 返回 NORMAL 的 70ms 内，纯色层平滑淡出交给 hazeChild。
                    val crossfade = ((1f - factor) * 2f).coerceIn(0f, 1f)
                    (baseOpacity * 0.5f * crossfade).coerceIn(0f, 1f)
                }
                if (bgAlpha > 0f) {
                    drawRect(color = panelColor.copy(alpha = bgAlpha))
                }
            }
            drawContent()
            if (drawBorder) {
                val outline = shape.createOutline(size, layoutDirection, this)
                drawOutline(
                    outline = outline,
                    color = borderColor,
                    alpha = 1f,
                    style = Stroke(width = borderStrokePx),
                )
            }
        }
}

/**
 * 可避让面板的内部内容透明度与绘画态被动穿透修饰符。
 *
 * - 在 RenderNode 层级将内部控件/图标/色轮透明度设为 `evadeAlpha`
 *   （1.0f <-> 0.5f），零重组、零 Measure/Layout；
 * - 当 [PaintViewModel.isStrokeSessionActive] 为 true 时处于被动避让态，
 *   在 Initial pass 消费额外触点，绝不抢断正在进行的笔画。
 */
internal fun Modifier.evadablePanelContent(
    vm: PaintViewModel,
    slot: UiPanelSlot,
): Modifier = if (!slot.evadable) {
    this
} else {
    val evadeAlpha = vm.slotEvadeAlpha[slot.ordinal]
    this
        .graphicsLayer { alpha = evadeAlpha.value }
        .pointerInput(slot) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (vm.isStrokeSessionActive) {
                        event.changes.forEach { it.consume() }
                    }
                }
            }
        }
}

private data class UiSlotElement(
    val vm: PaintViewModel,
    val slot: UiPanelSlot,
    val density: Float,
) : ModifierNodeElement<UiSlotNode>() {
    override fun create(): UiSlotNode = UiSlotNode(vm, slot, density)

    override fun update(node: UiSlotNode) {
        if (node.vm !== vm || node.slot != slot) {
            node.unregister()
            node.vm = vm
            node.slot = slot
            node.density = density
            if (node.isAttached) node.register()
        } else {
            node.density = density
        }
    }
}

private class UiSlotNode(
    var vm: PaintViewModel,
    var slot: UiPanelSlot,
    var density: Float,
) : Modifier.Node(), GlobalPositionAwareModifierNode {

    private var ownerToken = 0

    override fun onAttach() {
        register()
    }

    override fun onDetach() {
        unregister()
    }

    override fun onReset() {
        unregister()
    }

    fun register() {
        ownerToken = vm.panelOcclusion.nextToken()
    }

    fun unregister() {
        if (ownerToken != 0) {
            vm.panelOcclusion.clear(slot, ownerToken)
            ownerToken = 0
        }
    }

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        if (!coordinates.isAttached) return
        if (ownerToken == 0) return
        val b = coordinates.boundsInRoot()
        val d = density
        vm.panelOcclusion.update(
            slot,
            ownerToken,
            b.left,
            b.top,
            b.right,
            b.bottom,
            padH = slot.shadowPadHDp * d,
            padT = slot.shadowPadTopDp * d,
            padB = slot.shadowPadBottomDp * d,
        )
    }
}
