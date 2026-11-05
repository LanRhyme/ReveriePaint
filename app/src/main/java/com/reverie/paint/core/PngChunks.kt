/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * PNG 块的读写，全 App 只此一份（原先 AbrParser 与 KppHelper 各写一套 writeChunk，
 * KppHelper 里读 preset / 读 version 又是三份各不相同的 tEXt/zTXt/iTXt 解析）。
 *
 * 读：[readText] 同时认 tEXt、zTXt、iTXt（压缩与不压缩）。
 * 写：文本块写 iTXt/UTF-8（[itxt]）——Krita 经 Qt 读 preset，Qt 把 tEXt/zTXt 当 Latin-1、
 * 只把 iTXt 当 UTF-8，中文文件名写进 zTXt 会读成乱码（见 KppHelper.replacePresetXml 的说明）。
 */
internal object PngChunks {
    val SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    /** 一个块在字节数组里的位置：[start,end) 含长度、类型、数据、CRC；数据区 [dataStart,dataEnd)。 */
    class Chunk(val type: String, val start: Int, val dataStart: Int, val dataEnd: Int, val end: Int)

    fun isPng(png: ByteArray): Boolean {
        if (png.size < 8) return false
        for (i in 0 until 8) if (png[i] != SIGNATURE[i]) return false
        return true
    }

    /**
     * 从第 9 个字节起逐块遍历；块被截断或长度损坏就停。
     * 长度用 Long 累加：损坏文件的 length 可以是 0x7FFFFFFF，Int 相加会溢出成负数、绕过越界判断。
     */
    fun chunks(png: ByteArray): Sequence<Chunk> = sequence {
        var idx = 8
        while (idx + 12 <= png.size) {
            val length = ByteBuffer.wrap(png, idx, 4).order(ByteOrder.BIG_ENDIAN).int
            val dataStart = idx + 8
            val dataEnd = dataStart.toLong() + length.toLong()
            if (length < 0 || dataEnd + 4 > png.size.toLong()) break
            val type = String(png, idx + 4, 4, Charsets.ISO_8859_1)
            val end = dataEnd.toInt() + 4
            yield(Chunk(type, idx, dataStart, dataEnd.toInt(), end))
            idx = end
        }
    }

    fun isText(c: Chunk) = c.type == "tEXt" || c.type == "zTXt" || c.type == "iTXt"

    /** 文本块的关键字（0x00 结尾）；不是文本块或没有结尾返回 null。 */
    fun keyword(png: ByteArray, c: Chunk): String? {
        if (!isText(c)) return null
        val z = nul(png, c.dataStart, c.dataEnd)
        return if (z >= c.dataEnd) null else String(png, c.dataStart, z - c.dataStart, Charsets.ISO_8859_1)
    }

    /**
     * 第一个关键字为 [keyword] 且能解出内容的 tEXt/zTXt/iTXt 块的内容字节（已解压）。
     * 不做字符集转换：调用方按需要用 UTF-8 / Latin-1 解码。读不出来的块跳过，继续往后找。
     */
    fun readText(png: ByteArray, keyword: String): ByteArray? {
        if (!isPng(png)) return null
        for (c in chunks(png)) {
            if (keyword(png, c) != keyword) continue
            val z = nul(png, c.dataStart, c.dataEnd)
            val v: ByteArray? = when (c.type) {
                "tEXt" -> png.copyOfRange(z + 1, c.dataEnd)
                "zTXt" ->
                    if (z + 2 <= c.dataEnd && png[z + 1].toInt() == 0) inflate(png, z + 2, c.dataEnd) else null
                else -> itxtValue(png, z, c.dataEnd)
            }
            if (v != null) return v
        }
        return null
    }

    private fun itxtValue(png: ByteArray, z: Int, end: Int): ByteArray? {
        if (z + 2 >= end) return null
        val compFlag = png[z + 1].toInt()
        val compMethod = png[z + 2].toInt()
        var p = nul(png, z + 3, end) + 1 // language tag
        p = nul(png, p, end) + 1 // translated keyword
        if (p > end) return null
        return when {
            compFlag == 1 && compMethod == 0 -> inflate(png, p, end)
            compFlag == 0 -> png.copyOfRange(p, end)
            else -> null
        }
    }

    private fun nul(b: ByteArray, from: Int, to: Int): Int {
        var p = from
        while (p < to && b[p] != 0.toByte()) p++
        return p
    }

    fun inflate(src: ByteArray, from: Int, to: Int): ByteArray {
        val inflater = Inflater(false)
        inflater.setInput(src, from, to - from)
        val bos = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (!inflater.finished() && !inflater.needsInput()) {
            val n = inflater.inflate(buf)
            if (n > 0) bos.write(buf, 0, n) else break
        }
        inflater.end()
        return bos.toByteArray()
    }

    fun deflate(data: ByteArray, level: Int = Deflater.DEFAULT_COMPRESSION): ByteArray {
        val d = Deflater(level)
        d.setInput(data)
        d.finish()
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (!d.finished()) {
            val n = d.deflate(buf)
            if (n > 0) out.write(buf, 0, n)
        }
        d.end()
        return out.toByteArray()
    }

    /** 完整一个块：长度 + 类型 + 数据 + CRC32(类型 + 数据)。 */
    fun write(out: ByteArrayOutputStream, type: String, data: ByteArray) {
        out.write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(data.size).array())
        val t = type.toByteArray(Charsets.ISO_8859_1)
        out.write(t)
        out.write(data)
        val crc = CRC32().apply { update(t); update(data) }
        out.write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(crc.value.toInt()).array())
    }

    /** 待写入的新块。 */
    class NewChunk(val type: String, val data: ByteArray)

    /** tEXt 块：关键字 + 0x00 + 内容（Latin-1 文本，如 version）。 */
    fun text(keyword: String, value: ByteArray) =
        NewChunk("tEXt", keyword.toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0) + value)

    /** iTXt 块：关键字、压缩标志 1、方法 0、空语言、空译名，然后是压缩后的 UTF-8 文本。 */
    fun itxt(keyword: String, text: String) =
        NewChunk("iTXt", keyword.toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0, 1, 0, 0, 0) + deflate(text.toByteArray(Charsets.UTF_8)))

    /**
     * 丢掉所有关键字在 [drop] 里的文本块，把 [add] 写在第一个 IDAT 之前（没有图像数据就写在 IEND 之前），
     * 其余块原样保留。位置是硬约束：Krita 在 reader.read() 之前就调 text("preset")，
     * 此时 Qt 的 QPngHandler 只暴露 IDAT 之前的文本块；写在 IDAT 之后等于没写。
     */
    fun replaceTexts(png: ByteArray, drop: Set<String>, add: List<NewChunk>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(SIGNATURE)
        var added = false
        for (c in chunks(png)) {
            if (keyword(png, c) in drop) continue
            if (!added && (c.type == "IDAT" || c.type == "IEND")) {
                add.forEach { write(out, it.type, it.data) }
                added = true
            }
            out.write(png, c.start, c.end - c.start)
        }
        // 截断到既没 IDAT 也没 IEND 的 PNG：补在最后
        if (!added) add.forEach { write(out, it.type, it.data) }
        return out.toByteArray()
    }

    /**
     * 8 位 PNG：[colorType] 0=灰度 / 6=RGBA，[pixels] 是紧排的行（不含滤波字节）。
     * 每行加滤波字节 0，BEST_SPEED 压缩。像素不足的部分补 0。
     */
    fun encodeImage(width: Int, height: Int, colorType: Int, pixels: ByteArray): ByteArray {
        val bpp = if (colorType == 6) 4 else 1
        val rowSize = width * bpp
        val raw = ByteArray(height * (rowSize + 1))
        for (y in 0 until height) {
            val n = minOf(rowSize, pixels.size - y * rowSize)
            if (n > 0) System.arraycopy(pixels, y * rowSize, raw, y * (rowSize + 1) + 1, n)
        }
        val ihdr = ByteBuffer.allocate(13).order(ByteOrder.BIG_ENDIAN)
            .putInt(width).putInt(height).put(8).put(colorType.toByte()).put(0).put(0).put(0).array()
        val out = ByteArrayOutputStream()
        out.write(SIGNATURE)
        write(out, "IHDR", ihdr)
        write(out, "IDAT", deflate(raw, Deflater.BEST_SPEED))
        write(out, "IEND", ByteArray(0))
        return out.toByteArray()
    }
}
