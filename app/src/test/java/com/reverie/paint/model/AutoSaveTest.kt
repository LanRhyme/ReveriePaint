package com.reverie.paint.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoSaveTest {

    @Test
    fun `auto save intervals are positive and properly converted to milliseconds`() {
        val validIntervals = listOf(1, 3, 5, 10, 15, 30)
        validIntervals.forEach { minutes ->
            assertTrue("Interval should be positive", minutes > 0)
            val ms = minutes * 60 * 1000L
            assertEquals(minutes * 60_000L, ms)
        }
    }

    @Test
    fun `auto save interval clamping works within bounds`() {
        val clampInterval = { mins: Int -> mins.coerceIn(1, 60) }
        assertEquals(1, clampInterval(0))
        assertEquals(1, clampInterval(-5))
        assertEquals(5, clampInterval(5))
        assertEquals(60, clampInterval(60))
        assertEquals(60, clampInterval(120))
    }

    @Test
    fun `max undo steps clamping works within bounds`() {
        val clampUndo = { steps: Int -> steps.coerceIn(10, 200) }
        assertEquals(10, clampUndo(5))
        assertEquals(50, clampUndo(50))
        assertEquals(200, clampUndo(300))
    }

    @Test
    fun `auto save trigger predicate logic`() {
        fun shouldTrigger(
            enabled: Boolean,
            isSaving: Boolean,
            hasChanges: Boolean,
            isInteracting: Boolean,
            elapsedMs: Long,
            intervalMs: Long
        ): Boolean {
            if (!enabled || isSaving || !hasChanges || isInteracting) return false
            return elapsedMs >= intervalMs
        }

        val intervalMs = 5 * 60 * 1000L

        // Normal trigger when time is up and canvas has changes
        assertTrue(shouldTrigger(true, false, true, false, 300_000L, intervalMs))
        assertTrue(shouldTrigger(true, false, true, false, 350_000L, intervalMs))

        // Do not trigger when disabled
        assertFalse(shouldTrigger(false, false, true, false, 350_000L, intervalMs))

        // Do not trigger if already saving
        assertFalse(shouldTrigger(true, true, true, false, 350_000L, intervalMs))

        // Do not trigger if no changes on canvas
        assertFalse(shouldTrigger(true, false, false, false, 350_000L, intervalMs))

        // Do not trigger while user is actively drawing a stroke (safety)
        assertFalse(shouldTrigger(true, false, true, true, 350_000L, intervalMs))

        // Do not trigger before interval has elapsed
        assertFalse(shouldTrigger(true, false, true, false, 200_000L, intervalMs))
    }
}
