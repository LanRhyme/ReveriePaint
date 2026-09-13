/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.home.stylus

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.reverie.paint.R
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.ui.components.ReTextButton
import com.reverie.paint.ui.home.GroupedSettingsCard
import com.reverie.paint.ui.home.SettingCategoryHeader
import com.reverie.paint.ui.home.SettingDropdownRow
import com.reverie.paint.ui.home.SettingSliderRow
import com.reverie.paint.ui.home.SettingSwitchRow
import com.reverie.paint.ui.home.SettingsCardDivider
import com.reverie.paint.ui.theme.Theme

@Composable
internal fun SamsungStylusConfigDialog(
    vm: PaintViewModel,
    onDismiss: () -> Unit,
) {
    val colors = Theme.current
    val actionOptions = listOf(
        "切换画笔与橡皮" to "toggle_eraser",
        "撤销" to "undo",
        "重做" to "redo",
        "吸管取色" to "tool_picker",
        "切换上一工具" to "toggle_last_tool",
        "快捷调色盘" to "tool_color",
        "无操作" to "none",
    )

    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .width(420.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(colors.panel)
                .padding(20.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "三星 S Pen 专属设置",
                        color = colors.text,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(colors.panelHi)
                            .clickable(onClick = onDismiss),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_x),
                            contentDescription = "关闭",
                            tint = colors.subText,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                SettingCategoryHeader("侧键动作映射")
                GroupedSettingsCard(containerColor = colors.panelHi) {
                    val singleClickTitle = actionOptions.find { it.second == vm.samsungSingleClickAction }?.first ?: "切换画笔与橡皮"
                    SettingDropdownRow(
                        title = "侧键单击动作",
                        currentText = singleClickTitle,
                        options = actionOptions.map { it.first },
                        onSelect = { idx ->
                            vm.updateSamsungSingleClickAction(actionOptions[idx].second)
                        },
                    )
                    SettingsCardDivider()
                    val doubleClickTitle = actionOptions.find { it.second == vm.samsungDoubleClickAction }?.first ?: "撤销"
                    SettingDropdownRow(
                        title = "侧键双击动作",
                        currentText = doubleClickTitle,
                        options = actionOptions.map { it.first },
                        onSelect = { idx ->
                            vm.updateSamsungDoubleClickAction(actionOptions[idx].second)
                        },
                    )
                    SettingsCardDivider()
                    val longPressTitle = actionOptions.find { it.second == vm.samsungLongPressAction }?.first ?: "吸管取色"
                    SettingDropdownRow(
                        title = "侧键长按动作",
                        currentText = longPressTitle,
                        options = actionOptions.map { it.first },
                        onSelect = { idx ->
                            vm.updateSamsungLongPressAction(actionOptions[idx].second)
                        },
                    )
                }

                Spacer(Modifier.height(12.dp))

                SettingCategoryHeader("触觉与微震")
                GroupedSettingsCard(containerColor = colors.panelHi) {
                    SettingSwitchRow(
                        title = "笔尖书写微震",
                        summary = "运笔时线性振动马达提供沙沙质感",
                        checked = vm.stylusHapticsEnabled,
                        onCheckedChange = { vm.updateStylusHapticsEnabled(it) },
                    )
                    if (vm.stylusHapticsEnabled) {
                        SettingsCardDivider()
                        SettingSliderRow(
                            title = "微震强度",
                            summary = "${(vm.stylusHapticsIntensity * 100).toInt()}%",
                            value = vm.stylusHapticsIntensity,
                            onValueChange = { vm.updateStylusHapticsIntensity(it) },
                        )
                    }
                }

                Spacer(Modifier.height(18.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    ReTextButton("完成", onDismiss, textColor = colors.accent, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
