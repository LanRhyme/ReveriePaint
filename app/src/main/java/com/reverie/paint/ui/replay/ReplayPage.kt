/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.replay

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import android.os.SystemClock
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.window.Dialog
import androidx.core.content.FileProvider
import com.reverie.paint.R
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.core.TimelapseExportOptions
import com.reverie.paint.core.clearTimelapseData
import com.reverie.paint.core.compactTimelapseData
import com.reverie.paint.core.exitReplay
import com.reverie.paint.core.exportTimelapseToGallery
import com.reverie.paint.core.exportTimelapseToUri
import com.reverie.paint.core.formatByteSize
import com.reverie.paint.core.getTimelapseFrameFiles
import com.reverie.paint.core.getTimelapseTotalBytes
import com.reverie.paint.core.shareTimelapse
import com.reverie.paint.model.Project
import com.reverie.paint.ui.components.ReDropdownMenu
import com.reverie.paint.ui.components.ReDropdownMenuItem
import com.reverie.paint.ui.components.ReIconButton
import com.reverie.paint.ui.components.ReSlider
import com.reverie.paint.ui.components.ReSwitch
import com.reverie.paint.ui.components.ReTextButton
import com.reverie.paint.ui.theme.Theme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

/** 内存解码帧缓存，加速拖动条拖拽平滑度与回放连贯性 */
private object ReplayBitmapCache {
    private val cache = object : LruCache<String, Bitmap>(48) {
        override fun entryRemoved(evicted: Boolean, key: String?, oldValue: Bitmap?, newValue: Bitmap?) {
            // 不主动 recycle，由 GC 统一管理，避免异步加载并发竞争
        }
    }

    fun get(path: String): Bitmap? = cache.get(path)
    fun put(path: String, bitmap: Bitmap) {
        cache.put(path, bitmap)
    }
    fun clear() {
        cache.evictAll()
    }
}

/** 携带指定 MIME 类型的文档创建合约 */
private class CreateDocumentWithMime : ActivityResultContract<Pair<String, String>, Uri?>() {
    override fun createIntent(context: Context, input: Pair<String, String>): Intent {
        val (mime, fileName) = input
        return Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(mime)
            .putExtra(Intent.EXTRA_TITLE, fileName)
    }

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? {
        return intent.takeIf { resultCode == Activity.RESULT_OK }?.data
    }
}

/**
 * 延时摄影独立全屏回放页 (Procreate 极简浮岛风格)。
 * 基于周期捕获的 frame_%05d.webp 序列帧进行全屏播放、沉浸控制、手动清理与高级导出。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReplayPage(vm: PaintViewModel) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val colors = Theme.current
    val project = vm.replayProject
    var refreshTrigger by remember { mutableIntStateOf(0) }
    val frameFiles = remember(project, vm.currentProjectFile, refreshTrigger) {
        vm.getTimelapseFrameFiles(project)
    }
    val totalBytesSnapshot = remember(frameFiles) {
        vm.getTimelapseTotalBytes(project)
    }

    var currentFrameIndex by remember { mutableIntStateOf(0) }
    var currentBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isPlaying by remember { mutableStateOf(frameFiles.isNotEmpty()) }
    var speed by remember { mutableFloatStateOf(1f) }
    var isDragging by remember { mutableStateOf(false) }
    var dragProgress by remember { mutableFloatStateOf(-1f) }

    var isLooping by remember { mutableStateOf(false) }
    var scrubAccumulator by remember { mutableFloatStateOf(0f) }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    // 沉浸模式控制条显隐状态 (单击画面平滑切换)
    var showControls by remember { mutableStateOf(true) }
    // 高级导出抽屉显隐状态
    var showExportSheet by remember { mutableStateOf(false) }
    // 清理延时摄影确认弹窗状态
    var showClearConfirmDialog by remember { mutableStateOf(false) }
    // 延时摄影瘦身抽稀确认弹窗状态
    var showCompactConfirmDialog by remember { mutableStateOf(false) }
    var isCompacting by remember { mutableStateOf(false) }
    // 折叠式倍速选择菜单展开状态
    var showSpeedDropdown by remember { mutableStateOf(false) }
    // 自定义倍速弹窗状态
    var showCustomSpeedDialog by remember { mutableStateOf(false) }

    // 手势缩放与平移状态
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    val presetSpeeds = remember { listOf(0.5f, 1f, 2f, 4f, 8f, 16f, 32f, 64f, 128f) }

    BackHandler {
        when {
            showCustomSpeedDialog -> showCustomSpeedDialog = false
            showClearConfirmDialog -> showClearConfirmDialog = false
            showCompactConfirmDialog -> if (!isCompacting) showCompactConfirmDialog = false
            showExportSheet -> showExportSheet = false
            else -> vm.exitReplay()
        }
    }

    // 播放循环调度：采用基于真实实测时间的帧累加器，确保在 32x、64x、128x 等高倍速下依然精准推进，突破 60fps 刷新限制
    LaunchedEffect(isPlaying, speed, isDragging, frameFiles.size, isLooping) {
        if (!isPlaying || isDragging || frameFiles.isEmpty()) return@LaunchedEffect
        var lastUptime = SystemClock.uptimeMillis()
        var frameAccumulator = 0f
        while (isActive && isPlaying && !isDragging) {
            delay(16L) // ~60 FPS 刷新轮询
            val now = SystemClock.uptimeMillis()
            val dtSec = (now - lastUptime).coerceAtLeast(0L) / 1000f
            lastUptime = now
            val framesPerSec = 12f * speed
            frameAccumulator += framesPerSec * dtSec
            val advance = frameAccumulator.toInt()
            if (advance > 0) {
                frameAccumulator -= advance
                val nextIndex = currentFrameIndex + advance
                if (nextIndex >= frameFiles.size - 1) {
                    if (isLooping) {
                        currentFrameIndex = 0
                    } else {
                        currentFrameIndex = frameFiles.size - 1
                        isPlaying = false
                    }
                } else {
                    currentFrameIndex = nextIndex
                }
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

        // 预加载临近帧 (根据播放倍速自适应步长预取)
        withContext(Dispatchers.IO) {
            val step = if (speed >= 16f) (speed / 4f).roundToInt().coerceAtLeast(1) else 1
            for (offset in 1..4) {
                val nextIdx = currentFrameIndex + offset * step
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
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.bg)
            .focusRequester(focusRequester)
            .focusable()
            .onKeyEvent { keyEvent ->
                if (keyEvent.type == KeyEventType.KeyDown) {
                    when (keyEvent.key) {
                        Key.Spacebar -> {
                            if (frameFiles.isNotEmpty()) {
                                if (isPlaying) {
                                    isPlaying = false
                                } else {
                                    if (currentFrameIndex >= frameFiles.size - 1) {
                                        currentFrameIndex = 0
                                    }
                                    isPlaying = true
                                }
                            }
                            true
                        }
                        Key.DirectionLeft -> {
                            isPlaying = false
                            currentFrameIndex = (currentFrameIndex - 1).coerceAtLeast(0)
                            true
                        }
                        Key.DirectionRight -> {
                            isPlaying = false
                            currentFrameIndex = (currentFrameIndex + 1).coerceAtMost(frameFiles.size - 1)
                            true
                        }
                        Key.MoveHome, Key.PageUp -> {
                            isPlaying = false
                            currentFrameIndex = 0
                            true
                        }
                        Key.MoveEnd, Key.PageDown -> {
                            isPlaying = false
                            currentFrameIndex = (frameFiles.size - 1).coerceAtLeast(0)
                            true
                        }
                        Key.L -> {
                            isLooping = !isLooping
                            true
                        }
                        else -> false
                    }
                } else {
                    false
                }
            }
    ) {
        // ---- 全屏画布视图 (支持双指缩放、平移、双击重置、单击切换控制条、单指横向洗牌) ----
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.canvasBg)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = {
                            showControls = !showControls
                        },
                        onDoubleTap = {
                            scale = 1f
                            offset = Offset.Zero
                        },
                    )
                }
                .pointerInput(frameFiles.size) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        val newScale = (scale * zoom).coerceIn(0.5f, 5.0f)
                        if (newScale > 1.05f || zoom != 1f) {
                            scale = newScale
                            offset += pan
                        } else {
                            scale = 1f
                            offset = Offset.Zero
                            if (frameFiles.size > 1 && kotlin.math.abs(pan.x) > 0f) {
                                isPlaying = false
                                scrubAccumulator += (pan.x / 600f) * (frameFiles.size - 1)
                                val advance = scrubAccumulator.toInt()
                                if (advance != 0) {
                                    scrubAccumulator -= advance
                                    currentFrameIndex = (currentFrameIndex + advance).coerceIn(0, frameFiles.size - 1)
                                }
                            }
                        }
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
        }

        // ---- 沉浸模式下播放状态角标 (控制条隐藏且正在播放时提示) ----
        AnimatedVisibility(
            visible = isPlaying && !showControls,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 16.dp),
        ) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
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

        // ---- 顶部全宽统一样式导航栏 ----
        AnimatedVisibility(
            visible = showControls,
            enter = fadeIn() + slideInVertically { -it },
            exit = fadeOut() + slideOutVertically { -it },
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Surface(
                color = colors.panel.copy(alpha = 0.95f),
                tonalElevation = 4.dp,
                shadowElevation = 8.dp,
                border = BorderStroke(1.dp, colors.panelHi.copy(alpha = 0.6f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .height(54.dp)
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 返回按钮
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(colors.panelHi)
                            .clickable { vm.exitReplay() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_left),
                            contentDescription = stringResource(R.string.back),
                            tint = colors.text,
                            modifier = Modifier.size(18.dp),
                        )
                    }

                    Spacer(Modifier.width(14.dp))

                    // 作品标题与延时摄影元信息
                    val docName = project?.name?.ifBlank { vm.docName } ?: vm.docName
                    Column(
                        modifier = Modifier.weight(1f, fill = false),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = docName.ifBlank { stringResource(R.string.replay_title) },
                                color = colors.text,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.width(8.dp))
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(colors.accent.copy(alpha = 0.15f))
                                    .padding(horizontal = 8.dp, vertical = 2.dp),
                            ) {
                                Text(
                                    text = stringResource(R.string.replay_title),
                                    color = colors.accent,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                        if (frameFiles.isNotEmpty()) {
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = stringResource(R.string.timelapse_clear_info, frameFiles.size, formatByteSize(totalBytesSnapshot)),
                                color = colors.subText,
                                fontSize = 12.sp,
                            )
                        }
                    }

                    Spacer(Modifier.weight(1f))

                    // 右侧动作区：瘦身 + 清理 + 导出
                    if (frameFiles.isNotEmpty()) {
                        // 延时摄影瘦身按钮 (仅在帧数 >= 10 时显示，避免过少帧数无意义抽稀)
                        if (frameFiles.size >= 10) {
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(colors.panelHi.copy(alpha = 0.6f))
                                    .clickable { showCompactConfirmDialog = true }
                                    .padding(horizontal = 12.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_cut),
                                    contentDescription = stringResource(R.string.timelapse_compact_btn),
                                    tint = colors.subText,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = stringResource(R.string.timelapse_compact_btn),
                                    color = colors.text,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                            }

                            Spacer(Modifier.width(10.dp))
                        }

                        // 清理延时摄影按钮
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(colors.panelHi.copy(alpha = 0.6f))
                                .clickable { showClearConfirmDialog = true }
                                .padding(horizontal = 12.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_trash),
                                contentDescription = stringResource(R.string.timelapse_clear_btn),
                                tint = colors.subText,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = stringResource(R.string.timelapse_clear_btn),
                                color = colors.text,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                            )
                        }

                        Spacer(Modifier.width(10.dp))

                        // 导出按钮 (主题色高亮按钮)
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(colors.accent)
                                .clickable {
                                    isPlaying = false
                                    showExportSheet = true
                                }
                                .padding(horizontal = 14.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_export),
                                contentDescription = stringResource(R.string.replay_export_btn),
                                tint = colors.onAccent,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = stringResource(R.string.replay_export_btn),
                                color = colors.onAccent,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }
        }

        // ---- 底部浮动控制条 ----
        AnimatedVisibility(
            visible = showControls && frameFiles.isNotEmpty(),
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 16.dp, start = 16.dp, end = 16.dp),
        ) {
            val totalFrames = frameFiles.size
            val currentProgress = if (dragProgress >= 0f) {
                dragProgress
            } else if (totalFrames > 1) {
                currentFrameIndex.toFloat() / (totalFrames - 1)
            } else {
                0f
            }

            Surface(
                shape = RoundedCornerShape(22.dp),
                color = colors.panel.copy(alpha = 0.95f),
                tonalElevation = 6.dp,
                shadowElevation = 10.dp,
                border = BorderStroke(1.dp, colors.panelHi.copy(alpha = 0.6f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 840.dp)
                    .height(56.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 播放 / 暂停圆形按钮
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(colors.accent)
                            .clickable {
                                if (isPlaying) {
                                    isPlaying = false
                                } else {
                                    if (currentFrameIndex >= totalFrames - 1) {
                                        currentFrameIndex = 0
                                    }
                                    isPlaying = true
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painter = painterResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play),
                            contentDescription = if (isPlaying) stringResource(R.string.pause) else stringResource(R.string.play),
                            tint = colors.onAccent,
                            modifier = Modifier.size(18.dp),
                        )
                    }

                    Spacer(Modifier.width(8.dp))

                    // 后退一帧 (长按跳至开头)
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .pointerInput(totalFrames, currentFrameIndex) {
                                detectTapGestures(
                                    onTap = {
                                        isPlaying = false
                                        currentFrameIndex = (currentFrameIndex - 1).coerceAtLeast(0)
                                    },
                                    onLongPress = {
                                        isPlaying = false
                                        currentFrameIndex = 0
                                    },
                                )
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_chevron),
                            contentDescription = stringResource(R.string.replay_step_back),
                            tint = if (currentFrameIndex > 0) colors.text else colors.subText.copy(alpha = 0.4f),
                            modifier = Modifier
                                .size(16.dp)
                                .graphicsLayer { rotationZ = 180f },
                        )
                    }

                    // 前进一帧 (长按跳至结尾)
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .pointerInput(totalFrames, currentFrameIndex) {
                                detectTapGestures(
                                    onTap = {
                                        isPlaying = false
                                        currentFrameIndex = (currentFrameIndex + 1).coerceAtMost(totalFrames - 1)
                                    },
                                    onLongPress = {
                                        isPlaying = false
                                        currentFrameIndex = totalFrames - 1
                                    },
                                )
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_chevron),
                            contentDescription = stringResource(R.string.replay_step_forward),
                            tint = if (currentFrameIndex < totalFrames - 1) colors.text else colors.subText.copy(alpha = 0.4f),
                            modifier = Modifier.size(16.dp),
                        )
                    }

                    Spacer(Modifier.width(4.dp))

                    // 从头播放
                    ReIconButton(
                        icon = R.drawable.ic_refresh,
                        desc = stringResource(R.string.replay_restart),
                        onTap = {
                            currentFrameIndex = 0
                            isPlaying = true
                        },
                        tint = colors.text,
                    )

                    Spacer(Modifier.width(2.dp))

                    // 循环播放开关
                    ReIconButton(
                        icon = if (isLooping) R.drawable.ic_repeat_loop else R.drawable.ic_repeat_none,
                        desc = stringResource(R.string.replay_loop),
                        onTap = { isLooping = !isLooping },
                        tint = if (isLooping) colors.accent else colors.subText,
                    )

                    Spacer(Modifier.width(8.dp))

                    // 进度时间指示
                    val currentSec = (currentFrameIndex / 12f).toDouble()
                    val totalSec = (totalFrames / 12f).toDouble()
                    Text(
                        text = String.format(Locale.US, "%02d:%02d / %02d:%02d",
                            (currentSec / 60).toInt(), (currentSec % 60).toInt(),
                            (totalSec / 60).toInt(), (totalSec % 60).toInt(),
                        ),
                        color = colors.subText,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                    )

                    Spacer(Modifier.width(12.dp))

                    // 流体细线时间轴滑块
                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.Center,
                    ) {
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
                    }

                    Spacer(Modifier.width(12.dp))

                    // 倍速切换小胶囊 (统一 ReDropdownMenu 风格)
                    Box {
                        val currentSpeedLabel = if (speed % 1f == 0f) "${speed.toInt()}x" else "${String.format(Locale.US, "%.1f", speed)}x"
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(colors.panelHi.copy(alpha = 0.8f))
                                .clickable { showSpeedDropdown = true }
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = currentSpeedLabel,
                                color = colors.text,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Icon(
                                painter = painterResource(R.drawable.ic_chevron_down),
                                contentDescription = null,
                                tint = colors.subText,
                                modifier = Modifier.size(12.dp),
                            )
                        }

                        ReDropdownMenu(
                            expanded = showSpeedDropdown,
                            onDismissRequest = { showSpeedDropdown = false },
                        ) {
                            presetSpeeds.forEach { sp ->
                                val isSel = (speed == sp)
                                val label = if (sp % 1f == 0f) "${sp.toInt()}x" else "${sp}x"
                                ReDropdownMenuItem(
                                    text = label,
                                    selected = isSel,
                                    onClick = {
                                        speed = sp
                                        showSpeedDropdown = false
                                    },
                                )
                            }
                            HorizontalDivider(
                                color = colors.subText.copy(alpha = 0.15f),
                                modifier = Modifier.padding(vertical = 4.dp, horizontal = 8.dp),
                            )
                            val isCustom = (speed !in presetSpeeds)
                            val customLabel = if (isCustom) {
                                stringResource(R.string.replay_speed_custom_active, currentSpeedLabel)
                            } else {
                                stringResource(R.string.replay_speed_custom)
                            }
                            ReDropdownMenuItem(
                                text = customLabel,
                                selected = isCustom,
                                onClick = {
                                    showSpeedDropdown = false
                                    showCustomSpeedDialog = true
                                },
                            )
                        }
                    }
                }
            }
        }

        // ---- 自定义播放倍速弹窗 ----
        if (showCustomSpeedDialog) {
            CustomSpeedDialog(
                initialSpeed = speed,
                onDismiss = { showCustomSpeedDialog = false },
                onConfirm = { newSpeed ->
                    speed = newSpeed
                    showCustomSpeedDialog = false
                },
            )
        }

        // ---- 清理延时摄影二次确认弹窗 ----
        if (showClearConfirmDialog) {
            val totalBytes = remember(frameFiles) { vm.getTimelapseTotalBytes(project) }
            Dialog(onDismissRequest = { showClearConfirmDialog = false }) {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = colors.panel,
                    tonalElevation = 6.dp,
                    modifier = Modifier
                        .fillMaxWidth(0.9f)
                        .widthIn(max = 420.dp),
                ) {
                    Column(
                        modifier = Modifier.padding(22.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.timelapse_clear_title),
                            color = colors.text,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = stringResource(R.string.timelapse_clear_desc),
                            color = colors.subText,
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(colors.panelHi.copy(alpha = 0.5f))
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.timelapse_clear_info, frameFiles.size, formatByteSize(totalBytes)),
                                color = colors.accent,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            ReTextButton(
                                text = stringResource(R.string.cancel),
                                onClick = { showClearConfirmDialog = false },
                                textColor = colors.text,
                            )
                            Button(
                                onClick = {
                                    showClearConfirmDialog = false
                                    vm.clearTimelapseData(project)
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.timelapse_cleared_toast),
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                    vm.exitReplay()
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = colors.error,
                                    contentColor = Color.White,
                                ),
                                shape = RoundedCornerShape(10.dp),
                            ) {
                                Text(
                                    text = stringResource(R.string.timelapse_clear_confirm),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                    }
                }
            }
        }

        // ---- 延时摄影瘦身抽稀二次确认弹窗 ----
        if (showCompactConfirmDialog) {
            Dialog(onDismissRequest = { if (!isCompacting) showCompactConfirmDialog = false }) {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = colors.panel,
                    tonalElevation = 6.dp,
                    modifier = Modifier
                        .fillMaxWidth(0.9f)
                        .widthIn(max = 420.dp),
                ) {
                    Column(
                        modifier = Modifier.padding(22.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.timelapse_compact_title),
                            color = colors.text,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = stringResource(R.string.timelapse_compact_desc),
                            color = colors.subText,
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(colors.panelHi.copy(alpha = 0.5f))
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            val estimatedNewCount = (frameFiles.size + 1) / 2
                            val estimatedFreed = totalBytesSnapshot / 2
                            Text(
                                text = stringResource(
                                    R.string.timelapse_compact_info,
                                    frameFiles.size,
                                    estimatedNewCount,
                                    formatByteSize(estimatedFreed),
                                ),
                                color = colors.accent,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            ReTextButton(
                                text = stringResource(R.string.cancel),
                                onClick = { if (!isCompacting) showCompactConfirmDialog = false },
                                textColor = colors.text,
                            )
                            Button(
                                onClick = {
                                    if (isCompacting) return@Button
                                    isCompacting = true
                                    coroutineScope.launch {
                                        withContext(Dispatchers.IO) {
                                            val (newCount, freedBytes) = vm.compactTimelapseData(project)
                                            ReplayBitmapCache.clear()
                                            withContext(Dispatchers.Main) {
                                                isCompacting = false
                                                showCompactConfirmDialog = false
                                                refreshTrigger++
                                                currentFrameIndex = (currentFrameIndex / 2).coerceIn(0, (newCount - 1).coerceAtLeast(0))
                                                android.widget.Toast.makeText(
                                                    context,
                                                    context.getString(
                                                        R.string.timelapse_compact_success,
                                                        formatByteSize(freedBytes),
                                                    ),
                                                    android.widget.Toast.LENGTH_SHORT,
                                                ).show()
                                            }
                                        }
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = colors.accent,
                                    contentColor = Color.White,
                                ),
                                shape = RoundedCornerShape(10.dp),
                            ) {
                                if (isCompacting) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                        color = Color.White,
                                    )
                                } else {
                                    Text(
                                        text = stringResource(R.string.timelapse_compact_confirm),
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

        // ---- 高级导出抽屉 ----
        if (showExportSheet) {
            TimelapseExportSheet(
                vm = vm,
                project = project,
                frameFiles = frameFiles,
                onDismiss = { showExportSheet = false },
                onTriggerClear = {
                    showExportSheet = false
                    showClearConfirmDialog = true
                },
            )
        }
    }
}

/**
 * 延时摄影高级导出抽屉 (精致莫兰迪分段卡片风格)。
 * 包含格式 (MP4/GIF/ZIP)、时长 (15s/30s/60s/原速)、区间裁剪 (RangeSlider)、
 * 分辨率缩放 (100%/50%/25%)、结尾定格、作者水印、相册直存 (DCIM)、SAF 文件存储、系统分享与清理缓存。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimelapseExportSheet(
    vm: PaintViewModel,
    project: Project?,
    frameFiles: List<File>,
    onDismiss: () -> Unit,
    onTriggerClear: () -> Unit,
) {
    val context = LocalContext.current
    val colors = Theme.current

    var exportFormat by remember { mutableStateOf("mp4") }
    var selectedDurationSec by remember { mutableIntStateOf(30) }
    var selectedScale by remember { mutableFloatStateOf(1.0f) }
    var rangeStart by remember { mutableFloatStateOf(0f) }
    var rangeEnd by remember { mutableFloatStateOf(1f) }
    var holdEndFrames by remember { mutableStateOf(true) }
    var showWatermark by remember { mutableStateOf(false) }

    // 原始分辨率探测
    var baseW by remember { mutableIntStateOf(1280) }
    var baseH by remember { mutableIntStateOf(720) }
    val firstFile = frameFiles.firstOrNull()

    LaunchedEffect(firstFile) {
        if (firstFile != null && firstFile.exists()) {
            withContext(Dispatchers.IO) {
                try {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(firstFile.absolutePath, bounds)
                    if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                        withContext(Dispatchers.Main) {
                            baseW = bounds.outWidth
                            baseH = bounds.outHeight
                        }
                    }
                } catch (_: Throwable) {}
            }
        }
    }

    // 导出中状态
    var isExporting by remember { mutableStateOf(false) }
    var exportProgress by remember { mutableFloatStateOf(0f) }
    var exportCurrentFrame by remember { mutableIntStateOf(0) }
    var exportTotalFrames by remember { mutableIntStateOf(0) }
    var exportStage by remember { mutableStateOf("") }
    var exportCancelled by remember { mutableStateOf(false) }

    val docName = project?.name?.ifBlank { vm.docName } ?: vm.docName
    val safeDocName = docName.ifBlank { "artwork" }

    val buildExportOptions = {
        TimelapseExportOptions(
            format = exportFormat,
            durationSec = if (exportFormat == "zip") 0 else selectedDurationSec,
            fps = 12,
            scale = selectedScale,
            startFraction = rangeStart,
            endFraction = rangeEnd,
            showWatermark = showWatermark,
            holdEndFrames = if (exportFormat == "zip") false else holdEndFrames,
        )
    }

    val createDocLauncher = rememberLauncherForActivityResult(
        contract = remember { CreateDocumentWithMime() },
    ) { uri: Uri? ->
        if (uri != null) {
            isExporting = true
            exportProgress = 0f
            exportCancelled = false
            exportStage = ""
            vm.exportTimelapseToUri(
                project = project,
                options = buildExportOptions(),
                targetUri = uri,
                isCancelled = { exportCancelled },
                onProgress = { cur, tot, stage ->
                    exportCurrentFrame = cur
                    exportTotalFrames = tot
                    exportStage = stage
                    if (tot > 0) exportProgress = cur.toFloat() / tot.toFloat()
                },
                onSuccess = {
                    isExporting = false
                    Toast.makeText(
                        context,
                        context.getString(R.string.replay_export_toast_saved),
                        Toast.LENGTH_LONG,
                    ).show()
                    onDismiss()
                },
                onError = { err ->
                    isExporting = false
                    Toast.makeText(context, err, Toast.LENGTH_SHORT).show()
                },
            )
        }
    }

    ModalBottomSheet(
        onDismissRequest = {
            if (!isExporting) onDismiss()
        },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.panel,
        tonalElevation = 8.dp,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(top = 10.dp, bottom = 4.dp)
                    .size(width = 36.dp, height = 4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(colors.panelHi)
            )
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp)
        ) {
            // ---- 抽屉标题栏 ----
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.replay_export_title),
                        color = colors.text,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(colors.panelHi)
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.replay_export_frames_badge, frameFiles.size),
                            color = colors.subText,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(colors.panelHi.copy(alpha = 0.7f))
                        .clickable(enabled = !isExporting) { onDismiss() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.close),
                        tint = colors.icon,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            if (isExporting) {
                // ---- 原位导出进度界面 ----
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = exportStage.ifEmpty { stringResource(R.string.replay_export_progress_title) },
                        color = colors.text,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.height(14.dp))
                    LinearProgressIndicator(
                        progress = { exportProgress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp)),
                        color = colors.accent,
                        trackColor = colors.panelHi,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "${(exportProgress * 100).toInt()}%",
                            color = colors.accent,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = if (exportTotalFrames > 0) {
                                stringResource(R.string.replay_export_progress_frames, exportCurrentFrame, exportTotalFrames)
                            } else {
                                ""
                            },
                            color = colors.subText,
                            fontSize = 12.sp,
                        )
                    }
                    Spacer(Modifier.height(20.dp))
                    ReTextButton(
                        text = stringResource(R.string.cancel),
                        onClick = { exportCancelled = true },
                        textColor = colors.error,
                    )
                    Spacer(Modifier.height(12.dp))
                }
            } else {
                // ---- 导出高级选项配置表单 (莫兰迪卡片风格) ----
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    // 1. 导出格式选择
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = stringResource(R.string.replay_export_format_label),
                            color = colors.subText,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            listOf("mp4" to "MP4", "gif" to "GIF", "zip" to "ZIP").forEach { (fmt, label) ->
                                val isSel = exportFormat == fmt
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(if (isSel) colors.accent else colors.panelHi.copy(alpha = 0.6f))
                                        .clickable { exportFormat = fmt }
                                        .padding(vertical = 9.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = label,
                                        color = if (isSel) colors.onAccent else colors.text,
                                        fontSize = 13.sp,
                                        fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                                    )
                                }
                            }
                        }
                    }

                    // 2. 缩时时长 (ZIP 格式不适用)
                    if (exportFormat != "zip") {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = stringResource(R.string.replay_export_duration_label),
                                color = colors.subText,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                listOf(
                                    15 to "15s",
                                    30 to "30s",
                                    60 to "60s",
                                    0 to stringResource(R.string.replay_export_duration_original),
                                ).forEach { (sec, label) ->
                                    val isSel = selectedDurationSec == sec
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(if (isSel) colors.accent else colors.panelHi.copy(alpha = 0.6f))
                                            .clickable { selectedDurationSec = sec }
                                            .padding(vertical = 9.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            text = label,
                                            color = if (isSel) colors.onAccent else colors.text,
                                            fontSize = 13.sp,
                                            fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // 3. 导出区间裁剪 (RangeSlider)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        val totalFrames = frameFiles.size
                        val selStartIdx = (totalFrames * rangeStart).toInt().coerceIn(0, totalFrames - 1)
                        val selEndIdx = (totalFrames * rangeEnd).toInt().coerceIn(selStartIdx, totalFrames - 1)
                        val selCount = selEndIdx - selStartIdx + 1
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource(R.string.replay_export_range_label),
                                color = colors.subText,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                text = if (exportFormat == "zip" || selectedDurationSec == 0) {
                                    stringResource(
                                        R.string.replay_export_range_frames_origin,
                                        selCount,
                                        String.format(Locale.US, "%.1fs", selCount / 12f),
                                    )
                                } else {
                                    stringResource(
                                        R.string.replay_export_range_frames_timelapse,
                                        selCount,
                                        selectedDurationSec,
                                    )
                                },
                                color = colors.accent,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        RangeSlider(
                            value = rangeStart..rangeEnd,
                            onValueChange = { range ->
                                rangeStart = range.start
                                rangeEnd = range.endInclusive
                            },
                            valueRange = 0f..1f,
                            colors = SliderDefaults.colors(
                                thumbColor = colors.accent,
                                activeTrackColor = colors.accent,
                                inactiveTrackColor = colors.panelHi,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    // 4. 输出尺寸缩放
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        val outW = (baseW * selectedScale).roundToInt()
                        val outH = (baseH * selectedScale).roundToInt()
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource(R.string.replay_export_scale_label),
                                color = colors.subText,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                text = "$outW × $outH px",
                                color = colors.subText,
                                fontSize = 12.sp,
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            listOf(1.0f to "100%", 0.5f to "50%", 0.25f to "25%").forEach { (scale, label) ->
                                val isSel = selectedScale == scale
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(if (isSel) colors.accent else colors.panelHi.copy(alpha = 0.6f))
                                        .clickable { selectedScale = scale }
                                        .padding(vertical = 9.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = label,
                                        color = if (isSel) colors.onAccent else colors.text,
                                        fontSize = 13.sp,
                                        fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                                    )
                                }
                            }
                        }
                    }

                    // 5. 定格与水印开关
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (exportFormat != "zip") {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = stringResource(R.string.replay_export_hold_end),
                                    color = colors.text,
                                    fontSize = 13.sp,
                                )
                                ReSwitch(
                                    checked = holdEndFrames,
                                    onChecked = { holdEndFrames = it },
                                )
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource(R.string.replay_export_show_watermark),
                                color = colors.text,
                                fontSize = 13.sp,
                            )
                            ReSwitch(
                                checked = showWatermark,
                                onChecked = { showWatermark = it },
                            )
                        }
                    }

                    // 6. 操作按钮条
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (exportFormat == "zip") {
                            Button(
                                onClick = {
                                    createDocLauncher.launch("application/zip" to "${safeDocName}_timelapse.zip")
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(46.dp),
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
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = stringResource(R.string.replay_export_save_zip),
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 14.sp,
                                )
                            }
                        } else {
                            Button(
                                onClick = {
                                    isExporting = true
                                    exportProgress = 0f
                                    exportCancelled = false
                                    exportStage = ""
                                    vm.exportTimelapseToGallery(
                                        project = project,
                                        options = buildExportOptions(),
                                        isCancelled = { exportCancelled },
                                        onProgress = { cur, tot, stage ->
                                            exportCurrentFrame = cur
                                            exportTotalFrames = tot
                                            exportStage = stage
                                            if (tot > 0) exportProgress = cur.toFloat() / tot.toFloat()
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
                                    )
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(46.dp),
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
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = stringResource(R.string.replay_export_save_gallery),
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 14.sp,
                                )
                            }
                        }

                        // 系统分享按钮
                        OutlinedButton(
                            onClick = {
                                isExporting = true
                                exportProgress = 0f
                                exportCancelled = false
                                exportStage = ""
                                vm.shareTimelapse(
                                    project = project,
                                    options = buildExportOptions(),
                                    isCancelled = { exportCancelled },
                                    onProgress = { cur, tot, stage ->
                                        exportCurrentFrame = cur
                                        exportTotalFrames = tot
                                        exportStage = stage
                                        if (tot > 0) exportProgress = cur.toFloat() / tot.toFloat()
                                    },
                                    onSuccess = { shareFile ->
                                        isExporting = false
                                        onDismiss()
                                        try {
                                            val uri = FileProvider.getUriForFile(
                                                context,
                                                "${context.packageName}.fileprovider",
                                                shareFile,
                                            )
                                            val mimeType = when (exportFormat) {
                                                "gif" -> "image/gif"
                                                "zip" -> "application/zip"
                                                else -> "video/mp4"
                                            }
                                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                                type = mimeType
                                                putExtra(Intent.EXTRA_STREAM, uri)
                                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            }
                                            context.startActivity(Intent.createChooser(shareIntent, null))
                                        } catch (e: Exception) {
                                            Toast.makeText(context, e.message ?: "Share failed", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    onError = { err ->
                                        isExporting = false
                                        Toast.makeText(context, err, Toast.LENGTH_SHORT).show()
                                    },
                                )
                            },
                            modifier = Modifier
                                .weight(0.7f)
                                .height(46.dp),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, colors.panelHi),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = colors.text,
                            ),
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_share),
                                contentDescription = null,
                                tint = colors.text,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = stringResource(R.string.common_share),
                                fontWeight = FontWeight.Medium,
                                fontSize = 14.sp,
                            )
                        }
                    }

                    // 7. 抽屉底部清理延时摄影入口
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onTriggerClear() }
                            .padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_trash),
                            contentDescription = null,
                            tint = colors.error,
                            modifier = Modifier.size(15.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.timelapse_clear_btn),
                            color = colors.error,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 延时摄影自定义播放倍速弹窗 (优雅莫兰迪卡片风格)。
 * 支持 0.25x ~ 128x 自由调节，带步进微调按钮与常用预设胶囊。
 */
@Composable
private fun CustomSpeedDialog(
    initialSpeed: Float,
    onDismiss: () -> Unit,
    onConfirm: (Float) -> Unit,
) {
    val colors = Theme.current
    var currentVal by remember { mutableFloatStateOf(initialSpeed.coerceIn(0.25f, 128f)) }
    val quickPresets = remember { listOf(1.5f, 3f, 6f, 10f, 20f, 48f, 96f) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = colors.panel,
            tonalElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .widthIn(max = 400.dp),
        ) {
            Column(
                modifier = Modifier.padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.replay_custom_speed_title),
                        color = colors.text,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    // 倍速徽章展示
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(colors.accent.copy(alpha = 0.15f))
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    ) {
                        val badgeText = if (currentVal % 1f == 0f) "${currentVal.toInt()}x" else "${String.format(Locale.US, "%.1f", currentVal)}x"
                        Text(
                            text = badgeText,
                            color = colors.accent,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }

                Text(
                    text = stringResource(R.string.replay_speed_custom_desc),
                    color = colors.subText,
                    fontSize = 12.sp,
                )

                // 滑块与微调步进器
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    // 减速微调按钮
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(colors.panelHi)
                            .clickable {
                                val delta = when {
                                    currentVal > 20f -> 5f
                                    currentVal > 5f -> 1f
                                    else -> 0.5f
                                }
                                currentVal = (currentVal - delta).coerceAtLeast(0.25f)
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("-", color = colors.text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }

                    Box(modifier = Modifier.weight(1f)) {
                        // 非线性映射，前半段细致调节 0.25~16，后半段平滑过渡 16~128
                        val sliderFrac = if (currentVal <= 16f) {
                            ((currentVal - 0.25f) / (16f - 0.25f)) * 0.5f
                        } else {
                            0.5f + ((currentVal - 16f) / (128f - 16f)) * 0.5f
                        }
                        ReSlider(
                            value = sliderFrac.coerceIn(0f, 1f),
                            onValue = { f ->
                                val mapped = if (f <= 0.5f) {
                                    0.25f + (f / 0.5f) * (16f - 0.25f)
                                } else {
                                    16f + ((f - 0.5f) / 0.5f) * (128f - 16f)
                                }
                                currentVal = if (mapped < 10f) {
                                    (mapped * 2).roundToInt() / 2f
                                } else {
                                    mapped.roundToInt().toFloat()
                                }
                            },
                            height = 20,
                        )
                    }

                    // 加速微调按钮
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(colors.panelHi)
                            .clickable {
                                val delta = when {
                                    currentVal >= 20f -> 5f
                                    currentVal >= 5f -> 1f
                                    else -> 0.5f
                                }
                                currentVal = (currentVal + delta).coerceAtMost(128f)
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("+", color = colors.text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }
                }

                // 快捷倍速预设小胶囊
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    quickPresets.forEach { p ->
                        val isSel = (currentVal == p)
                        val pText = if (p % 1f == 0f) "${p.toInt()}x" else "${p}x"
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (isSel) colors.accent else colors.panelHi)
                                .clickable { currentVal = p }
                                .padding(vertical = 6.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = pText,
                                color = if (isSel) colors.onAccent else colors.text,
                                fontSize = 11.sp,
                                fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium,
                            )
                        }
                    }
                }

                // 底部操作区
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ReTextButton(
                        text = stringResource(R.string.cancel),
                        onClick = onDismiss,
                        textColor = colors.text,
                    )
                    Button(
                        onClick = { onConfirm(currentVal) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colors.accent,
                            contentColor = colors.onAccent,
                        ),
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.confirm),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
    }
}
