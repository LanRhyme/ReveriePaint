/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ExitToApp
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.ui.theme.Theme

@Composable
internal fun GeneralSettingsSubPage(
    vm: PaintViewModel,
    onBack: () -> Unit,
    compact: Boolean = false,
    showBackButton: Boolean = true,
) {
    val colors = Theme.current

    val intervalOptions = listOf(
        1 to "1分钟",
        3 to "3分钟",
        5 to "5分钟",
        10 to "10分钟",
        15 to "15分钟",
        30 to "30分钟",
    )

    val undoOptions = listOf(
        30 to "30步",
        50 to "50步 (推荐)",
        100 to "100步",
        200 to "200步",
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
                title = "通用设置",
                subtitle = "自动保存策略、撤销历史步数与退出偏好",
                showBackButton = showBackButton,
                compact = compact,
                onBack = onBack,
            )

            // Section 1: 自动保存
            SettingCategoryTitle("自动保存")
            SettingGroup {
                val autoSaveTotal = if (vm.autoSaveEnabled) 3 else 1
                SettingSwitchGroupItem(
                    icon = Icons.Rounded.Save,
                    title = "启用自动保存",
                    summary = "在绘画过程中按设定时间间隔自动在后台保存作品",
                    checked = vm.autoSaveEnabled,
                    shape = settingGroupShape(0, autoSaveTotal),
                    onCheckedChange = { vm.updateAutoSaveEnabled(it) },
                )

                if (vm.autoSaveEnabled) {
                    SettingSegmentGroupItem(
                        icon = Icons.Rounded.Schedule,
                        title = "保存时间间隔",
                        summary = "自动在后台执行静默保存的时长频率",
                        options = intervalOptions,
                        selected = vm.autoSaveIntervalMinutes,
                        shape = settingGroupShape(1, autoSaveTotal),
                        onSelect = { vm.updateAutoSaveIntervalMinutes(it) },
                    )

                    SettingSwitchGroupItem(
                        icon = Icons.Rounded.NotificationsActive,
                        title = "自动保存轻量提示",
                        summary = "自动保存成功后在屏幕上方弹出非阻塞提示",
                        checked = vm.autoSaveToastEnabled,
                        shape = settingGroupShape(2, autoSaveTotal),
                        onCheckedChange = { vm.updateAutoSaveToastEnabled(it) },
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            // 说明卡片
            SettingInfoCard(
                title = "自动保存机制说明",
                text = "自动保存将在后台静默执行，仅在画布产生修改时触发，且绝不会打断您当前的笔画绘制。",
            )

            // Section 2: 历史记录与性能
            SettingCategoryTitle("历史记录与性能")
            SettingGroup {
                SettingDropdownGroupItem(
                    icon = Icons.Rounded.History,
                    title = "最大撤销步数",
                    summary = "保留的历史操作记录上限，步数越多支持更长撤销链",
                    currentText = undoOptions.find { it.first == vm.maxUndoSteps }?.second ?: "${vm.maxUndoSteps}步",
                    options = undoOptions.map { it.second },
                    shape = settingGroupShape(0, 1),
                    onSelect = { idx ->
                        vm.updateMaxUndoSteps(undoOptions[idx].first)
                    },
                )
            }

            // Section 3: 项目管理
            SettingCategoryTitle("项目管理")
            SettingGroup {
                SettingSwitchGroupItem(
                    icon = Icons.AutoMirrored.Rounded.ExitToApp,
                    title = "退出时提示保存",
                    summary = "若当前画布有未保存的修改，退出到主页时提示保存",
                    checked = vm.promptSaveOnExit,
                    shape = settingGroupShape(0, 1),
                    onCheckedChange = { vm.updatePromptSaveOnExit(it) },
                )
            }

            Spacer(Modifier.height(80.dp))
        }
    }
}
