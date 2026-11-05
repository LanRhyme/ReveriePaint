/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import com.reverie.paint.core.brushstore.KppXml
import com.reverie.paint.core.brushstore.PresetReadback
import com.reverie.paint.core.brushstore.ResourceStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Krita 先例：PS 的纹理深度要经 `Texture/Pattern/UseSoftTexturing=true` 走软减
 * (`dst - mul(src, strength)`)，非软的 SUBTRACT 是 `dst - (src + inv(strength))`，
 * 深度一低纹理就几乎被吃光。亮度/对比度对应 `Texture/Pattern/Brightness`(-1..1) / `Contrast`(0..2)。
 */
class AbrTextureSoftTest {
    private val ref = ResourceStore.Ref("0123456789abcdef0123456789abcdef", "abr_pat_x.png", ResourceStore.Kind.PATTERN)

    private fun xml(p: AbrParser.AbrPresetInfo, refs: Map<String, ResourceStore.Ref> = mapOf("u" to ref)) =
        KppHelper.readPresetXml(
            AbrPresetConverter.convert(p, p.name, null, "", emptyMap(), "pack", patternRefs = refs).kppBytes,
        )!!

    @Test
    fun abrTextureIsWrittenAsSoftTexturing() {
        val x = xml(
            AbrParser.AbrPresetInfo(
                name = "Tex", isComputed = true,
                texture = AbrParser.AbrTextureInfo(patternName = "p", patternUuid = "u", mode = "subtract", depth = 0.3),
            ),
        )
        assertEquals("true", KppXml.get(x, "Texture/Pattern/UseSoftTexturing"))
        assertEquals(true, PresetReadback.fromXml(x).textureEnabled)
    }

    @Test
    fun brightnessAndContrastAreCarried() {
        val x = xml(
            AbrParser.AbrPresetInfo(
                name = "Tex", isComputed = true,
                texture = AbrParser.AbrTextureInfo(patternName = "p", patternUuid = "u", brightness = 51, contrast = 20),
            ),
        )
        assertEquals(-0.2, KppXml.getDouble(x, "Texture/Pattern/Brightness")!!, 1e-6)
        assertEquals(1.2, KppXml.getDouble(x, "Texture/Pattern/Contrast")!!, 1e-6)
    }

    @Test
    fun noTextureLeavesTheSoftKeysOut() {
        val x = xml(AbrParser.AbrPresetInfo(name = "Plain", isComputed = true))
        assertNull(KppXml.get(x, "Texture/Pattern/UseSoftTexturing"))
        assertNull(KppXml.get(x, "Texture/Pattern/Brightness"))
    }
}
