/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.reverie.paint.R
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.core.exportTimelapseVideo
import com.reverie.paint.core.getTimelapseFrameFiles
import com.reverie.paint.model.Project
import com.reverie.paint.ui.theme.Theme
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * 延时摄影播放预览与 MP4 导出对话框。
 * - 帧序列按 200ms 间隔循环播放，显示「当前帧/总帧数」
 * - 解码新帧后立即 recycle 上一张，避免大画布 OOM
 * - 支持导出为 MP4 视频并自动保存至系统相册 Movies/ReveriePaint
 */
@Composable
fun TimelapseDialog(
    vm: PaintViewModel,
    project: Project? = null,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val colors = Theme.current
    val frameFiles = remember(project, vm.currentProjectFile) {
        vm.getTimelapseFrameFiles(project)
    }

    var currentFrameIndex by remember { mutableIntStateOf(0) }
    var currentBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isPlaying by remember { mutableStateOf(true) }

    var isExporting by remember { mutableStateOf(false) }
    var exportProgress by remember { mutableFloatStateOf(0f) }
    var exportCancelled by remember { mutableStateOf(false) }

    // 帧序列按 200ms 间隔循环播放 (每帧解码后 recycle 上一张，避免 OOM)
    LaunchedEffect(frameFiles, isPlaying) {
        if (frameFiles.isEmpty() || !isPlaying) return@LaunchedEffect
        while (isActive) {
            val file = frameFiles.getOrNull(currentFrameIndex)
            if (file != null && file.exists()) {
                val nextBmp = BitmapFactory.decodeFile(file.absolutePath)
                val oldBmp = currentBitmap
                currentBitmap = nextBmp
                if (oldBmp != null && oldBmp !== nextBmp && !oldBmp.isRecycled) {
                    oldBmp.recycle()
                }
            }
            delay(200)
            if (frameFiles.isNotEmpty()) {
                currentFrameIndex = (currentFrameIndex + 1) % frameFiles.size
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            currentBitmap?.let {
                if (!it.isRecycled) it.recycle()
            }
            currentBitmap = null
        }
    }

    Dialog(
        onDismissRequest = {
            if (!isExporting) onDismiss()
        },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .clip(RoundedCornerShape(20.dp)),
            color = colors.panel,
            tonalElevation = 8.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // 顶部标题栏
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text(
                            text = stringResource(R.string.timelapse_export_title),
                            color = colors.text,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = if (frameFiles.isNotEmpty()) {
                                stringResource(
                                    R.string.timelapse_export_frame_counter,
                                    currentFrameIndex + 1,
                                    frameFiles.size,
                                )
                            } else {
                                stringResource(R.string.timelapse_export_err_no_frames)
                            },
                            color = colors.subText,
                            fontSize = 12.sp,
                        )
                    }

                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(colors.panelHi)
                            .clickable(enabled = !isExporting) { onDismiss() },
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

                Spacer(modifier = Modifier.height(16.dp))

                // 画布画面预览容器
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 10f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(colors.canvasBg),
                    contentAlignment = Alignment.Center,
                ) {
                    val bmp = currentBitmap
                    if (bmp != null && !bmp.isRecycled) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit,
                        )
                    } else if (frameFiles.isEmpty()) {
                        Text(
                            text = stringResource(R.string.timelapse_export_err_no_frames),
                            color = colors.subText,
                            fontSize = 14.sp,
                        )
                    } else {
                        CircularProgressIndicator(
                            color = colors.accent,
                            modifier = Modifier.size(32.dp),
                            strokeWidth = 3.dp,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 导出进度条
                if (isExporting) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = stringResource(R.string.timelapse_exporting),
                            color = colors.text,
                            fontSize = 13.sp,
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        LinearProgressIndicator(
                            progress = { exportProgress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = colors.accent,
                            trackColor = colors.panelHi,
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                }

                // 底部操作按钮
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 播放 / 暂停切换按钮
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(colors.panelHi)
                            .clickable(enabled = !isExporting && frameFiles.isNotEmpty()) {
                                isPlaying = !isPlaying
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            tint = colors.icon,
                            modifier = Modifier.size(24.dp),
                        )
                    }

                    // 导出 MP4 按钮
                    Button(
                        onClick = {
                            if (frameFiles.isEmpty()) {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.timelapse_export_err_no_frames),
                                    Toast.LENGTH_SHORT,
                                ).show()
                                return@Button
                            }
                            isExporting = true
                            exportProgress = 0f
                            exportCancelled = false
                            vm.exportTimelapseVideo(
                                project = project,
                                fps = 12,
                                onProgress = { cur, tot ->
                                    if (tot > 0) {
                                        exportProgress = cur.toFloat() / tot.toFloat()
                                    }
                                },
                                onSuccess = {
                                    isExporting = false
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.timelapse_export_success),
                                        Toast.LENGTH_LONG,
                                    ).show()
                                    onDismiss()
                                },
                                onError = { err ->
                                    isExporting = false
                                    Toast.makeText(context, err, Toast.LENGTH_SHORT).show()
                                },
                                isCancelled = { exportCancelled },
                            )
                        },
                        enabled = !isExporting && frameFiles.isNotEmpty(),
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colors.accent,
                            contentColor = colors.onAccent,
                        ),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_export),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.timelapse_export_to_gallery),
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp,
                        )
                    }
                }
            }
        }
    }
}
