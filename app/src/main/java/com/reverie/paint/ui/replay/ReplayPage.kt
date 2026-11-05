/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.reverie.paint.ui.replay

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import com.reverie.paint.ui.components.ReSlider
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.reverie.paint.R
import com.reverie.paint.core.*
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.ui.components.ReFab
import com.reverie.paint.ui.components.ReIconButton
import com.reverie.paint.ui.components.ReSwitch
import com.reverie.paint.ui.components.ReTextButton
import com.reverie.paint.ui.theme.AppColors
import com.reverie.paint.ui.theme.Theme
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

private class CreateReplayDocWithMime : ActivityResultContract<Pair<String, String>, Uri?>() {
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
 * Replay page: plays back the recorded drawing process on a read-only
 * canvas. The engine (C++ document + render pipeline) is reused as-is; the
 * page only renders vm.displayBitmap and drives playback via the ViewModel.
 */
@Composable
fun ReplayPage(vm: PaintViewModel) {
    val colors = Theme.current
    var canvasW by remember { mutableStateOf(1) }
    var canvasH by remember { mutableStateOf(1) }
    val s = vm.replaySession
    // While the user drags the slider we show the thumb position; -1 means
    // "follow playback"
    var dragProgress by remember { mutableFloatStateOf(-1f) }
    // 拖动中 (手指未抬起); seek 完成回调在拖动中到达时不清 dragProgress、不恢复播放
    var isDragging by remember { mutableStateOf(false) }
    // 拖动开始时是否在播放 (含上一次 seek 完成后待恢复的播放); seek 完成后据此自动继续
    var resumeAfterSeek by remember { mutableStateOf(false) }
    var showCustomSpeedDialog by remember { mutableStateOf(false) }
    var showExportDialog by remember { mutableStateOf(false) }
    val presetSpeeds = remember { listOf(0.5f, 1f, 2f, 4f, 8f, 16f, 32f) }

    LaunchedEffect(canvasW, canvasH) {
        if (canvasW > 0 && canvasH > 0) {
            vm.setRenderViewport(canvasW, canvasH)
        }
    }

    BackHandler { vm.exitReplay() }

    Box(Modifier.fillMaxSize().background(colors.bg)) {
        Column(Modifier.fillMaxSize()) {
            // ---- Top bar ----
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .background(colors.panel)
                        .statusBarsPadding()
                        .height(52.dp)
                        .padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ReIconButton(R.drawable.ic_arrow_left, stringResource(R.string.back), { vm.exitReplay() }, tint = colors.text)
                Text(
                    text = if (vm.docName.isNotBlank()) stringResource(R.string.replay_title_with_doc, vm.docName) else stringResource(R.string.replay_title),
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
                        val selected = s != null && s.speed == sp
                        Text(
                            text = if (sp >= 1f) "${sp.toInt()}x" else "0.5x",
                            color = if (selected) colors.onAccent else colors.text,
                            fontSize = 12.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            modifier =
                                Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (selected) colors.accent else colors.panelHi)
                                    .clickable { vm.setReplaySpeed(sp) }
                                    .padding(horizontal = 8.dp, vertical = 5.dp),
                        )
                    }
                    val isCustom = s != null && s.speed !in presetSpeeds
                    val customLabel = if (isCustom && s != null) {
                        val v = s.speed
                        if (v == v.toInt().toFloat()) "${v.toInt()}x" else "${v}x"
                    } else {
                        stringResource(R.string.brush_preset_custom_tag)
                    }
                    Text(
                        text = customLabel,
                        color = if (isCustom) colors.onAccent else colors.text,
                        fontSize = 12.sp,
                        fontWeight = if (isCustom) FontWeight.Bold else FontWeight.Normal,
                        modifier =
                            Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (isCustom) colors.accent else colors.panelHi)
                                .clickable { showCustomSpeedDialog = true }
                                .padding(horizontal = 8.dp, vertical = 5.dp),
                    )
                }
                Spacer(Modifier.width(6.dp))
                if (s != null) {
                    ReIconButton(
                        icon = R.drawable.ic_export,
                        desc = stringResource(R.string.replay_export_btn),
                        onTap = {
                            vm.pauseReplay()
                            showExportDialog = true
                        },
                        tint = colors.text,
                    )
                }
                Spacer(Modifier.width(4.dp))
            }

            // ---- Canvas ----
            Box(
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(colors.canvasBg)
                        .onSizeChanged {
                            canvasW = it.width
                            canvasH = it.height
                        },
                contentAlignment = Alignment.Center,
            ) {
                val bmp = vm.displayBitmap
                val rev = vm.displayRevision
                val ib = remember(bmp, rev) { bmp?.asImageBitmap() }
                if (ib != null) {
                    Image(
                        bitmap = ib,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .padding(10.dp),
                    )
                } else {
                    Text(stringResource(R.string.replay_preparing_canvas), color = colors.subText, fontSize = 14.sp)
                }

                if (s != null && s.isPlaying) {
                    Row(
                        modifier =
                            Modifier
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
                        Text(stringResource(R.string.replay_playing_status), color = Color.White, fontSize = 12.sp)
                    }
                }
            }

            // ---- Player controls ----
            if (s != null) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .background(colors.panel)
                            .navigationBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ReFab(
                            icon = if (s.isPlaying) R.drawable.ic_pause else R.drawable.ic_play,
                            desc = if (s.isPlaying) stringResource(R.string.pause) else stringResource(R.string.play),
                            onTap = {
                                resumeAfterSeek = false
                                if (s.isPlaying) vm.pauseReplay() else vm.playReplay()
                            },
                            sizeDp = 54.dp,
                        )
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            ReSlider(
                                value = if (dragProgress >= 0f) dragProgress else s.progress,
                                onValue = { v ->
                                    if (!isDragging) {
                                        // 拖动开始: 记住是否在播放并立即暂停, 拖动期间渲染线程不再步进
                                        isDragging = true
                                        resumeAfterSeek = s.isPlaying || resumeAfterSeek
                                        if (s.isPlaying) vm.pauseReplay()
                                    }
                                    dragProgress = v
                                },
                                onRelease = {
                                    isDragging = false
                                    val target = dragProgress
                                    if (target >= 0f) {
                                        // seek 完成前保持显示拖到的位置, 避免滑块弹回旧进度
                                        vm.seekReplay(target) { applied ->
                                            if (!isDragging) {
                                                dragProgress = -1f
                                                if (applied && resumeAfterSeek) {
                                                    resumeAfterSeek = false
                                                    if ((vm.replaySession?.progress ?: 1f) < 1f) vm.playReplay()
                                                }
                                            }
                                        }
                                    }
                                },
                                height = 20,
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    formatTime(if (dragProgress >= 0f) (s.totalMs * dragProgress).toLong() else s.elapsedMs),
                                    color = colors.subText,
                                    fontSize = 12.sp,
                                )
                                Text(formatTime(s.totalMs), color = colors.subText, fontSize = 12.sp)
                            }
                        }
                        Spacer(Modifier.width(10.dp))
                        ReIconButton(R.drawable.ic_refresh, stringResource(R.string.replay_restart), {
                            resumeAfterSeek = false
                            vm.seekReplay(0f)
                        }, tint = colors.text)
                    }
                }
            }
        }
    }

    if (showCustomSpeedDialog) {
        CustomSpeedDialog(
            colors = colors,
            initialSpeed = s?.speed ?: 1f,
            onConfirm = { speed ->
                vm.setReplaySpeed(speed)
                showCustomSpeedDialog = false
            },
            onDismiss = { showCustomSpeedDialog = false },
        )
    }

    if (showExportDialog && s != null) {
        ReplayExportDialog(
            vm = vm,
            session = s,
            colors = colors,
            onDismiss = { showExportDialog = false },
        )
    }
}

@Composable
private fun ReplayExportDialog(
    vm: PaintViewModel,
    session: ReplaySession,
    colors: AppColors,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val savedToast = stringResource(R.string.replay_export_toast_saved)
    val storagePermissionMsg = stringResource(R.string.replay_export_err_storage_permission)
    val shareChooserTitle = stringResource(R.string.replay_export_share_btn)
    var selectedFormat by remember { mutableStateOf("MP4") }
    var selectedDurationSec by remember { mutableIntStateOf(30) } // 15, 30, 60, or 0 (follow speed)
    var selectedScale by remember { mutableFloatStateOf(1.0f) }
    var rangeValues by remember { mutableStateOf(0f..1f) }
    var showBrushCursor by remember { mutableStateOf(true) }
    var showWatermark by remember { mutableStateOf(vm.authorProfile.enabled && vm.authorProfile.isNotEmpty()) }
    var holdEndFrames by remember { mutableStateOf(true) }

    var isExporting by remember { mutableStateOf(false) }
    val exportCancelled = remember { AtomicBoolean(false) }
    var exportCurrentFrame by remember { mutableIntStateOf(0) }
    var exportTotalFrames by remember { mutableIntStateOf(100) }
    var exportStage by remember { mutableStateOf("") }

    val fps = if (selectedFormat == "GIF") 15 else 30
    val exportOptions = remember(
        selectedFormat,
        selectedDurationSec,
        selectedScale,
        rangeValues,
        showBrushCursor,
        showWatermark,
        holdEndFrames,
    ) {
        ReplayExportOptions(
            format = selectedFormat.lowercase(),
            durationSec = selectedDurationSec,
            fps = fps,
            scale = selectedScale,
            startFraction = rangeValues.start,
            endFraction = rangeValues.endInclusive,
            showBrushCursor = showBrushCursor,
            showWatermark = showWatermark,
            holdEndFrames = holdEndFrames,
        )
    }

    fun startGalleryExport() {
        if (isExporting) return
        isExporting = true
        exportCancelled.set(false)
        vm.exportReplayToGallery(
            options = exportOptions,
            isCancelled = { exportCancelled.get() },
            onProgress = { cur, tot, stage ->
                exportCurrentFrame = cur
                exportTotalFrames = tot
                exportStage = stage
            },
            onSuccess = {
                isExporting = false
                Toast.makeText(
                    context,
                    savedToast,
                    Toast.LENGTH_SHORT,
                ).show()
                onDismiss()
            },
            onError = { err ->
                isExporting = false
                Toast.makeText(context, err, Toast.LENGTH_SHORT).show()
            },
        )
    }

    val storagePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            startGalleryExport()
        } else {
            Toast.makeText(
                context,
                storagePermissionMsg,
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    val createZipLauncher = rememberLauncherForActivityResult(
        contract = remember { CreateReplayDocWithMime() },
    ) { uri: Uri? ->
        if (isExporting) return@rememberLauncherForActivityResult
        if (uri != null) {
            isExporting = true
            exportCancelled.set(false)
            vm.exportReplayToUri(
                options = exportOptions,
                targetUri = uri,
                isCancelled = { exportCancelled.get() },
                onProgress = { cur, tot, stage ->
                    exportCurrentFrame = cur
                    exportTotalFrames = tot
                    exportStage = stage
                },
                onSuccess = {
                    isExporting = false
                    Toast.makeText(context, savedToast, Toast.LENGTH_SHORT).show()
                    onDismiss()
                },
                onError = { err ->
                    isExporting = false
                    Toast.makeText(context, err, Toast.LENGTH_SHORT).show()
                },
            )
        }
    }

    val (outW, outH) = remember(session.docW, session.docH, selectedScale, selectedFormat) {
        computeExportDimensions(session.docW, session.docH, selectedScale, selectedFormat)
    }

    Dialog(onDismissRequest = { if (!isExporting) onDismiss() }) {
        Box(
            modifier =
                Modifier
                    .width(348.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(colors.panel)
                    .border(1.dp, colors.border, RoundedCornerShape(16.dp))
                    .padding(20.dp),
        ) {
            if (isExporting) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = stringResource(R.string.replay_export_progress_title),
                        color = colors.text,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = exportStage.ifBlank { stringResource(R.string.replay_preparing_canvas) },
                        color = colors.subText,
                        fontSize = 12.sp,
                    )
                    val progress = if (exportTotalFrames > 0) {
                        (exportCurrentFrame.toFloat() / exportTotalFrames).coerceIn(0f, 1f)
                    } else 0f
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(CircleShape),
                        color = colors.accent,
                        trackColor = colors.panelHi,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "$exportCurrentFrame / $exportTotalFrames",
                            color = colors.subText,
                            fontSize = 11.sp,
                        )
                        Text(
                            text = "${(progress * 100).roundToInt()}%",
                            color = colors.accent,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    ReTextButton(
                        text = stringResource(R.string.cancel),
                        onClick = {
                            exportCancelled.set(true)
                        },
                    )
                }
            } else {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = stringResource(R.string.replay_export_title),
                        color = colors.text,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                    )

                    // 1. 格式选择 (MP4 / GIF / ZIP)
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(stringResource(R.string.replay_export_format_label), color = colors.subText, fontSize = 12.sp)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            listOf("MP4", "GIF", "ZIP").forEach { fmt ->
                                val selected = selectedFormat == fmt
                                Box(
                                    modifier =
                                        Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(if (selected) colors.accent else colors.panelHi)
                                            .clickable { selectedFormat = fmt }
                                            .padding(vertical = 8.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = fmt,
                                        color = if (selected) colors.onAccent else colors.text,
                                        fontSize = 13.sp,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                    )
                                }
                            }
                        }
                    }

                    // 2. 缩时时长 (15s / 30s / 60s / 当前倍速)
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(stringResource(R.string.replay_export_duration_label), color = colors.subText, fontSize = 12.sp)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            val speed = session.speed
                            val speedLabel = if (speed == speed.toInt().toFloat()) "${speed.toInt()}x" else "${speed}x"
                            val durations = listOf(15 to "15s", 30 to "30s", 60 to "60s", 0 to speedLabel)
                            durations.forEach { (sec, label) ->
                                val selected = selectedDurationSec == sec
                                Box(
                                    modifier =
                                        Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(if (selected) colors.accent else colors.panelHi)
                                            .clickable { selectedDurationSec = sec }
                                            .padding(vertical = 7.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = label,
                                        color = if (selected) colors.onAccent else colors.text,
                                        fontSize = 12.sp,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                    )
                                }
                            }
                        }
                    }

                    // 3. 导出区间裁剪 (startFraction .. endFraction)
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(stringResource(R.string.replay_export_range_label), color = colors.subText, fontSize = 12.sp)
                            val startPct = (rangeValues.start * 100).roundToInt()
                            val endPct = (rangeValues.endInclusive * 100).roundToInt()
                            Text(
                                text = "$startPct% – $endPct% (${formatTime((session.totalMs * rangeValues.start).toLong())} - ${formatTime((session.totalMs * rangeValues.endInclusive).toLong())})",
                                color = colors.text,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                        RangeSlider(
                            value = rangeValues,
                            onValueChange = { range ->
                                val sVal = range.start.coerceIn(0f, 0.95f)
                                val eVal = range.endInclusive.coerceIn(sVal + 0.05f, 1f)
                                rangeValues = sVal..eVal
                            },
                            valueRange = 0f..1f,
                            colors =
                                SliderDefaults.colors(
                                    thumbColor = colors.accent,
                                    activeTrackColor = colors.accent,
                                    inactiveTrackColor = colors.panelHi,
                                ),
                        )
                    }

                    // 4. 分辨率缩放 (100% / 50% / 25%) — 展示对齐与安全上限后的真实输出像素
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(stringResource(R.string.replay_export_scale_label), color = colors.subText, fontSize = 12.sp)
                            Text("${outW} × ${outH}", color = colors.text, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            listOf(1.0f to "100%", 0.5f to "50%", 0.25f to "25%").forEach { (sc, label) ->
                                val selected = selectedScale == sc
                                Box(
                                    modifier =
                                        Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(if (selected) colors.accent else colors.panelHi)
                                            .clickable { selectedScale = sc }
                                            .padding(vertical = 7.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = label,
                                        color = if (selected) colors.onAccent else colors.text,
                                        fontSize = 12.sp,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                    )
                                }
                            }
                        }
                        if (outW >= 3840 || outH >= 2160) {
                            Text(
                                text = stringResource(R.string.replay_export_hint_4k),
                                color = colors.subText,
                                fontSize = 11.sp,
                            )
                        }
                    }

                    // 5. 叠加层与定格开关
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = stringResource(R.string.replay_export_show_cursor),
                                color = colors.text,
                                fontSize = 13.sp,
                            )
                            ReSwitch(
                                checked = showBrushCursor,
                                onChecked = { showBrushCursor = it },
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
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

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
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

                    // 6. 底部操作按钮: 保存至相册(或文件) + 分享
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box(
                            modifier =
                                Modifier
                                    .weight(1f)
                                    .height(40.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(colors.accent)
                                    .clickable {
                                        if (isExporting) return@clickable
                                        if (selectedFormat == "ZIP") {
                                            val safeName = sanitizeExportFileName(vm.docName)
                                            createZipLauncher.launch("application/zip" to "${safeName}_replay_frames.zip")
                                        } else if (
                                            Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                                            ContextCompat.checkSelfPermission(
                                                context,
                                                Manifest.permission.WRITE_EXTERNAL_STORAGE,
                                            ) != PackageManager.PERMISSION_GRANTED
                                        ) {
                                            storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                                        } else {
                                            startGalleryExport()
                                        }
                                    },
                            contentAlignment = Alignment.Center,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_save),
                                    contentDescription = null,
                                    tint = colors.onAccent,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = if (selectedFormat == "ZIP") stringResource(R.string.replay_export_save_zip) else stringResource(R.string.replay_export_save_gallery),
                                    color = colors.onAccent,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }

                        Box(
                            modifier =
                                Modifier
                                    .weight(1f)
                                    .height(40.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(colors.panelHi)
                                    .clickable {
                                        if (isExporting) return@clickable
                                        isExporting = true
                                        exportCancelled.set(false)
                                        vm.shareReplay(
                                            options = exportOptions,
                                            isCancelled = { exportCancelled.get() },
                                            onProgress = { cur, tot, stage ->
                                                exportCurrentFrame = cur
                                                exportTotalFrames = tot
                                                exportStage = stage
                                            },
                                            onSuccess = { file ->
                                                isExporting = false
                                                try {
                                                    val uri = FileProvider.getUriForFile(
                                                        context,
                                                        "${context.packageName}.fileprovider",
                                                        file,
                                                    )
                                                    val mime = when (selectedFormat.lowercase()) {
                                                        "gif" -> "image/gif"
                                                        "mp4" -> "video/mp4"
                                                        "zip" -> "application/zip"
                                                        else -> "*/*"
                                                    }
                                                    val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                                        type = mime
                                                        putExtra(Intent.EXTRA_STREAM, uri)
                                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                    }
                                                    context.startActivity(
                                                        Intent.createChooser(
                                                            sendIntent,
                                                            shareChooserTitle,
                                                        ),
                                                    )
                                                } catch (e: Exception) {
                                                    Toast.makeText(context, "${e.message}", Toast.LENGTH_SHORT).show()
                                                }
                                                onDismiss()
                                            },
                                            onError = { err ->
                                                isExporting = false
                                                Toast.makeText(context, err, Toast.LENGTH_SHORT).show()
                                            },
                                        )
                                    },
                            contentAlignment = Alignment.Center,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_share),
                                    contentDescription = null,
                                    tint = colors.text,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = stringResource(R.string.replay_export_share_btn),
                                    color = colors.text,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CustomSpeedDialog(
    colors: AppColors,
    initialSpeed: Float,
    onConfirm: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    var textValue by remember {
        mutableStateOf(
            if (initialSpeed == initialSpeed.toInt().toFloat()) {
                initialSpeed.toInt().toString()
            } else {
                initialSpeed.toString()
            }
        )
    }
    var sliderValue by remember { mutableFloatStateOf(initialSpeed.coerceIn(0.1f, 128f)) }

    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier =
                Modifier
                    .width(320.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(colors.panel)
                    .border(1.dp, colors.border, RoundedCornerShape(16.dp))
                    .padding(22.dp),
        ) {
            Column {
                Text(
                    text = stringResource(R.string.replay_custom_speed_title),
                    color = colors.text,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(14.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(colors.panelHi)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    BasicTextField(
                        value = textValue,
                        onValueChange = { str ->
                            textValue = str
                            str.toFloatOrNull()?.let {
                                if (it in 0.1f..128f) sliderValue = it
                            }
                        },
                        textStyle =
                            TextStyle(
                                color = colors.text,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Medium,
                            ),
                        cursorBrush = SolidColor(colors.accent),
                        singleLine = true,
                        keyboardOptions =
                            KeyboardOptions(
                                keyboardType = KeyboardType.Decimal,
                            ),
                        modifier = Modifier.weight(1f),
                    )
                    Text("x", color = colors.subText, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(14.dp))
                ReSlider(
                    value = sliderValue.coerceIn(0.5f, 64f),
                    onValue = {
                        sliderValue = it
                        textValue =
                            if (it >= 10f) {
                                it.toInt().toString()
                            } else {
                                String.format(Locale.US, "%.1f", it)
                            }
                    },
                    valueRange = 0.5f..64f,
                    height = 20,
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    listOf(6f, 10f, 20f, 48f, 64f, 100f).forEach { qp ->
                        Text(
                            text = "${qp.toInt()}x",
                            color = colors.subText,
                            fontSize = 12.sp,
                            modifier =
                                Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(colors.panelHi)
                                    .clickable {
                                        sliderValue = qp
                                        textValue = qp.toInt().toString()
                                    }
                                    .padding(horizontal = 6.dp, vertical = 3.dp),
                        )
                    }
                }
                Spacer(Modifier.height(20.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    ReTextButton(text = stringResource(R.string.cancel), onClick = onDismiss)
                    Spacer(Modifier.width(10.dp))
                    ReTextButton(
                        text = stringResource(R.string.confirm),
                        primary = true,
                        onClick = {
                            val v = textValue.toFloatOrNull() ?: sliderValue
                            onConfirm(v.coerceIn(0.1f, 128f))
                        },
                    )
                }
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    val totalSec = (ms / 1000L).coerceAtLeast(0L)
    val m = totalSec / 60L
    val s = totalSec % 60L
    return String.format(Locale.US, "%02d:%02d", m, s)
}
