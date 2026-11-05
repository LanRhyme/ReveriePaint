/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.dialog

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.reverie.paint.R
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.ui.components.ReTextButton
import com.reverie.paint.ui.components.liquidHighlight
import com.reverie.paint.ui.components.pressGrow
import com.reverie.paint.ui.components.pressScale
import com.reverie.paint.ui.theme.Theme
import kotlinx.coroutines.launch

/**
 * 风格化画室纪念通票与来信组件 (Atelier Commemorative Pass & Letter Overlay)
 * 不依赖系统 Dialog 组件，作为顶级页面叠加层渲染，具备实体票券剪口与手账质感
 */
@Composable
fun SponsorPromptDialog(
    vm: PaintViewModel,
    onViewSponsorList: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = Theme.current
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()

    val launchCount = vm.launchCount
    val projectCount = maxOf(vm.createdCanvasCount, vm.exportCount, vm.projects.size)

    val primaryInteraction = remember { MutableInteractionSource() }
    val secondaryInteraction = remember { MutableInteractionSource() }
    val cardInteraction = remember { MutableInteractionSource() }

    var isExiting by remember { mutableStateOf(false) }
    val animProgress = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        animProgress.animateTo(
            targetValue = 1f,
            animationSpec = spring(
                dampingRatio = 0.82f,
                stiffness = 180f,
            ),
        )
    }

    val animateDismiss: (afterDismiss: (() -> Unit)?) -> Unit = { callback ->
        if (!isExiting) {
            isExiting = true
            scope.launch {
                animProgress.animateTo(
                    targetValue = 0f,
                    animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing),
                )
                onDismiss()
                callback?.invoke()
            }
        }
    }

    BackHandler(enabled = true) {
        vm.postponeSponsorPrompt()
        Toast.makeText(context, R.string.sponsor_prompt_toast_postponed, Toast.LENGTH_SHORT).show()
        animateDismiss(null)
    }

    val scrimColor = Color(0xCC0C0D10)
    val visibleBorder = if (colors.border != Color.Transparent) colors.border else colors.subText.copy(alpha = 0.22f)

    val p = animProgress.value.coerceIn(0f, 1f)
    val cardScale = 0.90f + 0.10f * p
    val cardAlpha = p
    val cardOffsetY = 36.dp * (1f - p)
    val cardRotationZ = (1f - p) * -1.8f

    Box(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(100f)
            .graphicsLayer { this.alpha = p }
            .background(scrimColor)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                        event.changes.forEach { it.consume() }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        // Ambient radial light wash
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            colors.accent.copy(alpha = 0.22f),
                            Color.Transparent,
                        ),
                        radius = 900f,
                    )
                )
        )

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .widthIn(max = 660.dp)
                .graphicsLayer {
                    this.scaleX = cardScale
                    this.scaleY = cardScale
                    this.alpha = cardAlpha
                    this.translationY = cardOffsetY.toPx()
                    this.rotationZ = cardRotationZ
                }
                .shadow(
                    elevation = 28.dp,
                    shape = RoundedCornerShape(24.dp),
                    ambientColor = Color.Black.copy(alpha = 0.55f),
                    spotColor = Color.Black.copy(alpha = 0.65f),
                )
                .clip(RoundedCornerShape(24.dp))
                .background(colors.panelHi)
                .border(1.dp, visibleBorder, RoundedCornerShape(24.dp))
                .clickable(
                    interactionSource = cardInteraction,
                    indication = null,
                    onClick = { /* Intercept click on card */ },
                ),
        ) {
            val isWide = maxWidth >= 540.dp

            if (isWide) {
                // Wide Landscape Tablet: Asymmetric Ticket Pass (Left Stub + Perforation + Right Letter)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min),
                ) {
                    // 1. Left Stub: Creator's Milestones & Studio Seal
                    Column(
                        modifier = Modifier
                            .width(190.dp)
                            .fillMaxHeight()
                            .background(colors.panel)
                            .padding(horizontal = 16.dp, vertical = 20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.SpaceBetween,
                    ) {
                        // Top Seal
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(42.dp)
                                    .clip(CircleShape)
                                    .background(colors.accent.copy(alpha = 0.16f))
                                    .border(1.2.dp, colors.accent.copy(alpha = 0.45f), CircleShape),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_heart),
                                    contentDescription = null,
                                    tint = colors.accent,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                            Text(
                                text = "REVERIE PASS",
                                color = colors.accent,
                                fontSize = 8.5.sp,
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = 1.4.sp,
                            )
                        }

                        // Middle Milestones
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = "$launchCount",
                                    color = colors.text,
                                    fontSize = 24.sp,
                                    fontWeight = FontWeight.Black,
                                    lineHeight = 26.sp,
                                )
                                Text(
                                    text = stringResource(R.string.sponsor_prompt_milestone_launch_desc),
                                    color = colors.subText,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                            }

                            Box(
                                modifier = Modifier
                                    .width(28.dp)
                                    .height(1.dp)
                                    .background(visibleBorder)
                            )

                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = "$projectCount",
                                    color = colors.accent,
                                    fontSize = 24.sp,
                                    fontWeight = FontWeight.Black,
                                    lineHeight = 26.sp,
                                )
                                Text(
                                    text = stringResource(R.string.sponsor_prompt_milestone_canvas_desc),
                                    color = colors.subText,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                            }
                        }

                        // Bottom Stamp
                        Box(
                            modifier = Modifier
                                .graphicsLayer(rotationZ = -3f)
                                .clip(RoundedCornerShape(6.dp))
                                .background(colors.panelHi.copy(alpha = 0.85f))
                                .border(1.dp, colors.accent.copy(alpha = 0.35f), RoundedCornerShape(6.dp))
                                .padding(horizontal = 7.dp, vertical = 3.5.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp),
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_reverie_logo),
                                    contentDescription = null,
                                    tint = colors.accent,
                                    modifier = Modifier.size(10.dp),
                                )
                                Text(
                                    text = "CREATOR",
                                    color = colors.accent,
                                    fontSize = 7.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 0.6.sp,
                                )
                            }
                        }
                    }

                    // 2. Ticket Notch Divider (Semicircular cutouts + Perforated line)
                    TicketVerticalNotchDivider(
                        scrimColor = scrimColor,
                        borderColor = visibleBorder,
                    )

                    // 3. Right Section: Studio Letter & Interactive Actions
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 6.dp, end = 20.dp, top = 20.dp, bottom = 18.dp),
                        verticalArrangement = Arrangement.spacedBy(13.dp),
                    ) {
                        // Header: Title & Subtitle
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = stringResource(R.string.sponsor_prompt_title),
                                color = colors.text,
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = stringResource(R.string.sponsor_prompt_subtitle),
                                color = colors.subText,
                                fontSize = 11.5.sp,
                            )
                        }

                        // Sincere Letter Card
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(colors.panel.copy(alpha = 0.60f))
                                .border(1.dp, colors.border.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.Top,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .width(3.dp)
                                        .height(44.dp)
                                        .clip(RoundedCornerShape(2.dp))
                                        .background(colors.accent.copy(alpha = 0.55f))
                                )
                                Text(
                                    text = stringResource(R.string.sponsor_prompt_body),
                                    color = colors.subText,
                                    fontSize = 12.sp,
                                    lineHeight = 18.5.sp,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }

                        // Action Buttons: Coffee CTA + Patron Wall
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // Primary CTA: Coffee / Afdian
                            Box(
                                modifier = Modifier
                                    .weight(1.35f)
                                    .height(42.dp)
                                    .pressGrow(primaryInteraction, growFraction = 0.03f)
                                    .clip(RoundedCornerShape(13.dp))
                                    .liquidHighlight(primaryInteraction, Color.White, radius = 40.dp)
                                    .background(colors.accent)
                                    .clickable(interactionSource = primaryInteraction, indication = null) {
                                        try {
                                            uriHandler.openUri("https://afdian.com/a/LanRhyme")
                                        } catch (_: Exception) {}
                                        vm.suppressSponsorPrompt()
                                        Toast.makeText(context, R.string.sponsor_prompt_toast_suppressed, Toast.LENGTH_SHORT).show()
                                        animateDismiss(null)
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_heart),
                                        contentDescription = null,
                                        tint = colors.onAccent,
                                        modifier = Modifier.size(16.dp),
                                    )
                                    Text(
                                        text = stringResource(R.string.sponsor_prompt_action_afdian),
                                        color = colors.onAccent,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                            }

                            // Secondary Button: Sponsor Wall
                            Box(
                                modifier = Modifier
                                    .weight(1.0f)
                                    .height(42.dp)
                                    .pressScale(secondaryInteraction, pressedScale = 0.97f)
                                    .clip(RoundedCornerShape(13.dp))
                                    .liquidHighlight(secondaryInteraction, Color.White, radius = 40.dp)
                                    .background(colors.panel)
                                    .border(1.dp, colors.border.copy(alpha = 0.5f), RoundedCornerShape(13.dp))
                                    .clickable(interactionSource = secondaryInteraction, indication = null) {
                                        animateDismiss { onViewSponsorList() }
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_star_filled),
                                        contentDescription = null,
                                        tint = colors.accent,
                                        modifier = Modifier.size(14.dp),
                                    )
                                    Text(
                                        text = stringResource(R.string.sponsor_prompt_action_view_list),
                                        color = colors.text,
                                        fontSize = 12.5.sp,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                }
                            }
                        }

                        // Dismiss options
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            ReTextButton(
                                text = stringResource(R.string.sponsor_prompt_next_time),
                                textColor = colors.subText,
                                fontSize = 11.5.sp,
                                onClick = {
                                    vm.postponeSponsorPrompt()
                                    Toast.makeText(context, R.string.sponsor_prompt_toast_postponed, Toast.LENGTH_SHORT).show()
                                    animateDismiss(null)
                                },
                            )

                            ReTextButton(
                                text = stringResource(R.string.sponsor_prompt_never),
                                textColor = colors.subText.copy(alpha = 0.75f),
                                fontSize = 11.5.sp,
                                onClick = {
                                    vm.suppressSponsorPrompt()
                                    Toast.makeText(context, R.string.sponsor_prompt_toast_suppressed, Toast.LENGTH_SHORT).show()
                                    animateDismiss(null)
                                },
                            )
                        }
                    }
                }
            } else {
                // Compact / Portrait layout (Top Header Stub + Bottom Letter)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    // Header: Icon + Title & Subtitle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(colors.accent.copy(alpha = 0.16f))
                                .border(1.2.dp, colors.accent.copy(alpha = 0.45f), CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_heart),
                                contentDescription = null,
                                tint = colors.accent,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        Column {
                            Text(
                                text = stringResource(R.string.sponsor_prompt_title),
                                color = colors.text,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                text = stringResource(R.string.sponsor_prompt_subtitle),
                                color = colors.subText,
                                fontSize = 11.sp,
                            )
                        }
                    }

                    // Compact Milestone Badges
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(colors.panel)
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_clock),
                                contentDescription = null,
                                tint = colors.accent,
                                modifier = Modifier.size(16.dp),
                            )
                            Column {
                                Text(
                                    text = "$launchCount",
                                    color = colors.text,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                                Text(
                                    text = stringResource(R.string.sponsor_prompt_milestone_launch_desc),
                                    color = colors.subText,
                                    fontSize = 10.5.sp,
                                )
                            }
                        }

                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(colors.panel)
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_brush),
                                contentDescription = null,
                                tint = colors.accent,
                                modifier = Modifier.size(16.dp),
                            )
                            Column {
                                Text(
                                    text = "$projectCount",
                                    color = colors.accent,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                                Text(
                                    text = stringResource(R.string.sponsor_prompt_milestone_canvas_desc),
                                    color = colors.subText,
                                    fontSize = 10.5.sp,
                                )
                            }
                        }
                    }

                    // Letter Body
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(colors.panel.copy(alpha = 0.6f))
                            .padding(12.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.sponsor_prompt_body),
                            color = colors.subText,
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                        )
                    }

                    // Action Buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(1.3f)
                                .height(42.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(colors.accent)
                                .clickable {
                                    try {
                                        uriHandler.openUri("https://afdian.com/a/LanRhyme")
                                    } catch (_: Exception) {}
                                    vm.suppressSponsorPrompt()
                                    Toast.makeText(context, R.string.sponsor_prompt_toast_suppressed, Toast.LENGTH_SHORT).show()
                                    animateDismiss(null)
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = stringResource(R.string.sponsor_prompt_action_afdian),
                                color = colors.onAccent,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }

                        Box(
                            modifier = Modifier
                                .weight(1.0f)
                                .height(42.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(colors.panel)
                                .clickable {
                                    animateDismiss { onViewSponsorList() }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = stringResource(R.string.sponsor_prompt_action_view_list),
                                color = colors.text,
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }

                    // Bottom Dismiss Links
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        ReTextButton(
                            text = stringResource(R.string.sponsor_prompt_next_time),
                            textColor = colors.subText,
                            fontSize = 11.5.sp,
                            onClick = {
                                vm.postponeSponsorPrompt()
                                Toast.makeText(context, R.string.sponsor_prompt_toast_postponed, Toast.LENGTH_SHORT).show()
                                animateDismiss(null)
                            },
                        )

                        ReTextButton(
                            text = stringResource(R.string.sponsor_prompt_never),
                            textColor = colors.subText.copy(alpha = 0.75f),
                            fontSize = 11.5.sp,
                            onClick = {
                                vm.suppressSponsorPrompt()
                                Toast.makeText(context, R.string.sponsor_prompt_toast_suppressed, Toast.LENGTH_SHORT).show()
                                animateDismiss(null)
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * 竖向票券剪口与齿孔撕线 (Ticket Notch Divider)
 */
@Composable
private fun TicketVerticalNotchDivider(
    scrimColor: Color,
    borderColor: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(
        modifier = modifier
            .width(18.dp)
            .fillMaxHeight(),
    ) {
        val r = 9.dp.toPx()
        val centerX = size.width / 2f

        // 1. Top semicircle cutout (cut into ticket from outside)
        drawCircle(
            color = scrimColor,
            radius = r,
            center = Offset(centerX, 0f),
        )
        drawArc(
            color = borderColor,
            startAngle = 0f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(centerX - r, -r),
            size = Size(r * 2, r * 2),
            style = Stroke(width = 1.dp.toPx()),
        )

        // 2. Bottom semicircle cutout
        drawCircle(
            color = scrimColor,
            radius = r,
            center = Offset(centerX, size.height),
        )
        drawArc(
            color = borderColor,
            startAngle = 180f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(centerX - r, size.height - r),
            size = Size(r * 2, r * 2),
            style = Stroke(width = 1.dp.toPx()),
        )

        // 3. Dashed vertical tear line
        drawLine(
            color = borderColor,
            start = Offset(centerX, r + 4.dp.toPx()),
            end = Offset(centerX, size.height - r - 4.dp.toPx()),
            strokeWidth = 1.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 6.dp.toPx()), 0f),
        )
    }
}
