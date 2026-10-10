/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import org.junit.Assert.*
import org.junit.Test

class BrushDynamicsTest {

    @Test
    fun `CurvePoint clamps coordinates between 0 and 1`() {
        val pt1 = CurvePoint.of(-0.5f, 1.5f)
        assertEquals(0f, pt1.x, 0.0001f)
        assertEquals(1f, pt1.y, 0.0001f)

        val pt2 = CurvePoint.of(0.35f, 0.85f)
        assertEquals(0.35f, pt2.x, 0.0001f)
        assertEquals(0.85f, pt2.y, 0.0001f)
    }

    @Test
    fun `CurvePreset creates valid points`() {
        for (preset in CurvePreset.entries) {
            val pts = preset.createPoints()
            assertTrue("Preset ${preset.name} should have at least 2 points", pts.size >= 2)
            assertEquals("Start point x should be 0", 0f, pts.first().x, 0.0001f)
            assertEquals("End point x should be 1", 1f, pts.last().x, 0.0001f)
        }
    }

    @Test
    fun `toKritaCurveString formats correctly`() {
        val config = DynamicOptionConfig(
            optionKey = "Size",
            enabled = true,
            sensorId = "pressure",
            points = listOf(
                CurvePoint(0f, 0f),
                CurvePoint(0.5f, 0.8f),
                CurvePoint(1f, 1f)
            )
        )
        val s = config.toKritaCurveString()
        assertTrue(s.startsWith("0.000,0.000;"))
        assertTrue(s.contains("0.500,0.800;"))
        assertTrue(s.endsWith("1.000,1.000;"))
    }

    @Test
    fun `parseKritaCurve parses points properly`() {
        val raw = "0.000,0.000; 0.250,0.500; 0.750,0.900; 1.000,1.000;"
        val pts = DynamicOptionConfig.parseKritaCurve(raw)
        assertEquals(4, pts.size)
        assertEquals(0f, pts[0].x, 0.0001f)
        assertEquals(0f, pts[0].y, 0.0001f)
        assertEquals(0.25f, pts[1].x, 0.0001f)
        assertEquals(0.5f, pts[1].y, 0.0001f)
        assertEquals(0.75f, pts[2].x, 0.0001f)
        assertEquals(0.9f, pts[2].y, 0.0001f)
        assertEquals(1f, pts[3].x, 0.0001f)
        assertEquals(1f, pts[3].y, 0.0001f)
    }

    @Test
    fun `evaluate performs smooth curve interpolation`() {
        val linearConfig = DynamicOptionConfig(
            optionKey = "Opacity",
            points = listOf(CurvePoint(0f, 0f), CurvePoint(1f, 1f))
        )
        // 2点时必须严格为直线，不能是 S 形 (旧实现 smoothstep 在 0.25 处会偏离到 0.156)
        assertEquals(0f, linearConfig.evaluate(0f), 0.0001f)
        assertEquals(0.25f, linearConfig.evaluate(0.25f), 0.0001f)
        assertEquals(0.5f, linearConfig.evaluate(0.5f), 0.0001f)
        assertEquals(0.75f, linearConfig.evaluate(0.75f), 0.0001f)
        assertEquals(1f, linearConfig.evaluate(1f), 0.0001f)

        // 边界保护
        assertEquals(0f, linearConfig.evaluate(-0.5f), 0.0001f)
        assertEquals(1f, linearConfig.evaluate(1.5f), 0.0001f)

        // 3点自然三次样条测试
        val splineConfig = DynamicOptionConfig(
            optionKey = "Size",
            points = listOf(CurvePoint(0f, 0f), CurvePoint(0.5f, 1f), CurvePoint(1f, 0f))
        )
        assertEquals(0f, splineConfig.evaluate(0f), 0.001f)
        assertEquals(1f, splineConfig.evaluate(0.5f), 0.001f)
        assertEquals(0f, splineConfig.evaluate(1f), 0.001f)
        assertTrue(splineConfig.evaluate(0.25f) > 0.5f)
    }

    @Test
    fun `BrushSensor resolves by id case-insensitively`() {
        assertEquals(BrushSensor.PRESSURE, BrushSensor.fromId("pressure"))
        assertEquals(BrushSensor.PRESSURE, BrushSensor.fromId("PRESSURE"))
        assertEquals(BrushSensor.SPEED, BrushSensor.fromId("speed"))
        assertEquals(BrushSensor.TILT_ELEVATION, BrushSensor.fromId("tilt-elevation"))
        assertEquals(BrushSensor.TANGENTIAL_PRESSURE, BrushSensor.fromId("tangentialpressure"))
        // Unknown defaults to PRESSURE
        assertEquals(BrushSensor.PRESSURE, BrushSensor.fromId("unknown_sensor"))
    }
}
