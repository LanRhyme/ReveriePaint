/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import com.reverie.paint.core.brushstore.BrushIdentity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * #136 A / v5 S3：工具槽里的预设如何解析。
 *
 * 优先按 presetId 解析，其次旧名字，最后索引兜底；被删除的 id 或名字绝不退回索引。
 */
class ToolPresetMemoryTest {

    private lateinit var root: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("tool_preset_memory_test").toFile()
        BrushIdentity.open(root)
    }

    @After
    fun tearDown() {
        root.walkBottomUp().forEach { it.setWritable(true) }
        root.deleteRecursively()
    }

    private fun preset(index: Int, name: String, group: String = "基础") =
        BrushPresetInfo(index = index, id = com.reverie.paint.core.brushstore.BrushIdentity.idForName(name) ?: com.reverie.paint.core.brushstore.BrushIdentity.builtinId(name), name = name, thumbBytes = ByteArray(0), group = group)

    // 删过预设之后的表：每支笔都换了位置
    private val shifted = listOf(
        preset(0, "Airbrush_eraser", "橡皮擦"),
        preset(47, "FX_splat_starfield", "特效与滤镜"),
        preset(66, "G pen", "勾线"),
        preset(144, "a)_Eraser_Circle", "橡皮擦"),
    )

    @Test
    fun idWinsOverStaleNameAndIndex() {
        BrushIdentity.register(BrushIdentity.Entry("imp:eraser_id", "a)_Eraser_Circle", "kpp", BrushIdentity.Kind.IMPORTED))
        val hit = ToolPresetMemory.resolve("imp:eraser_id", 47, shifted)
        assertEquals("必须按 id 找到 144 a)_Eraser_Circle，而不是按名字/索引拿到 47", 144, hit?.index)
        assertEquals("a)_Eraser_Circle", hit?.name)
    }

    @Test
    fun deletedIdNeverFallsBackToNameOrIndex() {
        // id 存在但在 BrushIdentity 中不存在（被删除）：绝不退回名字或索引
        assertNull(ToolPresetMemory.resolve("imp:deleted_id", 66, shifted))
    }

    @Test
    fun legacyRecordWithoutIdOrNameFallsBackToIndex() {
        // 老数据只有 "pi"：按索引取，下一次写槽时会把 id 补上
        val hit = ToolPresetMemory.resolve("", 66, shifted)
        assertEquals(66, hit?.index)
        assertEquals("G pen", hit?.name)
    }

    @Test
    fun noNameAndNoIndexResolvesToNothing() {
        assertNull(ToolPresetMemory.resolve("", -1, shifted))
        assertNull(ToolPresetMemory.resolve("", 999, shifted))
    }

    @Test
    fun idMatchIgnoresIndexCompletely() {
        BrushIdentity.register(BrushIdentity.Entry("imp:gpen_id", "G pen", "kpp", BrushIdentity.Kind.IMPORTED))
        val hit = ToolPresetMemory.resolve("imp:gpen_id", -1, shifted)
        assertEquals(66, hit?.index)
    }
}
