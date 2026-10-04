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
import com.reverie.paint.core.BrushThumbCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

typealias BrushThumbCache = com.reverie.paint.core.BrushThumbCache

@Composable
fun rememberPresetThumb(name: String, bytes: ByteArray?): Bitmap? {
    if (name.isBlank()) return null

    var bmp by remember(name) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(name, bytes) {
        if (bytes != null && bytes.isNotEmpty() && BrushThumbCache.getFast(name) == null) {
            val decoded = withContext(Dispatchers.IO) {
                BrushThumbCache.get(name, bytes)
            }
            if (decoded != null) bmp = decoded
        }
    }

    if (bmp == null) {
        // 占位阶段订阅缓存版本: 缓存被面板级 preload 或其他窗口异步填充后,
        // 本卡片立即重组并命中新缓存, 修复"缩略图空白直到点击选中才刷新"
        // 的时序洞 (此前没有任何机制感知缓存变热, 重组只会由无关状态变化触发)。
        @Suppress("UNUSED_VARIABLE") val cacheVersion = BrushThumbCache.version
        return BrushThumbCache.getFast(name)
    }
    return bmp
}
