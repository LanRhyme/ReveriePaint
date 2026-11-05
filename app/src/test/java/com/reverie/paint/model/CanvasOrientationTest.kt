/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.reverie.paint.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CanvasOrientationTest {

    @Test
    fun cardinal_angles_map_to_their_orientation() {
        assertEquals(CanvasOrientation.LANDSCAPE, CanvasOrientation.fromAngleDegrees(0f))
        assertEquals(CanvasOrientation.PORTRAIT, CanvasOrientation.fromAngleDegrees(90f))
        assertEquals(CanvasOrientation.LANDSCAPE, CanvasOrientation.fromAngleDegrees(180f))
        assertEquals(CanvasOrientation.PORTRAIT, CanvasOrientation.fromAngleDegrees(270f))
        assertEquals(CanvasOrientation.LANDSCAPE, CanvasOrientation.fromAngleDegrees(360f))
    }

    @Test
    fun equivalent_and_negative_angles_fold_to_the_same_orientation() {
        assertEquals(CanvasOrientation.PORTRAIT, CanvasOrientation.fromAngleDegrees(-270f))
        assertEquals(CanvasOrientation.LANDSCAPE, CanvasOrientation.fromAngleDegrees(-180f))
        assertEquals(CanvasOrientation.PORTRAIT, CanvasOrientation.fromAngleDegrees(450f))
        assertEquals(CanvasOrientation.LANDSCAPE, CanvasOrientation.fromAngleDegrees(720f))
    }

    @Test
    fun intermediate_angles_normalize_to_the_nearest_orientation() {
        assertEquals(CanvasOrientation.LANDSCAPE, CanvasOrientation.fromAngleDegrees(30f))
        assertEquals(CanvasOrientation.PORTRAIT, CanvasOrientation.fromAngleDegrees(60f))
        assertEquals(CanvasOrientation.LANDSCAPE, CanvasOrientation.fromAngleDegrees(200f))
        assertEquals(CanvasOrientation.PORTRAIT, CanvasOrientation.fromAngleDegrees(300f))
    }

    @Test
    fun midpoint_ties_are_deterministic() {
        assertEquals(CanvasOrientation.PORTRAIT, CanvasOrientation.fromAngleDegrees(45f))
        assertEquals(CanvasOrientation.LANDSCAPE, CanvasOrientation.fromAngleDegrees(135f))
        assertEquals(CanvasOrientation.PORTRAIT, CanvasOrientation.fromAngleDegrees(225f))
        assertEquals(CanvasOrientation.LANDSCAPE, CanvasOrientation.fromAngleDegrees(315f))
    }

    @Test
    fun non_finite_angles_fall_back_to_landscape_without_throwing() {
        assertEquals(CanvasOrientation.LANDSCAPE, CanvasOrientation.fromAngleDegrees(Float.NaN))
        assertEquals(CanvasOrientation.LANDSCAPE, CanvasOrientation.fromAngleDegrees(Float.POSITIVE_INFINITY))
    }

    @Test
    fun restore_only_ever_writes_zero_or_ninety_degrees() {
        assertEquals(0f, CanvasOrientation.LANDSCAPE.rotationDegrees)
        assertEquals(90f, CanvasOrientation.PORTRAIT.rotationDegrees)
    }

    @Test
    fun json_roundtrip_and_unknown_values() {
        assertEquals(CanvasOrientation.LANDSCAPE, CanvasOrientation.fromJsonValue("landscape"))
        assertEquals(CanvasOrientation.PORTRAIT, CanvasOrientation.fromJsonValue("portrait"))
        assertNull(CanvasOrientation.fromJsonValue(null))
        assertNull(CanvasOrientation.fromJsonValue(""))
        assertNull(CanvasOrientation.fromJsonValue("45"))
        assertNull(CanvasOrientation.fromJsonValue("diagonal"))
    }
}
