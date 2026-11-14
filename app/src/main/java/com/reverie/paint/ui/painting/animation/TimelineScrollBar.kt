/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.reverie.paint.ui.painting.animation

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.reverie.paint.R
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.core.playbackEndFrame
import com.reverie.paint.ui.theme.Morandi

/** Scrubs the viewport, not the playhead. Kept separate from frame/trim gestures. */
@Composable
internal fun TimelineScrollBar(vm: PaintViewModel, headerWidth: Dp) {
    var viewport by remember { mutableFloatStateOf(0f) }
    val frames = maxOf(vm.anim.length.toLong(), vm.playbackEndFrame().toLong() + 1, vm.anim.currentTime.toLong() + 1) + 8
    val maxScroll = (frames * vm.anim.frameWidthPx - viewport).coerceAtLeast(0f)
    val description = stringResource(R.string.anim_scroll_timeline)
    Slider(
        value = vm.anim.scrollPx.coerceIn(0f, maxScroll),
        onValueChange = { vm.anim.scrollPx = it },
        valueRange = 0f..maxScroll.coerceAtLeast(1f),
        enabled = maxScroll > 0f,
        colors = SliderDefaults.colors(
            thumbColor = Morandi.accent,
            activeTrackColor = Morandi.accent,
            inactiveTrackColor = Morandi.subText.copy(alpha = 0.25f),
        ),
        modifier = Modifier.fillMaxWidth().padding(start = headerWidth, end = 8.dp).height(32.dp)
            .onSizeChanged { viewport = it.width.toFloat() }
            .semantics { contentDescription = description },
    )
}
