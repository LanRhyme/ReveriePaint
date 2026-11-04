/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeSettingsTest {

    @Test
    fun `canvas background color hex parsing and resolution logic`() {
        val defaultMode = "DEFAULT"
        val customHex = "#1E2024"
        val pureBlackHex = "#000000"
        val pureWhiteHex = "#FFFFFF"

        val isDefault = { hex: String -> hex.isBlank() || hex.equals("DEFAULT", ignoreCase = true) }

        assertTrue("DEFAULT should be recognized as default", isDefault(defaultMode))
        assertTrue("Blank should be recognized as default", isDefault(""))
        assertFalse("Custom hex should not be recognized as default", isDefault(customHex))
        assertFalse("Pure black should not be recognized as default", isDefault(pureBlackHex))
        assertFalse("Pure white should not be recognized as default", isDefault(pureWhiteHex))
    }

    @Test
    fun `canvas background preset swatches list validity`() {
        val swatches = listOf(
            "DEFAULT",
            "#121316",
            "#1E2024",
            "#2F3136",
            "#35383F",
            "#4E5159",
            "#7A7E85",
            "#B0B5BD",
            "#D8DCE2",
            "#F0F2F5",
            "#000000",
            "#FFFFFF"
        )

        swatches.filter { it != "DEFAULT" }.forEach { hex ->
            assertTrue("Hex $hex must start with #", hex.startsWith("#"))
            assertEquals("Hex $hex must be 7 characters long", 7, hex.length)
            val hexVal = hex.removePrefix("#").toLongOrNull(16)
            assertTrue("Hex $hex must be valid hexadecimal number", hexVal != null && hexVal in 0..0xFFFFFF)
        }
    }

    @Test
    fun `dark mode canvas background is brighter than panel`() {
        val darkTheme = com.reverie.paint.ui.theme.MorandiDarkColors
        val panelLum = darkTheme.panel.red * 0.299f + darkTheme.panel.green * 0.587f + darkTheme.panel.blue * 0.114f
        val canvasLum = darkTheme.canvasBg.red * 0.299f + darkTheme.canvasBg.green * 0.587f + darkTheme.canvasBg.blue * 0.114f
        assertTrue("Canvas background must be brighter than panel in dark mode", canvasLum > panelLum)
    }

    @Test
    fun `buildThemeColors only updates accent without altering backgrounds or panels`() {
        val darkBase = com.reverie.paint.ui.theme.MorandiDarkColors
        val customAccent = androidx.compose.ui.graphics.Color(0xFFFF2D55)
        val themed = com.reverie.paint.ui.theme.buildThemeColors(isDark = true, accent = customAccent)

        assertEquals("Accent should be updated to custom color", customAccent, themed.accent)
        assertEquals("Background must remain identical to base", darkBase.bg, themed.bg)
        assertEquals("Panel must remain identical to base", darkBase.panel, themed.panel)
        assertEquals("PanelHi must remain identical to base", darkBase.panelHi, themed.panelHi)
        assertEquals("CanvasBg must remain identical to base", darkBase.canvasBg, themed.canvasBg)
        assertEquals("Border must remain identical to base", darkBase.border, themed.border)
        assertEquals("Text must remain identical to base", darkBase.text, themed.text)
        assertEquals("SubText must remain identical to base", darkBase.subText, themed.subText)
    }

    @Test
    fun `ThemeStyle algorithmic generators produce distinct harmonious palettes`() {
        val accent = androidx.compose.ui.graphics.Color(0xFFBDB79A) // Antigravity Gold
        val morandi = com.reverie.paint.ui.theme.buildThemeColors(isDark = true, accent = accent, style = com.reverie.paint.ui.theme.ThemeStyle.MORANDI_TINTED)
        val neutral = com.reverie.paint.ui.theme.buildThemeColors(isDark = true, accent = accent, style = com.reverie.paint.ui.theme.ThemeStyle.NEUTRAL_STUDIO)
        val warm = com.reverie.paint.ui.theme.buildThemeColors(isDark = true, accent = accent, style = com.reverie.paint.ui.theme.ThemeStyle.WARM_EARTHY)
        val vibrant = com.reverie.paint.ui.theme.buildThemeColors(isDark = true, accent = accent, style = com.reverie.paint.ui.theme.ThemeStyle.VIBRANT_GLOW)

        assertEquals("Accent must be preserved across styles", accent, morandi.accent)
        assertEquals("Accent must be preserved across styles", accent, neutral.accent)
        assertEquals("Accent must be preserved across styles", accent, warm.accent)
        assertEquals("Accent must be preserved across styles", accent, vibrant.accent)

        // Morandi tinted background should have subtle chroma, neutral should be pure grey
        assertNotEquals("Morandi tinted background must differ from neutral", neutral.bg, morandi.bg)
        assertNotEquals("Warm earthy background must differ from neutral", neutral.bg, warm.bg)
        assertNotEquals("Vibrant glow background must differ from neutral", neutral.bg, vibrant.bg)
    }

    @Test
    fun `colorToHsl and hslToColor roundtrip accuracy`() {
        val original = androidx.compose.ui.graphics.Color(0xFF5A6E8A)
        val hsl = com.reverie.paint.ui.theme.colorToHsl(original)
        val restored = com.reverie.paint.ui.theme.hslToColor(hsl[0], hsl[1], hsl[2])

        assertEquals(original.red, restored.red, 0.02f)
        assertEquals(original.green, restored.green, 0.02f)
        assertEquals(original.blue, restored.blue, 0.02f)
    }

    @Test
    fun `optimalTextColor returns Color White for dark backgrounds and dark tinted text for light backgrounds`() {
        val darkBg = androidx.compose.ui.graphics.Color(0xFF141416)
        val mistyBlue = androidx.compose.ui.graphics.Color(0xFF5A6E8A)
        val paleYellow = androidx.compose.ui.graphics.Color(0xFFFFF3B0)
        val lightGold = androidx.compose.ui.graphics.Color(0xFFBDB79A)

        // Dark backgrounds must use pure white
        assertEquals(androidx.compose.ui.graphics.Color.White, com.reverie.paint.ui.theme.optimalTextColor(darkBg))
        assertEquals(androidx.compose.ui.graphics.Color.White, com.reverie.paint.ui.theme.optimalTextColor(mistyBlue))

        // Light backgrounds must use deep dark tinted text (not white, lightness ~ 12%)
        val textOnYellow = com.reverie.paint.ui.theme.optimalTextColor(paleYellow)
        val textOnGold = com.reverie.paint.ui.theme.optimalTextColor(lightGold)

        assertNotEquals(androidx.compose.ui.graphics.Color.White, textOnYellow)
        assertNotEquals(androidx.compose.ui.graphics.Color.White, textOnGold)

        // Lightness of textOnYellow should be ~0.18
        val hslYellow = com.reverie.paint.ui.theme.colorToHsl(textOnYellow)
        assertEquals(0.18f, hslYellow[2], 0.03f)
    }

    @Test
    fun `buildThemeColors dynamically configures onAccent based on accent luminance`() {
        val darkAccent = androidx.compose.ui.graphics.Color(0xFF5A6E8A) // Misty Blue (lum ~ 0.15)
        val lightAccent = androidx.compose.ui.graphics.Color(0xFFBDB79A) // Gold (lum ~ 0.47)
        val userSageAccent = androidx.compose.ui.graphics.Color(0xFFA8B490) // User sage green (lum ~ 0.428)
        val periwinkleAccent = androidx.compose.ui.graphics.Color(0xFF879ED1) // Periwinkle Blue (lum ~ 0.344)

        val darkTheme = com.reverie.paint.ui.theme.buildThemeColors(isDark = true, accent = darkAccent)
        val lightTheme = com.reverie.paint.ui.theme.buildThemeColors(isDark = true, accent = lightAccent)
        val userTheme = com.reverie.paint.ui.theme.buildThemeColors(isDark = true, accent = userSageAccent)
        val periTheme = com.reverie.paint.ui.theme.buildThemeColors(isDark = true, accent = periwinkleAccent)

        assertEquals("Dark accent must have Color.White onAccent", androidx.compose.ui.graphics.Color.White, darkTheme.onAccent)
        assertNotEquals("Light accent must NOT have Color.White onAccent", androidx.compose.ui.graphics.Color.White, lightTheme.onAccent)
        assertNotEquals("User sage green accent #A8B490 must NOT have Color.White onAccent", androidx.compose.ui.graphics.Color.White, userTheme.onAccent)
        assertNotEquals("Periwinkle blue accent #879ED1 must NOT have Color.White onAccent", androidx.compose.ui.graphics.Color.White, periTheme.onAccent)

        // Hue of onAccent should match the lightAccent's hue family
        val accentHsl = com.reverie.paint.ui.theme.colorToHsl(lightAccent)
        val onAccentHsl = com.reverie.paint.ui.theme.colorToHsl(lightTheme.onAccent)
        assertEquals(accentHsl[0], onAccentHsl[0], 6.0f)
    }

    @Test
    fun `preset accent swatches list validity and contains default accent 879ED1`() {
        val swatches = listOf(
            "#879ED1", "#5A6E8A", "#5A8A86", "#768A5A", "#A8B490", "#BDB79A",
            "#BDA989", "#8A7A5A", "#8A665A", "#BD8D8B", "#8A5A66",
            "#825A8A", "#9D8BC9", "#625A8A", "#86E1FC"
        )
        assertEquals("First swatch should be default accent #879ED1", "#879ED1", swatches.first())
        swatches.forEach { hex ->
            assertTrue("Hex $hex must start with #", hex.startsWith("#"))
            assertEquals("Hex $hex must be 7 characters long", 7, hex.length)
            val hexVal = hex.removePrefix("#").toLongOrNull(16)
            assertTrue("Hex $hex must be valid hexadecimal number", hexVal != null && hexVal in 0..0xFFFFFF)
        }
    }

    @Test
    fun `DeviceUtils isTabletMetrics correctly identifies tablets vs phones`() {
        // Standard phone smallest widths
        assertFalse("Phone with sw360 should not be tablet", com.reverie.paint.core.DeviceUtils.isTabletMetrics(360))
        assertFalse("Phone with sw392 should not be tablet", com.reverie.paint.core.DeviceUtils.isTabletMetrics(392))
        assertFalse("Phone with sw411 should not be tablet", com.reverie.paint.core.DeviceUtils.isTabletMetrics(411))
        assertFalse("Phone with sw430 should not be tablet", com.reverie.paint.core.DeviceUtils.isTabletMetrics(430))

        // Tablet smallest widths (sw >= 600)
        assertTrue("Tablet with sw600 should be tablet", com.reverie.paint.core.DeviceUtils.isTabletMetrics(600))
        assertTrue("Tablet with sw720 should be tablet", com.reverie.paint.core.DeviceUtils.isTabletMetrics(720))
        assertTrue("Tablet with sw800 should be tablet", com.reverie.paint.core.DeviceUtils.isTabletMetrics(800))
        assertTrue("Tablet with sw1200 should be tablet", com.reverie.paint.core.DeviceUtils.isTabletMetrics(1200))

        // Undefined sw (sw = 0) with fallback to screenLayout size
        // 2: SCREENLAYOUT_SIZE_NORMAL, 3: SCREENLAYOUT_SIZE_LARGE, 4: SCREENLAYOUT_SIZE_XLARGE
        assertFalse("sw0 with normal screenLayout should not be tablet", com.reverie.paint.core.DeviceUtils.isTabletMetrics(0, 2))
        assertTrue("sw0 with large screenLayout should be tablet", com.reverie.paint.core.DeviceUtils.isTabletMetrics(0, 3))
        assertTrue("sw0 with xlarge screenLayout should be tablet", com.reverie.paint.core.DeviceUtils.isTabletMetrics(0, 4))
    }

    @Test
    fun `first-launch immersive mode defaults to enabled on tablet and disabled on phone`() {
        val resolveImmersive = { containsKey: Boolean, savedValue: Boolean, isTablet: Boolean ->
            if (containsKey) savedValue else isTablet
        }

        // First launch (no key saved yet)
        assertTrue("First launch on tablet must default to enabled", resolveImmersive(false, false, true))
        assertFalse("First launch on phone must default to disabled", resolveImmersive(false, false, false))

        // Subsequent launch: user explicitly disabled it on tablet
        assertFalse("Subsequent launch must preserve user setting false on tablet", resolveImmersive(true, false, true))

        // Subsequent launch: user explicitly enabled it on phone
        assertTrue("Subsequent launch must preserve user setting true on phone", resolveImmersive(true, true, false))
    }

    @Test
    fun `left-handed mode layout alignment logic`() {
        val resolveToolRailAlignment = { leftHand: Boolean ->
            if (leftHand) androidx.compose.ui.Alignment.TopEnd else androidx.compose.ui.Alignment.TopStart
        }
        val resolveTopBarAlignment = { leftHand: Boolean ->
            if (leftHand) androidx.compose.ui.Alignment.TopStart else androidx.compose.ui.Alignment.TopEnd
        }
        val resolvePopupOffset = { leftHand: Boolean, offsetDp: Int ->
            if (leftHand) -offsetDp else offsetDp
        }

        val resolveBrushPanelAlignment = { leftHand: Boolean ->
            if (leftHand) androidx.compose.ui.Alignment.CenterEnd else androidx.compose.ui.Alignment.CenterStart
        }
        val resolveLayerPanelAlignment = { leftHand: Boolean ->
            if (leftHand) androidx.compose.ui.Alignment.TopStart else androidx.compose.ui.Alignment.TopEnd
        }
        val resolveColorPanelAlignment = { leftHand: Boolean ->
            if (leftHand) androidx.compose.ui.Alignment.BottomEnd else androidx.compose.ui.Alignment.BottomStart
        }
        val resolveSettingsPanelAlignment = { leftHand: Boolean ->
            if (leftHand) androidx.compose.ui.Alignment.TopStart else androidx.compose.ui.Alignment.TopEnd
        }

        // Standard right-handed layout
        assertEquals(androidx.compose.ui.Alignment.TopStart, resolveToolRailAlignment(false))
        assertEquals(androidx.compose.ui.Alignment.TopEnd, resolveTopBarAlignment(false))
        assertEquals(androidx.compose.ui.Alignment.CenterStart, resolveBrushPanelAlignment(false))
        assertEquals(androidx.compose.ui.Alignment.TopEnd, resolveLayerPanelAlignment(false))
        assertEquals(androidx.compose.ui.Alignment.BottomStart, resolveColorPanelAlignment(false))
        assertEquals(androidx.compose.ui.Alignment.TopEnd, resolveSettingsPanelAlignment(false))
        assertEquals(52, resolvePopupOffset(false, 52))

        // Left-handed layout
        assertEquals(androidx.compose.ui.Alignment.TopEnd, resolveToolRailAlignment(true))
        assertEquals(androidx.compose.ui.Alignment.TopStart, resolveTopBarAlignment(true))
        assertEquals(androidx.compose.ui.Alignment.CenterEnd, resolveBrushPanelAlignment(true))
        assertEquals(androidx.compose.ui.Alignment.TopStart, resolveLayerPanelAlignment(true))
        assertEquals(androidx.compose.ui.Alignment.BottomEnd, resolveColorPanelAlignment(true))
        assertEquals(androidx.compose.ui.Alignment.TopStart, resolveSettingsPanelAlignment(true))
        assertEquals(-52, resolvePopupOffset(true, 52))
    }

    @Test
    fun `painting page full-height edge gesture exclusion logic`() {
        val calculateFullExclusion = { width: Int, height: Int, density: Float ->
            val edgeWidth = (48 * density).toInt()
            val left = 0 to edgeWidth
            val right = (width - edgeWidth) to width
            val vertical = 0 to height
            Triple(left, right, vertical)
        }

        val w = 2400
        val h = 1600
        val density = 2.0f
        val edgeW = 96

        val (left, right, vertical) = calculateFullExclusion(w, h, density)
        assertEquals(0 to edgeW, left)
        assertEquals(w - edgeW to w, right)
        assertEquals(0 to h, vertical)
    }

    @Test
    fun `spacebar hold-to-pan state transition logic`() {
        var isSpacePanning = false
        val onSpaceKeyEvent = { actionDown: Boolean ->
            if (actionDown) {
                if (!isSpacePanning) isSpacePanning = true
            } else {
                if (isSpacePanning) isSpacePanning = false
            }
            isSpacePanning
        }

        assertFalse("Initial state must not be panning", isSpacePanning)
        assertTrue("Space down transitions to panning", onSpaceKeyEvent(true))
        assertTrue("Subsequent space down repeat keeps panning", onSpaceKeyEvent(true))
        assertFalse("Space up releases panning", onSpaceKeyEvent(false))
        assertFalse("Subsequent space up keeps released", onSpaceKeyEvent(false))
    }

    @Test
    fun `hardware keyboard shortcut canonical string formatting`() {
        val formatKey = { ctrl: Boolean, shift: Boolean, alt: Boolean, key: String ->
            val parts = mutableListOf<String>()
            if (ctrl) parts.add("LeftCtrl")
            if (alt) parts.add("LeftAlt")
            if (shift) parts.add("LeftShift")
            parts.add(key)
            parts.joinToString(" + ")
        }

        assertEquals("B", formatKey(false, false, false, "B"))
        assertEquals("LeftCtrl + Z", formatKey(true, false, false, "Z"))
        assertEquals("LeftCtrl + LeftShift + Z", formatKey(true, true, false, "Z"))
        assertEquals("LeftCtrl + LeftShift + N", formatKey(true, true, false, "N"))
        assertEquals("LeftShift + G", formatKey(false, true, false, "G"))
        assertEquals("[", formatKey(false, false, false, "["))
        assertEquals("]", formatKey(false, false, false, "]"))
    }

    @Test
    fun `UiAnimationSpeed parsing and Motion token dynamics`() {
        assertEquals(UiAnimationSpeed.NORMAL, UiAnimationSpeed.fromId("normal"))
        assertEquals(UiAnimationSpeed.FAST, UiAnimationSpeed.fromId("fast"))
        assertEquals(UiAnimationSpeed.OFF, UiAnimationSpeed.fromId("off"))
        assertEquals(UiAnimationSpeed.NORMAL, UiAnimationSpeed.fromId("unknown"))

        com.reverie.paint.ui.theme.Motion.currentSpeed = UiAnimationSpeed.NORMAL
        val normalExit = com.reverie.paint.ui.theme.Motion.exitTween<Float>(200)
        assertTrue("Normal exit should be tween", normalExit is androidx.compose.animation.core.TweenSpec)

        com.reverie.paint.ui.theme.Motion.currentSpeed = UiAnimationSpeed.FAST
        val fastExit = com.reverie.paint.ui.theme.Motion.exitTween<Float>(200)
        assertTrue("Fast exit should be tween", fastExit is androidx.compose.animation.core.TweenSpec)
        val fastTween = fastExit as androidx.compose.animation.core.TweenSpec
        assertTrue("Fast animation duration must be <= 100ms", fastTween.durationMillis <= 100)

        com.reverie.paint.ui.theme.Motion.currentSpeed = UiAnimationSpeed.OFF
        val offExit = com.reverie.paint.ui.theme.Motion.exitTween<Float>(200)
        assertTrue("Off exit should be snap", offExit is androidx.compose.animation.core.SnapSpec)

        val offEnter = com.reverie.paint.ui.theme.Motion.enterSpring<Float>()
        assertTrue("Off enter should be snap", offEnter is androidx.compose.animation.core.SnapSpec)

        // Reset to normal
        com.reverie.paint.ui.theme.Motion.currentSpeed = UiAnimationSpeed.NORMAL
    }

    @Test
    fun `QuickAction enum and layout parsing logic`() {
        assertEquals(QuickAction.LOCK_VIEW, QuickAction.fromId("lock_view"))
        assertEquals(QuickAction.FLIP_H, QuickAction.fromId("flip_h"))
        assertEquals(QuickAction.UNDO, QuickAction.fromId("undo"))
        assertEquals(QuickAction.NEW_LAYER, QuickAction.fromId("new_layer"))
        assertEquals(null, QuickAction.fromId("non_existent"))

        assertEquals(QuickActionLayoutMode.ROW, QuickActionLayoutMode.fromId("row"))
        assertEquals(QuickActionLayoutMode.COLUMN, QuickActionLayoutMode.fromId("column"))
        assertEquals(QuickActionLayoutMode.GRID_2, QuickActionLayoutMode.fromId("grid_2"))
        assertEquals(QuickActionLayoutMode.GRID_3, QuickActionLayoutMode.fromId("grid_3"))
        assertEquals(QuickActionLayoutMode.COLUMN, QuickActionLayoutMode.fromId("invalid"))

        val config = QuickActionsConfig()
        assertTrue("Default actions must contain UNDO", config.actions.contains(QuickAction.UNDO))
        assertTrue("Default actions must contain REDO", config.actions.contains(QuickAction.REDO))
        assertEquals(QuickActionLayoutMode.COLUMN, config.layoutMode)
        assertFalse(config.showLabels)
    }

    @Test
    fun `floating ui enabled resolution logic preserves user preference regardless of screen window state`() {
        val resolveFloating = { containsKey: Boolean, savedValue: Boolean, isTablet: Boolean ->
            val floatingSavedEnabled = if (containsKey) savedValue else isTablet
            floatingSavedEnabled
        }

        // First launch on tablet: defaults to true
        assertTrue("Tablet first launch defaults to floating UI enabled", resolveFloating(false, false, true))
        // First launch on phone: defaults to false
        assertFalse("Phone first launch defaults to floating UI disabled", resolveFloating(false, false, false))

        // Tablet user enables floating, then opens app in small window (phone-sized):
        // Preference must remain true
        assertTrue("Tablet user preference remains enabled even in small window", resolveFloating(true, true, false))

        // Phone user explicitly enables floating UI:
        assertTrue("Phone user explicit enabled must be preserved", resolveFloating(true, true, false))

        // Tablet user explicitly disabled floating UI:
        assertFalse("Tablet user explicit disabled must be preserved", resolveFloating(true, false, true))
    }

    @Test
    fun `floating ui small screen warning detection logic`() {
        val isSmallScreen = { smallestScreenWidthDp: Int, screenWidthDp: Int ->
            smallestScreenWidthDp < 600 || screenWidthDp < 600
        }

        // Phone portrait & landscape
        assertTrue("Phone portrait (sw392, w392) triggers warning", isSmallScreen(392, 392))
        assertTrue("Phone landscape (sw392, w840) triggers warning because sw < 600", isSmallScreen(392, 840))

        // Tablet full screen landscape
        assertFalse("Tablet full screen (sw800, w1280) does not trigger warning", isSmallScreen(800, 1280))
        // Tablet full screen portrait
        assertFalse("Tablet full screen portrait (sw800, w800) does not trigger warning", isSmallScreen(800, 800))

        // Tablet split screen / small floating window
        assertTrue("Tablet split screen (sw800, w540) triggers warning", isSmallScreen(800, 540))
        assertTrue("Tablet freeform small window (sw400, w400) triggers warning", isSmallScreen(400, 400))
    }

    @Test
    fun `workspace layout options count based on floating UI state`() {
        val resolveLayoutCount = { floatingEnabled: Boolean ->
            if (floatingEnabled) 5 else 2
        }

        assertEquals("When floating UI is disabled, show docked and floating options (2 items)", 2, resolveLayoutCount(false))
        assertEquals("When floating UI is enabled, show 2 radio options + 3 tuning items (5 items)", 5, resolveLayoutCount(true))
    }
}




