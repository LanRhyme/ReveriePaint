/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.brushstore

import com.reverie.paint.core.BrushParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 纹理强度是曲线选项 `Texture/Strength/`（KisTextureOptionData / KisCurveOptionData 的 prefix 拼接），
 * 键为 `PressureTexture/Strength/`、`Texture/Strength/Value`；旧写入器写的 `Texture/Pattern/Strength` 不被 Krita 读取。
 */
class TextureKeyTest {

    private val strength = PresetPropertyMap.spec(ControlId.TEXTURE_STRENGTH)
    private val pattern = PresetPropertyMap.spec(ControlId.TEXTURE_PATTERN)

    @Test
    fun strengthWrite_usesCurveOptionKeys_onEveryAsset() {
        for ((name, xml) in KppFixtures.all) {
            val out = strength.write!!(xml, 0.42, BrushParams())
            assertEquals(name, "true", KppXml.get(out, "PressureTexture/Strength/"))
            assertEquals(name, 0.42, KppXml.getDouble(out, "Texture/Strength/Value")!!, 1e-9)
            assertEquals(name, 0.42, strength.read!!(out) as Double, 1e-9)
            // 不新增 Krita 不读的旧键
            assertEquals(name, KppXml.has(xml, "Texture/Pattern/Strength"), KppXml.has(out, "Texture/Pattern/Strength"))
        }
    }

    @Test
    fun strengthRead_prefersCurveOption_fallsBackToLegacy() {
        val withCurve = KppFixtures.all.filter { KppXml.has(it.second, "Texture/Strength/Value") }
        assertTrue(withCurve.isNotEmpty())
        for ((name, xml) in withCurve) {
            val expect = if (KppSensors.isChecked(xml, "Texture/Strength/")) KppXml.getDouble(xml, "Texture/Strength/Value")!! else 1.0
            assertEquals(name, expect, strength.read!!(xml) as Double, 1e-9)
        }
        val legacyOnly = """<Preset paintopid="paintbrush"><param type="string" name="Texture/Pattern/Strength"><![CDATA[0.3]]></param></Preset>"""
        assertEquals(0.3, PresetPropertyMap.readTextureStrength(legacyOnly)!!, 1e-9)
    }

    @Test
    fun patternWrite_dropsMd5AndEmbeddedPattern() {
        val withMd5 = KppFixtures.all.filter { KppXml.has(it.second, "Texture/Pattern/PatternMD5") }
        assertTrue(withMd5.isNotEmpty())
        for ((name, xml) in withMd5) {
            val out = pattern.write!!(xml, "my_paper.png", BrushParams())
            assertEquals(name, "", KppXml.get(out, "Texture/Pattern/PatternMD5"))
            assertEquals(name, "", KppXml.get(out, "Texture/Pattern/PatternMD5Sum"))
            assertFalse(name, KppXml.has(out, "Texture/Pattern/Pattern"))
            assertEquals(name, "my_paper.png", KppXml.get(out, "Texture/Pattern/PatternFileName"))
            assertEquals(name, "my_paper.png", KppXml.get(out, "Texture/Pattern/Name"))
            assertEquals(name, "my_paper.png", pattern.read!!(out))
            // 空串不动原图
            assertEquals(name, xml, pattern.write!!(xml, "", BrushParams()))
        }
    }
}
