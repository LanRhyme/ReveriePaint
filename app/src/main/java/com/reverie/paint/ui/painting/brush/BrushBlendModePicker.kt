/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.brush

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.reverie.paint.R
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.core.brushstore.BrushCompositeIds
import com.reverie.paint.ui.components.ReIconButton
import com.reverie.paint.ui.components.noRippleClickable
import com.reverie.paint.ui.components.pressScale
import com.reverie.paint.ui.painting.TextInputGuard
import com.reverie.paint.ui.theme.Morandi

internal fun brushBlendModeIconRes(id: String): Int {
    return when (BrushCompositeIds.canonical(id)) {
        "normal", "dissolve", "copy", "behind", "greater", "destination-in", "destination-atop" -> R.drawable.ic_blend_normal
        "multiply", "darker color" -> R.drawable.ic_blend_multiply
        "screen" -> R.drawable.ic_blend_screen
        "darken", "alphadarken", "gamma_dark", "easy burn", "shade_ifs_illusions", "fog_darken_ifs_illusions" -> R.drawable.ic_blend_darken
        "lighten", "lighter color", "gamma_light", "gamma_illumination", "easy dodge", "tint_ifs_illusions", "fog_lighten_ifs_illusions" -> R.drawable.ic_blend_lighten
        "dodge", "luminosity_sai" -> R.drawable.ic_blend_dodge
        "burn", "linear_burn", "marker" -> R.drawable.ic_blend_burn
        "soft_light", "soft_light_svg", "soft_light_pegtop_delphi", "soft_light_ifs_illusions" -> R.drawable.ic_blend_softlight
        "overlay", "hard_light", "vivid_light", "linear light", "pin_light", "hard mix",
        "hard_mix_photoshop", "hard_mix_softer_photoshop", "hard overlay", "flat_light", "super_light",
        "pnorm_a", "pnorm_b", "lambert_lighting", "lambert_lighting_gamma2.2",
        -> R.drawable.ic_blend_hardlight
        "diff", "exclusion", "equivalence", "negation", "arc_tangent", "grain_extract" -> R.drawable.ic_blend_difference
        "add", "linear_dodge", "glow", "reflect", "freeze", "heat", "glow_heat",
        "heat_glow", "reflect_freeze", "freeze_reflect", "heat_glow_freeze_reflect_hybrid",
        "grain_merge", "allanon", "parallel", "geometric_mean", "interpolation", "interpolation 2x",
        "penumbra a", "penumbra b", "penumbra c", "penumbra d",
        -> R.drawable.ic_blend_add
        "subtract", "inverse_subtract", "additive_subtractive" -> R.drawable.ic_blend_subtract
        "divide", "modulo", "modulo_continuous", "divisive_modulo", "divisive_modulo_continuous",
        "modulo_shift", "modulo_shift_continuous",
        -> R.drawable.ic_blend_divide
        "hue", "hue_hsl", "hue_hsv", "hue_hsi" -> R.drawable.ic_blend_hue
        "saturation", "saturation_hsl", "saturation_hsv", "saturation_hsi",
        "inc_saturation", "dec_saturation", "inc_saturation_hsl", "dec_saturation_hsl",
        "inc_saturation_hsv", "dec_saturation_hsv", "inc_saturation_hsi", "dec_saturation_hsi",
        -> R.drawable.ic_blend_saturation
        "color", "color_hsl", "color_hsv", "color_hsi", "tint" -> R.drawable.ic_blend_color
        "luminize", "lightness", "value", "intensity",
        "inc_luminosity", "dec_luminosity", "inc_lightness", "dec_lightness",
        "inc_value", "dec_value", "inc_intensity", "dec_intensity",
        -> R.drawable.ic_blend_value
        else -> R.drawable.ic_blend_normal
    }
}

@Composable
internal fun BrushBlendModeSelectorRow(
    label: String,
    currentId: String,
    eraserModeActive: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    textMain: Color = Morandi.text,
    textSub: Color = Morandi.subText,
    pillBg: Color = Morandi.panelHi,
    labelFontSize: TextUnit = 13.sp,
) {
    val entry = remember(currentId) { findBrushBlendEntry(currentId) }
    val displayName = entry?.labelRes?.let { stringResource(it) } ?: currentId
    val subtitle = when {
        eraserModeActive -> stringResource(R.string.blend_picker_eraser_override_hint)
        entry?.hintRes != null -> stringResource(entry.hintRes)
        else -> null
    }
    val interaction = remember { MutableInteractionSource() }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .pressScale(interaction, pressedScale = 0.985f)
            .clip(RoundedCornerShape(10.dp))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(vertical = 6.dp, horizontal = 2.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = label,
                color = textMain,
                fontSize = labelFontSize,
                fontWeight = FontWeight.Normal,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(pillBg)
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            ) {
                Icon(
                    painter = painterResource(brushBlendModeIconRes(currentId)),
                    contentDescription = null,
                    tint = if (eraserModeActive) textSub else Morandi.accent,
                    modifier = Modifier.size(14.dp),
                )
                if (entry?.isBrushSpecial == true) {
                    Text(
                        text = stringResource(R.string.blend_badge_brush_special),
                        color = Morandi.accent,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(Morandi.accent.copy(alpha = 0.16f))
                            .padding(horizontal = 5.dp, vertical = 1.dp),
                    )
                }
                Text(
                    text = displayName,
                    color = if (eraserModeActive) textSub else Morandi.accent,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(
                    painter = painterResource(R.drawable.ic_chevron),
                    contentDescription = null,
                    tint = textSub,
                    modifier = Modifier.size(13.dp),
                )
            }
        }
        if (subtitle != null) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = subtitle,
                color = if (eraserModeActive) Morandi.accent.copy(alpha = 0.9f) else textSub,
                fontSize = 11.sp,
                lineHeight = 14.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private sealed interface BlendPickerRow {
    data class CategoryHeader(val id: String, val label: String) : BlendPickerRow
    data class EntryItem(val entry: BrushBlendEntry, val localizedLabel: String) : BlendPickerRow
}

@Composable
internal fun BrushBlendModePickerDialog(
    currentId: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
    vm: PaintViewModel? = null,
) {
    val canonicalCurrent = remember(currentId) { BrushCompositeIds.canonical(currentId) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedCategoryId by remember { mutableStateOf<String?>(null) }

    val categoryLabels = BRUSH_BLEND_CATEGORIES.associate { cat ->
        cat.id to stringResource(cat.labelRes)
    }
    val localizedLabels = BRUSH_BLEND_ALL.associate { entry ->
        entry.id to stringResource(entry.labelRes)
    }

    val trimmedQuery = searchQuery.trim()
    val rows: List<BlendPickerRow> = remember(trimmedQuery, selectedCategoryId, categoryLabels, localizedLabels) {
        if (trimmedQuery.isNotEmpty()) {
            val q = trimmedQuery.lowercase()
            BRUSH_BLEND_ALL.mapNotNull { entry ->
                val label = localizedLabels[entry.id] ?: entry.englishName
                val matched = label.lowercase().contains(q) ||
                    entry.englishName.lowercase().contains(q) ||
                    entry.id.lowercase().contains(q)
                if (matched) BlendPickerRow.EntryItem(entry, label) else null
            }
        } else {
            val cats = if (selectedCategoryId == null) {
                BRUSH_BLEND_CATEGORIES
            } else {
                BRUSH_BLEND_CATEGORIES.filter { it.id == selectedCategoryId }
            }
            buildList {
                for (cat in cats) {
                    if (selectedCategoryId == null) {
                        add(BlendPickerRow.CategoryHeader(cat.id, categoryLabels[cat.id].orEmpty()))
                    }
                    for (entry in cat.entries) {
                        add(BlendPickerRow.EntryItem(entry, localizedLabels[entry.id] ?: entry.englishName))
                    }
                }
            }
        }
    }

    val selectedRowIndex = remember(rows, canonicalCurrent) {
        rows.indexOfFirst { it is BlendPickerRow.EntryItem && it.entry.id == canonicalCurrent }
    }
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) {
        if (selectedRowIndex > 1) {
            listState.scrollToItem((selectedRowIndex - 2).coerceAtLeast(0))
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        if (vm != null) {
            TextInputGuard(vm)
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Morandi.scrim)
                .noRippleClickable(onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.92f)
                    .widthIn(max = 480.dp)
                    .fillMaxHeight(0.82f)
                    .heightIn(max = 680.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Morandi.panel)
                    .clickable(enabled = false) {}
                    .padding(18.dp),
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    // Header
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = stringResource(R.string.blend_picker_title),
                            color = Morandi.text,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        ReIconButton(
                            icon = R.drawable.ic_x,
                            desc = stringResource(R.string.common_close),
                            onTap = onDismiss,
                            size = 30.dp,
                            tint = Morandi.subText,
                            iconSize = 16.dp,
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Search Bar
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(40.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Morandi.panelHi)
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_search),
                            contentDescription = null,
                            tint = Morandi.subText,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(
                            modifier = Modifier.weight(1f),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            if (searchQuery.isEmpty()) {
                                Text(
                                    text = stringResource(R.string.blend_picker_search_hint),
                                    color = Morandi.subText.copy(alpha = 0.65f),
                                    fontSize = 12.5.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            BasicTextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                singleLine = true,
                                textStyle = TextStyle(color = Morandi.text, fontSize = 13.sp),
                                cursorBrush = SolidColor(Morandi.accent),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        if (searchQuery.isNotEmpty()) {
                            Icon(
                                painter = painterResource(R.drawable.ic_x),
                                contentDescription = null,
                                tint = Morandi.subText,
                                modifier = Modifier
                                    .size(16.dp)
                                    .noRippleClickable { searchQuery = "" },
                            )
                        }
                    }

                    if (trimmedQuery.isEmpty()) {
                        Spacer(modifier = Modifier.height(12.dp))

                        // Quick Picks
                        Text(
                            text = stringResource(R.string.blend_picker_quick_label),
                            color = Morandi.accent,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 0.4.sp,
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            contentPadding = PaddingValues(end = 4.dp),
                        ) {
                            items(BRUSH_BLEND_QUICK_IDS) { quickId ->
                                val isSelected = quickId == canonicalCurrent
                                val qEntry = findBrushBlendEntry(quickId)
                                val qLabel = qEntry?.labelRes?.let { stringResource(it) } ?: quickId
                                val chipInteraction = remember(quickId) { MutableInteractionSource() }
                                Box(
                                    modifier = Modifier
                                        .pressScale(chipInteraction, pressedScale = 0.94f)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(if (isSelected) Morandi.accent else Morandi.panelHi)
                                        .clickable(interactionSource = chipInteraction, indication = null) {
                                            onSelect(quickId)
                                            onDismiss()
                                        }
                                        .padding(horizontal = 11.dp, vertical = 6.dp),
                                ) {
                                    Text(
                                        text = qLabel,
                                        color = if (isSelected) Morandi.onAccent else Morandi.text,
                                        fontSize = 12.sp,
                                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // Category Filter Chips
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            contentPadding = PaddingValues(end = 4.dp),
                        ) {
                            item(key = "ALL") {
                                val active = selectedCategoryId == null
                                BlendCategoryChip(
                                    label = stringResource(R.string.blend_picker_all_category),
                                    selected = active,
                                    onClick = { selectedCategoryId = null },
                                )
                            }
                            items(BRUSH_BLEND_CATEGORIES, key = { it.id }) { cat ->
                                val active = selectedCategoryId == cat.id
                                BlendCategoryChip(
                                    label = categoryLabels[cat.id].orEmpty(),
                                    selected = active,
                                    onClick = { selectedCategoryId = if (active) null else cat.id },
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(0.8.dp)
                            .background(Morandi.text.copy(alpha = 0.06f)),
                    )

                    // Blend Mode List
                    if (rows.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = stringResource(R.string.blend_picker_empty),
                                color = Morandi.subText,
                                fontSize = 13.sp,
                            )
                        }
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            contentPadding = PaddingValues(vertical = 6.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            items(
                                items = rows,
                                key = { row ->
                                    when (row) {
                                        is BlendPickerRow.CategoryHeader -> "hdr_${row.id}"
                                        is BlendPickerRow.EntryItem -> "op_${row.entry.id}"
                                    }
                                },
                            ) { row ->
                                when (row) {
                                    is BlendPickerRow.CategoryHeader -> {
                                        Text(
                                            text = row.label,
                                            color = Morandi.subText.copy(alpha = 0.72f),
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            letterSpacing = 0.4.sp,
                                            modifier = Modifier.padding(start = 8.dp, top = 10.dp, bottom = 4.dp),
                                        )
                                    }
                                    is BlendPickerRow.EntryItem -> {
                                        val entry = row.entry
                                        val isSelected = entry.id == canonicalCurrent
                                        val hintText = entry.hintRes?.let { stringResource(it) }
                                        val rowInteraction = remember(entry.id) { MutableInteractionSource() }
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .pressScale(rowInteraction, pressedScale = 0.98f)
                                                .clip(RoundedCornerShape(10.dp))
                                                .background(
                                                    if (isSelected) Morandi.accent.copy(alpha = 0.14f) else Color.Transparent,
                                                )
                                                .clickable(interactionSource = rowInteraction, indication = null) {
                                                    onSelect(entry.id)
                                                    onDismiss()
                                                }
                                                .padding(horizontal = 10.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Icon(
                                                painter = painterResource(brushBlendModeIconRes(entry.id)),
                                                contentDescription = null,
                                                tint = if (isSelected) Morandi.accent else Morandi.icon,
                                                modifier = Modifier.size(16.dp),
                                            )
                                            Spacer(modifier = Modifier.width(10.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                ) {
                                                    Text(
                                                        text = row.localizedLabel,
                                                        color = if (isSelected) Morandi.accent else Morandi.text,
                                                        fontSize = 13.sp,
                                                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                                    )
                                                    if (entry.isBrushSpecial) {
                                                        Text(
                                                            text = stringResource(R.string.blend_badge_brush_special),
                                                            color = Morandi.accent,
                                                            fontSize = 10.sp,
                                                            fontWeight = FontWeight.SemiBold,
                                                            modifier = Modifier
                                                                .clip(RoundedCornerShape(4.dp))
                                                                .background(Morandi.accent.copy(alpha = 0.15f))
                                                                .padding(horizontal = 5.dp, vertical = 1.dp),
                                                        )
                                                    }
                                                }
                                                val meta = if (!row.localizedLabel.equals(entry.englishName, ignoreCase = true)) {
                                                    "${entry.englishName} · ${entry.id}"
                                                } else {
                                                    entry.id
                                                }
                                                Text(
                                                    text = if (hintText != null) "$meta · $hintText" else meta,
                                                    color = Morandi.subText,
                                                    fontSize = 10.5.sp,
                                                    maxLines = 2,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                            }
                                            if (isSelected) {
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Icon(
                                                    painter = painterResource(R.drawable.ic_check),
                                                    contentDescription = null,
                                                    tint = Morandi.accent,
                                                    modifier = Modifier.size(16.dp),
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BlendCategoryChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .pressScale(interaction, pressedScale = 0.94f)
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) Morandi.accent.copy(alpha = 0.16f) else Morandi.panelHi)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 6.dp),
    ) {
        Text(
            text = label,
            color = if (selected) Morandi.accent else Morandi.subText,
            fontSize = 11.5.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}
