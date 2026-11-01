/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.panels

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reverie.paint.R
import com.reverie.paint.core.*
import com.reverie.paint.model.Interaction3DMode
import com.reverie.paint.model.RenderMode3D
import com.reverie.paint.model.clampPlacedWindowOrigin
import com.reverie.paint.ui.components.ReDropdownMenu
import com.reverie.paint.ui.components.ReDropdownMenuItem
import com.reverie.paint.ui.theme.Morandi
import kotlin.math.roundToInt

/**
 * 手机与平板自适应 3D 参考图层专属视口悬浮微型控制条
 *
 * 采用超紧凑胶囊设计，支持全屏自由拖拽吸附，手势操作 1 触即达，次级功能收纳于菜单中，
 * 彻底消除小屏遮挡与溢出问题。
 */
@Composable
fun Floating3DToolbar(
    vm: PaintViewModel,
    modifier: Modifier = Modifier,
) {
    val state = vm.currentLayer3DState ?: return

    val density = LocalDensity.current
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    var barW by remember { mutableFloatStateOf(0f) }
    var barH by remember { mutableFloatStateOf(0f) }
    var restX by remember { mutableFloatStateOf(0f) }
    var restY by remember { mutableFloatStateOf(0f) }

    var showStyleMenu by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }

    AnimatedVisibility(
        visible = vm.isCurrentLayer3D,
        enter = fadeIn() + slideInVertically { it / 2 },
        exit = fadeOut() + slideOutVertically { it / 2 },
        modifier = modifier
            .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    val hostW = vm.floatingHostWidthPx
                    val hostH = vm.floatingHostHeightPx
                    if (hostW <= 0f || hostH <= 0f || barW <= 0f || barH <= 0f) {
                        offsetX += dragAmount.x
                        offsetY += dragAmount.y
                    } else {
                        val keep = 48f * density.density
                        val (nx, ny) = clampPlacedWindowOrigin(
                            restX + offsetX + dragAmount.x,
                            restY + offsetY + dragAmount.y,
                            hostW, hostH,
                            0f, 0f, barW, barH,
                            keep,
                        )
                        offsetX = nx - restX
                        offsetY = ny - restY
                    }
                }
            },
    ) {
        Box(
            modifier = Modifier
                .onSizeChanged {
                    barW = it.width.toFloat()
                    barH = it.height.toFloat()
                }
                .onGloballyPositioned { coords ->
                    val pos = coords.positionInRoot()
                    restX = pos.x - offsetX
                    restY = pos.y - offsetY
                }
                .shadow(10.dp, RoundedCornerShape(22.dp), ambientColor = Color.Black.copy(alpha = 0.2f))
                .clip(RoundedCornerShape(22.dp))
                .background(Morandi.panel.copy(alpha = 0.94f))
                .border(1.dp, Morandi.border.copy(alpha = 0.35f), RoundedCornerShape(22.dp))
                .padding(horizontal = 6.dp, vertical = 4.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                // 3D 拖拽手柄与标识
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(Morandi.accent.copy(alpha = 0.16f))
                        .padding(horizontal = 7.dp, vertical = 5.dp)
                ) {
                    Text(
                        text = "3D",
                        color = Morandi.accent,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp,
                    )
                }

                Spacer(modifier = Modifier.width(2.dp))

                // 1. 核心手势模式 (旋转 / 平移 / 打光)
                CompactModeIconButton(
                    icon = R.drawable.ic_rotate_cw,
                    tooltip = stringResource(R.string.layer_3d_mode_rotate),
                    active = vm.active3DInteractionMode == Interaction3DMode.ROTATE,
                    onClick = { vm.active3DInteractionMode = Interaction3DMode.ROTATE },
                )
                CompactModeIconButton(
                    icon = R.drawable.ic_move,
                    tooltip = stringResource(R.string.layer_3d_mode_pan),
                    active = vm.active3DInteractionMode == Interaction3DMode.PAN,
                    onClick = { vm.active3DInteractionMode = Interaction3DMode.PAN },
                )
                CompactModeIconButton(
                    icon = R.drawable.ic_sun,
                    tooltip = stringResource(R.string.layer_3d_mode_light),
                    active = vm.active3DInteractionMode == Interaction3DMode.LIGHT,
                    onClick = { vm.active3DInteractionMode = Interaction3DMode.LIGHT },
                )
                if (state.isRigged()) {
                    CompactModeIconButton(
                        icon = R.drawable.ic_author,
                        tooltip = stringResource(R.string.layer_3d_mode_bone),
                        active = vm.active3DInteractionMode == Interaction3DMode.BONE,
                        onClick = {
                            vm.active3DInteractionMode = if (vm.active3DInteractionMode == Interaction3DMode.BONE) {
                                Interaction3DMode.ROTATE
                            } else {
                                Interaction3DMode.BONE
                            }
                        },
                    )
                }

                // 竖向细微分割线
                Box(
                    modifier = Modifier
                        .height(18.dp)
                        .width(1.dp)
                        .background(Morandi.border.copy(alpha = 0.4f))
                        .padding(horizontal = 1.dp)
                )

                // 2. 缩放快捷调节微组件
                CompactModeIconButton(
                    icon = R.drawable.ic_minus,
                    tooltip = stringResource(R.string.layer_3d_zoom_out),
                    active = false,
                    onClick = { vm.zoom3DModel(1f / 1.15f) },
                )
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { vm.reset3DZoom() }
                        .padding(horizontal = 3.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "${(state.zoom * 100).roundToInt()}%",
                        color = Morandi.text,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
                CompactModeIconButton(
                    icon = R.drawable.ic_plus,
                    tooltip = stringResource(R.string.layer_3d_zoom_in),
                    active = false,
                    onClick = { vm.zoom3DModel(1.15f) },
                )

                // 竖向细微分割线
                Box(
                    modifier = Modifier
                        .height(18.dp)
                        .width(1.dp)
                        .background(Morandi.border.copy(alpha = 0.4f))
                        .padding(horizontal = 1.dp)
                )

                // 3. 着色风格下拉菜单
                Box {
                    val currentStyleIcon = when (state.renderMode) {
                        RenderMode3D.CLAY -> R.drawable.ic_circle
                        RenderMode3D.SHADING -> R.drawable.ic_contrast
                        RenderMode3D.WIREFRAME -> R.drawable.ic_grid
                    }
                    CompactModeIconButton(
                        icon = currentStyleIcon,
                        tooltip = stringResource(R.string.layer_3d_style_clay),
                        active = showStyleMenu,
                        onClick = { showStyleMenu = true },
                    )

                    ReDropdownMenu(
                        expanded = showStyleMenu,
                        onDismissRequest = { showStyleMenu = false },
                        modifier = Modifier.width(150.dp),
                    ) {
                        ReDropdownMenuItem(
                            text = stringResource(R.string.layer_3d_style_clay),
                            icon = R.drawable.ic_circle,
                            selected = state.renderMode == RenderMode3D.CLAY,
                            onClick = {
                                showStyleMenu = false
                                vm.set3DRenderMode(RenderMode3D.CLAY)
                            },
                        )
                        ReDropdownMenuItem(
                            text = stringResource(R.string.layer_3d_style_shading),
                            icon = R.drawable.ic_contrast,
                            selected = state.renderMode == RenderMode3D.SHADING,
                            onClick = {
                                showStyleMenu = false
                                vm.set3DRenderMode(RenderMode3D.SHADING)
                            },
                        )
                        ReDropdownMenuItem(
                            text = stringResource(R.string.layer_3d_style_wireframe),
                            icon = R.drawable.ic_grid,
                            selected = state.renderMode == RenderMode3D.WIREFRAME,
                            onClick = {
                                showStyleMenu = false
                                vm.set3DRenderMode(RenderMode3D.WIREFRAME)
                            },
                        )
                    }
                }

                // 4. 骨骼与姿态微调面板 (仅支持骨骼的人体与动物模型显示)
                if (state.isRigged()) {
                    CompactModeIconButton(
                        icon = R.drawable.ic_sliders,
                        tooltip = stringResource(R.string.layer_3d_pose_rigging),
                        active = vm.show3DPosePanel,
                        onClick = { vm.show3DPosePanel = !vm.show3DPosePanel },
                    )
                }

                // 5. 更多功能下拉菜单 (复位 / 提取线稿 / 栅格化)
                Box {
                    CompactModeIconButton(
                        icon = R.drawable.ic_dots_vertical,
                        tooltip = stringResource(R.string.common_more),
                        active = showMoreMenu,
                        onClick = { showMoreMenu = true },
                    )

                    ReDropdownMenu(
                        expanded = showMoreMenu,
                        onDismissRequest = { showMoreMenu = false },
                        modifier = Modifier.width(160.dp),
                    ) {
                        ReDropdownMenuItem(
                            text = stringResource(R.string.layer_3d_mode_reset),
                            icon = R.drawable.ic_refresh,
                            onClick = {
                                showMoreMenu = false
                                vm.reset3DView()
                            },
                        )
                        ReDropdownMenuItem(
                            text = stringResource(R.string.layer_3d_extract_outline),
                            icon = R.drawable.ic_magicwand,
                            onClick = {
                                showMoreMenu = false
                                vm.extract3DOutlines(state.layerId)
                            },
                        )
                        ReDropdownMenuItem(
                            text = stringResource(R.string.layer_3d_rasterize),
                            icon = R.drawable.ic_layers,
                            onClick = {
                                showMoreMenu = false
                                vm.rasterize3DLayer(state.layerId)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CompactModeIconButton(
    icon: Int,
    tooltip: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    val bg = if (active) Morandi.accent.copy(alpha = 0.22f) else Color.Transparent
    val tint = if (active) Morandi.accent else Morandi.icon

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(bg)
            .clickable(onClick = onClick)
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = tooltip,
            tint = tint,
            modifier = Modifier.size(16.dp),
        )
    }
}
