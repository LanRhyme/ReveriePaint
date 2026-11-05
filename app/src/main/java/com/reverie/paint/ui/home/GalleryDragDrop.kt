/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reverie.paint.model.Project
import com.reverie.paint.ui.theme.AppColors
import com.reverie.paint.ui.theme.Motion
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
    val dragCardSize = 130.dp
    val density = LocalDensity.current
    val halfSizePx = with(density) { (dragCardSize / 2).toPx() }

    // 拾起的一瞬间物理弹出
    val dragLift = remember(project.filePath) { Animatable(0.82f) }
    LaunchedEffect(project.filePath) {
        dragLift.animateTo(1f, Motion.snapBouncy)
    }

    Box(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .offset {
                    IntOffset(
                        (dragTouchRootPos.x - halfSizePx).roundToInt(),
                        (dragTouchRootPos.y - halfSizePx).roundToInt(),
                    )
                }
                .size(dragCardSize),
            contentAlignment = Alignment.Center,
        ) {
            // 多选批量拖拽时的底层堆叠效果 (第 2 层)
            if (dragCount > 2) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            rotationZ = 6f
                            translationX = 7.dp.toPx()
                            translationY = 5.dp.toPx()
                            alpha = 0.85f * dragLift.value
                        }
                        .shadow(8.dp, RoundedCornerShape(12.dp), clip = false)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFFE8E8E8))
                        .border(1.dp, colors.border.copy(alpha = 0.6f), RoundedCornerShape(12.dp)),
                )
            }

            // 多选批量拖拽时的底层堆叠效果 (第 1 层)
            if (dragCount > 1) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            rotationZ = -7f
                            translationX = (-6).dp.toPx()
                            translationY = 4.dp.toPx()
                            alpha = 0.90f * dragLift.value
                        }
                        .shadow(12.dp, RoundedCornerShape(12.dp), clip = false)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFFF2F2F2))
                        .border(1.dp, colors.border.copy(alpha = 0.8f), RoundedCornerShape(12.dp)),
                )
            }

            // 顶层主卡片
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .shadow(16.dp, RoundedCornerShape(12.dp), clip = false)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White)
                    .border(1.5.dp, colors.border, RoundedCornerShape(12.dp))
                    .graphicsLayer {
                        rotationZ = -3.5f
                        alpha = 0.94f
                        scaleX = dragLift.value
                        scaleY = dragLift.value
                    },
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
                    Box(modifier = Modifier.fillMaxSize().background(Color(0xFFEEEEF0)))
                }

                if (dragCount > 1) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .shadow(3.dp, CircleShape)
                            .clip(CircleShape)
                            .background(colors.panel.copy(alpha = 0.95f))
                            .border(1.dp, colors.border, CircleShape)
                            .size(24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "$dragCount",
                            color = colors.text,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
    }
}
