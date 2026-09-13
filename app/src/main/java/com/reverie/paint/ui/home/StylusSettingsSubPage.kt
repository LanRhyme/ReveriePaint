/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    val audioTypeOptions = listOf("铅笔沙沙 (细腻磨砂)", "钢笔划纸 (清脆微响)", "系统微触音 (极简轻触)")

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
                .padding(horizontal = 20.dp, vertical = 20.dp),
        ) {
            // Back Bar
            if (showBackButton) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .clickable(onClick = onBack),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_left),
                            contentDescription = "返回",
                            tint = colors.text,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "手写笔设置",
                        color = colors.text,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            // 1. 触控与光标设置 (Grouped Rounded Card)
            SettingCategoryHeader("触控与光标")
            GroupedSettingsCard {
                SettingSwitchRow(
                    title = "笔模式",
                    summary = "开启后禁止手指绘制，单指可平移画布，双指可缩放与旋转画布",
                    checked = vm.penOnlyMode,
                    onCheckedChange = { vm.updatePenOnlyMode(it) },
                )
                SettingsCardDivider()
                SettingSwitchRow(
                    title = "超低延迟笔迹预测",
                    summary = "采用 120Hz/144Hz 毫秒级瞬态前向预测，笔迹即时紧跟笔尖",
                    checked = vm.stylusStrokePredictionEnabled,
                    onCheckedChange = { vm.updateStylusStrokePredictionEnabled(it) },
                )
                SettingsCardDivider()
                SettingDropdownRow(
                    title = "画笔光标",
                    currentText = cursorModeOptions.getOrElse(vm.brushCursorMode) { "不显示" },
                    options = cursorModeOptions,
                    onSelect = { vm.updateBrushCursorMode(it) },
                )
                SettingsCardDivider()
                SettingDropdownRow(
                    title = "橡皮光标",
                    currentText = cursorModeOptions.getOrElse(vm.eraserCursorMode) { "绘画和悬空显示" },
                    options = cursorModeOptions,
                    onSelect = { vm.updateEraserCursorMode(it) },
                )
                SettingsCardDivider()
                SettingDropdownRow(
                    title = "光标样式",
                    currentText = cursorStyleOptions.getOrElse(vm.cursorStyleMode) { "圆形" },
                    options = cursorStyleOptions,
                    onSelect = { vm.updateCursorStyleMode(it) },
                )
            }

            Spacer(Modifier.height(18.dp))

            // 2. 真实书写音效 (Grouped Rounded Card)
            SettingCategoryHeader("真实书写音效")
            GroupedSettingsCard {
                SettingSwitchRow(
                    title = "纸张微摩擦音效",
                    summary = "落笔与运笔时模拟真实笔尖在纸张上的微摩擦发声，营造沉浸式触感体验",
                    checked = vm.stylusAudioEnabled,
                    onCheckedChange = { vm.updateStylusAudioEnabled(it) },
                )
                if (vm.stylusAudioEnabled) {
                    SettingsCardDivider()
                    SettingDropdownRow(
                        title = "音效类型",
                        currentText = audioTypeOptions.getOrElse(vm.stylusAudioTypeOrdinal) { "铅笔沙沙 (细腻磨砂)" },
                        options = audioTypeOptions,
                        onSelect = { idx ->
                            vm.updateStylusAudioTypeOrdinal(idx)
                        },
                    )
                    SettingsCardDivider()
                    SettingSliderRow(
                        title = "音效音量",
                        summary = "${(vm.stylusAudioVolume * 100).toInt()}%",
                        value = vm.stylusAudioVolume,
                        onValueChange = { vm.updateStylusAudioVolume(it) },
                    )
                }
            }

            Spacer(Modifier.height(18.dp))

            // 3. 全局压力曲线 (Grouped Rounded Card)
            SettingCategoryHeader("全局压力曲线")
            GroupedSettingsCard {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "压力曲线映射",
                        color = colors.text,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "部分手写笔手感偏硬时，可选择「轻压灵敏」预设或展开微调控制点",
                        color = colors.subText,
                        fontSize = 11.sp,
                        modifier = Modifier.weight(1f),
                    )
                }

                Spacer(Modifier.height(8.dp))

                // 紧凑只读曲线卡片 (零垂直滚动冲突，点击唤起微调弹窗)
                CompactPressureCurveCard(
                    points = vm.pressureControlPoints,
                    presetIndex = vm.pressureCurvePreset,
                    onSelectPreset = { vm.updatePressureCurvePreset(it) },
                    onOpenEditDialog = { showPressureCurveDialog = true },
                    onOpenHelpDialog = { showHelpDialog = true },
                )
            }

            Spacer(Modifier.height(18.dp))

            // 4. 已适配的手写笔生态 (专属品牌设备与自动检测置顶 - 移至页面底部)
            SettingCategoryHeader("已适配的手写笔品牌与设备")
            GroupedSettingsCard {
                detectedDevices.forEachIndexed { index, device ->
                    if (index > 0) {
                        SettingsCardDivider()
                    }
                    when (device.brand) {
                        StylusBrand.OPPO_ONEPLUS -> {
                            SettingStylusDeviceRow(
                                title = device.deviceName,
                                summary = "笔身手势、触控滑动与原笔迹微震专属配置",
                                isCurrentDevice = device.isCurrentDeviceSupported,
                                isConnected = device.isConnected,
                                onClick = { activeConfigBrand = StylusBrand.OPPO_ONEPLUS },
                            )
                        }
                        StylusBrand.SAMSUNG_SPEN -> {
                            SettingStylusDeviceRow(
                                title = device.deviceName,
                                summary = "侧键动作映射与触觉微震专属配置",
                                isCurrentDevice = device.isCurrentDeviceSupported,
                                isConnected = device.isConnected,
                                onClick = { activeConfigBrand = StylusBrand.SAMSUNG_SPEN },
                            )
                        }
                        StylusBrand.GENERIC -> {
                            SettingStylusDeviceRow(
                                title = device.deviceName,
                                summary = "标准 Android 压感与倾角触控协议",
                                isCurrentDevice = device.isCurrentDeviceSupported,
                                isConnected = device.isConnected,
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
