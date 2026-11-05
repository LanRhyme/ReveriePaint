/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.reverie.paint.ui.painting.animation

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reverie.paint.R
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.core.animationImportSrt
import com.reverie.paint.core.animationPause
import com.reverie.paint.core.animationSeek
import com.reverie.paint.model.SrtStoryboard
import com.reverie.paint.ui.components.ReChip
import com.reverie.paint.ui.theme.Morandi

@Composable
internal fun StoryboardImportButton(vm: PaintViewModel) {
    var confirm by remember { mutableStateOf(false) }
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
            title = { Text(stringResource(R.string.srt_import_title)) },
            text = { Text(stringResource(R.string.srt_import_description)) },
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
    val frames = state.frames
    val mappingMissing = state.singleFrameMode && frames.size != cues.size
    val active = SrtStoryboard.findActiveFrameIndex(frames, vm.anim.currentTime)
    val isFloating = vm.floatingUiConfig.enabled
    val shape = if (isFloating) RoundedCornerShape(14.dp) else RoundedCornerShape(0.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (isFloating) {
                    Modifier
                        .padding(bottom = 6.dp)
                        .shadow(6.dp, shape, spotColor = Color.Black.copy(alpha = 0.35f))
                        .clip(shape)
                } else {
                    Modifier
                },
            )
            .background(Morandi.panel)
            .clickable { showList = true }
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
            title = {
                Text(if (state.singleFrameMode) stringResource(R.string.srt_single_frame_title)
                    else stringResource(R.string.srt_list_title, state.fps))
            },
            text = {
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                    itemsIndexed(cues) { index, cue ->
                        val targetFrame = frames.getOrNull(index)
                        Column(
                            modifier = Modifier.fillMaxWidth().clickable(enabled = !mappingMissing && targetFrame != null) {
                                targetFrame?.let { frame ->
                                    vm.animationPause()
                                    vm.animationSeek(frame.start)
                                    vm.anim.scrollPx = frame.start * vm.anim.frameWidthPx
                                    vm.anim.toolbarExpanded = false
                                    showList = false
                                }
                            }.padding(vertical = 8.dp),
                        ) {
                            Text("${index + 1} · ${SrtStoryboard.timestamp(cue.startMs)} → " +
                                SrtStoryboard.timestamp(cue.endMs), fontSize = 11.sp, color = Morandi.subText)
                            Text(cue.text, color = Morandi.text, fontSize = 14.sp)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showList = false }) { Text(stringResource(R.string.srt_close)) }
            },
        )
    }
}
