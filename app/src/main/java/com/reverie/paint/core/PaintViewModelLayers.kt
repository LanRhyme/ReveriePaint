/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

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
        val b = layerThumbStates[layerIndex]
        if (b != null && !b.isRecycled) return b
    }
    val b = layerThumbByName[layerName]
    return if (b != null && !b.isRecycled) b else null
}

internal fun PaintViewModel.clearLayerThumbs() {
    layerThumbStates.clear()
    layerThumbIndexName.clear()
    layerThumbByName.clear()
}

private var thumbRefreshJob: Job? = null
@Volatile
private var isRefreshingThumbs = false

/** Thumbnail refresh debounce: thumbs are only visible in the layer panel,
 *  which is never open while actively painting, so we skip refreshes when
 *  the panel is closed, and render on-demand when the panel opens. */
private const val THUMB_REFRESH_DEBOUNCE_MS = 1_000L

/** 引擎忙时的延后轮询间隔与轮询次数上限 (最多等 3 秒) */
private const val THUMB_DEFER_POLL_MS = 200L

private const val THUMB_DEFER_MAX_TICKS = 15

/**
 * 渲染线程是否正忙。缩略图必须占用同一个渲染线程逐层渲染, 抢在笔画前面会让
 * 思路: 重活推迟到交互停下来之后
 */
internal fun PaintViewModel.engineBusy(): Boolean =
    // pendingCoreOps 在笔画批次排队与每次引擎操作期间都 >0 (比 strokeBatchQueued
    // 覆盖面更广), hQueued 则覆盖渲染线程消息队列里还压着的任务
    pendingCoreOps.get() > 0 || hQueued() > 0

/** (Re)generate layer thumbnails on the render thread. Debounced; triggers
 *  inside the window keep postponing the refresh until the user pauses. */
internal fun PaintViewModel.refreshLayerThumbs(force: Boolean = false) {
    if (!force && !layerPanelOpen) {
        return
    }
    // 正在落笔/液化/滤镜: 推迟到引擎空闲再刷, 别和笔画抢渲染线程
    if (!force && engineBusy()) {
        val vm = this
        thumbRefreshJob?.cancel()
        thumbRefreshJob =
            viewModelScope.launch {
                repeat(THUMB_DEFER_MAX_TICKS) {
                    delay(THUMB_DEFER_POLL_MS)
                    if (!vm.layerPanelOpen) return@launch
                    if (!vm.engineBusy()) {
                        lastThumbRefreshNs = System.nanoTime()
                        vm.doRefreshLayerThumbs()
                        return@launch
                    }
                }
                // 一直没闲下来也要刷一次, 否则面板会长期停留在旧缩略图
                if (vm.layerPanelOpen) {
                    lastThumbRefreshNs = System.nanoTime()
                    vm.doRefreshLayerThumbs()
                }
            }
        return
    }
    val now = System.nanoTime()
    if (!force && now - lastThumbRefreshNs < THUMB_REFRESH_DEBOUNCE_MS * 1_000_000L) {
        thumbRefreshJob?.cancel()
        thumbRefreshJob =
            viewModelScope.launch {
                delay(THUMB_REFRESH_DEBOUNCE_MS)
                lastThumbRefreshNs = System.nanoTime()
                if (layerPanelOpen) {
                    doRefreshLayerThumbs()
                }
            }
        return
    }
    lastThumbRefreshNs = now
    doRefreshLayerThumbs()
}

private fun PaintViewModel.doRefreshLayerThumbs() {
    if (isRefreshingThumbs) return
    isRefreshingThumbs = true
    runCore(render = false, after = { isRefreshingThumbs = false }) {
        try {
            val n = ReverieCoreBridge.layerCount()
            if (n <= 0) return@runCore
            for (i in 0 until n) {
                // Rule 3 (Double-buffering): always allocate an independent back-buffer Bitmap (12KB)
                // so the background engine thread never writes into a Bitmap actively drawn by UI/GPU
                val bmp = Bitmap.createBitmap(56, 56, Bitmap.Config.ARGB_8888)
                if (ReverieCoreBridge.renderLayerThumb(i, bmp)) {
                    val idx = i
                    val name = ReverieCoreBridge.layerName(idx)
                    mainHandler.post {
                        layerThumbStates[idx] = bmp
                        layerThumbIndexName[idx] = name
                        layerThumbByName[name] = bmp
                    }
                } else {
                    bmp.recycle()
                }
            }
        } catch (t: Throwable) {
            android.util.Log.e("PaintViewModel", "doRefreshLayerThumbs error", t)
        }
    }
}

internal fun PaintViewModel.notifyLayerChanged(
    forceThumbs: Boolean = true,
    immediateRender: Boolean = true,
    /**
     * 本次变更是否改动了图层像素。
     *
     * 只有填充 / 滤镜 / 导入这类"直接改内容但不走 touchEnd"的路径需要传 true,
     * 用于触发洋葱皮缓存失效。改名、显隐、排序、增删图层都不碰像素, 保持默认
     * false —— 见函数末尾的说明。
     */
    pixelChanged: Boolean = false,
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
    // 动画时间轴: 图层内容变了, 帧块里的画面缩略图也要跟着重取。
    // 非动画文档下 anim.enabled 为 false, 这里等同空操作。
    if (anim.enabled) {
        anim.thumbRevision++
        syncAnimationFromNativeAfter()
    }
    // 洋葱皮缓存失效: 只有"真的改了像素"的路径才传 pixelChanged=true。
    //
    // notifyLayerChanged 是图层操作的公共出口 (改名 / 显隐 / 排序 / 增删 /
    // 滤镜 / 填充 / 导入 / 撤销重做), 其中改名、显隐、排序都不碰像素,
    // 对它们丢缓存纯属浪费 —— 缓存被丢弃意味着下一次渲染要把前后 N 帧全部
    // 重新 writeFrameToDevice + bitBlt, 是逐帧作画里最贵的一步。
    // 默认 false, 由改像素的调用方显式打开。
    if (anim.enabled && pixelChanged) {
        runCore(render = false) { ReverieCoreBridge.flushOnionSkinCaches() }
    }
    scheduleRender(immediate = immediateRender)
}

internal fun PaintViewModel.generateDefaultLayerName(): String {
    val isZh = LanguageManager.isChinese()
    val prefix = if (isZh) "颜料图层" else "Paint Layer"
    val existing = layers.map { it.name }
    var idx = 1
    val regex = Regex("""^(?:颜料图层|Paint Layer)\s*(\d+)$""", RegexOption.IGNORE_CASE)
    for (name in existing) {
        val match = regex.find(name.trim())
        if (match != null) {
            val num = match.groupValues[1].toIntOrNull() ?: 0
            if (num >= idx) idx = num + 1
        }
    }
    return "$prefix $idx"
}

internal fun PaintViewModel.generateDefaultFillLayerName(): String {
    val isZh = LanguageManager.isChinese()
    val prefix = if (isZh) "填充图层" else "Fill Layer"
    val existing = layers.map { it.name }
    var idx = 1
    val regex = Regex("""^(?:填充图层|Fill Layer)\s*(\d+)?$""", RegexOption.IGNORE_CASE)
    var hasPlain = false
    for (name in existing) {
        val match = regex.find(name.trim())
        if (match != null) {
            val numStr = match.groupValues[1]
            if (numStr.isEmpty()) {
                hasPlain = true
            } else {
                val num = numStr.toIntOrNull() ?: 0
                if (num >= idx) idx = num + 1
            }
        }
    }
    return if (!hasPlain && idx == 1) prefix else "$prefix $idx"
}

internal fun PaintViewModel.generateDefaultFilterLayerName(): String {
    val isZh = LanguageManager.isChinese()
    val prefix = if (isZh) "滤镜图层" else "Filter Layer"
    val existing = layers.map { it.name }
    var idx = 1
    val regex = Regex("""^(?:滤镜图层|Filter Layer)\s*(\d+)?$""", RegexOption.IGNORE_CASE)
    var hasPlain = false
    for (name in existing) {
        val match = regex.find(name.trim())
        if (match != null) {
            val numStr = match.groupValues[1]
            if (numStr.isEmpty()) {
                hasPlain = true
            } else {
                val num = numStr.toIntOrNull() ?: 0
                if (num >= idx) idx = num + 1
            }
        }
    }
    return if (!hasPlain && idx == 1) prefix else "$prefix $idx"
}

internal fun PaintViewModel.generateDefaultGroupName(): String {
    val isZh = LanguageManager.isChinese()
    val prefix = if (isZh) "图层组" else "Group"
    val existing = layers.map { it.name }
    var idx = 1
    val regex = Regex("""^(?:图层组|Group)\s*(\d+)?$""", RegexOption.IGNORE_CASE)
    var hasPlain = false
    for (name in existing) {
        val match = regex.find(name.trim())
        if (match != null) {
            val numStr = match.groupValues[1]
            if (numStr.isEmpty()) {
                hasPlain = true
            } else {
                val num = numStr.toIntOrNull() ?: 0
                if (num >= idx) idx = num + 1
            }
        }
    }
    return if (!hasPlain && idx == 1) prefix else "$prefix $idx"
}

internal fun PaintViewModel.addLayer() {
    if (recorder.recording) {
        recorder.layerOp(com.reverie.paint.model.RecordingEvents.L_ADD)
    }
    val defaultName = generateDefaultLayerName()
    runCore(after = ::notifyLayerChanged) {
        ReverieCoreBridge.addLayer(defaultName)
    }
}

internal fun PaintViewModel.importImageToNewLayer(
    bitmap: Bitmap,
    layerName: String = "",
    onComplete: () -> Unit = {},
) {
    if (recorder.recording) {
        recorder.layerOp(com.reverie.paint.model.RecordingEvents.L_ADD)
    }
    val defaultImportName = if (LanguageManager.isChinese()) "导入图片" else "Imported Image"
    val finalName = layerName.ifBlank { defaultImportName }
    runCore(
        after = {
            notifyLayerChanged(pixelChanged = true)
            onComplete()
        },
    ) {
        try {
            ReverieCoreBridge.addLayer(finalName)
            val placement = ImageImportHelper.calculateFitPlacement(
                docW = coreW,
                docH = coreH,
                imgW = bitmap.width,
                imgH = bitmap.height,
                maxRatio = 0.8f,
            )
            val scaledBmp = if (placement.targetW != bitmap.width || placement.targetH != bitmap.height) {
                Bitmap.createScaledBitmap(bitmap, placement.targetW, placement.targetH, true)
            } else {
                bitmap
            }
            val stampBmp = ImageImportHelper.swapRedAndBlueForStamp(scaledBmp)
            try {
                ReverieCoreBridge.stampBitmap(placement.x, placement.y, stampBmp)
            } finally {
                stampBmp.recycle()
            }
            if (scaledBmp != bitmap) {
                scaledBmp.recycle()
            }
        } finally {
            if (!bitmap.isRecycled) {
                bitmap.recycle()
            }
        }
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
    // Record the TARGET visibility so replay sets it directly (toggling the
    // live value instead cascades wrong once any earlier op drifts indices).
    val target = !ReverieCoreBridge.layerVisible(i)
    if (recorder.recording) {
        recorder.layerOp(com.reverie.paint.model.RecordingEvents.L_VISIBLE, i, if (target) "1" else "0")
    }
    runCore(after = ::notifyLayerChanged) {
        ReverieCoreBridge.setLayerVisible(i, target)
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
    val groupName = generateDefaultGroupName()
    runCore(after = {
        notifyLayerChanged()
        clearLayerSelection()
    }) {
        val newGroupIndex = ReverieCoreBridge.addGroupLayer(groupName)
        if (selected.isNotEmpty() && newGroupIndex >= 0) {
            val groupIdx = ReverieCoreBridge.currentLayerIndex()
            for (idx in selected) {
                val actualIdx = if (idx >= newGroupIndex) idx + 1 else idx
                if (actualIdx != groupIdx && actualIdx > 0) {
                    if (recorder.recording) {
                        // 组员移入必须入事件流, 否则回放只建空组、后续 index 全错位
                        recorder.layerOp(
                            com.reverie.paint.model.RecordingEvents.L_MOVE_TO_GROUP,
                            actualIdx,
                            groupIdx.toString(),
                        )
                    }
                    ReverieCoreBridge.moveLayerToGroup(actualIdx, groupIdx)
                }
            }
        }
    }
}

internal fun PaintViewModel.copyLayer(i: Int) {
    if (isCopyingLayer) return
    isCopyingLayer = true
    if (recorder.recording) {
        recorder.layerOp(com.reverie.paint.model.RecordingEvents.L_COPY, i)
    }
    val isZh = LanguageManager.isChinese()
    runCore(after = {
        isCopyingLayer = false
        notifyLayerChanged()
    }) {
        val newIdx = ReverieCoreBridge.copyLayer(i)
        if (!isZh && newIdx >= 0 && newIdx < ReverieCoreBridge.layerCount()) {
            val createdName = ReverieCoreBridge.layerName(newIdx)
            if (createdName.contains(" 副本")) {
                val engName = createdName.replace(" 副本", " Copy")
                ReverieCoreBridge.setLayerName(newIdx, engName)
            }
        }
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

internal fun PaintViewModel.flipCanvasHorizontal() {
    if (recorder.recording) {
        recorder.layerOp(com.reverie.paint.model.RecordingEvents.L_CANVAS_FLIP_H)
    }
    runCore(after = ::notifyLayerChanged) {
        ReverieCoreBridge.flipCanvasHorizontal()
    }
}

internal fun PaintViewModel.flipCanvasVertical() {
    if (recorder.recording) {
        recorder.layerOp(com.reverie.paint.model.RecordingEvents.L_CANVAS_FLIP_V)
    }
    runCore(after = ::notifyLayerChanged) {
        ReverieCoreBridge.flipCanvasVertical()
    }
}

/** 用当前前景色填充整层 (区别于 floodFill 的角点连通区填充)。 */
internal fun PaintViewModel.fillLayerForeground(i: Int) {
    if (recorder.recording) {
        recorder.layerOp(com.reverie.paint.model.RecordingEvents.L_FILL_LAYER, i)
    }
    runCore(after = ::notifyLayerChanged) {
        ReverieCoreBridge.fillLayer(i)
    }
}

internal fun PaintViewModel.stampVisibleLayers() {
    if (recorder.recording) {
        recorder.layerOp(com.reverie.paint.model.RecordingEvents.L_STAMP)
    }
    val isZh = LanguageManager.isChinese()
    runCore(after = ::notifyLayerChanged) {
        val newIdx = ReverieCoreBridge.stampVisibleLayers()
        if (!isZh && newIdx >= 0) {
            ReverieCoreBridge.setLayerName(newIdx, "Stamp Visible Layers")
        }
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

internal fun PaintViewModel.moveLayersToGroup(
    fromIndices: List<Int>,
    group: Int,
) {
    if (fromIndices.isEmpty()) return
    val selectedIds = layers.filter { it.index in fromIndices }.map { it.id }.toSet()
    val selectedNames = layers.filter { it.index in fromIndices }.map { it.name }.toSet()
    if (recorder.recording) {
        for (from in fromIndices) {
            recorder.layerOp(com.reverie.paint.model.RecordingEvents.L_MOVE_TO_GROUP, from, group.toString())
        }
    }
    runCore(after = {
        notifyLayerChanged()
        selectedLayerIndices = layers.filter { it.id in selectedIds || it.name in selectedNames }.map { it.index }.toSet()
    }) {
        ReverieCoreBridge.moveLayersToGroup(fromIndices.toIntArray(), group)
    }
}

internal fun PaintViewModel.moveLayersRelative(
    fromIndices: List<Int>,
    target: Int,
    placeAbove: Boolean,
) {
    if (fromIndices.isEmpty()) return
    val selectedIds = layers.filter { it.index in fromIndices }.map { it.id }.toSet()
    val selectedNames = layers.filter { it.index in fromIndices }.map { it.name }.toSet()
    if (recorder.recording) {
        for (from in fromIndices) {
            recorder.layerOp(
                com.reverie.paint.model.RecordingEvents.L_MOVE_RELATIVE,
                from,
                "$target:${if (placeAbove) 1 else 0}",
            )
        }
    }
    runCore(after = {
        notifyLayerChanged()
        selectedLayerIndices = layers.filter { it.id in selectedIds || it.name in selectedNames }.map { it.index }.toSet()
    }) {
        ReverieCoreBridge.moveLayersRelative(fromIndices.toIntArray(), target, placeAbove)
    }
}

internal fun PaintViewModel.moveLayerRelative(
    from: Int,
    target: Int,
    placeAbove: Boolean,
) {
    moveLayersRelative(listOf(from), target, placeAbove)
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
    val fillLayerName = generateDefaultFillLayerName()
    if (recorder.recording) {
        recorder.layerOp(
            com.reverie.paint.model.RecordingEvents.L_ADD_LAYER_TYPE,
            0,
            "$fillLayerName|0|$colorInt",
        )
    }
    // 回滚至稳定行为: type=0 预填色颜料层 + floodFill 补刀 (generator 填充层暂缓)
    runCore(
        after = {
            notifyLayerChanged()
            floodFill(1f, 1f, tolerance = 100, sampleMerged = false)
        },
    ) {
        ReverieCoreBridge.addLayerWithType(fillLayerName, 0, colorInt)
    }
}

internal fun PaintViewModel.addFilterLayer(onOpenFilters: (Int) -> Unit) {
    if (recorder.recording) {
        // 与 stampVisibleLayers 包装一致: 盖印可见层作为滤镜底图层
        recorder.layerOp(com.reverie.paint.model.RecordingEvents.L_STAMP)
    }
    val filterLayerName = generateDefaultFilterLayerName()
    runCore(after = {
        notifyLayerChanged()
        val cur = currentLayerIndex
        if (cur >= 0) {
            renameLayer(cur, filterLayerName)
            onOpenFilters(cur)
        }
    }) {
        ReverieCoreBridge.stampVisibleLayers()
    }
}
