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
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.reverie.paint.R
import com.reverie.paint.core.DiagnosticsManager
import com.reverie.paint.ui.components.ReTextButton
import com.reverie.paint.ui.theme.Morandi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 全景系统运行诊断与混合日志对话框。
 * 提供当前进程日志实时抓取、脱敏预览、一键复制、保存到 Downloads 与系统分享功能。
 */
@Composable
fun DiagnosticsDialog(
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val saveSuccessTemplate = stringResource(R.string.diagnostics_save_success_toast)
    var reportContent by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var showFullLog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val generated = withContext(Dispatchers.IO) {
            DiagnosticsManager.generateDiagnosticsReport(context)
        }
        reportContent = generated
        isLoading = false
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .widthIn(max = 580.dp)
                .heightIn(max = 700.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Morandi.panel)
                .border(1.dp, Morandi.border, RoundedCornerShape(24.dp))
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
                            .background(Morandi.accent.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.Article,
                            contentDescription = "Diagnostics Icon",
                            tint = Morandi.accent,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.diagnostics_dialog_title),
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = Morandi.text,
                        )
                        Text(
                            text = stringResource(R.string.diagnostics_dialog_desc),
                            fontSize = 12.sp,
                            color = Morandi.subText,
                            lineHeight = 16.sp,
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(Morandi.panelHi.copy(alpha = 0.5f))
                            .clickable(onClick = onDismiss),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Close",
                            tint = Morandi.icon,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }

                if (isLoading) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(260.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(32.dp),
                                color = Morandi.accent,
                                strokeWidth = 3.dp,
                            )
                            Text(
                                text = stringResource(R.string.diagnostics_dialog_generating),
                                fontSize = 13.sp,
                                color = Morandi.subText,
                            )
                        }
                    }
                } else {
                    val content = reportContent.orEmpty()
                    val lineCount = remember(content) { content.lines().size }

                    // 日志内容预览区域
                    Column(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Morandi.panelHi.copy(alpha = 0.35f))
                            .border(1.dp, Morandi.border, RoundedCornerShape(12.dp))
                            .padding(10.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "共 $lineCount 行诊断记录",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Morandi.text,
                            )
                            Text(
                                text = if (showFullLog) "仅看摘要" else "展开完整",
                                fontSize = 12.sp,
                                color = Morandi.accent,
                                modifier = Modifier
                                    .clickable { showFullLog = !showFullLog }
                                    .padding(4.dp),
                            )
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        val scrollState = rememberScrollState()
                        val horizScrollState = rememberScrollState()
                        val displayContent = remember(content, showFullLog) {
                            if (showFullLog) {
                                content
                            } else {
                                val lines = content.lines()
                                val head = lines.take(25).joinToString("\n")
                                val tail = lines.takeLast(25).joinToString("\n")
                                "$head\n\n... (中间已省略 ${lines.size - 50} 行，点击右上角展开完整查看) ...\n\n$tail"
                            }
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 140.dp, max = if (showFullLog) 360.dp else 220.dp)
                                .verticalScroll(scrollState)
                                .horizontalScroll(horizScrollState),
                        ) {
                            Text(
                                text = displayContent,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = Morandi.subText,
                                lineHeight = 15.sp,
                            )
                        }
                    }

                    // 底部操作按钮栏
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // 复制
                        ReTextButton(
                            text = stringResource(R.string.diagnostics_copy_btn),
                            onClick = {
                                DiagnosticsManager.copyReportToClipboard(context, content)
                            },
                            modifier = Modifier.weight(1f),
                            textColor = Morandi.text,
                            fontSize = 13.sp,
                        )

                        // 保存到下载
                        ReTextButton(
                            text = stringResource(R.string.diagnostics_save_downloads_btn),
                            onClick = {
                                val savedPath = DiagnosticsManager.exportReportToDownloads(context, content)
                                if (savedPath != null) {
                                    Toast.makeText(
                                        context,
                                        String.format(saveSuccessTemplate, savedPath),
                                        Toast.LENGTH_LONG,
                                    ).show()
                                } else {
                                    Toast.makeText(
                                        context,
                                        R.string.diagnostics_save_failed_toast,
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            },
                            modifier = Modifier.weight(1f),
                            textColor = Morandi.text,
                            fontSize = 13.sp,
                        )

                        // 分享
                        ReTextButton(
                            text = stringResource(R.string.diagnostics_share_btn),
                            onClick = {
                                DiagnosticsManager.shareReport(context, content)
                            },
                            modifier = Modifier.weight(1f),
                            textColor = Morandi.accent,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                        )
                    }
                }
            }
        }
    }
}
