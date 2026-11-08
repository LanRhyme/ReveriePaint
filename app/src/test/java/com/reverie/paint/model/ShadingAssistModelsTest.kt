/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

class ShadingAssistModelsTest {

    @Test
    fun `test default light vector is normalized and points upwards-z`() {
        val config = ShadingAssistConfig()
        val vec = config.computeLightVector()
        assertEquals(3, vec.size)

        val len = sqrt(vec[0] * vec[0] + vec[1] * vec[1] + vec[2] * vec[2])
        assertTrue(abs(len - 1.0f) < 1e-4f)
        assertTrue("Light Z component should point towards viewer", vec[2] > 0f)
    }

    @Test
    fun `test light azimuth directions`() {
        // Azimuth 0 -> along positive X axis (+cos(0) = 1)
        val configRight = ShadingAssistConfig(lightAzimuthDeg = 0f, lightElevationDeg = 0f)
        val vecRight = configRight.computeLightVector()
        // Note: elevation 0f is coerced to 30f in computeLightVector, so cos(30 deg) ~= 0.866
        assertTrue("X should be strongly positive", vecRight[0] > 0.85f)

        // Azimuth 180 -> along negative X axis
        val configLeft = ShadingAssistConfig(lightAzimuthDeg = 180f, lightElevationDeg = 0f)
        val vecLeft = configLeft.computeLightVector()
        assertTrue("X should be strongly negative", vecLeft[0] < -0.85f)
    }

    @Test
    fun `test enum conversions and fallbacks`() {
        assertEquals(ShadingMode.CEL, ShadingMode.fromId("cel"))
        assertEquals(ShadingMode.SMOOTH, ShadingMode.fromId("smooth"))
        assertEquals(ShadingMode.CEL, ShadingMode.fromId("unknown"))

        assertEquals(CelSteps.ONE_STEP, CelSteps.fromCount(1))
        assertEquals(CelSteps.TWO_STEP, CelSteps.fromCount(2))
        assertEquals(CelSteps.ONE_STEP, CelSteps.fromCount(99))
    }

    @Test
    fun `test cel shading step quantization logic`() {
        val config1Step = ShadingAssistConfig(mode = ShadingMode.CEL, celSteps = CelSteps.ONE_STEP, threshold1 = 0.5f)
        val config2Step = ShadingAssistConfig(mode = ShadingMode.CEL, celSteps = CelSteps.TWO_STEP, threshold1 = 0.5f, threshold2 = 0.25f)

        // For illumination = 0.1f (< threshold2), 2-step should be 1.0f (deep shadow)
        assertTrue(0.1f < config2Step.threshold2)

        // For illumination = 0.35f (between threshold2 and threshold1), 2-step is mid shadow (0.55f)
        assertTrue(0.35f in config2Step.threshold2..config2Step.threshold1)

        // For illumination = 0.7f (> threshold1), lit (0.0f shadow)
        assertTrue(0.7f > config1Step.threshold1)
    }

    @Test
    fun `test shading model presets and cel tuning parameters`() {
        assertEquals(ShadingModelPreset.DEPTH_ANYTHING, ShadingModelPreset.fromId("depth_anything"))
        assertEquals(ShadingModelPreset.DEPTH_ANYTHING, ShadingModelPreset.fromId("unknown"))

        assertTrue(!ShadingModelPreset.DEPTH_ANYTHING.isNormalMap)

        val config = ShadingAssistConfig(
            colorEdgeSnapping = true,
            colorEdgeSnapTolerance = 0.6f,
            morphologicalSmoothRadius = 2,
            ambientOcclusionIntensity = 0.45f,
            materialAdaptiveMetering = true,
            materialMeteringStrength = 0.7f,
            rollingBallContour = true,
            rollingBallRadius = 10,
            cleanSmallIslands = true,
            islandThreshold = 20,
            artistExperienceCurve = true
        )
        assertTrue(config.colorEdgeSnapping)
        assertEquals(0.6f, config.colorEdgeSnapTolerance, 1e-4f)
        assertEquals(2, config.morphologicalSmoothRadius)
        assertEquals(0.45f, config.ambientOcclusionIntensity, 1e-4f)
        assertTrue(config.materialAdaptiveMetering)
        assertEquals(0.7f, config.materialMeteringStrength, 1e-4f)
        assertTrue(config.rollingBallContour)
        assertEquals(10, config.rollingBallRadius)
        assertTrue(config.cleanSmallIslands)
        assertEquals(20, config.islandThreshold)
        assertTrue(config.artistExperienceCurve)
    }

    @Test
    fun `test default phase 1 auto shading features are active`() {
        val defaultConfig = ShadingAssistConfig()
        assertTrue(defaultConfig.materialAdaptiveMetering)
        assertTrue(defaultConfig.rollingBallContour)
        assertTrue(defaultConfig.cleanSmallIslands)
        assertTrue(defaultConfig.artistExperienceCurve)
        assertEquals(0.65f, defaultConfig.materialMeteringStrength, 1e-4f)
        assertEquals(8, defaultConfig.rollingBallRadius)
        assertEquals(18, defaultConfig.islandThreshold)
    }
}

