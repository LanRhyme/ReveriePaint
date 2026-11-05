/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.zip.Inflater

/**
 * ABR 导入的笔尖在加载预设时解析不到(brushRESOLVED tip=0x0 valid=0 spacing=1.0,退化成 auto_brush 的点)的根因:
 * Krita 读 kpp 是 `QImageReader::text("preset")`,Qt 的 PNG 读取器把 tEXt/zTXt 当 Latin-1、只把 iTXt 当 UTF-8。
 * 旧写入器把 UTF-8 的 XML 写进 zTXt,文件名含中文(包名取自 abr 文件名,`铅笔质感勾线_tip_1.png`)时,
 * Krita 读到的是乱码,`KisPredefinedBrushFactory::createBrush -> bestMatch(md5, filename)` 按文件名匹配不上。
 * 这里用测试内的 Qt 读取语义复刻(tEXt/zTXt=Latin-1, iTXt=UTF-8),不依赖本地 KppHelper 的读取器。
 */
class KppPresetTextEncodingTest {
    private val cnTip = "铅笔质感勾线_tip_1.png"

    /** Qt6 QPngHandler 读 text chunk 的语义。 */
    private fun qtPresetText(png: ByteArray): String? {
        var i = 8
        while (i + 12 <= png.size) {
            val len = ByteBuffer.wrap(png, i, 4).int
            val type = String(png, i + 4, 4, Charsets.ISO_8859_1)
            val s = i + 8
            val e = s + len
            if (type == "tEXt" || type == "zTXt" || type == "iTXt") {
                var z = s
                while (z < e && png[z] != 0.toByte()) z++
                if (String(png, s, z - s, Charsets.ISO_8859_1) == "preset") {
                    return when (type) {
                        "tEXt" -> String(png, z + 1, e - z - 1, Charsets.ISO_8859_1)
                        "zTXt" -> String(inflate(png, z + 2, e), Charsets.ISO_8859_1)
                        else -> {
                            val compressed = png[z + 1].toInt() == 1
                            var p = z + 3
                            while (p < e && png[p] != 0.toByte()) p++
                            p++
                            while (p < e && png[p] != 0.toByte()) p++
                            p++
                            val raw = if (compressed) inflate(png, p, e) else png.copyOfRange(p, e)
                            String(raw, Charsets.UTF_8)
                        }
                    }
                }
            }
            i += 12 + len
        }
        return null
    }

    private fun inflate(b: ByteArray, from: Int, to: Int): ByteArray {
        val inf = Inflater()
        inf.setInput(b, from, to - from)
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (!inf.finished()) {
            val n = inf.inflate(buf)
            if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break
            out.write(buf, 0, n)
        }
        inf.end()
        return out.toByteArray()
    }

    @Test
    fun convertedKppKeepsNonAsciiTipFilenameForKrita() {
        val c = AbrPresetConverter.convert(
            AbrParser.AbrPresetInfo(name = "HB", diameter = 50.0),
            "HB",
            AbrParser.AbrDecodedTip(uuid = "u", index = 1, width = 2, height = 2, data = ByteArray(4) { 0x7f.toByte() }),
            cnTip,
            emptyMap(),
            "铅笔质感勾线",
        )
        val xml = qtPresetText(c.kppBytes)!!
        assertTrue(xml, xml.contains("filename=\"$cnTip\""))
        assertTrue(xml, xml.contains("<![CDATA[$cnTip]]>"))
    }

    @Test
    fun replacePresetXmlRoundTripsUtf8ThroughQtTextSemantics() {
        val base = KppHelper.replacePresetXml(
            AbrParser.encodePreviewPng(null, 8.0, 1.0),
            "<Preset name=\"x\"><param name=\"a\"><![CDATA[$cnTip]]></param></Preset>",
        )
        assertEquals("2.2", qtVersion(base))
        assertTrue(qtPresetText(base)!!.contains(cnTip))
        // 本地读取器同样要读得回
        assertTrue(KppHelper.readPresetXml(base)!!.contains(cnTip))
    }

    private fun qtVersion(png: ByteArray): String? {
        var i = 8
        while (i + 12 <= png.size) {
            val len = ByteBuffer.wrap(png, i, 4).int
            if (String(png, i + 4, 4, Charsets.ISO_8859_1) == "tEXt") {
                val s = i + 8
                var z = s
                while (png[z] != 0.toByte()) z++
                if (String(png, s, z - s, Charsets.ISO_8859_1) == "version") return String(png, z + 1, s + len - z - 1, Charsets.ISO_8859_1)
            }
            i += 12 + len
        }
        return null
    }
}
