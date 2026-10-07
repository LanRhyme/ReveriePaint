/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.reverie.paint.R

/**
 * 快捷操作支持的动作类型定义
 */
enum class QuickAction(
    val id: String,
    @param:StringRes val titleRes: Int,
    @param:DrawableRes val iconRes: Int,
) {
    LOCK_VIEW("lock_view", R.string.quick_action_lock_view, R.drawable.ic_lock),
    FLIP_H("flip_h", R.string.quick_action_flip_h, R.drawable.ic_flip_horizontal),
    FLIP_V("flip_v", R.string.quick_action_flip_v, R.drawable.ic_flip_vertical),
    RESET_VIEW("reset_view", R.string.quick_action_reset_view, R.drawable.ic_focus_view),
    UNDO("undo", R.string.quick_action_undo, R.drawable.ic_undo),
    REDO("redo", R.string.quick_action_redo, R.drawable.ic_redo),
    TOGGLE_ERASER("toggle_eraser", R.string.quick_action_toggle_eraser, R.drawable.ic_eraser),
    LAST_TOOL("last_tool", R.string.quick_action_last_tool, R.drawable.ic_last_tool),
    PICK_COLOR("pick_color", R.string.quick_action_pick_color, R.drawable.ic_picker),
    SWAP_COLOR("swap_color", R.string.quick_action_swap_color, R.drawable.ic_swap_colors),
    BRUSH_SIZE_INC("brush_size_inc", R.string.quick_action_brush_size_inc, R.drawable.ic_plus),
    BRUSH_SIZE_DEC("brush_size_dec", R.string.quick_action_brush_size_dec, R.drawable.ic_minus),
    ALPHA_LOCK("alpha_lock", R.string.quick_action_alpha_lock, R.drawable.ic_grid),
    NEW_LAYER("new_layer", R.string.quick_action_new_layer, R.drawable.ic_square_plus),
    DUPLICATE_LAYER("duplicate_layer", R.string.quick_action_duplicate_layer, R.drawable.ic_copy),
    MERGE_DOWN("merge_down", R.string.quick_action_merge_down, R.drawable.ic_merge_down),
    CLEAR_LAYER("clear_layer", R.string.quick_action_clear_layer, R.drawable.ic_trash),
    DISABLE_TOUCH("disable_touch", R.string.quick_action_disable_touch, R.drawable.ic_hand),
    TOGGLE_QUICK_COLOR("toggle_quick_color", R.string.quick_action_toggle_quick_color, R.drawable.ic_palette),
    TOGGLE_QUICK_LAYER("toggle_quick_layer", R.string.quick_action_toggle_quick_layer, R.drawable.ic_layers),
    ;

    companion object {
        fun fromId(id: String): QuickAction? = entries.find { it.id == id }

        /** 默认启用的常用快捷动作集合 */
        val DEFAULT_ACTIONS = listOf(
            UNDO,
            REDO,
            TOGGLE_ERASER,
            PICK_COLOR,
            LOCK_VIEW,
            FLIP_H,
            RESET_VIEW,
            NEW_LAYER,
        )
    }
}

/** 快捷面板布局模式 */
enum class QuickActionLayoutMode(val id: String, @param:StringRes val labelRes: Int) {
    ROW("row", R.string.quick_action_layout_row),             // 单行胶囊
    COLUMN("column", R.string.quick_action_layout_column),       // 单列胶囊
    GRID_2("grid_2", R.string.quick_action_layout_grid_2),       // 2 列网格 (圆角矩形)
    GRID_3("grid_3", R.string.quick_action_layout_grid_3),       // 3 列网格 (圆角矩形)
    ;

    companion object {
        fun fromId(id: String): QuickActionLayoutMode = entries.find { it.id == id } ?: COLUMN
    }
}

/** 快捷操作面板的用户配置数据模型 */
data class QuickActionsConfig(
    val actions: List<QuickAction> = QuickAction.DEFAULT_ACTIONS,
    val layoutMode: QuickActionLayoutMode = QuickActionLayoutMode.COLUMN,
    val showLabels: Boolean = false,
)
