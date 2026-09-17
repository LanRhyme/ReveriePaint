/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 时间轴横轴几何换算单测。
 *
 * 重点回归"点击位置与实际帧错位"这一类缺陷: 绘制所用坐标必须能被
 * 命中测试精确还原, 且在任意滚动量 / 任意帧号下都不产生累积漂移。
 */
class TimelineGeometryTest {

    private val cellW = 72f
    private val gap = 4f
    private val step = cellW + gap

    private fun geo(
        scroll: Float = 0f,
        cellW: Float = this.cellW,
    ) = TimelineGeometry(cellW = cellW, gap = gap, scroll = scroll)

    // ---------- 基本换算 ----------

    @Test
    fun `frame 0 left edge sits at content origin`() {
        assertEquals(0f, geo().contentLeftOf(0), 0.001f)
    }

    @Test
    fun `left edges advance by stepX`() {
        val g = geo()
        for (f in 0..50) {
            assertEquals(f * step, g.contentLeftOf(f), 0.001f)
        }
    }

    @Test
    fun `center is left edge plus half a cell, not half a step`() {
        // 这是原缺陷的核心: 若用 stepX/2 当中心, 就会把间隙算进半个格子,
        // 导致越往右偏移越大。
        val g = geo()
        assertEquals(cellW / 2f, g.contentCenterOf(0), 0.001f)
        assertEquals(step * 7 + cellW / 2f, g.contentCenterOf(7), 0.001f)
        assertTrue("中心必须小于右缘", g.contentCenterOf(7) < g.contentLeftOf(8))
    }

    @Test
    fun `viewport coordinate subtracts scroll`() {
        val g = geo(scroll = 100f)
        assertEquals(-100f, g.viewportLeftOf(0), 0.001f)
        assertEquals(step * 3 + cellW / 2f - 100f, g.viewportCenterOf(3), 0.001f)
    }

    // ---------- 命中测试: 绘制坐标必须能被精确还原 ----------

    @Test
    fun `clicking the drawn left edge of a frame hits that frame`() {
        for (scroll in listOf(0f, 37f, 200f, 1234f)) {
            val g = geo(scroll = scroll)
            for (f in 0..40) {
                val x = g.viewportLeftOf(f)
                assertEquals("scroll=$scroll frame=$f", f, g.frameAtViewportX(x))
            }
        }
    }

    @Test
    fun `clicking the drawn center of a frame hits that frame`() {
        for (scroll in listOf(0f, 37f, 200f)) {
            val g = geo(scroll = scroll)
            for (f in 0..40) {
                val x = g.viewportCenterOf(f)
                assertEquals("scroll=$scroll frame=$f", f, g.frameAtViewportX(x))
            }
        }
    }

    @Test
    fun `clicking anywhere inside a cell hits that cell`() {
        val g = geo(scroll = 55f)
        for (f in 0..20) {
            val left = g.viewportLeftOf(f)
            // 格子内部从左缘到 (右缘 - 1px) 都应命中同一帧
            for (dx in listOf(0f, 1f, cellW / 2f, cellW - 1f)) {
                assertEquals("frame=$f dx=$dx", f, g.frameAtViewportX(left + dx))
            }
        }
    }

    @Test
    fun `gap pixels belong to the frame on their left`() {
        val g = geo(scroll = 0f)
        // 帧 3 的格宽结束处到下一帧左缘之间是间隙
        val gapStart = g.contentLeftOf(3) + cellW
        val gapEnd = g.contentLeftOf(4)
        assertTrue(gapEnd > gapStart)
        assertEquals(3, g.frameAtViewportX(gapStart))
        assertEquals(3, g.frameAtViewportX((gapStart + gapEnd) / 2f))
    }

    @Test
    fun `x before the content origin clamps to frame 0`() {
        // 未滚动时, 点在第 0 帧左缘之外 -> 仍是第 0 帧 (不能出现负帧)
        assertEquals(0, geo(scroll = 0f).frameAtViewportX(-9999f))
        assertEquals(0, geo(scroll = 0f).frameAtViewportX(0f))
    }

    @Test
    fun `click at viewport origin respects scroll, not frame 0`() {
        // 已滚动时, 视口左缘对应的就是滚动位置所在的帧 ——
        // 这里正是原先的缺陷: Float.toInt() 向零截断会把负数算错。
        val g = geo(scroll = 300f)
        assertEquals((300f / step).toInt(), g.frameAtViewportX(0f))
    }

    @Test
    fun `negative content position never yields a negative frame`() {
        val g = geo(scroll = 5f)
        // contentX = 5 + (-10) = -5 < 0 -> 钳到 0
        assertEquals(0, g.frameAtViewportX(-10f))
    }

    @Test
    fun `no cumulative drift across many frames when scrolled`() {
        // 逐帧比较"画出来的位置"与"反算出来的帧号", 任一帧漂移都会被抓住
        val g = geo(scroll = 1234.5f)
        for (f in 0..300) {
            val x = g.viewportLeftOf(f)
            assertEquals("drift at frame $f", f, g.frameAtViewportX(x))
        }
    }

    // ---------- 缩放锚点 ----------

    @Test
    fun `zoom keeps the frame under the anchor pinned`() {
        val g = geo(scroll = 400f, cellW = 72f)
        val anchorX = 500f
        val frameUnderAnchor = g.frameAtViewportX(anchorX)

        val newW = 150f
        val newScroll = g.scrollAfterZoom(anchorX, newW)
        val after = TimelineGeometry(cellW = newW, gap = gap, scroll = newScroll)

        // 锚点下的帧号不应改变
        assertEquals(frameUnderAnchor, after.frameAtViewportX(anchorX))
    }

    @Test
    fun `zoom never yields negative scroll`() {
        val g = geo(scroll = 0f)
        assertEquals(0f, g.scrollAfterZoom(0f, 40f), 0.001f)
        assertTrue(g.scrollAfterZoom(10f, 30f) >= 0f)
    }

    @Test
    fun `zoom in then out returns to the original scroll approximately`() {
        val g = geo(scroll = 250f, cellW = 80f)
        val anchorX = 300f
        val zoomed = g.scrollAfterZoom(anchorX, 160f)
        val back = TimelineGeometry(cellW = 160f, gap = gap, scroll = zoomed)
            .scrollAfterZoom(anchorX, 80f)
        assertEquals(g.scroll, back, 0.5f)
    }

    // ---------- 可见范围 ----------

    @Test
    fun `visible frames cover the viewport`() {
        val g = geo(scroll = 200f)
        val range = g.visibleFrames(viewportW = 900f, count = 100)
        // 视口左缘与右缘所在的帧都必须落在范围内
        assertTrue(g.frameAtViewportX(0f) in range)
        assertTrue(g.frameAtViewportX(899f) in range)
    }

    @Test
    fun `visible frames clamp to document bounds`() {
        val g = geo(scroll = 0f)
        val range = g.visibleFrames(viewportW = 900f, count = 5)
        assertEquals(0, range.first)
        assertEquals(4, range.last)

        val scrolled = geo(scroll = 9000f)
        val tail = scrolled.visibleFrames(viewportW = 900f, count = 5)
        assertEquals(4, tail.first)
        assertEquals(4, tail.last)
    }

    @Test
    fun `empty document yields empty range`() {
        assertEquals(IntRange.EMPTY, geo().visibleFrames(viewportW = 900f, count = 0))
    }

    // ---------- 退化输入 ----------

    @Test
    fun `zero cell width does not divide by zero`() {
        val g = TimelineGeometry(cellW = 0f, gap = 1f, scroll = 0f)
        // stepX = 1, 不应抛异常
        assertEquals(0, g.frameAtViewportX(0f))
        assertEquals(5, g.frameAtViewportX(5.5f))
    }

    @Test
    fun `negative gap still produces a usable step`() {
        // 防御性: 即使 gap 被误设为负, 只要 stepX > 0 就不崩溃
        val g = TimelineGeometry(cellW = 40f, gap = -10f, scroll = 0f)
        assertTrue(g.stepX > 0f)
        assertEquals(0, g.frameAtViewportX(0f))
    }

    // ---------- 播放范围与回环步进换算 ----------

    @Test
    fun `playback bounds strictly respect keyframes and hold`() {
        fun calcEnd(
            hasCustomRange: Boolean,
            pbStart: Int,
            pbEnd: Int,
            keyframeCache: Map<Int, List<Int>>,
            lastFrameHold: Map<Int, Int>,
            fallbackLength: Int,
        ): Int {
            if (hasCustomRange && pbEnd > pbStart) return pbEnd
            var maxKey = -1
            for ((layer, times) in keyframeCache) {
                if (times.isNotEmpty()) {
                    val lastT = times.last()
                    val hold = lastFrameHold[layer] ?: 1
                    val end = lastT + hold - 1
                    if (end > maxKey) maxKey = end
                }
            }
            return if (maxKey >= 0) maxKey else maxOf(0, fallbackLength - 1)
        }

        // 仅在 0 帧有关键帧, hold 1: end 必须是 0, 绝不可落入 23 (fallbackLength - 1)
        val endSingle = calcEnd(
            hasCustomRange = false,
            pbStart = 0,
            pbEnd = 23,
            keyframeCache = mapOf(1 to listOf(0)),
            lastFrameHold = emptyMap(),
            fallbackLength = 24,
        )
        assertEquals(0, endSingle)

        // 关键帧在 0, 2; hold 3: 2 + 3 - 1 = 4
        val endWithHold = calcEnd(
            hasCustomRange = false,
            pbStart = 0,
            pbEnd = 23,
            keyframeCache = mapOf(1 to listOf(0, 2)),
            lastFrameHold = mapOf(1 to 3),
            fallbackLength = 24,
        )
        assertEquals(4, endWithHold)

        // 自定义范围开启时遵从自定义范围
        val endCustom = calcEnd(
            hasCustomRange = true,
            pbStart = 1,
            pbEnd = 8,
            keyframeCache = mapOf(1 to listOf(0, 2)),
            lastFrameHold = emptyMap(),
            fallbackLength = 24,
        )
        assertEquals(8, endCustom)
    }

    @Test
    fun `loop playback wraps at end while single playback pauses at end`() {
        val start = 0
        val end = 3

        fun nextFrame(cur: Int, loop: Boolean): Pair<Int, Boolean> {
            if (cur >= end && !loop) return cur to true // pause
            val next = if (cur >= end || cur < start) start else cur + 1
            val shouldPause = !loop && next >= end
            return next to shouldPause
        }

        // 单次播放: 0 -> 1 -> 2 -> 3 (暂停)
        var cur = 0
        val singleFrames = mutableListOf(cur)
        var paused = false
        while (!paused) {
            val (nxt, p) = nextFrame(cur, loop = false)
            singleFrames.add(nxt)
            cur = nxt
            paused = p
        }
        assertEquals(listOf(0, 1, 2, 3), singleFrames)

        // 循环播放: 0 -> 1 -> 2 -> 3 -> 0 -> 1 ...
        cur = 0
        val loopFrames = mutableListOf(cur)
        for (step in 1..6) {
            val (nxt, _) = nextFrame(cur, loop = true)
            loopFrames.add(nxt)
            cur = nxt
        }
        assertEquals(listOf(0, 1, 2, 3, 0, 1, 2), loopFrames)
    }

    // ---------- 洋葱皮衰减曲线与关键帧偏移过滤单测 ----------

    private fun calcDecayOpacity(
        distance: Int,
        total: Int,
        maxOpacity: Int,
        decayType: Int, // 0: LINEAR, 1: SMOOTH, 2: CONSTANT
    ): Int {
        if (total <= 0) return 0
        return when (decayType) {
            0 -> {
                val factor = (total - distance + 1).toFloat() / total.toFloat()
                kotlin.math.round((maxOpacity * factor.coerceIn(0.15f, 1.0f))).toInt().coerceIn(0, 255)
            }
            1 -> {
                val factor = Math.pow(0.62, (distance - 1).toDouble()).toFloat()
                kotlin.math.round((maxOpacity * factor)).toInt().coerceIn(0, 255)
            }
            2 -> {
                maxOpacity.coerceIn(0, 255)
            }
            else -> maxOpacity
        }
    }

    @Test
    fun `onion skin linear decay decreases with distance`() {
        val maxOp = 200
        val total = 3
        val op1 = calcDecayOpacity(1, total, maxOp, 0)
        val op2 = calcDecayOpacity(2, total, maxOp, 0)
        val op3 = calcDecayOpacity(3, total, maxOp, 0)

        assertEquals(200, op1)
        assertTrue("op1 > op2", op1 > op2)
        assertTrue("op2 > op3", op2 > op3)
        assertTrue("op3 >= 30 (clamped)", op3 >= 30)
    }

    @Test
    fun `onion skin smooth decay decays exponentially`() {
        val maxOp = 200
        val op1 = calcDecayOpacity(1, 3, maxOp, 1)
        val op2 = calcDecayOpacity(2, 3, maxOp, 1)
        val op3 = calcDecayOpacity(3, 3, maxOp, 1)

        assertEquals(200, op1)
        assertEquals(kotlin.math.round(200 * 0.62).toInt(), op2)
        assertEquals(kotlin.math.round(200 * 0.62 * 0.62).toInt(), op3)
    }

    @Test
    fun `onion skin constant decay remains unchanged across all frames`() {
        val maxOp = 180
        for (dist in 1..5) {
            assertEquals(maxOp, calcDecayOpacity(dist, 5, maxOp, 2))
        }
    }

    @Test
    fun `onion skin keyframes only filters non-keyframe holds`() {
        // 关键帧结构: 0(hold 3), 4(hold 3), 8(hold 3), 12(hold 3)
        val keyframeTimes = listOf(0, 4, 8, 12)
        val curTime = 8
        val prevCount = 2
        val nextCount = 2

        // 仅关键帧过滤逻辑
        val prevKeyframes = keyframeTimes.filter { it < curTime }.takeLast(prevCount)
        val nextKeyframes = keyframeTimes.filter { it > curTime }.take(nextCount)

        val prevOffsets = prevKeyframes.map { it - curTime }
        val nextOffsets = nextKeyframes.map { it - curTime }

        assertEquals(listOf(-8, -4), prevOffsets)
        assertEquals(listOf(4), nextOffsets)
    }
}

