/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickShapeAssistPolicyTest {

    @Test
    fun `policy allows OFF and SYMMETRY and 3D perspective and blocks 2D snap guides`() {
        assertTrue(QuickShapeAssistPolicy.isQuickShapeAllowed(DrawingGuideConfig(mode = GuideMode.OFF, assistedDrawing = true)))
        assertTrue(QuickShapeAssistPolicy.isQuickShapeAllowed(DrawingGuideConfig(mode = GuideMode.GRID_2D, assistedDrawing = false)))
        assertTrue(QuickShapeAssistPolicy.isQuickShapeAllowed(DrawingGuideConfig(mode = GuideMode.SYMMETRY, assistedDrawing = true)))
        assertTrue(
            QuickShapeAssistPolicy.isQuickShapeAllowed(
                DrawingGuideConfig(mode = GuideMode.PERSPECTIVE, assistedDrawing = true, perspective3d = true),
            ),
        )

        assertFalse(QuickShapeAssistPolicy.isQuickShapeAllowed(DrawingGuideConfig(mode = GuideMode.GRID_2D, assistedDrawing = true)))
        assertFalse(QuickShapeAssistPolicy.isQuickShapeAllowed(DrawingGuideConfig(mode = GuideMode.ISOMETRIC, assistedDrawing = true)))
        assertFalse(
            QuickShapeAssistPolicy.isQuickShapeAllowed(
                DrawingGuideConfig(mode = GuideMode.PERSPECTIVE, assistedDrawing = true, perspective3d = false),
            ),
        )
    }

    @Test
    fun `vertical symmetry mirrors quickshape stride-6 samples into stride-7 branch accurately`() {
        val guide = DrawingGuideConfig(
            mode = GuideMode.SYMMETRY,
            assistedDrawing = true,
            symmetryType = SymmetryType.VERTICAL,
            symmetryCenterX = 0.5f,
            symmetryCenterY = 0.5f,
            symmetryRotationDeg = 0f,
        )
        // 2 samples (stride 6): (200, 100, p=0.8) -> (300, 400, p=0.6) on 1000x800 canvas
        val primary = floatArrayOf(
            200f, 100f, 0.8f, 0.1f, -0.2f, 0f,
            300f, 400f, 0.6f, 0.2f, -0.1f, 0f,
        )
        val branches = QuickShapeAssistPolicy.buildSymmetricReplayBranches7(primary, guide, 1000, 800)
        assertEquals(1, branches.size)
        val b0 = branches[0]
        assertEquals(14, b0.size)
        // Mirrored across x=500 => (800, 100) and (700, 400)
        assertEquals(800f, b0[0], 0.01f)
        assertEquals(100f, b0[1], 0.01f)
        assertEquals(0.8f, b0[2], 0.001f)
        assertEquals(700f, b0[7], 0.01f)
        assertEquals(400f, b0[8], 0.01f)
        assertEquals(0.6f, b0[9], 0.001f)

        val repacked = QuickShapeAssistPolicy.repackBranch7To6(b0)
        assertEquals(12, repacked.size)
        assertEquals(800f, repacked[0], 0.01f)
        assertEquals(700f, repacked[6], 0.01f)
    }

    @Test
    fun `rotational and kaleidoscope symmetry produce expected branch counts`() {
        val primary = floatArrayOf(
            600f, 500f, 1f, 0f, 0f, 0f,
            700f, 500f, 1f, 0f, 0f, 0f,
        )
        val rotational = DrawingGuideConfig(
            mode = GuideMode.SYMMETRY,
            assistedDrawing = true,
            symmetryType = SymmetryType.ROTATIONAL,
            symmetryRayCount = 4,
        )
        assertEquals(3, QuickShapeAssistPolicy.buildSymmetricReplayBranches7(primary, rotational, 1000, 1000).size)

        val kaleidoscope = DrawingGuideConfig(
            mode = GuideMode.SYMMETRY,
            assistedDrawing = true,
            symmetryType = SymmetryType.KALEIDOSCOPE,
            symmetryRayCount = 4,
        )
        assertEquals(7, QuickShapeAssistPolicy.buildSymmetricReplayBranches7(primary, kaleidoscope, 1000, 1000).size)
    }
}
