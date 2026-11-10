/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.create

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileOutputStream
import java.util.zip.ZipFile
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import com.reverie.paint.R
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.core.PsdMetadataReader
import com.reverie.paint.core.TemplateManager
import com.reverie.paint.core.loadProject
import com.reverie.paint.model.CanvasTemplate
import com.reverie.paint.ui.components.ReTextButton
import com.reverie.paint.ui.theme.Morandi
import kotlinx.coroutines.launch
import java.io.File

/**
 * 模板工程浏览与管理主视图 (支持左侧标签分类、右侧网格浏览、批量管理、卡片快捷操作与外部导入)。
 */
@Composable
fun TemplateStudioSection(
    vm: PaintViewModel,
    modifier: Modifier = Modifier,
    isWide: Boolean = true,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var categories by remember { mutableStateOf(listOf("漫画", "插画", "纸纹", TemplateManager.CATEGORY_UNCATEGORIZED)) }
    var templates by remember { mutableStateOf(emptyList<CanvasTemplate>()) }
    var selectedCategory by remember { mutableStateOf(TemplateManager.CATEGORY_ALL) }
    var searchQuery by remember { mutableStateOf("") }

    var isBatchMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(setOf<String>()) }

    // 对话框状态
    var showNewCategoryDialog by remember { mutableStateOf(false) }
    var categoryToRename by remember { mutableStateOf<String?>(null) }
    var categoryToDelete by remember { mutableStateOf<String?>(null) }

    var templateToRename by remember { mutableStateOf<CanvasTemplate?>(null) }
    var templateToMove by remember { mutableStateOf<CanvasTemplate?>(null) }
    var templateToDelete by remember { mutableStateOf<CanvasTemplate?>(null) }

    var showBatchMoveDialog by remember { mutableStateOf(false) }
    var showBatchDeleteConfirm by remember { mutableStateOf(false) }

    // 重新从持久化元数据刷新
    fun refreshData() {
        val (cats, tmpls) = TemplateManager.loadTemplateData(context)
        categories = cats
        templates = tmpls
    }

    LaunchedEffect(Unit) {
        refreshData()
    }

    // 外部模板导入文件选择器 (.revp, .kra, .psd)
    val templateImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) {
            coroutineScope.launch {
                val targetCat = if (selectedCategory == TemplateManager.CATEGORY_ALL) TemplateManager.CATEGORY_UNCATEGORIZED else selectedCategory
                val imported = TemplateManager.importExternalTemplate(context, uri, targetCat, vm = vm)
                if (imported != null) {
                    Toast.makeText(context, context.resources.getString(R.string.template_import_success, imported.name), Toast.LENGTH_SHORT).show()
                    refreshData()
                } else {
                    Toast.makeText(context, context.resources.getString(R.string.template_import_failed), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // 过滤后的模板列表
    val filteredTemplates = remember(templates, selectedCategory, searchQuery) {
        templates.filter { t ->
            val matchCat = (selectedCategory == TemplateManager.CATEGORY_ALL) || (t.category == selectedCategory)
            val matchQuery = searchQuery.isBlank() || t.name.contains(searchQuery.trim(), ignoreCase = true)
            matchCat && matchQuery
        }
    }

    Row(modifier = modifier.fillMaxSize()) {
        // 1. 左侧分类标签栏 (宽屏固定宽度，窄屏略微收窄)
        val sidebarWidth = if (isWide) 180.dp else 130.dp
        Column(
            modifier = Modifier
                .width(sidebarWidth)
                .fillMaxHeight()
                .background(Morandi.panelHi)
                .padding(vertical = 12.dp, horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // "全部" 标签
            val isAllSelected = selectedCategory == TemplateManager.CATEGORY_ALL
            val totalCount = templates.size
            CategoryTagItem(
                name = stringResource(R.string.template_category_all),
                count = totalCount,
                isSelected = isAllSelected,
                onClick = { selectedCategory = TemplateManager.CATEGORY_ALL },
                onRename = null,
                onDelete = null,
            )

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 4.dp),
                color = Morandi.border.copy(alpha = 0.5f),
                thickness = 0.8.dp,
            )

            // 用户分类列表
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(categories, key = { it }) { cat ->
                    val isSelected = selectedCategory == cat
                    val count = templates.count { it.category == cat }
                    val canEdit = cat != TemplateManager.CATEGORY_UNCATEGORIZED
                    CategoryTagItem(
                        name = cat,
                        count = count,
                        isSelected = isSelected,
                        onClick = { selectedCategory = cat },
                        onRename = if (canEdit) { { categoryToRename = cat } } else null,
                        onDelete = if (canEdit) { { categoryToDelete = cat } } else null,
                    )
                }
            }

            // 底部「新建标签」按钮
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Morandi.panel)
                    .clickable { showNewCategoryDialog = true }
                    .padding(vertical = 8.dp, horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_plus),
                    contentDescription = null,
                    tint = Morandi.accent,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.template_category_new),
                    color = Morandi.accent,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }

        VerticalDivider(color = Morandi.border, thickness = 0.8.dp)

        // 2. 右侧主体内容区域 (搜索栏/工具栏 + 模板卡片网格 + 批量操作底栏)
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 顶部工具条
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // 搜索框
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .height(38.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Morandi.panelHi)
                        .border(0.8.dp, Morandi.border, RoundedCornerShape(10.dp))
                        .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_search),
                        contentDescription = null,
                        tint = Morandi.subText,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    BasicTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        textStyle = LocalTextStyle.current.copy(
                            color = Morandi.text,
                            fontSize = 13.sp,
                        ),
                        cursorBrush = SolidColor(Morandi.accent),
                        decorationBox = { innerTextField ->
                            if (searchQuery.isEmpty()) {
                                Text(
                                    text = stringResource(R.string.template_search_hint),
                                    color = Morandi.subText.copy(alpha = 0.6f),
                                    fontSize = 13.sp,
                                )
                            }
                            innerTextField()
                        },
                    )
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

                // 「导入外部模板」按钮
                Row(
                    modifier = Modifier
                        .height(38.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Morandi.accent.copy(alpha = 0.16f))
                        .clickable {
                            templateImportLauncher.launch(
                                arrayOf(
                                    "application/octet-stream",
                                    "application/x-krita",
                                    "image/vnd.adobe.photoshop",
                                    "*/*"
                                )
                            )
                        }
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_import),
                        contentDescription = null,
                        tint = Morandi.accent,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = stringResource(R.string.template_import),
                        color = Morandi.accent,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }

                // 「批量管理」开关按钮
                Box(
                    modifier = Modifier
                        .height(38.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (isBatchMode) Morandi.accent else Morandi.panelHi)
                        .clickable {
                            isBatchMode = !isBatchMode
                            if (!isBatchMode) selectedIds = emptySet()
                        }
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(if (isBatchMode) R.string.template_batch_done else R.string.template_batch_mode),
                        color = if (isBatchMode) Color.White else Morandi.text,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }

            // 模板网格或空状态
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                if (filteredTemplates.isEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_layers),
                            contentDescription = null,
                            tint = Morandi.subText.copy(alpha = 0.4f),
                            modifier = Modifier.size(54.dp),
                        )
                        Spacer(Modifier.height(14.dp))
                        Text(
                            text = stringResource(R.string.template_empty_title),
                            color = Morandi.text,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = stringResource(R.string.template_empty_desc),
                            color = Morandi.subText,
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.widthIn(max = 380.dp),
                        )
                    }
                } else {
                    val gridCols = if (isWide) 3 else 2
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(gridCols),
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(bottom = if (isBatchMode) 68.dp else 12.dp),
                    ) {
                        items(filteredTemplates, key = { it.id }) { template ->
                            val isSelected = selectedIds.contains(template.id)
                            TemplateCardItem(
                                template = template,
                                isBatchMode = isBatchMode,
                                isSelected = isSelected,
                                onClick = {
                                    if (isBatchMode) {
                                        selectedIds = if (isSelected) selectedIds - template.id else selectedIds + template.id
                                    } else {
                                        // 一键基于此模板创建全新工程并进入绘画页
                                        coroutineScope.launch {
                                            val newProject = TemplateManager.createProjectFromTemplate(context, vm, template)
                                            if (newProject != null) {
                                                vm.loadProject(newProject)
                                            } else {
                                                Toast.makeText(context, context.resources.getString(R.string.template_save_failed), Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    }
                                },
                                onRename = { templateToRename = template },
                                onMoveCategory = { templateToMove = template },
                                onExport = {
                                    val f = File(template.filePath)
                                    if (f.exists()) {
                                        shareTemplateFile(context, f)
                                    }
                                },
                                onDelete = { templateToDelete = template },
                            )
                        }
                    }
                }

                // 批量编辑浮动底栏
                if (isBatchMode) {
                    Box(modifier = Modifier.align(Alignment.BottomCenter)) {
                        BatchActionsBar(
                            selectedCount = selectedIds.size,
                            totalCount = filteredTemplates.size,
                            onSelectAll = {
                                selectedIds = if (selectedIds.size == filteredTemplates.size) emptySet() else filteredTemplates.map { it.id }.toSet()
                            },
                            onMoveCategory = { showBatchMoveDialog = true },
                            onShare = {
                                val files = filteredTemplates.filter { selectedIds.contains(it.id) }.map { File(it.filePath) }
                                shareTemplateFilesBatch(context, files)
                            },
                            onDelete = { showBatchDeleteConfirm = true },
                        )
                    }
                }
            }
        }
    }

    // --- 对话框组件们 ---

    // 1. 新建分类标签对话框
    if (showNewCategoryDialog) {
        InputTextDialog(
            title = stringResource(R.string.template_category_new_title),
            hint = stringResource(R.string.template_category_input_hint),
            onDismiss = { showNewCategoryDialog = false },
            onConfirm = { name ->
                TemplateManager.addCategory(context, name)
                refreshData()
                showNewCategoryDialog = false
            },
        )
    }

    // 2. 重命名分类标签对话框
    categoryToRename?.let { cat ->
        InputTextDialog(
            title = stringResource(R.string.template_category_rename_title),
            hint = cat,
            initialText = cat,
            onDismiss = { categoryToRename = null },
            onConfirm = { newName ->
                TemplateManager.renameCategory(context, cat, newName)
                if (selectedCategory == cat) selectedCategory = newName
                refreshData()
                categoryToRename = null
            },
        )
    }

    // 3. 删除分类标签确认对话框
    categoryToDelete?.let { cat ->
        TemplateConfirmDialog(
            title = stringResource(R.string.template_category_delete_title),
            message = stringResource(R.string.template_category_delete_message),
            confirmText = stringResource(R.string.common_delete),
            onDismiss = { categoryToDelete = null },
            onConfirm = {
                TemplateManager.deleteCategory(context, cat)
                if (selectedCategory == cat) selectedCategory = TemplateManager.CATEGORY_ALL
                refreshData()
                categoryToDelete = null
            },
        )
    }

    // 4. 重命名模板对话框
    templateToRename?.let { t ->
        InputTextDialog(
            title = stringResource(R.string.template_action_rename),
            hint = t.name,
            initialText = t.name,
            onDismiss = { templateToRename = null },
            onConfirm = { newName ->
                TemplateManager.renameTemplate(context, t.id, newName)
                refreshData()
                templateToRename = null
            },
        )
    }

    // 5. 修改模板分类标签对话框
    templateToMove?.let { t ->
        CategoryPickerRadioDialog(
            title = stringResource(R.string.template_action_move),
            categories = categories,
            currentCategory = t.category,
            onDismiss = { templateToMove = null },
            onSelect = { newCat ->
                TemplateManager.moveTemplateCategory(context, t.id, newCat)
                refreshData()
                templateToMove = null
            },
        )
    }

    // 6. 删除模板确认对话框
    templateToDelete?.let { t ->
        TemplateConfirmDialog(
            title = stringResource(R.string.template_delete_title),
            message = stringResource(R.string.template_delete_confirm, t.name),
            confirmText = stringResource(R.string.common_delete),
            onDismiss = { templateToDelete = null },
            onConfirm = {
                TemplateManager.deleteTemplate(context, t.id)
                refreshData()
                templateToDelete = null
            },
        )
    }

    // 7. 批量移动分类对话框
    if (showBatchMoveDialog) {
        CategoryPickerRadioDialog(
            title = stringResource(R.string.template_batch_move_category),
            categories = categories,
            currentCategory = "",
            onDismiss = { showBatchMoveDialog = false },
            onSelect = { newCat ->
                TemplateManager.batchMoveTemplates(context, selectedIds, newCat)
                refreshData()
                showBatchMoveDialog = false
                selectedIds = emptySet()
                isBatchMode = false
            },
        )
    }

    // 8. 批量删除确认对话框
    if (showBatchDeleteConfirm) {
        TemplateConfirmDialog(
            title = stringResource(R.string.template_batch_delete),
            message = stringResource(R.string.template_batch_delete_confirm, selectedIds.size),
            confirmText = stringResource(R.string.common_delete),
            onDismiss = { showBatchDeleteConfirm = false },
            onConfirm = {
                TemplateManager.batchDeleteTemplates(context, selectedIds)
                refreshData()
                showBatchDeleteConfirm = false
                selectedIds = emptySet()
                isBatchMode = false
            },
        )
    }
}

private fun shareTemplateFile(context: Context, file: File) {
    try {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "*/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "分享模板"))
    } catch (e: Exception) {
        Toast.makeText(context, "分享失败: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}

private fun shareTemplateFilesBatch(context: Context, files: List<File>) {
    try {
        val uris = ArrayList<Uri>()
        for (f in files) {
            uris.add(
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    f
                )
            )
        }
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "批量分享 (${uris.size} 个模板)"))
    } catch (e: Exception) {
        Toast.makeText(context, "批量分享失败: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}

/** 单个分类标签项 (支持点击选中、重命名与删除) */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CategoryTagItem(
    name: String,
    count: Int,
    isSelected: Boolean,
    onClick: () -> Unit,
    onRename: (() -> Unit)?,
    onDelete: (() -> Unit)?,
) {
    var showMenu by remember { mutableStateOf(false) }

    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(if (isSelected) Morandi.accent.copy(alpha = 0.18f) else Color.Transparent)
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = {
                        if (onRename != null || onDelete != null) showMenu = true
                    },
                )
                .padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = name,
                color = if (isSelected) Morandi.accent else Morandi.text,
                fontSize = 13.sp,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Text(
                text = count.toString(),
                color = if (isSelected) Morandi.accent.copy(alpha = 0.8f) else Morandi.subText,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
            )
        }

        // 长按呼出的下拉操作菜单
        if (showMenu) {
            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false },
                modifier = Modifier.background(Morandi.panel),
            ) {
                if (onRename != null) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.template_action_rename), color = Morandi.text, fontSize = 13.sp) },
                        onClick = {
                            showMenu = false
                            onRename()
                        },
                        leadingIcon = {
                            Icon(painter = painterResource(R.drawable.ic_rename), contentDescription = null, tint = Morandi.subText, modifier = Modifier.size(16.dp))
                        },
                    )
                }
                if (onDelete != null) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.common_delete), color = Color(0xFFE57373), fontSize = 13.sp) },
                        onClick = {
                            showMenu = false
                            onDelete()
                        },
                        leadingIcon = {
                            Icon(painter = painterResource(R.drawable.ic_trash), contentDescription = null, tint = Color(0xFFE57373), modifier = Modifier.size(16.dp))
                        },
                    )
                }
            }
        }
    }
}

private object TemplateThumbCache {
    private val lruCache = object : android.util.LruCache<String, Bitmap>(32) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    fun peek(key: String): Bitmap? = lruCache.get(key)?.takeIf { !it.isRecycled }

    fun put(key: String, bitmap: Bitmap) {
        lruCache.put(key, bitmap)
    }
}

@Composable
private fun rememberTemplateThumbnail(template: CanvasTemplate): Bitmap? {
    val file = File(template.filePath)
    val key = "${template.id}:${template.previewPath}:${if (file.exists()) file.lastModified() else 0L}"
    val cached = remember(key) { TemplateThumbCache.peek(key) }
    if (cached != null) return cached

    var bmp by remember(key) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(key) {
        val loaded = withContext(Dispatchers.IO) {
            var path = template.previewPath
            if (path.isEmpty() || !File(path).exists()) {
                if (file.exists()) {
                    val cand = File(file.parentFile, "${file.nameWithoutExtension}_thumb.png")
                    if (cand.exists() && cand.length() > 0) {
                        path = cand.absolutePath
                    } else if (file.extension.lowercase() == "revp" || file.extension.lowercase() == "kra") {
                        try {
                            ZipFile(file).use { zf ->
                                val entry = zf.getEntry("thumbnail.png")
                                    ?: zf.getEntry("preview.png")
                                    ?: zf.getEntry("mergedimage.png")
                                if (entry != null) {
                                    zf.getInputStream(entry).use { input ->
                                        FileOutputStream(cand).use { output -> input.copyTo(output) }
                                    }
                                    if (cand.exists() && cand.length() > 0) {
                                        path = cand.absolutePath
                                    }
                                }
                            }
                        } catch (_: Exception) {}
                    } else if (file.extension.lowercase() == "psd") {
                        if (PsdMetadataReader.extractThumbnail(file, cand)) {
                            path = cand.absolutePath
                        }
                    }
                }
            }
            if (path.isNotEmpty() && File(path).exists()) {
                try {
                    val b = BitmapFactory.decodeFile(path)
                    if (b != null) TemplateThumbCache.put(key, b)
                    b
                } catch (_: Exception) {
                    null
                }
            } else {
                null
            }
        }
        bmp = loaded
    }
    return bmp
}

/** 单个模板卡片 */
@Composable
private fun TemplateCardItem(
    template: CanvasTemplate,
    isBatchMode: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onMoveCategory: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }
    val thumbBitmap = rememberTemplateThumbnail(template)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Morandi.panelHi)
            .border(
                width = if (isSelected) 1.5.dp else 0.8.dp,
                color = if (isSelected) Morandi.accent else Morandi.border,
                shape = RoundedCornerShape(12.dp)
            )
            .clickable(onClick = onClick)
            .padding(10.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 缩略图区域
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1.35f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF141518)),
                contentAlignment = Alignment.Center,
            ) {
                if (thumbBitmap != null) {
                    androidx.compose.foundation.Image(
                        bitmap = thumbBitmap.asImageBitmap(),
                        contentDescription = template.name,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Icon(
                        painter = painterResource(R.drawable.ic_layers),
                        contentDescription = null,
                        tint = Morandi.subText.copy(alpha = 0.35f),
                        modifier = Modifier.size(36.dp),
                    )
                }

                // 批量选择勾选框
                if (isBatchMode) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(if (isSelected) Morandi.accent else Color.Black.copy(alpha = 0.5f))
                            .border(1.dp, Color.White, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (isSelected) {
                            Icon(
                                painter = painterResource(R.drawable.ic_check),
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                }
            }

            // 标题与分类标签
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = template.name,
                    color = Morandi.text,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(Morandi.panel)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = template.category,
                        color = Morandi.subText,
                        fontSize = 10.sp,
                    )
                }
            }

            // 规格参数说明与快捷菜单按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "${template.width}×${template.height} • ${template.layerCount}图层",
                    color = Morandi.subText,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )

                if (!isBatchMode) {
                    Box {
                        Icon(
                            painter = painterResource(R.drawable.ic_dots_vertical),
                            contentDescription = null,
                            tint = Morandi.subText,
                            modifier = Modifier
                                .size(20.dp)
                                .clip(CircleShape)
                                .clickable { showMenu = true }
                                .padding(2.dp),
                        )
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false },
                            modifier = Modifier.background(Morandi.panel),
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.template_action_create), color = Morandi.accent, fontSize = 13.sp, fontWeight = FontWeight.Medium) },
                                onClick = {
                                    showMenu = false
                                    onClick()
                                },
                                leadingIcon = {
                                    Icon(painter = painterResource(R.drawable.ic_plus), contentDescription = null, tint = Morandi.accent, modifier = Modifier.size(16.dp))
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.template_action_rename), color = Morandi.text, fontSize = 13.sp) },
                                onClick = {
                                    showMenu = false
                                    onRename()
                                },
                                leadingIcon = {
                                    Icon(painter = painterResource(R.drawable.ic_rename), contentDescription = null, tint = Morandi.subText, modifier = Modifier.size(16.dp))
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.template_action_move), color = Morandi.text, fontSize = 13.sp) },
                                onClick = {
                                    showMenu = false
                                    onMoveCategory()
                                },
                                leadingIcon = {
                                    Icon(painter = painterResource(R.drawable.ic_folder), contentDescription = null, tint = Morandi.subText, modifier = Modifier.size(16.dp))
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.template_action_export), color = Morandi.text, fontSize = 13.sp) },
                                onClick = {
                                    showMenu = false
                                    onExport()
                                },
                                leadingIcon = {
                                    Icon(painter = painterResource(R.drawable.ic_share), contentDescription = null, tint = Morandi.subText, modifier = Modifier.size(16.dp))
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.template_action_delete), color = Color(0xFFE57373), fontSize = 13.sp) },
                                onClick = {
                                    showMenu = false
                                    onDelete()
                                },
                                leadingIcon = {
                                    Icon(painter = painterResource(R.drawable.ic_trash), contentDescription = null, tint = Color(0xFFE57373), modifier = Modifier.size(16.dp))
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 批量操作底栏 */
@Composable
private fun BatchActionsBar(
    selectedCount: Int,
    totalCount: Int,
    onSelectAll: () -> Unit,
    onMoveCategory: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Morandi.panelHi)
            .border(0.8.dp, Morandi.border, RoundedCornerShape(14.dp))
            .shadow(8.dp, RoundedCornerShape(14.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.template_batch_selected_count, selectedCount),
                color = Morandi.text,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(if (selectedCount == totalCount) R.string.template_batch_deselect_all else R.string.template_batch_select_all),
                color = Morandi.accent,
                fontSize = 13.sp,
                modifier = Modifier.clickable(onClick = onSelectAll),
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ReTextButton(
                text = stringResource(R.string.template_batch_move_category),
                enabled = selectedCount > 0,
                onClick = onMoveCategory,
            )
            ReTextButton(
                text = stringResource(R.string.template_batch_share),
                enabled = selectedCount > 0,
                onClick = onShare,
            )
            ReTextButton(
                text = stringResource(R.string.template_batch_delete),
                enabled = selectedCount > 0,
                textColor = Color(0xFFE57373),
                onClick = onDelete,
            )
        }
    }
}

/** 统一风格确认对话框 (样式对齐 ReveriePaint 全局规范) */
@Composable
private fun TemplateConfirmDialog(
    title: String,
    message: String,
    confirmText: String = stringResource(R.string.common_delete),
    isDestructive: Boolean = true,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .width(320.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    )
                    .clip(RoundedCornerShape(18.dp))
                    .background(Morandi.panel)
                    .border(0.8.dp, Morandi.border, RoundedCornerShape(18.dp))
                    .padding(22.dp),
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (isDestructive) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFFFF5252).copy(alpha = 0.12f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_alert_triangle),
                                    contentDescription = null,
                                    tint = Color(0xFFFF5252),
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                        }
                        Text(
                            text = title,
                            color = Morandi.text,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    Text(
                        text = message,
                        color = Morandi.subText,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                    )
                    Spacer(Modifier.height(22.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ReTextButton(
                            text = stringResource(R.string.common_cancel),
                            onClick = onDismiss,
                            textColor = Morandi.subText,
                            fontSize = 14.sp,
                        )
                        Spacer(Modifier.width(8.dp))
                        ReTextButton(
                            text = confirmText,
                            onClick = onConfirm,
                            primary = true,
                            containerColor = if (isDestructive) Color(0xFFFF5252) else Morandi.accent,
                            contentColor = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                        )
                    }
                }
            }
        }
    }
}

/** 通用文本输入对话框 */
@Composable
private fun InputTextDialog(
    title: String,
    hint: String,
    initialText: String = "",
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initialText) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .width(320.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    )
                    .clip(RoundedCornerShape(18.dp))
                    .background(Morandi.panel)
                    .border(0.8.dp, Morandi.border, RoundedCornerShape(18.dp))
                    .padding(22.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(
                        text = title,
                        color = Morandi.text,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                    )

                    BasicTextField(
                        value = text,
                        onValueChange = { text = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(42.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Morandi.panelHi)
                            .border(0.8.dp, Morandi.border, RoundedCornerShape(10.dp))
                            .padding(horizontal = 12.dp, vertical = 11.dp),
                        singleLine = true,
                        textStyle = LocalTextStyle.current.copy(color = Morandi.text, fontSize = 14.sp),
                        cursorBrush = SolidColor(Morandi.accent),
                        decorationBox = { inner ->
                            if (text.isEmpty()) {
                                Text(text = hint, color = Morandi.subText, fontSize = 14.sp)
                            }
                            inner()
                        },
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ReTextButton(
                            text = stringResource(R.string.common_cancel),
                            onClick = onDismiss,
                            textColor = Morandi.subText,
                            fontSize = 14.sp,
                        )
                        Spacer(Modifier.width(8.dp))
                        ReTextButton(
                            text = stringResource(R.string.common_confirm),
                            onClick = { onConfirm(text.trim()) },
                            enabled = text.isNotBlank(),
                            primary = true,
                            containerColor = Morandi.accent,
                            contentColor = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                        )
                    }
                }
            }
        }
    }
}

/** 分类单选切换对话框 */
@Composable
private fun CategoryPickerRadioDialog(
    title: String,
    categories: List<String>,
    currentCategory: String,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    var selected by remember { mutableStateOf(currentCategory.ifEmpty { categories.firstOrNull() ?: TemplateManager.CATEGORY_UNCATEGORIZED }) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .width(320.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    )
                    .clip(RoundedCornerShape(18.dp))
                    .background(Morandi.panel)
                    .border(0.8.dp, Morandi.border, RoundedCornerShape(18.dp))
                    .padding(22.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(
                        text = title,
                        color = Morandi.text,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                    )

                    LazyColumn(
                        modifier = Modifier.heightIn(max = 240.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(categories) { cat ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (selected == cat) Morandi.accent.copy(alpha = 0.16f) else Color.Transparent)
                                    .clickable { selected = cat }
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    text = cat,
                                    color = if (selected == cat) Morandi.accent else Morandi.text,
                                    fontSize = 13.sp,
                                    fontWeight = if (selected == cat) FontWeight.SemiBold else FontWeight.Normal,
                                )
                                if (selected == cat) {
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

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ReTextButton(
                            text = stringResource(R.string.common_cancel),
                            onClick = onDismiss,
                            textColor = Morandi.subText,
                            fontSize = 14.sp,
                        )
                        Spacer(Modifier.width(8.dp))
                        ReTextButton(
                            text = stringResource(R.string.common_confirm),
                            onClick = { onSelect(selected) },
                            primary = true,
                            containerColor = Morandi.accent,
                            contentColor = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                        )
                    }
                }
            }
        }
    }
}
