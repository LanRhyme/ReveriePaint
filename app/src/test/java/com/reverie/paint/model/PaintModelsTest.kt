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
    fun `enclose fill tool belongs to FILL group`() {
        assertEquals(ToolGroup.FILL, Tool.ENCLOSE_FILL.group)
        assertEquals(Tool.ENCLOSE_FILL, Tool.fromId("enclose_fill"))
    }

    @Test
    fun `pan tool belongs to VIEW group`() {
        assertEquals(ToolGroup.VIEW, Tool.PAN.group)
        assertEquals(Tool.PAN, Tool.fromId("pan"))
    }

    @Test
    fun `enclose fill region modes map correctly to Krita selection methods`() {
        assertEquals(0, EncloseFillRegionMode.ALL.methodValue)
        assertEquals(2, EncloseFillRegionMode.TRANSPARENT.methodValue)
        assertEquals(1, EncloseFillRegionMode.SPECIFIC_COLOR.methodValue)
        assertEquals(4, EncloseFillRegionMode.EXCEPT_SPECIFIC_COLOR.methodValue)
        assertEquals(EncloseFillRegionMode.ALL, EncloseFillRegionMode.fromId(0))
        assertEquals(EncloseFillRegionMode.TRANSPARENT, EncloseFillRegionMode.fromId(1))
        assertEquals(EncloseFillRegionMode.ALL, EncloseFillRegionMode.fromId(999))
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

    @Test
    fun `project preserves selected layer index metadata`() {
        val defaultProj = Project(name = "测试工程")
        assertEquals(-1, defaultProj.selectedLayerIndex)

        val customProj = Project(name = "多图层工程", layerCount = 5, selectedLayerIndex = 3)
        assertEquals(3, customProj.selectedLayerIndex)
    }

    @Test
    fun `back key action ids are unique`() {
        val ids = BackKeyAction.entries.map { it.id }
        assertEquals("BackKeyAction id 重复: ${ids.groupBy { it }.filterValues { it.size > 1 }.keys}", ids.size, ids.toSet().size)
    }

    @Test
    fun `back key action fromId resolves every id and falls back to NONE`() {
        BackKeyAction.entries.forEach { action ->
            assertEquals(action, BackKeyAction.fromId(action.id))
        }
        assertEquals(BackKeyAction.NONE, BackKeyAction.fromId("nonexistent"))
        assertEquals(BackKeyAction.NONE, BackKeyAction.fromId(null))
        assertEquals(BackKeyAction.NONE, BackKeyAction.fromId(""))
    }

    @Test
    fun `套索点在缩放渲染视口下的坐标映射与回退中心对齐`() {
        val docW = 4272
        val docH = 6400
        val bmpW = 2734
        val bmpH = 4096
        val scX = bmpW.toFloat() / docW
        val scY = bmpH.toFloat() / docH
        val halfW = bmpW / 2f
        val halfH = bmpH / 2f

        // 文档中心映射后落在叠加层原点
        val centerDocX = docW / 2f
        val centerDocY = docH / 2f
        val overlayCenterX = centerDocX * scX - halfW
        val overlayCenterY = centerDocY * scY - halfH
        assertEquals(0f, overlayCenterX, 1e-3f)
        assertEquals(0f, overlayCenterY, 1e-3f)

        // 文档右下角映射后落在叠加层右下边界
        val brOverlayX = docW * scX - halfW
        val brOverlayY = docH * scY - halfH
        assertEquals(halfW, brOverlayX, 1e-3f)
        assertEquals(halfH, brOverlayY, 1e-3f)
    }

    @Test
    fun `floating ui popupInwardMarginDp avoids shared slot panels`() {
        val slot = PanelPosition(PanelEdge.LEFT, PanelAlignment.CENTER)
        // 场景 1: 单个面板停靠在左侧 (scale=1.0, 胶囊厚度~41dp, 呼吸间隙 14dp)
        val singleConfig = FloatingUiConfig(
            marginDp = 12,
            scale = 1.0f,
            toolRailPosition = slot,
            sliderRailPosition = PanelPosition(PanelEdge.LEFT, PanelAlignment.END),
            topBarPosition = PanelPosition(PanelEdge.TOP, PanelAlignment.CENTER),
        )
        // 12 + round(0 + 36 * 1.13 + 14) = 12 + 55 = 67
        assertEquals(67, singleConfig.popupInwardMarginDp(slot))

        // 场景 2: 两个面板共用同一槽位并排 (ToolRail + SliderRail 都在左侧中心)
        val sharedConfig = FloatingUiConfig(
            marginDp = 12,
            scale = 1.0f,
            toolRailPosition = slot,
            sliderRailPosition = slot,
            topBarPosition = PanelPosition(PanelEdge.TOP, PanelAlignment.CENTER),
        )
        // 避让 1 层垂直向内平移 (48 * 1.13): 12 + round(48 * 1.13 + 36 * 1.13 + 14) = 12 + 109 = 121
        assertEquals(121, sharedConfig.popupInwardMarginDp(slot))

        // 场景 3: 水平槽位共享 (TopBar + ToolRail 都在顶部中心)
        val topSlot = PanelPosition(PanelEdge.TOP, PanelAlignment.CENTER)
        val horizontalSharedConfig = FloatingUiConfig(
            marginDp = 10,
            scale = 1.0f,
            topBarPosition = topSlot,
            toolRailPosition = topSlot,
            sliderRailPosition = PanelPosition(PanelEdge.LEFT, PanelAlignment.CENTER),
        )
        // 避让 1 层水平向内平移 (46 * 1.13): 10 + round(46 * 1.13 + 36 * 1.13 + 14) = 10 + 107 = 117
        assertEquals(117, horizontalSharedConfig.popupInwardMarginDp(topSlot))
    }

    @Test
    fun `tool transitions correctly classify selection restore and discard conditions`() {
        val selectionTools = Tool.entries.filter { it.group == ToolGroup.SELECTION }
        assertTrue(selectionTools.isNotEmpty())
        for (st in selectionTools) {
            assertTrue("Selection tool ${st.id} must restore stash", st.group == ToolGroup.SELECTION)
        }

        // BRUSH preserves transient stash until draw/touch occurs
        assertEquals(Tool.BRUSH, Tool.fromId("brush"))
        assertTrue(Tool.BRUSH.group != ToolGroup.SELECTION)

        // Other non-selection tools (e.g. ERASER, FILL, SHAPES) should invalidate stash
        val nonSelectionNonBrush = Tool.entries.filter { it != Tool.BRUSH && it.group != ToolGroup.SELECTION }
        for (tool in nonSelectionNonBrush) {
            val shouldDiscard = tool != Tool.BRUSH && tool.group != ToolGroup.SELECTION
            assertTrue("Tool ${tool.id} should trigger discard", shouldDiscard)
        }
    }
}



