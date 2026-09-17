/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.animation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.core.ShiftTraceGestureMode
import com.reverie.paint.core.ShiftTraceTarget
import com.reverie.paint.core.animationResetShiftTrace
import com.reverie.paint.core.animationToggleShiftTrace
import com.reverie.paint.ui.theme.Morandi

/**
 * 透光台位移对比 (Shift & Trace) 悬浮控制胶囊。
 *
 * 浮动于画布顶部区域，提供：
 * - [前帧 / 后帧] 参考目标切换
 * - [对齐参考帧 / 缩放平移画布] 手势作用目标切换
 * - [复位] 一键将当前参考帧偏移旋转归零
 * - [完成] 退出透光台对位模式
 */
@Composable
internal fun ShiftTraceCapsule(
    vm: PaintViewModel,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val shape = RoundedCornerShape(20.dp)

    AnimatedVisibility(
        visible = vm.anim.shiftTraceActive,
        enter = slideInVertically { -it } + fadeIn(),
        exit = slideOutVertically { -it } + fadeOut(),
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier
                .shadow(16.dp, shape, ambientColor = Color.Black.copy(alpha = 0.3f), spotColor = Color.Black.copy(alpha = 0.4f))
                .clip(shape)
                .background(Morandi.panelHi.copy(alpha = 0.94f))
                .border(
                    width = 1.dp,
                    brush = Brush.verticalGradient(
                        listOf(
                            Color.White.copy(alpha = 0.25f),
                            Color.White.copy(alpha = 0.06f),
                        ),
                    ),
                    shape = shape,
                )
                .padding(horizontal = 6.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // 前帧 / 后帧切换
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(Morandi.panel)
                    .padding(2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                val isPrev = vm.anim.shiftTraceTarget == ShiftTraceTarget.PREV
                val prevColor = Color(vm.anim.onionColorBackward)
                val nextColor = Color(vm.anim.onionColorForward)

                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (isPrev) Morandi.accent.copy(alpha = 0.22f) else Color.Transparent)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            vm.anim.shiftTraceTarget = ShiftTraceTarget.PREV
                        }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(prevColor),
                    )
                    Text(
                        text = "前帧",
                        color = if (isPrev) Morandi.accent else Morandi.subText,
                        fontSize = 11.5.sp,
                        fontWeight = if (isPrev) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }

                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (!isPrev) Morandi.accent.copy(alpha = 0.22f) else Color.Transparent)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            vm.anim.shiftTraceTarget = ShiftTraceTarget.NEXT
                        }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(nextColor),
                    )
                    Text(
                        text = "后帧",
                        color = if (!isPrev) Morandi.accent else Morandi.subText,
                        fontSize = 11.5.sp,
                        fontWeight = if (!isPrev) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }

            // 分割微线
            Spacer(
                modifier = Modifier
                    .width(1.dp)
                    .height(18.dp)
                    .background(Morandi.border.copy(alpha = 0.6f)),
            )

            // 手势模式切换: 对位 vs 移画布
            val isAlign = vm.anim.shiftTraceGestureMode == ShiftTraceGestureMode.ALIGN_FRAME
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (isAlign) Morandi.accent.copy(alpha = 0.18f) else Morandi.panel)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        vm.anim.shiftTraceGestureMode =
                            if (isAlign) ShiftTraceGestureMode.MOVE_CANVAS else ShiftTraceGestureMode.ALIGN_FRAME
                    }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = if (isAlign) "手势: 对位" else "手势: 移画布",
                    color = if (isAlign) Morandi.accent else Morandi.subText,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Medium,
                )
            }

            // 复位按钮
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(Morandi.panel)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        vm.animationResetShiftTrace(vm.anim.shiftTraceTarget)
                    }
                    .padding(horizontal = 9.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "复位",
                    color = Morandi.text,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Medium,
                )
            }

            // 完成按钮
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(Morandi.accent)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        vm.animationToggleShiftTrace()
                    }
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "完成",
                    color = Color.White,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}
