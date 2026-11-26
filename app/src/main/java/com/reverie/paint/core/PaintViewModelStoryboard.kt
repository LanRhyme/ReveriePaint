/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.reverie.paint.core

import android.net.Uri
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import com.reverie.paint.R
import com.reverie.paint.model.AnimationAudioClips
import com.reverie.paint.model.AnimationLayerOps
import com.reverie.paint.model.StoryboardFrame
import com.reverie.paint.model.SrtCue
import com.reverie.paint.model.SrtImportException
import com.reverie.paint.model.SrtStoryboard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.min
import kotlin.math.roundToInt

internal class StoryboardState {
    /** Currently visible captions (backed by [allCues] minus cues whose keyframe is gone). */
    var cues by mutableStateOf<List<SrtCue>>(emptyList())

    /** Full caption asset. A deleted shot keeps its caption here so undo can reveal it again. */
    @Volatile var allCues: List<SrtCue> = emptyList()

    /** Cue -> keyframe time mapping, aligned with [allCues]. */
    @Volatile var cueTimes: List<Int> = emptyList()

    /** Indices into [allCues] currently visible, aligned with [cues] and [frames]. */
    @Volatile var visibleIndices: List<Int> = emptyList()

    var fps by mutableStateOf(DEFAULT_ANIMATION_FPS)
    var importing by mutableStateOf(false)
    var exportingPdf by mutableStateOf(false)
    var adding by mutableStateOf(false)
    var singleFrameMode by mutableStateOf(false)
    var frames by mutableStateOf<List<StoryboardFrame>>(emptyList())
    var trackIndex by mutableStateOf(-1)
    var trackId = 0L
    var probed = false
    @Volatile var generation = 0

    /** Last single-cue delete, used to restore the cue->frame mapping with engine undo/redo. */
    @Volatile var pendingCueDelete: PendingStoryboardCueDelete? = null

    fun reset() {
        generation++
        cues = emptyList()
        allCues = emptyList()
        cueTimes = emptyList()
        visibleIndices = emptyList()
        importing = false
        exportingPdf = false
        adding = false
        fps = DEFAULT_ANIMATION_FPS
        probed = false
        singleFrameMode = false
        frames = emptyList()
        trackIndex = -1
        trackId = 0L
        pendingCueDelete = null
    }
}

internal data class PendingStoryboardCueDelete(
    val generation: Int,
    val undoCountAfter: Int,
    val allIndex: Int,
    val cueTime: Int,
    var undone: Boolean = false,
)

/** Export every mapped SRT shot to A4 PDF; 4-up is landscape and 6-up is portrait. */
internal fun PaintViewModel.animationExportStoryboardPdf(uri: Uri, cellsPerPage: Int) {
    val state = anim.storyboard
    val cues = state.cues.toList()
    val frames = state.frames.toList()
    if (state.exportingPdf || cues.isEmpty()) return
    if (cellsPerPage !in setOf(4, 6) || frames.size != cues.size) {
        showActionToast(R.string.srt_pdf_mapping_error)
        return
    }

    state.exportingPdf = true
    animationPause()
    viewModelScope.launch {
        val output = try {
            withContext(Dispatchers.IO) { appContext.contentResolver.openOutputStream(uri, "wt") }
        } catch (_: Exception) {
            null
        }
        if (output == null) {
            state.exportingPdf = false
            showActionToast(R.string.srt_pdf_export_failed)
            return@launch
        }

        var originalTime = anim.currentTime
        var succeeded = false
        runCore(after = {
            state.exportingPdf = false
            anim.currentTime = originalTime
            showActionToast(
                if (succeeded) R.string.srt_pdf_export_success else R.string.srt_pdf_export_failed,
            )
        }) {
            originalTime = ReverieCoreBridge.animationCurrentTime()
            val width = ReverieCoreBridge.docWidth()
            val height = ReverieCoreBridge.docHeight()
            if (width <= 0 || height <= 0) {
                output.close()
                return@runCore
            }

            val pdf = PdfDocument()
            var page: PdfDocument.Page? = null
            var bitmap: Bitmap? = null
            val restoreOnionSuppression = anim.isPlaying || anim.shiftTraceActive
            try {
                bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                ReverieCoreBridge.setOnionSkinSuppressed(true)
                val pageWidth = if (cellsPerPage == 4) 842 else 595
                val pageHeight = if (cellsPerPage == 4) 595 else 842
                val columns = 2
                val rows = if (cellsPerPage == 4) 2 else 3
                val margin = 26f
                val gap = 16f
                val cellWidth = (pageWidth - margin * 2 - gap) / columns
                val cellHeight = (pageHeight - margin * 2 - gap * (rows - 1)) / rows
                val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.rgb(70, 70, 70)
                    textSize = 9f
                    typeface = android.graphics.Typeface.create(
                        "sans-serif-medium",
                        android.graphics.Typeface.NORMAL,
                    )
                }
                val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.BLACK
                    textSize = 10f
                }
                val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.rgb(205, 205, 205)
                    style = Paint.Style.STROKE
                    strokeWidth = 0.8f
                }
                val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                val whitePaint = Paint().apply { color = Color.WHITE }
                val captionHeight = if (cellsPerPage == 4) 67f else 63f
                var pageIndex = -1

                cues.indices.forEach { index ->
                    if (index % cellsPerPage == 0) {
                        page?.let(pdf::finishPage)
                        pageIndex++
                        page = pdf.startPage(
                            PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageIndex + 1).create(),
                        )
                        page!!.canvas.drawColor(Color.WHITE)
                        page!!.canvas.drawText(
                            appContext.getString(R.string.srt_pdf_page_title, pageIndex + 1),
                            margin,
                            17f,
                            headerPaint,
                        )
                    }

                    val canvas = page!!.canvas
                    val slot = index % cellsPerPage
                    val column = slot % columns
                    val row = slot / columns
                    val left = margin + column * (cellWidth + gap)
                    val top = margin + row * (cellHeight + gap)
                    val cue = cues[index]
                    canvas.drawText(
                        appContext.getString(R.string.srt_shot_header, index + 1),
                        left,
                        top + 11f,
                        headerPaint,
                    )
                    val caption = StaticLayout.Builder.obtain(
                        cue.text,
                        0,
                        cue.text.length,
                        bodyPaint,
                        cellWidth.toInt(),
                    )
                        .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                        .setIncludePad(false)
                        .setMaxLines(3)
                        .build()
                    canvas.save()
                    canvas.translate(left, top + 15f)
                    caption.draw(canvas)
                    canvas.restore()

                    val imageRect = RectF(left, top + captionHeight, left + cellWidth, top + cellHeight)
                    canvas.drawRect(imageRect, whitePaint)
                    val frame = frames[index]
                    ReverieCoreBridge.setAnimationCurrentTime(frame.start, false)
                    check(ReverieCoreBridge.renderToBuffer(bitmap!!, true, null))
                    val scale = min(
                        (imageRect.width() - 8f) / width,
                        (imageRect.height() - 8f) / height,
                    )
                    val drawWidth = width * scale
                    val drawHeight = height * scale
                    val destination = RectF(
                        imageRect.centerX() - drawWidth / 2f,
                        imageRect.centerY() - drawHeight / 2f,
                        imageRect.centerX() + drawWidth / 2f,
                        imageRect.centerY() + drawHeight / 2f,
                    )
                    val pageBitmap = Bitmap.createScaledBitmap(
                        bitmap!!,
                        (drawWidth * 1.5f).roundToInt().coerceAtLeast(1),
                        (drawHeight * 1.5f).roundToInt().coerceAtLeast(1),
                        true,
                    )
                    try {
                        canvas.drawBitmap(pageBitmap, null, destination, imagePaint)
                    } finally {
                        if (pageBitmap !== bitmap && !pageBitmap.isRecycled) pageBitmap.recycle()
                    }
                    canvas.drawRect(imageRect, borderPaint)
                }
                page?.let(pdf::finishPage)
                page = null
                output.use { pdf.writeTo(it) }
                succeeded = true
            } catch (e: Exception) {
                android.util.Log.e("RP_SRT", "Storyboard PDF export failed", e)
                runCatching { output.close() }
            } finally {
                page?.let { runCatching { pdf.finishPage(it) } }
                ReverieCoreBridge.setAnimationCurrentTime(originalTime, false)
                ReverieCoreBridge.setOnionSkinSuppressed(restoreOnionSuppression)
                bitmap?.recycle()
                pdf.close()
            }
        }
    }
}

/** Called only on the render thread. Reference text is a .revp asset, never canvas pixels. */
internal fun PaintViewModel.syncStoryboardFromNative() {
    val state = anim.storyboard
    if (!state.probed) {
        state.singleFrameMode = ReverieCoreBridge.revAssetBytes(SrtStoryboard.ASSET + ".layout")
            ?.decodeToString() == "single-frame"
        state.fps = ReverieCoreBridge.revAssetBytes(SrtStoryboard.ASSET + ".fps")
            ?.decodeToString()?.toIntOrNull()?.takeIf { it in 1..240 } ?: anim.framerate.coerceIn(1, 240)
        state.allCues = runCatching {
            ReverieCoreBridge.revAssetBytes(SrtStoryboard.ASSET)?.takeIf { it.isNotEmpty() }
                ?.let { SrtStoryboard.parseStored(SrtStoryboard.decode(it)) } ?: emptyList()
        }.getOrDefault(emptyList())
        state.cueTimes = SrtStoryboard.decodeFrameTimes(
            ReverieCoreBridge.revAssetBytes(SrtStoryboard.ASSET + ".frames"),
        )
        state.cues = state.allCues
        val savedIndex = ReverieCoreBridge.revAssetBytes(SrtStoryboard.ASSET + ".track")
            ?.decodeToString()?.toIntOrNull()
        // Compatibility: earlier builds did not store the generated track binding.
        val layerCount = ReverieCoreBridge.layerCount()
        val track = if (state.allCues.isEmpty()) {
            -1
        } else {
            savedIndex ?: (1 until layerCount).lastOrNull {
                val name = ReverieCoreBridge.layerName(it)
                name == "SRT 分镜" || name.startsWith("SRT 分镜 ") ||
                    name == "SRT storyboard" || name.startsWith("SRT storyboard ")
            } ?: -1
        }
        // 存盘的是索引而不是稳定 ID, 换文档后可能落到别的图层上。只有同时满足范围、
        // 存在动画通道时才认这个绑定, 否则一律解绑, 避免旧字幕把新工程的普通图层
        // 当成字幕主轨 (表现为该图层不出帧块、洋葱皮不参与、内容跨帧合并)
        val validTrack = track in 1 until layerCount &&
            ReverieCoreBridge.layerAnimatable(track) && ReverieCoreBridge.layerAnimated(track)
        state.trackId = if (validTrack) ReverieCoreBridge.layerId(track) else 0L
        if (!validTrack) state.trackIndex = -1
        state.probed = true
    }
    syncStoryboardTrackFromNative()

    val keyTimes = anim.keyframeCache[state.trackIndex].orEmpty()
    if (state.singleFrameMode) {
        // With every cue visible, keyframe moves/resizes define the mapping positionally.
        if (state.allCues.isNotEmpty() &&
            state.cueTimes.size == state.allCues.size &&
            state.cueTimes.none { it < 0 } &&
            keyTimes.size == state.allCues.size
        ) {
            val sortedTimes = keyTimes.sorted()
            if (state.cueTimes != sortedTimes) {
                state.cueTimes = sortedTimes
                ReverieCoreBridge.storeRevAsset(
                    SrtStoryboard.ASSET + ".frames",
                    SrtStoryboard.encodeFrameTimes(state.cueTimes),
                )
            }
        } else if (state.cueTimes.size != state.allCues.size &&
            state.allCues.isNotEmpty() &&
            keyTimes.size == state.allCues.size
        ) {
            // Legacy projects have no .frames asset: adopt the keyframe order when counts agree.
            state.cueTimes = keyTimes.sorted()
            ReverieCoreBridge.storeRevAsset(
                SrtStoryboard.ASSET + ".frames",
                SrtStoryboard.encodeFrameTimes(state.cueTimes),
            )
        }
        if (state.cueTimes.size == state.allCues.size && state.allCues.isNotEmpty()) {
            val keySet = keyTimes.toHashSet()
            state.visibleIndices = state.allCues.indices.filter {
                val time = state.cueTimes[it]
                time >= 0 && time in keySet
            }
            state.cues = state.visibleIndices.map { state.allCues[it] }
            state.frames = buildVisibleFrames(
                state.allCues,
                state.cueTimes,
                state.visibleIndices,
                anim.lastFrameHold[state.trackIndex] ?: 1,
            )
        } else {
            // Missing mapping or track: keep the captions visible for reference, but no frames.
            state.visibleIndices = state.allCues.indices.toList()
            state.cues = state.allCues
            state.frames = emptyList()
        }
    } else {
        state.visibleIndices = state.allCues.indices.toList()
        state.cues = state.allCues
        state.frames = runCatching { SrtStoryboard.frames(state.allCues, state.fps) }.getOrDefault(emptyList())
    }
}

private fun buildVisibleFrames(
    allCues: List<SrtCue>,
    cueTimes: List<Int>,
    visibleIndices: List<Int>,
    lastHold: Int,
): List<StoryboardFrame> {
    if (visibleIndices.isEmpty()) return emptyList()
    val times = visibleIndices.map { cueTimes[it] }
    return visibleIndices.mapIndexed { i, allIndex ->
        val start = times[i]
        val end = times.getOrNull(i + 1) ?: (start + lastHold.coerceAtLeast(1))
        StoryboardFrame(allCues[allIndex], start, end)
    }
}

/** Persist the current index after layer moves/renames; the in-memory identity stays stable across undo. */
internal fun PaintViewModel.syncStoryboardTrackFromNative() {
    val state = anim.storyboard
    if (!state.probed || state.trackId == 0L) return
    // 字幕被移除 (或本工程根本没有字幕) 时不留任何绑定, 避免影响正常图层的分帧
    if (state.allCues.isEmpty()) {
        state.trackId = 0L
        state.trackIndex = -1
        return
    }
    val index = (0 until ReverieCoreBridge.layerCount()).firstOrNull {
        ReverieCoreBridge.layerId(it) == state.trackId
    } ?: -1
    // 临时找不到 (撤销/重做中途) 只清索引, 保留 ID 以便图层回来后自动重绑;
    // 但若该图层已不再是可动画层, 绑定失效, 直接解绑
    if (index <= 0) {
        state.trackIndex = -1
        return
    }
    if (!ReverieCoreBridge.layerAnimatable(index) || !ReverieCoreBridge.layerAnimated(index)) {
        state.trackId = 0L
        state.trackIndex = -1
        return
    }
    if (index != state.trackIndex) {
        state.trackIndex = index
        ReverieCoreBridge.storeRevAsset(SrtStoryboard.ASSET + ".track", index.toString().toByteArray())
    }
}

/**
 * 换文档时清掉上一份工程残留在引擎资源表里的字幕与音频。
 *
 * 引擎的 `m_revAssets` 是进程级单例: `newDocument()` / `closeDocument()` 都不会清,
 * 只有 `loadRevp()` 会重建。不清理会让新建工程继承上一个工程的 SRT/音频, 并且保存时
 * 把旧资源真正写进新 `.revp`。
 *
 * 必须在渲染线程调用。置空字节后键名仍在, 因此同步记入 [AnimationState.hiddenAssets]
 * 让界面的音频列表滤掉它们; 保存重开时空条目会被 `loadRevp` 跳过, 资源真正消失。
 */
internal fun PaintViewModel.clearInheritedRevAssets() {
    val names = ReverieCoreBridge.revAssetNames().toList()
    val audioNames = AnimationLayerOps.audioAssetsOf(names)
    val keysToClear = audioNames + listOf(
        SrtStoryboard.ASSET,
        SrtStoryboard.ASSET + ".layout",
        SrtStoryboard.ASSET + ".fps",
        SrtStoryboard.ASSET + ".frames",
        SrtStoryboard.ASSET + ".track",
        AnimationAudioClips.ASSET,
        AnimationLayerOps.ASSET_BACKGROUND,
        AnimationLayerOps.ASSET_FOREGROUND,
    )
    for (name in keysToClear) {
        ReverieCoreBridge.storeRevAsset(name, ByteArray(0))
    }
    anim.hiddenAssets = anim.hiddenAssets + audioNames
    anim.storyboard.reset()
    anim.audioAssets = emptyList()
    anim.audioClips = emptyList()
    anim.audioWaveform = emptyList()
    anim.cachedAudioFile = null
}

/** SRT tracks edit their held drawing; never auto-split it on pen down. Other tracks retain their workflow. */
internal fun PaintViewModel.isStoryboardTrackOnRenderThread(layer: Int): Boolean =
    layer > 0 && anim.storyboard.allCues.isNotEmpty() && anim.storyboard.trackId != 0L &&
        ReverieCoreBridge.layerId(layer) == anim.storyboard.trackId

internal fun PaintViewModel.animationImportSrt(uri: Uri) {
    animationImportStoryboard {
        val bytes = appContext.contentResolver.openInputStream(uri)?.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (output.size() + count > SrtStoryboard.MAX_BYTES) {
                    throw SrtImportException(SrtImportException.Reason.TOO_LARGE)
                }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        } ?: throw java.io.IOException("Cannot open subtitle")
        SrtStoryboard.parseImport(SrtStoryboard.decode(bytes))
    }
}

internal fun PaintViewModel.animationImportText(text: String) {
    animationImportStoryboard { SrtStoryboard.parseImport(text) }
}

/** Update only the storyboard reference asset; no canvas pixels or keyframes are changed. */
internal fun PaintViewModel.animationUpdateStoryboardCue(index: Int, text: String) {
    val state = anim.storyboard
    val allIndex = state.visibleIndices.getOrNull(index) ?: return
    val updatedAll = SrtStoryboard.replaceCue(state.allCues, allIndex, text)
    if (updatedAll == null) {
        showActionToast(R.string.srt_error_invalid)
        return
    }
    if (updatedAll == state.allCues) return
    val assetBytes = SrtStoryboard.encode(updatedAll)
    if (assetBytes.size > SrtStoryboard.MAX_BYTES) {
        showActionToast(R.string.srt_error_large)
        return
    }

    val updatedCue = updatedAll[allIndex]
    state.allCues = updatedAll
    state.cues = state.cues.toMutableList().also { if (index in it.indices) it[index] = updatedCue }
    state.frames = state.frames.toMutableList().also {
        if (index in it.indices) it[index] = it[index].copy(cue = updatedCue)
    }
    val generation = state.generation
    runCore(render = false) {
        if (generation == state.generation && state.probed) {
            ReverieCoreBridge.storeRevAsset(SrtStoryboard.ASSET, assetBytes)
        }
    }
    isModified = true
    onPaintingActivity()
}

/** Delete one caption and its linked SRT keyframe without moving any remaining drawing. */
internal fun PaintViewModel.animationRemoveStoryboardCue(index: Int): Boolean {
    val state = anim.storyboard
    if (state.adding || state.importing || isImportingMedia) return false
    if (!state.singleFrameMode || state.trackId == 0L) return false
    val allIndex = state.visibleIndices.getOrNull(index) ?: return false
    val frame = state.frames.getOrNull(index) ?: return false
    val cueTime = state.cueTimes.getOrNull(allIndex) ?: return false
    if (frame.start != cueTime) {
        showActionToast(R.string.srt_mapping_changed)
        return false
    }

    val generation = state.generation
    state.adding = true
    animationPause()
    var success = false
    runCore(after = {
        if (generation != state.generation) return@runCore
        state.adding = false
        if (success) {
            anim.selectedTrack = state.trackIndex
            anim.selectedFrames = emptySet()
            isModified = true
            hasPendingMajorOp = true
            onPaintingActivity()
            layerRevision++
            refreshLayerThumbs(force = true)
            showActionToast(R.string.srt_delete_cue_done)
        } else {
            showActionToast(R.string.srt_mapping_changed)
        }
    }) {
        if (generation != state.generation) return@runCore
        val layer = (1 until ReverieCoreBridge.layerCount()).firstOrNull {
            ReverieCoreBridge.layerId(it) == state.trackId
        } ?: return@runCore
        val keyTimes = ReverieCoreBridge.keyframeTimes(layer).sorted()
        if (cueTime !in keyTimes) return@runCore
        ReverieCoreBridge.beginUndoMacro(appContext.getString(R.string.srt_delete_cue))
        val removed = try {
            ReverieCoreBridge.removeKeyframe(layer, cueTime)
        } finally {
            ReverieCoreBridge.endUndoMacro()
        }
        val undoCountAfter = ReverieCoreBridge.undoCount()
        if (!removed) return@runCore
        val updatedTimes = state.cueTimes.toMutableList().also { it[allIndex] = -1 }
        state.cueTimes = updatedTimes
        state.pendingCueDelete = PendingStoryboardCueDelete(
            generation = generation,
            undoCountAfter = undoCountAfter,
            allIndex = allIndex,
            cueTime = cueTime,
        )
        ReverieCoreBridge.storeRevAsset(
            SrtStoryboard.ASSET + ".frames",
            SrtStoryboard.encodeFrameTimes(updatedTimes),
        )
        syncAnimationFromNative()
        success = true
    }
    return true
}

/**
 * Split the editable caption at the text cursor and insert a blank/draft keyframe after it.
 * Later keyframes are shifted by one frame so their drawings and caption order remain aligned.
 */
internal fun PaintViewModel.animationSplitStoryboardCue(
    index: Int,
    leftText: String,
    rightText: String,
): Boolean {
    val state = anim.storyboard
    if (state.adding || state.importing || isImportingMedia) return false
    if (!state.singleFrameMode || state.trackId == 0L ||
        state.cueTimes.size != state.allCues.size ||
        state.frames.size != state.cues.size || index !in state.cues.indices
    ) {
        showActionToast(R.string.srt_mapping_changed)
        return false
    }
    val allIndex = state.visibleIndices.getOrNull(index) ?: return false
    val updatedAll = SrtStoryboard.splitCue(state.allCues, allIndex, leftText, rightText)
    val updatedVisible = SrtStoryboard.splitCue(state.cues, index, leftText, rightText)
    if (updatedAll == null || updatedVisible == null) {
        showActionToast(R.string.srt_error_split)
        return false
    }
    val assetBytes = SrtStoryboard.encode(updatedAll)
    if (assetBytes.size > SrtStoryboard.MAX_BYTES) {
        showActionToast(R.string.srt_error_large)
        return false
    }
    val generation = state.generation
    state.adding = true
    animationPause()
    var success = false
    val insertionTime = state.frames.getOrNull(index)?.endExclusive
    runCore(after = {
        if (generation != state.generation) return@runCore
        state.adding = false
        if (success) {
            anim.selectedTrack = state.trackIndex
            anim.selectedFrames = emptySet()
            anim.scrollPx = anim.currentTime * anim.frameWidthPx
            isModified = true
            hasPendingMajorOp = true
            onPaintingActivity()
            layerRevision++
            refreshLayerThumbs(force = true)
        } else {
            showActionToast(R.string.srt_error_split)
        }
    }) {
        if (generation != state.generation || state.trackId == 0L) return@runCore
        val layer = (1 until ReverieCoreBridge.layerCount()).firstOrNull {
            ReverieCoreBridge.layerId(it) == state.trackId
        } ?: return@runCore
        if (!ReverieCoreBridge.layerAnimatable(layer) || !ReverieCoreBridge.layerAnimated(layer)) {
            return@runCore
        }
        val time = insertionTime?.coerceAtLeast(0) ?: return@runCore
        if (time == Int.MAX_VALUE) return@runCore
        val shiftTimes = ReverieCoreBridge.keyframeTimes(layer)
            .filter { it >= time }
            .sortedDescending()
        if (shiftTimes.any { it == Int.MAX_VALUE }) return@runCore

        val moved = ArrayList<Pair<Int, Int>>(shiftTimes.size)
        var added = false
        var readyToCommit = false
        ReverieCoreBridge.beginUndoMacro(appContext.getString(R.string.srt_add_frame))
        try {
            for (from in shiftTimes) {
                val to = from + 1
                if (!ReverieCoreBridge.moveKeyframe(layer, from, to)) {
                    throw IllegalStateException("Could not shift storyboard keyframe")
                }
                moved.add(from to to)
            }
            if (!ReverieCoreBridge.addKeyframe(layer, time)) {
                throw IllegalStateException("Could not create storyboard keyframe")
            }
            added = true
            readyToCommit = true
        } catch (e: Exception) {
            android.util.Log.e("RP_SRT", "Storyboard split failed", e)
        } finally {
            if (!readyToCommit) {
                if (added) ReverieCoreBridge.removeKeyframe(layer, time)
                for ((from, to) in moved.asReversed()) {
                    ReverieCoreBridge.moveKeyframe(layer, to, from)
                }
            }
            ReverieCoreBridge.endUndoMacro()
        }
        if (!readyToCommit) return@runCore
        val shiftedTimes = state.cueTimes.map { if (it >= time) it + 1 else it }.toMutableList()
        shiftedTimes.add(allIndex + 1, time)
        state.allCues = updatedAll
        state.cueTimes = shiftedTimes
        state.cues = updatedVisible
        ReverieCoreBridge.storeRevAsset(SrtStoryboard.ASSET, assetBytes)
        ReverieCoreBridge.storeRevAsset(
            SrtStoryboard.ASSET + ".frames",
            SrtStoryboard.encodeFrameTimes(shiftedTimes),
        )
        ReverieCoreBridge.storeRevAsset(SrtStoryboard.ASSET + ".track", layer.toString().toByteArray())
        ReverieCoreBridge.setAnimationCurrentTime(time, false)
        syncAnimationFromNative()
        success = true
    }
    return true
}

private fun PaintViewModel.animationImportStoryboard(readCues: suspend () -> List<SrtCue>) {
    val state = anim.storyboard
    if (state.importing || isImportingMedia || !anim.enabled) return
    val generation = state.generation
    state.importing = true
    isImportingMedia = true
    animationPause()
    viewModelScope.launch {
        val (cues, assetBytes) = try {
            withContext(Dispatchers.IO) {
                readCues().let { parsed ->
                    val assetBytes = SrtStoryboard.encode(parsed)
                    if (assetBytes.size > SrtStoryboard.MAX_BYTES) {
                        throw SrtImportException(SrtImportException.Reason.TOO_LARGE)
                    }
                    parsed to assetBytes
                }
            }
        } catch (e: Exception) {
            isImportingMedia = false
            if (generation == state.generation) {
                state.importing = false
                if (e !is CancellationException) showActionToast(srtErrorResource(e))
            }
            if (e is CancellationException) throw e
            return@launch
        }
        if (generation != state.generation) {
            isImportingMedia = false
            return@launch
        }
        var success = false
        var error = R.string.srt_error_import
        var selected = -1
        var importedFps = state.fps
        runCore(after = {
            isImportingMedia = false
            if (generation == state.generation) {
                state.importing = false
                if (success) {
                    anim.selectedTrack = selected
                    anim.selectedFrames = emptySet()
                    isModified = true
                    hasPendingMajorOp = true
                    onPaintingActivity()
                    anim.scrollPx = anim.currentTime * anim.frameWidthPx
                    anim.toolbarExpanded = false
                    layerRevision++
                    refreshLayerThumbs(force = true)
                    showActionToast(R.string.srt_import_success)
                } else {
                    showActionToast(error)
                }
            }
        }) {
            if (generation != state.generation || !ReverieCoreBridge.animationEnabled()) return@runCore
            importedFps = ReverieCoreBridge.animationFramerate()
            val frames = SrtStoryboard.oneFrameShots(cues)
            val oldLayer = ReverieCoreBridge.currentLayerIndex()
            val oldTime = ReverieCoreBridge.animationCurrentTime()
            val oldCount = ReverieCoreBridge.layerCount()
            var createdLayerId: Long? = null
            var completed = false
            try {
                // Insert at root, then move only the new layer to the top.
                ReverieCoreBridge.setCurrentLayer(0)
                ReverieCoreBridge.addLayer(appContext.getString(R.string.srt_layer_name))
                check(ReverieCoreBridge.layerCount() == oldCount + 1)
                var layer = ReverieCoreBridge.currentLayerIndex()
                val layerId = ReverieCoreBridge.layerId(layer)
                createdLayerId = layerId
                // Use an explicit root sibling. The prebuilt engine's -1/null insertion
                // puts the node at index 0, which is reserved for the background.
                val topRoot = (0 until ReverieCoreBridge.layerCount()).lastOrNull {
                    it != layer && ReverieCoreBridge.layerDepth(it) == 0
                }
                if (topRoot != null && topRoot > layer) {
                    check(ReverieCoreBridge.moveLayerAbove(layer, topRoot))
                }
                layer = (0 until ReverieCoreBridge.layerCount()).first {
                    ReverieCoreBridge.layerId(it) == layerId
                }
                if (ReverieCoreBridge.layerClipped(layer)) {
                    ReverieCoreBridge.setLayerClipped(layer, false)
                }
                ReverieCoreBridge.setCurrentLayer(layer)
                check(ReverieCoreBridge.enableLayerAnimation(layer))
                // No end-boundary keys: each subtitle is exactly one editable keyframe.
                // LegacyUndoAdapter holds the image barrier until endUndoMacro. Structural
                // operations above wait for projection updates, so they MUST stay outside it.
                ReverieCoreBridge.beginUndoMacro(appContext.getString(R.string.srt_import_title))
                try {
                    for (frame in frames) check(ReverieCoreBridge.addKeyframe(layer, frame.start))
                } finally {
                    ReverieCoreBridge.endUndoMacro()
                }
                selected = layer
                completed = true
            } catch (e: Exception) {
                android.util.Log.e("RP_SRT", "Storyboard creation failed", e)
            }
            if (!completed) {
                // Only remove the layer created by this import; a failed/empty macro must
                // never undo an unrelated user operation.
                val failedLayer = (0 until ReverieCoreBridge.layerCount()).firstOrNull {
                    ReverieCoreBridge.layerId(it) == createdLayerId
                }
                if (failedLayer != null) ReverieCoreBridge.removeLayer(failedLayer)
                ReverieCoreBridge.setCurrentLayer(oldLayer)
                ReverieCoreBridge.setAnimationCurrentTime(oldTime, false)
                syncLayersFromNative()
                syncAnimationFromNative()
                return@runCore
            }
            state.allCues = cues
            state.cueTimes = cues.indices.toList()
            state.visibleIndices = cues.indices.toList()
            state.cues = cues
            state.frames = frames
            state.fps = importedFps
            state.singleFrameMode = true
            state.trackId = ReverieCoreBridge.layerId(selected)
            state.trackIndex = -1
            state.probed = true
            ReverieCoreBridge.storeRevAsset(SrtStoryboard.ASSET + ".layout", "single-frame".toByteArray())
            ReverieCoreBridge.storeRevAsset(SrtStoryboard.ASSET, assetBytes)
            ReverieCoreBridge.storeRevAsset(
                SrtStoryboard.ASSET + ".frames",
                SrtStoryboard.encodeFrameTimes(state.cueTimes),
            )
            ReverieCoreBridge.storeRevAsset(
                SrtStoryboard.ASSET + ".fps", importedFps.toString().toByteArray(),
            )
            ReverieCoreBridge.setAnimationCurrentTime(frames.first().start, false)
            syncLayersFromNative()
            syncAnimationFromNative()
            success = true
        }
    }
}

private fun srtErrorResource(error: Exception): Int = when ((error as? SrtImportException)?.reason) {
    SrtImportException.Reason.INVALID -> R.string.srt_error_invalid
    SrtImportException.Reason.TOO_LARGE -> R.string.srt_error_large
    SrtImportException.Reason.OVERLAP -> R.string.srt_error_overlap
    SrtImportException.Reason.FRAME_COLLISION -> R.string.srt_error_frames
    null -> R.string.srt_error_import
}

/** 只移除字幕参考 (保留 SRT 轨道与已画好的帧)。必须在渲染线程调用。 */
internal fun PaintViewModel.removeStoryboardReference() {
    val keys = listOf(
        SrtStoryboard.ASSET,
        SrtStoryboard.ASSET + ".layout",
        SrtStoryboard.ASSET + ".fps",
        SrtStoryboard.ASSET + ".frames",
        SrtStoryboard.ASSET + ".track",
    )
    for (key in keys) ReverieCoreBridge.storeRevAsset(key, ByteArray(0))
    anim.storyboard.reset()
}

/** 长按字幕条调用: 停播后清掉字幕参考, 轨道与画面保持原样。 */
internal fun PaintViewModel.animationRemoveStoryboardReference() {
    if (anim.storyboard.cues.isEmpty()) return
    animationPause()
    runCore(after = {
        isModified = true
        layerRevision++
        showActionToast(R.string.srt_remove_done)
    }) {
        removeStoryboardReference()
    }
}
