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

    @Test
    fun `issue 193 - moving upper layer does not touch or steal lower layer mask`() {
        // Issue #193:
        // [0: Bg, 1: L1, 2: L1_Mask(d=1, mask), 3: L2, 4: L3]
        val nodes = listOf(
            LayerOrderNode(id = 100L, depth = 0, isGroup = false),
            LayerOrderNode(id = 101L, depth = 0, isGroup = false),
            LayerOrderNode(id = 102L, depth = 1, isGroup = false, isMask = true),
            LayerOrderNode(id = 103L, depth = 0, isGroup = false),
            LayerOrderNode(id = 104L, depth = 0, isGroup = false),
        )

        // 1. Verify attached mask collection for L2 (index 3) is empty (does not steal index 2)
        val l2Masks = collectAttachedMaskIndices(nodes, 3)
        assertEquals(emptyList<Int>(), l2Masks)

        // 2. Verify attached mask collection for L1 (index 1) correctly identifies index 2
        val l1Masks = collectAttachedMaskIndices(nodes, 1)
        assertEquals(listOf(2), l1Masks)

        // 3. Move L2 (index 3) above L3 (index 4)
        val moved = computeOptimisticMoveRelative(nodes, listOf(3), targetIndex = 4, placeAbove = true)
        assertNotNull(moved)
        // Original order [0:Bg, 1:L1, 2:L1_Mask, 3:L2, 4:L3] -> moving 3 above 4 results in [0, 1, 2, 4, 3]
        assertEquals(listOf(0, 1, 2, 4, 3), moved!!.map { it.originalIndex })
        assertEquals(listOf(0, 0, 1, 0, 0), moved.map { it.newDepth })
    }

    @Test
    fun `moving layer with attached mask carries mask together and places above target`() {
        // [0: Bg, 1: L1, 2: L1_Mask(d=1, mask), 3: L2, 4: L3]
        val nodes = listOf(
            LayerOrderNode(id = 100L, depth = 0, isGroup = false),
            LayerOrderNode(id = 101L, depth = 0, isGroup = false),
            LayerOrderNode(id = 102L, depth = 1, isGroup = false, isMask = true),
            LayerOrderNode(id = 103L, depth = 0, isGroup = false),
            LayerOrderNode(id = 104L, depth = 0, isGroup = false),
        )

        val l1Masks = collectAttachedMaskIndices(nodes, 1)
        val effective = listOf(1) + l1Masks
        assertEquals(listOf(1, 2), effective)

        // Move L1 and its mask above L3 (index 4)
        val moved = computeOptimisticMoveRelative(nodes, effective, targetIndex = 4, placeAbove = true)
        assertNotNull(moved)
        assertEquals(listOf(0, 3, 4, 1, 2), moved!!.map { it.originalIndex })
        assertEquals(listOf(0, 0, 0, 0, 1), moved.map { it.newDepth })
    }

    @Test
    fun `collectAttachedMaskIndices collects all consecutive masks and stops at next sibling`() {
        val nodes = listOf(
            LayerOrderNode(id = 100L, depth = 0, isGroup = false),
            LayerOrderNode(id = 101L, depth = 0, isGroup = false),
            LayerOrderNode(id = 102L, depth = 1, isGroup = false, isMask = true), // transparency mask
            LayerOrderNode(id = 103L, depth = 1, isGroup = false, isMask = true), // filter mask
            LayerOrderNode(id = 104L, depth = 0, isGroup = false), // next layer
        )
        assertEquals(listOf(2, 3), collectAttachedMaskIndices(nodes, 1))
        assertEquals(emptyList<Int>(), collectAttachedMaskIndices(nodes, 4))
        assertEquals(emptyList<Int>(), collectAttachedMaskIndices(nodes, 2)) // mask itself has no attached masks
    }
}
