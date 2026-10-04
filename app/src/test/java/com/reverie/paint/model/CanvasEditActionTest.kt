/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.reverie.paint.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CanvasEditActionTest {
    @Test fun `existing installs keep cut copy paste in order`() {
        assertEquals("cut,copy,paste", CanvasEditAction.encode(CanvasEditAction.decode(null)))
    }

    @Test fun `mixed menu keeps user order across persistence`() {
        val stored = "quick:undo,paste,quick:toggle_eraser,copy"
        assertEquals(stored, CanvasEditAction.encode(CanvasEditAction.decode(stored)))
    }

    @Test fun `all quick actions are available without colliding with clipboard actions`() {
        val actions = CanvasEditAction.available
        assertEquals(QuickAction.entries.size + 3, actions.size)
        assertEquals(actions.size, actions.map { it.id }.distinct().size)
        assertEquals(actions, CanvasEditAction.decode(CanvasEditAction.encode(actions)))
    }

    @Test fun `unknown and duplicate IDs are ignored without changing surviving order`() {
        assertEquals(
            "quick:redo,cut,paste",
            CanvasEditAction.encode(CanvasEditAction.decode("unknown, quick:redo,cut,quick:redo,,paste")),
        )
    }

    @Test fun `empty or entirely invalid preferences recover a usable menu`() {
        for (stored in listOf("", "  ", "future_action,obsolete_action")) {
            assertEquals(CanvasEditAction.defaults, CanvasEditAction.decode(stored))
        }
        assertEquals("cut,copy,paste", CanvasEditAction.encode(emptyList()))
    }

    @Test fun `menu may consist of shortcuts only`() {
        val menu = CanvasEditAction.decode("quick:undo")
        assertEquals(listOf(CanvasEditAction.Shortcut(QuickAction.UNDO)), menu)
        assertTrue(menu.none { it is CanvasEditAction.Clipboard })
    }
}
