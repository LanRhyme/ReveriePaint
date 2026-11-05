/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.brushstore

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 验证 BrushEditSession.reset 契约测试 (W-06)。
 */
class BrushEditSessionTest {
    private lateinit var tmp: File
    private lateinit var presetDir: File
    private val stem = "b)_Basic-5_Size_Opacity"
    private val id = "builtin:$stem"

    @Before
    fun setUp() {
        tmp = Files.createTempDirectory("sess").toFile()
        presetDir = File(tmp, "paintoppresets").apply { mkdirs() }
        BrushStoreBootstrap.resetForTest()
        BrushEditSession.resetForTest()
        val stems = listOf(stem, "c)_Pencil-3_Large_4B")
        assertTrue(
            BrushStoreBootstrap.run(tmp, presetDir, true, "1", { stems.associate { "$it.kpp" to KppFixtures.bytes(it) } }, migrate = { true }),
        )
    }

    @After
    fun tearDown() {
        BrushEditSession.resetForTest()
        BrushStoreBootstrap.resetForTest()
        tmp.walkBottomUp().forEach { it.setWritable(true) }
        tmp.deleteRecursively()
    }

    private fun file() = File(presetDir, "$stem.kpp")
    private fun md5File() = BrushDiagnostics.md5(file().readBytes())
    private fun md5Snap() = BrushDiagnostics.md5(PresetSnapshotStore.read(id)!!)

    @Test
    fun `reset clears controls and restores effective file to snapshot`() {
        // 1. 添加参数覆盖并生成有效文件
        BrushOverrideStore.replace(
            id,
            BrushOverrideStore.Overrides(
                controls = mapOf("opacity" to 0.42, "spacing" to 0.77),
                dirty = setOf("opacity", "spacing"),
            )
        )
        EffectivePresetBuilder.materialize(id, presetDir)
        assertTrue(BrushDiagnostics.md5(file().readBytes()) != md5Snap())

        // 2. 执行 reset
        val result = BrushEditSession.reset(id, presetDir)
        assertNotNull(result)
        assertTrue(BrushOverrideStore.get(id).isEmpty())
        assertEquals(md5Snap(), md5File())
    }

    @Test
    fun `reset preserves rawKeys and metadata controls`() {
        val metaControls = mapOf(
            ControlId.AUTHOR.key to "AuthorName",
            ControlId.AUTHOR_LOCKED.key to true,
            "opacity" to 0.35,
        )
        val rawKeys = mapOf("TextureKey" to "PatternValue")
        BrushOverrideStore.replace(
            id,
            BrushOverrideStore.Overrides(
                controls = metaControls,
                rawKeys = rawKeys,
                dirty = setOf("opacity"),
            )
        )
        EffectivePresetBuilder.materialize(id, presetDir)

        val result = BrushEditSession.reset(id, presetDir)
        assertNotNull(result)

        val after = BrushOverrideStore.get(id)
        assertFalse("opacity must be cleared", after.controls.containsKey("opacity"))
        assertEquals("AuthorName", after.controls[ControlId.AUTHOR.key])
        assertEquals(true, after.controls[ControlId.AUTHOR_LOCKED.key])
        assertEquals(rawKeys, after.rawKeys)
    }

    @Test
    fun `reset returns null when in read-only mode`() {
        BrushStoreBootstrap.readOnly = true
        try {
            assertNull(BrushEditSession.reset(id, presetDir))
        } finally {
            BrushStoreBootstrap.readOnly = false
        }
    }

    @Test
    fun `locked runs block under mutex`() {
        var executed = false
        val ret = BrushEditSession.locked {
            executed = true
            42
        }
        assertTrue(executed)
        assertEquals(42, ret)
    }
}
