/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.reverie.paint.ui.painting

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.reverie.paint.R
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.model.CanvasEditAction
import com.reverie.paint.ui.theme.Morandi

internal val CanvasEditAction.titleRes: Int
    @StringRes get() = when (this) {
        CanvasEditAction.Clipboard.CUT -> R.string.canvas_edit_cut
        CanvasEditAction.Clipboard.COPY -> R.string.copy
        CanvasEditAction.Clipboard.PASTE -> R.string.paste
        is CanvasEditAction.Shortcut -> action.titleRes
    }

@Composable
internal fun CanvasEditMenuCustomizeDialog(vm: PaintViewModel, onDismiss: () -> Unit) {
    // Edits are a draft until Save, including restoring defaults. Rotation preserves the draft.
    var draft by rememberSaveable { mutableStateOf(CanvasEditAction.encode(vm.canvasEditMenuActions)) }
    val actions = CanvasEditAction.decode(draft)
    val available = CanvasEditAction.available.filterNot { it in actions }
    val windowHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
    val buttonColors = ButtonDefaults.textButtonColors(contentColor = Morandi.text)
    val iconColors = IconButtonDefaults.iconButtonColors(
        contentColor = Morandi.text,
        disabledContentColor = Morandi.subText.copy(alpha = 0.35f),
    )

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.padding(16.dp).widthIn(max = 560.dp).fillMaxWidth()
                .heightIn(max = windowHeight * 0.9f),
            shape = RoundedCornerShape(16.dp),
            color = Morandi.panel,
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.canvas_edit_customize), color = Morandi.text, fontSize = 17.sp)
                Text(stringResource(R.string.canvas_edit_customize_hint), color = Morandi.subText, fontSize = 12.sp)
                LazyColumn(Modifier.weight(1f, fill = false)) {
                    item {
                        Text(
                            stringResource(R.string.quick_action_active_list),
                            Modifier.padding(vertical = 8.dp), color = Morandi.accent, fontSize = 13.sp,
                        )
                    }
                    items(actions, key = { "selected:${it.id}" }) { action ->
                        val index = actions.indexOf(action)
                        val title = stringResource(action.titleRes)
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(title, Modifier.weight(1f), color = Morandi.text, fontSize = 13.sp)
                            for (direction in listOf(-1, 1)) {
                                IconButton(
                                    modifier = Modifier.size(48.dp),
                                    colors = iconColors,
                                    enabled = index + direction in actions.indices,
                                    onClick = {
                                        val reordered = actions.toMutableList()
                                        reordered.add(index + direction, reordered.removeAt(index))
                                        draft = CanvasEditAction.encode(reordered)
                                    },
                                ) {
                                    Icon(
                                        painterResource(
                                            if (direction < 0) R.drawable.ic_arrow_up else R.drawable.ic_arrow_down,
                                        ),
                                        stringResource(
                                            if (direction < 0) R.string.canvas_edit_move_up
                                            else R.string.canvas_edit_move_down,
                                            title,
                                        ),
                                        Modifier.size(18.dp),
                                    )
                                }
                            }
                            IconButton(
                                onClick = { draft = CanvasEditAction.encode(actions - action) },
                                modifier = Modifier.size(48.dp), colors = iconColors, enabled = actions.size > 1,
                            ) {
                                Icon(
                                    painterResource(R.drawable.ic_minus),
                                    stringResource(R.string.canvas_edit_remove, title), Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                    item {
                        Text(
                            stringResource(R.string.quick_action_available_list),
                            Modifier.padding(vertical = 8.dp), color = Morandi.accent, fontSize = 13.sp,
                        )
                    }
                    items(available, key = { "available:${it.id}" }) { action ->
                        val title = stringResource(action.titleRes)
                        TextButton(
                            onClick = { draft = CanvasEditAction.encode(actions + action) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), colors = buttonColors,
                        ) {
                            Text(title, Modifier.weight(1f))
                            Icon(
                                painterResource(R.drawable.ic_plus),
                                stringResource(R.string.canvas_edit_add, title), Modifier.size(18.dp),
                            )
                        }
                    }
                    if (available.isEmpty()) {
                        item { Text(stringResource(R.string.quick_action_all_added), color = Morandi.subText) }
                    }
                }
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(
                        onClick = { draft = CanvasEditAction.encode(CanvasEditAction.defaults) }, colors = buttonColors,
                    ) {
                        Text(stringResource(R.string.quick_action_reset_default))
                    }
                    TextButton(onClick = onDismiss, colors = buttonColors) { Text(stringResource(R.string.cancel)) }
                    TextButton(onClick = {
                        vm.updateCanvasEditMenuActions(actions)
                        onDismiss()
                    }, colors = ButtonDefaults.textButtonColors(contentColor = Morandi.accent)) {
                        Text(stringResource(R.string.canvas_edit_save))
                    }
                }
            }
        }
    }
}
