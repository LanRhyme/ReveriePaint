/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.brushstore

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 引擎 id 归一：未注册的 paintopid 会让 `KisPaintOpPreset::load` 把预设标成 invalid
 * （引擎停在上一支笔上），所以历史短名必须在这里被改写成 Krita 真实注册 id。
 */
class PaintOpIdsTest {

    @Test
    fun `历史短名改写成真实注册 id`() {
        assertEquals("spraybrush", PaintOpIds.canonical("spray"))
        assertEquals("sketchbrush", PaintOpIds.canonical("sketch"))
        assertEquals("hairybrush", PaintOpIds.canonical("hairy"))
        assertEquals("particlebrush", PaintOpIds.canonical("particle"))
        assertEquals("deformbrush", PaintOpIds.canonical("deform"))
    }

    @Test
    fun `空值与默认 id 落到 paintbrush`() {
        assertEquals("paintbrush", PaintOpIds.canonical(""))
        assertEquals("paintbrush", PaintOpIds.canonical("   "))
        assertEquals("paintbrush", PaintOpIds.canonical("defaultpaintop"))
    }

    @Test
    fun `合法 id 原样返回且大小写不敏感只作用于别名`() {
        assertEquals("paintbrush", PaintOpIds.canonical("paintbrush"))
        assertEquals("colorsmudge", PaintOpIds.canonical("colorsmudge"))
        assertEquals("roundmarker", PaintOpIds.canonical("roundmarker"))
        assertEquals("spraybrush", PaintOpIds.canonical(" spraybrush "))
        assertEquals("spraybrush", PaintOpIds.canonical("SPRAY"))
    }

    @Test
    fun `未登记的自定义 id 不改写`() {
        assertEquals("someoplugin", PaintOpIds.canonical("someoplugin"))
    }
}
