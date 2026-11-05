/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.brushstore

import com.reverie.paint.core.KppHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** 基线 + 快照参数只有一份缓存（PresetReadback），快照文件变了下一次读取自动反映 */
class SnapshotParamsCacheTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var presetDir: File
    private val stem = "b)_Basic-5_Size_Opacity"

    @Before
    fun setUp() {
        presetDir = File(tempFolder.root, "paintoppresets").apply { mkdirs() }
        BrushStoreBootstrap.resetForTest()
        BrushStoreBootstrap.openStores(File(tempFolder.root, "brush_store").apply { mkdirs() })
        PresetReadback.clearForTest()
    }

    private fun spacing(p: com.reverie.paint.core.BrushParams) = PresetPropertyMap.spec(ControlId.SPACING).get(p) as Number

    @Test
    fun `快照文件被替换后不用手动清缓存，下一次读取即反映新内容`() {
        val id = BrushImportPipeline.importFile("$stem.kpp", KppFixtures.bytes(stem), presetDir).imported.single().id!!
        val before = PresetReadback.snapshotParams(id)!!
        assertEquals(before.first, PresetReadback.baseline(id))

        val file = PresetSnapshotStore.snapshotFile(id)!!
        val orig = file.readBytes()
        val xml = KppHelper.readPresetXml(orig)!!
        val changed = KppHelper.replacePresetXml(orig, KppXml.setBrushAttr(KppXml.setBrushAttr(xml, "useAutoSpacing", "0"), "spacing", "0.777"))
        file.setWritable(true)
        file.writeBytes(changed)
        file.setLastModified(file.lastModified() + 2000)

        val after = PresetReadback.snapshotParams(id)!!
        assertNotEquals(spacing(before.second).toDouble(), spacing(after.second).toDouble(), 1e-9)
        assertEquals(0.777, spacing(after.second).toDouble(), 1e-3)
    }

    @Test
    fun `没有快照时返回空，基线退回默认值`() {
        assertNull(PresetReadback.snapshotParams("imp:nope"))
        assertEquals(com.reverie.paint.core.BrushParams(), PresetReadback.baseline("imp:nope"))
    }
}
