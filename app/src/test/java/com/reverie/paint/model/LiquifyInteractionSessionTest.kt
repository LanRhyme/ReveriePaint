/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 3A: [LiquifyInteractionSession] 的纯逻辑单测(AGENTS.md §8)。
 *
 * 关注三件事: ①latest-state-wins 的调度上限; ②backlog / lag 指标口径; ③"分帧只改节奏、
 * 不改总量"—— 强度折算与补点步长始终按**整段**(planTotalSteps)计算。
 */
class LiquifyInteractionSessionTest {

    private fun session(cap: Int, x: Float = 0f, y: Float = 0f): LiquifyInteractionSession =
        LiquifyInteractionSession().apply { begin(x, y, cap) }

    @Test
    fun `begin stores cap and zeroes counters`() {
        val s = session(5)
        assertTrue(s.coalescing)
        assertEquals(5, s.maxDabsPerFlush)
        assertEquals(0L, s.inputSequence)
        assertEquals(0L, s.renderedSequence)
        assertEquals(0L, s.lag)
        assertEquals(0L, s.inputCount)
        assertEquals(0L, s.flushCount)
        assertEquals(0L, s.dabCount)
        assertEquals(0L, s.operationCount)
        assertFalse(s.hasPending)
    }

    @Test
    fun `zero cap means no coalescing`() {
        val s = session(0)
        assertFalse(s.coalescing)
    }

    @Test
    fun `submitTarget advances input sequence and lag`() {
        val s = session(2)
        s.submitTarget(10f, 0f)
        s.submitTarget(20f, 0f)
        assertEquals(2L, s.inputSequence)
        assertEquals(2L, s.inputCount)
        assertEquals(2L, s.lag)
        assertTrue(s.hasPending)
        assertEquals(20f, s.targetX, 1e-6f)
    }

    @Test
    fun `prepareFlush without a pending target does nothing`() {
        val s = session(2)
        assertFalse(s.prepareFlush(60f, LiquifyPath.MODE_PUSH, forceFull = false))
        assertEquals(0, s.planSteps)
    }

    @Test
    fun `force full flush covers the whole segment`() {
        val s = session(2)
        s.submitTarget(120f, 0f)
        assertTrue(s.prepareFlush(60f, LiquifyPath.MODE_PUSH, forceFull = true))
        val full = LiquifyPath.substepCount(120f, 60f)
        assertEquals(full, s.planTotalSteps)
        assertEquals("forceFull 不受每帧上限约束", full, s.planSteps)
        s.advanceFlush(s.planSteps)
        assertEquals(120f, s.renderedX, 1e-3f)
        assertFalse(s.hasPending)
        assertEquals(0, s.backlogDabs)
    }

    @Test
    fun `coalescing caps steps per flush and keeps backlog`() {
        val s = session(2)
        s.submitTarget(300f, 0f)
        assertTrue(s.prepareFlush(60f, LiquifyPath.MODE_PUSH, forceFull = false))
        assertEquals(12, s.planTotalSteps) // 300/18 = 17 -> 截到 MAX_SUBSTEPS
        assertEquals("每帧最多推进 cap 个补点", 2, s.planSteps)
        // 强度折算与步长按整段计算 —— 分帧不改变总量口径
        assertEquals(300f / s.planTotalSteps, s.planStepX, 1e-4f)

        s.advanceFlush(s.planSteps)
        assertTrue("还没追上最新位置", s.hasPending)
        assertTrue("仍有未提交补点", s.backlogDabs > 0)
        assertTrue("已推进了一段但未到终点", s.renderedX > 0f && s.renderedX < 300f)
        assertEquals("推进后 lag 归零", 0L, s.lag)
        assertEquals(2L, s.dabCount)
        assertEquals(1L, s.flushCount)
        assertEquals(1L, s.operationCount)
        assertEquals("本帧合并了 1 个输入", 1, s.lastFlushInputs)
    }

    @Test
    fun `repeated flushes keep chasing and a final full flush lands exactly`() {
        val s = session(2)
        s.submitTarget(300f, 0f)
        var firstX = 0f
        repeat(3) {
            if (s.prepareFlush(60f, LiquifyPath.MODE_PUSH, forceFull = false)) {
                s.advanceFlush(s.planSteps)
            }
            if (firstX == 0f) firstX = s.renderedX
        }
        assertTrue("多次推进后应离起点更远", s.renderedX > firstX)
        assertTrue("仍应有未提交的 backlog", s.hasPending)

        // 抬笔补齐: forceFull 必须精确落到目标
        assertTrue(s.prepareFlush(60f, LiquifyPath.MODE_PUSH, forceFull = true))
        s.advanceFlush(s.planSteps)
        assertEquals(300f, s.renderedX, 1e-3f)
        assertEquals(0, s.backlogDabs)
        assertFalse(s.hasPending)
    }

    @Test
    fun `tiny movement snaps to target without a flush`() {
        val s = session(2)
        s.submitTarget(0.2f, 0f)
        assertFalse("位移小于阈值时不推进", s.prepareFlush(60f, LiquifyPath.MODE_PUSH, forceFull = false))
        assertEquals(0.2f, s.renderedX, 1e-6f)
        assertFalse(s.hasPending)
        assertEquals("吸附后 lag 归零", 0L, s.lag)
        assertEquals(0L, s.flushCount)
    }

    @Test
    fun `push mode needs no strength compensation`() {
        val s = session(2)
        s.submitTarget(120f, 0f)
        s.prepareFlush(60f, LiquifyPath.MODE_PUSH, forceFull = false)
        assertEquals(1f, s.planStrengthScale, 1e-6f)
    }

    @Test
    fun `radial modes are rescaled by the total step count`() {
        val s = session(2)
        s.submitTarget(120f, 0f)
        s.prepareFlush(60f, 1, forceFull = false)
        val full = LiquifyPath.substepCount(120f, 60f)
        assertEquals(
            LiquifyPath.substepStrengthScale(120f, 60f, full, 1),
            s.planStrengthScale,
            1e-6f,
        )
        assertTrue("径向模式的折算系数应小于 1", s.planStrengthScale < 1f)
    }

    @Test
    fun `counters accumulate across flushes and inputs`() {
        val s = session(1)
        // 第一批: 2 个输入, 每帧 1 步
        s.submitTarget(100f, 0f)
        assertTrue(s.prepareFlush(60f, LiquifyPath.MODE_PUSH, forceFull = true))
        s.advanceFlush(s.planSteps)
        // 第二批: 再 2 个输入
        s.submitTarget(200f, 0f)
        s.submitTarget(220f, 0f)
        assertTrue(s.prepareFlush(60f, LiquifyPath.MODE_PUSH, forceFull = false))
        s.advanceFlush(s.planSteps)

        assertEquals(3L, s.inputCount)
        assertEquals(2L, s.flushCount)
        assertEquals("最后一批合并了 2 个输入", 2, s.lastFlushInputs)
        assertTrue(s.operationCount >= 2L)
    }

    @Test
    fun `reset drops the pending target`() {
        val s = session(2)
        s.submitTarget(300f, 0f)
        s.prepareFlush(60f, LiquifyPath.MODE_PUSH, forceFull = false)
        s.advanceFlush(s.planSteps)
        assertTrue(s.hasPending)
        s.reset()
        assertFalse(s.hasPending)
        assertEquals(0, s.backlogDabs)
        assertFalse(s.prepareFlush(60f, LiquifyPath.MODE_PUSH, forceFull = false))
    }
}
