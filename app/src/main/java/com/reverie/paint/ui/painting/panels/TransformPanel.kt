/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.panels

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.Offset
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import com.reverie.paint.ui.components.liquidHighlight
import com.reverie.paint.ui.components.liquidLean
import com.reverie.paint.ui.components.liquidSheen
import com.reverie.paint.ui.components.pressScale
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reverie.paint.R
import com.reverie.paint.core.*
import com.reverie.paint.ui.theme.Morandi
import dev.chrisbanes.haze.HazeState
import com.reverie.paint.ui.painting.canvas.TransformMode
import com.reverie.paint.ui.painting.canvas.TransformState

/**
 * Professional Transform Tool Panel matching Krita & Huashijie Pro.
 * Provides quick transform modes (标准, 自由, 透视, 扭曲), horizontal/vertical flips,
 * 90-degree rotations, canvas alignment, micro-step D-pad, and reset action.
 */
@Composable
fun TransformPanel(
    vm: PaintViewModel,
    tfState: TransformState,
    onReset: () -> Unit = {},
    onCommit: () -> Unit = {},
    onCancel: () -> Unit = {},
    hazeState: HazeState? = null,
) {
    var showDpad by remember { mutableStateOf(false) }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Floating D-Pad for Pixel-Perfect Micro Adjustments (No background, No center dot)
        AnimatedVisibility(visible = showDpad) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // UP (Chevron points up: rotate 270)
                TransformIconButton(
                    iconRes = R.drawable.ic_chevron,
                    label = "上移",
                    rotation = 270f,
                    onClick = {
                        tfState.ty -= 1f
                        if (tfState.mode == TransformMode.PERSPECTIVE) {
                            tfState.quadCorners = tfState.quadCorners.map { it.copy(y = it.y - 1f) }
                        } else if (tfState.mode == TransformMode.DISTORT) {
                            tfState.meshPoints = tfState.meshPoints.map { it.copy(y = it.y - 1f) }
                        }
                    }
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(28.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // LEFT (Chevron points left: rotate 180)
                    TransformIconButton(
                        iconRes = R.drawable.ic_chevron,
                        label = "左移",
                        rotation = 180f,
                        onClick = {
                            tfState.tx -= 1f
                            if (tfState.mode == TransformMode.PERSPECTIVE) {
                                tfState.quadCorners = tfState.quadCorners.map { it.copy(x = it.x - 1f) }
                            } else if (tfState.mode == TransformMode.DISTORT) {
                                tfState.meshPoints = tfState.meshPoints.map { it.copy(x = it.x - 1f) }
                            }
                        }
                    )
                    // RIGHT (Chevron points right: rotate 0)
                    TransformIconButton(
                        iconRes = R.drawable.ic_chevron,
                        label = "右移",
                        rotation = 0f,
                        onClick = {
                            tfState.tx += 1f
                            if (tfState.mode == TransformMode.PERSPECTIVE) {
                                tfState.quadCorners = tfState.quadCorners.map { it.copy(x = it.x + 1f) }
                            } else if (tfState.mode == TransformMode.DISTORT) {
                                tfState.meshPoints = tfState.meshPoints.map { it.copy(x = it.x + 1f) }
                            }
                        }
                    )
                }
                // DOWN (Chevron points down: rotate 90)
                TransformIconButton(
                    iconRes = R.drawable.ic_chevron,
                    label = "下移",
                    rotation = 90f,
                    onClick = {
                        tfState.ty += 1f
                        if (tfState.mode == TransformMode.PERSPECTIVE) {
                            tfState.quadCorners = tfState.quadCorners.map { it.copy(y = it.y + 1f) }
                        } else if (tfState.mode == TransformMode.DISTORT) {
                            tfState.meshPoints = tfState.meshPoints.map { it.copy(y = it.y + 1f) }
                        }
                    }
                )
            }
        }

        // Main Bottom Floating Panel
        ToolFloatPanel(modifier = Modifier, vm = vm, hazeState = hazeState) {
            // Functional Action Buttons Row
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 1. 变换模式下拉
                ToolBubbleDropdown(
                    items = listOf(
                        ToolDropdownItemData(TransformMode.STANDARD, R.drawable.ic_tf_standard, "标准"),
                        ToolDropdownItemData(TransformMode.FREE, R.drawable.ic_tf_free, "自由"),
                        ToolDropdownItemData(TransformMode.PERSPECTIVE, R.drawable.ic_tf_perspective, "透视"),
                        ToolDropdownItemData(TransformMode.DISTORT, R.drawable.ic_tf_distort, "扭曲"),
                    ),
                    selected = tfState.mode,
                    onSelect = { modeVal ->
                        if (tfState.mode != modeVal) {
                            tfState.mode = modeVal
                            val b = tfState.bounds
                            val mList = List(16) { idx ->
                                val r = idx / 4
                                val c = idx % 4
                                Offset(
                                    b.left + b.width * (c / 3f),
                                    b.top + b.height * (r / 3f),
                                )
                            }
                            tfState.meshPoints = mList
                            tfState.origMeshPoints = mList
                            tfState.quadCorners = listOf(b.topLeft, b.topRight, b.bottomRight, b.bottomLeft)
                        }
                    },
                    active = true,
                )

                // 2. 翻转下拉 (水平翻转 / 垂直翻转)
                ToolBubbleDropdown(
                    items = listOf(
                        ToolDropdownItemData(0, R.drawable.ic_flip_h, "水平翻转"),
                        ToolDropdownItemData(1, R.drawable.ic_flip_v, "垂直翻转"),
                    ),
                    selected = null,
                    labelOverride = "翻转",
                    iconOverride = R.drawable.ic_flip_h,
                    onSelect = { option ->
                        if (option == 0) {
                            tfState.scaleX *= -1f
                            val c = tfState.bounds.center + androidx.compose.ui.geometry.Offset(tfState.tx, tfState.ty)
                            tfState.quadCorners = tfState.quadCorners.map {
                                it.copy(x = c.x - (it.x - c.x))
                            }
                            tfState.meshPoints = tfState.meshPoints.map {
                                it.copy(x = c.x - (it.x - c.x))
                            }
                        } else {
                            tfState.scaleY *= -1f
                            val c = tfState.bounds.center + androidx.compose.ui.geometry.Offset(tfState.tx, tfState.ty)
                            tfState.quadCorners = tfState.quadCorners.map {
                                it.copy(y = c.y - (it.y - c.y))
                            }
                            tfState.meshPoints = tfState.meshPoints.map {
                                it.copy(y = c.y - (it.y - c.y))
                            }
                        }
                    },
                )

                // 3. 旋转下拉 (顺时针 / 逆时针 / 180度)
                ToolBubbleDropdown(
                    items = listOf(
                        ToolDropdownItemData(0, R.drawable.ic_rotate_cw, "顺时针 90°"),
                        ToolDropdownItemData(1, R.drawable.ic_rotate_ccw, "逆时针 90°"),
                        ToolDropdownItemData(2, R.drawable.ic_refresh, "旋转 180°"),
                    ),
                    selected = null,
                    labelOverride = "旋转",
                    iconOverride = R.drawable.ic_rotate_cw,
                    onSelect = { option ->
                        val delta = when (option) {
                            0 -> 90f
                            1 -> -90f
                            else -> 180f
                        }
                        var r = (tfState.rotation + delta) % 360f
                        if (r > 180f) r -= 360f
                        else if (r < -180f) r += 360f
                        tfState.rotation = r
                    },
                )

                // 4. 微调手柄开关
                ToolActionButton(
                    iconRes = R.drawable.ic_move,
                    label = "微调",
                    active = showDpad,
                    onClick = { showDpad = !showDpad },
                )

                // 5. 重置所有变形
                ToolActionButton(
                    iconRes = R.drawable.ic_refresh,
                    label = "重置",
                    onClick = {
                        val b = tfState.bounds
                        tfState.reset(b)
                        onReset()
                    },
                )

                // 6. 取消
                ToolActionButton(
                    iconRes = R.drawable.ic_x,
                    label = "取消",
                    danger = true,
                    onClick = onCancel,
                )

                // 7. 完成
                ToolActionButton(
                    iconRes = R.drawable.ic_check,
                    label = "完成",
                    primary = true,
                    onClick = onCommit,
                )
            }
        }
    }
}



@Composable
private fun TransformIconButton(
    iconRes: Int,
    label: String,
    size: androidx.compose.ui.unit.Dp = 34.dp,
    rotation: Float = 0f,
    onClick: () -> Unit,
) {
    val iconSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }

    Box(
        modifier = Modifier
            .size(size)
            .pressScale(iconSource, pressedScale = 0.86f)
            .liquidLean(iconSource, maxOffset = 3.dp)
            .clip(CircleShape)
            .background(Morandi.panelHi)
            .liquidHighlight(iconSource, Color.White, radius = 20.dp)
            .clickable(interactionSource = iconSource, indication = null) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(id = iconRes),
            contentDescription = label,
            tint = Color.White,
            modifier = Modifier
                .size(18.dp)
                .rotate(rotation),
        )
    }
}
