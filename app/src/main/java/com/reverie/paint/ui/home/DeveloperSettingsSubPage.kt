/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.home

import android.os.Build
import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.reverie.paint.R
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.perf.PerfHud
import com.reverie.paint.ui.dialog.DiagnosticsDialog
import com.reverie.paint.ui.theme.Theme

@Composable
internal fun DeveloperSettingsSubPage(
    vm: PaintViewModel,
    onBack: () -> Unit,
    compact: Boolean = false,
) {
    BackHandler(onBack = onBack)

    val colors = Theme.current
    var showDiagnosticsDialog by remember { mutableStateOf(false) }

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
                title = stringResource(R.string.settings_developer_options),
                subtitle = stringResource(R.string.settings_developer_options_sub),
                showBackButton = true,
                compact = compact,
                onBack = onBack,
            )

            // Section 1: 性能标尺与渲染实验 (Debug 构建展示全部选项, Release 为同名空实现)
            PerfHud.SettingsSection(vm)

            // Section 2: 系统运行诊断
            SettingCategoryTitle(stringResource(R.string.settings_category_diagnostics_tools))
            SettingGroup {
                SettingNavGroupItem(
                    icon = Icons.AutoMirrored.Rounded.Article,
                    title = stringResource(R.string.settings_diagnostics_export),
                    summary = stringResource(R.string.settings_diagnostics_export_sub),
                    shape = settingGroupShape(0, 1),
                    onClick = { showDiagnosticsDialog = true },
                )
            }

            // Section 3: 环境与运行时信息
            SettingCategoryTitle(stringResource(R.string.settings_category_environment))
            SettingGroup {
                val primaryAbi = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
                val deviceModel = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}, API ${Build.VERSION.SDK_INT})"

                SettingValueGroupItem(
                    icon = Icons.Rounded.Smartphone,
                    title = stringResource(R.string.settings_env_device),
                    value = deviceModel,
                    shape = settingGroupShape(0, 2),
                )

                SettingValueGroupItem(
                    icon = Icons.Rounded.Memory,
                    title = stringResource(R.string.settings_env_engine),
                    value = "KisImage / KisPainter ($primaryAbi)",
                    shape = settingGroupShape(1, 2),
                )
            }

            Spacer(Modifier.height(80.dp))
        }
    }

    if (showDiagnosticsDialog) {
        DiagnosticsDialog(onDismiss = { showDiagnosticsDialog = false })
    }
}
