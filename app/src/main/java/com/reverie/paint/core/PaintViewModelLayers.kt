package com.reverie.paint.core

import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipFile

/**
 * Thumbnail lookup with index+name double check: an index-keyed entry is
 * only trusted when the layer name currently at that index matches the
 * requested one (indexes shift after add/remove/move and stale entries
 * would show another layer's thumbnail on a blank layer). Falls back to
 * the name-keyed map, which also avoids the stale-index case.
 */
internal fun PaintViewModel.thumbFor(
    layerIndex: Int,
    layerName: String,
): Bitmap? {
    val idxName = layerThumbIndexName[layerIndex]
    if (idxName != null && idxName == layerName) {
        return layerThumbStates[layerIndex]
    }
    return layerThumbByName[layerName]
}

private var thumbRefreshJob: Job? = null

/** Thumbnail refresh debounce: thumbs are only visible in the layer panel,
 *  which is never open while actively painting, so a refresh a few seconds
 *  after the pen lifts is plenty. The old 400ms window re-rendered the
 *  active layer's full-device thumbnail during brief pauses between rapid
 *  strokes - a visible hitch (full-layer convertToQImage + smooth scale on
 *  the render thread). Structural changes (add/remove/undo) still refresh
 *  immediately via [force]. */
private const val THUMB_REFRESH_DEBOUNCE_MS = 3_000L

/** (Re)generate layer thumbnails on the render thread. Debounced; triggers
 *  inside the window keep postponing the refresh until the user pauses. */
internal fun PaintViewModel.refreshLayerThumbs(force: Boolean = false) {
    val now = System.nanoTime()
    if (!force && now - lastThumbRefreshNs < THUMB_REFRESH_DEBOUNCE_MS * 1_000_000L) {
        thumbRefreshJob?.cancel()
        thumbRefreshJob =
            viewModelScope.launch {
                delay(THUMB_REFRESH_DEBOUNCE_MS)
                lastThumbRefreshNs = System.nanoTime()
                doRefreshLayerThumbs()
            }
        return
    }
    lastThumbRefreshNs = now
    doRefreshLayerThumbs()
}

private fun PaintViewModel.doRefreshLayerThumbs() {
    val n = ReverieCoreBridge.layerCount()
    if (n <= 0) return
    runCore(after = {}) {
        for (i in 0 until n) {
            val bmp = Bitmap.createBitmap(56, 56, Bitmap.Config.ARGB_8888)
            if (ReverieCoreBridge.renderLayerThumb(i, bmp)) {
                val idx = i
                val name = ReverieCoreBridge.layerName(idx)
                mainHandler.post {
                    layerThumbStates[idx] = bmp
                    layerThumbIndexName[idx] = name
                    layerThumbByName[name] = bmp
                }
            }
        }
    }
}

internal fun PaintViewModel.notifyLayerChanged(
    forceThumbs: Boolean = true,
    immediateRender: Boolean = true,
) {
    isModified = true
    onPaintingActivity()
    syncLayersFromNative()
    // Mirror the C++ solo raw-mode flag into Compose state so the solo
    // floating panel's chips re-highlight immediately after a toggle
    soloRawMode = ReverieCoreBridge.layerSoloRawMode()
    layerRevision++
    // Structural/attribute changes can shift layer indexes and invalidate
    // index-keyed thumbnails, so force a fresh render (the 400ms throttle
    // would otherwise skip it and show another layer's stale thumbnail).
    // Undo/redo passes forceThumbs=false + immediateRender=false so a fast
    // undo chain merges into one thumbnail refresh and one frame render.
    refreshLayerThumbs(force = forceThumbs)
    scheduleRender(immediate = immediateRender)
}

internal fun PaintViewModel.addLayer() {
    if (recorder.recording) {
        recorder.layerOp(com.reverie.paint.model.RecordingEvents.L_ADD)
    }
    runCore(after = ::notifyLayerChanged) {
        // empty name -> C++ generates 颜料图层 N
        ReverieCoreBridge.addLayer("")
    }
}

internal fun PaintViewModel.removeLayer() {
    removeLayer(currentLayerIndex)
}

internal fun PaintViewModel.removeLayer(index: Int) {
    if (recorder.recording) {
        recorder.layerOp(com.reverie.paint.model.RecordingEvents.L_REMOVE, index)
    }
    runCore(after = ::notifyLayerChanged) {
        ReverieCoreBridge.removeLayer(index)
    }
}

internal fun PaintViewModel.setCurrentLayer(i: Int) {
    if (recorder.recording) {
        recorder.layerOp(com.reverie.paint.model.RecordingEvents.L_SET_CURRENT, i)
    }
    runCore(after = ::notifyLayerChanged) {
        ReverieCoreBridge.setCurrentLayer(i)
    }
}

internal fun PaintViewModel.layerBlendMode(i: Int) = ReverieCoreBridge.layerBlendMode(i)

internal fun PaintViewModel.setLayerBlendMode(
    i: Int,
    opId: String,
) {
    if (recorder.recording) {
        recorder.layerOp(com.reverie.paint.model.RecordingEvents.L_BLEND, i, opId)
    }
    runCore(after = ::notifyLayerChanged) {
        ReverieCoreBridge.setLayerBlendMode(i, opId)
    }
}

internal fun PaintViewModel.toggleLayerVisible(i: Int) {
    if (recorder.recording) {
        recorder.layerOp(com.reverie.paint.model.RecordingEvents.L_VISIBLE, i)
    }
    runCore(after = ::notifyLayerChanged) {
        ReverieCoreBridge.setLayerVisible(i, !ReverieCoreBridge.layerVisible(i))
    }
}

internal fun PaintViewModel.layerName(i: Int) = ReverieCoreBridge.layerName(i)

/** Sample the color at document-space (x, y) and set it as the brush color. */
internal fun PaintViewModel.pickColor(
    x: Float,
    y: Float,
    currentLayerOnly: Boolean = pickerCurrentLayerOnly,
): String? {
    val c = ReverieCoreBridge.pickColorAt(x.toInt(), y.toInt(), currentLayerOnly) ?: return null
    updateBrushColor(c)
    return c
}

internal fun PaintViewModel.layerVisible(i: Int) = ReverieCoreBridge.layerVisible(i)

// ---- Full layer system ----
internal fun PaintViewModel.addGroupLayer() {
    val selected = selectedLayerIndices.filter { it > 0 }.sortedDescending()
    if (recorder.recording) {
        recorder.layerOp(com.reverie.paint.model.RecordingEvents.L_ADD_GROUP)
    }
    runCore(after = {
        notifyLayerChanged()
        clearLayerSelection()
    }) {
        val newGroupIndex = ReverieCoreBridge.addGroupLayer("")
        if (selected.isNotEmpty() && newGroupIndex >= 0) {
            val groupIdx = ReverieCoreBridge.currentLayerIndex()
            for (idx in selected) {
                val actualIdx = if (idx >= newGroupIndex) idx + 1 else idx
                if (actualIdx != groupIdx && actualIdx > 0) {
                    ReverieCoreBridge.moveLayerToGroup(actualIdx, groupIdx)
                }
            }
        }
    }
}

internal fun PaintViewModel.copyLayer(i: Int) {
    if (recorder.recording) {
        recorder.layerOp(com.reverie.paint.model.RecordingEvents.L_COPY, i)
    }
    runCore(after = ::notifyLayerChanged) {
        ReverieCoreBridge.copyLayer(i)
    }
}

internal fun PaintViewModel.clearLayer(i: Int) {
    if (recorder.recording) {
        recorder.layerOp(com.reverie.paint.model.RecordingEvents.L_CLEAR, i)
    }
    runCore(after = ::notifyLayerChanged) {
        ReverieCoreBridge.clearLayer(i)
    }
}

internal fun PaintViewModel.renameLayer(
    i: Int,
    name: String,
) {
    if (name.isBlank()) return
    if (recorder.recording) {
        recorder.layerOp(com.reverie.paint.model.RecordingEvents.L_RENAME, i, name.trim())
    }
    runCore(after = ::notifyLayerChanged) {
        ReverieCoreBridge.setLayerName(i, name.trim())
    }
}

internal fun PaintViewModel.layerOpacity(i: Int) = ReverieCoreBridge.layerOpacity(i)

internal fun PaintViewModel.setLayerOpacity(
    i: Int,
    v: Double,
    preview: Boolean = false,
) {
    if (preview) {
        // Slider drag preview: apply without an undo step and render through
        // the 16ms throttle - no layer sync / thumbnail refresh / immediate
        // frame per tick, so dragging the slider stays smooth
        runCore(after = { scheduleRender(immediate = false) }) {
            ReverieCoreBridge.setLayerOpacityDirect(i, v)
        }
    } else {
        // Slider release commit: single undo step + full refresh
        if (recorder.recording) {
            recorder.layerOp(com.reverie.paint.model.RecordingEvents.L_OPACITY, i, v.toString())
        }
        runCore(after = ::notifyLayerChanged) {
            ReverieCoreBridge.setLayerOpacity(i, v)
        }
    }
}

internal fun PaintViewModel.layerLocked(i: Int) = ReverieCoreBridge.layerLocked(i)

internal fun PaintViewModel.setLayerLocked(
    i: Int,
    locked: Boolean,
) {
    if (recorder.recording) {
        recorder.layerOp(com.reverie.paint.model.RecordingEvents.L_LOCKED, i, if (locked) "1" else "0")
    }
    runCore(after = ::notifyLayerChanged) {
        ReverieCoreBridge.setLayerLocked(i, locked)
    }
}

internal fun PaintViewModel.layerAlphaLocked(i: Int) = ReverieCoreBridge.layerAlphaLocked(i)

internal fun PaintViewModel.setLayerAlphaLocked(
    i: Int,
    locked: Boolean,
) {
    if (recorder.recording) {
        recorder.layerOp(com.reverie.paint.model.RecordingEvents.L_ALPHA_LOCKED, i, if (locked) "1" else "0")
    }
    runCore(after = ::notifyLayerChanged) {
        ReverieCoreBridge.setLayerAlphaLocked(i, locked)
    }
}

internal fun PaintViewModel.layerColorLabel(i: Int) = ReverieCoreBridge.layerColorLabel(i)

internal fun PaintViewModel.setLayerColorLabel(
    i: Int,
    label: Int,
) {
    if (recorder.recording) {
        recorder.layerOp(com.reverie.paint.model.RecordingEvents.L_COLOR_LABEL, i, label.toString())
    }
    runCore(after = ::notifyLayerChanged) {
        ReverieCoreBridge.setLayerColorLabel(i, label)
    }
}

internal fun PaintViewModel.layerIsGroup(i: Int) = ReverieCoreBridge.layerIsGroup(i)

internal fun PaintViewModel.layerDepth(i: Int) = ReverieCoreBridge.layerDepth(i)

internal fun PaintViewModel.layerBackground(i: Int) = ReverieCoreBridge.layerBackground(i)

internal fun PaintViewModel.layerClipped(i: Int) = ReverieCoreBridge.layerClipped(i)

internal fun PaintViewModel.setLayerClipped(
    i: Int,
    clipped: Boolean,
) {
    if (recorder.recording) {
        recorder.layerOp(com.reverie.paint.model.RecordingEvents.L_CLIPPED, i, if (clipped) "1" else "0")
    }
    runCore(after = ::notifyLayerChanged) {
        ReverieCoreBridge.setLayerClipped(i, clipped)
    }
}

internal fun PaintViewModel.flipLayerHorizontal(i: Int) {
    if (recorder.recording) {
        recorder.layerOp(com.reverie.paint.model.RecordingEvents.L_FLIP_H, i)
    }
    runCore(after = ::notifyLayerChanged) {
        ReverieCoreBridge.flipLayerHorizontal(i)
    }
}

internal fun PaintViewModel.flipLayerVertical(i: Int) {
    if (recorder.recording) {
        recorder.layerOp(com.reverie.paint.model.RecordingEvents.L_FLIP_V, i)
    }
    runCore(after = ::notifyLayerChanged) {
        ReverieCoreBridge.flipLayerVertical(i)
    }
}

internal fun PaintViewModel.stampVisibleLayers() {
    if (recorder.recording) {
        recorder.layerOp(com.reverie.paint.model.RecordingEvents.L_STAMP)
    }
    runCore(after = ::notifyLayerChanged) {
        ReverieCoreBridge.stampVisibleLayers()
    }
}

internal fun PaintViewModel.setBackgroundColor(
    color: Int,
    commit: Boolean = true,
) {
    if (commit && recorder.recording) {
        recorder.layerOp(com.reverie.paint.model.RecordingEvents.L_SET_BG, 0, color.toString())
    }
    if (commit) {
        runCore(after = ::notifyLayerChanged) {
            ReverieCoreBridge.setBackgroundColor(color, true)
        }
    } else {
        runCore {
            ReverieCoreBridge.setBackgroundColor(color, false)
            scheduleRender(immediate = true)
        }
    }
}

internal fun PaintViewModel.moveLayer(
    from: Int,
    to: Int,
) {
    if (recorder.recording) {
        recorder.layerOp(com.reverie.paint.model.RecordingEvents.L_MOVE, from, to.toString())
    }
    runCore(after = ::notifyLayerChanged) {
        ReverieCoreBridge.moveLayer(from, to)
    }
}

internal fun PaintViewModel.moveLayerAbove(
    from: Int,
    above: Int,
) {
    if (recorder.recording) {
        recorder.layerOp(com.reverie.paint.model.RecordingEvents.L_MOVE_ABOVE, from, above.toString())
    }
    runCore(after = ::notifyLayerChanged) {
        ReverieCoreBridge.moveLayerAbove(from, above)
    }
}

internal fun PaintViewModel.moveLayerToGroup(
    from: Int,
    group: Int,
) {
    if (recorder.recording) {
        recorder.layerOp(com.reverie.paint.model.RecordingEvents.L_MOVE_TO_GROUP, from, group.toString())
    }
    runCore(after = ::notifyLayerChanged) {
        ReverieCoreBridge.moveLayerToGroup(from, group)
    }
}

internal fun PaintViewModel.moveLayerRelative(
    from: Int,
    target: Int,
    placeAbove: Boolean,
) {
    if (recorder.recording) {
        recorder.layerOp(
            com.reverie.paint.model.RecordingEvents.L_MOVE_RELATIVE,
            from,
            "$target:${if (placeAbove) 1 else 0}",
        )
    }
    runCore(after = ::notifyLayerChanged) {
        ReverieCoreBridge.moveLayerRelative(from, target, placeAbove)
    }
}

internal fun PaintViewModel.moveLayerUp(i: Int) {
    if (recorder.recording) {
        recorder.layerOp(com.reverie.paint.model.RecordingEvents.L_MOVE_UP, i)
    }
    runCore(after = ::notifyLayerChanged) {
        ReverieCoreBridge.moveLayerUp(i)
    }
}

internal fun PaintViewModel.moveLayerDown(i: Int) {
    if (recorder.recording) {
        recorder.layerOp(com.reverie.paint.model.RecordingEvents.L_MOVE_DOWN, i)
    }
    runCore(after = ::notifyLayerChanged) {
        ReverieCoreBridge.moveLayerDown(i)
    }
}

internal fun PaintViewModel.moveLayerOut(i: Int) {
    if (recorder.recording) {
        recorder.layerOp(com.reverie.paint.model.RecordingEvents.L_MOVE_OUT, i)
    }
    runCore(after = ::notifyLayerChanged) {
        ReverieCoreBridge.moveLayerOut(i)
    }
}

internal fun PaintViewModel.addMaskToLayer(
    layerIndex: Int,
    maskType: Int,
) {
    if (recorder.recording) {
        recorder.layerOp(com.reverie.paint.model.RecordingEvents.L_ADD_MASK, layerIndex, maskType.toString())
    }
    runCore(after = ::notifyLayerChanged) {
        ReverieCoreBridge.addMaskToLayer(layerIndex, maskType)
    }
}

internal fun PaintViewModel.removeMask(layerIndex: Int) {
    if (recorder.recording) {
        recorder.layerOp(com.reverie.paint.model.RecordingEvents.L_REMOVE_MASK, layerIndex)
    }
    runCore(after = ::notifyLayerChanged) {
        ReverieCoreBridge.removeMask(layerIndex)
    }
}

internal fun PaintViewModel.rasterizeLayer(i: Int) {
    if (recorder.recording) {
        recorder.layerOp(com.reverie.paint.model.RecordingEvents.L_RASTERIZE, i)
    }
    runCore(after = ::notifyLayerChanged) {
        ReverieCoreBridge.rasterizeLayer(i)
    }
}

internal fun PaintViewModel.flattenGroup(i: Int) {
    if (recorder.recording) {
        recorder.layerOp(com.reverie.paint.model.RecordingEvents.L_FLATTEN_GROUP, i)
    }
    runCore(after = ::notifyLayerChanged) {
        ReverieCoreBridge.flattenGroup(i)
    }
}

internal fun PaintViewModel.setGroupPassThrough(
    i: Int,
    passThrough: Boolean,
) {
    if (recorder.recording) {
        recorder.layerOp(com.reverie.paint.model.RecordingEvents.L_PASS_THROUGH, i, if (passThrough) "1" else "0")
    }
    runCore(after = ::notifyLayerChanged) {
        ReverieCoreBridge.setGroupPassThrough(i, passThrough)
    }
}

internal fun PaintViewModel.groupPassThrough(i: Int) = ReverieCoreBridge.groupPassThrough(i)

internal fun PaintViewModel.mergeDown(i: Int) {
    if (recorder.recording) {
        recorder.layerOp(com.reverie.paint.model.RecordingEvents.L_MERGE_DOWN, i)
    }
    runCore(after = ::notifyLayerChanged) {
        ReverieCoreBridge.mergeDown(i)
    }
}

internal fun PaintViewModel.soloLayer(i: Int) {
    if (recorder.recording) {
        recorder.layerOp(com.reverie.paint.model.RecordingEvents.L_SOLO, i)
    }
    runCore(after = ::notifyLayerChanged) {
        ReverieCoreBridge.soloLayer(i)
    }
}

internal fun PaintViewModel.layerSoloed(i: Int) = ReverieCoreBridge.layerSoloed(i)

/** 是否处于独显模式（任意层被独显） */
val PaintViewModel.soloActive: Boolean
    get() = layers.any { layerSoloed(it.index) }

/** 独显浮窗：常规 ↔ 取消所有效果 切换 */
internal fun PaintViewModel.toggleSoloRawMode() {
    runCore(after = ::notifyLayerChanged) {
        ReverieCoreBridge.toggleLayerSoloRawMode()
    }
}

// ---- Multi-select (right-swipe in the layer panel) ----
// State lives in PaintViewModel (selectedLayerIndices); these are helpers.

internal fun PaintViewModel.toggleLayerSelection(index: Int) {
    selectedLayerIndices =
        if (index in selectedLayerIndices) {
            selectedLayerIndices - index
        } else {
            selectedLayerIndices + index
        }
    android.util.Log.d("ReverieLq", "toggle($index) -> $selectedLayerIndices")
}

internal fun PaintViewModel.clearLayerSelection() {
    selectedLayerIndices = emptySet()
}

/** 独显模式下选中其他图层时自动取消独显 (FolioLayers 行为) */
internal fun PaintViewModel.cancelSoloIfSwitchingLayer() {
    val soloed = layers.firstOrNull { layerSoloed(it.index) } ?: return
    soloLayer(soloed.index)
}

internal fun PaintViewModel.applyFilter(
    i: Int,
    filterId: Int,
) {
    if (recorder.recording) {
        recorder.layerOp(com.reverie.paint.model.RecordingEvents.L_APPLY_FILTER, i, filterId.toString())
    }
    runCore(after = ::notifyLayerChanged) {
        ReverieCoreBridge.applyFilter(i, filterId)
    }
}

internal fun PaintViewModel.selectionFromLayer(i: Int) {
    if (recorder.recording) {
        recorder.toolOp(com.reverie.paint.model.RecordingEvents.T_SELECT_ALL) { it.u16(i.coerceIn(0, 65535)) }
    }
    runCore(after = ::notifyLayerChanged) {
        ReverieCoreBridge.selectionFromLayer(i)
    }
}

internal fun PaintViewModel.clearSelection() {
    runCore(after = ::notifyLayerChanged) {
        ReverieCoreBridge.clearSelection()
    }
}

internal fun PaintViewModel.addLayerWithType(
    name: String = "",
    type: Int = 0,
    fillColor: Int = 0xFFFFFFFF.toInt(),
) {
    if (recorder.recording) {
        recorder.layerOp(
            com.reverie.paint.model.RecordingEvents.L_ADD_LAYER_TYPE,
            0,
            "$name|$type|$fillColor",
        )
    }
    runCore(after = ::notifyLayerChanged) {
        ReverieCoreBridge.addLayerWithType(name, type, fillColor)
    }
}

internal fun PaintViewModel.addFillLayer(colorHex: String = brushColor) {
    val colorInt = try {
        android.graphics.Color.parseColor(colorHex)
    } catch (_: Exception) {
        0xFFFFFFFF.toInt()
    }
    if (recorder.recording) {
        recorder.layerOp(
            com.reverie.paint.model.RecordingEvents.L_ADD_LAYER_TYPE,
            0,
            "填充图层|0|$colorInt",
        )
    }
    runCore(after = {
        notifyLayerChanged()
        floodFill(1f, 1f, tolerance = 100, sampleMerged = false)
    }) {
        ReverieCoreBridge.addLayerWithType("填充图层", 0, colorInt)
    }
}

internal fun PaintViewModel.addFilterLayer(onOpenFilters: (Int) -> Unit) {
    runCore(after = {
        notifyLayerChanged()
        val cur = currentLayerIndex
        if (cur >= 0) {
            onOpenFilters(cur)
        }
    }) {
        val newIdx = ReverieCoreBridge.stampVisibleLayers()
        if (newIdx >= 0) {
            ReverieCoreBridge.setLayerName(newIdx, "滤镜图层")
            ReverieCoreBridge.setCurrentLayer(newIdx)
        }
    }
}
