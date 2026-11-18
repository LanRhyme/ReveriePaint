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

    @Test
    fun `document to direction round trip keeps the point inside the disk`() {
        val cx = 500.0
        val cy = 400.0
        val radius = 300.0
        val direction = FisheyeGuideGeometry.directionFromDocument(650.0, 550.0, cx, cy, radius)
        val restored = FisheyeGuideGeometry.documentFromDirection(
            direction.first, direction.second, direction.third, cx, cy, radius,
        )
        assertEquals(650f, restored.x, 1e-3f)
        assertEquals(550f, restored.y, 1e-3f)
    }

    @Test
    fun `snapped direction stays on the chosen horizontal or vertical curve`() {
        val a = 0.3
        val horizontal = FisheyeGuideGeometry.snapToHorizontalCurve(a, 0.4, 0.55, 0.3)
        assertEquals(kotlin.math.tan(a), horizontal.second / horizontal.third, 1e-6)
        val vertical = FisheyeGuideGeometry.snapToVerticalCurve(-0.2, 0.5, 0.45, 0.4)
        assertEquals(kotlin.math.tan(-0.2), vertical.first / vertical.third, 1e-6)
    }

    @Test
    fun `center ray snap keeps the start azimuth and passes through the true center`() {
        val x = 0.42
        val y = -0.31
        val z = 0.85
        val phi = FisheyeGuideGeometry.azimuthOf(x, y)
        val snapped = FisheyeGuideGeometry.snapToCenterRay(phi, x, y, z)
        // 任意采样点方位角都与起笔一致, 因此延长线穿过圆心
        assertEquals(phi, FisheyeGuideGeometry.azimuthOf(snapped.first, snapped.second), 1e-9)
        val projected = FisheyeGuideGeometry.projectDirection(snapped.first, snapped.second, snapped.third)
        val expected = FisheyeGuideGeometry.projectDirection(
            x / kotlin.math.sqrt(x * x + y * y + z * z),
            y / kotlin.math.sqrt(x * x + y * y + z * z),
            z / kotlin.math.sqrt(x * x + y * y + z * z),
        )
        // 方向不变时投影不变 (径向吸附只在半径方向压缩/拉伸)
        assertEquals(expected.x, projected.x, 1e-5f)
        assertEquals(expected.y, projected.y, 1e-5f)
    }

    @Test
    fun `continuous curve parameters pass through the start point without jumping to a visible line`() {
        val cx = 500.0
        val cy = 400.0
        val radius = 300.0
        val direction = FisheyeGuideGeometry.directionFromDocument(650.0, 550.0, cx, cy, radius)
        val x = direction.first
        val y = direction.second
        val z = direction.third

        val h = FisheyeGuideGeometry.horizontalDirection(
            FisheyeGuideGeometry.horizontalParameter(x, y, z),
            kotlin.math.asin(x.coerceIn(-1.0, 1.0)),
        )
        val v = FisheyeGuideGeometry.verticalDirection(
            FisheyeGuideGeometry.verticalParameter(x, y, z),
            kotlin.math.asin(y.coerceIn(-1.0, 1.0)),
        )
        assertEquals(x, h.first, 1e-9)
        assertEquals(y, h.second, 1e-9)
        assertEquals(z, h.third, 1e-9)
        assertEquals(x, v.first, 1e-9)
        assertEquals(y, v.second, 1e-9)
        assertEquals(z, v.third, 1e-9)
    }

    @Test
    fun `merged curve family follows the initial stroke direction`() {
        assertEquals(
            0,
            FisheyeGuideGeometry.chooseCurveFamily(0.0, 0.0, 1.0, 10.0, 0.0, 0.0, 0.0, 1.0),
        )
        assertEquals(
            1,
            FisheyeGuideGeometry.chooseCurveFamily(0.0, 0.0, 1.0, 0.0, 10.0, 0.0, 0.0, 1.0),
        )
    }

    @Test
    fun `snapping candidates match displayed curves including the diameters`() {
        val visible = FisheyeGuideGeometry.curveAngles(7)
        val snapping = FisheyeGuideGeometry.snappingCurveAngles(7)
        assertEquals(visible.size, snapping.size)
        assertTrue(snapping.any { kotlin.math.abs(it) < 1e-9 })
        for (i in snapping.indices) assertEquals(visible[i], snapping[i], 0.0)
    }

    @Test
    fun `nearest curve angle chooses the closest allowed line`() {
        val angles = FisheyeGuideGeometry.curveAngles(7)
        val target = angles[3] + 0.01
        assertEquals(angles[3], FisheyeGuideGeometry.nearestCurveAngle(target, angles), 0.0)
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
