package com.reverie.paint.ui.home

import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.reverie.paint.R
import com.reverie.paint.core.*
import com.reverie.paint.model.Project
import com.reverie.paint.ui.theme.AppColors
import com.reverie.paint.ui.theme.Theme
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.haze
import kotlinx.coroutines.delay
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

// In-Memory LRU Cache for high-performance thumbnail rendering without disk jank
private object ThumbnailCache {
    private val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
    private val cacheSize = (maxMemory / 8).coerceAtLeast(1024 * 16)
    private val lruCache =
        object : android.util.LruCache<String, android.graphics.Bitmap>(cacheSize) {
            override fun sizeOf(
                key: String,
                bitmap: android.graphics.Bitmap,
            ): Int = bitmap.byteCount / 1024
        }

    fun get(
        path: String,
        lastModified: Long,
    ): android.graphics.Bitmap? {
        if (path.isEmpty()) return null
        val key = "$path:$lastModified"
        val cached = lruCache.get(key)
        if (cached != null && !cached.isRecycled) return cached
        val file = File(path)
        if (file.exists()) {
            return try {
                val bmp = BitmapFactory.decodeFile(path)
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

    // Long-press Context Menu on card
    var longPressedProject by remember { mutableStateOf<Project?>(null) }

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

    // Custom Styled Dialog: Create Stack / Folder (新建画集)
    if (showNewFolderDialog) {
        NewFolderDialog(
            colors = colors,
            folderName = newFolderName,
            onFolderNameChange = { newFolderName = it },
            onCreate = { name ->
                vm.createFolder(name)
                Toast.makeText(context, "已创建画集: $name", Toast.LENGTH_SHORT).show()
            },
            onDismiss = { showNewFolderDialog = false },
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

    if (projectToDelete != null) {
        val deleteTarget = projectToDelete!!
        DeleteProjectDialog(
            colors = colors,
            target = deleteTarget,
            onDelete = {
                vm.deleteProject(deleteTarget)
                Toast.makeText(context, if (deleteTarget.isFolder) "画集已删除" else "作品已删除", Toast.LENGTH_SHORT).show()
            },
            onDismiss = { projectToDelete = null },
        )
    }

    if (showBatchDeleteConfirm && selectedProjects.isNotEmpty()) {
        BatchDeleteConfirmDialog(
            colors = colors,
            count = selectedProjects.size,
            onConfirm = {
                selectedProjects.forEach { vm.deleteProject(it) }
                selectedProjects.clear()
                isSelectMode = false
                Toast.makeText(context, "已删除 ${selectedProjects.size} 项内容", Toast.LENGTH_SHORT).show()
            },
            onDismiss = { showBatchDeleteConfirm = false },
        )
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
                                                    contentDescription = "返回",
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
                                        Text(
                                            text = "已选 ${selectedProjects.size} 项",
                                            color = colors.text,
                                            fontSize = 17.sp,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                    }

                                    else -> {
                                        Column {
                                            Text(
                                                text = "画廊",
                                                color = colors.text,
                                                fontSize = 22.sp,
                                                fontWeight = FontWeight.Bold,
                                                letterSpacing = 0.5.sp,
                                            )
                                            Text(
                                                text = "${displayProjects.size} 个项目",
                                                color = colors.subText,
                                                fontSize = 11.sp,
                                            )
                                        }
                                    }
                                }
                            }

                            Spacer(Modifier.weight(1f))

                            AnimatedVisibility(
                                visible = isSearchActive,
                                enter = fadeIn(tween(180)) + expandHorizontally(expandFrom = Alignment.End),
                                exit = fadeOut(tween(150)) + shrinkHorizontally(shrinkTowards = Alignment.End),
                            ) {
                                OutlinedTextField(
                                    value = vm.searchQuery,
                                    onValueChange = { vm.searchQuery = it },
                                    placeholder = { Text("搜索作品...", color = colors.subText, fontSize = 13.sp) },
                                    singleLine = true,
                                    trailingIcon = {
                                        IconButton(onClick = {
                                            vm.searchQuery = ""
                                            isSearchActive = false
                                        }) {
                                            Icon(
                                                painterResource(R.drawable.ic_x),
                                                contentDescription = "Close",
                                                tint = colors.subText,
                                                modifier = Modifier.size(18.dp),
                                            )
                                        }
                                    },
                                    colors =
                                        OutlinedTextFieldDefaults.colors(
                                            focusedTextColor = colors.text,
                                            unfocusedTextColor = colors.text,
                                            focusedBorderColor = colors.accent,
                                            unfocusedBorderColor = colors.border,
                                            focusedContainerColor = colors.panelHi,
                                            unfocusedContainerColor = colors.panelHi,
                                            cursorColor = colors.accent,
                                        ),
                                    modifier =
                                        Modifier
                                            .width(220.dp)
                                            .height(44.dp),
                                )
                            }

                            if (!isSearchActive) {
                                if (isSelectMode) {
                                    TextButton(onClick = {
                                        isSelectMode = false
                                        selectedProjects.clear()
                                    }) {
                                        Text("完成", color = colors.accent, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                    }
                                } else {
                                    // Top Bar Buttons Group
                                    Row(
                                        modifier =
                                            Modifier
                                                .clip(RoundedCornerShape(20.dp))
                                                .background(colors.panel.copy(alpha = 0.85f))
                                                .border(1.dp, colors.border, RoundedCornerShape(20.dp))
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
                                                contentDescription = "Search",
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
                                                    contentDescription = "More",
                                                    tint = colors.icon,
                                                    modifier = Modifier.size(18.dp),
                                                )
                                            }

                                            DropdownMenu(
                                                expanded = showMoreMenu,
                                                onDismissRequest = { showMoreMenu = false },
                                                modifier =
                                                    Modifier
                                                        .background(
                                                            colors.panel,
                                                        ).border(1.dp, colors.border, RoundedCornerShape(10.dp)),
                                            ) {
                                                DropdownMenuItem(
                                                    text = { Text("选择", color = colors.text) },
                                                    onClick = {
                                                        showMoreMenu = false
                                                        isSelectMode = true
                                                        selectedProjects.clear()
                                                    },
                                                    leadingIcon = {
                                                        Icon(
                                                            painterResource(R.drawable.ic_circle_check),
                                                            contentDescription = null,
                                                            tint = colors.icon,
                                                            modifier = Modifier.size(18.dp),
                                                        )
                                                    },
                                                )
                                                DropdownMenuItem(
                                                    text = { Text("新建画集", color = colors.text) },
                                                    onClick = {
                                                        showMoreMenu = false
                                                        newFolderName = "画集_${System.currentTimeMillis() % 1000}"
                                                        showNewFolderDialog = true
                                                    },
                                                    leadingIcon = {
                                                        Icon(
                                                            painterResource(R.drawable.ic_folder_plus),
                                                            contentDescription = null,
                                                            tint = colors.icon,
                                                            modifier = Modifier.size(18.dp),
                                                        )
                                                    },
                                                )
                                                DropdownMenuItem(
                                                    text = { Text("刷新作品", color = colors.text) },
                                                    onClick = {
                                                        showMoreMenu = false
                                                        vm.refreshProjects()
                                                    },
                                                    leadingIcon = {
                                                        Icon(
                                                            painterResource(R.drawable.ic_refresh),
                                                            contentDescription = null,
                                                            tint = colors.icon,
                                                            modifier = Modifier.size(18.dp),
                                                        )
                                                    },
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // Clean Animated Folder Transition with Staggered Per-Card Cascade Unfolding
                        AnimatedContent(
                            targetState = currentFolder,
                            transitionSpec = {
                                (fadeIn(tween(220, easing = FastOutSlowInEasing)))
                                    .togetherWith(fadeOut(tween(140, easing = FastOutSlowInEasing)))
                            },
                            modifier = Modifier.weight(1f),
                            label = "FolderTransition",
                        ) { targetFolder ->
                            if (displayProjects.isEmpty()) {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
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
                                                tint = colors.accent.copy(alpha = 0.7f),
                                                modifier = Modifier.size(36.dp),
                                            )
                                        }
                                        Spacer(Modifier.height(16.dp))
                                        Text(
                                            if (vm.searchQuery.isNotEmpty()) {
                                                "未找到相关作品"
                                            } else if (targetFolder !=
                                                null
                                            ) {
                                                "画集中暂无作品"
                                            } else {
                                                "开启你的第一幅画作"
                                            },
                                            color = colors.text,
                                            fontSize = 16.sp,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                        Spacer(Modifier.height(6.dp))
                                        Text(
                                            if (vm.searchQuery.isNotEmpty()) {
                                                "请尝试使用其他关键词搜索"
                                            } else if (targetFolder !=
                                                null
                                            ) {
                                                "你可以长按外部作品并选择「移动到画集」"
                                            } else {
                                                "点击下方「＋」按钮创建新画布或导入图像"
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
                                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(16.dp),
                                    contentPadding = PaddingValues(top = 8.dp, bottom = 100.dp),
                                ) {
                                    itemsIndexed(displayProjects, key = { _, it -> it.filePath }) { index, p ->
                                        val isSelected = selectedProjects.contains(p)

                                        // Staggered cascade entrance physics for each individual painting card
                                        val enterProgress = remember(targetFolder?.filePath, p.filePath) { Animatable(0f) }
                                        LaunchedEffect(targetFolder?.filePath, p.filePath) {
                                            val delayMs = (index * 25L).coerceAtMost(200L)
                                            delay(delayMs)
                                            enterProgress.animateTo(
                                                targetValue = 1f,
                                                animationSpec =
                                                    spring(
                                                        dampingRatio = Spring.DampingRatioLowBouncy,
                                                        stiffness = Spring.StiffnessMediumLow,
                                                    ),
                                            )
                                        }

                                        val progress = enterProgress.value
                                        val itemScale = 0.74f + 0.26f * progress
                                        val itemOffsetY = 28.dp * (1f - progress)
                                        val initialFanAngle =
                                            remember(p.filePath) {
                                                val hash = kotlin.math.abs(p.filePath.hashCode())
                                                ((hash % 9) - 4).toFloat() * 1.5f // -6° to +6° fanned spread
                                            }
                                        val itemRotation = (1f - progress) * initialFanAngle

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

                                        // Organic tactile fan-out physics when pressing stack card
                                        val fanBottomAngle by animateFloatAsState(
                                            targetValue = if (isPressed) -12.5f else -7.0f,
                                            animationSpec =
                                                spring(
                                                    dampingRatio = Spring.DampingRatioMediumBouncy,
                                                    stiffness = Spring.StiffnessMediumLow,
                                                ),
                                            label = "FanBottomAngle",
                                        )
                                        val fanBottomOffsetX by animateDpAsState(
                                            targetValue = if (isPressed) (-12).dp else (-7).dp,
                                            animationSpec =
                                                spring(
                                                    dampingRatio = Spring.DampingRatioMediumBouncy,
                                                    stiffness = Spring.StiffnessMediumLow,
                                                ),
                                            label = "FanBottomOffsetX",
                                        )
                                        val fanBottomOffsetY by animateDpAsState(
                                            targetValue = if (isPressed) 7.dp else 4.dp,
                                            animationSpec =
                                                spring(
                                                    dampingRatio = Spring.DampingRatioMediumBouncy,
                                                    stiffness = Spring.StiffnessMediumLow,
                                                ),
                                            label = "FanBottomOffsetY",
                                        )

                                        val fanMiddleAngle by animateFloatAsState(
                                            targetValue = if (isPressed) 11.0f else 6.0f,
                                            animationSpec =
                                                spring(
                                                    dampingRatio = Spring.DampingRatioMediumBouncy,
                                                    stiffness = Spring.StiffnessMediumLow,
                                                ),
                                            label = "FanMiddleAngle",
                                        )
                                        val fanMiddleOffsetX by animateDpAsState(
                                            targetValue = if (isPressed) 11.dp else 6.dp,
                                            animationSpec =
                                                spring(
                                                    dampingRatio = Spring.DampingRatioMediumBouncy,
                                                    stiffness = Spring.StiffnessMediumLow,
                                                ),
                                            label = "FanMiddleOffsetX",
                                        )
                                        val fanMiddleOffsetY by animateDpAsState(
                                            targetValue = if (isPressed) (-5).dp else (-3).dp,
                                            animationSpec =
                                                spring(
                                                    dampingRatio = Spring.DampingRatioMediumBouncy,
                                                    stiffness = Spring.StiffnessMediumLow,
                                                ),
                                            label = "FanMiddleOffsetY",
                                        )

                                        val fanTopAngle by animateFloatAsState(
                                            targetValue = if (isPressed) -1.8f else -0.8f,
                                            animationSpec =
                                                spring(
                                                    dampingRatio = Spring.DampingRatioMediumBouncy,
                                                    stiffness = Spring.StiffnessMediumLow,
                                                ),
                                            label = "FanTopAngle",
                                        )

                                        Box(
                                            modifier =
                                                Modifier.graphicsLayer {
                                                    alpha = progress.coerceIn(0f, 1f)
                                                    scaleX = itemScale * cardPressScale
                                                    scaleY = itemScale * cardPressScale
                                                    translationY = itemOffsetY.toPx()
                                                    rotationZ = itemRotation
                                                },
                                        ) {
                                            Column(
                                                modifier =
                                                    Modifier
                                                        .fillMaxWidth()
                                                        .combinedClickable(
                                                            interactionSource = interactionSource,
                                                            indication = null,
                                                            onClick = {
                                                                if (isSelectMode) {
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
                                                        ),
                                            ) {
                                                val thumb =
                                                    remember(p.previewPath, p.lastModified) {
                                                        ThumbnailCache.get(p.previewPath, p.lastModified)
                                                    }

                                                if (p.isFolder) {
                                                    // Procreate-style loose layered fan stack visual with irregular aspect ratios
                                                    val item0 = p.items.getOrNull(0)
                                                    val item1 = p.items.getOrNull(1)
                                                    val item2 = p.items.getOrNull(2)

                                                    val thumb0 =
                                                        remember(item0?.previewPath, item0?.lastModified) {
                                                            item0?.let { ThumbnailCache.get(it.previewPath, it.lastModified) }
                                                        }
                                                    val thumb1 =
                                                        remember(item1?.previewPath, item1?.lastModified) {
                                                            item1?.let { ThumbnailCache.get(it.previewPath, it.lastModified) }
                                                        }
                                                    val thumb2 =
                                                        remember(item2?.previewPath, item2?.lastModified) {
                                                            item2?.let { ThumbnailCache.get(it.previewPath, it.lastModified) }
                                                        }

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
                                                        // Stacked layer 1 (bottom left loose tilt & offset with its own aspect ratio)
                                                        Box(
                                                            modifier =
                                                                Modifier
                                                                    .fillMaxSize(0.86f)
                                                                    .aspectRatio(ratio2, matchHeightConstraintsFirst = ratio2 < 1.0f)
                                                                    .offset(x = fanBottomOffsetX, y = fanBottomOffsetY)
                                                                    .rotate(fanBottomAngle)
                                                                    .shadow(4.dp, RoundedCornerShape(8.dp), clip = false)
                                                                    .clip(RoundedCornerShape(8.dp))
                                                                    .background(Color(0xFFEDEDED))
                                                                    .border(
                                                                        0.5.dp,
                                                                        Color.Black.copy(alpha = 0.12f),
                                                                        RoundedCornerShape(8.dp),
                                                                    ),
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
                                                                    .offset(x = fanMiddleOffsetX, y = fanMiddleOffsetY)
                                                                    .rotate(fanMiddleAngle)
                                                                    .shadow(6.dp, RoundedCornerShape(8.dp), clip = false)
                                                                    .clip(RoundedCornerShape(8.dp))
                                                                    .background(Color(0xFFF3F3F3))
                                                                    .border(
                                                                        0.5.dp,
                                                                        Color.Black.copy(alpha = 0.15f),
                                                                        RoundedCornerShape(8.dp),
                                                                    ),
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
                                                                    .rotate(fanTopAngle)
                                                                    .shadow(8.dp, RoundedCornerShape(8.dp), clip = false)
                                                                    .clip(RoundedCornerShape(8.dp))
                                                                    .background(Color.White)
                                                                    .border(
                                                                        if (isSelectMode && isSelected) 2.5.dp else 0.5.dp,
                                                                        if (isSelectMode &&
                                                                            isSelected
                                                                        ) {
                                                                            colors.accent
                                                                        } else {
                                                                            Color.Black.copy(alpha = 0.18f)
                                                                        },
                                                                        RoundedCornerShape(8.dp),
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
                                                                        "画集",
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

                                                        // Selection checkmark
                                                        if (isSelectMode) {
                                                            Box(
                                                                modifier =
                                                                    Modifier
                                                                        .align(Alignment.BottomEnd)
                                                                        .padding(6.dp)
                                                                        .size(22.dp)
                                                                        .clip(CircleShape)
                                                                        .background(
                                                                            if (isSelected) {
                                                                                colors.accent
                                                                            } else {
                                                                                Color.Black.copy(
                                                                                    alpha = 0.45f,
                                                                                )
                                                                            },
                                                                        ),
                                                                contentAlignment = Alignment.Center,
                                                            ) {
                                                                if (isSelected) {
                                                                    Icon(
                                                                        painterResource(R.drawable.ic_check),
                                                                        contentDescription = null,
                                                                        tint = Color.White,
                                                                        modifier = Modifier.size(14.dp),
                                                                    )
                                                                }
                                                            }
                                                        }
                                                    }
                                                } else {
                                                    // Single artwork card in uniform 1:1 cell with pure canvas aspect ratio
                                                    val rawRatio = if (p.width > 0 && p.height > 0) p.width.toFloat() / p.height.toFloat() else 1.0f
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
                                                                    .shadow(4.dp, RoundedCornerShape(8.dp), clip = false)
                                                                    .clip(RoundedCornerShape(8.dp))
                                                                    .background(Color.White)
                                                                    .border(
                                                                        if (isSelectMode && isSelected) 2.5.dp else 0.5.dp,
                                                                        if (isSelectMode &&
                                                                            isSelected
                                                                        ) {
                                                                            colors.accent
                                                                        } else {
                                                                            Color.Black.copy(alpha = 0.12f)
                                                                        },
                                                                        RoundedCornerShape(8.dp),
                                                                    ),
                                                            contentAlignment = Alignment.Center,
                                                        ) {
                                                            if (thumb != null) {
                                                                Image(
                                                                    bitmap = thumb.asImageBitmap(),
                                                                    contentDescription = null,
                                                                    contentScale = ContentScale.Crop,
                                                                    modifier = Modifier.fillMaxSize(),
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
                                                        }

                                                        // Selection checkmark
                                                        if (isSelectMode) {
                                                            Box(
                                                                modifier =
                                                                    Modifier
                                                                        .align(Alignment.BottomEnd)
                                                                        .padding(6.dp)
                                                                        .size(22.dp)
                                                                        .clip(CircleShape)
                                                                        .background(
                                                                            if (isSelected) {
                                                                                colors.accent
                                                                            } else {
                                                                                Color.Black.copy(
                                                                                    alpha = 0.45f,
                                                                                )
                                                                            },
                                                                        ),
                                                                contentAlignment = Alignment.Center,
                                                            ) {
                                                                if (isSelected) {
                                                                    Icon(
                                                                        painterResource(R.drawable.ic_check),
                                                                        contentDescription = null,
                                                                        tint = Color.White,
                                                                        modifier = Modifier.size(14.dp),
                                                                    )
                                                                }
                                                            }
                                                        }
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
                                                    val dateStr =
                                                        remember(p.lastModified) {
                                                            if (p.lastModified > 0) {
                                                                SimpleDateFormat(
                                                                    "MM-dd HH:mm",
                                                                    Locale.getDefault(),
                                                                ).format(Date(p.lastModified))
                                                            } else {
                                                                "刚刚"
                                                            }
                                                        }
                                                    Text(
                                                        text = if (p.isFolder) "${p.items.size} 个作品" else dateStr,
                                                        color = colors.subText,
                                                        fontSize = 11.sp,
                                                    )
                                                    if (!p.isFolder && p.strokeCount > 0) {
                                                        Text(
                                                            text = "${p.strokeCount} 笔",
                                                            color = colors.subText,
                                                            fontSize = 11.sp,
                                                        )
                                                    }
                                                }
                                            }

                                            // Long-press Context Dropdown Menu
                                            DropdownMenu(
                                                expanded = longPressedProject == p,
                                                onDismissRequest = { longPressedProject = null },
                                                modifier =
                                                    Modifier
                                                        .background(
                                                            colors.panel,
                                                        ).border(1.dp, colors.border, RoundedCornerShape(10.dp)),
                                            ) {
                                                DropdownMenuItem(
                                                    text = { Text(if (p.isFolder) "打开画集" else "打开作品", color = colors.text) },
                                                    onClick = {
                                                        longPressedProject = null
                                                        if (p.isFolder) {
                                                            vm.currentFolder = p
                                                            vm.refreshProjects()
                                                        } else {
                                                            vm.loadProject(p)
                                                        }
                                                    },
                                                    leadingIcon = {
                                                        Icon(
                                                            painterResource(R.drawable.ic_external_link),
                                                            contentDescription = null,
                                                            tint = colors.icon,
                                                            modifier = Modifier.size(18.dp),
                                                        )
                                                    },
                                                )
                                                if (!p.isFolder && p.hasRecording) {
                                                    DropdownMenuItem(
                                                        text = { Text("回放", color = colors.accent) },
                                                        onClick = {
                                                            longPressedProject = null
                                                            vm.goReplay(p)
                                                        },
                                                        leadingIcon = {
                                                            Icon(
                                                                painterResource(R.drawable.ic_play),
                                                                contentDescription = null,
                                                                tint = colors.accent,
                                                                modifier = Modifier.size(18.dp),
                                                            )
                                                        },
                                                    )
                                                }
                                                if (!p.isFolder) {
                                                    DropdownMenuItem(
                                                        text = { Text("移动到画集...", color = colors.text) },
                                                        onClick = {
                                                            longPressedProject = null
                                                            targetMoveProjects = listOf(p)
                                                            showMoveDialog = true
                                                        },
                                                        leadingIcon = {
                                                            Icon(
                                                                painterResource(R.drawable.ic_folder_symlink),
                                                                contentDescription = null,
                                                                tint = colors.icon,
                                                                modifier = Modifier.size(18.dp),
                                                            )
                                                        },
                                                    )
                                                }
                                                DropdownMenuItem(
                                                    text = { Text("重命名", color = colors.text) },
                                                    onClick = {
                                                        longPressedProject = null
                                                        targetRenameProject = p
                                                        newProjectName = p.name
                                                        showRenameDialog = true
                                                    },
                                                    leadingIcon = {
                                                        Icon(
                                                            painterResource(R.drawable.ic_pencil),
                                                            contentDescription = null,
                                                            tint = colors.icon,
                                                            modifier = Modifier.size(18.dp),
                                                        )
                                                    },
                                                )
                                                DropdownMenuItem(
                                                    text = { Text(if (p.isFolder) "删除画集" else "删除作品", color = Color(0xFFFF5252)) },
                                                    onClick = {
                                                        longPressedProject = null
                                                        projectToDelete = p
                                                    },
                                                    leadingIcon = {
                                                        Icon(
                                                            painterResource(R.drawable.ic_trash),
                                                            contentDescription = null,
                                                            tint = Color(0xFFFF5252),
                                                            modifier = Modifier.size(18.dp),
                                                        )
                                                    },
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } // Added closing brace for Column

                    // Floating selection mode action bar (Move, Delete)
                    androidx.compose.animation.AnimatedVisibility(
                        visible = isSelectMode && selectedProjects.isNotEmpty(),
                        enter = fadeIn(tween(180)) + slideInVertically(tween(180)) { it / 2 },
                        exit = fadeOut(tween(150)) + slideOutVertically(tween(150)) { it / 2 },
                        modifier =
                            Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 20.dp),
                    ) {
                        Row(
                            modifier =
                                Modifier
                                    .clip(RoundedCornerShape(24.dp))
                                    .background(colors.panelHi)
                                    .border(1.dp, colors.border, RoundedCornerShape(24.dp))
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TextButton(onClick = {
                                targetMoveProjects = selectedProjects.toList()
                                showMoveDialog = true
                            }) {
                                Icon(
                                    painterResource(R.drawable.ic_folder_symlink),
                                    contentDescription = null,
                                    tint = colors.accent,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    "移动 (${selectedProjects.size})",
                                    color = colors.accent,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                            Box(modifier = Modifier.width(1.dp).height(18.dp).background(colors.border))
                            TextButton(onClick = {
                                showBatchDeleteConfirm = true
                            }) {
                                Icon(
                                    painterResource(R.drawable.ic_trash),
                                    contentDescription = null,
                                    tint = Color(0xFFFF5252),
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(6.dp))
                                Text("删除", color = Color(0xFFFF5252), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            } else {
                // Settings Tab
                Box(modifier = Modifier.fillMaxSize()) {
                    SettingsPageContent(vm)
                }
            }
        }

        HomeBottomBar(
            colors = colors,
            vm = vm,
            selectedTab = selectedTab,
            hazeState = hazeState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding(),
        )
    }
}
