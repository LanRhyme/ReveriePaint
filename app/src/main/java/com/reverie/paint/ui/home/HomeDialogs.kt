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
import kotlinx.coroutines.delay
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.activity.compose.BackHandler
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
                    value = folderName,
                    onValueChange = onFolderNameChange,
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
                    TextButton(onClick = onDismiss) {
                        Text("取消", color = colors.subText)
                    }
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = {
                        if (folderName.isNotBlank()) {
                            onCreate(folderName.trim())
                        }
                        onDismiss()
                        onFolderNameChange("")
                    }) {
                        Text("创建", color = colors.accent, fontWeight = FontWeight.Bold)
                    }
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
                .clip(RoundedCornerShape(16.dp))
                .background(colors.panel)
                .border(1.dp, colors.border, RoundedCornerShape(16.dp))
                .padding(22.dp)
        ) {
            Column {
                Text(
                    text = if (project.isFolder) "重命名画集" else "重命名作品",
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
                    TextButton(onClick = onDismiss) {
                        Text("取消", color = colors.subText)
                    }
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = {
                        if (name.isNotBlank()) onRename(name.trim())
                        onDismiss()
                    }) {
                        Text("确定", color = colors.accent, fontWeight = FontWeight.Bold)
                    }
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
                                    onMoved()
                                }
                                .padding(14.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(painterResource(R.drawable.ic_folder_symlink), contentDescription = null, tint = colors.accent, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("移出到画廊根目录", color = colors.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            }
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
                    TextButton(onClick = onDismiss) {
                        Text("取消", color = colors.subText)
                    }
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
                    .clip(RoundedCornerShape(18.dp))
                    .background(colors.panel)
                    .border(1.dp, colors.border, RoundedCornerShape(18.dp))
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
                            text = if (isFolder) "删除画集" else "删除画布",
                            color = colors.text,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    Text(
                        text = if (isFolder) {
                            "确定要删除画集「${target.name}」吗？画集内的所有作品也将被永久删除，此操作无法撤销。"
                        } else {
                            "确定要删除作品「${target.name}」吗？文件将被永久删除，此操作无法撤销。"
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
                        TextButton(onClick = onDismiss) {
                            Text("取消", color = colors.subText, fontSize = 14.sp)
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = {
                                onDismiss()
                                onDelete()
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFFF5252),
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("确认删除", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
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
                    .clip(RoundedCornerShape(18.dp))
                    .background(colors.panel)
                    .border(1.dp, colors.border, RoundedCornerShape(18.dp))
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
                            text = "批量删除",
                            color = colors.text,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    Text(
                        text = "确定要删除选中的 ${count} 项内容吗？此操作无法撤销。",
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
                        TextButton(onClick = onDismiss) {
                            Text("取消", color = colors.subText, fontSize = 14.sp)
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = {
                                onDismiss()
                                onConfirm()
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFFF5252),
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("确认删除", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                    }
                }
            }
        }
    }
}
