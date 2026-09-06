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
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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

    Box(
        modifier = modifier
            .fillMaxSize()
            .systemHoverIcon(context)
            .noRippleClickable(onClose),
    ) {
        Box(
            modifier = Modifier
                .systemHoverIcon(context)
                .padding(start = 48.dp)
                .align(Alignment.CenterStart)
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
                .clickable(enabled = false) {}
        ) {
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
                                                .height(42.dp)
                                                .background(if (sel) Morandi.panelHi.copy(alpha = opacity) else Color.Transparent)
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
                                                Text(
                                                    text = cat,
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
                                        Text("笔刷库", color = Morandi.text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                        Spacer(Modifier.weight(1f))
                                        Text(
                                            "${filtered.size} 预设",
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
                                                contentDescription = if (vm.brushPanelGridView) "切换为列表视图" else "切换为网格视图",
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
                                                        "常用" -> "暂无常用笔刷"
                                                        "最近" -> "暂无最近使用记录"
                                                        else -> "该分类暂无笔刷"
                                                    },
                                                    color = Morandi.subText,
                                                    fontSize = 13.sp,
                                                )
                                                Spacer(Modifier.height(2.dp))
                                                Text(
                                                    when (selectedCategory) {
                                                        "常用" -> "点击笔刷星标即可加入常用"
                                                        "最近" -> "使用笔刷后将自动记录"
                                                        else -> "点击下方加号添加笔刷"
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
                                    Text("新建笔刷", color = Morandi.text, fontSize = 11.sp, fontWeight = FontWeight.Medium)
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
                                    Text("导入", color = Morandi.text, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                                }

                                Spacer(Modifier.weight(1f))

                                Icon(
                                    painterResource(R.drawable.ic_folder_plus),
                                    contentDescription = "新建组",
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
                                vm.brushPresetIndex = v.index
                                vm.brushStudioOpen = true
                                onClose()
                            },
                        )
                    }
                }
            }
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
        CategoryMenuDialog(
            categoryName = cat,
            isBuiltIn = isBuiltIn,
            canMoveUp = !isSpecialPinned && catIdx > 3,
            canMoveDown = !isSpecialPinned && catIdx >= 3 && catIdx < categories.size - 1,
            onDismiss = { categoryMenuTarget = null },
            onMoveUp = { vm.moveCategoryUp(cat, categories) },
            onMoveDown = { vm.moveCategoryDown(cat, categories) },
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
        ReorderBrushMenu(
            presetName = rp,
            isBuiltIn = isBuiltIn,
            isFavorite = isFav,
            onDismiss = { reorderPresetName = null },
            onToggleFavorite = { vm.toggleFavoriteBrush(rp) },
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
            title = { Text("删除自定义分组", color = Morandi.text, fontSize = 15.sp) },
            text = { Text("确定要删除分类「$grp」吗？组内的笔刷将保留并移至默认分类。", color = Morandi.subText, fontSize = 13.sp) },
            confirmButton = {
                ReTextButton(
                    "删除",
                    onClick = {
                    vm.deleteBrushGroup(grp)
                    if (selectedCategory == grp) selectedCategory = "全部"
                    groupPendingDelete = null
                },
                    textColor = Color(0xFFC86464),
                )
            },
            dismissButton = {
                ReTextButton("取消", { groupPendingDelete = null }, textColor = Morandi.subText)
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
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cellBg = if (isSelected) Morandi.accent.copy(alpha = 0.12f) else Morandi.panelHi.copy(alpha = 0.5f)
    val cellBorderColor = if (isSelected) Morandi.accent else Color.Transparent

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(cellBg)
            .border(
                width = if (isSelected) 1.5.dp else 0.dp,
                color = cellBorderColor,
                shape = RoundedCornerShape(8.dp)
            )
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
                .background(Morandi.panelHi),
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
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onToggleFav: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cellBg = if (isSelected) Morandi.accent.copy(alpha = 0.10f) else Morandi.panelHi.copy(alpha = 0.5f)
    val cellBorderColor = if (isSelected) Morandi.accent else Color.Transparent

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(cellBg)
            .border(
                width = if (isSelected) 1.dp else 0.dp,
                color = cellBorderColor,
                shape = RoundedCornerShape(10.dp)
            )
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .padding(start = 8.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val bmp = rememberPresetThumb(preset.name, preset.thumbBytes)
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = preset.name,
                modifier = Modifier
                    .size(38.dp)
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
                    .size(38.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(Morandi.panelHi)
            )
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
                Text("使用中 · 点按调属性", color = Morandi.subText, fontSize = 10.sp)
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
                contentDescription = if (isFav) "取消常用" else "加入常用",
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
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("分类: $categoryName", color = Morandi.text, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 15.sp) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                val menuItems = mutableListOf<Pair<String, () -> Unit>>()
                if (canMoveUp) {
                    menuItems.add("上移分类" to onMoveUp)
                }
                if (canMoveDown) {
                    menuItems.add("下移分类" to onMoveDown)
                }
                if (!isBuiltIn) {
                    menuItems.add("重命名分类" to onRename)
                    menuItems.add("删除此分类" to onDelete)
                } else {
                    menuItems.add("(内置分类 · 固定名称)" to {})
                }
                menuItems.forEach { (label, act) ->
                    val isDelete = label.startsWith("删除")
                    val isHint = label.startsWith("(")
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
            ReTextButton("取消", onDismiss, textColor = Morandi.subText)
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
        title = { Text("重命名分类", color = Morandi.text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("输入新的分类名称", color = Morandi.subText, fontSize = 12.sp)
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
                "确定",
                onClick = { onRename(name.trim()) },
                enabled = name.isNotBlank() && name.trim() != initialName,
                textColor = Morandi.accent,
            )
        },
        dismissButton = {
            ReTextButton("取消", onDismiss, textColor = Morandi.subText)
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
    onDismiss: () -> Unit,
    onToggleFavorite: () -> Unit,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onMoveGroup: () -> Unit,
    onDuplicate: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(presetName, color = Morandi.text, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 15.sp) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                val menuItems = mutableListOf(
                    (if (isFavorite) "取消常用" else "加入常用") to onToggleFavorite,
                    "复制笔刷" to onDuplicate,
                )
                if (!isBuiltIn) {
                    menuItems.add("重命名" to onRename)
                }
                menuItems.add("上移" to onUp)
                menuItems.add("下移" to onDown)
                menuItems.add("移动到组..." to onMoveGroup)
                if (!isBuiltIn) {
                    menuItems.add("删除此笔刷" to onDelete)
                }
                menuItems.forEach { (label, act) ->
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
                            color = if (label.startsWith("删除")) Color(0xFFC86464) else Morandi.text,
                            fontSize = 13.sp,
                            fontWeight = if (label.startsWith("删除")) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            ReTextButton("取消", onDismiss, textColor = Morandi.subText)
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
    var selectedGroup by remember { mutableStateOf(groups.firstOrNull() ?: "自定义") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建笔刷", color = Morandi.text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("输入笔刷名称", color = Morandi.subText, fontSize = 12.sp)
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
                Text("基准模板: Basic-1 (基础笔刷)", color = Morandi.subText.copy(alpha = 0.7f), fontSize = 11.sp)
            }
        },
        confirmButton = {
            ReTextButton("创建", { onCreate(name.trim(), selectedGroup) }, enabled = name.isNotBlank(), textColor = Morandi.accent)
        },
        dismissButton = {
            ReTextButton("取消", onDismiss, textColor = Morandi.subText)
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
        title = { Text("重命名笔刷", color = Morandi.text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("输入新名称", color = Morandi.subText, fontSize = 12.sp)
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
            ReTextButton("保存", { onRename(name.trim()) }, enabled = name.isNotBlank(), textColor = Morandi.accent)
        },
        dismissButton = {
            ReTextButton("取消", onDismiss, textColor = Morandi.subText)
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
        title = { Text("新建笔刷组", color = Morandi.text) },
        text = {
            Column {
                Text("输入组名称", color = Morandi.subText, fontSize = 13.sp)
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
                    Text("组已存在", color = Color(0xFFB05552), fontSize = 11.sp)
                }
            }
        },
        confirmButton = {
            ReTextButton(
                "创建",
                onClick = { onCreate(name.trim()) },
                enabled = name.isNotBlank() && !existing.contains(name.trim()),
                textColor = Morandi.accent,
            )
        },
        dismissButton = {
            ReTextButton("取消", onDismiss, textColor = Morandi.subText)
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
        title = { Text("移动到组", color = Morandi.text) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text(presetName, color = Morandi.subText, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.heightIn(max = 280.dp)) {
                    items(groups) { g ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { onMove(g) }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                        ) {
                            Text(g, color = Morandi.text, fontSize = 14.sp)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            ReTextButton("取消", onDismiss, textColor = Morandi.subText)
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
    var showBlendModes by remember { mutableStateOf(false) }

    val blendModeList = listOf(
        "normal" to "正常",
        "multiply" to "正片叠底",
        "screen" to "滤色",
        "overlay" to "叠加",
        "darken" to "变暗",
        "lighten" to "变亮",
        "dodge" to "颜色减淡",
        "burn" to "颜色加深",
        "hard_light" to "强光",
        "soft_light" to "柔光",
        "difference" to "差值",
        "exclusion" to "排除",
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
        modifier =
            Modifier
                .fillMaxWidth()
                .verticalScroll(scrollState),
    ) {
        // Header
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
                        .noRippleClickable(onBack),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painterResource(R.drawable.ic_chevron),
                    contentDescription = "返回",
                    tint = Morandi.icon,
                    modifier = Modifier.size(18.dp),
                )
            }
            Text(
                "笔刷设置",
                color = Morandi.text,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .noRippleClickable(onOpenStudio),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painterResource(R.drawable.ic_sliders),
                    contentDescription = "工作室",
                    tint = Morandi.accent,
                    modifier = Modifier.size(16.dp),
                )
            }
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .noRippleClickable { vm.resetBrushParams() }
                    .padding(end = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painterResource(R.drawable.ic_refresh),
                    contentDescription = "重置数值",
                    tint = Morandi.subText,
                    modifier = Modifier.size(16.dp),
                )
            }
        }

        Box(Modifier.fillMaxWidth().height(1.dp).background(Morandi.border))

        // Advanced Brush Studio entry button
        Box(modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
            ReTextButton(
                "进入高级笔刷工作室",
                onOpenStudio,
                modifier = Modifier.fillMaxWidth(),
                icon = R.drawable.ic_sliders,
                primary = true,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }

        Box(Modifier.fillMaxWidth().padding(horizontal = 14.dp).height(1.dp).background(Morandi.border.copy(alpha = 0.5f)))

        // Blend mode row button
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .noRippleClickable { showBlendModes = !showBlendModes }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                painterResource(R.drawable.ic_layerstack),
                contentDescription = null,
                tint = Morandi.accent,
                modifier = Modifier.size(18.dp),
            )
            Text("混合模式", color = Morandi.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Text(
                blendModeList.firstOrNull { it.first == vm.brushCompositeOp }?.second ?: "正常",
                color = Morandi.subText,
                fontSize = 13.sp,
            )
            Icon(
                painterResource(R.drawable.ic_chevron),
                contentDescription = null,
                tint = Morandi.subText,
                modifier = Modifier.size(16.dp).rotate(if (showBlendModes) 90f else 0f),
            )
        }

        if (showBlendModes) {
            for ((opId, name) in blendModeList) {
                val sel = vm.brushCompositeOp == opId
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .noRippleClickable { vm.updateBrushCompositeOp(opId) }
                            .background(if (sel) Morandi.accent.copy(alpha = 0.18f) else Color.Transparent)
                            .padding(horizontal = 26.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        name,
                        color = if (sel) Morandi.accent else Morandi.text,
                        fontSize = 13.sp,
                        fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                    )
                    Spacer(Modifier.weight(1f))
                    if (sel) {
                        Icon(
                            painterResource(R.drawable.ic_check),
                            contentDescription = null,
                            tint = Morandi.accent,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Morandi.border))
        } else {
            Box(Modifier.fillMaxWidth().padding(horizontal = 14.dp).height(1.dp).background(Morandi.border.copy(alpha = 0.5f)))
        }

        // Parameter sliders
        BrushParamReSlider("大小", vm.brushSize, 1.0, 200.0) { vm.updateBrushSize(it) }
        BrushParamReSlider("不透明度", vm.brushOpacity, 0.05, 1.0) { vm.updateBrushOpacity(it) }
        BrushParamReSlider("流量", vm.brushFlow, 0.05, 1.0) { vm.updateBrushFlow(it) }
        BrushParamReSlider("间距", vm.brushSpacing, 0.0, 1.0) { vm.updateBrushSpacing(it) }
        BrushParamReSlider("角度", vm.brushAngle, 0.0, 360.0) { vm.updateBrushAngle(it) }
        BrushParamReSlider("旋转", vm.brushRotation, 0.0, 360.0) { vm.updateBrushRotation(it) }
        BrushParamReSlider("散布", vm.brushScatter, 0.0, 1.0) { vm.updateBrushScatter(it) }
        BrushParamReSlider("渐隐", vm.brushFade, 0.0, 1.0) { vm.updateBrushFade(it) }
        BrushParamReSlider("硬度", vm.brushSoftness, 0.0, 1.0) { vm.updateBrushSoftness(it) }
        BrushParamReSlider("比例", vm.brushRatio, 0.0, 1.0) { vm.updateBrushRatio(it) }
        BrushParamReSlider("锐度", vm.brushSharpness, 0.0, 1.0) { vm.updateBrushSharpness(it) }
        
        Spacer(Modifier.height(16.dp))
    }
}


@Composable
private fun rememberBytes(bytes: ByteArray): android.graphics.Bitmap? {
    return androidx.compose.runtime.remember(bytes) {
        BrushThumbCache.get(bytes.hashCode().toString(), bytes)
    }
}

@Composable
private fun BrushParamReSlider(
    label: String,
    value: Double,
    min: Double,
    max: Double,
    onChange: (Double) -> Unit,
) {
    val fraction = ((value - min) / (max - min)).toFloat().coerceIn(0f, 1f)
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, color = Morandi.text, fontSize = 13.sp)
            Spacer(Modifier.weight(1f))
            Text(
                text = if (max > 100) "${value.toInt()}" else "${(value * 100).toInt()}%",
                color = Morandi.subText,
                fontSize = 13.sp,
            )
        }
        ReSlider(
            value = fraction,
            onValue = { f ->
                onChange(f * (max - min) + min)
            },
            modifier = Modifier.padding(horizontal = 14.dp)
        )
    }
    Box(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp).height(1.dp).background(Morandi.border.copy(alpha = 0.5f)))
}

