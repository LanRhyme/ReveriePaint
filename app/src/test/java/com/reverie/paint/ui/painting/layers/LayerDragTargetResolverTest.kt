/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.layers

import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.model.LayerNodeType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class LayerDragTargetResolverTest {

    private fun mockLayer(
        index: Int,
        depth: Int,
        isGroup: Boolean = false,
        name: String = "Layer $index",
        locked: Boolean = false,
        isBackground: Boolean = false,
        nodeType: Int = 0,
    ) = PaintViewModel.LayerUiState(
        index = index,
        name = name,
        visible = true,
        locked = locked,
        alphaLocked = false,
        isGroup = isGroup,
        nodeType = nodeType,
        depth = depth,
        colorLabel = 0,
        clipped = false,
        isBackground = isBackground,
        soloed = false,
        opacity = 1.0,
        blendMode = "normal",
    )

    @Test
    fun `single layer drag above and below another layer`() {
        val l1 = mockLayer(1, depth = 0)
        val l2 = mockLayer(2, depth = 0)
        val remaining = listOf(l2, l1) // display order: 2 on top, 1 below
        val bounds = mapOf(
            2 to (0f to 50f),
            1 to (50f to 100f),
        )

        // Hover over top half of l2 (y = 15f) -> above l2
        val targetAbove = resolveLayerDropTarget(
            fingerX = 100f,
            fingerY = 15f,
            dragStartX = 100f,
            listLeft = 0f,
            listTop = 0f,
            listWidth = 300f,
            rowHeightPx = 50f,
            remainingRows = remaining,
            rowBounds = bounds,
            allLayers = remaining,
        )
        assertNotNull(targetAbove)
        assertEquals(2, targetAbove?.anchorIndex)
        assertEquals(LayerDropPlacement.ABOVE, targetAbove?.placement)
        assertEquals(0, targetAbove?.visualDepth)
        assertEquals(0, targetAbove?.visualSlot)

        // Hover over bottom half of l2 (y = 35f) -> below l2
        val targetBelow = resolveLayerDropTarget(
            fingerX = 100f,
            fingerY = 35f,
            dragStartX = 100f,
            listLeft = 0f,
            listTop = 0f,
            listWidth = 300f,
            rowHeightPx = 50f,
            remainingRows = remaining,
            rowBounds = bounds,
            allLayers = remaining,
        )
        assertNotNull(targetBelow)
        assertEquals(2, targetBelow?.anchorIndex)
        assertEquals(LayerDropPlacement.BELOW, targetBelow?.placement)
        assertEquals(0, targetBelow?.visualDepth)
        assertEquals(1, targetBelow?.visualSlot)
    }

    @Test
    fun `透明度蒙版行不能作为 BELOW 落点锚 - issue 216`() {
        // 显示顺序 (顶到底): 图层1 / 蒙版(深度1) / 图层组1 / 图层2 / 图层组2
        val host = mockLayer(3, depth = 0, name = "图层1")
        val mask = mockLayer(4, depth = 1, name = "蒙版 图层1", nodeType = LayerNodeType.TRANSPARENCY_MASK)
        val group1 = mockLayer(0, depth = 0, isGroup = true, name = "图层组1")
        val child = mockLayer(1, depth = 1, name = "图层2")
        val group2 = mockLayer(2, depth = 1, isGroup = true, name = "图层组2")
        val remaining = listOf(host, mask, group1, child, group2)
        val bounds = mapOf(
            3 to (0f to 50f),
            4 to (50f to 100f),
            0 to (100f to 150f),
            1 to (150f to 200f),
            2 to (200f to 250f),
        )

        // 悬停在蒙版行下半区、手指向右偏移偏深层: 旧逻辑会给出 anchor=蒙版 BELOW,
        // 引擎按"插到蒙版下方"执行, 结果把图层组2 插进宿主与蒙版之间 (蒙版改跟图层组2)
        val target = resolveLayerDropTarget(
            fingerX = 300f,
            fingerY = 90f,
            dragStartX = 0f,
            listLeft = 0f,
            listTop = 0f,
            listWidth = 300f,
            rowHeightPx = 50f,
            remainingRows = remaining,
            rowBounds = bounds,
            allLayers = remaining,
        )
        assertNotNull(target)
        assertEquals("落点必须是宿主图层, 不能以蒙版为锚", host.index, target!!.anchorIndex)
        assertEquals(LayerDropPlacement.BELOW, target.placement)
    }

    @Test
    fun `蒙版行的父组沿宿主图层继续向上查找`() {
        // 显示顺序: 组 / 宿主图层 / 蒙版
        val group = mockLayer(10, depth = 0, isGroup = true)
        val host = mockLayer(11, depth = 1)
        val mask = mockLayer(12, depth = 2, nodeType = LayerNodeType.TRANSPARENCY_MASK)
        val rows = listOf(group, host, mask)
        assertEquals(10, findParentGroupIndex(rows, 2))
        // 宿主在根级时没有父组
        assertEquals(null, findParentGroupIndex(listOf(host, mask), 1))
    }

    @Test
    fun `group 3-zone fraction detection - above, inside, below`() {
        val group = mockLayer(10, depth = 0, isGroup = true, name = "Folder")
        val child = mockLayer(11, depth = 1, name = "Child")
        val rootBelow = mockLayer(1, depth = 0, name = "Root")
        val remaining = listOf(group, child, rootBelow)
        val bounds = mapOf(
            10 to (0f to 100f),
            11 to (100f to 200f),
            1 to (200f to 300f),
        )

        // Top 20% of group (y = 15f) -> ABOVE group
        val targetAbove = resolveLayerDropTarget(
            fingerX = 100f,
            fingerY = 15f,
            dragStartX = 100f,
            listLeft = 0f,
            listTop = 0f,
            listWidth = 300f,
            rowHeightPx = 100f,
            remainingRows = remaining,
            rowBounds = bounds,
            allLayers = remaining,
        )
        assertNotNull(targetAbove)
        assertEquals(10, targetAbove?.anchorIndex)
        assertEquals(LayerDropPlacement.ABOVE, targetAbove?.placement)

        // Middle 50% of group (y = 50f) -> INSIDE group
        val targetInside = resolveLayerDropTarget(
            fingerX = 100f,
            fingerY = 50f,
            dragStartX = 100f,
            listLeft = 0f,
            listTop = 0f,
            listWidth = 300f,
            rowHeightPx = 100f,
            remainingRows = remaining,
            rowBounds = bounds,
            allLayers = remaining,
        )
        assertNotNull(targetInside)
        assertEquals(10, targetInside?.anchorIndex)
        assertEquals(LayerDropPlacement.INSIDE, targetInside?.placement)
        assertEquals(1, targetInside?.visualDepth)
        assertEquals(10, targetInside?.parentGroupIndex)

        // Bottom 20% of group (y = 85f) -> BELOW group and all its children!
        val targetBelow = resolveLayerDropTarget(
            fingerX = 100f,
            fingerY = 85f,
            dragStartX = 100f,
            listLeft = 0f,
            listTop = 0f,
            listWidth = 300f,
            rowHeightPx = 100f,
            remainingRows = remaining,
            rowBounds = bounds,
            allLayers = remaining,
        )
        assertNotNull(targetBelow)
        assertEquals(10, targetBelow?.anchorIndex)
        assertEquals(LayerDropPlacement.BELOW, targetBelow?.placement)
        assertEquals(0, targetBelow?.visualDepth)
        // visualSlot should skip child (index 1) and be placed after child (slot 2)
        assertEquals(2, targetBelow?.visualSlot)
    }

    @Test
    fun `horizontal drag disambiguation at group exit seam`() {
        val group = mockLayer(10, depth = 0, isGroup = true)
        val child = mockLayer(11, depth = 1)
        val root = mockLayer(1, depth = 0)
        val remaining = listOf(group, child, root)
        val bounds = mapOf(
            10 to (0f to 100f),
            11 to (100f to 200f),
            1 to (200f to 300f),
        )

        // Hover at seam below child (y = 175f, lower half of child):
        // Possible candidates:
        // - depth 1: below child (inside group 10)
        // - depth 0: below group 10 (root level)

        // 1. Dragging with neutral horizontal offset (startX = 100f, fingerX = 100f) -> natural continuation (depth 1)
        val targetNeutral = resolveLayerDropTarget(
            fingerX = 100f,
            fingerY = 175f,
            dragStartX = 100f,
            listLeft = 0f,
            listTop = 0f,
            listWidth = 300f,
            rowHeightPx = 100f,
            remainingRows = remaining,
            rowBounds = bounds,
            allLayers = remaining,
            horizontalThresholdPx = 20f,
        )
        assertNotNull(targetNeutral)
        assertEquals(1, targetNeutral?.visualDepth)
        assertEquals(11, targetNeutral?.anchorIndex)
        assertEquals(10, targetNeutral?.parentGroupIndex)

        // 2. Dragging to the left (fingerX = 50f, offset = -50f < -20f) -> outdent to depth 0!
        val targetLeft = resolveLayerDropTarget(
            fingerX = 50f,
            fingerY = 175f,
            dragStartX = 100f,
            listLeft = 0f,
            listTop = 0f,
            listWidth = 300f,
            rowHeightPx = 100f,
            remainingRows = remaining,
            rowBounds = bounds,
            allLayers = remaining,
            horizontalThresholdPx = 20f,
        )
        assertNotNull(targetLeft)
        assertEquals(0, targetLeft?.visualDepth)
        assertEquals(10, targetLeft?.anchorIndex) // below group 10
        assertNull(targetLeft?.parentGroupIndex)
    }

    @Test
    fun `outside panel returns null to trigger spring back`() {
        val l1 = mockLayer(1, depth = 0)
        val remaining = listOf(l1)
        val bounds = mapOf(1 to (0f to 50f))

        val outside = resolveLayerDropTarget(
            fingerX = 500f, // listWidth is 300f
            fingerY = 25f,
            dragStartX = 100f,
            listLeft = 0f,
            listTop = 0f,
            listWidth = 300f,
            rowHeightPx = 50f,
            remainingRows = remaining,
            rowBounds = bounds,
            allLayers = remaining,
        )
        assertNull(outside)
    }

    @Test
    fun `dragging below bottom protects background layer by placing above it`() {
        val l1 = mockLayer(1, depth = 0)
        val bg = mockLayer(0, depth = 0, isBackground = true)
        val remaining = listOf(l1, bg)
        val bounds = mapOf(
            1 to (0f to 50f),
            0 to (50f to 100f),
        )

        // Drop in empty area below background layer (y = 150f > 100f)
        val target = resolveLayerDropTarget(
            fingerX = 100f,
            fingerY = 150f,
            dragStartX = 100f,
            listLeft = 0f,
            listTop = 0f,
            listWidth = 300f,
            rowHeightPx = 50f,
            remainingRows = remaining,
            rowBounds = bounds,
            allLayers = remaining,
        )
        assertNotNull(target)
        // Must be placed ABOVE background layer (index 0) so background remains at bottom
        assertEquals(0, target?.anchorIndex)
        assertEquals(LayerDropPlacement.ABOVE, target?.placement)
    }

    @Test
    fun `resolveDisplayDropSlot lands between bottom regular layer and background when dragging upper layer`() {
        val l2 = mockLayer(2, depth = 0)
        val l1 = mockLayer(1, depth = 0)
        val bg = mockLayer(0, depth = 0, isBackground = true)
        val displayRows = listOf(l2, l1, bg)
        val remaining = listOf(l1, bg)
        val bounds = mapOf(
            2 to (0f to 50f),
            1 to (50f to 100f),
            0 to (100f to 150f),
        )

        // Hover bottom half of l1 (y = 85f) -> BELOW l1, slot in displayRows must be 2 (above bg)
        val belowL1 = resolveLayerDropTarget(
            fingerX = 100f,
            fingerY = 85f,
            dragStartX = 100f,
            listLeft = 0f,
            listTop = 0f,
            listWidth = 300f,
            rowHeightPx = 50f,
            remainingRows = remaining,
            rowBounds = bounds,
            allLayers = listOf(bg, l1, l2),
        )
        assertNotNull(belowL1)
        assertEquals(2, resolveDisplayDropSlot(belowL1!!, displayRows))

        // Hover on background layer (y = 135f) -> ABOVE bg, slot in displayRows must also be 2
        val aboveBg = resolveLayerDropTarget(
            fingerX = 100f,
            fingerY = 135f,
            dragStartX = 100f,
            listLeft = 0f,
            listTop = 0f,
            listWidth = 300f,
            rowHeightPx = 50f,
            remainingRows = remaining,
            rowBounds = bounds,
            allLayers = listOf(bg, l1, l2),
        )
        assertNotNull(aboveBg)
        assertEquals(2, resolveDisplayDropSlot(aboveBg!!, displayRows))
    }

    @Test
    fun `resolveDisplayDropSlot skips dragged group subtree and maps group exit seam accurately`() {
        val draggedGroup = mockLayer(10, depth = 0, isGroup = true)
        val draggedChild = mockLayer(9, depth = 1)
        val group2 = mockLayer(4, depth = 0, isGroup = true)
        val group2Child = mockLayer(3, depth = 1)
        val bg = mockLayer(0, depth = 0, isBackground = true)
        val displayRows = listOf(draggedGroup, draggedChild, group2, group2Child, bg)

        // Placing BELOW group2 (display index 2, with child at 3) must land at slot 4 (right above bg)
        val belowGroup2 = LayerDragTarget(
            anchorIndex = 4,
            placement = LayerDropPlacement.BELOW,
            parentGroupIndex = null,
            visualDepth = 0,
            visualSlot = 2,
        )
        assertEquals(4, resolveDisplayDropSlot(belowGroup2, displayRows))

        // Placing ABOVE bg (display index 4) must also land at slot 4
        val aboveBg = LayerDragTarget(
            anchorIndex = 0,
            placement = LayerDropPlacement.ABOVE,
            parentGroupIndex = null,
            visualDepth = 0,
            visualSlot = 2,
        )
        assertEquals(4, resolveDisplayDropSlot(aboveBg, displayRows))
    }
}

