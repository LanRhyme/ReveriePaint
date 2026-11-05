/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class GuideModelsTest {

    @Test
    fun `fit straight line with angle snapping`() {
        val linePoints = (0..20).map { i ->
            Point2D(10f + i * 10f, 20f + (if (i % 2 == 0) 0.5f else -0.5f))
        }
        val res = QuickShapeFitter.fit(linePoints)
        assertNotNull(res)
        assertEquals(QuickShapeType.LINE, res!!.type)
        assertEquals(2, res.points.size)
    }

    @Test
    fun `fit circle for circular point loop`() {
        val circlePoints = (0..32).map { i ->
            val angle = i * 2.0 * Math.PI / 32.0
            val r = 100.0 + (if (i % 2 == 0) 1.0 else -1.0)
            Point2D((200.0 + r * cos(angle)).toFloat(), (200.0 + r * sin(angle)).toFloat())
        }
        val res = QuickShapeFitter.fit(circlePoints)
        assertNotNull(res)
        assertEquals(QuickShapeType.CIRCLE, res!!.type)
        assertTrue("Radius should be around 100", res.radiusX in 90f..110f)
    }

    @Test
    fun `fit rectangle for rectangular loop`() {
        val pts = mutableListOf<Point2D>()
        // Top edge
        for (x in 0..10) pts.add(Point2D(x * 10f, 0f))
        // Right edge
        for (y in 0..10) pts.add(Point2D(100f, y * 10f))
        // Bottom edge
        for (x in 10 downTo 0) pts.add(Point2D(x * 10f, 100f))
        // Left edge
        for (y in 10 downTo 0) pts.add(Point2D(0f, y * 10f))

        val res = QuickShapeFitter.fit(pts)
        assertNotNull(res)
        assertEquals(QuickShapeType.RECTANGLE, res!!.type)
    }

    @Test
    fun `color quantizer extracts diverse palette`() {
        // Mock image with red, green, blue, yellow, and cyan blocks
        val pixels = IntArray(1000)
        for (i in 0 until 200) pixels[i] = 0xFFFF0000.toInt() // Red
        for (i in 200 until 400) pixels[i] = 0xFF00FF00.toInt() // Green
        for (i in 400 until 600) pixels[i] = 0xFF0000FF.toInt() // Blue
        for (i in 600 until 800) pixels[i] = 0xFFFFFF00.toInt() // Yellow
        for (i in 800 until 1000) pixels[i] = 0xFF00FFFF.toInt() // Cyan

        val palette = ColorQuantizer.extractPalette(pixels, targetCount = 10)
        assertTrue("Palette should have colors", palette.isNotEmpty())
        assertTrue("Palette should contain multiple distinct colors", palette.size >= 4)
    }

    @Test
    fun `computeSymmetricPoints vertical mode mirrors across center X`() {
        val config = DrawingGuideConfig(
            mode = GuideMode.SYMMETRY,
            symmetryType = SymmetryType.VERTICAL,
            symmetryCenterX = 0.5f,
            symmetryCenterY = 0.5f
        )
        val symPts = config.computeSymmetricPoints(Point2D(200f, 300f), docWidth = 1000, docHeight = 1000)
        assertEquals(1, symPts.size)
        assertEquals(800f, symPts[0].x, 0.001f)
        assertEquals(300f, symPts[0].y, 0.001f)
    }

    @Test
    fun `computeSymmetricPoints horizontal mode mirrors across center Y`() {
        val config = DrawingGuideConfig(
            mode = GuideMode.SYMMETRY,
            symmetryType = SymmetryType.HORIZONTAL,
            symmetryCenterX = 0.5f,
            symmetryCenterY = 0.5f
        )
        val symPts = config.computeSymmetricPoints(Point2D(200f, 300f), docWidth = 1000, docHeight = 1000)
        assertEquals(1, symPts.size)
        assertEquals(200f, symPts[0].x, 0.001f)
        assertEquals(700f, symPts[0].y, 0.001f)
    }

    @Test
    fun `computeSymmetricPoints quadrant mode mirrors across both axes`() {
        val config = DrawingGuideConfig(
            mode = GuideMode.SYMMETRY,
            symmetryType = SymmetryType.QUADRANT,
            symmetryCenterX = 0.5f,
            symmetryCenterY = 0.5f
        )
        val symPts = config.computeSymmetricPoints(Point2D(200f, 300f), docWidth = 1000, docHeight = 1000)
        assertEquals(3, symPts.size)
        assertEquals(Point2D(800f, 300f), symPts[0])
        assertEquals(Point2D(200f, 700f), symPts[1])
        assertEquals(Point2D(800f, 700f), symPts[2])
    }

    @Test
    fun `computeSymmetricPoints radial mode produces 7 branches`() {
        val config = DrawingGuideConfig(
            mode = GuideMode.SYMMETRY,
            symmetryType = SymmetryType.RADIAL,
            symmetryCenterX = 0.5f,
            symmetryCenterY = 0.5f
        )
        val symPts = config.computeSymmetricPoints(Point2D(600f, 500f), docWidth = 1000, docHeight = 1000)
        assertEquals(7, symPts.size)
        // Opposite branch (k=4, 180 degrees) should be at (400, 500)
        val opposite = symPts[3]
        assertEquals(400f, opposite.x, 0.01f)
        assertEquals(500f, opposite.y, 0.01f)
    }

    @Test
    fun `computeSymmetricPoints returns empty for mode OFF or invalid coordinates`() {
        val offConfig = DrawingGuideConfig(mode = GuideMode.OFF)
        assertTrue(offConfig.computeSymmetricPoints(Point2D(200f, 300f), 1000, 1000).isEmpty())

        val symConfig = DrawingGuideConfig(mode = GuideMode.SYMMETRY)
        assertTrue(symConfig.computeSymmetricPoints(Point2D(Float.NaN, 300f), 1000, 1000).isEmpty())
        assertTrue(symConfig.computeSymmetricPoints(Point2D(200f, Float.POSITIVE_INFINITY), 1000, 1000).isEmpty())
    }

    @Test
    fun `computeSymmetricPoints vertical mode rotated 90 degrees mirrors vertically`() {
        val config = DrawingGuideConfig(
            mode = GuideMode.SYMMETRY,
            symmetryType = SymmetryType.VERTICAL,
            symmetryCenterX = 0.5f,
            symmetryCenterY = 0.5f,
            symmetryRotationDeg = 90f
        )
        // Center is (500, 500). Point (500, 300) rotated 90 degrees axis should mirror to (500, 700)
        val symPts = config.computeSymmetricPoints(Point2D(500f, 300f), docWidth = 1000, docHeight = 1000)
        assertEquals(1, symPts.size)
        assertEquals(500f, symPts[0].x, 0.01f)
        assertEquals(700f, symPts[0].y, 0.01f)
    }

    @Test
    fun `computeSymmetricPoints vertical mode rotated 45 degrees mirrors across diagonal`() {
        val config = DrawingGuideConfig(
            mode = GuideMode.SYMMETRY,
            symmetryType = SymmetryType.VERTICAL,
            symmetryCenterX = 0.5f,
            symmetryCenterY = 0.5f,
            symmetryRotationDeg = 45f
        )
        // Center is (500, 500). Point (600, 500) -> vector (+100, 0).
        // Vertical axis rotated 45 deg has direction (-sin45, cos45).
        // Reflection of (600, 500) across this axis is (500, 400)
        val symPts = config.computeSymmetricPoints(Point2D(600f, 500f), docWidth = 1000, docHeight = 1000)
        assertEquals(1, symPts.size)
        assertEquals(500f, symPts[0].x, 0.01f)
        assertEquals(400f, symPts[0].y, 0.01f)
    }

    @Test
    fun `computeSymmetricSamples vertical mode mirrors point tilt and rotation`() {
        val config = DrawingGuideConfig(
            mode = GuideMode.SYMMETRY,
            symmetryType = SymmetryType.VERTICAL,
            symmetryCenterX = 0.5f,
            symmetryCenterY = 0.5f
        )
        val samples = config.computeSymmetricSamples(
            Point2D(600f, 500f), tiltX = 10f, tiltY = 3f, rotation = 30f,
            docWidth = 1000, docHeight = 1000
        )
        assertEquals(1, samples.size)
        assertEquals(400f, samples[0].point.x, 0.001f)
        assertEquals(500f, samples[0].point.y, 0.001f)
        // 倾角向量随点一起镜像, 笔杆自转在反射下取负
        assertEquals(-10f, samples[0].tiltX, 0.001f)
        assertEquals(3f, samples[0].tiltY, 0.001f)
        assertEquals(-30f, samples[0].rotation, 0.001f)
    }

    @Test
    fun `computeSymmetricSamples horizontal mode mirrors point tilt and rotation`() {
        val config = DrawingGuideConfig(
            mode = GuideMode.SYMMETRY,
            symmetryType = SymmetryType.HORIZONTAL,
            symmetryCenterX = 0.5f,
            symmetryCenterY = 0.5f
        )
        val samples = config.computeSymmetricSamples(
            Point2D(500f, 600f), tiltX = 10f, tiltY = 3f, rotation = 30f,
            docWidth = 1000, docHeight = 1000
        )
        assertEquals(1, samples.size)
        assertEquals(500f, samples[0].point.x, 0.001f)
        assertEquals(400f, samples[0].point.y, 0.001f)
        assertEquals(10f, samples[0].tiltX, 0.001f)
        assertEquals(-3f, samples[0].tiltY, 0.001f)
        assertEquals(-30f, samples[0].rotation, 0.001f)
    }

    @Test
    fun `computeSymmetricSamples returns empty when symmetry mode is off`() {
        val offConfig = DrawingGuideConfig(mode = GuideMode.OFF)
        assertTrue(
            offConfig.computeSymmetricSamples(
                Point2D(600f, 500f), 10f, 3f, 30f, 1000, 1000
            ).isEmpty()
        )
    }

    @Test
    fun `writeSymmetricPoints vertical mode matches computeSymmetricPoints`() {
        val config = DrawingGuideConfig(
            mode = GuideMode.SYMMETRY,
            symmetryType = SymmetryType.VERTICAL,
            symmetryCenterX = 0.5f,
            symmetryCenterY = 0.5f
        )
        val docPt = Point2D(200f, 300f)
        val expected = config.computeSymmetricPoints(docPt, docWidth = 1000, docHeight = 1000)
        val outX = FloatArray(16)
        val outY = FloatArray(16)
        val count = config.writeSymmetricPoints(docPt.x, docPt.y, 1000, 1000, outX, outY)
        assertEquals(expected.size, count)
        for (i in 0 until count) {
            assertEquals(expected[i].x, outX[i], 0.001f)
            assertEquals(expected[i].y, outY[i], 0.001f)
        }

        val tinyX = FloatArray(1)
        val tinyY = FloatArray(1)
        val tinyCount = config.writeSymmetricPoints(docPt.x, docPt.y, 1000, 1000, tinyX, tinyY)
        assertTrue(tinyCount <= 1)
        assertEquals(1, tinyCount)
        assertEquals(expected[0].x, tinyX[0], 0.001f)
        assertEquals(expected[0].y, tinyY[0], 0.001f)
    }

    @Test
    fun `writeSymmetricPoints horizontal mode matches computeSymmetricPoints`() {
        val config = DrawingGuideConfig(
            mode = GuideMode.SYMMETRY,
            symmetryType = SymmetryType.HORIZONTAL,
            symmetryCenterX = 0.5f,
            symmetryCenterY = 0.5f
        )
        val docPt = Point2D(200f, 300f)
        val expected = config.computeSymmetricPoints(docPt, docWidth = 1000, docHeight = 1000)
        val outX = FloatArray(16)
        val outY = FloatArray(16)
        val count = config.writeSymmetricPoints(docPt.x, docPt.y, 1000, 1000, outX, outY)
        assertEquals(expected.size, count)
        for (i in 0 until count) {
            assertEquals(expected[i].x, outX[i], 0.001f)
            assertEquals(expected[i].y, outY[i], 0.001f)
        }

        val tinyX = FloatArray(1)
        val tinyY = FloatArray(1)
        val tinyCount = config.writeSymmetricPoints(docPt.x, docPt.y, 1000, 1000, tinyX, tinyY)
        assertTrue(tinyCount <= 1)
        assertEquals(1, tinyCount)
        assertEquals(expected[0].x, tinyX[0], 0.001f)
        assertEquals(expected[0].y, tinyY[0], 0.001f)
    }

    @Test
    fun `writeSymmetricPoints quadrant mode matches computeSymmetricPoints`() {
        val config = DrawingGuideConfig(
            mode = GuideMode.SYMMETRY,
            symmetryType = SymmetryType.QUADRANT,
            symmetryCenterX = 0.5f,
            symmetryCenterY = 0.5f
        )
        val docPt = Point2D(200f, 300f)
        val expected = config.computeSymmetricPoints(docPt, docWidth = 1000, docHeight = 1000)
        val outX = FloatArray(16)
        val outY = FloatArray(16)
        val count = config.writeSymmetricPoints(docPt.x, docPt.y, 1000, 1000, outX, outY)
        assertEquals(expected.size, count)
        for (i in 0 until count) {
            assertEquals(expected[i].x, outX[i], 0.001f)
            assertEquals(expected[i].y, outY[i], 0.001f)
        }

        val tinyX = FloatArray(1)
        val tinyY = FloatArray(1)
        val tinyCount = config.writeSymmetricPoints(docPt.x, docPt.y, 1000, 1000, tinyX, tinyY)
        assertTrue(tinyCount <= 1)
        assertEquals(1, tinyCount)
        assertEquals(expected[0].x, tinyX[0], 0.001f)
        assertEquals(expected[0].y, tinyY[0], 0.001f)
    }

    @Test
    fun `writeSymmetricPoints radial mode matches computeSymmetricPoints`() {
        val config = DrawingGuideConfig(
            mode = GuideMode.SYMMETRY,
            symmetryType = SymmetryType.RADIAL,
            symmetryCenterX = 0.5f,
            symmetryCenterY = 0.5f
        )
        val docPt = Point2D(600f, 500f)
        val expected = config.computeSymmetricPoints(docPt, docWidth = 1000, docHeight = 1000)
        val outX = FloatArray(16)
        val outY = FloatArray(16)
        val count = config.writeSymmetricPoints(docPt.x, docPt.y, 1000, 1000, outX, outY)
        assertEquals(expected.size, count)
        for (i in 0 until count) {
            assertEquals(expected[i].x, outX[i], 0.001f)
            assertEquals(expected[i].y, outY[i], 0.001f)
        }

        val tinyX = FloatArray(1)
        val tinyY = FloatArray(1)
        val tinyCount = config.writeSymmetricPoints(docPt.x, docPt.y, 1000, 1000, tinyX, tinyY)
        assertTrue(tinyCount <= 1)
        assertEquals(1, tinyCount)
        assertEquals(expected[0].x, tinyX[0], 0.001f)
        assertEquals(expected[0].y, tinyY[0], 0.001f)
    }

    @Test
    fun `writeSymmetricPoints returns zero for mode off or non-finite coords`() {
        val offConfig = DrawingGuideConfig(mode = GuideMode.OFF)
        val outX = FloatArray(16)
        val outY = FloatArray(16)
        assertEquals(0, offConfig.writeSymmetricPoints(200f, 300f, 1000, 1000, outX, outY))

        val symConfig = DrawingGuideConfig(mode = GuideMode.SYMMETRY)
        assertEquals(0, symConfig.writeSymmetricPoints(Float.NaN, 300f, 1000, 1000, outX, outY))
        assertEquals(0, symConfig.writeSymmetricPoints(200f, Float.POSITIVE_INFINITY, 1000, 1000, outX, outY))
        assertEquals(0, symConfig.writeSymmetricPoints(200f, 300f, 1000, 1000, FloatArray(0), outY))
    }

    @Test
    fun `computeSymmetricPoints radial mode with 3 rays produces 2 branches for 3-blade top`() {
        val config = DrawingGuideConfig(
            mode = GuideMode.SYMMETRY,
            symmetryType = SymmetryType.RADIAL,
            symmetryRayCount = 3,
            symmetryRotational = true,
            symmetryCenterX = 0.5f,
            symmetryCenterY = 0.5f
        )
        val symPts = config.computeSymmetricPoints(Point2D(600f, 500f), docWidth = 1000, docHeight = 1000)
        assertEquals(2, symPts.size)
        // Point is (100, 0) in local.
        // Branch 1 at 120 deg: cos(120) = -0.5, sin(120) = 0.866025
        assertEquals(450f, symPts[0].x, 0.05f)
        assertEquals(586.6f, symPts[0].y, 0.1f)
        // Branch 2 at 240 deg: cos(240) = -0.5, sin(240) = -0.866025
        assertEquals(450f, symPts[1].x, 0.05f)
        assertEquals(413.4f, symPts[1].y, 0.1f)
    }

    @Test
    fun `computeSymmetricPoints radial kaleidoscope mode with 4 rays produces 7 branches`() {
        val config = DrawingGuideConfig(
            mode = GuideMode.SYMMETRY,
            symmetryType = SymmetryType.RADIAL,
            symmetryRayCount = 4,
            symmetryRotational = false,
            symmetryCenterX = 0.5f,
            symmetryCenterY = 0.5f
        )
        val docPt = Point2D(600f, 500f)
        val symPts = config.computeSymmetricPoints(docPt, docWidth = 1000, docHeight = 1000)
        assertEquals(2 * 4 - 1, symPts.size) // 7 branches (8 total sectors)

        val outX = FloatArray(32)
        val outY = FloatArray(32)
        val count = config.writeSymmetricPoints(docPt.x, docPt.y, 1000, 1000, outX, outY)
        assertEquals(symPts.size, count)
        for (i in 0 until count) {
            assertEquals(symPts[i].x, outX[i], 0.001f)
            assertEquals(symPts[i].y, outY[i], 0.001f)
        }
    }

    @Test
    fun `computeSymmetricSamples default kaleidoscope exceeds live mirror branch cap`() {
        // 1.6.2 线上 AIOOBE 的回归钉子: 万花筒默认 8 轴 -> 15 个镜像分支, 而实时镜像
        // 只支持 8 支。分支数本身是产品语义 (2*rays-1), 不能靠调小它来"修"崩溃。
        val config = DrawingGuideConfig(
            mode = GuideMode.SYMMETRY,
            symmetryType = SymmetryType.RADIAL,
            symmetryRayCount = SYMMETRY_RAY_COUNT_DEFAULT,
            symmetryRotational = false,
        )
        val branches = config.computeSymmetricSamples(Point2D(600f, 500f), 0f, 0f, 0f, 1000, 1000)
        assertEquals(2 * SYMMETRY_RAY_COUNT_DEFAULT - 1, branches.size)
        assertTrue("默认万花筒分支数应超过实时镜像上限", branches.size > 8)
    }

    @Test
    fun `computeSymmetricSamples covers full ray range without shrinking branches`() {
        // 2..36 轴全覆盖: 旋转 = rays-1 支, 万花筒 = 2*rays-1 支 (最多 71 支)。
        // 缓冲/队列必须按这个规模准备, 不能假设 <= 8。
        for (rays in SYMMETRY_RAY_COUNT_MIN..SYMMETRY_RAY_COUNT_MAX) {
            val rotational = DrawingGuideConfig(
                mode = GuideMode.SYMMETRY,
                symmetryType = SymmetryType.RADIAL,
                symmetryRayCount = rays,
                symmetryRotational = true,
            )
            assertEquals(
                rays - 1,
                rotational.computeSymmetricSamples(Point2D(600f, 500f), 0f, 0f, 0f, 1000, 1000).size,
            )

            val kaleidoscope = DrawingGuideConfig(
                mode = GuideMode.SYMMETRY,
                symmetryType = SymmetryType.RADIAL,
                symmetryRayCount = rays,
                symmetryRotational = false,
            )
            assertEquals(
                2 * rays - 1,
                kaleidoscope.computeSymmetricSamples(Point2D(600f, 500f), 0f, 0f, 0f, 1000, 1000).size,
            )
        }
    }
}
