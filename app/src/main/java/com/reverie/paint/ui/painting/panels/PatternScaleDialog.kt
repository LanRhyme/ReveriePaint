/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.reverie.paint.ui.painting.panels

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.reverie.paint.R
import com.reverie.paint.core.FillPattern
import com.reverie.paint.model.PatternScale
import com.reverie.paint.ui.theme.Morandi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@Composable
internal fun PatternScaleDialog(
    pattern: FillPattern, busy: Boolean, failed: Boolean,
    onCancel: () -> Unit, onApply: (Float, Boolean) -> Unit,
) {
    var percent by remember(pattern) { mutableFloatStateOf(100f) }
    var smooth by remember(pattern) { mutableStateOf(false) }
    // Preview the imported pixels instead of enlarging the library's 128 px thumbnail.
    val preview by produceState<ImageBitmap?>(null, pattern) {
        value = withContext(Dispatchers.IO) {
            BitmapFactory.decodeByteArray(pattern.png, 0, pattern.png.size)?.asImageBitmap()
        }
    }
    val (width, height) = PatternScale.size(pattern.width, pattern.height, percent)
    AlertDialog(
        onDismissRequest = { if (!busy) onCancel() },
        containerColor = Morandi.panel,
        titleContentColor = Morandi.text,
        textContentColor = Morandi.subText,
        title = { Text(stringResource(R.string.pattern_scale_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Image(preview ?: pattern.thumbnail.asImageBitmap(), contentDescription = null,
                    modifier = Modifier.fillMaxWidth().height(96.dp),
                    filterQuality = if (smooth) FilterQuality.Low else FilterQuality.None)
                Text(stringResource(R.string.pattern_scale_value, percent.roundToInt(), width, height))
                Slider(value = percent, onValueChange = { percent = it }, enabled = !busy,
                    valueRange = PatternScale.MIN_PERCENT..PatternScale.maxPercent(pattern.width, pattern.height),
                    colors = SliderDefaults.colors(thumbColor = Morandi.accent,
                        activeTrackColor = Morandi.accent, inactiveTrackColor = Morandi.panelHi))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(false, true).forEach { filtered ->
                        FilterChip(selected = smooth == filtered, onClick = { smooth = filtered }, enabled = !busy,
                            label = { Text(stringResource(if (filtered) R.string.pattern_scale_smooth
                                else R.string.pattern_scale_crisp)) },
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = Morandi.panel, labelColor = Morandi.text,
                                selectedContainerColor = Morandi.accent, selectedLabelColor = Morandi.onAccent))
                    }
                }
                Text(stringResource(if (smooth) R.string.pattern_scale_smooth_hint else R.string.pattern_scale_crisp_hint))
                Text(stringResource(R.string.pattern_scale_hint))
                TextButton(onClick = { percent = 100f }, enabled = !busy) {
                    Text(stringResource(R.string.common_reset), color = Morandi.accent)
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Morandi.accent)
                if (failed) Text(stringResource(R.string.pattern_scale_failed), color = Morandi.text)
            }
        },
        confirmButton = {
            TextButton(onClick = { onApply(percent, smooth) }, enabled = !busy) {
                Text(stringResource(R.string.common_apply), color = Morandi.accent)
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel, enabled = !busy) {
                Text(stringResource(R.string.common_cancel), color = Morandi.text)
            }
        },
    )
}
