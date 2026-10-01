/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 守护 .kpp 的 PNG 文本块布局。
 *
 * Krita 的 KisPaintOpPreset::loadFromDevice 在 reader.read() **之前** 调
 * text("version") / text("preset")；Qt 的 QPngHandler 在那个时点只暴露位于
 * 第一个 IDAT **之前** 的文本块 —— 写在 IDAT 之后就等于没写。
 *
 * 一旦这里失守，表现是：导入当次笔刷正常（参数直接作用于内存里的 preset），
 * 但**重启后**引擎从 .kpp 读不到 preset，回退 auto_brush，所有笔刷变成圆球。
 * 这个回归真实发生过，所以用测试钉死，不再靠人工回忆。
 */
class KppPresetChunkTest {

    /** 顺序列出 (chunk 类型, 起始偏移)。 */
    private fun chunkList(png: ByteArray): List<Pair<String, Int>> {
        val out = mutableListOf<Pair<String, Int>>()
        var i = 8
        while (i + 8 <= png.size) {
            val len = ((png[i].toInt() and 0xFF) shl 24) or
                ((png[i + 1].toInt() and 0xFF) shl 16) or
                ((png[i + 2].toInt() and 0xFF) shl 8) or
                (png[i + 3].toInt() and 0xFF)
            if (len < 0 || i + 12 + len > png.size) break
            out.add(String(png, i + 4, 4, Charsets.ISO_8859_1) to i)
            i += 12 + len
        }
        return out
    }

    /** 取文本块的关键字（keyword 以 0x00 结尾）。 */
    private fun textKeyword(png: ByteArray, at: Int): String {
        val len = ((png[at].toInt() and 0xFF) shl 24) or ((png[at + 1].toInt() and 0xFF) shl 16) or
            ((png[at + 2].toInt() and 0xFF) shl 8) or (png[at + 3].toInt() and 0xFF)
        var end = at + 8
        val limit = at + 8 + len
        while (end < limit && png[end] != 0.toByte()) end++
        return String(png, at + 8, end - at - 8, Charsets.ISO_8859_1)
    }

    private fun textChunkOffset(png: ByteArray, keyword: String): Int? =
        chunkList(png).firstOrNull {
            (it.first == "tEXt" || it.first == "zTXt") && textKeyword(png, it.second) == keyword
        }?.second

    /** 模拟导入链路：用 encodePreviewPng 生成的新 PNG 作底图（这正是出问题的场景）。 */
    private fun freshPreviewPng(): ByteArray {
        val tip = AbrParser.AbrDecodedTip(
            uuid = "9f74ac31-602c-11e0-a1b2-0002a5d5c51b",
            index = 1,
            width = 64,
            height = 64,
            depth = 8,
            data = ByteArray(64 * 64) { i ->
                val dx = (i % 64) - 32
                val dy = (i / 64) - 32
                if (dx * dx + dy * dy < 24 * 24) 0xFF.toByte() else 0
            },
        )
        return AbrParser.encodePreviewPng(tip, diameter = 30.0, roundness = 1.0)
    }

    private fun buildKpp(): ByteArray =
        KppHelper.updateKppBytes(
            freshPreviewPng(),
            "回归用预设",
            BrushParams(tipAsset = "t_tip_1.png"),
        )

    @Test
    fun `preset 文本块必须排在第一个 IDAT 之前`() {
        val kpp = buildKpp()
        val idatAt = chunkList(kpp).firstOrNull { it.first == "IDAT" }?.second
        val presetAt = textChunkOffset(kpp, "preset")

        assertNotNull("生成的 .kpp 必须含 IDAT", idatAt)
        assertNotNull("生成的 .kpp 必须含 preset 文本块", presetAt)
        assertTrue(
            "preset 文本块位于 offset $presetAt，晚于第一个 IDAT($idatAt)。" +
                "Qt 的 QPngHandler 只暴露 pre-IDAT 文本块，Krita 读不到 —— 重启后笔刷会全变圆球",
            presetAt!! < idatAt!!,
        )
    }

    @Test
    fun `version 文本块必须存在且排在第一个 IDAT 之前`() {
        val kpp = buildKpp()
        val idatAt = chunkList(kpp).firstOrNull { it.first == "IDAT" }?.second
        val versionAt = textChunkOffset(kpp, "version")

        assertNotNull("生成的 .kpp 必须含 IDAT", idatAt)
        assertNotNull(
            "生成的 .kpp 缺少 version 文本块 —— Krita 用它决定 preset XML 的 schema 版本",
            versionAt,
        )
        assertTrue("version 文本块必须排在第一个 IDAT 之前", versionAt!! < idatAt!!)
    }

    @Test
    fun `写入的 brush_definition 指向导入的笔尖文件名`() {
        val xml = KppHelper.readPresetXml(buildKpp())
        assertNotNull("preset 内容必须可读回", xml)
        assertTrue(
            "XML 必须带 brush_definition，否则引擎回退 auto_brush",
            xml!!.contains("brush_definition"),
        )
        assertTrue(
            "brush_definition 的 filename 必须与落盘的笔尖文件名一致",
            xml.contains("t_tip_1.png"),
        )
    }

    /** 取文本块的文本值（tEXt 形如 keyword\\0value）。 */
    private fun textValue(png: ByteArray, keyword: String): String? {
        val at = textChunkOffset(png, keyword) ?: return null
        val len = ((png[at].toInt() and 0xFF) shl 24) or ((png[at + 1].toInt() and 0xFF) shl 16) or
            ((png[at + 2].toInt() and 0xFF) shl 8) or (png[at + 3].toInt() and 0xFF)
        val start = at + 8
        val end = start + len
        var p = start
        while (p < end && png[p] != 0.toByte()) p++
        if (p >= end) return null
        return String(png, p + 1, end - p - 1, Charsets.ISO_8859_1)
    }

    @Test
    fun `用真实 kpp 作底图时沿用原有 version 值且不破坏块顺序`() {
        val asset = java.io.File("src/main/assets/paintoppresets/b)_Basic-5_Size_default.kpp")
        if (!asset.exists()) return // 与仓库里其他依赖资源文件的用例保持同样的宽容度

        val kpp = KppHelper.updateKppBytes(
            asset.readBytes(),
            "回归用预设",
            BrushParams(tipAsset = "t_tip_1.png"),
        )
        val chunks = chunkList(kpp)
        val idatAt = chunks.firstOrNull { it.first == "IDAT" }?.second
        val presetAt = textChunkOffset(kpp, "preset")
        val versionAt = textChunkOffset(kpp, "version")

        assertNotNull("preset 块必须存在", presetAt)
        assertNotNull("version 块必须存在", versionAt)
        assertTrue("preset 必须在第一个 IDAT 之前", presetAt!! < idatAt!!)
        assertTrue("version 必须在第一个 IDAT 之前", versionAt!! < idatAt!!)
        assertEquals("底图已有的 version 值必须原样沿用，不能擅自改写", "2.2", textValue(kpp, "version"))
        assertEquals(
            "version 块必须只有一个（不能旧块保留、新块又补一个）",
            1,
            chunks.count { it.first == "tEXt" && textKeyword(kpp, it.second) == "version" },
        )
    }

    @Test
    fun `chunk 长度声明为超大值时不得抛异常`() {
        // 损坏/被截断的 .kpp 可以把某个 chunk 的长度声明成 0x7FFFFFFF。
        // 若用 Int 累加做越界判断，`chunkDataStart + length` 会溢出成负数，
        // `> size` 判断随之失效，紧接着 out.write(bytes, off, 12 + length)
        // 也是一个负数长度 —— 直接抛 IndexOutOfBoundsException。
        // 这条路径在导入用户从网上下载的合集时会真的走到，所以必须安全。
        val header = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        )
        val bogus = header +
            byteArrayOf(0x7F, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()) + // length = 0x7FFFFFFF
            "tEXt".toByteArray(Charsets.ISO_8859_1) +
            byteArrayOf(0x00) + // 只有 1 字节真实数据，后面全是补位
            ByteArray(8) { 0x41 }

        val kpp = KppHelper.updateKppBytes(bogus, "损坏底图", BrushParams(tipAsset = "t_tip_1.png"))

        assertNotNull("损坏底图也必须能写出 preset，不能抛异常", KppHelper.readPresetXml(kpp))
        assertTrue(
            "损坏底图也要能读回 brush_definition",
            KppHelper.readPresetXml(kpp)!!.contains("brush_definition"),
        )
    }

    // ------------------------------------------------------------------
    // 笔刷尺寸：scale 是引擎唯一认的尺寸来源
    //
    // Krita libs/brush/kis_scaling_size_brush.cpp:
    //   qreal KisScalingSizeBrush::userEffectiveSize() const
    //   { return qMax(this->width(), this->height()) * this->scale(); }
    // 而 KisBrushBasedPaintOpSettings::paintOpSize() 返回的就是它 —— preset XML 里的
    // paintopSize 键对它完全无效。所以导入 ABR 时必须把
    // `ABR 声明的 diameter / 笔尖最长边` 写进 scale，否则 282x282 的笔尖会顶着
    // ABR 声明的 80 却报 282；写死 scale=1 就是这个后果。
    // ------------------------------------------------------------------

    /** 从生成的 XML 里取主 brush_definition 的 scale 值。 */
    private fun brushScaleIn(xml: String): Double? =
        Regex("""<Brush\b[^>]*\bscale="([^"]*)"""")
            .find(xml)
            ?.groupValues?.get(1)
            ?.toDoubleOrNull()

    private fun presetSizeIn(xml: String): Double? =
        Regex("""<param\b[^>]*\bname="paintopSize"[^>]*>(?:<!\[CDATA\[)?([^<\]]+)(?:\]\]>)?\s*</param>""")
            .find(xml)
            ?.groupValues?.get(1)
            ?.trim()
            ?.toDoubleOrNull()

    @Test
    fun `导入时按 ABR 直径换算 scale，使引擎报出的尺寸等于声明值`() {
        // 底图里的笔尖是 64x64 的（见 freshPreviewPng），ABR 声明 30 —— 求 scale 得 0.46875
        val diameter = 30.0
        val tipLongSide = 64.0
        val expectedScale = diameter / tipLongSide

        val xml = KppHelper.readPresetXml(
            KppHelper.updateKppBytes(
                freshPreviewPng(),
                "尺寸回归",
                BrushParams(size = diameter, tipAsset = "t_tip_1.png"),
                tipScale = expectedScale,
            ),
        )!!

        val scale = brushScaleIn(xml)
        assertNotNull("brush_definition 必须带 scale", scale)
        assertEquals(
            "scale 必须是 ABR 直径 / 笔尖最长边",
            expectedScale,
            scale!!,
            1e-6,
        )
        assertEquals(
            "按 Krita 的公式 max(宽,高)*scale 反推出来的尺寸必须等于 ABR 声明的 diameter",
            diameter,
            tipLongSide * scale!!,
            1e-6,
        )
        assertEquals(
            "preset 自述的 paintopSize 必须与直径一致（引擎在笔刷解析失败时以它为兜底）",
            diameter,
            presetSizeIn(xml)!!,
            1e-6,
        )
    }

    @Test
    fun `tipScale 传 null 时必须沿用文件里已有的 scale 而不是重置回 1`() {
        // 笔刷工坊改参数走 updateKppFile，调用方不知道正确的 scale。
        // 若这里默认写回 scale="1"，用户每编辑一次参数，导入笔刷的尺寸就被打回
        // 笔尖原始像素 —— 这正是「每个笔刷都要重校正」的复现路径。
        val first = KppHelper.updateKppBytes(
            freshPreviewPng(),
            "尺寸回归",
            BrushParams(size = 30.0, tipAsset = "t_tip_1.png"),
            tipScale = 30.0 / 64.0,
        )
        val second = KppHelper.updateKppBytes(
            first,
            "尺寸回归",
            BrushParams(size = 30.0, tipAsset = "t_tip_1.png"),
            tipScale = null,
        )

        assertEquals(
            "再次保存（tipScale=null）必须沿用文件中已有的 scale",
            30.0 / 64.0,
            brushScaleIn(KppHelper.readPresetXml(second)!!)!!,
            1e-6,
        )
    }

    @Test
    fun `计算型预设的尺寸来自 MaskGenerator，不受 tipScale 影响`() {
        val xml = KppHelper.readPresetXml(
            KppHelper.updateKppBytes(
                freshPreviewPng(),
                "计算型",
                BrushParams(size = 120.0, tipAsset = ""),
                tipScale = 0.001,
            ),
        )!!

        assertTrue("计算型预设必须走 auto_brush", xml.contains("auto_brush"))
        assertTrue("auto_brush 的尺寸由 MaskGenerator 的 diameter 决定", xml.contains("diameter=\"120.0\""))
        assertTrue("auto_brush 不能带 scale（引擎不从那里取尺寸）", !xml.contains("scale=\"0.001\""))
    }
}
