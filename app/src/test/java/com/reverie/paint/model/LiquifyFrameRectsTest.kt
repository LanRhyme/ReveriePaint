// SPDX-License-Identifier: GPL-3.0-or-later
package com.reverie.paint.model
import org.junit.Assert.*
import org.junit.Test

class LiquifyFrameRectsTest {
    @Test fun `cleared surface rejects a previously consumed frame`() {
        val frames = LiquifyFrameRects()
        frames.stage(100, 7, 4, 6, 20, 30)
        frames.clear()
        assertFalse(frames.copyPresented(100, 7, IntArray(4)))
        frames.stage(200, 8, 0, 0, 40, 40)
        assertTrue(frames.copyPresented(200, 8, IntArray(4)))
    }
    @Test fun `presented frame never borrows a newer submitted rectangle`() {
        val frames = LiquifyFrameRects()
        val out = IntArray(4)
        frames.stage(10, 1, 0, 0, 20, 20)
        frames.stage(20, 1, 0, 0, 40, 40)
        assertTrue(frames.copyPresented(10, 1, out))
        assertArrayEquals(intArrayOf(0, 0, 20, 20), out)
    }
    @Test fun `stationary pen needs no new input to publish the consumed frame`() {
        val frames = LiquifyFrameRects()
        val out = IntArray(4)
        frames.stage(100, 7, 4, 6, 20, 30)
        assertTrue(frames.copyPresented(100, 7, out))
        assertArrayEquals(intArrayOf(4, 6, 20, 30), out)
    }
    @Test fun `late frame from previous gesture is rejected`() {
        val frames = LiquifyFrameRects()
        frames.stage(100, 7, 4, 6, 20, 30)
        assertFalse(frames.copyPresented(100, 8, IntArray(4)))
    }
    @Test fun `evicted and unpresented timestamps cannot move the base`() {
        val frames = LiquifyFrameRects()
        for (t in 1L..20L) frames.stage(t, 1, 0, 0, t.toInt(), 8)
        assertFalse(frames.copyPresented(1, 1, IntArray(4)))
        assertFalse(frames.copyPresented(21, 1, IntArray(4)))
        assertFalse(frames.copyPresented(0, 1, IntArray(4)))
        assertTrue(frames.copyPresented(20, 1, IntArray(4)))
    }
    @Test fun `whole layer preview prepares base once across continuous frames`() {
        val frames = LiquifyFrameRects()
        val policy = LiquifyPreviewBasePolicy()
        val out = IntArray(4)
        var updates = 0
        for (t in 1L..240L) {
            frames.stage(t, 1, 0, 0, 2048, 2048)
            assertTrue(frames.copyPresented(t, 1, out))
            if (policy.shouldAdvance(out, 1)) updates++
        }
        assertEquals(1, updates)
    }
}
