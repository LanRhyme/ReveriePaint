/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class LayerOrderMathTest {

    @Test
    fun `move upper layer below lower layer above background`() {
        // Engine order (bottom-to-top): [0: Bg, 1: L1, 2: L2]
        val nodes = listOf(
            LayerOrderNode(id = 100L, depth = 0, isGroup = false),
            LayerOrderNode(id = 101L, depth = 0, isGroup = false),
            LayerOrderNode(id = 102L, depth = 0, isGroup = false),
        )
        val moved = computeOptimisticMoveRelative(nodes, listOf(2), targetIndex = 1, placeAbove = false)
        assertNotNull(moved)
        assertEquals(listOf(0, 2, 1), moved!!.map { it.originalIndex })
        assertEquals(listOf(0, 0, 0), moved.map { it.newDepth })
    }

    @Test
    fun `move lower layer above upper layer`() {
        // Engine order: [0: Bg, 1: L1, 2: L2]
        val nodes = listOf(
            LayerOrderNode(id = 100L, depth = 0, isGroup = false),
            LayerOrderNode(id = 101L, depth = 0, isGroup = false),
            LayerOrderNode(id = 102L, depth = 0, isGroup = false),
        )
        val moved = computeOptimisticMoveRelative(nodes, listOf(1), targetIndex = 2, placeAbove = true)
        assertNotNull(moved)
        assertEquals(listOf(0, 2, 1), moved!!.map { it.originalIndex })
    }

    @Test
    fun `move layer into group updates depth and places at top of group`() {
        // Engine order: [0: Bg, 1: Group, 2: Child1(d=1), 3: TopLayer(d=0)]
        val nodes = listOf(
            LayerOrderNode(id = 100L, depth = 0, isGroup = false),
            LayerOrderNode(id = 101L, depth = 0, isGroup = true),
            LayerOrderNode(id = 102L, depth = 1, isGroup = false),
            LayerOrderNode(id = 103L, depth = 0, isGroup = false),
        )
        val moved = computeOptimisticMoveToGroup(nodes, listOf(3), groupIndex = 1)
        assertNotNull(moved)
        assertEquals(listOf(0, 1, 2, 3), moved!!.map { it.originalIndex })
        assertEquals(listOf(0, 0, 1, 1), moved.map { it.newDepth })
    }

    @Test
    fun `move group carries its children subtree together`() {
        // Engine order: [0: Bg, 1: L1, 2: Group, 3: Child(d=1)]
        val nodes = listOf(
            LayerOrderNode(id = 100L, depth = 0, isGroup = false),
            LayerOrderNode(id = 101L, depth = 0, isGroup = false),
            LayerOrderNode(id = 102L, depth = 0, isGroup = true),
            LayerOrderNode(id = 103L, depth = 1, isGroup = false),
        )
        // Move Group below L1
        val moved = computeOptimisticMoveRelative(nodes, listOf(2), targetIndex = 1, placeAbove = false)
        assertNotNull(moved)
        assertEquals(listOf(0, 2, 3, 1), moved!!.map { it.originalIndex })
        assertEquals(listOf(0, 0, 1, 0), moved.map { it.newDepth })
    }
}
