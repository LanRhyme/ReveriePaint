/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.home.stylus

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.sp
import com.reverie.paint.R
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.ui.theme.Theme

@Composable
internal fun XiaomiStylusConfigDialog(
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

    val modelOptions = listOf(
        "AUTO" to stringResource(R.string.stylus_xiaomi_auto_model, vm.detectedXiaomiPencilModel.editionName),
        "FOCUS_PEN" to stringResource(R.string.stylus_xiaomi_focus_model),
        "SMART_PEN_2" to stringResource(R.string.stylus_xiaomi_smart2_model),
        "SMART_PEN_1" to stringResource(R.string.stylus_xiaomi_smart1_model),
    )

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .widthIn(max = 440.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(colors.panel)
                .padding(20.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                StylusDialogHeader(
                    badgeText = "Xiaomi",
                    title = stringResource(R.string.stylus_xiaomi_title),
                    onClose = onDismiss,
                )

                Spacer(Modifier.height(8.dp))

                // 硬件型号
                StylusDialogSectionTitle(stringResource(R.string.stylus_xiaomi_model))
                StylusDialogCard {
                    val currentModelTitle = when (vm.xiaomiPencilModelMode) {
                        "FOCUS_PEN" -> stringResource(R.string.stylus_xiaomi_focus_model)
                        "SMART_PEN_2" -> stringResource(R.string.stylus_xiaomi_smart2_model)
                        "SMART_PEN_1" -> stringResource(R.string.stylus_xiaomi_smart1_model)
                        else -> stringResource(R.string.stylus_xiaomi_auto_model, vm.detectedXiaomiPencilModel.editionName)
                    }
                    StylusDialogDropdownItem(
                        title = stringResource(R.string.stylus_xiaomi_model),
                        currentText = currentModelTitle,
                        options = modelOptions.map { it.second },
                        onSelect = { idx ->
                            vm.updateXiaomiPencilModelMode(modelOptions[idx].first)
                        },
                    )
                }

                // 侧键行为
                StylusDialogSectionTitle(stringResource(R.string.stylus_xiaomi_side_key))
                StylusDialogCard {
                    StylusDialogSwitchItem(
                        title = stringResource(R.string.stylus_xiaomi_hold_eraser),
                        summary = stringResource(R.string.stylus_xiaomi_hold_eraser_desc),
                        checked = vm.xiaomiSideButtonErase,
                        onCheckedChange = { vm.updateXiaomiSideButtonErase(it) },
                    )
                }

                // 按键动作映射
                StylusDialogSectionTitle(stringResource(R.string.stylus_xiaomi_key_mapping))
                StylusDialogCard {
                    // 主键单击
                    val primaryClickTitle = actionOptions.find { it.second == vm.xiaomiPrimaryButtonAction }?.first ?: actionOptions[0].first
                    StylusDialogDropdownItem(
                        title = stringResource(R.string.stylus_xiaomi_primary_action),
                        currentText = primaryClickTitle,
                        options = actionOptions.map { it.first },
                        onSelect = { idx ->
                            vm.updateXiaomiPrimaryButtonAction(actionOptions[idx].second)
                        },
                    )

                    // 主键双击
                    val doubleClickTitle = actionOptions.find { it.second == vm.xiaomiDoubleTapAction }?.first ?: actionOptions[0].first
                    StylusDialogDropdownItem(
                        title = stringResource(R.string.stylus_xiaomi_double_tap_action),
                        currentText = doubleClickTitle,
                        options = actionOptions.map { it.first },
                        onSelect = { idx ->
                            vm.updateXiaomiDoubleTapAction(actionOptions[idx].second)
                        },
                    )

                    // 副键单击
                    val secondaryClickTitle = actionOptions.find { it.second == vm.xiaomiSecondaryButtonAction }?.first ?: actionOptions[0].first
                    StylusDialogDropdownItem(
                        title = stringResource(R.string.stylus_xiaomi_secondary_action),
                        currentText = secondaryClickTitle,
                        options = actionOptions.map { it.first },
                        onSelect = { idx ->
                            vm.updateXiaomiSecondaryButtonAction(actionOptions[idx].second)
                        },
                    )

                    // 焦点键单击 (焦点笔专属)
                    if (vm.xiaomiPencilModel.hasFocusKey) {
                        val focusClickTitle = actionOptions.find { it.second == vm.xiaomiFocusButtonAction }?.first ?: actionOptions[0].first
                        StylusDialogDropdownItem(
                            title = stringResource(R.string.stylus_xiaomi_focus_action),
                            currentText = focusClickTitle,
                            options = actionOptions.map { it.first },
                            onSelect = { idx ->
                                vm.updateXiaomiFocusButtonAction(actionOptions[idx].second)
                            },
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))

                // 提示卡片
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(colors.accent.copy(alpha = 0.08f))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_help_circle),
                        contentDescription = null,
                        tint = colors.accent,
                        modifier = Modifier
                            .size(16.dp)
                            .padding(top = 1.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.stylus_xiaomi_hint),
                        color = colors.subText,
                        fontSize = 11.5.sp,
                        lineHeight = 16.sp,
                    )
                }

                Spacer(Modifier.height(18.dp))

                StylusDialogDoneButton(onClick = onDismiss)
            }
        }
    }
}
