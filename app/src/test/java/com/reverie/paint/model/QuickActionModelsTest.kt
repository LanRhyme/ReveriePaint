/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class QuickActionModelsTest {

    @Test
    fun `quick action ids are unique`() {
        val ids = QuickAction.entries.map { it.id }
        assertEquals("QuickAction id 重复", ids.size, ids.toSet().size)
    }

    @Test
    fun `fromId resolves all quick action types`() {
        QuickAction.entries.forEach { action ->
            assertEquals(action, QuickAction.fromId(action.id))
        }
        assertNull(QuickAction.fromId("unknown_action"))
    }

    @Test
    fun `quick color and layer actions exist and have valid ids`() {
        assertNotNull(QuickAction.fromId("toggle_quick_color"))
        assertNotNull(QuickAction.fromId("toggle_quick_layer"))
        assertEquals(QuickAction.TOGGLE_QUICK_COLOR, QuickAction.fromId("toggle_quick_color"))
        assertEquals(QuickAction.TOGGLE_QUICK_LAYER, QuickAction.fromId("toggle_quick_layer"))
    }

    @Test
    fun `quick action layout modes resolve correctly`() {
        QuickActionLayoutMode.entries.forEach { mode ->
            assertEquals(mode, QuickActionLayoutMode.fromId(mode.id))
        }
        assertEquals(QuickActionLayoutMode.COLUMN, QuickActionLayoutMode.fromId("invalid"))
    }
}
