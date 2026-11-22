/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 图层行内交互的纯换算测试 (见 [LayerRowInteractionMath])。
 *
 * 拖拽换算与"父组隐藏"传播都是判定逻辑: 真机上只能看到"某一次拖动的结果",
 * 边界 (clamp / 深度跳跃 / 隐藏组的兄弟节点) 必须靠用例守住。
 */
class LayerRowInteractionMathTest {

    // ==================== 不透明度药丸拖动换算 ====================

    @Test
    fun `不透明度拖动按位移线性换算并夹在 0 到 100`() {
        assertEquals(50, layerOpacityScrubPercent(50, 0f, 400f))
        assertEquals(75, layerOpacityScrubPercent(50, 100f, 400f))
        assertEquals(25, layerOpacityScrubPercent(50, -100f, 400f))
        assertEquals(100, layerOpacityScrubPercent(50, 400f, 400f))
        assertEquals(0, layerOpacityScrubPercent(50, -400f, 400f))
    }

    @Test
    fun `不透明度拖动超界时夹住, 手指退回立即跟回`() {
        assertEquals(100, layerOpacityScrubPercent(90, 500f, 200f))
        // 退回 10px = 5 个百分点, 数值立刻跟回而不是留在上限
        assertEquals(85, layerOpacityScrubPercent(90, -10f, 200f))
        assertEquals(0, layerOpacityScrubPercent(10, -500f, 200f))
    }

    @Test
    fun `不透明度拖动对退化输入不崩, 保持起手值`() {
        assertEquals(50, layerOpacityScrubPercent(50, 40f, 0f))
        assertEquals(50, layerOpacityScrubPercent(50, 40f, -10f))
        assertEquals(50, layerOpacityScrubPercent(50, 40f, Float.NaN))
        assertEquals(50, layerOpacityScrubPercent(50, Float.POSITIVE_INFINITY, 200f))
        // 起手值越界时先夹住, 再参与换算
        assertEquals(100, layerOpacityScrubPercent(150, 0f, 200f))
    }

    // ==================== 父组隐藏传播 ====================

    @Test
    fun `全部可见时没有任何行被标记`() {
        val hidden = layersHiddenByHiddenGroup(
            depths = listOf(0, 1, 1, 2),
            visible = listOf(true, true, true, true),
        )
        assertArrayEquals(booleanArrayOf(false, false, false, false), hidden)
    }

    @Test
    fun `隐藏的组把整棵子树标记为祖先隐藏`() {
        // 组(0) 隐藏 -> 子层(1) 与孙层(2) 都点着眼睛却看不见
        val hidden = layersHiddenByHiddenGroup(
            depths = listOf(0, 1, 1, 2),
            visible = listOf(false, true, true, true),
        )
        assertArrayEquals(booleanArrayOf(false, true, true, true), hidden)
    }

    @Test
    fun `自身隐藏的行不算祖先隐藏`() {
        val hidden = layersHiddenByHiddenGroup(
            depths = listOf(0, 1, 1, 2),
            visible = listOf(true, false, false, false),
        )
        assertArrayEquals(booleanArrayOf(false, false, false, false), hidden)
    }

    @Test
    fun `隐藏组不影响其后的兄弟子树`() {
        // 组A(0) 隐藏 / 子A(1) / 组B(0) / 子B(1)
        val hidden = layersHiddenByHiddenGroup(
            depths = listOf(0, 1, 0, 1),
            visible = listOf(false, true, true, true),
        )
        assertArrayEquals(booleanArrayOf(false, true, false, false), hidden)
    }

    @Test
    fun `深层嵌套里被隐藏的是中间组时, 只影响它下面的节点`() {
        // 根组(0) / 子组(1) 隐藏 / 孙层(2) / 孙层(2)
        val hidden = layersHiddenByHiddenGroup(
            depths = listOf(0, 1, 2, 2),
            visible = listOf(true, false, true, true),
        )
        assertArrayEquals(booleanArrayOf(false, false, true, true), hidden)
    }

    @Test
    fun `蒙版深度是所属图层加一, 按所属图层判定祖先`() {
        // 组(0) 可见 / 图层(1) / 蒙版(2) / 组(0) 隐藏 / 图层(1) / 蒙版(2)
        val hidden = layersHiddenByHiddenGroup(
            depths = listOf(0, 1, 2, 0, 1, 2),
            visible = listOf(true, true, true, false, true, true),
        )
        assertArrayEquals(booleanArrayOf(false, false, false, false, true, true), hidden)
    }

    // ==================== 单行可编辑性 (落笔门禁) ====================

    @Test
    fun `蒙版可编辑性只看自身与宿主祖先, 不跟随下方无关组 - issue 215`() {
        // 引擎层序 (底到顶): 组1(0,隐藏) / 图层2(1) / 图层1(0) / 蒙版(1)
        val depths = listOf(0, 1, 0, 1)
        val visible = listOf(false, true, true, true)
        val groups = listOf(true, false, false, false)
        val masks = listOf(false, false, false, true)
        fun hiddenAt(i: Int) = isLayerEffectivelyHiddenAt(
            index = i,
            layerCount = depths.size,
            depthAt = { depths[it] },
            visibleAt = { visible[it] },
            groupAt = { groups[it] },
            maskAt = { masks[it] },
        )
        // 蒙版与宿主可见; 下方隐藏组不是它们的祖先
        assertFalse(hiddenAt(3))
        assertFalse(hiddenAt(2))
        // 组1 的子层确实被隐藏
        assertTrue(hiddenAt(1))
        // 自身隐藏仍然不可编辑
        assertTrue(hiddenAt(0))
    }

    @Test
    fun `宿主位于隐藏组内时蒙版跟随祖先组隐藏`() {
        // 组(0,隐藏) / 宿主(1) / 蒙版(2)
        val depths = listOf(0, 1, 2)
        val visible = listOf(false, true, true)
        val groups = listOf(true, false, false)
        val masks = listOf(false, false, true)
        fun hiddenAt(i: Int) = isLayerEffectivelyHiddenAt(
            index = i,
            layerCount = depths.size,
            depthAt = { depths[it] },
            visibleAt = { visible[it] },
            groupAt = { groups[it] },
            maskAt = { masks[it] },
        )
        assertTrue(hiddenAt(2))
    }

    @Test
    fun `宿主自身隐藏时蒙版不可编辑`() {
        val depths = listOf(0, 1)
        val visible = listOf(false, true)
        fun hiddenAt(i: Int) = isLayerEffectivelyHiddenAt(
            index = i,
            layerCount = depths.size,
            depthAt = { depths[it] },
            visibleAt = { visible[it] },
            groupAt = { false },
            maskAt = { it == 1 },
        )
        assertTrue(hiddenAt(1))
    }

    @Test
    fun `越界或深度异常时保持可编辑`() {
        assertFalse(
            isLayerEffectivelyHiddenAt(
                index = -1,
                layerCount = 3,
                depthAt = { 0 },
                visibleAt = { true },
                groupAt = { false },
                maskAt = { false },
            ),
        )
    }

    @Test
    fun `深度跳跃等异常输入按祖先可见处理`() {
        val hidden = layersHiddenByHiddenGroup(
            depths = listOf(0, 2, 2),
            visible = listOf(true, true, true),
        )
        assertArrayEquals(booleanArrayOf(false, false, false), hidden)
        // 空输入不崩
        assertEquals(0, layersHiddenByHiddenGroup(emptyList(), emptyList()).size)
        // visible 比 depths 短时按不可见处理, 不越界
        assertArrayEquals(
            booleanArrayOf(false, false),
            layersHiddenByHiddenGroup(listOf(0, 1), listOf(true)),
        )
    }
}
