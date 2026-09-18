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
import androidx.compose.ui.res.stringResource
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
        stringResource(R.string.stylus_action_switch_brush_eraser) to "toggle_eraser",
        stringResource(R.string.stylus_action_undo) to "undo",
        stringResource(R.string.stylus_action_redo) to "redo",
        stringResource(R.string.stylus_action_eyedropper) to "tool_picker",
        stringResource(R.string.stylus_action_prev_tool) to "toggle_last_tool",
        stringResource(R.string.stylus_action_quick_palette) to "tool_color",
        stringResource(R.string.stylus_action_none) to "none",
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
                        text = stringResource(R.string.stylus_samsung_title),
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
                            contentDescription = stringResource(R.string.common_close),
                            tint = colors.subText,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                SettingCategoryHeader(stringResource(R.string.stylus_samsung_side_key))
                GroupedSettingsCard(containerColor = colors.panelHi) {
                    SettingSwitchRow(
                        title = stringResource(R.string.stylus_samsung_hold_eraser),
                        summary = stringResource(R.string.stylus_samsung_hold_eraser_desc),
                        checked = vm.samsungSideButtonErase,
                        onCheckedChange = { vm.updateSamsungSideButtonErase(it) },
                    )
                }

                Spacer(Modifier.height(12.dp))

                SettingCategoryHeader(stringResource(R.string.stylus_samsung_key_mapping))
                GroupedSettingsCard(containerColor = colors.panelHi) {
                    val singleClickTitle = actionOptions.find { it.second == vm.samsungSingleClickAction }?.first ?: actionOptions[0].first
                    SettingDropdownRow(
                        title = stringResource(R.string.stylus_samsung_click_action),
                        currentText = singleClickTitle,
                        options = actionOptions.map { it.first },
                        onSelect = { idx ->
                            vm.updateSamsungSingleClickAction(actionOptions[idx].second)
                        },
                    )
                    SettingsCardDivider()
                    val doubleClickTitle = actionOptions.find { it.second == vm.samsungDoubleClickAction }?.first ?: actionOptions[1].first
                    SettingDropdownRow(
                        title = stringResource(R.string.stylus_samsung_double_click_action),
                        currentText = doubleClickTitle,
                        options = actionOptions.map { it.first },
                        onSelect = { idx ->
                            vm.updateSamsungDoubleClickAction(actionOptions[idx].second)
                        },
                    )
                    SettingsCardDivider()
                    val longPressTitle = actionOptions.find { it.second == vm.samsungLongPressAction }?.first ?: actionOptions[3].first
                    SettingDropdownRow(
                        title = stringResource(R.string.stylus_samsung_long_press_action),
                        currentText = longPressTitle,
                        options = actionOptions.map { it.first },
                        onSelect = { idx ->
                            vm.updateSamsungLongPressAction(actionOptions[idx].second)
                        },
                    )
                }

                Spacer(Modifier.height(10.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(colors.accent.copy(alpha = 0.10f))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.stylus_samsung_hint),
                        color = colors.subText,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                    )
                }

                Spacer(Modifier.height(18.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    ReTextButton(stringResource(R.string.common_done), onDismiss, textColor = colors.accent, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
