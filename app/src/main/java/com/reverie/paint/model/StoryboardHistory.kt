/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.reverie.paint.model

/** Metadata that associates a sidecar storyboard edit with one native undo/redo entry. */
internal data class StoryboardHistoryEntry<T>(
    val generation: Int,
    val undoCountAfter: Int,
    val label: String,
    val payload: T,
    val undone: Boolean = false,
)

/** Pure matching helpers keep sidecar history aligned with the native undo stack. */
internal object StoryboardHistory {
    fun <T> discardRedoBranch(entries: List<StoryboardHistoryEntry<T>>): List<StoryboardHistoryEntry<T>> =
        entries.filterNot { it.undone }

    fun <T> append(
        entries: List<StoryboardHistoryEntry<T>>,
        entry: StoryboardHistoryEntry<T>,
    ): List<StoryboardHistoryEntry<T>> =
        discardRedoBranch(entries) + entry

    fun <T> undoIndex(
        entries: List<StoryboardHistoryEntry<T>>,
        generation: Int,
        undoCountBefore: Int,
        label: String,
    ): Int = entries.indexOfLast {
        it.generation == generation &&
            !it.undone &&
            it.undoCountAfter == undoCountBefore &&
            it.label == label
    }

    fun <T> redoIndex(
        entries: List<StoryboardHistoryEntry<T>>,
        generation: Int,
        undoCountCurrent: Int,
        label: String,
    ): Int = entries.indexOfLast {
        it.generation == generation &&
            it.undone &&
            it.undoCountAfter - 1 == undoCountCurrent &&
            it.label == label
    }

    fun <T> markUndone(
        entries: List<StoryboardHistoryEntry<T>>,
        index: Int,
        undone: Boolean,
    ): List<StoryboardHistoryEntry<T>> {
        if (index !in entries.indices) return entries
        return entries.toMutableList().also { it[index] = it[index].copy(undone = undone) }
    }
}
