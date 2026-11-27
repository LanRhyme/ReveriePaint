/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.panels

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reverie.paint.R
import com.reverie.paint.core.CustomToolIconBitmapCache
import com.reverie.paint.model.CustomToolIcon
import com.reverie.paint.model.CustomToolIconCodec
import com.reverie.paint.model.Tool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 可选内置矢量图标库，按绘图类别分组排序。
 */
val BUILTIN_TOOL_ICON_CATALOG: List<Pair<String, Int>> = listOf(
    "ic_brush" to R.drawable.ic_brush,
    "ic_pencil" to R.drawable.ic_pencil,
    "ic_brush_quick" to R.drawable.ic_brush_quick,
    "ic_eraser" to R.drawable.ic_eraser,
    "ic_smudge" to R.drawable.ic_smudge,
    "ic_liquify" to R.drawable.ic_liquify,
    "ic_fill" to R.drawable.ic_fill,
    "ic_enclose_fill" to R.drawable.ic_enclose_fill,
    "ic_gradient" to R.drawable.ic_gradient,
    "ic_picker" to R.drawable.ic_picker,
    "ic_palette" to R.drawable.ic_palette,
    "ic_oklch" to R.drawable.ic_oklch,
    "ic_lasso" to R.drawable.ic_lasso,
    "ic_lasso_multi" to R.drawable.ic_lasso_multi,
    "ic_magicwand" to R.drawable.ic_magicwand,
    "ic_select_rect" to R.drawable.ic_select_rect,
    "ic_select" to R.drawable.ic_select,
    "ic_shapes" to R.drawable.ic_shapes,
    "ic_rect" to R.drawable.ic_rect,
    "ic_circle" to R.drawable.ic_circle,
    "ic_ellipse" to R.drawable.ic_ellipse,
    "ic_triangle" to R.drawable.ic_triangle,
    "ic_line" to R.drawable.ic_line,
    "ic_polyline" to R.drawable.ic_polyline,
    "ic_move" to R.drawable.ic_move,
    "ic_crop" to R.drawable.ic_crop,
    "ic_tf_standard" to R.drawable.ic_tf_standard,
    "ic_tf_free" to R.drawable.ic_tf_free,
    "ic_tf_perspective" to R.drawable.ic_tf_perspective,
    "ic_tf_distort" to R.drawable.ic_tf_distort,
    "ic_layers" to R.drawable.ic_layers,
    "ic_layerstack" to R.drawable.ic_layerstack,
    "ic_grid" to R.drawable.ic_grid,
    "ic_guide_lines" to R.drawable.ic_guide_lines,
    "ic_panel_frame" to R.drawable.ic_panel_frame,
    "ic_reference" to R.drawable.ic_reference,
    "ic_hand" to R.drawable.ic_hand,
    "ic_zoom_in" to R.drawable.ic_zoom_in,
    "ic_text" to R.drawable.ic_text,
    "ic_stamp" to R.drawable.ic_stamp,
    "ic_canvas_resize" to R.drawable.ic_canvas_resize,
    "ic_star" to R.drawable.ic_star,
    "ic_star_filled" to R.drawable.ic_star_filled,
    "ic_heart" to R.drawable.ic_heart,
    "ic_moon" to R.drawable.ic_moon,
    "ic_sun" to R.drawable.ic_sun,
    "ic_droplet" to R.drawable.ic_droplet,
    "ic_eye" to R.drawable.ic_eye,
    "ic_shortcut" to R.drawable.ic_shortcut,
    "ic_sliders" to R.drawable.ic_sliders,
    "ic_clip" to R.drawable.ic_clip,
    "ic_lock" to R.drawable.ic_lock,
    "ic_bookmark_plus" to R.drawable.ic_bookmark_plus,
    "ic_focus_view" to R.drawable.ic_focus_view,
)

private val BUILTIN_TOOL_ICON_MAP: Map<String, Int> = BUILTIN_TOOL_ICON_CATALOG.toMap()

fun resolveBuiltinIconRes(iconKey: String): Int? = BUILTIN_TOOL_ICON_MAP[iconKey]

fun CustomToolIcon?.usesColorSelectEmphasis(): Boolean = when (this) {
    is CustomToolIcon.Image -> !tintWithTheme
    is CustomToolIcon.Text -> CustomToolIconCodec.isLikelyColorEmoji(glyph)
    else -> false
}

@Composable
fun ToolIconGlyph(
    tool: Tool,
    customIcon: CustomToolIcon?,
    tint: Color,
    size: Dp,
    contentDescription: String,
    resolveFile: (String) -> File?,
) {
    when (customIcon) {
        null -> {
            Icon(
                painter = painterResource(toolIcon(tool)),
                contentDescription = contentDescription,
                tint = tint,
                modifier = Modifier.size(size),
            )
        }
        is CustomToolIcon.Builtin -> {
            val resId = resolveBuiltinIconRes(customIcon.iconKey) ?: toolIcon(tool)
            Icon(
                painter = painterResource(resId),
                contentDescription = contentDescription,
                tint = tint,
                modifier = Modifier.size(size),
            )
        }
        is CustomToolIcon.Text -> {
            val clusters = CustomToolIconCodec.countGraphemeClusters(customIcon.glyph)
            val fontSize = if (clusters <= 1) (size.value * 0.74f).sp else (size.value * 0.50f).sp
            Box(
                modifier = Modifier.size(size),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = customIcon.glyph,
                    color = tint,
                    fontSize = fontSize,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
        is CustomToolIcon.Image -> {
            val cached = CustomToolIconBitmapCache.get(customIcon.fileName)
            val decodedBitmap by produceState<Bitmap?>(initialValue = cached, key1 = customIcon.fileName) {
                if (cached != null) {
                    value = cached
                    return@produceState
                }
                val file = resolveFile(customIcon.fileName)
                value = if (file != null) {
                    withContext(Dispatchers.IO) {
                        BitmapFactory.decodeFile(file.absolutePath)?.also {
                            CustomToolIconBitmapCache.put(customIcon.fileName, it)
                        }
                    }
                } else {
                    null
                }
            }
            val bmp = decodedBitmap
            if (bmp != null && !bmp.isRecycled) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = contentDescription,
                    contentScale = ContentScale.Crop,
                    colorFilter = if (customIcon.tintWithTheme) ColorFilter.tint(tint) else null,
                    modifier = Modifier
                        .size(size)
                        .clip(RoundedCornerShape(5.dp)),
                )
            } else {
                Icon(
                    painter = painterResource(toolIcon(tool)),
                    contentDescription = contentDescription,
                    tint = tint,
                    modifier = Modifier.size(size),
                )
            }
        }
    }
}
