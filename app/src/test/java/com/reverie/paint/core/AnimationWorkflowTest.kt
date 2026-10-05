/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnimationWorkflowTest {
    @Test
    fun `new sessions keep automatic keyframes and reference disabled`() {
        val anim = AnimationState()
        assertFalse(anim.manualKeyframes)
        assertFalse(anim.previousFrameReference)
    }

    @Test
    fun `switching documents preserves drawing preference but clears reference visibility`() {
        val anim = AnimationState()
        anim.manualKeyframes = true
        anim.setPreviousFrameReference(true)
        anim.reset()
        assertTrue(anim.manualKeyframes)
        assertFalse(anim.previousFrameReference)
        assertFalse(anim.onionSkin)
    }

    @Test
    fun `reference preset replaces future and distant references with one faint previous drawing`() {
        val anim = AnimationState()
        anim.onionPrev = 4
        anim.onionNext = 3
        anim.onionKeyframesOnly = true
        anim.onionOpacity = 255
        anim.setPreviousFrameReference(true)
        assertTrue(anim.previousFrameReference)
        assertFalse(anim.manualKeyframes)
        assertEquals(1, anim.onionPrev)
        assertEquals(0, anim.onionNext)
        assertEquals(96, anim.onionOpacity)
        assertFalse(anim.onionKeyframesOnly)
    }

    @Test
    fun `toggling previous reference preserves adjusted opacity and tint`() {
        val anim = AnimationState()
        anim.setPreviousFrameReference(true)
        anim.onionOpacity = 72
        anim.onionTint = 17
        anim.setPreviousFrameReference(false)
        assertFalse(anim.previousFrameReference)
        anim.setPreviousFrameReference(true)
        assertTrue(anim.previousFrameReference)
        assertEquals(72, anim.onionOpacity)
        assertEquals(17, anim.onionTint)
    }

    @Test
    fun `custom onion settings are not shown as the previous drawing preset`() {
        val anim = AnimationState()
        anim.setPreviousFrameReference(true)
        anim.onionNext = 1
        assertFalse(anim.previousFrameReference)
        assertTrue(anim.onionSkin)
    }
}
