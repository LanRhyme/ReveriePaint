/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.reverie.paint.ui.theme.Motion
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.math.tanh

/**
 * 液态交互 Modifier 集（交互参数模型参考 Kyant0/LiquidGlass LiquidButton，纯 Compose 复刻）。
 *
 * 全部以外部 [MutableInteractionSource] 驱动，可与既有 `clickable(interactionSource = …)`
 * 组合；所有状态对象在组合期 remember 预分配，符合零分配铁律。
 *
 * 典型用法：
 * ```
 * val isr = remember { MutableInteractionSource() }
 * Box(Modifier.pressScale(isr).clickable(interactionSource = isr, indication = null) { … })
 * ```
 */

/** 按压缩放回弹（图标/chip/图层行/卡片），pressedScale < 1 压缩手感 */
fun Modifier.pressScale(source: MutableInteractionSource, pressedScale: Float = 0.96f): Modifier =
    composed {
        val pressed by source.collectIsPressedAsState()
        val scale by animateFloatAsState(
            targetValue = if (pressed) pressedScale else 1f,
            animationSpec = Motion.snapBouncy,
            label = "liquidPress",
        )
        scale(scale)
    }

/** 按压微放大（大按钮，LiquidButton 参数模型：lerp(1, 1+growFraction, progress)） */
fun Modifier.pressGrow(source: MutableInteractionSource, growFraction: Float = 0.04f): Modifier =
    pressScale(source, 1f + growFraction)

/**
 * 触点倾倒：按住时向触点方向饱和位移 `maxOffset * tanh(0.05 · d/maxOffset)`（不越界），
 * 松手弹回。用于工具栏大按钮。位移经 offset{} lambda 延迟读取，不触发重组。
 */
fun Modifier.liquidLean(source: MutableInteractionSource, maxOffset: Dp = 5.dp): Modifier =
    composed {
        val density = LocalDensity.current
        val maxPx = with(density) { maxOffset.toPx() }.coerceAtLeast(0.01f)
        var size by remember { mutableStateOf(IntSize.Zero) }
        val offsetX = remember { Animatable(0f) }
        val offsetY = remember { Animatable(0f) }

        LaunchedEffect(source) {
            source.interactions.collect { interaction ->
                when (interaction) {
                    is PressInteraction.Press -> {
                        val dx = interaction.pressPosition.x - size.width / 2f
                        val dy = interaction.pressPosition.y - size.height / 2f
                        val tx = (maxPx * tanh(0.05 * dx / maxPx)).toFloat()
                        val ty = (maxPx * tanh(0.05 * dy / maxPx)).toFloat()
                        coroutineScope {
                            launch { offsetX.animateTo(tx, Motion.springSnap) }
                            launch { offsetY.animateTo(ty, Motion.springSnap) }
                        }
                    }
                    is PressInteraction.Release, is PressInteraction.Cancel -> {
                        coroutineScope {
                            launch { offsetX.animateTo(0f, Motion.springSnap) }
                            launch { offsetY.animateTo(0f, Motion.springSnap) }
                        }
                    }
                }
            }
        }

        this
            .onSizeChanged { size = it }
            .offset { IntOffset(offsetX.value.roundToInt(), offsetY.value.roundToInt()) }
    }

/** 高光跟随：按压时在触点画径向高光，松手淡出。center 固定于按下点。 */
fun Modifier.liquidHighlight(
    source: MutableInteractionSource,
    color: Color,
    radius: Dp = 56.dp,
): Modifier = composed {
    val density = LocalDensity.current
    val rPx = with(density) { radius.toPx() }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var center by remember { mutableStateOf(Offset.Zero) }
    val alpha = remember { Animatable(0f) }

    LaunchedEffect(source) {
        source.interactions.collect { interaction ->
            when (interaction) {
                is PressInteraction.Press -> {
                    center = Offset(
                        interaction.pressPosition.x.coerceIn(0f, size.width.toFloat()),
                        interaction.pressPosition.y.coerceIn(0f, size.height.toFloat()),
                    )
                    alpha.animateTo(0.35f, Motion.springSnap)
                }
                is PressInteraction.Release, is PressInteraction.Cancel -> {
                    alpha.animateTo(0f, Motion.springSoft)
                }
            }
        }
    }

    this
        .onSizeChanged { size = it }
        .drawWithContent {
            drawContent()
            if (alpha.value > 0.01f && center != Offset.Zero) {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(color.copy(alpha = alpha.value), Color.Transparent),
                        center = center,
                        radius = rPx,
                    ),
                    radius = rPx,
                    center = center,
                )
            }
        }
}
