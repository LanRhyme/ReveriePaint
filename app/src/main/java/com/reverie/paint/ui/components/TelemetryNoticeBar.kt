/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BugReport
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reverie.paint.R
import com.reverie.paint.core.telemetry.TelemetryPrefs
import com.reverie.paint.core.telemetry.TelemetryReporter
import com.reverie.paint.ui.theme.Theme

/**
 * 首启一次性遥测告知条 (非模态, 不阻断任何操作)。
 *
 * 只在同时满足三条时出现: 构建注入了端点、用户没关过、还没点过"知道了"。
 * 点任意一个按钮后写入 noticeSeen, 之后永不再现; "关闭上报"同时清掉本地标识。
 */
@Composable
fun TelemetryNoticeBar(modifier: Modifier = Modifier) {
    if (!TelemetryReporter.isConfigured) return

    val context = LocalContext.current
    val colors = Theme.current
    val prefs = remember(context) { TelemetryPrefs(context) }
    var visible by remember { mutableStateOf(!prefs.noticeSeen && prefs.enabled) }
    if (!visible) return

    fun dismiss(disableUpload: Boolean) {
        if (disableUpload) prefs.enabled = false
        prefs.noticeSeen = true
        visible = false
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(colors.panel.copy(alpha = 0.9f))
            .border(1.dp, colors.border, RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.foundation.layout.Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(colors.accent.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.BugReport,
                    contentDescription = null,
                    tint = colors.accent,
                    modifier = Modifier.size(17.dp),
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.telemetry_notice_title),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.text,
                )
                Spacer(Modifier.size(2.dp))
                Text(
                    text = stringResource(R.string.telemetry_notice_body),
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    color = colors.subText,
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.telemetry_notice_disable),
                fontSize = 12.sp,
                color = colors.subText,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { dismiss(disableUpload = true) }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
            Text(
                text = stringResource(R.string.telemetry_notice_ok),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = colors.accent,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { dismiss(disableUpload = false) }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
}
