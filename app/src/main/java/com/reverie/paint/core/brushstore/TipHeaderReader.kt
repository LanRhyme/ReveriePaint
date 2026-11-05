/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.reverie.paint.core.brushstore

/**
 * 笔尖文件头解析（纯 Kotlin，无 Android 依赖）：只读位图宽高，供 writeTipAsset 把自包含尺寸
 * （width/height）写进 brush_definition，使 kpp 不再依赖引擎侧解析文件才能报告大小。
 *
 * - PNG：8 字节魔数后第一个 chunk 必是 IHDR，宽高在文件 16..23 字节（各 4 字节大端 u32）
 * - GBR：GBR v2 头 —— header_size(0..3)、version(4..7)、width(8..11)、height(12..15)、
 *   bytes-per-pixel(16..19)、magic "GIMP"(20..23)；读前核对 version>=2 与 magic（与
 *   kis_gbr_brush.cpp 的加载顺序一致；真实 .gbr 以 header_size 开头，没有 "gbr" 前缀魔数）
 * - GIH：文本头 —— 第 1 行名字，第 2 行是 "<ncells> ncells:N cellwidth:W cellheight:H …"
 *   寄生串（KisPipeBrushParasite），宽高从 key:value 对读取；兼容旧格式第 3 行前两个整数
 *   依次为 cell width / height 的写法
 *
 * 任何异常 / 不识别的格式 / 疑似损坏的头都返回 null。
 */
object TipHeaderReader {

    /** (宽, 高)；读不出返回 null */
    fun readDimensions(bytes: ByteArray): Pair<Int, Int>? = try {
        when {
            isPng(bytes) -> pngDimensions(bytes)
            isGbr(bytes) -> gbrDimensions(bytes)
            isGih(bytes) -> gihDimensions(bytes)
            else -> null
        }
    } catch (_: Exception) {
        null
    }?.takeIf { (w, h) -> w > 0 && h > 0 }

    private fun isPng(b: ByteArray) = b.size >= 8 &&
        b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() && b[2] == 'N'.code.toByte() && b[3] == 'G'.code.toByte() &&
        b[4] == 0x0D.toByte() && b[5] == 0x0A.toByte() && b[6] == 0x1A.toByte() && b[7] == 0x0A.toByte()

    private fun isGbr(b: ByteArray) = b.size >= 28 &&
        u32(b, 4) != null && (u32(b, 4) ?: 0) >= 2 &&
        b[20] == 'G'.code.toByte() && b[21] == 'I'.code.toByte() &&
        b[22] == 'M'.code.toByte() && b[23] == 'P'.code.toByte()

    private fun isGih(b: ByteArray) = b.size > 4

    private fun pngDimensions(b: ByteArray): Pair<Int, Int>? {
        // 签名后第一个 chunk 必是 IHDR：长度(8..11) + 类型串(12..15) + 宽(16..19) + 高(20..23)
        if (b.size < 24) return null
        if (b[12] != 'I'.code.toByte() || b[13] != 'H'.code.toByte() ||
            b[14] != 'D'.code.toByte() || b[15] != 'R'.code.toByte()
        ) return null
        val w = u32(b, 16) ?: return null
        val h = u32(b, 20) ?: return null
        return w to h
    }

    private fun gbrDimensions(b: ByteArray): Pair<Int, Int>? {
        val w = u32(b, 8) ?: return null
        val h = u32(b, 12) ?: return null
        return w to h
    }

    /** GIH：第 2 行寄生串里的 cellwidth:/cellheight:；旧格式回退第 3 行前两个整数 */
    private fun gihDimensions(b: ByteArray): Pair<Int, Int>? {
        val textEnd = minOf(b.size, 4096)
        val head = String(b, 0, textEnd, Charsets.ISO_8859_1)
        val lines = head.split('\n')
        if (lines.size < 2) return null
        var w: Int? = null
        var h: Int? = null
        for (token in lines[1].trim().split(Regex("\\s+"))) {
            val i = token.indexOf(':')
            if (i <= 0) continue
            val v = token.substring(i + 1).trim().toIntOrNull() ?: continue
            when (token.substring(0, i).lowercase()) {
                "cellwidth" -> w = v
                "cellheight" -> h = v
            }
        }
        if (w != null && h != null) return w to h
        // 旧格式：第 3 行 "cellwidth cellheight spacing …"
        if (lines.size >= 3) {
            val nums = lines[2].trim().split(Regex("\\s+")).mapNotNull { it.toIntOrNull() }
            if (nums.size >= 2) return nums[0] to nums[1]
        }
        return null
    }

    private fun u32(b: ByteArray, o: Int): Int? {
        if (o < 0 || o + 4 > b.size) return null
        val v = ((b[o].toLong() and 0xFF) shl 24) or ((b[o + 1].toLong() and 0xFF) shl 16) or
            ((b[o + 2].toLong() and 0xFF) shl 8) or (b[o + 3].toLong() and 0xFF)
        return if (v > Int.MAX_VALUE) null else v.toInt()
    }
}
