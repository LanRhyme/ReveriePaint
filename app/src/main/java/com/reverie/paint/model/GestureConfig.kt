/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

/**
 * Supported configurable gesture actions.
 */
enum class GestureAction(val id: String) {
    QUICK_MENU("quick_menu"),
    UNDO("undo"),
    REDO("redo"),
    FULLSCREEN("fullscreen"),
    EYEDROPPER("eyedropper"),
    FIT_CANVAS("fit_canvas"),
    CLEAR_LAYER("clear_layer"),
    SELECT_LAYER_AT_POINT("select_layer_at_point"),
    NONE("none");

    companion object {
        fun fromId(id: String): GestureAction = entries.firstOrNull { it.id == id } ?: NONE
    }
}

/**
 * Trigger gestures supported across different actions.
 */
enum class GestureTrigger(val id: String) {
    SINGLE_FINGER_TOUCH_SLIDE("single_finger_touch_slide"),
    SINGLE_FINGER_DOUBLE_TAP("single_finger_double_tap"),
    TWO_FINGER_TAP("two_finger_tap"),
    TWO_FINGER_DOUBLE_TAP("two_finger_double_tap"),
    TWO_FINGER_LONG_PRESS("two_finger_long_press"),
    THREE_FINGER_TAP("three_finger_tap"),
    THREE_FINGER_DOUBLE_TAP("three_finger_double_tap"),
    THREE_FINGER_LONG_PRESS("three_finger_long_press"),
    THREE_FINGER_SWIPE_DOWN("three_finger_swipe_down"),
    THREE_FINGER_SWIPE_UP("three_finger_swipe_up"),
    THREE_FINGER_SWIPE_LEFT("three_finger_swipe_left"),
    THREE_FINGER_SWIPE_RIGHT("three_finger_swipe_right"),
    FOUR_FINGER_TAP("four_finger_tap"),
    SINGLE_FINGER_LONG_PRESS("single_finger_long_press"),
    QUICK_PINCH("quick_pinch"),
    NONE("none");

    companion object {
        fun fromId(id: String): GestureTrigger = entries.firstOrNull { it.id == id } ?: NONE
    }
}

/**
 * User gesture configuration mapping actions to trigger gestures.
 */
data class GestureConfig(
    val mappings: Map<GestureAction, GestureTrigger> = DEFAULT_MAPPINGS
) {
    fun getTrigger(action: GestureAction): GestureTrigger = mappings[action] ?: GestureTrigger.NONE

    fun getActionForTrigger(trigger: GestureTrigger): GestureAction? {
        if (trigger == GestureTrigger.NONE) return null
        return mappings.entries.firstOrNull { it.value == trigger }?.key
    }

    /**
     * Assigns a trigger to an action, automatically unbinding the trigger from any previous action
     * to prevent conflicts.
     * Returns a Pair of the updated GestureConfig and the conflicting action that was unbound (if any).
     */
    fun withMapping(action: GestureAction, trigger: GestureTrigger): Pair<GestureConfig, GestureAction?> {
        val newMap = mappings.toMutableMap()
        var conflictingAction: GestureAction? = null

        if (trigger != GestureTrigger.NONE) {
            val existing = mappings.entries.firstOrNull { it.key != action && it.value == trigger }?.key
            if (existing != null) {
                conflictingAction = existing
                newMap[existing] = GestureTrigger.NONE
            }
        }
        newMap[action] = trigger
        return Pair(GestureConfig(newMap), conflictingAction)
    }

    fun serialize(): String {
        return mappings.entries.joinToString(";") { "${it.key.id}:${it.value.id}" }
    }

    companion object {
        val DEFAULT_MAPPINGS = mapOf(
            GestureAction.QUICK_MENU to GestureTrigger.THREE_FINGER_SWIPE_DOWN,
            GestureAction.UNDO to GestureTrigger.TWO_FINGER_TAP,
            GestureAction.REDO to GestureTrigger.THREE_FINGER_TAP,
            GestureAction.FULLSCREEN to GestureTrigger.FOUR_FINGER_TAP,
            GestureAction.EYEDROPPER to GestureTrigger.SINGLE_FINGER_LONG_PRESS,
            GestureAction.FIT_CANVAS to GestureTrigger.QUICK_PINCH,
            GestureAction.CLEAR_LAYER to GestureTrigger.NONE,
            GestureAction.SELECT_LAYER_AT_POINT to GestureTrigger.SINGLE_FINGER_DOUBLE_TAP,
        )

        /**
         * Supported trigger options for each action.
         */
        val SUPPORTED_TRIGGERS: Map<GestureAction, List<GestureTrigger>> = mapOf(
            GestureAction.QUICK_MENU to listOf(
                GestureTrigger.SINGLE_FINGER_TOUCH_SLIDE,
                GestureTrigger.THREE_FINGER_SWIPE_DOWN,
                GestureTrigger.THREE_FINGER_SWIPE_UP,
                GestureTrigger.FOUR_FINGER_TAP,
                GestureTrigger.THREE_FINGER_TAP,
                GestureTrigger.THREE_FINGER_DOUBLE_TAP,
                GestureTrigger.THREE_FINGER_LONG_PRESS,
                GestureTrigger.NONE,
            ),
            GestureAction.UNDO to listOf(
                GestureTrigger.TWO_FINGER_TAP,
                GestureTrigger.TWO_FINGER_DOUBLE_TAP,
                GestureTrigger.THREE_FINGER_SWIPE_LEFT,
                GestureTrigger.NONE,
            ),
            GestureAction.REDO to listOf(
                GestureTrigger.THREE_FINGER_TAP,
                GestureTrigger.FOUR_FINGER_TAP,
                GestureTrigger.THREE_FINGER_DOUBLE_TAP,
                GestureTrigger.THREE_FINGER_SWIPE_RIGHT,
                GestureTrigger.NONE,
            ),
            GestureAction.FULLSCREEN to listOf(
                GestureTrigger.FOUR_FINGER_TAP,
                GestureTrigger.THREE_FINGER_SWIPE_UP,
                GestureTrigger.TWO_FINGER_DOUBLE_TAP,
                GestureTrigger.NONE,
            ),
            GestureAction.EYEDROPPER to listOf(
                GestureTrigger.SINGLE_FINGER_LONG_PRESS,
                GestureTrigger.TWO_FINGER_LONG_PRESS,
                GestureTrigger.NONE,
            ),
            GestureAction.FIT_CANVAS to listOf(
                GestureTrigger.QUICK_PINCH,
                GestureTrigger.TWO_FINGER_DOUBLE_TAP,
                GestureTrigger.FOUR_FINGER_TAP,
                GestureTrigger.SINGLE_FINGER_DOUBLE_TAP,
                GestureTrigger.NONE,
            ),
            GestureAction.CLEAR_LAYER to listOf(
                GestureTrigger.THREE_FINGER_SWIPE_LEFT,
                GestureTrigger.THREE_FINGER_SWIPE_RIGHT,
                GestureTrigger.THREE_FINGER_LONG_PRESS,
                GestureTrigger.NONE,
            ),
            GestureAction.SELECT_LAYER_AT_POINT to listOf(
                GestureTrigger.SINGLE_FINGER_DOUBLE_TAP,
                GestureTrigger.TWO_FINGER_DOUBLE_TAP,
                GestureTrigger.THREE_FINGER_DOUBLE_TAP,
                GestureTrigger.NONE,
            ),
        )

        fun deserialize(serialized: String?): GestureConfig {
            if (serialized.isNullOrBlank()) return GestureConfig(DEFAULT_MAPPINGS)
            val map = DEFAULT_MAPPINGS.toMutableMap()
            serialized.split(";").forEach { entry ->
                val parts = entry.split(":")
                if (parts.size == 2) {
                    val action = GestureAction.fromId(parts[0])
                    val trigger = GestureTrigger.fromId(parts[1])
                    if (action != GestureAction.NONE) {
                        map[action] = trigger
                    }
                }
            }
            return GestureConfig(map)
        }
    }
}

fun GestureAction.labelRes(): Int = when (this) {
    GestureAction.QUICK_MENU -> com.reverie.paint.R.string.gesture_action_quick_menu
    GestureAction.UNDO -> com.reverie.paint.R.string.gesture_action_undo
    GestureAction.REDO -> com.reverie.paint.R.string.gesture_action_redo
    GestureAction.FULLSCREEN -> com.reverie.paint.R.string.gesture_action_fullscreen
    GestureAction.EYEDROPPER -> com.reverie.paint.R.string.gesture_action_eyedropper
    GestureAction.FIT_CANVAS -> com.reverie.paint.R.string.gesture_action_fit_canvas
    GestureAction.CLEAR_LAYER -> com.reverie.paint.R.string.gesture_action_clear_layer
    GestureAction.SELECT_LAYER_AT_POINT -> com.reverie.paint.R.string.gesture_action_select_layer_at_point
    GestureAction.NONE -> com.reverie.paint.R.string.gesture_action_none
}

fun GestureAction.descRes(): Int = when (this) {
    GestureAction.QUICK_MENU -> com.reverie.paint.R.string.gesture_action_quick_menu_desc
    GestureAction.UNDO -> com.reverie.paint.R.string.gesture_action_undo_desc
    GestureAction.REDO -> com.reverie.paint.R.string.gesture_action_redo_desc
    GestureAction.FULLSCREEN -> com.reverie.paint.R.string.gesture_action_fullscreen_desc
    GestureAction.EYEDROPPER -> com.reverie.paint.R.string.gesture_action_eyedropper_desc
    GestureAction.FIT_CANVAS -> com.reverie.paint.R.string.gesture_action_fit_canvas_desc
    GestureAction.CLEAR_LAYER -> com.reverie.paint.R.string.gesture_action_clear_layer_desc
    GestureAction.SELECT_LAYER_AT_POINT -> com.reverie.paint.R.string.gesture_action_select_layer_at_point_desc
    GestureAction.NONE -> com.reverie.paint.R.string.gesture_action_none
}

fun GestureTrigger.labelRes(): Int = when (this) {
    GestureTrigger.SINGLE_FINGER_TOUCH_SLIDE -> com.reverie.paint.R.string.gesture_trigger_single_finger_touch_slide
    GestureTrigger.SINGLE_FINGER_DOUBLE_TAP -> com.reverie.paint.R.string.gesture_trigger_single_finger_double_tap
    GestureTrigger.TWO_FINGER_TAP -> com.reverie.paint.R.string.gesture_trigger_two_finger_tap
    GestureTrigger.TWO_FINGER_DOUBLE_TAP -> com.reverie.paint.R.string.gesture_trigger_two_finger_double_tap
    GestureTrigger.TWO_FINGER_LONG_PRESS -> com.reverie.paint.R.string.gesture_trigger_two_finger_long_press
    GestureTrigger.THREE_FINGER_TAP -> com.reverie.paint.R.string.gesture_trigger_three_finger_tap
    GestureTrigger.THREE_FINGER_DOUBLE_TAP -> com.reverie.paint.R.string.gesture_trigger_three_finger_double_tap
    GestureTrigger.THREE_FINGER_LONG_PRESS -> com.reverie.paint.R.string.gesture_trigger_three_finger_long_press
    GestureTrigger.THREE_FINGER_SWIPE_DOWN -> com.reverie.paint.R.string.gesture_trigger_three_finger_swipe_down
    GestureTrigger.THREE_FINGER_SWIPE_UP -> com.reverie.paint.R.string.gesture_trigger_three_finger_swipe_up
    GestureTrigger.THREE_FINGER_SWIPE_LEFT -> com.reverie.paint.R.string.gesture_trigger_three_finger_swipe_left
    GestureTrigger.THREE_FINGER_SWIPE_RIGHT -> com.reverie.paint.R.string.gesture_trigger_three_finger_swipe_right
    GestureTrigger.FOUR_FINGER_TAP -> com.reverie.paint.R.string.gesture_trigger_four_finger_tap
    GestureTrigger.SINGLE_FINGER_LONG_PRESS -> com.reverie.paint.R.string.gesture_trigger_single_finger_long_press
    GestureTrigger.QUICK_PINCH -> com.reverie.paint.R.string.gesture_trigger_quick_pinch
    GestureTrigger.NONE -> com.reverie.paint.R.string.gesture_trigger_none
}
