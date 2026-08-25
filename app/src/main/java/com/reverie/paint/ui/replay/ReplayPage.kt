/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.replay

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reverie.paint.R
import com.reverie.paint.ui.components.ReFab
import com.reverie.paint.ui.components.ReIconButton
import com.reverie.paint.core.*
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.ui.theme.Theme
import java.util.Locale

/**
 * Replay page: plays back the recorded drawing process on a read-only
 * canvas. The engine (C++ document + render pipeline) is reused as-is; the
 * page only renders vm.displayBitmap and drives playback via the ViewModel.
 */
@Composable
fun ReplayPage(vm: PaintViewModel) {
    val colors = Theme.current
    var canvasW by remember { mutableStateOf(1) }
    var canvasH by remember { mutableStateOf(1) }
    val s = vm.replaySession
    // While the user drags the slider we show the thumb position; -1 means
    // "follow playback"
    var dragProgress by remember { mutableFloatStateOf(-1f) }

    LaunchedEffect(canvasW, canvasH) {
        if (canvasW > 0 && canvasH > 0) {
            vm.setRenderViewport(canvasW, canvasH)
        }
    }

    BackHandler { vm.exitReplay() }

    Box(Modifier.fillMaxSize().background(colors.bg)) {
        Column(Modifier.fillMaxSize()) {
            // ---- Top bar ----
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .background(colors.panel)
                        .padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ReIconButton(R.drawable.ic_arrow_left, "返回", { vm.exitReplay() }, tint = colors.text)
                Text(
                    text = if (vm.docName.isNotBlank()) "回放 · ${vm.docName}" else "回放",
                    color = colors.text,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0.5f, 1f, 2f, 4f).forEach { sp ->
                        val selected = s != null && s.speed == sp
                        Text(
                            text = if (sp >= 1f) "${sp.toInt()}x" else "0.5x",
                            color = if (selected) colors.onAccent else colors.text,
                            fontSize = 12.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            modifier =
                                Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (selected) colors.selStroke else colors.panelHi)
                                    .clickable { vm.setReplaySpeed(sp) }
                                    .padding(horizontal = 9.dp, vertical = 5.dp),
                        )
                    }
                }
                Spacer(Modifier.width(4.dp))
            }

            // ---- Canvas ----
            Box(
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(colors.canvasBg)
                        .onSizeChanged {
                            canvasW = it.width
                            canvasH = it.height
                        },
                contentAlignment = Alignment.Center,
            ) {
                val bmp = vm.displayBitmap
                val rev = vm.displayRevision
                val ib = remember(bmp, rev) { bmp?.asImageBitmap() }
                if (ib != null) {
                    Image(
                        bitmap = ib,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .padding(10.dp),
                    )
                } else {
                    Text("正在准备画布...", color = colors.subText, fontSize = 14.sp)
                }

                if (s != null && s.isPlaying) {
                    Row(
                        modifier =
                            Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 14.dp)
                                .clip(RoundedCornerShape(20.dp))
                                .background(Color.Black.copy(alpha = 0.55f))
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFFF6B6B)),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("回放中", color = Color.White, fontSize = 12.sp)
                    }
                }
            }

            // ---- Player controls ----
            if (s != null) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .background(colors.panel)
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ReFab(
                            icon = if (s.isPlaying) R.drawable.ic_pause else R.drawable.ic_play,
                            desc = if (s.isPlaying) "暂停" else "播放",
                            onTap = { if (s.isPlaying) vm.pauseReplay() else vm.playReplay() },
                            sizeDp = 54.dp,
                        )
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Slider(
                                value = if (dragProgress >= 0f) dragProgress else s.progress,
                                onValueChange = { dragProgress = it },
                                onValueChangeFinished = {
                                    vm.seekReplay(dragProgress)
                                    dragProgress = -1f
                                },
                                valueRange = 0f..1f,
                                colors =
                                    SliderDefaults.colors(
                                        thumbColor = colors.accent,
                                        activeTrackColor = colors.text.copy(alpha = 0.85f),
                                        inactiveTrackColor = colors.panelHi,
                                    ),
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(formatTime(s.elapsedMs), color = colors.subText, fontSize = 12.sp)
                                Text(formatTime(s.totalMs), color = colors.subText, fontSize = 12.sp)
                            }
                        }
                        Spacer(Modifier.width(10.dp))
                        ReIconButton(R.drawable.ic_refresh, "从头播放", { vm.seekReplay(0f) }, tint = colors.text)
                    }
                }
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    val totalSec = (ms / 1000L).coerceAtLeast(0L)
    val m = totalSec / 60L
    val s = totalSec % 60L
    return String.format(Locale.US, "%02d:%02d", m, s)
}
