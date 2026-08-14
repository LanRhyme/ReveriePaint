package com.reverie.paint.ui.home

import android.graphics.BitmapFactory
import android.widget.Toast
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.automirrored.filled.Launch
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.Brush
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Settings
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.activity.compose.BackHandler
import com.reverie.paint.R
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.model.Project
import com.reverie.paint.ui.theme.Theme
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomePage(vm: PaintViewModel) {
    val colors = Theme.current
    val selectedTab = vm.homeSelectedTab
    val context = LocalContext.current

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

    var showMoreMenu by remember { mutableStateOf(false) }

    // Long-press Context Menu on card
    var longPressedProject by remember { mutableStateOf<Project?>(null) }

    // Filter projects by search query
    val currentFolder = vm.currentFolder
    val displayProjects = remember(vm.projects, vm.searchQuery, currentFolder) {
        val q = vm.searchQuery.trim().lowercase()
        if (q.isEmpty()) {
            vm.projects
        } else {
            vm.projects.filter { it.name.lowercase().contains(q) }
        }
    }

    // Refresh projects upon entering
    LaunchedEffect(currentFolder) {
        vm.refreshProjects()
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
        Dialog(onDismissRequest = { showNewFolderDialog = false }) {
            Box(
                modifier = Modifier
                    .width(320.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(colors.panel)
                    .border(1.dp, colors.border, RoundedCornerShape(16.dp))
                    .padding(22.dp)
            ) {
                Column {
                    Text(
                        text = "新建画集",
                        color = colors.text,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(14.dp))
                    OutlinedTextField(
                        value = newFolderName,
                        onValueChange = { newFolderName = it },
                        singleLine = true,
                        placeholder = { Text("画集名称", color = colors.subText) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = colors.text,
                            unfocusedTextColor = colors.text,
                            focusedBorderColor = colors.accent,
                            unfocusedBorderColor = colors.border,
                            focusedContainerColor = colors.panelHi,
                            unfocusedContainerColor = colors.panelHi,
                            cursorColor = colors.accent
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(20.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = { showNewFolderDialog = false }) {
                            Text("取消", color = colors.subText)
                        }
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = {
                            if (newFolderName.isNotBlank()) {
                                vm.createFolder(newFolderName.trim())
                                Toast.makeText(context, "已创建画集: ${newFolderName.trim()}", Toast.LENGTH_SHORT).show()
                            }
                            showNewFolderDialog = false
                            newFolderName = ""
                        }) {
                            Text("创建", color = colors.accent, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }

    // Custom Styled Dialog: Rename (重命名)
    if (showRenameDialog && targetRenameProject != null) {
        Dialog(onDismissRequest = { showRenameDialog = false }) {
            Box(
                modifier = Modifier
                    .width(320.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(colors.panel)
                    .border(1.dp, colors.border, RoundedCornerShape(16.dp))
                    .padding(22.dp)
            ) {
                Column {
                    Text(
                        text = if (targetRenameProject?.isFolder == true) "重命名画集" else "重命名作品",
                        color = colors.text,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(14.dp))
                    OutlinedTextField(
                        value = newProjectName,
                        onValueChange = { newProjectName = it },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = colors.text,
                            unfocusedTextColor = colors.text,
                            focusedBorderColor = colors.accent,
                            unfocusedBorderColor = colors.border,
                            focusedContainerColor = colors.panelHi,
                            unfocusedContainerColor = colors.panelHi,
                            cursorColor = colors.accent
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(20.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = {
                            showRenameDialog = false
                            targetRenameProject = null
                        }) {
                            Text("取消", color = colors.subText)
                        }
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = {
                            val p = targetRenameProject
                            if (p != null && newProjectName.isNotBlank()) {
                                vm.renameProject(p, newProjectName.trim())
                            }
                            showRenameDialog = false
                            targetRenameProject = null
                        }) {
                            Text("确定", color = colors.accent, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }

    // Custom Styled Dialog: Move to Stack / Root (移动到画集)
    if (showMoveDialog && targetMoveProjects.isNotEmpty()) {
        val rootDir = vm.projectDir()
        val allFolders = remember(vm.projects) {
            rootDir.listFiles { f: File -> f.isDirectory }?.map { it.name } ?: emptyList()
        }

        Dialog(onDismissRequest = { showMoveDialog = false }) {
            Box(
                modifier = Modifier
                    .width(320.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(colors.panel)
                    .border(1.dp, colors.border, RoundedCornerShape(16.dp))
                    .padding(22.dp)
            ) {
                Column {
                    Text(
                        text = "移动作品到画集",
                        color = colors.text,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(14.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        // Option to move to Root
                        if (currentFolder != null) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(colors.panelHi)
                                    .border(1.dp, colors.border.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                                    .clickable {
                                        targetMoveProjects.forEach { p ->
                                            vm.moveProjectToFolder(p, null)
                                        }
                                        Toast.makeText(context, "已移出到画廊根目录", Toast.LENGTH_SHORT).show()
                                        showMoveDialog = false
                                        isSelectMode = false
                                        selectedProjects.clear()
                                    }
                                    .padding(14.dp)
                            ) {
                                Text("📁 移出到画廊根目录", color = colors.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }

                        if (allFolders.isEmpty()) {
                            Text("当前暂无画集，可先在右上角菜单中新建画集", color = colors.subText, fontSize = 13.sp)
                        } else {
                            allFolders.forEach { folderName ->
                                if (currentFolder?.name != folderName) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(colors.panelHi)
                                            .border(1.dp, colors.border.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                                            .clickable {
                                                targetMoveProjects.forEach { p ->
                                                    vm.moveProjectToFolder(p, folderName)
                                                }
                                                Toast.makeText(context, "已移动到画集: $folderName", Toast.LENGTH_SHORT).show()
                                                showMoveDialog = false
                                                isSelectMode = false
                                                selectedProjects.clear()
                                            }
                                            .padding(14.dp)
                                    ) {
                                        Text("🗂️ $folderName", color = colors.text, fontSize = 13.sp)
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(18.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = { showMoveDialog = false }) {
                            Text("取消", color = colors.subText)
                        }
                    }
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.bg)
    ) {
        AnimatedContent(
            targetState = selectedTab,
            transitionSpec = {
                fadeIn(tween(220, easing = FastOutSlowInEasing))
                    .togetherWith(fadeOut(tween(160)))
            },
            modifier = Modifier.weight(1f),
            label = "HomeTabTransition"
        ) { tabIndex ->
            if (tabIndex == 0) {
                Box(modifier = Modifier.fillMaxSize()) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        // Floating Morandi Header (matching PaintingPage style)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (currentFolder != null) {
                                // Inside Folder
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(20.dp))
                                        .background(colors.panel.copy(alpha = 0.85f))
                                        .border(1.dp, colors.border, RoundedCornerShape(20.dp))
                                        .clickable {
                                            vm.currentFolder = null
                                            vm.refreshProjects()
                                        }
                                        .padding(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = "返回",
                                        tint = colors.text,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = currentFolder.name,
                                        color = colors.text,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        text = "(${displayProjects.size})",
                                        color = colors.subText,
                                        fontSize = 12.sp
                                    )
                                }
                            } else if (isSelectMode) {
                                Text(
                                    text = "已选 ${selectedProjects.size} 项",
                                    color = colors.text,
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            } else {
                                Column {
                                    Text(
                                        text = "画廊",
                                        color = colors.text,
                                        fontSize = 22.sp,
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 0.5.sp
                                    )
                                    Text(
                                        text = "${displayProjects.size} 个项目",
                                        color = colors.subText,
                                        fontSize = 11.sp
                                    )
                                }
                            }

                            Spacer(Modifier.weight(1f))

                            AnimatedVisibility(
                                visible = isSearchActive,
                                enter = fadeIn(tween(180)) + expandHorizontally(expandFrom = Alignment.End),
                                exit = fadeOut(tween(150)) + shrinkHorizontally(shrinkTowards = Alignment.End)
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
                                            Icon(Icons.Default.Close, contentDescription = "Close", tint = colors.subText, modifier = Modifier.size(18.dp))
                                        }
                                    },
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedTextColor = colors.text,
                                        unfocusedTextColor = colors.text,
                                        focusedBorderColor = colors.accent,
                                        unfocusedBorderColor = colors.border,
                                        focusedContainerColor = colors.panelHi,
                                        unfocusedContainerColor = colors.panelHi,
                                        cursorColor = colors.accent
                                    ),
                                    modifier = Modifier
                                        .width(220.dp)
                                        .height(44.dp)
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
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(20.dp))
                                            .background(colors.panel.copy(alpha = 0.85f))
                                            .border(1.dp, colors.border, RoundedCornerShape(20.dp))
                                            .padding(4.dp),
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        // Search icon button
                                        Box(
                                            modifier = Modifier
                                                .size(34.dp)
                                                .clip(CircleShape)
                                                .clickable { isSearchActive = true },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(Icons.Default.Search, contentDescription = "Search", tint = colors.icon, modifier = Modifier.size(18.dp))
                                        }

                                        // More Menu icon button
                                        Box {
                                            Box(
                                                modifier = Modifier
                                                    .size(34.dp)
                                                    .clip(CircleShape)
                                                    .clickable { showMoreMenu = true },
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(Icons.Default.MoreVert, contentDescription = "More", tint = colors.icon, modifier = Modifier.size(18.dp))
                                            }

                                            DropdownMenu(
                                                expanded = showMoreMenu,
                                                onDismissRequest = { showMoreMenu = false },
                                                modifier = Modifier.background(colors.panel).border(1.dp, colors.border, RoundedCornerShape(10.dp))
                                            ) {
                                                DropdownMenuItem(
                                                    text = { Text("选择", color = colors.text) },
                                                    onClick = {
                                                        showMoreMenu = false
                                                        isSelectMode = true
                                                        selectedProjects.clear()
                                                    },
                                                    leadingIcon = {
                                                        Icon(Icons.Default.CheckCircleOutline, contentDescription = null, tint = colors.icon, modifier = Modifier.size(18.dp))
                                                    }
                                                )
                                                DropdownMenuItem(
                                                    text = { Text("新建画集", color = colors.text) },
                                                    onClick = {
                                                        showMoreMenu = false
                                                        newFolderName = "画集_${System.currentTimeMillis() % 1000}"
                                                        showNewFolderDialog = true
                                                    },
                                                    leadingIcon = {
                                                        Icon(Icons.Default.CreateNewFolder, contentDescription = null, tint = colors.icon, modifier = Modifier.size(18.dp))
                                                    }
                                                )
                                                DropdownMenuItem(
                                                    text = { Text("刷新作品", color = colors.text) },
                                                    onClick = {
                                                        showMoreMenu = false
                                                        vm.refreshProjects()
                                                    },
                                                    leadingIcon = {
                                                        Icon(Icons.Default.Refresh, contentDescription = null, tint = colors.icon, modifier = Modifier.size(18.dp))
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // Animated Folder vs Root Gallery Transition
                        AnimatedContent(
                            targetState = currentFolder,
                            transitionSpec = {
                                if (targetState != null) {
                                    // Entering folder
                                    (fadeIn(tween(220)) + scaleIn(initialScale = 0.92f, animationSpec = tween(220)))
                                        .togetherWith(fadeOut(tween(160)) + scaleOut(targetScale = 1.05f, animationSpec = tween(160)))
                                } else {
                                    // Exiting folder
                                    (fadeIn(tween(220)) + scaleIn(initialScale = 1.05f, animationSpec = tween(220)))
                                        .togetherWith(fadeOut(tween(160)) + scaleOut(targetScale = 0.92f, animationSpec = tween(160)))
                                }
                            },
                            modifier = Modifier.weight(1f),
                            label = "FolderTransition"
                        ) { targetFolder ->
                            if (displayProjects.isEmpty()) {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        modifier = Modifier.padding(horizontal = 32.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(80.dp)
                                                .clip(CircleShape)
                                                .background(colors.panel.copy(alpha = 0.8f))
                                                .border(1.dp, colors.border, CircleShape),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                painterResource(R.drawable.ic_canvas_tab),
                                                contentDescription = null,
                                                tint = colors.accent.copy(alpha = 0.7f),
                                                modifier = Modifier.size(36.dp)
                                            )
                                        }
                                        Spacer(Modifier.height(16.dp))
                                        Text(
                                            if (vm.searchQuery.isNotEmpty()) "未找到相关作品" else if (targetFolder != null) "画集中暂无作品" else "开启你的第一幅画作",
                                            color = colors.text,
                                            fontSize = 16.sp,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Spacer(Modifier.height(6.dp))
                                        Text(
                                            if (vm.searchQuery.isNotEmpty()) "请尝试使用其他关键词搜索" else if (targetFolder != null) "你可以长按外部作品并选择「移动到画集」" else "点击下方「＋」按钮创建新画布或导入图像",
                                            color = colors.subText,
                                            fontSize = 12.sp,
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                }
                            } else {
                                LazyVerticalGrid(
                                    columns = GridCells.Fixed(2),
                                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(16.dp),
                                    contentPadding = PaddingValues(top = 8.dp, bottom = 100.dp)
                                ) {
                                    items(displayProjects, key = { it.filePath }) { p ->
                                        val isSelected = selectedProjects.contains(p)

                                        val interactionSource = remember { MutableInteractionSource() }
                                        val isPressed by interactionSource.collectIsPressedAsState()
                                        val scale by animateFloatAsState(
                                            targetValue = if (isPressed) 0.94f else 1.0f,
                                            animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow),
                                            label = "CardScaleAnim"
                                        )

                                        Box {
                                            Column(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .scale(scale)
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
                                                        }
                                                    )
                                            ) {
                                                val thumb = remember(p.previewPath, p.lastModified) {
                                                    if (p.previewPath.isNotEmpty() && File(p.previewPath).exists()) {
                                                        BitmapFactory.decodeFile(p.previewPath)
                                                    } else null
                                                }

                                                if (p.isFolder) {
                                                    // Procreate-style layered fan stack visual
                                                    val subThumbs = remember(p.items) {
                                                        p.items.take(3).mapNotNull { item ->
                                                            if (item.previewPath.isNotEmpty() && File(item.previewPath).exists()) {
                                                                BitmapFactory.decodeFile(item.previewPath)
                                                            } else null
                                                        }
                                                    }

                                                    Box(
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .aspectRatio(1f),
                                                        contentAlignment = Alignment.Center
                                                    ) {
                                                        // Stacked layer 1 (bottom left tilt)
                                                        Box(
                                                            modifier = Modifier
                                                                .fillMaxSize(0.85f)
                                                                .rotate(-5f)
                                                                .background(Color(0xFFE0E0E0))
                                                                .border(0.5.dp, Color.Black.copy(alpha = 0.15f)),
                                                            contentAlignment = Alignment.Center
                                                        ) {
                                                            if (subThumbs.size > 2) {
                                                                Image(bitmap = subThumbs[2].asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                                            }
                                                        }
                                                        // Stacked layer 2 (middle right tilt)
                                                        Box(
                                                            modifier = Modifier
                                                                .fillMaxSize(0.88f)
                                                                .rotate(4f)
                                                                .background(Color(0xFFEDEDED))
                                                                .border(0.5.dp, Color.Black.copy(alpha = 0.2f)),
                                                            contentAlignment = Alignment.Center
                                                        ) {
                                                            if (subThumbs.size > 1) {
                                                                Image(bitmap = subThumbs[1].asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                                            }
                                                        }
                                                        // Foreground main folder cover
                                                        Box(
                                                            modifier = Modifier
                                                                .fillMaxSize(0.91f)
                                                                .background(Color.White)
                                                                .border(0.5.dp, Color.Black.copy(alpha = 0.25f))
                                                                .then(
                                                                    if (isSelectMode && isSelected) Modifier.border(3.dp, colors.accent) else Modifier
                                                                ),
                                                            contentAlignment = Alignment.Center
                                                        ) {
                                                            if (thumb != null) {
                                                                Image(
                                                                    bitmap = thumb.asImageBitmap(),
                                                                    contentDescription = null,
                                                                    contentScale = ContentScale.Crop,
                                                                    modifier = Modifier.fillMaxSize()
                                                                )
                                                            } else {
                                                                Column(
                                                                    horizontalAlignment = Alignment.CenterHorizontally,
                                                                    verticalArrangement = Arrangement.Center
                                                                ) {
                                                                    Icon(
                                                                        Icons.Default.FolderCopy,
                                                                        contentDescription = null,
                                                                        tint = Color.DarkGray,
                                                                        modifier = Modifier.size(36.dp)
                                                                    )
                                                                    Spacer(Modifier.height(4.dp))
                                                                    Text(
                                                                        "画集",
                                                                        color = Color.Gray,
                                                                        fontSize = 11.sp,
                                                                        fontWeight = FontWeight.Medium
                                                                    )
                                                                }
                                                            }
                                                        }

                                                        // Badge: Folder count
                                                        Box(
                                                            modifier = Modifier
                                                                .align(Alignment.TopEnd)
                                                                .padding(4.dp)
                                                                .background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(4.dp))
                                                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                                        ) {
                                                            Text(
                                                                text = "${p.items.size}",
                                                                color = Color.White,
                                                                fontSize = 10.sp,
                                                                fontWeight = FontWeight.Bold
                                                            )
                                                        }

                                                        // Selection checkmark
                                                        if (isSelectMode) {
                                                            Box(
                                                                modifier = Modifier
                                                                    .align(Alignment.BottomEnd)
                                                                    .padding(6.dp)
                                                                    .size(22.dp)
                                                                    .clip(CircleShape)
                                                                    .background(if (isSelected) colors.accent else Color.Black.copy(alpha = 0.45f)),
                                                                contentAlignment = Alignment.Center
                                                            ) {
                                                                if (isSelected) {
                                                                    Icon(Icons.Default.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                                                                }
                                                            }
                                                        }
                                                    }
                                                } else {
                                                // Single artwork card (Pure Canvas Aesthetic without heavy bezel or format badge)
                                                Box(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .aspectRatio(1f)
                                                        .background(Color.White)
                                                        .then(
                                                            if (isSelectMode && isSelected) {
                                                                Modifier.border(3.dp, colors.accent)
                                                            } else Modifier
                                                        ),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    if (thumb != null) {
                                                        Image(
                                                            bitmap = thumb.asImageBitmap(),
                                                            contentDescription = null,
                                                            contentScale = ContentScale.Fit,
                                                            modifier = Modifier.fillMaxSize()
                                                        )
                                                    } else {
                                                        Box(
                                                            modifier = Modifier.fillMaxSize(),
                                                            contentAlignment = Alignment.Center
                                                        ) {
                                                            Icon(
                                                                painterResource(R.drawable.ic_canvas_tab),
                                                                contentDescription = null,
                                                                tint = Color(0xFFB0B0B0),
                                                                modifier = Modifier.size(36.dp)
                                                            )
                                                        }
                                                    }

                                                    // Selection checkmark
                                                    if (isSelectMode) {
                                                        Box(
                                                            modifier = Modifier
                                                                .align(Alignment.BottomEnd)
                                                                .padding(6.dp)
                                                                .size(22.dp)
                                                                .clip(CircleShape)
                                                                .background(if (isSelected) colors.accent else Color.Black.copy(alpha = 0.45f)),
                                                            contentAlignment = Alignment.Center
                                                        ) {
                                                            if (isSelected) {
                                                                Icon(Icons.Default.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
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
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Spacer(Modifier.height(2.dp))
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                val dateStr = remember(p.lastModified) {
                                                    if (p.lastModified > 0) {
                                                        SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(p.lastModified))
                                                    } else "刚刚"
                                                }
                                                Text(
                                                    text = if (p.isFolder) "${p.items.size} 个作品" else dateStr,
                                                    color = colors.subText,
                                                    fontSize = 11.sp
                                                )
                                                if (!p.isFolder && p.strokeCount > 0) {
                                                    Text(
                                                        text = "${p.strokeCount} 笔",
                                                        color = colors.subText,
                                                        fontSize = 11.sp
                                                    )
                                                }
                                            }
                                        }

                                        // Long-press Context Dropdown Menu
                                        DropdownMenu(
                                            expanded = longPressedProject == p,
                                            onDismissRequest = { longPressedProject = null },
                                            modifier = Modifier.background(colors.panel).border(1.dp, colors.border, RoundedCornerShape(10.dp))
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
                                                    Icon(Icons.AutoMirrored.Filled.Launch, contentDescription = null, tint = colors.icon, modifier = Modifier.size(18.dp))
                                                }
                                            )
                                            if (!p.isFolder) {
                                                DropdownMenuItem(
                                                    text = { Text("移动到画集...", color = colors.text) },
                                                    onClick = {
                                                        longPressedProject = null
                                                        targetMoveProjects = listOf(p)
                                                        showMoveDialog = true
                                                    },
                                                    leadingIcon = {
                                                        Icon(Icons.AutoMirrored.Filled.DriveFileMove, contentDescription = null, tint = colors.icon, modifier = Modifier.size(18.dp))
                                                    }
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
                                                    Icon(Icons.Default.Edit, contentDescription = null, tint = colors.icon, modifier = Modifier.size(18.dp))
                                                }
                                            )
                                            DropdownMenuItem(
                                                text = { Text(if (p.isFolder) "删除画集" else "删除作品", color = Color(0xFFFF5252)) },
                                                onClick = {
                                                    longPressedProject = null
                                                    vm.deleteProject(p)
                                                },
                                                leadingIcon = {
                                                    Icon(Icons.Default.Delete, contentDescription = null, tint = Color(0xFFFF5252), modifier = Modifier.size(18.dp))
                                                }
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
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 20.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(24.dp))
                                .background(colors.panelHi)
                                .border(1.dp, colors.border, RoundedCornerShape(24.dp))
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(onClick = {
                                targetMoveProjects = selectedProjects.toList()
                                showMoveDialog = true
                            }) {
                                Icon(Icons.AutoMirrored.Filled.DriveFileMove, contentDescription = null, tint = colors.accent, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("移动 (${selectedProjects.size})", color = colors.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            }
                            Box(modifier = Modifier.width(1.dp).height(18.dp).background(colors.border))
                            TextButton(onClick = {
                                selectedProjects.forEach { vm.deleteProject(it) }
                                selectedProjects.clear()
                                isSelectMode = false
                            }) {
                                Icon(Icons.Default.Delete, contentDescription = null, tint = Color(0xFFFF5252), modifier = Modifier.size(18.dp))
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

        // Floating Morandi Bottom Navigation Bar with Spring Animations
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp, vertical = 14.dp),
            contentAlignment = Alignment.Center
        ) {
            val isGallery = selectedTab == 0
            val isSettings = selectedTab == 1

            val createSource = remember { MutableInteractionSource() }
            val isCreatePressed by createSource.collectIsPressedAsState()
            val createScale by animateFloatAsState(
                targetValue = if (isCreatePressed) 0.88f else 1.0f,
                animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow),
                label = "CreateBtnScale"
            )

            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(32.dp))
                    .background(colors.panel.copy(alpha = 0.95f))
                    .border(1.dp, colors.border, RoundedCornerShape(32.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Gallery Tab Button with Animated Pill Container
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(22.dp))
                        .background(if (isGallery) colors.panelHi else Color.Transparent)
                        .clickable { vm.homeSelectedTab = 0 }
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    Icon(
                        Icons.Rounded.Brush,
                        contentDescription = "画廊",
                        tint = if (isGallery) colors.accent else colors.subText,
                        modifier = Modifier.size(20.dp)
                    )
                    AnimatedVisibility(
                        visible = isGallery,
                        enter = fadeIn(tween(200)) + expandHorizontally(expandFrom = Alignment.Start),
                        exit = fadeOut(tween(150)) + shrinkHorizontally(shrinkTowards = Alignment.Start)
                    ) {
                        Row {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "画廊",
                                color = colors.text,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                // Create Action Button (Pulsing / Press-responsive Accent Circle)
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .scale(createScale)
                        .clip(CircleShape)
                        .background(colors.accent)
                        .clickable(interactionSource = createSource, indication = null) { vm.goCreate() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Add, contentDescription = "新建", tint = colors.onAccent, modifier = Modifier.size(28.dp))
                }

                // Settings Tab Button with Animated Pill Container
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(22.dp))
                        .background(if (isSettings) colors.panelHi else Color.Transparent)
                        .clickable { vm.homeSelectedTab = 1 }
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    Icon(
                        Icons.Rounded.Settings,
                        contentDescription = "设置",
                        tint = if (isSettings) colors.accent else colors.subText,
                        modifier = Modifier.size(20.dp)
                    )
                    AnimatedVisibility(
                        visible = isSettings,
                        enter = fadeIn(tween(200)) + expandHorizontally(expandFrom = Alignment.Start),
                        exit = fadeOut(tween(150)) + shrinkHorizontally(shrinkTowards = Alignment.Start)
                    ) {
                        Row {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "设置",
                                color = colors.text,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}




