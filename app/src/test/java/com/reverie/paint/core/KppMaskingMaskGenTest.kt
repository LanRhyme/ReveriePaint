/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 守护蒙版子预设的 MaskGenerator 存在性。
 *
 * 用户反馈"部分参数修改后没什么变化", 根因之一: 选了**自定义蒙版笔尖**时,
 * `injectParamsIntoXml` 生成的 `MaskingBrush/Preset/brush_definition` 是一个
 * 自闭合的 `<Brush ... />`, 没有任何 MaskGenerator —— 蒙版淡出/柔和没有落盘
 * 位置, 保存后被重载打回原值, 表现为滑块拖了没变化。
 *
 * 不带自定义笔尖的分支本来就生成 auto_brush + MaskGenerator, 所以同两个滑块
 * 在那种情况下是好的 —— 这正是"部分参数"只在一部分预设上失灵的原因。
 */
class KppMaskingMaskGenTest {

    private val pngHeader = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    )

    private fun chunk(type: String, data: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(java.nio.ByteBuffer.allocate(4).order(java.nio.ByteOrder.BIG_ENDIAN).putInt(data.size).array())
        val t = type.toByteArray(Charsets.ISO_8859_1)
        out.write(t)
        out.write(data)
        val crc = java.util.zip.CRC32()
        crc.update(t)
        crc.update(data)
        out.write(java.nio.ByteBuffer.allocate(4).order(java.nio.ByteOrder.BIG_ENDIAN).putInt(crc.value.toInt()).array())
        return out.toByteArray()
    }

    private fun buildKpp(xml: String): ByteArray {
        val deflater = java.util.zip.Deflater(java.util.zip.Deflater.BEST_COMPRESSION)
        deflater.setInput(xml.toByteArray(Charsets.UTF_8))
        deflater.finish()
        val payload = java.io.ByteArrayOutputStream().also { bos ->
            val buf = ByteArray(4096)
            while (!deflater.finished()) bos.write(buf, 0, deflater.deflate(buf))
        }.toByteArray()
        deflater.end()
        val data = java.io.ByteArrayOutputStream().apply {
            write("preset".toByteArray(Charsets.ISO_8859_1))
            write(0) // keyword 终止符
            write(0) // 压缩方式 = deflate
            write(payload)
        }.toByteArray()
        val out = java.io.ByteArrayOutputStream()
        out.write(pngHeader)
        out.write(chunk("IHDR", ByteArray(13)))
        out.write(chunk("zTXt", data))
        out.write(chunk("IEND", ByteArray(0)))
        return out.toByteArray()
    }

    private fun kppWithMaskingPreset(definition: String): ByteArray {
        // 根标签必须是 <Preset> —— updateParam 在键不存在时是往 </Preset> 前追加的
        val xml = """
            <Preset name="probe" paintopid="paintbrush">
              <params>
                <param name="brush_definition" type="string"><![CDATA[<Brush scale="1" type="auto_brush" BrushVersion="2" spacing="0.1" angle="0.0"> <MaskGenerator diameter="20.0" hfade="1.0" vfade="1.0" id="default" spikes="2" type="circle" ratio="1.0" antialiasEdges="1"/> </Brush>]]></param>
                $definition
              </params>
            </Preset>
        """.trimIndent()
        return buildKpp(xml)
    }

    private fun params(maskingTip: String, fade: Double) = BrushParams(
        size = 20.0,
        opacity = 1.0,
        flow = 1.0,
        spacing = 0.1,
        isCustomized = true,
        maskingEnabled = true,
        maskingTipAsset = maskingTip,
        maskingFade = fade,
        maskingSpacing = 0.1,
        maskingSizeRatio = 1.0,
    )

    @Test
    fun `自定义蒙版笔尖也必须带 MaskGenerator 以承载淡出`() {
        val out = KppHelper.updateKppBytes(
            kppWithMaskingPreset(
                """<param name="MaskingBrush/Enabled" type="bool">true</param>"""
            ),
            "probe",
            params("mask_tip.gbr", 0.35),
        )
        val xml = KppHelper.readPresetXml(out)!!
        val maskingDef = Regex(
            """<param[^>]*name="MaskingBrush/Preset/brush_definition"[^>]*>(?:<!\[CDATA\[)?(.*?)(?:\]\]>)?\s*</param>""",
            RegexOption.DOT_MATCHES_ALL,
        ).find(xml)?.groupValues?.getOrNull(1)
        assertTrue("蒙版子预设 brush_definition 应存在", maskingDef != null)
        assertTrue("自定义蒙版笔尖也必须带 MaskGenerator", maskingDef!!.contains("<MaskGenerator"))
        assertTrue("蒙版淡出应写入 hfade", maskingDef.contains("""hfade="0.35"""))
    }

    @Test
    fun `无自定义蒙版笔尖的 auto_brush 分支行为不变`() {
        val out = KppHelper.updateKppBytes(
            kppWithMaskingPreset(
                """<param name="MaskingBrush/Enabled" type="bool">true</param>"""
            ),
            "probe",
            params("", 0.62),
        )
        val xml = KppHelper.readPresetXml(out)!!
        val maskingDef = Regex(
            """<param[^>]*name="MaskingBrush/Preset/brush_definition"[^>]*>(?:<!\[CDATA\[)?(.*?)(?:\]\]>)?\s*</param>""",
            RegexOption.DOT_MATCHES_ALL,
        ).find(xml)?.groupValues?.getOrNull(1)
        assertTrue(maskingDef!!.contains("type=\"auto_brush\""))
        assertTrue(maskingDef.contains("""hfade="0.62"""))
    }
}
