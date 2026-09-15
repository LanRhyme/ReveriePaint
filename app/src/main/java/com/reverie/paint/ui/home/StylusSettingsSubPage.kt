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

    val cursorModeOptions = listOf("不显示", "绘画时显示", "悬空显示", "绘画和悬空显示")
    val cursorStyleOptions = listOf("圆形", "十字准星", "点", "无", "系统指针", "圆+十字准星")

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
                title = "手写笔设置",
                subtitle = "手写笔硬件级适配、触感音效与高精度压感映射",
                showBackButton = showBackButton,
                compact = compact,
                onBack = onBack,
            )

            // 1. 触控与光标设置
            SettingCategoryTitle("触控与光标")
            SettingGroup {
                SettingSwitchGroupItem(
                    icon = Icons.Rounded.Edit,
                    title = "笔模式",
                    summary = "开启后禁止手指绘制，单指可平移画布，双指可缩放与旋转画布",
                    checked = vm.penOnlyMode,
                    shape = settingGroupShape(0, 5),
                    onCheckedChange = { vm.updatePenOnlyMode(it) },
                )
                SettingSwitchGroupItem(
                    icon = Icons.Rounded.Speed,
                    title = "超低延迟笔迹预测",
                    summary = "采用 120Hz/144Hz 毫秒级瞬态前向预测，笔迹即时紧跟笔尖",
                    checked = vm.stylusStrokePredictionEnabled,
                    shape = settingGroupShape(1, 5),
                    onCheckedChange = { vm.updateStylusStrokePredictionEnabled(it) },
                )
                SettingDropdownGroupItem(
                    icon = Icons.Rounded.Brush,
                    title = "画笔光标",
                    summary = "画笔悬浮或绘制时的光标显示策略",
                    currentText = cursorModeOptions.getOrElse(vm.brushCursorMode) { "不显示" },
                    options = cursorModeOptions,
                    shape = settingGroupShape(2, 5),
                    onSelect = { vm.updateBrushCursorMode(it) },
                )
                SettingDropdownGroupItem(
                    icon = Icons.Rounded.AutoFixHigh,
                    title = "橡皮光标",
                    summary = "橡皮擦悬浮或擦除时的光标显示策略",
                    currentText = cursorModeOptions.getOrElse(vm.eraserCursorMode) { "绘画和悬空显示" },
                    options = cursorModeOptions,
                    shape = settingGroupShape(3, 5),
                    onSelect = { vm.updateEraserCursorMode(it) },
                )
                SettingDropdownGroupItem(
                    icon = Icons.Rounded.ControlCamera,
                    title = "光标样式",
                    summary = "准星形态、单点圆环或系统光标",
                    currentText = cursorStyleOptions.getOrElse(vm.cursorStyleMode) { "圆形" },
                    options = cursorStyleOptions,
                    shape = settingGroupShape(4, 5),
                    onSelect = { vm.updateCursorStyleMode(it) },
                )
            }

            // 2. 真实书写音效
            SettingCategoryTitle("真实书写音效")
            SettingGroup {
                val audioTotal = if (vm.stylusAudioEnabled) 2 else 1
                SettingSwitchGroupItem(
                    icon = Icons.AutoMirrored.Rounded.VolumeUp,
                    title = "纸张微摩擦音效",
                    summary = "落笔与运笔时模拟真实笔尖在纸张上的微摩擦发声，随笔速变化，营造沉浸式触感体验",
                    checked = vm.stylusAudioEnabled,
                    shape = settingGroupShape(0, audioTotal),
                    onCheckedChange = { vm.updateStylusAudioEnabled(it) },
                )
                if (vm.stylusAudioEnabled) {
                    SettingSliderGroupItem(
                        icon = Icons.AutoMirrored.Rounded.VolumeDown,
                        title = "音效音量",
                        summary = "纸张微摩擦声播放音量",
                        valueText = "${(vm.stylusAudioVolume * 100).toInt()}%",
                        sliderFraction = vm.stylusAudioVolume,
                        shape = settingGroupShape(1, audioTotal),
                        onValueChange = { vm.updateStylusAudioVolume(it) },
                    )
                }
            }

            // 3. 全局压力曲线
            SettingCategoryTitle("全局压力曲线")
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
                                    text = "压力曲线映射",
                                    color = colors.text,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = "手感偏硬时可选用「轻压灵敏」预设或微调控制点",
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
            SettingCategoryTitle("已适配的手写笔品牌与设备")
            SettingGroup {
                detectedDevices.forEachIndexed { index, device ->
                    val shape = settingGroupShape(index, detectedDevices.size)
                    when (device.brand) {
                        StylusBrand.OPPO_ONEPLUS -> {
                            SettingStylusDeviceRow(
                                title = device.deviceName,
                                summary = "笔身手势、触控滑动与原笔迹微震专属配置",
                                isCurrentDevice = device.isCurrentDeviceSupported,
                                isConnected = device.isConnected,
                                shape = shape,
                                onClick = { activeConfigBrand = StylusBrand.OPPO_ONEPLUS },
                            )
                        }
                        StylusBrand.SAMSUNG_SPEN -> {
                            SettingStylusDeviceRow(
                                title = device.deviceName,
                                summary = "侧键动作映射与触觉微震专属配置",
                                isCurrentDevice = device.isCurrentDeviceSupported,
                                isConnected = device.isConnected,
                                shape = shape,
                                onClick = { activeConfigBrand = StylusBrand.SAMSUNG_SPEN },
                            )
                        }
                        StylusBrand.GENERIC -> {
                            SettingStylusDeviceRow(
                                title = device.deviceName,
                                summary = "标准 Android 压感与倾角触控协议",
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
