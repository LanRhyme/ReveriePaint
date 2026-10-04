/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * 守护"巨型内嵌资源预设"的解析路径。
 *
 * Krita 允许把笔尖与贴图直接内嵌进 .kpp 的 preset 块。"memileo Impasto" 就是这种
 * 笔刷：单个 .kpp 10.5 MB，解压后 preset XML 达 27 MB，其中 24.5 MB 是多帧 .gih
 * 动画笔尖的 base64、1.9 MB 是贴图 PNG，真正能被 [KppHelper.parseKppAttributes]
 * 读到的设置只有 ~12 KB。
 *
 * 旧实现把整份 XML 拉成 String 再跑 40 多条正则，而 [com.reverie.paint.core.PaintViewModelBrush]
 * 的选中笔刷路径是在**主线程**调它的 —— 用户侧表现是"点这个笔刷就卡死、内存飙高"，
 * 而其他几十 KB 的普通笔刷只是"卡一会"。这里钉死两件事：
 *  1. 剥离内嵌 payload 后属性读取结果必须与完整解析**完全一致**（不能为了快而丢设置）；
 *  2. 写回路径（dedupe / updateKppFile）必须仍拿到完整 XML，否则会把笔尖从预设里抹掉。
 */
class KppEmbeddedResourceStripTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val pngHeader = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    )

    private fun writeChunk(type: String, data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(data.size).array())
        val t = type.toByteArray(Charsets.ISO_8859_1)
        out.write(t)
        out.write(data)
        val crc = CRC32()
        crc.update(t)
        crc.update(data)
        out.write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(crc.value.toInt()).array())
        return out.toByteArray()
    }

    /** iTXt (压缩) 文本块，Krita 5.x 写预设用的就是这一种。 */
    private fun itxtPresetChunk(xml: String): ByteArray {
        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        deflater.setInput(xml.toByteArray(Charsets.UTF_8))
        deflater.finish()
        val bos = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (!deflater.finished()) {
            bos.write(buf, 0, deflater.deflate(buf))
        }
        deflater.end()
        val payload = bos.toByteArray()
        val data = ByteArrayOutputStream()
        data.write("preset".toByteArray(Charsets.ISO_8859_1))
        data.write(0)
        data.write(1) // compressed
        data.write(0) // deflate
        data.write(0) // empty language tag + 0x00
        data.write(0) // empty translated keyword + 0x00
        data.write(payload)
        return writeChunk("iTXt", data.toByteArray())
    }

    /** 造一个"内嵌 2 MB 笔尖 + 正常设置"的预设 XML。 */
    private fun presetXml(payloadSize: Int = 2 * 1024 * 1024): String {
        val filler = "A".repeat(payloadSize)
        return buildString {
            append("<paintop preset=\"test\" paintop=\"paintbrush\">")
            append("<resource name=\"heavy_tip\" type=\"brushes\" ")
            append("filename=\"heavy_tip.gih\">")
            append("<![CDATA[").append(filler).append("]]>")
            append("</resource>")
            append("<resource name=\"heavy_tex\" type=\"patterns\" ")
            append("filename=\"heavy_tex.png\">")
            append("<![CDATA[").append("B".repeat(4096)).append("]]>")
            append("</resource>")
            append("<params>")
            append("<param name=\"brush_definition\" type=\"string\"><![CDATA[")
            append("<brush_definition width=\"200\" height=\"200\" spacing=\"0.3\" ")
            append("filename=\"heavy_tip.gih\"><MaskGenerator type=\"circle\" ")
            append("hfade=\"0.85\" vfade=\"0.85\" ratio=\"0.42\" spikes=\"3\" ")
            append("antialiasEdges=\"1\"/></brush_definition>")
            append("]]></param>")
            append("<param name=\"SoftnessValue\" type=\"range\"><![CDATA[0.37]]></param>")
            append("<param name=\"Texture/Pattern/Enabled\" type=\"bool\">true</param>")
            append("<param name=\"Texture/Pattern/Scale\" type=\"double\"><![CDATA[0.5]]></param>")
            append("</params>")
            append("</paintop>")
        }
    }

    private fun buildKpp(xml: String): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(pngHeader)
        out.write(writeChunk("IHDR", ByteArray(13)))
        out.write(itxtPresetChunk(xml))
        out.write(writeChunk("IEND", ByteArray(0)))
        return out.toByteArray()
    }

    @Test
    fun `剥离内嵌 payload 后体积降到可忽略且属性读取结果不变`() {
        val kpp = buildKpp(presetXml())
        val full = KppHelper.readPresetXml(kpp)
        val stripped = KppHelper.readPresetXml(kpp, stripEmbeddedResources = true)
        assertNotNull(full)
        assertNotNull(stripped)

        // 完整解析必须确实很大（证明这条用例覆盖了真实场景）
        assertTrue("完整 XML 应大于 2 MB，实际 ${full!!.length}", full.length > 2 * 1024 * 1024)
        // 剥离后只剩设置本身
        assertTrue(
            "剥离后应小于 16 KB，实际 ${stripped!!.length}",
            stripped.length < 16 * 1024
        )
        assertFalse("剥离后不应残留 payload 主体", stripped.contains("AAAAAAAAAA"))

        // 元素本身与 filename= 必须保留（extractTipAssetFilename 依赖它）
        assertTrue(stripped.contains("filename=\"heavy_tip.gih\""))
        assertEquals("heavy_tip.gih", KppHelper.extractTipAssetFilename(kpp))

        // 属性读取结果必须一致
        val a = KppHelper.parseKppAttributes(full)
        val b = KppHelper.parseKppAttributes(stripped)
        assertEquals(a.softness, b.softness)
        assertEquals(0.37, b.softness!!, 1e-9)
        assertEquals(0.85, b.fade!!, 1e-9)
        assertEquals(0.42, b.ratio!!, 1e-9)
        assertEquals(3, b.spikes)
        assertEquals(true, b.textureEnabled)
        assertEquals(0.5, b.textureScale!!, 1e-9)
    }

    @Test
    fun `写回路径默认拿到完整 XML 不受剥离影响`() {
        val kpp = buildKpp(presetXml(64 * 1024))
        val full = KppHelper.readPresetXml(kpp)!!
        assertTrue("写回路径必须保留内嵌笔尖数据", full.contains("AAAAAAAAAA"))
    }

    @Test
    fun `parseKppFile 结果按文件版本缓存且改文件后失效`() {
        val f = tmp.newFile("heavy.kpp")
        f.writeBytes(buildKpp(presetXml(64 * 1024)))

        val first = KppHelper.parseKppFile(f)
        assertEquals(0.37, first.softness!!, 1e-9)
        // 同一文件版本：直接命中缓存（同一实例）
        assertTrue("未变更文件应命中缓存", first === KppHelper.parseKppFile(f))

        // 改内容 + 改 mtime：缓存必须失效并重新解析
        f.writeBytes(buildKpp(presetXml(64 * 1024).replace("0.37", "0.91")))
        f.setLastModified(System.currentTimeMillis() + 2000)
        val third = KppHelper.parseKppFile(f)
        assertEquals(0.91, third.softness!!, 1e-9)
    }

    @Test
    fun `非 PNG 与缺块输入保持原有降级行为`() {
        assertNull(KppHelper.readPresetXml(ByteArray(4)))
        assertNull(KppHelper.readPresetXml(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9)))
        val empty = KppHelper.parseKppFile(File(tmp.root, "does-not-exist.kpp"))
        assertNull(empty.softness)
    }
}
