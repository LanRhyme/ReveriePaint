/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.reverie.paint.model

/** The swipe menu has its own order; it never changes the floating quick-action window. */
sealed interface CanvasEditAction {
    val id: String

    enum class Clipboard(override val id: String, val capability: Int) : CanvasEditAction {
        CUT("cut", 2), COPY("copy", 1), PASTE("paste", 4),
    }

    data class Shortcut(val action: QuickAction) : CanvasEditAction {
        override val id: String get() = "quick:${action.id}"
    }

    companion object {
        const val PREFERENCE_KEY = "canvas_edit_menu_actions"
        val defaults: List<CanvasEditAction> = Clipboard.entries.toList()
        val available: List<CanvasEditAction> = defaults + QuickAction.entries.map(::Shortcut)

        /** Tolerate unknown IDs from other versions and keep the first occurrence of each action. */
        fun decode(value: String?): List<CanvasEditAction> {
            val ids = value?.split(',')?.map(String::trim).orEmpty()
            return ids.mapNotNull { id -> available.find { it.id == id } }
                .distinct().ifEmpty { defaults }
        }

        fun encode(actions: List<CanvasEditAction>): String =
            actions.distinct().ifEmpty { defaults }.joinToString(",") { it.id }
    }
}
