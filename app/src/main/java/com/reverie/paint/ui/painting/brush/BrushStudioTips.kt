/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.brush

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import com.reverie.paint.ui.painting.TextInputGuard
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import com.reverie.paint.ui.theme.glassBorder
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.reverie.paint.ui.components.ReTextButton
import com.reverie.paint.R
import com.reverie.paint.core.*
import com.reverie.paint.ui.components.ReSlider
import com.reverie.paint.ui.components.ReSwitch
import com.reverie.paint.ui.components.ReIconButton
import com.reverie.paint.ui.components.noRippleClickable
import com.reverie.paint.ui.theme.Morandi
import com.reverie.paint.ui.theme.systemHoverIcon
import dev.chrisbanes.haze.HazeState
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

data class ScratchPoint(val x: Float, val y: Float, val pressure: Float)
data class BrushTipItem(val filename: String, val name: String, val isCustom: Boolean, val bitmap: Bitmap?)

internal object BrushTipDecoder {
    private val cache = object : android.util.LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /**
     * @param maxSize 目标最长边 (px)。笔尖库网格里一张只有 64dp, 让它按 2048px
     *                全尺寸解码纯属浪费；传 0 表示要原尺寸 (试画板要真实笔尖形状)。
     */
    fun loadTip(context: Context, filename: String, maxSize: Int = 0): Bitmap? {
        if (filename.isBlank()) return null
        val key = "$filename@$maxSize"
        cache.get(key)?.let { if (!it.isRecycled) return it }

        val bmp = runCatching {
            val internalFile = File(File(context.filesDir, "brushes"), filename)
            val stream = if (internalFile.exists()) {
                internalFile.inputStream()
            } else {
                context.assets.open("brushes/$filename")
            }
            stream.use { s ->
                if (filename.endsWith(".png", true) || filename.endsWith(".jpg", true) || filename.endsWith(".jpeg", true)) {
                    decodeScaled(s.readBytes(), maxSize)
                } else if (filename.endsWith(".gbr", true)) {
                    decodeGbr(s.readBytes(), maxSize)
                } else if (filename.endsWith(".gih", true)) {
                    decodeGih(s.readBytes(), maxSize)
                } else {
                    null
                }
            }
        }.getOrNull()
        if (bmp != null) cache.put(key, bmp)
        return bmp
    }

    /** [BitmapFactory] 两遍解码: 先量尺寸, 再按 inSampleSize 降采样。 */
    private fun decodeScaled(bytes: ByteArray, maxSize: Int): Bitmap? {
        if (maxSize <= 0) return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, maxSize)
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }

    private fun sampleSizeFor(width: Int, height: Int, maxSize: Int): Int {
        if (width <= 0 || height <= 0) return 1
        var sample = 1
        var longest = maxOf(width, height)
        while (longest / 2 >= maxSize) {
            longest /= 2
            sample *= 2
        }
        return sample
    }

    private fun decodeGbr(bytes: ByteArray, maxSize: Int = 0, base: Int = 0): Bitmap? {
        if (bytes.size - base < 28) return null
        val buf = ByteBuffer.wrap(bytes, base, bytes.size - base).order(ByteOrder.BIG_ENDIAN)
        val headerSize = buf.int
        val version = buf.int
        val width = buf.int
        val height = buf.int
        val bpp = buf.int
        if (width <= 0 || height <= 0 || width > 2048 || height > 2048) return null
        val offset = base + headerSize.coerceAtLeast(28)
        if (bytes.size < offset + width * height * bpp) return null

        // 网格里最多显示 64dp, 没有理由逐像素铺满 2048x2048; 按 step 抽样即可。
        val step = if (maxSize > 0) (maxOf(width, height) / maxSize).coerceAtLeast(1) else 1
        val dstW = (width + step - 1) / step
        val dstH = (height + step - 1) / step
        val pixels = IntArray(dstW * dstH)
        for (y in 0 until dstH) {
            val sy = (y * step).coerceAtMost(height - 1)
            for (x in 0 until dstW) {
                val sx = (x * step).coerceAtMost(width - 1)
                val src = offset + sy * width * bpp + sx * bpp
                pixels[y * dstW + x] = when (bpp) {
                    1 -> {
                        val a = 255 - (bytes[src].toInt() and 0xFF)
                        (a shl 24) or 0x00FFFFFF
                    }
                    4 -> {
                        val r = bytes[src].toInt() and 0xFF
                        val g = bytes[src + 1].toInt() and 0xFF
                        val b = bytes[src + 2].toInt() and 0xFF
                        val a = bytes[src + 3].toInt() and 0xFF
                        (a shl 24) or (r shl 16) or (g shl 8) or b
                    }
                    else -> 0
                }
            }
        }
        return Bitmap.createBitmap(pixels, dstW, dstH, Bitmap.Config.ARGB_8888)
    }

    private fun decodeGih(bytes: ByteArray, maxSize: Int = 0): Bitmap? {
        if (bytes.size < 64) return null
        for (i in 0 until bytes.size - 28) {
            if (bytes[i] == 0.toByte() && bytes[i + 1] == 0.toByte() && (bytes[i + 6] == 0.toByte() && bytes[i + 7] == 2.toByte())) {
                // 整包 copyOfRange 会为 19MB 的动画笔尖再复制一份; 直接带偏移解码
                return decodeGbr(bytes, maxSize, base = i)
            }
        }
        return null
    }
}

/**
 * 笔尖库清单构建 (IO 线程): 只产出文件名与显示名, 不碰像素。
 */
internal fun buildTipItems(context: Context): List<BrushTipItem> {
    val list = mutableListOf<BrushTipItem>()
    val customDir = File(context.filesDir, "brushes")
    if (customDir.exists()) {
        customDir.listFiles()?.forEach { f ->
            val name = f.name
            if (name.endsWith(".png", true) || name.endsWith(".jpg", true) ||
                name.endsWith(".gbr", true) || name.endsWith(".gih", true)
            ) {
                list.add(
                    BrushTipItem(
                        filename = name,
                        name = context.getString(R.string.brush_studio_tip_custom_prefix, name.substringBeforeLast(".")),
                        isCustom = true,
                        bitmap = null,
                    )
                )
            }
        }
    }
    val files = runCatching { context.assets.list("brushes")?.toList() }.getOrNull() ?: emptyList()
    files.sorted().forEach { f ->
        if (f.endsWith(".png", true) || f.endsWith(".gbr", true) || f.endsWith(".gih", true)) {
            if (list.none { it.filename == f }) {
                val cleanName = f.substringBeforeLast(".")
                    .replace("A_", "")
                    .replace("Z_", "")
                    .replace("P_", "")
                    .replace("M_", "")
                    .replace("_", " ")
                list.add(BrushTipItem(filename = f, name = cleanName, isCustom = false, bitmap = null))
            }
        }
    }
    return list
}

/**
 * 笔尖网格项的缩略图: 可见时才异步解码, 且按 192px 下采样。
 * 与 [com.reverie.paint.ui.painting.brush.rememberPresetThumb] 同一套模式 ——
 * 缓存命中时同步返回, 未命中后台解码后触发一次重组。
 */
@Composable
internal fun TipThumb(filename: String, contentDescription: String?) {
    val context = LocalContext.current
    if (filename.isBlank()) {
        Box(Modifier.size(24.dp).clip(CircleShape).background(Color.White))
        return
    }
    val cached = remember(filename) { BrushTipDecoder.loadTip(context, filename, TIP_THUMB_MAX) }
    var bmp by remember(filename) { mutableStateOf(cached) }
    LaunchedEffect(filename) {
        if (bmp == null) {
            bmp = withContext(Dispatchers.IO) { BrushTipDecoder.loadTip(context, filename, TIP_THUMB_MAX) }
        }
    }
    if (bmp != null) {
        Image(
            bitmap = bmp!!.asImageBitmap(),
            contentDescription = contentDescription,
            modifier = Modifier.fillMaxSize().padding(2.dp),
        )
    } else {
        Box(Modifier.size(24.dp).clip(CircleShape).background(Color.White))
    }
}
