/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.dialog

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.OpenInBrowser
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.reverie.paint.BuildConfig
import com.reverie.paint.core.UpdateManager
import com.reverie.paint.model.DownloadStatus
import com.reverie.paint.model.ReleaseInfo
import com.reverie.paint.ui.components.ReIconButton
import com.reverie.paint.ui.components.ReTextButton
import com.reverie.paint.ui.theme.Morandi
import com.reverie.paint.ui.theme.Theme
import java.util.Locale

@Composable
fun UpdateDialog(
    release: ReleaseInfo,
    onDismiss: () -> Unit,
) {
    val colors = Theme.current
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current

    val status = UpdateManager.downloadStatus
    val progress = UpdateManager.downloadProgress
    val downloaded = UpdateManager.downloadedBytes
    val total = UpdateManager.totalBytes

    Dialog(
        onDismissRequest = {
            if (status != DownloadStatus.DOWNLOADING) {
                onDismiss()
            }
        },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .widthIn(max = 480.dp)
                .heightIn(max = 620.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Morandi.panel)
                .border(1.dp, Morandi.border, RoundedCornerShape(24.dp))
                .padding(22.dp),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
            ) {
                // 1. 顶部标题栏
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(Morandi.accent.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.SystemUpdate,
                                contentDescription = null,
                                tint = Morandi.accent,
                                modifier = Modifier.size(20.dp),
                            )
                        }

                        Column {
                            Text(
                                text = "发现新版本",
                                color = colors.text,
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = "当前: v${BuildConfig.VERSION_NAME}",
                                color = Morandi.subText,
                                fontSize = 11.sp,
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(Morandi.panelHi)
                            .border(1.dp, Morandi.border, CircleShape)
                            .clickable {
                                if (status == DownloadStatus.DOWNLOADING) {
                                    UpdateManager.cancelDownload()
                                }
                                onDismiss()
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "关闭",
                            tint = Morandi.icon,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))

                // 2. 版本号与大小标签卡片
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Morandi.panelHi)
                        .border(1.dp, Morandi.border, RoundedCornerShape(12.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column {
                        Text(
                            text = release.name.ifBlank { release.tagName },
                            color = colors.text,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (release.publishedAt.isNotBlank()) {
                            Text(
                                text = "发布于 " + release.publishedAt.take(10),
                                color = Morandi.subText,
                                fontSize = 11.sp,
                            )
                        }
                    }

                    if (release.apkAsset != null && release.apkAsset.size > 0L) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(Morandi.accent.copy(alpha = 0.15f))
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                        ) {
                            Text(
                                text = formatFileSize(release.apkAsset.size),
                                color = Morandi.accent,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                // 3. 更新日志内容卡片
                Text(
                    text = "更新内容",
                    color = Morandi.subText,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(start = 2.dp, bottom = 6.dp),
                )

                val scrollState = rememberScrollState()
                Box(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .heightIn(min = 80.dp, max = 220.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Morandi.panelHi)
                        .border(1.dp, Morandi.border, RoundedCornerShape(12.dp))
                        .padding(12.dp)
                        .verticalScroll(scrollState),
                ) {
                    val bodyText = release.body.ifBlank { "暂无详细更新说明" }
                    Text(
                        text = bodyText,
                        color = colors.text.copy(alpha = 0.9f),
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                    )
                }

                Spacer(Modifier.height(16.dp))

                // 4. 下载进度或状态展示
                when (status) {
                    DownloadStatus.DOWNLOADING -> {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    text = "正在下载更新...",
                                    color = Morandi.accent,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                                Text(
                                    text = "${(progress * 100).toInt()}%  (${formatFileSize(downloaded)} / ${formatFileSize(total)})",
                                    color = Morandi.subText,
                                    fontSize = 11.sp,
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                            LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(3.dp)),
                                color = Morandi.accent,
                                trackColor = Morandi.panelHi,
                            )
                        }
                    }

                    DownloadStatus.FAILED -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFFE57373).copy(alpha = 0.15f))
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            Text(
                                text = "下载失败: ${UpdateManager.downloadError ?: "网络错误"}",
                                color = Color(0xFFE57373),
                                fontSize = 12.sp,
                            )
                        }
                    }

                    DownloadStatus.COMPLETED -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(Morandi.accent.copy(alpha = 0.15f))
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            Text(
                                text = "安装包已就绪，正在准备安装...",
                                color = Morandi.accent,
                                fontSize = 12.sp,
                            )
                        }
                    }

                    DownloadStatus.CANCELED -> {
                        Text(
                            text = "已取消下载",
                            color = Morandi.subText,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                    }

                    DownloadStatus.IDLE -> {
                        // 空闲无特殊展示
                    }
                }

                Spacer(Modifier.height(14.dp))

                // 5. 底部操作按钮栏
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    // 左侧：在浏览器中打开兜底按钮
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { uriHandler.openUri(release.htmlUrl) }
                            .padding(horizontal = 6.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.OpenInBrowser,
                            contentDescription = "浏览器打开",
                            tint = Morandi.subText,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "前往浏览器",
                            color = Morandi.subText,
                            fontSize = 12.sp,
                        )
                    }

                    // 右侧动作按钮
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        when (status) {
                            DownloadStatus.IDLE, DownloadStatus.CANCELED -> {
                                ReTextButton(
                                    text = "稍后",
                                    onClick = onDismiss,
                                    textColor = Morandi.subText,
                                )

                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(Morandi.accent)
                                        .clickable {
                                            UpdateManager.startDownload(context, release)
                                        }
                                        .padding(horizontal = 16.dp, vertical = 8.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Rounded.FileDownload,
                                            contentDescription = null,
                                            tint = Color.White,
                                            modifier = Modifier.size(16.dp),
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Text(
                                            text = "立即更新",
                                            color = Color.White,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                    }
                                }
                            }

                            DownloadStatus.DOWNLOADING -> {
                                ReTextButton(
                                    text = "取消",
                                    onClick = { UpdateManager.cancelDownload() },
                                    textColor = Morandi.subText,
                                )

                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(Morandi.panelHi)
                                        .border(1.dp, Morandi.border, RoundedCornerShape(10.dp))
                                        .clickable { onDismiss() }
                                        .padding(horizontal = 14.dp, vertical = 8.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = "后台下载",
                                        color = colors.text,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium,
                                    )
                                }
                            }

                            DownloadStatus.FAILED -> {
                                ReTextButton(
                                    text = "关闭",
                                    onClick = onDismiss,
                                    textColor = Morandi.subText,
                                )

                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(Morandi.accent)
                                        .clickable {
                                            UpdateManager.startDownload(context, release)
                                        }
                                        .padding(horizontal = 16.dp, vertical = 8.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = "重试下载",
                                        color = Color.White,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                }
                            }

                            DownloadStatus.COMPLETED -> {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(Morandi.accent)
                                        .clickable {
                                            val file = UpdateManager.downloadedApkFile
                                            if (file != null) {
                                                UpdateManager.installApk(context, file)
                                            }
                                        }
                                        .padding(horizontal = 18.dp, vertical = 8.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = "立即安装",
                                        color = Color.White,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatFileSize(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    var b = bytes.toDouble()
    var i = 0
    while (b >= 1024.0 && i < units.size - 1) {
        b /= 1024.0
        i++
    }
    return String.format(Locale.US, "%.1f %s", b, units[i])
}
