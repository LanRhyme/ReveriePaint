/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * 守护间距写回语义的回归。
 *
 * `SpacingValue` 不是笔刷间距，而是 KisSpacingOption 的 **extraScale 乘数**
 * (Krita 原生预设里恒为 1)。历史实现把它写成笔刷间距，于是每保存一次参数
 * 就把有效间距再乘一次自身：spacing=0.24 的预设 -> 0.24 x 0.24 = 0.058，
 * 笔画退化成点状，即用户反馈的"间距明显不对"。
 *
 * 笔刷间距本体只能写进 brush_definition 的 `spacing=` 属性，引擎从那里读。
 * 该约束与 `ReverieCore::setBrushSpacing` 里的同名约束必须同时成立。
 */
class KppSpacingWriteTest {

    private val pngHeader = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    )

    private fun chunk(type: String, data: ByteArray): ByteArray {
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

    private fun buildKpp(xml: String): ByteArray {
        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        deflater.setInput(xml.toByteArray(Charsets.UTF_8))
        deflater.finish()
        val payload = ByteArrayOutputStream().also { bos ->
            val buf = ByteArray(4096)
            while (!deflater.finished()) bos.write(buf, 0, deflater.deflate(buf))
        }.toByteArray()
        deflater.end()
        val data = ByteArrayOutputStream().apply {
            write("preset".toByteArray(Charsets.ISO_8859_1))
            write(0) // keyword 终止符
            write(0) // 压缩方式 = deflate
            write(payload)
        }.toByteArray()
        val out = ByteArrayOutputStream()
        out.write(pngHeader)
        out.write(chunk("IHDR", ByteArray(13)))
        out.write(chunk("zTXt", data))
        out.write(chunk("IEND", ByteArray(0)))
        return out.toByteArray()
    }

    private fun params(spacing: Double) = BrushParams(
        size = 20.0,
        opacity = 1.0,
        flow = 1.0,
        spacing = spacing,
        isCustomized = true,
        spacingCustomized = true,
    )

    private fun sourceXml() = """
        <paintop preset="probe" paintop="paintbrush">
          <params>
            <param name="brush_definition" type="string"><![CDATA[<Brush scale="1" type="gbr_brush" useAutoSpacing="0" BrushVersion="2" filename="tip.gbr" spacing="0.24" angle="0.0"/>]]></param>
            <param name="Spacing" type="internal">0.24</param>
            <param name="SpacingValue" type="internal">1</param>
            <param name="PressureSpacing" type="internal">false</param>
          </params>
        </paintop>
    """.trimIndent()

    @Test
    fun `写回不得改动 SpacingValue 乘数`() {
        val out = KppHelper.updateKppBytes(buildKpp(sourceXml()), "probe", params(0.35))
        val xml = KppHelper.readPresetXml(out)!!
        val m = Regex("""<param name="SpacingValue"[^>]*>([^<]*)<""").find(xml)
        assertTrue("SpacingValue 必须仍存在", m != null)
        assertEquals("SpacingValue 是 extraScale 乘数, 必须保持 1", "1", m!!.groupValues[1])
    }

    @Test
    fun `笔刷间距写进 brush_definition 的 spacing 属性`() {
        val out = KppHelper.updateKppBytes(buildKpp(sourceXml()), "probe", params(0.35))
        val xml = KppHelper.readPresetXml(out)!!
        assertTrue("brush_definition 的 spacing 应更新为 0.35", xml.contains("""spacing="0.35""""))
        val spacingParam = Regex("""<param[^>]*name="Spacing"[^>]*>(?:<!\[CDATA\[)?([^<\]]*)""").find(xml)
        assertTrue("同名 Spacing 参数键应存在", spacingParam != null)
        assertEquals("0.35", spacingParam!!.groupValues[1])
    }

    @Test
    fun `连续保存不会让间距乘数漂移`() {
        var bytes = buildKpp(sourceXml())
        repeat(5) { bytes = KppHelper.updateKppBytes(bytes, "probe", params(0.4)) }
        val xml = KppHelper.readPresetXml(bytes)!!
        assertTrue(xml.contains("""spacing="0.4""""))
        val m = Regex("""<param name="SpacingValue"[^>]*>([^<]*)<""").find(xml)
        assertEquals("反复保存后乘数仍应为 1", "1", m!!.groupValues[1])
    }
}
