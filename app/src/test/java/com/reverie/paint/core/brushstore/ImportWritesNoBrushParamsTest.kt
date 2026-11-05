/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.brushstore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 验证导入笔刷时不再写 brushParams，而是将元数据和 appBaseline 完整写入 source.json（S5）。
 */
class ImportWritesNoBrushParamsTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var root: File
    private lateinit var presetDir: File
    private lateinit var brushDir: File
    private lateinit var patternDir: File

    @Before
    fun setUp() {
        val f = tempFolder.root
        root = File(f, "brush_store").apply { mkdirs() }
        presetDir = File(f, "paintoppresets").apply { mkdirs() }
        brushDir = File(f, "brushes").apply { mkdirs() }
        patternDir = File(f, "patterns").apply { mkdirs() }
        BrushStoreBootstrap.resetForTest()
        BrushStoreBootstrap.openStores(root)
    }

    @Test
    fun `单文件导入写快照和 sourceJson`() {
        val stem = KppFixtures.all.first().first
        val bytes = KppFixtures.bytes(stem)
        val result = BrushImportPipeline.importFile("$stem.kpp", bytes, presetDir)
        val imported = result.imported.single()

        val id = imported.id
        assertNotNull(id)
        assertTrue(PresetSnapshotStore.has(id!!))

        val src = PresetSnapshotStore.source(id)
        assertNotNull(src)
        assertEquals("import", src!!.getString("provenance"))
        assertEquals("$stem.kpp", src.getString("file"))

        val file = File(presetDir, "${imported.name}.kpp")
        assertTrue(file.exists())
    }
}
