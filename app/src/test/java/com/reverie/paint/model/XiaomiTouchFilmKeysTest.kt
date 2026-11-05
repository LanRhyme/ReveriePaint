/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.reverie.paint.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class XiaomiTouchFilmKeysTest {
    @Test
    fun `pinch and double tap have separate key codes regardless of system action`() {
        val keys = XiaomiTouchFilmKeys()
        assertEquals(XiaomiTouchFilmGesture.SQUEEZE, keys.onDown(194, 0))
        assertEquals(XiaomiTouchFilmGesture.DOUBLE_TAP, keys.onDown(195, 0))
        assertEquals(XiaomiTouchFilmGesture.SLIDE_UP, keys.onDown(196, 0))
        assertEquals(XiaomiTouchFilmGesture.SLIDE_DOWN, keys.onDown(197, 0))
    }

    @Test
    fun `duplicate dispatch and long press repeat cannot execute a gesture twice`() {
        val keys = XiaomiTouchFilmKeys()
        assertEquals(XiaomiTouchFilmGesture.DOUBLE_TAP, keys.onDown(195, 0))
        assertNull(keys.onDown(195, 0))
        assertNull(keys.onDown(195, 1))
        keys.onUp(195)
        assertEquals(XiaomiTouchFilmGesture.DOUBLE_TAP, keys.onDown(195, 0))
    }

    @Test
    fun `release and pause reset pending pinch state`() {
        val keys = XiaomiTouchFilmKeys()
        keys.onDown(194, 0)
        keys.onUp(194)
        assertEquals(XiaomiTouchFilmGesture.SQUEEZE, keys.onDown(194, 0))
        keys.reset()
        assertEquals(XiaomiTouchFilmGesture.SQUEEZE, keys.onDown(194, 0))
    }

    @Test
    fun `legacy pen buttons and normal keyboard keys do not become Pro gestures`() {
        val keys = XiaomiTouchFilmKeys()
        listOf(0, 29, 66, 92, 93, 131, 142, 193, 198, 308, 309, 310, 328, 329, 330, 331).forEach { keyCode ->
            assertNull(XiaomiTouchFilmGesture.fromKeyCode(keyCode))
            assertNull(keys.onDown(keyCode, 0))
            keys.onUp(keyCode)
        }
    }
}
