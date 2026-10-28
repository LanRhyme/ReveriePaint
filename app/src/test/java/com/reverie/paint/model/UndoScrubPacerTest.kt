/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UndoScrubPacerTest {

    /** 模拟撤回条追赶泵: 逐步取步-标记在途-落定, 返回实际执行的方向序列 */
    private fun drain(pacer: UndoScrubPacer): List<Int> {
        val executed = mutableListOf<Int>()
        var guard = 0
        while (guard++ < 1000) {
            val step = pacer.nextStep()
            if (step == 0) break
            pacer.beginStep(step)
            executed.add(step)
            pacer.onStepDone()
        }
        return executed
    }

    @Test
    fun `reset 用真实 undoCount 初始化位置并合成 max`() {
        val pacer = UndoScrubPacer()
        pacer.reset(3, 2)
        assertEquals(5, pacer.max)
        assertEquals(3, pacer.position)
        assertEquals(3, pacer.target)
        assertFalse(pacer.hasPendingWork)
    }

    @Test
    fun `reset 负数入参被钳到零`() {
        val pacer = UndoScrubPacer()
        pacer.reset(-1, -5)
        assertEquals(0, pacer.max)
        assertEquals(0, pacer.position)
    }

    @Test
    fun `dragTarget 越界被 clamp 到 0 与 max`() {
        val pacer = UndoScrubPacer()
        pacer.reset(2, 3)
        pacer.dragTarget(-7)
        assertEquals(0, pacer.target)
        pacer.dragTarget(99)
        assertEquals(5, pacer.target)
        pacer.dragTarget(4)
        assertEquals(4, pacer.target)
    }

    @Test
    fun `目标左移产生连续 undo 步直到追平`() {
        val pacer = UndoScrubPacer()
        pacer.reset(3, 1)
        pacer.dragTarget(1)
        assertEquals(listOf(-1, -1), drain(pacer))
        assertEquals(1, pacer.position)
    }

    @Test
    fun `目标右移产生连续 redo 步直到追平`() {
        val pacer = UndoScrubPacer()
        pacer.reset(2, 3)
        pacer.dragTarget(4)
        assertEquals(listOf(1, 1), drain(pacer))
        assertEquals(4, pacer.position)
    }

    @Test
    fun `连续拖动多目标中途改向仍收敛`() {
        val pacer = UndoScrubPacer()
        pacer.reset(2, 2)
        pacer.dragTarget(4)
        // 第一步 redo 在途时用户把目标一下拖回 0
        pacer.beginStep(pacer.nextStep())
        assertEquals(0, pacer.nextStep()) // 在途, 不发新步
        pacer.dragTarget(0)
        pacer.onStepDone()
        assertEquals(3, pacer.position) // 已发出的那步 redo 仍按原方向推进
        assertEquals(listOf(-1, -1, -1), drain(pacer))
        assertEquals(0, pacer.position)
    }

    @Test
    fun `追平后 nextStep 返回零`() {
        val pacer = UndoScrubPacer()
        pacer.reset(2, 2)
        pacer.dragTarget(2)
        assertEquals(0, pacer.nextStep())
        pacer.dragTarget(5)
        drain(pacer)
        assertEquals(0, pacer.nextStep())
        assertEquals(4, pacer.position) // target 5 被 clamp 到 max=4
    }

    @Test
    fun `onStepDone 按发出时锁定的方向推进`() {
        val pacer = UndoScrubPacer()
        pacer.reset(2, 2)
        pacer.dragTarget(3)
        pacer.beginStep(pacer.nextStep())
        // 在途时把目标拖过中线, 本步方向不变
        pacer.dragTarget(0)
        pacer.onStepDone()
        assertEquals(3, pacer.position)
    }

    @Test
    fun `在途时 nextStep 为零且重复 beginStep 被忽略`() {
        val pacer = UndoScrubPacer()
        pacer.reset(2, 2)
        pacer.dragTarget(3)
        pacer.beginStep(pacer.nextStep())
        assertTrue(pacer.hasPendingWork)
        assertEquals(0, pacer.nextStep())
        pacer.beginStep(-1) // 重复标记被忽略
        pacer.onStepDone()
        assertEquals(3, pacer.position) // 仍按 +1 只推进一步
        assertFalse(pacer.hasPendingWork)
    }

    @Test
    fun `beginStep 非法方向被忽略`() {
        val pacer = UndoScrubPacer()
        pacer.reset(1, 1)
        pacer.beginStep(0)
        pacer.beginStep(7)
        assertFalse(pacer.hasPendingWork)
        assertEquals(0, pacer.nextStep()) // 目标未拖, 无步
    }

    @Test
    fun `alignTo 重建 max 与 position 但保留用户目标`() {
        val pacer = UndoScrubPacer()
        pacer.reset(3, 2)
        pacer.dragTarget(0)
        // 泵外路径动过历史 (如又画了一笔), 真实计数变为 undo=1 redo=1
        pacer.alignTo(1, 1)
        assertEquals(2, pacer.max)
        assertEquals(1, pacer.position)
        assertEquals(0, pacer.target)
        assertEquals(listOf(-1), drain(pacer))
        assertEquals(0, pacer.position)
    }

    @Test
    fun `alignTo 目标越界被 clamp 到新 max`() {
        val pacer = UndoScrubPacer()
        pacer.reset(1, 1)
        pacer.dragTarget(2)
        pacer.alignTo(2, 0) // redo 被泵外 undo 吸收, max 缩为 2
        assertEquals(2, pacer.max)
        assertEquals(2, pacer.position)
        assertEquals(2, pacer.target)
    }

    @Test
    fun `alignTo 负数入参被钳到零`() {
        val pacer = UndoScrubPacer()
        pacer.reset(2, 1)
        pacer.alignTo(-1, -1)
        assertEquals(0, pacer.max)
        assertEquals(0, pacer.position)
    }

    @Test
    fun `alignTo 在途时被忽略避免双计`() {
        val pacer = UndoScrubPacer()
        pacer.reset(3, 1)
        pacer.dragTarget(2)
        pacer.beginStep(pacer.nextStep())
        pacer.alignTo(0, 0) // 在途, 忽略
        pacer.onStepDone()
        assertEquals(2, pacer.position) // 3 - 1, 只算在途步
    }

    @Test
    fun `reset 清空在途与旧目标`() {
        val pacer = UndoScrubPacer()
        pacer.reset(2, 2)
        pacer.dragTarget(0)
        pacer.beginStep(pacer.nextStep())
        pacer.reset(4, 0)
        assertFalse(pacer.hasPendingWork)
        assertEquals(4, pacer.target)
        assertEquals(4, pacer.position)
        assertEquals(0, pacer.nextStep())
    }

    @Test
    fun `空历史下拖动不产生任何步`() {
        val pacer = UndoScrubPacer()
        pacer.reset(0, 0)
        pacer.dragTarget(5)
        assertEquals(0, pacer.target)
        assertTrue(drain(pacer).isEmpty())
    }
}
