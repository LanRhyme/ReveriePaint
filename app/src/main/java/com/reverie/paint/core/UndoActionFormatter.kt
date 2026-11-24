/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import android.content.Context
import com.reverie.paint.R

object UndoActionFormatter {
    fun formatUndo(context: Context, rawAction: String?): String {
        val label = resolveActionLabel(context, rawAction)
        return if (!label.isNullOrBlank()) {
            context.getString(R.string.toast_undo_with_action, label)
        } else {
            context.getString(R.string.toast_undo)
        }
    }

    fun formatRedo(context: Context, rawAction: String?): String {
        val label = resolveActionLabel(context, rawAction)
        return if (!label.isNullOrBlank()) {
            context.getString(R.string.toast_redo_with_action, label)
        } else {
            context.getString(R.string.toast_redo)
        }
    }

    fun resolveActionLabel(context: Context, rawAction: String?): String? {
        if (rawAction.isNullOrBlank()) return null
        val trimmed = rawAction.trim()
        return when (trimmed) {
            "Stroke", "Symmetry Stroke" -> context.getString(R.string.undo_action_stroke)
            "Merge Down", "向下合并" -> context.getString(R.string.undo_action_merge_down)
            "合并图层组" -> context.getString(R.string.undo_action_merge_group)
            "栅格化图层" -> context.getString(R.string.undo_action_rasterize_layer)
            "栅格化滤镜图层" -> context.getString(R.string.undo_action_rasterize_filter)
            "栅格化描边", "Rasterize Stroke" -> context.getString(R.string.undo_action_rasterize_stroke)
            "Clear" -> context.getString(R.string.undo_action_clear)
            "Fill", "Fill Layer Color", "Fill Layer" -> context.getString(R.string.undo_action_fill)
            "Fill Background", "Change Background Color", "修改背景颜色" -> context.getString(R.string.undo_action_bg_color)
            "Filter" -> context.getString(R.string.undo_action_filter)
            "Transform", "Transform Preview" -> context.getString(R.string.undo_action_transform)
            "Perspective Transform" -> context.getString(R.string.undo_action_perspective_transform)
            "Mesh Warp Transform" -> context.getString(R.string.undo_action_mesh_warp)
            "Liquify" -> context.getString(R.string.undo_action_liquify)
            "Cut" -> context.getString(R.string.undo_action_cut)
            "Cut Selection" -> context.getString(R.string.undo_action_cut)
            "Move", "Move Layers" -> context.getString(R.string.undo_action_move_layers)
            "Move Layers to Group" -> context.getString(R.string.undo_action_move_layers)
            "Copy Layer with Masks" -> context.getString(R.string.undo_action_copy_layer)
            "Stamp" -> context.getString(R.string.undo_action_stamp)
            "Shape" -> context.getString(R.string.undo_action_shape)
            "Polygon" -> context.getString(R.string.undo_action_polygon)
            "Gradient" -> context.getString(R.string.undo_action_gradient)
            "Pattern Fill", "Fill Layer Pattern" -> context.getString(R.string.undo_action_pattern_fill)
            "Lasso Fill" -> context.getString(R.string.undo_action_lasso_fill)
            "Lasso Clear" -> context.getString(R.string.undo_action_lasso_clear)
            "Enclose and Fill" -> context.getString(R.string.undo_action_enclose_fill)
            "Invert Mask" -> context.getString(R.string.undo_action_invert_mask)
            "Apply Mask", "应用蒙版" -> context.getString(R.string.undo_action_apply_mask)
            "QuickShape Symmetry" -> context.getString(R.string.undo_action_quick_shape)
            "调整层配置" -> context.getString(R.string.undo_action_adjustment_layer)
            else -> trimmed
        }
    }
}
