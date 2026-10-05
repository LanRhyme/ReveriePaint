/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 守护动力学曲线的落盘闭环。
 *
 * 背景: `updateBrushDynamicOption` 原先只写内存 map + 下发引擎, **不调 saveBrushParam**,
 * 而 kpp 写入侧也没有任何曲线输出 (`Curveh`/`Curves` 是硬编码恒等曲线)。于是工作台
 * 画出来的曲线切笔刷/重启即丢, 等于白做。
 *
 * Krita 把曲线存在 `<Key>Sensor` param 的 XML 里, 由 `<Key>UseCurve` 开关启用:
 * `<param name="SizeSensor" type="string"><!DOCTYPE params><params id="pressure">
 *  <curve>0,0;0.5,0.7;1,1;</curve></params></param>`
 */
class KppSensorCurveRoundTripTest {

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
            write(0); write(0)
            write(payload)
        }.toByteArray()
        val out = java.io.ByteArrayOutputStream()
        out.write(pngHeader)
        out.write(chunk("IHDR", ByteArray(13)))
        out.write(chunk("zTXt", data))
        out.write(chunk("IEND", ByteArray(0)))
        return out.toByteArray()
    }

    private val sourceXml = """
        <Preset name="probe" paintopid="paintbrush">
          <params>
            <param name="brush_definition" type="string"><![CDATA[<Brush scale="1" type="auto_brush" BrushVersion="2" spacing="0.1" angle="0.0"> <MaskGenerator diameter="20.0" hfade="1.0" vfade="1.0" id="default" spikes="2" type="circle" ratio="1.0" antialiasEdges="1"/> </Brush>]]></param>
          </params>
        </Preset>
    """.trimIndent()

    private val curveXml =
        "<!DOCTYPE params><params id=\"pressure\"><curve>0.000,0.000;0.500,0.700;1.000,1.000;</curve></params>"

    @Test
    fun `曲线写进 kpp 后能原样读回`() {
        val out = KppHelper.updateKppBytes(
            buildKpp(sourceXml),
            "probe",
            BrushParams(size = 20.0, opacity = 1.0, flow = 1.0, spacing = 0.1, isCustomized = true)
                .copy(dynamicOptions = mapOf("Size" to curveXml)),
        )
        val parsed = KppHelper.parseKppAttributes(KppHelper.readPresetXml(out)!!)

        val body = parsed.sensorXml["Size"]
        assertTrue("SizeSensor 应被写进 kpp 并读回", body != null)
        assertTrue("曲线内容应包含中间控制点", body!!.contains("0.500,0.700"))
        assertTrue("传感器 id 应保留", body.contains("""id="pressure""""))
    }

    @Test
    fun `未提供曲线时不写入自定义曲线`() {
        val out = KppHelper.updateKppBytes(
            buildKpp(sourceXml),
            "probe",
            BrushParams(size = 20.0, opacity = 1.0, flow = 1.0, spacing = 0.1, isCustomized = true),
        )
        val parsed = KppHelper.parseKppAttributes(KppHelper.readPresetXml(out)!!)
        // 注意: 解析出的 sensorXml 不一定为空 —— injectParamsIntoXml 本来就会写
        // SizeSensor/OpacitySensor 等键(用于引擎侧传感器选择)。这里要验的是
        // "没提供曲线时不会把自定义曲线写进去"。
        assertTrue(
            "未提供曲线时不应出现自定义控制点",
            parsed.sensorXml.values.none { it.contains("0.500,0.700") }
        )
    }

    @Test
    fun `解析出的控制点可还原为 Krita 曲线串`() {
        val out = KppHelper.updateKppBytes(
            buildKpp(sourceXml),
            "probe",
            BrushParams(size = 20.0, opacity = 1.0, flow = 1.0, spacing = 0.1, isCustomized = true)
                .copy(dynamicOptions = mapOf("Size" to curveXml)),
        )
        val parsed = KppHelper.parseKppAttributes(KppHelper.readPresetXml(out)!!)
        val curve = Regex("""<curve>([^<]*)</curve>""").find(parsed.sensorXml["Size"]!!)!!.groupValues[1]
        val points = com.reverie.paint.model.DynamicOptionConfig.parseKritaCurve(curve)
        assertEquals(3, points.size)
        assertEquals(0.5f, points[1].x, 1e-4f)
        assertEquals(0.7f, points[1].y, 1e-4f)
    }
}
