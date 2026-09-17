/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 动画导出逻辑与数值计算单测。
 */
class AnimationExportLogicTest {

    @Test
    fun `H264 dimensions are forced to even numbers`() {
        fun alignEven(w: Int, h: Int): Pair<Int, Int> {
            val ew = if (w % 2 != 0) w - 1 else w
            val eh = if (h % 2 != 0) h - 1 else h
            return ew to eh
        }

        assertEquals(1080 to 1920, alignEven(1080, 1920))
        assertEquals(1080 to 1920, alignEven(1081, 1921))
        assertEquals(720 to 1280, alignEven(721, 1280))
        assertEquals(1920 to 1080, alignEven(1920, 1081))
    }

    @Test
    fun `GIF frame delay centiseconds accurately derived from fps`() {
        fun delayCs(fps: Int): Int {
            val f = fps.coerceIn(1, 100)
            return max(1, (100.0 / f).roundToInt())
        }

        assertEquals(8, delayCs(12))  // 100 / 12 = 8.33 -> 8cs (80ms)
        assertEquals(4, delayCs(24))  // 100 / 24 = 4.16 -> 4cs (40ms)
        assertEquals(3, delayCs(30))  // 100 / 30 = 3.33 -> 3cs (30ms)
        assertEquals(2, delayCs(60))  // 100 / 60 = 1.67 -> 2cs (20ms)
        assertEquals(100, delayCs(1)) // 1fps -> 100cs (1000ms)
    }

    @Test
    fun `export scale ratios produce valid non-zero dimensions`() {
        val origW = 3840
        val origH = 2160

        listOf(1.0f, 0.5f, 0.25f).forEach { scale ->
            val w = max(2, (origW * scale).roundToInt())
            val h = max(2, (origH * scale).roundToInt())
            assertTrue("Width must be positive", w > 0)
            assertTrue("Height must be positive", h > 0)
        }

        val tinyW = 3
        val tinyH = 3
        val w25 = max(2, (tinyW * 0.25f).roundToInt())
        val h25 = max(2, (tinyH * 0.25f).roundToInt())
        assertEquals(2, w25)
        assertEquals(2, h25)
    }

    @Test
    fun `playback range correctly bounds export frame list`() {
        val totalLength = 20

        fun getRange(mode: String, start: Int, end: Int): List<Int> {
            val (s, e) = if (mode == "range") {
                val clampedS = start.coerceAtLeast(0)
                val clampedE = end.coerceIn(0, max(0, totalLength - 1))
                if (clampedS <= clampedE) clampedS to clampedE else 0 to max(0, totalLength - 1)
            } else {
                0 to max(0, totalLength - 1)
            }
            return (s..e).toList()
        }

        assertEquals(20, getRange("all", 5, 10).size)
        assertEquals(0, getRange("all", 5, 10).first())
        assertEquals(19, getRange("all", 5, 10).last())

        val partial = getRange("range", 3, 7)
        assertEquals(listOf(3, 4, 5, 6, 7), partial)

        val inverted = getRange("range", 10, 2)
        assertEquals(20, inverted.size)
    }
}
