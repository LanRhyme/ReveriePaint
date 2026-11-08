/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 延时摄影抓帧预算。
 *
 * 锁住的是"抓帧不能把渲染线程吃干净"这条: 引擎侧 createThumbnail 的成本 ∝ 输出像素,
 * 6400x10240 按 2160 抓一帧实测 95ms, 而它跑在渲染线程上、默认还会每笔触发。这里断言
 * 输出被压到 1080p 级、间隔按成本拉长。
 */
class TimelapseCapturePolicyTest {
    @Test
    fun `65MP document never gets a 2160-class frame`() {
        val plan = TimelapseCapturePolicy.plan(6400, 10240, requestedMaxDim = 2160, userMinIntervalMs = 200L)!!
        assertTrue(
            "output must stay within budget",
            plan.width.toLong() * plan.height <= TimelapseCapturePolicy.MAX_CAPTURE_PX,
        )
        // 竖长画幅: 长边是高度, 2160 请求被压到 1MP 预算内 (约 790x1265)
        assertTrue(plan.height > plan.width)
        assertTrue(plan.height in 1100..1400)
        // 成本按 120ns/px 估算: ~1MP → 约 120ms 上界
        assertTrue(plan.estimatedCostMs in 110..130)
        assertTrue(plan.minIntervalMs >= plan.estimatedCostMs * TimelapseCapturePolicy.DUTY_DIVISOR)
        assertTrue(plan.minIntervalMs > 200L)
    }

    @Test
    fun `default 1080 setting keeps its size but throttles by cost`() {
        val plan = TimelapseCapturePolicy.plan(6400, 10240, requestedMaxDim = 1080, userMinIntervalMs = 200L)!!
        assertEquals(1080, plan.height)
        assertEquals(675, plan.width)
        // 真机实测这个尺寸一帧 90~95ms; 有效间隔抬到 8 倍 (≈700ms) 而不是 200ms
        assertTrue(plan.estimatedCostMs in 80..100)
        assertTrue(plan.minIntervalMs >= 640L)
    }

    @Test
    fun `small documents keep the requested resolution and the user interval`() {
        val plan = TimelapseCapturePolicy.plan(1080, 1920, requestedMaxDim = 1080, userMinIntervalMs = 200L)!!
        // 1080x1920 → 竖长, 高 = 1080, 宽 = 607; 未超预算。
        // 成本 ~79ms ⇒ 有效间隔被抬到 8 倍成本 (632ms), 用户设置的 200ms 只会被拉长
        assertEquals(1080, plan.height)
        assertEquals(607, plan.width)
        assertTrue(plan.minIntervalMs >= plan.estimatedCostMs * TimelapseCapturePolicy.DUTY_DIVISOR)
    }

    @Test
    fun `budget caps a square canvas too`() {
        val plan = TimelapseCapturePolicy.plan(4096, 4096, requestedMaxDim = 3840, userMinIntervalMs = 100L)!!
        assertTrue(plan.width.toLong() * plan.height <= TimelapseCapturePolicy.MAX_CAPTURE_PX)
        // 3840² = 14.7MP 远超预算, 必须被压到 1MP 以下, 且保持正方
        assertEquals(plan.width, plan.height)
    }

    @Test
    fun `interval is clamped and never shortened`() {
        val plan = TimelapseCapturePolicy.plan(6400, 10240, requestedMaxDim = 2160, userMinIntervalMs = 5000L)!!
        assertEquals(TimelapseCapturePolicy.MAX_INTERVAL_MS, plan.minIntervalMs)
        val fast = TimelapseCapturePolicy.plan(6400, 10240, requestedMaxDim = 2160, userMinIntervalMs = 50L)!!
        assertTrue(fast.minIntervalMs >= 50L)
    }

    @Test
    fun `invalid document yields no plan`() {
        assertNull(TimelapseCapturePolicy.plan(0, 1024, 1080, 200L))
        assertNull(TimelapseCapturePolicy.plan(1024, 0, 1080, 200L))
        assertNull(TimelapseCapturePolicy.plan(1024, 1024, 0, 200L))
    }
}
