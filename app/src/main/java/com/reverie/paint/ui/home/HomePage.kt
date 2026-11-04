/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.home

import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.graphics.TransformOrigin
import kotlin.math.roundToInt
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.reverie.paint.ui.components.ReTextButton
import com.reverie.paint.ui.components.TelemetryNoticeBar
import com.reverie.paint.R
import com.reverie.paint.ui.components.ReIconButton
import com.reverie.paint.ui.dialog.RecentAutoSavesDialog
import com.reverie.paint.core.*
import com.reverie.paint.model.Project
import com.reverie.paint.ui.theme.AppColors
import com.reverie.paint.ui.theme.Motion
import com.reverie.paint.ui.theme.Theme
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.haze
import java.io.File
import java.text.SimpleDateFormat
import java.util.*



@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomePage(vm: PaintViewModel) {
    val colors = Theme.current
    val selectedTab = vm.homeSelectedTab
    val context = LocalContext.current
    val hazeState = remember { HazeState() }

    // Search and selection modes
    var isSearchActive by remember { mutableStateOf(false) }
    var isSelectMode by remember { mutableStateOf(false) }
    val selectedProjects = remember { mutableStateListOf<Project>() }

    // Dialogs
    var showNewFolderDialog by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }
    var showRenameDialog by remember { mutableStateOf(false) }
    var targetRenameProject by remember { mutableStateOf<Project?>(null) }
    var newProjectName by remember { mutableStateOf("") }

    var showMoveDialog by remember { mutableStateOf(false) }
    var targetMoveProjects by remember { mutableStateOf<List<Project>>(emptyList()) }

    // Deletion confirmation dialogs (Secondary confirmation)
    var projectToDelete by remember { mutableStateOf<Project?>(null) }
    var showBatchDeleteConfirm by remember { mutableStateOf(false) }

    var showMoreMenu by remember { mutableStateOf(false) }
    var showAutoSaveHistoryDialog by remember { mutableStateOf(false) }

    val importLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenMultipleDocuments(),
        ) { uris ->
            if (uris.isNotEmpty()) {
                vm.importDocuments(uris, context)
            }
        }

    // Long-press Context Menu on card
    var longPressedProject by remember { mutableStateOf<Project?>(null) }
    var detailsProject by remember { mutableStateOf<Project?>(null) }

    // Choose folder cover dialog
    var showChooseCoverDialog by remember { mutableStateOf(false) }
    var targetCoverFolder by remember { mutableStateOf<Project?>(null) }

    // Drag-and-drop projects to album stack or merge into new stack
    var draggingProject by remember { mutableStateOf<Project?>(null) }
    var dragTouchRootPos by remember { mutableStateOf(Offset.Zero) }
    var dragStartRootPos by remember { mutableStateOf(Offset.Zero) }
    var hoveredFolderKey by remember { mutableStateOf<String?>(null) }
    var hoveredMergeArtworkKey by remember { mutableStateOf<String?>(null) }
    var isMergeReady by remember { mutableStateOf(false) }
    var isHoveringExitFolder by remember { mutableStateOf(false) }
    var folderHeaderBounds by remember { mutableStateOf<Rect?>(null) }
    var lastClickedFolderBounds by remember { mutableStateOf<Rect?>(null) }
    val folderBoundsMap = remember { mutableStateMapOf<String, Rect>() }
    val artworkBoundsMap = remember { mutableStateMapOf<String, Rect>() }
    val allItemBoundsMap = remember { mutableStateMapOf<String, Rect>() }

    // Filter projects by search query
    val currentFolder = vm.currentFolder
    val displayProjects =
        remember(vm.projects, vm.searchQuery, currentFolder) {
            val q = vm.searchQuery.trim().lowercase()
            if (q.isEmpty()) {
                vm.projects
            } else {
                vm.projects.filter { it.name.lowercase().contains(q) }
            }
        }

    // Refresh projects upon entering
    LaunchedEffect(currentFolder, selectedTab, vm.currentPage) {
        if (selectedTab == 0) {
            vm.refreshProjects()
        }
    }

    // 滚动位置保留: 主页与画集各持一个网格状态, 进画集再返回时停在原处 (旧实现每次切页都回顶)
    val rootGridState = rememberLazyGridState()
    val folderGridState = rememberLazyGridState()

    // 进入画集从顶部开始
    LaunchedEffect(currentFolder?.filePath) {
        if (currentFolder != null) folderGridState.scrollToItem(0)
    }

    // 搜索条件变化时回到顶部, 避免停在过滤后越界的旧索引上
    LaunchedEffect(vm.searchQuery) {
        if (vm.searchQuery.isNotEmpty()) {
            if (currentFolder == null) rootGridState.scrollToItem(0) else folderGridState.scrollToItem(0)
        }
    }

    // 拖拽命中表跟随数据清理, 避免已删除的画集留下"幽灵"投放区
    LaunchedEffect(currentFolder?.filePath, displayProjects) {
        val aliveFolders = HashSet<String>(displayProjects.size)
        val aliveArtworks = HashSet<String>(displayProjects.size)
        displayProjects.forEach {
            if (it.isFolder) aliveFolders.add(it.filePath) else aliveArtworks.add(it.filePath)
        }
        folderBoundsMap.keys.retainAll(aliveFolders)
        artworkBoundsMap.keys.retainAll(aliveArtworks)
        allItemBoundsMap.keys.retainAll(aliveFolders + aliveArtworks)
    }

    // Handle back navigation for nested states (Folder, Selection Mode, Search Mode, Settings Subpage)
    val backEnabled = currentFolder != null || isSelectMode || isSearchActive || (selectedTab == 1 && vm.settingsInitialSubPage != "MAIN")
    BackHandler(enabled = backEnabled) {
        when {
            isSelectMode -> {
                isSelectMode = false
                selectedProjects.clear()
            }

            isSearchActive -> {
                isSearchActive = false
                vm.searchQuery = ""
            }

            currentFolder != null -> {
                vm.currentFolder = null
                vm.refreshProjects()
            }

            selectedTab == 1 -> {
                vm.homeSelectedTab = 0
            }
        }
    }

    val toastStackCreated = stringResource(R.string.gallery_toast_stack_created)
    val toastStackDeleted = stringResource(R.string.gallery_toast_stack_deleted)
    val toastArtworkDeleted = stringResource(R.string.gallery_toast_artwork_deleted)
    val toastBatchDeleted = stringResource(R.string.gallery_toast_batch_deleted)
    val toastCoverSet = stringResource(R.string.gallery_toast_cover_set)
    val toastCoverCleared = stringResource(R.string.gallery_toast_cover_cleared)
    val toastMovedToStack = stringResource(R.string.gallery_toast_moved_to_stack)
    val toastStackMerged = stringResource(R.string.gallery_toast_stack_merged)
    val toastMovedOut = stringResource(R.string.gallery_toast_moved_out)
    val defaultStackBaseName = stringResource(R.string.gallery_new_stack_default)
    val defaultStackIndexedFormat = stringResource(R.string.gallery_new_stack_default_indexed)
    val defaultProcessingText = stringResource(R.string.gallery_processing)

    // Custom Styled Dialog: Create Stack / Folder (新建画集)
    if (showNewFolderDialog) {
        NewFolderDialog(
            colors = colors,
            folderName = newFolderName,
            onFolderNameChange = { newFolderName = it },
            onCreate = { name ->
                vm.createFolder(name)
                Toast.makeText(context, String.format(toastStackCreated, name), Toast.LENGTH_SHORT).show()
            },
            onDismiss = { showNewFolderDialog = false },
        )
    }

    if (showAutoSaveHistoryDialog) {
        RecentAutoSavesDialog(
            vm = vm,
            onDismiss = { showAutoSaveHistoryDialog = false },
        )
    }

    if (showRenameDialog && targetRenameProject != null) {
        val renameTarget = targetRenameProject!!
        RenameProjectDialog(
            colors = colors,
            project = renameTarget,
            name = newProjectName,
            onNameChange = { newProjectName = it },
            onRename = { newName -> vm.renameProject(renameTarget, newName) },
            onDismiss = {
                showRenameDialog = false
                targetRenameProject = null
            },
        )
    }

    if (showMoveDialog && targetMoveProjects.isNotEmpty()) {
        MoveProjectDialog(
            colors = colors,
            vm = vm,
            currentFolder = currentFolder,
            targetMoveProjects = targetMoveProjects,
            onMoved = {
                showMoveDialog = false
                isSelectMode = false
                selectedProjects.clear()
            },
            onDismiss = { showMoveDialog = false },
        )
    }

    if (showChooseCoverDialog && targetCoverFolder != null) {
        val folder = targetCoverFolder!!
        ChooseFolderCoverDialog(
            colors = colors,
            folder = folder,
            onSelectCover = { coverProject ->
                vm.setFolderCover(folder, coverProject)
                Toast.makeText(context, String.format(toastCoverSet, coverProject.name), Toast.LENGTH_SHORT).show()
            },
            onResetCover = {
                vm.clearFolderCover(folder)
                Toast.makeText(context, toastCoverCleared, Toast.LENGTH_SHORT).show()
            },
            onDismiss = {
                showChooseCoverDialog = false
                targetCoverFolder = null
            },
        )
    }

    if (projectToDelete != null) {
        val deleteTarget = projectToDelete!!
        DeleteProjectDialog(
            colors = colors,
            target = deleteTarget,
            onDelete = {
                vm.deleteProject(deleteTarget)
                Toast.makeText(context, if (deleteTarget.isFolder) toastStackDeleted else toastArtworkDeleted, Toast.LENGTH_SHORT).show()
            },
            onDismiss = { projectToDelete = null },
        )
    }

    if (detailsProject != null) {
        ProjectDetailsDialog(
            colors = colors,
            project = detailsProject!!,
            onDismiss = { detailsProject = null },
        )
    }

    if (showBatchDeleteConfirm && selectedProjects.isNotEmpty()) {
        val deleteCount = selectedProjects.size
        BatchDeleteConfirmDialog(
            colors = colors,
            count = deleteCount,
            onConfirm = {
                vm.deleteProjects(selectedProjects.toList())
                selectedProjects.clear()
                isSelectMode = false
                Toast.makeText(context, String.format(toastBatchDeleted, deleteCount), Toast.LENGTH_SHORT).show()
            },
            onDismiss = { showBatchDeleteConfirm = false },
        )
    }

    if (vm.isBlockingLoading && selectedTab == 0) {
        Dialog(
            onDismissRequest = {},
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = colors.panel,
                tonalElevation = 8.dp,
                border = androidx.compose.foundation.BorderStroke(1.dp, colors.border),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = colors.accent,
                        strokeWidth = 2.5.dp,
                    )
                    Text(
                        text = vm.blockingLoadingMessage.ifBlank { defaultProcessingText },
                        color = colors.text,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(colors.bg),
    ) {
        AnimatedContent(
            targetState = selectedTab,
            transitionSpec = {
                fadeIn(tween(220, easing = FastOutSlowInEasing))
                    .togetherWith(fadeOut(tween(160)))
            },
            modifier = Modifier.fillMaxSize().haze(hazeState),
            label = "HomeTabTransition",
        ) { tabIndex ->
            if (tabIndex == 0) {
                Box(modifier = Modifier.fillMaxSize()) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        // Floating Morandi Header (matching PaintingPage style)
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // Animated Header Title and Back Button Transition
                            AnimatedContent(
                                targetState =
                                    if (isSelectMode) {
                                        "SELECT"
                                    } else if (currentFolder != null) {
                                        "FOLDER"
                                    } else {
                                        "GALLERY"
                                    },
                                transitionSpec = {
                                    if (targetState == "FOLDER") {
                                        (slideInHorizontally(tween(260, easing = FastOutSlowInEasing)) { it / 3 } + fadeIn(tween(220)))
                                            .togetherWith(
                                                slideOutHorizontally(tween(180, easing = FastOutSlowInEasing)) { -it / 3 } +
                                                    fadeOut(tween(160)),
                                            )
                                    } else if (initialState == "FOLDER") {
                                        (slideInHorizontally(tween(260, easing = FastOutSlowInEasing)) { -it / 3 } + fadeIn(tween(220)))
                                            .togetherWith(
                                                slideOutHorizontally(tween(180, easing = FastOutSlowInEasing)) { it / 3 } +
                                                    fadeOut(tween(160)),
                                            )
                                    } else {
                                        fadeIn(tween(200)).togetherWith(fadeOut(tween(160)))
                                    }
                                },
                                label = "HeaderStateTransition",
                            ) { state ->
                                when (state) {
                                    "FOLDER" -> {
                                        val folder = currentFolder
                                        if (folder != null) {
                                            val isExitDrop = isHoveringExitFolder
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier =
                                                    Modifier
                                                        .onGloballyPositioned { folderHeaderBounds = it.boundsInRoot() }
                                                        .clip(RoundedCornerShape(20.dp))
                                                        .background(if (isExitDrop) colors.accent.copy(alpha = 0.95f) else colors.panel.copy(alpha = 0.85f))
                                                        .border(if (isExitDrop) 2.dp else 1.dp, if (isExitDrop) Color.White else colors.border, RoundedCornerShape(20.dp))
                                                        .clickable {
                                                            vm.currentFolder = null
                                                            vm.refreshProjects()
                                                        }.padding(horizontal = 12.dp, vertical = 6.dp),
                                            ) {
                                                Icon(
                                                    painter = painterResource(if (isExitDrop) R.drawable.ic_external_link else R.drawable.ic_arrow_left),
                                                    contentDescription = stringResource(R.string.common_back),
                                                    tint = if (isExitDrop) Color.White else colors.text,
                                                    modifier = Modifier.size(18.dp),
                                                )
                                                Spacer(Modifier.width(8.dp))
                                                Text(
                                                    text = if (isExitDrop) stringResource(R.string.gallery_drag_move_out) else folder.name,
                                                    color = if (isExitDrop) Color.White else colors.text,
                                                    fontSize = 15.sp,
                                                    fontWeight = FontWeight.Bold,
                                                )
                                                if (!isExitDrop) {
                                                    Spacer(Modifier.width(4.dp))
                                                    Text(
                                                        text = "(${displayProjects.size})",
                                                        color = colors.subText,
                                                        fontSize = 12.sp,
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    "SELECT" -> {
                                        AnimatedContent(
                                            targetState = selectedProjects.size,
                                            transitionSpec = {
                                                if (targetState > initialState) {
                                                    (slideInVertically(tween(180)) { -it / 2 } + fadeIn(tween(180)))
                                                        .togetherWith(slideOutVertically(tween(140)) { it / 2 } + fadeOut(tween(140)))
                                                } else {
                                                    (slideInVertically(tween(180)) { it / 2 } + fadeIn(tween(180)))
                                                        .togetherWith(slideOutVertically(tween(140)) { -it / 2 } + fadeOut(tween(140)))
                                                }
                                            },
                                            label = "SelectedCountAnim",
                                        ) { count ->
                                            Text(
                                                text = stringResource(R.string.gallery_selected_count, count),
                                                color = colors.text,
                                                fontSize = 18.sp,
                                                fontWeight = FontWeight.Bold,
                                            )
                                        }
                                    }

                                    else -> {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        ) {
                                            Icon(
                                                painter = painterResource(R.drawable.ic_reverie_logo),
                                                contentDescription = null,
                                                tint = colors.accent,
                                                modifier = Modifier.size(28.dp),
                                            )
                                            Column {
                                                Text(
                                                    text = stringResource(R.string.gallery_title),
                                                    color = colors.text,
                                                    fontSize = 22.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    letterSpacing = 0.5.sp,
                                                )
                                                // 数量变化 (删除/导入) 时交叉淡入, 不再原地闪一下
                                                AnimatedContent(
                                                    targetState = displayProjects.size,
                                                    transitionSpec = {
                                                        fadeIn(tween(220, easing = FastOutSlowInEasing))
                                                            .togetherWith(fadeOut(tween(140)))
                                                    },
                                                    label = "GalleryItemCountAnim",
                                                ) { count ->
                                                    Text(
                                                        text = stringResource(R.string.gallery_items_count, count),
                                                        color = colors.subText,
                                                        fontSize = 11.sp,
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            Spacer(Modifier.weight(1f))

                            AnimatedContent(
                                targetState = isSearchActive,
                                transitionSpec = {
                                    (fadeIn(tween(160)) + scaleIn(tween(160), initialScale = 0.94f))
                                        .togetherWith(fadeOut(tween(100)) + scaleOut(tween(100), targetScale = 0.94f))
                                },
                                label = "HomeTopBarActionTransition",
                            ) { searching ->
                                if (searching) {
                                    HomeSearchBar(
                                        query = vm.searchQuery,
                                        onQueryChange = { vm.searchQuery = it },
                                        onClose = {
                                            vm.searchQuery = ""
                                            isSearchActive = false
                                        },
                                        colors = colors,
                                    )
                                } else {
                                    if (isSelectMode) {
                                        val haptic = LocalHapticFeedback.current
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        ) {
                                            val allSelected = displayProjects.isNotEmpty() && selectedProjects.size == displayProjects.size
                                            ReTextButton(
                                                text = if (allSelected) stringResource(R.string.gallery_deselect_all) else stringResource(R.string.gallery_select_all),
                                                onClick = {
                                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                    if (allSelected) {
                                                        selectedProjects.clear()
                                                    } else {
                                                        selectedProjects.clear()
                                                        selectedProjects.addAll(displayProjects)
                                                    }
                                                },
                                                textColor = colors.accent,
                                                fontWeight = FontWeight.Medium,
                                                fontSize = 14.sp,
                                            )

                                            ReTextButton(
                                                stringResource(R.string.common_done),
                                                onClick = {
                                                    isSelectMode = false
                                                    selectedProjects.clear()
                                                },
                                                textColor = colors.accent,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 15.sp,
                                            )
                                        }
                                    } else {
                                        // Top Bar Buttons Group
                                        Row(
                                            modifier =
                                                Modifier
                                                    .clip(RoundedCornerShape(21.dp))
                                                    .background(colors.panel.copy(alpha = 0.85f))
                                                    .border(1.dp, colors.border, RoundedCornerShape(21.dp))
                                                    .padding(4.dp),
                                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            // Search icon button
                                            Box(
                                                modifier =
                                                    Modifier
                                                        .size(34.dp)
                                                        .clip(CircleShape)
                                                        .clickable { isSearchActive = true },
                                                contentAlignment = Alignment.Center,
                                            ) {
                                                Icon(
                                                    painterResource(R.drawable.ic_search),
                                                    contentDescription = stringResource(R.string.common_search),
                                                    tint = colors.icon,
                                                    modifier = Modifier.size(18.dp),
                                                )
                                            }

                                            // More Menu icon button
                                            Box {
                                                Box(
                                                    modifier =
                                                        Modifier
                                                            .size(34.dp)
                                                            .clip(CircleShape)
                                                            .clickable { showMoreMenu = true },
                                                    contentAlignment = Alignment.Center,
                                                ) {
                                                    Icon(
                                                        painterResource(R.drawable.ic_dots_vertical),
                                                        contentDescription = stringResource(R.string.common_more),
                                                        tint = colors.icon,
                                                        modifier = Modifier.size(18.dp),
                                                    )
                                                }

                                                HomeDropdownMenu(
                                                    expanded = showMoreMenu,
                                                    onDismissRequest = { showMoreMenu = false },
                                                ) {
                                                    HomeDropdownMenuItem(
                                                        text = stringResource(R.string.common_import),
                                                        icon = R.drawable.ic_import,
                                                        onClick = {
                                                            showMoreMenu = false
                                                            importLauncher.launch(arrayOf("*/*"))
                                                        },
                                                    )
                                                    HomeDropdownMenuItem(
                                                        text = stringResource(R.string.gallery_select),
                                                        icon = R.drawable.ic_circle_check,
                                                        onClick = {
                                                            showMoreMenu = false
                                                            isSelectMode = true
                                                            selectedProjects.clear()
                                                        },
                                                    )
                                                    HomeDropdownMenuItem(
                                                        text = stringResource(R.string.gallery_new_stack),
                                                        icon = R.drawable.ic_folder_plus,
                                                        onClick = {
                                                            showMoreMenu = false
                                                            newFolderName = generateDefaultStackName(displayProjects, defaultStackBaseName, defaultStackIndexedFormat)
                                                            showNewFolderDialog = true
                                                        },
                                                    )
                                                    HomeDropdownMenuItem(
                                                        text = stringResource(R.string.auto_save_history_title),
                                                        icon = R.drawable.ic_clock,
                                                        onClick = {
                                                            showMoreMenu = false
                                                            showAutoSaveHistoryDialog = true
                                                        },
                                                    )
                                                    HomeDropdownMenuItem(
                                                        text = stringResource(R.string.gallery_refresh),
                                                        icon = R.drawable.ic_refresh,
                                                        onClick = {
                                                            showMoreMenu = false
                                                            vm.refreshProjects()
                                                        },
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // 崩溃遥测首启一次性告知条 (非模态; 未注入端点时自身不渲染)
                        TelemetryNoticeBar(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )

                        val density = LocalDensity.current
                        val config = LocalConfiguration.current
                        val screenWPx = with(density) { config.screenWidthDp.dp.toPx() }
                        val screenHPx = with(density) { config.screenHeightDp.dp.toPx() }
                        val folderPivotX = lastClickedFolderBounds?.let { (it.center.x / screenWPx).coerceIn(0.08f, 0.92f) } ?: 0.5f
                        val folderPivotY = lastClickedFolderBounds?.let { (it.center.y / screenHPx).coerceIn(0.08f, 0.92f) } ?: 0.5f
                        val folderOrigin = TransformOrigin(folderPivotX, folderPivotY)

                        // 画集原位展开/收拢: 以点击卡片屏幕锚点为中心扩散撑开/聚拢归位
                        AnimatedContent(
                            targetState = currentFolder?.filePath,
                            transitionSpec = {
                                if (targetState != null) {
                                    (
                                        fadeIn(tween(240, easing = FastOutSlowInEasing)) +
                                            scaleIn(
                                                animationSpec = spring(dampingRatio = 0.82f, stiffness = 420f),
                                                initialScale = 0.70f,
                                                transformOrigin = folderOrigin,
                                            )
                                    ).togetherWith(
                                        fadeOut(tween(140, easing = FastOutSlowInEasing)) +
                                            scaleOut(
                                                animationSpec = tween(180, easing = FastOutSlowInEasing),
                                                targetScale = 1.08f,
                                            ),
                                    )
                                } else {
                                    (
                                        fadeIn(tween(240, easing = FastOutSlowInEasing)) +
                                            scaleIn(
                                                animationSpec = tween(220, easing = FastOutSlowInEasing),
                                                initialScale = 1.06f,
                                            )
                                    ).togetherWith(
                                        fadeOut(tween(150, easing = FastOutSlowInEasing)) +
                                            scaleOut(
                                                animationSpec = spring(dampingRatio = 0.85f, stiffness = 440f),
                                                targetScale = 0.70f,
                                                transformOrigin = folderOrigin,
                                            ),
                                    )
                                }
                            },
                            modifier = Modifier.weight(1f),
                            label = "FolderTransition",
                        ) { folderKey ->
                            // 入场级联记录器: 每个画集视图各自持有一份, 重新进入时重新播一次入场
                            val entranceTracker = remember(folderKey) { GalleryEntranceTracker() }
                            if (displayProjects.isEmpty()) {
                                // 空状态: 整体淡入 + 轻微上浮, 不再硬切
                                val emptyEnter = remember(folderKey) { Animatable(0f) }
                                LaunchedEffect(folderKey) {
                                    emptyEnter.animateTo(1f, tween(300, easing = FastOutSlowInEasing))
                                }
                                Box(
                                    modifier =
                                        Modifier
                                            .fillMaxSize()
                                            .graphicsLayer {
                                                alpha = emptyEnter.value
                                                translationY = (1f - emptyEnter.value) * 18.dp.toPx()
                                            },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        modifier = Modifier.padding(horizontal = 32.dp),
                                    ) {
                                        Box(
                                            modifier =
                                                Modifier
                                                    .size(80.dp)
                                                    .clip(CircleShape)
                                                    .background(colors.panel.copy(alpha = 0.8f))
                                                    .border(1.dp, colors.border, CircleShape),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Icon(
                                                painterResource(R.drawable.ic_canvas_tab),
                                                contentDescription = null,
                                                tint = colors.subText.copy(alpha = 0.7f),
                                                modifier = Modifier.size(36.dp),
                                            )
                                        }
                                        Spacer(Modifier.height(16.dp))
                                        Text(
                                            if (vm.searchQuery.isNotEmpty()) {
                                                stringResource(R.string.gallery_empty_search_title)
                                            } else if (folderKey !=
                                                null
                                            ) {
                                                stringResource(R.string.gallery_empty_stack_title)
                                            } else {
                                                stringResource(R.string.gallery_empty_main_title)
                                            },
                                            color = colors.text,
                                            fontSize = 16.sp,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                        Spacer(Modifier.height(6.dp))
                                        Text(
                                            if (vm.searchQuery.isNotEmpty()) {
                                                stringResource(R.string.gallery_empty_search_desc)
                                            } else if (folderKey !=
                                                null
                                            ) {
                                                stringResource(R.string.gallery_empty_stack_desc)
                                            } else {
                                                stringResource(R.string.gallery_empty_main_desc)
                                            },
                                            color = colors.subText,
                                            fontSize = 12.sp,
                                            textAlign = TextAlign.Center,
                                        )
                                    }
                                }
                            } else {
                                LazyVerticalGrid(
                                    columns = GridCells.Adaptive(minSize = 160.dp),
                                    state = if (folderKey == null) rootGridState else folderGridState,
                                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(16.dp),
                                    contentPadding = PaddingValues(top = 8.dp, bottom = 100.dp),
                                ) {
                                    itemsIndexed(
                                        items = displayProjects,
                                        key = { _, it -> it.filePath },
                                        // 画集卡片与单图卡片是两套布局, 分开复用可减少滑动时的重组
                                        contentType = { _, it -> if (it.isFolder) "stack" else "artwork" },
                                    ) { index, p ->
                                        val isSelected = selectedProjects.contains(p)

                                        // 入场级联: 只在该卡片首次出现时播一次, 滚回来不再重播
                                        val enterProgress =
                                            rememberGalleryEntrance(
                                                tracker = entranceTracker,
                                                key = p.filePath,
                                                index = index,
                                            )
                                        val initialFanAngle =
                                            remember(p.filePath) {
                                                val hash = kotlin.math.abs(p.filePath.hashCode())
                                                ((hash % 9) - 4).toFloat() * 1.5f // -6° to +6° fanned spread
                                            }

                                        val interactionSource = remember { MutableInteractionSource() }
                                        val isPressed by interactionSource.collectIsPressedAsState()
                                        val cardPressScale by animateFloatAsState(
                                            targetValue = if (isPressed) 0.94f else 1.0f,
                                            animationSpec =
                                                spring(
                                                    dampingRatio = Spring.DampingRatioMediumBouncy,
                                                    stiffness = Spring.StiffnessMediumLow,
                                                ),
                                            label = "CardScaleAnim",
                                        )

                                        val cardSelectScale by animateFloatAsState(
                                            targetValue = if (isSelectMode && isSelected) 0.96f else 1.0f,
                                            animationSpec =
                                                spring(
                                                    dampingRatio = Spring.DampingRatioMediumBouncy,
                                                    stiffness = Spring.StiffnessMediumLow,
                                                ),
                                            label = "CardSelectScale",
                                        )

                                        val borderStrokeWidth by animateDpAsState(
                                            targetValue = if (isSelectMode && isSelected) 2.5.dp else 0.dp,
                                            animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium),
                                            label = "CardBorderWidth",
                                        )

                                        // 选中蒙层: 与边框一起把"已选"画进卡面, 比单纯缩小更好认
                                        val selectTint by animateColorAsState(
                                            targetValue =
                                                if (isSelectMode && isSelected) {
                                                    colors.accent.copy(alpha = 0.16f)
                                                } else {
                                                    Color.Transparent
                                                },
                                            animationSpec = tween(180),
                                            label = "CardSelectTint",
                                        )

                                        val haptic = LocalHapticFeedback.current

                                        // 长按托起: 长按触发/拖拽中把卡片托起并描边, 让"可以拖了"看得见
                                        var holdLifted by remember { mutableStateOf(false) }
                                        val holdLiftScale by animateFloatAsState(
                                            targetValue = if (holdLifted) 1.045f else 1.0f,
                                            animationSpec =
                                                spring(
                                                    dampingRatio = Spring.DampingRatioMediumBouncy,
                                                    stiffness = Spring.StiffnessMediumLow,
                                                ),
                                            label = "CardHoldLift",
                                        )
                                        val holdRingWidth by animateDpAsState(
                                            targetValue = if (holdLifted) 2.dp else 0.dp,
                                            animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium),
                                            label = "CardHoldRing",
                                        )
                                        val cardRingWidth = maxOf(borderStrokeWidth, holdRingWidth)

                                        // 画集卡片按压扇形展开: 单个进度驱动三层, 数值只在图层阶段读取
                                        val fanPress by animateFloatAsState(
                                            targetValue = if (isPressed) 1f else 0f,
                                            animationSpec =
                                                spring(
                                                    dampingRatio = Spring.DampingRatioMediumBouncy,
                                                    stiffness = Spring.StiffnessMediumLow,
                                                ),
                                            label = "FanPress",
                                        )

                                        val isFolderHovered = p.isFolder && hoveredFolderKey == p.filePath
                                        val folderHoverScale by animateFloatAsState(
                                            targetValue = if (isFolderHovered) 1.07f else 1.0f,
                                            animationSpec =
                                                spring(
                                                    dampingRatio = Spring.DampingRatioMediumBouncy,
                                                    stiffness = Spring.StiffnessMediumLow,
                                                ),
                                            label = "FolderHoverScale",
                                        )
                                        var itemCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }

                                        Box(
                                            modifier =
                                                Modifier
                                                    // 增删/筛选造成的重排做插值位移, 卡片不再硬跳
                                                    .animateItem(
                                                        fadeInSpec = null,
                                                        placementSpec = Motion.enterSpring(),
                                                        fadeOutSpec = tween(140, easing = FastOutSlowInEasing),
                                                    )
                                                    .graphicsLayer {
                                                        // 入场/按压/选中/拖拽悬停全部在图层阶段取值, 逐帧不触发重组
                                                        val enter = enterProgress.value.coerceIn(0f, 1f)
                                                        val scale =
                                                            (0.74f + 0.26f * enter) * cardPressScale *
                                                                cardSelectScale * folderHoverScale * holdLiftScale
                                                        alpha = enter
                                                        scaleX = scale
                                                        scaleY = scale
                                                        rotationZ = (1f - enter) * initialFanAngle
                                                        translationY = (1f - enter) * 28.dp.toPx()
                                                    }
                                                    .onGloballyPositioned { coords ->
                                                        itemCoordinates = coords
                                                        val bounds = coords.boundsInRoot()
                                                        allItemBoundsMap[p.filePath] = bounds
                                                        if (p.isFolder) {
                                                            folderBoundsMap[p.filePath] = bounds
                                                        } else {
                                                            artworkBoundsMap[p.filePath] = bounds
                                                        }
                                                    },
                                        ) {
                                            // 手势里读的是最新值: pointerInput 只按路径重建, 不能捕获旧闭包
                                            val currentProject by rememberUpdatedState(p)
                                            val currentSelectMode by rememberUpdatedState(isSelectMode)
                                            val currentProjects by rememberUpdatedState(displayProjects)

                                            val cardModifier = if (!p.isFolder) {
                                                Modifier
                                                    .fillMaxWidth()
                                                    // 只按项目路径重建: 选中态变化不该重置正在进行的按压/长按
                                                    .pointerInput(p.filePath) {
                                                        awaitEachGesture {
                                                            val down = awaitFirstDown(requireUnconsumed = false)
                                                            val press = PressInteraction.Press(down.position)
                                                            interactionSource.tryEmit(press)
                                                            var pressEnded = false
                                                            val endPress: (Boolean) -> Unit = { cancelled ->
                                                                if (!pressEnded) {
                                                                    pressEnded = true
                                                                    interactionSource.tryEmit(
                                                                        if (cancelled) {
                                                                            PressInteraction.Cancel(press)
                                                                        } else {
                                                                            PressInteraction.Release(press)
                                                                        },
                                                                    )
                                                                }
                                                            }
                                                            val downRoot = itemCoordinates?.localToRoot(down.position) ?: down.position
                                                            val downPos = down.position
                                                            val touchSlop = viewConfiguration.touchSlop
                                                            var isLongPressTriggered = false
                                                            var currentPointer = down

                                                            if (currentSelectMode) {
                                                                val startSelected = selectedProjects.contains(currentProject)
                                                                var isSwiping = false
                                                                try {
                                                                    while (true) {
                                                                        val event = awaitPointerEvent()
                                                                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                                                        if (change.isConsumed) break
                                                                        if (change.changedToUpIgnoreConsumed()) {
                                                                            change.consume()
                                                                            endPress(false)
                                                                            if (!isSwiping) {
                                                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                                                if (startSelected) selectedProjects.remove(currentProject)
                                                                                else selectedProjects.add(currentProject)
                                                                            }
                                                                            break
                                                                        }
                                                                        val moveDist = (change.position - downPos).getDistance()
                                                                        if (!isSwiping && moveDist > touchSlop) {
                                                                            isSwiping = true
                                                                            if (startSelected) selectedProjects.remove(currentProject)
                                                                            else selectedProjects.add(currentProject)
                                                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                                        }
                                                                        if (isSwiping) {
                                                                            change.consume()
                                                                            val rootPos = itemCoordinates?.localToRoot(change.position) ?: (downRoot + (change.position - downPos))
                                                                            val hitItem = allItemBoundsMap.entries.firstOrNull { it.value.contains(rootPos) }
                                                                            if (hitItem != null) {
                                                                                val target = currentProjects.firstOrNull { it.filePath == hitItem.key }
                                                                                if (target != null) {
                                                                                    if (!startSelected && !selectedProjects.contains(target)) {
                                                                                        selectedProjects.add(target)
                                                                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                                                    } else if (startSelected && selectedProjects.contains(target)) {
                                                                                        selectedProjects.remove(target)
                                                                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                                                    }
                                                                                }
                                                                            }
                                                                        }
                                                                    }
                                                                } finally {
                                                                    endPress(true)
                                                                }
                                                            } else {
                                                                try {
                                                                    try {
                                                                        withTimeout(viewConfiguration.longPressTimeoutMillis) {
                                                                            while (true) {
                                                                                val event = awaitPointerEvent()
                                                                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                                                                currentPointer = change
                                                                                if (change.isConsumed) {
                                                                                    endPress(true)
                                                                                    return@withTimeout
                                                                                }
                                                                                val dist = (change.position - downPos).getDistance()
                                                                                if (dist > touchSlop) {
                                                                                    endPress(true)
                                                                                    return@withTimeout
                                                                                }
                                                                                if (change.changedToUpIgnoreConsumed()) {
                                                                                    change.consume()
                                                                                    endPress(false)
                                                                                    vm.loadProject(currentProject)
                                                                                    return@withTimeout
                                                                                }
                                                                            }
                                                                        }
                                                                    } catch (_: PointerEventTimeoutCancellationException) {
                                                                        isLongPressTriggered = true
                                                                    }

                                                                    if (isLongPressTriggered) {
                                                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                                        holdLifted = true
                                                                        var dragStarted = false
                                                                        var totalDrag = Offset.Zero
                                                                        var currentPos = itemCoordinates?.localToRoot(currentPointer.position) ?: downRoot
                                                                        val pointerId = currentPointer.id
                                                                        var mergeTimerStart = 0L

                                                                        while (true) {
                                                                            val event = awaitPointerEvent()
                                                                            val change = event.changes.firstOrNull { it.id == pointerId } ?: break
                                                                            if (change.isConsumed) break

                                                                            if (change.changedToUpIgnoreConsumed()) {
                                                                                change.consume()
                                                                                break
                                                                            }

                                                                            val dragDelta = change.position - change.previousPosition
                                                                            totalDrag += dragDelta

                                                                            if (!dragStarted && totalDrag.getDistance() > touchSlop) {
                                                                                dragStarted = true
                                                                                draggingProject = currentProject
                                                                                dragStartRootPos = currentPos
                                                                                dragTouchRootPos = currentPos
                                                                            }

                                                                            if (dragStarted) {
                                                                                change.consume()
                                                                                currentPos = itemCoordinates?.localToRoot(change.position) ?: (dragTouchRootPos + dragDelta)
                                                                                dragTouchRootPos = currentPos

                                                                                // 1. 判断是否拖入顶部移出画集投放区 (在画集内有效)
                                                                                val isExit = folderKey != null && (
                                                                                    folderHeaderBounds?.inflate(24.dp.toPx())?.contains(dragTouchRootPos) == true ||
                                                                                    dragTouchRootPos.y < 88.dp.toPx()
                                                                                )
                                                                                if (isExit != isHoveringExitFolder) {
                                                                                    isHoveringExitFolder = isExit
                                                                                    if (isExit) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                                                }

                                                                                // 2. 判断是否拖入已有画集
                                                                                if (!isExit) {
                                                                                    val hitFolder = folderBoundsMap.entries.firstOrNull { (_, rect) -> rect.contains(dragTouchRootPos) }
                                                                                    if (hitFolder?.key != hoveredFolderKey) {
                                                                                        if (hitFolder != null) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                                                        hoveredFolderKey = hitFolder?.key
                                                                                    }

                                                                                    // 3. 判断是否拖入单张画作互叠 (400ms 触发合成新画集)
                                                                                    if (hitFolder == null) {
                                                                                        val hitArt = artworkBoundsMap.entries.firstOrNull { (k, rect) ->
                                                                                            k != currentProject.filePath && rect.contains(dragTouchRootPos)
                                                                                        }
                                                                                        val newMergeKey = hitArt?.key
                                                                                        if (newMergeKey != hoveredMergeArtworkKey) {
                                                                                            hoveredMergeArtworkKey = newMergeKey
                                                                                            mergeTimerStart = if (newMergeKey != null) System.currentTimeMillis() else 0L
                                                                                            isMergeReady = false
                                                                                        } else if (newMergeKey != null && !isMergeReady && mergeTimerStart > 0L) {
                                                                                            if (System.currentTimeMillis() - mergeTimerStart >= 400L) {
                                                                                                isMergeReady = true
                                                                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                                                            }
                                                                                        }
                                                                                    } else {
                                                                                        hoveredMergeArtworkKey = null
                                                                                        isMergeReady = false
                                                                                    }
                                                                                } else {
                                                                                    hoveredFolderKey = null
                                                                                    hoveredMergeArtworkKey = null
                                                                                    isMergeReady = false
                                                                                }
                                                                            }
                                                                        }

                                                                        if (dragStarted) {
                                                                            val toMove = listOf(currentProject)
                                                                            when {
                                                                                isHoveringExitFolder -> {
                                                                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                                                    vm.moveProjectsToFolder(toMove, null)
                                                                                    Toast.makeText(context, String.format(toastMovedOut, toMove.size), Toast.LENGTH_SHORT).show()
                                                                                }
                                                                                hoveredFolderKey != null -> {
                                                                                    val targetFolder = currentProjects.firstOrNull { it.isFolder && it.filePath == hoveredFolderKey }
                                                                                    if (targetFolder != null) {
                                                                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                                                        vm.moveProjectsToFolder(toMove, targetFolder.name)
                                                                                        Toast.makeText(context, String.format(toastMovedToStack, toMove.size, targetFolder.name), Toast.LENGTH_SHORT).show()
                                                                                    }
                                                                                }
                                                                                hoveredMergeArtworkKey != null && isMergeReady -> {
                                                                                    val targetArtwork = currentProjects.firstOrNull { it.filePath == hoveredMergeArtworkKey }
                                                                                    if (targetArtwork != null) {
                                                                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                                                        val defaultName = generateDefaultStackName(currentProjects, defaultStackBaseName, defaultStackIndexedFormat)
                                                                                        vm.createStackWithProjects(defaultName, listOf(currentProject, targetArtwork))
                                                                                        Toast.makeText(context, String.format(toastStackMerged, defaultName), Toast.LENGTH_SHORT).show()
                                                                                    }
                                                                                }
                                                                            }
                                                                            draggingProject = null
                                                                            dragTouchRootPos = Offset.Zero
                                                                            hoveredFolderKey = null
                                                                            hoveredMergeArtworkKey = null
                                                                            isMergeReady = false
                                                                            isHoveringExitFolder = false
                                                                        } else {
                                                                            longPressedProject = currentProject
                                                                        }
                                                                    }
                                                                } finally {
                                                                    endPress(true)
                                                                    holdLifted = false
                                                                }
                                                            }
                                                        }
                                                    }
                                            } else {
                                                Modifier
                                                    .fillMaxWidth()
                                                    .combinedClickable(
                                                        interactionSource = interactionSource,
                                                        indication = null,
                                                        onClick = {
                                                            if (isSelectMode) {
                                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                                if (isSelected) selectedProjects.remove(p) else selectedProjects.add(p)
                                                            } else {
                                                                lastClickedFolderBounds = folderBoundsMap[p.filePath]
                                                                vm.currentFolder = p
                                                                vm.refreshProjects()
                                                            }
                                                        },
                                                        onLongClick = {
                                                            if (!isSelectMode) {
                                                                longPressedProject = p
                                                            }
                                                        },
                                                    )
                                            }

                                            Column(
                                                modifier = Modifier.fillMaxWidth(),
                                            ) {
                                                Box(
                                                    modifier = cardModifier,
                                                ) {
                                                    if (p.isFolder) {
                                                        FolderThumbnailBox(
                                                            project = p,
                                                            fanPress = fanPress,
                                                            borderStrokeWidth = borderStrokeWidth,
                                                            isFolderHovered = isFolderHovered,
                                                            isSelected = isSelected,
                                                            isSelectMode = isSelectMode,
                                                            colors = colors,
                                                        )
                                                    } else {
                                                        ArtworkThumbnailBox(
                                                            project = p,
                                                            cardRingWidth = cardRingWidth,
                                                            selectTint = selectTint,
                                                            isSelected = isSelected,
                                                            isSelectMode = isSelectMode,
                                                            isFolderCover = currentFolder != null && p.isFolderCover,
                                                            isMergeTarget = hoveredMergeArtworkKey == p.filePath && isMergeReady,
                                                            colors = colors,
                                                        )
                                                    }
                                                }

                                                CardMetaSection(
                                                    project = p,
                                                    isSelectMode = isSelectMode,
                                                    colors = colors,
                                                    onRenameClick = {
                                                        if (isSelectMode) {
                                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                            if (isSelected) selectedProjects.remove(p) else selectedProjects.add(p)
                                                        } else {
                                                            targetRenameProject = p
                                                            newProjectName = p.name
                                                            showRenameDialog = true
                                                        }
                                                    },
                                                    onLongClick = {
                                                        if (!isSelectMode) {
                                                            longPressedProject = p
                                                        }
                                                    },
                                                )
                                            }

                                            // Long-press Context Dropdown Menu
                                            HomeDropdownMenu(
                                                expanded = longPressedProject == p,
                                                onDismissRequest = { longPressedProject = null },
                                            ) {
                                                HomeDropdownMenuItem(
                                                    text = if (p.isFolder) stringResource(R.string.gallery_action_open_stack) else stringResource(R.string.gallery_action_open_artwork),
                                                    icon = R.drawable.ic_external_link,
                                                    onClick = {
                                                        longPressedProject = null
                                                        if (p.isFolder) {
                                                            vm.currentFolder = p
                                                            vm.refreshProjects()
                                                        } else {
                                                            vm.loadProject(p)
                                                        }
                                                    },
                                                )
                                                if (!p.isFolder && p.hasRecording) {
                                                    HomeDropdownMenuItem(
                                                        text = stringResource(R.string.gallery_action_replay),
                                                        icon = R.drawable.ic_play,
                                                        textColor = colors.accent,
                                                        iconColor = colors.accent,
                                                        onClick = {
                                                            longPressedProject = null
                                                            vm.goReplay(p)
                                                        },
                                                    )
                                                }
                                                if (!p.isFolder) {
                                                    HomeDropdownMenuItem(
                                                        text = stringResource(R.string.gallery_action_share_artwork),
                                                        icon = R.drawable.ic_share,
                                                        onClick = {
                                                            longPressedProject = null
                                                            shareProjectFile(context, p)
                                                        },
                                                    )
                                                    HomeDropdownMenuItem(
                                                        text = stringResource(R.string.home_draft_duplicate),
                                                        icon = R.drawable.ic_copy,
                                                        onClick = {
                                                            longPressedProject = null
                                                            vm.duplicateProject(p)
                                                        },
                                                    )
                                                    HomeDropdownMenuItem(
                                                        text = stringResource(R.string.gallery_action_move_to_stack),
                                                        icon = R.drawable.ic_folder_symlink,
                                                        onClick = {
                                                            longPressedProject = null
                                                            targetMoveProjects = listOf(p)
                                                            showMoveDialog = true
                                                        },
                                                    )
                                                    if (currentFolder != null) {
                                                        if (p.isFolderCover) {
                                                            HomeDropdownMenuItem(
                                                                text = stringResource(R.string.gallery_action_clear_cover),
                                                                icon = R.drawable.ic_x,
                                                                onClick = {
                                                                    longPressedProject = null
                                                                    vm.clearFolderCover(currentFolder)
                                                                    Toast.makeText(context, toastCoverCleared, Toast.LENGTH_SHORT).show()
                                                                },
                                                            )
                                                        } else {
                                                            HomeDropdownMenuItem(
                                                                text = stringResource(R.string.gallery_action_set_as_cover),
                                                                icon = R.drawable.ic_image,
                                                                onClick = {
                                                                    longPressedProject = null
                                                                    vm.setFolderCover(currentFolder, p)
                                                                    Toast.makeText(context, String.format(toastCoverSet, p.name), Toast.LENGTH_SHORT).show()
                                                                },
                                                            )
                                                        }
                                                    }
                                                }
                                                if (p.isFolder) {
                                                    HomeDropdownMenuItem(
                                                        text = stringResource(R.string.gallery_action_set_stack_cover),
                                                        icon = R.drawable.ic_image,
                                                        onClick = {
                                                            longPressedProject = null
                                                            targetCoverFolder = p
                                                            showChooseCoverDialog = true
                                                        },
                                                    )
                                                }
                                                HomeDropdownMenuItem(
                                                    text = stringResource(R.string.home_draft_rename),
                                                    icon = R.drawable.ic_pencil,
                                                    onClick = {
                                                        longPressedProject = null
                                                        targetRenameProject = p
                                                        newProjectName = p.name
                                                        showRenameDialog = true
                                                    },
                                                )
                                                HomeDropdownMenuItem(
                                                    text = stringResource(R.string.gallery_action_details),
                                                    icon = R.drawable.ic_info_circle,
                                                    onClick = {
                                                        longPressedProject = null
                                                        detailsProject = p
                                                    },
                                                )
                                                HomeDropdownMenuItem(
                                                    text = if (p.isFolder) stringResource(R.string.gallery_action_delete_stack) else stringResource(R.string.gallery_action_delete_artwork),
                                                    icon = R.drawable.ic_trash,
                                                    isDestructive = true,
                                                    onClick = {
                                                        longPressedProject = null
                                                        projectToDelete = p
                                                    },
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } // Added closing brace for Column
                }
            } else {
                // Settings Tab
                Box(modifier = Modifier.fillMaxSize()) {
                    SettingsPageContent(vm)
                }
            }
        }

        val inGallerySelectMode = isSelectMode && selectedTab == 0

        AnimatedContent(
            targetState = inGallerySelectMode,
            transitionSpec = {
                if (targetState) {
                    (slideInVertically(spring(dampingRatio = 0.82f, stiffness = 420f)) { it } + fadeIn(tween(200)))
                        .togetherWith(slideOutVertically(tween(160)) { it } + fadeOut(tween(140)))
                } else {
                    (slideInVertically(spring(dampingRatio = 0.82f, stiffness = 420f)) { it } + fadeIn(tween(200)))
                        .togetherWith(slideOutVertically(tween(160)) { it } + fadeOut(tween(140)))
                }
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding(),
            label = "BottomBarModeTransition",
        ) { inSelect ->
            if (inSelect) {
                GallerySelectionBottomBar(
                    colors = colors,
                    selectedCount = selectedProjects.size,
                    canShareOrDuplicate = selectedProjects.any { !it.isFolder },
                    hazeState = hazeState,
                    onShare = {
                        shareProjectFiles(context, selectedProjects.toList())
                    },
                    onDuplicate = {
                        vm.duplicateProjects(selectedProjects.toList())
                        isSelectMode = false
                        selectedProjects.clear()
                    },
                    onMove = {
                        targetMoveProjects = selectedProjects.toList()
                        showMoveDialog = true
                    },
                    onDelete = {
                        showBatchDeleteConfirm = true
                    },
                )
            } else {
                HomeBottomBar(
                    colors = colors,
                    vm = vm,
                    selectedTab = selectedTab,
                    hazeState = hazeState,
                )
            }
        }

        // Floating drag proxy preview card
        draggingProject?.let { dragProj ->
            val dragCount = if (isSelectMode && selectedProjects.contains(dragProj)) selectedProjects.size else 1
            FloatingDragProxy(
                project = dragProj,
                dragTouchRootPos = dragTouchRootPos,
                dragCount = dragCount,
                colors = colors,
            )
        }

        com.reverie.paint.ui.components.DragHoverOverlay(
            visible = vm.isDraggingExternal,
            hint = stringResource(R.string.gallery_drop_hint),
        )
    }
}

@Composable
private fun HomeSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit,
    colors: AppColors,
    modifier: Modifier = Modifier,
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Row(
        modifier = modifier
            .width(260.dp)
            .height(42.dp)
            .clip(RoundedCornerShape(21.dp))
            .background(colors.panel.copy(alpha = 0.90f))
            .border(1.dp, colors.border, RoundedCornerShape(21.dp))
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_search),
            contentDescription = null,
            tint = colors.subText,
            modifier = Modifier.size(17.dp),
        )

        Box(modifier = Modifier.weight(1f)) {
            if (query.isEmpty()) {
                Text(
                    text = stringResource(R.string.gallery_search_hint),
                    color = colors.subText.copy(alpha = 0.7f),
                    fontSize = 13.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = TextStyle(
                    color = colors.text,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Normal,
                ),
                cursorBrush = SolidColor(colors.accent),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
            )
        }

        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(CircleShape)
                .clickable {
                    if (query.isNotEmpty()) {
                        onQueryChange("")
                    } else {
                        onClose()
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_x),
                contentDescription = "Close",
                tint = colors.subText,
                modifier = Modifier.size(15.dp),
            )
        }
    }
}

@Composable
private fun HomeDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    com.reverie.paint.ui.components.ReDropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        content = content,
    )
}

@Composable
private fun HomeDropdownMenuItem(
    text: String,
    icon: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isDestructive: Boolean = false,
    textColor: Color? = null,
    iconColor: Color? = null,
) {
    com.reverie.paint.ui.components.ReDropdownMenuItem(
        text = text,
        onClick = onClick,
        modifier = modifier,
        icon = icon,
        isDestructive = isDestructive,
        textColor = textColor,
        iconColor = iconColor,
    )
}

