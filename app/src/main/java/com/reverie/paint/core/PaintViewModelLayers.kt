/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import android.graphics.Bitmap
import com.reverie.paint.R
import com.reverie.paint.model.SettledTilePolicy
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

/** 图层缩略图现在是否真的看得见: 完整图层面板, 或常驻的快捷图层小窗。
 *  刷新门控用它而不是只用 layerPanelOpen —— 小窗开着时同样需要缩略图。 */
internal val PaintViewModel.layerThumbsVisible: Boolean
    get() = layerPanelOpen || quickLayerWindowOpen

internal fun PaintViewModel.clearLayerThumbs() {
    layerThumbStates.clear()
    layerThumbIndexName.clear()
    layerThumbByName.clear()
}

private var thumbRefreshJob: Job? = null
@Volatile
private var isRefreshingThumbs = false

/** 渲染中的刷新期间又来了请求: 收尾后补跑一次 (见 doRefreshLayerThumbs) */
@Volatile
private var thumbRefreshPending = false

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

internal fun PaintViewModel.findLayerAndAncestorIds(targetIndex: Int): Set<Long> {
    val list = layers
    val indices = SettledTilePolicy.findLayerAndAncestorIndices(
        targetIndex = targetIndex,
        layerCount = list.size,
        depth = { list[it].depth },
        isGroup = { list[it].isGroup },
    )
    return indices.mapNotNull { list.getOrNull(it)?.id }.toSet()
}

internal fun PaintViewModel.updatePendingThumbIds(onlyLayerIds: Set<Long>?) {
    if (onlyLayerIds == null) {
        pendingThumbRefreshAll = true
        pendingThumbLayerIds = null
    } else if (!pendingThumbRefreshAll) {
        if (pendingThumbLayerIds == null) pendingThumbLayerIds = mutableSetOf()
        pendingThumbLayerIds?.addAll(onlyLayerIds)
    }
}

internal fun PaintViewModel.consumePendingThumbIds(): Set<Long>? {
    if (pendingThumbRefreshAll) {
        pendingThumbRefreshAll = false
        pendingThumbLayerIds = null
        return null
    }
    val ids = pendingThumbLayerIds
    pendingThumbLayerIds = null
    return ids
}

/** (Re)generate layer thumbnails on the render thread. Debounced; triggers
 *  inside the window keep postponing the refresh until the user pauses. */
internal fun PaintViewModel.refreshLayerThumbs(
    force: Boolean = false,
    onlyLayerIds: Set<Long>? = null,
) {
    // 门控必须用 layerThumbsVisible (面板 dock 或快捷图层小窗任一可见),
    // 否则打开工程后只有小窗开着时缩略图不刷新 -> 小窗空白 (见 7705b483)。
    if (!force && !layerThumbsVisible) {
        return
    }
    updatePendingThumbIds(onlyLayerIds)
    // 正在落笔/液化/滤镜: 推迟到引擎空闲再刷, 别和笔画抢渲染线程
    if (!force && engineBusy()) {
        val vm = this
        thumbRefreshJob?.cancel()
        thumbRefreshJob =
            viewModelScope.launch {
                repeat(THUMB_DEFER_MAX_TICKS) {
                    delay(THUMB_DEFER_POLL_MS)
                    if (!vm.layerThumbsVisible) return@launch
                    if (!vm.engineBusy()) {
                        lastThumbRefreshNs = System.nanoTime()
                        vm.doRefreshLayerThumbs(vm.consumePendingThumbIds())
                        return@launch
                    }
                }
                // 一直没闲下来也要刷一次, 否则面板会长期停留在旧缩略图
                if (vm.layerThumbsVisible) {
                    lastThumbRefreshNs = System.nanoTime()
                    vm.doRefreshLayerThumbs(vm.consumePendingThumbIds())
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
                if (layerThumbsVisible) {
                    doRefreshLayerThumbs(consumePendingThumbIds())
                }
            }
        return
    }
    lastThumbRefreshNs = now
    doRefreshLayerThumbs(consumePendingThumbIds())
}

private fun PaintViewModel.doRefreshLayerThumbs(onlyLayerIds: Set<Long>? = null) {
    if (isRefreshingThumbs) {
        // 上一轮还在引擎线程上渲染: 直接 return 会把这次请求吞掉, 面板就停在
        // 旧/空缩略图上直到下一次有人触发 (典型场景: 打开工程时面板已开, 面板
        // 打开触发的刷新与 loadRevp 抢同一个引擎队列)。记一个待办, 等当前这轮
        // 收尾后补跑一次。渐进刷新 (onlyLayerIds) 的待办集合由 consume 侧合并。
        thumbRefreshPending = true
        return
    }
    isRefreshingThumbs = true
    runCore(render = false, after = {
        isRefreshingThumbs = false
        if (thumbRefreshPending) {
            thumbRefreshPending = false
            if (layerThumbsVisible) doRefreshLayerThumbs()
        }
    }) {
        try {
            val n = ReverieCoreBridge.layerCount()
            if (n <= 0) return@runCore
            for (i in 0 until n) {
                if (onlyLayerIds != null) {
                    val id = ReverieCoreBridge.layerId(i)
                    if (id !in onlyLayerIds) continue
                }
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
    /**
     * 本次变更是否真的改动了文档内容/结构。
     *
     * 纯**选中/切换**类操作 (点图层、播放中切层) 传 false: 不标 isModified、
     * 不置 hasPendingMajorOp。选中不改内容, 下一次真实变更会顺带把选中索引存档;
     * 若不区分, 每次点图层都会在 1s 内拉响自动保存 —— 大工程全量保存耗时可达
     * 十秒级并占住渲染线程, 是「点击图层卡顿」的直接来源。
     */
    contentChanged: Boolean = true,
) {
    if (contentChanged) {
        isModified = true
        hasPendingMajorOp = true
    }
    onPaintingActivity()
    syncLayersFromNative()
    // 分格镜像重扫: 分格会话中或已有分格/跨格容器时, 任何图层结构变化都可能让
    // 分格索引漂移, 从引擎重扫一次 (无分格时零开销, 见 PaintViewModelPanels.kt)。
    if (panelSessionActive() || panels.isNotEmpty() || panelCrossContainerIdx >= 0) {
        refreshPanels()
    }
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
    // 撤回条刻度同步: undo/redo 之外的历史变化 (图层结构操作 / undoLimit 修剪
    // 等经本函数出口的路径) 也可能改变 undo/redo 计数, 撤回条显示期间补一次
    // 镜像刷新 (2 个 O(1) JNI 整数读); 不可见时不调, 隐藏态由打开路径对齐。
    if (undoBarVisible) {
        refreshUndoCounts()
    }
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

internal fun PaintViewModel.generateDefaultStrokeLayerName(): String {
    val isZh = LanguageManager.isChinese()
    val prefix = if (isZh) "描边图层" else "Stroke Layer"
    val existing = layers.map { it.name }
    var idx = 1
    val regex = Regex("""^(?:描边图层|Stroke Layer)\s*(\d+)?$""", RegexOption.IGNORE_CASE)
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

private inline fun PaintViewModel.executeAddLayerSafely(
    wasInsidePanel: Boolean,
    action: () -> Unit,
) {
    action()
    val s = scanEnginePanels()
    if (s.rootIdx < 0) return
    val newIdx = ReverieCoreBridge.currentLayerIndex()
    if (newIdx < 0 || newIdx !in s.layers.indices) return

    if (!wasInsidePanel) {
        // 用户原先不在具体分格内: 严禁新层被意外卷入分格根组
        val rootDepth = s.layers[s.rootIdx].depth
        var isInsideRoot = false
        if (newIdx > s.rootIdx) {
            isInsideRoot = true
            for (i in s.rootIdx + 1..newIdx) {
                if (s.layers[i].depth <= rootDepth) {
                    isInsideRoot = false
                    break
                }
            }
        }
        if (isInsideRoot) {
            ReverieCoreBridge.moveLayerRelative(newIdx, s.rootIdx, true)
        }
    } else {
        // 用户原先在某个具体分格内: 就地保留在当前分格组, 确保边框层黑框压顶
        ensurePanelBordersOnTop(s)
    }
}

internal fun PaintViewModel.addLayer() {
    if (transientTransformSelectionPending || canUndoTransformSelection || canRedoTransformSelection) {
        discardTransientTransformSelection()
    }
    clearLayerSelection()
    if (recorder.recording) {
        recorder.layerOp(com.reverie.paint.model.RecordingEvents.L_ADD)
    }
    val defaultName = generateDefaultLayerName()
    Breadcrumbs.record("Layer", "Add layer: $defaultName")
    val pids = currentPaintingPanelIds()
    val wasInside = pids.isNotEmpty()
    if (wasInside) {
        collapsedPanelIds = collapsedPanelIds - pids
    }
    runCore(after = {
        clearLayerSelection()
        notifyLayerChanged()
    }) {
        executeAddLayerSafely(wasInside) {
            ReverieCoreBridge.addLayer(defaultName)
        }
    }
}

internal fun PaintViewModel.importImageToNewLayer(
    bitmap: Bitmap,
    layerName: String = "",
    onComplete: () -> Unit = {},
) {
    clearLayerSelection()
    if (recorder.recording) {
        recorder.layerOp(com.reverie.paint.model.RecordingEvents.L_ADD)
    }
    val defaultImportName = if (LanguageManager.isChinese()) "导入图片" else "Imported Image"
    val finalName = layerName.ifBlank { defaultImportName }
    val pids = currentPaintingPanelIds()
    val wasInside = pids.isNotEmpty()
    if (wasInside) {
        collapsedPanelIds = collapsedPanelIds - pids
    }
    runCore(
        after = {
            clearLayerSelection()
            notifyLayerChanged(pixelChanged = true)
            onComplete()
        },
    ) {
        try {
            executeAddLayerSafely(wasInside) {
                ReverieCoreBridge.addLayer(finalName)
            }
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
    // 引擎侧 `ReverieCore::removeLayer` 对「背景层 / 锁定图层 / 越界索引」是**静默 return**
    // (JNI 不返回值), 表现就是「点了删除、面板关了、图层还在」, 日志里连一行都没有 ——
    // 真机反馈里「删不掉图层」就是这么来的。这里先在镜像上挡一层并给出明确反馈,
    // 不再把注定被拒绝的请求发进引擎。
    if (index < 0) {
        showActionToast(R.string.toast_layer_delete_stale, R.drawable.ic_trash)
        return
    }
    if (index == 0) {
        showActionToast(R.string.toast_layer_delete_background, R.drawable.ic_trash)
        return
    }
    val target = layers.getOrNull(index)
    if (target == null) {
        showActionToast(R.string.toast_layer_delete_stale, R.drawable.ic_trash)
        return
    }
    if (target.locked) {
        showActionToast(R.string.toast_layer_delete_locked, R.drawable.ic_lock)
        return
    }
    if (transientTransformSelectionPending || canUndoTransformSelection || canRedoTransformSelection) {
        discardTransientTransformSelection()
    }
    clearLayerSelection()
    if (active3DLayers.containsKey(target.id)) {
        active3DLayers = active3DLayers - target.id
    }
    Breadcrumbs.record("Layer", "Remove layer: $index")
    if (recorder.recording) {
        recorder.layerOp(com.reverie.paint.model.RecordingEvents.L_REMOVE, index)
    }
    runCore(after = {
        clearLayerSelection()
        notifyLayerChanged()
    }) {
        ReverieCoreBridge.removeLayer(index)
    }
}

internal fun PaintViewModel.setCurrentLayer(i: Int) {
    Breadcrumbs.record("Layer", "Set current layer: $i")
    val prevIdx = currentLayerIndex
    val changed = prevIdx != i

    currentLayerIndex = i
    if (changed && (transientTransformSelectionPending || canUndoTransformSelection || canRedoTransformSelection)) {
        discardTransientTransformSelection()
    }
    // 选中索引会写进工程 (selectedLayerIndex), 所以记一笔"只差它没落盘": 不触发自动保存,
    // 但软件切后台时的静默保存会顺带存上。同一个图层重复点击不算变化。
    if (changed) selectionDirty = true

    val newLayer = layers.getOrNull(i)
    if (newLayer != null && active3DLayers.containsKey(newLayer.id) && changed) {
        // 进入 3D 参考图层时，清空引擎中可能留存的静止像素，完全交由实时 120Hz Canvas3DDrawer 接管
        ensure3DLayerClearedInCore(newLayer.id)
    }
    if (recorder.recording) {
        recorder.layerOp(com.reverie.paint.model.RecordingEvents.L_SET_CURRENT, i)
    }
    // 纯选中: 不改内容, 不标脏、不拉自动保存引信 (见 notifyLayerChanged 注释)
    runCore(after = { notifyLayerChanged(forceThumbs = false, contentChanged = false) }) {
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
    runCore(after = { notifyLayerChanged(forceThumbs = false) }) {
        ReverieCoreBridge.setLayerBlendMode(i, opId)
    }
}

internal fun PaintViewModel.toggleLayerVisible(i: Int) {
    if (i !in layers.indices) return
    val current = layers[i].visible
    val target = !current

    // 1. 立即乐观更新 UI 状态 (主线程即时响应，眼睛图标无延迟翻转)
    layers = layers.mapIndexed { idx, item ->
        if (idx == i) item.copy(visible = target) else item
    }

    // Record the TARGET visibility so replay sets it directly
    if (recorder.recording) {
        recorder.layerOp(com.reverie.paint.model.RecordingEvents.L_VISIBLE, i, if (target) "1" else "0")
    }

    // 2. 异步在渲染线程调用 C++ 引擎切换显隐并重组投影 (runCore 完成后自动触发 scheduleRender)
    val isSolo = ReverieCoreBridge.soloActive()
    runCore(render = true, after = {
        isModified = true
        hasPendingMajorOp = true
        onPaintingActivity()
        if (isSolo) {
            // 独显模式下显隐联动可能影响多行，做全量同步
            syncLayersFromNative()
            soloRawMode = ReverieCoreBridge.layerSoloRawMode()
        } else {
            // 局部校验：若底层执行失败或与预期不一致，回滚为真实状态
            val actual = ReverieCoreBridge.layerVisible(i)
            if (i in layers.indices && layers[i].visible != actual) {
                layers = layers.mapIndexed { idx, item ->
                    if (idx == i) item.copy(visible = actual) else item
                }
            }
        }
        layerRevision++
        if (anim.enabled) {
            anim.thumbRevision++
            syncAnimationFromNativeAfter()
        }
    }) {
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
    clearLayerSelection()
    if (recorder.recording) {
        recorder.layerOp(com.reverie.paint.model.RecordingEvents.L_ADD_GROUP)
    }
    val groupName = generateDefaultGroupName()
    runCore(after = {
        clearLayerSelection()
        notifyLayerChanged()
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
    clearLayerSelection()
    val srcLayer = layers.getOrNull(i)
    val src3DState = srcLayer?.let { active3DLayers[it.id] }
    if (recorder.recording) {
        recorder.layerOp(com.reverie.paint.model.RecordingEvents.L_COPY, i)
    }
    val isZh = LanguageManager.isChinese()
    runCore(after = {
        isCopyingLayer = false
        clearLayerSelection()
        notifyLayerChanged()
        if (src3DState != null) {
            val newLayer = layers.getOrNull(currentLayerIndex)
            if (newLayer != null) {
                active3DLayers = active3DLayers + (newLayer.id to src3DState.copy(layerId = newLayer.id))
                ensure3DLayerClearedInCore(newLayer.id)
            }
        }
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
    val layer = layers.getOrNull(i)
    if (layer != null && active3DLayers.containsKey(layer.id)) {
        active3DLayers = active3DLayers - layer.id
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
    runCore(after = { notifyLayerChanged(forceThumbs = false, immediateRender = false) }) {
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
        runCore(after = { notifyLayerChanged(forceThumbs = false) }) {
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
    runCore(after = { notifyLayerChanged(forceThumbs = false, immediateRender = false) }) {
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
    runCore(after = { notifyLayerChanged(forceThumbs = false, immediateRender = false) }) {
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
    runCore(after = { notifyLayerChanged(forceThumbs = false, immediateRender = false) }) {
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
    runCore(after = { notifyLayerChanged(forceThumbs = false) }) {
        ReverieCoreBridge.setLayerClipped(i, clipped)
    }
}

internal fun PaintViewModel.layerAlphaInherited(i: Int) = ReverieCoreBridge.layerAlphaInherited(i)

internal fun PaintViewModel.setLayerAlphaInherited(
    i: Int,
    enable: Boolean,
) {
    if (recorder.recording) {
        recorder.layerOp(com.reverie.paint.model.RecordingEvents.L_ALPHA_INHERITED, i, if (enable) "1" else "0")
    }
    runCore(after = { notifyLayerChanged(forceThumbs = false) }) {
        ReverieCoreBridge.setLayerAlphaInherited(i, enable)
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

/**
 * 视图翻转 (水平): 只镜像显示, 不动像素。
 *
 * 与 [flipCanvasHorizontal] 的区别: 后者要逐层镜像 paint device, 图层多时会明显卡顿,
 * 而且进撤销栈。临时翻过来看一眼/对照着画两笔用这个 —— 坐标反算走
 * CanvasViewTransform 的 signX, 所以"看到哪就画到哪"依旧成立。
 */
internal fun PaintViewModel.toggleViewFlipHorizontal() {
    viewFlipX = !viewFlipX
    // 画布是硬件直出的, 必须让 AndroidView 重跑一次 update 才会重绘
    displayRevision++
    // 以**当前视图中心**为轴翻转 (CSP 行为): 翻转后视图中心看到的还是同一处
    // 内容, 不用手动把画布拖回去找。平移量只有 UI 侧持有, 这里发一次性命令,
    // 由 PaintingPage 用自己的 rotation/pan 做补偿 (公式见 viewFlipMirroredPan)。
    requestUiCommand(UI_CMD_VIEW_FLIP_X)
    showActionToast(
        if (viewFlipX) R.string.toast_view_flip_h_on else R.string.toast_view_flip_h_off,
        R.drawable.ic_flip_horizontal,
    )
}

/** 视图翻转 (垂直), 见 [toggleViewFlipHorizontal]。 */
internal fun PaintViewModel.toggleViewFlipVertical() {
    viewFlipY = !viewFlipY
    displayRevision++
    requestUiCommand(UI_CMD_VIEW_FLIP_Y)
    showActionToast(
        if (viewFlipY) R.string.toast_view_flip_v_on else R.string.toast_view_flip_v_off,
        R.drawable.ic_flip_vertical,
    )
}

/**
 * 完全翻转 (水平): 真的镜像每一层像素, 并把该轴的视图翻转复位。
 *
 * 复位是必须的 —— 视图翻转是"临时看", 完全翻转是"改数据"; 两者叠在一次操作上,
 * 用户预期是"翻转完就回到未翻转的基准", 否则画面会翻两次, 看起来像没生效。
 */
internal fun PaintViewModel.flipCanvasHorizontalFull() {
    viewFlipX = false
    flipCanvasHorizontal()
    showActionToast(R.string.toast_canvas_flip_h_done, R.drawable.ic_flip_horizontal)
}

/** 完全翻转 (垂直), 见 [flipCanvasHorizontalFull]。 */
internal fun PaintViewModel.flipCanvasVerticalFull() {
    viewFlipY = false
    flipCanvasVertical()
    showActionToast(R.string.toast_canvas_flip_v_done, R.drawable.ic_flip_vertical)
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

/** 修改填充图层的颜色 (区别于 fillLayerForeground, 不污染画笔颜色且支持填充图层与预填色颜料层)。 */
internal fun PaintViewModel.setFillLayerColor(index: Int, colorInt: Int) {
    if (recorder.recording) {
        recorder.layerOp(com.reverie.paint.model.RecordingEvents.L_FILL_LAYER, index, colorInt.toString())
    }
    runCore(after = { notifyLayerChanged(pixelChanged = true) }) {
        ReverieCoreBridge.setFillLayerColor(index, colorInt)
    }
}

internal fun PaintViewModel.stampVisibleLayers() {
    clearLayerSelection()
    if (recorder.recording) {
        recorder.layerOp(com.reverie.paint.model.RecordingEvents.L_STAMP)
    }
    val isZh = LanguageManager.isChinese()
    runCore(after = {
        clearLayerSelection()
        notifyLayerChanged()
    }) {
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
        ensurePanelBordersOnTop(scanEnginePanels())
    }
}

internal fun PaintViewModel.moveLayersToGroup(
    fromIndices: List<Int>,
    group: Int,
) {
    if (fromIndices.isEmpty()) return
    val effectiveIndices = mutableListOf<Int>()
    val fromSet = fromIndices.toSet()
    for (idx in fromIndices) {
        effectiveIndices.add(idx)
        val maskIdx = idx - 1
        if (maskIdx > 0 && maskIdx !in fromSet && maskIdx in layers.indices) {
            val maskCandidate = layers[maskIdx]
            if (com.reverie.paint.model.LayerNodeType.isMask(maskCandidate.nodeType)) {
                effectiveIndices.add(maskIdx)
            }
        }
    }
    val selectedIds = layers.filter { it.index in effectiveIndices }.map { it.id }.toSet()
    val selectedNames = layers.filter { it.index in effectiveIndices }.map { it.name }.toSet()
    if (recorder.recording) {
        for (from in effectiveIndices) {
            recorder.layerOp(com.reverie.paint.model.RecordingEvents.L_MOVE_TO_GROUP, from, group.toString())
        }
    }
    runCore(after = {
        notifyLayerChanged()
        selectedLayerIndices = layers.filter { it.id in selectedIds || it.name in selectedNames }.map { it.index }.toSet()
    }) {
        ReverieCoreBridge.moveLayersToGroup(effectiveIndices.toIntArray(), group)
        ensurePanelBordersOnTop(scanEnginePanels())
    }
}

internal fun PaintViewModel.moveLayersRelative(
    fromIndices: List<Int>,
    target: Int,
    placeAbove: Boolean,
) {
    if (fromIndices.isEmpty()) return
    // 强制绑定: 若移动父图层，自动附带其直接附属的透明度蒙版图层一同移动
    val effectiveIndices = mutableListOf<Int>()
    val fromSet = fromIndices.toSet()
    for (idx in fromIndices) {
        effectiveIndices.add(idx)
        val maskIdx = idx - 1
        if (maskIdx > 0 && maskIdx !in fromSet && maskIdx in layers.indices) {
            val maskCandidate = layers[maskIdx]
            if (com.reverie.paint.model.LayerNodeType.isMask(maskCandidate.nodeType)) {
                effectiveIndices.add(maskIdx)
            }
        }
    }
    val selectedIds = layers.filter { it.index in effectiveIndices }.map { it.id }.toSet()
    val selectedNames = layers.filter { it.index in effectiveIndices }.map { it.name }.toSet()
    if (recorder.recording) {
        for (from in effectiveIndices) {
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
        ReverieCoreBridge.moveLayersRelative(effectiveIndices.toIntArray(), target, placeAbove)
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

internal fun PaintViewModel.invertMask(layerIndex: Int) {
    if (recorder.recording) {
        recorder.layerOp(com.reverie.paint.model.RecordingEvents.L_INVERT_MASK, layerIndex)
    }
    runCore(after = ::notifyLayerChanged) {
        ReverieCoreBridge.invertMask(layerIndex)
    }
}

internal fun PaintViewModel.applyMask(layerIndex: Int) {
    if (recorder.recording) {
        recorder.layerOp(com.reverie.paint.model.RecordingEvents.L_APPLY_MASK, layerIndex)
    }
    runCore(after = ::notifyLayerChanged) {
        ReverieCoreBridge.applyMask(layerIndex)
    }
}

internal fun PaintViewModel.rasterizeLayer(i: Int) {
    val layer = layers.getOrNull(i)
    if (layer != null && active3DLayers.containsKey(layer.id)) {
        rasterize3DLayer(layer.id)
        return
    }
    if (recorder.recording) {
        recorder.layerOp(com.reverie.paint.model.RecordingEvents.L_RASTERIZE, i)
    }
    runCore(after = ::notifyLayerChanged) {
        ReverieCoreBridge.rasterizeLayer(i)
    }
}

internal fun PaintViewModel.flattenGroup(i: Int) {
    val groupLayer = layers.getOrNull(i)
    if (groupLayer != null && groupLayer.isGroup) {
        setGroupCollapsed(groupLayer.id, collapsed = false)
    }
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

internal fun PaintViewModel.mergeDown(i: Int) {
    if (transientTransformSelectionPending || canUndoTransformSelection || canRedoTransformSelection) {
        discardTransientTransformSelection()
    }
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


internal fun PaintViewModel.selectionFromLayer(i: Int, mode: Int = 0) {
    if (recorder.recording) {
        recorder.toolOp(com.reverie.paint.model.RecordingEvents.T_SELECT_ALL) {
            it.u16(i.coerceIn(0, 65535))
            it.u8(mode.coerceIn(0, 255))
        }
    }
    var ov: android.graphics.Bitmap? = null
    var has = false
    runCore(render = false, after = {
        selectionOverlayBitmap = ov
        selectionOutlinePath = pendingSelectionOutlinePath
        hasSelection = has && ov != null
        notifyLayerChanged()
        if (hasSelection) {
            val msgRes = when (mode) {
                1 -> R.string.selection_toast_added
                2 -> R.string.selection_toast_subtracted
                3 -> R.string.selection_toast_intersected
                else -> R.string.toast_selection_from_layer_success
            }
            showActionToast(msgRes, R.drawable.ic_select)
        } else {
            showActionToast(R.string.toast_selection_layer_empty, R.drawable.ic_select)
        }
    }) {
        val ok = ReverieCoreBridge.selectionFromLayer(i, mode)
        has = ok && ReverieCoreBridge.hasSelection()
        if (has) {
            ov = buildSelectionOverlayLocked()
        }
    }
}

internal fun PaintViewModel.clearSelection() {
    hasSelection = false
    selectionOverlayBitmap = null
    selectionOutlinePath = null
    runCore(render = false, after = ::notifyLayerChanged) {
        ReverieCoreBridge.clearSelection()
    }
}

internal fun PaintViewModel.addLayerWithType(
    name: String = "",
    type: Int = 0,
    fillColor: Int = 0xFFFFFFFF.toInt(),
) {
    clearLayerSelection()
    if (recorder.recording) {
        recorder.layerOp(
            com.reverie.paint.model.RecordingEvents.L_ADD_LAYER_TYPE,
            0,
            "$name|$type|$fillColor",
        )
    }
    val pids = currentPaintingPanelIds()
    val wasInside = pids.isNotEmpty()
    if (wasInside) {
        collapsedPanelIds = collapsedPanelIds - pids
    }
    runCore(after = {
        clearLayerSelection()
        notifyLayerChanged()
    }) {
        executeAddLayerSafely(wasInside) {
            ReverieCoreBridge.addLayerWithType(name, type, fillColor)
        }
    }
}

internal fun PaintViewModel.addFillLayer(colorHex: String = brushColor) {
    clearLayerSelection()
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
            "$fillLayerName|2|$colorInt",
        )
    }
    val pids = currentPaintingPanelIds()
    val wasInside = pids.isNotEmpty()
    if (wasInside) {
        collapsedPanelIds = collapsedPanelIds - pids
    }
    // type=2 仍创建预填色颜料层，由引擎保存显式类型标记
    runCore(
        after = {
            clearLayerSelection()
            notifyLayerChanged()
        },
    ) {
        executeAddLayerSafely(wasInside) {
            ReverieCoreBridge.addLayerWithType(fillLayerName, 2, colorInt)
        }
    }
}

internal fun PaintViewModel.addFilterLayer(onOpenFilters: (Int) -> Unit) {
    clearLayerSelection()
    if (recorder.recording) {
        // 与 stampVisibleLayers 包装一致: 盖印可见层作为滤镜底图层
        recorder.layerOp(com.reverie.paint.model.RecordingEvents.L_STAMP)
    }
    val filterLayerName = generateDefaultFilterLayerName()
    val pids = currentPaintingPanelIds()
    val wasInside = pids.isNotEmpty()
    if (wasInside) {
        collapsedPanelIds = collapsedPanelIds - pids
    }
    runCore(after = {
        clearLayerSelection()
        notifyLayerChanged()
        val cur = currentLayerIndex
        if (cur >= 0) {
            renameLayer(cur, filterLayerName)
            onOpenFilters(cur)
        }
    }) {
        executeAddLayerSafely(wasInside) {
            ReverieCoreBridge.stampVisibleLayers()
        }
    }
}

internal fun PaintViewModel.addStrokeLayer() {
    clearLayerSelection()
    val strokeLayerName = generateDefaultStrokeLayerName()
    if (recorder.recording) {
        recorder.layerOp(
            com.reverie.paint.model.RecordingEvents.L_ADD_LAYER_TYPE,
            0,
            "$strokeLayerName|6|0",
        )
    }
    val pids = currentPaintingPanelIds()
    val wasInside = pids.isNotEmpty()
    if (wasInside) {
        collapsedPanelIds = collapsedPanelIds - pids
    }
    runCore(after = {
        clearLayerSelection()
        notifyLayerChanged()
    }) {
        executeAddLayerSafely(wasInside) {
            ReverieCoreBridge.addLayerWithType(strokeLayerName, 6, 0)
        }
    }
}

internal fun PaintViewModel.updateLayerStrokeParams(
    layerIndex: Int,
    size: Int,
    color: Int,
    position: Int,
    opacity: Int,
    preview: Boolean = false,
) {
    if (preview) {
        // Fast drag preview: no undo command, no thumbnail invalidation, just immediate render
        runCore(render = true) {
            ReverieCoreBridge.setLayerStrokeParamsDirect(layerIndex, size, color, position, opacity)
        }
    } else {
        runCore(after = {
            notifyLayerChanged()
        }) {
            ReverieCoreBridge.setLayerStrokeParams(layerIndex, size, color, position, opacity)
        }
    }
}

internal fun PaintViewModel.rasterizeCurrentLayerStroke(layerIndex: Int) {
    runCore(after = {
        notifyLayerChanged()
    }) {
        ReverieCoreBridge.rasterizeLayerStroke(layerIndex)
    }
}
