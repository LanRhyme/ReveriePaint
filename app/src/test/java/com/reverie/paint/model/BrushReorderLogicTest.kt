/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BrushReorderLogicTest {

    private data class Item(val name: String, val group: String)

    @Test
    fun `moveItem moves upward and downward cleanly`() {
        val list = listOf("A", "B", "C", "D")
        assertEquals(listOf("D", "A", "B", "C"), BrushReorderLogic.moveItem(list, 3, 0))
        assertEquals(listOf("B", "C", "A", "D"), BrushReorderLogic.moveItem(list, 0, 2))
    }

    @Test
    fun `moveItem returns null on no-op or out-of-bounds indices`() {
        val list = listOf("A", "B", "C")
        assertNull(BrushReorderLogic.moveItem(list, 1, 1))
        assertNull(BrushReorderLogic.moveItem(list, -1, 1))
        assertNull(BrushReorderLogic.moveItem(list, 1, 5))
    }

    @Test
    fun `commitMovedItemByKey moves item to top of filtered subset without disturbing other groups`() {
        val full = listOf(
            Item("A", "g1"),
            Item("B", "g2"),
            Item("C", "g1"),
            Item("D", "g2"),
            Item("E", "g1"),
        )
        val reorderedSubset = listOf("E", "A", "C")
        val committed = BrushReorderLogic.commitMovedItemByKey(full, "E", reorderedSubset) { it.name }
        assertEquals(listOf("E", "A", "B", "C", "D"), committed.map { it.name })
        assertEquals(listOf("E", "A", "C"), committed.filter { it.group == "g1" }.map { it.name })
        assertEquals(listOf("B", "D"), committed.filter { it.group == "g2" }.map { it.name })
    }

    @Test
    fun `commitMovedItemByKey moves item to end of filtered subset`() {
        val full = listOf(
            Item("A", "g1"),
            Item("B", "g2"),
            Item("C", "g1"),
            Item("D", "g2"),
            Item("E", "g1"),
        )
        val reorderedSubset = listOf("C", "E", "A")
        val committed = BrushReorderLogic.commitMovedItemByKey(full, "A", reorderedSubset) { it.name }
        assertEquals(listOf("B", "C", "D", "E", "A"), committed.map { it.name })
        assertEquals(listOf("C", "E", "A"), committed.filter { it.group == "g1" }.map { it.name })
    }

    @Test
    fun `commitMovedItemByKey moves item into middle of filtered subset`() {
        val full = listOf(
            Item("A", "g1"),
            Item("B", "g2"),
            Item("C", "g1"),
            Item("D", "g2"),
            Item("E", "g1"),
        )
        val reorderedSubset = listOf("A", "E", "C")
        val committed = BrushReorderLogic.commitMovedItemByKey(full, "E", reorderedSubset) { it.name }
        assertEquals(listOf("A", "B", "E", "C", "D"), committed.map { it.name })
        assertEquals(listOf("A", "E", "C"), committed.filter { it.group == "g1" }.map { it.name })
    }

    @Test
    fun `commitMovedItemByKey preserves full list on single-item or missing subset`() {
        val full = listOf(Item("A", "g1"), Item("B", "g2"))
        assertEquals(full, BrushReorderLogic.commitMovedItemByKey(full, "A", listOf("A")) { it.name })
        assertEquals(full, BrushReorderLogic.commitMovedItemByKey(full, "MISSING", listOf("A")) { it.name })
    }
}
