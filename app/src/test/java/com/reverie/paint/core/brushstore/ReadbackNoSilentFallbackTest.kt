/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.brushstore

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * gap3：基线缓存只靠文件指纹（快照 + source.json）自校验，不靠各处手动清；
 * 回读不到/读不懂时不再悄悄退回默认值，必须在 brush.log 里留一条。
 */
class ReadbackNoSilentFallbackTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var presetDir: File
    private lateinit var diag: File
    private val stem = "b)_Basic-5_Size_Opacity"

    @Before
    fun setUp() {
        presetDir = File(tempFolder.root, "paintoppresets").apply { mkdirs() }
        diag = File(tempFolder.root, "diag")
        BrushStoreBootstrap.resetForTest()
        BrushStoreBootstrap.openStores(File(tempFolder.root, "brush_store").apply { mkdirs() })
        BrushDiagnostics.reset()
        BrushDiagnostics.init(diag)
    }

    @After
    fun tearDown() = BrushDiagnostics.reset()

    private fun log() = BrushDiagnostics.logFile()?.takeIf { it.exists() }?.readText().orEmpty()

    @Test
    fun `source json 里的基线被改后不用手动清缓存，下一次读取即反映`() {
        val id = BrushImportPipeline.importFile("$stem.kpp", KppFixtures.bytes(stem), presetDir).imported.single().id!!
        val before = PresetReadback.baseline(id).pressureEnabled
        val srcFile = File(PresetSnapshotStore.importedDir(id), "source.json")
        srcFile.setWritable(true)
        val j = JSONObject(srcFile.readText())
        j.put("appBaseline", JSONObject().put(ControlId.PRESSURE_ENABLED.key, !before))
        srcFile.writeText(j.toString())
        srcFile.setLastModified(srcFile.lastModified() + 2000)
        assertEquals(!before, PresetReadback.baseline(id).pressureEnabled)
        assertNotEquals(before, PresetReadback.baseline(id).pressureEnabled)
    }

    @Test
    fun `没有快照时退回默认值要在日志里留痕`() {
        PresetReadback.baseline("imp:nope")
        assertTrue(log(), log().contains("baseline_snapshot_missing"))
    }

    @Test
    fun `基线里读不懂的值要在日志里留痕`() {
        val p = PresetReadback.baselineFromJson(JSONObject().put(ControlId.PRESSURE_ENABLED.key, "不是布尔"))
        assertEquals(com.reverie.paint.core.BrushParams(), p)
        assertTrue(log(), log().contains("baseline_value_rejected"))
    }

    @Test
    fun `预设 XML 读不出时退回基线要留痕`() {
        PresetReadback.fromXml(null)
        assertTrue(log(), log().contains("readback_xml_null"))
    }

    @Test
    fun `source json 损坏时要留痕`() {
        val id = BrushImportPipeline.importFile("$stem.kpp", KppFixtures.bytes(stem), presetDir).imported.single().id!!
        File(PresetSnapshotStore.importedDir(id), "source.json").apply { setWritable(true); writeText("{不是 json") }
        assertEquals(null, PresetSnapshotStore.source(id))
        assertTrue(log(), log().contains("source_json_unreadable"))
    }
}
