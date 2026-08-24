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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import com.reverie.paint.R
import com.reverie.paint.core.*
import com.reverie.paint.ui.theme.AppColors
import com.reverie.paint.ui.theme.Motion
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeChild
import com.reverie.paint.ui.components.liquidHighlight
import com.reverie.paint.ui.components.liquidLean
import com.reverie.paint.ui.components.pressScale
import com.reverie.paint.ui.theme.Glass
import com.reverie.paint.ui.theme.glassBorder

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

    // iOS 式 tab 选中：pill 背景 spring 缩放浮现 + 图标 pop 回弹
    val galleryPill by animateFloatAsState(if (isGallery) 1f else 0f, Motion.springSnap, label = "tabGalleryPill")
    val settingsPill by animateFloatAsState(if (isSettings) 1f else 0f, Motion.springSnap, label = "tabSettingsPill")
    val galleryIconScale = remember { Animatable(1f) }
    val settingsIconScale = remember { Animatable(1f) }
    LaunchedEffect(isGallery) {
        if (isGallery) {
            galleryIconScale.snapTo(0.75f)
            galleryIconScale.animateTo(1f, Motion.snapBouncy)
        }
    }
    LaunchedEffect(isSettings) {
        if (isSettings) {
            settingsIconScale.snapTo(0.75f)
            settingsIconScale.animateTo(1f, Motion.snapBouncy)
        }
    }

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
                            style = Glass.barStyle(0.90f),
                        )
                    } else {
                        Modifier.background(colors.panel.copy(alpha = 0.90f))
                    }
                )
                .glassBorder(shape)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Gallery Tab Button with Animated Pill Container
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(22.dp))
                    .drawBehind {
                        if (galleryPill > 0.01f) withTransform({
                            scale(lerp(0.75f, 1f, galleryPill), lerp(0.6f, 1f, galleryPill), center)
                        }) {
                            drawRoundRect(
                                colors.panelHi.copy(alpha = 0.85f * galleryPill),
                                cornerRadius = CornerRadius(22.dp.toPx()),
                            )
                        }
                    }
                    .clickable { vm.homeSelectedTab = 0 }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_brush),
                    contentDescription = "画廊",
                    tint = if (isGallery) colors.accent else colors.subText,
                    modifier = Modifier.size(20.dp).scale(galleryIconScale.value),
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

            // Create Action Button (Liquid: 触点倾倒 + 按压缩放 + 高光跟随)
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .liquidLean(createSource, maxOffset = 6.dp)
                    .pressScale(createSource, pressedScale = 0.90f)
                    .clip(CircleShape)
                    .liquidHighlight(createSource, Color.White, radius = 30.dp)
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
                    .drawBehind {
                        if (settingsPill > 0.01f) withTransform({
                            scale(lerp(0.75f, 1f, settingsPill), lerp(0.6f, 1f, settingsPill), center)
                        }) {
                            drawRoundRect(
                                colors.panelHi.copy(alpha = 0.85f * settingsPill),
                                cornerRadius = CornerRadius(22.dp.toPx()),
                            )
                        }
                    }
                    .clickable { vm.homeSelectedTab = 1 }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Icon(
                    painterResource(R.drawable.ic_settings),
                    contentDescription = "设置",
                    tint = if (isSettings) colors.accent else colors.subText,
                    modifier = Modifier.size(20.dp).scale(settingsIconScale.value),
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
