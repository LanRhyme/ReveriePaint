/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.home.stylus

import android.content.Intent
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.reverie.paint.R
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.core.stylus.OppoPencilModel
import com.reverie.paint.ui.components.ReTextButton
import com.reverie.paint.ui.home.GroupedSettingsCard
import com.reverie.paint.ui.home.SettingCategoryHeader
import com.reverie.paint.ui.home.SettingDropdownRow
import com.reverie.paint.ui.home.SettingNavRow
import com.reverie.paint.ui.home.SettingSliderRow
import com.reverie.paint.ui.home.SettingSwitchRow
import com.reverie.paint.ui.home.SettingsCardDivider
import com.reverie.paint.ui.theme.Theme

@Composable
internal fun OppoStylusConfigDialog(
    vm: PaintViewModel,
    onDismiss: () -> Unit,
) {
    val colors = Theme.current
    val context = LocalContext.current
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
        "AUTO" to stringResource(R.string.stylus_oppo_auto_model, vm.detectedOppoPencilModel.editionName),
        "PRO" to "OPPO Pencil 2 Pro / OnePlus Stylo 2",
        "STANDARD" to stringResource(R.string.stylus_oppo_standard_model),
    )

    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .width(440.dp)
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
                        text = stringResource(R.string.stylus_oppo_title),
                        color = colors.text,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(colors.panelHi)
                            .clickable(onClick = onDismiss),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_x),
                            contentDescription = stringResource(R.string.common_close),
                            tint = colors.subText,
                            modifier = Modifier.size(15.dp),
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))

                // 设备硬件规格与型号选择
                GroupedSettingsCard(containerColor = colors.panelHi) {
                    val currentModelText = when (vm.oppoPencilModelMode) {
                        "PRO" -> "OPPO Pencil 2 Pro / OnePlus Stylo 2"
                        "STANDARD" -> stringResource(R.string.stylus_oppo_standard_model)
                        else -> stringResource(R.string.stylus_oppo_auto_model, vm.detectedOppoPencilModel.editionName)
                    }
                    SettingDropdownRow(
                        title = stringResource(R.string.stylus_oppo_model),
                        currentText = currentModelText,
                        options = modelOptions.map { it.second },
                        onSelect = { idx ->
                            vm.updateOppoPencilModelMode(modelOptions[idx].first)
                        },
                    )
                    SettingsCardDivider()
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = vm.oppoPencilModel.displayName,
                                color = colors.text,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = if (vm.oppoPencilModel == OppoPencilModel.PRO) stringResource(R.string.stylus_oppo_press_16k) else stringResource(R.string.stylus_oppo_press_4k),
                                color = colors.accent,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                        Spacer(Modifier.height(3.dp))
                        Text(
                            text = vm.oppoPencilModel.desc,
                            color = colors.subText,
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                SettingCategoryHeader(stringResource(R.string.stylus_gesture_and_keys))
                GroupedSettingsCard(containerColor = colors.panelHi) {
                    val currentTitle = actionOptions.find { it.second == vm.oppoDoubleTapAction }?.first ?: actionOptions[0].first
                    SettingDropdownRow(
                        title = stringResource(R.string.stylus_oppo_double_tap),
                        currentText = currentTitle,
                        options = actionOptions.map { it.first },
                        onSelect = { idx ->
                            vm.updateOppoDoubleTapAction(actionOptions[idx].second)
                        },
                    )
                    if (vm.oppoPencilModel.hasSlideGesture) {
                        SettingsCardDivider()
                        val slideActionOptions = listOf(
                            stringResource(R.string.stylus_slide_brush_size) to "adjust_brush_size",
                            stringResource(R.string.stylus_slide_opacity) to "adjust_opacity",
                            stringResource(R.string.stylus_slide_undo_redo) to "undo_redo",
                            stringResource(R.string.stylus_action_none) to "none",
                        )
                        val slideTitle = slideActionOptions.find { it.second == vm.oppoSlideAction }?.first ?: slideActionOptions[0].first
                        SettingDropdownRow(
                            title = stringResource(R.string.stylus_oppo_slide),
                            currentText = slideTitle,
                            options = slideActionOptions.map { it.first },
                            onSelect = { idx ->
                                vm.updateOppoSlideAction(slideActionOptions[idx].second)
                            },
                        )
                        if (vm.oppoSlideAction != "none") {
                            SettingsCardDivider()
                            val sensitivityOptions = listOf(
                                stringResource(R.string.stylus_oppo_sens_low) to "low",
                                stringResource(R.string.stylus_oppo_sens_standard) to "normal",
                                stringResource(R.string.stylus_oppo_sens_high) to "high",
                            )
                            val currentSensitivityTitle = sensitivityOptions.find { it.second == vm.oppoSlideSensitivity }?.first ?: sensitivityOptions[1].first
                            SettingDropdownRow(
                                title = stringResource(R.string.stylus_oppo_slide_sens),
                                currentText = currentSensitivityTitle,
                                options = sensitivityOptions.map { it.first },
                                onSelect = { idx ->
                                    vm.updateOppoSlideSensitivity(sensitivityOptions[idx].second)
                                },
                            )
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                SettingCategoryHeader(stringResource(R.string.stylus_oppo_haptics))
                GroupedSettingsCard(containerColor = colors.panelHi) {
                    SettingSwitchRow(
                        title = stringResource(R.string.stylus_oppo_haptics_pen),
                        summary = stringResource(R.string.stylus_oppo_haptics_pen_desc),
                        checked = vm.oppoInPenHapticsEnabled,
                        onCheckedChange = { vm.updateOppoInPenHapticsEnabled(it) },
                    )
                    SettingsCardDivider()
                    SettingSwitchRow(
                        title = stringResource(R.string.stylus_oppo_haptics_gesture),
                        summary = stringResource(R.string.stylus_oppo_haptics_gesture_desc),
                        checked = vm.stylusHapticsEnabled,
                        onCheckedChange = { vm.updateStylusHapticsEnabled(it) },
                    )
                    if (vm.stylusHapticsEnabled) {
                        SettingsCardDivider()
                        SettingSliderRow(
                            title = stringResource(R.string.stylus_oppo_haptics_strength),
                            summary = "${(vm.stylusHapticsIntensity * 100).toInt()}%",
                            value = vm.stylusHapticsIntensity,
                            onValueChange = { vm.updateStylusHapticsIntensity(it) },
                        )
                    }
                    SettingsCardDivider()
                    val openFailedMsg = stringResource(R.string.stylus_oppo_open_failed)
                    SettingNavRow(
                        iconRes = R.drawable.ic_settings,
                        title = stringResource(R.string.stylus_oppo_system_settings),
                        summary = stringResource(R.string.stylus_oppo_system_settings_desc),
                        onClick = {
                            try {
                                val intent = Intent("com.android.settings.MANUFACTURER_APPLICATION_SETTING_TOUCH_FEEDBACK")
                                context.startActivity(intent)
                            } catch (_: Throwable) {
                                try {
                                    val intent2 = Intent("com.oplus.ipemanager.action.pencil_setting_from_notes")
                                    intent2.setPackage("com.oplus.ipemanager")
                                    context.startActivity(intent2)
                                } catch (_: Throwable) {
                                    vm.showActionToast(openFailedMsg, R.drawable.ic_help_circle)
                                }
                            }
                        },
                    )
                }

                Spacer(Modifier.height(12.dp))

                SettingCategoryHeader(stringResource(R.string.stylus_oppo_latency_title))
                GroupedSettingsCard(containerColor = colors.panelHi) {
                    SettingSwitchRow(
                        title = stringResource(R.string.stylus_prediction_title),
                        summary = stringResource(R.string.stylus_oppo_latency_desc),
                        checked = vm.stylusStrokePredictionEnabled,
                        onCheckedChange = { vm.updateStylusStrokePredictionEnabled(it) },
                    )
                }

                Spacer(Modifier.height(16.dp))

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
