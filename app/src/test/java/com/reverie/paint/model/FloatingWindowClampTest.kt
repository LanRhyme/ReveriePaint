/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.reverie.paint.model

import org.junit.Assert.assertEquals
import org.junit.Test

class FloatingWindowClampTest {

    @Test
    fun `landscape parent lets the window travel past the old portrait width`() {
        val (x, y) = clampPlacedWindowOrigin(
            x = 2000f, y = 100f,
            parentW = 2560f, parentH = 1600f,
            gripLeft = 0f, gripTop = 0f, gripW = 400f, gripH = 200f,
            keepInsidePx = 48f,
        )
        assertEquals(2000f, x)
        assertEquals(100f, y)
    }

    @Test
    fun `right and bottom keep forty eight pixels of the top handle inside`() {
        val (x, y) = clampPlacedWindowOrigin(
            x = 2700f, y = 1700f,
            parentW = 2560f, parentH = 1600f,
            gripLeft = 0f, gripTop = 0f, gripW = 400f, gripH = 200f,
            keepInsidePx = 48f,
        )
        assertEquals(2512f, x)
        assertEquals(1552f, y)
    }

    @Test
    fun `left overflow keeps forty eight pixels inside and top handle cannot leave`() {
        val (x, y) = clampPlacedWindowOrigin(
            x = -400f, y = -20f,
            parentW = 2560f, parentH = 1600f,
            gripLeft = 0f, gripTop = 0f, gripW = 400f, gripH = 200f,
            keepInsidePx = 48f,
        )
        assertEquals(-352f, x)
        assertEquals(0f, y)
    }

    @Test
    fun `non positive parent returns origin unchanged`() {
        val (x, y) = clampPlacedWindowOrigin(
            x = -350f, y = 9999f,
            parentW = 0f, parentH = 1600f,
            gripLeft = 0f, gripTop = 0f, gripW = 200f, gripH = 100f,
            keepInsidePx = 48f,
        )
        assertEquals(-350f, x)
        assertEquals(9999f, y)
    }

    @Test
    fun `window smaller than the grip stays fully grabbable`() {
        val (x, y) = clampPlacedWindowOrigin(
            x = -10f, y = 2000f,
            parentW = 2560f, parentH = 1600f,
            gripLeft = 0f, gripTop = 0f, gripW = 40f, gripH = 30f,
            keepInsidePx = 48f,
        )
        assertEquals(0f, x)
        assertEquals(1570f, y)
    }

    @Test
    fun `centered pill keeps forty eight pixels of the grip inside`() {
        val (rightX, rightY) = clampPlacedWindowOrigin(
            x = 3000f, y = 0f,
            parentW = 2560f, parentH = 1600f,
            gripLeft = 76f, gripTop = 0f, gripW = 60f, gripH = 16f,
            keepInsidePx = 48f,
        )
        assertEquals(2436f, rightX)
        assertEquals(0f, rightY)

        val (leftX, leftY) = clampPlacedWindowOrigin(
            x = -1000f, y = -20f,
            parentW = 2560f, parentH = 1600f,
            gripLeft = 76f, gripTop = 0f, gripW = 60f, gripH = 16f,
            keepInsidePx = 48f,
        )
        assertEquals(-88f, leftX)
        assertEquals(0f, leftY)
    }

    @Test
    fun `narrow left grip stays fully inside the parent`() {
        val (leftX, leftY) = clampPlacedWindowOrigin(
            x = -500f, y = -100f,
            parentW = 2560f, parentH = 1600f,
            gripLeft = 6f, gripTop = 13f, gripW = 14f, gripH = 28f,
            keepInsidePx = 48f,
        )
        assertEquals(-6f, leftX)
        assertEquals(-13f, leftY)

        val (rightX, rightY) = clampPlacedWindowOrigin(
            x = 4000f, y = 4000f,
            parentW = 2560f, parentH = 1600f,
            gripLeft = 6f, gripTop = 13f, gripW = 14f, gripH = 28f,
            keepInsidePx = 48f,
        )
        assertEquals(2540f, rightX)
        assertEquals(1559f, rightY)
    }
}
