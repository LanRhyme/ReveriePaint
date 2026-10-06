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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.reverie.paint.R
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.core.stylus.XiaomiPencilModel
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
        "FOCUS_PEN_PRO" to stringResource(R.string.stylus_xiaomi_focus_pro_model),
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
                    badgeText = "Xiaomi",
                    title = stringResource(R.string.stylus_xiaomi_title),
                    onClose = onDismiss,
                )

                Spacer(Modifier.height(8.dp))

                // 硬件型号
                StylusDialogSectionTitle(stringResource(R.string.stylus_xiaomi_model))
                StylusDialogCard {
                    val currentModelTitle = when (vm.xiaomiPencilModelMode) {
                        "FOCUS_PEN_PRO" -> stringResource(R.string.stylus_xiaomi_focus_pro_model)
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
                    StylusDialogDivider()
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                    ) {
                        val isPro = vm.xiaomiPencilModel == XiaomiPencilModel.FOCUS_PEN_PRO
                        val isFocus = vm.xiaomiPencilModel == XiaomiPencilModel.FOCUS_PEN
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = vm.xiaomiPencilModel.displayName,
                                color = colors.text,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(Modifier.width(8.dp))
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (isPro || isFocus) colors.accent.copy(alpha = 0.2f) else colors.panel)
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                            ) {
                                Text(
                                    text = when {
                                        isPro -> stringResource(R.string.stylus_xiaomi_press_16k)
                                        isFocus -> stringResource(R.string.stylus_xiaomi_press_8k)
                                        else -> stringResource(R.string.stylus_xiaomi_press_4k)
                                    },
                                    color = if (isPro || isFocus) colors.accent else colors.subText,
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = vm.xiaomiPencilModel.desc,
                            color = colors.subText,
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                        )
                    }
                }

                // 触控手势映射 (Focus Pen Pro 专属)
                if (vm.xiaomiPencilModel.hasSqueezeGesture || vm.xiaomiPencilModel.hasSlideGesture) {
                    StylusDialogSectionTitle(stringResource(R.string.stylus_xiaomi_gestures))
                    StylusDialogCard {
                        // 轻捏动作
                        val squeezeTitle = actionOptions.find { it.second == vm.xiaomiSqueezeAction }?.first ?: actionOptions[5].first
                        StylusDialogDropdownItem(
                            title = stringResource(R.string.stylus_xiaomi_squeeze_action),
                            currentText = squeezeTitle,
                            options = actionOptions.map { it.first },
                            onSelect = { idx ->
                                vm.updateXiaomiSqueezeAction(actionOptions[idx].second)
                            },
                        )

                        // 触控双击动作
                        StylusDialogDivider()
                        val doubleClickTitle = actionOptions.find { it.second == vm.xiaomiDoubleTapAction }?.first ?: actionOptions[0].first
                        StylusDialogDropdownItem(
                            title = stringResource(R.string.stylus_oppo_double_tap),
                            currentText = doubleClickTitle,
                            options = actionOptions.map { it.first },
                            onSelect = { idx ->
                                vm.updateXiaomiDoubleTapAction(actionOptions[idx].second)
                            },
                        )

                        // 笔身触控滑动动作
                        StylusDialogDivider()
                        val slideActionOptions = listOf(
                            stringResource(R.string.stylus_slide_brush_size) to "adjust_brush_size",
                            stringResource(R.string.stylus_slide_opacity) to "adjust_opacity",
                            stringResource(R.string.stylus_slide_undo_redo) to "undo_redo",
                            stringResource(R.string.stylus_action_none) to "none",
                        )
                        val slideTitle = slideActionOptions.find { it.second == vm.xiaomiSlideAction }?.first ?: slideActionOptions[0].first
                        StylusDialogDropdownItem(
                            title = stringResource(R.string.stylus_xiaomi_slide_action),
                            currentText = slideTitle,
                            options = slideActionOptions.map { it.first },
                            onSelect = { idx ->
                                vm.updateXiaomiSlideAction(slideActionOptions[idx].second)
                            },
                        )

                        // 滑动灵敏度
                        if (vm.xiaomiSlideAction != "none") {
                            StylusDialogDivider()
                            val sensitivityOptions = listOf(
                                stringResource(R.string.stylus_oppo_sens_low) to "low",
                                stringResource(R.string.stylus_oppo_sens_standard) to "normal",
                                stringResource(R.string.stylus_oppo_sens_high) to "high",
                            )
                            val currentSensitivityTitle = sensitivityOptions.find { it.second == vm.xiaomiSlideSensitivity }?.first ?: sensitivityOptions[1].first
                            StylusDialogDropdownItem(
                                title = stringResource(R.string.stylus_xiaomi_slide_sens),
                                currentText = currentSensitivityTitle,
                                options = sensitivityOptions.map { it.first },
                                onSelect = { idx ->
                                    vm.updateXiaomiSlideSensitivity(sensitivityOptions[idx].second)
                                },
                            )
                        }
                    }

                    // 触感反馈与微震 (Focus Pen Pro 专属)
                    StylusDialogSectionTitle(stringResource(R.string.stylus_xiaomi_haptics))
                    StylusDialogCard {
                        StylusDialogSwitchItem(
                            title = stringResource(R.string.stylus_xiaomi_haptics_pen),
                            summary = stringResource(R.string.stylus_xiaomi_haptics_pen_desc),
                            checked = vm.xiaomiInPenHapticsEnabled,
                            onCheckedChange = { vm.updateXiaomiInPenHapticsEnabled(it) },
                        )
                    }
                }

                // 物理实体按键行为 (传统实体按键型号专属)
                if (vm.xiaomiPencilModel.hasPhysicalButtons) {
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

                        // 主键双击 (仅支持双击的型号展示)
                        if (vm.xiaomiPencilModel.hasDoubleTap) {
                            StylusDialogDivider()
                            val doubleClickTitle = actionOptions.find { it.second == vm.xiaomiDoubleTapAction }?.first ?: actionOptions[0].first
                            StylusDialogDropdownItem(
                                title = stringResource(R.string.stylus_xiaomi_double_tap_action),
                                currentText = doubleClickTitle,
                                options = actionOptions.map { it.first },
                                onSelect = { idx ->
                                    vm.updateXiaomiDoubleTapAction(actionOptions[idx].second)
                                },
                            )
                        }

                        // 副键单击
                        StylusDialogDivider()
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
                            StylusDialogDivider()
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
