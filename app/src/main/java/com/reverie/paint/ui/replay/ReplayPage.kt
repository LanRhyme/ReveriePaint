/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.replay

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.reverie.paint.R
import com.reverie.paint.core.*
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.ui.components.ReFab
import com.reverie.paint.ui.components.ReIconButton
import com.reverie.paint.ui.components.ReTextButton
import com.reverie.paint.ui.theme.AppColors
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
    var showCustomSpeedDialog by remember { mutableStateOf(false) }

    val presetSpeeds = remember { listOf(0.5f, 1f, 2f, 4f, 8f, 16f, 32f) }

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
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(8.dp))
                Row(
                    modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    presetSpeeds.forEach { sp ->
                        val selected = s != null && s.speed == sp
                        Text(
                            text = if (sp >= 1f) "${sp.toInt()}x" else "0.5x",
                            color = if (selected) colors.onAccent else colors.text,
                            fontSize = 12.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            modifier =
                                Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (selected) colors.accent else colors.panelHi)
                                    .clickable { vm.setReplaySpeed(sp) }
                                    .padding(horizontal = 8.dp, vertical = 5.dp),
                        )
                    }

                    val isCustom = s != null && s.speed !in presetSpeeds
                    val customLabel = if (isCustom && s != null) {
                        val v = s.speed
                        if (v == v.toInt().toFloat()) "${v.toInt()}x" else "${v}x"
                    } else {
                        "自定义"
                    }
                    Text(
                        text = customLabel,
                        color = if (isCustom) colors.onAccent else colors.text,
                        fontSize = 12.sp,
                        fontWeight = if (isCustom) FontWeight.Bold else FontWeight.Normal,
                        modifier =
                            Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (isCustom) colors.accent else colors.panelHi)
                                .clickable { showCustomSpeedDialog = true }
                                .padding(horizontal = 8.dp, vertical = 5.dp),
                    )
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
                                        activeTrackColor = colors.accent,
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

    if (showCustomSpeedDialog) {
        CustomSpeedDialog(
            colors = colors,
            initialSpeed = s?.speed ?: 1f,
            onConfirm = { speed ->
                vm.setReplaySpeed(speed)
                showCustomSpeedDialog = false
            },
            onDismiss = { showCustomSpeedDialog = false },
        )
    }
}

@Composable
private fun CustomSpeedDialog(
    colors: AppColors,
    initialSpeed: Float,
    onConfirm: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    var textValue by remember {
        mutableStateOf(
            if (initialSpeed == initialSpeed.toInt().toFloat()) {
                initialSpeed.toInt().toString()
            } else {
                initialSpeed.toString()
            }
        )
    }
    var sliderValue by remember { mutableFloatStateOf(initialSpeed.coerceIn(0.1f, 128f)) }

    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier =
                Modifier
                    .width(320.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(colors.panel)
                    .border(1.dp, colors.border, RoundedCornerShape(16.dp))
                    .padding(22.dp),
        ) {
            Column {
                Text(
                    text = "自定义播放倍速",
                    color = colors.text,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(14.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(colors.panelHi)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    BasicTextField(
                        value = textValue,
                        onValueChange = { str ->
                            textValue = str
                            str.toFloatOrNull()?.let {
                                if (it in 0.1f..128f) sliderValue = it
                            }
                        },
                        textStyle =
                            TextStyle(
                                color = colors.text,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Medium,
                            ),
                        cursorBrush = SolidColor(colors.accent),
                        singleLine = true,
                        keyboardOptions =
                            KeyboardOptions(
                                keyboardType = KeyboardType.Decimal,
                            ),
                        modifier = Modifier.weight(1f),
                    )
                    Text("x", color = colors.subText, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }

                Spacer(Modifier.height(14.dp))

                Slider(
                    value = sliderValue.coerceIn(0.5f, 64f),
                    onValueChange = {
                        sliderValue = it
                        textValue =
                            if (it >= 10f) {
                                it.toInt().toString()
                            } else {
                                String.format(Locale.US, "%.1f", it)
                            }
                    },
                    valueRange = 0.5f..64f,
                    colors =
                        SliderDefaults.colors(
                            thumbColor = colors.accent,
                            activeTrackColor = colors.accent,
                            inactiveTrackColor = colors.panelHi,
                        ),
                )

                Spacer(Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    listOf(6f, 10f, 20f, 48f, 64f, 100f).forEach { qp ->
                        Text(
                            text = "${qp.toInt()}x",
                            color = colors.subText,
                            fontSize = 12.sp,
                            modifier =
                                Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(colors.panelHi)
                                    .clickable {
                                        sliderValue = qp
                                        textValue = qp.toInt().toString()
                                    }
                                    .padding(horizontal = 6.dp, vertical = 3.dp),
                        )
                    }
                }

                Spacer(Modifier.height(20.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    ReTextButton(text = "取消", onClick = onDismiss)
                    Spacer(Modifier.width(10.dp))
                    ReTextButton(
                        text = "确定",
                        primary = true,
                        onClick = {
                            val v = textValue.toFloatOrNull() ?: sliderValue
                            onConfirm(v.coerceIn(0.1f, 128f))
                        },
                    )
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
