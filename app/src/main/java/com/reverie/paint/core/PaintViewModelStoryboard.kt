/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.reverie.paint.core

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import com.reverie.paint.R
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
                ?.let { SrtStoryboard.parse(SrtStoryboard.decode(it)) } ?: emptyList()
        }.getOrDefault(emptyList())
        val savedIndex = ReverieCoreBridge.revAssetBytes(SrtStoryboard.ASSET + ".track")
            ?.decodeToString()?.toIntOrNull()
        // Compatibility: earlier builds did not store the generated track binding.
        val track = savedIndex ?: if (state.cues.isNotEmpty()) {
            (1 until ReverieCoreBridge.layerCount()).lastOrNull {
                val name = ReverieCoreBridge.layerName(it)
                name == "SRT 分镜" || name.startsWith("SRT 分镜 ") ||
                    name == "SRT storyboard" || name.startsWith("SRT storyboard ")
            } ?: -1
        } else -1
        if (track in 1 until ReverieCoreBridge.layerCount()) state.trackId = ReverieCoreBridge.layerId(track)
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
    val index = (0 until ReverieCoreBridge.layerCount()).firstOrNull {
        ReverieCoreBridge.layerId(it) == state.trackId
    } ?: -1
    if (index != state.trackIndex) {
        state.trackIndex = index
        ReverieCoreBridge.storeRevAsset(SrtStoryboard.ASSET + ".track", index.toString().toByteArray())
    }
}

/** SRT tracks edit their held drawing; never auto-split it on pen down. Other tracks retain their workflow. */
internal fun PaintViewModel.isStoryboardTrackOnRenderThread(layer: Int): Boolean =
    anim.storyboard.cues.isNotEmpty() && anim.storyboard.trackId != 0L &&
        ReverieCoreBridge.layerId(layer) == anim.storyboard.trackId

internal fun PaintViewModel.animationImportSrt(uri: Uri) {
    val state = anim.storyboard
    if (state.importing || isImportingMedia || !anim.enabled) return
    val generation = state.generation
    state.importing = true
    isImportingMedia = true
    animationPause()
    viewModelScope.launch {
        var assetBytes = ByteArray(0)
        val cues = try {
            withContext(Dispatchers.IO) {
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
                SrtStoryboard.parse(SrtStoryboard.decode(bytes)).also { parsed ->
                    assetBytes = SrtStoryboard.encode(parsed)
                    if (assetBytes.size > SrtStoryboard.MAX_BYTES) {
                        throw SrtImportException(SrtImportException.Reason.TOO_LARGE)
                    }
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
