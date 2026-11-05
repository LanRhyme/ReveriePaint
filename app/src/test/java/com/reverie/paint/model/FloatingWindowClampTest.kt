/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.reverie.paint.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class FloatingWindowClampTest {

    /** 默认父级 2560x1600、把手 400x200、留 48px, 各用例只覆盖自己关心的入参 */
    private fun clamp(
        x: Float,
        y: Float,
        parentW: Float = 2560f,
        parentH: Float = 1600f,
        gripLeft: Float = 0f,
        gripTop: Float = 0f,
        gripW: Float = 400f,
        gripH: Float = 200f,
        keepInsidePx: Float = 48f,
        out: WindowOrigin = WindowOrigin(),
    ): WindowOrigin = clampPlacedWindowOrigin(
        x, y, parentW, parentH, gripLeft, gripTop, gripW, gripH, keepInsidePx, out,
    )

    @Test
    fun `landscape parent lets the window travel past the old portrait width`() {
        val origin = clamp(x = 2000f, y = 100f)
        assertEquals(2000f, origin.x)
        assertEquals(100f, origin.y)
    }

    @Test
    fun `right and bottom keep forty eight pixels of the top handle inside`() {
        val origin = clamp(x = 2700f, y = 1700f)
        assertEquals(2512f, origin.x)
        assertEquals(1552f, origin.y)
    }

    @Test
    fun `left overflow keeps forty eight pixels inside and top handle cannot leave`() {
        val origin = clamp(x = -400f, y = -20f)
        assertEquals(-352f, origin.x)
        assertEquals(0f, origin.y)
    }

    @Test
    fun `non positive parent returns origin unchanged`() {
        val origin = clamp(x = -350f, y = 9999f, parentW = 0f, gripW = 200f, gripH = 100f)
        assertEquals(-350f, origin.x)
        assertEquals(9999f, origin.y)
    }

    @Test
    fun `window smaller than the grip stays fully grabbable`() {
        val origin = clamp(x = -10f, y = 2000f, gripW = 40f, gripH = 30f)
        assertEquals(0f, origin.x)
        assertEquals(1570f, origin.y)
    }

    @Test
    fun `centered pill keeps forty eight pixels of the grip inside`() {
        val right = clamp(x = 3000f, y = 0f, gripLeft = 76f, gripW = 60f, gripH = 16f)
        assertEquals(2436f, right.x)
        assertEquals(0f, right.y)

        val left = clamp(x = -1000f, y = -20f, gripLeft = 76f, gripW = 60f, gripH = 16f)
        assertEquals(-88f, left.x)
        assertEquals(0f, left.y)
    }

    @Test
    fun `narrow left grip stays fully inside the parent`() {
        val left = clamp(x = -500f, y = -100f, gripLeft = 6f, gripTop = 13f, gripW = 14f, gripH = 28f)
        assertEquals(-6f, left.x)
        assertEquals(-13f, left.y)

        val right = clamp(x = 4000f, y = 4000f, gripLeft = 6f, gripTop = 13f, gripW = 14f, gripH = 28f)
        assertEquals(2540f, right.x)
        assertEquals(1559f, right.y)
    }

    @Test
    fun `reused result holder is filled in place without allocating`() {
        val reuse = WindowOrigin()
        val first = clamp(x = 2700f, y = 1700f, out = reuse)
        assertSame(reuse, first)
        assertEquals(2512f, reuse.x)
        assertEquals(1552f, reuse.y)

        // 同一个容器再夹一次: 上次的结果不能残留
        clamp(x = 100f, y = 200f, out = reuse)
        assertEquals(100f, reuse.x)
        assertEquals(200f, reuse.y)
    }
}
