/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.reverie.paint.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickMenuLayoutTest {
    @Test
    fun `angles and slot offsets match item counts`() {
        for (count in 1..6) {
            val angles = QuickMenuLayout.getAngles(count)
            val offsets = QuickMenuLayout.getSlotOffsets(count)
            assertEquals("Angles size for count $count", count, angles.size)
            assertEquals("Offsets size for count $count", count, offsets.size)
        }
    }

    @Test
    fun `single item points straight up`() {
        val angles = QuickMenuLayout.getAngles(1)
        assertEquals(-90f, angles[0], 0.01f)
        assertEquals(0, QuickMenuLayout.findHoverIndex(-90f, 1))
        assertEquals(0, QuickMenuLayout.findHoverIndex(0f, 1))
    }

    @Test
    fun `two items are arranged left and right`() {
        val angles = QuickMenuLayout.getAngles(2)
        assertEquals(180f, angles[0], 0.01f)
        assertEquals(0f, angles[1], 0.01f)

        // Leftward slides pick item 0
        assertEquals(0, QuickMenuLayout.findHoverIndex(180f, 2))
        assertEquals(0, QuickMenuLayout.findHoverIndex(-170f, 2))
        assertEquals(0, QuickMenuLayout.findHoverIndex(160f, 2))

        // Rightward slides pick item 1
        assertEquals(1, QuickMenuLayout.findHoverIndex(0f, 2))
        assertEquals(1, QuickMenuLayout.findHoverIndex(15f, 2))
        assertEquals(1, QuickMenuLayout.findHoverIndex(-20f, 2))
    }

    @Test
    fun `three items are equilateral triangle with top vertex`() {
        val angles = QuickMenuLayout.getAngles(3)
        assertEquals(-90f, angles[0], 0.01f)
        assertEquals(30f, angles[1], 0.01f)
        assertEquals(150f, angles[2], 0.01f)

        assertEquals(0, QuickMenuLayout.findHoverIndex(-90f, 3))
        assertEquals(1, QuickMenuLayout.findHoverIndex(30f, 3))
        assertEquals(2, QuickMenuLayout.findHoverIndex(150f, 3))
    }

    @Test
    fun `four items form cross distribution`() {
        val angles = QuickMenuLayout.getAngles(4)
        assertEquals(-90f, angles[0], 0.01f)
        assertEquals(0f, angles[1], 0.01f)
        assertEquals(90f, angles[2], 0.01f)
        assertEquals(180f, angles[3], 0.01f)

        assertEquals(0, QuickMenuLayout.findHoverIndex(-90f, 4))
        assertEquals(1, QuickMenuLayout.findHoverIndex(0f, 4))
        assertEquals(2, QuickMenuLayout.findHoverIndex(90f, 4))
        assertEquals(3, QuickMenuLayout.findHoverIndex(180f, 4))
    }

    @Test
    fun `six items match standard radial hexagon`() {
        val angles = QuickMenuLayout.getAngles(6)
        assertEquals(-90f, angles[0], 0.01f)
        assertEquals(-30f, angles[1], 0.01f)
        assertEquals(30f, angles[2], 0.01f)
        assertEquals(90f, angles[3], 0.01f)
        assertEquals(150f, angles[4], 0.01f)
        assertEquals(-150f, angles[5], 0.01f)

        assertEquals(0, QuickMenuLayout.findHoverIndex(-90f, 6))
        assertEquals(1, QuickMenuLayout.findHoverIndex(-30f, 6))
        assertEquals(2, QuickMenuLayout.findHoverIndex(30f, 6))
        assertEquals(3, QuickMenuLayout.findHoverIndex(90f, 6))
        assertEquals(4, QuickMenuLayout.findHoverIndex(150f, 6))
        assertEquals(5, QuickMenuLayout.findHoverIndex(-150f, 6))
    }
}
