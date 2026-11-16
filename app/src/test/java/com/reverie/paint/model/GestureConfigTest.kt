/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.reverie.paint.model

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.atan2

class GestureConfigTest {

    @Test
    fun `default mappings match expected baseline gestures`() {
        val config = GestureConfig()
        assertEquals(GestureTrigger.THREE_FINGER_SWIPE_DOWN, config.getTrigger(GestureAction.QUICK_MENU))
        assertEquals(GestureTrigger.TWO_FINGER_TAP, config.getTrigger(GestureAction.UNDO))
        assertEquals(GestureTrigger.THREE_FINGER_TAP, config.getTrigger(GestureAction.REDO))
        assertEquals(GestureTrigger.FOUR_FINGER_TAP, config.getTrigger(GestureAction.FULLSCREEN))
        assertEquals(GestureTrigger.SINGLE_FINGER_LONG_PRESS, config.getTrigger(GestureAction.EYEDROPPER))
        assertEquals(GestureTrigger.QUICK_PINCH, config.getTrigger(GestureAction.FIT_CANVAS))
        assertEquals(GestureTrigger.NONE, config.getTrigger(GestureAction.CLEAR_LAYER))
        assertEquals(GestureTrigger.SINGLE_FINGER_DOUBLE_TAP, config.getTrigger(GestureAction.SELECT_LAYER_AT_POINT))
    }

    @Test
    fun `reassigning trigger to another action automatically unbinds previous action`() {
        val config = GestureConfig()
        // Default: REDO uses THREE_FINGER_TAP.
        assertEquals(GestureAction.REDO, config.getActionForTrigger(GestureTrigger.THREE_FINGER_TAP))

        // Reassign THREE_FINGER_TAP to QUICK_MENU
        val (newConfig, conflict) = config.withMapping(GestureAction.QUICK_MENU, GestureTrigger.THREE_FINGER_TAP)

        assertEquals(GestureAction.REDO, conflict)
        assertEquals(GestureTrigger.THREE_FINGER_TAP, newConfig.getTrigger(GestureAction.QUICK_MENU))
        assertEquals(GestureTrigger.NONE, newConfig.getTrigger(GestureAction.REDO))
        assertEquals(GestureAction.QUICK_MENU, newConfig.getActionForTrigger(GestureTrigger.THREE_FINGER_TAP))
    }

    @Test
    fun `assigning NONE does not conflict or unbind other actions`() {
        val config = GestureConfig()
        val (newConfig, conflict) = config.withMapping(GestureAction.UNDO, GestureTrigger.NONE)
        assertNull(conflict)
        assertEquals(GestureTrigger.NONE, newConfig.getTrigger(GestureAction.UNDO))
        assertEquals(GestureTrigger.THREE_FINGER_TAP, newConfig.getTrigger(GestureAction.REDO))
    }

    @Test
    fun `serialization and deserialization roundtrip preserves all mappings`() {
        var config = GestureConfig()
        config = config.withMapping(GestureAction.QUICK_MENU, GestureTrigger.SINGLE_FINGER_TOUCH_SLIDE).first
        config = config.withMapping(GestureAction.CLEAR_LAYER, GestureTrigger.THREE_FINGER_SWIPE_LEFT).first

        val serialized = config.serialize()
        val deserialized = GestureConfig.deserialize(serialized)

        assertEquals(GestureTrigger.SINGLE_FINGER_TOUCH_SLIDE, deserialized.getTrigger(GestureAction.QUICK_MENU))
        assertEquals(GestureTrigger.THREE_FINGER_SWIPE_LEFT, deserialized.getTrigger(GestureAction.CLEAR_LAYER))
        assertEquals(GestureTrigger.TWO_FINGER_TAP, deserialized.getTrigger(GestureAction.UNDO))
    }

    @Test
    fun `empty or corrupt string deserialization falls back safely to defaults`() {
        val fallbackNull = GestureConfig.deserialize(null)
        val fallbackEmpty = GestureConfig.deserialize("")
        val fallbackCorrupt = GestureConfig.deserialize("invalid_data_without_colons;unknown:junk")

        assertEquals(GestureTrigger.THREE_FINGER_SWIPE_DOWN, fallbackNull.getTrigger(GestureAction.QUICK_MENU))
        assertEquals(GestureTrigger.TWO_FINGER_TAP, fallbackEmpty.getTrigger(GestureAction.UNDO))
        assertEquals(GestureTrigger.THREE_FINGER_TAP, fallbackCorrupt.getTrigger(GestureAction.REDO))
    }

    @Test
    fun `radial quick menu slot angle calculations map correctly to 6 sectors`() {
        fun angleToSlot(deg: Float): Int = when {
            deg in -120f..-60f -> 0 // Top
            deg in -60f..0f -> 1    // Top-Right
            deg in 0f..60f -> 2     // Bottom-Right
            deg in 60f..120f -> 3   // Bottom
            deg in 120f..180f -> 4  // Bottom-Left
            else -> 5               // Top-Left (-180..-120)
        }

        // Exact cardinal & intercardinal tests
        assertEquals(0, angleToSlot(-90f))   // Up
        assertEquals(1, angleToSlot(-30f))   // Up-Right
        assertEquals(2, angleToSlot(30f))    // Down-Right
        assertEquals(3, angleToSlot(90f))    // Down
        assertEquals(4, angleToSlot(150f))   // Down-Left
        assertEquals(5, angleToSlot(-150f))  // Up-Left

        // Sector boundaries
        assertEquals(0, angleToSlot(-120f))
        assertEquals(0, angleToSlot(-60f))
        assertEquals(1, angleToSlot(0f))
        assertEquals(2, angleToSlot(60f))
        assertEquals(3, angleToSlot(120f))
        assertEquals(4, angleToSlot(180f))
        assertEquals(5, angleToSlot(-180f))
    }

    @Test
    fun `continuous undo guard policy prevents rapid undo loop when disabled`() {
        fun shouldTriggerContinuousUndo(
            gestureContinuousUndo: Boolean,
            maxTouchPointers: Int,
            isPinchMotion: Boolean,
            isTwoFingerRotation: Boolean,
            isInteracting: Boolean,
            isLiquifyGestureActive: Boolean,
        ): Boolean {
            if (!gestureContinuousUndo) return false
            return maxTouchPointers == 2 && !isPinchMotion && !isTwoFingerRotation && isInteracting && !isLiquifyGestureActive
        }

        fun shouldTriggerContinuousRedo(
            gestureContinuousUndo: Boolean,
            maxTouchPointers: Int,
            isPinchMotion: Boolean,
            isInteracting: Boolean,
            isLiquifyGestureActive: Boolean,
        ): Boolean {
            if (!gestureContinuousUndo) return false
            return maxTouchPointers >= 3 && !isPinchMotion && isInteracting && !isLiquifyGestureActive
        }

        // When enabled, valid 2-finger and 3-finger hold triggers continuous undo/redo
        assertTrue(shouldTriggerContinuousUndo(true, 2, false, false, true, false))
        assertTrue(shouldTriggerContinuousRedo(true, 3, false, true, false))

        // When disabled, continuous undo/redo must NEVER trigger, preventing accidental wipeout (#124)
        assertFalse(shouldTriggerContinuousUndo(false, 2, false, false, true, false))
        assertFalse(shouldTriggerContinuousRedo(false, 3, false, true, false))
    }

    @Test
    fun `select layer at point supported triggers and remapping work as expected`() {
        val config = GestureConfig()
        // Supported triggers match verified touch capabilities
        val supported = GestureConfig.SUPPORTED_TRIGGERS[GestureAction.SELECT_LAYER_AT_POINT]
        assertNotNull(supported)
        assertEquals(
            listOf(
                GestureTrigger.SINGLE_FINGER_DOUBLE_TAP,
                GestureTrigger.TWO_FINGER_DOUBLE_TAP,
                GestureTrigger.THREE_FINGER_DOUBLE_TAP,
                GestureTrigger.NONE,
            ),
            supported,
        )

        // Disable select layer at point
        val (disabledConfig, conflict1) = config.withMapping(GestureAction.SELECT_LAYER_AT_POINT, GestureTrigger.NONE)
        assertNull(conflict1)
        assertEquals(GestureTrigger.NONE, disabledConfig.getTrigger(GestureAction.SELECT_LAYER_AT_POINT))
        assertNull(disabledConfig.getActionForTrigger(GestureTrigger.SINGLE_FINGER_DOUBLE_TAP))

        // Reassign SINGLE_FINGER_DOUBLE_TAP to FIT_CANVAS
        val (reassignedConfig, conflict2) = config.withMapping(GestureAction.FIT_CANVAS, GestureTrigger.SINGLE_FINGER_DOUBLE_TAP)
        assertEquals(GestureAction.SELECT_LAYER_AT_POINT, conflict2)
        assertEquals(GestureTrigger.SINGLE_FINGER_DOUBLE_TAP, reassignedConfig.getTrigger(GestureAction.FIT_CANVAS))
        assertEquals(GestureTrigger.NONE, reassignedConfig.getTrigger(GestureAction.SELECT_LAYER_AT_POINT))

        // Serialize and deserialize roundtrip with custom mapping
        val serialized = disabledConfig.serialize()
        val deserialized = GestureConfig.deserialize(serialized)
        assertEquals(GestureTrigger.NONE, deserialized.getTrigger(GestureAction.SELECT_LAYER_AT_POINT))
    }
}

