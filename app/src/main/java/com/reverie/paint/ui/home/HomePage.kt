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

// In-Memory LRU Cache for high-performance thumbnail rendering without disk jank
private object ThumbnailCache {
    /**
     * 缩略图解码长边上限: 网格单元约 160dp, 4x 屏约 640px, 取 768px 覆盖到平板的大单元格;
     * 不解码原尺寸可省下数倍位图内存, 滑动时少一次 GC 抖动。
     */
    private const val MAX_DECODE_EDGE = 768

    private val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
    private val cacheSize = (maxMemory / 8).coerceAtLeast(1024 * 16)
    private val lruCache =
        object : android.util.LruCache<String, android.graphics.Bitmap>(cacheSize) {
            override fun sizeOf(
                key: String,
                bitmap: android.graphics.Bitmap,
            ): Int = bitmap.byteCount / 1024
        }

    fun peek(key: String): android.graphics.Bitmap? {
        val cached = lruCache.get(key)
        return if (cached != null && !cached.isRecycled) cached else null
    }

    private fun sampleSizeFor(path: String): Int {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return 1
        // 按长边收敛: 超宽画布也不会解出几十 MB 的位图
        val longEdge = maxOf(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (longEdge / (sample * 2) >= MAX_DECODE_EDGE) {
            sample *= 2
        }
        return sample
    }

    fun get(
        path: String,
        lastModified: Long,
    ): android.graphics.Bitmap? {
        if (path.isEmpty()) return null
        val key = "$path:$lastModified"
        val cached = peek(key)
        if (cached != null) return cached
        val file = File(path)
        if (file.exists()) {
            return try {
                val options = BitmapFactory.Options().apply { inSampleSize = sampleSizeFor(path) }
                val bmp = BitmapFactory.decodeFile(path, options)
                if (bmp != null) {
                    lruCache.put(key, bmp)
                }
                bmp
            } catch (e: Exception) {
                null
            }
        }
        return null
    }
}

@Composable
internal fun rememberThumbnail(path: String?, lastModified: Long): android.graphics.Bitmap? {
    if (path.isNullOrEmpty()) return null
    val key = "$path:$lastModified"
    val immediate = remember(key) { ThumbnailCache.peek(key) }
    if (immediate != null) return immediate

    var bmp by remember(key) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(key) {
        val loaded = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            ThumbnailCache.get(path, lastModified)
        }
        bmp = loaded
    }
    return bmp
}

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

    // Choose folder cover dialog
    var showChooseCoverDialog by remember { mutableStateOf(false) }
    var targetCoverFolder by remember { mutableStateOf<Project?>(null) }

    // Drag-and-drop projects to album stack
    var draggingProject by remember { mutableStateOf<Project?>(null) }
    var dragTouchRootPos by remember { mutableStateOf(Offset.Zero) }
    var dragStartRootPos by remember { mutableStateOf(Offset.Zero) }
    var hoveredFolderKey by remember { mutableStateOf<String?>(null) }
    val folderBoundsMap = remember { mutableStateMapOf<String, Rect>() }

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
        val alive = HashSet<String>(displayProjects.size)
        displayProjects.forEach { if (it.isFolder) alive.add(it.filePath) }
        folderBoundsMap.keys.retainAll(alive)
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
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier =
                                                    Modifier
                                                        .clip(RoundedCornerShape(20.dp))
                                                        .background(colors.panel.copy(alpha = 0.85f))
                                                        .border(1.dp, colors.border, RoundedCornerShape(20.dp))
                                                        .clickable {
                                                            vm.currentFolder = null
                                                            vm.refreshProjects()
                                                        }.padding(horizontal = 12.dp, vertical = 6.dp),
                                            ) {
                                                Icon(
                                                    painter = painterResource(R.drawable.ic_arrow_left),
                                                    contentDescription = stringResource(R.string.common_back),
                                                    tint = colors.text,
                                                    modifier = Modifier.size(18.dp),
                                                )
                                                Spacer(Modifier.width(8.dp))
                                                Text(
                                                    text = folder.name,
                                                    color = colors.text,
                                                    fontSize = 15.sp,
                                                    fontWeight = FontWeight.Bold,
                                                )
                                                Spacer(Modifier.width(4.dp))
                                                Text(
                                                    text = "(${displayProjects.size})",
                                                    color = colors.subText,
                                                    fontSize = 12.sp,
                                                )
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
                                                    val defaultFolderName = stringResource(R.string.gallery_new_stack_default, (System.currentTimeMillis() % 1000).toInt())
                                                    HomeDropdownMenuItem(
                                                        text = stringResource(R.string.gallery_new_stack),
                                                        icon = R.drawable.ic_folder_plus,
                                                        onClick = {
                                                            showMoreMenu = false
                                                            newFolderName = defaultFolderName
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

                        // 画集切换: 以路径作为状态键 (旧实现用 Project 整体相等, 画集内容一变就误触发切换动画)
                        AnimatedContent(
                            targetState = currentFolder?.filePath,
                            transitionSpec = {
                                (
                                    fadeIn(tween(240, easing = FastOutSlowInEasing)) +
                                        scaleIn(tween(260, easing = FastOutSlowInEasing), initialScale = 0.97f)
                                ).togetherWith(
                                    fadeOut(tween(140, easing = FastOutSlowInEasing)) +
                                        scaleOut(tween(160, easing = FastOutSlowInEasing), targetScale = 1.02f),
                                )
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
                                                        if (p.isFolder) {
                                                            folderBoundsMap[p.filePath] = coords.boundsInRoot()
                                                        }
                                                    },
                                        ) {
                                            // 手势里读的是最新值: pointerInput 只按路径重建, 不能捕获旧闭包
                                            val currentProject by rememberUpdatedState(p)
                                            val currentSelectMode by rememberUpdatedState(isSelectMode)
                                            val currentProjects by rememberUpdatedState(displayProjects)

                                            val cardModifier = if (folderKey == null && !p.isFolder) {
                                                Modifier
                                                    .fillMaxWidth()
                                                    // 只按项目路径重建: 选中态变化不该重置正在进行的按压/长按
                                                    .pointerInput(p.filePath) {
                                                        awaitEachGesture {
                                                            val down = awaitFirstDown(requireUnconsumed = false)
                                                            // 自定义手势需自己发按压交互, 否则卡片拿不到按压缩放反馈
                                                            // (combinedClickable 才会发, 旧实现下主画廊卡片点了没有任何反馈)
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

                                                            try {
                                                                try {
                                                                    withTimeout(viewConfiguration.longPressTimeoutMillis) {
                                                                        while (true) {
                                                                            val event = awaitPointerEvent()
                                                                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                                                            currentPointer = change
                                                                            if (change.isConsumed) {
                                                                                // 已被父级滚动容器消费，放弃点击与长按
                                                                                endPress(true)
                                                                                return@withTimeout
                                                                            }
                                                                            val dist = (change.position - downPos).getDistance()
                                                                            if (dist > touchSlop) {
                                                                                // 移动距离超过滑动容差，属于滑动手势，放弃点击与长按
                                                                                endPress(true)
                                                                                return@withTimeout
                                                                            }
                                                                            if (change.changedToUpIgnoreConsumed()) {
                                                                                // 手指在容差范围内正常抬起，属于纯正的点击
                                                                                change.consume()
                                                                                endPress(false)
                                                                                if (currentSelectMode) {
                                                                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                                                    val project = currentProject
                                                                                    if (selectedProjects.contains(project)) {
                                                                                        selectedProjects.remove(project)
                                                                                    } else {
                                                                                        selectedProjects.add(project)
                                                                                    }
                                                                                } else {
                                                                                    vm.loadProject(currentProject)
                                                                                }
                                                                                return@withTimeout
                                                                            }
                                                                        }
                                                                    }
                                                                } catch (_: PointerEventTimeoutCancellationException) {
                                                                    // 长按时间已到且未移动，触发长按
                                                                    isLongPressTriggered = true
                                                                }

                                                                if (isLongPressTriggered) {
                                                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                                    // 托起卡片: 长按后能拖去画集, 先给出可见的"拿起来了"反馈
                                                                    holdLifted = true
                                                                    var dragStarted = false
                                                                    var totalDrag = Offset.Zero
                                                                    var currentPos = itemCoordinates?.localToRoot(currentPointer.position) ?: downRoot
                                                                    val pointerId = currentPointer.id

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

                                                                            val hit = folderBoundsMap.entries.firstOrNull { (_, rect) ->
                                                                                rect.contains(dragTouchRootPos)
                                                                            }
                                                                            if (hit?.key != hoveredFolderKey) {
                                                                                if (hit != null) {
                                                                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                                                }
                                                                                hoveredFolderKey = hit?.key
                                                                            }
                                                                        }
                                                                    }

                                                                    if (dragStarted) {
                                                                        val targetPath = hoveredFolderKey
                                                                        if (targetPath != null) {
                                                                            val targetFolder =
                                                                                currentProjects.firstOrNull {
                                                                                    it.isFolder && it.filePath == targetPath
                                                                                }
                                                                            if (targetFolder != null) {
                                                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                                                val toMove = if (currentSelectMode && selectedProjects.contains(currentProject)) {
                                                                                    selectedProjects.toList()
                                                                                } else {
                                                                                    listOf(currentProject)
                                                                                }
                                                                                vm.moveProjectsToFolder(toMove, targetFolder.name)
                                                                                Toast.makeText(
                                                                                    context,
                                                                                    String.format(toastMovedToStack, toMove.size, targetFolder.name),
                                                                                    Toast.LENGTH_SHORT,
                                                                                ).show()
                                                                                if (currentSelectMode) {
                                                                                    selectedProjects.clear()
                                                                                    isSelectMode = false
                                                                                }
                                                                            }
                                                                        }
                                                                        draggingProject = null
                                                                        dragTouchRootPos = Offset.Zero
                                                                        hoveredFolderKey = null
                                                                    } else {
                                                                        if (!currentSelectMode) {
                                                                            longPressedProject = currentProject
                                                                        }
                                                                    }
                                                                }
                                                            } finally {
                                                                // 兜底: 手势被滚动/取消打断时不能让按压态或托起态卡住
                                                                endPress(true)
                                                                holdLifted = false
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
                                                            } else if (p.isFolder) {
                                                                vm.currentFolder = p
                                                                vm.refreshProjects()
                                                            } else {
                                                                vm.loadProject(p)
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
                                                modifier = cardModifier,
                                            ) {
                                                val thumb = rememberThumbnail(p.previewPath, p.lastModified)

                                                if (p.isFolder) {
                                                    // Procreate-style loose layered fan stack visual with irregular aspect ratios
                                                    val item0 = p.items.getOrNull(0)
                                                    val item1 = p.items.getOrNull(1)
                                                    val item2 = p.items.getOrNull(2)

                                                    val thumb0 = rememberThumbnail(item0?.previewPath, item0?.lastModified ?: 0L)
                                                    val thumb1 = rememberThumbnail(item1?.previewPath, item1?.lastModified ?: 0L)
                                                    val thumb2 = rememberThumbnail(item2?.previewPath, item2?.lastModified ?: 0L)

                                                    val ratio0 =
                                                        remember(item0?.width, item0?.height) {
                                                            if (item0 != null && item0.width > 0 && item0.height > 0) {
                                                                (item0.width.toFloat() / item0.height.toFloat()).coerceIn(0.72f, 1.38f)
                                                            } else {
                                                                1.0f
                                                            }
                                                        }
                                                    val ratio1 =
                                                        remember(item1?.width, item1?.height, ratio0) {
                                                            if (item1 != null && item1.width > 0 && item1.height > 0) {
                                                                (item1.width.toFloat() / item1.height.toFloat()).coerceIn(0.72f, 1.38f)
                                                            } else {
                                                                ratio0
                                                            }
                                                        }
                                                    val ratio2 =
                                                        remember(item2?.width, item2?.height, ratio0) {
                                                            if (item2 != null && item2.width > 0 && item2.height > 0) {
                                                                (item2.width.toFloat() / item2.height.toFloat()).coerceIn(0.72f, 1.38f)
                                                            } else {
                                                                ratio0
                                                            }
                                                        }

                                                    Box(
                                                        modifier =
                                                            Modifier
                                                                .fillMaxWidth()
                                                                .aspectRatio(1f),
                                                        contentAlignment = Alignment.Center,
                                                    ) {
                                                        // 拖拽悬停描边: 宽度与透明度一起插值, 不再硬切
                                                        val hoverRingWidth by animateDpAsState(
                                                            targetValue = if (isFolderHovered) 3.dp else 0.dp,
                                                            animationSpec =
                                                                spring(
                                                                    dampingRatio = Spring.DampingRatioNoBouncy,
                                                                    stiffness = Spring.StiffnessMedium,
                                                                ),
                                                            label = "FolderHoverRingWidth",
                                                        )
                                                        val hoverRingAlpha by animateFloatAsState(
                                                            targetValue = if (isFolderHovered) 1f else 0f,
                                                            animationSpec = tween(160),
                                                            label = "FolderHoverRingAlpha",
                                                        )
                                                        // 选中时就按选中描边走, 否则跟随悬停淡入
                                                        val ringAlpha = if (borderStrokeWidth > 0.dp) 1f else hoverRingAlpha

                                                        // Stacked layer 1 (bottom left loose tilt & offset with its own aspect ratio)
                                                        Box(
                                                            modifier =
                                                                Modifier
                                                                    .fillMaxSize(0.86f)
                                                                    .aspectRatio(ratio2, matchHeightConstraintsFirst = ratio2 < 1.0f)
                                                                    .graphicsLayer {
                                                                        // 按压时整摞扇形散开: 只读一个进度, 逐帧不重组
                                                                        rotationZ = -7f - 5.5f * fanPress
                                                                        translationX = (-7).dp.toPx() - 5.dp.toPx() * fanPress
                                                                        translationY = 4.dp.toPx() + 3.dp.toPx() * fanPress
                                                                    }
                                                                    .shadow(4.dp, RoundedCornerShape(8.dp), clip = false)
                                                                    .clip(RoundedCornerShape(8.dp))
                                                                    .background(Color(0xFFEDEDED)),
                                                            contentAlignment = Alignment.Center,
                                                        ) {
                                                            if (thumb2 != null) {
                                                                Image(
                                                                    bitmap = thumb2.asImageBitmap(),
                                                                    contentDescription = null,
                                                                    contentScale = ContentScale.Crop,
                                                                    modifier = Modifier.fillMaxSize(),
                                                                )
                                                                Box(
                                                                    modifier =
                                                                        Modifier.fillMaxSize().background(
                                                                            Color.Black.copy(alpha = 0.08f),
                                                                        ),
                                                                )
                                                            } else {
                                                                Box(modifier = Modifier.fillMaxSize().background(Color(0xFFE5E5E7)))
                                                            }
                                                        }

                                                        // Stacked layer 2 (middle right loose tilt & offset with its own aspect ratio)
                                                        Box(
                                                            modifier =
                                                                Modifier
                                                                    .fillMaxSize(0.88f)
                                                                    .aspectRatio(ratio1, matchHeightConstraintsFirst = ratio1 < 1.0f)
                                                                    .graphicsLayer {
                                                                        rotationZ = 6f + 5f * fanPress
                                                                        translationX = 6.dp.toPx() + 5.dp.toPx() * fanPress
                                                                        translationY = (-3).dp.toPx() - 2.dp.toPx() * fanPress
                                                                    }
                                                                    .shadow(6.dp, RoundedCornerShape(8.dp), clip = false)
                                                                    .clip(RoundedCornerShape(8.dp))
                                                                    .background(Color(0xFFF3F3F3)),
                                                            contentAlignment = Alignment.Center,
                                                        ) {
                                                            if (thumb1 != null) {
                                                                Image(
                                                                    bitmap = thumb1.asImageBitmap(),
                                                                    contentDescription = null,
                                                                    contentScale = ContentScale.Crop,
                                                                    modifier = Modifier.fillMaxSize(),
                                                                )
                                                                Box(
                                                                    modifier =
                                                                        Modifier.fillMaxSize().background(
                                                                            Color.Black.copy(alpha = 0.04f),
                                                                        ),
                                                                )
                                                            } else {
                                                                Box(modifier = Modifier.fillMaxSize().background(Color(0xFFEEEEF0)))
                                                            }
                                                        }

                                                        // Foreground main folder cover (with its own aspect ratio)
                                                        Box(
                                                            modifier =
                                                                Modifier
                                                                    .fillMaxSize(0.92f)
                                                                    .aspectRatio(ratio0, matchHeightConstraintsFirst = ratio0 < 1.0f)
                                                                    .graphicsLayer {
                                                                        rotationZ = -0.8f - 1.0f * fanPress
                                                                    }
                                                                    .shadow(8.dp, RoundedCornerShape(8.dp), clip = false)
                                                                    .clip(RoundedCornerShape(8.dp))
                                                                    .background(Color.White)
                                                                    .then(
                                                                        if (hoverRingWidth > 0.dp || borderStrokeWidth > 0.dp) {
                                                                            Modifier.border(
                                                                                width = maxOf(hoverRingWidth, borderStrokeWidth),
                                                                                color = colors.accent.copy(alpha = ringAlpha),
                                                                                shape = RoundedCornerShape(8.dp),
                                                                            )
                                                                        } else {
                                                                            Modifier
                                                                        },
                                                                    ),
                                                            contentAlignment = Alignment.Center,
                                                        ) {
                                                            if (thumb0 != null) {
                                                                Image(
                                                                    bitmap = thumb0.asImageBitmap(),
                                                                    contentDescription = null,
                                                                    contentScale = ContentScale.Crop,
                                                                    modifier = Modifier.fillMaxSize(),
                                                                )
                                                            } else {
                                                                Column(
                                                                    horizontalAlignment = Alignment.CenterHorizontally,
                                                                    verticalArrangement = Arrangement.Center,
                                                                ) {
                                                                    Icon(
                                                                        painterResource(R.drawable.ic_folders),
                                                                        contentDescription = null,
                                                                        tint = Color(0xFF757575),
                                                                        modifier = Modifier.size(36.dp),
                                                                    )
                                                                    Spacer(Modifier.height(4.dp))
                                                                    Text(
                                                                        stringResource(R.string.gallery_badge_stack),
                                                                        color = Color(0xFF9E9E9E),
                                                                        fontSize = 11.sp,
                                                                        fontWeight = FontWeight.Medium,
                                                                    )
                                                                }
                                                            }
                                                        }

                                                        // Badge: Folder count with layer icon pill
                                                        Box(
                                                            modifier =
                                                                Modifier
                                                                    .align(Alignment.TopEnd)
                                                                    .padding(6.dp)
                                                                    .shadow(3.dp, RoundedCornerShape(12.dp), clip = false)
                                                                    .clip(RoundedCornerShape(12.dp))
                                                                    .background(Color.Black.copy(alpha = 0.72f))
                                                                    .padding(horizontal = 7.dp, vertical = 3.dp),
                                                        ) {
                                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                                Icon(
                                                                    painterResource(R.drawable.ic_folders),
                                                                    contentDescription = null,
                                                                    tint = Color.White.copy(alpha = 0.85f),
                                                                    modifier = Modifier.size(11.dp),
                                                                )
                                                                Spacer(Modifier.width(3.dp))
                                                                Text(
                                                                    text = "${p.items.size}",
                                                                    color = Color.White,
                                                                    fontSize = 10.sp,
                                                                    fontWeight = FontWeight.Bold,
                                                                )
                                                            }
                                                        }

                                                        // Drop hint badge when dragging project over folder
                                                        // (此处处于 BoxScope/ColumnScope 混合隐式接收者中, 需显式限定包级版本)
                                                        androidx.compose.animation.AnimatedVisibility(
                                                            visible = isFolderHovered,
                                                            enter =
                                                                scaleIn(
                                                                    spring(
                                                                        dampingRatio = Spring.DampingRatioMediumBouncy,
                                                                        stiffness = Spring.StiffnessMediumLow,
                                                                    ),
                                                                    initialScale = 0.8f,
                                                                ) + fadeIn(tween(140)),
                                                            exit = scaleOut(tween(120), targetScale = 0.9f) + fadeOut(tween(100)),
                                                            modifier = Modifier.align(Alignment.Center),
                                                        ) {
                                                            Box(
                                                                modifier =
                                                                    Modifier
                                                                        .shadow(6.dp, RoundedCornerShape(16.dp))
                                                                        .clip(RoundedCornerShape(16.dp))
                                                                        .background(colors.accent)
                                                                        .padding(horizontal = 10.dp, vertical = 5.dp),
                                                            ) {
                                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                                    Icon(
                                                                        painterResource(R.drawable.ic_folders),
                                                                        contentDescription = null,
                                                                        tint = Color.White,
                                                                        modifier = Modifier.size(13.dp),
                                                                    )
                                                                    Spacer(Modifier.width(4.dp))
                                                                    Text(
                                                                        stringResource(R.string.gallery_drag_drop_to_stack),
                                                                        color = Color.White,
                                                                        fontSize = 11.sp,
                                                                        fontWeight = FontWeight.Bold,
                                                                    )
                                                                }
                                                            }
                                                        }

                                                        // Selection checkmark badge
                                                        CardSelectionBadge(
                                                            isSelectMode = isSelectMode,
                                                            isSelected = isSelected,
                                                            colors = colors,
                                                            modifier =
                                                                Modifier
                                                                    .align(Alignment.BottomEnd)
                                                                    .padding(8.dp),
                                                        )
                                                    }
                                                } else {
                                                    // Single artwork card in uniform 1:1 cell with pure canvas aspect ratio
                                                    val rawRatio = if (p.width > 0 && p.height > 0) p.width.toFloat() / p.height.toFloat() else 1.0f
                                                    // 缩略图解码完成时淡入, 不再"啪"地弹出来
                                                    val thumbFade by animateFloatAsState(
                                                        targetValue = if (thumb != null) 1f else 0f,
                                                        animationSpec = tween(220),
                                                        label = "ThumbFade",
                                                    )
                                                    Box(
                                                        modifier =
                                                            Modifier
                                                                .fillMaxWidth()
                                                                .aspectRatio(1f),
                                                        contentAlignment = Alignment.Center,
                                                    ) {
                                                        Box(
                                                            modifier =
                                                                Modifier
                                                                    .fillMaxSize(0.92f)
                                                                    .aspectRatio(rawRatio, matchHeightConstraintsFirst = rawRatio < 1.0f)
                                                                    .shadow(6.dp, RoundedCornerShape(8.dp), clip = false)
                                                                    .clip(RoundedCornerShape(8.dp))
                                                                    .background(Color.White)
                                                                    .then(
                                                                        if (cardRingWidth > 0.dp) {
                                                                            Modifier.border(cardRingWidth, colors.accent, RoundedCornerShape(8.dp))
                                                                        } else Modifier
                                                                    ),
                                                            contentAlignment = Alignment.Center,
                                                        ) {
                                                            if (thumb != null) {
                                                                Image(
                                                                    bitmap = thumb.asImageBitmap(),
                                                                    contentDescription = null,
                                                                    contentScale = ContentScale.Crop,
                                                                    modifier =
                                                                        Modifier
                                                                            .fillMaxSize()
                                                                            .graphicsLayer { alpha = thumbFade },
                                                                )
                                                            } else {
                                                                Box(
                                                                    modifier = Modifier.fillMaxSize(),
                                                                    contentAlignment = Alignment.Center,
                                                                ) {
                                                                    Icon(
                                                                        painterResource(R.drawable.ic_canvas_tab),
                                                                        contentDescription = null,
                                                                        tint = Color(0xFFB0B0B0),
                                                                        modifier = Modifier.size(36.dp),
                                                                    )
                                                                }
                                                            }
                                                            // 选中蒙层 (与边框一起指示"已选")
                                                            Box(modifier = Modifier.fillMaxSize().background(selectTint))
                                                        }

                                                        // AutoSave recovery badge (自动保存 / 异常退出恢复草稿)
                                                        if (p.isAutoSaved) {
                                                            Box(
                                                                modifier =
                                                                    Modifier
                                                                        .align(Alignment.TopStart)
                                                                        .padding(6.dp)
                                                                        .shadow(3.dp, RoundedCornerShape(12.dp), clip = false)
                                                                        .clip(RoundedCornerShape(12.dp))
                                                                        .background(Color.Black.copy(alpha = 0.55f))
                                                                        .padding(horizontal = 7.dp, vertical = 3.dp),
                                                            ) {
                                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                                    Icon(
                                                                        painterResource(R.drawable.ic_save),
                                                                        contentDescription = null,
                                                                        tint = Color.White,
                                                                        modifier = Modifier.size(10.dp),
                                                                    )
                                                                    Spacer(Modifier.width(3.dp))
                                                                    Text(
                                                                        text = stringResource(R.string.gallery_badge_autosave),
                                                                        color = Color.White,
                                                                        fontSize = 10.sp,
                                                                        fontWeight = FontWeight.Bold,
                                                                    )
                                                                }
                                                            }
                                                        }

                                                        // Animation badge (动画项目)
                                                        if (!p.isFolder && p.isAnimation) {
                                                            Box(
                                                                modifier =
                                                                    Modifier
                                                                        .align(Alignment.TopEnd)
                                                                        .padding(6.dp)
                                                                        .shadow(3.dp, RoundedCornerShape(12.dp), clip = false)
                                                                        .clip(RoundedCornerShape(12.dp))
                                                                        .background(Color.Black.copy(alpha = 0.55f))
                                                                        .padding(horizontal = 7.dp, vertical = 3.dp),
                                                            ) {
                                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                                    Icon(
                                                                        painterResource(R.drawable.ic_clock),
                                                                        contentDescription = null,
                                                                        tint = Color.White,
                                                                        modifier = Modifier.size(10.dp),
                                                                    )
                                                                    Spacer(Modifier.width(3.dp))
                                                                    Text(
                                                                        text = stringResource(R.string.gallery_badge_anim),
                                                                        color = Color.White,
                                                                        fontSize = 10.sp,
                                                                        fontWeight = FontWeight.Bold,
                                                                    )
                                                                }
                                                            }
                                                        }

                                                        // Album cover badge (画集封面角标)
                                                        if (currentFolder != null && p.isFolderCover) {
                                                            Box(
                                                                modifier =
                                                                    Modifier
                                                                        .align(Alignment.BottomStart)
                                                                        .padding(6.dp)
                                                                        .shadow(3.dp, RoundedCornerShape(12.dp), clip = false)
                                                                        .clip(RoundedCornerShape(12.dp))
                                                                        .background(colors.accent.copy(alpha = 0.90f))
                                                                        .padding(horizontal = 7.dp, vertical = 3.dp),
                                                            ) {
                                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                                    Icon(
                                                                        painterResource(R.drawable.ic_image),
                                                                        contentDescription = null,
                                                                        tint = Color.White,
                                                                        modifier = Modifier.size(10.dp),
                                                                    )
                                                                    Spacer(Modifier.width(3.dp))
                                                                    Text(
                                                                        text = stringResource(R.string.gallery_cover_badge),
                                                                        color = Color.White,
                                                                        fontSize = 10.sp,
                                                                        fontWeight = FontWeight.Bold,
                                                                    )
                                                                }
                                                            }
                                                        }

                                                        // Selection checkmark badge
                                                        CardSelectionBadge(
                                                            isSelectMode = isSelectMode,
                                                            isSelected = isSelected,
                                                            colors = colors,
                                                            modifier =
                                                                Modifier
                                                                    .align(Alignment.BottomEnd)
                                                                    .padding(8.dp),
                                                        )
                                                    }
                                                }

                                                Spacer(Modifier.height(8.dp))
                                                Text(
                                                    text = p.name,
                                                    color = colors.text,
                                                    fontSize = 14.sp,
                                                    fontWeight = FontWeight.Medium,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                                Spacer(Modifier.height(2.dp))
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                ) {
                                                    val justNowText = stringResource(R.string.gallery_time_just_now)
                                                    val autoSavedText = stringResource(R.string.gallery_status_autosave)
                                                    val dateStr =
                                                        remember(p.lastModified) {
                                                            if (p.lastModified > 0) {
                                                                SimpleDateFormat(
                                                                    "MM-dd HH:mm",
                                                                    Locale.getDefault(),
                                                                ).format(Date(p.lastModified))
                                                            } else {
                                                                justNowText
                                                            }
                                                        }
                                                    val statusText = if (p.isAutoSaved) autoSavedText else dateStr
                                                    Text(
                                                        text = if (p.isFolder) stringResource(R.string.gallery_stack_artworks_count, p.items.size) else statusText,
                                                        color = if (p.isAutoSaved) colors.accent else colors.subText,
                                                        fontSize = 11.sp,
                                                        fontWeight = if (p.isAutoSaved) FontWeight.SemiBold else FontWeight.Normal,
                                                    )
                                                    if (!p.isFolder && p.strokeCount > 0) {
                                                        Text(
                                                            text = stringResource(R.string.gallery_stroke_count, p.strokeCount),
                                                            color = colors.subText,
                                                            fontSize = 11.sp,
                                                        )
                                                    }
                                                }
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
        if (draggingProject != null) {
            val dragProj = draggingProject!!
            val dragThumb = rememberThumbnail(dragProj.previewPath, dragProj.lastModified)
            val dragCount = if (isSelectMode && selectedProjects.contains(dragProj)) selectedProjects.size else 1
            val dragCardSize = 130.dp
            val density = LocalDensity.current
            val halfSizePx = with(density) { (dragCardSize / 2).toPx() }

            // 拿起来的一瞬间轻微弹出, 与源卡片的托起动画衔接
            val dragLift = remember(dragProj.filePath) { Animatable(0.82f) }
            LaunchedEffect(dragProj.filePath) { dragLift.animateTo(1f, Motion.snapBouncy) }

            Box(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier =
                        Modifier
                            .offset {
                                IntOffset(
                                    (dragTouchRootPos.x - halfSizePx).roundToInt(),
                                    (dragTouchRootPos.y - halfSizePx).roundToInt(),
                                )
                            }
                            .size(dragCardSize)
                            .shadow(16.dp, RoundedCornerShape(12.dp), clip = false)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.White)
                            .border(2.dp, colors.accent, RoundedCornerShape(12.dp))
                            .graphicsLayer {
                                rotationZ = -4f
                                alpha = 0.92f
                                scaleX = dragLift.value
                                scaleY = dragLift.value
                            },
                    contentAlignment = Alignment.Center,
                ) {
                    if (dragThumb != null) {
                        Image(
                            bitmap = dragThumb.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Box(modifier = Modifier.fillMaxSize().background(Color(0xFFEEEEF0)))
                    }

                    if (dragCount > 1) {
                        Box(
                            modifier =
                                Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(6.dp)
                                    .shadow(4.dp, CircleShape)
                                    .clip(CircleShape)
                                    .background(colors.accent)
                                    .size(24.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "$dragCount",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }
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

