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

    @Test
    fun `MlsWarp identity deformation preserves all points`() {
        val orig = (0..3).flatMap { r ->
            (0..3).map { c ->
                androidx.compose.ui.geometry.Offset(c * 100f, r * 100f)
            }
        }
        val transf = orig.toList()
        for (x in 0..300 step 25) {
            for (y in 0..300 step 25) {
                val res = MlsWarp.evaluate(x.toFloat(), y.toFloat(), orig, transf)
                assertEquals(x.toFloat(), res.x, 0.01f)
                assertEquals(y.toFloat(), res.y, 0.01f)
            }
        }
    }

    @Test
    fun `MlsWarp uniform translation shifts all points identically`() {
        val orig = (0..3).flatMap { r ->
            (0..3).map { c ->
                androidx.compose.ui.geometry.Offset(c * 100f, r * 100f)
            }
        }
        val dx = 35f
        val dy = -20f
        val transf = orig.map { it + androidx.compose.ui.geometry.Offset(dx, dy) }
        for (x in 0..300 step 50) {
            for (y in 0..300 step 50) {
                val res = MlsWarp.evaluate(x.toFloat(), y.toFloat(), orig, transf)
                assertEquals(x + dx, res.x, 0.01f)
                assertEquals(y + dy, res.y, 0.01f)
            }
        }
    }

    @Test
    fun `MlsWarp passes exactly through control points`() {
        val orig = (0..3).flatMap { r ->
            (0..3).map { c ->
                androidx.compose.ui.geometry.Offset(c * 100f, r * 100f)
            }
        }
        val transf = orig.toMutableList()
        transf[5] = transf[5] + androidx.compose.ui.geometry.Offset(30f, 40f)
        transf[10] = transf[10] + androidx.compose.ui.geometry.Offset(-20f, 15f)

        for (i in orig.indices) {
            val p = orig[i]
            val expected = transf[i]
            val actual = MlsWarp.evaluate(p.x, p.y, orig, transf)
            assertEquals(expected.x, actual.x, 0.01f)
            assertEquals(expected.y, actual.y, 0.01f)
        }
    }

    @Test
    fun `MlsWarp produces smooth continuous deformation when interior point moves`() {
        val orig = (0..3).flatMap { r ->
            (0..3).map { c ->
                androidx.compose.ui.geometry.Offset(c * 100f, r * 100f)
            }
        }
        val transf = orig.toMutableList()
        transf[5] = transf[5] + androidx.compose.ui.geometry.Offset(50f, 0f)

        var prevX = -1f
        for (x in 0..300 step 5) {
            val pt = MlsWarp.evaluate(x.toFloat(), 100f, orig, transf)
            if (prevX >= 0f) {
                val step = pt.x - prevX
                assertTrue("X coordinate must be monotonically increasing along y=100", step > 0f)
            }
            prevX = pt.x
        }
    }
}

