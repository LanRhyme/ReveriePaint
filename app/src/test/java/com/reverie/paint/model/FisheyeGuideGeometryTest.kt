/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class FisheyeGuideGeometryTest {
    @Test
    fun `horizontal and vertical curves land on their four exact cardinal endpoints`() {
        val mesh = FisheyeGuideGeometry.build(11, 11, screenRadiusPx = 800f)
        for (curve in mesh.horizontal) {
            assertPoint(curve, 0, -1f, 0f)
            assertPoint(curve, curve.size - 2, 1f, 0f)
        }
        for (curve in mesh.vertical) {
            assertPoint(curve, 0, 0f, -1f)
            assertPoint(curve, curve.size - 2, 0f, 1f)
        }
    }

    @Test
    fun `each family contains one straight diameter and noncentral curves bow away from center`() {
        val mesh = FisheyeGuideGeometry.build(11, 11, screenRadiusPx = 700f)
        val middle = 5
        assertEquals(2, mesh.horizontal[middle].size / 2)
        assertPoint(mesh.horizontal[middle], 0, -1f, 0f)
        assertPoint(mesh.horizontal[middle], 2, 1f, 0f)
        assertEquals(2, mesh.vertical[middle].size / 2)
        assertPoint(mesh.vertical[middle], 0, 0f, -1f)
        assertPoint(mesh.vertical[middle], 2, 0f, 1f)

        val upper = mesh.horizontal[middle - 1]
        val lower = mesh.horizontal[middle + 1]
        assertTrue(upper.any { it > 0.05f })
        assertTrue(lower.any { it < -0.05f })
    }

    @Test
    fun `opposite curves are mirror symmetric and every projected point remains on disk`() {
        val mesh = FisheyeGuideGeometry.build(9, 13, screenRadiusPx = 1100f)
        for (i in mesh.horizontal.indices) {
            val opposite = mesh.horizontal.lastIndex - i
            val first = mesh.horizontal[i]
            val second = mesh.horizontal[opposite]
            assertEquals(first.size, second.size)
            for (p in first.indices step 2) {
                assertEquals(first[p], second[p], 1e-5f)
                assertEquals(first[p + 1], -second[p + 1], 1e-5f)
            }
        }
        for (curve in mesh.horizontal + mesh.vertical) {
            for (i in curve.indices step 2) {
                assertTrue(hypot(curve[i], curve[i + 1]) <= 1.00001f)
            }
        }
    }

    @Test
    fun `equator z zero projects to the rim and optical axis projects to center`() {
        assertPoint(FisheyeGuideGeometry.projectDirection(1.0, 0.0, 0.0), 1f, 0f)
        assertPoint(FisheyeGuideGeometry.projectDirection(-1.0, 0.0, 0.0), -1f, 0f)
        assertPoint(FisheyeGuideGeometry.projectDirection(0.0, 1.0, 0.0), 0f, 1f)
        assertPoint(FisheyeGuideGeometry.projectDirection(0.0, -1.0, 0.0), 0f, -1f)
        assertPoint(FisheyeGuideGeometry.projectDirection(0.0, 0.0, 1.0), 0f, 0f)
    }

    @Test
    fun `line counts are odd and explicitly include the central diameter`() {
        assertEquals(11, FisheyeGuideGeometry.normalizeCurveCount(11))
        assertEquals(11, FisheyeGuideGeometry.normalizeCurveCount(10))
        assertEquals(25, FisheyeGuideGeometry.normalizeCurveCount(25))
        assertEquals(5, FisheyeGuideGeometry.normalizeCurveCount(2))
        assertEquals(0.0, FisheyeGuideGeometry.curveAngles(11)[5], 0.0)
    }

    @Test
    fun `pixel error adaptive sampling adds points when the guide is more zoomed`() {
        val lowZoom = FisheyeGuideGeometry.build(11, 11, screenRadiusPx = 100f)
        val highZoom = FisheyeGuideGeometry.build(11, 11, screenRadiusPx = 1600f)
        val lowPoints = lowZoom.horizontal.sumOf { it.size } + lowZoom.vertical.sumOf { it.size }
        val highPoints = highZoom.horizontal.sumOf { it.size } + highZoom.vertical.sumOf { it.size }
        assertTrue(highPoints > lowPoints)
    }

    private fun assertPoint(curve: FloatArray, pointOffset: Int, x: Float, y: Float) {
        assertEquals(x, curve[pointOffset], 1e-5f)
        assertEquals(y, curve[pointOffset + 1], 1e-5f)
    }

    private fun assertPoint(point: Point2D, x: Float, y: Float) {
        assertEquals(x, point.x, 1e-5f)
        assertEquals(y, point.y, 1e-5f)
    }
}
