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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reverie.paint.R
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.core.PanelMirror
import com.reverie.paint.core.applyPanelDefaults
import com.reverie.paint.core.panelDefaults
import com.reverie.paint.core.panelDivideMode
import com.reverie.paint.core.panelMultiSelectedIds
import com.reverie.paint.core.panelSnapAxis
import com.reverie.paint.core.panelSnapPageCenter
import com.reverie.paint.core.panels
import com.reverie.paint.core.selectedPanelId
import com.reverie.paint.core.setPanelDivideMode
import com.reverie.paint.core.setPanelSnapAxis
import com.reverie.paint.core.setPanelSnapPageCenter
import com.reverie.paint.core.updatePanelStyle
import com.reverie.paint.model.PanelStyle
import com.reverie.paint.ui.theme.Morandi
import dev.chrisbanes.haze.HazeState
import kotlin.math.roundToInt

/**
 * 分格工具属性浮窗。建格/切分共用同一套控件:
 * 边框粗细、边框色、圆角、水平/垂直间隔、格内填充。底部是长按提示。
 * 未选中时边框/圆角/填充改新格默认 (applyPanelDefaults), 选中某一格时改该格
 * (updatePanelStyle), 间隔始终是页面默认。裁剪不再从浮窗开关 (新建格固定开),
 * 图层树眼睛仍走 setPanelCrop。复制/删除/恢复默认在长按菜单。
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

            PanelPropertiesSection(vm = vm, mirror = mirror)

            Text(
                text = stringResource(R.string.panel_longpress_hint),
                color = Morandi.subText,
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun PanelPropertiesSection(vm: PaintViewModel, mirror: PanelMirror?) {
    val d = vm.panelDefaults
    val style = mirror?.meta?.style ?: PanelStyle()
    val commitStyle: (PanelStyle) -> Unit = { next ->
        val id = mirror?.panelId
        if (id != null) vm.updatePanelStyle(id, next) else vm.applyPanelDefaults(
            d.copy(
                borderWidth = next.borderWidth ?: d.borderWidth,
                borderColor = next.borderColor ?: d.borderColor,
                cornerRadius = next.cornerRadius ?: d.cornerRadius,
                fillColor = next.fillColor ?: d.fillColor,
                fillEnabled = next.fillEnabled ?: d.fillEnabled,
            ),
        )
    }
    val borderWidth = style.borderWidth ?: d.borderWidth
    val cornerRadius = style.cornerRadius ?: d.cornerRadius
    var borderWidthDraft by remember(mirror?.panelId, borderWidth) { mutableStateOf(borderWidth) }
    var cornerRadiusDraft by remember(mirror?.panelId, cornerRadius) { mutableStateOf(cornerRadius) }
    var hGapDraft by remember(d.hGap) { mutableStateOf(d.hGap) }
    var vGapDraft by remember(d.vGap) { mutableStateOf(d.vGap) }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ToolFloatSlider(
            label = stringResource(R.string.panel_border_width),
            valueText = "${borderWidthDraft.roundToInt()}px",
            range = 0f..32f,
            value = borderWidthDraft,
            onValue = { borderWidthDraft = it },
            onRelease = {
                if (borderWidthDraft != borderWidth) commitStyle(style.copy(borderWidth = borderWidthDraft))
            },
        )
        PanelColorRow(
            label = stringResource(R.string.panel_border_color),
            colorArgb = style.borderColor ?: d.borderColor,
            onPick = { argb -> commitStyle(style.copy(borderColor = argb)) },
        )
        ToolFloatSlider(
            label = stringResource(R.string.panel_corner_radius),
            valueText = "${cornerRadiusDraft.roundToInt()}px",
            range = 0f..48f,
            value = cornerRadiusDraft,
            onValue = { cornerRadiusDraft = it },
            onRelease = {
                if (cornerRadiusDraft != cornerRadius) commitStyle(style.copy(cornerRadius = cornerRadiusDraft))
            },
        )
        ToolFloatSlider(
            label = stringResource(R.string.panel_h_gap),
            valueText = "${hGapDraft.roundToInt()}px",
            range = 0f..64f,
            value = hGapDraft,
            onValue = { hGapDraft = it },
            onRelease = {
                if (hGapDraft != d.hGap) vm.applyPanelDefaults(d.copy(hGap = hGapDraft))
            },
        )
        ToolFloatSlider(
            label = stringResource(R.string.panel_v_gap),
            valueText = "${vGapDraft.roundToInt()}px",
            range = 0f..64f,
            value = vGapDraft,
            onValue = { vGapDraft = it },
            onRelease = {
                if (vGapDraft != d.vGap) vm.applyPanelDefaults(d.copy(vGap = vGapDraft))
            },
        )
        Text(
            text = stringResource(R.string.panel_gap_hint),
            color = Morandi.subText,
            fontSize = 10.sp,
        )
        val divideMode = vm.panelDivideMode
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(
                    if (divideMode) R.string.panel_snap_axis else R.string.panel_snap_page_center,
                ),
                color = Morandi.subText,
                fontSize = 12.sp,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = if (divideMode) vm.panelSnapAxis else vm.panelSnapPageCenter,
                onCheckedChange = { on ->
                    if (divideMode) vm.setPanelSnapAxis(on) else vm.setPanelSnapPageCenter(on)
                },
                colors = MorandiSwitchColors(),
            )
        }
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
                    onPick = { argb -> commitStyle(style.copy(fillColor = argb)) },
                )
                Switch(
                    checked = style.fillEnabled ?: d.fillEnabled,
                    onCheckedChange = { on -> commitStyle(style.copy(fillEnabled = on)) },
                    colors = MorandiSwitchColors(),
                )
            }
        }
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

/** 颜色行: 标签 + 圆形色块 (点击弹 CompactColorPickerPopup)。 */
@Composable
private fun PanelColorRow(
    label: String,
    colorArgb: Int,
    onPick: (Int) -> Unit,
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
        PanelColorSwatch(colorArgb = colorArgb, onPick = onPick)
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
