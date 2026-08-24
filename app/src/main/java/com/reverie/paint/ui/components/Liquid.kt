/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.reverie.paint.ui.theme.Motion
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
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
fun Modifier.liquidLean(source: MutableInteractionSource, maxOffset: Dp = 8.dp): Modifier =
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
                        val tx = (maxPx * tanh(0.07 * dx / maxPx)).toFloat()
                        val ty = (maxPx * tanh(0.07 * dy / maxPx)).toFloat()
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

/** 高光跟随：按压时在触点画径向高光，指尖移动时高光实时跟随，松手淡出。 */
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

    // 按压状态驱动高光淡入淡出（与 clickable 共用同一 interactionSource）
    LaunchedEffect(source) {
        source.interactions.collect { interaction ->
            when (interaction) {
                is PressInteraction.Press -> {
                    center = Offset(
                        interaction.pressPosition.x.coerceIn(0f, size.width.toFloat()),
                        interaction.pressPosition.y.coerceIn(0f, size.height.toFloat()),
                    )
                    alpha.animateTo(0.45f, Motion.springSnap)
                }
                is PressInteraction.Release, is PressInteraction.Cancel -> {
                    alpha.animateTo(0f, Motion.springSoft)
                }
            }
        }
    }

    // 触点跟踪：只观察不消费，不影响既有手势；写 center 仅触发 draw 重绘
    pointerInput(size) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull() ?: break
                if (!change.pressed) break
                center = Offset(
                    change.position.x.coerceIn(0f, size.width.toFloat()),
                    change.position.y.coerceIn(0f, size.height.toFloat()),
                )
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

/**
 * 软体果冻：容器级液态变形。按下时整体朝触点倾倒 + 触点侧不等比压扁，
 * 拖动持续跟随，松手以低阻尼弹簧晃动回正（Motion.springJelly）。
 *
 * 自带 pointerInput 观察原始按下事件（不消费，不影响子元素点击），
 * 无需外部 interactionSource——用于整条底栏/整块面板这类「软体容器」。
 */
fun Modifier.liquidJelly(maxOffset: Dp = 10.dp): Modifier = composed {
    val density = LocalDensity.current
    val maxPx = with(density) { maxOffset.toPx() }.coerceAtLeast(0.01f)
    var size by remember { mutableStateOf(IntSize.Zero) }
    val scope = rememberCoroutineScope()
    val leanX = remember { Animatable(0f) }
    val leanY = remember { Animatable(0f) }
    val squashX = remember { Animatable(1f) }
    val squashY = remember { Animatable(1f) }

    pointerInput(maxPx) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull() ?: break
                if (!change.pressed) break
                if (size == IntSize.Zero) continue
                val dx = change.position.x - size.width / 2f
                val dy = change.position.y - size.height / 2f
                val nx = tanh(0.06 * dx / maxPx).toFloat()
                val ny = tanh(0.06 * dy / maxPx).toFloat()
                val tx = maxPx * nx
                val ty = maxPx * ny
                val sxT = 1f - 0.015f * abs(ny) - 0.008f * abs(nx)
                val syT = 1f - 0.015f * abs(nx) - 0.042f * abs(ny)
                scope.launch {
                    launch { leanX.animateTo(tx, Motion.springSnap) }
                    launch { leanY.animateTo(ty, Motion.springSnap) }
                    launch { squashX.animateTo(sxT, Motion.springSnap) }
                    launch { squashY.animateTo(syT, Motion.springSnap) }
                }
            }
            // 松手：低阻尼果冻回正
            scope.launch {
                launch { leanX.animateTo(0f, Motion.springJelly) }
                launch { leanY.animateTo(0f, Motion.springJelly) }
                launch { squashX.animateTo(1f, Motion.springJelly) }
                launch { squashY.animateTo(1f, Motion.springJelly) }
            }
        }
    }

    this
        .onSizeChanged { size = it }
        .graphicsLayer {
            translationX = leanX.value
            translationY = leanY.value
            scaleX = squashX.value
            scaleY = squashY.value
        }
}

/**
 * 高光扫过（specular sweep）：[trigger] 变化时（跳过首次组合）一道斜向亮带快速掠过。
 * 用于选中态切换的「光擦过」反馈。放在 clip 之后、背景之后调用。
 */
fun Modifier.liquidSheen(trigger: Any?): Modifier = composed {
    val progress = remember { Animatable(1f) } // 1f = 完成/隐藏
    var first by remember { mutableStateOf(true) }

    LaunchedEffect(trigger) {
        if (first) {
            first = false
            return@LaunchedEffect
        }
        progress.snapTo(0f)
        progress.animateTo(1f, tween(durationMillis = 550, easing = CubicBezierEasing(0.2f, 0f, 0.15f, 1f)))
    }

    drawWithContent {
        drawContent()
        val p = progress.value
        if (p < 0.999f && size.width > 0f) {
            val bandX = size.width * (p * 2.2f - 0.6f)
            val half = size.width * 0.22f
            drawRect(
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color.Transparent,
                        Color.White.copy(alpha = 0.30f * (1f - p)),
                        Color.Transparent,
                    ),
                    start = Offset(bandX - half, 0f),
                    end = Offset(bandX + half, size.height.toFloat()),
                ),
            )
        }
    }
}
