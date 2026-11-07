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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.core.content.FileProvider
import com.reverie.paint.R
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.core.TimelapseExportOptions
import com.reverie.paint.core.exitReplay
import com.reverie.paint.core.exportTimelapseToGallery
import com.reverie.paint.core.exportTimelapseToUri
import com.reverie.paint.core.getTimelapseFrameFiles
import com.reverie.paint.core.shareTimelapse
import com.reverie.paint.model.Project
import com.reverie.paint.ui.components.ReIconButton
import com.reverie.paint.ui.components.ReSlider
import com.reverie.paint.ui.components.ReSwitch
import com.reverie.paint.ui.components.ReTextButton
import com.reverie.paint.ui.theme.Theme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

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
 * 延时摄影独立全屏回放页。
 * 基于周期捕获的 frame_%05d.webp 序列帧进行全屏播放、沉浸控制与高级导出。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReplayPage(vm: PaintViewModel) {
    val context = LocalContext.current
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

    // 沉浸模式控制条显隐状态 (单击画面切换)
    var showControls by remember { mutableStateOf(true) }
    // 高级导出抽屉显隐状态
    var showExportSheet by remember { mutableStateOf(false) }

    // 手势缩放与平移状态
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    val presetSpeeds = remember { listOf(0.5f, 1f, 2f, 4f, 8f) }

    BackHandler {
        if (showExportSheet) {
            showExportSheet = false
        } else {
            vm.exitReplay()
        }
    }

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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.bg)
    ) {
        // ---- 全屏画布视图 (支持双指缩放、平移、双击重置、单击切换控制条) ----
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
        }

        // ---- 沉浸模式下播放状态角标 (控制条隐藏但仍在播放时提示) ----
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

        // ---- 顶部悬浮磨砂胶囊栏 ----
        AnimatedVisibility(
            visible = showControls,
            enter = fadeIn() + slideInVertically { -it },
            exit = fadeOut() + slideOutVertically { -it },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 10.dp, start = 16.dp, end = 16.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = colors.panel.copy(alpha = 0.94f),
                tonalElevation = 6.dp,
                shadowElevation = 8.dp,
                border = BorderStroke(1.dp, colors.panelHi.copy(alpha = 0.6f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 760.dp)
                    .height(52.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ReIconButton(
                        icon = R.drawable.ic_arrow_left,
                        desc = stringResource(R.string.back),
                        onTap = { vm.exitReplay() },
                        tint = colors.text,
                    )
                    Spacer(Modifier.width(4.dp))
                    val docName = project?.name?.ifBlank { vm.docName } ?: vm.docName
                    Text(
                        text = if (docName.isNotBlank()) {
                            stringResource(R.string.replay_title_with_doc, docName)
                        } else {
                            stringResource(R.string.replay_title)
                        },
                        color = colors.text,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(8.dp))
                    // 倍速切换胶囊
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        presetSpeeds.forEach { sp ->
                            val isSelected = speed == sp
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isSelected) colors.accent else colors.panelHi.copy(alpha = 0.6f))
                                    .clickable { speed = sp }
                                    .padding(horizontal = 8.dp, vertical = 5.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = if (sp >= 1f) "${sp.toInt()}x" else "0.5x",
                                    color = if (isSelected) colors.onAccent else colors.text,
                                    fontSize = 11.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.width(6.dp))
                    if (frameFiles.isNotEmpty()) {
                        ReIconButton(
                            icon = R.drawable.ic_export,
                            desc = stringResource(R.string.replay_export_btn),
                            onTap = {
                                isPlaying = false
                                showExportSheet = true
                            },
                            tint = colors.accent,
                        )
                    }
                }
            }
        }

        // ---- 底部悬浮磨砂胶囊控制条 ----
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
                shape = RoundedCornerShape(28.dp),
                color = colors.panel.copy(alpha = 0.94f),
                tonalElevation = 6.dp,
                shadowElevation = 8.dp,
                border = BorderStroke(1.dp, colors.panelHi.copy(alpha = 0.6f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 760.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 播放 / 暂停切换按钮
                    Box(
                        modifier = Modifier
                            .size(46.dp)
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
                            modifier = Modifier.size(22.dp),
                        )
                    }

                    Spacer(Modifier.width(14.dp))

                    // 时间轴拖拽与指示
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
                                fontSize = 11.sp,
                            )
                            Text(
                                text = "${currentFrameIndex + 1} / $totalFrames",
                                color = colors.subText,
                                fontSize = 11.sp,
                            )
                        }
                    }

                    Spacer(Modifier.width(10.dp))

                    // 从头播放按钮
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

        // ---- 高级导出抽屉 ----
        if (showExportSheet) {
            TimelapseExportSheet(
                vm = vm,
                project = project,
                frameFiles = frameFiles,
                onDismiss = { showExportSheet = false },
            )
        }
    }
}

/**
 * 延时摄影高级导出抽屉。
 * 包含格式 (MP4/GIF/ZIP)、时长 (15s/30s/60s/原速)、区间裁剪 (RangeSlider)、
 * 分辨率缩放 (100%/50%/25%)、结尾定格、作者水印，以及相册保存、SAF 文件存储与系统分享。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimelapseExportSheet(
    vm: PaintViewModel,
    project: Project?,
    frameFiles: List<File>,
    onDismiss: () -> Unit,
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
                        fontSize = 18.sp,
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
                            text = "${frameFiles.size} 帧",
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
                        contentDescription = "Close",
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
                            text = if (exportTotalFrames > 0) "$exportCurrentFrame / $exportTotalFrames 帧" else "",
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
                // ---- 导出高级选项配置表单 ----
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    // 1. 导出格式选择
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                                        .background(if (isSel) colors.accent else colors.panelHi)
                                        .clickable { exportFormat = fmt }
                                        .padding(vertical = 10.dp),
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
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                                            .background(if (isSel) colors.accent else colors.panelHi)
                                            .clickable { selectedDurationSec = sec }
                                            .padding(vertical = 10.dp),
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
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
                                    "$selCount 帧 · ${String.format(Locale.US, "%.1fs", selCount / 12f)}"
                                } else {
                                    "$selCount 帧 (缩时至 ${selectedDurationSec}s)"
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
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                                        .background(if (isSel) colors.accent else colors.panelHi)
                                        .clickable { selectedScale = scale }
                                        .padding(vertical = 10.dp),
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
                            .padding(top = 4.dp, bottom = 12.dp),
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
                }
            }
        }
    }
}
