/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.reverie.paint.ui.painting

import androidx.annotation.StringRes
import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Tab
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
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

internal val CanvasEditAction.iconRes: Int
    @DrawableRes get() = when (this) {
        CanvasEditAction.Clipboard.CUT -> R.drawable.ic_cut
        CanvasEditAction.Clipboard.COPY -> R.drawable.ic_copy
        CanvasEditAction.Clipboard.PASTE -> R.drawable.ic_paste
        is CanvasEditAction.Shortcut -> action.iconRes
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CanvasEditMenuCustomizeDialog(vm: PaintViewModel, onDismiss: () -> Unit) {
    // Edits are a draft until Save, including restoring defaults. Rotation preserves the draft.
    var draft by rememberSaveable { mutableStateOf(CanvasEditAction.encode(vm.canvasEditMenuActions)) }
    val actions = CanvasEditAction.decode(draft)
    val available = CanvasEditAction.available.filterNot { it in actions }
    var adding by rememberSaveable { mutableStateOf(false) }
    val selectedScroll = rememberLazyListState()
    val availableScroll = rememberLazyListState()
    val windowHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
    val buttonColors = ButtonDefaults.textButtonColors(contentColor = Morandi.text)
    val iconColors = IconButtonDefaults.iconButtonColors(
        contentColor = Morandi.text,
        disabledContentColor = Morandi.subText.copy(alpha = 0.35f),
    )

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.padding(16.dp).widthIn(max = 560.dp).fillMaxWidth()
                .heightIn(max = minOf(windowHeight * 0.9f, 640.dp)),
            shape = RoundedCornerShape(16.dp),
            color = Morandi.panel,
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.canvas_edit_customize), color = Morandi.text, fontSize = 17.sp)
                PrimaryTabRow(
                    selectedTabIndex = if (adding) 1 else 0,
                    containerColor = Morandi.panel, contentColor = Morandi.text,
                    indicator = {
                        TabRowDefaults.PrimaryIndicator(
                            modifier = Modifier.tabIndicatorOffset(if (adding) 1 else 0), color = Morandi.accent,
                        )
                    },
                    divider = {},
                ) {
                    for (showAvailable in listOf(false, true)) {
                        Tab(
                            selected = adding == showAvailable,
                            onClick = { adding = showAvailable },
                            selectedContentColor = Morandi.text,
                            unselectedContentColor = Morandi.text.copy(alpha = 0.65f),
                            text = {
                                Text(
                                    stringResource(
                                        if (showAvailable) R.string.canvas_edit_available_tab
                                        else R.string.canvas_edit_selected_tab,
                                        if (showAvailable) available.size else actions.size,
                                    ),
                                    fontSize = 13.sp,
                                )
                            },
                        )
                    }
                }
                LazyColumn(
                    Modifier.weight(1f), state = if (adding) availableScroll else selectedScroll,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (windowHeight >= 480.dp) item {
                        Text(
                            stringResource(R.string.canvas_edit_customize_hint), Modifier.padding(vertical = 8.dp),
                            color = Morandi.text.copy(alpha = 0.75f), fontSize = 12.sp,
                        )
                    }
                    if (!adding) items(actions, key = { "selected:${it.id}" }) { action ->
                        val index = actions.indexOf(action)
                        val title = stringResource(action.titleRes)
                        BoxWithConstraints(Modifier.fillMaxWidth()) {
                            val compact = maxWidth < 400.dp * LocalDensity.current.fontScale
                            val label: @Composable (Modifier) -> Unit = { modifier ->
                                Row(
                                    modifier, verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    Icon(
                                        painterResource(action.iconRes), null,
                                        Modifier.size(20.dp), tint = Morandi.text,
                                    )
                                    Text(title, Modifier.weight(1f), color = Morandi.text, fontSize = 14.sp)
                                }
                            }
                            val controls: @Composable () -> Unit = {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                                                    if (direction < 0) R.drawable.ic_arrow_up
                                                    else R.drawable.ic_arrow_down,
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
                                        modifier = Modifier.size(48.dp), colors = iconColors,
                                        enabled = actions.size > 1,
                                    ) {
                                        Icon(
                                            painterResource(R.drawable.ic_minus),
                                            stringResource(R.string.canvas_edit_remove, title), Modifier.size(18.dp),
                                        )
                                    }
                                }
                            }
                            if (compact) {
                                Column(Modifier.padding(top = 8.dp)) {
                                    label(Modifier.fillMaxWidth())
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { controls() }
                                }
                            } else {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    label(Modifier.weight(1f))
                                    controls()
                                }
                            }
                        }
                    }
                    if (adding) items(available, key = { "available:${it.id}" }) { action ->
                        val title = stringResource(action.titleRes)
                        TextButton(
                            onClick = { draft = CanvasEditAction.encode(actions + action) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), colors = buttonColors,
                        ) {
                            Icon(painterResource(action.iconRes), null, Modifier.size(20.dp))
                            Text(title, Modifier.weight(1f).padding(horizontal = 12.dp), fontSize = 14.sp)
                            Icon(
                                painterResource(R.drawable.ic_plus),
                                null, Modifier.size(18.dp),
                            )
                        }
                    }
                    if (adding && available.isEmpty()) {
                        item { Text(stringResource(R.string.quick_action_all_added), color = Morandi.subText) }
                    }
                }
                FlowRow(
                    Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    TextButton(
                        onClick = { draft = CanvasEditAction.encode(CanvasEditAction.defaults) }, colors = buttonColors,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(stringResource(R.string.quick_action_reset_default))
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = onDismiss, colors = buttonColors) { Text(stringResource(R.string.cancel)) }
                        Button(onClick = {
                            vm.updateCanvasEditMenuActions(actions)
                            onDismiss()
                        }, colors = ButtonDefaults.buttonColors(
                            containerColor = Morandi.accent, contentColor = Morandi.onAccent,
                        ), modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.canvas_edit_save))
                        }
                    }
                }
            }
        }
    }
}
