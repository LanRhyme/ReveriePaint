/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import com.reverie.paint.ui.create.CanvasPresetItem
import com.reverie.paint.ui.create.getAspectRatioLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CreateCanvasLogicTest {

    @Test
    fun `getAspectRatioLabel accurately identifies common aspect ratios`() {
        assertEquals("1:1 正方形", getAspectRatioLabel(2048, 2048))
        assertEquals("1:1 正方形", getAspectRatioLabel(4096, 4096))
        assertEquals("16:9 宽屏", getAspectRatioLabel(3840, 2160))
        assertEquals("16:9 宽屏", getAspectRatioLabel(1920, 1080))
        assertEquals("9:16 竖屏", getAspectRatioLabel(1080, 1920))
        assertEquals("4:3 标准", getAspectRatioLabel(2048, 1536))
        assertEquals("3:4 竖屏", getAspectRatioLabel(1536, 2048))
        assertEquals("A4 纸张", getAspectRatioLabel(2480, 3508))
        assertEquals("A4 横版", getAspectRatioLabel(3508, 2480))
        assertEquals("B5 漫画", getAspectRatioLabel(2150, 3035))
        assertEquals("20:9 手机壁纸", getAspectRatioLabel(1080, 2400))
        assertEquals("长图条漫", getAspectRatioLabel(1080, 4000))
    }

    @Test
    fun `getAspectRatioLabel handles edge cases and arbitrary aspect ratios`() {
        assertEquals("", getAspectRatioLabel(0, 0))
        assertEquals("", getAspectRatioLabel(-100, 200))
        assertEquals("3:2", getAspectRatioLabel(3000, 2000))
        assertEquals("5:4", getAspectRatioLabel(2500, 2000))
    }

    @Test
    fun `CanvasPresetItem default properties and custom flags`() {
        val defaultItem = CanvasPresetItem(
            name = "测试预设",
            width = 1920,
            height = 1080
        )
        assertEquals(300, defaultItem.defaultPpi)
        assertEquals(false, defaultItem.isCustom)
        assertTrue(defaultItem.id.isNotEmpty())

        val customItem = CanvasPresetItem(
            name = "我的画幅",
            width = 3000,
            height = 2000,
            defaultPpi = 350,
            description = "自定义测试",
            isCustom = true
        )
        assertEquals(true, customItem.isCustom)
        assertEquals(350, customItem.defaultPpi)
        assertEquals("自定义测试", customItem.description)
    }
}
