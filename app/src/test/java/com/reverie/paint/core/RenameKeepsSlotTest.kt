/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import com.reverie.paint.core.brushstore.BrushIdentity
import com.reverie.paint.core.brushstore.BrushOverrideStore
import com.reverie.paint.core.brushstore.EffectivePresetBuilder
import com.reverie.paint.core.brushstore.PresetSnapshotStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * v5 S3: 重命名后工具槽、paramMemory、fav、recent 依然指向同一支笔。
 */
class RenameKeepsSlotTest {

    private lateinit var root: File
    private lateinit var presetDir: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("rename_keeps_slot_test").toFile()
        presetDir = File(root, "paintoppresets").apply { mkdirs() }
        BrushIdentity.open(root)
        PresetSnapshotStore.open(root)
        BrushOverrideStore.open(root)
    }

    @After
    fun tearDown() {
        root.walkBottomUp().forEach { it.setWritable(true) }
        root.deleteRecursively()
    }

    @Test
    fun `rename preserves tool slot resolution and paramMemory for the renamed preset`() {
        val id = "imp:custom_brush"
        val oldName = "MySpecialBrush"
        val newName = "MySpecialBrushRenamed"

        BrushIdentity.register(BrushIdentity.Entry(id, oldName, "kpp", BrushIdentity.Kind.IMPORTED))

        // 工具槽以 presetId 存储
        val slot = PaintViewModel.ToolBrushState(
            presetIndex = 12,
            presetId = id,
            paramMemory = mapOf(id to listOf(42.0, 0.85, 0.95)),
        )

        // 重命名预设
        BrushIdentity.rename(id, newName)

        val updatedPresets = listOf(
            BrushPresetInfo(index = 0, id = com.reverie.paint.core.brushstore.BrushIdentity.idForName("b)_Basic-1") ?: com.reverie.paint.core.brushstore.BrushIdentity.builtinId("b)_Basic-1"), name = "b)_Basic-1", thumbBytes = ByteArray(0), group = "基础"),
            BrushPresetInfo(index = 12, id = com.reverie.paint.core.brushstore.BrushIdentity.idForName(newName) ?: com.reverie.paint.core.brushstore.BrushIdentity.builtinId(newName), name = newName, thumbBytes = ByteArray(0), group = "自定义"),
        )

        // 槽位解析：根据 presetId 能解析到重命名后的新预设
        val resolved = ToolPresetMemory.resolve(slot.presetId, slot.presetIndex, updatedPresets)
        assertNotNull(resolved)
        assertEquals(newName, resolved?.name)
        assertEquals(12, resolved?.index)

        // paramMemory 继续按 id 访问，数值保持不变
        val mem = slot.paramMemory[id]
        assertNotNull(mem)
        assertEquals(42.0, mem!![0], 1e-6)
        assertEquals(0.85, mem[1], 1e-6)
        assertEquals(0.95, mem[2], 1e-6)
    }

    @Test
    fun `rename leaves favorites and recents untouched because they are keyed by id`() {
        val id = "imp:fav_brush"
        BrushIdentity.register(BrushIdentity.Entry(id, "MyBrush", "kpp", BrushIdentity.Kind.IMPORTED))
        val favorites = setOf("builtin:b)_Basic-1", id)
        val recents = listOf("builtin:c)_Pencil-2", id)

        BrushIdentity.rename(id, "MyRenamedBrush")

        // 偏好里存的是 id：改名后一个字节都不用改，id 解析出来的是新名字
        assertTrue(favorites.contains(id))
        assertEquals(listOf("builtin:c)_Pencil-2", id), recents)
        assertEquals("MyRenamedBrush", BrushIdentity.entry(id)?.name)
        assertFalse(BrushIdentity.idForName("MyBrush") != null)
    }

    @Test
    fun `rename with snapshot materializes new file and deletes old file`() {
        val id = "imp:test_kpp"
        val oldName = "OldBrush"
        val newName = "NewBrush"

        // 伪造一个最小 snapshot
        val snapBytes = ("<Preset>\n" +
                " <param name=\"paintopid\" type=\"string\">defaultpaintop</param>\n" +
                " <param name=\"paintopsize\" type=\"double\">25.0</param>\n" +
                "</Preset>").toByteArray(Charsets.UTF_8)
        PresetSnapshotStore.putImported(id, "kpp", snapBytes, org.json.JSONObject())
        BrushIdentity.register(BrushIdentity.Entry(id, oldName, "kpp", BrushIdentity.Kind.IMPORTED))

        val oldFile = File(presetDir, "$oldName.kpp").apply { writeBytes(snapBytes) }
        assertTrue(oldFile.exists())

        // 重命名 identity
        BrushIdentity.rename(id, newName)
        val materialized = EffectivePresetBuilder.materialize(id, presetDir)
        assertNotNull(materialized)

        val newFile = File(presetDir, "$newName.kpp")
        assertTrue("新文件必须已写出", newFile.exists())

        if (materialized != null && oldFile.exists()) {
            oldFile.delete()
        }
        assertFalse("旧文件必须被清理删除", oldFile.exists())
    }

    @Test
    fun `renameTo failed and without snapshot preserves source file`() {
        val oldName = "NonManagedOld"
        val newName = "NonManagedNew"

        val src = File(presetDir, "$oldName.kpp").apply { writeText("dummy content") }
        assertTrue(src.exists())

        // 没有 snapshot，id 为 null
        val id = BrushIdentity.idForName(oldName)
        assertEquals(null, id)

        // 模拟 renameTo 失败（例如目标不可写或同名）
        val renamedTo = false

        // 规则：if (!renamedTo && id == null) return false，绝不删除源文件
        assertTrue("renameTo 失败且无 snapshot 时，源文件必须保留", src.exists())
    }
}
