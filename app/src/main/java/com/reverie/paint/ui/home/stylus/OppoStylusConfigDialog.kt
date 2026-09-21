/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.home.stylus

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.reverie.paint.R
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.core.stylus.OppoPencilModel
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
            Column(modifier = Modifier.fillMaxWidth()) {
                StylusDialogHeader(
                    badgeText = "OPPO / OnePlus",
                    title = stringResource(R.string.stylus_oppo_title),
                    onClose = onDismiss,
                )

                Spacer(Modifier.height(8.dp))

                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                ) {
                    // 设备硬件规格与型号选择
                    StylusDialogSectionTitle(stringResource(R.string.stylus_oppo_model))
                    StylusDialogCard {
                        val currentModelText = when (vm.oppoPencilModelMode) {
                            "PRO" -> "OPPO Pencil 2 Pro / OnePlus Stylo 2"
                            "STANDARD" -> stringResource(R.string.stylus_oppo_standard_model)
                            else -> stringResource(R.string.stylus_oppo_auto_model, vm.detectedOppoPencilModel.editionName)
                        }
                        StylusDialogDropdownItem(
                            title = stringResource(R.string.stylus_oppo_model),
                            currentText = currentModelText,
                            options = modelOptions.map { it.second },
                            onSelect = { idx ->
                                vm.updateOppoPencilModelMode(modelOptions[idx].first)
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
                                    text = vm.oppoPencilModel.displayName,
                                    color = colors.text,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Spacer(Modifier.width(8.dp))
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(colors.accent.copy(alpha = 0.15f))
                                        .padding(horizontal = 6.dp, vertical = 2.dp),
                                ) {
                                    Text(
                                        text = if (vm.oppoPencilModel == OppoPencilModel.PRO) {
                                            stringResource(R.string.stylus_oppo_press_16k)
                                        } else {
                                            stringResource(R.string.stylus_oppo_press_4k)
                                        },
                                        color = colors.accent,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = vm.oppoPencilModel.desc,
                                color = colors.subText,
                                fontSize = 11.5.sp,
                                lineHeight = 16.sp,
                            )
                        }
                    }

                    // 手势与按键
                    StylusDialogSectionTitle(stringResource(R.string.stylus_gesture_and_keys))
                    StylusDialogCard {
                        val currentTitle = actionOptions.find { it.second == vm.oppoDoubleTapAction }?.first ?: actionOptions[0].first
                        StylusDialogDropdownItem(
                            title = stringResource(R.string.stylus_oppo_double_tap),
                            currentText = currentTitle,
                            options = actionOptions.map { it.first },
                            onSelect = { idx ->
                                vm.updateOppoDoubleTapAction(actionOptions[idx].second)
                            },
                        )
                        if (vm.oppoPencilModel.hasSlideGesture) {
                            StylusDialogDivider()
                            val slideActionOptions = listOf(
                                stringResource(R.string.stylus_slide_brush_size) to "adjust_brush_size",
                                stringResource(R.string.stylus_slide_opacity) to "adjust_opacity",
                                stringResource(R.string.stylus_slide_undo_redo) to "undo_redo",
                                stringResource(R.string.stylus_action_none) to "none",
                            )
                            val slideTitle = slideActionOptions.find { it.second == vm.oppoSlideAction }?.first ?: slideActionOptions[0].first
                            StylusDialogDropdownItem(
                                title = stringResource(R.string.stylus_oppo_slide),
                                currentText = slideTitle,
                                options = slideActionOptions.map { it.first },
                                onSelect = { idx ->
                                    vm.updateOppoSlideAction(slideActionOptions[idx].second)
                                },
                            )
                            if (vm.oppoSlideAction != "none") {
                                StylusDialogDivider()
                                val sensitivityOptions = listOf(
                                    stringResource(R.string.stylus_oppo_sens_low) to "low",
                                    stringResource(R.string.stylus_oppo_sens_standard) to "normal",
                                    stringResource(R.string.stylus_oppo_sens_high) to "high",
                                )
                                val currentSensitivityTitle = sensitivityOptions.find { it.second == vm.oppoSlideSensitivity }?.first ?: sensitivityOptions[1].first
                                StylusDialogDropdownItem(
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

                    // 触觉微震反馈
                    StylusDialogSectionTitle(stringResource(R.string.stylus_oppo_haptics))
                    StylusDialogCard {
                        StylusDialogSwitchItem(
                            title = stringResource(R.string.stylus_oppo_haptics_pen),
                            summary = stringResource(R.string.stylus_oppo_haptics_pen_desc),
                            checked = vm.oppoInPenHapticsEnabled,
                            onCheckedChange = { vm.updateOppoInPenHapticsEnabled(it) },
                        )
                        StylusDialogDivider()
                        StylusDialogSwitchItem(
                            title = stringResource(R.string.stylus_oppo_haptics_gesture),
                            summary = stringResource(R.string.stylus_oppo_haptics_gesture_desc),
                            checked = vm.stylusHapticsEnabled,
                            onCheckedChange = { vm.updateStylusHapticsEnabled(it) },
                        )
                        if (vm.stylusHapticsEnabled) {
                            StylusDialogDivider()
                            StylusDialogSliderItem(
                                title = stringResource(R.string.stylus_oppo_haptics_strength),
                                valueText = "${(vm.stylusHapticsIntensity * 100).toInt()}%",
                                value = vm.stylusHapticsIntensity,
                                onValueChange = { vm.updateStylusHapticsIntensity(it) },
                            )
                        }
                        StylusDialogDivider()
                        val openFailedMsg = stringResource(R.string.stylus_oppo_open_failed)
                        StylusDialogNavItem(
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

                    // 笔画预测
                    StylusDialogSectionTitle(stringResource(R.string.stylus_oppo_latency_title))
                    StylusDialogCard {
                        StylusDialogSwitchItem(
                            title = stringResource(R.string.stylus_prediction_title),
                            summary = stringResource(R.string.stylus_oppo_latency_desc),
                            checked = vm.stylusStrokePredictionEnabled,
                            onCheckedChange = { vm.updateStylusStrokePredictionEnabled(it) },
                        )
                    }

                    Spacer(Modifier.height(14.dp))
                }

                Spacer(Modifier.height(14.dp))

                StylusDialogDoneButton(onClick = onDismiss)
            }
        }
    }
}
