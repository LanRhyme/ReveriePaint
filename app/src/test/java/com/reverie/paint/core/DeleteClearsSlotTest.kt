/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import com.reverie.paint.core.brushstore.BrushIdentity
import com.reverie.paint.core.brushstore.BrushImportPipeline
import com.reverie.paint.core.brushstore.BrushOverrideStore
import com.reverie.paint.core.brushstore.BrushThumbStore
import com.reverie.paint.core.brushstore.PresetSnapshotStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * v5 S3: 单删、删组、批量删三种情况，工具槽和 paramMemory 里都不再有该 id，缩略图同步清除。
 */
class DeleteClearsSlotTest {

    private lateinit var root: File
    private lateinit var presetDir: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("delete_clears_slot_test").toFile()
        presetDir = File(root, "paintoppresets").apply { mkdirs() }
        BrushIdentity.open(root)
        PresetSnapshotStore.open(root)
        BrushOverrideStore.open(root)
        BrushThumbStore.open(root)
    }

    @After
    fun tearDown() {
        BrushThumbStore.resetForTest()
        root.walkBottomUp().forEach { it.setWritable(true) }
        root.deleteRecursively()
    }

    @Test
    fun `single delete clears slot and paramMemory and thumb`() {
        val id1 = "imp:brush_1"
        val name1 = "CustomBrush1"
        val id2 = "imp:brush_2"
        val name2 = "CustomBrush2"

        BrushIdentity.register(BrushIdentity.Entry(id1, name1, "kpp", BrushIdentity.Kind.IMPORTED))
        BrushIdentity.register(BrushIdentity.Entry(id2, name2, "kpp", BrushIdentity.Kind.IMPORTED))
        BrushThumbStore.put(id1, byteArrayOf(1, 2, 3))
        BrushThumbStore.put(id2, byteArrayOf(4, 5, 6))

        var toolBrushStates = mapOf(
            "brush" to PaintViewModel.ToolBrushState(
                presetIndex = 10,
                presetId = id1,
                paramMemory = mapOf(
                    id1 to listOf(30.0, 1.0, 1.0),
                    id2 to listOf(40.0, 0.8, 1.0),
                )
            ),
            "eraser" to PaintViewModel.ToolBrushState(
                presetIndex = 20,
                presetId = id2,
                paramMemory = mapOf(
                    id1 to listOf(50.0, 1.0, 1.0),
                )
            )
        )

        // 模拟单删 id1
        val deletedPresetId = BrushIdentity.idForName(name1)
        assertEquals(id1, deletedPresetId)

        if (deletedPresetId != null) {
            BrushThumbStore.delete(deletedPresetId)
        }
        BrushImportPipeline.forget(com.reverie.paint.core.brushstore.BrushIdentity.idForName(name1) ?: "")

        toolBrushStates = toolBrushStates.mapValues { (_, s) ->
            val nextS = if (deletedPresetId != null && s.presetId == deletedPresetId) {
                s.copy(presetId = "", presetIndex = -1)
            } else {
                s
            }
            if (deletedPresetId != null) nextS.copy(paramMemory = nextS.paramMemory - deletedPresetId) else nextS
        }

        // 断言：工具槽不再持有 id1
        assertEquals("", toolBrushStates["brush"]?.presetId)
        assertEquals(-1, toolBrushStates["brush"]?.presetIndex)
        assertFalse(toolBrushStates["brush"]!!.paramMemory.containsKey(id1))
        assertTrue(toolBrushStates["brush"]!!.paramMemory.containsKey(id2))

        // eraser 槽位原本不是 id1，保留；但其 paramMemory 里的 id1 被清除
        assertEquals(id2, toolBrushStates["eraser"]?.presetId)
        assertFalse(toolBrushStates["eraser"]!!.paramMemory.containsKey(id1))

        // 缩略图被删除
        assertNull(BrushThumbStore.get(id1))
        assertNull(BrushIdentity.idForName(name1))

        // 被删除的 id 解析返回 null
        val presets = listOf(BrushPresetInfo(index = 20, id = com.reverie.paint.core.brushstore.BrushIdentity.idForName(name2) ?: com.reverie.paint.core.brushstore.BrushIdentity.builtinId(name2), name = name2, thumbBytes = ByteArray(0), group = "自定义"))
        assertNull(ToolPresetMemory.resolve(id1, 10, presets))
    }

    @Test
    fun `group delete clears all custom preset slots and paramMemory and thumbs`() {
        val id1 = "imp:grp_brush_1"
        val name1 = "GrpBrush1"
        val id2 = "imp:grp_brush_2"
        val name2 = "GrpBrush2"
        val otherId = "imp:other_brush"
        val otherName = "OtherBrush"

        BrushIdentity.register(BrushIdentity.Entry(id1, name1, "kpp", BrushIdentity.Kind.IMPORTED))
        BrushIdentity.register(BrushIdentity.Entry(id2, name2, "kpp", BrushIdentity.Kind.IMPORTED))
        BrushIdentity.register(BrushIdentity.Entry(otherId, otherName, "kpp", BrushIdentity.Kind.IMPORTED))

        BrushThumbStore.put(id1, byteArrayOf(1, 1))
        BrushThumbStore.put(id2, byteArrayOf(2, 2))
        BrushThumbStore.put(otherId, byteArrayOf(3, 3))

        val groupPresets = listOf(
            BrushPresetInfo(index = 1, id = com.reverie.paint.core.brushstore.BrushIdentity.idForName(name1) ?: com.reverie.paint.core.brushstore.BrushIdentity.builtinId(name1), name = name1, thumbBytes = ByteArray(0), group = "测试组", isBuiltIn = false),
            BrushPresetInfo(index = 2, id = com.reverie.paint.core.brushstore.BrushIdentity.idForName(name2) ?: com.reverie.paint.core.brushstore.BrushIdentity.builtinId(name2), name = name2, thumbBytes = ByteArray(0), group = "测试组", isBuiltIn = false),
            BrushPresetInfo(index = 0, id = com.reverie.paint.core.brushstore.BrushIdentity.idForName("BuiltIn1") ?: com.reverie.paint.core.brushstore.BrushIdentity.builtinId("BuiltIn1"), name = "BuiltIn1", thumbBytes = ByteArray(0), group = "测试组", isBuiltIn = true),
        )

        var toolBrushStates = mapOf(
            "brush" to PaintViewModel.ToolBrushState(
                presetIndex = 1,
                presetId = id1,
                paramMemory = mapOf(
                    id1 to listOf(25.0, 1.0, 1.0),
                    id2 to listOf(35.0, 1.0, 1.0),
                    otherId to listOf(45.0, 1.0, 1.0),
                )
            ),
            "eraser" to PaintViewModel.ToolBrushState(
                presetIndex = 2,
                presetId = id2,
                paramMemory = mapOf(
                    id2 to listOf(60.0, 1.0, 1.0),
                )
            )
        )

        // 模拟删组逻辑
        val customPresets = groupPresets.filter { !it.isBuiltIn }
        val deletedIds = customPresets.mapNotNull { BrushIdentity.idForName(it.name) }.toSet()
        assertEquals(setOf(id1, id2), deletedIds)

        for (id in deletedIds) {
            BrushThumbStore.delete(id)
        }
        for (p in customPresets) {
            BrushImportPipeline.forget(com.reverie.paint.core.brushstore.BrushIdentity.idForName(p.name) ?: "")
        }

        toolBrushStates = toolBrushStates.mapValues { (_, s) ->
            val nextS = if (s.presetId in deletedIds) s.copy(presetId = "", presetIndex = -1) else s
            nextS.copy(paramMemory = nextS.paramMemory - deletedIds)
        }

        // 校验：组内两个自定义笔刷的槽位和 paramMemory 全部清除
        assertEquals("", toolBrushStates["brush"]?.presetId)
        assertEquals(-1, toolBrushStates["brush"]?.presetIndex)
        assertFalse(toolBrushStates["brush"]!!.paramMemory.containsKey(id1))
        assertFalse(toolBrushStates["brush"]!!.paramMemory.containsKey(id2))
        assertTrue(toolBrushStates["brush"]!!.paramMemory.containsKey(otherId))

        assertEquals("", toolBrushStates["eraser"]?.presetId)
        assertFalse(toolBrushStates["eraser"]!!.paramMemory.containsKey(id2))

        assertNull(BrushThumbStore.get(id1))
        assertNull(BrushThumbStore.get(id2))
        // 组外笔刷不受影响
        assertTrue(BrushThumbStore.get(otherId) != null)
    }

    @Test
    fun `batch delete clears multiple preset slots and paramMemory and thumbs`() {
        val id1 = "imp:batch_1"
        val name1 = "Batch1"
        val id2 = "imp:batch_2"
        val name2 = "Batch2"
        val id3 = "imp:batch_3"
        val name3 = "Batch3"

        BrushIdentity.register(BrushIdentity.Entry(id1, name1, "kpp", BrushIdentity.Kind.IMPORTED))
        BrushIdentity.register(BrushIdentity.Entry(id2, name2, "kpp", BrushIdentity.Kind.IMPORTED))
        BrushIdentity.register(BrushIdentity.Entry(id3, name3, "kpp", BrushIdentity.Kind.IMPORTED))

        BrushThumbStore.put(id1, byteArrayOf(1))
        BrushThumbStore.put(id2, byteArrayOf(2))
        BrushThumbStore.put(id3, byteArrayOf(3))

        val allPresets = listOf(
            BrushPresetInfo(index = 10, id = com.reverie.paint.core.brushstore.BrushIdentity.idForName(name1) ?: com.reverie.paint.core.brushstore.BrushIdentity.builtinId(name1), name = name1, thumbBytes = ByteArray(0), group = "常用", isBuiltIn = false),
            BrushPresetInfo(index = 11, id = com.reverie.paint.core.brushstore.BrushIdentity.idForName(name2) ?: com.reverie.paint.core.brushstore.BrushIdentity.builtinId(name2), name = name2, thumbBytes = ByteArray(0), group = "常用", isBuiltIn = false),
            BrushPresetInfo(index = 12, id = com.reverie.paint.core.brushstore.BrushIdentity.idForName(name3) ?: com.reverie.paint.core.brushstore.BrushIdentity.builtinId(name3), name = name3, thumbBytes = ByteArray(0), group = "常用", isBuiltIn = false),
        )

        var toolBrushStates = mapOf(
            "brush" to PaintViewModel.ToolBrushState(
                presetIndex = 11,
                presetId = id2,
                paramMemory = mapOf(
                    id1 to listOf(10.0, 1.0, 1.0),
                    id2 to listOf(20.0, 1.0, 1.0),
                    id3 to listOf(30.0, 1.0, 1.0),
                )
            )
        )

        // 批量删除 name1 与 name2
        val targets = allPresets.filter { it.name in listOf(name1, name2) && !it.isBuiltIn }
        val deletedIds = targets.mapNotNull { BrushIdentity.idForName(it.name) }.toSet()
        assertEquals(setOf(id1, id2), deletedIds)

        for (id in deletedIds) {
            BrushThumbStore.delete(id)
        }
        for (p in targets) {
            BrushImportPipeline.forget(com.reverie.paint.core.brushstore.BrushIdentity.idForName(p.name) ?: "")
        }

        toolBrushStates = toolBrushStates.mapValues { (_, s) ->
            val nextS = if (s.presetId in deletedIds) s.copy(presetId = "", presetIndex = -1) else s
            nextS.copy(paramMemory = nextS.paramMemory - deletedIds)
        }

        assertEquals("", toolBrushStates["brush"]?.presetId)
        assertEquals(-1, toolBrushStates["brush"]?.presetIndex)
        assertFalse(toolBrushStates["brush"]!!.paramMemory.containsKey(id1))
        assertFalse(toolBrushStates["brush"]!!.paramMemory.containsKey(id2))
        assertTrue(toolBrushStates["brush"]!!.paramMemory.containsKey(id3))

        assertNull(BrushThumbStore.get(id1))
        assertNull(BrushThumbStore.get(id2))
        assertTrue(BrushThumbStore.get(id3) != null)
    }
}
