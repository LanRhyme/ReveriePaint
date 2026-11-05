/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.dialog

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.reverie.paint.R
import com.reverie.paint.core.CrashHandler
import com.reverie.paint.core.telemetry.TelemetryPrefs
import com.reverie.paint.core.telemetry.TelemetryReporter
import com.reverie.paint.ui.components.ReTextButton
import com.reverie.paint.ui.theme.Theme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun CrashReportDialog(
    logInfo: CrashHandler.CrashLogInfo,
    onDismiss: () -> Unit,
) {
    val colors = Theme.current
    val context = LocalContext.current
    var showFullLog by remember { mutableStateOf(false) }
    val canCopy = remember(logInfo.content) { CrashHandler.canCopy(logInfo.content) }

    val formattedTime = remember(logInfo.timestamp) {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(logInfo.timestamp))
    }

    val crashTypeTitle = if (logInfo.isNative) {
        stringResource(R.string.crash_report_type_native)
    } else {
        stringResource(R.string.crash_report_type_java)
    }

    Dialog(
        onDismissRequest = {
            CrashHandler.markLatestLogAsSeen(context, logInfo.timestamp)
            onDismiss()
        },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .widthIn(max = 560.dp)
                .heightIn(max = 680.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(colors.panel)
                .padding(22.dp),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                // 顶部标题栏
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(colors.accent.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.BugReport,
                            contentDescription = "Crash Icon",
                            tint = colors.accent,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.crash_report_title),
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = colors.text,
                        )
                        Text(
                            text = crashTypeTitle,
                            fontSize = 12.sp,
                            color = colors.subText,
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(colors.panelHi.copy(alpha = 0.5f))
                            .clickable {
                                CrashHandler.markLatestLogAsSeen(context, logInfo.timestamp)
                                onDismiss()
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Close",
                            tint = colors.icon,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }

                // 说明文本
                Text(
                    text = stringResource(R.string.crash_report_desc),
                    fontSize = 13.sp,
                    color = colors.subText,
                    lineHeight = 18.sp,
                )

                if (!canCopy) {
                    Text(
                        text = stringResource(R.string.crash_report_long_hint),
                        fontSize = 12.sp,
                        color = colors.accent,
                    )
                }

                // 时间与简略状态标签
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(colors.panelHi.copy(alpha = 0.4f))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = stringResource(R.string.crash_report_time_prefix, formattedTime),
                        fontSize = 12.sp,
                        color = colors.subText,
                    )
                    Text(
                        text = if (logInfo.isNative) {
                            stringResource(R.string.crash_report_tag_native)
                        } else {
                            stringResource(R.string.crash_report_tag_java)
                        },
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = colors.accent,
                    )
                }

                // 日志内容预览 / 展开区域
                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(colors.bg)
                        .border(1.dp, colors.panelHi, RoundedCornerShape(12.dp))
                        .padding(10.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = if (showFullLog) {
                                stringResource(R.string.crash_report_full_title)
                            } else {
                                stringResource(R.string.crash_report_preview_title)
                            },
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = colors.text,
                        )
                        Text(
                            text = if (showFullLog) {
                                stringResource(R.string.crash_report_collapse)
                            } else {
                                stringResource(R.string.crash_report_expand)
                            },
                            fontSize = 12.sp,
                            color = colors.accent,
                            modifier = Modifier
                                .clickable { showFullLog = !showFullLog }
                                .padding(4.dp),
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    val scrollState = rememberScrollState()
                    val horizScrollState = rememberScrollState()
                    val displayContent = if (showFullLog) {
                        logInfo.content
                    } else {
                        logInfo.content.lineSequence().take(15).joinToString("\n") + "\n..."
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 90.dp, max = if (showFullLog) 300.dp else 120.dp)
                            .verticalScroll(scrollState)
                            .horizontalScroll(horizScrollState),
                    ) {
                        Text(
                            text = displayContent,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = colors.subText,
                            lineHeight = 15.sp,
                        )
                    }
                }

                // 底部操作按钮栏
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // 仅当日志不长时才允许复制
                    if (canCopy) {
                        ReTextButton(
                            text = stringResource(R.string.crash_report_copy),
                            onClick = {
                                val ok = CrashHandler.copyToClipboard(context, logInfo.content)
                                if (ok) {
                                    Toast.makeText(context, R.string.crash_report_copied_toast, Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }

                    // 导出到 Downloads
                    val saveSuccessPattern = stringResource(R.string.diagnostics_save_success_toast)
                    ReTextButton(
                        text = stringResource(R.string.crash_report_export),
                        onClick = {
                            val savedPath = CrashHandler.exportCrashLogToDownloads(
                                context,
                                logInfo.file.name,
                                logInfo.content,
                            )
                            if (savedPath != null) {
                                Toast.makeText(
                                    context,
                                    String.format(saveSuccessPattern, savedPath),
                                    Toast.LENGTH_LONG,
                                ).show()
                            } else {
                                Toast.makeText(context, R.string.diagnostics_save_failed_toast, Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.weight(1f),
                    )

                    // 系统文件分享
                    ReTextButton(
                        text = stringResource(R.string.crash_report_share),
                        onClick = {
                            CrashHandler.shareCrashLog(context, logInfo.file)
                        },
                        modifier = Modifier.weight(1f),
                    )
                }

                // 上报状态说明。默认是自动上报, 所以不再放一个"点了也没区别"的按钮;
                // 只有用户自己关掉自动上报时, 才补一个仅针对这一条的手动出口。
                if (TelemetryReporter.isConfigured) {
                    val prefs = remember(context) { TelemetryPrefs(context) }
                    var sent by remember(logInfo.file.name) { mutableStateOf(prefs.isUploaded(logInfo.file.name)) }
                    var uploading by remember { mutableStateOf(false) }
                    val okMsg = stringResource(R.string.crash_report_upload_ok)
                    val failMsg = stringResource(R.string.crash_report_upload_failed)
                    val autoOn = prefs.enabled

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = when {
                                sent -> stringResource(R.string.crash_report_uploaded)
                                autoOn -> stringResource(R.string.crash_report_auto_reporting)
                                else -> stringResource(R.string.crash_report_auto_off)
                            },
                            fontSize = 11.5.sp,
                            lineHeight = 15.sp,
                            color = colors.subText,
                            modifier = Modifier.weight(1f),
                        )
                        if (!sent && !autoOn) {
                            ReTextButton(
                                text = if (uploading) {
                                    stringResource(R.string.crash_report_uploading)
                                } else {
                                    stringResource(R.string.crash_report_upload_once)
                                },
                                onClick = {
                                    if (!uploading) {
                                        uploading = true
                                        TelemetryReporter.uploadNow(context, logInfo.file) { ok ->
                                            uploading = false
                                            if (ok) sent = true
                                            Toast.makeText(
                                                context,
                                                if (ok) okMsg else failMsg,
                                                Toast.LENGTH_SHORT,
                                            ).show()
                                        }
                                    }
                                },
                            )
                        }
                    }
                }

                // 忽略 / 已知晓
                ReTextButton(
                    text = stringResource(R.string.crash_report_dismiss),
                    onClick = {
                        CrashHandler.markLatestLogAsSeen(context, logInfo.timestamp)
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    primary = true,
                )
            }
        }
    }
}
