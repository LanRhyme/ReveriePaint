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
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.reverie.paint.ui.components.ReTextButton
import com.reverie.paint.R
import com.reverie.paint.core.*
import com.reverie.paint.model.Project
import com.reverie.paint.model.TimelapseStorage
import com.reverie.paint.ui.theme.AppColors
import com.reverie.paint.ui.theme.optimalTextColor
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
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }
    val submit = {
        if (folderName.isNotBlank()) {
            onCreate(folderName.trim())
            onDismiss()
            onFolderNameChange("")
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(decorFitsSystemWindows = false),
    ) {
        Box(
            modifier = Modifier
                .width(328.dp)
                .imePadding()
                .studioDialogSurface(colors, RoundedCornerShape(24.dp))
                .padding(22.dp),
        ) {
            Column {
                Text(
                    text = stringResource(R.string.home_dialog_new_folder_title),
                    color = colors.text,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(14.dp))
                OutlinedTextField(
                    value = folderName,
                    onValueChange = onFolderNameChange,
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    placeholder = { Text(stringResource(R.string.home_dialog_folder_name_hint), color = colors.subText) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = colors.text,
                        unfocusedTextColor = colors.text,
                        focusedBorderColor = colors.accent,
                        unfocusedBorderColor = Color.Transparent,
                        focusedContainerColor = colors.panelHi,
                        unfocusedContainerColor = colors.panelHi,
                        cursorColor = colors.accent,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                )
                Spacer(Modifier.height(20.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ReTextButton(stringResource(R.string.common_cancel), onDismiss, textColor = colors.subText)
                    Spacer(Modifier.width(8.dp))
                    ReTextButton(
                        text = stringResource(R.string.common_create),
                        onClick = submit,
                        primary = true,
                        enabled = folderName.isNotBlank(),
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
    errorText: String? = null,
    confirmEnabled: Boolean = name.isNotBlank() && errorText == null,
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }
    val submit = {
        if (confirmEnabled && name.isNotBlank()) onRename(name.trim())
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(decorFitsSystemWindows = false),
    ) {
        Box(
            modifier = Modifier
                .width(328.dp)
                .imePadding()
                .studioDialogSurface(colors, RoundedCornerShape(24.dp))
                .padding(22.dp),
        ) {
            Column {
                Text(
                    text = if (project.isFolder) stringResource(R.string.home_dialog_rename_folder_title) else stringResource(R.string.home_dialog_rename_project_title),
                    color = colors.text,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(14.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = onNameChange,
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    isError = errorText != null,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = colors.text,
                        unfocusedTextColor = colors.text,
                        focusedBorderColor = colors.accent,
                        unfocusedBorderColor = Color.Transparent,
                        errorBorderColor = colors.error,
                        focusedContainerColor = colors.panelHi,
                        unfocusedContainerColor = colors.panelHi,
                        errorContainerColor = colors.panelHi,
                        cursorColor = colors.accent,
                        errorCursorColor = colors.error,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                )
                if (errorText != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = errorText,
                        color = colors.error,
                        fontSize = 12.sp,
                    )
                }
                Spacer(Modifier.height(20.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ReTextButton(stringResource(R.string.common_cancel), onDismiss, textColor = colors.subText)
                    Spacer(Modifier.width(8.dp))
                    ReTextButton(
                        text = stringResource(R.string.common_confirm),
                        onClick = submit,
                        primary = true,
                        enabled = confirmEnabled,
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
    currentFolder: Project?,
    targetMoveProjects: List<Project>,
    onMoved: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val rootDir = vm.projectDir()
    val folderChoices = remember(vm.projects, currentFolder?.name) {
        rootDir.listFiles { f: File -> f.isDirectory && f.name != currentFolder?.name }
            ?.sortedBy { it.name.lowercase(Locale.getDefault()) }
            ?.map { dir ->
                val count = dir.listFiles { f ->
                    f.isFile && f.extension.lowercase() in listOf("revp", "kra", "psd", "png")
                }?.size ?: 0
                dir.name to count
            } ?: emptyList()
    }
    val hasRootOption = currentFolder != null
    val totalRows = (if (hasRootOption) 1 else 0) + folderChoices.size

    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .width(328.dp)
                .studioDialogSurface(colors, RoundedCornerShape(24.dp))
                .padding(22.dp),
        ) {
            Column {
                Text(
                    text = stringResource(R.string.home_dialog_move_title),
                    color = colors.text,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(14.dp))
                if (totalRows == 0) {
                    Text(
                        text = stringResource(R.string.home_dialog_move_empty_folders),
                        color = colors.subText,
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                    )
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 300.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        var rowIdx = 0
                        if (hasRootOption) {
                            val rowShape = settingGroupShape(rowIdx++, totalRows, radius = 14.dp, smallRadius = 4.dp)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(rowShape)
                                    .background(colors.panelHi)
                                    .clickable {
                                        targetMoveProjects.forEach { p ->
                                            vm.moveProjectToFolder(p, null)
                                        }
                                        Toast.makeText(context, context.getString(R.string.home_toast_moved_to_root), Toast.LENGTH_SHORT).show()
                                        onMoved()
                                    }
                                    .padding(horizontal = 14.dp, vertical = 13.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_folder_symlink),
                                    contentDescription = null,
                                    tint = colors.accent,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    text = stringResource(R.string.home_dialog_move_to_root),
                                    color = colors.accent,
                                    fontSize = 13.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }

                        folderChoices.forEach { (folderName, count) ->
                            val rowShape = settingGroupShape(rowIdx++, totalRows, radius = 14.dp, smallRadius = 4.dp)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(rowShape)
                                    .background(colors.panelHi)
                                    .clickable {
                                        targetMoveProjects.forEach { p ->
                                            vm.moveProjectToFolder(p, folderName)
                                        }
                                        Toast.makeText(context, context.getString(R.string.home_toast_moved_to_folder, folderName), Toast.LENGTH_SHORT).show()
                                        onMoved()
                                    }
                                    .padding(horizontal = 14.dp, vertical = 13.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_folder),
                                    contentDescription = null,
                                    tint = colors.icon,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    text = folderName,
                                    color = colors.text,
                                    fontSize = 13.5.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                Spacer(Modifier.width(8.dp))
                                Box(
                                    modifier = Modifier
                                        .clip(CircleShape)
                                        .background(colors.panel.copy(alpha = 0.7f))
                                        .padding(horizontal = 7.dp, vertical = 2.dp),
                                ) {
                                    Text(
                                        text = "$count",
                                        color = colors.subText,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        style = TextStyle(fontFeatureSettings = "tnum"),
                                    )
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(18.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
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
    val isFolder = target.isFolder
    val errorColor = colors.error
    val onErrorColor = errorColor.optimalTextColor()
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
                    .width(328.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    )
                    .studioDialogSurface(colors, RoundedCornerShape(24.dp))
                    .padding(22.dp),
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(errorColor.copy(alpha = 0.14f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_alert_triangle),
                                contentDescription = null,
                                tint = errorColor,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = if (isFolder) stringResource(R.string.home_dialog_delete_folder_title) else stringResource(R.string.home_dialog_delete_project_title),
                            color = colors.text,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
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
                        lineHeight = 19.sp,
                    )
                    Spacer(Modifier.height(22.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ReTextButton(stringResource(R.string.common_cancel), onDismiss, textColor = colors.subText, fontSize = 14.sp)
                        Spacer(Modifier.width(8.dp))
                        ReTextButton(
                            text = stringResource(R.string.home_dialog_confirm_delete),
                            onClick = {
                                onDismiss()
                                onDelete()
                            },
                            primary = true,
                            containerColor = errorColor,
                            contentColor = onErrorColor,
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
    val errorColor = colors.error
    val onErrorColor = errorColor.optimalTextColor()
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
                    .width(328.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    )
                    .studioDialogSurface(colors, RoundedCornerShape(24.dp))
                    .padding(22.dp),
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(errorColor.copy(alpha = 0.14f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_alert_triangle),
                                contentDescription = null,
                                tint = errorColor,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = stringResource(R.string.home_dialog_batch_delete_title),
                            color = colors.text,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    Text(
                        text = stringResource(R.string.home_dialog_batch_delete_confirm, count),
                        color = colors.subText,
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                    )
                    Spacer(Modifier.height(22.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ReTextButton(stringResource(R.string.common_cancel), onDismiss, textColor = colors.subText, fontSize = 14.sp)
                        Spacer(Modifier.width(8.dp))
                        ReTextButton(
                            text = stringResource(R.string.home_dialog_confirm_delete),
                            onClick = {
                                onDismiss()
                                onConfirm()
                            },
                            primary = true,
                            containerColor = errorColor,
                            contentColor = onErrorColor,
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
    val hairlineBrush = remember(colors.isDarkTheme()) { artboardHairlineBrush(colors) }
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
                    .padding(horizontal = 20.dp)
                    .widthIn(max = 420.dp)
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    )
                    .studioDialogSurface(colors, RoundedCornerShape(24.dp))
                    .padding(22.dp),
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
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
                                            .then(
                                                if (isCover) {
                                                    Modifier.border(2.5.dp, colors.accent, RoundedCornerShape(10.dp))
                                                } else {
                                                    Modifier.border(0.75.dp, hairlineBrush, RoundedCornerShape(10.dp))
                                                },
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
                                                    .clip(CircleShape)
                                                    .background(colors.accent)
                                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                                            ) {
                                                Text(
                                                    text = stringResource(R.string.gallery_cover_badge),
                                                    color = colors.onAccent,
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
                                        overflow = TextOverflow.Ellipsis,
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
    val isDark = colors.isDarkTheme()
    val hairlineBrush = remember(isDark) { artboardHairlineBrush(colors) }
    val rowDividerColor = (if (isDark) Color.White else Color.Black).copy(alpha = 0.06f)

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

    val hasReplay = remember(project) {
        // 延时摄影帧存放在私有目录 <filesDir>/timelapse/<dirName>/
        // 统一使用 TimelapseStorage 解析以支持画集隔离与去草稿标记
        project.hasRecording || run {
            val dir = TimelapseStorage.resolveTimelapseFramesDir(
                filesDir = context.filesDir,
                filePath = project.filePath,
                projectName = project.name,
                masterFilePath = project.masterFilePath.takeIf { it.isNotBlank() },
            )
            dir?.exists() == true && dir.isDirectory && (dir.list()?.isNotEmpty() == true)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .padding(24.dp)
                .widthIn(max = 440.dp)
                .heightIn(max = 600.dp)
                .fillMaxWidth()
                .studioDialogSurface(colors, RoundedCornerShape(24.dp))
                .padding(20.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                // Header: Thumbnail + Title + Type Badge
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .shadow(6.dp, RoundedCornerShape(12.dp), clip = false)
                            .clip(RoundedCornerShape(12.dp))
                            .background(colors.panelHi)
                            .border(0.75.dp, hairlineBrush, RoundedCornerShape(12.dp)),
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
                        Spacer(Modifier.height(6.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(colors.panelHi)
                                    .border(0.5.dp, hairlineBrush, CircleShape)
                                    .padding(horizontal = 8.dp, vertical = 3.dp),
                            ) {
                                Text(
                                    text = typeLabel,
                                    color = colors.subText,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                            }
                            if (hasReplay) {
                                Box(
                                    modifier = Modifier
                                        .clip(CircleShape)
                                        .background(colors.accent.copy(alpha = 0.14f))
                                        .padding(horizontal = 8.dp, vertical = 3.dp),
                                ) {
                                    Text(
                                        text = stringResource(R.string.gallery_action_replay),
                                        color = colors.accent,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                }
                            }
                        }
                    }
                }

                // Quick Metrics Strip for artworks
                if (!project.isFolder && project.width > 0 && project.height > 0) {
                    Spacer(Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        QuickMetricTile(
                            label = stringResource(R.string.gallery_details_dimensions),
                            value = "${project.width}×${project.height}",
                            colors = colors,
                            modifier = Modifier.weight(1.2f),
                        )
                        QuickMetricTile(
                            label = stringResource(R.string.gallery_details_layers),
                            value = "${project.layerCount}",
                            colors = colors,
                            modifier = Modifier.weight(0.8f),
                        )
                        QuickMetricTile(
                            label = if (project.strokeCount > 0) {
                                stringResource(R.string.gallery_details_strokes)
                            } else {
                                stringResource(R.string.gallery_details_time)
                            },
                            value = if (project.strokeCount > 0) {
                                "${project.strokeCount}"
                            } else {
                                formattedDuration
                            },
                            colors = colors,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))

                // Detail Items Card
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(colors.panelHi.copy(alpha = 0.55f))
                        .border(0.75.dp, hairlineBrush, RoundedCornerShape(16.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    if (project.isFolder) {
                        DetailRow(
                            label = stringResource(R.string.gallery_details_stack_count),
                            value = stringResource(R.string.gallery_stack_artworks_count, project.items.size),
                            colors = colors,
                        )
                        DetailDivider(rowDividerColor)
                    } else {
                        DetailRow(
                            label = stringResource(R.string.gallery_details_dpi),
                            value = "${project.dpi} DPI",
                            colors = colors,
                        )
                        DetailDivider(rowDividerColor)
                        DetailRow(
                            label = stringResource(R.string.gallery_details_color_mode),
                            value = project.colorMode,
                            colors = colors,
                        )
                        DetailDivider(rowDividerColor)
                        if (project.elapsedSeconds > 0L && project.strokeCount > 0) {
                            DetailRow(
                                label = stringResource(R.string.gallery_details_time),
                                value = formattedDuration,
                                colors = colors,
                            )
                            DetailDivider(rowDividerColor)
                        }
                    }

                    DetailRow(
                        label = stringResource(R.string.gallery_details_file_size),
                        value = formattedSize,
                        colors = colors,
                    )
                    DetailDivider(rowDividerColor)

                    DetailRow(
                        label = stringResource(R.string.gallery_details_modified),
                        value = dateFormatted,
                        colors = colors,
                    )

                    val displayPath = if (project.isFolder) project.folderPath else project.filePath
                    if (displayPath.isNotEmpty()) {
                        DetailDivider(rowDividerColor)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .clickable {
                                    val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                                    cm?.setPrimaryClip(android.content.ClipData.newPlainText("path", displayPath))
                                    Toast.makeText(context, toastCopied, Toast.LENGTH_SHORT).show()
                                }
                                .padding(vertical = 5.dp),
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
private fun QuickMetricTile(
    label: String,
    value: String,
    colors: AppColors,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(colors.panelHi.copy(alpha = 0.72f))
            .padding(horizontal = 10.dp, vertical = 9.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        Text(
            text = label,
            color = colors.subText,
            fontSize = 10.5.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(3.dp))
        Text(
            text = value,
            color = colors.text,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            style = TextStyle(fontFeatureSettings = "tnum"),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun DetailDivider(color: Color) {
    HorizontalDivider(
        thickness = 0.5.dp,
        color = color,
        modifier = Modifier.padding(vertical = 2.dp),
    )
}

@Composable
private fun DetailRow(
    label: String,
    value: String,
    colors: AppColors,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
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
            style = TextStyle(fontFeatureSettings = "tnum"),
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
