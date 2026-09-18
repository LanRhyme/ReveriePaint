/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.Brush
import androidx.compose.material.icons.rounded.ControlCamera
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Timeline
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.reverie.paint.R
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.core.stylus.StylusBrand
import com.reverie.paint.ui.home.stylus.CompactPressureCurveCard
import com.reverie.paint.ui.home.stylus.OppoStylusConfigDialog
import com.reverie.paint.ui.home.stylus.PressureCurveDetailDialog
import com.reverie.paint.ui.home.stylus.PressureCurveHelpDialog
import com.reverie.paint.ui.home.stylus.SamsungStylusConfigDialog
import com.reverie.paint.ui.theme.Theme

@Composable
internal fun StylusSettingsSubPage(
    vm: PaintViewModel,
    showBackButton: Boolean = true,
    compact: Boolean = false,
    onBack: () -> Unit,
) {
    val colors = Theme.current
    val context = LocalContext.current

    var showHelpDialog by remember { mutableStateOf(false) }
    var showPressureCurveDialog by remember { mutableStateOf(false) }
    var activeConfigBrand by remember { mutableStateOf<StylusBrand?>(null) }

    val stylusDriver = remember { vm.getOrCreateStylusDriver(context) }
    val detectedDevices = remember { stylusDriver.detectDevices() }

    val cursorModeOptions = listOf(
        stringResource(R.string.stylus_cursor_mode_none),
        stringResource(R.string.stylus_cursor_mode_drawing),
        stringResource(R.string.stylus_cursor_mode_hover),
        stringResource(R.string.stylus_cursor_mode_both),
    )
    val cursorStyleOptions = listOf(
        stringResource(R.string.stylus_cursor_shape_circle),
        stringResource(R.string.stylus_cursor_shape_crosshair),
        stringResource(R.string.stylus_cursor_shape_dot),
        stringResource(R.string.stylus_cursor_shape_none),
        stringResource(R.string.stylus_cursor_shape_system),
        stringResource(R.string.stylus_cursor_shape_circle_crosshair),
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.bg)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 680.dp)
                .padding(horizontal = if (compact) 12.dp else 20.dp, vertical = if (compact) 12.dp else 20.dp),
        ) {
            SettingSubPageHeader(
                title = stringResource(R.string.settings_stylus),
                subtitle = stringResource(R.string.stylus_settings_desc),
                showBackButton = showBackButton,
                compact = compact,
                onBack = onBack,
            )

            // 1. 触控与光标设置
            SettingCategoryTitle(stringResource(R.string.stylus_touch_and_cursor))
            SettingGroup {
                SettingSwitchGroupItem(
                    icon = Icons.Rounded.Edit,
                    title = stringResource(R.string.settings_pen_mode),
                    summary = stringResource(R.string.stylus_pen_mode_desc),
                    checked = vm.penOnlyMode,
                    shape = settingGroupShape(0, 5),
                    onCheckedChange = { vm.updatePenOnlyMode(it) },
                )
                SettingSwitchGroupItem(
                    icon = Icons.Rounded.Speed,
                    title = stringResource(R.string.stylus_prediction_title),
                    summary = stringResource(R.string.stylus_prediction_desc),
                    checked = vm.stylusStrokePredictionEnabled,
                    shape = settingGroupShape(1, 5),
                    onCheckedChange = { vm.updateStylusStrokePredictionEnabled(it) },
                )
                SettingDropdownGroupItem(
                    icon = Icons.Rounded.Brush,
                    title = stringResource(R.string.stylus_brush_cursor),
                    summary = stringResource(R.string.stylus_brush_cursor_desc),
                    currentText = cursorModeOptions.getOrElse(vm.brushCursorMode) { cursorModeOptions[0] },
                    options = cursorModeOptions,
                    shape = settingGroupShape(2, 5),
                    onSelect = { vm.updateBrushCursorMode(it) },
                )
                SettingDropdownGroupItem(
                    icon = Icons.Rounded.AutoFixHigh,
                    title = stringResource(R.string.stylus_eraser_cursor),
                    summary = stringResource(R.string.stylus_eraser_cursor_desc),
                    currentText = cursorModeOptions.getOrElse(vm.eraserCursorMode) { cursorModeOptions.last() },
                    options = cursorModeOptions,
                    shape = settingGroupShape(3, 5),
                    onSelect = { vm.updateEraserCursorMode(it) },
                )
                SettingDropdownGroupItem(
                    icon = Icons.Rounded.ControlCamera,
                    title = stringResource(R.string.stylus_cursor_style),
                    summary = stringResource(R.string.stylus_cursor_style_desc),
                    currentText = cursorStyleOptions.getOrElse(vm.cursorStyleMode) { cursorStyleOptions[0] },
                    options = cursorStyleOptions,
                    shape = settingGroupShape(4, 5),
                    onSelect = { vm.updateCursorStyleMode(it) },
                )
            }

            // 2. 真实书写音效
            SettingCategoryTitle(stringResource(R.string.stylus_sound_title))
            SettingGroup {
                val audioTotal = if (vm.stylusAudioEnabled) 2 else 1
                SettingSwitchGroupItem(
                    icon = Icons.AutoMirrored.Rounded.VolumeUp,
                    title = stringResource(R.string.stylus_sound_paper),
                    summary = stringResource(R.string.stylus_sound_paper_desc),
                    checked = vm.stylusAudioEnabled,
                    shape = settingGroupShape(0, audioTotal),
                    onCheckedChange = { vm.updateStylusAudioEnabled(it) },
                )
                if (vm.stylusAudioEnabled) {
                    SettingSliderGroupItem(
                        icon = Icons.AutoMirrored.Rounded.VolumeDown,
                        title = stringResource(R.string.stylus_sound_volume),
                        summary = stringResource(R.string.stylus_sound_volume_desc),
                        valueText = "${(vm.stylusAudioVolume * 100).toInt()}%",
                        sliderFraction = vm.stylusAudioVolume,
                        shape = settingGroupShape(1, audioTotal),
                        onValueChange = { vm.updateStylusAudioVolume(it) },
                    )
                }
            }

            // 3. 全局压力曲线
            SettingCategoryTitle(stringResource(R.string.stylus_curve_title))
            SettingGroup {
                SettingCardBox(shape = settingGroupShape(0, 1)) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            SettingIcon(icon = Icons.Rounded.Timeline, tint = colors.icon)
                            Spacer(Modifier.width(14.dp))
                            Column {
                                Text(
                                    text = stringResource(R.string.stylus_curve_mapping),
                                    color = colors.text,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = stringResource(R.string.stylus_curve_mapping_desc),
                                    color = colors.subText,
                                    fontSize = 12.sp,
                                    lineHeight = 16.sp,
                                )
                            }
                        }

                        Spacer(Modifier.height(14.dp))

                        // 紧凑只读曲线卡片 (零垂直滚动冲突，点击唤起微调弹窗)
                        CompactPressureCurveCard(
                            points = vm.pressureControlPoints,
                            presetIndex = vm.pressureCurvePreset,
                            onSelectPreset = { vm.updatePressureCurvePreset(it) },
                            onOpenEditDialog = { showPressureCurveDialog = true },
                            onOpenHelpDialog = { showHelpDialog = true },
                        )
                    }
                }
            }

            // 4. 已适配的手写笔品牌与设备
            SettingCategoryTitle(stringResource(R.string.stylus_brand_devices))
            SettingGroup {
                detectedDevices.forEachIndexed { index, device ->
                    val shape = settingGroupShape(index, detectedDevices.size)
                    when (device.brand) {
                        StylusBrand.OPPO_ONEPLUS -> {
                            SettingStylusDeviceRow(
                                title = device.deviceName,
                                summary = stringResource(R.string.stylus_oppo_features),
                                isCurrentDevice = device.isCurrentDeviceSupported,
                                isConnected = device.isConnected,
                                shape = shape,
                                onClick = { activeConfigBrand = StylusBrand.OPPO_ONEPLUS },
                            )
                        }
                        StylusBrand.SAMSUNG_SPEN -> {
                            SettingStylusDeviceRow(
                                title = device.deviceName,
                                summary = stringResource(R.string.stylus_samsung_features),
                                isCurrentDevice = device.isCurrentDeviceSupported,
                                isConnected = device.isConnected,
                                shape = shape,
                                onClick = { activeConfigBrand = StylusBrand.SAMSUNG_SPEN },
                            )
                        }
                        StylusBrand.GENERIC -> {
                            SettingStylusDeviceRow(
                                title = device.deviceName,
                                summary = stringResource(R.string.stylus_generic_features),
                                isCurrentDevice = device.isCurrentDeviceSupported,
                                isConnected = device.isConnected,
                                shape = shape,
                                onClick = null,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(80.dp))
        }
    }

    if (showHelpDialog) {
        PressureCurveHelpDialog(onDismiss = { showHelpDialog = false })
    }

    if (showPressureCurveDialog) {
        PressureCurveDetailDialog(
            points = vm.pressureControlPoints,
            presetIndex = vm.pressureCurvePreset,
            onPointsChanged = { newPoints ->
                vm.updateCustomPressureCurve(newPoints)
            },
            onSelectPreset = { vm.updatePressureCurvePreset(it) },
            onDismiss = { showPressureCurveDialog = false },
        )
    }

    when (activeConfigBrand) {
        StylusBrand.OPPO_ONEPLUS -> {
            OppoStylusConfigDialog(vm = vm, onDismiss = { activeConfigBrand = null })
        }
        StylusBrand.SAMSUNG_SPEN -> {
            SamsungStylusConfigDialog(vm = vm, onDismiss = { activeConfigBrand = null })
        }
        else -> {}
    }
}
