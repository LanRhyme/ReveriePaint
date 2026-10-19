/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UiClipSnapshotTest {

    @Test
    fun `empty snapshot never intersects or contains`() {
        val empty = UiClipSnapshot.EMPTY
        assertTrue(empty.isEmpty)
        assertEquals(0, empty.count)
        assertFalse(empty.intersects(0f, 0f, 100f, 100f))
        assertFalse(empty.contains(0f, 0f))
    }

    @Test
    fun `intersects treats touching edges as intersection`() {
        val snapshot = snapshotOf(10f, 20f, 30f, 40f)

        assertTrue(snapshot.intersects(30f, 20f, 40f, 40f))
        assertTrue(snapshot.intersects(0f, 0f, 10f, 20f))
        assertTrue(snapshot.intersects(15f, 25f, 20f, 30f))
        assertFalse(snapshot.intersects(30.01f, 20f, 40f, 40f))
        assertFalse(snapshot.intersects(0f, 40.01f, 30f, 50f))
    }

    @Test
    fun `contains treats boundary as hit`() {
        val snapshot = snapshotOf(10f, 20f, 30f, 40f)

        assertTrue(snapshot.contains(10f, 20f))
        assertTrue(snapshot.contains(30f, 40f))
        assertTrue(snapshot.contains(20f, 30f))
        assertFalse(snapshot.contains(30.01f, 30f))
        assertFalse(snapshot.contains(20f, 19.99f))
    }

    @Test
    fun `copyOf clamps count to max and source capacity`() {
        val src = floatArrayOf(
            0f, 0f, 10f, 10f,
            20f, 20f, 30f, 30f,
        )

        assertEquals(2, UiClipSnapshot.copyOf(src, 5, 24).count)
        assertEquals(1, UiClipSnapshot.copyOf(src, 1, 24).count)
        assertEquals(1, UiClipSnapshot.copyOf(src, 2, 1).count)
        assertEquals(0, UiClipSnapshot.copyOf(floatArrayOf(0f, 0f, 1f), 1, 24).count)
    }

    @Test
    fun `copyOf returns empty for non-positive count`() {
        val src = floatArrayOf(0f, 0f, 10f, 10f)
        assertEquals(UiClipSnapshot.EMPTY, UiClipSnapshot.copyOf(src, 0, 24))
        assertEquals(UiClipSnapshot.EMPTY, UiClipSnapshot.copyOf(src, -3, 24))
    }

    @Test
    fun `copyOf treats negative max as empty`() {
        val src = floatArrayOf(0f, 0f, 10f, 10f)
        assertEquals(UiClipSnapshot.EMPTY, UiClipSnapshot.copyOf(src, 1, -1))
    }

    @Test
    fun `copyInto copies only valid items`() {
        val snapshot = UiClipSnapshot.copyOf(
            floatArrayOf(
                1f, 2f, 3f, 4f,
                5f, 6f, 7f, 8f,
            ),
            2,
            24,
        )

        val dest = FloatArray(8)
        assertEquals(2, snapshot.copyInto(dest, 24))
        assertEquals(5f, dest[4])
        assertEquals(8f, dest[7])

        val smallDest = FloatArray(4)
        assertEquals(1, snapshot.copyInto(smallDest, 24))
        assertEquals(1f, smallDest[0])
        assertEquals(4f, smallDest[3])
    }

    @Test
    fun `copyInto respects maxItems`() {
        val snapshot = UiClipSnapshot.copyOf(
            floatArrayOf(
                1f, 2f, 3f, 4f,
                5f, 6f, 7f, 8f,
            ),
            2,
            24,
        )
        val dest = FloatArray(8) { -1f }

        assertEquals(1, snapshot.copyInto(dest, 1))
        assertEquals(1f, dest[0], 0f)
        assertEquals(4f, dest[3], 0f)
        assertEquals(-1f, dest[4], 0f)
    }

    @Test
    fun `copyInto defaults to full count`() {
        val snapshot = UiClipSnapshot.copyOf(
            floatArrayOf(
                1f, 2f, 3f, 4f,
                5f, 6f, 7f, 8f,
            ),
            2,
            24,
        )
        val dest = FloatArray(8)

        assertEquals(2, snapshot.copyInto(dest))
        assertEquals(5f, dest[4], 0f)
        assertEquals(8f, dest[7], 0f)
    }

    @Test
    fun `copyInto with negative maxItems copies nothing`() {
        val snapshot = snapshotOf(1f, 2f, 3f, 4f)
        val dest = FloatArray(4) { 7f }

        assertEquals(0, snapshot.copyInto(dest, -1))
        assertTrue(dest.all { it == 7f })
    }

    @Test
    fun `bounds accessors expose copied values`() {
        val snapshot = snapshotOf(1f, 2f, 3f, 4f)

        assertEquals(1f, snapshot.left(0))
        assertEquals(2f, snapshot.top(0))
        assertEquals(3f, snapshot.right(0))
        assertEquals(4f, snapshot.bottom(0))
    }

    @Test
    fun `snapshots with same content are equal`() {
        assertEquals(
            snapshotOf(1f, 2f, 3f, 4f),
            snapshotOf(1f, 2f, 3f, 4f),
        )
    }

    private fun snapshotOf(l: Float, t: Float, r: Float, b: Float): UiClipSnapshot =
        UiClipSnapshot.copyOf(floatArrayOf(l, t, r, b), 1, UiPanelSlot.COUNT)
}
