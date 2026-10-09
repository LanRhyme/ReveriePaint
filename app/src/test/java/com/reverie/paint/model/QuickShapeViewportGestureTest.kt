/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.reverie.paint.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickShapeViewportGestureTest {
    private class Viewport(var zoom: Float = 1f, var panX: Float = 0f, var panY: Float = 0f) {
        val gesture = QuickShapeViewportGesture()
        var locked = false

        fun frame(id1: Long, x1: Float, id2: Long, x2: Float, y: Float = 500f): Boolean {
            val changed = gesture.update(id1, x1, y, id2, x2, y, 1000, 1000, zoom, panX, panY, locked)
            if (changed) {
                zoom = gesture.zoom
                panX = gesture.panX
                panY = gesture.panY
            }
            return changed
        }
    }

    @Test
    fun `first pair does not move viewport and subsequent movement pans`() {
        val v = Viewport()
        assertFalse(v.frame(1, 100f, 2, 300f))
        assertTrue(v.frame(1, 200f, 2, 400f))
        assertEquals(1f, v.zoom, 0f)
        assertEquals(100f, v.panX, 0f)
    }

    @Test
    fun `lifting and replacing a finger rebases without a zoom or pan jump`() {
        val v = Viewport()
        v.frame(1, 100f, 2, 300f)
        v.frame(1, 200f, 2, 400f)
        v.gesture.reset() // One remaining viewport pointer.
        assertFalse(v.frame(1, 200f, 3, 600f))
        assertEquals(1f, v.zoom, 0f)
        assertEquals(100f, v.panX, 0f)
        assertTrue(v.frame(1, 220f, 3, 620f))
        assertEquals(120f, v.panX, 0f)
    }

    @Test
    fun `reusing the same pointer id after a lift still rebases`() {
        val v = Viewport()
        v.frame(1, 100f, 2, 300f)
        v.gesture.reset()
        assertFalse(v.frame(1, 100f, 2, 700f))
        assertEquals(1f, v.zoom, 0f)
        assertEquals(0f, v.panX, 0f)
    }

    @Test
    fun `third finger replacing an active finger only establishes a baseline`() {
        val v = Viewport()
        v.frame(1, 100f, 2, 300f)
        v.frame(1, 100f, 2, 300f) // Third finger down, existing pair retained.
        assertFalse(v.frame(2, 300f, 3, 700f))
        assertEquals(1f, v.zoom, 0f)
        assertEquals(0f, v.panX, 0f)
        v.frame(2, 350f, 3, 750f)
        assertEquals(50f, v.panX, 0f)
    }

    @Test
    fun `pointer ordering changes do not interrupt the same pair`() {
        val v = Viewport()
        v.frame(1, 100f, 2, 300f)
        assertTrue(v.frame(2, 400f, 1, 200f))
        assertEquals(100f, v.panX, 0f)
        assertEquals(1f, v.zoom, 0f)
    }

    @Test
    fun `locked viewport ignores pinch but retains centroid panning`() {
        val v = Viewport(2f, 30f, 40f)
        v.locked = true
        v.frame(1, 100f, 2, 300f)
        v.frame(1, 0f, 2, 400f)
        assertEquals(2f, v.zoom, 0f)
        assertEquals(30f, v.panX, 0f)
        v.frame(1, 50f, 2, 450f, 520f)
        assertEquals(2f, v.zoom, 0f)
        assertEquals(80f, v.panX, 0f)
        assertEquals(60f, v.panY, 0f)
    }

    @Test
    fun `unlocking does not replay distance changes made while locked`() {
        val v = Viewport()
        v.locked = true
        v.frame(1, 100f, 2, 300f)
        v.frame(1, 0f, 2, 400f)
        v.locked = false
        v.frame(1, 0f, 2, 400f)
        assertEquals(1f, v.zoom, 0f)
        v.frame(1, -100f, 2, 500f)
        assertEquals(1.5f, v.zoom, 0f)
    }

    @Test
    fun `rotation flips and render resolution preserve the document anchor`() {
        for (angle in listOf(0f, 37f, -90f)) {
            for (flipX in listOf(false, true)) for (flipY in listOf(false, true)) {
                val v = Viewport(1.7f, 50f, -30f)
                val transform = CanvasViewTransform()
                fun update() = transform.update(1000, 1000, v.panX, v.panY, v.zoom, 0.7f, angle,
                    512, 256, 2048, 1024, flipX, flipY)
                update()
                val anchor = FloatArray(2)
                transform.screenToDoc(200f, 500f, anchor)
                v.frame(1, 100f, 2, 300f)
                v.frame(1, 150f, 2, 550f, 570f)
                update()
                val screen = FloatArray(2)
                transform.docToScreen(anchor[0], anchor[1], screen)
                assertEquals(350f, screen[0], 0.001f)
                assertEquals(570f, screen[1], 0.001f)
                assertEquals(3.4f, v.zoom, 0.0001f)
            }
        }
    }

    @Test
    fun `zoom clamp compensates pan with actual scale and immediately permits reversing`() {
        val v = Viewport(100f)
        v.frame(1, 100f, 2, 300f)
        v.frame(1, 0f, 2, 400f)
        assertEquals(128f, v.zoom, 0f)
        assertEquals(84f, v.panX, 0.001f)
        v.frame(1, 100f, 2, 300f)
        assertEquals(64f, v.zoom, 0f)
    }

    @Test
    fun `minimum zoom still allows panning`() {
        val v = Viewport(0.02f)
        v.frame(1, 100f, 2, 300f)
        v.frame(1, 200f, 2, 300f)
        assertEquals(0.02f, v.zoom, 0f)
        assertEquals(50f, v.panX, 0f)
    }

    @Test
    fun `invalid event resets baseline instead of contaminating the next frame`() {
        val v = Viewport()
        v.frame(1, 100f, 2, 300f)
        assertFalse(v.frame(1, Float.NaN, 2, 300f))
        assertFalse(v.frame(1, 200f, 2, 600f))
        assertEquals(1f, v.zoom, 0f)
        assertEquals(0f, v.panX, 0f)
    }

    @Test
    fun `invalid viewport or duplicated pointer cannot start pinch`() {
        val g = QuickShapeViewportGesture()
        assertFalse(g.update(1, 0f, 0f, 1, 10f, 0f, 1000, 1000, 1f, 0f, 0f, false))
        assertFalse(g.update(1, 0f, 0f, 2, 10f, 0f, 0, 1000, 1f, 0f, 0f, false))
        assertFalse(g.update(1, 0f, 0f, 2, 10f, 0f, 1000, 1000, 0f, 0f, 0f, false))
    }
}
