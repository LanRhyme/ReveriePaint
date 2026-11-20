/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.animation

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.reverie.paint.R
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.core.animationMoveAudioClip
import com.reverie.paint.core.animationRemoveAudio
import com.reverie.paint.core.animationSeek
import com.reverie.paint.model.AnimationAudioClips
import com.reverie.paint.ui.theme.Morandi
import kotlin.math.roundToInt

@Composable
internal fun AudioReferenceBars(vm: PaintViewModel, headerWidth: androidx.compose.ui.unit.Dp) {
    val clips = vm.anim.audioClips
    if (clips.isEmpty()) return

    Column(modifier = Modifier.fillMaxWidth().padding(start = headerWidth, bottom = 3.dp)) {
        clips.forEach { clip ->
            var showRemove by remember(clip.name) { mutableStateOf(false) }
            val density = LocalDensity.current
            val frameWidth = vm.anim.frameWidthPx
            val widthPx = (clip.durationFrames * frameWidth).coerceAtLeast(with(density) { 60.dp.toPx() })
            val widthDp = with(density) { widthPx.toDp() }
            val xPx = clip.startFrame * frameWidth - vm.anim.scrollPx
            val label = clip.name.substringAfterLast('/').takeLast(24)

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(30.dp)
                    .clipToBounds()
                    .background(Morandi.panel),
            ) {
                Box(
                    modifier = Modifier
                        .offset { IntOffset(xPx.roundToInt(), 0) }
                        .width(widthDp)
                        .fillMaxHeight()
                        .padding(vertical = 2.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Morandi.accent.copy(alpha = 0.72f))
                        .combinedClickable(
                            onClick = { vm.animationSeek(clip.startFrame) },
                            onLongClick = { showRemove = true },
                        )
                        .pointerInput(vm, clip.name) {
                            var startAtDown = clip.startFrame
                            var currentStart = clip.startFrame
                            var accumulatedX = 0f
                            var dragged = false
                            detectDragGestures(
                                onDragStart = {
                                    startAtDown = vm.anim.audioClips
                                        .firstOrNull { it.name == clip.name }?.startFrame ?: clip.startFrame
                                    currentStart = startAtDown
                                    accumulatedX = 0f
                                    dragged = false
                                },
                                onDragEnd = {
                                    if (dragged) vm.animationMoveAudioClip(clip.name, currentStart)
                                },
                                onDragCancel = {
                                    if (dragged) vm.animationMoveAudioClip(clip.name, currentStart)
                                },
                            ) { change, dragAmount ->
                                change.consume()
                                accumulatedX += dragAmount.x
                                currentStart = (startAtDown + (accumulatedX / vm.anim.frameWidthPx).roundToInt())
                                    .coerceAtLeast(0)
                                dragged = true
                                vm.anim.audioClips = AnimationAudioClips.withStart(
                                    vm.anim.audioClips,
                                    clip.name,
                                    currentStart,
                                )
                            }
                        }
                        .padding(horizontal = 8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.anim_audio_bar_label, label),
                        color = Morandi.onAccent,
                        maxLines = 1,
                        modifier = Modifier.padding(vertical = 5.dp),
                    )
                }
            }

            if (showRemove) {
                AlertDialog(
                    onDismissRequest = { showRemove = false },
                    containerColor = Morandi.panel,
                    title = { Text(stringResource(R.string.anim_audio_remove_title)) },
                    text = { Text(stringResource(R.string.anim_audio_remove_message, label)) },
                    confirmButton = {
                        TextButton(onClick = {
                            showRemove = false
                            vm.animationRemoveAudio(clip.name)
                        }) { Text(stringResource(R.string.anim_audio_remove_confirm)) }
                    },
                    dismissButton = {
                        TextButton(onClick = { showRemove = false }) {
                            Text(stringResource(R.string.srt_close))
                        }
                    },
                )
            }
        }
    }
}
