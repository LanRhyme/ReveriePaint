/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.brush

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.reverie.paint.R

@StringRes
fun brushCategoryResOf(category: String): Int? = when (category) {
    "全部" -> R.string.brush_tab_all
    "常用" -> R.string.brush_tab_fav
    "最近" -> R.string.brush_tab_recent
    "基础" -> R.string.brush_cat_basic
    "铅笔" -> R.string.brush_cat_pencil
    "勾线" -> R.string.brush_cat_inking
    "马克笔" -> R.string.brush_cat_marker
    "绘画" -> R.string.brush_cat_paint
    "水彩" -> R.string.brush_cat_watercolor
    "混合" -> R.string.brush_cat_blender
    "速写" -> R.string.brush_cat_sketch
    "形状" -> R.string.brush_cat_shapes
    "特效与滤镜" -> R.string.brush_cat_fx
    "纹理与排线" -> R.string.brush_cat_texture
    "印章与喷溅" -> R.string.brush_cat_stamp
    "像素画" -> R.string.brush_cat_pixel
    "橡皮擦" -> R.string.brush_cat_eraser
    "导入" -> R.string.brush_cat_import
    else -> null
}

@Composable
fun brushCategoryDisplayName(category: String): String {
    val res = brushCategoryResOf(category)
    return if (res != null) stringResource(res) else category
}
