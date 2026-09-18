/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.panels

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.reverie.paint.R
import com.reverie.paint.model.Tool
import com.reverie.paint.model.ToolGroup

@StringRes
fun ToolGroup.labelRes(): Int = when (this) {
    ToolGroup.BRUSH -> R.string.tool_group_brush
    ToolGroup.FILL -> R.string.tool_group_fill
    ToolGroup.SHAPES -> R.string.tool_group_shapes
    ToolGroup.SELECTION -> R.string.tool_group_selection
    ToolGroup.TRANSFORM -> R.string.tool_group_transform
    ToolGroup.VIEW -> R.string.tool_group_view
    ToolGroup.OTHER -> R.string.tool_group_other
}

@StringRes
fun Tool.labelRes(): Int = when (this) {
    Tool.BRUSH -> R.string.tool_brush
    Tool.ERASER -> R.string.tool_eraser
    Tool.SMUDGE -> R.string.tool_smudge
    Tool.FILL -> R.string.tool_fill
    Tool.GRADIENT -> R.string.tool_gradient
    Tool.SHAPES -> R.string.tool_shapes
    Tool.LINE -> R.string.tool_line
    Tool.RECT -> R.string.tool_rect
    Tool.ELLIPSE -> R.string.tool_ellipse
    Tool.POLYGON -> R.string.tool_polygon
    Tool.POLYLINE -> R.string.tool_polyline
    Tool.SELECT_RECT -> R.string.tool_select_rect
    Tool.SELECT_ELLIPSE -> R.string.tool_select_ellipse
    Tool.SELECT_POLYGON -> R.string.tool_select_polygon
    Tool.LASSO -> R.string.tool_lasso
    Tool.MAGICWAND -> R.string.tool_magicwand
    Tool.SELECT_SIMILAR -> R.string.tool_select_similar
    Tool.TRANSFORM -> R.string.tool_transform
    Tool.MOVE -> R.string.tool_move
    Tool.CROP -> R.string.tool_crop
    Tool.PICKER -> R.string.tool_picker
    Tool.TEXT -> R.string.tool_text
    Tool.LIQUIFY -> R.string.tool_liquify
    Tool.MEASURE -> R.string.tool_measure
    Tool.PATH -> R.string.tool_path
    Tool.REFERENCE -> R.string.tool_reference
    Tool.SYMMETRY -> R.string.tool_symmetry
    Tool.PERSPECTIVE -> R.string.tool_perspective
}

val ToolGroup.displayName: String
    @Composable
    get() = stringResource(labelRes())

val Tool.displayName: String
    @Composable
    get() = stringResource(labelRes())
