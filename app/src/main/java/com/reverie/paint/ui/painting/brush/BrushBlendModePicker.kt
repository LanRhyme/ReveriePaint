/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.brush

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.reverie.paint.R
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.core.brushstore.BrushCompositeIds
import com.reverie.paint.ui.components.ReIconButton
import com.reverie.paint.ui.painting.TextInputGuard
import com.reverie.paint.ui.theme.Morandi
import com.reverie.paint.ui.theme.glassBorder

@Composable
internal fun BrushBlendModeSelectorRow(
    label: String,
    currentId: String,
    eraserModeActive: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    textMain: Color = Morandi.text,
    textSub: Color = Morandi.subText,
) {
    val entry = remember(currentId) { findBrushBlendEntry(currentId) }
    val displayName = entry?.labelRes?.let { stringResource(it) } ?: currentId
    val subtitle = when {
        eraserModeActive -> stringResource(R.string.blend_picker_eraser_override_hint)
        entry?.hintRes != null -> stringResource(entry.hintRes)
        else -> null
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = label,
                color = textMain,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
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
                    .background(Morandi.panelHi.copy(alpha = 0.72f))
                    .border(0.6.dp, Morandi.border.copy(alpha = 0.28f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            ) {
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
                    modifier = Modifier.size(14.dp),
                )
            }
        }
        if (subtitle != null) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = subtitle,
                color = if (eraserModeActive) Morandi.accent.copy(alpha = 0.85f) else textSub,
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
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .widthIn(max = 480.dp)
                .fillMaxHeight(0.82f)
                .heightIn(max = 680.dp)
                .glassBorder(RoundedCornerShape(16.dp)),
            shape = RoundedCornerShape(16.dp),
            color = Morandi.panel,
            border = androidx.compose.foundation.BorderStroke(0.6.dp, Morandi.border.copy(alpha = 0.25f)),
            tonalElevation = 0.dp,
            shadowElevation = 12.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(14.dp),
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = stringResource(R.string.blend_picker_title),
                        color = Morandi.text,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    ReIconButton(
                        icon = R.drawable.ic_x,
                        desc = stringResource(R.string.common_close),
                        onTap = onDismiss,
                        size = 28.dp,
                        tint = Morandi.subText,
                        iconSize = 16.dp,
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Search Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(38.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Morandi.panelHi.copy(alpha = 0.72f))
                        .border(0.6.dp, Morandi.border.copy(alpha = 0.3f), RoundedCornerShape(10.dp))
                        .padding(horizontal = 10.dp),
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
                                fontSize = 12.sp,
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
                                .clickable { searchQuery = "" },
                        )
                    }
                }

                if (trimmedQuery.isEmpty()) {
                    Spacer(modifier = Modifier.height(10.dp))

                    // Quick Picks
                    Text(
                        text = stringResource(R.string.blend_picker_quick_label),
                        color = Morandi.subText,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
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
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        if (isSelected) Morandi.accent else Morandi.panelHi.copy(alpha = 0.65f),
                                    )
                                    .border(
                                        0.6.dp,
                                        if (isSelected) Morandi.accent else Morandi.border.copy(alpha = 0.25f),
                                        RoundedCornerShape(8.dp),
                                    )
                                    .clickable {
                                        onSelect(quickId)
                                        onDismiss()
                                    }
                                    .padding(horizontal = 10.dp, vertical = 5.dp),
                            ) {
                                Text(
                                    text = qLabel,
                                    color = if (isSelected) Color.White else Morandi.text,
                                    fontSize = 11.5.sp,
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

                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider(color = Morandi.border.copy(alpha = 0.2f), thickness = 0.6.dp)

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
                                        color = Morandi.accent,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.padding(start = 6.dp, top = 10.dp, bottom = 4.dp),
                                    )
                                }
                                is BlendPickerRow.EntryItem -> {
                                    val entry = row.entry
                                    val isSelected = entry.id == canonicalCurrent
                                    val hintText = entry.hintRes?.let { stringResource(it) }
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(9.dp))
                                            .background(
                                                if (isSelected) Morandi.accent.copy(alpha = 0.16f) else Color.Transparent,
                                            )
                                            .clickable {
                                                onSelect(entry.id)
                                                onDismiss()
                                            }
                                            .padding(horizontal = 10.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
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

@Composable
private fun BlendCategoryChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) Morandi.accent.copy(alpha = 0.2f) else Color.Transparent)
            .border(
                0.6.dp,
                if (selected) Morandi.accent else Morandi.border.copy(alpha = 0.25f),
                RoundedCornerShape(14.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            text = label,
            color = if (selected) Morandi.accent else Morandi.subText,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}
