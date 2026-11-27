/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DrawingGuideHandleGeometryTest {

    @Test
    fun `3D orbit handle hits near origin and ignores normal canvas region`() {
        val guide = DrawingGuideConfig(
            mode = GuideMode.PERSPECTIVE,
            perspective3d = true,
            perspectiveYawDeg = 35f,
            perspectivePitchDeg = 22f,
        )
        val handleDoc = DrawingGuideHandleGeometry.perspective3dOrbitHandleDocPos(2000, 1200, guide)
        assertEquals(1000f, handleDoc.x, 0.01f)
        assertEquals(600f, handleDoc.y, 0.01f)

        val density = 3f
        assertTrue(
            DrawingGuideHandleGeometry.isWithinHitRadius(
                touchX = 500f + 10f * density,
                touchY = 300f,
                handleX = 500f,
                handleY = 300f,
                radiusDp = DrawingGuideHandleGeometry.PERSPECTIVE_3D_ORBIT_HIT_RADIUS_DP,
                density = density,
            ),
        )
        assertFalse(
            DrawingGuideHandleGeometry.isWithinHitRadius(
                touchX = 500f + 25f * density,
                touchY = 300f,
                handleX = 500f,
                handleY = 300f,
                radiusDp = DrawingGuideHandleGeometry.PERSPECTIVE_3D_ORBIT_HIT_RADIUS_DP,
                density = density,
            ),
        )
    }

    @Test
    fun `symmetry handle hit radius converges to 20dp and rejects old 36dp zone`() {
        val density = 2.5f
        assertTrue(
            DrawingGuideHandleGeometry.isWithinHitRadius(
                touchX = 400f + 19f * density,
                touchY = 400f,
                handleX = 400f,
                handleY = 400f,
                radiusDp = DrawingGuideHandleGeometry.SYMMETRY_HANDLE_HIT_RADIUS_DP,
                density = density,
            ),
        )
        assertFalse(
            DrawingGuideHandleGeometry.isWithinHitRadius(
                touchX = 400f + 24f * density,
                touchY = 400f,
                handleX = 400f,
                handleY = 400f,
                radiusDp = DrawingGuideHandleGeometry.SYMMETRY_HANDLE_HIT_RADIUS_DP,
                density = density,
            ),
        )
    }

    @Test
    fun `2D vanishing point hit radius converges to 22dp and rejects old 48dp zone`() {
        val density = 2f
        assertTrue(
            DrawingGuideHandleGeometry.isWithinHitRadius(
                touchX = 200f + 21f * density,
                touchY = 200f,
                handleX = 200f,
                handleY = 200f,
                radiusDp = DrawingGuideHandleGeometry.PERSPECTIVE_VP_HIT_RADIUS_DP,
                density = density,
            ),
        )
        assertFalse(
            DrawingGuideHandleGeometry.isWithinHitRadius(
                touchX = 200f + 30f * density,
                touchY = 200f,
                handleX = 200f,
                handleY = 200f,
                radiusDp = DrawingGuideHandleGeometry.PERSPECTIVE_VP_HIT_RADIUS_DP,
                density = density,
            ),
        )
    }

    @Test
    fun `symmetry rotation handle maps identically across downscaled display buffer`() {
        val docW = 4000
        val docH = 3000
        val bmpW = 2000f
        val bmpH = 1500f
        val scX = bmpW / docW
        val scY = bmpH / docH
        val guide = DrawingGuideConfig(
            mode = GuideMode.SYMMETRY,
            symmetryType = SymmetryType.VERTICAL,
            symmetryCenterX = 0.5f,
            symmetryCenterY = 0.5f,
            symmetryRotationDeg = 0f,
        )
        val rotDoc = DrawingGuideHandleGeometry.symmetryRotationHandleDocPos(docW, docH, guide)
        // doc space: cx=2000, cy=1500, dist=3000*0.35=1050 => (2000, 2550)
        assertEquals(2000f, rotDoc.x, 0.01f)
        assertEquals(2550f, rotDoc.y, 0.01f)

        // Centered display-buffer coordinates used by CanvasGuidesOverlay:
        val overlayX = rotDoc.x * scX - bmpW / 2f
        val overlayY = rotDoc.y * scY - bmpH / 2f
        // Should be (0, 525), NOT the old double-scaled (0, 262.5)
        assertEquals(0f, overlayX, 0.01f)
        assertEquals(525f, overlayY, 0.01f)
    }

    @Test
    fun `axis rotation zero check handles float drift and 180-degree wrap`() {
        assertTrue(DrawingGuideHandleGeometry.isAxisRotationZero(0f))
        assertTrue(DrawingGuideHandleGeometry.isAxisRotationZero(0.005f))
        assertTrue(DrawingGuideHandleGeometry.isAxisRotationZero(-0.005f))
        assertTrue(DrawingGuideHandleGeometry.isAxisRotationZero(180f))
        assertTrue(DrawingGuideHandleGeometry.isAxisRotationZero(360f))
        assertFalse(DrawingGuideHandleGeometry.isAxisRotationZero(1f))
        assertFalse(DrawingGuideHandleGeometry.isAxisRotationZero(45f))
        assertFalse(DrawingGuideHandleGeometry.isAxisRotationZero(90f))
    }

    @Test
    fun `grid origin and rotation handle map identically across downscaled display buffer`() {
        val docW = 2000
        val docH = 1000
        val bmpW = 1000f
        val bmpH = 500f
        val scX = bmpW / docW
        val scY = bmpH / docH
        val guide0 = DrawingGuideConfig(
            mode = GuideMode.GRID_2D,
            gridOffsetX = 0.25f,
            gridOffsetY = 0.4f,
            gridRotationDeg = 0f,
        )
        val origin = DrawingGuideHandleGeometry.gridOriginDocPos(docW, docH, guide0)
        assertEquals(500f, origin.x, 0.01f)
        assertEquals(400f, origin.y, 0.01f)

        val rot0 = DrawingGuideHandleGeometry.gridRotationHandleDocPos(docW, docH, guide0)
        // dist = 1000 * 0.28 = 280 => (780, 400)
        assertEquals(780f, rot0.x, 0.01f)
        assertEquals(400f, rot0.y, 0.01f)
        assertEquals(780f * scX - bmpW / 2f, -110f, 0.01f)
        assertEquals(400f * scY - bmpH / 2f, -50f, 0.01f)

        val guide90 = guide0.copy(gridRotationDeg = 90f)
        val rot90 = DrawingGuideHandleGeometry.gridRotationHandleDocPos(docW, docH, guide90)
        assertEquals(500f, rot90.x, 0.01f)
        assertEquals(680f, rot90.y, 0.01f)
    }
}
