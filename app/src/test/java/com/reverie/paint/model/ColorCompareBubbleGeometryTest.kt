/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ColorCompareBubbleGeometryTest {

    @Test
    fun `progress zero collapses onto reticle with zero alpha`() {
        val pose = ColorCompareBubbleGeometry.compute(
            reticleX = 120f,
            reticleY = 95f,
            areaWidth = 300f,
            areaHeight = 250f,
            bubbleRadiusPx = 40f,
            bubbleOuterRadiusPx = 46f,
            liftOffsetPx = 70f,
            topOverflowAllowancePx = 80f,
            progress = 0f,
        )
        assertEquals(120f, pose.cx, 1e-4f)
        assertEquals(95f, pose.cy, 1e-4f)
        assertEquals(0f, pose.alpha, 1e-4f)
        assertEquals(14f, pose.radius, 1e-4f)
    }

    @Test
    fun `progress one lifts above center reticle with full radius and alpha`() {
        val pose = ColorCompareBubbleGeometry.compute(
            reticleX = 150f,
            reticleY = 140f,
            areaWidth = 300f,
            areaHeight = 250f,
            bubbleRadiusPx = 40f,
            bubbleOuterRadiusPx = 46f,
            liftOffsetPx = 70f,
            topOverflowAllowancePx = 80f,
            progress = 1f,
        )
        assertEquals(150f, pose.cx, 1e-4f)
        assertEquals(70f, pose.cy, 1e-4f)
        assertEquals(40f, pose.radius, 1e-4f)
        assertEquals(1f, pose.alpha, 1e-4f)
    }

    @Test
    fun `horizontal clamping keeps bubble outer rim inside area edges`() {
        val leftPose = ColorCompareBubbleGeometry.compute(
            reticleX = 2f,
            reticleY = 150f,
            areaWidth = 300f,
            areaHeight = 250f,
            bubbleRadiusPx = 40f,
            bubbleOuterRadiusPx = 46f,
            liftOffsetPx = 70f,
            topOverflowAllowancePx = 80f,
            progress = 1f,
        )
        assertEquals(46f, leftPose.cx, 1e-4f)

        val rightPose = ColorCompareBubbleGeometry.compute(
            reticleX = 298f,
            reticleY = 150f,
            areaWidth = 300f,
            areaHeight = 250f,
            bubbleRadiusPx = 40f,
            bubbleOuterRadiusPx = 46f,
            liftOffsetPx = 70f,
            topOverflowAllowancePx = 80f,
            progress = 1f,
        )
        assertEquals(254f, rightPose.cx, 1e-4f)
    }

    @Test
    fun `top clamping honors controlled overflow allowance into header`() {
        val pose = ColorCompareBubbleGeometry.compute(
            reticleX = 150f,
            reticleY = 0f,
            areaWidth = 300f,
            areaHeight = 250f,
            bubbleRadiusPx = 40f,
            bubbleOuterRadiusPx = 46f,
            liftOffsetPx = 70f,
            topOverflowAllowancePx = 80f,
            progress = 1f,
        )
        // minY = -topOverflowAllowancePx + outerR = -80 + 46 = -34
        assertEquals(-34f, pose.cy, 1e-4f)
    }

    @Test
    fun `degenerate or non-finite inputs never produce NaN`() {
        val zeroArea = ColorCompareBubbleGeometry.compute(
            reticleX = 10f,
            reticleY = 20f,
            areaWidth = 0f,
            areaHeight = 200f,
            bubbleRadiusPx = 40f,
            bubbleOuterRadiusPx = 46f,
            liftOffsetPx = 70f,
            topOverflowAllowancePx = 80f,
            progress = 1f,
        )
        assertEquals(0f, zeroArea.alpha, 1e-4f)
        assertEquals(0f, zeroArea.radius, 1e-4f)

        val nanInput = ColorCompareBubbleGeometry.compute(
            reticleX = Float.NaN,
            reticleY = Float.POSITIVE_INFINITY,
            areaWidth = 300f,
            areaHeight = 250f,
            bubbleRadiusPx = 40f,
            bubbleOuterRadiusPx = 46f,
            liftOffsetPx = Float.NaN,
            topOverflowAllowancePx = 80f,
            progress = Float.NaN,
        )
        assertFalse(nanInput.cx.isNaN())
        assertFalse(nanInput.cy.isNaN())
        assertFalse(nanInput.radius.isNaN())
        assertTrue(nanInput.alpha in 0f..1f)
    }
}
