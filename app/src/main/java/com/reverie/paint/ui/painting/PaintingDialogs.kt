/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting

import androidx.compose.foundation.Image
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import com.reverie.paint.ui.components.ReTextButton
import com.reverie.paint.ui.components.ReSlider
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import com.reverie.paint.ui.components.noRippleClickable
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.shadow
import com.reverie.paint.ui.theme.glassBorder
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.ui.res.painterResource
import com.reverie.paint.R
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.haze
import dev.chrisbanes.haze.hazeChild
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.reverie.paint.core.*
import com.reverie.paint.model.Tool
import com.reverie.paint.model.ToolGroup
import com.reverie.paint.ui.theme.Morandi
import kotlin.math.min

/**
 * Painting page: full-bleed canvas with touch painting + gestures,
 * overlaid by the top bar, left tool rail and popup panels.
 *
 * 画世界 Pro style: left tool rail with vertical sliders, top operation
 * bar, dark grid workspace with a centered white canvas.
 */
@Composable
internal fun ExitSaveDialog(
    vm: PaintViewModel,
    onDiscard: () -> Unit,
    onSaveAndExit: () -> Unit,
    onDismiss: () -> Unit,
) {
        val context = androidx.compose.ui.platform.LocalContext.current
        androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
            Box(
                modifier = Modifier
                    .width(320.dp)
                    .shadow(20.dp, RoundedCornerShape(16.dp), spotColor = Color.Black.copy(alpha = 0.4f))
                    .clip(RoundedCornerShape(16.dp))
                    .background(Morandi.panel)
                    .glassBorder(RoundedCornerShape(16.dp))
                    .padding(20.dp)
            ) {
                Column {
                    Text(
                        text = "保存工程",
                        color = Morandi.text,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "是否在退出绘画之前保存当前工程 (${vm.docName}.revp)？",
                        color = Morandi.subText,
                        fontSize = 13.sp,
                        lineHeight = 18.sp
                    )
                    Spacer(Modifier.height(20.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ReTextButton("取消", onDismiss, textColor = Morandi.subText, fontSize = 13.sp)
                        Spacer(Modifier.width(4.dp))
                        ReTextButton("不保存", onDiscard, textColor = Color(0xFFFF5252), fontSize = 13.sp)
                        Spacer(Modifier.width(4.dp))
                        ReTextButton("保存并退出", onSaveAndExit, textColor = Morandi.accent, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }
            }
        }
}

@Composable
internal fun DiscardConfirmDialog(
    onDiscard: () -> Unit,
    onDismiss: () -> Unit,
) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = onDismiss,
            properties = androidx.compose.ui.window.DialogProperties(
                usePlatformDefaultWidth = false
            )
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss
                    ),
                contentAlignment = Alignment.BottomCenter
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {} // Consume click on modal content
                        )
                        .shadow(20.dp, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp), spotColor = Color.Black.copy(alpha = 0.4f))
                        .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                        .background(Morandi.panel)
                        .glassBorder(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                        .navigationBarsPadding()
                        .padding(horizontal = 24.dp, vertical = 20.dp)
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        // Top Drag Handle Indicator
                        Box(
                            modifier = Modifier
                                .width(36.dp)
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(Morandi.border.copy(alpha = 0.8f))
                        )
                        Spacer(Modifier.height(16.dp))

                        // Warning Header
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFFFF5252).copy(alpha = 0.12f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    painterResource(R.drawable.ic_alert_triangle),
                                    contentDescription = null,
                                    tint = Color(0xFFFF5252),
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "确认丢弃未保存的修改？",
                                    color = Morandi.text,
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = "未保存的笔迹与图层调整将无法恢复",
                                    color = Morandi.subText,
                                    fontSize = 12.sp
                                )
                            }
                        }

                        Spacer(Modifier.height(14.dp))
                        Text(
                            text = "退出后，工程将恢复至上次保存的状态。若继续退出，当前画布上的所有新绘制内容都将被永久丢弃。",
                            color = Morandi.subText,
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(Modifier.height(22.dp))

                        // Top Action: Continue Editing (Cancel)
                        ReTextButton(
                            "继续编辑",
                            onDismiss,
                            modifier = Modifier.fillMaxWidth(),
                            containerColor = Morandi.border.copy(alpha = 0.4f),
                            contentColor = Morandi.text,
                            fontSize = 14.sp,
                        )

                        Spacer(Modifier.height(10.dp))

                        // Bottom Action: Discard Changes & Exit (Pushed to bottom of screen)
                        ReTextButton(
                            "丢弃修改并退出",
                            onDiscard,
                            modifier = Modifier.fillMaxWidth(),
                            icon = R.drawable.ic_trash,
                            containerColor = Color(0xFFFF5252),
                            contentColor = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
}
