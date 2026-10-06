/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.home.stylus

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.window.DialogProperties
import com.reverie.paint.R
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.ui.theme.Theme

@Composable
internal fun HonorStylusConfigDialog(
    vm: PaintViewModel,
    onDismiss: () -> Unit,
) {
    val colors = Theme.current
    val actionOptions = listOf(
        stringResource(R.string.stylus_action_switch_brush_eraser) to "toggle_eraser",
        stringResource(R.string.stylus_action_follow_system) to "system",
        stringResource(R.string.stylus_action_undo) to "undo",
        stringResource(R.string.stylus_action_redo) to "redo",
        stringResource(R.string.stylus_action_eyedropper) to "tool_picker",
        stringResource(R.string.stylus_action_prev_tool) to "toggle_last_tool",
        stringResource(R.string.stylus_action_quick_palette) to "tool_color",
        stringResource(R.string.stylus_action_none) to "none",
    )

    val modelOptions = listOf(
        "AUTO" to stringResource(R.string.stylus_honor_auto_model, vm.detectedHonorPencilModel.editionName),
        "MAGIC_PENCIL_3" to stringResource(R.string.stylus_honor_model_gen3),
        "MAGIC_PENCIL_2" to stringResource(R.string.stylus_honor_model_gen2),
        "MAGIC_PEN" to stringResource(R.string.stylus_honor_model_pen),
    )

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .widthIn(max = 460.dp)
                .heightIn(max = 700.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(colors.panel)
                .padding(20.dp),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                StylusDialogHeader(
                    badgeText = "HONOR",
                    title = stringResource(R.string.stylus_honor_title),
                    onClose = onDismiss,
                )

                Spacer(Modifier.height(8.dp))

                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                ) {
                    // 1. 设备硬件规格与型号选择
                    StylusDialogSectionTitle(stringResource(R.string.stylus_honor_model))
                    StylusDialogCard {
                        val currentModelText = when (vm.honorPencilModelMode) {
                            "MAGIC_PENCIL_3" -> stringResource(R.string.stylus_honor_model_gen3)
                            "MAGIC_PENCIL_2" -> stringResource(R.string.stylus_honor_model_gen2)
                            "MAGIC_PEN" -> stringResource(R.string.stylus_honor_model_pen)
                            else -> stringResource(R.string.stylus_honor_auto_model, vm.detectedHonorPencilModel.editionName)
                        }
                        StylusDialogDropdownItem(
                            title = stringResource(R.string.stylus_honor_model),
                            currentText = currentModelText,
                            options = modelOptions.map { it.second },
                            onSelect = { idx ->
                                vm.updateHonorPencilModelMode(modelOptions[idx].first)
                            },
                        )
                        StylusDialogDivider()
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = vm.honorPencilModel.displayName,
                                    color = colors.text,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Spacer(Modifier.width(8.dp))
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(colors.accent.copy(alpha = 0.2f))
                                        .padding(horizontal = 6.dp, vertical = 2.dp),
                                ) {
                                    Text(
                                        text = stringResource(R.string.stylus_honor_press_4k),
                                        color = colors.accent,
                                        fontSize = 10.5.sp,
                                        fontWeight = FontWeight.Medium,
                                    )
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = vm.honorPencilModel.desc,
                                color = colors.subText,
                                fontSize = 11.sp,
                                lineHeight = 15.sp,
                            )
                        }
                    }

                    // 2. 笔身双击手势
                    if (!vm.honorPencilModel.hasPhysicalButton) {
                        StylusDialogSectionTitle(stringResource(R.string.stylus_honor_double_tap))
                        StylusDialogCard {
                            val doubleClickTitle = actionOptions.find { it.second == vm.honorDoubleTapAction }?.first
                                ?: actionOptions[0].first
                            StylusDialogDropdownItem(
                                title = stringResource(R.string.stylus_honor_double_tap_action),
                                currentText = doubleClickTitle,
                                options = actionOptions.map { it.first },
                                onSelect = { idx ->
                                    vm.updateHonorDoubleTapAction(actionOptions[idx].second)
                                },
                            )
                        }
                    }

                    // 3. 物理侧键行为 (折叠屏手写笔 Magic-Pen)
                    if (vm.honorPencilModel.hasPhysicalButton) {
                        StylusDialogSectionTitle(stringResource(R.string.stylus_honor_side_key))
                        StylusDialogCard {
                            StylusDialogSwitchItem(
                                title = stringResource(R.string.stylus_honor_hold_eraser),
                                summary = stringResource(R.string.stylus_honor_hold_eraser_desc),
                                checked = vm.honorSideButtonErase,
                                onCheckedChange = { vm.updateHonorSideButtonErase(it) },
                            )
                            StylusDialogDivider()
                            val singleClickTitle = actionOptions.find { it.second == vm.honorSingleClickAction }?.first
                                ?: actionOptions.last().first
                            StylusDialogDropdownItem(
                                title = stringResource(R.string.stylus_honor_click_action),
                                currentText = singleClickTitle,
                                options = actionOptions.map { it.first },
                                onSelect = { idx ->
                                    vm.updateHonorSingleClickAction(actionOptions[idx].second)
                                },
                            )
                        }
                    }

                    // 4. 触觉反馈
                    StylusDialogSectionTitle(stringResource(R.string.stylus_honor_haptics))
                    StylusDialogCard {
                        StylusDialogSwitchItem(
                            title = stringResource(R.string.stylus_honor_haptics_title),
                            summary = stringResource(R.string.stylus_honor_haptics_desc),
                            checked = vm.honorHapticsEnabled,
                            onCheckedChange = { vm.updateHonorHapticsEnabled(it) },
                        )
                    }

                    Spacer(Modifier.height(14.dp))

                    // 5. 提示卡片
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
                            text = stringResource(R.string.stylus_honor_hint),
                            color = colors.subText,
                            fontSize = 11.5.sp,
                            lineHeight = 16.sp,
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                StylusDialogDoneButton(onClick = onDismiss)
            }
        }
    }
}
