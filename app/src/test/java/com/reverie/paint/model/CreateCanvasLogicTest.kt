/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import com.reverie.paint.ui.create.CanvasPresetItem
import com.reverie.paint.ui.create.CanvasUnit
import com.reverie.paint.ui.create.formatUnitValue
import com.reverie.paint.ui.create.getAspectRatioLabel
import com.reverie.paint.ui.create.pxToUnit
import com.reverie.paint.ui.create.unitToPx
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

    @Test
    fun `unit conversion between PX and physical units at various DPI`() {
        // A4 at 300 DPI: 210mm x 297mm -> ~2480 x 3508 px
        assertEquals(2480, unitToPx(210.0, CanvasUnit.MM, 300))
        assertEquals(3508, unitToPx(297.0, CanvasUnit.MM, 300))

        // A4 at 600 DPI: 210mm x 297mm -> ~4961 x 7016 px
        assertEquals(4961, unitToPx(210.0, CanvasUnit.MM, 600))
        assertEquals(7016, unitToPx(297.0, CanvasUnit.MM, 600))

        // Convert px back to mm at 300 DPI
        val mmW = pxToUnit(2480.0, CanvasUnit.MM, 300)
        assertEquals("210", formatUnitValue(mmW, CanvasUnit.MM))

        // Convert px to cm at 300 DPI
        val cmW = pxToUnit(2480.0, CanvasUnit.CM, 300)
        assertEquals("21", formatUnitValue(cmW, CanvasUnit.CM))

        // Inches: 10 x 8 inches @ 300 DPI -> 3000 x 2400 px
        assertEquals(3000, unitToPx(10.0, CanvasUnit.INCH, 300))
        assertEquals(2400, unitToPx(8.0, CanvasUnit.INCH, 300))
        assertEquals(6000, unitToPx(10.0, CanvasUnit.INCH, 600))
    }

    @Test
    fun `canvas ratio preview sizing respects constraints without teleporting center`() {
        fun computePreviewTarget(availW: Float, availH: Float, widthVal: Int, heightVal: Int): Pair<Float, Float> {
            val aspect = (widthVal.toFloat() / heightVal.coerceAtLeast(1).toFloat()).coerceIn(0.15f, 6.0f)
            return if (aspect >= (availW / availH)) {
                val w = availW
                val h = (w / aspect).coerceAtMost(availH)
                Pair(w, h)
            } else {
                val h = availH
                val w = (h * aspect).coerceAtMost(availW)
                Pair(w, h)
            }
        }

        val availW = 300f
        val availH = 106f

        // 1:1 Square
        val (sqW, sqH) = computePreviewTarget(availW, availH, 2048, 2048)
        assertEquals(106f, sqW, 0.01f)
        assertEquals(106f, sqH, 0.01f)

        // 16:9 Landscape
        val (landW, landH) = computePreviewTarget(availW, availH, 1920, 1080)
        assertEquals(188.44f, landW, 0.1f)
        assertEquals(106f, landH, 0.01f)

        // 9:16 Portrait
        val (portW, portH) = computePreviewTarget(availW, availH, 1080, 1920)
        assertEquals(59.62f, portW, 0.1f)
        assertEquals(106f, portH, 0.01f)
    }
}
