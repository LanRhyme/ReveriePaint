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
    FLIP_CANVAS_H("flip_canvas_h"),
    FLIP_CANVAS_V("flip_canvas_v"),
    RESET_ROTATION("reset_rotation"),
    TOGGLE_ERASER("toggle_eraser"),
    NEW_LAYER("new_layer"),
    LOCK_VIEW("lock_view"),
    BRUSH_SLIDE_ADJUST("brush_slide_adjust"),
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
    SINGLE_FINGER_LONG_PRESS("single_finger_long_press"),
    TWO_FINGER_TAP("two_finger_tap"),
    TWO_FINGER_DOUBLE_TAP("two_finger_double_tap"),
    TWO_FINGER_LONG_PRESS("two_finger_long_press"),
    QUICK_PINCH("quick_pinch"),
    THREE_FINGER_TAP("three_finger_tap"),
    THREE_FINGER_DOUBLE_TAP("three_finger_double_tap"),
    THREE_FINGER_LONG_PRESS("three_finger_long_press"),
    THREE_FINGER_SWIPE_DOWN("three_finger_swipe_down"),
    THREE_FINGER_SWIPE_UP("three_finger_swipe_up"),
    THREE_FINGER_SWIPE_LEFT("three_finger_swipe_left"),
    THREE_FINGER_SWIPE_RIGHT("three_finger_swipe_right"),
    THREE_FINGER_SLIDE("three_finger_slide"),
    FOUR_FINGER_TAP("four_finger_tap"),
    NONE("none");

    companion object {
        fun fromId(id: String): GestureTrigger = entries.firstOrNull { it.id == id } ?: NONE
    }
}

/**
 * User gesture configuration mapping triggers to gesture actions.
 */
data class GestureConfig(
    val triggerMappings: Map<GestureTrigger, GestureAction> = DEFAULT_TRIGGER_MAPPINGS
) {

    /**
     * Gets the trigger assigned to an action (for backward compatibility).
     */
    fun getTrigger(action: GestureAction): GestureTrigger {
        if (action == GestureAction.NONE) return GestureTrigger.NONE
        return triggerMappings.entries.firstOrNull { it.value == action }?.key ?: GestureTrigger.NONE
    }

    /**
     * Gets the configured action for a trigger.
     */
    fun getActionForTrigger(trigger: GestureTrigger): GestureAction? {
        if (trigger == GestureTrigger.NONE) return null
        val action = triggerMappings[trigger] ?: GestureAction.NONE
        return if (action == GestureAction.NONE) null else action
    }

    /**
     * Directly maps a trigger to an action.
     */
    fun withTriggerMapping(trigger: GestureTrigger, action: GestureAction): GestureConfig {
        if (trigger == GestureTrigger.NONE) return this
        val newMap = triggerMappings.toMutableMap()
        newMap[trigger] = action
        return GestureConfig(newMap)
    }

    /**
     * Assigns a trigger to an action, automatically unbinding the trigger from any previous action
     * to prevent conflicts (backward compatibility API).
     */
    fun withMapping(action: GestureAction, trigger: GestureTrigger): Pair<GestureConfig, GestureAction?> {
        val newMap = triggerMappings.toMutableMap()
        var conflictingAction: GestureAction? = null

        // Unbind any previous trigger for this action
        if (action != GestureAction.NONE) {
            val oldTrig = triggerMappings.entries.firstOrNull { it.key != trigger && it.value == action }?.key
            if (oldTrig != null) {
                newMap[oldTrig] = GestureAction.NONE
            }
        }

        if (trigger != GestureTrigger.NONE) {
            val existing = newMap[trigger]
            if (existing != null && existing != GestureAction.NONE && existing != action) {
                conflictingAction = existing
            }
            newMap[trigger] = action
        }
        return Pair(GestureConfig(newMap), conflictingAction)
    }

    val mappings: Map<GestureAction, GestureTrigger>
        get() = GestureAction.entries.filter { it != GestureAction.NONE }.associateWith { getTrigger(it) }

    fun serialize(): String {
        return triggerMappings.entries.joinToString(";") { "${it.key.id}:${it.value.id}" }
    }

    companion object {
        val PRIMARY_TRIGGERS: List<GestureTrigger> = listOf(
            GestureTrigger.TWO_FINGER_TAP,
            GestureTrigger.TWO_FINGER_DOUBLE_TAP,
            GestureTrigger.TWO_FINGER_LONG_PRESS,
            GestureTrigger.THREE_FINGER_TAP,
            GestureTrigger.THREE_FINGER_DOUBLE_TAP,
            GestureTrigger.THREE_FINGER_LONG_PRESS,
            GestureTrigger.QUICK_PINCH,
            GestureTrigger.THREE_FINGER_SWIPE_DOWN,
            GestureTrigger.FOUR_FINGER_TAP,
            GestureTrigger.SINGLE_FINGER_LONG_PRESS,
            GestureTrigger.SINGLE_FINGER_DOUBLE_TAP,
        )

        val TRIGGER_SUPPORTED_ACTIONS: Map<GestureTrigger, List<GestureAction>> = mapOf(
            GestureTrigger.TWO_FINGER_TAP to listOf(
                GestureAction.UNDO,
                GestureAction.TOGGLE_ERASER,
                GestureAction.NONE,
            ),
            GestureTrigger.TWO_FINGER_DOUBLE_TAP to listOf(
                GestureAction.RESET_ROTATION,
                GestureAction.FIT_CANVAS,
                GestureAction.FLIP_CANVAS_H,
                GestureAction.FLIP_CANVAS_V,
                GestureAction.NONE,
            ),
            GestureTrigger.TWO_FINGER_LONG_PRESS to listOf(
                GestureAction.EYEDROPPER,
                GestureAction.QUICK_MENU,
                GestureAction.FIT_CANVAS,
                GestureAction.NONE,
            ),
            GestureTrigger.THREE_FINGER_TAP to listOf(
                GestureAction.REDO,
                GestureAction.NONE,
            ),
            GestureTrigger.THREE_FINGER_DOUBLE_TAP to listOf(
                GestureAction.FIT_CANVAS,
                GestureAction.RESET_ROTATION,
                GestureAction.FLIP_CANVAS_H,
                GestureAction.FLIP_CANVAS_V,
                GestureAction.NONE,
            ),
            GestureTrigger.THREE_FINGER_LONG_PRESS to listOf(
                GestureAction.CLEAR_LAYER,
                GestureAction.QUICK_MENU,
                GestureAction.FULLSCREEN,
                GestureAction.NONE,
            ),
            GestureTrigger.QUICK_PINCH to listOf(
                GestureAction.FIT_CANVAS,
                GestureAction.RESET_ROTATION,
                GestureAction.NONE,
            ),
            GestureTrigger.THREE_FINGER_SWIPE_DOWN to listOf(
                GestureAction.QUICK_MENU,
                GestureAction.CLEAR_LAYER,
                GestureAction.FLIP_CANVAS_H,
                GestureAction.FLIP_CANVAS_V,
                GestureAction.RESET_ROTATION,
                GestureAction.FIT_CANVAS,
                GestureAction.FULLSCREEN,
                GestureAction.NONE,
            ),
            GestureTrigger.FOUR_FINGER_TAP to listOf(
                GestureAction.FULLSCREEN,
                GestureAction.FLIP_CANVAS_H,
                GestureAction.FLIP_CANVAS_V,
                GestureAction.RESET_ROTATION,
                GestureAction.LOCK_VIEW,
                GestureAction.NEW_LAYER,
                GestureAction.QUICK_MENU,
                GestureAction.NONE,
            ),
            GestureTrigger.SINGLE_FINGER_LONG_PRESS to listOf(
                GestureAction.EYEDROPPER,
                GestureAction.QUICK_MENU,
                GestureAction.SELECT_LAYER_AT_POINT,
                GestureAction.NONE,
            ),
            GestureTrigger.SINGLE_FINGER_DOUBLE_TAP to listOf(
                GestureAction.SELECT_LAYER_AT_POINT,
                GestureAction.EYEDROPPER,
                GestureAction.FIT_CANVAS,
                GestureAction.RESET_ROTATION,
                GestureAction.NONE,
            ),
        )

        val SELECTABLE_ACTIONS: List<GestureAction> = listOf(
            GestureAction.NONE,
            GestureAction.UNDO,
            GestureAction.REDO,
            GestureAction.QUICK_MENU,
            GestureAction.EYEDROPPER,
            GestureAction.FIT_CANVAS,
            GestureAction.FLIP_CANVAS_H,
            GestureAction.FLIP_CANVAS_V,
            GestureAction.RESET_ROTATION,
            GestureAction.TOGGLE_ERASER,
            GestureAction.NEW_LAYER,
            GestureAction.CLEAR_LAYER,
            GestureAction.SELECT_LAYER_AT_POINT,
            GestureAction.FULLSCREEN,
            GestureAction.LOCK_VIEW,
        )

        val DEFAULT_TRIGGER_MAPPINGS: Map<GestureTrigger, GestureAction> = mapOf(
            GestureTrigger.TWO_FINGER_TAP to GestureAction.UNDO,
            GestureTrigger.TWO_FINGER_DOUBLE_TAP to GestureAction.NONE,
            GestureTrigger.TWO_FINGER_LONG_PRESS to GestureAction.NONE,
            GestureTrigger.THREE_FINGER_TAP to GestureAction.REDO,
            GestureTrigger.THREE_FINGER_DOUBLE_TAP to GestureAction.NONE,
            GestureTrigger.THREE_FINGER_LONG_PRESS to GestureAction.NONE,
            GestureTrigger.QUICK_PINCH to GestureAction.FIT_CANVAS,
            GestureTrigger.THREE_FINGER_SWIPE_DOWN to GestureAction.QUICK_MENU,
            GestureTrigger.FOUR_FINGER_TAP to GestureAction.FULLSCREEN,
            GestureTrigger.SINGLE_FINGER_LONG_PRESS to GestureAction.EYEDROPPER,
            GestureTrigger.SINGLE_FINGER_DOUBLE_TAP to GestureAction.SELECT_LAYER_AT_POINT,
        )

        val DEFAULT_MAPPINGS: Map<GestureAction, GestureTrigger> = mapOf(
            GestureAction.QUICK_MENU to GestureTrigger.THREE_FINGER_SWIPE_DOWN,
            GestureAction.UNDO to GestureTrigger.TWO_FINGER_TAP,
            GestureAction.REDO to GestureTrigger.THREE_FINGER_TAP,
            GestureAction.FULLSCREEN to GestureTrigger.FOUR_FINGER_TAP,
            GestureAction.EYEDROPPER to GestureTrigger.SINGLE_FINGER_LONG_PRESS,
            GestureAction.FIT_CANVAS to GestureTrigger.QUICK_PINCH,
            GestureAction.CLEAR_LAYER to GestureTrigger.NONE,
            GestureAction.SELECT_LAYER_AT_POINT to GestureTrigger.SINGLE_FINGER_DOUBLE_TAP,
            GestureAction.FLIP_CANVAS_H to GestureTrigger.NONE,
            GestureAction.FLIP_CANVAS_V to GestureTrigger.NONE,
            GestureAction.RESET_ROTATION to GestureTrigger.NONE,
            GestureAction.TOGGLE_ERASER to GestureTrigger.NONE,
            GestureAction.NEW_LAYER to GestureTrigger.NONE,
            GestureAction.LOCK_VIEW to GestureTrigger.NONE,
            GestureAction.BRUSH_SLIDE_ADJUST to GestureTrigger.THREE_FINGER_SLIDE,
        )

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

        fun fromActionMap(actionMap: Map<GestureAction, GestureTrigger>): GestureConfig {
            val map = DEFAULT_TRIGGER_MAPPINGS.toMutableMap()
            actionMap.forEach { (action, trigger) ->
                if (trigger != GestureTrigger.NONE && action != GestureAction.NONE) {
                    map[trigger] = action
                }
            }
            return GestureConfig(map)
        }

        fun deserialize(serialized: String?): GestureConfig {
            if (serialized.isNullOrBlank()) return GestureConfig(DEFAULT_TRIGGER_MAPPINGS)
            val map = DEFAULT_TRIGGER_MAPPINGS.toMutableMap()
            serialized.split(";").forEach { entry ->
                val parts = entry.split(":")
                if (parts.size == 2) {
                    val key = parts[0]
                    val value = parts[1]
                    val trig = GestureTrigger.fromId(key)
                    if (trig != GestureTrigger.NONE) {
                        // New format: trigger:action
                        val act = GestureAction.fromId(value)
                        map[trig] = act
                    } else {
                        // Old format: action:trigger
                        val act = GestureAction.fromId(key)
                        val oldTrig = GestureTrigger.fromId(value)
                        if (act != GestureAction.NONE && oldTrig != GestureTrigger.NONE) {
                            map[oldTrig] = act
                        }
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
    GestureAction.FLIP_CANVAS_H -> com.reverie.paint.R.string.gesture_action_flip_canvas_h
    GestureAction.FLIP_CANVAS_V -> com.reverie.paint.R.string.gesture_action_flip_canvas_v
    GestureAction.RESET_ROTATION -> com.reverie.paint.R.string.gesture_action_reset_rotation
    GestureAction.TOGGLE_ERASER -> com.reverie.paint.R.string.gesture_action_toggle_eraser
    GestureAction.NEW_LAYER -> com.reverie.paint.R.string.gesture_action_new_layer
    GestureAction.LOCK_VIEW -> com.reverie.paint.R.string.gesture_action_lock_view
    GestureAction.BRUSH_SLIDE_ADJUST -> com.reverie.paint.R.string.gesture_action_brush_slide_adjust
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
    GestureAction.FLIP_CANVAS_H -> com.reverie.paint.R.string.gesture_action_flip_canvas_h_desc
    GestureAction.FLIP_CANVAS_V -> com.reverie.paint.R.string.gesture_action_flip_canvas_v_desc
    GestureAction.RESET_ROTATION -> com.reverie.paint.R.string.gesture_action_reset_rotation_desc
    GestureAction.TOGGLE_ERASER -> com.reverie.paint.R.string.gesture_action_toggle_eraser_desc
    GestureAction.NEW_LAYER -> com.reverie.paint.R.string.gesture_action_new_layer_desc
    GestureAction.LOCK_VIEW -> com.reverie.paint.R.string.gesture_action_lock_view_desc
    GestureAction.BRUSH_SLIDE_ADJUST -> com.reverie.paint.R.string.gesture_action_brush_slide_adjust_desc
    GestureAction.NONE -> com.reverie.paint.R.string.gesture_action_none
}

fun GestureAction.iconRes(): Int = when (this) {
    GestureAction.QUICK_MENU -> com.reverie.paint.R.drawable.ic_brush_quick
    GestureAction.UNDO -> com.reverie.paint.R.drawable.ic_undo
    GestureAction.REDO -> com.reverie.paint.R.drawable.ic_redo
    GestureAction.FULLSCREEN -> com.reverie.paint.R.drawable.ic_focus_view
    GestureAction.EYEDROPPER -> com.reverie.paint.R.drawable.ic_picker
    GestureAction.FIT_CANVAS -> com.reverie.paint.R.drawable.ic_refresh
    GestureAction.CLEAR_LAYER -> com.reverie.paint.R.drawable.ic_trash
    GestureAction.SELECT_LAYER_AT_POINT -> com.reverie.paint.R.drawable.ic_layers
    GestureAction.FLIP_CANVAS_H -> com.reverie.paint.R.drawable.ic_flip_horizontal
    GestureAction.FLIP_CANVAS_V -> com.reverie.paint.R.drawable.ic_flip_vertical
    GestureAction.RESET_ROTATION -> com.reverie.paint.R.drawable.ic_rotate_cw
    GestureAction.TOGGLE_ERASER -> com.reverie.paint.R.drawable.ic_eraser
    GestureAction.NEW_LAYER -> com.reverie.paint.R.drawable.ic_plus
    GestureAction.LOCK_VIEW -> com.reverie.paint.R.drawable.ic_lock
    GestureAction.BRUSH_SLIDE_ADJUST -> com.reverie.paint.R.drawable.ic_sliders
    GestureAction.NONE -> com.reverie.paint.R.drawable.ic_x
}

fun GestureTrigger.labelRes(): Int = when (this) {
    GestureTrigger.SINGLE_FINGER_TOUCH_SLIDE -> com.reverie.paint.R.string.gesture_trigger_single_finger_touch_slide
    GestureTrigger.SINGLE_FINGER_DOUBLE_TAP -> com.reverie.paint.R.string.gesture_trigger_single_finger_double_tap
    GestureTrigger.SINGLE_FINGER_LONG_PRESS -> com.reverie.paint.R.string.gesture_trigger_single_finger_long_press
    GestureTrigger.TWO_FINGER_TAP -> com.reverie.paint.R.string.gesture_trigger_two_finger_tap
    GestureTrigger.TWO_FINGER_DOUBLE_TAP -> com.reverie.paint.R.string.gesture_trigger_two_finger_double_tap
    GestureTrigger.TWO_FINGER_LONG_PRESS -> com.reverie.paint.R.string.gesture_trigger_two_finger_long_press
    GestureTrigger.QUICK_PINCH -> com.reverie.paint.R.string.gesture_trigger_quick_pinch
    GestureTrigger.THREE_FINGER_TAP -> com.reverie.paint.R.string.gesture_trigger_three_finger_tap
    GestureTrigger.THREE_FINGER_DOUBLE_TAP -> com.reverie.paint.R.string.gesture_trigger_three_finger_double_tap
    GestureTrigger.THREE_FINGER_LONG_PRESS -> com.reverie.paint.R.string.gesture_trigger_three_finger_long_press
    GestureTrigger.THREE_FINGER_SWIPE_DOWN -> com.reverie.paint.R.string.gesture_trigger_three_finger_swipe_down
    GestureTrigger.THREE_FINGER_SWIPE_UP -> com.reverie.paint.R.string.gesture_trigger_three_finger_swipe_up
    GestureTrigger.THREE_FINGER_SWIPE_LEFT -> com.reverie.paint.R.string.gesture_trigger_three_finger_swipe_left
    GestureTrigger.THREE_FINGER_SWIPE_RIGHT -> com.reverie.paint.R.string.gesture_trigger_three_finger_swipe_right
    GestureTrigger.THREE_FINGER_SLIDE -> com.reverie.paint.R.string.gesture_trigger_three_finger_slide
    GestureTrigger.FOUR_FINGER_TAP -> com.reverie.paint.R.string.gesture_trigger_four_finger_tap
    GestureTrigger.NONE -> com.reverie.paint.R.string.gesture_trigger_none
}
