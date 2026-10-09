/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.dialog

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.reverie.paint.R
import com.reverie.paint.core.AutoSaveHistoryManager
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.core.restoreAutoSaveSnapshot
import com.reverie.paint.model.AutoSaveSnapshot
import com.reverie.paint.ui.components.ReDropdownMenu
import com.reverie.paint.ui.components.ReDropdownMenuItem
import com.reverie.paint.ui.components.ReTextButton
import com.reverie.paint.ui.theme.Theme
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun RecentAutoSavesDialog(
    vm: PaintViewModel,
    onDismiss: () -> Unit,
) {
    val colors = Theme.current
    val context = LocalContext.current
    var snapshots by remember { mutableStateOf(AutoSaveHistoryManager.getSnapshots(context)) }
    var pendingRestoreSnapshot by remember { mutableStateOf<AutoSaveSnapshot?>(null) }
    var showClearConfirm by remember { mutableStateOf(false) }

    val totalBytes = remember(snapshots) { snapshots.sumOf { it.fileSize } }
    val totalMbStr = remember(totalBytes) {
        String.format(Locale.getDefault(), "%.1f", totalBytes / (1024.0 * 1024.0))
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .widthIn(max = 600.dp)
                .heightIn(max = 650.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(colors.panel)
                .border(1.dp, colors.panelHi, RoundedCornerShape(24.dp))
                .padding(22.dp),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
            ) {
                // 顶部标题栏
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        modifier = Modifier.weight(1f, fill = false),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(colors.panelHi),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.History,
                                contentDescription = null,
                                tint = colors.accent,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                text = stringResource(R.string.auto_save_history_title),
                                color = colors.text,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                text = if (snapshots.isEmpty()) {
                                    stringResource(R.string.settings_auto_save_history_sub)
                                } else {
                                    stringResource(R.string.auto_save_history_stats_with_limit, snapshots.size, vm.autoSaveMaxSnapshots, totalMbStr)
                                },
                                color = colors.subText,
                                fontSize = 12.sp,
                            )
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        // 容量设置下拉
                        var showCapacityMenu by remember { mutableStateOf(false) }
                        Box {
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(colors.panelHi)
                                    .clickable { showCapacityMenu = true }
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = stringResource(R.string.auto_save_capacity_pill, vm.autoSaveMaxSnapshots),
                                    color = colors.accent,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Spacer(Modifier.width(4.dp))
                                Icon(
                                    painter = painterResource(R.drawable.ic_chevron),
                                    contentDescription = null,
                                    tint = colors.accent,
                                    modifier = Modifier
                                        .size(11.dp)
                                        .rotate(90f),
                                )
                            }
                            ReDropdownMenu(
                                expanded = showCapacityMenu,
                                onDismissRequest = { showCapacityMenu = false },
                            ) {
                                listOf(3, 5, 8, 12, 16, 24).forEach { count ->
                                    val isSelected = count == vm.autoSaveMaxSnapshots
                                    ReDropdownMenuItem(
                                        text = if (count == 8) {
                                            stringResource(R.string.settings_snapshot_count_default, count)
                                        } else {
                                            stringResource(R.string.settings_snapshot_count_unit, count)
                                        },
                                        selected = isSelected,
                                        trailingIcon = if (isSelected) {
                                            {
                                                Icon(
                                                    painter = painterResource(R.drawable.ic_check),
                                                    contentDescription = null,
                                                    tint = colors.accent,
                                                    modifier = Modifier.size(16.dp),
                                                )
                                            }
                                        } else null,
                                        onClick = {
                                            vm.updateAutoSaveMaxSnapshots(count)
                                            snapshots = AutoSaveHistoryManager.getSnapshots(context)
                                            showCapacityMenu = false
                                        },
                                    )
                                }
                            }
                        }

                        if (snapshots.isNotEmpty()) {
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(colors.panelHi)
                                    .clickable { showClearConfirm = true }
                                    .padding(horizontal = 9.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.DeleteOutline,
                                    contentDescription = null,
                                    tint = colors.subText,
                                    modifier = Modifier.size(14.dp),
                                )
                                Spacer(Modifier.width(3.dp))
                                Text(
                                    text = stringResource(R.string.auto_save_clear_all),
                                    color = colors.subText,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                            }
                        }
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(colors.panelHi)
                                .clickable { onDismiss() },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = "Close",
                                tint = colors.icon,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))

                // 快照列表
                if (snapshots.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(colors.panelHi),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Rounded.History,
                                contentDescription = null,
                                tint = colors.subText,
                                modifier = Modifier.size(42.dp),
                            )
                            Spacer(Modifier.height(10.dp))
                            Text(
                                text = stringResource(R.string.auto_save_history_empty),
                                color = colors.subText,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = stringResource(R.string.auto_save_history_empty_sub),
                                color = colors.subText.copy(alpha = 0.7f),
                                fontSize = 12.sp,
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f, fill = false),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(snapshots, key = { it.id }) { item ->
                            SnapshotCard(
                                snapshot = item,
                                onRestore = {
                                    pendingRestoreSnapshot = item
                                },
                                onDelete = {
                                    AutoSaveHistoryManager.deleteSnapshot(context, item.id)
                                    snapshots = AutoSaveHistoryManager.getSnapshots(context)
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    // 清空全部二次确认对话框
    if (showClearConfirm) {
        Dialog(
            onDismissRequest = { showClearConfirm = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.85f)
                    .widthIn(max = 400.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(colors.panel)
                    .border(1.dp, colors.panelHi, RoundedCornerShape(20.dp))
                    .padding(20.dp),
            ) {
                Column {
                    Text(
                        text = stringResource(R.string.auto_save_clear_confirm_title),
                        color = colors.text,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = stringResource(R.string.auto_save_clear_confirm_desc),
                        color = colors.subText,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                    )
                    Spacer(Modifier.height(18.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        ReTextButton(
                            text = stringResource(R.string.common_cancel),
                            onClick = { showClearConfirm = false },
                            primary = false,
                            textColor = colors.subText,
                            modifier = Modifier.weight(1f),
                        )
                        ReTextButton(
                            text = stringResource(R.string.auto_save_clear_all),
                            onClick = {
                                AutoSaveHistoryManager.clearSnapshots(context)
                                snapshots = emptyList()
                                showClearConfirm = false
                            },
                            primary = true,
                            containerColor = Color(0xFFC86464),
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }

    // 恢复确认对话框
    pendingRestoreSnapshot?.let { snapshot ->
        RestoreConfirmDialog(
            snapshot = snapshot,
            hasUnsavedChanges = vm.currentPage == com.reverie.paint.core.Page.PAINTING && vm.hasUnsavedChanges(),
            onDismiss = { pendingRestoreSnapshot = null },
            onRestoreReplace = {
                vm.restoreAutoSaveSnapshot(snapshot, asCopy = false)
                pendingRestoreSnapshot = null
                onDismiss()
            },
            onRestoreAsCopy = {
                vm.restoreAutoSaveSnapshot(snapshot, asCopy = true)
                pendingRestoreSnapshot = null
                onDismiss()
            },
        )
    }
}

@Composable
private fun SnapshotCard(
    snapshot: AutoSaveSnapshot,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = Theme.current
    val formattedTime = remember(snapshot.timestamp) {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(snapshot.timestamp))
    }
    val sizeText = remember(snapshot.fileSize) {
        val mb = snapshot.fileSize / (1024.0 * 1024.0)
        String.format(Locale.getDefault(), "%.1f MB", mb)
    }

    val thumbBitmap = remember(snapshot.thumbPath) {
        if (snapshot.thumbPath.isNotBlank() && File(snapshot.thumbPath).exists()) {
            try {
                BitmapFactory.decodeFile(snapshot.thumbPath)
            } catch (_: Throwable) {
                null
            }
        } else {
            null
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(colors.panelHi)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 缩略图
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(colors.panel),
            contentAlignment = Alignment.Center,
        ) {
            if (thumbBitmap != null) {
                Image(
                    bitmap = thumbBitmap.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    imageVector = Icons.Rounded.Image,
                    contentDescription = null,
                    tint = colors.subText,
                    modifier = Modifier.size(28.dp),
                )
            }
        }

        Spacer(Modifier.width(14.dp))

        // 信息区
        Column(
            modifier = Modifier.weight(1f),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = snapshot.displayName,
                    color = colors.text,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (snapshot.isEmergency) {
                    Spacer(Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(colors.accent.copy(alpha = 0.2f))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.auto_save_badge_emergency),
                            color = colors.accent,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
            Spacer(Modifier.height(3.dp))
            Text(
                text = formattedTime,
                color = colors.subText,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = stringResource(R.string.auto_save_snapshot_meta, snapshot.strokeCount, snapshot.layerCount, sizeText),
                color = colors.subText.copy(alpha = 0.8f),
                fontSize = 11.sp,
            )
        }

        Spacer(Modifier.width(10.dp))

        // 操作按钮
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(colors.accent.copy(alpha = 0.15f))
                    .clickable { onRestore() }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.auto_save_restore_action),
                    color = colors.accent,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            Spacer(Modifier.width(8.dp))

            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(colors.panel)
                    .clickable { onDelete() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.DeleteOutline,
                    contentDescription = "Delete",
                    tint = colors.subText,
                    modifier = Modifier.size(17.dp),
                )
            }
        }
    }
}

@Composable
private fun RestoreConfirmDialog(
    snapshot: AutoSaveSnapshot,
    hasUnsavedChanges: Boolean,
    onDismiss: () -> Unit,
    onRestoreReplace: () -> Unit,
    onRestoreAsCopy: () -> Unit,
) {
    val colors = Theme.current
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.88f)
                .widthIn(max = 460.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(colors.panel)
                .border(1.dp, colors.panelHi, RoundedCornerShape(22.dp))
                .padding(20.dp),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(colors.accent.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = null,
                            tint = colors.accent,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = stringResource(R.string.auto_save_restore_confirm_title),
                        color = colors.text,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }

                Spacer(Modifier.height(14.dp))

                Text(
                    text = if (hasUnsavedChanges) {
                        stringResource(R.string.auto_save_restore_confirm_msg)
                    } else {
                        stringResource(R.string.auto_save_restore_confirm_msg_clean, snapshot.displayName)
                    },
                    color = colors.subText,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                )

                Spacer(Modifier.height(20.dp))

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // 方式 1: 载入画布
                    ReTextButton(
                        text = stringResource(R.string.auto_save_restore_replace),
                        onClick = onRestoreReplace,
                        primary = true,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    // 方式 2: 新建为画廊副本
                    ReTextButton(
                        text = stringResource(R.string.auto_save_restore_as_copy),
                        onClick = onRestoreAsCopy,
                        primary = false,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    // 取消
                    ReTextButton(
                        text = stringResource(R.string.cancel),
                        onClick = onDismiss,
                        primary = false,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}
