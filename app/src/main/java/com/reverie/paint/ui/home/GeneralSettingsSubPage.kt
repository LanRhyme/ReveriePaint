/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.home

import android.app.Activity
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
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.reverie.paint.R
import com.reverie.paint.core.AppLanguage
import com.reverie.paint.core.LanguageManager
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.perf.PerfHud
import com.reverie.paint.ui.theme.Theme

@Composable
internal fun GeneralSettingsSubPage(
    vm: PaintViewModel,
    onBack: () -> Unit,
    compact: Boolean = false,
    showBackButton: Boolean = true,
) {
    val colors = Theme.current
    val context = LocalContext.current
    val activity = context as? Activity

    val intervalOptions = listOf(
        1 to stringResource(R.string.settings_minute_unit, 1),
        3 to stringResource(R.string.settings_minute_unit, 3),
        5 to stringResource(R.string.settings_minute_unit, 5),
        10 to stringResource(R.string.settings_minute_unit, 10),
        15 to stringResource(R.string.settings_minute_unit, 15),
        30 to stringResource(R.string.settings_minute_unit, 30),
    )

    val undoOptions = listOf(
        30 to stringResource(R.string.settings_step_unit, 30),
        50 to stringResource(R.string.settings_step_recommend, 50),
        100 to stringResource(R.string.settings_step_unit, 100),
        200 to stringResource(R.string.settings_step_unit, 200),
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
                title = stringResource(R.string.settings_general),
                subtitle = stringResource(R.string.settings_general_sub),
                showBackButton = showBackButton,
                compact = compact,
                onBack = onBack,
            )

            // Section 0: 语言选择
            SettingCategoryTitle(stringResource(R.string.settings_language))
            SettingGroup {
                val currentLang = LanguageManager.currentLanguage
                val langOptions = listOf(
                    AppLanguage.FOLLOW_SYSTEM to stringResource(R.string.settings_lang_follow_system),
                    AppLanguage.ZH_CN to stringResource(R.string.settings_lang_zh_cn),
                    AppLanguage.EN to stringResource(R.string.settings_lang_en),
                )
                val currentText = langOptions.find { it.first == currentLang }?.second
                    ?: stringResource(R.string.settings_lang_follow_system)

                SettingDropdownGroupItem(
                    icon = Icons.Rounded.Language,
                    title = stringResource(R.string.settings_language),
                    summary = stringResource(R.string.settings_language_sub),
                    currentText = currentText,
                    options = langOptions.map { it.second },
                    shape = settingGroupShape(0, 1),
                    onSelect = { idx ->
                        val selectedLang = langOptions[idx].first
                        if (selectedLang != currentLang && activity != null) {
                            LanguageManager.setLanguage(activity, selectedLang)
                        }
                    },
                )
            }

            Spacer(Modifier.height(10.dp))

            // Section 1: 自动保存
            SettingCategoryTitle(stringResource(R.string.settings_auto_save))
            SettingGroup {
                val autoSaveTotal = if (vm.autoSaveEnabled) 3 else 1
                SettingSwitchGroupItem(
                    icon = Icons.Rounded.Save,
                    title = stringResource(R.string.settings_auto_save_enable),
                    summary = stringResource(R.string.settings_auto_save_enable_sub),
                    checked = vm.autoSaveEnabled,
                    shape = settingGroupShape(0, autoSaveTotal),
                    onCheckedChange = { vm.updateAutoSaveEnabled(it) },
                )

                if (vm.autoSaveEnabled) {
                    SettingSegmentGroupItem(
                        icon = Icons.Rounded.Schedule,
                        title = stringResource(R.string.settings_auto_save_interval),
                        summary = stringResource(R.string.settings_auto_save_interval_sub),
                        options = intervalOptions,
                        selected = vm.autoSaveIntervalMinutes,
                        shape = settingGroupShape(1, autoSaveTotal),
                        onSelect = { vm.updateAutoSaveIntervalMinutes(it) },
                    )

                    SettingSwitchGroupItem(
                        icon = Icons.Rounded.NotificationsActive,
                        title = stringResource(R.string.settings_auto_save_toast),
                        summary = stringResource(R.string.settings_auto_save_toast_sub),
                        checked = vm.autoSaveToastEnabled,
                        shape = settingGroupShape(2, autoSaveTotal),
                        onCheckedChange = { vm.updateAutoSaveToastEnabled(it) },
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            // 说明卡片
            SettingInfoCard(
                title = stringResource(R.string.settings_auto_save_info_title),
                text = stringResource(R.string.settings_auto_save_info_desc),
            )

            // Section 2: 历史记录与性能
            SettingCategoryTitle(stringResource(R.string.settings_history_performance))
            SettingGroup {
                SettingDropdownGroupItem(
                    icon = Icons.Rounded.History,
                    title = stringResource(R.string.settings_max_undo_steps),
                    summary = stringResource(R.string.settings_max_undo_steps_sub),
                    currentText = undoOptions.find { it.first == vm.maxUndoSteps }?.second
                        ?: stringResource(R.string.settings_step_unit, vm.maxUndoSteps),
                    options = undoOptions.map { it.second },
                    shape = settingGroupShape(0, 1),
                    onSelect = { idx ->
                        vm.updateMaxUndoSteps(undoOptions[idx].first)
                    },
                )
            }

            // Section 3: 项目管理
            SettingCategoryTitle(stringResource(R.string.settings_project_manage))
            SettingGroup {
                SettingSwitchGroupItem(
                    icon = Icons.AutoMirrored.Rounded.ExitToApp,
                    title = stringResource(R.string.settings_prompt_save_exit),
                    summary = stringResource(R.string.settings_prompt_save_exit_sub),
                    checked = vm.promptSaveOnExit,
                    shape = settingGroupShape(0, 1),
                    onCheckedChange = { vm.updatePromptSaveOnExit(it) },
                )
            }

            // Section 4: 诊断 (性能标尺)。debug 构建才有内容, 正式版是空实现 ——
            // 见 [com.reverie.paint.perf.PerfHud]。
            PerfHud.SettingsSection(vm)

            Spacer(Modifier.height(80.dp))
        }
    }
}
