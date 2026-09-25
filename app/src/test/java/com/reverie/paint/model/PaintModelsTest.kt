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

    @Test
    fun `import filename deduplication appends progressive index`() {
        val existingFiles = setOf("我的作品.revp", "我的作品 (1).revp")
        val baseName = "我的作品"
        var candidate = baseName
        var counter = 1
        while ("$candidate.revp" in existingFiles) {
            candidate = "$baseName ($counter)"
            counter++
        }
        assertEquals("我的作品 (2)", candidate)
    }

    @Test
    fun `layer selection pruning retains only valid layer indices`() {
        val totalLayers = 4 // indices: 0 (bg), 1, 2, 3
        val staleSelection = setOf(0, 1, 3, 5, -1)
        val pruned = staleSelection.filter { it in 1 until totalLayers }.toSet()
        assertEquals(setOf(1, 3), pruned)
    }

    @Test
    fun `swipe gesture classification rejects vertical dominant scroll`() {
        val touchSlop = 8f
        // Vertical diagonal scroll: dx=9, dy=12 (touchSlop exceeded, but vertical dominant)
        val dx1 = 9f
        val dy1 = 12f
        val isSwipe1 = kotlin.math.abs(dx1) > touchSlop && kotlin.math.abs(dx1) > kotlin.math.abs(dy1) * 1.25f
        assertTrue(!isSwipe1)

        // Clear horizontal swipe: dx=25, dy=8
        val dx2 = 25f
        val dy2 = 8f
        val isSwipe2 = kotlin.math.abs(dx2) > touchSlop && kotlin.math.abs(dx2) > kotlin.math.abs(dy2) * 1.25f
        assertTrue(isSwipe2)
    }
}
