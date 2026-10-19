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
import com.reverie.paint.ui.components.ReDropdownMenu
import com.reverie.paint.ui.components.ReDropdownMenuItem
import com.reverie.paint.ui.painting.TextInputGuard
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
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
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import dev.chrisbanes.haze.hazeEffect
import com.reverie.paint.ui.theme.Glass
import androidx.compose.ui.unit.sp
import com.reverie.paint.core.*
import com.reverie.paint.model.UiPanelSlot
import com.reverie.paint.ui.theme.Morandi
import com.reverie.paint.ui.theme.systemHoverIcon
import com.reverie.paint.ui.components.noRippleClickable
import com.reverie.paint.ui.components.reportUiSlot
import com.reverie.paint.ui.components.evadablePanelContent
import com.reverie.paint.ui.components.evadablePanelSurface
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.runtime.mutableFloatStateOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

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
        val groupsFromPresets = vm.brushPresets.map { it.group }.filter { it.isNotBlank() && it !in listOf("全部", "常用", "最近") }.distinct()
        val baseGroups = if (groupsFromPresets.isEmpty()) {
            com.reverie.paint.core.BUILT_IN_BRUSH_GROUPS.filter { it !in listOf("全部", "常用", "最近") }
        } else {
            groupsFromPresets
        }
        listOf("全部", "常用", "最近") + baseGroups +
            vm.customBrushGroups.filter { g -> g !in listOf("全部", "常用", "最近") && g !in baseGroups }
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
    LaunchedEffect(vm.brushPanelSelectedCategory) {
        if (selectedCategory != vm.brushPanelSelectedCategory) {
            selectedCategory = vm.brushPanelSelectedCategory
        }
    }
    var showNewGroupDialog by remember { mutableStateOf(false) }
    var showNewBrushDialog by remember { mutableStateOf(false) }
    var renamePresetName by remember { mutableStateOf<String?>(null) }
    var editingCategoryName by remember { mutableStateOf<String?>(null) }
    var groupPendingDelete by remember { mutableStateOf<String?>(null) }
    var deleteGroupWithPresets by remember { mutableStateOf(true) }
    var isBatchMode by remember { mutableStateOf(false) }
    var selectedPresets by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showBatchDeleteConfirm by remember { mutableStateOf(false) }
    var showBatchMoveDialog by remember { mutableStateOf(false) }
    var categoryMenuTarget by remember { mutableStateOf<String?>(null) }
    var renameCategoryTarget by remember { mutableStateOf<String?>(null) }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }
    var movePresetName by remember { mutableStateOf<String?>(null) }
    var reorderPresetName by remember { mutableStateOf<String?>(null) }
    var isSearchActive by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    val searchFocusRequester = remember { FocusRequester() }

    if (isSearchActive) {
        TextInputGuard(vm)
        BackHandler {
            isSearchActive = false
            searchQuery = ""
        }
    }

    LaunchedEffect(isSearchActive) {
        if (isSearchActive) {
            searchFocusRequester.requestFocus()
        }
    }

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

    // Auto heal: if brush presets are empty when opening the panel, trigger reload
    LaunchedEffect(Unit) {
        if (vm.brushPresets.isEmpty() && !vm.isBrushPresetsLoading) {
            vm.loadBrushPresets(force = true)
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

    val panelShape = RoundedCornerShape(16.dp)
    val density = LocalDensity.current
    val baseStartOffsetPx = remember(density) { with(density) { 48.dp.roundToPx() } }
    val haptic = LocalHapticFeedback.current
    val pinnedCategories = remember { listOf("全部", "常用", "最近") }
    val maxScrollSpeed = with(density) { 12.dp.toPx() }
    val autoScrollThreshold = with(density) { 40.dp.toPx() }

    // Category drag state
    var draggingCategory by remember { mutableStateOf<String?>(null) }
    var categoryFloatingY by remember { mutableFloatStateOf(0f) }
    var categoryDragYOffsetInItem by remember { mutableFloatStateOf(0f) }
    var categoryFingerYInViewport by remember { mutableFloatStateOf(0f) }
    var categoryViewportHeightPx by remember { mutableFloatStateOf(0f) }
    var categoryAutoScrollSpeed by remember { mutableFloatStateOf(0f) }
    val categoryItemHeightPx = with(density) { 38.dp.toPx() }

    fun checkAndPerformCategoryReorder() {
        val draggedCat = draggingCategory ?: return
        val visibleItems = categoryScrollState.layoutInfo.visibleItemsInfo
        if (visibleItems.isEmpty()) return

        val centerY = categoryFloatingY + categoryItemHeightPx / 2f
        val targetItem = visibleItems.firstOrNull { item ->
            centerY >= item.offset && centerY < item.offset + item.size
        }
        val targetCat = targetItem?.key as? String ?: return
        if (targetCat !in pinnedCategories && targetCat != draggedCat) {
            vm.reorderCategoryByName(draggedCat, targetCat, categories)
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }

    LaunchedEffect(draggingCategory) {
        if (draggingCategory == null) return@LaunchedEffect
        while (isActive && draggingCategory != null) {
            val speed = categoryAutoScrollSpeed
            if (speed != 0f) {
                val scrolled = categoryScrollState.scrollBy(speed)
                if (scrolled != 0f) {
                    checkAndPerformCategoryReorder()
                }
            }
            delay(16)
        }
    }

    // Preset list drag state
    var draggingPresetName by remember { mutableStateOf<String?>(null) }
    var presetListFloatingY by remember { mutableFloatStateOf(0f) }
    var presetListDragYOffsetInItem by remember { mutableFloatStateOf(0f) }
    var presetListFingerYInViewport by remember { mutableFloatStateOf(0f) }
    var presetListViewportHeightPx by remember { mutableFloatStateOf(0f) }
    var presetListAutoScrollSpeed by remember { mutableFloatStateOf(0f) }
    val presetRowHeightPx = with(density) { 48.dp.toPx() }

    fun checkAndPerformPresetListReorder() {
        val dragged = draggingPresetName ?: return
        val visibleItems = presetScrollState.layoutInfo.visibleItemsInfo
        if (visibleItems.isEmpty()) return

        val centerY = presetListFloatingY + presetRowHeightPx / 2f
        val targetItem = visibleItems.firstOrNull { item ->
            centerY >= item.offset && centerY < item.offset + item.size
        }
        val targetName = targetItem?.key as? String ?: return
        if (targetName != dragged) {
            vm.reorderBrushInView(dragged, targetName)
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }

    LaunchedEffect(draggingPresetName) {
        if (draggingPresetName == null) return@LaunchedEffect
        while (isActive && draggingPresetName != null) {
            val speed = presetListAutoScrollSpeed
            if (speed != 0f) {
                val scrolled = presetScrollState.scrollBy(speed)
                if (scrolled != 0f) {
                    checkAndPerformPresetListReorder()
                }
            }
            delay(16)
        }
    }

    // Preset grid drag state
    var draggingPresetGridName by remember { mutableStateOf<String?>(null) }
    var presetGridFloatingX by remember { mutableFloatStateOf(0f) }
    var presetGridFloatingY by remember { mutableFloatStateOf(0f) }
    var presetGridDragXOffsetInItem by remember { mutableFloatStateOf(0f) }
    var presetGridDragYOffsetInItem by remember { mutableFloatStateOf(0f) }
    var presetGridFingerXInViewport by remember { mutableFloatStateOf(0f) }
    var presetGridFingerYInViewport by remember { mutableFloatStateOf(0f) }
    var presetGridViewportWidthPx by remember { mutableFloatStateOf(0f) }
    var presetGridViewportHeightPx by remember { mutableFloatStateOf(0f) }
    var presetGridAutoScrollSpeed by remember { mutableFloatStateOf(0f) }
    var presetGridCardWidthPx by remember { mutableFloatStateOf(0f) }
    var presetGridCardHeightPx by remember { mutableFloatStateOf(0f) }

    fun checkAndPerformPresetGridReorder() {
        val dragged = draggingPresetGridName ?: return
        val visibleItems = presetGridScrollState.layoutInfo.visibleItemsInfo
        if (visibleItems.isEmpty()) return

        val cw = presetGridCardWidthPx.takeIf { it > 0f } ?: 80f
        val ch = presetGridCardHeightPx.takeIf { it > 0f } ?: 80f
        val centerX = presetGridFloatingX + cw / 2f
        val centerY = presetGridFloatingY + ch / 2f

        val targetItem = visibleItems.firstOrNull { item ->
            centerX >= item.offset.x && centerX < item.offset.x + item.size.width &&
            centerY >= item.offset.y && centerY < item.offset.y + item.size.height
        }
        val targetName = targetItem?.key as? String ?: return
        if (targetName != dragged) {
            vm.reorderBrushInView(dragged, targetName)
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }

    LaunchedEffect(draggingPresetGridName) {
        if (draggingPresetGridName == null) return@LaunchedEffect
        while (isActive && draggingPresetGridName != null) {
            val speed = presetGridAutoScrollSpeed
            if (speed != 0f) {
                val scrolled = presetGridScrollState.scrollBy(speed)
                if (scrolled != 0f) {
                    checkAndPerformPresetGridReorder()
                }
            }
            delay(16)
        }
    }

    val cardContent: @Composable (Modifier) -> Unit = { cardModifier ->
        Column(
            modifier = cardModifier
                .systemHoverIcon(context)
                .width(320.dp)
                .fillMaxHeight(0.75f)
                .shadow(16.dp, panelShape, spotColor = Color.Black.copy(alpha = 0.45f))
                .clip(panelShape)
                .evadablePanelSurface(
                    vm = vm,
                    slot = UiPanelSlot.PINNED_BRUSH_PANEL,
                    shape = panelShape,
                    baseOpacity = opacity,
                    hazeState = hazeState,
                    isBarStyle = true,
                    drawBorder = vm.panelPinningEnabled && vm.isBrushPanelPinned,
                )
                .evadablePanelContent(vm, UiPanelSlot.PINNED_BRUSH_PANEL)
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
                                Box(
                                    modifier = Modifier
                                        .width(88.dp)
                                        .fillMaxHeight()
                                        .onGloballyPositioned { coordinates ->
                                            categoryViewportHeightPx = coordinates.size.height.toFloat()
                                        }
                                ) {
                                    LazyColumn(
                                        state = categoryScrollState,
                                        userScrollEnabled = draggingCategory == null,
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(Morandi.panelHi.copy(alpha = 0.35f))
                                    ) {
                                        items(categories, key = { it }) { cat ->
                                            val isDraggingThis = draggingCategory == cat
                                            val sel = cat == selectedCategory
                                            val iconRes = when (cat) {
                                                "常用" -> R.drawable.ic_star
                                                "最近" -> R.drawable.ic_clock
                                                else -> null
                                            }
                                            val canDrag = cat !in pinnedCategories
                                            val catGestureModifier = if (canDrag) {
                                                Modifier.reorderDragGesture(
                                                    key = cat,
                                                    onTap = {
                                                        if (selectedCategory != cat) {
                                                            selectedCategory = cat
                                                            vm.updateBrushPanelCategory(cat)
                                                            isBatchMode = false
                                                            selectedPresets = emptySet()
                                                        }
                                                    },
                                                    onLongPressOnly = {
                                                        categoryMenuTarget = cat
                                                    },
                                                    onDragStart = { offset ->
                                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                        val itemInfo = categoryScrollState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == cat }
                                                        val itemTop = itemInfo?.offset?.toFloat()
                                                            ?: (categories.indexOf(cat) * categoryItemHeightPx)
                                                        categoryDragYOffsetInItem = offset.y
                                                        categoryFloatingY = itemTop
                                                        categoryFingerYInViewport = itemTop + offset.y
                                                        draggingCategory = cat
                                                    },
                                                    onDrag = { change, dragAmount ->
                                                        categoryFingerYInViewport += dragAmount.y
                                                        val minY = 0f
                                                        val maxY = (categoryViewportHeightPx - categoryItemHeightPx).coerceAtLeast(0f)
                                                        categoryFloatingY = (categoryFingerYInViewport - categoryDragYOffsetInItem).coerceIn(minY, maxY)

                                                        val topDist = autoScrollThreshold - categoryFloatingY
                                                        val bottomDist = if (categoryViewportHeightPx > 0f) {
                                                            (categoryFloatingY + categoryItemHeightPx) - (categoryViewportHeightPx - autoScrollThreshold)
                                                        } else 0f
                                                        categoryAutoScrollSpeed = when {
                                                            topDist > 0 -> -((topDist / autoScrollThreshold).coerceIn(0f, 1f) * maxScrollSpeed)
                                                            bottomDist > 0 -> ((bottomDist / autoScrollThreshold).coerceIn(0f, 1f) * maxScrollSpeed)
                                                            else -> 0f
                                                        }
                                                        checkAndPerformCategoryReorder()
                                                    },
                                                    onDragEnd = {
                                                        draggingCategory = null
                                                        categoryAutoScrollSpeed = 0f
                                                    },
                                                    onDragCancel = {
                                                        draggingCategory = null
                                                        categoryAutoScrollSpeed = 0f
                                                    }
                                                )
                                            } else {
                                                Modifier.combinedClickable(
                                                    onClick = {
                                                        if (selectedCategory != cat) {
                                                            selectedCategory = cat
                                                            vm.updateBrushPanelCategory(cat)
                                                            isBatchMode = false
                                                            selectedPresets = emptySet()
                                                        }
                                                    },
                                                    onLongClick = { categoryMenuTarget = cat }
                                                )
                                            }

                                            Box(
                                                modifier = Modifier
                                                    .animateItem(
                                                        fadeInSpec = null,
                                                        fadeOutSpec = null,
                                                        placementSpec = tween(180)
                                                    )
                                                    .fillMaxWidth()
                                                    .padding(horizontal = 5.dp, vertical = 2.dp)
                                                    .height(38.dp)
                                                    .graphicsLayer {
                                                        if (isDraggingThis) alpha = 0.25f
                                                    }
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .background(if (sel) Morandi.accent.copy(alpha = 0.16f) else Color.Transparent)
                                                    .then(catGestureModifier),
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

                                    // Floating category overlay
                                    if (draggingCategory != null) {
                                        CategoryDragOverlay(
                                            category = draggingCategory!!,
                                            offsetY = categoryFloatingY
                                        )
                                    }
                                }

                                // Right preset list
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                ) {
                                    val allCategoryMatches = remember(searchQuery, vm.brushPresets) {
                                        if (searchQuery.isBlank()) emptyList()
                                        else {
                                            vm.brushPresets.filter { preset ->
                                                matchesBrushQuery(
                                                    preset.name,
                                                    preset.group,
                                                    searchQuery,
                                                    brushCategoryDisplayName(context, preset.group)
                                                )
                                            }
                                        }
                                    }

                                    val filtered = remember(selectedCategory, vm.brushPresets, vm.favoriteBrushNames, vm.recentBrushNames, searchQuery) {
                                        val baseList = when (selectedCategory) {
                                            "全部" -> vm.brushPresets
                                            "常用" -> vm.brushPresets.filter { vm.isFavoriteBrush(it.name) }
                                            "最近" -> vm.recentBrushNames.mapNotNull { name ->
                                                vm.brushPresets.firstOrNull { it.name == name }
                                            }
                                            else -> vm.brushPresets.filter { it.group == selectedCategory }
                                        }
                                        if (searchQuery.isBlank()) {
                                            baseList
                                        } else {
                                            baseList.filter { preset ->
                                                matchesBrushQuery(
                                                    preset.name,
                                                    preset.group,
                                                    searchQuery,
                                                    brushCategoryDisplayName(context, preset.group)
                                                )
                                            }
                                        }
                                    }

                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(52.dp)
                                            .padding(horizontal = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        if (isBatchMode) {
                                            Text(
                                                stringResource(R.string.brush_batch_selected_count, selectedPresets.size),
                                                color = Morandi.text,
                                                fontSize = 15.sp,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                            Spacer(Modifier.weight(1f))
                                            val allSelected = filtered.isNotEmpty() && filtered.all { it.name in selectedPresets }
                                            Text(
                                                if (allSelected) stringResource(R.string.brush_batch_deselect_all) else stringResource(R.string.brush_batch_select_all),
                                                color = Morandi.subText,
                                                fontSize = 12.sp,
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(6.dp))
                                                    .clickable {
                                                        selectedPresets = if (allSelected) emptySet() else filtered.map { it.name }.toSet()
                                                    }
                                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                                            )
                                            Spacer(Modifier.width(4.dp))
                                            Text(
                                                stringResource(R.string.brush_batch_done),
                                                color = Morandi.accent,
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(6.dp))
                                                    .clickable {
                                                        isBatchMode = false
                                                        selectedPresets = emptySet()
                                                    }
                                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                                            )
                                        } else if (isSearchActive) {
                                            Row(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .height(34.dp)
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .background(Morandi.panelHi.copy(alpha = 0.6f))
                                                    .padding(horizontal = 8.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Icon(
                                                    painter = painterResource(R.drawable.ic_search),
                                                    contentDescription = null,
                                                    tint = Morandi.subText,
                                                    modifier = Modifier.size(14.dp)
                                                )
                                                Spacer(Modifier.width(6.dp))
                                                Box(
                                                    modifier = Modifier.weight(1f),
                                                    contentAlignment = Alignment.CenterStart
                                                ) {
                                                    if (searchQuery.isEmpty()) {
                                                        Text(
                                                            text = stringResource(R.string.brush_search_placeholder),
                                                            color = Morandi.subText.copy(alpha = 0.6f),
                                                            fontSize = 12.sp,
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis
                                                        )
                                                    }
                                                    BasicTextField(
                                                        value = searchQuery,
                                                        onValueChange = { searchQuery = it },
                                                        singleLine = true,
                                                        textStyle = androidx.compose.ui.text.TextStyle(
                                                            color = Morandi.text,
                                                            fontSize = 12.sp
                                                        ),
                                                        cursorBrush = SolidColor(Morandi.accent),
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .focusRequester(searchFocusRequester)
                                                    )
                                                }
                                                if (searchQuery.isNotEmpty()) {
                                                    Box(
                                                        modifier = Modifier
                                                            .size(20.dp)
                                                            .clip(CircleShape)
                                                            .clickable { searchQuery = "" },
                                                        contentAlignment = Alignment.Center
                                                    ) {
                                                        Icon(
                                                            painter = painterResource(R.drawable.ic_x),
                                                            contentDescription = stringResource(R.string.brush_search_clear),
                                                            tint = Morandi.subText,
                                                            modifier = Modifier.size(12.dp)
                                                        )
                                                    }
                                                }
                                            }

                                            Spacer(Modifier.width(6.dp))

                                            Text(
                                                text = stringResource(R.string.common_cancel),
                                                color = Morandi.accent,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Medium,
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(6.dp))
                                                    .clickable {
                                                        isSearchActive = false
                                                        searchQuery = ""
                                                    }
                                                    .padding(horizontal = 4.dp, vertical = 6.dp)
                                            )
                                        } else {
                                            Text(
                                                brushCategoryDisplayName(selectedCategory),
                                                color = Morandi.text,
                                                fontSize = 15.sp,
                                                fontWeight = FontWeight.Bold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f, fill = false)
                                            )
                                            Spacer(Modifier.width(2.dp))
                                            IconButton(
                                                onClick = { categoryMenuTarget = selectedCategory },
                                                modifier = Modifier.size(26.dp)
                                            ) {
                                                Icon(
                                                    painter = painterResource(R.drawable.ic_dots_vertical),
                                                    contentDescription = stringResource(R.string.brush_group_more_options),
                                                    tint = Morandi.icon,
                                                    modifier = Modifier.size(15.dp)
                                                )
                                            }
                                            Spacer(Modifier.weight(1f))
                                            Text(
                                                stringResource(R.string.brush_presets_count, filtered.size),
                                                color = Morandi.subText,
                                                fontSize = 11.sp,
                                            )
                                            Spacer(Modifier.width(6.dp))
                                            Box(
                                                modifier = Modifier
                                                    .size(28.dp)
                                                    .clip(RoundedCornerShape(6.dp))
                                                    .background(Morandi.panelHi.copy(alpha = 0.5f))
                                                    .clickable { isSearchActive = true },
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(
                                                    painter = painterResource(R.drawable.ic_search),
                                                    contentDescription = stringResource(R.string.brush_search_hint),
                                                    tint = Morandi.icon,
                                                    modifier = Modifier.size(15.dp)
                                                )
                                            }
                                            Spacer(Modifier.width(6.dp))
                                            Box(
                                                modifier = Modifier
                                                    .size(28.dp)
                                                    .clip(RoundedCornerShape(6.dp))
                                                    .background(if (vm.brushPanelGridView) Morandi.accent.copy(alpha = 0.14f) else Morandi.panelHi.copy(alpha = 0.5f))
                                                    .clickable { vm.toggleBrushPanelGridView() },
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(
                                                    painter = painterResource(if (vm.brushPanelGridView) R.drawable.ic_menu else R.drawable.ic_grid),
                                                    contentDescription = stringResource(if (vm.brushPanelGridView) R.string.brush_switch_to_list else R.string.brush_switch_to_grid),
                                                    tint = if (vm.brushPanelGridView) Morandi.accent else Morandi.icon,
                                                    modifier = Modifier.size(15.dp)
                                                )
                                            }
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
                                            if (vm.isBrushPresetsLoading) {
                                                Column(
                                                    horizontalAlignment = Alignment.CenterHorizontally,
                                                    verticalArrangement = Arrangement.Center
                                                ) {
                                                    androidx.compose.material3.CircularProgressIndicator(
                                                        modifier = Modifier.size(28.dp),
                                                        color = Morandi.accent,
                                                        strokeWidth = 2.5.dp,
                                                    )
                                                    Spacer(Modifier.height(10.dp))
                                                    Text(
                                                        stringResource(R.string.brush_presets_loading),
                                                        color = Morandi.subText,
                                                        fontSize = 12.sp,
                                                    )
                                                }
                                            } else if (searchQuery.isNotBlank()) {
                                                if (selectedCategory != "全部" && allCategoryMatches.isNotEmpty()) {
                                                    Column(
                                                        horizontalAlignment = Alignment.CenterHorizontally,
                                                        verticalArrangement = Arrangement.Center
                                                    ) {
                                                        Icon(
                                                            painter = painterResource(R.drawable.ic_search),
                                                            contentDescription = null,
                                                            tint = Morandi.subText.copy(alpha = 0.35f),
                                                            modifier = Modifier.size(36.dp)
                                                        )
                                                        Spacer(Modifier.height(8.dp))
                                                        Text(
                                                            stringResource(R.string.brush_search_empty_current_group),
                                                            color = Morandi.subText,
                                                            fontSize = 13.sp,
                                                        )
                                                        Spacer(Modifier.height(8.dp))
                                                        Text(
                                                            stringResource(R.string.brush_search_in_all_count, allCategoryMatches.size),
                                                            color = Morandi.accent,
                                                            fontSize = 12.sp,
                                                            fontWeight = FontWeight.Medium,
                                                            modifier = Modifier
                                                                .clip(RoundedCornerShape(6.dp))
                                                                .background(Morandi.accent.copy(alpha = 0.12f))
                                                                .clickable {
                                                                    selectedCategory = "全部"
                                                                    vm.updateBrushPanelCategory("全部")
                                                                }
                                                                .padding(horizontal = 10.dp, vertical = 6.dp)
                                                        )
                                                    }
                                                } else {
                                                    Column(
                                                        horizontalAlignment = Alignment.CenterHorizontally,
                                                        verticalArrangement = Arrangement.Center
                                                    ) {
                                                        Icon(
                                                            painter = painterResource(R.drawable.ic_search),
                                                            contentDescription = null,
                                                            tint = Morandi.subText.copy(alpha = 0.35f),
                                                            modifier = Modifier.size(36.dp)
                                                        )
                                                        Spacer(Modifier.height(8.dp))
                                                        Text(
                                                            stringResource(R.string.brush_search_empty_title),
                                                            color = Morandi.subText,
                                                            fontSize = 13.sp,
                                                        )
                                                        Spacer(Modifier.height(4.dp))
                                                        Text(
                                                            stringResource(R.string.brush_search_empty_desc),
                                                            color = Morandi.subText.copy(alpha = 0.6f),
                                                            fontSize = 11.sp,
                                                        )
                                                    }
                                                }
                                            } else if (vm.brushPresets.isEmpty()) {
                                                Column(
                                                    horizontalAlignment = Alignment.CenterHorizontally,
                                                    verticalArrangement = Arrangement.Center
                                                ) {
                                                    Icon(
                                                        painter = painterResource(R.drawable.ic_brush),
                                                        contentDescription = null,
                                                        tint = Morandi.subText.copy(alpha = 0.35f),
                                                        modifier = Modifier.size(36.dp)
                                                    )
                                                    Spacer(Modifier.height(8.dp))
                                                    Text(
                                                        stringResource(R.string.brush_empty_category_title),
                                                        color = Morandi.subText,
                                                        fontSize = 13.sp,
                                                    )
                                                    Spacer(Modifier.height(8.dp))
                                                    Text(
                                                        stringResource(R.string.brush_presets_reload),
                                                        color = Morandi.accent,
                                                        fontSize = 12.sp,
                                                        modifier = Modifier
                                                            .clip(RoundedCornerShape(6.dp))
                                                            .clickable { vm.loadBrushPresets(force = true) }
                                                            .padding(horizontal = 12.dp, vertical = 6.dp)
                                                    )
                                                }
                                            } else {
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
                                                Box(
                                                    modifier = Modifier
                                                        .fillMaxSize()
                                                        .onGloballyPositioned { coordinates ->
                                                            presetGridViewportWidthPx = coordinates.size.width.toFloat()
                                                            presetGridViewportHeightPx = coordinates.size.height.toFloat()
                                                        }
                                                ) {
                                                    LazyVerticalGrid(
                                                        columns = GridCells.Fixed(3),
                                                        state = presetGridScrollState,
                                                        userScrollEnabled = draggingPresetGridName == null,
                                                        modifier = Modifier
                                                            .fillMaxSize()
                                                            .padding(horizontal = 8.dp),
                                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                                    ) {
                                                        items(filtered, key = { it.name }, contentType = { "preset_grid" }) { preset ->
                                                            val isSelected = preset.index == vm.brushPresetIndex
                                                            val isDraggingThis = draggingPresetGridName == preset.name
                                                            val canReorder = !isBatchMode && !isSearchActive && selectedCategory != "最近"

                                                            val itemGesture = if (canReorder) {
                                                                Modifier.reorderDragGesture(
                                                                    key = preset.name,
                                                                    onTap = {
                                                                        if (isSelected) view = BrushView.Detail(preset.index)
                                                                        else vm.selectBrushPreset(preset.index)
                                                                    },
                                                                    onLongPressOnly = {
                                                                        reorderPresetName = preset.name
                                                                    },
                                                                    onDragStart = { offset ->
                                                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                                        val itemInfo = presetGridScrollState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == preset.name }
                                                                        val itemLeft = itemInfo?.offset?.x?.toFloat() ?: 0f
                                                                        val itemTop = itemInfo?.offset?.y?.toFloat() ?: 0f
                                                                        if (itemInfo != null) {
                                                                            presetGridCardWidthPx = itemInfo.size.width.toFloat()
                                                                            presetGridCardHeightPx = itemInfo.size.height.toFloat()
                                                                        }
                                                                        presetGridDragXOffsetInItem = offset.x
                                                                        presetGridDragYOffsetInItem = offset.y
                                                                        presetGridFloatingX = itemLeft
                                                                        presetGridFloatingY = itemTop
                                                                        presetGridFingerXInViewport = itemLeft + offset.x
                                                                        presetGridFingerYInViewport = itemTop + offset.y
                                                                        draggingPresetGridName = preset.name
                                                                    },
                                                                    onDrag = { change, dragAmount ->
                                                                        presetGridFingerXInViewport += dragAmount.x
                                                                        presetGridFingerYInViewport += dragAmount.y
                                                                        val minX = 0f
                                                                        val minY = 0f
                                                                        val cw = presetGridCardWidthPx.takeIf { it > 0f } ?: 80f
                                                                        val ch = presetGridCardHeightPx.takeIf { it > 0f } ?: 80f
                                                                        val maxX = (presetGridViewportWidthPx - cw).coerceAtLeast(0f)
                                                                        val maxY = (presetGridViewportHeightPx - ch).coerceAtLeast(0f)
                                                                        presetGridFloatingX = (presetGridFingerXInViewport - presetGridDragXOffsetInItem).coerceIn(minX, maxX)
                                                                        presetGridFloatingY = (presetGridFingerYInViewport - presetGridDragYOffsetInItem).coerceIn(minY, maxY)

                                                                        val topDist = autoScrollThreshold - presetGridFloatingY
                                                                        val bottomDist = if (presetGridViewportHeightPx > 0f) {
                                                                            (presetGridFloatingY + ch) - (presetGridViewportHeightPx - autoScrollThreshold)
                                                                        } else 0f
                                                                        presetGridAutoScrollSpeed = when {
                                                                            topDist > 0 -> -((topDist / autoScrollThreshold).coerceIn(0f, 1f) * maxScrollSpeed)
                                                                            bottomDist > 0 -> ((bottomDist / autoScrollThreshold).coerceIn(0f, 1f) * maxScrollSpeed)
                                                                            else -> 0f
                                                                        }
                                                                        checkAndPerformPresetGridReorder()
                                                                    },
                                                                    onDragEnd = {
                                                                        draggingPresetGridName = null
                                                                        presetGridAutoScrollSpeed = 0f
                                                                    },
                                                                    onDragCancel = {
                                                                        draggingPresetGridName = null
                                                                        presetGridAutoScrollSpeed = 0f
                                                                    }
                                                                )
                                                            } else null

                                                            PresetGridCard(
                                                                preset = preset,
                                                                isSelected = isSelected,
                                                                isModified = vm.isBrushModified(preset.name),
                                                                isBatchMode = isBatchMode,
                                                                isChecked = preset.name in selectedPresets,
                                                                onClick = {
                                                                    if (isBatchMode) {
                                                                        selectedPresets = if (preset.name in selectedPresets) {
                                                                            selectedPresets - preset.name
                                                                        } else {
                                                                            selectedPresets + preset.name
                                                                        }
                                                                    } else {
                                                                        if (isSelected) view = BrushView.Detail(preset.index)
                                                                        else vm.selectBrushPreset(preset.index)
                                                                    }
                                                                },
                                                                onLongClick = {
                                                                    if (!isBatchMode) {
                                                                        reorderPresetName = preset.name
                                                                    }
                                                                },
                                                                itemGestureModifier = itemGesture,
                                                                modifier = Modifier
                                                                    .animateItem(
                                                                        fadeInSpec = null,
                                                                        fadeOutSpec = null,
                                                                        placementSpec = tween(180)
                                                                    )
                                                                    .graphicsLayer {
                                                                        if (isDraggingThis) alpha = 0.25f
                                                                    }
                                                            )
                                                        }
                                                    }

                                                    // Floating grid card overlay
                                                    if (draggingPresetGridName != null) {
                                                        val draggedPreset = vm.brushPresets.firstOrNull { it.name == draggingPresetGridName }
                                                        if (draggedPreset != null) {
                                                            val cardWidthDp = with(density) { (presetGridCardWidthPx.takeIf { it > 0f } ?: 80f).toDp() }
                                                            val cardHeightDp = with(density) { (presetGridCardHeightPx.takeIf { it > 0f } ?: 80f).toDp() }
                                                            PresetGridDragOverlay(
                                                                preset = draggedPreset,
                                                                offsetX = presetGridFloatingX,
                                                                offsetY = presetGridFloatingY,
                                                                cardWidthDp = if (cardWidthDp > 0.dp) cardWidthDp else 80.dp,
                                                                cardHeightDp = if (cardHeightDp > 0.dp) cardHeightDp else 96.dp,
                                                                isModified = vm.isBrushModified(draggedPreset.name)
                                                            )
                                                        }
                                                    }
                                                }
                                            } else {
                                                Box(
                                                    modifier = Modifier
                                                        .fillMaxSize()
                                                        .onGloballyPositioned { coordinates ->
                                                            presetListViewportHeightPx = coordinates.size.height.toFloat()
                                                        }
                                                ) {
                                                    LazyColumn(
                                                        state = presetScrollState,
                                                        userScrollEnabled = draggingPresetName == null,
                                                        modifier = Modifier
                                                            .fillMaxSize()
                                                            .padding(horizontal = 12.dp),
                                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                                    ) {
                                                        items(filtered, key = { it.name }, contentType = { "preset_list" }) { preset ->
                                                            val isSelected = preset.index == vm.brushPresetIndex
                                                            val isFav = vm.isFavoriteBrush(preset.name)
                                                            val isDraggingThis = draggingPresetName == preset.name
                                                            val canReorder = !isBatchMode && !isSearchActive && selectedCategory != "最近"
                                                            val subtitleText = if (isSearchActive && searchQuery.isNotBlank() && selectedCategory == "全部") {
                                                                brushCategoryDisplayName(context, preset.group)
                                                            } else null

                                                            val itemGesture = if (canReorder) {
                                                                Modifier.reorderDragGesture(
                                                                    key = preset.name,
                                                                    onTap = {
                                                                        if (isSelected) view = BrushView.Detail(preset.index)
                                                                        else vm.selectBrushPreset(preset.index)
                                                                    },
                                                                    onLongPressOnly = {
                                                                        reorderPresetName = preset.name
                                                                    },
                                                                    onDragStart = { offset ->
                                                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                                        val itemInfo = presetScrollState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == preset.name }
                                                                        val itemTop = itemInfo?.offset?.toFloat()
                                                                            ?: (filtered.indexOfFirst { it.name == preset.name } * presetRowHeightPx)
                                                                        presetListDragYOffsetInItem = offset.y
                                                                        presetListFloatingY = itemTop
                                                                        presetListFingerYInViewport = itemTop + offset.y
                                                                        draggingPresetName = preset.name
                                                                    },
                                                                    onDrag = { change, dragAmount ->
                                                                        presetListFingerYInViewport += dragAmount.y
                                                                        val minY = 0f
                                                                        val maxY = (presetListViewportHeightPx - presetRowHeightPx).coerceAtLeast(0f)
                                                                        presetListFloatingY = (presetListFingerYInViewport - presetListDragYOffsetInItem).coerceIn(minY, maxY)

                                                                        val topDist = autoScrollThreshold - presetListFloatingY
                                                                        val bottomDist = if (presetListViewportHeightPx > 0f) {
                                                                            (presetListFloatingY + presetRowHeightPx) - (presetListViewportHeightPx - autoScrollThreshold)
                                                                        } else 0f
                                                                        presetListAutoScrollSpeed = when {
                                                                            topDist > 0 -> -((topDist / autoScrollThreshold).coerceIn(0f, 1f) * maxScrollSpeed)
                                                                            bottomDist > 0 -> ((bottomDist / autoScrollThreshold).coerceIn(0f, 1f) * maxScrollSpeed)
                                                                            else -> 0f
                                                                        }
                                                                        checkAndPerformPresetListReorder()
                                                                    },
                                                                    onDragEnd = {
                                                                        draggingPresetName = null
                                                                        presetListAutoScrollSpeed = 0f
                                                                    },
                                                                    onDragCancel = {
                                                                        draggingPresetName = null
                                                                        presetListAutoScrollSpeed = 0f
                                                                    }
                                                                )
                                                            } else null

                                                            PresetListRow(
                                                                preset = preset,
                                                                isSelected = isSelected,
                                                                isFav = isFav,
                                                                isModified = vm.isBrushModified(preset.name),
                                                                isBatchMode = isBatchMode,
                                                                isChecked = preset.name in selectedPresets,
                                                                subtitleText = subtitleText,
                                                                onClick = {
                                                                    if (isBatchMode) {
                                                                        selectedPresets = if (preset.name in selectedPresets) {
                                                                            selectedPresets - preset.name
                                                                        } else {
                                                                            selectedPresets + preset.name
                                                                        }
                                                                    } else {
                                                                        if (isSelected) view = BrushView.Detail(preset.index)
                                                                        else vm.selectBrushPreset(preset.index)
                                                                    }
                                                                },
                                                                onLongClick = {
                                                                    if (!isBatchMode) {
                                                                        reorderPresetName = preset.name
                                                                    }
                                                                },
                                                                onToggleFav = {
                                                                    vm.toggleFavoriteBrush(preset.name)
                                                                },
                                                                itemGestureModifier = itemGesture,
                                                                modifier = Modifier
                                                                    .animateItem(
                                                                        fadeInSpec = null,
                                                                        fadeOutSpec = null,
                                                                        placementSpec = tween(180)
                                                                    )
                                                                    .graphicsLayer {
                                                                        if (isDraggingThis) alpha = 0.25f
                                                                    }
                                                            )
                                                        }
                                                    }

                                                    // Floating list row overlay
                                                    if (draggingPresetName != null) {
                                                        val draggedPreset = vm.brushPresets.firstOrNull { it.name == draggingPresetName }
                                                        if (draggedPreset != null) {
                                                            PresetListDragOverlay(
                                                                preset = draggedPreset,
                                                                offsetY = presetListFloatingY,
                                                                isFav = vm.isFavoriteBrush(draggedPreset.name),
                                                                isModified = vm.isBrushModified(draggedPreset.name),
                                                                modifier = Modifier.padding(horizontal = 12.dp)
                                                            )
                                                        }
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
                                    .height(44.dp)
                                    .background(Morandi.panel.copy(alpha = opacity))
                                    .padding(horizontal = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (isBatchMode) {
                                    val hasSelection = selectedPresets.isNotEmpty()
                                    Row(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(if (hasSelection) Morandi.panelHi else Morandi.panelHi.copy(alpha = 0.4f))
                                            .clickable(enabled = hasSelection) { showBatchMoveDialog = true }
                                            .padding(horizontal = 10.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Icon(
                                            painter = painterResource(R.drawable.ic_folder),
                                            contentDescription = null,
                                            tint = if (hasSelection) Morandi.text else Morandi.subText.copy(alpha = 0.4f),
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Text(
                                            stringResource(R.string.brush_batch_move),
                                            color = if (hasSelection) Morandi.text else Morandi.subText.copy(alpha = 0.4f),
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }

                                    Spacer(Modifier.weight(1f))

                                    Row(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(if (hasSelection) Color(0xFFC86464).copy(alpha = 0.15f) else Morandi.panelHi.copy(alpha = 0.4f))
                                            .clickable(enabled = hasSelection) { showBatchDeleteConfirm = true }
                                            .padding(horizontal = 10.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Icon(
                                            painter = painterResource(R.drawable.ic_trash),
                                            contentDescription = null,
                                            tint = if (hasSelection) Color(0xFFC86464) else Morandi.subText.copy(alpha = 0.4f),
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Text(
                                            stringResource(R.string.brush_batch_delete),
                                            color = if (hasSelection) Color(0xFFC86464) else Morandi.subText.copy(alpha = 0.4f),
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                } else {
                                    Row(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(Morandi.panelHi.copy(alpha = 0.65f))
                                            .clickable { showNewBrushDialog = true }
                                            .padding(horizontal = 10.dp, vertical = 5.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Icon(painterResource(R.drawable.ic_plus), contentDescription = null, tint = Morandi.icon, modifier = Modifier.size(13.dp))
                                        Text(stringResource(R.string.brush_action_new), color = Morandi.text, fontSize = 11.5.sp, fontWeight = FontWeight.Medium)
                                    }

                                    Spacer(Modifier.width(8.dp))

                                    Row(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(Morandi.panelHi.copy(alpha = 0.65f))
                                            .clickable { importBrushLauncher.launch(arrayOf("*/*")) }
                                            .padding(horizontal = 10.dp, vertical = 5.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Icon(painterResource(R.drawable.ic_export_tab), contentDescription = null, tint = Morandi.icon, modifier = Modifier.size(13.dp))
                                        Text(stringResource(R.string.brush_action_import), color = Morandi.text, fontSize = 11.5.sp, fontWeight = FontWeight.Medium)
                                    }

                                    Spacer(Modifier.weight(1f))

                                    Box(
                                        modifier = Modifier
                                            .size(28.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(Morandi.panelHi.copy(alpha = 0.65f))
                                            .clickable { showNewGroupDialog = true },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            painterResource(R.drawable.ic_folder_plus),
                                            contentDescription = stringResource(R.string.brush_action_new_group),
                                            tint = Morandi.icon,
                                            modifier = Modifier.size(15.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                    is BrushView.Detail -> {
                        BrushPropertyPage(
                            vm = vm,
                            presetIndex = v.index,
                            onBack = { view = BrushView.List },
                            onOpenStudio = {
                                if (vm.brushPresetIndex != v.index) {
                                    vm.selectBrushPreset(v.index)
                                }
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
        cardContent(modifier.reportUiSlot(vm, UiPanelSlot.PINNED_BRUSH_PANEL))
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

            val (baseAlign, basePaddingModifier) = if (vm.floatingUiConfig.enabled) {
                val hostPos = vm.floatingUiConfig.toolRailPosition
                val mDp = vm.floatingUiConfig.popupInwardMarginDp(hostPos).dp
                when (hostPos.edge) {
                    com.reverie.paint.model.PanelEdge.LEFT -> {
                        when (hostPos.alignment) {
                            com.reverie.paint.model.PanelAlignment.START -> Alignment.TopStart to Modifier.padding(start = mDp, top = 16.dp)
                            com.reverie.paint.model.PanelAlignment.CENTER -> Alignment.CenterStart to Modifier.padding(start = mDp)
                            com.reverie.paint.model.PanelAlignment.END -> Alignment.BottomStart to Modifier.padding(start = mDp, bottom = 16.dp)
                        }
                    }
                    com.reverie.paint.model.PanelEdge.RIGHT -> {
                        when (hostPos.alignment) {
                            com.reverie.paint.model.PanelAlignment.START -> Alignment.TopEnd to Modifier.padding(end = mDp, top = 16.dp)
                            com.reverie.paint.model.PanelAlignment.CENTER -> Alignment.CenterEnd to Modifier.padding(end = mDp)
                            com.reverie.paint.model.PanelAlignment.END -> Alignment.BottomEnd to Modifier.padding(end = mDp, bottom = 16.dp)
                        }
                    }
                    com.reverie.paint.model.PanelEdge.TOP -> {
                        when (hostPos.alignment) {
                            com.reverie.paint.model.PanelAlignment.START -> Alignment.TopStart to Modifier.padding(start = 16.dp, top = mDp)
                            com.reverie.paint.model.PanelAlignment.CENTER -> Alignment.TopCenter to Modifier.padding(top = mDp)
                            com.reverie.paint.model.PanelAlignment.END -> Alignment.TopEnd to Modifier.padding(end = 16.dp, top = mDp)
                        }
                    }
                    com.reverie.paint.model.PanelEdge.BOTTOM -> {
                        when (hostPos.alignment) {
                            com.reverie.paint.model.PanelAlignment.START -> Alignment.BottomStart to Modifier.padding(start = 16.dp, bottom = mDp)
                            com.reverie.paint.model.PanelAlignment.CENTER -> Alignment.BottomCenter to Modifier.padding(bottom = mDp)
                            com.reverie.paint.model.PanelAlignment.END -> Alignment.BottomEnd to Modifier.padding(end = 16.dp, bottom = mDp)
                        }
                    }
                }
            } else {
                (if (vm.leftHandMode) Alignment.CenterEnd else Alignment.CenterStart) to Modifier.offset {
                    IntOffset(
                        (if (vm.leftHandMode) -baseStartOffsetPx + offsetX else baseStartOffsetPx + offsetX).roundToInt(),
                        offsetY.roundToInt(),
                    )
                }
            }

            cardContent(
                Modifier
                    .align(baseAlign)
                    .then(basePaddingModifier)
                    .then(
                        if (vm.floatingUiConfig.enabled) {
                            Modifier.offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
                        } else Modifier
                    )
            )
        }
    }

    // ---- dialogs -----------------------------------------------------
    if (showNewBrushDialog) {
        TextInputGuard(vm)
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
        TextInputGuard(vm)
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
        TextInputGuard(vm)
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
            isSpecialPinned = isSpecialPinned,
            canMoveUp = !isSpecialPinned && catIdx > 3,
            canMoveDown = !isSpecialPinned && catIdx >= 3 && catIdx < categories.size - 1,
            onDismiss = { categoryMenuTarget = null },
            onMoveUp = { vm.moveCategoryUp(cat, categories) },
            onMoveDown = { vm.moveCategoryDown(cat, categories) },
            onExportGroup = { vm.exportBrushGroup(context, cat) },
            onBatchManage = {
                selectedCategory = cat
                isBatchMode = true
                selectedPresets = emptySet()
            },
            onRename = { renameCategoryTarget = cat },
            onDelete = {
                deleteGroupWithPresets = true
                groupPendingDelete = cat
            },
        )
    }
    if (renameCategoryTarget != null) {
        TextInputGuard(vm)
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
        val presetsInGrp = remember(grp, vm.brushPresets) { vm.brushPresets.filter { it.group == grp } }
        val customCount = presetsInGrp.count { !it.isBuiltIn }
        val builtInCount = presetsInGrp.count { it.isBuiltIn }

        androidx.compose.material3.AlertDialog(
            onDismissRequest = { groupPendingDelete = null },
            title = { Text(stringResource(R.string.brush_delete_group_title), color = Morandi.text, fontSize = 15.sp) },
            text = {
                Column(Modifier.fillMaxWidth()) {
                    Text(
                        stringResource(R.string.brush_delete_group_msg, brushCategoryDisplayName(grp)),
                        color = Morandi.text,
                        fontSize = 13.sp
                    )
                    if (customCount > 0) {
                        Spacer(Modifier.height(10.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { deleteGroupWithPresets = !deleteGroupWithPresets }
                                .padding(vertical = 4.dp)
                        ) {
                            Checkbox(
                                checked = deleteGroupWithPresets,
                                onCheckedChange = { deleteGroupWithPresets = it },
                                colors = CheckboxDefaults.colors(
                                    checkedColor = Morandi.accent,
                                    uncheckedColor = Morandi.subText,
                                    checkmarkColor = Color.White
                                ),
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                stringResource(R.string.brush_delete_group_with_presets, customCount),
                                color = Morandi.text,
                                fontSize = 12.sp
                            )
                        }
                    }
                    if (builtInCount > 0) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            stringResource(R.string.brush_delete_group_builtin_note, builtInCount),
                            color = Morandi.subText,
                            fontSize = 11.sp
                        )
                    }
                }
            },
            confirmButton = {
                ReTextButton(
                    stringResource(R.string.common_delete),
                    onClick = {
                        vm.deleteBrushGroup(grp, deletePresets = (customCount > 0 && deleteGroupWithPresets))
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
    if (showBatchDeleteConfirm) {
        val targets = remember(selectedPresets, vm.brushPresets) {
            vm.brushPresets.filter { it.name in selectedPresets }
        }
        val customCount = targets.count { !it.isBuiltIn }
        val builtInCount = targets.count { it.isBuiltIn }

        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showBatchDeleteConfirm = false },
            title = { Text(stringResource(R.string.brush_batch_delete_dialog_title), color = Morandi.text, fontSize = 15.sp) },
            text = {
                Column(Modifier.fillMaxWidth()) {
                    Text(
                        stringResource(R.string.brush_batch_delete_dialog_msg, customCount),
                        color = Morandi.text,
                        fontSize = 13.sp
                    )
                    if (builtInCount > 0) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            stringResource(R.string.brush_batch_delete_builtin_skipped, builtInCount),
                            color = Morandi.subText,
                            fontSize = 11.sp
                        )
                    }
                }
            },
            confirmButton = {
                ReTextButton(
                    stringResource(R.string.common_delete),
                    onClick = {
                        val deleted = vm.deleteBrushPresetsBatch(selectedPresets.toList())
                        android.widget.Toast.makeText(
                            context,
                            context.getString(R.string.brush_batch_delete_success, deleted),
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                        showBatchDeleteConfirm = false
                        isBatchMode = false
                        selectedPresets = emptySet()
                    },
                    textColor = Color(0xFFC86464),
                )
            },
            dismissButton = {
                ReTextButton(stringResource(R.string.common_cancel), { showBatchDeleteConfirm = false }, textColor = Morandi.subText)
            },
            containerColor = Morandi.panelHi,
        )
    }
    if (showBatchMoveDialog) {
        MoveBrushGroupDialog(
            presetName = stringResource(R.string.brush_batch_selected_count, selectedPresets.size),
            groups = categories.filter { it !in listOf("全部", "常用", "最近") },
            onDismiss = { showBatchMoveDialog = false },
            onMove = { g ->
                vm.moveBrushPresetsBatch(selectedPresets.toList(), g)
                android.widget.Toast.makeText(
                    context,
                    context.getString(R.string.brush_batch_move_success, selectedPresets.size, brushCategoryDisplayName(context, g)),
                    android.widget.Toast.LENGTH_SHORT
                ).show()
                selectedCategory = g
                showBatchMoveDialog = false
                isBatchMode = false
                selectedPresets = emptySet()
            },
            title = stringResource(R.string.brush_batch_move_dialog_title),
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
    isBatchMode: Boolean = false,
    isChecked: Boolean = false,
    onClick: () -> Unit = {},
    onLongClick: () -> Unit = {},
    itemGestureModifier: Modifier? = null,
    modifier: Modifier = Modifier,
) {
    val cellBg = when {
        isBatchMode && isChecked -> Morandi.accent.copy(alpha = 0.22f)
        isSelected -> Morandi.accent.copy(alpha = 0.18f)
        else -> Morandi.panelHi.copy(alpha = 0.45f)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(cellBg)
            .then(
                itemGestureModifier ?: Modifier.combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick,
                )
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
                            if (isSelected && !isBatchMode) {
                                Modifier.graphicsLayer {
                                    scaleX = 1.04f
                                    scaleY = 1.04f
                                }
                            } else Modifier
                        )
                        .clip(RoundedCornerShape(6.dp)),
                )
            }
            if (isBatchMode) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(4.dp)
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(if (isChecked) Morandi.accent else Morandi.panel.copy(alpha = 0.85f))
                        .border(1.dp, if (isChecked) Morandi.accent else Morandi.border, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    if (isChecked) {
                        Icon(
                            painter = painterResource(R.drawable.ic_check),
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(11.dp)
                        )
                    }
                }
            } else if (isModified) {
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
            color = if ((isSelected && !isBatchMode) || (isBatchMode && isChecked)) Morandi.text else Morandi.subText,
            fontSize = 10.sp,
            fontWeight = if ((isSelected && !isBatchMode) || (isBatchMode && isChecked)) FontWeight.SemiBold else FontWeight.Normal,
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
    isBatchMode: Boolean = false,
    isChecked: Boolean = false,
    subtitleText: String? = null,
    onClick: () -> Unit = {},
    onLongClick: () -> Unit = {},
    onToggleFav: () -> Unit,
    itemGestureModifier: Modifier? = null,
    modifier: Modifier = Modifier,
) {
    val cellBg = when {
        isBatchMode && isChecked -> Morandi.accent.copy(alpha = 0.2f)
        isSelected -> Morandi.accent.copy(alpha = 0.16f)
        else -> Morandi.panelHi.copy(alpha = 0.35f)
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(cellBg)
            .then(
                itemGestureModifier ?: Modifier.combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick,
                )
            )
            .padding(start = 8.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isBatchMode) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(if (isChecked) Morandi.accent else Color.Transparent)
                    .border(1.5.dp, if (isChecked) Morandi.accent else Morandi.border, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                if (isChecked) {
                    Icon(
                        painter = painterResource(R.drawable.ic_check),
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(12.dp)
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
        }

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
                            if (isSelected && !isBatchMode) {
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
        val context = LocalContext.current
        val translatedName = BrushPresetTranslations.getTranslatedDisplayName(context, preset.name)
        val inUseText = if (isSelected && !isBatchMode) stringResource(R.string.brush_in_use_hint) else null
        val fullSubtitle = remember(translatedName, inUseText, subtitleText) {
            val parts = mutableListOf<String>()
            if (translatedName != null) {
                parts.add(translatedName)
            }
            if (inUseText != null) {
                parts.add(inUseText)
            }
            if (subtitleText != null) {
                parts.add(subtitleText)
            }
            if (parts.isNotEmpty()) parts.joinToString(" · ") else null
        }

        Column(Modifier.weight(1f)) {
            Text(
                preset.name,
                color = if (isSelected && !isBatchMode) Morandi.text else Morandi.subText,
                fontSize = 12.sp,
                fontWeight = if (isSelected && !isBatchMode) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (fullSubtitle != null) {
                Text(
                    fullSubtitle,
                    color = Morandi.subText.copy(alpha = 0.75f),
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        // Star button (hidden in batch mode)
        if (!isBatchMode) {
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
}

/** Long-press menu for categories: move up/down, rename, delete. */
