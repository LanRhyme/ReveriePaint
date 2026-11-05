/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.brushstore

import com.reverie.paint.core.BrushExportSource
import com.reverie.paint.core.BrushStoreVm
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

/** 缺口 6：id 是唯一查找键；显示名只用来显示（以及磁盘文件名 → id 的那一次扫描）。 */
class IdOnlyLookupTest {
    private lateinit var tmp: File
    private lateinit var presetDir: File
    private val myb = """{"version":3,"setting":{"radius_logarithm":{"base_value":2.1}}}""".toByteArray()

    @Before fun setUp() {
        tmp = Files.createTempDirectory("idonly").toFile()
        BrushStoreBootstrap.resetForTest(); BrushEditSession.resetForTest(); BrushStoreVm.resetForTest()
        presetDir = File(tmp, "paintoppresets").apply { mkdirs() }
        assertTrue(BrushStoreBootstrap.run(tmp, presetDir, true, "1", { emptyMap() }, migrate = { true }))
    }

    @After fun tearDown() {
        BrushStoreVm.resetForTest(); BrushEditSession.resetForTest(); BrushStoreBootstrap.resetForTest()
        tmp.deleteRecursively()
    }

    private fun imp(name: String) = BrushImportPipeline.importFile("$name.myb", myb, presetDir).imported.single()

    @Test fun `managedEntry 与 resolveEntry 只认 id 不认名字`() {
        val r = imp("Soft Pencil")
        assertNotNull(BrushStoreBootstrap.managedEntry(r.id!!))
        assertNull("名字不是查找键", BrushStoreBootstrap.managedEntry("Soft Pencil"))
        assertNotNull(BrushStoreVm.resolveEntry(r.id))
        assertNull("名字不是查找键", BrushStoreVm.resolveEntry("Soft Pencil"))
        assertFalse(BrushStoreVm.hasEffectivePreset("Soft Pencil", presetDir))
        assertTrue(BrushStoreVm.hasEffectivePreset(r.id, presetDir))
    }

    @Test fun `rename 与 delete 按 id`() {
        val r = imp("Old Name")
        assertFalse("按名字改名不再成立", BrushImportPipeline.rename("Old Name", "New Name", presetDir))
        assertTrue(BrushImportPipeline.rename(r.id!!, "New Name", presetDir))
        assertEquals("New Name", BrushIdentity.entry(r.id)!!.name)
        assertTrue(File(presetDir, "New Name.myb").isFile)
        assertFalse(File(presetDir, "Old Name.myb").exists())
        assertEquals(r.id, BrushImportPipeline.delete(r.id, presetDir))
        assertFalse(File(presetDir, "New Name.myb").exists())
        assertNull(BrushIdentity.entry(r.id))
    }

    @Test fun `导出按 id 取有效字节 名字只用于暂存文件名`() {
        val r = imp("Exp")
        val e = BrushExportSource.effective(r.id!!, presetDir)
        assertNotNull(e)
        assertEquals("myb", e!!.ext)
        assertNull("名字不是查找键", BrushExportSource.effective("Exp", presetDir))
    }

    @Test fun `适用性与已修改提示按 id`() {
        val r = imp("Hint")
        assertTrue(BrushApplicability.forId(r.id!!).isNotEmpty()) // myb：整体门控
        assertTrue(BrushApplicability.forId("Hint").isEmpty())
    }
}
