/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.layers

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeChild
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Surface
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Brush
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import java.util.Locale
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.mutableStateListOf
import androidx.annotation.StringRes
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.reverie.paint.R
import com.reverie.paint.core.*
import com.reverie.paint.ui.components.ReSlider
import com.reverie.paint.ui.components.noRippleClickable
import com.reverie.paint.ui.theme.Morandi
import kotlin.math.abs
import kotlin.math.roundToInt

data class FilterItemDef(
    val id: Int,
    @StringRes val nameRes: Int,
    val hasSliders: Boolean,
    @StringRes val descRes: Int = 0
)

data class FilterCategoryDef(
    val id: String,
    @StringRes val nameRes: Int,
    val iconRes: Int,
    val filters: List<FilterItemDef>
)

@Composable
internal fun FiltersPage(
    vm: PaintViewModel,
    indices: List<Int>,
    onBack: () -> Unit,
    onSelectFilter: (Int, String) -> Unit,
    initialCategoryId: String? = null,
) {
    val context = LocalContext.current
    val categories = remember {
        listOf(
            FilterCategoryDef(
                id = "color",
                nameRes = R.string.filter_cat_adjust_color,
                iconRes = R.drawable.ic_image_adjust,
                filters = listOf(
                    FilterItemDef(0, R.string.filter_name_hsv, true, R.string.filter_desc_hsv),
                    FilterItemDef(13, R.string.filter_name_curves, true, R.string.filter_desc_curves),
                    FilterItemDef(14, R.string.filter_name_levels, true, R.string.filter_desc_levels),
                    FilterItemDef(28, R.string.filter_name_vibrance, true, R.string.filter_desc_vibrance),
                    FilterItemDef(1, R.string.filter_name_color_balance, true, R.string.filter_desc_color_balance),
                    FilterItemDef(15, R.string.filter_name_temp_tint, true, R.string.filter_desc_temp_tint),
                    FilterItemDef(27, R.string.filter_name_shadow_highlight, true, R.string.filter_desc_shadow_highlight),
                    FilterItemDef(24, R.string.filter_name_exposure, true, R.string.filter_desc_exposure),
                    FilterItemDef(12, R.string.filter_name_desaturate, true, R.string.filter_desc_desaturate),
                    FilterItemDef(6, R.string.filter_name_invert, true, R.string.filter_desc_invert),
                    FilterItemDef(16, R.string.filter_name_threshold, true, R.string.filter_desc_threshold),
                )
            ),
            FilterCategoryDef(
                id = "blur",
                nameRes = R.string.filter_cat_blur_smooth,
                iconRes = R.drawable.ic_smudge,
                filters = listOf(
                    FilterItemDef(2, R.string.filter_name_gaussian_blur, true, R.string.filter_desc_gaussian_blur),
                    FilterItemDef(3, R.string.filter_name_motion_blur, true, R.string.filter_desc_motion_blur),
                    FilterItemDef(22, R.string.filter_name_radial_blur, true, R.string.filter_desc_radial_blur),
                    FilterItemDef(33, R.string.filter_name_surface_blur, true, R.string.filter_desc_surface_blur),
                    FilterItemDef(26, R.string.filter_name_defocus, true, R.string.filter_desc_defocus),
                )
            ),
            FilterCategoryDef(
                id = "enhance",
                nameRes = R.string.filter_cat_enhance,
                iconRes = R.drawable.ic_magicwand,
                filters = listOf(
                    FilterItemDef(18, R.string.filter_name_bloom, true, R.string.filter_desc_bloom),
                    FilterItemDef(4, R.string.filter_name_sharpen, true, R.string.filter_desc_sharpen),
                    FilterItemDef(19, R.string.filter_name_drop_shadow, true, R.string.filter_desc_drop_shadow),
                    FilterItemDef(25, R.string.filter_name_edge_glow, true, R.string.filter_desc_edge_glow),
                    FilterItemDef(8, R.string.filter_name_sobel, true, R.string.filter_desc_sobel),
                    FilterItemDef(9, R.string.filter_name_emboss, true, R.string.filter_desc_emboss),
                )
            ),
            FilterCategoryDef(
                id = "map",
                nameRes = R.string.filter_cat_map_channels,
                iconRes = R.drawable.ic_gradient,
                filters = listOf(
                    FilterItemDef(30, R.string.filter_name_gradient_map, true, R.string.filter_desc_gradient_map),
                    FilterItemDef(7, R.string.filter_name_lum_to_alpha, true, R.string.filter_desc_lum_to_alpha),
                    FilterItemDef(29, R.string.filter_name_color_to_alpha, true, R.string.filter_desc_color_to_alpha),
                    FilterItemDef(20, R.string.filter_name_lum_to_opacity, true, R.string.filter_desc_lum_to_opacity),
                )
            ),
            FilterCategoryDef(
                id = "artistic",
                nameRes = R.string.filter_cat_artistic,
                iconRes = R.drawable.ic_brush,
                filters = listOf(
                    FilterItemDef(10, R.string.filter_name_noise, true, R.string.filter_desc_noise),
                    FilterItemDef(21, R.string.filter_name_oil, true, R.string.filter_desc_oil),
                    FilterItemDef(17, R.string.filter_name_posterize, true, R.string.filter_desc_posterize),
                    FilterItemDef(5, R.string.filter_name_mosaic, true, R.string.filter_desc_mosaic),
                    FilterItemDef(23, R.string.filter_name_halftone, true, R.string.filter_desc_halftone),
                    FilterItemDef(34, R.string.filter_name_scanline, true, R.string.filter_desc_scanline),
                )
            ),
            FilterCategoryDef(
                id = "distort",
                nameRes = R.string.filter_cat_distort,
                iconRes = R.drawable.ic_crop,
                filters = listOf(
                    FilterItemDef(11, R.string.filter_name_glitch, true, R.string.filter_desc_glitch),
                    FilterItemDef(31, R.string.filter_name_ripple, true, R.string.filter_desc_ripple),
                    FilterItemDef(32, R.string.filter_name_swirl, true, R.string.filter_desc_swirl),
                )
            ),
        )
    }

    // 快捷键 (filter_hsv/curves/blur/sharpen) 预选分类; 用户仍可返回重选
    var selectedCategory by remember(initialCategoryId) {
        mutableStateOf(initialCategoryId?.let { id -> categories.firstOrNull { it.id == id } })
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        // Top Header
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Box(
                modifier =
                    Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .noRippleClickable {
                            if (selectedCategory != null) {
                                selectedCategory = null
                            } else {
                                onBack()
                            }
                        },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painterResource(R.drawable.ic_chevron),
                    contentDescription = stringResource(R.string.back),
                    tint = Morandi.icon,
                    modifier = Modifier.size(18.dp),
                )
            }
            val titleText = selectedCategory?.let { stringResource(it.nameRes) }
                ?: if (indices.size > 1) stringResource(R.string.filter_library_multi_layers, indices.size)
                else stringResource(R.string.filter_library_title)
            Text(
                text = titleText,
                color = Morandi.text,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Morandi.border))

        AnimatedContent(
            targetState = selectedCategory,
            label = "FilterNav"
        ) { category ->
            if (category == null) {
                // Category List (Level 1)
                Column(
                    modifier = Modifier
                        .heightIn(max = 480.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    categories.forEach { cat ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .noRippleClickable { selectedCategory = cat }
                                .padding(horizontal = 14.dp, vertical = 13.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(30.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Morandi.accent.copy(alpha = 0.12f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        painterResource(cat.iconRes),
                                        contentDescription = null,
                                        tint = Morandi.accent,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                                Column {
                                    Text(stringResource(cat.nameRes), color = Morandi.text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                    Text(stringResource(R.string.filter_count_format, cat.filters.size), color = Morandi.subText, fontSize = 11.sp)
                                }
                            }
                            Icon(
                                painterResource(R.drawable.ic_chevron),
                                contentDescription = null,
                                tint = Morandi.subText.copy(alpha = 0.5f),
                                modifier = Modifier.size(14.dp)
                            )
                        }
                        Box(Modifier.fillMaxWidth().padding(horizontal = 14.dp).height(0.5.dp).background(Morandi.border.copy(alpha = 0.4f)))
                    }
                }
            } else {
                // Category Filters (Level 2)
                Column(
                    modifier = Modifier
                        .heightIn(max = 480.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    category.filters.forEach { item ->
                        val itemName = stringResource(item.nameRes)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .noRippleClickable {
                                    onSelectFilter(item.id, itemName)
                                }.padding(horizontal = 14.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(itemName, color = Morandi.text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                if (item.descRes != 0) {
                                    Text(stringResource(item.descRes), color = Morandi.subText, fontSize = 11.sp)
                                }
                            }
                            Icon(
                                painterResource(R.drawable.ic_chevron),
                                contentDescription = null,
                                tint = Morandi.subText.copy(alpha = 0.5f),
                                modifier = Modifier.size(14.dp)
                            )
                        }
                        Box(Modifier.fillMaxWidth().padding(horizontal = 14.dp).height(0.5.dp).background(Morandi.border.copy(alpha = 0.4f)))
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Real Interactive 2D Curves Graph Component (Steffen Monotone Cubic Spline)
// ---------------------------------------------------------------------------

internal fun evaluateSteffenSpline(points: List<Offset>, curX: Float): Float {
    val sorted = points.sortedBy { it.x }.distinctBy { (it.x * 10f).roundToInt() }
    if (sorted.isEmpty()) return curX.coerceIn(0f, 255f)
    if (sorted.size == 1) return sorted[0].y.coerceIn(0f, 255f)
    if (curX <= sorted.first().x) return sorted.first().y.coerceIn(0f, 255f)
    if (curX >= sorted.last().x) return sorted.last().y.coerceIn(0f, 255f)

    val n = sorted.size
    val x = FloatArray(n) { sorted[it].x.coerceIn(0f, 255f) }
    val y = FloatArray(n) { sorted[it].y.coerceIn(0f, 255f) }
    val h = FloatArray(n - 1) { x[it + 1] - x[it] }
    val d = FloatArray(n - 1) { if (h[it] != 0f) (y[it + 1] - y[it]) / h[it] else 0f }

    val m = FloatArray(n)
    m[0] = d[0]
    m[n - 1] = d[n - 2]
    for (i in 1 until n - 1) {
        val hSum = h[i - 1] + h[i]
        val p = if (hSum != 0f) (d[i - 1] * h[i] + d[i] * h[i - 1]) / hSum else 0f
        if (d[i - 1] * d[i] <= 0f) {
            m[i] = 0f
        } else {
            val s = if (d[i] >= 0f) 1f else -1f
            m[i] = s * minOf(kotlin.math.abs(d[i - 1]), kotlin.math.abs(d[i]), 0.5f * kotlin.math.abs(p))
        }
    }

    var seg = 0
    while (seg < n - 2 && curX > x[seg + 1]) {
        seg++
    }
    val segH = h[seg]
    val t = if (segH != 0f) (curX - x[seg]) / segH else 0f
    val t2 = t * t
    val t3 = t2 * t
    val h00 = 2f * t3 - 3f * t2 + 1f
    val h10 = t3 - 2f * t2 + t
    val h01 = -2f * t3 + 3f * t2
    val h11 = t3 - t2
    val valY = h00 * y[seg] + h10 * segH * m[seg] + h01 * y[seg + 1] + h11 * segH * m[seg + 1]
    return valY.coerceIn(0f, 255f)
}

internal fun calculateMonotoneCubicSplineLUT(points: List<Offset>): ByteArray {
    val lut = ByteArray(256)
    for (i in 0..255) {
        val y = evaluateSteffenSpline(points, i.toFloat())
        lut[i] = y.roundToInt().coerceIn(0, 255).toByte()
    }
    return lut
}

