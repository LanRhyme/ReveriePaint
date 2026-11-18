/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.reverie.paint.core

import android.net.Uri
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

internal class StoryboardState {
    var cues by mutableStateOf<List<SrtCue>>(emptyList())
    var fps by mutableStateOf(DEFAULT_ANIMATION_FPS)
    var importing by mutableStateOf(false)
    var adding by mutableStateOf(false)
    var singleFrameMode by mutableStateOf(false)
    var frames by mutableStateOf<List<StoryboardFrame>>(emptyList())
    var trackIndex by mutableStateOf(-1)
    var trackId = 0L
    var probed = false
    @Volatile var generation = 0

    fun reset() {
        generation++
        cues = emptyList()
        importing = false
        adding = false
        fps = DEFAULT_ANIMATION_FPS
        probed = false
        singleFrameMode = false
        frames = emptyList()
        trackIndex = -1
        trackId = 0L
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
        state.cues = runCatching {
            ReverieCoreBridge.revAssetBytes(SrtStoryboard.ASSET)?.takeIf { it.isNotEmpty() }
                ?.let { SrtStoryboard.parseStored(SrtStoryboard.decode(it)) } ?: emptyList()
        }.getOrDefault(emptyList())
        val savedIndex = ReverieCoreBridge.revAssetBytes(SrtStoryboard.ASSET + ".track")
            ?.decodeToString()?.toIntOrNull()
        // Compatibility: earlier builds did not store the generated track binding.
        val layerCount = ReverieCoreBridge.layerCount()
        val track = if (state.cues.isEmpty()) {
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
    state.frames = if (state.singleFrameMode) {
        SrtStoryboard.resizedShots(
            state.cues, anim.keyframeCache[state.trackIndex].orEmpty(), anim.lastFrameHold[state.trackIndex] ?: 1,
        )
    } else {
        runCatching { SrtStoryboard.frames(state.cues, state.fps) }.getOrDefault(emptyList())
    }
}

/** Persist the current index after layer moves/renames; the in-memory identity stays stable across undo. */
internal fun PaintViewModel.syncStoryboardTrackFromNative() {
    val state = anim.storyboard
    if (!state.probed || state.trackId == 0L) return
    // 字幕被移除 (或本工程根本没有字幕) 时不留任何绑定, 避免影响正常图层的分帧
    if (state.cues.isEmpty()) {
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
    layer > 0 && anim.storyboard.cues.isNotEmpty() && anim.storyboard.trackId != 0L &&
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
    val updated = SrtStoryboard.replaceCue(state.cues, index, text)
    if (updated == null) {
        showActionToast(R.string.srt_error_invalid)
        return
    }
    if (updated == state.cues) return
    val assetBytes = SrtStoryboard.encode(updated)
    if (assetBytes.size > SrtStoryboard.MAX_BYTES) {
        showActionToast(R.string.srt_error_large)
        return
    }

    state.cues = updated
    state.frames = if (state.singleFrameMode) {
        SrtStoryboard.resizedShots(
            updated,
            anim.keyframeCache[state.trackIndex].orEmpty(),
            anim.lastFrameHold[state.trackIndex] ?: 1,
        )
    } else {
        runCatching { SrtStoryboard.frames(updated, state.fps) }.getOrDefault(emptyList())
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
        state.frames.size != state.cues.size || index !in state.cues.indices
    ) {
        showActionToast(R.string.srt_mapping_changed)
        return false
    }
    val updated = SrtStoryboard.splitCue(state.cues, index, leftText, rightText)
    if (updated == null) {
        showActionToast(R.string.srt_error_split)
        return false
    }
    val assetBytes = SrtStoryboard.encode(updated)
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
        success = true
        state.cues = updated
        ReverieCoreBridge.storeRevAsset(SrtStoryboard.ASSET, assetBytes)
        ReverieCoreBridge.storeRevAsset(SrtStoryboard.ASSET + ".track", layer.toString().toByteArray())
        ReverieCoreBridge.setAnimationCurrentTime(time, false)
        syncAnimationFromNative()
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
            state.cues = cues
            state.fps = importedFps
            state.singleFrameMode = true
            state.trackId = ReverieCoreBridge.layerId(selected)
            state.trackIndex = -1
            state.probed = true
            ReverieCoreBridge.storeRevAsset(SrtStoryboard.ASSET + ".layout", "single-frame".toByteArray())
            ReverieCoreBridge.storeRevAsset(SrtStoryboard.ASSET, assetBytes)
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
