/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.reverie.paint.ui.painting

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.reverie.paint.R
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.core.editCanvasClipboard
import com.reverie.paint.model.CanvasEditAction
import com.reverie.paint.ui.theme.Morandi

/** Focusable, dismissible popup with no full-screen dimming over the artwork. */
@Composable
internal fun CanvasEditMenu(vm: PaintViewModel, onDismiss: () -> Unit, onCustomize: () -> Unit) {
    val actions = vm.canvasEditMenuActions
    val hasClipboard = actions.any { it is CanvasEditAction.Clipboard }
    val windowHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
    Popup(
        alignment = Alignment.TopCenter,
        offset = IntOffset(0, with(LocalDensity.current) { 72.dp.roundToPx() }),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp).widthIn(max = 360.dp)
                .heightIn(max = (windowHeight - 96.dp).coerceAtLeast(120.dp))
                .shadow(8.dp, RoundedCornerShape(16.dp))
                .background(Morandi.panel, RoundedCornerShape(16.dp)).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(stringResource(R.string.canvas_edit_title), color = Morandi.text, fontSize = 15.sp)
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                if (hasClipboard) {
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
                        color = Morandi.subText,
                        fontSize = 12.sp,
                    )
                }
                BoxWithConstraints {
                    val columns = if (actions.all { it is CanvasEditAction.Clipboard }) 3 else {
                        (maxWidth / (100.dp * LocalDensity.current.fontScale)).toInt().coerceIn(1, 3)
                    }
                    Column {
                        for (row in actions.chunked(columns)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                for (action in row) {
                                    CanvasEditActionButton(vm, action, onDismiss, Modifier.weight(1f))
                                }
                                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                }
                if (hasClipboard) {
                    Text(
                        stringResource(R.string.canvas_edit_clipboard_hint),
                        color = Morandi.subText, fontSize = 11.sp,
                    )
                }
            }
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onCustomize) {
                    Text(stringResource(R.string.canvas_edit_customize), color = Morandi.accent)
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.close), color = Morandi.text)
                }
            }
        }
    }
}

@Composable
private fun CanvasEditActionButton(
    vm: PaintViewModel,
    action: CanvasEditAction,
    onDismiss: () -> Unit,
    modifier: Modifier,
) {
    val clipboard = action as? CanvasEditAction.Clipboard
    TextButton(
        modifier = modifier.heightIn(min = 48.dp),
        colors = ButtonDefaults.textButtonColors(
            contentColor = Morandi.text,
            disabledContentColor = Morandi.subText.copy(alpha = 0.5f),
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
        Text(stringResource(action.titleRes))
    }
}
