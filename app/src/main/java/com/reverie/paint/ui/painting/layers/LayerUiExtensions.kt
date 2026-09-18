/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.layers

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.reverie.paint.R

private val PAINT_LAYER_REGEX = Regex("""^(?:颜料图层|Paint Layer)\s*(\d+)$""", RegexOption.IGNORE_CASE)
private val GROUP_LAYER_REGEX = Regex("""^(?:图层组|Group)\s*(\d+)?$""", RegexOption.IGNORE_CASE)
private val FILL_LAYER_REGEX = Regex("""^(?:填充图层|Fill Layer)\s*(\d+)?$""", RegexOption.IGNORE_CASE)
private val FILTER_LAYER_REGEX = Regex("""^(?:滤镜图层|Filter Layer)\s*(\d+)?$""", RegexOption.IGNORE_CASE)
private val SELECTION_LAYER_REGEX = Regex("""^(?:选区|Selection)\s*(\d+)$""", RegexOption.IGNORE_CASE)

@Composable
fun layerDisplayName(name: String): String {
    val trimmed = name.trim()
    if (trimmed.equals("背景", ignoreCase = true) || trimmed.equals("Background", ignoreCase = true)) {
        return stringResource(R.string.layer_default_background)
    }
    val paintMatch = PAINT_LAYER_REGEX.find(trimmed)
    if (paintMatch != null) {
        val num = paintMatch.groupValues[1].toIntOrNull() ?: 1
        return stringResource(R.string.layer_default_paint_layer, num)
    }
    if (trimmed.equals("颜料图层", ignoreCase = true) || trimmed.equals("Paint Layer", ignoreCase = true)) {
        return stringResource(R.string.layer_default_paint_layer_base)
    }
    val groupMatch = GROUP_LAYER_REGEX.find(trimmed)
    if (groupMatch != null) {
        val numStr = groupMatch.groupValues[1]
        return if (numStr.isEmpty()) {
            stringResource(R.string.layer_default_group)
        } else {
            stringResource(R.string.layer_default_group_indexed, numStr.toIntOrNull() ?: 1)
        }
    }
    val fillMatch = FILL_LAYER_REGEX.find(trimmed)
    if (fillMatch != null) {
        val numStr = fillMatch.groupValues[1]
        return if (numStr.isEmpty()) {
            stringResource(R.string.layer_default_fill)
        } else {
            stringResource(R.string.layer_default_fill_indexed, numStr.toIntOrNull() ?: 1)
        }
    }
    val filterMatch = FILTER_LAYER_REGEX.find(trimmed)
    if (filterMatch != null) {
        val numStr = filterMatch.groupValues[1]
        return if (numStr.isEmpty()) {
            stringResource(R.string.layer_default_filter)
        } else {
            stringResource(R.string.layer_default_filter_indexed, numStr.toIntOrNull() ?: 1)
        }
    }
    if (trimmed.equals("导入图片", ignoreCase = true) || trimmed.equals("Imported Image", ignoreCase = true)) {
        return stringResource(R.string.layer_default_import_image)
    }
    if (trimmed.equals("盖印可见图层", ignoreCase = true) || trimmed.equals("Stamp Visible Layers", ignoreCase = true)) {
        return stringResource(R.string.layer_default_stamp)
    }
    val selMatch = SELECTION_LAYER_REGEX.find(trimmed)
    if (selMatch != null) {
        val num = selMatch.groupValues[1].toIntOrNull() ?: 1
        return stringResource(R.string.layer_default_selection, num)
    }
    return name
}

fun layerDisplayName(context: Context, name: String): String {
    val trimmed = name.trim()
    if (trimmed.equals("背景", ignoreCase = true) || trimmed.equals("Background", ignoreCase = true)) {
        return context.getString(R.string.layer_default_background)
    }
    val paintMatch = PAINT_LAYER_REGEX.find(trimmed)
    if (paintMatch != null) {
        val num = paintMatch.groupValues[1].toIntOrNull() ?: 1
        return context.getString(R.string.layer_default_paint_layer, num)
    }
    if (trimmed.equals("颜料图层", ignoreCase = true) || trimmed.equals("Paint Layer", ignoreCase = true)) {
        return context.getString(R.string.layer_default_paint_layer_base)
    }
    val groupMatch = GROUP_LAYER_REGEX.find(trimmed)
    if (groupMatch != null) {
        val numStr = groupMatch.groupValues[1]
        return if (numStr.isEmpty()) {
            context.getString(R.string.layer_default_group)
        } else {
            context.getString(R.string.layer_default_group_indexed, numStr.toIntOrNull() ?: 1)
        }
    }
    val fillMatch = FILL_LAYER_REGEX.find(trimmed)
    if (fillMatch != null) {
        val numStr = fillMatch.groupValues[1]
        return if (numStr.isEmpty()) {
            context.getString(R.string.layer_default_fill)
        } else {
            context.getString(R.string.layer_default_fill_indexed, numStr.toIntOrNull() ?: 1)
        }
    }
    val filterMatch = FILTER_LAYER_REGEX.find(trimmed)
    if (filterMatch != null) {
        val numStr = filterMatch.groupValues[1]
        return if (numStr.isEmpty()) {
            context.getString(R.string.layer_default_filter)
        } else {
            context.getString(R.string.layer_default_filter_indexed, numStr.toIntOrNull() ?: 1)
        }
    }
    if (trimmed.equals("导入图片", ignoreCase = true) || trimmed.equals("Imported Image", ignoreCase = true)) {
        return context.getString(R.string.layer_default_import_image)
    }
    if (trimmed.equals("盖印可见图层", ignoreCase = true) || trimmed.equals("Stamp Visible Layers", ignoreCase = true)) {
        return context.getString(R.string.layer_default_stamp)
    }
    val selMatch = SELECTION_LAYER_REGEX.find(trimmed)
    if (selMatch != null) {
        val num = selMatch.groupValues[1].toIntOrNull() ?: 1
        return context.getString(R.string.layer_default_selection, num)
    }
    return name
}
