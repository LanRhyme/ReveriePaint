/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.home

import android.widget.Toast
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.reverie.paint.R
import com.reverie.paint.core.telemetry.TelemetryPrefs
import com.reverie.paint.core.telemetry.TelemetryReporter

/**
 * 设置页 → 隐私与诊断。
 *
 * 端点未注入的构建(fork / 第三方再分发 / 纯净构建)整节不渲染 —— 不给一个点了也没用的开关。
 */
@Composable
internal fun TelemetrySettingsSection() {
    if (!TelemetryReporter.isConfigured) return

    val context = LocalContext.current
    val prefs = remember(context) { TelemetryPrefs(context) }
    var enabled by remember { mutableStateOf(prefs.enabled) }

    SettingCategoryTitle(stringResource(R.string.settings_category_privacy))
    SettingGroup {
        SettingSwitchGroupItem(
            icon = Icons.Rounded.BugReport,
            title = stringResource(R.string.settings_telemetry_title),
            summary = stringResource(R.string.settings_telemetry_sub),
            checked = enabled,
            shape = settingGroupShape(0, 1),
            onCheckedChange = { on ->
                enabled = on
                prefs.enabled = on
                if (on) {
                    prefs.reviveAfterUserEnable()
                } else {
                    Toast.makeText(context, R.string.settings_telemetry_off_toast, Toast.LENGTH_SHORT).show()
                }
            },
        )
    }
}
