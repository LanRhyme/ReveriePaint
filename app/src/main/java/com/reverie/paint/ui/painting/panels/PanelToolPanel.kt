/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reverie.paint.R
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.core.PanelMirror
import com.reverie.paint.core.applyPanelDefaults
import com.reverie.paint.core.deletePanel
import com.reverie.paint.core.duplicatePanel
import com.reverie.paint.core.panelDefaults
import com.reverie.paint.core.panelDivideMode
import com.reverie.paint.core.panelMultiSelectedIds
import com.reverie.paint.core.panels
import com.reverie.paint.core.selectedPanelId
import com.reverie.paint.core.setPanelCrop
import com.reverie.paint.core.setPanelDivideMode
import com.reverie.paint.core.updatePanelStyle
import com.reverie.paint.model.PanelStyle
import com.reverie.paint.ui.theme.Morandi
import dev.chrisbanes.haze.HazeState
import kotlin.math.roundToInt

/**
 * 分格工具属性浮窗 (两态自适应, 不弹窗不切页):
 *  - 状态 A (未选中格): 页面默认参数 — 默认边框粗细/颜色、水平/垂直间隔、格内默认填充;
 *  - 状态 B (选中格): 该格边框粗细/颜色 (行尾"使用默认"勾选 + "已覆盖"圆点)、
 *    格内填充、裁剪开关、恢复默认样式、删除/复制。
 *
 * 遵循 FillPanel / EncloseFillPanel 的 ToolFloatPanel 折叠式设计。
 */
@Composable
fun PanelToolPanel(
    vm: PaintViewModel,
    hazeState: HazeState? = null,
) {
    val selectedId = vm.selectedPanelId
    val multiIds = vm.panelMultiSelectedIds
    val mirror = selectedId?.let { id -> vm.panels.firstOrNull { it.panelId == id } }

    ToolFloatPanel(modifier = Modifier, vm = vm, hazeState = hazeState) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.width(320.dp),
        ) {
            // 多选头: 已选 N 格 + 取消多选
            if (multiIds.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.panel_multi_selected, multiIds.size),
                        color = Morandi.text,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    ToolFloatChip(
                        label = stringResource(R.string.panel_cancel_multi),
                        onClick = { vm.panelMultiSelectedIds = emptySet() },
                    )
                }
            }

            // 模式段: 建格 | 切分 (互斥单选; 三命令显式分离, 对照 CSP)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                PanelModeButton(
                    label = stringResource(R.string.panel_mode_create),
                    selected = !vm.panelDivideMode,
                    onClick = { vm.setPanelDivideMode(false) },
                    modifier = Modifier.weight(1f),
                )
                PanelModeButton(
                    label = stringResource(R.string.panel_mode_divide),
                    selected = vm.panelDivideMode,
                    onClick = { vm.setPanelDivideMode(true) },
                    modifier = Modifier.weight(1f),
                )
            }

            if (mirror != null) {
                PanelSelectedSection(vm = vm, mirror = mirror)
            } else {
                PanelDefaultsSection(vm = vm)
            }
        }
    }
}

// ---- 状态 A: 页面默认参数 ----

@Composable
private fun PanelDefaultsSection(vm: PaintViewModel) {
    val d = vm.panelDefaults
    // 滑条拖动草稿: 拖动中只改本地值跟随手指, 松手才入引擎
    // (applyPanelDefaults 会重绘所有未覆盖格, 逐帧调用会刷爆渲染队列)
    var borderWidthDraft by remember(d) { mutableStateOf(d.borderWidth) }
    var cornerRadiusDraft by remember(d) { mutableStateOf(d.cornerRadius) }
    var hGapDraft by remember(d) { mutableStateOf(d.hGap) }
    var vGapDraft by remember(d) { mutableStateOf(d.vGap) }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ToolFloatSlider(
            label = stringResource(R.string.panel_defaults_border_width),
            valueText = "${borderWidthDraft.roundToInt()}px",
            range = 0f..32f,
            value = borderWidthDraft,
            onValue = { borderWidthDraft = it },
            onRelease = {
                if (borderWidthDraft != d.borderWidth) {
                    vm.applyPanelDefaults(d.copy(borderWidth = borderWidthDraft))
                }
            },
        )
        PanelColorRow(
            label = stringResource(R.string.panel_border_color),
            colorArgb = d.borderColor,
            onPick = { argb -> vm.applyPanelDefaults(d.copy(borderColor = argb)) },
            trailing = {
                // 应用到所有格: applyPanelDefaults 只重绘"跟随默认"的格, 显式覆盖过
                // 边框色的格需把覆盖清回 null, 让全部格吃到当前默认边框色
                ToolFloatChip(
                    label = stringResource(R.string.panel_apply_border_all),
                    onClick = {
                        vm.applyPanelDefaults(d)
                        vm.panels
                            .filter { it.meta.style.borderColor != null }
                            .forEach { m ->
                                vm.updatePanelStyle(m.panelId, m.meta.style.copy(borderColor = null))
                            }
                    },
                )
            },
        )
        ToolFloatSlider(
            label = stringResource(R.string.panel_corner_radius),
            valueText = "${cornerRadiusDraft.roundToInt()}px",
            range = 0f..48f,
            value = cornerRadiusDraft,
            onValue = { cornerRadiusDraft = it },
            onRelease = {
                if (cornerRadiusDraft != d.cornerRadius) {
                    vm.applyPanelDefaults(d.copy(cornerRadius = cornerRadiusDraft))
                }
            },
        )
        ToolFloatSlider(
            label = stringResource(R.string.panel_h_gap),
            valueText = "${hGapDraft.roundToInt()}px",
            range = 0f..64f,
            value = hGapDraft,
            onValue = { hGapDraft = it },
            onRelease = {
                if (hGapDraft != d.hGap) {
                    vm.applyPanelDefaults(d.copy(hGap = hGapDraft))
                }
            },
        )
        ToolFloatSlider(
            label = stringResource(R.string.panel_v_gap),
            valueText = "${vGapDraft.roundToInt()}px",
            range = 0f..64f,
            value = vGapDraft,
            onValue = { vGapDraft = it },
            onRelease = {
                if (vGapDraft != d.vGap) {
                    vm.applyPanelDefaults(d.copy(vGap = vGapDraft))
                }
            },
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.panel_fill),
                color = Morandi.subText,
                fontSize = 12.sp,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PanelColorSwatch(
                    colorArgb = d.fillColor,
                    onPick = { argb -> vm.applyPanelDefaults(d.copy(fillColor = argb)) },
                )
                Switch(
                    checked = d.fillEnabled,
                    onCheckedChange = { on -> vm.applyPanelDefaults(d.copy(fillEnabled = on)) },
                    colors = MorandiSwitchColors(),
                )
            }
        }
    }
}

// ---- 状态 B: 选中格参数 ----

@Composable
private fun PanelSelectedSection(
    vm: PaintViewModel,
    mirror: PanelMirror,
) {
    val meta = mirror.meta
    val style = meta.style
    val d = vm.panelDefaults
    val commit: (PanelStyle) -> Unit = { vm.updatePanelStyle(mirror.panelId, it) }

    // 边框粗细拖动草稿 (镜像异步刷新, 拖动中用本地值跟随手指)
    var borderWidthDraft by remember(mirror.panelId, style.borderWidth) {
        mutableStateOf(style.borderWidth ?: d.borderWidth)
    }
    // 圆角拖动草稿 (与边框粗细同款: 松手才入引擎, 拖动即视为覆盖)
    var cornerRadiusDraft by remember(mirror.panelId, style.cornerRadius) {
        mutableStateOf(style.cornerRadius ?: d.cornerRadius)
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // 边框粗细: 滑条 + "已覆盖"圆点 (非 null 即覆盖) + "使用默认"勾选
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.weight(1f)) {
                ToolFloatSlider(
                    label = stringResource(R.string.panel_border_width),
                    valueText = "${borderWidthDraft.roundToInt()}px",
                    range = 0f..32f,
                    value = borderWidthDraft,
                    onValue = { borderWidthDraft = it },
                    onRelease = {
                        // 松手才入引擎; 值有变化即视为覆盖 (跟随默认时拖滑条也会转为覆盖)
                        val effective = style.borderWidth ?: d.borderWidth
                        if (borderWidthDraft != effective) {
                            commit(style.copy(borderWidth = borderWidthDraft))
                        }
                    },
                )
            }
            if (style.borderWidth != null) {
                OverriddenDot()
            }
            UseDefaultCheckbox(
                useDefault = style.borderWidth == null,
                onToggle = { useDefault ->
                    commit(
                        if (useDefault) {
                            style.copy(borderWidth = null)
                        } else {
                            style.copy(borderWidth = style.borderWidth ?: d.borderWidth)
                        },
                    )
                },
            )
        }

        // 边框颜色: 同上 (使用默认勾选 + 已覆盖圆点)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.panel_border_color),
                    color = Morandi.subText,
                    fontSize = 12.sp,
                )
                if (style.borderColor != null) OverriddenDot()
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PanelColorSwatch(
                    colorArgb = style.borderColor ?: d.borderColor,
                    onPick = { argb -> commit(style.copy(borderColor = argb)) },
                )
                UseDefaultCheckbox(
                    useDefault = style.borderColor == null,
                    onToggle = { useDefault ->
                        commit(
                            if (useDefault) {
                                style.copy(borderColor = null)
                            } else {
                                style.copy(borderColor = style.borderColor ?: d.borderColor)
                            },
                        )
                    },
                )
            }
        }

        // 圆角: 滑条 + "已覆盖"圆点 + "使用默认"勾选 (与边框粗细行同构)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.weight(1f)) {
                ToolFloatSlider(
                    label = stringResource(R.string.panel_corner_radius),
                    valueText = "${cornerRadiusDraft.roundToInt()}px",
                    range = 0f..48f,
                    value = cornerRadiusDraft,
                    onValue = { cornerRadiusDraft = it },
                    onRelease = {
                        val effective = style.cornerRadius ?: d.cornerRadius
                        if (cornerRadiusDraft != effective) {
                            commit(style.copy(cornerRadius = cornerRadiusDraft))
                        }
                    },
                )
            }
            if (style.cornerRadius != null) {
                OverriddenDot()
            }
            UseDefaultCheckbox(
                useDefault = style.cornerRadius == null,
                onToggle = { useDefault ->
                    commit(
                        if (useDefault) {
                            style.copy(cornerRadius = null)
                        } else {
                            style.copy(cornerRadius = style.cornerRadius ?: d.cornerRadius)
                        },
                    )
                },
            )
        }

        // 格内填充: 开关 + 颜色
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.panel_fill),
                color = Morandi.subText,
                fontSize = 12.sp,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PanelColorSwatch(
                    colorArgb = style.fillColor ?: d.fillColor,
                    onPick = { argb -> commit(style.copy(fillColor = argb)) },
                )
                Switch(
                    checked = style.fillEnabled ?: d.fillEnabled,
                    onCheckedChange = { on -> commit(style.copy(fillEnabled = on)) },
                    colors = MorandiSwitchColors(),
                )
            }
        }

        // 裁剪开关 (蒙版可见性)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.panel_crop),
                color = Morandi.subText,
                fontSize = 12.sp,
            )
            Switch(
                checked = meta.cropEnabled,
                onCheckedChange = { crop -> vm.setPanelCrop(mirror.panelId, crop) },
                colors = MorandiSwitchColors(),
            )
        }

        // 复制/删除 (置顶/置底 UI 已移除: z 序仅重叠场景可见, 引擎入口 reorderPanel 保留供旧录制回放)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ToolFloatChip(
                label = stringResource(R.string.panel_menu_duplicate),
                onClick = { vm.duplicatePanel(mirror.panelId) },
            )
            Box(modifier = Modifier.weight(1f))
            ToolFloatChip(
                label = stringResource(R.string.panel_menu_delete),
                danger = true,
                onClick = { vm.deletePanel(mirror.panelId) },
            )
        }

        // 恢复默认样式: 该格 style 全部清回 null (跟随页面默认)
        ToolFloatChip(
            label = stringResource(R.string.panel_reset_style),
            selected = true,
            onClick = { commit(PanelStyle()) },
        )
    }
}

// ---- 通用小部件 ----

/** 两段模式切换按钮 (建格|切分), 样式对齐 ToolFloatChip 的选中/未选中态。 */
@Composable
private fun PanelModeButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) Morandi.accent else Morandi.panelHi)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (selected) Morandi.onAccent else Morandi.subText,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

/** "已覆盖"标记圆点 (对应 style 字段非 null 时显示)。 */
@Composable
private fun OverriddenDot() {
    val overriddenLabel = stringResource(R.string.panel_overridden)
    Box(
        modifier = Modifier
            .padding(horizontal = 2.dp)
            .size(6.dp)
            .clip(CircleShape)
            .background(Morandi.accent)
            .semantics { contentDescription = overriddenLabel },
    )
}

/** "使用默认"勾选: 勾上 = 该字段清回 null 跟随页面默认。 */
@Composable
private fun UseDefaultCheckbox(
    useDefault: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = stringResource(R.string.panel_use_default),
            color = Morandi.subText,
            fontSize = 10.sp,
        )
        Checkbox(
            checked = useDefault,
            onCheckedChange = onToggle,
            colors = CheckboxDefaults.colors(
                checkedColor = Morandi.accent,
                uncheckedColor = Morandi.subText,
                checkmarkColor = Morandi.onAccent,
            ),
        )
    }
}

/** 颜色行: 标签 + 圆形色块 (点击弹 CompactColorPickerPopup); [trailing] 可选行尾附加控件。 */
@Composable
private fun PanelColorRow(
    label: String,
    colorArgb: Int,
    onPick: (Int) -> Unit,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = Morandi.subText,
            fontSize = 12.sp,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PanelColorSwatch(colorArgb = colorArgb, onPick = onPick)
            trailing?.invoke()
        }
    }
}

/** 圆形色块 (18dp, Morandi 边框), 点击弹统一轻量取色浮窗; 回调给 ARGB。 */
@Composable
private fun PanelColorSwatch(
    colorArgb: Int,
    onPick: (Int) -> Unit,
) {
    var picking by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .size(18.dp)
            .clip(CircleShape)
            .background(Color(colorArgb))
            .border(1.dp, Morandi.border, CircleShape)
            .clickable { picking = true },
    )
    if (picking) {
        CompactColorPickerPopup(
            initialColor = Color(colorArgb),
            onColorSelected = { c ->
                onPick(
                    android.graphics.Color.argb(
                        (c.alpha * 255f).roundToInt().coerceIn(0, 255),
                        (c.red * 255f).roundToInt().coerceIn(0, 255),
                        (c.green * 255f).roundToInt().coerceIn(0, 255),
                        (c.blue * 255f).roundToInt().coerceIn(0, 255),
                    ),
                )
                picking = false
            },
            onDismiss = { picking = false },
        )
    }
}

/** 项目统一的 Morandi 开关配色 (与 EncloseFillPanel 等一致)。 */
@Composable
private fun MorandiSwitchColors() = SwitchDefaults.colors(
    checkedThumbColor = Morandi.accent,
    checkedTrackColor = Morandi.accent.copy(alpha = 0.5f),
    uncheckedThumbColor = Morandi.subText,
    uncheckedTrackColor = Morandi.panelHi,
)
