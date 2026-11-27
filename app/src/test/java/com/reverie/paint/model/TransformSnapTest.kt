/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransformSnapTest {
    @Test
    fun `nudge acceleration scales step size by hold duration`() {
        assertEquals(1f, NudgeAcceleration.stepPxForHoldMs(0L), 1e-4f)
        assertEquals(1f, NudgeAcceleration.stepPxForHoldMs(299L), 1e-4f)
        assertEquals(2f, NudgeAcceleration.stepPxForHoldMs(300L), 1e-4f)
        assertEquals(2f, NudgeAcceleration.stepPxForHoldMs(899L), 1e-4f)
        assertEquals(5f, NudgeAcceleration.stepPxForHoldMs(900L), 1e-4f)
        assertEquals(5f, NudgeAcceleration.stepPxForHoldMs(1699L), 1e-4f)
        assertEquals(10f, NudgeAcceleration.stepPxForHoldMs(1700L), 1e-4f)
        assertEquals(10f, NudgeAcceleration.stepPxForHoldMs(5000L), 1e-4f)
    }

    @Test
    fun `snapTranslation locks horizontal vertical and diagonal moves without axis drift`() {
        val horiz = TransformSnap.snapTranslation(
            startTx = 10f,
            startTy = -5f,
            rawDx = 120f,
            rawDy = 14f,
            boundsCenterX = 200f,
            boundsCenterY = 200f,
            docWidth = 1000,
            docHeight = 1000,
            snapThresholdDoc = 8f,
        )
        assertEquals(130f, horiz.tx, 1e-3f)
        assertEquals(-5f, horiz.ty, 1e-3f)
        assertEquals(0f, horiz.snappedAxisAngleDeg!!, 1e-3f)

        val vert = TransformSnap.snapTranslation(
            startTx = 10f,
            startTy = -5f,
            rawDx = -12f,
            rawDy = 100f,
            boundsCenterX = 200f,
            boundsCenterY = 200f,
            docWidth = 1000,
            docHeight = 1000,
            snapThresholdDoc = 8f,
        )
        assertEquals(10f, vert.tx, 1e-3f)
        assertEquals(95f, vert.ty, 1e-3f)
        assertEquals(90f, vert.snappedAxisAngleDeg!!, 1e-3f)

        val diag = TransformSnap.snapTranslation(
            startTx = 0f,
            startTy = 0f,
            rawDx = 100f,
            rawDy = 104f,
            boundsCenterX = 200f,
            boundsCenterY = 200f,
            docWidth = 1000,
            docHeight = 1000,
            snapThresholdDoc = 8f,
        )
        assertEquals(diag.tx, diag.ty, 1e-3f)
        assertEquals(45f, diag.snappedAxisAngleDeg!!, 1e-3f)

        val antiDiag = TransformSnap.snapTranslation(
            startTx = 0f,
            startTy = 0f,
            rawDx = -80f,
            rawDy = 86f,
            boundsCenterX = 200f,
            boundsCenterY = 200f,
            docWidth = 1000,
            docHeight = 1000,
            snapThresholdDoc = 8f,
        )
        assertEquals(-antiDiag.tx, antiDiag.ty, 1e-3f)
        assertEquals(135f, antiDiag.snappedAxisAngleDeg!!, 1e-3f)
    }

    @Test
    fun `snapTranslation snaps to canvas center along locked axes`() {
        val snapX = TransformSnap.snapTranslation(
            startTx = 0f,
            startTy = 0f,
            rawDx = 295f,
            rawDy = 5f,
            boundsCenterX = 200f,
            boundsCenterY = 200f,
            docWidth = 1000,
            docHeight = 1000,
            snapThresholdDoc = 10f,
        )
        assertTrue(snapX.snappedCenterX)
        assertFalse(snapX.snappedCenterY)
        assertEquals(300f, snapX.tx, 1e-3f)
        assertEquals(0f, snapX.ty, 1e-3f)

        val snapY = TransformSnap.snapTranslation(
            startTx = 0f,
            startTy = 0f,
            rawDx = 4f,
            rawDy = 307f,
            boundsCenterX = 200f,
            boundsCenterY = 200f,
            docWidth = 1000,
            docHeight = 1000,
            snapThresholdDoc = 10f,
        )
        assertFalse(snapY.snappedCenterX)
        assertTrue(snapY.snappedCenterY)
        assertEquals(0f, snapY.tx, 1e-3f)
        assertEquals(300f, snapY.ty, 1e-3f)

        val snapBothDiag = TransformSnap.snapTranslation(
            startTx = 0f,
            startTy = 0f,
            rawDx = 294f,
            rawDy = 298f,
            boundsCenterX = 200f,
            boundsCenterY = 200f,
            docWidth = 1000,
            docHeight = 1000,
            snapThresholdDoc = 10f,
        )
        assertTrue(snapBothDiag.snappedCenterX)
        assertTrue(snapBothDiag.snappedCenterY)
        assertEquals(300f, snapBothDiag.tx, 1e-3f)
        assertEquals(300f, snapBothDiag.ty, 1e-3f)
    }

    @Test
    fun `snapTranslation aligns to 2D grid with custom offset and rotation`() {
        val defaultGrid = DrawingGuideConfig(
            mode = GuideMode.GRID_2D,
            gridSize = 50f,
            gridOffsetX = 0f,
            gridOffsetY = 0f,
            gridRotationDeg = 0f,
        )
        val res = TransformSnap.snapTranslation(
            startTx = 0f,
            startTy = 0f,
            rawDx = 118f,
            rawDy = 79f,
            boundsCenterX = 10f,
            boundsCenterY = 10f,
            docWidth = 1000,
            docHeight = 1000,
            snapThresholdDoc = 10f,
            gridGuide = defaultGrid,
        )
        assertTrue(res.snappedToGrid)
        // Raw center = (128, 89) -> nearest 50px lattice point = (150, 100) -> tx=140, ty=90
        assertEquals(140f, res.tx, 1e-3f)
        assertEquals(90f, res.ty, 1e-3f)

        val rotatedGrid = DrawingGuideConfig(
            mode = GuideMode.GRID_2D,
            gridSize = 100f,
            gridOffsetX = 0.5f,
            gridOffsetY = 0.5f,
            gridRotationDeg = 30f,
        )
        val cos30 = kotlin.math.cos(Math.toRadians(30.0)).toFloat()
        val sin30 = kotlin.math.sin(Math.toRadians(30.0)).toFloat()
        val rotRes = TransformSnap.snapTranslation(
            startTx = 0f,
            startTy = 0f,
            rawDx = 100f * cos30 + 6f,
            rawDy = 100f * sin30 - 4f,
            boundsCenterX = 500f,
            boundsCenterY = 500f,
            docWidth = 1000,
            docHeight = 1000,
            snapThresholdDoc = 10f,
            gridGuide = rotatedGrid,
        )
        assertTrue(rotRes.snappedToGrid)
        assertEquals(100f * cos30, rotRes.tx, 1e-2f)
        assertEquals(100f * sin30, rotRes.ty, 1e-2f)
    }

    @Test
    fun `snapTranslation handles zero movement without NaN or drift`() {
        val zero = TransformSnap.snapTranslation(
            startTx = 12f,
            startTy = -8f,
            rawDx = 0f,
            rawDy = 0f,
            boundsCenterX = 300f,
            boundsCenterY = 400f,
            docWidth = 1000,
            docHeight = 1000,
            snapThresholdDoc = 10f,
        )
        assertEquals(12f, zero.tx, 1e-4f)
        assertEquals(-8f, zero.ty, 1e-4f)
        assertNotNull(zero)
    }

    @Test
    fun `snapRotationDeg snaps to 15 degree increments and wraps to signed range`() {
        assertEquals(0f, TransformSnap.snapRotationDeg(6f), 1e-4f)
        assertEquals(15f, TransformSnap.snapRotationDeg(9f), 1e-4f)
        assertEquals(45f, TransformSnap.snapRotationDeg(42f), 1e-4f)
        assertEquals(-90f, TransformSnap.snapRotationDeg(-88f), 1e-4f)
        assertEquals(180f, TransformSnap.snapRotationDeg(176f), 1e-4f)
        assertEquals(180f, TransformSnap.snapRotationDeg(-178f), 1e-4f)
    }
}
