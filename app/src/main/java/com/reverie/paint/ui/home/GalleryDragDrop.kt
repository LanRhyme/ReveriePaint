/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateOffsetAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reverie.paint.R
import com.reverie.paint.model.Project
import com.reverie.paint.model.UiAnimationSpeed
import com.reverie.paint.ui.theme.AppColors
import com.reverie.paint.ui.theme.Motion
import com.reverie.paint.ui.theme.blendColors
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * 拖拽浮动代理卡片。
 *
 * 支持单图拖拽与多选批量拖拽:
 * 当拖动多张卡片 ([dragCount] > 1) 时，底层以物理错峰散开额外 2 层卡片呈现拟物纸张堆叠质感，
 * 右上角展示聚合数量角标。
 */
@Composable
internal fun FloatingDragProxy(
    project: Project,
    dragTouchRootPos: Offset,
    dragCount: Int,
    colors: AppColors,
    modifier: Modifier = Modifier,
) {
    val thumb = rememberThumbnail(project.previewPath, project.lastModified)
    val dragCardSize = 136.dp
    val density = LocalDensity.current
    val halfSizePx = with(density) { (dragCardSize / 2).toPx() }
    val isDark = colors.isDarkTheme()
    val proxyShape = RoundedCornerShape(12.dp)
    val hairlineBrush = remember(isDark) { artboardHairlineBrush(colors) }
    val aspectRatio = remember(project.width, project.height) {
        if (project.width > 0 && project.height > 0) {
            (project.width.toFloat() / project.height.toFloat()).coerceIn(0.65f, 1.55f)
        } else {
            1.0f
        }
    }

    // 拾起的一瞬间物理弹出 (遵循动效速度设置)
    val dragLift = remember(project.filePath) {
        Animatable(if (Motion.currentSpeed == UiAnimationSpeed.OFF) 1f else 0.82f)
    }
    LaunchedEffect(project.filePath) {
        if (Motion.currentSpeed == UiAnimationSpeed.OFF) {
            dragLift.snapTo(1f)
        } else {
            dragLift.animateTo(1f, Motion.snapBouncy)
        }
    }

    // 阻尼弹簧惯性跟随: 赋予代理卡片真实物理质量感 (动效关闭时瞬时跟随)
    val smoothTouchPos by animateOffsetAsState(
        targetValue = dragTouchRootPos,
        animationSpec = if (Motion.currentSpeed == UiAnimationSpeed.OFF) {
            snap()
        } else {
            spring(dampingRatio = 0.88f, stiffness = 1400f)
        },
        label = "DragProxyFollowPos",
    )

    // 拖拽速度响应惯性倾角: 水平移动时向运动反方向微倾, 停手后回正到静息微倾角 (-2.5°)
    var prevPos by remember(project.filePath) { mutableStateOf(dragTouchRootPos) }
    var targetTilt by remember(project.filePath) { mutableFloatStateOf(-2.5f) }
    LaunchedEffect(dragTouchRootPos) {
        if (Motion.currentSpeed == UiAnimationSpeed.OFF) {
            prevPos = dragTouchRootPos
            targetTilt = -2.5f
            return@LaunchedEffect
        }
        val dx = dragTouchRootPos.x - prevPos.x
        prevPos = dragTouchRootPos
        targetTilt = (-2.5f + (dx * 0.28f).coerceIn(-8.5f, 8.5f))
        delay(90)
        targetTilt = -2.5f
    }
    val tiltAngle by animateFloatAsState(
        targetValue = if (Motion.currentSpeed == UiAnimationSpeed.OFF) -2.5f else targetTilt,
        animationSpec = if (Motion.currentSpeed == UiAnimationSpeed.OFF) {
            snap()
        } else {
            spring(dampingRatio = 0.62f, stiffness = 280f)
        },
        label = "DragProxyTilt",
    )

    val backLayer2Color = if (isDark) blendColors(colors.panelHi, colors.bg, 0.35f) else blendColors(colors.panel, Color.Black, 0.05f)
    val backLayer1Color = if (isDark) colors.panelHi else colors.panel
    val frontBackdrop = if (thumb != null) {
        if (isDark) colors.panelHi else Color.White
    } else {
        colors.panelHi
    }

    Box(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .offset {
                    IntOffset(
                        (smoothTouchPos.x - halfSizePx).roundToInt(),
                        (smoothTouchPos.y - halfSizePx).roundToInt(),
                    )
                }
                .size(dragCardSize),
            contentAlignment = Alignment.Center,
        ) {
            // 多选批量拖拽时的底层堆叠效果 (第 2 层)
            if (dragCount > 2) {
                Box(
                    modifier = Modifier
                        .fillMaxSize(0.92f)
                        .aspectRatio(aspectRatio, matchHeightConstraintsFirst = aspectRatio < 1.0f)
                        .graphicsLayer {
                            rotationZ = 6f
                            translationX = 7.dp.toPx()
                            translationY = 5.dp.toPx()
                            alpha = 0.88f * dragLift.value
                        }
                        .shadow(8.dp, proxyShape, clip = false)
                        .clip(proxyShape)
                        .background(backLayer2Color)
                        .border(0.75.dp, hairlineBrush, proxyShape),
                )
            }

            // 多选批量拖拽时的底层堆叠效果 (第 1 层)
            if (dragCount > 1) {
                Box(
                    modifier = Modifier
                        .fillMaxSize(0.92f)
                        .aspectRatio(aspectRatio, matchHeightConstraintsFirst = aspectRatio < 1.0f)
                        .graphicsLayer {
                            rotationZ = -7f
                            translationX = (-6).dp.toPx()
                            translationY = 4.dp.toPx()
                            alpha = 0.92f * dragLift.value
                        }
                        .shadow(12.dp, proxyShape, clip = false)
                        .clip(proxyShape)
                        .background(backLayer1Color)
                        .border(0.75.dp, hairlineBrush, proxyShape),
                )
            }

            // 顶层主卡片
            Box(
                modifier = Modifier
                    .fillMaxSize(0.94f)
                    .aspectRatio(aspectRatio, matchHeightConstraintsFirst = aspectRatio < 1.0f)
                    .graphicsLayer {
                        rotationZ = tiltAngle
                        alpha = 0.95f
                        val liftedScale = dragLift.value * 1.06f
                        scaleX = liftedScale
                        scaleY = liftedScale
                    }
                    .shadow(
                        elevation = if (isDark) 18.dp else 14.dp,
                        shape = proxyShape,
                        spotColor = Color.Black.copy(alpha = if (isDark) 0.50f else 0.22f),
                        clip = false,
                    )
                    .clip(proxyShape)
                    .background(frontBackdrop)
                    .border(0.75.dp, hairlineBrush, proxyShape),
                contentAlignment = Alignment.Center,
            ) {
                if (thumb != null) {
                    Image(
                        bitmap = thumb.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Icon(
                        painter = painterResource(R.drawable.ic_canvas_tab),
                        contentDescription = null,
                        tint = colors.subText.copy(alpha = 0.45f),
                        modifier = Modifier.size(30.dp),
                    )
                }

                if (dragCount > 1) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .shadow(4.dp, CircleShape, clip = false)
                            .clip(CircleShape)
                            .background(colors.accent)
                            .border(1.dp, Color.White.copy(alpha = 0.28f), CircleShape)
                            .size(24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "$dragCount",
                            color = colors.onAccent,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
    }
}
