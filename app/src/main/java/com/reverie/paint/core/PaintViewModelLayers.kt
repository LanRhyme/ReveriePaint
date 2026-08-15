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

/** (Re)generate layer thumbnails on the render thread. Throttled; a burst
 * of triggers inside the window (rapid consecutive undo/redo) is debounced
 * and merged into a single refresh instead of being dropped. */
internal fun PaintViewModel.refreshLayerThumbs(force: Boolean = false) {
    val now = System.nanoTime()
    if (!force && now - lastThumbRefreshNs < 400_000_000L) {
        thumbRefreshJob?.cancel()
        thumbRefreshJob =
            viewModelScope.launch {
                delay(400)
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
    runCore(after = ::notifyLayerChanged) {
        // empty name -> C++ generates 颜料图层 N
        ReverieCoreBridge.addLayer("")
    }
}

internal fun PaintViewModel.removeLayer() {
    removeLayer(currentLayerIndex)
}

internal fun PaintViewModel.removeLayer(index: Int) {
    runCore(after = ::notifyLayerChanged) {
        ReverieCoreBridge.removeLayer(index)
    }
}

internal fun PaintViewModel.setCurrentLayer(i: Int) {
    runCore(after = ::notifyLayerChanged) {
        ReverieCoreBridge.setCurrentLayer(i)
    }
}

internal fun PaintViewModel.layerBlendMode(i: Int) = ReverieCoreBridge.layerBlendMode(i)

internal fun PaintViewModel.setLayerBlendMode(
    i: Int,
    opId: String,
) {
    runCore(after = ::notifyLayerChanged) {
        ReverieCoreBridge.setLayerBlendMode(i, opId)
    }
}

internal fun PaintViewModel.toggleLayerVisible(i: Int) {
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
    runCore(after = ::notifyLayerChanged) {
        ReverieCoreBridge.addGroupLayer("")
    }
}

internal fun PaintViewModel.copyLayer(i: Int) {
    runCore(after = ::notifyLayerChanged) {
        ReverieCoreBridge.copyLayer(i)
    }
}

internal fun PaintViewModel.clearLayer(i: Int) {
    runCore(after = ::notifyLayerChanged) {
        ReverieCoreBridge.clearLayer(i)
    }
}

internal fun PaintViewModel.renameLayer(
    i: Int,
    name: String,
) {
    if (name.isBlank()) return
    runCore(after = ::notifyLayerChanged) {
        ReverieCoreBridge.setLayerName(i, name.trim())
    }
}

internal fun PaintViewModel.layerOpacity(i: Int) = ReverieCoreBridge.layerOpacity(i)

internal fun PaintViewModel.setLayerOpacity(
    i: Int,
    v: Double,
) {
    runCore(after = ::notifyLayerChanged) {
        ReverieCoreBridge.setLayerOpacity(i, v)
    }
}

internal fun PaintViewModel.layerLocked(i: Int) = ReverieCoreBridge.layerLocked(i)

internal fun PaintViewModel.setLayerLocked(
    i: Int,
    locked: Boolean,
) {
    runCore(after = ::notifyLayerChanged) {
        ReverieCoreBridge.setLayerLocked(i, locked)
    }
}

internal fun PaintViewModel.layerAlphaLocked(i: Int) = ReverieCoreBridge.layerAlphaLocked(i)

internal fun PaintViewModel.setLayerAlphaLocked(
    i: Int,
    locked: Boolean,
) {
    runCore(after = ::notifyLayerChanged) {
        ReverieCoreBridge.setLayerAlphaLocked(i, locked)
    }
}

internal fun PaintViewModel.layerColorLabel(i: Int) = ReverieCoreBridge.layerColorLabel(i)

internal fun PaintViewModel.setLayerColorLabel(
    i: Int,
    label: Int,
) {
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
    runCore(after = ::notifyLayerChanged) {
        ReverieCoreBridge.setLayerClipped(i, clipped)
    }
}

internal fun PaintViewModel.flipLayerHorizontal(i: Int) {
    runCore(after = ::notifyLayerChanged) {
        ReverieCoreBridge.flipLayerHorizontal(i)
    }
}

internal fun PaintViewModel.flipLayerVertical(i: Int) {
    runCore(after = ::notifyLayerChanged) {
        ReverieCoreBridge.flipLayerVertical(i)
    }
}

internal fun PaintViewModel.stampVisibleLayers() {
    runCore(after = ::notifyLayerChanged) {
        ReverieCoreBridge.stampVisibleLayers()
    }
}

internal fun PaintViewModel.setBackgroundColor(
    color: Int,
    commit: Boolean = true,
) {
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
    runCore(after = ::notifyLayerChanged) {
        ReverieCoreBridge.moveLayer(from, to)
    }
}

internal fun PaintViewModel.moveLayerAbove(
    from: Int,
    above: Int,
) {
    runCore(after = ::notifyLayerChanged) {
        ReverieCoreBridge.moveLayerAbove(from, above)
    }
}

internal fun PaintViewModel.moveLayerToGroup(
    from: Int,
    group: Int,
) {
    runCore(after = ::notifyLayerChanged) {
        ReverieCoreBridge.moveLayerToGroup(from, group)
    }
}

internal fun PaintViewModel.moveLayerRelative(
    from: Int,
    target: Int,
    placeAbove: Boolean,
) {
    runCore(after = ::notifyLayerChanged) {
        ReverieCoreBridge.moveLayerRelative(from, target, placeAbove)
    }
}

internal fun PaintViewModel.moveLayerUp(i: Int) {
    runCore(after = ::notifyLayerChanged) {
        ReverieCoreBridge.moveLayerUp(i)
    }
}

internal fun PaintViewModel.moveLayerDown(i: Int) {
    runCore(after = ::notifyLayerChanged) {
        ReverieCoreBridge.moveLayerDown(i)
    }
}

internal fun PaintViewModel.moveLayerOut(i: Int) {
    runCore(after = ::notifyLayerChanged) {
        ReverieCoreBridge.moveLayerOut(i)
    }
}

internal fun PaintViewModel.addMaskToLayer(
    layerIndex: Int,
    maskType: Int,
) {
    runCore(after = ::notifyLayerChanged) {
        ReverieCoreBridge.addMaskToLayer(layerIndex, maskType)
    }
}

internal fun PaintViewModel.removeMask(layerIndex: Int) {
    runCore(after = ::notifyLayerChanged) {
        ReverieCoreBridge.removeMask(layerIndex)
    }
}

internal fun PaintViewModel.rasterizeLayer(i: Int) {
    runCore(after = ::notifyLayerChanged) {
        ReverieCoreBridge.rasterizeLayer(i)
    }
}

internal fun PaintViewModel.flattenGroup(i: Int) {
    runCore(after = ::notifyLayerChanged) {
        ReverieCoreBridge.flattenGroup(i)
    }
}

internal fun PaintViewModel.setGroupPassThrough(
    i: Int,
    passThrough: Boolean,
) {
    runCore(after = ::notifyLayerChanged) {
        ReverieCoreBridge.setGroupPassThrough(i, passThrough)
    }
}

internal fun PaintViewModel.groupPassThrough(i: Int) = ReverieCoreBridge.groupPassThrough(i)

internal fun PaintViewModel.mergeDown(i: Int) {
    runCore(after = ::notifyLayerChanged) {
        ReverieCoreBridge.mergeDown(i)
    }
}

internal fun PaintViewModel.soloLayer(i: Int) {
    runCore(after = ::notifyLayerChanged) {
        ReverieCoreBridge.soloLayer(i)
    }
}

internal fun PaintViewModel.layerSoloed(i: Int) = ReverieCoreBridge.layerSoloed(i)

// ---- Multi-select (right-swipe in the layer panel) ----
// State lives in PaintViewModel (selectedLayerIndices); these are helpers.

internal fun PaintViewModel.toggleLayerSelection(index: Int) {
    selectedLayerIndices =
        if (index in selectedLayerIndices) {
            selectedLayerIndices - index
        } else {
            selectedLayerIndices + index
        }
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
    runCore(after = ::notifyLayerChanged) {
        ReverieCoreBridge.applyFilter(i, filterId)
    }
}

internal fun PaintViewModel.selectionFromLayer(i: Int) {
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
    runCore(after = ::notifyLayerChanged) {
        ReverieCoreBridge.addLayerWithType(name, type, fillColor)
    }
}
