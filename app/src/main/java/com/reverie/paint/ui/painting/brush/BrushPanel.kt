/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.brush

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import com.reverie.paint.ui.components.ReTextButton
import com.reverie.paint.R
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeChild
import com.reverie.paint.ui.theme.Glass
import com.reverie.paint.ui.theme.glassBorder
import androidx.compose.ui.unit.sp
import com.reverie.paint.core.*
import com.reverie.paint.ui.theme.Morandi
import com.reverie.paint.ui.theme.systemHoverIcon
import com.reverie.paint.ui.components.noRippleClickable
import com.reverie.paint.ui.components.ReSlider
import com.reverie.paint.ui.components.DragPillHandle
import com.reverie.paint.ui.components.PanelCloseButton
import com.reverie.paint.ui.components.PinButton
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts

/**
 * Brush library panel with Krita's real bundled presets (.kpp).
 * Main view = category rail + preset list. Tapping the already-selected
 * preset opens the second-level property page (size/opacity/flow).
 */

private sealed interface BrushView {
    data object List : BrushView
    data class Detail(val index: Int) : BrushView
}

@Composable
fun BrushPanel(
    vm: PaintViewModel,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    opacity: Float = 0.95f,
    hazeState: HazeState? = null,
) {
    val context = LocalContext.current
    val rawCategories = remember(vm.brushPresets, vm.customBrushGroups) {
        listOf("全部", "常用", "最近") +
            vm.brushPresets.map { it.group }.filter { it.isNotBlank() && it !in listOf("全部", "常用", "最近") }.distinct() +
            vm.customBrushGroups.filter { g -> g !in listOf("全部", "常用", "最近") && vm.brushPresets.none { it.group == g } }
    }
    val categories = remember(rawCategories, vm.categoryOrder) {
        val pinned = listOf("全部", "常用", "最近")
        val restRaw = rawCategories.filter { !pinned.contains(it) }
        if (vm.categoryOrder.isEmpty()) {
            (pinned + restRaw).distinct()
        } else {
            val ordered = vm.categoryOrder.filter { restRaw.contains(it) }
            val remaining = restRaw.filter { !vm.categoryOrder.contains(it) }
            (pinned + ordered + remaining).distinct()
        }
    }
    var selectedCategory by remember { mutableStateOf(vm.brushPanelSelectedCategory) }
    var showNewGroupDialog by remember { mutableStateOf(false) }
    var showNewBrushDialog by remember { mutableStateOf(false) }
    var renamePresetName by remember { mutableStateOf<String?>(null) }
    var editingCategoryName by remember { mutableStateOf<String?>(null) }
    var groupPendingDelete by remember { mutableStateOf<String?>(null) }
    var categoryMenuTarget by remember { mutableStateOf<String?>(null) }
    var renameCategoryTarget by remember { mutableStateOf<String?>(null) }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }
    var movePresetName by remember { mutableStateOf<String?>(null) }
    var reorderPresetName by remember { mutableStateOf<String?>(null) }

    val importBrushLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            vm.importBrushFromUri(uri)
        }
    }

    var view by remember {
        mutableStateOf<BrushView>(
            vm.brushPanelDetailIndex?.let { BrushView.Detail(it) } ?: BrushView.List
        )
    }

    val initialPresetScroll = remember(vm.brushPanelSelectedCategory) {
        vm.getCategoryPresetScroll(vm.brushPanelSelectedCategory)
    }

    val categoryScrollState = rememberLazyListState(
        initialFirstVisibleItemIndex = vm.brushCategoryScrollIndex,
        initialFirstVisibleItemScrollOffset = vm.brushCategoryScrollOffset
    )

    val presetScrollState = rememberLazyListState(
        initialFirstVisibleItemIndex = initialPresetScroll.first,
        initialFirstVisibleItemScrollOffset = initialPresetScroll.second
    )

    val presetGridScrollState = rememberLazyGridState(
        initialFirstVisibleItemIndex = initialPresetScroll.first,
        initialFirstVisibleItemScrollOffset = initialPresetScroll.second
    )

    // Preload thumbnails in background to eliminate UI thread decoding during scroll
    LaunchedEffect(vm.brushPresets) {
        withContext(Dispatchers.IO) {
            vm.brushPresets.forEach { preset ->
                BrushThumbCache.preload(preset.name, preset.thumbBytes)
            }
        }
    }

    // Sync scroll positions when scrolling is idle, avoiding continuous work while scrolling
    LaunchedEffect(categoryScrollState) {
        androidx.compose.runtime.snapshotFlow { categoryScrollState.isScrollInProgress }
            .collect { inProgress ->
                if (!inProgress) {
                    vm.brushCategoryScrollIndex = categoryScrollState.firstVisibleItemIndex
                    vm.brushCategoryScrollOffset = categoryScrollState.firstVisibleItemScrollOffset
                }
            }
    }

    LaunchedEffect(presetScrollState, selectedCategory) {
        androidx.compose.runtime.snapshotFlow { presetScrollState.isScrollInProgress }
            .collect { inProgress ->
                if (!inProgress && !vm.brushPanelGridView) {
                    vm.saveCategoryPresetScroll(
                        selectedCategory,
                        presetScrollState.firstVisibleItemIndex,
                        presetScrollState.firstVisibleItemScrollOffset,
                        persist = false
                    )
                }
            }
    }

    LaunchedEffect(presetGridScrollState, selectedCategory) {
        androidx.compose.runtime.snapshotFlow { presetGridScrollState.isScrollInProgress }
            .collect { inProgress ->
                if (!inProgress && vm.brushPanelGridView) {
                    vm.saveCategoryPresetScroll(
                        selectedCategory,
                        presetGridScrollState.firstVisibleItemIndex,
                        presetGridScrollState.firstVisibleItemScrollOffset,
                        persist = false
                    )
                }
            }
    }

    // Restore category-specific scroll offset when switching category
    var isInitialCategoryLaunch by remember { mutableStateOf(true) }
    LaunchedEffect(selectedCategory) {
        if (isInitialCategoryLaunch) {
            isInitialCategoryLaunch = false
        } else {
            val (savedIdx, savedOffset) = vm.getCategoryPresetScroll(selectedCategory)
            presetScrollState.scrollToItem(savedIdx, savedOffset)
            presetGridScrollState.scrollToItem(savedIdx, savedOffset)
        }
    }

    // Save state on dispose / change
    DisposableEffect(selectedCategory, view, vm.brushPanelGridView) {
        onDispose {
            vm.brushPanelSelectedCategory = selectedCategory
            vm.brushCategoryScrollIndex = categoryScrollState.firstVisibleItemIndex
            vm.brushCategoryScrollOffset = categoryScrollState.firstVisibleItemScrollOffset
            val (curIdx, curOffset) = if (vm.brushPanelGridView) {
                presetGridScrollState.firstVisibleItemIndex to presetGridScrollState.firstVisibleItemScrollOffset
            } else {
                presetScrollState.firstVisibleItemIndex to presetScrollState.firstVisibleItemScrollOffset
            }
            vm.saveCategoryPresetScroll(
                selectedCategory,
                curIdx,
                curOffset,
                persist = true
            )
            vm.brushPanelDetailIndex = (view as? BrushView.Detail)?.index
            vm.persistBrushPanelState()
        }
    }

    val panelShape = RoundedCornerShape(12.dp)
    val density = LocalDensity.current
    val baseStartOffsetPx = remember(density) { with(density) { 48.dp.roundToPx() } }

    val cardContent: @Composable (Modifier) -> Unit = { cardModifier ->
        Column(
            modifier = cardModifier
                .systemHoverIcon(context)
                .width(320.dp)
                .fillMaxHeight(0.75f)
                .shadow(16.dp, panelShape, spotColor = Color.Black.copy(alpha = 0.5f))
                .clip(panelShape)
                .then(
                    if (vm.blurBackground && hazeState != null) {
                        Modifier.hazeChild(
                            state = hazeState,
                            style = Glass.barStyle(if (opacity >= 0.99f) 0.92f else opacity),
                        )
                    } else {
                        Modifier.background(Morandi.panel.copy(alpha = opacity))
                    }
                )
                .glassBorder(panelShape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {}
                )
        ) {
            // Header Bar: Drag Handle Pill in center, Pin Button & Close Button on right (only when enabled)
            if (vm.panelPinningEnabled) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 2.dp),
                ) {
                    DragPillHandle(
                        onDrag = { dragAmount -> vm.brushPanelOffset += dragAmount },
                        modifier = Modifier.align(Alignment.Center)
                    )
                    Row(
                        modifier = Modifier.align(Alignment.CenterEnd),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        PinButton(
                            isPinned = vm.isBrushPanelPinned,
                            onClick = {
                                vm.isBrushPanelPinned = !vm.isBrushPanelPinned
                                if (!vm.isBrushPanelPinned) {
                                    vm.brushPanelOffset = androidx.compose.ui.geometry.Offset.Zero
                                }
                                Toast.makeText(
                                    context,
                                    if (vm.isBrushPanelPinned) context.getString(R.string.brush_pin_hint) else context.getString(R.string.brush_unpin_hint),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        )
                        if (vm.isBrushPanelPinned) {
                            PanelCloseButton(onClose = onClose)
                        }
                    }
                }
            }

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                AnimatedContent(
                    targetState = view,
                transitionSpec = {
                    if (targetState is BrushView.Detail && initialState is BrushView.List) {
                        (slideInHorizontally { it } + fadeIn(tween(180)))
                            .togetherWith(slideOutHorizontally { -it / 3 } + fadeOut(tween(120)))
                    } else if (targetState is BrushView.List && initialState is BrushView.Detail) {
                        (slideInHorizontally { -it / 3 } + fadeIn(tween(180)))
                            .togetherWith(slideOutHorizontally { it } + fadeOut(tween(120)))
                    } else {
                        fadeIn() togetherWith fadeOut()
                    }
                },
                label = "brushPages"
            ) { v ->
                when (v) {
                    is BrushView.List -> {
                        Column(Modifier.fillMaxSize()) {
                            Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                                // Left categories
                                LazyColumn(
                                    state = categoryScrollState,
                                    modifier = Modifier
                                        .width(88.dp)
                                        .fillMaxHeight()
                                        .background(Morandi.panelHi.copy(alpha = 0.35f))
                                ) {
                                    items(categories, key = { it }) { cat ->
                                        val sel = cat == selectedCategory
                                        val iconRes = when (cat) {
                                            "常用" -> R.drawable.ic_star
                                            "最近" -> R.drawable.ic_clock
                                            else -> null
                                        }
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 5.dp, vertical = 2.dp)
                                                .height(38.dp)
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(if (sel) Morandi.accent.copy(alpha = 0.16f) else Color.Transparent)
                                                .combinedClickable(
                                                    onClick = {
                                                        selectedCategory = cat
                                                        vm.updateBrushPanelCategory(cat)
                                                    },
                                                    onLongClick = { categoryMenuTarget = cat },
                                                ),
                                            contentAlignment = Alignment.CenterStart
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(start = 12.dp, end = 6.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                if (iconRes != null) {
                                                    Icon(
                                                        painter = painterResource(iconRes),
                                                        contentDescription = null,
                                                        tint = if (sel) Morandi.accent else Morandi.subText,
                                                        modifier = Modifier.size(14.dp)
                                                    )
                                                    Spacer(Modifier.width(6.dp))
                                                }
                                                val catDisplayName = brushCategoryDisplayName(cat)
                                                Text(
                                                    text = catDisplayName,
                                                    color = if (sel) Morandi.accent else Morandi.subText,
                                                    fontSize = 13.sp,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                        }
                                    }
                                }

                                // Right preset list
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                ) {
                                    val filtered = remember(selectedCategory, vm.brushPresets, vm.favoriteBrushNames, vm.recentBrushNames) {
                                        when (selectedCategory) {
                                            "全部" -> vm.brushPresets
                                            "常用" -> vm.brushPresets.filter { vm.isFavoriteBrush(it.name) }
                                            "最近" -> vm.recentBrushNames.mapNotNull { name ->
                                                vm.brushPresets.firstOrNull { it.name == name }
                                            }
                                            else -> vm.brushPresets.filter { it.group == selectedCategory }
                                        }
                                    }

                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(52.dp)
                                            .padding(horizontal = 14.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(stringResource(R.string.brush_library_title), color = Morandi.text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                        Spacer(Modifier.weight(1f))
                                        Text(
                                            stringResource(R.string.brush_presets_count, filtered.size),
                                            color = Morandi.subText,
                                            fontSize = 11.sp,
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Box(
                                            modifier = Modifier
                                                .size(30.dp)
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(if (vm.brushPanelGridView) Morandi.panelHi else Color.Transparent)
                                                .clickable { vm.toggleBrushPanelGridView() },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                painter = painterResource(if (vm.brushPanelGridView) R.drawable.ic_menu else R.drawable.ic_grid),
                                                contentDescription = stringResource(if (vm.brushPanelGridView) R.string.brush_switch_to_list else R.string.brush_switch_to_grid),
                                                tint = if (vm.brushPanelGridView) Morandi.accent else Morandi.subText,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }

                                    if (filtered.isEmpty()) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .weight(1f)
                                                .padding(16.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Column(
                                                horizontalAlignment = Alignment.CenterHorizontally,
                                                verticalArrangement = Arrangement.Center
                                            ) {
                                                Icon(
                                                    painter = painterResource(
                                                        when (selectedCategory) {
                                                            "常用" -> R.drawable.ic_star
                                                            "最近" -> R.drawable.ic_clock
                                                            else -> R.drawable.ic_brush
                                                        }
                                                    ),
                                                    contentDescription = null,
                                                    tint = Morandi.subText.copy(alpha = 0.35f),
                                                    modifier = Modifier.size(36.dp)
                                                )
                                                Spacer(Modifier.height(8.dp))
                                                Text(
                                                    when (selectedCategory) {
                                                        "常用" -> stringResource(R.string.brush_empty_fav_title)
                                                        "最近" -> stringResource(R.string.brush_empty_recent_title)
                                                        else -> stringResource(R.string.brush_empty_category_title)
                                                    },
                                                    color = Morandi.subText,
                                                    fontSize = 13.sp,
                                                )
                                                Spacer(Modifier.height(2.dp))
                                                Text(
                                                    when (selectedCategory) {
                                                        "常用" -> stringResource(R.string.brush_empty_fav_desc)
                                                        "最近" -> stringResource(R.string.brush_empty_recent_desc)
                                                        else -> stringResource(R.string.brush_empty_category_desc)
                                                    },
                                                    color = Morandi.subText.copy(alpha = 0.6f),
                                                    fontSize = 11.sp,
                                                )
                                            }
                                        }
                                    } else {
                                        AnimatedContent(
                                            targetState = vm.brushPanelGridView,
                                            transitionSpec = {
                                                (fadeIn(animationSpec = tween(180)) + scaleIn(initialScale = 0.96f, animationSpec = tween(180)))
                                                    .togetherWith(fadeOut(animationSpec = tween(140)))
                                            },
                                            label = "gridListToggleAnim",
                                            modifier = Modifier.fillMaxWidth().weight(1f)
                                        ) { isGrid ->
                                            if (isGrid) {
                                                LazyVerticalGrid(
                                                    columns = GridCells.Fixed(3),
                                                    state = presetGridScrollState,
                                                    modifier = Modifier
                                                        .fillMaxSize()
                                                        .padding(horizontal = 8.dp),
                                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                                ) {
                                                    items(filtered, key = { it.name }, contentType = { "preset_grid" }) { preset ->
                                                        val isSelected = preset.index == vm.brushPresetIndex
                                                        PresetGridCard(
                                                            preset = preset,
                                                            isSelected = isSelected,
                                                            isModified = vm.isBrushModified(preset.name),
                                                            onClick = {
                                                                if (isSelected) view = BrushView.Detail(preset.index)
                                                                else vm.selectBrushPreset(preset.index)
                                                            },
                                                            onLongClick = {
                                                                reorderPresetName = preset.name
                                                            },
                                                            modifier = Modifier.animateItem()
                                                        )
                                                    }
                                                }
                                            } else {
                                                LazyColumn(
                                                    state = presetScrollState,
                                                    modifier = Modifier
                                                        .fillMaxSize()
                                                        .padding(horizontal = 12.dp),
                                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                                ) {
                                                    items(filtered, key = { it.name }, contentType = { "preset_list" }) { preset ->
                                                        val isSelected = preset.index == vm.brushPresetIndex
                                                        val isFav = vm.isFavoriteBrush(preset.name)
                                                        PresetListRow(
                                                            preset = preset,
                                                            isSelected = isSelected,
                                                            isFav = isFav,
                                                            isModified = vm.isBrushModified(preset.name),
                                                            onClick = {
                                                                if (isSelected) view = BrushView.Detail(preset.index)
                                                                else vm.selectBrushPreset(preset.index)
                                                            },
                                                            onLongClick = {
                                                                reorderPresetName = preset.name
                                                            },
                                                            onToggleFav = {
                                                                vm.toggleFavoriteBrush(preset.name)
                                                            },
                                                            modifier = Modifier.animateItem()
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            // Bottom toolbar
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .background(Morandi.panel.copy(alpha = opacity))
                                    .padding(horizontal = 14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(Morandi.panelHi)
                                        .clickable { showNewBrushDialog = true }
                                        .padding(horizontal = 8.dp, vertical = 5.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(painterResource(R.drawable.ic_plus), contentDescription = null, tint = Morandi.text, modifier = Modifier.size(14.dp))
                                    Text(stringResource(R.string.brush_action_new), color = Morandi.text, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                                }

                                Spacer(Modifier.width(8.dp))

                                Row(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(Morandi.panelHi)
                                        .clickable { importBrushLauncher.launch(arrayOf("*/*")) }
                                        .padding(horizontal = 8.dp, vertical = 5.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(painterResource(R.drawable.ic_export_tab), contentDescription = null, tint = Morandi.text, modifier = Modifier.size(14.dp))
                                    Text(stringResource(R.string.brush_action_import), color = Morandi.text, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                                }

                                Spacer(Modifier.weight(1f))

                                Icon(
                                    painterResource(R.drawable.ic_folder_plus),
                                    contentDescription = stringResource(R.string.brush_action_new_group),
                                    tint = Morandi.icon,
                                    modifier = Modifier.size(18.dp).clickable { showNewGroupDialog = true }
                                )
                            }
                        }
                    }
                    is BrushView.Detail -> {
                        BrushPropertyPage(
                            vm = vm,
                            presetIndex = v.index,
                            onBack = { view = BrushView.List },
                            onOpenStudio = {
                                vm.selectBrushPreset(v.index)
                                vm.brushStudioOpen = true
                                onClose()
                            },
                        )
                    }
                }
            }
        }
    }
}

    if (vm.panelPinningEnabled && vm.isBrushPanelPinned) {
        cardContent(modifier)
    } else {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(Color.Transparent)
                .noRippleClickable(onClose)
                .systemHoverIcon(context),
        ) {
            val offsetX = if (vm.panelPinningEnabled) vm.brushPanelOffset.x else 0f
            val offsetY = if (vm.panelPinningEnabled) vm.brushPanelOffset.y else 0f
            cardContent(
                Modifier
                    .align(Alignment.CenterStart)
                    .offset {
                        IntOffset(
                            (baseStartOffsetPx + offsetX).roundToInt(),
                            offsetY.roundToInt(),
                        )
                    }
            )
        }
    }

    // ---- dialogs -----------------------------------------------------
    if (showNewBrushDialog) {
        NewBrushPresetDialog(
            groups = categories.filter { it !in listOf("全部", "常用", "最近") },
            onDismiss = { showNewBrushDialog = false },
            onCreate = { name, group ->
                vm.createNewBrushPreset(name = name, group = group)
                showNewBrushDialog = false
            },
        )
    }
    if (renamePresetName != null) {
        val rn = renamePresetName!!
        val pIdx = vm.brushPresets.firstOrNull { it.name == rn }?.index ?: -1
        RenameBrushPresetDialog(
            initialName = rn,
            onDismiss = { renamePresetName = null },
            onRename = { newName ->
                if (pIdx >= 0) {
                    vm.renameBrushPreset(pIdx, newName)
                }
                renamePresetName = null
            },
        )
    }
    if (showNewGroupDialog) {
        NewBrushGroupDialog(
            existing = categories.filter { it !in listOf("全部", "常用", "最近") },
            onDismiss = { showNewGroupDialog = false },
            onCreate = { name ->
                if (vm.createBrushGroup(name)) {
                    selectedCategory = name
                }
                showNewGroupDialog = false
            },
        )
    }
    if (categoryMenuTarget != null) {
        val cat = categoryMenuTarget!!
        val isSpecialPinned = cat in setOf("全部", "常用", "最近")
        val isBuiltIn = isSpecialPinned || vm.isBuiltInGroup(cat)
        val catIdx = categories.indexOf(cat)
        val context = LocalContext.current
        CategoryMenuDialog(
            categoryName = cat,
            isBuiltIn = isBuiltIn,
            canMoveUp = !isSpecialPinned && catIdx > 3,
            canMoveDown = !isSpecialPinned && catIdx >= 3 && catIdx < categories.size - 1,
            onDismiss = { categoryMenuTarget = null },
            onMoveUp = { vm.moveCategoryUp(cat, categories) },
            onMoveDown = { vm.moveCategoryDown(cat, categories) },
            onExportGroup = { vm.exportBrushGroup(context, cat) },
            onRename = { renameCategoryTarget = cat },
            onDelete = { groupPendingDelete = cat },
        )
    }
    if (renameCategoryTarget != null) {
        val cat = renameCategoryTarget!!
        RenameBrushGroupDialog(
            initialName = cat,
            onDismiss = { renameCategoryTarget = null },
            onRename = { newName ->
                vm.renameBrushGroup(cat, newName)
                if (selectedCategory == cat) selectedCategory = newName
                renameCategoryTarget = null
            },
        )
    }
    if (reorderPresetName != null) {
        val rp = reorderPresetName!!
        val preset = vm.brushPresets.firstOrNull { it.name == rp }
        val pIdx = preset?.index ?: -1
        val isBuiltIn = preset?.isBuiltIn == true
        val isFav = vm.isFavoriteBrush(rp)
        val isModified = vm.isBrushModified(rp)
        val context = LocalContext.current
        ReorderBrushMenu(
            presetName = rp,
            isBuiltIn = isBuiltIn,
            isFavorite = isFav,
            isModified = isModified,
            onDismiss = { reorderPresetName = null },
            onToggleFavorite = { vm.toggleFavoriteBrush(rp) },
            onShare = { vm.shareBrushPreset(context, rp) },
            onReset = { vm.resetBrushPresetToDefault(rp) },
            onUp = { vm.moveBrushUp(rp) },
            onDown = { vm.moveBrushDown(rp) },
            onMoveGroup = { movePresetName = rp },
            onDuplicate = {
                if (pIdx >= 0) vm.duplicateBrushPreset(pIdx)
            },
            onRename = { renamePresetName = rp },
            onDelete = {
                if (pIdx >= 0) vm.deleteBrushPreset(pIdx)
            },
        )
    }
    if (groupPendingDelete != null) {
        val grp = groupPendingDelete!!
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { groupPendingDelete = null },
            title = { Text(stringResource(R.string.brush_delete_group_title), color = Morandi.text, fontSize = 15.sp) },
            text = { Text(stringResource(R.string.brush_delete_group_msg, brushCategoryDisplayName(grp)), color = Morandi.subText, fontSize = 13.sp) },
            confirmButton = {
                ReTextButton(
                    stringResource(R.string.common_delete),
                    onClick = {
                    vm.deleteBrushGroup(grp)
                    if (selectedCategory == grp) selectedCategory = "全部"
                    groupPendingDelete = null
                },
                    textColor = Color(0xFFC86464),
                )
            },
            dismissButton = {
                ReTextButton(stringResource(R.string.common_cancel), { groupPendingDelete = null }, textColor = Morandi.subText)
            },
            containerColor = Morandi.panelHi,
        )
    }
    if (movePresetName != null) {
        MoveBrushGroupDialog(
            presetName = movePresetName!!,
            groups = categories.filter { it !in listOf("全部", "常用", "最近") },
            onDismiss = { movePresetName = null },
            onMove = { g ->
                vm.moveBrushToGroup(movePresetName!!, g)
                selectedCategory = g
                movePresetName = null
            },
        )
    }
}

@Composable
private fun PresetGridCard(
    preset: BrushPresetInfo,
    isSelected: Boolean,
    isModified: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cellBg = if (isSelected) Morandi.accent.copy(alpha = 0.18f) else Color.Transparent

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(cellBg)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(6.dp))
                .background(Morandi.panel.copy(alpha = 0.5f)),
            contentAlignment = Alignment.Center
        ) {
            val bmp = rememberPresetThumb(preset.name, preset.thumbBytes)
            if (bmp != null) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = preset.name,
                    modifier = Modifier
                        .fillMaxSize()
                        .then(
                            if (isSelected) {
                                Modifier.graphicsLayer {
                                    scaleX = 1.04f
                                    scaleY = 1.04f
                                }
                            } else Modifier
                        )
                        .clip(RoundedCornerShape(6.dp)),
                )
            }
            if (isModified) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(Morandi.accent)
                )
            }
        }
        Spacer(Modifier.height(3.dp))
        Text(
            preset.name,
            color = if (isSelected) Morandi.text else Morandi.subText,
            fontSize = 10.sp,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 1.dp)
        )
    }
}

@Composable
private fun PresetListRow(
    preset: BrushPresetInfo,
    isSelected: Boolean,
    isFav: Boolean,
    isModified: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onToggleFav: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cellBg = if (isSelected) Morandi.accent.copy(alpha = 0.16f) else Color.Transparent

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(50.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(cellBg)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .padding(start = 8.dp, end = 4.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(38.dp),
            contentAlignment = Alignment.Center
        ) {
            val bmp = rememberPresetThumb(preset.name, preset.thumbBytes)
            if (bmp != null) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = preset.name,
                    modifier = Modifier
                        .fillMaxSize()
                        .then(
                            if (isSelected) {
                                Modifier.graphicsLayer {
                                    scaleX = 1.06f
                                    scaleY = 1.06f
                                }
                            } else Modifier
                        )
                        .clip(RoundedCornerShape(7.dp))
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(7.dp))
                        .background(Morandi.panelHi)
                )
            }
            if (isModified) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(3.dp)
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(Morandi.accent)
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                preset.name,
                color = if (isSelected) Morandi.text else Morandi.subText,
                fontSize = 12.sp,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (isSelected) {
                Text(stringResource(R.string.brush_in_use_hint), color = Morandi.subText, fontSize = 10.sp)
            }
        }
        // Star button
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .clickable(onClick = onToggleFav),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(if (isFav) R.drawable.ic_star_filled else R.drawable.ic_star),
                contentDescription = stringResource(if (isFav) R.string.brush_fav_remove else R.string.brush_fav_add),
                tint = if (isFav) Morandi.accent else Morandi.subText.copy(alpha = 0.35f),
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

/** Long-press menu for categories: move up/down, rename, delete. */
@Composable
private fun CategoryMenuDialog(
    categoryName: String,
    isBuiltIn: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onDismiss: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onExportGroup: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val displayCatName = brushCategoryDisplayName(categoryName)
    val moveUpText = stringResource(R.string.brush_group_move_up)
    val moveDownText = stringResource(R.string.brush_group_move_down)
    val exportGroupText = stringResource(R.string.brush_export_group_action)
    val renameText = stringResource(R.string.brush_group_rename)
    val deleteText = stringResource(R.string.brush_group_delete)
    val builtinTagText = stringResource(R.string.brush_group_builtin_tag)

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.brush_group_header, displayCatName), color = Morandi.text, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 15.sp) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                val menuItems = mutableListOf<Pair<String, () -> Unit>>()
                if (canMoveUp) {
                    menuItems.add(moveUpText to onMoveUp)
                }
                if (canMoveDown) {
                    menuItems.add(moveDownText to onMoveDown)
                }
                menuItems.add(exportGroupText to onExportGroup)
                if (!isBuiltIn) {
                    menuItems.add(renameText to onRename)
                    menuItems.add(deleteText to onDelete)
                } else {
                    menuItems.add(builtinTagText to {})
                }
                menuItems.forEach { (label, act) ->
                    val isDelete = label == deleteText
                    val isHint = label == builtinTagText
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .clickable(enabled = !isHint) {
                                onDismiss()
                                act()
                            }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                    ) {
                        Text(
                            label,
                            color = if (isDelete) Color(0xFFC86464) else if (isHint) Morandi.subText.copy(alpha = 0.6f) else Morandi.text,
                            fontSize = 13.sp,
                            fontWeight = if (isDelete) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            ReTextButton(stringResource(R.string.common_cancel), onDismiss, textColor = Morandi.subText)
        },
        containerColor = Morandi.panelHi,
    )
}

/** Dialog to rename a custom brush category. */
@Composable
private fun RenameBrushGroupDialog(
    initialName: String,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.brush_group_rename_title), color = Morandi.text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.brush_group_rename_hint), color = Morandi.subText, fontSize = 12.sp)
                androidx.compose.foundation.text.BasicTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(color = Morandi.text, fontSize = 14.sp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Morandi.panel, RoundedCornerShape(6.dp))
                        .padding(10.dp),
                )
            }
        },
        confirmButton = {
            ReTextButton(
                stringResource(R.string.common_confirm),
                onClick = { onRename(name.trim()) },
                enabled = name.isNotBlank() && name.trim() != initialName,
                textColor = Morandi.accent,
            )
        },
        dismissButton = {
            ReTextButton(stringResource(R.string.common_cancel), onDismiss, textColor = Morandi.subText)
        },
        containerColor = Morandi.panelHi,
    )
}

/** Long-press menu: move up/down, duplicate, rename, delete or move to a group. */
@Composable
private fun ReorderBrushMenu(
    presetName: String,
    isBuiltIn: Boolean,
    isFavorite: Boolean,
    isModified: Boolean = false,
    onDismiss: () -> Unit,
    onToggleFavorite: () -> Unit,
    onShare: () -> Unit,
    onReset: () -> Unit,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onMoveGroup: () -> Unit,
    onDuplicate: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val favRemoveText = stringResource(R.string.brush_fav_remove)
    val favAddText = stringResource(R.string.brush_fav_add)
    val duplicateText = stringResource(R.string.brush_studio_duplicate_brush)
    val shareText = stringResource(R.string.brush_share_action)
    val resetText = stringResource(R.string.brush_reset_action)
    val renameText = stringResource(R.string.common_rename)
    val moveUpText = stringResource(R.string.brush_move_up)
    val moveDownText = stringResource(R.string.brush_move_down)
    val moveToGroupText = stringResource(R.string.brush_move_to_group)
    val deleteBrushText = stringResource(R.string.brush_delete_brush_item)

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(presetName, color = Morandi.text, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 15.sp) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                val menuItems = mutableListOf(
                    (if (isFavorite) favRemoveText else favAddText) to onToggleFavorite,
                    duplicateText to onDuplicate,
                    shareText to onShare,
                )
                if (isModified) {
                    menuItems.add(resetText to onReset)
                }
                if (!isBuiltIn) {
                    menuItems.add(renameText to onRename)
                }
                menuItems.add(moveUpText to onUp)
                menuItems.add(moveDownText to onDown)
                menuItems.add(moveToGroupText to onMoveGroup)
                if (!isBuiltIn) {
                    menuItems.add(deleteBrushText to onDelete)
                }
                menuItems.forEach { (label, act) ->
                    val isDelete = label == deleteBrushText
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .clickable {
                                onDismiss()
                                act()
                            }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                    ) {
                        Text(
                            label,
                            color = if (isDelete) Color(0xFFC86464) else Morandi.text,
                            fontSize = 13.sp,
                            fontWeight = if (isDelete) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            ReTextButton(stringResource(R.string.common_cancel), onDismiss, textColor = Morandi.subText)
        },
        containerColor = Morandi.panelHi,
    )
}

/** Dialog to create a new custom brush preset. */
@Composable
private fun NewBrushPresetDialog(
    groups: List<String>,
    onDismiss: () -> Unit,
    onCreate: (String, String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    val defaultCustomGroupName = stringResource(R.string.brush_studio_custom_brush)
    var selectedGroup by remember { mutableStateOf(groups.firstOrNull() ?: defaultCustomGroupName) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.brush_new_dialog_title), color = Morandi.text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.brush_new_dialog_hint), color = Morandi.subText, fontSize = 12.sp)
                androidx.compose.foundation.text.BasicTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(color = Morandi.text, fontSize = 14.sp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Morandi.panel, RoundedCornerShape(6.dp))
                        .padding(10.dp),
                )
                Text(stringResource(R.string.brush_new_base_template), color = Morandi.subText.copy(alpha = 0.7f), fontSize = 11.sp)
            }
        },
        confirmButton = {
            ReTextButton(stringResource(R.string.common_create), { onCreate(name.trim(), selectedGroup) }, enabled = name.isNotBlank(), textColor = Morandi.accent)
        },
        dismissButton = {
            ReTextButton(stringResource(R.string.common_cancel), onDismiss, textColor = Morandi.subText)
        },
        containerColor = Morandi.panelHi,
    )
}

/** Dialog to rename a brush preset. */
@Composable
private fun RenameBrushPresetDialog(
    initialName: String,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.brush_studio_rename_dialog_title), color = Morandi.text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.brush_studio_rename_dialog_hint), color = Morandi.subText, fontSize = 12.sp)
                androidx.compose.foundation.text.BasicTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(color = Morandi.text, fontSize = 14.sp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Morandi.panel, RoundedCornerShape(6.dp))
                        .padding(10.dp),
                )
            }
        },
        confirmButton = {
            ReTextButton(stringResource(R.string.common_save), { onRename(name.trim()) }, enabled = name.isNotBlank(), textColor = Morandi.accent)
        },
        dismissButton = {
            ReTextButton(stringResource(R.string.common_cancel), onDismiss, textColor = Morandi.subText)
        },
        containerColor = Morandi.panelHi,
    )
}

/** Dialog to create a new user brush group. */
@Composable
private fun NewBrushGroupDialog(
    existing: List<String>,
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.brush_new_group_title), color = Morandi.text) },
        text = {
            Column {
                Text(stringResource(R.string.brush_new_group_hint), color = Morandi.subText, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                androidx.compose.foundation.text.BasicTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(color = Morandi.text, fontSize = 15.sp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Morandi.panel, RoundedCornerShape(8.dp))
                        .padding(12.dp),
                )
                if (existing.contains(name.trim())) {
                    Text(stringResource(R.string.brush_group_already_exists), color = Color(0xFFB05552), fontSize = 11.sp)
                }
            }
        },
        confirmButton = {
            ReTextButton(
                stringResource(R.string.common_create),
                onClick = { onCreate(name.trim()) },
                enabled = name.isNotBlank() && !existing.contains(name.trim()),
                textColor = Morandi.accent,
            )
        },
        dismissButton = {
            ReTextButton(stringResource(R.string.common_cancel), onDismiss, textColor = Morandi.subText)
        },
        containerColor = Morandi.panelHi,
    )
}

/** Dialog to move a preset into a group. */
@Composable
private fun MoveBrushGroupDialog(
    presetName: String,
    groups: List<String>,
    onDismiss: () -> Unit,
    onMove: (String) -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.brush_move_to_group_title), color = Morandi.text) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text(presetName, color = Morandi.subText, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.heightIn(max = 280.dp)) {
                    items(groups) { g ->
                        val groupDisplayName = brushCategoryDisplayName(g)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { onMove(g) }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                        ) {
                            Text(groupDisplayName, color = Morandi.text, fontSize = 14.sp)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            ReTextButton(stringResource(R.string.common_cancel), onDismiss, textColor = Morandi.subText)
        },
        containerColor = Morandi.panelHi,
    )
}

/** Second-level page: brush property sliders for the active preset. */
@Composable
fun BrushPropertyPage(
    vm: PaintViewModel,
    presetIndex: Int,
    onBack: () -> Unit,
    onOpenStudio: () -> Unit = {},
) {
    val preset = vm.brushPresets.firstOrNull { it.index == presetIndex }
    var showBlendMenu by remember { mutableStateOf(false) }

    val blendModeList = listOf(
        "normal" to stringResource(R.string.blend_normal),
        "multiply" to stringResource(R.string.blend_multiply),
        "screen" to stringResource(R.string.blend_screen),
        "overlay" to stringResource(R.string.blend_overlay),
        "darken" to stringResource(R.string.blend_darken),
        "lighten" to stringResource(R.string.blend_lighten),
        "dodge" to stringResource(R.string.blend_color_dodge),
        "burn" to stringResource(R.string.blend_color_burn),
        "hard_light" to stringResource(R.string.blend_hard_light),
        "soft_light" to stringResource(R.string.blend_soft_light),
        "difference" to stringResource(R.string.blend_difference),
        "exclusion" to stringResource(R.string.blend_exclusion),
    )

    val scrollState = rememberScrollState(initial = vm.brushPropertyScrollValue)
    LaunchedEffect(scrollState) {
        androidx.compose.runtime.snapshotFlow { scrollState.value }
            .collect {
                vm.brushPropertyScrollValue = it
            }
    }
    DisposableEffect(Unit) {
        onDispose {
            vm.brushPropertyScrollValue = scrollState.value
            vm.persistBrushPanelState()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(scrollState),
    ) {
        // Top Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Morandi.panelHi)
                    .noRippleClickable(onBack),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painterResource(R.drawable.ic_chevron),
                    contentDescription = stringResource(R.string.common_back),
                    tint = Morandi.icon,
                    modifier = Modifier.size(16.dp),
                )
            }

            Text(
                stringResource(R.string.brush_settings_title),
                color = Morandi.text,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )

            // Reset preset values action
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Morandi.panelHi)
                    .noRippleClickable {
                        if (preset != null) vm.resetBrushPresetToDefault(preset.name)
                        else vm.resetBrushParams()
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painterResource(R.drawable.ic_refresh),
                    contentDescription = stringResource(R.string.brush_reset_values),
                    tint = Morandi.subText,
                    modifier = Modifier.size(15.dp),
                )
            }

            // Quick Studio entry icon
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Morandi.accent.copy(alpha = 0.15f))
                    .noRippleClickable(onOpenStudio),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painterResource(R.drawable.ic_sliders),
                    contentDescription = stringResource(R.string.brush_studio_title),
                    tint = Morandi.accent,
                    modifier = Modifier.size(15.dp),
                )
            }
        }

        Box(Modifier.fillMaxWidth().height(0.6.dp).background(Morandi.border.copy(alpha = 0.2f)))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Preset Hero Card
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Morandi.panelHi)
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val bmp = rememberPresetThumb(preset?.name ?: "", preset?.thumbBytes ?: ByteArray(0))
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Morandi.panel),
                    contentAlignment = Alignment.Center,
                ) {
                    if (bmp != null) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = preset?.name,
                            modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)),
                        )
                    }
                    if (preset != null && vm.isBrushModified(preset.name)) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(3.dp)
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(Morandi.accent),
                        )
                    }
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = preset?.name ?: stringResource(R.string.brush_studio_custom_brush),
                        color = Morandi.text,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                    Spacer(Modifier.height(3.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        val grp = preset?.group?.let { brushCategoryDisplayName(it) } ?: "常用"
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(Morandi.panel)
                                .padding(horizontal = 5.dp, vertical = 1.dp),
                        ) {
                            Text(grp, color = Morandi.subText, fontSize = 10.sp)
                        }
                        if (preset?.isBuiltIn == true) {
                            Text("Krita", color = Morandi.subText.copy(alpha = 0.7f), fontSize = 10.sp)
                        }
                    }
                }

                // Studio pill button
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Morandi.accent.copy(alpha = 0.16f))
                        .noRippleClickable(onOpenStudio)
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        painterResource(R.drawable.ic_sliders),
                        contentDescription = null,
                        tint = Morandi.accent,
                        modifier = Modifier.size(12.dp),
                    )
                    Text(
                        stringResource(R.string.brush_card_open_studio),
                        color = Morandi.accent,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            // Card 1: 核心基础
            BrushSectionCard(title = stringResource(R.string.brush_group_basic)) {
                ModernParamSlider(
                    label = stringResource(R.string.brush_param_size),
                    value = vm.brushSize,
                    min = vm.brushMinSizeLimit.coerceAtLeast(0.5),
                    max = vm.brushMaxSizeLimit.coerceAtLeast(vm.brushMinSizeLimit.coerceAtLeast(0.5) + 0.1),
                    unit = ParamUnit.PIXEL,
                ) { vm.updateBrushSize(it) }

                ModernParamSlider(
                    label = stringResource(R.string.brush_param_opacity),
                    value = vm.brushOpacity,
                    min = 0.05,
                    max = 1.0,
                    unit = ParamUnit.PERCENT,
                ) { vm.updateBrushOpacity(it) }

                ModernParamSlider(
                    label = stringResource(R.string.brush_param_flow),
                    value = vm.brushFlow,
                    min = 0.05,
                    max = 1.0,
                    unit = ParamUnit.PERCENT,
                ) { vm.updateBrushFlow(it) }

                // Blend Mode row with compact selector
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        stringResource(R.string.brush_blend_mode),
                        color = Morandi.text,
                        fontSize = 12.sp,
                    )

                    Box {
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(Morandi.panel)
                                .noRippleClickable { showBlendMenu = true }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                blendModeList.firstOrNull { it.first == vm.brushCompositeOp }?.second ?: stringResource(R.string.blend_normal),
                                color = Morandi.accent,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                            )
                            Icon(
                                painterResource(R.drawable.ic_chevron),
                                contentDescription = null,
                                tint = Morandi.subText,
                                modifier = Modifier.size(12.dp).rotate(90f),
                            )
                        }

                        androidx.compose.material3.DropdownMenu(
                            expanded = showBlendMenu,
                            onDismissRequest = { showBlendMenu = false },
                            modifier = Modifier.background(Morandi.panelHi),
                        ) {
                            blendModeList.forEach { (opId, name) ->
                                val sel = vm.brushCompositeOp == opId
                                androidx.compose.material3.DropdownMenuItem(
                                    text = {
                                        Text(
                                            name,
                                            color = if (sel) Morandi.accent else Morandi.text,
                                            fontSize = 12.sp,
                                            fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                                        )
                                    },
                                    onClick = {
                                        vm.updateBrushCompositeOp(opId)
                                        showBlendMenu = false
                                    },
                                    trailingIcon = if (sel) {
                                        {
                                            Icon(
                                                painterResource(R.drawable.ic_check),
                                                contentDescription = null,
                                                tint = Morandi.accent,
                                                modifier = Modifier.size(14.dp),
                                            )
                                        }
                                    } else null,
                                )
                            }
                        }
                    }
                }
            }

            // Card 2: 笔尖几何
            BrushSectionCard(title = stringResource(R.string.brush_group_geometry)) {
                ModernParamSlider(
                    label = stringResource(R.string.brush_param_spacing),
                    value = vm.brushSpacing,
                    min = 0.0,
                    max = 1.0,
                    unit = ParamUnit.PERCENT,
                ) { vm.updateBrushSpacing(it) }

                ModernParamSlider(
                    label = stringResource(R.string.brush_param_ratio),
                    value = vm.brushRatio,
                    min = 0.0,
                    max = 1.0,
                    unit = ParamUnit.PERCENT,
                ) { vm.updateBrushRatio(it) }

                ModernParamSlider(
                    label = stringResource(R.string.brush_param_softness),
                    value = vm.brushSoftness,
                    min = 0.0,
                    max = 1.0,
                    unit = ParamUnit.PERCENT,
                ) { vm.updateBrushSoftness(it) }

                ModernParamSlider(
                    label = stringResource(R.string.brush_param_angle),
                    value = vm.brushAngle,
                    min = 0.0,
                    max = 360.0,
                    unit = ParamUnit.DEGREE,
                ) { vm.updateBrushAngle(it) }

                ModernParamSlider(
                    label = stringResource(R.string.brush_param_rotation),
                    value = vm.brushRotation,
                    min = 0.0,
                    max = 360.0,
                    unit = ParamUnit.DEGREE,
                ) { vm.updateBrushRotation(it) }

                ModernParamSlider(
                    label = stringResource(R.string.brush_param_sharpness),
                    value = vm.brushSharpness,
                    min = 0.0,
                    max = 1.0,
                    unit = ParamUnit.PERCENT,
                ) { vm.updateBrushSharpness(it) }
            }

            // Card 3: 动态表现
            BrushSectionCard(title = stringResource(R.string.brush_group_dynamics)) {
                ModernParamSlider(
                    label = stringResource(R.string.brush_param_scatter),
                    value = vm.brushScatter,
                    min = 0.0,
                    max = 1.0,
                    unit = ParamUnit.PERCENT,
                ) { vm.updateBrushScatter(it) }

                ModernParamSlider(
                    label = stringResource(R.string.brush_param_fade),
                    value = vm.brushFade,
                    min = 0.0,
                    max = 1.0,
                    unit = ParamUnit.PERCENT,
                ) { vm.updateBrushFade(it) }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

