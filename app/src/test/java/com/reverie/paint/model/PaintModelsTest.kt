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

    @Test
    fun `lasso submodes are well-defined and distinct`() {
        val modes = listOf(LassoSubMode.FREEHAND, LassoSubMode.POLYLINE, LassoSubMode.HYBRID)
        assertEquals(3, modes.toSet().size)
    }

    @Test
    fun `selection tools belong to SELECTION group`() {
        val selTools = listOf(
            Tool.SELECT_RECT,
            Tool.SELECT_ELLIPSE,
            Tool.SELECT_POLYGON,
            Tool.LASSO,
            Tool.MAGICWAND,
            Tool.SELECT_SIMILAR
        )
        selTools.forEach { tool ->
            assertEquals(ToolGroup.SELECTION, tool.group)
        }
    }

    @Test
    fun `lasso segment undo pops points by segment count`() {
        val points = mutableListOf(10 to 10, 20 to 20, 21 to 21, 22 to 22, 30 to 30)
        val segmentCounts = mutableListOf(1, 3, 1) // Tap (1), Drag (3), Tap (1)

        // Undo tap (1 pt)
        val count1 = segmentCounts.removeAt(segmentCounts.lastIndex)
        val newSize1 = (points.size - count1).coerceAtLeast(0)
        val remaining1 = points.take(newSize1).toMutableList()
        assertEquals(4, remaining1.size)
        assertEquals(listOf(10 to 10, 20 to 20, 21 to 21, 22 to 22), remaining1)

        // Undo drag (3 pts)
        val count2 = segmentCounts.removeAt(segmentCounts.lastIndex)
        val newSize2 = (remaining1.size - count2).coerceAtLeast(0)
        val remaining2 = remaining1.take(newSize2).toMutableList()
        assertEquals(1, remaining2.size)
        assertEquals(listOf(10 to 10), remaining2)

        // Undo initial tap (1 pt)
        val count3 = segmentCounts.removeAt(segmentCounts.lastIndex)
        val newSize3 = (remaining2.size - count3).coerceAtLeast(0)
        val remaining3 = remaining2.take(newSize3).toMutableList()
        assertTrue(remaining3.isEmpty())
        assertTrue(segmentCounts.isEmpty())
    }
}
