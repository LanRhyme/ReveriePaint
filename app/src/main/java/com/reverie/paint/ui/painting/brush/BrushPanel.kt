/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.brush

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeChild
import com.reverie.paint.ui.theme.Glass
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
        listOf("全部") +
            vm.brushPresets.map { it.group }.distinct() +
            vm.customBrushGroups.filter { g -> vm.brushPresets.none { it.group == g } }
    }
    val categories = remember(rawCategories, vm.categoryOrder) {
        if (vm.categoryOrder.isEmpty()) {
            rawCategories
        } else {
            val ordered = vm.categoryOrder.filter { rawCategories.contains(it) }
            val remaining = rawCategories.filter { !vm.categoryOrder.contains(it) }
            (ordered + remaining).distinct()
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
    var draggingPresetName by remember { mutableStateOf<String?>(null) }
    var dragAccumulatedY by remember { mutableStateOf(0f) }

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

    // Continuously sync scroll positions in real time so state is never lost on sudden dismiss
    LaunchedEffect(categoryScrollState) {
        androidx.compose.runtime.snapshotFlow {
            categoryScrollState.firstVisibleItemIndex to categoryScrollState.firstVisibleItemScrollOffset
        }.collect { (idx, offset) ->
            vm.brushCategoryScrollIndex = idx
            vm.brushCategoryScrollOffset = offset
        }
    }

    LaunchedEffect(presetScrollState, selectedCategory) {
        androidx.compose.runtime.snapshotFlow {
            presetScrollState.firstVisibleItemIndex to presetScrollState.firstVisibleItemScrollOffset
        }.collect { (idx, offset) ->
            vm.saveCategoryPresetScroll(selectedCategory, idx, offset)
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
        }
    }

    // Save state on dispose / change
    DisposableEffect(selectedCategory, view) {
        onDispose {
            vm.brushPanelSelectedCategory = selectedCategory
            vm.brushCategoryScrollIndex = categoryScrollState.firstVisibleItemIndex
            vm.brushCategoryScrollOffset = categoryScrollState.firstVisibleItemScrollOffset
            vm.saveCategoryPresetScroll(
                selectedCategory,
                presetScrollState.firstVisibleItemIndex,
                presetScrollState.firstVisibleItemScrollOffset
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
                .clip(panelShape)
                .then(
                    if (vm.blurBackground && hazeState != null) {
                        Modifier.hazeChild(
                            state = hazeState,
                            style = Glass.barStyle(opacity),
                        )
                    } else {
                        Modifier.background(Morandi.panel.copy(alpha = opacity))
                    }
                )
                .border(1.dp, Morandi.border.copy(alpha = opacity), panelShape)
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
                                        .background(Morandi.panel.copy(alpha = opacity))
                                ) {
                                    items(categories) { cat ->
                                        val sel = cat == selectedCategory
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
                                            Text(
                                                text = cat,
                                                color = if (sel) Morandi.accent else Morandi.subText,
                                                fontSize = 13.sp,
                                                modifier = Modifier.padding(start = 12.dp)
                                            )
                                        }
                                    }
                                }

                                // Right preset list
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                ) {
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
                                            "${vm.brushPresets.size} 个 Krita 预设",
                                            color = Morandi.subText,
                                            fontSize = 11.sp,
                                        )
                                    }

                                    LazyColumn(
                                        state = presetScrollState,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .weight(1f)
                                            .padding(horizontal = 12.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        val filtered = if (selectedCategory == "全部") {
                                            vm.brushPresets
                                        } else {
                                            vm.brushPresets.filter { it.group == selectedCategory }
                                        }
                                        items(filtered, key = { it.name }) { preset ->
                                            val isSelected = preset.index == vm.brushPresetIndex
                                            val isDragging = draggingPresetName == preset.name
                                            val cellBg by animateColorAsState(
                                                targetValue = when {
                                                    isDragging -> Morandi.panelHi
                                                    isSelected -> Morandi.accent.copy(alpha = 0.10f)
                                                    else -> Morandi.panel.copy(alpha = 0.5f)
                                                },
                                                animationSpec = spring(dampingRatio = 0.9f, stiffness = 500f),
                                                label = "brushCellBg",
                                            )
                                            val cellBorderColor by animateColorAsState(
                                                targetValue = if (isDragging || isSelected) Morandi.accent else Color.Transparent,
                                                animationSpec = spring(dampingRatio = 0.9f, stiffness = 500f),
                                                label = "brushCellBorder",
                                            )
                                            val thumbScale by animateFloatAsState(
                                                targetValue = if (isSelected) 1.06f else 1f,
                                                animationSpec = spring(dampingRatio = 0.55f, stiffness = 400f),
                                                label = "brushThumbPop",
                                            )
                                            Row(
                                                modifier = Modifier
                                                    .animateItem()
                                                    .fillMaxWidth()
                                                    .height(52.dp)
                                                    .graphicsLayer {
                                                        if (isDragging) {
                                                            translationY = dragAccumulatedY
                                                            shadowElevation = 16f
                                                            scaleX = 1.03f
                                                            scaleY = 1.03f
                                                        }
                                                    }
                                                    .zIndex(if (isDragging) 10f else 0f)
                                                    .clip(RoundedCornerShape(10.dp))
                                                    .background(cellBg)
                                                    .border(
                                                        width = if (isDragging || isSelected) 1.dp else 0.dp,
                                                        color = cellBorderColor,
                                                        shape = RoundedCornerShape(10.dp)
                                                    )
                                                    .pointerInput(preset.name) {
                                                        detectDragGesturesAfterLongPress(
                                                            onDragStart = {
                                                                draggingPresetName = preset.name
                                                                dragAccumulatedY = 0f
                                                            },
                                                            onDragEnd = {
                                                                draggingPresetName = null
                                                                dragAccumulatedY = 0f
                                                            },
                                                            onDragCancel = {
                                                                draggingPresetName = null
                                                                dragAccumulatedY = 0f
                                                            },
                                                            onDrag = { change, dragAmount ->
                                                                change.consume()
                                                                dragAccumulatedY += dragAmount.y
                                                                val threshold = 110f
                                                                if (dragAccumulatedY > threshold) {
                                                                    val curIdx = vm.brushPresets.indexOfFirst { it.name == preset.name }
                                                                    if (curIdx >= 0 && curIdx < vm.brushPresets.size - 1) {
                                                                        vm.reorderBrushPresets(curIdx, curIdx + 1)
                                                                        dragAccumulatedY -= threshold
                                                                    }
                                                                } else if (dragAccumulatedY < -threshold) {
                                                                    val curIdx = vm.brushPresets.indexOfFirst { it.name == preset.name }
                                                                    if (curIdx > 0) {
                                                                        vm.reorderBrushPresets(curIdx, curIdx - 1)
                                                                        dragAccumulatedY += threshold
                                                                    }
                                                                }
                                                            }
                                                        )
                                                    }
                                                    .combinedClickable(
                                                        onClick = {
                                                            if (isSelected) view = BrushView.Detail(preset.index)
                                                            else vm.selectBrushPreset(preset.index)
                                                        },
                                                        onLongClick = {
                                                            reorderPresetName = preset.name
                                                        },
                                                    )
                                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                val bmp = rememberBytes(preset.thumbBytes)
                                                if (bmp != null) {
                                                    Image(
                                                        bitmap = bmp.asImageBitmap(),
                                                        contentDescription = preset.name,
                                                        modifier = Modifier
                                                            .size(38.dp)
                                                            .graphicsLayer {
                                                                scaleX = thumbScale
                                                                scaleY = thumbScale
                                                            }
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
            groups = categories.filter { it != "全部" },
            onDismiss = { showNewBrushDialog = false },
            onCreate = { name, group ->
                vm.createNewBrushPreset(name = name, group = group)
                showNewBrushDialog = false
            },
        )
    }
    if (renamePresetName != null) {
        val rn = renamePresetName!!
        val pIdx = vm.brushPresets.indexOfFirst { it.name == rn }
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
            existing = categories.filter { it != "全部" },
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
        val catIdx = categories.indexOf(cat)
        val isBuiltIn = vm.isBuiltInGroup(cat)
        CategoryMenuDialog(
            categoryName = cat,
            isBuiltIn = isBuiltIn,
            canMoveUp = catIdx > 0,
            canMoveDown = catIdx >= 0 && catIdx < categories.size - 1,
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
        ReorderBrushMenu(
            presetName = rp,
            isBuiltIn = isBuiltIn,
            onDismiss = { reorderPresetName = null },
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
            groups = categories.filter { it != "全部" },
            onDismiss = { movePresetName = null },
            onMove = { g ->
                vm.moveBrushToGroup(movePresetName!!, g)
                selectedCategory = g
                movePresetName = null
            },
        )
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
    onDismiss: () -> Unit,
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
    val preset = vm.brushPresets.getOrNull(presetIndex)
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
    androidx.compose.runtime.remember(bytes) { }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
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

