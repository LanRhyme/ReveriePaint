/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.home

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reverie.paint.R
import com.reverie.paint.core.*
import com.reverie.paint.ui.theme.AppColors
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeChild

@Composable
internal fun HomeBottomBar(
    colors: AppColors,
    vm: PaintViewModel,
    selectedTab: Int,
    hazeState: HazeState? = null,
    modifier: Modifier = Modifier,
) {
    val isGallery = selectedTab == 0
    val isSettings = selectedTab == 1

    val createSource = remember { MutableInteractionSource() }
    val isCreatePressed by createSource.collectIsPressedAsState()
    val createScale by animateFloatAsState(
        targetValue = if (isCreatePressed) 0.88f else 1.0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "CreateBtnScale",
    )

    val shape = RoundedCornerShape(32.dp)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
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
                            style = HazeStyle(
                                backgroundColor = colors.panel,
                                tint = HazeTint(colors.panel.copy(alpha = 0.75f)),
                                blurRadius = 24.dp,
                                noiseFactor = 0.05f,
                            ),
                        )
                    } else {
                        Modifier.background(colors.panel.copy(alpha = 0.90f))
                    }
                )
                .border(1.dp, colors.border.copy(alpha = 0.7f), shape)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Gallery Tab Button with Animated Pill Container
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(22.dp))
                    .background(if (isGallery) colors.panelHi.copy(alpha = 0.85f) else Color.Transparent)
                    .clickable { vm.homeSelectedTab = 0 }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_brush),
                    contentDescription = "画廊",
                    tint = if (isGallery) colors.accent else colors.subText,
                    modifier = Modifier.size(20.dp),
                )
                AnimatedVisibility(
                    visible = isGallery,
                    enter = fadeIn(tween(200)) + expandHorizontally(expandFrom = Alignment.Start),
                    exit = fadeOut(tween(150)) + shrinkHorizontally(shrinkTowards = Alignment.Start),
                ) {
                    Row {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "画廊",
                            color = colors.text,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }

            // Create Action Button (Pulsing / Press-responsive Accent Circle)
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .scale(createScale)
                    .clip(CircleShape)
                    .background(colors.accent)
                    .clickable(interactionSource = createSource, indication = null) { vm.goCreate() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painterResource(R.drawable.ic_plus),
                    contentDescription = "新建",
                    tint = colors.onAccent,
                    modifier = Modifier.size(28.dp),
                )
            }

            // Settings Tab Button with Animated Pill Container
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(22.dp))
                    .background(if (isSettings) colors.panelHi.copy(alpha = 0.85f) else Color.Transparent)
                    .clickable { vm.homeSelectedTab = 1 }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Icon(
                    painterResource(R.drawable.ic_settings),
                    contentDescription = "设置",
                    tint = if (isSettings) colors.accent else colors.subText,
                    modifier = Modifier.size(20.dp),
                )
                AnimatedVisibility(
                    visible = isSettings,
                    enter = fadeIn(tween(200)) + expandHorizontally(expandFrom = Alignment.Start),
                    exit = fadeOut(tween(150)) + shrinkHorizontally(shrinkTowards = Alignment.Start),
                ) {
                    Row {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "设置",
                            color = colors.text,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
    }
}
