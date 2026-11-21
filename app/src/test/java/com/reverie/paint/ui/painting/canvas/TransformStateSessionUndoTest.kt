/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransformStateSessionUndoTest {

    private fun TransformState.captureStart() {
        startScaleX = scaleX
        startScaleY = scaleY
        startRotation = rotation
        startTx = tx
        startTy = ty
        startQuadCorners = quadCorners.toList()
        startMeshPoints = meshPoints.toList()
    }

    private fun TransformState.beginGesture() {
        handle = 8
        captureStart()
    }

    @Test
    fun `push returns false when handle is -1`() {
        val state = TransformState()
        state.reset(Rect(0f, 0f, 100f, 80f))
        state.tx = 12f
        assertEquals(-1, state.handle)
        assertFalse(state.pushGestureUndo())
        assertFalse(state.popGestureUndo())
    }

    @Test
    fun `push returns false when params are unchanged from start`() {
        val state = TransformState()
        state.reset(Rect(0f, 0f, 100f, 80f))
        state.beginGesture()
        assertFalse(state.pushGestureUndo())
        assertFalse(state.popGestureUndo())
    }

    @Test
    fun `push then pop restores all seven fields with independent list copies`() {
        val state = TransformState()
        state.reset(Rect(10f, 20f, 110f, 100f))
        state.beginGesture()
        val startQuad = state.quadCorners.toList()
        val startMesh = state.meshPoints.toList()

        state.scaleX = 1.4f
        state.scaleY = 0.6f
        state.rotation = 25f
        state.tx = 18f
        state.ty = -7f
        state.quadCorners = listOf(Offset(1f, 2f), Offset(3f, 4f), Offset(5f, 6f), Offset(7f, 8f))
        state.meshPoints = List(16) { Offset(it.toFloat(), it * 2f) }
        assertTrue(state.pushGestureUndo())

        // Mutating start* after push must not affect the stacked snapshot
        state.startScaleX = 9f
        state.startScaleY = 9f
        state.startRotation = 90f
        state.startTx = 999f
        state.startTy = 999f
        state.startQuadCorners = listOf(Offset.Zero, Offset.Zero, Offset.Zero, Offset.Zero)
        state.startMeshPoints = List(16) { Offset.Zero }

        assertTrue(state.popGestureUndo())
        assertEquals(1f, state.scaleX, 0f)
        assertEquals(1f, state.scaleY, 0f)
        assertEquals(0f, state.rotation, 0f)
        assertEquals(0f, state.tx, 0f)
        assertEquals(0f, state.ty, 0f)
        assertEquals(startQuad, state.quadCorners)
        assertEquals(startMesh, state.meshPoints)
        assertFalse(state.quadCorners === startQuad)
        assertFalse(state.meshPoints === startMesh)
    }

    @Test
    fun `multiple gestures pop in reverse order and empty stack returns false`() {
        val state = TransformState()
        state.reset(Rect(0f, 0f, 200f, 160f))

        state.beginGesture()
        state.tx = 10f
        assertTrue(state.pushGestureUndo())
        state.handle = -1

        state.beginGesture()
        state.tx = 20f
        state.rotation = 15f
        assertTrue(state.pushGestureUndo())
        state.handle = -1

        state.beginGesture()
        state.tx = 30f
        state.rotation = 40f
        assertTrue(state.pushGestureUndo())

        assertTrue(state.popGestureUndo())
        assertEquals(20f, state.tx, 0f)
        assertEquals(15f, state.rotation, 0f)

        assertTrue(state.popGestureUndo())
        assertEquals(10f, state.tx, 0f)
        assertEquals(0f, state.rotation, 0f)

        assertTrue(state.popGestureUndo())
        assertEquals(0f, state.tx, 0f)
        assertEquals(0f, state.rotation, 0f)

        assertFalse(state.popGestureUndo())
    }

    @Test
    fun `stack drops the oldest snapshot when exceeding 50`() {
        val state = TransformState()
        state.reset(Rect(0f, 0f, 100f, 80f))
        state.handle = 8
        for (i in 1..51) {
            state.startTx = i.toFloat()
            state.tx = i + 1000f
            assertTrue(state.pushGestureUndo())
        }
        assertTrue(state.popGestureUndo())
        assertEquals(51f, state.tx, 0f)
        for (expected in 50 downTo 2) {
            assertTrue(state.popGestureUndo())
            assertEquals(expected.toFloat(), state.tx, 0f)
        }
        assertFalse(state.popGestureUndo())
    }

    @Test
    fun `cancelDrag does not push and subsequent push is false because handle is -1`() {
        val state = TransformState()
        state.reset(Rect(0f, 0f, 100f, 80f))
        state.beginGesture()
        state.tx = 40f
        assertTrue(state.cancelDrag())
        assertEquals(-1, state.handle)
        assertFalse(state.pushGestureUndo())
        assertFalse(state.popGestureUndo())
    }

    @Test
    fun `reset clears leftover gesture undo stack`() {
        val state = TransformState()
        state.reset(Rect(0f, 0f, 100f, 80f))
        state.beginGesture()
        state.tx = 12f
        assertTrue(state.pushGestureUndo())
        state.reset(Rect(0f, 0f, 50f, 40f))
        assertFalse(state.popGestureUndo())
    }
}
