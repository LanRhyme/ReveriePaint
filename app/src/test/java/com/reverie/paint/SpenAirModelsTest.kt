/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint

import com.reverie.paint.model.SpenAirActions
import com.reverie.paint.model.SpenAirGesture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpenAirModelsTest {

    @Test
    fun `resolve returns corresponding gesture for the bare injected key`() {
        val expectedMappings = listOf(
            77 to SpenAirGesture.CLICK,
            18 to SpenAirGesture.DOUBLE_CLICK,
            137 to SpenAirGesture.SWIPE_LEFT,
            138 to SpenAirGesture.SWIPE_RIGHT,
            139 to SpenAirGesture.SWIPE_UP,
            140 to SpenAirGesture.SWIPE_DOWN,
            141 to SpenAirGesture.CIRCLE_CW,
            142 to SpenAirGesture.CIRCLE_CCW
        )

        for ((keyCode, expectedGesture) in expectedMappings) {
            val resolved = SpenAirGesture.resolve(keyCode, ctrl = false, alt = false, shift = false)
            assertEquals("KeyCode $keyCode should resolve to $expectedGesture", expectedGesture, resolved)
        }
    }

    @Test
    fun `resolve returns null when any modifier is held so user shortcuts win`() {
        val modifierCombinations = listOf(
            Triple(true, false, false),
            Triple(false, true, false),
            Triple(false, false, true),
            Triple(true, true, true)
        )

        for (gesture in SpenAirGesture.entries) {
            for ((ctrl, alt, shift) in modifierCombinations) {
                val resolved = SpenAirGesture.resolve(gesture.keyCode, ctrl = ctrl, alt = alt, shift = shift)
                assertNull(
                    "Modified key ${gesture.keyCode} must pass through (ctrl=$ctrl, alt=$alt, shift=$shift)",
                    resolved
                )
            }
        }
    }

    @Test
    fun `resolve returns null for keyCodes outside the air channel`() {
        val unknownCodes = listOf(0, 17, 19, 76, 78, 131, 136, 143, -1, 66, 111)
        for (code in unknownCodes) {
            val resolved = SpenAirGesture.resolve(code, ctrl = false, alt = false, shift = false)
            assertNull("Resolve should return null for unrelated keyCode $code", resolved)
        }
    }

    @Test
    fun `fromKey and fromKeyCode round trip is consistent`() {
        for (gesture in SpenAirGesture.entries) {
            val byKey = SpenAirGesture.fromKey(gesture.gestureKey)
            val byCode = SpenAirGesture.fromKeyCode(gesture.keyCode)

            assertEquals("fromKey must match gesture for ${gesture.name}", gesture, byKey)
            assertEquals("fromKeyCode must match gesture for ${gesture.name}", gesture, byCode)
        }
    }

    @Test
    fun `fromKey and fromKeyCode return null for unknown input`() {
        assertNull("fromKey with empty string should be null", SpenAirGesture.fromKey(""))
        assertNull("fromKey with invalid name should be null", SpenAirGesture.fromKey("unknown"))
        assertNull("fromKey with uppercase key should be null", SpenAirGesture.fromKey("CLICK"))

        assertNull("fromKeyCode with 0 should be null", SpenAirGesture.fromKeyCode(0))
        assertNull("fromKeyCode with 131 (old F1 mapping) should be null", SpenAirGesture.fromKeyCode(131))
        assertNull("fromKeyCode with negative number should be null", SpenAirGesture.fromKeyCode(-1))
    }

    @Test
    fun `DEFAULT_BINDINGS covers only the 6 motion gestures and all default actions are in ALLOWED`() {
        val motionGestures = SpenAirGesture.entries.toSet() -
            setOf(SpenAirGesture.CLICK, SpenAirGesture.DOUBLE_CLICK)
        assertEquals(
            "DEFAULT_BINDINGS must cover exactly the 6 motion gestures",
            motionGestures,
            SpenAirActions.DEFAULT_BINDINGS.keys
        )
        assertEquals(6, SpenAirActions.DEFAULT_BINDINGS.size)
        assertFalse(
            "DEFAULT_BINDINGS must not contain CLICK",
            SpenAirActions.DEFAULT_BINDINGS.containsKey(SpenAirGesture.CLICK)
        )
        assertFalse(
            "DEFAULT_BINDINGS must not contain DOUBLE_CLICK",
            SpenAirActions.DEFAULT_BINDINGS.containsKey(SpenAirGesture.DOUBLE_CLICK)
        )

        for (gesture in SpenAirGesture.entries) {
            val actionId = SpenAirActions.defaultBinding(gesture)
            assertTrue("Default action '$actionId' for $gesture must be in ALLOWED", SpenAirActions.isAllowed(actionId))
        }

        assertEquals("none", SpenAirActions.defaultBinding(SpenAirGesture.CLICK))
        assertEquals("none", SpenAirActions.defaultBinding(SpenAirGesture.DOUBLE_CLICK))
        assertEquals("undo", SpenAirActions.defaultBinding(SpenAirGesture.SWIPE_LEFT))
        assertEquals("redo", SpenAirActions.defaultBinding(SpenAirGesture.SWIPE_RIGHT))
        assertEquals("zoom_in", SpenAirActions.defaultBinding(SpenAirGesture.SWIPE_UP))
        assertEquals("zoom_out", SpenAirActions.defaultBinding(SpenAirGesture.SWIPE_DOWN))
        assertEquals("brush_size_inc", SpenAirActions.defaultBinding(SpenAirGesture.CIRCLE_CW))
        assertEquals("brush_size_dec", SpenAirActions.defaultBinding(SpenAirGesture.CIRCLE_CCW))
    }

    @Test
    fun `sanitize falls back to default binding on null blank or unallowed actionId`() {
        val gesture = SpenAirGesture.SWIPE_LEFT
        val defaultAction = "undo"

        assertEquals(defaultAction, SpenAirActions.sanitize(null, gesture))
        assertEquals(defaultAction, SpenAirActions.sanitize("", gesture))
        assertEquals(defaultAction, SpenAirActions.sanitize("   ", gesture))
        assertEquals(defaultAction, SpenAirActions.sanitize("invalid_action_id", gesture))
        assertEquals(defaultAction, SpenAirActions.sanitize("NONEXISTENT", gesture))
    }

    @Test
    fun `sanitize trims whitespace for allowed actions and accepts none`() {
        val gesture = SpenAirGesture.CIRCLE_CW

        assertEquals("undo", SpenAirActions.sanitize("  undo  ", gesture))
        assertEquals("redo", SpenAirActions.sanitize("redo ", gesture))
        assertEquals("none", SpenAirActions.sanitize("none", gesture))
        assertEquals("none", SpenAirActions.sanitize("  none  ", gesture))
        assertEquals("flip_canvas", SpenAirActions.sanitize(" flip_canvas\t", gesture))
    }

    @Test
    fun `ALLOWED contains exactly 18 specified items`() {
        val expectedAllowed = setOf(
            "none",
            "toggle_eraser",
            "toggle_quick_menu",
            "undo",
            "redo",
            "zoom_in",
            "zoom_out",
            "brush_size_inc",
            "brush_size_dec",
            "toggle_last_tool",
            "tool_picker",
            "tool_color",
            "brush_opacity_inc",
            "brush_opacity_dec",
            "flip_canvas",
            "layer_prev",
            "layer_next",
            "toggle_ref_window",
            "pan_canvas",
            "tool_pan"
        )

        assertEquals("ALLOWED must contain exactly 20 items", 20, SpenAirActions.ALLOWED.size)
        assertEquals("ALLOWED set contents must match expected specification", expectedAllowed, SpenAirActions.ALLOWED)
        for (action in expectedAllowed) {
            assertTrue("Action '$action' must be allowed", SpenAirActions.isAllowed(action))
        }
        assertFalse("Unlisted action must not be allowed", SpenAirActions.isAllowed("random_action"))
    }
}
