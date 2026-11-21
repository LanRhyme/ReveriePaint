/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.components.transition

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.reverie.paint.model.PanelEdge
import com.reverie.paint.model.UiAnimationSpeed
import com.reverie.paint.ui.theme.Motion
import kotlinx.coroutines.delay

/**
 * 绘画页边缘工具栏/顶栏的丝滑错峰入场进度 (0f -> 1f)。
 *
 * 仅驱动图层阶段属性, 避免触发布局重算或浮窗尺寸测算抖动; 自动适配标准 / 极速 / 关闭三档动效。
 */
@Composable
fun rememberChromeEntranceProgress(
    revealed: Boolean,
    delayMs: Int = 0,
): State<Float> {
    val speed = Motion.currentSpeed
    val anim = remember { Animatable(if (revealed && speed == UiAnimationSpeed.OFF) 1f else 0f) }
    LaunchedEffect(revealed, speed) {
        if (!revealed) {
            anim.snapTo(0f)
            return@LaunchedEffect
        }
        when (speed) {
            UiAnimationSpeed.OFF -> anim.snapTo(1f)
            UiAnimationSpeed.FAST -> {
                val d = delayMs / 2
                if (d > 0) delay(d.toLong())
                anim.animateTo(
                    targetValue = 1f,
                    animationSpec = spring(
                        dampingRatio = 0.90f,
                        stiffness = 900f,
                    ),
                )
            }
            UiAnimationSpeed.NORMAL -> {
                if (delayMs > 0) delay(delayMs.toLong())
                anim.animateTo(
                    targetValue = 1f,
                    animationSpec = spring(
                        dampingRatio = 0.86f,
                        stiffness = Spring.StiffnessMediumLow,
                    ),
                )
            }
        }
    }
    return anim.asState()
}

/**
 * 按面板所贴屏幕边缘做轻柔滑入 + 微缩放 + 淡入 (纯 graphicsLayer 阶段读值, 零重组)。
 */
fun Modifier.paintingChromeEntrance(
    progress: State<Float>,
    edge: PanelEdge,
    distance: Dp = 26.dp,
    initialScale: Float = 0.94f,
): Modifier = graphicsLayer {
    val p = progress.value.coerceIn(0f, 1f)
    alpha = p
    if (p < 0.999f) {
        val inv = 1f - p
        val offsetPx = distance.toPx() * inv
        when (edge) {
            PanelEdge.TOP -> translationY = -offsetPx
            PanelEdge.BOTTOM -> translationY = offsetPx
            PanelEdge.LEFT -> translationX = -offsetPx
            PanelEdge.RIGHT -> translationX = offsetPx
        }
        val s = initialScale + (1f - initialScale) * p
        scaleX = s
        scaleY = s
    }
}
