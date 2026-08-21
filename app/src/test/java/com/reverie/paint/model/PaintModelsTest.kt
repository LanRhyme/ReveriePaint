/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PaintModelsTest {

    @Test
    fun `tool ids are unique`() {
        val ids = Tool.entries.map { it.id }
        assertEquals("Tool id 重复: ${ids.groupBy { it }.filterValues { it.size > 1 }.keys}", ids.size, ids.toSet().size)
    }

    @Test
    fun `fromId resolves every tool id and falls back to BRUSH`() {
        Tool.entries.forEach { tool ->
            assertEquals(tool, Tool.fromId(tool.id))
        }
        assertEquals(Tool.BRUSH, Tool.fromId("nonexistent"))
    }

    @Test
    fun `every tool belongs to a group`() {
        Tool.entries.forEach { tool ->
            assertTrue(tool.group.name.isNotBlank())
        }
    }
}
