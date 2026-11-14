/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.reverie.paint.core

import com.reverie.paint.R
import com.reverie.paint.model.AnimationCelPlan

internal data class TimelineDeleteRequest(val layerId: Long, val name: String, val generation: Int)

/** Render thread only. Raster pixels and undo remain owned by the native document. */
internal fun PaintViewModel.prepareAnimationCel(
    layer: Int,
    time: Int,
    erase: Boolean = false,
    preserveDrawing: Boolean = false,
): Boolean {
    if (time !in 0 until Int.MAX_VALUE || layer <= 0 || layer >= ReverieCoreBridge.layerCount()) return false
    if (!ReverieCoreBridge.layerAnimatable(layer) || ReverieCoreBridge.layerLocked(layer)) return false
    val layerId = ReverieCoreBridge.layerId(layer)
    if (layerId == anim.backgroundLayerId || layerId == anim.foregroundLayerId) return false
    if (!ReverieCoreBridge.layerAnimated(layer) && !ReverieCoreBridge.enableLayerAnimation(layer)) return false

    val primary = isStoryboardTrackOnRenderThread(layer)
    if (primary && !erase) return false
    val range = AnimationCelPlan.range(time, anim.storyboard.frames)
    if (!erase && ReverieCoreBridge.hasKeyframe(layer, range.start) &&
        ReverieCoreBridge.hasKeyframe(layer, range.endExclusive)
    ) return false
    val times = ReverieCoreBridge.keyframeTimes(layer).toList()
    // The primary track's keys define subtitle timing. Never leave an extra end key on that track.
    val temporaryEnd = primary && range.endExclusive !in times
    val edits = AnimationCelPlan.edits(times, range, erase, preserveDrawing)
    if (edits.isEmpty()) return false
    var completed = 0
    var success = false
    ReverieCoreBridge.beginUndoMacro(appContext.getString(
        if (erase) R.string.anim_delete_cel else R.string.anim_create_cel,
    ))
    try {
        for (edit in edits) {
            val ok = when (edit) {
                is AnimationCelPlan.Edit.Blank -> ReverieCoreBridge.addKeyframe(layer, edit.time)
                is AnimationCelPlan.Edit.Copy -> ReverieCoreBridge.copyKeyframe(layer, edit.from, edit.to)
                is AnimationCelPlan.Edit.Remove -> ReverieCoreBridge.removeKeyframe(layer, edit.time)
            }
            check(ok)
            completed++
        }
        if (temporaryEnd) check(ReverieCoreBridge.removeKeyframe(layer, range.endExclusive))
        success = true
    } catch (error: Exception) {
        android.util.Log.e("RP_CEL", "Cell edit failed", error)
    } finally {
        ReverieCoreBridge.endUndoMacro()
    }
    // Only undo this macro if it actually changed the document; never undo a user's previous stroke.
    if (!success && completed > 0) ReverieCoreBridge.undo()
    if (!success) {
        mainHandler.post { showActionToast(R.string.anim_cel_failed) }
        // Abort the enclosing render operation as well: never stamp or start a stroke on an unisolated cel.
        error("Could not isolate animation cel")
    }
    return true
}

/** Called immediately after creating an empty paint layer, before importing/stamping any pixels. */
internal fun PaintViewModel.initializeNewAnimationCel() {
    if (!anim.enabled) return
    val layer = ReverieCoreBridge.currentLayerIndex()
    prepareAnimationCel(layer, ReverieCoreBridge.animationCurrentTime())
}

/** A tap creates a bounded cel in this slot; selecting an existing cel keeps its drawing. */
internal fun PaintViewModel.animationOpenCel(layerIndex: Int, time: Int) {
    if (anim.isPlaying || layerIndex < 0 || time < 0) return
    val id = layers.getOrNull(layerIndex)?.id ?: return
    val generation = anim.storyboard.generation
    var changed = false
    runCore(after = {
        if (generation == anim.storyboard.generation) {
            notifyLayerChanged(contentChanged = changed)
        }
    }) {
        if (generation != anim.storyboard.generation) return@runCore
        val layer = (0 until ReverieCoreBridge.layerCount()).firstOrNull {
            ReverieCoreBridge.layerId(it) == id
        } ?: return@runCore
        ReverieCoreBridge.setCurrentLayer(layer)
        ReverieCoreBridge.setAnimationCurrentTime(time, false)
        changed = prepareAnimationCel(layer, time, preserveDrawing = true)
        syncLayersFromNative()
        syncAnimationFromNative()
        anim.selectedTrack = layer
    }
}

internal fun PaintViewModel.animationRequestDeleteTrack(index: Int) {
    val layer = layers.getOrNull(index) ?: return
    if (index == 0 || layer.isBackground) {
        showActionToast(R.string.toast_layer_delete_background)
        return
    }
    if (layer.locked) {
        showActionToast(R.string.toast_layer_delete_locked)
        return
    }
    animationPause()
    anim.deleteTrackRequest = TimelineDeleteRequest(layer.id, layer.name, anim.storyboard.generation)
}

internal fun PaintViewModel.animationConfirmDeleteTrack(request: TimelineDeleteRequest) {
    if (anim.deleteTrackRequest != request) return
    anim.deleteTrackRequest = null
    if (request.generation != anim.storyboard.generation) return
    var changed = false
    runCore(after = {
        if (request.generation == anim.storyboard.generation) {
            clearLayerSelection()
            notifyLayerChanged(contentChanged = changed)
        }
    }) {
        if (request.generation != anim.storyboard.generation) return@runCore
        // Resolve identity at execution time: adding/reordering layers while a dialog is open is harmless.
        val index = (1 until ReverieCoreBridge.layerCount()).firstOrNull {
            ReverieCoreBridge.layerId(it) == request.layerId
        } ?: return@runCore
        if (ReverieCoreBridge.layerLocked(index)) return@runCore
        val previousCount = ReverieCoreBridge.layerCount()
        ReverieCoreBridge.removeLayer(index)
        changed = ReverieCoreBridge.layerCount() < previousCount
        if (changed) active3DLayers = active3DLayers - request.layerId
        syncLayersFromNative()
        syncAnimationFromNative()
    }
}
