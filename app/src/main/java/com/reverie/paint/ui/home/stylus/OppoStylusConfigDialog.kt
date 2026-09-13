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
        "切换画笔与橡皮" to "toggle_eraser",
        "撤销" to "undo",
        "重做" to "redo",
        "吸管取色" to "tool_picker",
        "切换上一工具" to "toggle_last_tool",
        "快捷调色盘" to "tool_color",
        "无操作" to "none",
    )

    val modelOptions = listOf(
        "AUTO" to "自动识别 (${vm.detectedOppoPencilModel.editionName})",
        "PRO" to "OPPO Pencil 2 Pro / OnePlus Stylo 2",
        "STANDARD" to "标准版手写笔 (无触控条)",
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
                        text = "OPPO / 一加手写笔专属设置",
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
                            contentDescription = "关闭",
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
                        "STANDARD" -> "标准版手写笔 (无触控条)"
                        else -> "自动识别 (${vm.detectedOppoPencilModel.editionName})"
                    }
                    SettingDropdownRow(
                        title = "设备型号",
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
                                text = if (vm.oppoPencilModel == OppoPencilModel.PRO) "16384级超高压感" else "4096级压感",
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

                SettingCategoryHeader("手势与按键映射")
                GroupedSettingsCard(containerColor = colors.panelHi) {
                    val currentTitle = actionOptions.find { it.second == vm.oppoDoubleTapAction }?.first ?: "切换画笔与橡皮"
                    SettingDropdownRow(
                        title = "笔身双击动作",
                        currentText = currentTitle,
                        options = actionOptions.map { it.first },
                        onSelect = { idx ->
                            vm.updateOppoDoubleTapAction(actionOptions[idx].second)
                        },
                    )
                    if (vm.oppoPencilModel.hasSlideGesture) {
                        SettingsCardDivider()
                        val slideActionOptions = listOf(
                            "滑动调节画笔粗细" to "adjust_brush_size",
                            "滑动调节不透明度" to "adjust_opacity",
                            "撤销与重做" to "undo_redo",
                            "无操作" to "none",
                        )
                        val slideTitle = slideActionOptions.find { it.second == vm.oppoSlideAction }?.first ?: "滑动调节画笔粗细"
                        SettingDropdownRow(
                            title = "笔身触控滑动动作",
                            currentText = slideTitle,
                            options = slideActionOptions.map { it.first },
                            onSelect = { idx ->
                                vm.updateOppoSlideAction(slideActionOptions[idx].second)
                            },
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                SettingCategoryHeader("触感反馈与震动")
                GroupedSettingsCard(containerColor = colors.panelHi) {
                    SettingSwitchRow(
                        title = "笔身书写微震",
                        summary = "激活手写笔内置超线性微马达，落笔时提供真实沙沙纸感反馈",
                        checked = vm.oppoInPenHapticsEnabled,
                        onCheckedChange = { vm.updateOppoInPenHapticsEnabled(it) },
                    )
                    SettingsCardDivider()
                    SettingSwitchRow(
                        title = "手势操作震动反馈",
                        summary = "双击笔身或滑动触控条调节参数时发出轻微触感提示",
                        checked = vm.stylusHapticsEnabled,
                        onCheckedChange = { vm.updateStylusHapticsEnabled(it) },
                    )
                    if (vm.stylusHapticsEnabled) {
                        SettingsCardDivider()
                        SettingSliderRow(
                            title = "震动强度",
                            summary = "${(vm.stylusHapticsIntensity * 100).toInt()}%",
                            value = vm.stylusHapticsIntensity,
                            onValueChange = { vm.updateStylusHapticsIntensity(it) },
                        )
                    }
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
                        },
                    )
                }

                Spacer(Modifier.height(12.dp))

                SettingCategoryHeader("算法与延迟优化")
                GroupedSettingsCard(containerColor = colors.panelHi) {
                    SettingSwitchRow(
                        title = "超低延迟笔迹预测",
                        summary = "ColorOS 毫秒级算法预测落笔轨迹，极速视觉跟随",
                        checked = vm.stylusStrokePredictionEnabled,
                        onCheckedChange = { vm.updateStylusStrokePredictionEnabled(it) },
                    )
                }

                Spacer(Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    ReTextButton("完成", onDismiss, textColor = colors.accent, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
