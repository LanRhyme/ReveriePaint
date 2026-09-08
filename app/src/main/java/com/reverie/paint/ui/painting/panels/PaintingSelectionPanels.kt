/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.panels

import androidx.compose.foundation.Image
import com.reverie.paint.model.LassoSubMode
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
internal fun PickerLayerSourceBar(
    tool: Tool,
    vm: PaintViewModel,
    hazeState: HazeState,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = tool == Tool.PICKER,
        modifier = modifier.padding(bottom = 24.dp),
        enter =
            androidx.compose.animation.fadeIn(
                androidx.compose.animation.core.tween(200)
            ) +
                androidx.compose.animation.slideInVertically(
                    androidx.compose.animation.core.tween(200),
                    initialOffsetY = { it / 2 }
                ),
        exit =
            androidx.compose.animation.fadeOut(
                androidx.compose.animation.core.tween(200)
            ) +
                androidx.compose.animation.slideOutVertically(
                    androidx.compose.animation.core.tween(200),
                    targetOffsetY = { it / 2 }
                )
    ) {
        ToolFloatPanel(vm = vm, hazeState = hazeState) {
            ToolBubbleDropdown(
                items = listOf(
                    ToolDropdownItemData(0, R.drawable.ic_layers, "当前图层"),
                    ToolDropdownItemData(1, R.drawable.ic_layerstack, "全部图层"),
                ),
                selected = vm.pickerSampleLayers,
                onSelect = { vm.updatePickerSampleLayers(it) },
                active = true,
            )
        }
    }
}

@Composable
internal fun SelectionMenuItem(
    label: String,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .noRippleClickable {
                    onClick()
                }
                .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            color = if (danger) Color(0xFFB05552) else Morandi.text,
            fontSize = 13.sp,
        )
    }
}

// Floating selection panel docked at the bottom of the screen, in the style
// of Krita's tool options docker. Each selection tool exposes its own
// property set: the magic wand / similar-color tools get a tolerance slider
// plus the common feather/expand/contract/smooth modifiers, while simple
// lasso-style tools only get the common modifiers (like Krita, which has no
// tolerance on the lasso). No close button: switching tools hides it.
@Composable
internal fun SelectionFloatPanel(
    modifier: Modifier = Modifier,
    vm: PaintViewModel,
    tool: Tool,
    propsOpen: Boolean,
    onToggleProps: () -> Unit,
    onDrag: (Float, Float) -> Unit,
    hazeState: HazeState? = null,
    polyPoints: List<Offset> = emptyList(),
    onPolyFinish: () -> Unit = {},
    onPolyUndo: () -> Unit = {},
    onPolyCancel: () -> Unit = {},
) {
    ToolFloatPanel(modifier = modifier, vm = vm, hazeState = hazeState) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // 多次操作套索在编完成/取消栏
            if (tool == Tool.LASSO && vm.lassoMultiPoints.isNotEmpty()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ToolFloatChip(
                        label = "完成闭合",
                        selected = true,
                        onClick = { vm.finishLassoMulti() }
                    )
                    ToolFloatChip(
                        label = "撤销点",
                        onClick = { vm.undoLassoPoint() }
                    )
                    ToolFloatChip(
                        label = "放弃",
                        danger = true,
                        onClick = { vm.cancelLassoMulti() }
                    )
                    Text(
                        if (vm.lassoSubMode == LassoSubMode.POLYLINE) "${vm.lassoMultiPoints.size} 点" else "${vm.lassoSegmentCounts.size} 段",
                        color = Morandi.subText,
                        fontSize = 11.sp,
                    )
                }
            }

            // 多边形选择在编完成/取消栏
            if (tool == Tool.SELECT_POLYGON && polyPoints.isNotEmpty()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ToolFloatChip(
                        label = "完成选区",
                        selected = true,
                        onClick = onPolyFinish
                    )
                    ToolFloatChip(
                        label = "撤销点",
                        onClick = onPolyUndo
                    )
                    ToolFloatChip(
                        label = "取消",
                        danger = true,
                        onClick = onPolyCancel
                    )
                    Text(
                        "${polyPoints.size} 顶点",
                        color = Morandi.subText,
                        fontSize = 11.sp,
                    )
                }
            }

            // Expandable Modifiers Drawer (空隙, 羽化, 扩展, 收缩, 平滑)
            androidx.compose.animation.AnimatedVisibility(visible = propsOpen) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.width(230.dp).padding(vertical = 4.dp),
                ) {
                    if (tool == Tool.MAGICWAND) {
                        ToolFloatSlider(
                            label = "空隙",
                            valueText = "${vm.selectionCloseGap}px",
                            range = 0f..16f,
                            value = vm.selectionCloseGap.toFloat().coerceIn(0f, 16f),
                            onValue = { vm.updateSelectionCloseGap(it.toInt()) },
                        )
                    }
                    // 拖动只更新本地显示, 松手才执行一次引擎操作——onValue 在
                    // 拖动中连续回调, 直接接引擎操作会叠加执行 (羽化越拖越糊)
                    var featherR by remember { mutableFloatStateOf(8f) }
                    var expandContractR by remember { mutableFloatStateOf(0f) }
                    var smoothR by remember { mutableFloatStateOf(4f) }
                    ToolFloatSlider(
                        label = "羽化",
                        valueText = "${featherR.toInt()}px",
                        range = 0f..32f,
                        value = featherR,
                        onValue = { featherR = it },
                        onRelease = { vm.featherSelection(featherR.toInt()) },
                    )
                    ToolFloatSlider(
                        label = "扩缩",
                        valueText = if (expandContractR.toInt() > 0) "+${expandContractR.toInt()}px" else "${expandContractR.toInt()}px",
                        range = -64f..64f,
                        value = expandContractR,
                        onValue = { expandContractR = it },
                        onRelease = {
                            val r = expandContractR.toInt()
                            if (r > 0) {
                                vm.expandSelection(r)
                            } else if (r < 0) {
                                vm.contractSelection(-r)
                            }
                        },
                    )
                    ToolFloatSlider(
                        label = "平滑",
                        valueText = "${smoothR.toInt()}px",
                        range = 1f..16f,
                        value = smoothR,
                        onValue = { smoothR = it },
                        onRelease = { vm.smoothSelection(smoothR.toInt()) },
                    )
                }
            }

            // Main Bar: Dropdowns + Action Buttons
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 套索工具操作方法 (自由描画, 折线, 自由+折线)
                if (tool == Tool.LASSO) {
                    ToolBubbleDropdown(
                        items = listOf(
                            ToolDropdownItemData(LassoSubMode.FREEHAND, R.drawable.ic_lasso, "自由"),
                            ToolDropdownItemData(LassoSubMode.POLYLINE, R.drawable.ic_polyline, "折线"),
                            ToolDropdownItemData(LassoSubMode.HYBRID, R.drawable.ic_lasso_multi, "自由+折线"),
                        ),
                        selected = vm.lassoSubMode,
                        onSelect = { vm.updateLassoSubMode(it) },
                        active = true,
                    )
                }

                // 选区模式 (新建, 增加, 减去, 相交)
                ToolBubbleDropdown(
                    items = listOf(
                        ToolDropdownItemData(0, R.drawable.ic_sel_mode_new, "新建"),
                        ToolDropdownItemData(1, R.drawable.ic_sel_mode_add, "增加"),
                        ToolDropdownItemData(2, R.drawable.ic_sel_mode_sub, "减去"),
                        ToolDropdownItemData(3, R.drawable.ic_sel_mode_intersect, "相交"),
                    ),
                    selected = vm.selectionMode,
                    onSelect = { vm.updateSelectionMode(it) },
                    active = true,
                )

                // 魔棒 / 相似采样图层 + 容差
                if (tool == Tool.MAGICWAND || tool == Tool.SELECT_SIMILAR) {
                    ToolBubbleDropdown(
                        items = listOf(
                            ToolDropdownItemData(0, R.drawable.ic_layers, "当前图层"),
                            ToolDropdownItemData(1, R.drawable.ic_layerstack, "全部图层"),
                        ),
                        selected = vm.selectionSampleLayers,
                        onSelect = { vm.updateSelectionSampleLayers(it) },
                        active = true,
                    )
                    Box(modifier = Modifier.width(136.dp)) {
                        ToolFloatSlider(
                            label = "容差",
                            valueText = "${vm.selectionTolerance}",
                            range = 1f..100f,
                            value = vm.selectionTolerance.toFloat().coerceIn(1f, 100f),
                            onValue = { vm.updateSelectionTolerance(it.toInt()) },
                        )
                    }
                }

                // 操作按钮组: 全选, 反选, 取消, 属性
                SelectionActionItem(
                    iconRes = R.drawable.ic_layers,
                    label = "全选",
                    onClick = { vm.selectAllCanvasAction() },
                )
                SelectionActionItem(
                    iconRes = R.drawable.ic_refresh,
                    label = "反选",
                    onClick = { vm.invertSelectionAction() },
                )
                SelectionActionItem(
                    iconRes = R.drawable.ic_trash,
                    label = "取消",
                    danger = true,
                    onClick = { vm.clearSelectionAction() },
                )
                SelectionActionItem(
                    iconRes = R.drawable.ic_sliders,
                    label = if (propsOpen) "收起" else "属性",
                    active = propsOpen,
                    onClick = { onToggleProps() },
                )
            }
        }
    }
}

@Composable
internal fun SelectionActionItem(
    iconRes: Int,
    label: String,
    primary: Boolean = false,
    danger: Boolean = false,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (pressed) 0.90f else 1f, spring(dampingRatio = 0.6f, stiffness = 500f), label = "btn_scale")

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
        modifier = Modifier
            .scale(scale)
            .clip(RoundedCornerShape(10.dp))
            .background(
                when {
                    primary -> Morandi.accent
                    danger -> Color(0xFFFF4D4F).copy(alpha = 0.12f)
                    active -> Morandi.accent.copy(alpha = 0.15f)
                    else -> Morandi.panelHi
                }
            )
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        tryAwaitRelease()
                        pressed = false
                    },
                    onTap = { onClick() }
                )
            }
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        androidx.compose.material3.Icon(
            painter = androidx.compose.ui.res.painterResource(id = iconRes),
            contentDescription = label,
            tint = when {
                primary -> Morandi.onAccent
                danger -> Color(0xFFFF4D4F)
                active -> Morandi.accent
                else -> Morandi.icon
            },
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = label,
            fontSize = 10.sp,
            fontWeight = if (primary || active) FontWeight.Bold else FontWeight.Normal,
            color = when {
                primary -> Morandi.onAccent
                danger -> Color(0xFFFF4D4F)
                active -> Morandi.accent
                else -> Morandi.subText
            },
        )
    }
}


/** Catmull-Rom spline through the anchor points - Krita's path tool draws
 * Bézier curves through the clicked anchors; this produces an equivalent
 * smooth curve used to commit a path selection */
