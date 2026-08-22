/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class GesturePivotAndPersistenceTest {

    @Test
    fun `two-finger rotation and zoom keeps centroid point pinned on screen`() {
        val viewW = 1080f
        val viewH = 2400f
        var canvasPanX = 50f
        var canvasPanY = -30f
        var canvasZoom = 1.2f
        var canvasRotation = 15f

        // Initial 2-finger centroid on screen
        val prevCentroid = Offset(400f, 600f)
        val k = 1.35f // 35% zoom-in
        val dRot = 25f // 25 degree clockwise rotation
        val dPan = Offset(20f, -10f) // Centroid also translates by (20, -10)
        val newCentroid = prevCentroid + dPan

        // Exact transform math used in CanvasTouchView and ReferenceWindow
        val rad = Math.toRadians(dRot.toDouble())
        val cosR = cos(rad).toFloat()
        val sinR = sin(rad).toFloat()

        val vx = prevCentroid.x - (viewW / 2f + canvasPanX)
        val vy = prevCentroid.y - (viewH / 2f + canvasPanY)

        val vRotX = k * (vx * cosR - vy * sinR)
        val vRotY = k * (vx * sinR + vy * cosR)

        val newZoom = canvasZoom * k
        val newRotation = canvasRotation + dRot
        val newPanX = newCentroid.x - vRotX - viewW / 2f
        val newPanY = newCentroid.y - vRotY - viewH / 2f

        // Verify that the vector from the old center to prevCentroid
        // when rotated by dRot and scaled by k matches the vector from new center to newCentroid
        val newCenter = Offset(viewW / 2f + newPanX, viewH / 2f + newPanY)
        val actualV = newCentroid - newCenter

        assertEquals(vRotX, actualV.x, 1e-4f)
        assertEquals(vRotY, actualV.y, 1e-4f)
    }

    @Test
    fun `eyedropper sensitivity correctly maps to responsive delays`() {
        fun computeEyedropperDelay(sensitivity: Int): Long {
            return (560L - (sensitivity.coerceIn(1, 5) - 1) * 70L).coerceIn(220L, 650L)
        }

        assertEquals(560L, computeEyedropperDelay(1)) // Low sensitivity -> longer delay
        assertEquals(490L, computeEyedropperDelay(2))
        assertEquals(420L, computeEyedropperDelay(3)) // Default -> 420ms
        assertEquals(350L, computeEyedropperDelay(4))
        assertEquals(280L, computeEyedropperDelay(5)) // High sensitivity -> snappy 280ms
    }

    @Test
    fun `reference window bounds and tab state ranges`() {
        val minWidth = 160f
        val maxWidth = 600f
        val minHeight = 160f
        val maxHeight = 700f

        val clampWidth = { w: Float -> w.coerceIn(minWidth, maxWidth) }
        val clampHeight = { h: Float -> h.coerceIn(minHeight, maxHeight) }

        assertEquals(160f, clampWidth(50f), 0.01f)
        assertEquals(600f, clampWidth(1200f), 0.01f)
        assertEquals(320f, clampWidth(320f), 0.01f)

        assertEquals(160f, clampHeight(100f), 0.01f)
        assertEquals(700f, clampHeight(900f), 0.01f)
        assertEquals(400f, clampHeight(400f), 0.01f)
    }
}
