/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import android.os.Handler
import android.os.Looper
import androidx.lifecycle.viewModelScope
import com.reverie.paint.R
import com.reverie.paint.model.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.reverie.paint.core.PaintViewModel.PredictionFidelityTier

// 拆分自 PaintViewModel.kt: 同域实现集中于此 (AGENTS.md §6 单文件行数上限)

internal fun PaintViewModel.isCurrentLayerAlphaLocked(): Boolean {
    val idx = currentLayerIndex
    return layers.firstOrNull { it.index == idx }?.alphaLocked == true
}

/** 更新单个槽位的避让状态（动画由 Compose 侧共享 [slotEvadeAlpha] 驱动）。 */
internal fun PaintViewModel.setSlotEvading(slot: com.reverie.paint.model.UiPanelSlot, evading: Boolean) {
    val index = slot.ordinal
    if (slotEvading[index].value != evading) slotEvading[index].value = evading
}

/** 立即清除全部避让状态（12 条强制复位路径共用）。 */
internal fun PaintViewModel.clearAllSlotEvasion() {
    panelOcclusion.clearAllEvasion()
    for (i in slotEvading.indices) {
        if (slotEvading[i].value) slotEvading[i].value = false
    }
    // 复位路径要求立即恢复 100%，跳过 70ms 过渡。
    viewModelScope.launch {
        for (i in slotEvadeAlpha.indices) {
            slotEvadeAlpha[i].snapTo(1f)
        }
    }
}

internal fun PaintViewModel.updateAutoHidePanelsOnStroke(enabled: Boolean) {
    autoHidePanelsOnStroke = enabled
    if (!enabled) clearAllSlotEvasion()
    if (isAppContextReady()) {
        appContext.getSharedPreferences("paint_prefs", android.content.Context.MODE_PRIVATE)
            .edit().putBoolean("autoHidePanelsOnStroke", enabled).apply()
    }
}

internal fun PaintViewModel.dismissToolbarSqueezedWarning(forever: Boolean) {
    showToolbarSqueezedDialog = false
    if (forever) {
        toolbarSqueezedWarningDismissed = true
        if (isAppContextReady()) {
            appContext.getSharedPreferences("paint_prefs", android.content.Context.MODE_PRIVATE)
                .edit()
                .putBoolean("toolbarSqueezedWarningDismissed", true)
                .apply()
        }
    }
}

internal fun PaintViewModel.enterCanvasAdjustMode(mode: com.reverie.paint.model.CanvasAdjustMode = com.reverie.paint.model.CanvasAdjustMode.CROP_EXPAND) {
    val w = docWidth
    val h = docHeight
    canvasAdjustState = com.reverie.paint.model.CanvasAdjustState(
        mode = mode,
        origWidth = w,
        origHeight = h,
        targetWidth = w,
        targetHeight = h,
        lockAspectRatio = mode == com.reverie.paint.model.CanvasAdjustMode.RESAMPLE_SCALE,
        selectedPreset = com.reverie.paint.model.AspectRatioPreset.FREE,
        anchor = com.reverie.paint.model.AnchorPosition.CENTER,
        resampleFilter = com.reverie.paint.model.ResampleFilter.BICUBIC,
        cropX = 0,
        cropY = 0,
    )
    isCanvasAdjustActive = true
}

internal fun PaintViewModel.exitCanvasAdjustMode() {
    isCanvasAdjustActive = false
    if (currentToolId == "crop") {
        val prev = lastToolId.ifEmpty { "brush" }
        applyTool(if (prev == "crop") "brush" else prev)
    }
}

internal fun PaintViewModel.applyCanvasAdjustment() {
    val s = canvasAdjustState
    isCanvasAdjustActive = false
    canvasAdjustPanelOffset = androidx.compose.ui.geometry.Offset.Zero
    if (currentToolId == "crop") {
        val prev = lastToolId.ifEmpty { "brush" }
        applyTool(if (prev == "crop") "brush" else prev)
    }
    if (s.mode == com.reverie.paint.model.CanvasAdjustMode.CROP_EXPAND) {
        val (cx, cy) = if (s.selectedPreset != com.reverie.paint.model.AspectRatioPreset.FREE || s.lockAspectRatio || s.cropX != 0 || s.cropY != 0) {
            s.cropX to s.cropY
        } else {
            com.reverie.paint.model.AnchorPosition.computeOffset(s.origWidth, s.origHeight, s.targetWidth, s.targetHeight, s.anchor)
        }
        cropCanvas(cx, cy, s.targetWidth, s.targetHeight)
        showActionToast(R.string.canvas_toast_resized, R.drawable.ic_crop, s.targetWidth, s.targetHeight)
    } else {
        scaleImage(s.targetWidth, s.targetHeight, s.resampleFilter.filterType)
        showActionToast(R.string.canvas_toast_scale_success, R.drawable.ic_crop, s.targetWidth, s.targetHeight)
    }
}

internal fun PaintViewModel.computeCurrentBrushPredictionTier(): PredictionFidelityTier {
    val preset = brushPresets.firstOrNull { it.index == brushPresetIndex }
    return PaintViewModel.resolvePredictionTier(
        predictionEnabled = frontBufferPredictionEnabled,
        toolId = currentToolId,
        compositeOp = brushCompositeOp,
        scatter = brushScatter,
        spacing = brushSpacing,
        opacity = brushOpacity,
        flow = brushFlow,
        textureEnabled = brushTextureEnabled,
        presetGroup = preset?.group,
        presetName = preset?.name ?: ""
    )
}

internal fun PaintViewModel.toggleDrawingGuideLines() {
    drawingGuideLinesVisible = !drawingGuideLinesVisible
    try {
        prefs().edit().putBoolean("drawing_guide_lines_visible", drawingGuideLinesVisible).apply()
    } catch (_: Exception) {}
}

internal fun PaintViewModel.isGroupCollapsed(id: Long): Boolean = groupCollapseKey(id) in collapsedGroupKeys

internal fun PaintViewModel.toggleGroupCollapsed(id: Long) {
    val key = groupCollapseKey(id)
    collapsedGroupKeys = if (key in collapsedGroupKeys) {
        collapsedGroupKeys - key
    } else {
        collapsedGroupKeys + key
    }
}

internal fun PaintViewModel.togglePanelCollapsed(panelId: Long) {
    collapsedPanelIds = if (panelId in collapsedPanelIds) {
        collapsedPanelIds - panelId
    } else {
        collapsedPanelIds + panelId
    }
}

internal fun PaintViewModel.setGroupCollapsed(id: Long, collapsed: Boolean) {
    val key = groupCollapseKey(id)
    if (collapsed == (key in collapsedGroupKeys)) return
    collapsedGroupKeys = if (collapsed) collapsedGroupKeys + key else collapsedGroupKeys - key
}

/**
 * 折叠状态写进工程元数据 (仍按**名字**序列化，保持旧工程可读)。
 * 同名组用 `名字@序号` 消歧: 序号是"该名字在组里出现的第几个"，载入时按同样
 * 规则还原，于是两个同名组不会挤到同一个键上。
 */
internal fun PaintViewModel.collapsedGroupsForSave(): List<String> {
    val seen = HashMap<String, Int>()
    val out = ArrayList<String>()
    for (l in layers) {
        if (!l.isGroup) continue
        val idx = seen[l.name] ?: 0
        seen[l.name] = idx + 1
        if (isGroupCollapsed(l.id)) out.add(if (idx == 0) l.name else "${l.name}@$idx")
    }
    return out
}

/** 载入工程时把"名字/名字@序号"还原成层 id 键 (必须在图层表就绪后调用)。 */
internal fun PaintViewModel.applyCollapsedGroupsFromFile(names: Collection<String>) {
    if (names.isEmpty()) {
        collapsedGroupKeys = emptySet()
        return
    }
    val wanted = names.toHashSet()
    val seen = HashMap<String, Int>()
    val keys = HashSet<String>()
    for (l in layers) {
        if (!l.isGroup) continue
        val idx = seen[l.name] ?: 0
        seen[l.name] = idx + 1
        if (l.name in wanted || "${l.name}@$idx" in wanted) keys.add(groupCollapseKey(l.id))
    }
    collapsedGroupKeys = keys
}

/** 检查指定图层自身是否隐藏，或其任意祖先图层组是否隐藏。 */
internal fun PaintViewModel.isLayerEffectivelyHidden(layerIndex: Int): Boolean =
    com.reverie.paint.model.isLayerEffectivelyHiddenAt(
        index = layerIndex,
        layerCount = layers.size,
        depthAt = { layers[it].depth },
        visibleAt = { layers[it].visible },
        groupAt = { layers[it].isGroup },
        maskAt = { com.reverie.paint.model.LayerNodeType.isMask(layers[it].nodeType) },
    )

internal fun PaintViewModel.openMoreSettings(initialSubPage: String = "MAIN") {
    settingsInitialSubPage = initialSubPage
    moreSettingsOpen = true
}

internal fun PaintViewModel.closeMoreSettings() {
    settingsInitialSubPage = "MAIN"
    moreSettingsOpen = false
}

/** Report the visible canvas size (device px); keep render buffer at full native resolution */
internal fun PaintViewModel.setRenderViewport(
    viewW: Int,
    viewH: Int,
) {
    if (viewW <= 0 || viewH <= 0) return
    // coreW/coreH 由渲染线程在换文档 (loadProject/startPainting 的 newDocument) 时写入。
    // 在 UI 线程上读会和它竞态, 把 renderW/renderH 写回旧尺寸, 首帧瓦片与文档尺寸错位。
    // 投递到渲染线程, 与文档尺寸变更串行执行。渲染线程还没起来或当前已在渲染线程时
    // 就地应用, 避免 resetReplayDocLocked / seekLocked 等渲染线程路径把视口更新排到队尾。
    val h = renderHandler
    if (h == null || Looper.myLooper() === h.looper) {
        applyRenderViewport()
    } else {
        h.post { applyRenderViewport() }
    }
}

internal fun PaintViewModel.applyRenderViewport(scheduleIfChanged: Boolean = true) {
    val nw = maxOf(1, coreW)
    val nh = maxOf(1, coreH)
    if (nw != renderW || nh != renderH) {
        renderW = nw
        renderH = nh
        displayBufferInvalid = true
        if (scheduleIfChanged) {
            scheduleRender(immediate = true)
        }
    }
}

/**
 * 主线程: 加载/新建收尾, 等新文档的第一帧上屏。
 *
 * 顺序是确定的: `runCore` 的引擎操作与渲染帧同在一个 Handler 队列上, 所以这里
 * "先 scheduleRender 再 post 收尾" ⇒ 收尾一定跑在那一帧之后。不用猜"哪一帧是新文档的",
 * 也不依赖 `notifyCanvasFramePublished` (加载期间也可能有帧, 那帧画的还是旧文档)。
 */
internal fun PaintViewModel.beginAwaitingFirstFrameAfterLoad() {
    documentSwitchPending = true
    awaitingFirstFrameAfterLoad = true
    mainHandler.removeCallbacks(postedClearAwaitingFirstFrame)
    mainHandler.postDelayed(postedClearAwaitingFirstFrame, PaintViewModel.FIRST_FRAME_GUARD_TIMEOUT_MS)
    val h = renderHandler
    if (h == null) {
        postedClearAwaitingFirstFrame.run()
        return
    }
    scheduleRender(immediate = true)
    h.post { mainHandler.post(postedClearAwaitingFirstFrame) }
}

/** 主线程: 放弃等待 (加载失败 / 返回画廊 / 超时兜底)。 */
internal fun PaintViewModel.cancelAwaitingFirstFrameAfterLoad() {
    mainHandler.removeCallbacks(postedClearAwaitingFirstFrame)
    postedClearAwaitingFirstFrame.run()
}

internal fun PaintViewModel.consumeLargeBitmapDirtyRect(out: android.graphics.Rect): Boolean {
    synchronized(largeBitmapDirtyRect) {
        if (largeBitmapDirtyRect.isEmpty) return false
        out.set(largeBitmapDirtyRect)
        largeBitmapDirtyRect.setEmpty()
        return true
    }
}

/**
 * 前缓冲真墨当前是否处于按住状态 (P7 / L2)。
 * 受 [FrontBufferFeatureFlags.powersaveExempt] (`debug.reverie.fb_powersave_exempt`, 默认 1) 控制。
 */
internal fun PaintViewModel.realInkHeld(): Boolean {
    if (!FrontBufferFeatureFlags.powersaveExempt) return false
    return realInkSink?.holding == true
}

internal fun PaintViewModel.flushPendingPresetSelect() {
    val job = presetSelectDebounceJob
    if (job != null && job.isActive) {
        job.cancel()
        presetSelectDebounceJob = null
        val pendingIndex = brushPresetIndex
        if (pendingIndex >= 0 && !isPresetLoading) {
            dispatchPresetLoad(pendingIndex)
        }
    }
}

internal fun PaintViewModel.schedulePersistToolBrushStates(delayMs: Long = 250L) {
    mainHandler.removeCallbacks(persistToolStatesRunnable)
    mainHandler.postDelayed(persistToolStatesRunnable, delayMs)
}
