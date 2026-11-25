/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import org.junit.Assert.assertEquals
import org.junit.Test

class CursorOutlineGeometryTest {

    @Test
    fun `full diameter radius is half the scaled brush size`() {
        // 100px 笔刷、2 倍缩放、满号直径 → 屏幕上直径 200px, 半径 100px
        assertEquals(
            100f,
            CursorOutlineGeometry.outlineRadius(100f, 2f, 1f, reflectsActualSize = true),
            1e-4f,
        )
    }

    @Test
    fun `pressure fraction shrinks the ring when it reflects actual size`() {
        assertEquals(
            25f,
            CursorOutlineGeometry.outlineRadius(100f, 1f, 0.5f, reflectsActualSize = true),
            1e-4f,
        )
    }

    @Test
    fun `ring keeps full size when actual size is off`() {
        assertEquals(
            50f,
            CursorOutlineGeometry.outlineRadius(100f, 1f, 0.5f, reflectsActualSize = false),
            1e-4f,
        )
        // 压感归零也不能把环缩掉
        assertEquals(
            50f,
            CursorOutlineGeometry.outlineRadius(100f, 1f, 0f, reflectsActualSize = false),
            1e-4f,
        )
    }

    @Test
    fun `tiny brushes never collapse below the minimum radius`() {
        assertEquals(
            CursorOutlineGeometry.MIN_RADIUS_PX,
            CursorOutlineGeometry.outlineRadius(1f, 0.5f, 0.01f, reflectsActualSize = true),
            1e-4f,
        )
        assertEquals(
            CursorOutlineGeometry.MIN_RADIUS_PX,
            CursorOutlineGeometry.outlineRadius(0f, 1f, 1f, reflectsActualSize = true),
            1e-4f,
        )
    }

    @Test
    fun `degenerate scale and fraction fall back instead of producing NaN`() {
        assertEquals(
            50f,
            CursorOutlineGeometry.outlineRadius(100f, 1f, Float.NaN, reflectsActualSize = true),
            1e-4f,
        )
        // 缩放为 0 时退回 0.001, 半径落到下限
        assertEquals(
            CursorOutlineGeometry.MIN_RADIUS_PX,
            CursorOutlineGeometry.outlineRadius(100f, 0f, 1f, reflectsActualSize = true),
            1e-4f,
        )
        assertEquals(
            CursorOutlineGeometry.MIN_RADIUS_PX,
            CursorOutlineGeometry.outlineRadius(Float.NaN, 1f, 1f, reflectsActualSize = true),
            1e-4f,
        )
    }

    @Test
    fun `fraction above one is clamped to the full diameter`() {
        assertEquals(
            50f,
            CursorOutlineGeometry.outlineRadius(100f, 1f, 3f, reflectsActualSize = true),
            1e-4f,
        )
    }

    @Test
    fun `liquify never reflects actual pressure size setting`() {
        org.junit.Assert.assertFalse(CursorOutlineGeometry.shouldReflectActualSize(Tool.LIQUIFY, true))
        org.junit.Assert.assertTrue(CursorOutlineGeometry.shouldReflectActualSize(Tool.BRUSH, true))
        org.junit.Assert.assertTrue(CursorOutlineGeometry.shouldReflectActualSize(Tool.ERASER, true))
        org.junit.Assert.assertTrue(CursorOutlineGeometry.shouldReflectActualSize(Tool.SMUDGE, true))
        org.junit.Assert.assertFalse(CursorOutlineGeometry.shouldReflectActualSize(Tool.BRUSH, false))

        val reflects = CursorOutlineGeometry.shouldReflectActualSize(Tool.LIQUIFY, true)
        for (p in floatArrayOf(0f, 0.2f, 1f)) {
            assertEquals(
                30f,
                CursorOutlineGeometry.outlineRadius(60f, 1f, p, reflects),
                1e-4f,
            )
        }
    }
}
