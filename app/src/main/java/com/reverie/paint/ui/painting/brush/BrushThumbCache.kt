/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.brush

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.reverie.paint.core.BrushPresetInfo
import com.reverie.paint.core.BrushStrokePreviewCache
import com.reverie.paint.core.BrushThumbCache
import com.reverie.paint.core.PaintViewModel
import com.reverie.paint.core.loadOrRenderBrushStrokePreview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

typealias BrushThumbCache = com.reverie.paint.core.BrushThumbCache

@Composable
fun rememberPresetThumb(name: String, bytes: ByteArray?): Bitmap? {
    val cached = BrushThumbCache.getFast(name)
    if (cached != null) return cached

    var bmp by remember(name) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(name, bytes) {
        if (bmp == null && bytes != null && bytes.isNotEmpty()) {
            val decoded = withContext(Dispatchers.IO) {
                BrushThumbCache.get(name, bytes)
            }
            bmp = decoded
        }
    }
    return bmp
}

@Composable
fun rememberPresetStrokePreview(vm: PaintViewModel, preset: BrushPresetInfo): Bitmap? {
    val epoch = vm.brushModifiedEpoch
    val fast = BrushStrokePreviewCache.getFast(preset.id)
    var bmp by remember(preset.id) { mutableStateOf(fast) }
    if (fast != null && bmp !== fast) {
        bmp = fast
    }
    LaunchedEffect(preset.id, preset.index, epoch) {
        val currentFast = BrushStrokePreviewCache.getFast(preset.id)
        if (currentFast != null) {
            bmp = currentFast
        } else {
            bmp = vm.loadOrRenderBrushStrokePreview(preset.index, preset.id)
        }
    }
    return bmp
}
