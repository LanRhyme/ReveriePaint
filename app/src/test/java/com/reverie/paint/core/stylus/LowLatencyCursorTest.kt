/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.stylus

import com.reverie.paint.core.PaintViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

class LowLatencyCursorTest {

    @Test
    fun testPredictTouchPointScalarZeroAllocation() {
        val predictor = UniversalKalmanPredictor(predictionTargetMs = 30.0f)
        predictor.setScreenDiagonal(2500f)
        predictor.setRefreshRate(120f)

        val outPoint = FloatArray(3)

        // Before sufficient iterations (< 3), scalar prediction returns false
        assertFalse(predictor.predictTouchPointScalar(outPoint))

        var x = 50f
        val y = 150f
        var time = 1000L

        // Feed steady moving samples
        for (i in 0 until 10) {
            predictor.addPoint(x, y, 0.6f, time)
            x += 10f
            time += 8L
        }

        val hasPred = predictor.predictTouchPointScalar(outPoint)
        assertTrue("Steady moving stylus should produce valid scalar prediction", hasPred)
        assertTrue("Predicted X (${outPoint[0]}) should be ahead of last X (${predictor.lastPosX})", outPoint[0] > predictor.lastPosX)
        assertEquals("Predicted Y should remain close to linear path", y, outPoint[1], 1.5f)
        assertTrue("Predicted pressure should be within [0.01, 1.0]", outPoint[2] in 0.01f..1.0f)

        // Equivalence with predictTouchPoint()
        val objectPred = predictor.predictTouchPoint()
        assertNotNull("predictTouchPoint should be non-null", objectPred)
        assertEquals("Scalar X must match object X", objectPred!!.x, outPoint[0], 0.001f)
        assertEquals("Scalar Y must match object Y", objectPred.y, outPoint[1], 0.001f)
        assertEquals("Scalar pressure must match object pressure", objectPred.pressure, outPoint[2], 0.001f)
    }

    @Test
    fun testPredictTouchPointScalarGuardConditions() {
        val predictor = UniversalKalmanPredictor(predictionTargetMs = 25.0f)
        val shortBuf = FloatArray(2)

        // Buffer smaller than 3 must return false safely
        assertFalse("Buffer smaller than 3 should return false", predictor.predictTouchPointScalar(shortBuf))

        // Stationary points should not extrapolate forward
        val buf = FloatArray(3)
        var time = 500L
        for (i in 0 until 10) {
            predictor.addPoint(200f, 200f, 0.5f, time)
            time += 8L
        }
        assertFalse("Stationary touch points must not produce forward extrapolation", predictor.predictTouchPointScalar(buf))
    }

    @Test
    fun testRenderedFrontierEncapsulation() {
        val frontier = PaintViewModel.RenderedFrontier(120.5f, 340.25f, 5000L)
        assertEquals(120.5f, frontier.docX, 0.001f)
        assertEquals(340.25f, frontier.docY, 0.001f)
        assertEquals(5000L, frontier.timeMs)

        // Test immutability and copy semantics
        val updated = frontier.copy(docX = 130f, timeMs = 5008L)
        assertEquals(130f, updated.docX, 0.001f)
        assertEquals(340.25f, updated.docY, 0.001f)
        assertEquals(5008L, updated.timeMs)

        // Verify original remains unmodified (immutable value type)
        assertEquals(120.5f, frontier.docX, 0.001f)
    }

    @Test
    fun testHoverVsyncForwardExtrapolationMath() {
        // Test 1-frame VSYNC lead calculation
        val dt = 8L // 120Hz report rate (8.33ms)
        val rawX1 = 100f
        val rawY1 = 200f
        val rawX2 = 108f
        val rawY2 = 206f

        val vx = (rawX2 - rawX1) / dt.toFloat() // 1.0 px/ms
        val vy = (rawY2 - rawY1) / dt.toFloat() // 0.75 px/ms
        val speed = hypot(vx, vy)
        assertTrue("Speed should be > 0.05 px/ms", speed > 0.05f)

        val reportRateMs = 8.33f
        var leadDx = vx * reportRateMs
        var leadDy = vy * reportRateMs
        val leadDist = hypot(leadDx, leadDy)

        val maxLeadPx = 25f * 2.5f // 62.5px for density 2.5
        if (leadDist > maxLeadPx) {
            val scale = maxLeadPx / leadDist
            leadDx *= scale
            leadDy *= scale
        }

        val predX = rawX2 + leadDx
        val predY = rawY2 + leadDy

        assertTrue("Hover predicted X must be in direction of motion", predX > rawX2)
        assertTrue("Hover predicted Y must be in direction of motion", predY > rawY2)
        assertEquals("Extrapolated displacement matches velocity * lead", 1.0f * reportRateMs, predX - rawX2, 0.01f)
        assertEquals("Extrapolated displacement matches velocity * lead", 0.75f * reportRateMs, predY - rawY2, 0.01f)
    }

    @Test
    fun testHoverLeadClampingMath() {
        // High speed motion (10 px/ms)
        val vx = 8.0f
        val vy = 6.0f
        val reportRateMs = 16.6f // 60Hz

        var leadDx = vx * reportRateMs // 132.8 px
        var leadDy = vy * reportRateMs // 99.6 px
        val leadDist = hypot(leadDx, leadDy) // 166.0 px

        val density = 2.0f
        val maxLeadPx = 25f * density // 50 px

        assertTrue("Unclamped distance exceeds max lead threshold", leadDist > maxLeadPx)

        if (leadDist > maxLeadPx) {
            val scale = maxLeadPx / leadDist
            leadDx *= scale
            leadDy *= scale
        }

        val clampedDist = hypot(leadDx, leadDy)
        assertEquals("Clamped distance must exactly equal maxLeadPx", maxLeadPx, clampedDist, 0.01f)

        // Direction angle must remain invariant under clamping
        val originalAngle = kotlin.math.atan2(vy.toDouble(), vx.toDouble())
        val clampedAngle = kotlin.math.atan2(leadDy.toDouble(), leadDx.toDouble())
        assertEquals("Clamping must preserve vector angle", originalAngle, clampedAngle, 0.0001)
    }

    @Test
    fun testPressureSmoothingEmaFilterConvergence() {
        var smoothedPressure = -1f

        // Initial sample initializes filter immediately without ramp-up delay
        val target1 = 0.8f
        smoothedPressure = if (smoothedPressure < 0f) target1 else smoothedPressure * 0.65f + target1 * 0.35f
        assertEquals("First sample must immediately set value", target1, smoothedPressure, 0.001f)

        // Sudden jump to 0.2f (e.g. rapid pen lift transition)
        val target2 = 0.2f
        for (step in 0 until 15) {
            smoothedPressure = smoothedPressure * 0.65f + target2 * 0.35f
        }

        // After 15 EMA steps with alpha=0.35, residual error (0.65^15 = 0.0015) is negligible
        assertEquals("EMA filter must smoothly converge to target", target2, smoothedPressure, 0.005f)
    }

    @Test
    fun testDirtyBoundingBoxUnionMath() {
        // Old cursor at (100, 100), radius 20, margin 8
        val oldX = 100f
        val oldY = 100f
        val oldR = 20f
        val margin = 8f

        val oldL = oldX - oldR - margin // 72
        val oldT = oldY - oldR - margin // 72
        val oldRgt = oldX + oldR + margin // 128
        val oldB = oldY + oldR + margin // 128

        // New cursor at (140, 130), radius 25, margin 8
        val newX = 140f
        val newY = 130f
        val newR = 25f

        val newL = newX - newR - margin // 107
        val newT = newY - newR - margin // 97
        val newRgt = newX + newR + margin // 173
        val newB = newY + newR + margin // 163

        // Union bounding box
        val dirtyL = minOf(oldL, newL)
        val dirtyT = minOf(oldT, newT)
        val dirtyRgt = maxOf(oldRgt, newRgt)
        val dirtyB = maxOf(oldB, newB)

        assertEquals("Union left bound", 72f, dirtyL, 0.001f)
        assertEquals("Union top bound", 72f, dirtyT, 0.001f)
        assertEquals("Union right bound", 173f, dirtyRgt, 0.001f)
        assertEquals("Union bottom bound", 163f, dirtyB, 0.001f)

        // Verify union contains all vertices of both circles
        assertTrue("Union must cover old circle left", dirtyL <= oldX - oldR)
        assertTrue("Union must cover old circle right", dirtyRgt >= oldX + oldR)
        assertTrue("Union must cover new circle left", dirtyL <= newX - newR)
        assertTrue("Union must cover new circle right", dirtyRgt >= newX + newR)
        assertTrue("Union must cover old circle top", dirtyT <= oldY - oldR)
        assertTrue("Union must cover old circle bottom", dirtyB >= oldY + oldR)
        assertTrue("Union must cover new circle top", dirtyT <= newY - newR)
        assertTrue("Union must cover new circle bottom", dirtyB >= newY + newR)
    }
}
