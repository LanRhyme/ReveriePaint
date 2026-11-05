/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import org.junit.Assume.assumeTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 针对 pencil.abr（53 支 Photoshop 真实笔刷）的 AbrParser 语义抽取回归测试。
 *
 * Photoshop 把纹理键（useTexture/textureBlendMode/textureDepth/textureScale/Txtr/InvT）写在
 * brushPreset 描述符**顶层**，dualBrush 的 useDualBrush 开关写在嵌套 dualBrush 描述符里，
 * minimumRoundness/minimumDiameter 写在顶层 —— 断言值全部来自源描述符核实结果，
 * 不是抄当前解析输出。
 *
 * 样本出于再分发考虑不入库：通过系统属性/环境变量 `PENCIL_ABR`（系统属性优先）指向
 * 外部 pencil.abr；未设置或文件不存在时各用例经 [org.junit.Assume] 门控跳过。
 */
internal object PencilAbrFixture {

    const val PATH_VAR = "PENCIL_ABR"

    /** 外部 pencil.abr 路径：系统属性优先、环境变量兜底；未设置或不是文件返回 null */
    val filePath: String? by lazy {
        (System.getProperty(PATH_VAR) ?: System.getenv(PATH_VAR))?.let { p ->
            File(p).takeIf { it.isFile }?.absolutePath
        }
    }

    fun available(): Boolean = filePath != null

    /** 整包解析结果（lazy 缓存，整个测试类只解析一次） */
    val result: AbrParser.AbrParseResult by lazy {
        val path = requireNotNull(filePath) { "缺少外部 pencil.abr（设置 $PATH_VAR）" }
        AbrParser.parse(java.io.FileInputStream(File(path)).buffered(), basePackName = "pencil")
    }

    fun presets(): List<AbrParser.AbrPresetInfo> = result.presets

    fun patterns(): List<AbrParser.AbrPatternResource> = result.patterns

    /** 按名字精确查找 Hard Charcoal 02；找不到则列出含 "Charcoal" 的实际名字帮助定位 */
    fun hc02(): AbrParser.AbrPresetInfo {
        val preset = presets().firstOrNull { it.name == "Hard Charcoal 02" }
        if (preset == null) {
            val charcoalNames = presets()
                .filter { it.name.contains("Charcoal", ignoreCase = true) }
                .map { it.name }
            throw AssertionError(
                "presets 中找不到名字恰好为 'Hard Charcoal 02' 的预设；" +
                    "共 ${presets().size} 支，含 'Charcoal' 的实际名字: $charcoalNames",
            )
        }
        return preset
    }
}

class PencilAbrParserTest {

    private fun gate() = assumeTrue("缺少外部 pencil.abr（设置 ${PencilAbrFixture.PATH_VAR}）", PencilAbrFixture.available())

    @Test
    fun `Hard Charcoal 02 reads top-level texture keys and top-level minimumRoundness`() {
        gate()
        val p = PencilAbrFixture.hc02()

        val texture = requireNotNull(p.texture) { "HC02 源描述符 useTexture=true，texture 不应为 null" }
        assertEquals("cc84d169-8efd-cb4d-9044-c2aae3b74d62", texture.patternUuid)
        assertEquals("papel_01_tile.jpg", texture.patternName)
        assertEquals(0.98, texture.depth, 1e-9)
        assertEquals(1.0, texture.scale, 1e-9)
        assertEquals("subtract", texture.mode)
        assertFalse(texture.invert)

        assertEquals(0.25, p.minRoundness, 1e-9)
        assertEquals(0.25, p.roundnessJitter, 1e-9)
        assertEquals(0.10, p.minDiameterRatio, 1e-9)
        assertNull("HC02 源描述符 dualBrush.useDualBrush=false，dualBrush 应为 null", p.dualBrush)
        assertEquals(0.0, p.hardness, 1e-9)
    }

    @Test
    fun `patt section yields the pattern referenced by the HC02 texture`() {
        gate()
        val uuid = "cc84d169-8efd-cb4d-9044-c2aae3b74d62"
        val pattern = PencilAbrFixture.patterns().firstOrNull { it.uuid == uuid }
        assertNotNull("patterns 中应存在 uuid=$uuid 的图案，实际共 ${PencilAbrFixture.patterns().size} 个", pattern)
        assertEquals("papel_01_tile.jpg", pattern!!.name)
    }

    @Test
    fun `texture and dualBrush adoption counts across the whole pack`() {
        gate()
        val presets = PencilAbrFixture.presets()
        assertEquals("53 支笔应全部抽出", 53, presets.size)
        assertEquals(
            "源描述符顶层 useTexture=true 的笔数",
            51,
            presets.count { it.texture != null },
        )
        assertEquals(
            "嵌套 dualBrush.useDualBrush=true 的笔数",
            2,
            presets.count { it.dualBrush != null },
        )
    }

    @Test
    fun `textureBlendMode Hght and linearHeight map to the new height modes`() {
        gate()
        val presets = PencilAbrFixture.presets()

        val mediumPencil03 = presets.filter { it.name.contains("Medium Pencil 03") }
        assertTrue("应存在名字含 Medium Pencil 03 的预设", mediumPencil03.isNotEmpty())
        for (p in mediumPencil03) {
            assertEquals("height_photoshop", p.texture?.mode)
        }

        val wateredPencil01A = presets.filter { it.name.contains("Watered Pencil 01A") }
        assertTrue("应存在名字含 Watered Pencil 01A 的预设", wateredPencil01A.isNotEmpty())
        for (p in wateredPencil01A) {
            assertEquals("linear_height_photoshop", p.texture?.mode)
        }
    }
}
