/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.reverie.paint.ui.painting

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.reverie.paint.R
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.core.editCanvasClipboard
import com.reverie.paint.model.CanvasEditAction
import com.reverie.paint.ui.theme.Morandi

/** Six floating capsules around the editor entry; the artwork stays visible between them. */
@Composable
internal fun CanvasEditMenu(vm: PaintViewModel, onDismiss: () -> Unit, onCustomize: () -> Unit) {
    val pages = vm.canvasEditMenuActions.chunked(6)
    var requestedPage by rememberSaveable { mutableIntStateOf(0) }
    val page = requestedPage.coerceIn(0, pages.lastIndex)
    val hasClipboard = pages[page].any { it is CanvasEditAction.Clipboard }
    val windowHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
    val customizeLabel = stringResource(R.string.canvas_edit_customize)
    Popup(
        alignment = Alignment.Center, onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Column(
            modifier = Modifier.padding(12.dp).widthIn(max = 600.dp)
                .heightIn(max = (windowHeight - 64.dp).coerceAtLeast(120.dp))
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CanvasEditCapsules(
                actions = pages[page],
                actionContent = { action -> CanvasEditActionButton(vm, action, onDismiss) },
                centerContent = {
                    FilledTonalButton(
                        onClick = onCustomize,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .semantics { contentDescription = customizeLabel },
                        shape = RoundedCornerShape(50),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 12.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = Morandi.text, contentColor = Morandi.panel,
                        ),
                    ) {
                        Text(
                            stringResource(R.string.canvas_edit_title), fontSize = 13.sp,
                            textAlign = TextAlign.Center,
                        )
                    }
                },
            )
            Surface(shape = RoundedCornerShape(50), color = Morandi.panel.copy(alpha = 0.96f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (pages.size > 1) {
                        val colors = IconButtonDefaults.iconButtonColors(
                            contentColor = Morandi.text, disabledContentColor = Morandi.subText.copy(alpha = 0.5f),
                        )
                        IconButton(onClick = { requestedPage = page - 1 }, enabled = page > 0, colors = colors) {
                            Icon(
                                painterResource(R.drawable.ic_arrow_left),
                                stringResource(R.string.canvas_edit_previous_page),
                            )
                        }
                        Text(
                            stringResource(R.string.canvas_edit_page, page + 1, pages.size),
                            color = Morandi.text, fontSize = 12.sp,
                        )
                        IconButton(
                            onClick = { requestedPage = page + 1 }, enabled = page < pages.lastIndex, colors = colors,
                        ) {
                            Icon(
                                painterResource(R.drawable.ic_arrow_left),
                                stringResource(R.string.canvas_edit_next_page), Modifier.rotate(180f),
                            )
                        }
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(painterResource(R.drawable.ic_x), stringResource(R.string.close), tint = Morandi.text)
                    }
                }
            }
            if (hasClipboard) {
                Surface(shape = RoundedCornerShape(16.dp), color = Morandi.panel.copy(alpha = 0.96f)) {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        Text(
                            stringResource(
                                when {
                                    vm.canvasEditBusy -> R.string.canvas_edit_loading
                                    !vm.canvasClipboardAvailable -> R.string.canvas_edit_unavailable
                                    vm.canvasEditCapabilities and 1 == 0 -> R.string.canvas_edit_no_pixels
                                    vm.hasSelection -> R.string.canvas_edit_selection_hint
                                    else -> R.string.canvas_edit_layer_hint
                                },
                            ),
                            color = Morandi.text, fontSize = 12.sp,
                        )
                        Text(
                            stringResource(R.string.canvas_edit_clipboard_hint),
                            color = Morandi.text.copy(alpha = 0.75f), fontSize = 11.sp,
                        )
                    }
                }
            }
        }
    }
}

/** Measure labels first so translations and enlarged fonts cannot overlap adjacent capsules. */
@Composable
private fun CanvasEditCapsules(
    actions: List<CanvasEditAction>,
    actionContent: @Composable (CanvasEditAction) -> Unit,
    centerContent: @Composable () -> Unit,
) {
    Layout(
        content = {
            actions.forEach { actionContent(it) }
            centerContent()
        },
        modifier = Modifier.fillMaxWidth(),
    ) { measurables, constraints ->
        val gap = 8.dp.roundToPx()
        val width = ((constraints.maxWidth - 2 * gap) / 3).coerceAtLeast(1)
        val children = measurables.map { it.measure(Constraints.fixedWidth(width)) }
        val height = children.maxOf { it.height }
        val step = height + 12.dp.roundToPx()
        // Clockwise from the top; sparse pages spread their actions around the center.
        val slots = when (actions.size) {
            1 -> listOf(0)
            2 -> listOf(5, 1)
            3 -> listOf(0, 2, 4)
            4 -> listOf(5, 1, 2, 4)
            else -> actions.indices.toList()
        }
        val columns = intArrayOf(1, 2, 2, 1, 0, 0)
        val rows = floatArrayOf(0f, 0.5f, 1.5f, 2f, 1.5f, 0.5f)
        layout(constraints.maxWidth, height + 2 * step) {
            children.dropLast(1).forEachIndexed { index, child ->
                val slot = slots[index]
                child.placeRelative(
                    columns[slot] * (width + gap), (rows[slot] * step).toInt() + (height - child.height) / 2,
                )
            }
            val center = children.last()
            center.placeRelative(width + gap, step + (height - center.height) / 2)
        }
    }
}

@Composable
private fun CanvasEditActionButton(vm: PaintViewModel, action: CanvasEditAction, onDismiss: () -> Unit) {
    val clipboard = action as? CanvasEditAction.Clipboard
    FilledTonalButton(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        shape = RoundedCornerShape(50),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 12.dp),
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = Morandi.panelHi.copy(alpha = 0.96f), contentColor = Morandi.text,
            disabledContainerColor = Morandi.panelHi.copy(alpha = 0.9f),
            disabledContentColor = Morandi.subText.copy(alpha = 0.6f),
        ),
        enabled = clipboard == null ||
            (!vm.canvasEditBusy && vm.canvasEditCapabilities and clipboard.capability != 0),
        onClick = {
            when (action) {
                is CanvasEditAction.Clipboard -> vm.editCanvasClipboard(
                    cut = action == CanvasEditAction.Clipboard.CUT,
                    paste = action == CanvasEditAction.Clipboard.PASTE,
                )
                is CanvasEditAction.Shortcut -> vm.executeQuickAction(action.action)
            }
            onDismiss()
        },
    ) {
        Text(stringResource(action.titleRes), fontSize = 13.sp, textAlign = TextAlign.Center)
    }
}
