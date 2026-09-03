/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.panels

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reverie.paint.R
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.core.cancelQuickShape
import com.reverie.paint.core.commitQuickShape
import com.reverie.paint.model.Point2D
import com.reverie.paint.model.QuickShapeType
import com.reverie.paint.ui.theme.Morandi
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeChild
import com.reverie.paint.ui.theme.Glass

/**
 * Procreate-style Top Capsule for QuickShape Editing
 */
@Composable
fun QuickShapeTopBar(
    vm: PaintViewModel,
    modifier: Modifier = Modifier,
    hazeState: HazeState? = null,
) {
    val shape = vm.activeQuickShape ?: return
    val capsuleShape = RoundedCornerShape(20.dp)

    AnimatedVisibility(
        visible = vm.isQuickShapeEditing,
        enter = fadeIn() + scaleIn(initialScale = 0.95f),
        exit = fadeOut() + scaleOut(targetScale = 0.95f),
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier
                .shadow(16.dp, capsuleShape, spotColor = Color.Black.copy(alpha = 0.25f))
                .clip(capsuleShape)
                .background(Morandi.panel.copy(alpha = vm.popupPanelOpacity))
                .then(
                    if (vm.blurBackground && hazeState != null) {
                        Modifier.hazeChild(state = hazeState, style = Glass.popupStyle(vm.popupPanelOpacity))
                    } else Modifier
                )
                .border(1.dp, Morandi.border, capsuleShape)
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_line),
                contentDescription = null,
                tint = Morandi.accent,
                modifier = Modifier.size(18.dp),
            )

            Text(
                text = "速创形状: ${shape.type.title}",
                color = Morandi.text,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            )

            Spacer(Modifier.width(4.dp))

            // Type switching buttons
            when (shape.type) {
                QuickShapeType.CIRCLE -> {
                    QuickShapePillChip(label = "转为椭圆", selected = false) {
                        val rx = shape.radiusX
                        val ry = rx * 0.7f
                        vm.activeQuickShape = shape.copy(type = QuickShapeType.ELLIPSE, radiusY = ry)
                    }
                }
                QuickShapeType.ELLIPSE -> {
                    QuickShapePillChip(label = "转为正圆", selected = false) {
                        val avgR = (shape.radiusX + shape.radiusY) / 2f
                        vm.activeQuickShape = shape.copy(type = QuickShapeType.CIRCLE, radiusX = avgR, radiusY = avgR)
                    }
                }
                QuickShapeType.RECTANGLE -> {
                    QuickShapePillChip(label = "转为正方", selected = false) {
                        val pts = shape.points
                        if (pts.size >= 4) {
                            val side = maxOf(pts[0].distanceTo(pts[1]), pts[1].distanceTo(pts[2]))
                            val c = shape.center
                            val half = side / 2f
                            val squarePts = listOf(
                                Point2D(c.x - half, c.y - half),
                                Point2D(c.x + half, c.y - half),
                                Point2D(c.x + half, c.y + half),
                                Point2D(c.x - half, c.y + half),
                            )
                            vm.activeQuickShape = shape.copy(points = squarePts)
                        }
                    }
                }
                QuickShapeType.LINE -> {
                    // Line already snapping
                }
                else -> Unit
            }

            Spacer(Modifier.width(6.dp))

            // Commit button
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(Morandi.accent)
                    .clickable { vm.commitQuickShape() }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painter = painterResource(R.drawable.ic_check),
                        contentDescription = "完成",
                        tint = Color.White,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("完成", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }

            // Cancel button
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(Morandi.border.copy(alpha = 0.5f))
                    .clickable { vm.cancelQuickShape() }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("取消", color = Morandi.subText, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun QuickShapePillChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) Morandi.accent.copy(alpha = 0.18f) else Morandi.border.copy(alpha = 0.35f))
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (selected) Morandi.accent else Morandi.text,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
        )
    }
}
