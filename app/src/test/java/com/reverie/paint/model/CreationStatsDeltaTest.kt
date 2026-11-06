/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CreationStatsDeltaTest {

    @Test
    fun `first report counts the whole session`() {
        val d = CreationStatsDelta.since(totalStrokes = 12, totalSeconds = 600L, reportedStrokes = 0, reportedSeconds = 0L)
        assertEquals(12, d.strokes)
        assertEquals(600L, d.seconds)
        assertFalse(d.isEmpty)
    }

    @Test
    fun `reopened project does not re-report its whole lifetime`() {
        // 工程自带 10 小时历史: 打开后基线同步成 36000, 立刻保存不该再加 10 小时
        val d = CreationStatsDelta.since(
            totalStrokes = 5000,
            totalSeconds = 36_000L,
            reportedStrokes = 5000,
            reportedSeconds = 36_000L,
        )
        assertTrue(d.isEmpty)
        assertEquals(0, d.strokes)
        assertEquals(0L, d.seconds)
    }

    @Test
    fun `only the new work since last report is counted`() {
        // 上次上报时 5000 笔 / 36000 秒; 这次存盘 5008 笔 / 36300 秒
        val d = CreationStatsDelta.since(
            totalStrokes = 5008,
            totalSeconds = 36_300L,
            reportedStrokes = 5000,
            reportedSeconds = 36_000L,
        )
        assertEquals(8, d.strokes)
        assertEquals(300L, d.seconds)
    }

    @Test
    fun `repeated save within the same session reports nothing extra`() {
        var reportedStrokes = 5000
        var reportedSeconds = 36_000L
        // 第一次保存
        var d = CreationStatsDelta.since(5008, 36_300L, reportedStrokes, reportedSeconds)
        assertEquals(8, d.strokes)
        reportedStrokes = 5008
        reportedSeconds = 36_300L
        // 紧接着第二次保存 (累计值没变): 不能再报一遍
        d = CreationStatsDelta.since(5008, 36_300L, reportedStrokes, reportedSeconds)
        assertTrue(d.isEmpty)
    }

    @Test
    fun `negative delta is clamped to zero`() {
        // 撤销把笔画数拉回到基线以下 (或重置基线) 时不该产生负数
        val d = CreationStatsDelta.since(
            totalStrokes = 4990,
            totalSeconds = 35_000L,
            reportedStrokes = 5000,
            reportedSeconds = 36_000L,
        )
        assertEquals(0, d.strokes)
        assertEquals(0L, d.seconds)
        assertTrue(d.isEmpty)
    }
}
