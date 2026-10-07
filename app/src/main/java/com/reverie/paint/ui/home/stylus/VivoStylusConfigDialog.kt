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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import com.reverie.paint.R
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.ui.theme.Theme

@Composable
internal fun VivoStylusConfigDialog(
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
        "AUTO" to stringResource(R.string.stylus_vivo_auto_model, vm.detectedVivoPencilModel.editionName),
        "VIVO_PENCIL2" to stringResource(R.string.stylus_vivo_model_pencil2),
        "VIVO_PENCIL2_NV" to stringResource(R.string.stylus_vivo_model_pencil2_nv),
        "VIVO_PENCIL2S" to stringResource(R.string.stylus_vivo_model_pencil2s),
        "VIVO_PENCIL3" to stringResource(R.string.stylus_vivo_model_pencil3),
        "VIVO_PENCIL1" to stringResource(R.string.stylus_vivo_model_pencil1),
    )

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .widthIn(max = 460.dp)
                .heightIn(max = 680.dp)
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
                    badgeText = "vivo",
                    title = stringResource(R.string.stylus_vivo_title),
                    onClose = onDismiss,
                )

                Spacer(Modifier.height(8.dp))

                // 硬件型号
                StylusDialogSectionTitle(stringResource(R.string.stylus_vivo_model))
                StylusDialogCard {
                    val currentModelTitle = when (vm.vivoPencilModelMode) {
                        "VIVO_PENCIL2" -> stringResource(R.string.stylus_vivo_model_pencil2)
                        "VIVO_PENCIL2_NV" -> stringResource(R.string.stylus_vivo_model_pencil2_nv)
                        "VIVO_PENCIL2S" -> stringResource(R.string.stylus_vivo_model_pencil2s)
                        "VIVO_PENCIL3" -> stringResource(R.string.stylus_vivo_model_pencil3)
                        "VIVO_PENCIL1" -> stringResource(R.string.stylus_vivo_model_pencil1)
                        else -> stringResource(R.string.stylus_vivo_auto_model, vm.detectedVivoPencilModel.editionName)
                    }
                    StylusDialogDropdownItem(
                        title = stringResource(R.string.stylus_vivo_model),
                        currentText = currentModelTitle,
                        options = modelOptions.map { it.second },
                        onSelect = { idx ->
                            vm.updateVivoPencilModelMode(modelOptions[idx].first)
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
                                text = vm.vivoPencilModel.displayName,
                                color = colors.text,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            if (!vm.vivoPencilModel.hasWritingVibrate) {
                                Spacer(Modifier.width(8.dp))
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(colors.panel)
                                        .padding(horizontal = 6.dp, vertical = 2.dp),
                                ) {
                                    Text(
                                        text = stringResource(R.string.stylus_vivo_no_vibrate_badge),
                                        color = colors.subText,
                                        fontSize = 10.5.sp,
                                        fontWeight = FontWeight.Medium,
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = vm.vivoPencilModel.desc,
                            color = colors.subText,
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                        )
                    }
                }

                // 笔身双击手势 (电容膜笔身型号)
                if (vm.vivoPencilModel.hasDoubleTap) {
                    StylusDialogSectionTitle(stringResource(R.string.stylus_gesture_and_keys))
                    StylusDialogCard {
                        val doubleTapTitle = actionOptions.find { it.second == vm.vivoDoubleTapAction }?.first
                            ?: actionOptions[0].first
                        StylusDialogDropdownItem(
                            title = stringResource(R.string.stylus_vivo_double_tap_action),
                            currentText = doubleTapTitle,
                            options = actionOptions.map { it.first },
                            onSelect = { idx ->
                                vm.updateVivoDoubleTapAction(actionOptions[idx].second)
                            },
                        )
                    }
                }

                // 物理按键型号: 单击手势映射 + 按住按键临时橡皮
                if (vm.vivoPencilModel.hasPhysicalButtons) {
                    StylusDialogSectionTitle(stringResource(R.string.stylus_vivo_key_mapping))
                    StylusDialogCard {
                        val primaryTitle = actionOptions.find { it.second == vm.vivoPrimaryClickAction }?.first
                            ?: actionOptions[0].first
                        StylusDialogDropdownItem(
                            title = stringResource(R.string.stylus_vivo_primary_action),
                            currentText = primaryTitle,
                            options = actionOptions.map { it.first },
                            onSelect = { idx ->
                                vm.updateVivoPrimaryClickAction(actionOptions[idx].second)
                            },
                        )
                        StylusDialogDivider()
                        val secondaryTitle = actionOptions.find { it.second == vm.vivoSecondaryClickAction }?.first
                            ?: actionOptions[0].first
                        StylusDialogDropdownItem(
                            title = stringResource(R.string.stylus_vivo_secondary_action),
                            currentText = secondaryTitle,
                            options = actionOptions.map { it.first },
                            onSelect = { idx ->
                                vm.updateVivoSecondaryClickAction(actionOptions[idx].second)
                            },
                        )
                    }

                    StylusDialogSectionTitle(stringResource(R.string.stylus_vivo_side_key))
                    StylusDialogCard {
                        StylusDialogSwitchItem(
                            title = stringResource(R.string.stylus_vivo_hold_eraser),
                            summary = stringResource(R.string.stylus_vivo_hold_eraser_desc),
                            checked = vm.vivoSideButtonErase,
                            onCheckedChange = { vm.updateVivoSideButtonErase(it) },
                        )
                    }
                }

                // 书写振动 (仅带笔身马达的型号)
                if (vm.vivoPencilModel.hasWritingVibrate) {
                    StylusDialogSectionTitle(stringResource(R.string.stylus_vivo_vibrate))
                    StylusDialogCard {
                        StylusDialogSwitchItem(
                            title = stringResource(R.string.stylus_vivo_vibrate_pen),
                            summary = stringResource(R.string.stylus_vivo_vibrate_pen_desc),
                            checked = vm.vivoWritingVibrateEnabled,
                            onCheckedChange = { vm.updateVivoWritingVibrateEnabled(it) },
                        )
                    }
                }

                // 笔迹预测 (复用全局预测开关, 与 OPPO 对话框口径一致)
                StylusDialogSectionTitle(stringResource(R.string.stylus_vivo_prediction))
                StylusDialogCard {
                    StylusDialogSwitchItem(
                        title = stringResource(R.string.stylus_vivo_prediction_enable),
                        summary = stringResource(R.string.stylus_vivo_prediction_desc),
                        checked = vm.stylusStrokePredictionEnabled,
                        onCheckedChange = { vm.updateStylusStrokePredictionEnabled(it) },
                    )
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
                        text = stringResource(R.string.stylus_vivo_hint),
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
