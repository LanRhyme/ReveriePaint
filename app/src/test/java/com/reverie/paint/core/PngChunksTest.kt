/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * PNG 块的读写只留一个模块（PngChunks）。
 * 原先 KppHelper 的 readExistingVersion 只认 tEXt/iTXt，不认 zTXt：
 * 基底 PNG 的 version 在 zTXt 里时，重写 preset 会把版本悄悄降成默认 2.2。
 */
class PngChunksTest {
    private val sig = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    private fun chunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
        out.write(ByteBuffer.allocate(4).putInt(data.size).array())
        val t = type.toByteArray(Charsets.ISO_8859_1)
        out.write(t)
        out.write(data)
        val crc = CRC32().apply { update(t); update(data) }
        out.write(ByteBuffer.allocate(4).putInt(crc.value.toInt()).array())
    }

    private fun deflate(b: ByteArray): ByteArray {
        val d = Deflater()
        d.setInput(b)
        d.finish()
        val o = ByteArrayOutputStream()
        val buf = ByteArray(256)
        while (!d.finished()) o.write(buf, 0, d.deflate(buf))
        d.end()
        return o.toByteArray()
    }

    private fun idat(out: ByteArrayOutputStream) {
        val ihdr = ByteBuffer.allocate(13).putInt(1).putInt(1).put(8).put(0).put(0).put(0).put(0).array()
        chunk(out, "IHDR", ihdr)
    }

    private fun pngWith(textChunks: List<Pair<String, ByteArray>>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(sig)
        idat(out)
        textChunks.forEach { (t, d) -> chunk(out, t, d) }
        chunk(out, "IDAT", deflate(byteArrayOf(0, 0)))
        chunk(out, "IEND", ByteArray(0))
        return out.toByteArray()
    }

    private fun zText(keyword: String, value: String) =
        keyword.toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0, 0) + deflate(value.toByteArray(Charsets.ISO_8859_1))

    /** 测试内独立的读取（tEXt / iTXt），不借被测代码 */
    private fun versionOf(png: ByteArray): String? {
        var i = 8
        while (i + 12 <= png.size) {
            val len = ByteBuffer.wrap(png, i, 4).int
            val type = String(png, i + 4, 4, Charsets.ISO_8859_1)
            val s = i + 8
            val e = s + len
            if (type == "tEXt" || type == "zTXt") {
                var z = s
                while (z < e && png[z] != 0.toByte()) z++
                if (String(png, s, z - s, Charsets.ISO_8859_1) == "version") {
                    return if (type == "tEXt") String(png, z + 1, e - z - 1, Charsets.ISO_8859_1)
                    else {
                        val inf = Inflater()
                        inf.setInput(png, z + 2, e - z - 2)
                        val o = ByteArray(64)
                        val n = inf.inflate(o)
                        String(o, 0, n, Charsets.ISO_8859_1)
                    }
                }
            }
            i += 12 + len
        }
        return null
    }

    @Test
    fun `基底 PNG 的 version 在 zTXt 里 重写 preset 后版本不变`() {
        val base = pngWith(listOf("zTXt" to zText("version", "2.3")))
        val out = KppHelper.replacePresetXml(base, "<Preset name=\"x\"/>")
        assertEquals("2.3", versionOf(out))
        assertEquals("<Preset name=\"x\"/>", KppHelper.readPresetXml(out))
    }

    @Test
    fun `基底 PNG 没有 version 时写默认 2_2`() {
        val out = KppHelper.replacePresetXml(pngWith(emptyList()), "<Preset/>")
        assertEquals("2.2", versionOf(out))
    }

    @Test
    fun `readPresetXml 认 tEXt zTXt iTXt 三种 preset 块`() {
        val xml = "<Preset name=\"铅笔\"/>"
        val t = "preset".toByteArray() + byteArrayOf(0) + xml.toByteArray(Charsets.UTF_8)
        val z = "preset".toByteArray() + byteArrayOf(0, 0) + deflate(xml.toByteArray(Charsets.UTF_8))
        val i = "preset".toByteArray() + byteArrayOf(0, 1, 0, 0, 0) + deflate(xml.toByteArray(Charsets.UTF_8))
        val iPlain = "preset".toByteArray() + byteArrayOf(0, 0, 0, 0, 0) + xml.toByteArray(Charsets.UTF_8)
        for ((n, c) in listOf("tEXt" to t, "zTXt" to z, "iTXt" to i, "iTXt-plain" to iPlain)) {
            val png = pngWith(listOf(n.substringBefore('-') to c))
            assertEquals(n, xml, KppHelper.readPresetXml(png))
        }
        assertNull(KppHelper.readPresetXml(pngWith(emptyList())))
        assertNotNull(KppHelper.readPresetXml(KppHelper.replacePresetXml(pngWith(emptyList()), xml)))
    }
}
