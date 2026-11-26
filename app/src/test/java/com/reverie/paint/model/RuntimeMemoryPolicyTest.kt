/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeMemoryPolicyTest {

    @Test
    fun `format bytes formats zero, sub-megabyte, megabyte, and gigabyte values`() {
        assertEquals("0 MB", RuntimeMemoryPolicy.formatBytes(0L))
        assertEquals("0 MB", RuntimeMemoryPolicy.formatBytes(-512L))
        assertEquals("< 1 MB", RuntimeMemoryPolicy.formatBytes(512L * 1024L))
        assertEquals("486 MB", RuntimeMemoryPolicy.formatBytes(486L * 1024L * 1024L))
        assertEquals("1.0 GB", RuntimeMemoryPolicy.formatBytes(1024L * 1024L * 1024L))
        assertEquals("3.2 GB", RuntimeMemoryPolicy.formatBytes((3.2 * 1024 * 1024 * 1024).toLong()))
    }

    @Test
    fun `parse statm rss bytes extracts second token and rejects malformed input`() {
        assertEquals(4096L * 2500L, RuntimeMemoryPolicy.parseStatmRssBytes("12000 2500 800 10 0 900 0\n", 4096L))
        assertEquals(0L, RuntimeMemoryPolicy.parseStatmRssBytes("12000", 4096L))
        assertEquals(0L, RuntimeMemoryPolicy.parseStatmRssBytes("12000 abc 800", 4096L))
        assertEquals(0L, RuntimeMemoryPolicy.parseStatmRssBytes("", 4096L))
        assertEquals(0L, RuntimeMemoryPolicy.parseStatmRssBytes("12000 2500", 0L))
    }

    @Test
    fun `classify pressure transitions between normal, elevated, and critical`() {
        val gb = 1024L * 1024L * 1024L
        assertEquals(
            MemoryPressureLevel.NORMAL,
            RuntimeMemoryPolicy.classifyPressure(500L * 1024L * 1024L, 4L * gb, 12L * gb, sysLowMemory = false),
        )
        assertEquals(
            MemoryPressureLevel.ELEVATED,
            RuntimeMemoryPolicy.classifyPressure(500L * 1024L * 1024L, 800L * 1024L * 1024L, 12L * gb, sysLowMemory = false),
        )
        assertEquals(
            MemoryPressureLevel.ELEVATED,
            RuntimeMemoryPolicy.classifyPressure(2L * gb, 4L * gb, 8L * gb, sysLowMemory = false),
        )
        assertEquals(
            MemoryPressureLevel.CRITICAL,
            RuntimeMemoryPolicy.classifyPressure(4L * gb, 4L * gb, 12L * gb, sysLowMemory = false),
        )
        assertEquals(
            MemoryPressureLevel.CRITICAL,
            RuntimeMemoryPolicy.classifyPressure(300L * 1024L * 1024L, 400L * 1024L * 1024L, 12L * gb, sysLowMemory = false),
        )
        assertEquals(
            MemoryPressureLevel.CRITICAL,
            RuntimeMemoryPolicy.classifyPressure(300L * 1024L * 1024L, 4L * gb, 12L * gb, sysLowMemory = true),
        )
    }

    @Test
    fun `compute tile budget scales hard limit by ram tier and caps clone pool`() {
        val (soft4g, hard4g, pool4g) = RuntimeMemoryPolicy.computeTileBudgetMb(4096L, 2000L)
        assertEquals(512.0, hard4g, 0.01)
        assertTrue(soft4g < hard4g)
        assertTrue(pool4g in 32.0..96.0)

        val (soft6g, hard6g, pool6g) = RuntimeMemoryPolicy.computeTileBudgetMb(6144L, 3000L)
        assertEquals(768.0, hard6g, 0.01)
        assertTrue(soft6g < hard6g)
        assertTrue(pool6g in 32.0..96.0)

        val (_, hard8g, pool8g) = RuntimeMemoryPolicy.computeTileBudgetMb(8192L, 4500L)
        assertEquals(1152.0, hard8g, 0.01)
        assertTrue(pool8g in 32.0..96.0)

        val (_, hard12g, pool12g) = RuntimeMemoryPolicy.computeTileBudgetMb(12288L, 6500L)
        assertEquals(1600.0, hard12g, 0.01)
        assertTrue(pool12g in 32.0..96.0)

        val (soft16g, hard16g, pool16g) = RuntimeMemoryPolicy.computeTileBudgetMb(16384L, 9000L)
        assertEquals(2048.0, hard16g, 0.01)
        assertTrue(soft16g < hard16g)
        assertEquals(96.0, pool16g, 0.01)
    }
}
