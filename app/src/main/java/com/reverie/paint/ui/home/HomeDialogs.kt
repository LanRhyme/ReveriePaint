/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

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
import androidx.compose.foundation.lazy.grid.itemsIndexed
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.activity.compose.BackHandler
import com.reverie.paint.ui.components.ReTextButton
import com.reverie.paint.R
import com.reverie.paint.core.*
import com.reverie.paint.model.Project
import com.reverie.paint.ui.theme.AppColors
import com.reverie.paint.ui.theme.Theme
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
@Composable
internal fun NewFolderDialog(
    colors: AppColors,
    folderName: String,
    onFolderNameChange: (String) -> Unit,
    onCreate: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .width(320.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(colors.panel)
                .padding(22.dp)
        ) {
            Column {
                Text(
                    text = stringResource(R.string.home_dialog_new_folder_title),
                    color = colors.text,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(14.dp))
                OutlinedTextField(
                    value = folderName,
                    onValueChange = onFolderNameChange,
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.home_dialog_folder_name_hint), color = colors.subText) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = colors.text,
                        unfocusedTextColor = colors.text,
                        focusedBorderColor = colors.accent,
                        unfocusedBorderColor = Color.Transparent,
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
                    ReTextButton(stringResource(R.string.common_cancel), onDismiss, textColor = colors.subText)
                    Spacer(Modifier.width(8.dp))
                    ReTextButton(
                        stringResource(R.string.common_create),
                        onClick = {
                        if (folderName.isNotBlank()) {
                            onCreate(folderName.trim())
                        }
                        onDismiss()
                        onFolderNameChange("")
                    },
                        textColor = colors.accent,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

@Composable
internal fun RenameProjectDialog(
    colors: AppColors,
    project: Project,
    name: String,
    onNameChange: (String) -> Unit,
    onRename: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .width(320.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(colors.panel)
                .padding(22.dp)
        ) {
            Column {
                Text(
                    text = if (project.isFolder) stringResource(R.string.home_dialog_rename_folder_title) else stringResource(R.string.home_dialog_rename_project_title),
                    color = colors.text,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(14.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = onNameChange,
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = colors.text,
                        unfocusedTextColor = colors.text,
                        focusedBorderColor = colors.accent,
                        unfocusedBorderColor = Color.Transparent,
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
                    ReTextButton(stringResource(R.string.common_cancel), onDismiss, textColor = colors.subText)
                    Spacer(Modifier.width(8.dp))
                    ReTextButton(
                        stringResource(R.string.common_confirm),
                        onClick = {
                        if (name.isNotBlank()) onRename(name.trim())
                        onDismiss()
                    },
                        textColor = colors.accent,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

@Composable
internal fun MoveProjectDialog(
    colors: AppColors,
    vm: PaintViewModel,
    currentFolder: com.reverie.paint.model.Project?,
    targetMoveProjects: List<Project>,
    onMoved: () -> Unit,
    onDismiss: () -> Unit,
) {
val context = LocalContext.current
    val rootDir = vm.projectDir()
    val allFolders = remember(vm.projects) {
        rootDir.listFiles { f: File -> f.isDirectory }?.map { it.name } ?: emptyList()
    }

    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .width(320.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(colors.panel)
                .padding(22.dp)
        ) {
            Column {
                Text(
                    text = stringResource(R.string.home_dialog_move_title),
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
                                .clickable {
                                    targetMoveProjects.forEach { p ->
                                        vm.moveProjectToFolder(p, null)
                                    }
                                    Toast.makeText(context, context.getString(R.string.home_toast_moved_to_root), Toast.LENGTH_SHORT).show()
                                    onMoved()
                                }
                                .padding(14.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(painterResource(R.drawable.ic_folder_symlink), contentDescription = null, tint = colors.accent, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.home_dialog_move_to_root), color = colors.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }

                    if (allFolders.isEmpty()) {
                        Text(stringResource(R.string.home_dialog_move_empty_folders), color = colors.subText, fontSize = 13.sp)
                    } else {
                        allFolders.forEach { folderName ->
                            if (currentFolder?.name != folderName) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(colors.panelHi)
                                        .clickable {
                                            targetMoveProjects.forEach { p ->
                                                vm.moveProjectToFolder(p, folderName)
                                            }
                                            Toast.makeText(context, context.getString(R.string.home_toast_moved_to_folder, folderName), Toast.LENGTH_SHORT).show()
                                            onMoved()
                                        }
                                        .padding(14.dp)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(painterResource(R.drawable.ic_folder), contentDescription = null, tint = colors.icon, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(8.dp))
                                        Text(folderName, color = colors.text, fontSize = 13.sp)
                                    }
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
                    ReTextButton(stringResource(R.string.common_cancel), onDismiss, textColor = colors.subText)
                }
            }
        }
    }
}

@Composable
internal fun DeleteProjectDialog(
    colors: AppColors,
    target: Project,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
val context = LocalContext.current
    // target passed as parameter
    val isFolder = target.isFolder
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss
                ),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .width(320.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {}
                    )
                    .clip(RoundedCornerShape(20.dp))
                    .background(colors.panel)
                    .padding(22.dp)
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFFF5252).copy(alpha = 0.12f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_alert_triangle),
                                contentDescription = null,
                                tint = Color(0xFFFF5252),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = if (isFolder) stringResource(R.string.home_dialog_delete_folder_title) else stringResource(R.string.home_dialog_delete_project_title),
                            color = colors.text,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    Text(
                        text = if (isFolder) {
                            stringResource(R.string.home_dialog_delete_folder_confirm, target.name)
                        } else {
                            stringResource(R.string.home_dialog_delete_project_confirm, target.name)
                        },
                        color = colors.subText,
                        fontSize = 13.sp,
                        lineHeight = 18.sp
                    )
                    Spacer(Modifier.height(22.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ReTextButton(stringResource(R.string.common_cancel), onDismiss, textColor = colors.subText, fontSize = 14.sp)
                        Spacer(Modifier.width(8.dp))
                        ReTextButton(
                            stringResource(R.string.home_dialog_confirm_delete),
                            {
                                onDismiss()
                                onDelete()
                            },
                            primary = true,
                            containerColor = Color(0xFFFF5252),
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

@Composable
internal fun BatchDeleteConfirmDialog(
    colors: AppColors,
    count: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
val context = LocalContext.current
    // count passed as parameter
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss
                ),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .width(320.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {}
                    )
                    .clip(RoundedCornerShape(20.dp))
                    .background(colors.panel)
                    .padding(22.dp)
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFFF5252).copy(alpha = 0.12f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_alert_triangle),
                                contentDescription = null,
                                tint = Color(0xFFFF5252),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = stringResource(R.string.home_dialog_batch_delete_title),
                            color = colors.text,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    Text(
                        text = stringResource(R.string.home_dialog_batch_delete_confirm, count),
                        color = colors.subText,
                        fontSize = 13.sp,
                        lineHeight = 18.sp
                    )
                    Spacer(Modifier.height(22.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ReTextButton(stringResource(R.string.common_cancel), onDismiss, textColor = colors.subText, fontSize = 14.sp)
                        Spacer(Modifier.width(8.dp))
                        ReTextButton(
                            stringResource(R.string.home_dialog_confirm_delete),
                            {
                                onDismiss()
                                onConfirm()
                            },
                            primary = true,
                            containerColor = Color(0xFFFF5252),
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

@Composable
internal fun ChooseFolderCoverDialog(
    colors: AppColors,
    folder: Project,
    onSelectCover: (Project) -> Unit,
    onResetCover: () -> Unit,
    onDismiss: () -> Unit,
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
                    .width(420.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    )
                    .clip(RoundedCornerShape(20.dp))
                    .background(colors.panel)
                    .padding(22.dp),
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(colors.accent.copy(alpha = 0.14f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_image),
                                contentDescription = null,
                                tint = colors.accent,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                text = stringResource(R.string.gallery_dialog_choose_cover_title),
                                color = colors.text,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                text = folder.name,
                                color = colors.subText,
                                fontSize = 12.sp,
                            )
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    if (folder.items.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(120.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = stringResource(R.string.gallery_empty_stack_desc),
                                color = colors.subText,
                                fontSize = 13.sp,
                            )
                        }
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(minSize = 90.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 280.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            items(folder.items, key = { it.filePath }) { item ->
                                val thumb = rememberThumbnail(item.previewPath, item.lastModified)
                                val isCover = item.isFolderCover
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(12.dp))
                                        .clickable {
                                            onSelectCover(item)
                                            onDismiss()
                                        }
                                        .padding(4.dp),
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .aspectRatio(1f)
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(colors.panelHi)
                                            .border(
                                                width = if (isCover) 2.5.dp else 1.dp,
                                                color = if (isCover) colors.accent else colors.border,
                                                shape = RoundedCornerShape(10.dp),
                                            ),
                                    ) {
                                        if (thumb != null) {
                                            Image(
                                                bitmap = thumb.asImageBitmap(),
                                                contentDescription = item.name,
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier.fillMaxSize(),
                                            )
                                        }
                                        if (isCover) {
                                            Box(
                                                modifier = Modifier
                                                    .align(Alignment.TopEnd)
                                                    .padding(4.dp)
                                                    .clip(RoundedCornerShape(4.dp))
                                                    .background(colors.accent)
                                                    .padding(horizontal = 4.dp, vertical = 2.dp),
                                            ) {
                                                Text(
                                                    text = stringResource(R.string.gallery_cover_badge),
                                                    color = Color.White,
                                                    fontSize = 9.sp,
                                                    fontWeight = FontWeight.Bold,
                                                )
                                            }
                                        }
                                    }
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        text = item.name,
                                        color = if (isCover) colors.accent else colors.text,
                                        fontSize = 11.sp,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                        fontWeight = if (isCover) FontWeight.Bold else FontWeight.Normal,
                                    )
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(18.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ReTextButton(
                            stringResource(R.string.gallery_action_clear_cover),
                            onClick = {
                                onResetCover()
                                onDismiss()
                            },
                            textColor = colors.subText,
                            fontSize = 13.sp,
                        )
                        ReTextButton(
                            stringResource(R.string.common_cancel),
                            onDismiss,
                            textColor = colors.accent,
                            fontWeight = FontWeight.Medium,
                            fontSize = 14.sp,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 工程详细信息面板 (长按画作/画集菜单呼出)
 */
@Composable
internal fun ProjectDetailsDialog(
    colors: AppColors,
    project: Project,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val toastCopied = stringResource(R.string.gallery_toast_path_copied)

    val thumb = if (project.isFolder) {
        val firstItem = project.items.firstOrNull()
        rememberThumbnail(firstItem?.previewPath, firstItem?.lastModified ?: 0L)
    } else {
        rememberThumbnail(project.previewPath, project.lastModified)
    }

    val typeLabel = when {
        project.isFolder -> stringResource(R.string.gallery_details_type_stack)
        project.isAnimation -> stringResource(R.string.gallery_details_type_animation)
        else -> stringResource(R.string.gallery_details_type_artwork)
    }

    val dateFormatted = remember(project.lastModified) {
        if (project.lastModified > 0L) {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(project.lastModified))
        } else {
            "—"
        }
    }

    val totalSize = remember(project) {
        if (project.isFolder) {
            project.items.sumOf { it.fileSize }
        } else {
            project.fileSize
        }
    }

    val formattedSize = remember(totalSize) {
        formatFileSize(totalSize)
    }

    val formattedDuration = remember(project.elapsedSeconds) {
        formatDuration(project.elapsedSeconds)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .padding(24.dp)
                .widthIn(max = 440.dp)
                .fillMaxWidth()
                .shadow(20.dp, RoundedCornerShape(24.dp), clip = false)
                .clip(RoundedCornerShape(24.dp))
                .background(colors.panel)
                .border(1.dp, colors.border.copy(alpha = 0.5f), RoundedCornerShape(24.dp))
                .padding(20.dp),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Header: Thumbnail + Title + Type Badge
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .shadow(4.dp, RoundedCornerShape(12.dp), clip = false)
                            .clip(RoundedCornerShape(12.dp))
                            .background(colors.panelHi)
                            .border(1.dp, colors.border.copy(alpha = 0.5f), RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (thumb != null) {
                            Image(
                                bitmap = thumb.asImageBitmap(),
                                contentDescription = project.name,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else {
                            Icon(
                                painter = painterResource(if (project.isFolder) R.drawable.ic_folders else R.drawable.ic_image),
                                contentDescription = null,
                                tint = colors.subText.copy(alpha = 0.7f),
                                modifier = Modifier.size(28.dp),
                            )
                        }
                    }

                    Spacer(Modifier.width(14.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = project.name,
                            color = colors.text,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(colors.panelHi)
                                    .border(0.5.dp, colors.border.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                                    .padding(horizontal = 7.dp, vertical = 2.5.dp),
                            ) {
                                Text(
                                    text = typeLabel,
                                    color = colors.subText,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                            }
                            if (project.hasRecording) {
                                Spacer(Modifier.width(6.dp))
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(colors.panelHi)
                                        .border(0.5.dp, colors.border.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                                        .padding(horizontal = 7.dp, vertical = 2.5.dp),
                                ) {
                                    Text(
                                        text = stringResource(R.string.gallery_action_replay),
                                        color = colors.subText,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium,
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(18.dp))

                // Detail Items Card
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(colors.panelHi.copy(alpha = 0.5f))
                        .border(1.dp, colors.border.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (project.isFolder) {
                        DetailRow(
                            label = stringResource(R.string.gallery_details_stack_count),
                            value = stringResource(R.string.gallery_stack_artworks_count, project.items.size),
                            colors = colors,
                        )
                    } else {
                        if (project.width > 0 && project.height > 0) {
                            DetailRow(
                                label = stringResource(R.string.gallery_details_dimensions),
                                value = "${project.width} × ${project.height} px",
                                colors = colors,
                            )
                        }
                        DetailRow(
                            label = stringResource(R.string.gallery_details_dpi),
                            value = "${project.dpi} DPI",
                            colors = colors,
                        )
                        DetailRow(
                            label = stringResource(R.string.gallery_details_color_mode),
                            value = project.colorMode,
                            colors = colors,
                        )
                        DetailRow(
                            label = stringResource(R.string.gallery_details_layers),
                            value = "${project.layerCount}",
                            colors = colors,
                        )
                        if (project.strokeCount > 0) {
                            DetailRow(
                                label = stringResource(R.string.gallery_details_strokes),
                                value = "${project.strokeCount}",
                                colors = colors,
                            )
                        }
                        if (project.elapsedSeconds > 0L) {
                            DetailRow(
                                label = stringResource(R.string.gallery_details_time),
                                value = formattedDuration,
                                colors = colors,
                            )
                        }
                    }

                    DetailRow(
                        label = stringResource(R.string.gallery_details_file_size),
                        value = formattedSize,
                        colors = colors,
                    )

                    DetailRow(
                        label = stringResource(R.string.gallery_details_modified),
                        value = dateFormatted,
                        colors = colors,
                    )

                    val displayPath = if (project.isFolder) project.folderPath else project.filePath
                    if (displayPath.isNotEmpty()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .clickable {
                                    val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                                    cm?.setPrimaryClip(android.content.ClipData.newPlainText("path", displayPath))
                                    Toast.makeText(context, toastCopied, Toast.LENGTH_SHORT).show()
                                }
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource(R.string.gallery_details_path),
                                color = colors.subText,
                                fontSize = 12.sp,
                                modifier = Modifier.width(76.dp),
                            )
                            Text(
                                text = displayPath,
                                color = colors.text,
                                fontSize = 11.5.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(4.dp))
                            Icon(
                                painter = painterResource(R.drawable.ic_copy),
                                contentDescription = null,
                                tint = colors.subText,
                                modifier = Modifier.size(13.dp),
                            )
                        }
                    }
                }

                Spacer(Modifier.height(18.dp))

                // Footer button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    ReTextButton(
                        text = stringResource(R.string.close),
                        onClick = onDismiss,
                        textColor = colors.accent,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailRow(
    label: String,
    value: String,
    colors: AppColors,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = colors.subText,
            fontSize = 12.sp,
            modifier = Modifier.width(76.dp),
        )
        Text(
            text = value,
            color = colors.text,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

private fun formatFileSize(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    val gb = mb / 1024.0
    return when {
        gb >= 1.0 -> String.format(Locale.getDefault(), "%.2f GB", gb)
        mb >= 1.0 -> String.format(Locale.getDefault(), "%.1f MB", mb)
        kb >= 1.0 -> String.format(Locale.getDefault(), "%.1f KB", kb)
        else -> "$bytes B"
    }
}

private fun formatDuration(seconds: Long): String {
    if (seconds <= 0L) return "0s"
    val hours = seconds / 3600
    val mins = (seconds % 3600) / 60
    val secs = seconds % 60
    return when {
        hours > 0 -> "${hours}h ${mins}m"
        mins > 0 -> "${mins}m ${secs}s"
        else -> "${secs}s"
    }
}
