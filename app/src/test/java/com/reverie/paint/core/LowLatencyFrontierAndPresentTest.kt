/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test

class LowLatencyFrontierAndPresentTest {

    @Before
    fun setUp() {
        PerfTrace.resetForTest()
        // recordFrontierError 仅在启用时记录 (PR #82 review 问题 5):
        // 单测需显式建立该前置条件。
        PerfTrace.enabled = true
    }

    @After
    fun tearDown() {
        PerfTrace.enabled = false
        PerfTrace.resetForTest()
    }

    @Test
    fun `presentWait ring buffer calculates p50 and p95 accurately and ignores outliers`() {
        PerfTrace.invalidatePercentileCachesForTest()
        // Less than 5 samples should return 0L fallback
        PerfTrace.recordPresentWait(10_000_000L) // 10ms
        PerfTrace.recordPresentWait(12_000_000L) // 12ms
        PerfTrace.invalidatePercentileCachesForTest()
        // If presentWaitN < 5, p50 should return 0L
        // (Note: PerfTrace is a singleton, so let's feed a known set of 10 samples)
        for (i in 1..10) {
            PerfTrace.recordPresentWait(i * 1_000_000L) // 1ms .. 10ms
        }
        PerfTrace.invalidatePercentileCachesForTest()
        val p50 = PerfTrace.presentWaitP50Ms
        val p95 = PerfTrace.presentWaitP95Ms
        assertTrue("p50 should be around 5ms, actual: $p50", p50 in 4L..6L)
        assertTrue("p95 should be around 9-10ms, actual: $p95", p95 in 9L..10L)

        // Extreme outliers (>200ms or <=0) should be discarded
        PerfTrace.recordPresentWait(300_000_000L)
        PerfTrace.recordPresentWait(-5_000_000L)
        PerfTrace.invalidatePercentileCachesForTest()
        val p95AfterOutlier = PerfTrace.presentWaitP95Ms
        assertTrue("Outlier >200ms must be discarded, actual p95: $p95AfterOutlier", p95AfterOutlier <= 10L)
    }

    @Test
    fun `percentile getters cache results within time window`() {
        PerfTrace.invalidatePercentileCachesForTest()
        for (i in 1..10) {
            PerfTrace.recordPresentWait(i * 1_000_000L) // 1..10ms
        }
        PerfTrace.invalidatePercentileCachesForTest()
        val initialP50 = PerfTrace.presentWaitP50Ms
        assertTrue(initialP50 in 4L..6L)

        // Record a surge of high latency (100ms)
        for (i in 1..20) {
            PerfTrace.recordPresentWait(100_000_000L)
        }
        // Without invalidating cache, within 250ms it must still return the cached initialP50
        val cachedP50 = PerfTrace.presentWaitP50Ms
        assertEquals(initialP50, cachedP50)

        // Once cache is invalidated, it reflects the new distribution
        PerfTrace.invalidatePercentileCachesForTest()
        val updatedP50 = PerfTrace.presentWaitP50Ms
        assertTrue("After cache invalidation, should reflect new high samples: $updatedP50", updatedP50 > initialP50)
    }

    @Test
    fun `frontierErr ring buffer calculates p50 and p95 correctly`() {
        PerfTrace.invalidatePercentileCachesForTest()
        for (i in 1..20) {
            PerfTrace.recordFrontierError(i * 2.0f) // 2.0px .. 40.0px
        }
        PerfTrace.invalidatePercentileCachesForTest()
        val errP50 = PerfTrace.frontierErrP50Px
        val errP95 = PerfTrace.frontierErrP95Px
        assertTrue("frontierErr p50 should be ~20px, actual: $errP50", errP50 in 18.0f..22.0f)
        assertTrue("frontierErr p95 should be ~38-40px, actual: $errP95", errP95 in 36.0f..40.0f)

        // Invalid values (NaN, negative, >5000px) should be filtered
        PerfTrace.recordFrontierError(Float.NaN)
        PerfTrace.recordFrontierError(-10.0f)
        PerfTrace.recordFrontierError(99999.0f)
        PerfTrace.invalidatePercentileCachesForTest()
        assertTrue(PerfTrace.frontierErrP95Px <= 40.0f)
    }

    @Test
    fun `effective pipeline compensation rule validates bounds`() {
        val defaultEstimate = PaintViewModel.PRESENT_ESTIMATE_MS
        val tailMs = PaintViewModel.PRESENT_TAIL_MS
        assertEquals(14L, defaultEstimate)
        assertEquals(8L, tailMs)

        fun calcEffective(lastE2e: Long, measuredPresent: Long): Long {
            val presentComp = if (measuredPresent in 2L..35L) {
                (measuredPresent + tailMs).coerceAtLeast(defaultEstimate)
            } else {
                defaultEstimate
            }
            return (lastE2e + presentComp).coerceIn(20L, 85L)
        }

        // Standard 144Hz scenario (measured ~5ms: 5 + 8 = 13ms, coerced to 14ms minimum)
        assertEquals(54L, calcEffective(lastE2e = 40L, measuredPresent = 5L))

        // Standard 60Hz scenario (measured ~16ms: 16 + 8 = 24ms)
        assertEquals(64L, calcEffective(lastE2e = 40L, measuredPresent = 16L))

        // Fallback when not enough samples (0L -> default 14L)
        assertEquals(54L, calcEffective(lastE2e = 40L, measuredPresent = 0L))

        // Outlier abnormal delay (>35L) safely falls back to default 14L
        assertEquals(54L, calcEffective(lastE2e = 40L, measuredPresent = 60L))

        // Clamping bounds [20L, 85L]
        // 5 + max(2 + 8, 14) = 5 + 14 = 19 -> clamped to 20L
        assertEquals(20L, calcEffective(lastE2e = 5L, measuredPresent = 2L))
        // 120 + 14 = 134 -> clamped to 85L
        assertEquals(85L, calcEffective(lastE2e = 120L, measuredPresent = 14L))
    }
}
