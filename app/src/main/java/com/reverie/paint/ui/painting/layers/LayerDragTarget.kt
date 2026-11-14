/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.layers

import com.reverie.paint.core.PaintViewModel
import kotlin.math.abs

/**
 * 图层放置相对位置。
 */
enum class LayerDropPlacement {
    /** 放置在锚点图层视觉上方 (同父级) */
    ABOVE,
    /** 放置在锚点图层视觉下方 (同父级) */
    BELOW,
    /** 放置在组图层内部 (作为组的子图层) */
    INSIDE,
}

/**
 * 图层拖拽放置目标计算结果 (参考 Mangar 的 LayerDragTarget 设计)。
 *
 * @param anchorIndex 放置参照的目标图层引擎索引 (用于 moveLayersRelative 或 moveLayersToGroup)
 * @param placement 放置位置: ABOVE / BELOW / INSIDE
 * @param parentGroupIndex 放置后的父图层组引擎索引 (null 表示位于画布根级)
 * @param visualDepth 放置指示线的视觉缩进层级 (0 = 根级, 1 = 一级子层, 2 = 二级子层...)
 * @param visualSlot 放置指示线在 displayRows 列表中的缝隙索引 (0 = 最顶行上方, k = displayRows[k] 上方)
 */
data class LayerDragTarget(
    val anchorIndex: Int,
    val placement: LayerDropPlacement,
    val parentGroupIndex: Int?,
    val visualDepth: Int,
    val visualSlot: Int,
)

/**
 * 获取指定图层集合在图层树中的所有子孙图层索引 (包含多层嵌套)。
 */
internal fun getAllDescendantIndices(
    indices: Collection<Int>,
    layers: List<PaintViewModel.LayerUiState>,
): Set<Int> {
    val result = mutableSetOf<Int>()
    for (idx in indices) {
        if (idx !in layers.indices) continue
        val l = layers[idx]
        if (!l.isGroup) continue
        val e = (idx + 1 until layers.size).firstOrNull { layers[it].depth <= l.depth } ?: layers.size
        for (k in (idx + 1 until e)) {
            result.add(k)
        }
    }
    return result
}

/**
 * 在 displayRows 列表中查找指定行 (位于 visualIndex) 的父组索引。
 */
internal fun findParentGroupIndex(
    rows: List<PaintViewModel.LayerUiState>,
    visualIndex: Int,
): Int? {
    if (visualIndex !in rows.indices) return null
    val target = rows[visualIndex]
    if (target.depth <= 0) return null
    val expectedParentDepth = target.depth - 1
    for (i in visualIndex - 1 downTo 0) {
        val r = rows[i]
        if (r.depth == expectedParentDepth && r.isGroup) {
            return r.index
        }
    }
    return null
}

/**
 * 计算图层拖拽落点目标 (参考 Mangar ContentView 中的 dropTarget 算法)。
 *
 * 核心优化:
 * 1. 组行三段式判定 (Mangar 3-zone fraction):
 *    - 顶部 25%: 放置于组上方;
 *    - 中间 50%: 放置于组内部 (INSIDE);
 *    - 底部 25%: 放置于整组 (含展开的子图层) 下方。
 * 2. 跨层级缝隙多候选 disambiguation (nextDepth..maximumDepth):
 *    - 当在层级跳变处 (例如从嵌套组底部回到外层) 悬停时，计算所有合法的深度候选;
 *    - 默认保持原有深度或缝隙自然深度;
 *    - 手指横向左右移动时直接切换内外层级，实时更新指示线缩进与父组高亮，告别误出组/误入组。
 */
internal fun resolveLayerDropTarget(
    fingerX: Float,
    fingerY: Float,
    dragStartX: Float,
    listLeft: Float,
    listTop: Float,
    listWidth: Float,
    rowHeightPx: Float,
    remainingRows: List<PaintViewModel.LayerUiState>,
    rowBounds: Map<Int, Pair<Float, Float>>,
    allLayers: List<PaintViewModel.LayerUiState>,
    panelRootGroupIdx: Int = -1,
    horizontalThresholdPx: Float = 36f,
): LayerDragTarget? {
    if (remainingRows.isEmpty()) return null

    // 拖出列表两侧容差范围 (80px) 判定为取消 / 离开有效区域
    val horizontalTolerance = 80f
    if (fingerX < listLeft - horizontalTolerance || fingerX > listLeft + listWidth + horizontalTolerance) {
        return null
    }

    val lastRow = remainingRows.last()
    val lastBound = rowBounds[lastRow.index]
    val lastBottom = lastBound?.second ?: (listTop + remainingRows.size * rowHeightPx)

    // 1. 手指位于列表所有可见行下方 (空白区域)
    if (fingerY >= lastBottom) {
        return dropTargetAtBoundary(
            boundary = remainingRows.size - 1,
            baseDepth = 0,
            horizontalOffset = fingerX - dragStartX,
            horizontalThresholdPx = horizontalThresholdPx,
            remainingRows = remainingRows,
            allLayers = allLayers,
            panelRootGroupIdx = panelRootGroupIdx,
        )
    }

    // 2. 查找手指命中哪一行
    var matchIndex = -1
    for (i in remainingRows.indices) {
        val row = remainingRows[i]
        val b = rowBounds[row.index]
        if (b != null) {
            if (fingerY in b.first..b.second) {
                matchIndex = i
                break
            }
        } else {
            val estTop = listTop + i * rowHeightPx
            val estBottom = estTop + rowHeightPx
            if (fingerY in estTop..estBottom) {
                matchIndex = i
                break
            }
        }
    }

    // 3. 手指在首行上方
    if (fingerY <= listTop) {
        return dropTargetAtBoundary(
            boundary = -1,
            baseDepth = 0,
            horizontalOffset = fingerX - dragStartX,
            horizontalThresholdPx = horizontalThresholdPx,
            remainingRows = remainingRows,
            allLayers = allLayers,
            panelRootGroupIdx = panelRootGroupIdx,
        )
    }

    if (matchIndex < 0) {
        // 估算最近行
        val estVisual = ((fingerY - listTop) / rowHeightPx.coerceAtLeast(1f)).toInt().coerceIn(0, remainingRows.size - 1)
        matchIndex = estVisual
    }

    val targetRow = remainingRows[matchIndex]
    val b = rowBounds[targetRow.index]
    val rowTop = b?.first ?: (listTop + matchIndex * rowHeightPx)
    val rowBottom = b?.second ?: (rowTop + rowHeightPx)
    val rowH = (rowBottom - rowTop).coerceAtLeast(1f)
    val fraction = ((fingerY - rowTop) / rowH).coerceIn(0f, 1f)

    // 三段式/二段式判定 boundary 与 baseDepth
    val boundary: Int
    val baseDepth: Int
    if (targetRow.isGroup) {
        when {
            fraction in 0.25f..0.75f -> {
                // 组行中间 50%: 尝试直接放置到组内
                val isLocked = targetRow.locked
                val isPanelRoot = targetRow.index == panelRootGroupIdx
                if (!isLocked && !isPanelRoot) {
                    return LayerDragTarget(
                        anchorIndex = targetRow.index,
                        placement = LayerDropPlacement.INSIDE,
                        parentGroupIndex = targetRow.index,
                        visualDepth = targetRow.depth + 1,
                        visualSlot = matchIndex,
                    )
                }
                // 无法放置进组时退化为上方/下方
                boundary = if (fraction < 0.5f) matchIndex - 1 else matchIndex
                baseDepth = targetRow.depth
            }
            fraction < 0.25f -> {
                boundary = matchIndex - 1
                baseDepth = targetRow.depth
            }
            else -> {
                // 组行下部: 跳过组所有展开的子层，放置在整组下方
                val lastDesc = remainingRows.drop(matchIndex + 1).takeWhile { it.depth > targetRow.depth }.lastOrNull() ?: targetRow
                boundary = remainingRows.indexOfFirst { it.index == lastDesc.index }
                baseDepth = targetRow.depth
            }
        }
    } else {
        if (fraction < 0.5f) {
            boundary = matchIndex - 1
            baseDepth = targetRow.depth
        } else {
            boundary = matchIndex
            baseDepth = targetRow.depth
        }
    }

    return dropTargetAtBoundary(
        boundary = boundary,
        baseDepth = baseDepth,
        horizontalOffset = fingerX - dragStartX,
        horizontalThresholdPx = horizontalThresholdPx,
        remainingRows = remainingRows,
        allLayers = allLayers,
        panelRootGroupIdx = panelRootGroupIdx,
    )
}

/**
 * 在 remainingRows 列表的 boundary 缝隙处计算最佳候选放置目标。
 */
private fun dropTargetAtBoundary(
    boundary: Int,
    baseDepth: Int,
    horizontalOffset: Float,
    horizontalThresholdPx: Float,
    remainingRows: List<PaintViewModel.LayerUiState>,
    allLayers: List<PaintViewModel.LayerUiState>,
    panelRootGroupIdx: Int,
): LayerDragTarget? {
    if (remainingRows.isEmpty()) return null

    // 位于最顶行上方
    if (boundary < 0) {
        val first = remainingRows[0]
        return LayerDragTarget(
            anchorIndex = first.index,
            placement = LayerDropPlacement.ABOVE,
            parentGroupIndex = findParentGroupIndex(remainingRows, 0),
            visualDepth = first.depth,
            visualSlot = 0,
        )
    }

    val previous = remainingRows[boundary]
    val nextDepth = if (boundary + 1 < remainingRows.size) remainingRows[boundary + 1].depth else 0
    val maximumDepth = previous.depth + (if (previous.isGroup) 1 else 0)
    val clampedNextDepth = nextDepth.coerceAtMost(maximumDepth)

    val candidates = mutableListOf<LayerDragTarget>()
    for (d in clampedNextDepth..maximumDepth) {
        val anchor: PaintViewModel.LayerUiState
        val placement: LayerDropPlacement
        val parentGroupIdx: Int?

        if (d == previous.depth + 1) {
            anchor = previous
            placement = LayerDropPlacement.INSIDE
            parentGroupIdx = previous.index
        } else {
            val prefix = remainingRows.take(boundary + 1)
            val matching = prefix.lastOrNull { it.depth == d } ?: continue
            anchor = matching
            placement = LayerDropPlacement.BELOW
            val matchingIdxInRows = prefix.indexOf(matching)
            parentGroupIdx = findParentGroupIndex(remainingRows, matchingIdxInRows)
        }

        // 过滤不可用候选:
        // 1. 父图层被锁定
        if (parentGroupIdx != null && allLayers.getOrNull(parentGroupIdx)?.locked == true) continue
        // 2. 严禁放置进分格根组
        if (placement == LayerDropPlacement.INSIDE && anchor.index == panelRootGroupIdx) continue

        // 3. 背景层保护: ReveriePaint 中背景层恒为底层，不能置于背景层下方
        val adjustedPlacement = if (anchor.isBackground && placement == LayerDropPlacement.BELOW) {
            LayerDropPlacement.ABOVE
        } else {
            placement
        }

        // 计算视觉指示线应该绘制在 displayRows 的哪个 slot
        val anchorVisualIdx = remainingRows.indexOfFirst { it.index == anchor.index }.coerceAtLeast(0)
        val visualSlot = if (adjustedPlacement == LayerDropPlacement.ABOVE) {
            anchorVisualIdx
        } else {
            val lastDesc = remainingRows.drop(anchorVisualIdx + 1).takeWhile { it.depth > anchor.depth }.lastOrNull() ?: anchor
            remainingRows.indexOfFirst { it.index == lastDesc.index } + 1
        }

        candidates.add(
            LayerDragTarget(
                anchorIndex = anchor.index,
                placement = adjustedPlacement,
                parentGroupIndex = parentGroupIdx,
                visualDepth = d,
                visualSlot = visualSlot,
            )
        )
    }

    if (candidates.isEmpty()) return null

    // 手指横向偏移 disambiguation:
    // - 偏移在阈值内: 保持与 baseDepth 最接近的层级 (自然连贯无突变)
    // - 向左拖出阈值: 偏向浅层 (移出图层组)
    // - 向右拖出阈值: 偏向深层 (移入图层组)
    return when {
        abs(horizontalOffset) < horizontalThresholdPx -> {
            candidates.minByOrNull { abs(it.visualDepth - baseDepth) } ?: candidates.first()
        }
        horizontalOffset < -horizontalThresholdPx -> {
            candidates.minByOrNull { it.visualDepth } ?: candidates.first()
        }
        else -> {
            candidates.maxByOrNull { it.visualDepth } ?: candidates.last()
        }
    }
}
