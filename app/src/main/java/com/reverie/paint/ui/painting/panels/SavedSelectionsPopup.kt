/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.panels

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.reverie.paint.R
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.core.deleteStoredSelectionAction
import com.reverie.paint.core.loadStoredSelectionAction
import com.reverie.paint.core.renameStoredSelectionAction
import com.reverie.paint.core.saveCurrentSelectionAction
import com.reverie.paint.core.updateStoredSelectionAction
import com.reverie.paint.ui.components.noRippleClickable
import com.reverie.paint.ui.theme.Morandi
import com.reverie.paint.ui.theme.glassBorder

@Composable
internal fun SavedSelectionsPopup(
    vm: PaintViewModel,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var renameIndex by remember { mutableStateOf<Int?>(null) }
    var renameInitialName by remember { mutableStateOf("") }

    Box(
        modifier = modifier
            .width(290.dp)
            .shadow(16.dp, RoundedCornerShape(16.dp), spotColor = Color.Black.copy(alpha = 0.35f))
            .clip(RoundedCornerShape(16.dp))
            .background(Morandi.panel)
            .glassBorder(RoundedCornerShape(16.dp))
            .padding(14.dp)
    ) {
        Column {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_bookmark_plus),
                    contentDescription = null,
                    tint = Morandi.accent,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.selection_history_title),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Morandi.text,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = stringResource(R.string.selection_count_label, vm.savedSelections.size),
                    fontSize = 11.sp,
                    color = Morandi.subText,
                )
                Spacer(Modifier.width(8.dp))

                // "+" button to save current selection
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(Morandi.panelHi)
                        .clickable { vm.saveCurrentSelectionAction() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_plus),
                        contentDescription = stringResource(R.string.selection_save_current),
                        tint = Morandi.accent,
                        modifier = Modifier.size(15.dp),
                    )
                }

                Spacer(Modifier.width(6.dp))

                // Close button
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(Morandi.panelHi)
                        .clickable { onDismiss() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_x),
                        contentDescription = stringResource(R.string.common_close),
                        tint = Morandi.subText,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // Body
            if (vm.savedSelections.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_bookmark_plus),
                        contentDescription = null,
                        tint = Morandi.subText.copy(alpha = 0.35f),
                        modifier = Modifier.size(32.dp),
                    )
                    Text(
                        text = stringResource(R.string.selection_empty_history),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = Morandi.subText,
                    )
                    Text(
                        text = stringResource(R.string.selection_empty_history_hint),
                        fontSize = 11.sp,
                        color = Morandi.subText.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 300.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    itemsIndexed(vm.savedSelections) { index, item ->
                        var menuOpen by remember { mutableStateOf(false) }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(Morandi.panelHi)
                                .clickable { vm.loadStoredSelectionAction(index, 0) }
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // Thumbnail
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .border(0.5.dp, Morandi.border, RoundedCornerShape(6.dp))
                                    .background(Color(0xFF222428)),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (item.thumbnail != null) {
                                    Image(
                                        bitmap = item.thumbnail.asImageBitmap(),
                                        contentDescription = null,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                } else {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_bookmark_plus),
                                        contentDescription = null,
                                        tint = Morandi.subText.copy(alpha = 0.4f),
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }

                            Spacer(Modifier.width(10.dp))

                            // Name & hint
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = item.name,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = Morandi.text,
                                    maxLines = 1,
                                )
                                Text(
                                    text = stringResource(R.string.selection_op_replace),
                                    fontSize = 10.sp,
                                    color = Morandi.subText,
                                )
                            }

                            // Menu action button
                            Box {
                                Box(
                                    modifier = Modifier
                                        .size(30.dp)
                                        .clip(CircleShape)
                                        .clickable { menuOpen = true },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_dots_vertical),
                                        contentDescription = null,
                                        tint = Morandi.subText,
                                        modifier = Modifier.size(15.dp),
                                    )
                                }

                                DropdownMenu(
                                    expanded = menuOpen,
                                    onDismissRequest = { menuOpen = false },
                                    modifier = Modifier
                                        .background(Morandi.panel)
                                        .glassBorder(RoundedCornerShape(10.dp)),
                                ) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.selection_op_replace), fontSize = 12.sp, color = Morandi.text) },
                                        onClick = {
                                            menuOpen = false
                                            vm.loadStoredSelectionAction(index, 0)
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.selection_op_add), fontSize = 12.sp, color = Morandi.text) },
                                        onClick = {
                                            menuOpen = false
                                            vm.loadStoredSelectionAction(index, 1)
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.selection_op_subtract), fontSize = 12.sp, color = Morandi.text) },
                                        onClick = {
                                            menuOpen = false
                                            vm.loadStoredSelectionAction(index, 2)
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.selection_op_intersect), fontSize = 12.sp, color = Morandi.text) },
                                        onClick = {
                                            menuOpen = false
                                            vm.loadStoredSelectionAction(index, 3)
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.selection_op_overwrite), fontSize = 12.sp, color = Morandi.text) },
                                        onClick = {
                                            menuOpen = false
                                            vm.updateStoredSelectionAction(index)
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.selection_op_rename), fontSize = 12.sp, color = Morandi.text) },
                                        onClick = {
                                            menuOpen = false
                                            renameIndex = index
                                            renameInitialName = item.name
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.common_delete), fontSize = 12.sp, color = Color(0xFFB05552)) },
                                        onClick = {
                                            menuOpen = false
                                            vm.deleteStoredSelectionAction(index)
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Rename Dialog
    if (renameIndex != null) {
        val targetIdx = renameIndex!!
        var text by remember { mutableStateOf(renameInitialName) }
        Dialog(onDismissRequest = { renameIndex = null }) {
            Box(
                modifier = Modifier
                    .width(280.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Morandi.panel)
                    .glassBorder(RoundedCornerShape(14.dp))
                    .padding(16.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = stringResource(R.string.selection_rename_title),
                        color = Morandi.text,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        singleLine = true,
                        placeholder = { Text(stringResource(R.string.selection_rename_hint), fontSize = 13.sp, color = Morandi.subText) },
                        textStyle = TextStyle(fontSize = 14.sp, color = Morandi.text),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Morandi.panelHi)
                                .clickable { renameIndex = null }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(stringResource(R.string.common_cancel), color = Morandi.subText, fontSize = 13.sp)
                        }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (text.isNotBlank()) Morandi.accent else Morandi.panelHi)
                                .clickable(enabled = text.isNotBlank()) {
                                    vm.renameStoredSelectionAction(targetIdx, text.trim())
                                    renameIndex = null
                                }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                stringResource(R.string.common_confirm),
                                color = if (text.isNotBlank()) Color.White else Morandi.subText,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }
        }
    }
}
