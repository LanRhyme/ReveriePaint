/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class AssistedDrawingGeometryTest {

    @Test
    fun `grid2d selects horizontal or vertical ray at 0 deg and rotated axes at 30 deg`() {
        val g0 = DrawingGuideConfig(mode = GuideMode.GRID_2D, assistedDrawing = true, gridRotationDeg = 0f)
        val rayH = AssistedDrawingGeometry.selectLinearAssistRay(g0, Point2D(100f, 100f), Point2D(130f, 104f), 1000, 1000)
        assertNotNull(rayH)
        assertEquals(1f, rayH!!.dirX, 0.001f)
        assertEquals(0f, rayH.dirY, 0.001f)
        assertEquals(1f, rayH.forwardSign, 0.001f)

        val rayV = AssistedDrawingGeometry.selectLinearAssistRay(g0, Point2D(100f, 100f), Point2D(97f, 65f), 1000, 1000)
        assertNotNull(rayV)
        assertEquals(0f, rayV!!.dirX, 0.001f)
        assertEquals(1f, rayV.dirY, 0.001f)
        assertEquals(-1f, rayV.forwardSign, 0.001f)

        val g30 = DrawingGuideConfig(mode = GuideMode.GRID_2D, assistedDrawing = true, gridRotationDeg = 30f)
        val rad30 = Math.toRadians(30.0)
        val target30 = Point2D(100f + 40f * cos(rad30).toFloat(), 100f + 40f * sin(rad30).toFloat())
        val ray30 = AssistedDrawingGeometry.selectLinearAssistRay(g30, Point2D(100f, 100f), target30, 1000, 1000)
        assertNotNull(ray30)
        assertEquals(cos(rad30).toFloat(), ray30!!.dirX, 0.001f)
        assertEquals(sin(rad30).toFloat(), ray30.dirY, 0.001f)
    }

    @Test
    fun `isometric selects standard 30 deg and rotated 45 deg axes`() {
        val gIso = DrawingGuideConfig(mode = GuideMode.ISOMETRIC, assistedDrawing = true, gridRotationDeg = 0f)
        val rayUpRight = AssistedDrawingGeometry.selectLinearAssistRay(
            gIso,
            Point2D(500f, 500f),
            Point2D(500f + 43.3f, 500f - 25f),
            1000,
            1000,
        )
        assertNotNull(rayUpRight)
        assertEquals(AssistedDrawingGeometry.ISO_COS_30, rayUpRight!!.dirX, 0.001f)
        assertEquals(-AssistedDrawingGeometry.ISO_SIN_30, rayUpRight.dirY, 0.001f)

        val gRot45 = DrawingGuideConfig(mode = GuideMode.ISOMETRIC, assistedDrawing = true, gridRotationDeg = 45f)
        val verticalAxisRotated45 = Point2D(500f - 30f, 500f + 30f) // rotated (0, 1) by +45° => (-sin45, cos45)
        val rayRot = AssistedDrawingGeometry.selectLinearAssistRay(gRot45, Point2D(500f, 500f), verticalAxisRotated45, 1000, 1000)
        assertNotNull(rayRot)
        assertEquals(-0.7071f, rayRot!!.dirX, 0.002f)
        assertEquals(0.7071f, rayRot.dirY, 0.002f)
    }

    @Test
    fun `perspective 2D chooses between horizontal vertical and vanishing point ray`() {
        val vp = Point2D(800f, 200f)
        val g = DrawingGuideConfig(
            mode = GuideMode.PERSPECTIVE,
            assistedDrawing = true,
            perspective3d = false,
            perspectiveFisheye = false,
            perspectiveVanishingPoints = listOf(vp),
        )
        val origin = Point2D(200f, 500f)
        // Direction directly toward VP (600, -300) normalized
        val towardVp = Point2D(origin.x + 20f, origin.y - 10f)
        val ray = AssistedDrawingGeometry.selectLinearAssistRay(g, origin, towardVp, 1000, 1000)
        assertNotNull(ray)
        assertEquals(vp, ray!!.vanishingPoint)
    }

    @Test
    fun `pre-lock forward clamping eliminates start-of-stroke hooks and backward tails`() {
        val g = DrawingGuideConfig(mode = GuideMode.GRID_2D, assistedDrawing = true, gridRotationDeg = 0f)
        val origin = Point2D(100f, 100f)
        val triggerPt = Point2D(115f, 101f)
        val ray = AssistedDrawingGeometry.selectLinearAssistRay(g, origin, triggerPt, 1000, 1000)!!
        val maxForwardT = (triggerPt.x - origin.x) * ray.dirX + (triggerPt.y - origin.y) * ray.dirY

        val preLockJitter = listOf(
            Point2D(98f, 103f), // backward & perpendicular jitter on touchdown
            Point2D(99f, 104f), // still behind origin
            Point2D(103f, 102f), // slightly forward with vertical offset
            Point2D(102f, 98f), // slight backward wobble
        )

        var prevT = 0f
        val emitted = mutableListOf<Point2D>()
        for (pt in preLockJitter) {
            val ft = AssistedDrawingGeometry.projectPreLockForwardT(origin, ray, pt, prevT, maxForwardT)
            if (ft - prevT >= 0.05f) {
                emitted.add(Point2D(origin.x + ray.dirX * ray.forwardSign * ft, origin.y + ray.dirY * ray.forwardSign * ft))
                prevT = ft
            }
        }
        val snappedTrigger = AssistedDrawingGeometry.projectPointOntoRay(origin, ray, triggerPt)
        emitted.add(snappedTrigger)

        assertTrue(emitted.isNotEmpty())
        var lastX = origin.x
        for (p in emitted) {
            assertEquals(100f, p.y, 0.0001f)
            assertTrue("Expected monotonic forward X >= $lastX, got ${p.x}", p.x >= lastX)
            assertTrue("Expected X <= trigger X", p.x <= triggerPt.x + 0.0001f)
            lastX = p.x
        }
        assertEquals(115f, emitted.last().x, 0.001f)
    }

    @Test
    fun `directional assist active excludes symmetry and 3d perspective`() {
        assertTrue(AssistedDrawingGeometry.isDirectionalAssistActive(DrawingGuideConfig(mode = GuideMode.GRID_2D, assistedDrawing = true)))
        assertTrue(AssistedDrawingGeometry.isDirectionalAssistActive(DrawingGuideConfig(mode = GuideMode.ISOMETRIC, assistedDrawing = true)))
        assertTrue(AssistedDrawingGeometry.isDirectionalAssistActive(DrawingGuideConfig(mode = GuideMode.PERSPECTIVE, assistedDrawing = true, perspective3d = false)))
        assertFalse(AssistedDrawingGeometry.isDirectionalAssistActive(DrawingGuideConfig(mode = GuideMode.PERSPECTIVE, assistedDrawing = true, perspective3d = true)))
        assertFalse(AssistedDrawingGeometry.isDirectionalAssistActive(DrawingGuideConfig(mode = GuideMode.SYMMETRY, assistedDrawing = true)))
        assertFalse(AssistedDrawingGeometry.isDirectionalAssistActive(DrawingGuideConfig(mode = GuideMode.GRID_2D, assistedDrawing = false)))
        assertNull(AssistedDrawingGeometry.selectLinearAssistRay(DrawingGuideConfig(mode = GuideMode.GRID_2D), Point2D(10f, 10f), Point2D(10f, 10f), 1000, 1000))
    }

    @Test
    fun `grid rotation handle snapping snaps near standard angles and wraps 180 to 0`() {
        assertEquals(0f, AssistedDrawingGeometry.snapGridRotationHandleDeg(1.5f), 0.001f)
        assertEquals(30f, AssistedDrawingGeometry.snapGridRotationHandleDeg(28.5f), 0.001f)
        assertEquals(45f, AssistedDrawingGeometry.snapGridRotationHandleDeg(46.8f), 0.001f)
        assertEquals(90f, AssistedDrawingGeometry.snapGridRotationHandleDeg(89.0f), 0.001f)
        assertEquals(0f, AssistedDrawingGeometry.snapGridRotationHandleDeg(178.5f), 0.001f)
        assertEquals(22f, AssistedDrawingGeometry.snapGridRotationHandleDeg(22f), 0.001f)
    }
}
