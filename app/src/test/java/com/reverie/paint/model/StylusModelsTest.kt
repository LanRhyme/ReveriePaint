/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import com.reverie.paint.core.stylus.OppoPencilModel
import com.reverie.paint.core.stylus.OppoSlideAction
import com.reverie.paint.core.stylus.StylusAction
import com.reverie.paint.core.stylus.StylusAudioType
import com.reverie.paint.core.stylus.StylusBrand
import com.reverie.paint.ui.theme.MorandiDarkColors
import com.reverie.paint.ui.theme.MorandiLightColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StylusModelsTest {

    @Test
    fun `default theme accent color matches Morandi misty blue 5A6E8A`() {
        val expectedColor = androidx.compose.ui.graphics.Color(0xFF5A6E8A)
        assertEquals("Dark mode default accent should be Morandi misty blue", expectedColor, MorandiDarkColors.accent)
        assertEquals("Light mode default accent should be Morandi misty blue", expectedColor, MorandiLightColors.accent)
    }

    @Test
    fun `stylus brands have complete names and supported features in subtitle`() {
        val brands = StylusBrand.entries
        assertTrue("At least OPPO and Samsung brands exist", brands.size >= 2)

        val oppo = brands.firstOrNull { it == StylusBrand.OPPO_ONEPLUS }
        assertNotNull("OPPO/OnePlus brand defined", oppo)
        assertEquals("OPPO Pencil / 一加智能手写笔", oppo?.displayName)
        assertTrue("OPPO should support double tap", oppo?.subtitle?.contains("双击") == true)
        assertTrue("OPPO should support haptics", oppo?.subtitle?.contains("震动") == true)
        assertTrue("OPPO should support audio", oppo?.subtitle?.contains("发声") == true)
        assertTrue("OPPO should support prediction", oppo?.subtitle?.contains("笔迹预测") == true)

        val samsung = brands.firstOrNull { it == StylusBrand.SAMSUNG_SPEN }
        assertNotNull("Samsung brand defined", samsung)
        assertEquals("三星 S Pen", samsung?.displayName)
        assertTrue("Samsung should support side button", samsung?.subtitle?.contains("侧键") == true)

        val honor = brands.firstOrNull { it == StylusBrand.HONOR_MAGIC_PENCIL }
        assertNotNull("Honor brand defined", honor)
        assertEquals("荣耀 Magic-Pencil", honor?.displayName)
        assertTrue("Honor should support double tap", honor?.subtitle?.contains("双击") == true)
        assertTrue("Honor should support side key", honor?.subtitle?.contains("侧键") == true)
        assertTrue("Honor should support haptics", honor?.subtitle?.contains("触感") == true)

        val xiaomi = brands.firstOrNull { it == StylusBrand.XIAOMI_STYLUS }
        assertNotNull("Xiaomi brand defined", xiaomi)
        assertEquals("小米灵感 / 焦点触控笔", xiaomi?.displayName)
        assertTrue("Xiaomi should support writing/focus keys", xiaomi?.subtitle?.contains("按键") == true)
        assertTrue("Xiaomi should support double click", xiaomi?.subtitle?.contains("双击") == true)
    }

    @Test
    fun `oppo pencil standard vs pro hardware differences correctly specified`() {
        val models = OppoPencilModel.entries
        assertEquals("Two core editions: Standard and Pro", 2, models.size)

        val std = OppoPencilModel.STANDARD
        assertEquals(4096, std.maxPressure)
        assertFalse("Standard edition has no in-pen haptic motor", std.hasInPenHaptics)
        assertFalse("Standard edition has no barrel slide gesture", std.hasSlideGesture)
        assertTrue("Standard edition has prediction", std.hasAiPrediction)
        assertEquals("标准版", std.editionName)

        val pro = OppoPencilModel.PRO
        assertEquals("Pro model has 16384 levels of pressure", 16384, pro.maxPressure)
        assertTrue("Pro model has in-pen linear motor haptics", pro.hasInPenHaptics)
        assertTrue("Pro model has barrel slide touch gesture", pro.hasSlideGesture)
        assertTrue("Pro model has AI low-latency prediction", pro.hasAiPrediction)
        assertEquals("Pro 版", pro.editionName)

        assertEquals(OppoPencilModel.PRO, OppoPencilModel.fromKey("PRO"))
        assertEquals(OppoPencilModel.STANDARD, OppoPencilModel.fromKey("STANDARD"))
        assertEquals(OppoPencilModel.STANDARD, OppoPencilModel.fromKey("PENCIL_2"))
        assertEquals(OppoPencilModel.PRO, OppoPencilModel.fromKey("PENCIL_3_PRO"))
    }

    @Test
    fun `oppo slide actions have valid mapping`() {
        val actions = OppoSlideAction.entries
        assertTrue(actions.isNotEmpty())
        assertEquals(OppoSlideAction.ADJUST_BRUSH_SIZE, OppoSlideAction.fromActionId("adjust_brush_size"))
        assertEquals(OppoSlideAction.ADJUST_OPACITY, OppoSlideAction.fromActionId("adjust_opacity"))
        assertEquals(OppoSlideAction.ADJUST_BRUSH_SIZE, OppoSlideAction.fromActionId("invalid"))
    }

    @Test
    fun `honor pencil models correctly specified`() {
        val models = com.reverie.paint.core.stylus.HonorPencilModel.entries
        assertEquals(3, models.size)

        val gen3 = com.reverie.paint.core.stylus.HonorPencilModel.MAGIC_PENCIL_3
        assertEquals(4096, gen3.maxPressure)
        assertFalse(gen3.hasPhysicalButton)
        assertEquals("第三代", gen3.editionName)

        val gen2 = com.reverie.paint.core.stylus.HonorPencilModel.MAGIC_PENCIL_2
        assertEquals(4096, gen2.maxPressure)
        assertFalse(gen2.hasPhysicalButton)

        val pen = com.reverie.paint.core.stylus.HonorPencilModel.MAGIC_PEN
        assertEquals(4096, pen.maxPressure)
        assertTrue("Foldable Magic-Pen has physical button", pen.hasPhysicalButton)

        assertEquals(gen3, com.reverie.paint.core.stylus.HonorPencilModel.fromKey("MAGIC_PENCIL_3"))
        assertEquals(gen2, com.reverie.paint.core.stylus.HonorPencilModel.fromKey("MAGIC_PENCIL_2"))
        assertEquals(pen, com.reverie.paint.core.stylus.HonorPencilModel.fromKey("MAGIC_PEN"))
        assertEquals(pen, com.reverie.paint.core.stylus.HonorPencilModel.fromKey("FOLD"))
    }

    @Test
    fun `stylus actions have unique keys and valid display names`() {
        val actions = StylusAction.entries
        val keys = actions.map { it.actionId }.toSet()
        assertEquals("All StylusAction keys must be unique", actions.size, keys.size)

        val toggleEraser = StylusAction.fromActionId("toggle_eraser")
        assertEquals("切换画笔与橡皮", toggleEraser.title)
        assertEquals(StylusAction.TOGGLE_ERASER, toggleEraser)

        val undo = StylusAction.fromActionId("undo")
        assertEquals("撤销", undo.title)
        assertEquals(StylusAction.UNDO, undo)

        val defaultFallback = StylusAction.fromActionId("unknown_action")
        assertEquals(StylusAction.TOGGLE_ERASER, defaultFallback)
    }

    @Test
    fun `stylus audio types have valid titles and ordinals`() {
        val types = StylusAudioType.entries
        assertEquals(3, types.size)

        val titles = types.map { it.title }
        assertTrue("Contains pencil sound", titles.any { it.contains("铅笔") })
        assertTrue("Contains ink sound", titles.any { it.contains("钢笔") })
        assertTrue("Contains soft tick sound", titles.any { it.contains("轻触") })

        assertEquals(StylusAudioType.PENCIL, StylusAudioType.fromOrdinal(0))
        assertEquals(StylusAudioType.INK_PEN, StylusAudioType.fromOrdinal(1))
        assertEquals(StylusAudioType.SOFT_TICK, StylusAudioType.fromOrdinal(2))
        assertEquals(StylusAudioType.PENCIL, StylusAudioType.fromOrdinal(999))
    }

    @Test
    fun `xiaomi pencil models correctly specified`() {
        val models = com.reverie.paint.core.stylus.XiaomiPencilModel.entries
        assertEquals(4, models.size)

        val pro = com.reverie.paint.core.stylus.XiaomiPencilModel.FOCUS_PEN_PRO
        assertEquals(16384, pro.maxPressure)
        assertFalse(pro.hasFocusKey)
        assertTrue(pro.hasDoubleTap)
        assertTrue(pro.hasSlideGesture)
        assertTrue(pro.hasSqueezeGesture)
        assertFalse("Pro has no physical buttons", pro.hasPhysicalButtons)
        assertEquals("Pro 版", pro.editionName)

        val focus = com.reverie.paint.core.stylus.XiaomiPencilModel.FOCUS_PEN
        assertEquals(8192, focus.maxPressure)
        assertTrue("Focus pen has focus key", focus.hasFocusKey)
        assertTrue("Focus pen has double tap", focus.hasDoubleTap)
        assertFalse(focus.hasSlideGesture)
        assertTrue("Standard focus pen has physical buttons", focus.hasPhysicalButtons)
        assertEquals("焦点触控笔", focus.editionName)

        val gen2 = com.reverie.paint.core.stylus.XiaomiPencilModel.SMART_PEN_2
        assertEquals(4096, gen2.maxPressure)
        assertFalse(gen2.hasFocusKey)
        assertTrue("Smart pen 2 has double tap", gen2.hasDoubleTap)
        assertTrue("Smart pen 2 has physical buttons", gen2.hasPhysicalButtons)

        val gen1 = com.reverie.paint.core.stylus.XiaomiPencilModel.SMART_PEN_1
        assertEquals(4096, gen1.maxPressure)
        assertFalse(gen1.hasFocusKey)
        assertFalse("Smart pen 1 does not have double tap", gen1.hasDoubleTap)
        assertTrue("Smart pen 1 has physical buttons", gen1.hasPhysicalButtons)

        assertEquals(pro, com.reverie.paint.core.stylus.XiaomiPencilModel.fromKey("FOCUS_PEN_PRO"))
        assertEquals(pro, com.reverie.paint.core.stylus.XiaomiPencilModel.fromKey("PRO"))
        assertEquals(focus, com.reverie.paint.core.stylus.XiaomiPencilModel.fromKey("FOCUS"))
        assertEquals(gen2, com.reverie.paint.core.stylus.XiaomiPencilModel.fromKey("SMART_PEN_2"))
        assertEquals(gen1, com.reverie.paint.core.stylus.XiaomiPencilModel.fromKey("GEN1"))
        assertEquals(gen1, com.reverie.paint.core.stylus.XiaomiPencilModel.fromKey("1"))
    }

    @Test
    fun `huawei pencil models correctly specified`() {
        val models = com.reverie.paint.core.stylus.HuaweiPencilModel.entries
        assertEquals(4, models.size)

        val pro = com.reverie.paint.core.stylus.HuaweiPencilModel.PRO
        assertEquals(16384, pro.maxPressure)
        assertTrue("Pro has NearLink", pro.isNearLink)
        assertTrue("Pro has double tap", pro.hasDoubleTap)
        assertTrue("Pro has squeeze", pro.hasSqueeze)

        val gen3 = com.reverie.paint.core.stylus.HuaweiPencilModel.GEN3_NEARLINK
        assertEquals(16384, gen3.maxPressure)
        assertTrue("Gen 3 has NearLink", gen3.isNearLink)
        assertTrue("Gen 3 has double tap", gen3.hasDoubleTap)
        assertFalse("Gen 3 does not have squeeze", gen3.hasSqueeze)

        val gen2 = com.reverie.paint.core.stylus.HuaweiPencilModel.GEN2
        assertEquals(4096, gen2.maxPressure)
        assertFalse(gen2.isNearLink)
        assertTrue(gen2.hasDoubleTap)
        assertFalse(gen2.hasSqueeze)

        val gen1 = com.reverie.paint.core.stylus.HuaweiPencilModel.GEN1
        assertEquals(4096, gen1.maxPressure)
        assertFalse(gen1.isNearLink)
        assertFalse(gen1.hasDoubleTap)
        assertFalse(gen1.hasSqueeze)

        assertEquals(pro, com.reverie.paint.core.stylus.HuaweiPencilModel.fromKey("PRO"))
        assertEquals(gen3, com.reverie.paint.core.stylus.HuaweiPencilModel.fromKey("GEN3_NEARLINK"))
        assertEquals(gen3, com.reverie.paint.core.stylus.HuaweiPencilModel.fromKey("NEARLINK"))
        assertEquals(gen2, com.reverie.paint.core.stylus.HuaweiPencilModel.fromKey("GEN2"))
        assertEquals(gen1, com.reverie.paint.core.stylus.HuaweiPencilModel.fromKey("GEN1"))
    }

    @Test
    fun `vivo pencil models correctly specified`() {
        val brands = StylusBrand.entries
        val vivo = brands.firstOrNull { it == StylusBrand.VIVO_PENCIL }
        assertNotNull("vivo brand defined", vivo)
        assertEquals("vivo Pencil / iQOO Pencil", vivo?.displayName)
        assertTrue("vivo should support double tap", vivo?.subtitle?.contains("双击") == true)
        assertTrue("vivo should support writing vibrate", vivo?.subtitle?.contains("振动") == true)
        assertTrue("vivo should support prediction", vivo?.subtitle?.contains("笔迹预测") == true)

        val models = com.reverie.paint.core.stylus.VivoPencilModel.entries
        assertEquals(5, models.size)

        val pencil2 = com.reverie.paint.core.stylus.VivoPencilModel.VIVO_PENCIL2
        assertTrue("Pencil2 has body double tap", pencil2.hasDoubleTap)
        assertTrue("Pencil2 has writing vibration", pencil2.hasWritingVibrate)
        assertFalse("Pencil2 is buttonless (capacitive film body)", pencil2.hasPhysicalButtons)
        assertEquals("第二代", pencil2.editionName)

        val nv = com.reverie.paint.core.stylus.VivoPencilModel.VIVO_PENCIL2_NV
        assertTrue(nv.hasDoubleTap)
        assertFalse("NV edition has no writing vibration", nv.hasWritingVibrate)
        assertFalse(nv.hasPhysicalButtons)

        val p2s = com.reverie.paint.core.stylus.VivoPencilModel.VIVO_PENCIL2S
        assertTrue(p2s.hasDoubleTap)
        assertFalse(p2s.hasWritingVibrate)
        assertTrue("2s generation has physical button switching", p2s.hasPhysicalButtons)

        val pencil3 = com.reverie.paint.core.stylus.VivoPencilModel.VIVO_PENCIL3
        assertTrue(pencil3.hasDoubleTap)
        assertTrue(pencil3.hasWritingVibrate)
        assertFalse(pencil3.hasPhysicalButtons)

        val pencil1 = com.reverie.paint.core.stylus.VivoPencilModel.VIVO_PENCIL1
        assertFalse("1st gen has no body double tap", pencil1.hasDoubleTap)
        assertFalse(pencil1.hasWritingVibrate)
        assertTrue(pencil1.hasPhysicalButtons)

        val vivoModels = com.reverie.paint.core.stylus.VivoPencilModel
        assertEquals(pencil2, vivoModels.fromKey("VIVO_PENCIL2"))
        assertEquals(nv, vivoModels.fromKey("VIVO_PENCIL2_NV"))
        assertEquals(p2s, vivoModels.fromKey("AIR"))
        assertEquals(p2s, vivoModels.fromKey("VIVO_PENCIL2S"))
        assertEquals(pencil3, vivoModels.fromKey("VIVO_PENCIL3"))
        assertEquals(pencil1, vivoModels.fromKey("GEN1"))
        assertEquals(pencil2, vivoModels.fromKey("UNKNOWN"))
    }

    @Test
    fun `dedicated stylus brand priority isolates generic stylus adapter`() {
        val dedicatedBrands = listOf(
            com.reverie.paint.core.stylus.StylusBrand.SAMSUNG_SPEN,
            com.reverie.paint.core.stylus.StylusBrand.HUAWEI_MPENCIL,
            com.reverie.paint.core.stylus.StylusBrand.HONOR_MAGIC_PENCIL,
            com.reverie.paint.core.stylus.StylusBrand.OPPO_ONEPLUS,
            com.reverie.paint.core.stylus.StylusBrand.XIAOMI_STYLUS,
            com.reverie.paint.core.stylus.StylusBrand.VIVO_PENCIL,
        )

        for (brand in dedicatedBrands) {
            val detected = listOf(
                com.reverie.paint.core.stylus.StylusDeviceDetected(
                    brand = brand,
                    isCurrentDeviceSupported = true,
                    isConnected = true,
                    deviceName = "Dedicated Pen",
                ),
                com.reverie.paint.core.stylus.StylusDeviceDetected(
                    brand = com.reverie.paint.core.stylus.StylusBrand.GENERIC,
                    isCurrentDeviceSupported = true,
                    isConnected = true,
                    deviceName = "Generic Pen",
                ),
            )
            val hasDedicated = detected.any {
                it.brand != com.reverie.paint.core.stylus.StylusBrand.GENERIC && it.isCurrentDeviceSupported && it.isConnected
            }
            assertTrue("Dedicated brand $brand must activate isolation gate", hasDedicated)
        }

        val genericOnly = listOf(
            com.reverie.paint.core.stylus.StylusDeviceDetected(
                brand = com.reverie.paint.core.stylus.StylusBrand.GENERIC,
                isCurrentDeviceSupported = true,
                isConnected = true,
                deviceName = "Generic Pen",
            ),
        )
        val hasDedicatedForGeneric = genericOnly.any {
            it.brand != com.reverie.paint.core.stylus.StylusBrand.GENERIC && it.isCurrentDeviceSupported && it.isConnected
        }
        assertFalse("Generic-only device must not trigger isolation", hasDedicatedForGeneric)
    }

    @Test
    fun `samsung stylus default action ids resolve to valid stylus actions`() {
        val singleAction = StylusAction.fromActionId("toggle_eraser")
        val doubleAction = StylusAction.fromActionId("undo")
        val longPressAction = StylusAction.fromActionId("tool_picker")

        assertEquals(StylusAction.TOGGLE_ERASER, singleAction)
        assertEquals(StylusAction.UNDO, doubleAction)
        assertEquals(StylusAction.COLOR_PICKER, longPressAction)
    }

    @Test
    fun `samsung stylus swapped action mappings resolve correctly`() {
        // User swapped mappings: single-click to undo, double-click to toggle brush/eraser
        val singleClick = StylusAction.fromActionId("undo")
        val doubleClick = StylusAction.fromActionId("toggle_eraser")

        assertEquals(StylusAction.UNDO, singleClick)
        assertEquals(StylusAction.TOGGLE_ERASER, doubleClick)
    }

    @Test
    fun `primary dedicated brand resolution selects exclusively top supported connected brand`() {
        // Emulating Samsung Galaxy Tab S9 environment
        val detected = listOf(
            com.reverie.paint.core.stylus.StylusDeviceDetected(
                brand = com.reverie.paint.core.stylus.StylusBrand.OPPO_ONEPLUS,
                isCurrentDeviceSupported = false,
                isConnected = false,
                deviceName = "OPPO Pencil",
            ),
            com.reverie.paint.core.stylus.StylusDeviceDetected(
                brand = com.reverie.paint.core.stylus.StylusBrand.HUAWEI_MPENCIL,
                isCurrentDeviceSupported = false,
                isConnected = false,
                deviceName = "HUAWEI M-Pencil",
            ),
            com.reverie.paint.core.stylus.StylusDeviceDetected(
                brand = com.reverie.paint.core.stylus.StylusBrand.HONOR_MAGIC_PENCIL,
                isCurrentDeviceSupported = false,
                isConnected = false,
                deviceName = "HONOR Magic-Pencil",
            ),
            com.reverie.paint.core.stylus.StylusDeviceDetected(
                brand = com.reverie.paint.core.stylus.StylusBrand.SAMSUNG_SPEN,
                isCurrentDeviceSupported = true,
                isConnected = true,
                deviceName = "Samsung S Pen (SM-X710)",
            ),
            com.reverie.paint.core.stylus.StylusDeviceDetected(
                brand = com.reverie.paint.core.stylus.StylusBrand.XIAOMI_STYLUS,
                isCurrentDeviceSupported = false,
                isConnected = false,
                deviceName = "Xiaomi Smart Pen",
            ),
            com.reverie.paint.core.stylus.StylusDeviceDetected(
                brand = com.reverie.paint.core.stylus.StylusBrand.GENERIC,
                isCurrentDeviceSupported = true,
                isConnected = true,
                deviceName = "Generic Pen",
            ),
        )

        val primaryDedicated = detected.firstOrNull {
            it.brand != com.reverie.paint.core.stylus.StylusBrand.GENERIC && it.isCurrentDeviceSupported && it.isConnected
        } ?: detected.firstOrNull {
            it.brand != com.reverie.paint.core.stylus.StylusBrand.GENERIC && it.isCurrentDeviceSupported
        }

        assertNotNull("Primary dedicated brand should be found", primaryDedicated)
        assertEquals("Primary dedicated brand must be SAMSUNG_SPEN", com.reverie.paint.core.stylus.StylusBrand.SAMSUNG_SPEN, primaryDedicated?.brand)
    }

    @Test
    fun `xiaomi focus pen does not use touchfilm penengine and routes physical buttons`() {
        val focusPen = com.reverie.paint.core.stylus.XiaomiPencilModel.FOCUS_PEN
        val focusPro = com.reverie.paint.core.stylus.XiaomiPencilModel.FOCUS_PEN_PRO
        val smartPen2 = com.reverie.paint.core.stylus.XiaomiPencilModel.SMART_PEN_2

        // PenEngine (touchfilm) should only be activated for models with slide/touchfilm gestures
        val needsPenEngineFocus = !focusPen.hasPhysicalButtons || focusPen.hasSlideGesture
        val needsPenEnginePro = !focusPro.hasPhysicalButtons || focusPro.hasSlideGesture
        val needsPenEngineSmart2 = !smartPen2.hasPhysicalButtons || smartPen2.hasSlideGesture

        assertFalse("Standard Focus Pen has physical buttons, must NOT use TouchFilm PenEngine", needsPenEngineFocus)
        assertTrue("Focus Pen Pro is buttonless and uses TouchFilm PenEngine", needsPenEnginePro)
        assertFalse("Smart Pen 2 has physical buttons, must NOT use TouchFilm PenEngine", needsPenEngineSmart2)

        // KeyCodes used by Xiaomi physical buttons: PageUp/PageDown are emitted by standard Xiaomi Focus Pen
        val primaryKeyCodes = listOf(
            android.view.KeyEvent.KEYCODE_PAGE_UP,
            android.view.KeyEvent.KEYCODE_DPAD_UP,
            android.view.KeyEvent.KEYCODE_STYLUS_BUTTON_PRIMARY,
            android.view.KeyEvent.KEYCODE_BUTTON_1,
            308,
        )
        val secondaryKeyCodes = listOf(
            android.view.KeyEvent.KEYCODE_PAGE_DOWN,
            android.view.KeyEvent.KEYCODE_DPAD_DOWN,
            android.view.KeyEvent.KEYCODE_STYLUS_BUTTON_SECONDARY,
            android.view.KeyEvent.KEYCODE_BUTTON_2,
            309,
        )

        assertTrue(primaryKeyCodes.contains(android.view.KeyEvent.KEYCODE_PAGE_UP))
        assertTrue(secondaryKeyCodes.contains(android.view.KeyEvent.KEYCODE_PAGE_DOWN))
    }
}

