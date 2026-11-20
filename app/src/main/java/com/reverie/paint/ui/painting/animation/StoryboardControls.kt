/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.reverie.paint.ui.painting.animation

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reverie.paint.R
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.core.animationImportSrt
import com.reverie.paint.core.animationImportText
import com.reverie.paint.core.animationPause
import com.reverie.paint.core.animationRemoveStoryboardReference
import com.reverie.paint.core.animationSeek
import com.reverie.paint.core.animationSplitStoryboardCue
import com.reverie.paint.core.animationUpdateStoryboardCue
import com.reverie.paint.model.SrtStoryboard
import com.reverie.paint.ui.components.ReChip
import com.reverie.paint.ui.theme.Morandi

@Composable
internal fun StoryboardImportButton(vm: PaintViewModel) {
    var confirm by remember { mutableStateOf(false) }
    var showTextImport by remember { mutableStateOf(false) }
    var pastedText by remember { mutableStateOf("") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.animationImportSrt(uri)
    }
    ReChip(
        text = stringResource(if (vm.anim.storyboard.importing) R.string.anim_importing else R.string.srt_import_title),
        onTap = { if (!vm.anim.storyboard.importing && !vm.isImportingMedia) confirm = true },
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
    )
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            containerColor = Morandi.panel,
            title = { Text(stringResource(R.string.srt_import_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.srt_import_description), color = Morandi.text)
                    TextButton(onClick = {
                        confirm = false
                        pastedText = ""
                        showTextImport = true
                    }) {
                        Text(stringResource(R.string.srt_import_text))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    // Many Android document providers label .srt as application/octet-stream.
                    picker.launch(arrayOf("*/*"))
                }) { Text(stringResource(R.string.srt_choose_file)) }
            },
            dismissButton = {
                TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.srt_close)) }
            },
        )
    }
    if (showTextImport) {
        AlertDialog(
            onDismissRequest = { showTextImport = false },
            containerColor = Morandi.panel,
            title = { Text(stringResource(R.string.srt_import_text_title), color = Morandi.text) },
            text = {
                OutlinedTextField(
                    value = pastedText,
                    onValueChange = { pastedText = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 280.dp),
                    placeholder = {
                        Text(stringResource(R.string.srt_import_text_hint), color = Morandi.subText)
                    },
                    minLines = 5,
                    maxLines = 10,
                    textStyle = TextStyle(color = Morandi.text, fontSize = 14.sp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Morandi.text,
                        unfocusedTextColor = Morandi.text,
                        focusedBorderColor = Morandi.accent,
                        unfocusedBorderColor = Morandi.border,
                        focusedContainerColor = Morandi.panel,
                        unfocusedContainerColor = Morandi.panel,
                        cursorColor = Morandi.accent,
                        focusedPlaceholderColor = Morandi.subText,
                        unfocusedPlaceholderColor = Morandi.subText,
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = pastedText.isNotBlank() && !vm.anim.storyboard.importing && !vm.isImportingMedia,
                    onClick = {
                        vm.animationImportText(pastedText)
                        showTextImport = false
                    },
                ) {
                    Text(stringResource(R.string.srt_import_text_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showTextImport = false }) {
                    Text(stringResource(R.string.srt_close))
                }
            },
        )
    }
}

@Composable
internal fun StoryboardReferenceBar(
    vm: PaintViewModel,
    modifier: Modifier = Modifier,
) {
    val state = vm.anim.storyboard
    val cues = state.cues
    if (cues.isEmpty()) return
    var showList by remember { mutableStateOf(false) }
    var showRemove by remember { mutableStateOf(false) }
    var pendingFocusIndex by remember { mutableIntStateOf(-1) }
    var pendingFocusCueCount by remember { mutableIntStateOf(-1) }
    var focusReadyIndex by remember { mutableIntStateOf(-1) }
    val listState = rememberLazyListState()
    val frames = state.frames
    val mappingMissing = state.singleFrameMode && frames.size != cues.size
    val active = SrtStoryboard.findActiveFrameIndex(frames, vm.anim.currentTime)
    val isFloating = vm.floatingUiConfig.enabled
    val shape = if (isFloating) RoundedCornerShape(14.dp) else RoundedCornerShape(0.dp)
    LaunchedEffect(cues.size, state.adding, pendingFocusIndex, pendingFocusCueCount) {
        if (pendingFocusIndex in cues.indices && cues.size >= pendingFocusCueCount && !state.adding) {
            // 先把新帧滚进可视区, 再放行聚焦, 避免 LazyColumn 尚未组合该行导致焦点丢失。
            listState.animateScrollToItem(pendingFocusIndex)
            focusReadyIndex = pendingFocusIndex
        }
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (isFloating) {
                    Modifier
                        .padding(bottom = 6.dp)
                        .clip(shape)
                } else {
                    Modifier
                },
            )
            .background(Morandi.panel)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { showList = true },
                    onLongPress = { showRemove = true },
                )
            }
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (active >= 0) stringResource(R.string.srt_reference_count, active + 1, cues.size)
                    else stringResource(if (mappingMissing) R.string.srt_mapping_changed else R.string.srt_reference_gap),
                color = Morandi.subText, fontSize = 11.sp,
            )
            if (state.singleFrameMode) {
                Text(stringResource(R.string.srt_single_frame_hint), color = Morandi.subText, fontSize = 11.sp)
            }
            Text(
                text = cues.getOrNull(active)?.text ?: stringResource(R.string.srt_open_list),
                color = Morandi.text, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
    }
    if (showList) {
        AlertDialog(
            onDismissRequest = { showList = false },
            containerColor = Morandi.panel,
            title = {
                Text(if (state.singleFrameMode) stringResource(R.string.srt_single_frame_title)
                    else stringResource(R.string.srt_list_title, state.fps), color = Morandi.text)
            },
            text = {
                Column {
                    if (state.singleFrameMode) {
                        Text(
                                    stringResource(R.string.srt_add_frame_hint),
                                    color = Morandi.subText,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                    }
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
                    ) {
                        itemsIndexed(
                            items = cues,
                            key = { _, cue -> "${cue.startMs}:${cue.endMs}" },
                        ) { index, cue ->
                            val targetFrame = frames.getOrNull(index)
                            val focusRequester = remember(index) { FocusRequester() }
                            val autoFocus = state.singleFrameMode && index == focusReadyIndex &&
                                cues.size >= pendingFocusCueCount && !state.adding
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            ) {
                                Text(
                                    "${index + 1} · ${SrtStoryboard.timestamp(cue.startMs)} → " +
                                        SrtStoryboard.timestamp(cue.endMs),
                                    fontSize = 11.sp,
                                    color = Morandi.subText,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable(enabled = !mappingMissing && targetFrame != null) {
                                            targetFrame?.let { frame ->
                                                vm.animationPause()
                                                vm.animationSeek(frame.start)
                                                vm.anim.scrollPx = frame.start * vm.anim.frameWidthPx
                                                vm.anim.toolbarExpanded = false
                                                showList = false
                                            }
                                        },
                                )
                                StoryboardCueEditor(
                                    vm = vm,
                                    index = index,
                                    cueText = cue.text,
                                    focusRequester = focusRequester,
                                    autoFocus = autoFocus,
                                    allowSplit = state.singleFrameMode && !mappingMissing && !state.adding,
                                    onSplit = { left, right ->
                                        val added = vm.animationSplitStoryboardCue(
                                            index = index,
                                            leftText = left,
                                            rightText = right,
                                        )
                                        if (added) {
                                            pendingFocusIndex = index + 1
                                            pendingFocusCueCount = cues.size + 1
                                        }
                                        added
                                    },
                                    onAutoFocus = {
                                        focusReadyIndex = -1
                                        pendingFocusIndex = -1
                                        pendingFocusCueCount = -1
                                    },
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showList = false }) { Text(stringResource(R.string.srt_close)) }
            },
        )
    }
    if (showRemove) {
        AlertDialog(
            onDismissRequest = { showRemove = false },
            containerColor = Morandi.panel,
            title = { Text(stringResource(R.string.srt_remove_title)) },
            text = { Text(stringResource(R.string.srt_remove_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showRemove = false
                    vm.animationRemoveStoryboardReference()
                }) { Text(stringResource(R.string.srt_remove_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showRemove = false }) { Text(stringResource(R.string.srt_close)) }
            },
        )
    }
}

@Composable
private fun StoryboardCueEditor(
    vm: PaintViewModel,
    index: Int,
    cueText: String,
    focusRequester: FocusRequester,
    autoFocus: Boolean,
    allowSplit: Boolean,
    onSplit: (String, String) -> Boolean,
    onAutoFocus: () -> Unit,
) {
    var value by remember(cueText) {
        mutableStateOf(
            TextFieldValue(cueText, selection = TextRange(if (autoFocus) 0 else cueText.length)),
        )
    }
    var hadFocus by remember { mutableStateOf(false) }
    var splitHandled by remember(cueText) { mutableStateOf(false) }

    fun commit() {
        if (splitHandled) return
        if (value.text != cueText && value.text.isNotBlank()) {
            vm.animationUpdateStoryboardCue(index, value.text)
        }
    }

    fun splitAt(left: String, right: String): Boolean {
        if (splitHandled) return true
        val handled = onSplit(left, right)
        if (handled) {
            splitHandled = true
            // 立刻把当前行同步为光标左侧文本, 避免随后失焦时把旧全文提交回去。
            value = TextFieldValue(left, selection = TextRange(left.length))
        }
        return handled
    }

    fun splitAtCaret(): Boolean {
        val caret = value.selection.start.coerceIn(0, value.text.length)
        return splitAt(value.text.substring(0, caret), value.text.substring(caret))
    }

    LaunchedEffect(autoFocus) {
        if (autoFocus) {
            value = value.copy(selection = TextRange(0))
            focusRequester.requestFocus()
            onAutoFocus()
        }
    }

    OutlinedTextField(
        value = value,
        onValueChange = { newValue ->
            // 用户重新输入 = 新的一次编辑, 允许失焦提交。
            splitHandled = false
            val newline = if (allowSplit) newValue.text.indexOf('\n') else -1
            if (newline >= 0 && splitAt(
                    newValue.text.substring(0, newline),
                    newValue.text.substring(newline + 1),
                )
            ) {
                // 已按换行位置拆帧; 不再把换行写回当前输入框。
            } else {
                value = newValue
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focusRequester)
            .onFocusChanged { focusState ->
                if (hadFocus && !focusState.isFocused) commit()
                hadFocus = focusState.isFocused
            }
            .onPreviewKeyEvent { event ->
                if (allowSplit && event.key == Key.Enter && event.type == KeyEventType.KeyDown) {
                    splitAtCaret()
                } else {
                    false
                }
            },
        minLines = 1,
        maxLines = 4,
        keyboardOptions = KeyboardOptions(imeAction = if (allowSplit) ImeAction.Done else ImeAction.Default),
        keyboardActions = KeyboardActions(
            onDone = {
                if (!allowSplit || !splitAtCaret()) commit()
            },
        ),
        textStyle = TextStyle(color = Morandi.text, fontSize = 14.sp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Morandi.text,
            unfocusedTextColor = Morandi.text,
            focusedBorderColor = Morandi.accent,
            unfocusedBorderColor = Morandi.border,
            focusedContainerColor = Morandi.panel,
            unfocusedContainerColor = Morandi.panel,
            cursorColor = Morandi.accent,
        ),
    )
}
