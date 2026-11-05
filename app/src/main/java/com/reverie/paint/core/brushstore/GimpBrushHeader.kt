/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.brushstore

/**
 * GIMP 笔刷头（.gbr；.gih 是文本头 + 若干个串联的 .gbr）里的间距。
 *
 * Krita 依据：`libs/brush/kis_gbr_brush.cpp` —— 头部 `spacing`（u32 大端，单位 %，0..1000）；
 * v1 文件没有这个字段，用默认值；超过 1000 视为损坏；加载后 `setSpacing(bh.spacing / 100.0)`。
 * Krita 在"笔尖选择"里换笔尖时沿用笔刷自带的间距；RP 以前导入 .gbr/.gih 直接套 Basic-1 的间距，
 * 笔刷作者设定的间距被丢掉。
 */
object GimpBrushHeader {
    private const val MAGIC = 0x47494D50 // 'GIMP'

    /** 笔刷自带间距（比例，1.0 = 100%）；认不出或没有该字段返回 null（调用方保持基底预设的间距） */
    fun spacing(bytes: ByteArray, filename: String): Double? {
        val start = if (filename.endsWith(".gih", ignoreCase = true)) gihFirstBrushOffset(bytes) ?: return null else 0
        return gbrSpacing(bytes, start)
    }

    private fun u32(b: ByteArray, o: Int): Long? =
        if (o < 0 || o + 4 > b.size) null
        else ((b[o].toLong() and 0xFF) shl 24) or ((b[o + 1].toLong() and 0xFF) shl 16) or ((b[o + 2].toLong() and 0xFF) shl 8) or (b[o + 3].toLong() and 0xFF)

    /** 头：header_size, version, width, height, bytes, magic, spacing（version 1 没有 magic/spacing）*/
    private fun gbrSpacing(b: ByteArray, o: Int): Double? {
        val version = u32(b, o + 4) ?: return null
        if (version < 2) return null
        if (u32(b, o + 20) != MAGIC.toLong()) return null
        val sp = u32(b, o + 24) ?: return null
        if (sp > 1000) return null
        return sp / 100.0
    }

    /** .gih：第 1 行名字，第 2 行笔刷数（+ 参数串）；其后紧跟第一个 .gbr */
    private fun gihFirstBrushOffset(b: ByteArray): Int? {
        var nl = 0
        var i = 0
        while (i < b.size && nl < 2) {
            if (b[i] == '\n'.code.toByte()) nl++
            i++
        }
        return if (nl == 2 && i < b.size) i else null
    }
}
