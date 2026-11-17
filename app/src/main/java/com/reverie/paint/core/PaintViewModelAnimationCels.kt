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
    manageUndoMacro: Boolean = true,
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
    if (manageUndoMacro) {
        ReverieCoreBridge.beginUndoMacro(appContext.getString(
            if (erase) R.string.anim_delete_cel else R.string.anim_create_cel,
        ))
    }
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
        if (manageUndoMacro) ReverieCoreBridge.endUndoMacro()
    }
    // Only undo this macro if it actually changed the document; never undo a user's previous stroke.
    if (!success && completed > 0 && manageUndoMacro) ReverieCoreBridge.undo()
    if (!success) {
        mainHandler.post { showActionToast(R.string.anim_cel_failed) }
        // Abort the enclosing render operation as well: never stamp or start a stroke on an unisolated cel.
        error("Could not isolate animation cel")
    }
    return true
}

/** 根图层组作为一条轨道, 里面所有可动画颜料层共同组成每个独立画格。 */
internal fun PaintViewModel.animationTrackGroupForLayer(layer: Int): Int {
    if (layer <= 0 || layer >= ReverieCoreBridge.layerCount()) return -1
    val count = ReverieCoreBridge.layerCount()
    if (ReverieCoreBridge.layerIsGroup(layer) && ReverieCoreBridge.layerDepth(layer) == 0) return layer
    for (candidate in 1 until layer) {
        if (!ReverieCoreBridge.layerIsGroup(candidate) || ReverieCoreBridge.layerDepth(candidate) != 0) {
            continue
        }
        val end = (candidate + 1 until count)
            .firstOrNull { ReverieCoreBridge.layerDepth(it) <= 0 } ?: count
        if (layer < end) return candidate
    }
    return -1
}

/** 返回轨道组中参与逐帧画格的全部普通颜料层 (含嵌套小组内的颜料层)。 */
internal fun PaintViewModel.animationTrackLayerIndices(group: Int): List<Int> {
    if (group <= 0 || group >= ReverieCoreBridge.layerCount() ||
        !ReverieCoreBridge.layerIsGroup(group)
    ) return emptyList()
    val depth = ReverieCoreBridge.layerDepth(group)
    val count = ReverieCoreBridge.layerCount()
    val end = (group + 1 until count).firstOrNull {
        ReverieCoreBridge.layerDepth(it) <= depth
    } ?: count
    return (group + 1 until end).filter(ReverieCoreBridge::layerAnimatable)
}

/**
 * 建立/清除一整格画面: 同一轨道内所有可动画颜料层一起隔离。
 *
 * 每个子层仍由 Krita 持有像素和关键帧; Kotlin 只把相同时间的多个子层关键帧作为一个
 * 轨道画格操作, 因而每格可以有多层且不会把相邻格的内容合并。
 */
internal fun PaintViewModel.prepareAnimationTrackCel(
    group: Int,
    time: Int,
    erase: Boolean = false,
    preserveDrawing: Boolean = false,
): Boolean {
    val children = animationTrackLayerIndices(group)
    if (children.isEmpty()) return false

    val updates = ArrayList<Int>(children.size)
    for (layer in children) {
        if (ReverieCoreBridge.layerLocked(layer) || isStoryboardTrackOnRenderThread(layer)) continue
        if (!ReverieCoreBridge.layerAnimated(layer) &&
            !ReverieCoreBridge.enableLayerAnimation(layer)
        ) {
            continue
        }
        val range = AnimationCelPlan.range(time, anim.storyboard.frames)
        if (!erase && ReverieCoreBridge.hasKeyframe(layer, range.start) &&
            ReverieCoreBridge.hasKeyframe(layer, range.endExclusive)
        ) continue
        if (AnimationCelPlan.edits(
                times = ReverieCoreBridge.keyframeTimes(layer).toList(),
                range = range,
                erase = erase,
                preserveDrawing = preserveDrawing,
            ).isNotEmpty()
        ) {
            updates += layer
        }
    }
    if (updates.isEmpty()) return false

    var changed = false
    var macroEnded = false
    ReverieCoreBridge.beginUndoMacro(appContext.getString(
        if (erase) R.string.anim_delete_cel else R.string.anim_create_cel,
    ))
    try {
        for (layer in updates) {
            if (prepareAnimationCel(
                    layer = layer,
                    time = time,
                    erase = erase,
                    preserveDrawing = preserveDrawing,
                    manageUndoMacro = false,
                )
            ) {
                changed = true
            }
        }
        ReverieCoreBridge.endUndoMacro()
        macroEnded = true
    } catch (error: Exception) {
        android.util.Log.e("RP_CEL", "Track cel edit failed", error)
        if (!macroEnded) ReverieCoreBridge.endUndoMacro()
        ReverieCoreBridge.undo()
        mainHandler.post { showActionToast(R.string.anim_cel_failed) }
        return false
    }
    return changed
}

/** Called immediately after creating an empty paint layer, before importing/stamping any pixels. */
internal fun PaintViewModel.initializeNewAnimationCel() {
    if (!anim.enabled) return
    val layer = ReverieCoreBridge.currentLayerIndex()
    // 轨道组内新增的子图层: 要对齐整条轨道已有的格子, 否则它只在一个格子出现,
    // 其他格子会被前后帧 hold 拖进来, 破坏"每格是独立画布"
    val trackGroup = animationTrackGroupForLayer(layer)
    if (trackGroup >= 0) {
        if (!ReverieCoreBridge.layerAnimated(layer) && !ReverieCoreBridge.enableLayerAnimation(layer)) return
        val times = animationTrackLayerIndices(trackGroup)
            .filter { it != layer }
            .flatMap { ReverieCoreBridge.keyframeTimes(it).toList() }
            .distinct()
            .sorted()
        if (times.isEmpty()) {
            prepareAnimationCel(layer, ReverieCoreBridge.animationCurrentTime())
            return
        }
        ReverieCoreBridge.beginUndoMacro(appContext.getString(R.string.anim_create_cel))
        try {
            for (t in times) ReverieCoreBridge.addKeyframe(layer, t)
        } finally {
            ReverieCoreBridge.endUndoMacro()
        }
        syncAnimationFromNative()
        return
    }
    prepareAnimationCel(layer, ReverieCoreBridge.animationCurrentTime())
}

/**
 * 旧动画工程迁移: 顶层散层全部收进同一条轨道。
 *
 * 时间轴以"轨道 = 根图层组"为单位显示; 旧工程没有组, 若按图层逐个显示会变成
 * 每个图层一条轨道, 与"一条轨道里有多个图层"的模型不符。这里把散层收进一个
 * "轨道 1" 组, 图层面板里仍然是原来的各图层, 只是多了一层容器。
 *
 * 仅在没有根组时执行一次; 保存后结构固定, 不会每次打开重复套组。
 */
internal fun PaintViewModel.ensureSingleAnimationTrackOnRenderThread(): String? {
    val count = ReverieCoreBridge.layerCount()
    if (count <= 2) return null
    val isRoot = { i: Int -> ReverieCoreBridge.layerDepth(i) == 0 }
    val rootGroups = (1 until count).filter { isRoot(it) && ReverieCoreBridge.layerIsGroup(it) }
    val loose = (1 until count).filter { isRoot(it) && !ReverieCoreBridge.layerIsGroup(it) }
    if (loose.isEmpty()) return null

    // 已经有一条轨道: 只把漏在外面的散层收进去, 不再新建轨道
    if (rootGroups.isNotEmpty()) {
        val existingGroup = rootGroups.first()
        val toMove = loose.filter { it != existingGroup }
        if (toMove.isNotEmpty()) {
            ReverieCoreBridge.moveLayersToGroup(toMove.toIntArray(), existingGroup)
            animationTrackLayerIndices(existingGroup).forEach { child ->
                if (!ReverieCoreBridge.layerAnimated(child)) ReverieCoreBridge.enableLayerAnimation(child)
            }
        }
        return null
    }

    val trackName = generateDefaultTrackName()
    ReverieCoreBridge.setCurrentLayer(0)
    val groupIndex = ReverieCoreBridge.addGroupLayer(trackName)
    if (groupIndex <= 0) return null

    // 组插在背景上方后, 其余图层整体后移一位。批量搬移由引擎一次性解析节点,
    // 不会因为逐个移动导致索引漂移 (否则会漏掉最上面一层)
    val groupNow = (1 until ReverieCoreBridge.layerCount()).firstOrNull {
        ReverieCoreBridge.layerDepth(it) == 0 && ReverieCoreBridge.layerIsGroup(it)
    } ?: return null
    val toMove = (1 until ReverieCoreBridge.layerCount())
        .filter { it != groupNow && ReverieCoreBridge.layerDepth(it) == 0 && !ReverieCoreBridge.layerIsGroup(it) }
    if (toMove.size >= 2) {
        ReverieCoreBridge.moveLayersToGroup(toMove.toIntArray(), groupNow)
    } else if (toMove.size == 1) {
        ReverieCoreBridge.moveLayerToGroup(toMove.first(), groupNow)
    }
    // 组内每个图层都开动画; 原来的静态画面会作为第 0 帧的内容
    val children = animationTrackLayerIndices(groupNow)
    for (child in children) {
        if (!ReverieCoreBridge.layerAnimated(child)) ReverieCoreBridge.enableLayerAnimation(child)
    }
    return ReverieCoreBridge.layerName(groupNow)
}

/** 轨道默认命名: 轨道 1 / 轨道 2… (英文 Track N), 与图层组的命名区分开。 */
private fun PaintViewModel.generateDefaultTrackName(): String {
    val prefix = if (LanguageManager.isChinese()) "轨道" else "Track"
    val regex = Regex("""^(?:轨道|Track)\s*(\d+)?$""", RegexOption.IGNORE_CASE)
    var max = 0
    for (layer in layers) {
        val match = regex.find(layer.name.trim()) ?: continue
        val number = match.groupValues[1].toIntOrNull() ?: 0
        if (number > max) max = number
    }
    return if (max == 0) "$prefix 1" else "$prefix ${max + 1}"
}

/** 新建一条时间轴轨道: 根图层组 + 组内可动画颜料层。 */
internal fun PaintViewModel.animationAddTrack() {
    if (!anim.enabled || anim.isPlaying) return
    val groupName = generateDefaultTrackName()
    val paintName = generateDefaultLayerName()
    val generation = anim.storyboard.generation
    var added = false
    var createdGroupName = groupName
    var createdLayerId = 0L

    runCore(after = {
        if (generation != anim.storyboard.generation) return@runCore
        if (added) {
            anim.timelineCollapsedGroups = anim.timelineCollapsedGroups + createdGroupName
            val layerIndex = layers.indexOfFirst { it.id == createdLayerId }
            if (layerIndex >= 0) anim.selectedTrack = layerIndex
            notifyLayerChanged()
            showActionToast(R.string.anim_track_added)
        } else {
            showActionToast(R.string.anim_track_add_failed)
        }
    }) {
        if (generation != anim.storyboard.generation) return@runCore

        // 从背景图层插入, 确保新轨道是根组, 不会意外套进当前选中的其他轨道。
        ReverieCoreBridge.setCurrentLayer(0)
        val groupIndex = ReverieCoreBridge.addGroupLayer(groupName)
        if (groupIndex <= 0) return@runCore
        val groupId = ReverieCoreBridge.layerId(groupIndex)
        createdGroupName = ReverieCoreBridge.layerName(groupIndex)

        try {
            ReverieCoreBridge.setCurrentLayer(groupIndex)
            val before = ReverieCoreBridge.layerCount()
            ReverieCoreBridge.addLayer(paintName)
            if (ReverieCoreBridge.layerCount() != before + 1) error("Could not add paint layer")

            val childIndex = ReverieCoreBridge.currentLayerIndex()
            createdLayerId = ReverieCoreBridge.layerId(childIndex)
            if (!ReverieCoreBridge.layerAnimated(childIndex) &&
                !ReverieCoreBridge.enableLayerAnimation(childIndex)
            ) {
                error("Could not enable animation on paint layer")
            }
            // 轨道的第一格就是整组画格: 组内所有子层在同一帧一起隔离
            prepareAnimationTrackCel(groupIndex, ReverieCoreBridge.animationCurrentTime())
            syncLayersFromNative()
            syncAnimationFromNative()
            added = createdLayerId != 0L
        } catch (error: Exception) {
            android.util.Log.e("RP_ANIM", "Add track failed", error)
            val rollbackIndex = (1 until ReverieCoreBridge.layerCount()).firstOrNull {
                ReverieCoreBridge.layerId(it) == groupId
            }
            if (rollbackIndex != null) ReverieCoreBridge.removeLayer(rollbackIndex)
            syncLayersFromNative()
            syncAnimationFromNative()
        }
    }
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
        val trackGroup = animationTrackGroupForLayer(layer)
        changed = if (trackGroup >= 0) {
            prepareAnimationTrackCel(trackGroup, time, preserveDrawing = true)
        } else {
            prepareAnimationCel(layer, time, preserveDrawing = true)
        }
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
