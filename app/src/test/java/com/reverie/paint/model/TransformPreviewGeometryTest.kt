/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.reverie.paint.ui.painting.canvas.TransformMode
import com.reverie.paint.ui.painting.canvas.TransformState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransformPreviewGeometryTest {

    @Test
    fun `affine destination size equals display buffer size without double scX scaling`() {
        val (w, h) = TransformPreviewGeometry.affineDestinationSize(bmpWidth = 2000, bmpHeight = 1500)
        assertEquals(2000, w)
        assertEquals(1500, h)

        val (minW, minH) = TransformPreviewGeometry.affineDestinationSize(bmpWidth = 0, bmpHeight = -10)
        assertEquals(1, minW)
        assertEquals(1, minH)
    }

    @Test
    fun `doc bounds map directly to preview bitmap space regardless of display buffer downsampling`() {
        val crop = TransformPreviewGeometry.docBoundsToPreviewCrop(
            left = 1000f,
            top = 600f,
            right = 3000f,
            bottom = 2400f,
            docWidth = 4000,
            docHeight = 3000,
            previewWidth = 2000,
            previewHeight = 1500,
        )
        assertEquals(500, crop.left)
        assertEquals(300, crop.top)
        assertEquals(1500, crop.right)
        assertEquals(1200, crop.bottom)
        assertEquals(1000, crop.width)
        assertEquals(900, crop.height)
    }

    @Test
    fun `doc bounds to preview crop clamps out-of-range coordinates safely`() {
        val crop = TransformPreviewGeometry.docBoundsToPreviewCrop(
            left = -200f,
            top = -100f,
            right = 9000f,
            bottom = 8000f,
            docWidth = 4000,
            docHeight = 3000,
            previewWidth = 2048,
            previewHeight = 1536,
        )
        assertEquals(0, crop.left)
        assertEquals(0, crop.top)
        assertEquals(2048, crop.right)
        assertEquals(1536, crop.bottom)
    }

    @Test
    fun `cancelDrag restores all transform fields and clears active handle`() {
        val state = TransformState()
        state.reset(Rect(100f, 100f, 500f, 400f))
        state.mode = TransformMode.STANDARD
        state.tx = 12f
        state.ty = -8f
        state.rotation = 15f
        state.scaleX = 1.2f
        state.scaleY = 0.9f

        // Simulate single-finger down capturing handle=8 then drifting before second finger lands
        state.handle = 8
        state.startTx = state.tx
        state.startTy = state.ty
        state.startRotation = state.rotation
        state.startScaleX = state.scaleX
        state.startScaleY = state.scaleY
        state.startQuadCorners = state.quadCorners.toList()
        state.startMeshPoints = state.meshPoints.toList()

        // Drift mutation
        state.tx = 45f
        state.ty = 33f
        state.rotation = 27f
        state.scaleX = 1.5f
        state.scaleY = 1.4f
        state.quadCorners = listOf(Offset(1f, 2f), Offset(3f, 4f), Offset(5f, 6f), Offset(7f, 8f))
        state.meshPoints = List(16) { Offset(it.toFloat(), it.toFloat()) }

        assertTrue(state.cancelDrag())
        assertEquals(-1, state.handle)
        assertEquals(12f, state.tx, 0.001f)
        assertEquals(-8f, state.ty, 0.001f)
        assertEquals(15f, state.rotation, 0.001f)
        assertEquals(1.2f, state.scaleX, 0.001f)
        assertEquals(0.9f, state.scaleY, 0.001f)
        assertEquals(Offset(100f, 100f), state.quadCorners[0])
        assertEquals(Offset(100f, 100f), state.meshPoints[0])

        // Calling again when no handle is active is a no-op
        assertFalse(state.cancelDrag())
    }
}
