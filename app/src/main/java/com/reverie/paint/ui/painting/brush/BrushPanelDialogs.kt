/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.brush

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reverie.paint.R
import com.reverie.paint.core.*
import com.reverie.paint.ui.components.*
import com.reverie.paint.ui.painting.TextInputGuard
import com.reverie.paint.ui.theme.Morandi

@Composable
internal fun CategoryMenuDialog(
    categoryName: String,
    isBuiltIn: Boolean,
    isSpecialPinned: Boolean = false,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onDismiss: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onExportGroup: () -> Unit,
    onBatchManage: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val displayCatName = brushCategoryDisplayName(categoryName)
    val moveUpText = stringResource(R.string.brush_group_move_up)
    val moveDownText = stringResource(R.string.brush_group_move_down)
    val exportGroupText = stringResource(R.string.brush_export_group_action)
    val batchManageText = stringResource(R.string.brush_batch_manage)
    val renameText = stringResource(R.string.brush_group_rename)
    val deleteText = stringResource(R.string.brush_group_delete)
    val builtinTagText = stringResource(R.string.brush_group_builtin_tag)

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.brush_group_header, displayCatName), color = Morandi.text, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 15.sp) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                val menuItems = mutableListOf<Pair<String, () -> Unit>>()
                if (canMoveUp) {
                    menuItems.add(moveUpText to onMoveUp)
                }
                if (canMoveDown) {
                    menuItems.add(moveDownText to onMoveDown)
                }
                if (!isSpecialPinned) {
                    menuItems.add(exportGroupText to onExportGroup)
                }
                menuItems.add(batchManageText to onBatchManage)
                if (!isBuiltIn) {
                    menuItems.add(renameText to onRename)
                    menuItems.add(deleteText to onDelete)
                } else if (!isSpecialPinned) {
                    menuItems.add(builtinTagText to {})
                }
                menuItems.forEach { (label, act) ->
                    val isDelete = label == deleteText
                    val isHint = label == builtinTagText
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
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
            ReTextButton(stringResource(R.string.common_cancel), onDismiss, textColor = Morandi.subText)
        },
        shape = RoundedCornerShape(14.dp),
        containerColor = Morandi.panelHi,
    )
}

/** Dialog to rename a custom brush category. */
@Composable
internal fun RenameBrushGroupDialog(
    initialName: String,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.brush_group_rename_title), color = Morandi.text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.brush_group_rename_hint), color = Morandi.subText, fontSize = 12.sp)
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
                stringResource(R.string.common_confirm),
                onClick = { onRename(name.trim()) },
                enabled = name.isNotBlank() && name.trim() != initialName,
                textColor = Morandi.accent,
            )
        },
        dismissButton = {
            ReTextButton(stringResource(R.string.common_cancel), onDismiss, textColor = Morandi.subText)
        },
        containerColor = Morandi.panelHi,
    )
}

/** Long-press menu: move up/down, duplicate, rename, delete or move to a group. */
@Composable
internal fun ReorderBrushMenu(
    presetName: String,
    isBuiltIn: Boolean,
    isFavorite: Boolean,
    isModified: Boolean = false,
    onDismiss: () -> Unit,
    onToggleFavorite: () -> Unit,
    onShare: () -> Unit,
    onReset: () -> Unit,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onMoveGroup: () -> Unit,
    onDuplicate: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val favRemoveText = stringResource(R.string.brush_fav_remove)
    val favAddText = stringResource(R.string.brush_fav_add)
    val duplicateText = stringResource(R.string.brush_studio_duplicate_brush)
    val shareText = stringResource(R.string.brush_share_action)
    val resetText = stringResource(R.string.brush_reset_action)
    val renameText = stringResource(R.string.common_rename)
    val moveUpText = stringResource(R.string.brush_move_up)
    val moveDownText = stringResource(R.string.brush_move_down)
    val moveToGroupText = stringResource(R.string.brush_move_to_group)
    val deleteBrushText = stringResource(R.string.brush_delete_brush_item)

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(presetName, color = Morandi.text, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 15.sp) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                val menuItems = mutableListOf(
                    (if (isFavorite) favRemoveText else favAddText) to onToggleFavorite,
                    duplicateText to onDuplicate,
                    shareText to onShare,
                )
                if (isModified) {
                    menuItems.add(resetText to onReset)
                }
                if (!isBuiltIn) {
                    menuItems.add(renameText to onRename)
                }
                menuItems.add(moveUpText to onUp)
                menuItems.add(moveDownText to onDown)
                menuItems.add(moveToGroupText to onMoveGroup)
                if (!isBuiltIn) {
                    menuItems.add(deleteBrushText to onDelete)
                }
                menuItems.forEach { (label, act) ->
                    val isDelete = label == deleteBrushText
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
                            color = if (isDelete) Color(0xFFC86464) else Morandi.text,
                            fontSize = 13.sp,
                            fontWeight = if (isDelete) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            ReTextButton(stringResource(R.string.common_cancel), onDismiss, textColor = Morandi.subText)
        },
        containerColor = Morandi.panelHi,
    )
}

/** Dialog to create a new custom brush preset. */
@Composable
internal fun NewBrushPresetDialog(
    groups: List<String>,
    onDismiss: () -> Unit,
    onCreate: (String, String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    val defaultCustomGroupName = stringResource(R.string.brush_studio_custom_brush)
    var selectedGroup by remember { mutableStateOf(groups.firstOrNull() ?: defaultCustomGroupName) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.brush_new_dialog_title), color = Morandi.text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.brush_new_dialog_hint), color = Morandi.subText, fontSize = 12.sp)
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
                Text(stringResource(R.string.brush_new_base_template), color = Morandi.subText.copy(alpha = 0.7f), fontSize = 11.sp)
            }
        },
        confirmButton = {
            ReTextButton(stringResource(R.string.common_create), { onCreate(name.trim(), selectedGroup) }, enabled = name.isNotBlank(), textColor = Morandi.accent)
        },
        dismissButton = {
            ReTextButton(stringResource(R.string.common_cancel), onDismiss, textColor = Morandi.subText)
        },
        containerColor = Morandi.panelHi,
    )
}

/** Dialog to rename a brush preset. */
@Composable
internal fun RenameBrushPresetDialog(
    initialName: String,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.brush_studio_rename_dialog_title), color = Morandi.text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.brush_studio_rename_dialog_hint), color = Morandi.subText, fontSize = 12.sp)
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
            ReTextButton(stringResource(R.string.common_save), { onRename(name.trim()) }, enabled = name.isNotBlank(), textColor = Morandi.accent)
        },
        dismissButton = {
            ReTextButton(stringResource(R.string.common_cancel), onDismiss, textColor = Morandi.subText)
        },
        containerColor = Morandi.panelHi,
    )
}

/** Dialog to create a new user brush group. */
@Composable
internal fun NewBrushGroupDialog(
    existing: List<String>,
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.brush_new_group_title), color = Morandi.text) },
        text = {
            Column {
                Text(stringResource(R.string.brush_new_group_hint), color = Morandi.subText, fontSize = 13.sp)
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
                    Text(stringResource(R.string.brush_group_already_exists), color = Color(0xFFB05552), fontSize = 11.sp)
                }
            }
        },
        confirmButton = {
            ReTextButton(
                stringResource(R.string.common_create),
                onClick = { onCreate(name.trim()) },
                enabled = name.isNotBlank() && !existing.contains(name.trim()),
                textColor = Morandi.accent,
            )
        },
        dismissButton = {
            ReTextButton(stringResource(R.string.common_cancel), onDismiss, textColor = Morandi.subText)
        },
        containerColor = Morandi.panelHi,
    )
}

/** Dialog to move a preset into a group. */
@Composable
internal fun MoveBrushGroupDialog(
    presetName: String,
    groups: List<String>,
    onDismiss: () -> Unit,
    onMove: (String) -> Unit,
    title: String? = null,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title ?: stringResource(R.string.brush_move_to_group_title), color = Morandi.text) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text(presetName, color = Morandi.subText, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.heightIn(max = 280.dp)) {
                    items(groups) { g ->
                        val groupDisplayName = brushCategoryDisplayName(g)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { onMove(g) }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                        ) {
                            Text(groupDisplayName, color = Morandi.text, fontSize = 14.sp)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            ReTextButton(stringResource(R.string.common_cancel), onDismiss, textColor = Morandi.subText)
        },
        containerColor = Morandi.panelHi,
    )
}

/** Second-level page: brush property sliders for the active preset. */
