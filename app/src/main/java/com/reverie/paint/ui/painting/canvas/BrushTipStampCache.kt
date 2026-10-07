/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.canvas

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 笔尖戳印位图缓存 (Brush Tip Stamp Cache)。
 *
 * 把笔刷 tip (assets/brushes/ 目录下的 png / gbr 文件) 解码为已按笔刷颜色着色的
 * ARGB_8888 戳印位图, 供前缓冲 STAMP 分级预览逐 dab 盖印。
 * 纯 Kotlin, 零 C++/JNI 改动。
 *
 * - PNG: BitmapFactory 直接解, 取亮度×alpha 为遮罩。
 * - GBR (GIMP brush): 自解析 (u32BE 头 + 名称 + 灰度像素), 白=不透明。
 * - GIH (动画笔尖) 及其他格式: 暂不支持 → 返回 null, 调用方降级。
 *
 * 线程: 方法全部 @Synchronized; 返回的 Bitmap 创建后不可变,
 * 前缓冲 GL 线程只读, 通过 packet inFlight CAS 建立 happens-before。
 */
class BrushTipStampCache(context: Context) {

    private val appContext = context.applicationContext

    private data class Key(val tipAsset: String, val colorRgb: Int)

    private val maskCache = LinkedHashMap<String, Bitmap>(8, 0.75f, true)
    private val cache = LinkedHashMap<Key, Bitmap>(8, 0.75f, true)
    private val maxEntries = 8

    /**
     * 取已着色戳印位图 (RGB=笔刷色, A=笔尖遮罩)。
     * tip 缺失/不可解码 → null, 调用方降级为发丝线。
     * 首次解码约数毫秒 (tip 通常 ≤256px), 之后 O(1) 命中。
     */
    @Synchronized
    fun get(tipAsset: String, color: Int): Bitmap? {
        if (tipAsset.isBlank()) return null
        val key = Key(tipAsset, color and 0x00FFFFFF)
        cache[key]?.let { bmp ->
            if (!bmp.isRecycled) return bmp
            cache.remove(key)
        }
        val mask = getOrDecodeMask(tipAsset) ?: return null
        val tinted = try {
            tintMask(mask, color)
        } catch (_: Throwable) {
            null
        } ?: return null
        if (cache.size >= maxEntries) {
            // 只摘除引用不 recycle: GL 线程可能仍有在途 packet 引用旧位图;
            // API 26+ 像素内存走 Java 堆, GC 负责回收, 无需手动 recycle。
            cache.keys.firstOrNull()?.let { oldest -> cache.remove(oldest) }
        }
        cache[key] = tinted
        return tinted
    }

    private fun getOrDecodeMask(tipAsset: String): Bitmap? {
        maskCache[tipAsset]?.let { bmp ->
            if (!bmp.isRecycled) return bmp
            maskCache.remove(tipAsset)
        }
        val decoded = try {
            decodeMask(tipAsset)
        } catch (_: Throwable) {
            null
        } ?: return null
        if (maskCache.size >= maxEntries) {
            maskCache.keys.firstOrNull()?.let { oldest -> maskCache.remove(oldest) }
        }
        maskCache[tipAsset] = decoded
        return decoded
    }

    @Synchronized
    fun clear() {
        // 不 recycle 只清引用: 防 GL 线程在途绘制野指针; GC 负责回收。
        cache.clear()
        maskCache.clear()
    }

    private fun decodeMask(tipAsset: String): Bitmap? {
        appContext.assets.open("brushes/$tipAsset").use { ins ->
            val bytes = ins.readBytes()
            if (bytes.isEmpty()) return null
            return when {
                tipAsset.endsWith(".png", ignoreCase = true) -> pngToMask(bytes)
                tipAsset.endsWith(".gbr", ignoreCase = true) -> gbrToMask(bytes)
                else -> null
            }
        }
    }

    /** PNG tip → ALPHA_8 遮罩 (遮罩值 = 亮度 × alpha)。 */
    private fun pngToMask(bytes: ByteArray): Bitmap? {
        val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val src = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
        try {
            val w = src.width
            val h = src.height
            if (w <= 0 || h <= 0 || w > 1024 || h > 1024) return null
            val px = IntArray(w * h)
            src.getPixels(px, 0, w, 0, 0, w, h)
            val mask = ByteArray(w * h)
            for (i in px.indices) {
                val p = px[i]
                val a = (p ushr 24) and 0xFF
                val r = (p ushr 16) and 0xFF
                val g = (p ushr 8) and 0xFF
                val b = p and 0xFF
                val lum = (r * 77 + g * 150 + b * 29) ushr 8
                mask[i] = ((lum * a) / 255).toByte()
            }
            // 全透明 tip 无意义, 视为不可用
            if (mask.all { it == 0.toByte() }) return null
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
            out.copyPixelsFromBuffer(ByteBuffer.wrap(mask))
            return out
        } finally {
            try { src.recycle() } catch (_: Throwable) {}
        }
    }

    /**
     * GBR (GIMP brush) → ALPHA_8 遮罩。
     * 头: width u32BE, height u32BE, bpp u32BE, magic u32BE (不强校验),
     * 名称 NUL 结尾, 像素: w*h*bpp 字节 (bpp=1 灰度, =2 灰度+alpha)。
     */
    private fun gbrToMask(bytes: ByteArray): Bitmap? {
        if (bytes.size < 24) return null
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val w = buf.int
        val h = buf.int
        val bpp = buf.int
        buf.int // magic, 不强校验以兼容变体
        if (w <= 0 || h <= 0 || w > 1024 || h > 1024) return null
        if (bpp != 1 && bpp != 2) return null
        var p = 16
        while (p < bytes.size && bytes[p] != 0.toByte()) p++
        p++ // 跳过 NUL
        if (p < 0 || p > bytes.size - w * h * bpp) return null
        val mask = ByteArray(w * h)
        for (i in 0 until w * h) {
            val g = bytes[p + i * bpp].toInt() and 0xFF
            val a = if (bpp == 2) bytes[p + i * bpp + 1].toInt() and 0xFF else 255
            mask[i] = ((g * a) / 255).toByte()
        }
        if (mask.all { it == 0.toByte() }) return null
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
        out.copyPixelsFromBuffer(ByteBuffer.wrap(mask))
        return out
    }

    /** ALPHA_8 遮罩 × 笔刷颜色 → ARGB_8888 戳印位图。 */
    private fun tintMask(mask: Bitmap, color: Int): Bitmap {
        val w = mask.width
        val h = mask.height
        val rgb = color and 0x00FFFFFF
        val mpx = ByteArray(w * h)
        mask.copyPixelsToBuffer(ByteBuffer.wrap(mpx))
        val out = IntArray(w * h)
        for (i in out.indices) {
            val a = mpx[i].toInt() and 0xFF
            out[i] = (a shl 24) or rgb
        }
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.setPixels(out, 0, w, 0, 0, w, h)
        // 遮罩在 maskCache 中长久复用, 绝不能 recycle
        return bmp
    }
}
