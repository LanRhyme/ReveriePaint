/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.canvas

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransformStateTest {

    @Test
    fun `reset restores mode to STANDARD and zeroes out transformations`() {
        val state = TransformState()
        state.mode = TransformMode.PERSPECTIVE
        state.scaleX = 2.5f
        state.scaleY = 0.5f
        state.rotation = 45f
        state.tx = 100f
        state.ty = -50f

        val rect = Rect(10f, 20f, 110f, 120f)
        state.reset(rect)

        assertTrue(state.active)
        assertEquals(TransformMode.STANDARD, state.mode)
        assertEquals(1f, state.scaleX, 0.001f)
        assertEquals(1f, state.scaleY, 0.001f)
        assertEquals(0f, state.rotation, 0.001f)
        assertEquals(0f, state.tx, 0.001f)
        assertEquals(0f, state.ty, 0.001f)
        assertEquals(rect, state.bounds)
    }

    @Test
    fun `reset clears DISTORT mode back to STANDARD`() {
        val state = TransformState()
        state.mode = TransformMode.DISTORT
        state.reset(Rect(0f, 0f, 500f, 500f))
        assertEquals(TransformMode.STANDARD, state.mode)
    }
}
