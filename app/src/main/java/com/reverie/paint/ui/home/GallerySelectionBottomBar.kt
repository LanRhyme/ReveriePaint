/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reverie.paint.R
import com.reverie.paint.ui.components.liquidHighlight
import com.reverie.paint.ui.components.liquidJelly
import com.reverie.paint.ui.components.pressScale
import com.reverie.paint.ui.theme.AppColors
import com.reverie.paint.ui.theme.Glass
import com.reverie.paint.ui.theme.glassBorder
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeChild

@Composable
internal fun GallerySelectionBottomBar(
    colors: AppColors,
    selectedCount: Int,
    canShareOrDuplicate: Boolean,
    hazeState: HazeState? = null,
    onShare: () -> Unit,
    onDuplicate: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(32.dp)
    val hasSelection = selectedCount > 0
    val canShare = hasSelection && canShareOrDuplicate
    val canDuplicate = hasSelection && canShareOrDuplicate
    val canMove = hasSelection
    val canDelete = hasSelection

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .liquidJelly(maxOffset = 10.dp)
                .shadow(
                    elevation = 16.dp,
                    shape = shape,
                    spotColor = colors.accent.copy(alpha = 0.35f),
                    ambientColor = Color.Black.copy(alpha = 0.25f),
                )
                .clip(shape)
                .then(
                    if (hazeState != null) {
                        Modifier.hazeChild(
                            state = hazeState,
                            style = Glass.barStyle(0.90f),
                        )
                    } else {
                        Modifier.background(colors.panel.copy(alpha = 0.90f))
                    }
                )
                .glassBorder(shape)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            SelectionActionButton(
                iconRes = R.drawable.ic_share,
                label = stringResource(R.string.common_share),
                colors = colors,
                enabled = canShare,
                onClick = onShare,
            )
            ActionDivider(colors)
            SelectionActionButton(
                iconRes = R.drawable.ic_copy,
                label = stringResource(R.string.common_copy),
                colors = colors,
                enabled = canDuplicate,
                onClick = onDuplicate,
            )
            ActionDivider(colors)
            SelectionActionButton(
                iconRes = R.drawable.ic_folder_symlink,
                label = stringResource(R.string.common_move),
                colors = colors,
                enabled = canMove,
                onClick = onMove,
            )
            ActionDivider(colors)
            SelectionActionButton(
                iconRes = R.drawable.ic_trash,
                label = stringResource(R.string.common_delete),
                colors = colors,
                tint = Color(0xFFFF5252),
                enabled = canDelete,
                onClick = onDelete,
            )
        }
    }
}

@Composable
private fun SelectionActionButton(
    iconRes: Int,
    label: String,
    colors: AppColors,
    enabled: Boolean,
    onClick: () -> Unit,
    tint: Color = colors.accent,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val contentColor by animateColorAsState(
        targetValue = if (enabled) tint else colors.subText.copy(alpha = 0.38f),
        animationSpec = tween(180),
        label = "ActionBtnColor",
    )
    val haptic = LocalHapticFeedback.current

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = modifier
            .pressScale(interactionSource, pressedScale = if (enabled) 0.92f else 1.0f)
            .clip(RoundedCornerShape(20.dp))
            .then(
                if (enabled) {
                    Modifier
                        .liquidHighlight(interactionSource, tint.copy(alpha = 0.25f), radius = 24.dp)
                        .clickable(
                            interactionSource = interactionSource,
                            indication = null,
                        ) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onClick()
                        }
                } else Modifier
            )
            .padding(horizontal = 12.dp, vertical = 9.dp),
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = label,
            tint = contentColor,
            modifier = Modifier.size(17.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            color = contentColor,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun ActionDivider(colors: AppColors) {
    Box(
        modifier = Modifier
            .width(1.dp)
            .height(16.dp)
            .background(colors.border.copy(alpha = 0.5f))
    )
}

@Composable
internal fun CardSelectionBadge(
    isSelectMode: Boolean,
    isSelected: Boolean,
    colors: AppColors,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = isSelectMode,
        enter = scaleIn(spring(dampingRatio = 0.7f, stiffness = 400f)) + fadeIn(tween(160)),
        exit = scaleOut(tween(120)) + fadeOut(tween(120)),
        modifier = modifier,
    ) {
        val bgColor by animateColorAsState(
            targetValue = if (isSelected) colors.accent else Color.Black.copy(alpha = 0.45f),
            animationSpec = tween(180),
            label = "BadgeBgColor",
        )
        val badgeScale by animateFloatAsState(
            targetValue = if (isSelected) 1.08f else 1.0f,
            animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow),
            label = "BadgeScale",
        )

        Box(
            modifier = Modifier
                .scale(badgeScale)
                .size(24.dp)
                .shadow(4.dp, CircleShape)
                .clip(CircleShape)
                .background(bgColor)
                .border(1.5.dp, Color.White.copy(alpha = 0.9f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            AnimatedVisibility(
                visible = isSelected,
                enter = scaleIn(spring(dampingRatio = 0.65f, stiffness = 500f)) + fadeIn(tween(120)),
                exit = scaleOut(tween(100)) + fadeOut(tween(100)),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_check),
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(15.dp),
                )
            }
        }
    }
}
