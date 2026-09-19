/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SphereShading 单元测试。
 * 验证 3D 光影球体积感着色、球心固有色约束、冷暖偏移推导与环境反光。
 */
class SphereShadingTest {

    private val base = SphereShading.parseHexToArgb("#6BA439")
    private val light = SphereShading.parseHexToArgb("#EEC829")
    private val shadow = SphereShading.parseHexToArgb("#006F68")
    private val softness = 0.5f

    @Test
    fun `球心采样严格等于固有色`() {
        val argb = SphereShading.sampleArgb(0f, 0f, base, shadow, light, softness)
        assertEquals(base or 0xFF000000.toInt(), argb)
    }

    @Test
    fun `HEX 采样与整型采样一致且球心等于固有色 HEX`() {
        val hex = SphereShading.calculateSphereColorHex(0f, 0f, "#6BA439", "#006F68", "#EEC829", softness)
        assertEquals("6BA439", hex)
    }

    @Test
    fun `关键方位采样具备真实3D体感与环境反光`() {
        val center = SphereShading.sampleArgb(0f, 0f, base, shadow, light, softness)
        val apex = SphereShading.sampleArgb(0.39f, -0.54f, base, shadow, light, softness)
        val lit = SphereShading.sampleArgb(0.55f, -0.55f, base, shadow, light, softness)
        val core = SphereShading.sampleArgb(-0.55f, 0.55f, base, shadow, light, softness)
        val rim = SphereShading.sampleArgb(-0.65f, 0.65f, base, shadow, light, softness)

        // 亮面应亮于球心
        assertTrue("高光点应亮于球心", lum(apex) > lum(center))
        assertTrue("受光弧面应亮于球心", lum(lit) > lum(center))
        // 核心暗部应暗于球心
        assertTrue("明暗交界核心应暗于球心", lum(core) < lum(center))
        // 轮缘反光区应明于核心暗部 (反光抬升)
        assertTrue("环境反光区应明显亮于核心暗部", lum(rim) > lum(core))
    }

    @Test
    fun `亮面亮于球心_暗面暗于球心`() {
        val c = lum(SphereShading.sampleArgb(0f, 0f, base, shadow, light, softness))
        val lit = lum(SphereShading.sampleArgb(0.55f, -0.55f, base, shadow, light, softness))
        val dark = lum(SphereShading.sampleArgb(-0.55f, 0.55f, base, shadow, light, softness))
        assertTrue("亮面应亮于球心", lit > c)
        assertTrue("暗面应暗于球心", dark < c)
    }

    @Test
    fun `圆外坐标收敛到轮缘而非越界`() {
        val rim = SphereShading.sampleArgb(0.9f, 0.9f, base, shadow, light, softness)
        val far = SphereShading.sampleArgb(3f, 3f, base, shadow, light, softness)
        assertEquals(rim, far)
    }

    @Test
    fun `自动光色偏暖_自动阴影色偏冷`() {
        val (lightInt, shadowInt) = SphereShading.computeAutoLightingColors(base)
        val lumL = lum(lightInt)
        val lumS = lum(shadowInt)
        assertTrue("自动主光色应更亮", lumL > lum(base))
        assertTrue("自动阴影色应更暗", lumS < lum(base))
        // 阴影色相应比固有色更偏青蓝/冷色
        val hb = hueOf(base)
        val hs = hueOf(shadowInt)
        assertTrue("阴影色应偏冷: hb=$hb hs=$hs", hs > hb)
    }

    private fun hueOf(argb: Int): Float {
        val r = ((argb shr 16) and 0xFF) / 255f
        val g = ((argb shr 8) and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        val mx = maxOf(r, g, b)
        val mn = minOf(r, g, b)
        val d = mx - mn
        if (d < 1e-6f) return 0f
        val h = when (mx) {
            r -> ((g - b) / d) % 6f
            g -> (b - r) / d + 2f
            else -> (r - g) / d + 4f
        }
        return ((h / 6f) + 1f) % 1f
    }

    private fun lum(argb: Int): Float =
        (((argb shr 16) and 0xFF) + ((argb shr 8) and 0xFF) + (argb and 0xFF)) / 3f
}
