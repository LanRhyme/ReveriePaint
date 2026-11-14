/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

/**
 * 仅含树结构信息的轻量节点，用于主线程即时乐观计算图层重排后的索引与深度。
 * 列表顺序与引擎一致: 自底向上 (index 0 为底层/背景层)，组节点紧跟其后的是 depth 更深的子孙节点。
 */
data class LayerOrderNode(
    val id: Long,
    val depth: Int,
    val isGroup: Boolean,
)

/**
 * 乐观计算结果: 返回重排后各原始下标的新位置 (originalIndex -> newDepth) 列表。
 * 按自底向上的引擎新顺序排列。
 */
data class OptimisticLayerPlacement(
    val originalIndex: Int,
    val newDepth: Int,
)

private fun subtreeEnd(
    nodes: List<LayerOrderNode>,
    startIndex: Int,
): Int {
    if (startIndex !in nodes.indices) return startIndex
    val rootDepth = nodes[startIndex].depth
    var i = startIndex + 1
    while (i < nodes.size && nodes[i].depth > rootDepth) {
        i++
    }
    return i
}

/**
 * 将 [movedIndices] (每个元素若为组则自动携带其子孙节点) 移动到 [targetIndex] 的上方或下方。
 *
 * 注意: 引擎列表为自底向上:
 * - [placeAbove] == true (视觉上方) -> 插入到 target 子树末尾之后 (`subtreeEnd(target)`)
 * - [placeAbove] == false (视觉下方) -> 插入到 target 本身之前 (`targetIndex`)
 */
fun computeOptimisticMoveRelative(
    nodes: List<LayerOrderNode>,
    movedIndices: List<Int>,
    targetIndex: Int,
    placeAbove: Boolean,
): List<OptimisticLayerPlacement>? {
    if (nodes.isEmpty() || targetIndex !in nodes.indices || movedIndices.isEmpty()) return null
    val movedSet = buildMovedSubtreeIndexSet(nodes, movedIndices)
    if (movedSet.isEmpty() || targetIndex in movedSet) return null

    val targetDepth = nodes[targetIndex].depth
    val rawInsertPos = if (placeAbove) subtreeEnd(nodes, targetIndex) else targetIndex

    val movedBlocks = extractMovedBlocks(nodes, movedIndices, movedSet, newRootDepth = targetDepth)
    val remaining = ArrayList<OptimisticLayerPlacement>(nodes.size)
    var insertAtInRemaining = 0
    for (i in nodes.indices) {
        if (i == rawInsertPos) {
            insertAtInRemaining = remaining.size
        }
        if (i !in movedSet) {
            remaining.add(OptimisticLayerPlacement(originalIndex = i, newDepth = nodes[i].depth))
        }
    }
    if (rawInsertPos >= nodes.size) {
        insertAtInRemaining = remaining.size
    }

    remaining.addAll(insertAtInRemaining, movedBlocks)
    return remaining
}

/**
 * 将 [movedIndices] (若为组则自动携带其子孙节点) 移动到 [groupIndex] 内部顶部。
 */
fun computeOptimisticMoveToGroup(
    nodes: List<LayerOrderNode>,
    movedIndices: List<Int>,
    groupIndex: Int,
): List<OptimisticLayerPlacement>? {
    if (nodes.isEmpty() || groupIndex !in nodes.indices || movedIndices.isEmpty()) return null
    if (!nodes[groupIndex].isGroup) return null
    val movedSet = buildMovedSubtreeIndexSet(nodes, movedIndices)
    if (movedSet.isEmpty() || groupIndex in movedSet) return null

    val targetDepth = nodes[groupIndex].depth + 1
    val rawInsertPos = subtreeEnd(nodes, groupIndex)

    val movedBlocks = extractMovedBlocks(nodes, movedIndices, movedSet, newRootDepth = targetDepth)
    val remaining = ArrayList<OptimisticLayerPlacement>(nodes.size)
    var insertAtInRemaining = 0
    for (i in nodes.indices) {
        if (i == rawInsertPos) {
            insertAtInRemaining = remaining.size
        }
        if (i !in movedSet) {
            remaining.add(OptimisticLayerPlacement(originalIndex = i, newDepth = nodes[i].depth))
        }
    }
    if (rawInsertPos >= nodes.size) {
        insertAtInRemaining = remaining.size
    }

    remaining.addAll(insertAtInRemaining, movedBlocks)
    return remaining
}

private fun buildMovedSubtreeIndexSet(
    nodes: List<LayerOrderNode>,
    movedIndices: List<Int>,
): Set<Int> {
    val set = HashSet<Int>()
    for (idx in movedIndices) {
        if (idx !in nodes.indices || idx == 0) continue
        val end = subtreeEnd(nodes, idx)
        for (k in idx until end) {
            set.add(k)
        }
    }
    return set
}

private fun extractMovedBlocks(
    nodes: List<LayerOrderNode>,
    movedIndices: List<Int>,
    movedSet: Set<Int>,
    newRootDepth: Int,
): List<OptimisticLayerPlacement> {
    val result = ArrayList<OptimisticLayerPlacement>(movedSet.size)
    val emitted = HashSet<Int>(movedSet.size)
    // 按原引擎顺序提取顶级被移动块，保持相对先后关系与子孙深度差
    val sortedRoots = movedIndices.filter { it in movedSet }.sorted()
    for (rootIdx in sortedRoots) {
        if (rootIdx in emitted) continue
        val end = subtreeEnd(nodes, rootIdx)
        val depthDelta = newRootDepth - nodes[rootIdx].depth
        for (k in rootIdx until end) {
            if (emitted.add(k)) {
                result.add(
                    OptimisticLayerPlacement(
                        originalIndex = k,
                        newDepth = (nodes[k].depth + depthDelta).coerceAtLeast(0),
                    ),
                )
            }
        }
    }
    return result
}
