/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import com.reverie.paint.model.RenderScheduleMode
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class StrokeLatencyDiagTest {

    @Before
    fun setUp() {
        StrokeLatencyDiag.resetForTest()
    }

    @After
    fun tearDown() {
        StrokeLatencyDiag.resetForTest()
    }

    @Test
    fun `empty state formats cleanly`() {
        val text = StrokeLatencyDiag.formatForDiagnostics(nowMs = 1000L)
        assertTrue(text.contains("RealInk Stats: arms=0 held=0 fallback=0 midRejects=0 bigAreaSkips=0 lastArmReason=none"))
        assertTrue(text.contains("Recent RealInk Events (0):"))
        assertTrue(text.contains("Recent StrokePerf Samples (0):"))
    }

    @Test
    fun `perf ring buffer wraps and keeps newest PERF_CAP samples`() {
        val total = StrokeLatencyDiag.PERF_CAP + 8
        for (i in 1..total) {
            StrokeLatencyDiag.recordStrokePerf(
                n = i,
                merged = i % 3,
                modeId = RenderScheduleMode.BALANCED.id,
                queueWaitMs = i.toLong(),
                kritaMs = (i * 2).toLong(),
                renderMs = 4L,
                e2eMs = (i + 10).toLong(),
                effectiveDelayMs = 11L,
                presentWaitP50Ms = 2L,
                frontierErrPx = 1.5f,
                realInkHeld = (i % 2 == 0),
                uptimeMs = i * 100L,
            )
        }
        val text = StrokeLatencyDiag.formatForDiagnostics(nowMs = total * 100L + 50L)
        assertTrue(text.contains("Recent StrokePerf Samples (${StrokeLatencyDiag.PERF_CAP}):"))
        assertFalse("Oldest evicted sample n=1 must not appear", text.contains(" n=1 merged="))
        assertFalse("Oldest evicted sample n=8 must not appear", text.contains(" n=8 merged="))
        assertTrue("First retained sample n=9 must appear", text.contains(" n=9 merged="))
        assertTrue("Newest sample n=$total must appear", text.contains(" n=$total merged="))
    }

    @Test
    fun `realInk counters and event ring buffer track wrap-around and rejects`() {
        StrokeLatencyDiag.recordRealInkArm(held = true, reason = "armed", uptimeMs = 10L)
        StrokeLatencyDiag.recordRealInkArm(held = false, reason = "fb_off", uptimeMs = 20L)
        StrokeLatencyDiag.recordRealInkEvent("hold_reject:geom", uptimeMs = 30L)
        StrokeLatencyDiag.recordRealInkEvent("skip_big_area:9000000/4000000", uptimeMs = 40L)

        val extra = StrokeLatencyDiag.EVENT_CAP
        for (i in 1..extra) {
            StrokeLatencyDiag.recordRealInkEvent("ev_$i", uptimeMs = 100L + i)
        }

        val text = StrokeLatencyDiag.formatForDiagnostics(nowMs = 500L)
        assertTrue(text.contains("arms=2 held=1 fallback=1 midRejects=1 bigAreaSkips=1 lastArmReason=fb_off"))
        assertTrue(text.contains("Recent RealInk Events (${StrokeLatencyDiag.EVENT_CAP}):"))
        assertFalse("Oldest arm event should be evicted", text.contains("arm held=true"))
        assertTrue("Latest event should be present", text.contains("ev_$extra"))
    }

    @Test
    fun `resolveStylusSummary picks active brand instead of always Huawei`() {
        val oppo = DiagnosticsManager.resolveStylusSummary(
            brand = "OPPO",
            manufacturer = "OPPO",
            oppoModel = "OPPO_PENCIL_2",
            oppoMode = "AUTO",
            huaweiModel = "M_PENCIL_3",
            huaweiMode = "AUTO",
            honorModel = "MAGIC_PENCIL_3",
            xiaomiModel = "FOCUS_PEN",
            vivoModel = "VIVO_PENCIL2",
            genericEnabled = false,
        )
        assertTrue(oppo.startsWith("OPPO/OPPO_PENCIL_2"))

        val huawei = DiagnosticsManager.resolveStylusSummary(
            brand = "HUAWEI",
            manufacturer = "HUAWEI",
            oppoModel = "PRO",
            oppoMode = "AUTO",
            huaweiModel = "M_PENCIL_3",
            huaweiMode = "AUTO",
            honorModel = "NONE",
            xiaomiModel = "NONE",
            vivoModel = "NONE",
            genericEnabled = false,
        )
        assertTrue(huawei.startsWith("Huawei/M_PENCIL_3"))

        val xiaomi = DiagnosticsManager.resolveStylusSummary(
            brand = "Xiaomi",
            manufacturer = "Xiaomi",
            oppoModel = "PRO",
            oppoMode = "AUTO",
            huaweiModel = "M_PENCIL_3",
            huaweiMode = "AUTO",
            honorModel = "NONE",
            xiaomiModel = "FOCUS_PEN_PRO",
            vivoModel = "NONE",
            genericEnabled = false,
        )
        assertTrue(xiaomi == "Xiaomi/FOCUS_PEN_PRO")

        val samsung = DiagnosticsManager.resolveStylusSummary(
            brand = "samsung",
            manufacturer = "samsung",
            oppoModel = "PRO",
            oppoMode = "AUTO",
            huaweiModel = "M_PENCIL_3",
            huaweiMode = "AUTO",
            honorModel = "NONE",
            xiaomiModel = "NONE",
            vivoModel = "NONE",
            genericEnabled = true,
        )
        assertTrue(samsung == "Samsung/S-Pen (generic=true)")
    }
}
