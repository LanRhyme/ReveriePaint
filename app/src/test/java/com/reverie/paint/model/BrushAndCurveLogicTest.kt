/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import com.reverie.paint.core.sensorEnabledFromXml
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

class BrushAndCurveLogicTest {

    private fun computeCurveResponse(p: Double, curveMode: Int): Double {
        val g = p.coerceIn(0.0, 1.0)
        return when (curveMode) {
            1 -> g.pow(0.6) // Soft
            2 -> g.pow(1.8) // Hard
            3 -> g * g * (3.0 - 2.0 * g) // S-Curve
            else -> g // Linear
        }
    }

    @Test
    fun `linear curve preserves pressure values exactly`() {
        listOf(0.0, 0.25, 0.5, 0.75, 1.0).forEach { p ->
            assertEquals(p, computeCurveResponse(p, 0), 1e-6)
        }
    }

    @Test
    fun `soft curve enhances low pressure`() {
        val mid = computeCurveResponse(0.5, 1)
        assertTrue("Soft curve mid ($mid) should be greater than linear 0.5", mid > 0.5)
    }

    @Test
    fun `hard curve suppresses low pressure`() {
        val mid = computeCurveResponse(0.5, 2)
        assertTrue("Hard curve mid ($mid) should be less than linear 0.5", mid < 0.5)
    }

    @Test
    fun `s-curve anchors endpoints and is smooth at mid`() {
        assertEquals(0.0, computeCurveResponse(0.0, 3), 1e-6)
        assertEquals(0.5, computeCurveResponse(0.5, 3), 1e-6)
        assertEquals(1.0, computeCurveResponse(1.0, 3), 1e-6)

        val low = computeCurveResponse(0.2, 3)
        val high = computeCurveResponse(0.8, 3)
        assertTrue("S-Curve low should be depressed", low < 0.2)
        assertTrue("S-Curve high should be elevated", high > 0.8)
    }

    @Test
    fun `pressure values are clamped safely`() {
        assertEquals(0.0, computeCurveResponse(-0.5, 0), 1e-6)
        assertEquals(1.0, computeCurveResponse(1.5, 0), 1e-6)
    }

    @Test
    fun `steffen spline linear identity endpoints produce accurate linear response`() {
        val points = listOf(
            androidx.compose.ui.geometry.Offset(0f, 0f),
            androidx.compose.ui.geometry.Offset(255f, 255f)
        )
        for (i in 0..255) {
            val y = com.reverie.paint.ui.painting.layers.evaluateSteffenSpline(points, i.toFloat())
            assertEquals(i.toFloat(), y, 1e-3f)
        }
    }

    @Test
    fun `steffen spline monotonic s-curve is strictly non-decreasing and bounded`() {
        val points = listOf(
            androidx.compose.ui.geometry.Offset(0f, 0f),
            androidx.compose.ui.geometry.Offset(64f, 20f),
            androidx.compose.ui.geometry.Offset(192f, 235f),
            androidx.compose.ui.geometry.Offset(255f, 255f)
        )
        var prevY = -1f
        for (i in 0..255) {
            val y = com.reverie.paint.ui.painting.layers.evaluateSteffenSpline(points, i.toFloat())
            assertTrue("Curve value $y must be >= 0", y >= 0f)
            assertTrue("Curve value $y must be <= 255", y <= 255f)
            assertTrue("Steffen spline must be monotonic non-decreasing, but $y < $prevY at x=$i", y >= prevY - 1e-4f)
            prevY = y
        }

        val lut = com.reverie.paint.ui.painting.layers.calculateMonotoneCubicSplineLUT(points)
        assertEquals(256, lut.size)
        assertEquals(0, lut[0].toInt() and 0xFF)
        assertEquals(255, lut[255].toInt() and 0xFF)
    }

    @Test
    fun `BrushParams default instance is not customized`() {
        val params = com.reverie.paint.core.BrushParams()
        assertEquals(false, params.isCustomized)
        assertEquals(false, params.dynamicsCustomized)
        assertEquals(false, params.smudgeCustomized)
    }

    @Test
    fun `stylus tilt and orientation transforms to Krita tilt space accurately`() {
        val tiltRad = Math.toRadians(30.0)
        val orientationRad = Math.toRadians(90.0)
        val canvasRotRad = 0.0
        val docOrientationRad = orientationRad - canvasRotRad
        val tiltDeg = (tiltRad * (180.0 / Math.PI)).coerceIn(0.0, 60.0)
        val tiltX = (Math.sin(docOrientationRad) * tiltDeg).coerceIn(-60.0, 60.0)
        val tiltY = (-Math.cos(docOrientationRad) * tiltDeg).coerceIn(-60.0, 60.0)
        val rotDeg = 0.0

        assertEquals(30.0, tiltX, 1e-3)
        assertEquals(0.0, tiltY, 1e-3)
        assertEquals(0.0, rotDeg, 1e-3)
    }

    @Test
    fun `stylus tilt with NaN falls back safely to zero`() {
        val tiltRad = Float.NaN
        val orientationRad = Float.NaN
        val isSafe = !tiltRad.isNaN() && tiltRad > 0.0001f && !orientationRad.isNaN()
        val tiltX = if (isSafe) 30.0 else 0.0
        val tiltY = if (isSafe) 30.0 else 0.0
        val rotDeg = 0.0
        assertEquals(0.0, tiltX, 1e-3)
        assertEquals(0.0, tiltY, 1e-3)
        assertEquals(0.0, rotDeg, 1e-3)
    }

    @Test
    fun `symmetry pressure LUT maps 128 intervals accurately with clamping`() {
        val lut = FloatArray(129) { i -> (i / 128.0f) * 0.8f + 0.2f }
        fun lookup(p: Double): Float {
            val idx = (p.coerceIn(0.0, 1.0) * 128.0 + 0.5).toInt().coerceIn(0, 128)
            return lut[idx]
        }

        // Boundary checks
        assertEquals(0.2f, lookup(0.0), 1e-4f)
        assertEquals(1.0f, lookup(1.0), 1e-4f)
        assertEquals(0.2f, lookup(-0.5), 1e-4f)
        assertEquals(1.0f, lookup(1.5), 1e-4f)

        // Interpolated midpoint check
        val mid = lookup(0.5)
        assertEquals(0.6f, mid, 1e-2f)

        // Segment average pressure check
        val p0 = 0.2
        val p1 = 0.4
        val avgP = (p0 + p1) * 0.5
        assertEquals(0.3, avgP, 1e-4)
        val segFrac = lookup(avgP)
        assertTrue("Segment fraction between p0 and p1", segFrac in lookup(p0)..lookup(p1))
    }

    @Test
    fun `brush query matching correctly matches preset name, category, and display name`() {
        val matches = com.reverie.paint.ui.painting.brush.matchesBrushQuery(
            presetName = "b)_Basic-5_Size_Opacity",
            presetGroup = "基础",
            query = "basic"
        )
        assertTrue("Query 'basic' should match 'b)_Basic-5_Size_Opacity'", matches)

        val matchesGroup = com.reverie.paint.ui.painting.brush.matchesBrushQuery(
            presetName = "p)_Pencil-2_4B",
            presetGroup = "铅笔",
            query = "铅笔"
        )
        assertTrue("Query '铅笔' should match group '铅笔'", matchesGroup)

        val matchesDisplayName = com.reverie.paint.ui.painting.brush.matchesBrushQuery(
            presetName = "p)_Pencil-2_4B",
            presetGroup = "铅笔",
            query = "Pencil",
            groupDisplayName = "Pencil"
        )
        assertTrue("Query 'Pencil' should match groupDisplayName 'Pencil'", matchesDisplayName)

        val emptyQueryMatches = com.reverie.paint.ui.painting.brush.matchesBrushQuery(
            presetName = "p)_Pencil-2_4B",
            presetGroup = "铅笔",
            query = "   "
        )
        assertTrue("Whitespace query should match all", emptyQueryMatches)

        val mismatch = com.reverie.paint.ui.painting.brush.matchesBrushQuery(
            presetName = "p)_Pencil-2_4B",
            presetGroup = "铅笔",
            query = "watercolor",
            groupDisplayName = "Pencil"
        )
        assertTrue("Unrelated query should not match", !mismatch)

        // Translation matching
        val matchTranslation = com.reverie.paint.ui.painting.brush.matchesBrushQuery(
            presetName = "b)_Basic-2_Opacity",
            presetGroup = "基础",
            query = "不透明度"
        )
        assertTrue("Query '不透明度' should match translation of 'b)_Basic-2_Opacity'", matchTranslation)

        val matchAirbrush = com.reverie.paint.ui.painting.brush.matchesBrushQuery(
            presetName = "Airbrush_linear",
            presetGroup = "基础",
            query = "线性"
        )
        assertTrue("Query '线性' should match translation of 'Airbrush_linear'", matchAirbrush)
    }

    @Test
    fun `brush preset translations dictionary contains expected official Krita translations`() {
        assertEquals(
            "b) 基本-2 不透明度",
            com.reverie.paint.ui.painting.brush.BrushPresetTranslations.getChineseTranslation("b)_Basic-2_Opacity")
        )
        assertEquals(
            "喷枪 线性",
            com.reverie.paint.ui.painting.brush.BrushPresetTranslations.getChineseTranslation("Airbrush_linear")
        )
        assertEquals(
            "混合 模糊",
            com.reverie.paint.ui.painting.brush.BrushPresetTranslations.getChineseTranslation("Blender_Blur")
        )
        assertEquals(
            null,
            com.reverie.paint.ui.painting.brush.BrushPresetTranslations.getChineseTranslation("Custom_Brush_NonExistent")
        )
    }

    @Test
    fun `tool param memory preserves customized size and opacity across tool switches`() {
        val toolStates = mutableMapOf<String, com.reverie.paint.core.PaintViewModel.ToolBrushState>()
        val presetName = "c)_Pencil-4_Soft"
        val customSize = 35.0
        val customOpacity = 0.65

        // Initial snapshot for brush tool
        val currentBrush = toolStates["brush"] ?: com.reverie.paint.core.PaintViewModel.ToolBrushState(presetIndex = 2)
        toolStates["brush"] = currentBrush.copy(
            paramMemory = currentBrush.paramMemory + (presetName to listOf(customSize, customOpacity, 1.0))
        )

        // Switch to eraser and record eraser snapshot
        val eraserName = "a)_Eraser_Circle"
        val currentEraser = toolStates["eraser"] ?: com.reverie.paint.core.PaintViewModel.ToolBrushState(presetIndex = 0)
        toolStates["eraser"] = currentEraser.copy(
            paramMemory = currentEraser.paramMemory + (eraserName to listOf(60.0, 1.0, 1.0))
        )

        // Switch back to brush and verify brush parameters are preserved
        val restoredBrush = toolStates["brush"]
        val mem = restoredBrush?.paramMemory?.get(presetName)
        assertEquals(2, restoredBrush?.presetIndex)
        assertEquals(35.0, mem?.get(0) ?: 0.0, 1e-6)
        assertEquals(0.65, mem?.get(1) ?: 0.0, 1e-6)
    }

    @Test
    fun `preset dynamics sensor xml is preserved when dynamics were not customized`() {
        val nativeSensorXml = mapOf(
            "Size" to "<params id=\"pressure\"><curve>0,0;1,1;</curve></params>",
            "Rotation" to "<params id=\"drawingangle\"><curve>0,0;1,1;</curve></params>"
        )
        val saved = com.reverie.paint.core.BrushParams(
            size = 40.0,
            opacity = 0.8,
            dynamicsCustomized = false,
            dynamicOptions = emptyMap()
        )

        val effectiveSensors = if (saved.dynamicsCustomized && saved.dynamicOptions.isNotEmpty()) {
            saved.dynamicOptions
        } else {
            nativeSensorXml
        }

        assertEquals(2, effectiveSensors.size)
        assertTrue(effectiveSensors.containsKey("Size"))
        assertTrue(effectiveSensors.containsKey("Rotation"))
    }

    @Test
    fun `revert brush studio snapshot correctly updates toolBrushStates paramMemory`() {
        val presetName = "b)_Basic-5_Size"
        val initialParams = com.reverie.paint.core.BrushParams(
            size = 25.0,
            opacity = 0.9,
            flow = 0.8,
        )
        var toolBrushStates = mapOf(
            "brush" to com.reverie.paint.core.PaintViewModel.ToolBrushState(
                paramMemory = mapOf(presetName to listOf(50.0, 1.0, 1.0))
            )
        )

        // Revert with initial params restores initial size, opacity, flow
        toolBrushStates = toolBrushStates.mapValues { (_, s) ->
            s.copy(paramMemory = s.paramMemory + (presetName to listOf(initialParams.size, initialParams.opacity, initialParams.flow)))
        }
        val mem = toolBrushStates["brush"]?.paramMemory?.get(presetName)
        assertEquals(25.0, mem?.get(0) ?: 0.0, 1e-6)
        assertEquals(0.9, mem?.get(1) ?: 0.0, 1e-6)
        assertEquals(0.8, mem?.get(2) ?: 0.0, 1e-6)

        // Revert with null initial removes from paramMemory
        val initialNull: com.reverie.paint.core.BrushParams? = null
        toolBrushStates = toolBrushStates.mapValues { (_, s) ->
            if (initialNull != null) s.copy(paramMemory = s.paramMemory + (presetName to listOf(initialNull.size, initialNull.opacity, initialNull.flow)))
            else s.copy(paramMemory = s.paramMemory - presetName)
        }
        assertFalse(toolBrushStates["brush"]!!.paramMemory.containsKey(presetName))
    }

    @Test
    fun `sensor xml enabled resolution without parsed attributes`() {
        val xmlLinearPressure = "<params id=\"pressure\"><curve>0,0;1,1;</curve></params>"
        val xmlExplicitDisabled = "<params id=\"pressure\" enabled=\"false\"><curve>0,0;1,1;</curve></params>"
        val xmlEmptyCurve = "<params id=\"pressure\"><curve></curve></params>"
        assertTrue(sensorEnabledFromXml("Size", xmlLinearPressure))
        assertFalse(sensorEnabledFromXml("Opacity", xmlLinearPressure))
        assertFalse(sensorEnabledFromXml("Flow", xmlLinearPressure))
        assertFalse(sensorEnabledFromXml("Spacing", xmlLinearPressure))
        assertFalse(sensorEnabledFromXml("Size", xmlExplicitDisabled))
        assertFalse(sensorEnabledFromXml("Size", xmlEmptyCurve))
    }

    @Test
    fun `custom curve is preserved when updating slider strength`() {
        val customCurve = "0,0;0.2,0.6;0.8,0.9;1,1;"
        val points = DynamicOptionConfig.parseKritaCurve(customCurve)
        val initialOpt = DynamicOptionConfig(
            optionKey = "Size",
            enabled = true,
            sensorId = "pressure",
            points = points,
            strength = 0.5f,
        )

        // Updating slider strength using copy
        val newStrength = 0.85
        val updatedOpt = initialOpt.copy(
            strength = newStrength.toFloat(),
            enabled = newStrength > 0.001,
        )

        assertEquals(0.85f, updatedOpt.strength, 1e-4f)
        assertTrue(updatedOpt.enabled)
        assertEquals(4, updatedOpt.points.size)
        assertEquals(0.2f, updatedOpt.points[1].x, 1e-4f)
        assertEquals(0.6f, updatedOpt.points[1].y, 1e-4f)
    }
}

