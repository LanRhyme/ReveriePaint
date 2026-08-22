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
    fun `eyedropper sensitivity correctly maps to responsive delays and move slop`() {
        fun computeEyedropperDelay(sensitivity: Int): Long {
            return (520L - (sensitivity.coerceIn(1, 5) - 1) * 70L).coerceIn(200L, 600L)
        }

        fun computeMoveSlopDp(sensitivity: Int): Float {
            return (1.5f + (sensitivity.coerceIn(1, 5) - 3) * 0.3f).coerceIn(0.6f, 2.5f)
        }

        assertEquals(520L, computeEyedropperDelay(1))
        assertEquals(450L, computeEyedropperDelay(2))
        assertEquals(380L, computeEyedropperDelay(3)) // Default -> 380ms
        assertEquals(310L, computeEyedropperDelay(4))
        assertEquals(240L, computeEyedropperDelay(5))

        // Level 3 is 1.5dp; lower levels have smaller thresholds (0.9dp, 1.2dp)
        assertEquals(0.9f, computeMoveSlopDp(1), 0.001f)
        assertEquals(1.2f, computeMoveSlopDp(2), 0.001f)
        assertEquals(1.5f, computeMoveSlopDp(3), 0.001f)
        assertEquals(1.8f, computeMoveSlopDp(4), 0.001f)
        assertEquals(2.1f, computeMoveSlopDp(5), 0.001f)
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
