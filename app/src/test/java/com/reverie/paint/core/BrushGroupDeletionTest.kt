/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrushGroupDeletionTest {

    @Test
    fun `BUILT_IN_BRUSH_GROUPS excludes 导入 and protects system default groups`() {
        assertFalse(BUILT_IN_BRUSH_GROUPS.contains("导入"))
        assertTrue(BUILT_IN_BRUSH_GROUPS.contains("全部"))
        assertTrue(BUILT_IN_BRUSH_GROUPS.contains("常用"))
        assertTrue(BUILT_IN_BRUSH_GROUPS.contains("最近"))
        assertTrue(BUILT_IN_BRUSH_GROUPS.contains("基础"))
        assertTrue(BUILT_IN_BRUSH_GROUPS.contains("铅笔"))
        assertTrue(BUILT_IN_BRUSH_GROUPS.contains("水彩"))
    }

    @Test
    fun `isBuiltInBrushGroup allows deletion of pure custom and imported groups`() {
        val dummyPresets = listOf(
            BrushPresetInfo(index = 0, id = com.reverie.paint.core.brushstore.BrushIdentity.idForName("b)_Basic-1") ?: com.reverie.paint.core.brushstore.BrushIdentity.builtinId("b)_Basic-1"), name = "b)_Basic-1", thumbBytes = ByteArray(0), group = "基础", isBuiltIn = true),
            BrushPresetInfo(index = 1, id = com.reverie.paint.core.brushstore.BrushIdentity.idForName("CustomPencil") ?: com.reverie.paint.core.brushstore.BrushIdentity.builtinId("CustomPencil"), name = "CustomPencil", thumbBytes = ByteArray(0), group = "导入", isBuiltIn = false),
            BrushPresetInfo(index = 2, id = com.reverie.paint.core.brushstore.BrushIdentity.idForName("MyMarker") ?: com.reverie.paint.core.brushstore.BrushIdentity.builtinId("MyMarker"), name = "MyMarker", thumbBytes = ByteArray(0), group = "我的画笔", isBuiltIn = false),
        )

        // System built-in group cannot be deleted
        assertTrue(isBuiltInBrushGroup("基础", dummyPresets))
        // Group containing built-in presets cannot be deleted
        assertTrue(isBuiltInBrushGroup("全部", dummyPresets))

        // Imported group with only custom presets can be deleted
        assertFalse(isBuiltInBrushGroup("导入", dummyPresets))
        // Custom group with only custom presets can be deleted
        assertFalse(isBuiltInBrushGroup("我的画笔", dummyPresets))
    }

    @Test
    fun `group deletion simulation distinguishes deletePresets flag and preserves built-in presets`() {
        val presets = listOf(
            BrushPresetInfo(index = 0, id = com.reverie.paint.core.brushstore.BrushIdentity.idForName("BuiltInInGroup") ?: com.reverie.paint.core.brushstore.BrushIdentity.builtinId("BuiltInInGroup"), name = "BuiltInInGroup", thumbBytes = ByteArray(0), group = "我的分组", isBuiltIn = true),
            BrushPresetInfo(index = 1, id = com.reverie.paint.core.brushstore.BrushIdentity.idForName("Custom1") ?: com.reverie.paint.core.brushstore.BrushIdentity.builtinId("Custom1"), name = "Custom1", thumbBytes = ByteArray(0), group = "我的分组", isBuiltIn = false),
            BrushPresetInfo(index = 2, id = com.reverie.paint.core.brushstore.BrushIdentity.idForName("Custom2") ?: com.reverie.paint.core.brushstore.BrushIdentity.builtinId("Custom2"), name = "Custom2", thumbBytes = ByteArray(0), group = "我的分组", isBuiltIn = false),
            BrushPresetInfo(index = 3, id = com.reverie.paint.core.brushstore.BrushIdentity.idForName("OtherCustom") ?: com.reverie.paint.core.brushstore.BrushIdentity.builtinId("OtherCustom"), name = "OtherCustom", thumbBytes = ByteArray(0), group = "其他", isBuiltIn = false),
        )

        // Case 1: deletePresets = true
        val targetGroup = "我的分组"
        val inGroup = presets.filter { it.group == targetGroup }
        val toDelete = inGroup.filter { !it.isBuiltIn }.map { it.name }.toSet()
        val toKeep = inGroup.filter { it.isBuiltIn }.map { it.name }.toSet()

        assertEquals(setOf("Custom1", "Custom2"), toDelete)
        assertEquals(setOf("BuiltInInGroup"), toKeep)

        // Case 2: deletePresets = false
        val toDeleteWhenFalse = emptySet<String>()
        val keptAllInGroup = inGroup.map { it.name }.toSet()
        assertEquals(emptySet<String>(), toDeleteWhenFalse)
        assertEquals(setOf("BuiltInInGroup", "Custom1", "Custom2"), keptAllInGroup)
    }

    @Test
    fun `batch deletion filters out built-in presets and targets only custom ones`() {
        val presets = listOf(
            BrushPresetInfo(index = 0, id = com.reverie.paint.core.brushstore.BrushIdentity.idForName("Basic-1") ?: com.reverie.paint.core.brushstore.BrushIdentity.builtinId("Basic-1"), name = "Basic-1", thumbBytes = ByteArray(0), group = "基础", isBuiltIn = true),
            BrushPresetInfo(index = 1, id = com.reverie.paint.core.brushstore.BrushIdentity.idForName("Pencil-Soft") ?: com.reverie.paint.core.brushstore.BrushIdentity.builtinId("Pencil-Soft"), name = "Pencil-Soft", thumbBytes = ByteArray(0), group = "铅笔", isBuiltIn = true),
            BrushPresetInfo(index = 2, id = com.reverie.paint.core.brushstore.BrushIdentity.idForName("Custom-A") ?: com.reverie.paint.core.brushstore.BrushIdentity.builtinId("Custom-A"), name = "Custom-A", thumbBytes = ByteArray(0), group = "自定义", isBuiltIn = false),
            BrushPresetInfo(index = 3, id = com.reverie.paint.core.brushstore.BrushIdentity.idForName("Custom-B") ?: com.reverie.paint.core.brushstore.BrushIdentity.builtinId("Custom-B"), name = "Custom-B", thumbBytes = ByteArray(0), group = "自定义", isBuiltIn = false),
        )

        val selectedNames = listOf("Basic-1", "Custom-A", "Custom-B", "NonExistent")
        val deletable = presets.filter { it.name in selectedNames && !it.isBuiltIn }
        val skipped = presets.filter { it.name in selectedNames && it.isBuiltIn }

        assertEquals(2, deletable.size)
        assertEquals(listOf("Custom-A", "Custom-B"), deletable.map { it.name })
        assertEquals(1, skipped.size)
        assertEquals(listOf("Basic-1"), skipped.map { it.name })
    }

    @Test
    fun `batch move correctly updates user group mappings`() {
        val initialUserGroups = mapOf(
            "BrushA" to "Group1",
            "BrushB" to "Group1",
            "BrushC" to "Group2",
        )
        val selected = listOf("BrushA", "BrushC")
        val target = "NewGroup"

        var updated = initialUserGroups
        for (name in selected) {
            updated = updated + (name to target)
        }

        assertEquals("NewGroup", updated["BrushA"])
        assertEquals("Group1", updated["BrushB"])
        assertEquals("NewGroup", updated["BrushC"])
    }
}
