/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import org.junit.Assert.assertEquals
import org.junit.Test

class CreationStatsTest {

    @Test
    fun `empty activity has level 0`() {
        val empty = DailyActivity(date = "2026-11-05")
        assertEquals(0, empty.level)
    }

    @Test
    fun `light activity has level 1`() {
        val light = DailyActivity(date = "2026-11-05", strokes = 100, durationSeconds = 60)
        assertEquals(1, light.level)
    }

    @Test
    fun `moderate activity has level 2`() {
        val mod = DailyActivity(date = "2026-11-05", strokes = 600, durationSeconds = 900)
        assertEquals(2, mod.level)
    }

    @Test
    fun `active activity has level 3`() {
        val active = DailyActivity(date = "2026-11-05", strokes = 1200, durationSeconds = 1800)
        assertEquals(3, active.level)
    }

    @Test
    fun `compute streaks handles active consecutive days`() {
        val activeDates = setOf("2026-11-03", "2026-11-04", "2026-11-05")
        val (current, longest) = com.reverie.paint.core.CreationStatsManager.computeStreaks(activeDates, "2026-11-05")
        assertEquals(3, current)
        assertEquals(3, longest)
    }

    @Test
    fun `compute streaks counts yesterday if today not yet drawn`() {
        val activeDates = setOf("2026-11-03", "2026-11-04")
        val (current, longest) = com.reverie.paint.core.CreationStatsManager.computeStreaks(activeDates, "2026-11-05")
        assertEquals(2, current)
        assertEquals(2, longest)
    }

    @Test
    fun `compute streaks breaks when missed two days`() {
        val activeDates = setOf("2026-11-01", "2026-11-02")
        val (current, longest) = com.reverie.paint.core.CreationStatsManager.computeStreaks(activeDates, "2026-11-05")
        assertEquals(0, current)
        assertEquals(2, longest)
    }
}
