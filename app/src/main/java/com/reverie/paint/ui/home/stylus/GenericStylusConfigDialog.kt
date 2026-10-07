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
import com.reverie.paint.R
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.ui.theme.Theme

@Composable
internal fun GenericStylusConfigDialog(
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
                    badgeText = "Android",
                    title = stringResource(R.string.stylus_generic_title),
                    onClose = onDismiss,
                )

                Spacer(Modifier.height(8.dp))

                // 通用协议按键映射主开关
                StylusDialogCard {
                    StylusDialogSwitchItem(
                        title = stringResource(R.string.stylus_generic_enable_protocol),
                        summary = stringResource(R.string.stylus_generic_enable_protocol_desc),
                        checked = vm.genericStylusEnabled,
                        onCheckedChange = { vm.updateGenericStylusEnabled(it) },
                    )
                }

                if (vm.genericStylusEnabled) {
                    // 侧键行为
                    StylusDialogSectionTitle(stringResource(R.string.stylus_generic_side_key))
                    StylusDialogCard {
                        StylusDialogSwitchItem(
                            title = stringResource(R.string.stylus_generic_hold_eraser),
                            summary = stringResource(R.string.stylus_generic_hold_eraser_desc),
                            checked = vm.genericSideButtonErase,
                            onCheckedChange = { vm.updateGenericSideButtonErase(it) },
                        )
                    }

                    // 按键动作映射
                    StylusDialogSectionTitle(stringResource(R.string.stylus_generic_key_mapping))
                    StylusDialogCard {
                        // 主侧键单击
                        val primaryClickTitle = actionOptions.find { it.second == vm.genericPrimaryButtonAction }?.first ?: actionOptions[0].first
                        StylusDialogDropdownItem(
                            title = stringResource(R.string.stylus_generic_primary_action),
                            currentText = primaryClickTitle,
                            options = actionOptions.map { it.first },
                            onSelect = { idx ->
                                vm.updateGenericPrimaryButtonAction(actionOptions[idx].second)
                            },
                        )

                        // 副侧键 / 笔尾按键单击
                        val secondaryClickTitle = actionOptions.find { it.second == vm.genericSecondaryButtonAction }?.first ?: actionOptions[0].first
                        StylusDialogDropdownItem(
                            title = stringResource(R.string.stylus_generic_secondary_action),
                            currentText = secondaryClickTitle,
                            options = actionOptions.map { it.first },
                            onSelect = { idx ->
                                vm.updateGenericSecondaryButtonAction(actionOptions[idx].second)
                            },
                        )
                    }
                }

                // 物理橡皮尾说明卡片
                StylusDialogSectionTitle(stringResource(R.string.stylus_generic_physical_eraser))
                StylusDialogCard {
                    StylusDialogSwitchItem(
                        title = stringResource(R.string.stylus_generic_physical_eraser),
                        summary = stringResource(R.string.stylus_generic_physical_eraser_desc),
                        checked = true,
                        onCheckedChange = {},
                        enabled = false,
                    )
                }

                Spacer(Modifier.height(18.dp))

                StylusDialogDoneButton(onClick = onDismiss)
            }
        }
    }
}
