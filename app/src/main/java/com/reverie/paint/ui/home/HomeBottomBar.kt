/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.home

import android.view.HapticFeedbackConstants
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reverie.paint.R
import com.reverie.paint.core.*
import com.reverie.paint.ui.components.liquidHighlight
import com.reverie.paint.ui.components.pressScale
import com.reverie.paint.ui.theme.AppColors
import com.reverie.paint.ui.theme.Glass
import com.reverie.paint.ui.theme.glassBorder
import dev.chrisbanes.haze.HazeState
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
    val view = LocalView.current

    val createSource = remember { MutableInteractionSource() }

    // 滑行 pill：指示背景在两个槽位之间弹簧滑行
    var gallerySlot by remember { mutableStateOf(Rect.Zero) }
    var settingsSlot by remember { mutableStateOf(Rect.Zero) }
    val targetSlot = if (isGallery) gallerySlot else settingsSlot
    val ready = targetSlot != Rect.Zero && gallerySlot != Rect.Zero && settingsSlot != Rect.Zero

    // 弹簧物理对齐 dsh-Go AppMotion.thumbSpring (320ms, bounce 0.15 -> dampingRatio ≈ 0.82, stiffness ≈ 380)
    val thumbSpring = remember { spring<Float>(dampingRatio = 0.82f, stiffness = 380f) }
    val pillLeft by animateFloatAsState(if (ready) targetSlot.left else 0f, thumbSpring, label = "pillX")
    val pillWidth by animateFloatAsState(if (ready) targetSlot.width else 0f, thumbSpring, label = "pillW")
    val pillHeight by animateFloatAsState(if (ready) targetSlot.height else 0f, thumbSpring, label = "pillH")
    val pillTop by animateFloatAsState(if (ready) targetSlot.top else 0f, thumbSpring, label = "pillY")

    // 图标切换 pop 弹性回弹
    val galleryIconScale = remember { Animatable(1f) }
    val settingsIconScale = remember { Animatable(1f) }
    LaunchedEffect(isGallery) {
        if (isGallery) {
            galleryIconScale.snapTo(0.82f)
            galleryIconScale.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 420f))
        }
    }
    LaunchedEffect(isSettings) {
        if (isSettings) {
            settingsIconScale.snapTo(0.82f)
            settingsIconScale.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 420f))
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
                    spotColor = colors.accent.copy(alpha = 0.28f),
                    ambientColor = Color.Black.copy(alpha = 0.20f),
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
                .padding(horizontal = 10.dp, vertical = 6.dp)
                .drawBehind {
                    // 滑行 pill 背景（画在内容之下，坐标系与 tab 槽位一致）
                    if (pillWidth > 1f) {
                        val pillRadius = CornerRadius(22.dp.toPx())
                        drawRoundRect(
                            color = colors.panelHi.copy(alpha = 0.88f),
                            topLeft = Offset(pillLeft, pillTop),
                            size = Size(pillWidth, pillHeight),
                            cornerRadius = pillRadius,
                        )
                        // 发丝高光微描边
                        drawRoundRect(
                            color = Color.White.copy(alpha = 0.12f),
                            topLeft = Offset(pillLeft, pillTop),
                            size = Size(pillWidth, pillHeight),
                            cornerRadius = pillRadius,
                            style = Stroke(width = 1.dp.toPx()),
                        )
                    }
                },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Gallery Tab
            val gallerySource = remember { MutableInteractionSource() }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .pressScale(gallerySource, pressedScale = 0.95f)
                    .clip(RoundedCornerShape(22.dp))
                    .liquidHighlight(gallerySource, Color.White, radius = 26.dp)
                    .clickable(interactionSource = gallerySource, indication = null) {
                        if (vm.homeSelectedTab != 0) {
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            vm.homeSelectedTab = 0
                        }
                    }
                    .onGloballyPositioned { gallerySlot = Rect(it.positionInParent(), Size(it.size.width.toFloat(), it.size.height.toFloat())) }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_brush),
                    contentDescription = stringResource(R.string.gallery_title),
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
                            stringResource(R.string.gallery_title),
                            color = colors.text,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }

            // Create Action Button (莫兰迪强调色光感按钮，居中对称)
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .shadow(
                        elevation = 10.dp,
                        shape = CircleShape,
                        spotColor = colors.accent.copy(alpha = 0.40f),
                        ambientColor = Color.Black.copy(alpha = 0.18f),
                    )
                    .clip(CircleShape)
                    .background(colors.accent)
                    .drawBehind {
                        // 顶部发丝高光弧
                        drawCircle(
                            brush = Brush.verticalGradient(
                                colors = listOf(
                                    Color.White.copy(alpha = 0.35f),
                                    Color.Transparent,
                                ),
                            ),
                            style = Stroke(width = 1.dp.toPx()),
                        )
                    }
                    .pressScale(createSource, pressedScale = 0.92f)
                    .liquidHighlight(createSource, Color.White, radius = 30.dp)
                    .clickable(interactionSource = createSource, indication = null) {
                        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        vm.goCreate()
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painterResource(R.drawable.ic_plus),
                    contentDescription = stringResource(R.string.home_new_canvas),
                    tint = colors.onAccent,
                    modifier = Modifier.size(28.dp),
                )
            }

            // Settings Tab
            val settingsSource = remember { MutableInteractionSource() }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .pressScale(settingsSource, pressedScale = 0.95f)
                    .clip(RoundedCornerShape(22.dp))
                    .liquidHighlight(settingsSource, Color.White, radius = 26.dp)
                    .clickable(interactionSource = settingsSource, indication = null) {
                        if (vm.homeSelectedTab != 1) {
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            vm.homeSelectedTab = 1
                        }
                    }
                    .onGloballyPositioned { settingsSlot = Rect(it.positionInParent(), Size(it.size.width.toFloat(), it.size.height.toFloat())) }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Icon(
                    painterResource(R.drawable.ic_settings),
                    contentDescription = stringResource(R.string.settings_title),
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
                            stringResource(R.string.settings_title),
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
