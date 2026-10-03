/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.reverie.paint.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RotationSnapGestureTest {
    @Test
    fun `entering capture range locks preview before lifting fingers`() {
        for (target in listOf(-360f, -90f, 0f, 90f, 180f, 270f, 360f, 720f)) {
            for (direction in listOf(-1f, 1f)) {
                val gesture = RotationSnapGesture().apply { begin(target - direction * 12f) }
                assertEquals(target - direction * 6f, gesture.update(direction * 6f, 5f), 0.0001f)
                assertEquals(target, gesture.update(direction, 5f), 0f)
                assertTrue(gesture.isSnapped)
            }
        }
    }

    @Test
    fun `jitter and pauses keep the preview exactly locked`() {
        val gesture = RotationSnapGesture().apply { begin(84f) }
        assertEquals(90f, gesture.update(1f, 5f), 0f)
        repeat(100) {
            assertEquals(90f, gesture.update(-0.3f, 5f), 0f)
            assertEquals(90f, gesture.update(0.3f, 5f), 0f)
            assertEquals(90f, gesture.update(0f, 5f), 0f)
            assertTrue(gesture.isSnapped)
        }
    }

    @Test
    fun `release uses only overshoot and does not immediately recapture`() {
        for (direction in listOf(-1f, 1f)) {
            val gesture = RotationSnapGesture().apply { begin(90f - direction * 6f) }
            gesture.update(direction, 5f)
            assertEquals(90f, gesture.update(direction * 10.75f, 5f), 0.0001f)
            assertTrue(gesture.isSnapped)
            assertEquals(90f + direction * 0.1f, gesture.update(direction * 0.1f, 5f), 0.0001f)
            assertFalse(gesture.isSnapped)
            assertEquals(90f + direction * 0.2f, gesture.update(direction * 0.1f, 5f), 0.0001f)
            assertFalse(gesture.isSnapped)
        }
    }

    @Test
    fun `released angle can snap again on returning through target`() {
        val gesture = RotationSnapGesture().apply { begin(84f) }
        gesture.update(1f, 5f)
        assertEquals(90.25f, gesture.update(11f, 5f), 0.0001f)
        assertEquals(90f, gesture.update(-0.5f, 5f), 0f)
        assertTrue(gesture.isSnapped)
    }

    @Test
    fun `leaving capture range rearms capture on returning`() {
        val gesture = RotationSnapGesture().apply { begin(84f) }
        gesture.update(1f, 5f)
        gesture.update(11f, 5f)
        assertEquals(96.25f, gesture.update(6f, 5f), 0.0001f)
        assertFalse(gesture.isSnapped)
        assertEquals(90f, gesture.update(-2f, 5f), 0f)
        assertTrue(gesture.isSnapped)
    }

    @Test
    fun `continued rotation can reach the next detent`() {
        val gesture = RotationSnapGesture().apply { begin(84f) }
        gesture.update(1f, 5f)
        gesture.update(11f, 5f)
        repeat(8) { gesture.update(10f, 5f) }
        assertEquals(180f, gesture.update(5f, 5f), 0f)
        assertTrue(gesture.isSnapped)
    }

    @Test
    fun `zero net jitter never arms or qualifies for settling`() {
        val gesture = RotationSnapGesture().apply { begin(92f) }
        repeat(1000) {
            gesture.update(0.1f, 5f)
            assertEquals(92f, gesture.update(-0.1f, 5f), 0.0001f)
            assertFalse(gesture.isActive)
            assertFalse(gesture.isSnapped)
        }
    }

    @Test
    fun `subthreshold excursions do not accumulate into intent`() {
        val gesture = RotationSnapGesture().apply { begin(92f) }
        repeat(100) {
            for (delta in listOf(0.4f, -0.8f, 0.4f)) {
                gesture.update(delta, 5f)
                assertFalse(gesture.isActive)
            }
        }
        assertEquals(90f, gesture.update(-0.7f, 5f), 0f)
        assertTrue(gesture.isSnapped)
    }

    @Test
    fun `disabled snapping preserves preview and resumes free rotation`() {
        val gesture = RotationSnapGesture().apply { begin(84f) }
        gesture.update(1f, 5f)
        assertEquals(90f, gesture.update(0f, 0f), 0f)
        assertEquals(92f, gesture.update(2f, 0f), 0f)
        assertFalse(gesture.isActive)
        assertFalse(gesture.isSnapped)
    }

    @Test
    fun `begin clears intent and locked state`() {
        val gesture = RotationSnapGesture().apply { begin(84f) }
        gesture.update(1f, 5f)
        gesture.begin(89f)
        assertFalse(gesture.isActive)
        assertFalse(gesture.isSnapped)
        assertEquals(89.1f, gesture.update(0.1f, 5f), 0.0001f)
    }

    @Test
    fun `threshold changes preserve preview without movement`() {
        val gesture = RotationSnapGesture().apply { begin(80f) }
        val free = gesture.update(1f, 5f)
        assertEquals(free, gesture.update(0f, 30f), 0f)
        assertEquals(free, gesture.update(0f, 30f), 0f)
        assertFalse(gesture.isSnapped)
    }

    @Test
    fun `invalid delta does not poison the gesture`() {
        val gesture = RotationSnapGesture().apply { begin(84f) }
        assertEquals(84f, gesture.update(Float.NaN, 5f), 0f)
        assertEquals(84f, gesture.update(Float.POSITIVE_INFINITY, 5f), 0f)
        assertEquals(90f, gesture.update(1f, 5f), 0f)
    }

    @Test
    fun `invalid threshold disables snapping`() {
        val gesture = RotationSnapGesture().apply { begin(84f) }
        gesture.update(1f, 5f)
        assertEquals(91f, gesture.update(1f, Float.NaN), 0f)
        assertFalse(gesture.isSnapped)
    }

    @Test
    fun `configured threshold controls capture width`() {
        for (threshold in listOf(1f, 5f, 30f)) {
            val gesture = RotationSnapGesture().apply { begin(90f - threshold - 2f) }
            assertEquals(90f, gesture.update(2f, threshold), 0f)
            assertTrue(gesture.isSnapped)
        }
    }
}
