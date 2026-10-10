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
import androidx.compose.material.icons.rounded.Bolt
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
import com.reverie.paint.ui.home.stylus.HonorStylusConfigDialog
import com.reverie.paint.ui.home.stylus.HuaweiStylusConfigDialog
import com.reverie.paint.ui.home.stylus.OppoStylusConfigDialog
import com.reverie.paint.ui.home.stylus.PressureCurveDetailDialog
import com.reverie.paint.ui.home.stylus.PressureCurveHelpDialog
import com.reverie.paint.ui.home.stylus.SamsungStylusConfigDialog
import com.reverie.paint.ui.home.stylus.VivoStylusConfigDialog
import com.reverie.paint.ui.home.stylus.XiaomiStylusConfigDialog
import com.reverie.paint.ui.home.stylus.GenericStylusConfigDialog
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
                val isPredictionOn = vm.stylusPredictionMasterEnabled
                val touchTotal = if (isPredictionOn) 8 else 6
                SettingSwitchGroupItem(
                    icon = Icons.Rounded.Edit,
                    title = stringResource(R.string.settings_pen_mode),
                    summary = stringResource(R.string.stylus_pen_mode_desc),
                    checked = vm.penOnlyMode,
                    shape = settingGroupShape(0, touchTotal),
                    onCheckedChange = { vm.updatePenOnlyMode(it) },
                )
                SettingSwitchGroupItem(
                    icon = R.drawable.ic_hand,
                    title = stringResource(R.string.settings_pen_mode_single_finger_pan_title),
                    summary = stringResource(R.string.settings_pen_mode_single_finger_pan_desc),
                    checked = vm.penModeSingleFingerPanEnabled,
                    shape = settingGroupShape(1, touchTotal),
                    onCheckedChange = { vm.updatePenModeSingleFingerPan(it) },
                )
                SettingSwitchGroupItem(
                    icon = Icons.Rounded.Speed,
                    title = stringResource(R.string.stylus_prediction_title),
                    summary = stringResource(R.string.stylus_prediction_desc),
                    checked = isPredictionOn,
                    shape = settingGroupShape(2, touchTotal),
                    onCheckedChange = { vm.updateStylusPredictionMaster(it) },
                )
                if (isPredictionOn) {
                    SettingRadioGroupItem(
                        title = stringResource(R.string.stylus_prediction_algo_hardware),
                        summary = stringResource(R.string.stylus_prediction_algo_hardware_desc),
                        selected = vm.stylusPredictionAlgorithmType == "HARDWARE",
                        shape = settingGroupShape(3, touchTotal),
                        onClick = { vm.updateStylusPredictionAlgorithm("HARDWARE") },
                    )
                    SettingRadioGroupItem(
                        title = stringResource(R.string.stylus_prediction_algo_software),
                        summary = stringResource(R.string.stylus_prediction_algo_software_desc),
                        selected = vm.stylusPredictionAlgorithmType == "SOFTWARE",
                        shape = settingGroupShape(4, touchTotal),
                        onClick = { vm.updateStylusPredictionAlgorithm("SOFTWARE") },
                    )
                }
                val cursorOffset = if (isPredictionOn) 5 else 3
                SettingDropdownGroupItem(
                    icon = Icons.Rounded.Brush,
                    title = stringResource(R.string.stylus_brush_cursor),
                    summary = stringResource(R.string.stylus_brush_cursor_desc),
                    currentText = cursorModeOptions.getOrElse(vm.brushCursorMode) { cursorModeOptions[0] },
                    options = cursorModeOptions,
                    shape = settingGroupShape(cursorOffset, touchTotal),
                    onSelect = { vm.updateBrushCursorMode(it) },
                )
                SettingDropdownGroupItem(
                    icon = Icons.Rounded.AutoFixHigh,
                    title = stringResource(R.string.stylus_eraser_cursor),
                    summary = stringResource(R.string.stylus_eraser_cursor_desc),
                    currentText = cursorModeOptions.getOrElse(vm.eraserCursorMode) { cursorModeOptions.last() },
                    options = cursorModeOptions,
                    shape = settingGroupShape(cursorOffset + 1, touchTotal),
                    onSelect = { vm.updateEraserCursorMode(it) },
                )
                SettingDropdownGroupItem(
                    icon = Icons.Rounded.ControlCamera,
                    title = stringResource(R.string.stylus_cursor_style),
                    summary = stringResource(R.string.stylus_cursor_style_desc),
                    currentText = cursorStyleOptions.getOrElse(vm.cursorStyleMode) { cursorStyleOptions[0] },
                    options = cursorStyleOptions,
                    shape = settingGroupShape(cursorOffset + 2, touchTotal),
                    onSelect = { vm.updateCursorStyleMode(it) },
                )
            }

            // 2. 真实书写音效
            val audioTypeOptions = listOf(
                stringResource(R.string.stylus_audio_type_pencil),
                stringResource(R.string.stylus_audio_type_ink),
                stringResource(R.string.stylus_audio_type_tick),
            )
            SettingCategoryTitle(stringResource(R.string.stylus_sound_title))
            SettingGroup {
                val audioTotal = if (vm.stylusAudioEnabled) 3 else 1
                SettingSwitchGroupItem(
                    icon = Icons.AutoMirrored.Rounded.VolumeUp,
                    title = stringResource(R.string.stylus_sound_paper),
                    summary = stringResource(R.string.stylus_sound_paper_desc),
                    checked = vm.stylusAudioEnabled,
                    shape = settingGroupShape(0, audioTotal),
                    onCheckedChange = { vm.updateStylusAudioEnabled(it) },
                )
                if (vm.stylusAudioEnabled) {
                    SettingDropdownGroupItem(
                        icon = Icons.Rounded.Edit,
                        title = stringResource(R.string.stylus_sound_type),
                        summary = stringResource(R.string.stylus_sound_type_desc),
                        currentText = audioTypeOptions.getOrElse(vm.stylusAudioType.ordinal) { audioTypeOptions[0] },
                        options = audioTypeOptions,
                        shape = settingGroupShape(1, audioTotal),
                        onSelect = { vm.updateStylusAudioType(com.reverie.paint.core.stylus.StylusAudioType.fromOrdinal(it)) },
                    )
                    SettingSliderGroupItem(
                        icon = Icons.AutoMirrored.Rounded.VolumeDown,
                        title = stringResource(R.string.stylus_sound_volume),
                        summary = stringResource(R.string.stylus_sound_volume_desc),
                        valueText = "${(vm.stylusAudioVolume * 100).toInt()}%",
                        sliderFraction = vm.stylusAudioVolume,
                        shape = settingGroupShape(2, audioTotal),
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
                        StylusBrand.HUAWEI_MPENCIL -> {
                            SettingStylusDeviceRow(
                                title = device.deviceName,
                                summary = stringResource(R.string.stylus_huawei_features),
                                isCurrentDevice = device.isCurrentDeviceSupported,
                                isConnected = device.isConnected,
                                shape = shape,
                                onClick = { activeConfigBrand = StylusBrand.HUAWEI_MPENCIL },
                            )
                        }
                        StylusBrand.HONOR_MAGIC_PENCIL -> {
                            SettingStylusDeviceRow(
                                title = device.deviceName,
                                summary = stringResource(R.string.stylus_honor_features),
                                isCurrentDevice = device.isCurrentDeviceSupported,
                                isConnected = device.isConnected,
                                shape = shape,
                                onClick = { activeConfigBrand = StylusBrand.HONOR_MAGIC_PENCIL },
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
                        StylusBrand.XIAOMI_STYLUS -> {
                            SettingStylusDeviceRow(
                                title = device.deviceName,
                                summary = stringResource(R.string.stylus_xiaomi_features),
                                isCurrentDevice = device.isCurrentDeviceSupported,
                                isConnected = device.isConnected,
                                shape = shape,
                                onClick = { activeConfigBrand = StylusBrand.XIAOMI_STYLUS },
                            )
                        }
                        StylusBrand.VIVO_PENCIL -> {
                            SettingStylusDeviceRow(
                                title = device.deviceName,
                                summary = stringResource(R.string.stylus_vivo_features),
                                isCurrentDevice = device.isCurrentDeviceSupported,
                                isConnected = device.isConnected,
                                shape = shape,
                                onClick = { activeConfigBrand = StylusBrand.VIVO_PENCIL },
                            )
                        }
                        StylusBrand.GENERIC -> {
                            SettingStylusDeviceRow(
                                title = device.deviceName,
                                summary = stringResource(R.string.stylus_generic_features),
                                isCurrentDevice = device.isCurrentDeviceSupported,
                                isConnected = device.isConnected,
                                shape = shape,
                                onClick = { activeConfigBrand = StylusBrand.GENERIC },
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
        StylusBrand.HUAWEI_MPENCIL -> {
            HuaweiStylusConfigDialog(vm = vm, onDismiss = { activeConfigBrand = null })
        }
        StylusBrand.HONOR_MAGIC_PENCIL -> {
            HonorStylusConfigDialog(vm = vm, onDismiss = { activeConfigBrand = null })
        }
        StylusBrand.SAMSUNG_SPEN -> {
            SamsungStylusConfigDialog(vm = vm, onDismiss = { activeConfigBrand = null })
        }
        StylusBrand.XIAOMI_STYLUS -> {
            XiaomiStylusConfigDialog(vm = vm, onDismiss = { activeConfigBrand = null })
        }
        StylusBrand.VIVO_PENCIL -> {
            VivoStylusConfigDialog(vm = vm, onDismiss = { activeConfigBrand = null })
        }
        StylusBrand.GENERIC -> {
            GenericStylusConfigDialog(vm = vm, onDismiss = { activeConfigBrand = null })
        }
        else -> {}
    }
}
