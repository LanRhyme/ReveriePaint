/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.home

import android.content.Intent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.reverie.paint.ui.components.ReTextButton
import com.reverie.paint.R
import com.reverie.paint.core.*
import com.reverie.paint.core.stylus.*
import com.reverie.paint.ui.theme.Theme
import com.reverie.paint.ui.theme.parseColor

@Composable
internal fun StylusSettingsSubPage(
    vm: PaintViewModel,
    showBackButton: Boolean = true,
    onBack: () -> Unit
) {
    val colors = Theme.current
    val context = androidx.compose.ui.platform.LocalContext.current

    var showHelpDialog by remember { mutableStateOf(false) }
    var showOppoConfigDialog by remember { mutableStateOf(false) }
    var showSamsungConfigDialog by remember { mutableStateOf(false) }

    val stylusDriver = remember { vm.getOrCreateStylusDriver(context) }
    val detectedDevices = remember { stylusDriver.detectDevices() }

    val cursorModeOptions = listOf("不显示", "绘画时显示", "悬空显示", "绘画和悬空显示")
    val cursorStyleOptions = listOf("圆形", "十字准星", "点", "无", "系统指针", "圆+十字准星")

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.bg)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 20.dp)
    ) {
        // Back Bar
        if (showBackButton) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onBack),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_arrow_left),
                        contentDescription = "返回",
                        tint = colors.text,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "手写笔设置",
                    color = colors.text,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
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
                onCheckedChange = { vm.updatePenOnlyMode(it) }
            )
            SettingsCardDivider()
            SettingSwitchRow(
                title = "超低延迟笔迹预测",
                summary = "采用 120Hz/144Hz 毫秒级瞬态前向预测，笔迹即时紧跟笔尖",
                checked = vm.stylusStrokePredictionEnabled,
                onCheckedChange = { vm.updateStylusStrokePredictionEnabled(it) }
            )
            SettingsCardDivider()
            SettingDropdownRow(
                title = "画笔光标",
                currentText = cursorModeOptions.getOrElse(vm.brushCursorMode) { "不显示" },
                options = cursorModeOptions,
                onSelect = { vm.updateBrushCursorMode(it) }
            )
            SettingsCardDivider()
            SettingDropdownRow(
                title = "橡皮光标",
                currentText = cursorModeOptions.getOrElse(vm.eraserCursorMode) { "绘画和悬空显示" },
                options = cursorModeOptions,
                onSelect = { vm.updateEraserCursorMode(it) }
            )
            SettingsCardDivider()
            SettingDropdownRow(
                title = "光标样式",
                currentText = cursorStyleOptions.getOrElse(vm.cursorStyleMode) { "圆形" },
                options = cursorStyleOptions,
                onSelect = { vm.updateCursorStyleMode(it) }
            )
            SettingsCardDivider()
            SettingSwitchRow(
                title = "驻停线条成形",
                summary = "功能重构中，暂未开放",
                checked = false,
                enabled = false,
                onCheckedChange = { }
            )
        }

        Spacer(Modifier.height(18.dp))

        // 2. 快捷手势与按键 (Grouped Rounded Card)
        SettingCategoryHeader("手势与按键映射")
        GroupedSettingsCard {
            val actionOptions = listOf(
                "切换画笔与橡皮" to "toggle_eraser",
                "撤销" to "undo",
                "重做" to "redo",
                "吸管取色" to "tool_picker",
                "切换上一工具" to "toggle_last_tool",
                "快捷调色盘" to "tool_color",
                "无操作" to "none"
            )
            val isSamsung = detectedDevices.any { it.brand == StylusBrand.SAMSUNG_SPEN && (it.isCurrentDeviceSupported || it.isConnected) }
            if (isSamsung) {
                val singleTitle = actionOptions.find { it.second == vm.samsungSingleClickAction }?.first ?: "切换画笔与橡皮"
                SettingDropdownRow(
                    title = "侧键单击动作",
                    currentText = singleTitle,
                    options = actionOptions.map { it.first },
                    onSelect = { idx ->
                        vm.updateSamsungSingleClickAction(actionOptions[idx].second)
                    }
                )
                SettingsCardDivider()
                val doubleTitle = actionOptions.find { it.second == vm.samsungDoubleClickAction }?.first ?: "撤销"
                SettingDropdownRow(
                    title = "侧键双击动作",
                    currentText = doubleTitle,
                    options = actionOptions.map { it.first },
                    onSelect = { idx ->
                        vm.updateSamsungDoubleClickAction(actionOptions[idx].second)
                    }
                )
            } else {
                val doubleTapTitle = actionOptions.find { it.second == vm.oppoDoubleTapAction }?.first ?: "切换画笔与橡皮"
                SettingDropdownRow(
                    title = "笔身双击动作",
                    currentText = doubleTapTitle,
                    options = actionOptions.map { it.first },
                    onSelect = { idx ->
                        vm.updateOppoDoubleTapAction(actionOptions[idx].second)
                    }
                )
                if (vm.oppoPencilModel.hasSlideGesture) {
                    SettingsCardDivider()
                    val slideActionOptions = listOf(
                        "滑动调节画笔粗细" to "adjust_brush_size",
                        "滑动调节不透明度" to "adjust_opacity",
                        "撤销与重做" to "undo_redo",
                        "无操作" to "none"
                    )
                    val slideTitle = slideActionOptions.find { it.second == vm.oppoSlideAction }?.first ?: "滑动调节画笔粗细"
                    SettingDropdownRow(
                        title = "笔身触控滑动动作",
                        currentText = slideTitle,
                        options = slideActionOptions.map { it.first },
                        onSelect = { idx ->
                            vm.updateOppoSlideAction(slideActionOptions[idx].second)
                        }
                    )
                }
            }
        }

        Spacer(Modifier.height(18.dp))

        // 3. 触感反馈与震动 (Grouped Rounded Card)
        SettingCategoryHeader("触感反馈与震动")
        GroupedSettingsCard {
            SettingSwitchRow(
                title = "手写笔手势与功能震动",
                summary = "笔身手势动作（双击/滑动）、取色及快捷操作时的震动反馈",
                checked = vm.stylusHapticsEnabled,
                onCheckedChange = { vm.updateStylusHapticsEnabled(it) }
            )
            if (vm.stylusHapticsEnabled) {
                SettingsCardDivider()
                SettingSliderRow(
                    title = "震动强度",
                    summary = "${(vm.stylusHapticsIntensity * 100).toInt()}%",
                    value = vm.stylusHapticsIntensity,
                    onValueChange = { vm.updateStylusHapticsIntensity(it) }
                )
            }
            SettingsCardDivider()
            SettingSwitchRow(
                title = "真实书写摩擦声",
                summary = "落笔与运笔时模拟真实铅笔在纸张上的微摩擦发声，营造沉浸式触感反馈",
                checked = vm.stylusAudioEnabled,
                onCheckedChange = { vm.updateStylusAudioEnabled(it) }
            )
        }

        Spacer(Modifier.height(18.dp))

        // 3. 全局压力曲线 (Grouped Rounded Card)
        SettingCategoryHeader("全局压力曲线")
        GroupedSettingsCard {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "压力曲线映射",
                    color = colors.text,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "部分手写笔手感偏硬时，可选择「轻压灵敏」预设或拖动控制点自由调节",
                    color = colors.subText,
                    fontSize = 11.sp,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(Modifier.height(8.dp))

            // Interactive 4x4 Grid Curve Canvas
            PressureCurveEditor(
                points = vm.pressureControlPoints,
                onPointsChanged = { newPoints ->
                    vm.updateCustomPressureCurve(newPoints)
                }
            )

            Spacer(Modifier.height(10.dp))

            // Bottom action bar (重置 + 5 预设图标 + 帮助 ?)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(colors.panelHi)
                        .clickable { vm.updatePressureCurvePreset(0) }
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "重置",
                        color = colors.subText,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CurvePresetIcon(0, vm.pressureCurvePreset == 0) { vm.updatePressureCurvePreset(0) }
                    CurvePresetIcon(1, vm.pressureCurvePreset == 1) { vm.updatePressureCurvePreset(1) }
                    CurvePresetIcon(2, vm.pressureCurvePreset == 2) { vm.updatePressureCurvePreset(2) }
                    CurvePresetIcon(3, vm.pressureCurvePreset == 3) { vm.updatePressureCurvePreset(3) }
                    CurvePresetIcon(4, vm.pressureCurvePreset == 4) { vm.updatePressureCurvePreset(4) }

                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .clickable { showHelpDialog = true },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_help_circle),
                            contentDescription = "帮助",
                            tint = colors.subText,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(18.dp))

        // 4. 已适配的手写笔生态 (专属品牌设备与自动检测置顶 - 移至页面底部)
        SettingCategoryHeader("已适配的手写笔设备")
        GroupedSettingsCard {
            detectedDevices.forEachIndexed { index, device ->
                if (index > 0) {
                    SettingsCardDivider()
                }
                when (device.brand) {
                    StylusBrand.OPPO_ONEPLUS -> {
                        SettingStylusDeviceRow(
                            title = device.deviceName,
                            summary = "笔身双击与滑动触控、内置微震、低延迟预测",
                            isCurrentDevice = device.isCurrentDeviceSupported,
                            isConnected = device.isConnected,
                            onClick = { showOppoConfigDialog = true }
                        )
                    }
                    StylusBrand.SAMSUNG_SPEN -> {
                        SettingStylusDeviceRow(
                            title = device.deviceName,
                            summary = "侧键单击/双击/长按映射、触觉微震",
                            isCurrentDevice = device.isCurrentDeviceSupported,
                            isConnected = device.isConnected,
                            onClick = { showSamsungConfigDialog = true }
                        )
                    }
                    StylusBrand.GENERIC -> {
                        SettingStylusDeviceRow(
                            title = device.deviceName,
                            summary = "标准 4096 级压感与倾角协议",
                            isCurrentDevice = device.isCurrentDeviceSupported,
                            isConnected = device.isConnected,
                            onClick = null
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(80.dp))
    }

    if (showHelpDialog) {
        Dialog(onDismissRequest = { showHelpDialog = false }) {
            Box(
                modifier = Modifier
                    .width(320.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(colors.panel)
                    .padding(20.dp)
            ) {
                Column {
                    Text(
                        text = "压力曲线说明",
                        color = colors.text,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "压力曲线用于调整手写笔从轻压到重压的感应输出。\n\n• 曲线向上凸起：轻握笔时即可输出较大粗细与浓度，适合手劲轻或压力较硬的手写笔。\n• 曲线向下凹陷：需要较用力按压才会达到最大粗细，手感更扎实。\n• S型曲线：两端平缓中间灵敏，层次更分明。",
                        color = colors.subText,
                        fontSize = 13.sp,
                        lineHeight = 18.sp
                    )
                    Spacer(Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        ReTextButton("我知道了", { showHelpDialog = false }, textColor = colors.accent, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }

    if (showOppoConfigDialog) {
        OppoPencilConfigDialog(
            vm = vm,
            onDismiss = { showOppoConfigDialog = false }
        )
    }

    if (showSamsungConfigDialog) {
        SamsungSPenConfigDialog(
            vm = vm,
            onDismiss = { showSamsungConfigDialog = false }
        )
    }
}

@Composable
private fun OppoPencilConfigDialog(
    vm: PaintViewModel,
    onDismiss: () -> Unit
) {
    val colors = Theme.current
    val context = LocalContext.current
    val actionOptions = listOf(
        "切换画笔与橡皮" to "toggle_eraser",
        "撤销" to "undo",
        "重做" to "redo",
        "吸管取色" to "tool_picker",
        "切换上一工具" to "toggle_last_tool",
        "快捷调色盘" to "tool_color",
        "无操作" to "none"
    )

    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .width(420.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(colors.panel)
                .padding(20.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "OPPO / 一加手写笔",
                        color = colors.text,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(colors.panelHi)
                            .clickable(onClick = onDismiss),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_x),
                            contentDescription = "关闭",
                            tint = colors.subText,
                            modifier = Modifier.size(15.dp)
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))

                // 设备硬件规格简报
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(colors.panelHi.copy(alpha = 0.5f))
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = vm.oppoPencilModel.displayName,
                                color = colors.text,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = if (vm.oppoPencilModel == OppoPencilModel.PRO) "16384级超高压感" else "4096级压感",
                                color = colors.accent,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = vm.oppoPencilModel.desc,
                            color = colors.subText,
                            fontSize = 11.sp,
                            lineHeight = 15.sp
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))

                SettingCategoryHeader("笔身手势动作")
                GroupedSettingsCard(containerColor = colors.panelHi) {
                    val currentTitle = actionOptions.find { it.second == vm.oppoDoubleTapAction }?.first ?: "切换画笔与橡皮"
                    SettingDropdownRow(
                        title = "双击笔身动作",
                        currentText = currentTitle,
                        options = actionOptions.map { it.first },
                        onSelect = { idx ->
                            vm.updateOppoDoubleTapAction(actionOptions[idx].second)
                        }
                    )
                    if (vm.oppoPencilModel.hasSlideGesture) {
                        SettingsCardDivider()
                        val slideActionOptions = listOf(
                            "滑动调节画笔粗细" to "adjust_brush_size",
                            "滑动调节不透明度" to "adjust_opacity",
                            "撤销与重做" to "undo_redo",
                            "无操作" to "none"
                        )
                        val slideTitle = slideActionOptions.find { it.second == vm.oppoSlideAction }?.first ?: "滑动调节画笔粗细"
                        SettingDropdownRow(
                            title = "笔身触控滑动动作",
                            currentText = slideTitle,
                            options = slideActionOptions.map { it.first },
                            onSelect = { idx ->
                                vm.updateOppoSlideAction(slideActionOptions[idx].second)
                            }
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                SettingCategoryHeader("触感与微震")
                GroupedSettingsCard(containerColor = colors.panelHi) {
                    SettingSwitchRow(
                        title = "笔身书写微震",
                        summary = "激活手写笔内置线性微马达，落笔书写时提供真实沙沙纸感",
                        checked = vm.oppoInPenHapticsEnabled,
                        onCheckedChange = { vm.updateOppoInPenHapticsEnabled(it) }
                    )
                    SettingsCardDivider()
                    SettingSwitchRow(
                        title = "手势操作震动反馈",
                        summary = "双击笔身或滑动触控区时发出轻微震动提示",
                        checked = vm.stylusHapticsEnabled,
                        onCheckedChange = { vm.updateStylusHapticsEnabled(it) }
                    )
                    SettingsCardDivider()
                    SettingNavRow(
                        iconRes = R.drawable.ic_settings,
                        title = "系统手写笔触感设置",
                        summary = "直达 ColorOS 系统手写笔触感与书写震动配置页",
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
                                    vm.showActionToast("未能打开系统手写笔设置", R.drawable.ic_help_circle)
                                }
                            }
                        }
                    )
                }

                Spacer(Modifier.height(12.dp))

                SettingCategoryHeader("算法与延迟优化")
                GroupedSettingsCard(containerColor = colors.panelHi) {
                    SettingSwitchRow(
                        title = "超低延迟笔迹预测",
                        summary = "ColorOS 毫秒级算法预测落笔轨迹，极速视觉跟随",
                        checked = vm.stylusStrokePredictionEnabled,
                        onCheckedChange = { vm.updateStylusStrokePredictionEnabled(it) }
                    )
                }

                Spacer(Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    ReTextButton("完成", onDismiss, textColor = colors.accent, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun SamsungSPenConfigDialog(
    vm: PaintViewModel,
    onDismiss: () -> Unit
) {
    val colors = Theme.current
    val actionOptions = listOf(
        "切换画笔与橡皮" to "toggle_eraser",
        "撤销" to "undo",
        "重做" to "redo",
        "吸管取色" to "tool_picker",
        "切换上一工具" to "toggle_last_tool",
        "快捷调色盘" to "tool_color",
        "无操作" to "none"
    )

    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .width(420.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(colors.panel)
                .padding(20.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "三星 S Pen 专属配置",
                        color = colors.text,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(colors.panelHi)
                            .clickable(onClick = onDismiss),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_x),
                            contentDescription = "关闭",
                            tint = colors.subText,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                SettingCategoryHeader("侧键动作映射")
                GroupedSettingsCard(containerColor = colors.panelHi) {
                    val singleClickTitle = actionOptions.find { it.second == vm.samsungSingleClickAction }?.first ?: "切换画笔与橡皮"
                    SettingDropdownRow(
                        title = "侧键单击动作",
                        currentText = singleClickTitle,
                        options = actionOptions.map { it.first },
                        onSelect = { idx ->
                            vm.updateSamsungSingleClickAction(actionOptions[idx].second)
                        }
                    )
                    SettingsCardDivider()
                    val doubleClickTitle = actionOptions.find { it.second == vm.samsungDoubleClickAction }?.first ?: "撤销"
                    SettingDropdownRow(
                        title = "侧键双击动作",
                        currentText = doubleClickTitle,
                        options = actionOptions.map { it.first },
                        onSelect = { idx ->
                            vm.updateSamsungDoubleClickAction(actionOptions[idx].second)
                        }
                    )
                    SettingsCardDivider()
                    val longPressTitle = actionOptions.find { it.second == vm.samsungLongPressAction }?.first ?: "吸管取色"
                    SettingDropdownRow(
                        title = "侧键长按动作",
                        currentText = longPressTitle,
                        options = actionOptions.map { it.first },
                        onSelect = { idx ->
                            vm.updateSamsungLongPressAction(actionOptions[idx].second)
                        }
                    )
                }

                Spacer(Modifier.height(12.dp))

                SettingCategoryHeader("触觉与微震")
                GroupedSettingsCard(containerColor = colors.panelHi) {
                    SettingSwitchRow(
                        title = "笔尖书写微震",
                        summary = "运笔时线性振动马达提供沙沙质感",
                        checked = vm.stylusHapticsEnabled,
                        onCheckedChange = { vm.updateStylusHapticsEnabled(it) }
                    )
                    if (vm.stylusHapticsEnabled) {
                        SettingsCardDivider()
                        SettingSliderRow(
                            title = "微震强度",
                            summary = "${(vm.stylusHapticsIntensity * 100).toInt()}%",
                            value = vm.stylusHapticsIntensity,
                            onValueChange = { vm.updateStylusHapticsIntensity(it) }
                        )
                    }
                }

                Spacer(Modifier.height(18.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    ReTextButton("完成", onDismiss, textColor = colors.accent, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun PressureCurveEditor(
    points: List<Offset>,
    onPointsChanged: (List<Offset>) -> Unit
) {
    val colors = Theme.current
    val currentPoints by rememberUpdatedState(points)
    val currentOnPointsChanged by rememberUpdatedState(onPointsChanged)
    var draggingPointIdx by remember { mutableIntStateOf(-1) }
    var lastTapTime by remember { mutableStateOf(0L) }
    var lastTapIndex by remember { mutableIntStateOf(-1) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1.25f)
                .clip(RoundedCornerShape(14.dp))
                .background(colors.panelHi)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        val w = size.width.toFloat()
                        val h = size.height.toFloat()
                        val touchOffset = down.position
                        val pts = currentPoints

                        // Find nearest point within 36dp
                        val hitRadiusSq = (36f * density).let { it * it }
                        var minD = Float.MAX_VALUE
                        var foundIdx = -1
                        pts.forEachIndexed { idx, pt ->
                            val px = pt.x * w
                            val py = (1f - pt.y) * h
                            val dx = touchOffset.x - px
                            val dy = touchOffset.y - py
                            val d = dx * dx + dy * dy
                            if (d <= hitRadiusSq && d < minD) {
                                minD = d
                                foundIdx = idx
                            }
                        }

                        val now = System.currentTimeMillis()
                        // Double-tap on an interior point deletes it
                        if (foundIdx > 0 && foundIdx < pts.size - 1) {
                            if (now - lastTapTime < 350L && lastTapIndex == foundIdx) {
                                val curList = pts.toMutableList()
                                curList.removeAt(foundIdx)
                                currentOnPointsChanged(curList)
                                draggingPointIdx = -1
                                lastTapTime = 0L
                                lastTapIndex = -1
                                while (true) {
                                    val ev = awaitPointerEvent()
                                    val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!ch.pressed) break
                                    ch.consume()
                                }
                                return@awaitEachGesture
                            }
                            lastTapTime = now
                            lastTapIndex = foundIdx
                        } else {
                            lastTapTime = 0L
                            lastTapIndex = -1
                        }

                        var activeIdx = foundIdx
                        if (activeIdx == -1) {
                            // Only allow adding points up to 6 total
                            if (pts.size < 6) {
                                val newPt = Offset(
                                    (touchOffset.x / w).coerceIn(0.02f, 0.98f),
                                    (1f - touchOffset.y / h).coerceIn(0f, 1f)
                                )
                                val updated = (pts + newPt).sortedBy { it.x }
                                activeIdx = updated.indexOf(newPt)
                                draggingPointIdx = activeIdx
                                currentOnPointsChanged(updated)
                            } else {
                                draggingPointIdx = -1
                                while (true) {
                                    val ev = awaitPointerEvent()
                                    val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!ch.pressed) break
                                    ch.consume()
                                }
                                return@awaitEachGesture
                            }
                        } else {
                            draggingPointIdx = activeIdx
                        }

                        var isDraggedOutOfCanvas = false

                        // Drag tracking loop
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            change.consume()

                            val curList = currentPoints.toMutableList()
                            if (activeIdx in curList.indices) {
                                val isInterior = activeIdx > 0 && activeIdx < curList.size - 1
                                if (isInterior) {
                                    val outThresh = 30f * density
                                    val p = change.position
                                    isDraggedOutOfCanvas = p.y < -outThresh || p.y > h + outThresh ||
                                            p.x < -outThresh || p.x > w + outThresh
                                }

                                val minX = if (activeIdx == 0) 0f else (curList[activeIdx - 1].x + 0.02f).coerceAtMost(1f)
                                val maxX = if (activeIdx == curList.size - 1) 1f else (curList[activeIdx + 1].x - 0.02f).coerceAtLeast(0f)
                                val curX = if (activeIdx == 0) 0f else if (activeIdx == curList.size - 1) 1f else (change.position.x / w).coerceIn(minX, maxX)
                                val curY = (1f - change.position.y / h).coerceIn(0f, 1f)
                                curList[activeIdx] = Offset(curX, curY)
                                currentOnPointsChanged(curList)
                            }
                        }

                        // If dragged out of canvas on release, remove interior point
                        if (isDraggedOutOfCanvas && activeIdx > 0 && activeIdx < currentPoints.size - 1) {
                            val curList = currentPoints.toMutableList()
                            if (activeIdx in curList.indices) {
                                curList.removeAt(activeIdx)
                                currentOnPointsChanged(curList)
                            }
                        }

                        draggingPointIdx = -1
                    }
                }
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height

                // Draw 4x4 Grid
                val gridColor = colors.gridLine
                for (i in 1..3) {
                    val gx = w * (i / 4f)
                    val gy = h * (i / 4f)
                    drawLine(gridColor, Offset(gx, 0f), Offset(gx, h), strokeWidth = 1.dp.toPx())
                    drawLine(gridColor, Offset(0f, gy), Offset(w, gy), strokeWidth = 1.dp.toPx())
                }

                // Draw Spline
                val sorted = points.sortedBy { it.x }
                if (sorted.isNotEmpty()) {
                    val path = Path()
                    val step = 120
                    for (s in 0..step) {
                        val xVal = s / step.toFloat()
                        val yVal = evaluateSpline(sorted, xVal)
                        val screenX = xVal * w
                        val screenY = (1f - yVal) * h
                        if (s == 0) path.moveTo(screenX, screenY) else path.lineTo(screenX, screenY)
                    }

                    drawPath(
                        path = path,
                        color = colors.text,
                        style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
                    )

                    // Control points handles
                    sorted.forEachIndexed { idx, pt ->
                        val cx = pt.x * w
                        val cy = (1f - pt.y) * h
                        val isDragging = idx == draggingPointIdx
                        if (isDragging) {
                            drawCircle(
                                color = colors.accent.copy(alpha = 0.35f),
                                radius = 12.dp.toPx(),
                                center = Offset(cx, cy)
                            )
                        }
                        drawCircle(
                            color = colors.accent,
                            radius = if (idx == 0 || idx == sorted.size - 1) 5.dp.toPx() else 4.dp.toPx(),
                            center = Offset(cx, cy)
                        )
                        drawCircle(
                            color = colors.onAccent,
                            radius = 2.5.dp.toPx(),
                            center = Offset(cx, cy)
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(6.dp))
        Text(
            text = "提示：拖动控制点调整曲线；点击空白处添加点（最多6个）；双击或拖出画布可删除控制点",
            color = colors.subText.copy(alpha = 0.75f),
            fontSize = 11.sp,
            lineHeight = 15.sp
        )
    }
}

private fun evaluateSpline(pts: List<Offset>, x: Float): Float {
    if (pts.isEmpty()) return x
    if (pts.size == 1) return pts[0].y
    if (pts.size == 2) {
        val p0 = pts[0]
        val p1 = pts[1]
        val dx = p1.x - p0.x
        if (dx <= 0.0001f) return p0.y
        val t = ((x - p0.x) / dx).coerceIn(0f, 1f)
        return (p0.y + t * (p1.y - p0.y)).coerceIn(0f, 1f)
    }
    if (x <= pts.first().x) return pts.first().y
    if (x >= pts.last().x) return pts.last().y

    var i = 0
    while (i < pts.size - 2 && pts[i + 1].x < x) {
        i++
    }
    val p0 = if (i > 0) pts[i - 1] else pts[i]
    val p1 = pts[i]
    val p2 = pts[i + 1]
    val p3 = if (i + 2 < pts.size) pts[i + 2] else p2

    val dx = (p2.x - p1.x).coerceAtLeast(0.0001f)
    val t = ((x - p1.x) / dx).coerceIn(0f, 1f)

    val m1 = (p2.y - p0.y) / (p2.x - p0.x).coerceAtLeast(0.0001f)
    val m2 = (p3.y - p1.y) / (p3.x - p1.x).coerceAtLeast(0.0001f)

    val t2 = t * t
    val t3 = t2 * t
    val h00 = 2f * t3 - 3f * t2 + 1f
    val h10 = t3 - 2f * t2 + t
    val h01 = -2f * t3 + 3f * t2
    val h11 = t3 - t2

    return (h00 * p1.y + h10 * dx * m1 + h01 * p2.y + h11 * dx * m2).coerceIn(0f, 1f)
}

@Composable
private fun CurvePresetIcon(
    type: Int,
    selected: Boolean,
    onClick: () -> Unit
) {
    val colors = Theme.current
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) colors.accent.copy(alpha = 0.2f) else colors.panelHi)
            .then(
                if (selected) Modifier.border(1.5.dp, colors.accent, RoundedCornerShape(8.dp))
                else Modifier
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(18.dp)) {
            val w = size.width
            val h = size.height
            val stroke = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round)
            val strokeColor = if (selected) colors.accent else colors.subText
            val path = Path()

            when (type) {
                0 -> { // Linear /
                    drawLine(strokeColor, Offset(2f, h - 2f), Offset(w - 2f, 2f), strokeWidth = 1.5.dp.toPx())
                }
                1 -> { // Soft / Convex ⌒
                    path.moveTo(2f, h - 2f)
                    path.cubicTo(w * 0.2f, h * 0.3f, w * 0.5f, 2f, w - 2f, 2f)
                    drawPath(path, strokeColor, style = stroke)
                }
                2 -> { // Hard / Concave ‿
                    path.moveTo(2f, h - 2f)
                    path.cubicTo(w * 0.5f, h - 2f, w * 0.8f, h * 0.7f, w - 2f, 2f)
                    drawPath(path, strokeColor, style = stroke)
                }
                3 -> { // S-Curve ~
                    path.moveTo(2f, h - 2f)
                    path.cubicTo(w * 0.4f, h - 2f, w * 0.6f, 2f, w - 2f, 2f)
                    drawPath(path, strokeColor, style = stroke)
                }
                4 -> { // Custom
                    path.moveTo(2f, h - 2f)
                    path.cubicTo(w * 0.3f, 2f, w * 0.7f, h - 2f, w - 2f, 2f)
                    drawPath(path, strokeColor, style = stroke)
                    drawCircle(strokeColor, 1.5.dp.toPx(), Offset(w * 0.5f, h * 0.5f))
                }
            }
        }
    }
}


