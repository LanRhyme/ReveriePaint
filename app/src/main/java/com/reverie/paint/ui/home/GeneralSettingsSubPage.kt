/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.home

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
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
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.DeveloperMode
import androidx.compose.material.icons.rounded.Highlight
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.reverie.paint.R
import com.reverie.paint.core.AppLanguage
import com.reverie.paint.core.LanguageManager
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.core.*
import com.reverie.paint.ui.dialog.RecentAutoSavesDialog
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
    var showAutoSaveHistoryDialog by remember { mutableStateOf(false) }
    var showDeveloperSettings by remember { mutableStateOf(false) }

    if (showDeveloperSettings) {
        DeveloperSettingsSubPage(
            vm = vm,
            compact = compact,
            onBack = { showDeveloperSettings = false },
        )
        return
    }

    val intervalOptions = listOf(
        1 to stringResource(R.string.settings_minute_unit, 1),
        3 to stringResource(R.string.settings_minute_unit, 3),
        5 to stringResource(R.string.settings_minute_unit, 5),
        10 to stringResource(R.string.settings_minute_unit, 10),
        15 to stringResource(R.string.settings_minute_unit, 15),
        30 to stringResource(R.string.settings_minute_unit, 30),
    )

    val snapshotCountOptions = listOf(
        3 to stringResource(R.string.settings_snapshot_count_unit, 3),
        5 to stringResource(R.string.settings_snapshot_count_unit, 5),
        8 to stringResource(R.string.settings_snapshot_count_default, 8),
        12 to stringResource(R.string.settings_snapshot_count_unit, 12),
        16 to stringResource(R.string.settings_snapshot_count_unit, 16),
        24 to stringResource(R.string.settings_snapshot_count_unit, 24),
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
            .background(if (vm.homeCustomBgEnabled && vm.getHomeCustomBgFile() != null) Color.Transparent else colors.bg)
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

            // Section 1: 基础偏好 (语言 + 项目退出提示)
            SettingCategoryTitle(stringResource(R.string.settings_category_basic))
            SettingGroup {
                val currentLang = LanguageManager.currentLanguage
                val langOptions = listOf(
                    AppLanguage.FOLLOW_SYSTEM to stringResource(R.string.settings_lang_follow_system),
                    AppLanguage.ZH_CN to stringResource(R.string.settings_lang_zh_cn),
                    AppLanguage.EN to stringResource(R.string.settings_lang_en),
                    AppLanguage.JA to stringResource(R.string.settings_lang_ja),
                    AppLanguage.ZH_TW to stringResource(R.string.settings_lang_zh_tw),
                    AppLanguage.CAT to stringResource(R.string.settings_lang_cat),
                )
                val currentText = langOptions.find { it.first == currentLang }?.second
                    ?: stringResource(R.string.settings_lang_follow_system)

                SettingDropdownGroupItem(
                    icon = Icons.Rounded.Language,
                    title = stringResource(R.string.settings_language),
                    summary = stringResource(R.string.settings_language_sub),
                    currentText = currentText,
                    options = langOptions.map { it.second },
                    shape = settingGroupShape(0, 2),
                    onSelect = { idx ->
                        val selectedLang = langOptions[idx].first
                        if (selectedLang != currentLang && activity != null) {
                            LanguageManager.setLanguage(activity, selectedLang)
                        }
                    },
                )

                SettingSwitchGroupItem(
                    icon = Icons.AutoMirrored.Rounded.ExitToApp,
                    title = stringResource(R.string.settings_prompt_save_exit),
                    summary = stringResource(R.string.settings_prompt_save_exit_sub),
                    checked = vm.promptSaveOnExit,
                    shape = settingGroupShape(1, 2),
                    onCheckedChange = { vm.updatePromptSaveOnExit(it) },
                )
            }

            // Section 2: 自动保存
            SettingCategoryTitle(stringResource(R.string.settings_auto_save))
            SettingGroup {
                val autoSaveTotal = if (vm.autoSaveEnabled) 5 else 1
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

                    SettingSegmentGroupItem(
                        icon = Icons.Rounded.History,
                        title = stringResource(R.string.settings_auto_save_max_snapshots),
                        summary = stringResource(R.string.settings_auto_save_max_snapshots_sub),
                        options = snapshotCountOptions,
                        selected = vm.autoSaveMaxSnapshots,
                        shape = settingGroupShape(2, autoSaveTotal),
                        onSelect = { vm.updateAutoSaveMaxSnapshots(it) },
                    )

                    SettingSwitchGroupItem(
                        icon = Icons.Rounded.NotificationsActive,
                        title = stringResource(R.string.settings_auto_save_toast),
                        summary = stringResource(R.string.settings_auto_save_toast_sub),
                        checked = vm.autoSaveToastEnabled,
                        shape = settingGroupShape(3, autoSaveTotal),
                        onCheckedChange = { vm.updateAutoSaveToastEnabled(it) },
                    )

                    SettingNavGroupItem(
                        icon = Icons.Rounded.History,
                        title = stringResource(R.string.settings_auto_save_history),
                        summary = stringResource(R.string.settings_auto_save_history_sub),
                        shape = settingGroupShape(4, autoSaveTotal),
                        onClick = { showAutoSaveHistoryDialog = true },
                    )
                }
            }

            if (vm.autoSaveEnabled) {
                Spacer(Modifier.height(8.dp))
                SettingInfoCard(
                    title = stringResource(R.string.settings_auto_save_info_title),
                    text = stringResource(R.string.settings_auto_save_info_desc),
                )
            }

            // Section 2.5: 延时摄影录制
            SettingCategoryTitle(stringResource(R.string.settings_category_timelapse))
            SettingGroup {
                val timelapseTotal = if (vm.timelapseRecordingEnabled) 5 else 1
                SettingSwitchGroupItem(
                    icon = Icons.Rounded.History,
                    title = stringResource(R.string.settings_timelapse_enable),
                    summary = stringResource(R.string.settings_timelapse_enable_sub),
                    checked = vm.timelapseRecordingEnabled,
                    shape = settingGroupShape(0, timelapseTotal),
                    onCheckedChange = { vm.updateTimelapseRecordingEnabled(it) },
                )

                if (vm.timelapseRecordingEnabled) {
                    val resolutionOptions = listOf(
                        720 to stringResource(R.string.settings_timelapse_res_720p),
                        1080 to stringResource(R.string.settings_timelapse_res_1080p),
                        1440 to stringResource(R.string.settings_timelapse_res_2k),
                        2160 to stringResource(R.string.settings_timelapse_res_4k),
                    )

                    SettingSegmentGroupItem(
                        icon = Icons.Rounded.AspectRatio,
                        title = stringResource(R.string.settings_timelapse_resolution),
                        summary = stringResource(R.string.settings_timelapse_resolution_sub),
                        options = resolutionOptions,
                        selected = vm.timelapseResolution,
                        shape = settingGroupShape(1, timelapseTotal),
                        onSelect = { vm.updateTimelapseResolution(it) },
                    )

                    val qualityOptions = listOf(
                        75 to stringResource(R.string.settings_timelapse_quality_standard),
                        85 to stringResource(R.string.settings_timelapse_quality_high),
                        95 to stringResource(R.string.settings_timelapse_quality_ultra),
                    )

                    SettingSegmentGroupItem(
                        icon = Icons.Rounded.AutoFixHigh,
                        title = stringResource(R.string.settings_timelapse_quality),
                        summary = stringResource(R.string.settings_timelapse_quality_sub),
                        options = qualityOptions,
                        selected = vm.timelapseQuality,
                        shape = settingGroupShape(2, timelapseTotal),
                        onSelect = { vm.updateTimelapseQuality(it) },
                    )

                    val intervalOptions = listOf(
                        200L to stringResource(R.string.settings_timelapse_interval_sec_unit, "0.2"),
                        500L to stringResource(R.string.settings_timelapse_interval_sec_unit, "0.5"),
                        1000L to stringResource(R.string.settings_timelapse_interval_sec_unit, "1.0"),
                        2000L to stringResource(R.string.settings_timelapse_interval_sec_unit, "2.0"),
                    )

                    SettingSegmentGroupItem(
                        icon = Icons.Rounded.Schedule,
                        title = stringResource(R.string.settings_timelapse_min_interval),
                        summary = stringResource(R.string.settings_timelapse_min_interval_sub),
                        options = intervalOptions,
                        selected = vm.timelapseMinIntervalMs,
                        shape = settingGroupShape(3, timelapseTotal),
                        onSelect = { vm.updateTimelapseMinInterval(it) },
                    )

                    val maxFramesOptions = listOf(
                        1000 to stringResource(R.string.settings_timelapse_frames_unit, 1000),
                        2000 to stringResource(R.string.settings_timelapse_frames_unit, 2000),
                        3000 to stringResource(R.string.settings_timelapse_frames_unit, 3000),
                        5000 to stringResource(R.string.settings_timelapse_frames_unit, 5000),
                    )

                    SettingSegmentGroupItem(
                        icon = Icons.Rounded.Speed,
                        title = stringResource(R.string.settings_timelapse_max_frames),
                        summary = stringResource(R.string.settings_timelapse_max_frames_sub),
                        options = maxFramesOptions,
                        selected = vm.timelapseMaxFrames,
                        shape = settingGroupShape(4, timelapseTotal),
                        onSelect = { vm.updateTimelapseMaxFrames(it) },
                    )
                }
            }

            // 切层内容闪现提示 (自动化"隐藏-查看-恢复"的确认动作): 独立分组, 不挤既有 settingGroupShape 编号
            SettingGroup {
                SettingSwitchGroupItem(
                    icon = Icons.Rounded.Highlight,
                    title = stringResource(R.string.settings_layer_flash),
                    summary = stringResource(R.string.settings_layer_flash_sub),
                    checked = vm.layerFlashEnabled,
                    shape = settingGroupShape(0, 1),
                    onCheckedChange = { vm.updateLayerFlashEnabled(it) },
                )
            }

            // Section 3: 存储与撤销性能
            SettingCategoryTitle(stringResource(R.string.settings_category_storage_perf))
            SettingGroup {
                val scheduleModeOptions = listOf(
                    com.reverie.paint.model.RenderScheduleMode.PERFORMANCE.id to stringResource(R.string.settings_schedule_mode_perf),
                    com.reverie.paint.model.RenderScheduleMode.BALANCED.id to stringResource(R.string.settings_schedule_mode_balanced),
                    com.reverie.paint.model.RenderScheduleMode.POWER_SAVER.id to stringResource(R.string.settings_schedule_mode_saver),
                )
                val scheduleModeSummary = when (vm.renderScheduleMode) {
                    com.reverie.paint.model.RenderScheduleMode.PERFORMANCE -> stringResource(R.string.settings_schedule_mode_perf_desc)
                    com.reverie.paint.model.RenderScheduleMode.BALANCED -> stringResource(R.string.settings_schedule_mode_balanced_desc)
                    com.reverie.paint.model.RenderScheduleMode.POWER_SAVER -> stringResource(R.string.settings_schedule_mode_saver_desc)
                }

                SettingSegmentGroupItem(
                    icon = Icons.Rounded.Speed,
                    title = stringResource(R.string.settings_render_schedule_mode),
                    summary = scheduleModeSummary,
                    options = scheduleModeOptions,
                    selected = vm.renderScheduleMode.id,
                    shape = settingGroupShape(0, 3),
                    onSelect = { id ->
                        vm.updateRenderScheduleMode(com.reverie.paint.model.RenderScheduleMode.fromId(id))
                    },
                )

                SettingDropdownGroupItem(
                    icon = Icons.Rounded.History,
                    title = stringResource(R.string.settings_max_undo_steps),
                    summary = stringResource(R.string.settings_max_undo_steps_sub),
                    currentText = undoOptions.find { it.first == vm.maxUndoSteps }?.second
                        ?: stringResource(R.string.settings_step_unit, vm.maxUndoSteps),
                    options = undoOptions.map { it.second },
                    shape = settingGroupShape(1, 3),
                    onSelect = { idx ->
                        vm.updateMaxUndoSteps(undoOptions[idx].first)
                    },
                )

                ReferenceCacheSettings(
                    vm = vm,
                    shape = settingGroupShape(2, 3),
                    showTitle = false,
                )
            }

            // Section 4: 隐私 (崩溃报告自动上报)
            TelemetrySettingsSection()

            // Section 5: 高级与开发者选项
            SettingCategoryTitle(stringResource(R.string.settings_category_advanced))
            SettingGroup {
                SettingNavGroupItem(
                    icon = Icons.Rounded.DeveloperMode,
                    title = stringResource(R.string.settings_developer_options),
                    summary = stringResource(R.string.settings_developer_options_sub),
                    shape = settingGroupShape(0, 1),
                    onClick = { showDeveloperSettings = true },
                )
            }

            Spacer(Modifier.height(80.dp))
        }
    }

    if (showAutoSaveHistoryDialog) {
        RecentAutoSavesDialog(
            vm = vm,
            onDismiss = { showAutoSaveHistoryDialog = false },
        )
    }
}
