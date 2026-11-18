/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

/**
 * Pure list-reordering helpers for brush panel drag-and-drop.
 * Keeps drag-time swaps purely in memory and maps the final dragged slot
 * back onto the full preset list without per-frame disk persistence.
 */
object BrushReorderLogic {
    /**
     * Moves [fromIndex] to [toIndex] in [items], returning null when indices are
     * invalid or identical.
     */
    fun <T> moveItem(items: List<T>, fromIndex: Int, toIndex: Int): List<T>? {
        if (fromIndex == toIndex || fromIndex !in items.indices || toIndex !in items.indices) {
            return null
        }
        return items.toMutableList().apply {
            val item = removeAt(fromIndex)
            add(toIndex, item)
        }
    }

    /**
     * Applies the final position of [draggedKey] from [reorderedSubsetKeys] back onto
     * [fullItems], preserving the relative order of items outside the subset.
     */
    fun <T> commitMovedItemByKey(
        fullItems: List<T>,
        draggedKey: String,
        reorderedSubsetKeys: List<String>,
        keySelector: (T) -> String,
    ): List<T> {
        val targetIdxInView = reorderedSubsetKeys.indexOf(draggedKey)
        if (targetIdxInView < 0) return fullItems
        val fromIdx = fullItems.indexOfFirst { keySelector(it) == draggedKey }
        if (fromIdx < 0) return fullItems

        val mutable = fullItems.toMutableList()
        val movedItem = mutable.removeAt(fromIdx)
        val afterKey = reorderedSubsetKeys.getOrNull(targetIdxInView + 1)
        val beforeKey = reorderedSubsetKeys.getOrNull(targetIdxInView - 1)
        val insertIdx = when {
            afterKey != null -> mutable.indexOfFirst { keySelector(it) == afterKey }.takeIf { it >= 0 }
            beforeKey != null -> mutable.indexOfFirst { keySelector(it) == beforeKey }.takeIf { it >= 0 }?.plus(1)
            else -> null
        }
        if (insertIdx != null) {
            mutable.add(insertIdx.coerceIn(0, mutable.size), movedItem)
        } else {
            mutable.add(fromIdx.coerceIn(0, mutable.size), movedItem)
        }
        return mutable
    }
}
