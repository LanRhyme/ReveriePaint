/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.replay

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.clickable
import android.util.LruCache
import com.reverie.paint.R
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.core.exitReplay
import com.reverie.paint.core.getTimelapseFrameFiles
import com.reverie.paint.ui.components.ReFab
import com.reverie.paint.ui.components.ReIconButton
import com.reverie.paint.ui.components.ReSlider
import com.reverie.paint.ui.painting.TimelapseDialog
import com.reverie.paint.ui.theme.Theme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/** 简单的内存解码帧缓存，加速拖动条拖拽平滑度 */
private object ReplayBitmapCache {
    private val cache = object : LruCache<String, Bitmap>(24) {
        override fun entryRemoved(evicted: Boolean, key: String?, oldValue: Bitmap?, newValue: Bitmap?) {
            // 不主动 recycle，由 GC 统一管理，避免异步加载并发竞争
        }
    }

    fun get(path: String): Bitmap? = cache.get(path)
    fun put(path: String, bitmap: Bitmap) {
        cache.put(path, bitmap)
    }
}

/**
 * 延时摄影独立全屏回放页。
 * 基于周期捕获的 frame_%05d.webp 序列帧进行全屏播放与时间轴拖拽控制。
 */
@Composable
fun ReplayPage(vm: PaintViewModel) {
    val colors = Theme.current
    val project = vm.replayProject
    val frameFiles = remember(project, vm.currentProjectFile) {
        vm.getTimelapseFrameFiles(project)
    }

    var currentFrameIndex by remember { mutableIntStateOf(0) }
    var currentBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isPlaying by remember { mutableStateOf(frameFiles.isNotEmpty()) }
    var speed by remember { mutableFloatStateOf(1f) }
    var isDragging by remember { mutableStateOf(false) }
    var dragProgress by remember { mutableFloatStateOf(-1f) }
    var showExportDialog by remember { mutableStateOf(false) }

    // 手势缩放与平移状态
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    val presetSpeeds = remember { listOf(0.5f, 1f, 2f, 4f, 8f) }

    BackHandler { vm.exitReplay() }

    // 播放循环调度
    LaunchedEffect(isPlaying, speed, isDragging, frameFiles.size) {
        if (!isPlaying || isDragging || frameFiles.isEmpty()) return@LaunchedEffect
        while (isActive && isPlaying && !isDragging) {
            val delayMs = (1000L / (12f * speed)).toLong().coerceIn(16L, 1000L)
            delay(delayMs)
            if (currentFrameIndex >= frameFiles.size - 1) {
                isPlaying = false
            } else {
                currentFrameIndex++
            }
        }
    }

    // 异步解码当前帧并加载缓存
    LaunchedEffect(currentFrameIndex, frameFiles) {
        if (frameFiles.isEmpty() || currentFrameIndex !in frameFiles.indices) {
            currentBitmap = null
            return@LaunchedEffect
        }
        val targetFile = frameFiles[currentFrameIndex]
        val cached = ReplayBitmapCache.get(targetFile.absolutePath)
        if (cached != null && !cached.isRecycled) {
            currentBitmap = cached
        } else {
            withContext(Dispatchers.IO) {
                try {
                    val bmp = BitmapFactory.decodeFile(targetFile.absolutePath)
                    if (bmp != null) {
                        ReplayBitmapCache.put(targetFile.absolutePath, bmp)
                        withContext(Dispatchers.Main) {
                            currentBitmap = bmp
                        }
                    }
                } catch (_: Throwable) {}
            }
        }

        // 预加载临近帧
        withContext(Dispatchers.IO) {
            val nextIdx = currentFrameIndex + 1
            if (nextIdx in frameFiles.indices) {
                val nextFile = frameFiles[nextIdx]
                if (ReplayBitmapCache.get(nextFile.absolutePath) == null) {
                    try {
                        val bmp = BitmapFactory.decodeFile(nextFile.absolutePath)
                        if (bmp != null) ReplayBitmapCache.put(nextFile.absolutePath, bmp)
                    } catch (_: Throwable) {}
                }
            }
        }
    }

    Box(Modifier.fillMaxSize().background(colors.bg)) {
        Column(Modifier.fillMaxSize()) {
            // ---- Top bar ----
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.panel)
                    .statusBarsPadding()
                    .height(52.dp)
                    .padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ReIconButton(
                    icon = R.drawable.ic_arrow_left,
                    desc = stringResource(R.string.back),
                    onTap = { vm.exitReplay() },
                    tint = colors.text,
                )
                Text(
                    text = if (!project?.name.isNullOrBlank()) {
                        stringResource(R.string.replay_title_with_doc, project?.name ?: "")
                    } else {
                        stringResource(R.string.replay_title)
                    },
                    color = colors.text,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(8.dp))
                Row(
                    modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    presetSpeeds.forEach { sp ->
                        val selected = speed == sp
                        Text(
                            text = if (sp >= 1f) "${sp.toInt()}x" else "0.5x",
                            color = if (selected) colors.onAccent else colors.text,
                            fontSize = 12.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (selected) colors.accent else colors.panelHi)
                                .clickable { speed = sp }
                                .padding(horizontal = 8.dp, vertical = 5.dp),
                        )
                    }
                }
                Spacer(Modifier.width(6.dp))
                if (frameFiles.isNotEmpty()) {
                    ReIconButton(
                        icon = R.drawable.ic_export,
                        desc = stringResource(R.string.replay_export_btn),
                        onTap = {
                            isPlaying = false
                            showExportDialog = true
                        },
                        tint = colors.text,
                    )
                }
                Spacer(Modifier.width(4.dp))
            }

            // ---- Canvas view area ----
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(colors.canvasBg)
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onDoubleTap = {
                                scale = 1f
                                offset = Offset.Zero
                            },
                        )
                    }
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(0.5f, 5.0f)
                            offset += pan
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                val bmp = currentBitmap
                if (bmp != null && !bmp.isRecycled) {
                    val ib = remember(bmp) { bmp.asImageBitmap() }
                    Image(
                        bitmap = ib,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(10.dp)
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                                translationX = offset.x
                                translationY = offset.y
                            },
                    )
                } else if (frameFiles.isEmpty()) {
                    Text(
                        text = stringResource(R.string.toast_project_no_replay_data),
                        color = colors.subText,
                        fontSize = 14.sp,
                    )
                } else {
                    Text(
                        text = stringResource(R.string.replay_preparing_canvas),
                        color = colors.subText,
                        fontSize = 14.sp,
                    )
                }

                if (isPlaying) {
                    Row(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 14.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .background(Color.Black.copy(alpha = 0.55f))
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFFF6B6B)),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.replay_playing_status),
                            color = Color.White,
                            fontSize = 12.sp,
                        )
                    }
                }
            }

            // ---- Player controls ----
            if (frameFiles.isNotEmpty()) {
                val totalFrames = frameFiles.size
                val currentProgress = if (dragProgress >= 0f) {
                    dragProgress
                } else if (totalFrames > 1) {
                    currentFrameIndex.toFloat() / (totalFrames - 1)
                } else {
                    0f
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(colors.panel)
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ReFab(
                            icon = if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play,
                            desc = if (isPlaying) stringResource(R.string.pause) else stringResource(R.string.play),
                            onTap = {
                                if (isPlaying) {
                                    isPlaying = false
                                } else {
                                    if (currentFrameIndex >= totalFrames - 1) {
                                        currentFrameIndex = 0
                                    }
                                    isPlaying = true
                                }
                            },
                            sizeDp = 54.dp,
                        )
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            ReSlider(
                                value = currentProgress.coerceIn(0f, 1f),
                                onValue = { v ->
                                    isDragging = true
                                    dragProgress = v
                                    currentFrameIndex = ((totalFrames - 1) * v).toInt().coerceIn(0, totalFrames - 1)
                                },
                                onRelease = {
                                    isDragging = false
                                    dragProgress = -1f
                                },
                                height = 20,
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                val currentSec = (currentFrameIndex / 12f).toDouble()
                                val totalSec = (totalFrames / 12f).toDouble()
                                Text(
                                    text = String.format(Locale.US, "%02d:%02d / %02d:%02d",
                                        (currentSec / 60).toInt(), (currentSec % 60).toInt(),
                                        (totalSec / 60).toInt(), (totalSec % 60).toInt(),
                                    ),
                                    color = colors.subText,
                                    fontSize = 12.sp,
                                )
                                Text(
                                    text = "${currentFrameIndex + 1} / $totalFrames",
                                    color = colors.subText,
                                    fontSize = 12.sp,
                                )
                            }
                        }
                        Spacer(Modifier.width(10.dp))
                        ReIconButton(
                            icon = R.drawable.ic_refresh,
                            desc = stringResource(R.string.replay_restart),
                            onTap = {
                                currentFrameIndex = 0
                                isPlaying = true
                            },
                            tint = colors.text,
                        )
                    }
                }
            }
        }
    }

    if (showExportDialog) {
        TimelapseDialog(
            vm = vm,
            project = project,
            onDismiss = { showExportDialog = false },
        )
    }
}
