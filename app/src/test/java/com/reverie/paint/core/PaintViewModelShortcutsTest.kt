/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import com.reverie.paint.model.ALL_SHORTCUT_DEFINITIONS
import com.reverie.paint.model.ShortcutCategory
import com.reverie.paint.model.isShortcutMatch
import com.reverie.paint.model.normalizeShortcut
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PaintViewModelShortcutsTest {

    @Test
    fun `default shortcut mappings align with desktop standards`() {
        val undoDef = ALL_SHORTCUT_DEFINITIONS.firstOrNull { it.id == "undo" }
        assertNotNull("Undo definition should exist", undoDef)
        assertEquals("LeftCtrl + Z", undoDef?.defaultKey)

        val redoDef = ALL_SHORTCUT_DEFINITIONS.firstOrNull { it.id == "redo" }
        assertNotNull("Redo definition should exist", redoDef)
        assertEquals("LeftCtrl + LeftShift + Z", redoDef?.defaultKey)
        // Krita/PS 的备用重做键
        assertTrue(redoDef?.defaultKeys?.contains("LeftCtrl + Y") == true)

        val brushDef = ALL_SHORTCUT_DEFINITIONS.firstOrNull { it.id == "tool_brush" }
        assertNotNull("Brush tool definition should exist", brushDef)
        assertEquals("B", brushDef?.defaultKey)

        val eraserDef = ALL_SHORTCUT_DEFINITIONS.firstOrNull { it.id == "tool_eraser" }
        assertNotNull("Eraser tool definition should exist", eraserDef)
        assertEquals("E", eraserDef?.defaultKey)

        // 变换工具同时挂 V 与 Ctrl+T (Krita 与 PS 习惯都照顾)
        val transformDef = ALL_SHORTCUT_DEFINITIONS.firstOrNull { it.id == "tool_transform" }
        assertEquals(listOf("V", "LeftCtrl + T"), transformDef?.defaultKeys)
    }

    @Test
    fun `disable touch shortcut and quick action are properly registered`() {
        val touchDef = ALL_SHORTCUT_DEFINITIONS.firstOrNull { it.id == "disable_touch" }
        assertNotNull("Disable touch shortcut definition should exist", touchDef)
        assertEquals("无", touchDef?.defaultKey)
        assertEquals(ShortcutCategory.PAINTING, touchDef?.category)

        val quickAction = com.reverie.paint.model.QuickAction.fromId("disable_touch")
        assertNotNull("QuickAction.DISABLE_TOUCH should exist", quickAction)
        assertEquals(com.reverie.paint.model.QuickAction.DISABLE_TOUCH, quickAction)
        // Ensure DISABLE_TOUCH is not in default actions list (as per user decision)
        assertFalse(
            "DISABLE_TOUCH should not be in DEFAULT_ACTIONS",
            com.reverie.paint.model.QuickAction.DEFAULT_ACTIONS.contains(com.reverie.paint.model.QuickAction.DISABLE_TOUCH),
        )
    }

    @Test
    fun `normalizeShortcut normalizes modifiers and orders correctly`() {
        assertEquals("CTRL+Z", normalizeShortcut("Ctrl + Z"))
        assertEquals("CTRL+Z", normalizeShortcut("LeftCtrl + Z"))
        assertEquals("CTRL+Z", normalizeShortcut("RightCtrl + Z"))
        assertEquals("CTRL+SHIFT+Z", normalizeShortcut("Ctrl + Shift + Z"))
        assertEquals("CTRL+SHIFT+Z", normalizeShortcut("Shift + Ctrl + Z"))
        assertEquals("CTRL+SHIFT+Z", normalizeShortcut("LeftCtrl + LeftShift + Z"))
        assertEquals("CTRL+ALT+SHIFT+Z", normalizeShortcut("Shift + Alt + Ctrl + Z"))
        assertEquals("SPACE(长按)", normalizeShortcut("Space(长按)"))
        assertEquals("F1", normalizeShortcut("F1"))
        assertEquals("BUTTON 1", normalizeShortcut("Button 1"))
        assertEquals("ZOOMIN", normalizeShortcut("ZoomIn"))
        assertEquals("", normalizeShortcut("无"))
        assertEquals("", normalizeShortcut("none"))
    }

    @Test
    fun `isShortcutMatch compares combinations irrespective of modifier naming and order`() {
        assertTrue(isShortcutMatch("LeftCtrl + Z", "Ctrl + Z"))
        assertTrue(isShortcutMatch("Ctrl + Shift + Z", "LeftCtrl + LeftShift + Z"))
        assertTrue(isShortcutMatch("Shift + Ctrl + Z", "Ctrl + Shift + Z"))
        assertTrue(isShortcutMatch("Button 1", "button 1"))
        assertTrue(isShortcutMatch("F1", "f1"))
        assertTrue(isShortcutMatch("ZoomIn", "zoomin"))
        assertFalse(isShortcutMatch("Ctrl + Z", "Ctrl + Y"))
        assertFalse(isShortcutMatch("Ctrl + Z", "无"))
    }
}
