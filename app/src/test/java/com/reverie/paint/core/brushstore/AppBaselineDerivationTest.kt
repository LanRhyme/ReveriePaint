/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.brushstore

import com.reverie.paint.core.KppHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** kpp/bundle 导入的快照没有 appBaseline 时，应用层基线（压感开关）取该笔刷自己的传感器设置，而不是 BrushParams 默认 true */
class AppBaselineDerivationTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var presetDir: File

    @Before
    fun setUp() {
        val f = tempFolder.root
        presetDir = File(f, "paintoppresets").apply { mkdirs() }
        BrushStoreBootstrap.resetForTest()
        BrushStoreBootstrap.openStores(File(f, "brush_store").apply { mkdirs() })
        PresetReadback.clearForTest()
    }

    private val stem = "b)_Basic-5_Size_Opacity"

    /** 把 Size/Opacity/Flow 的压感全部关掉的 kpp */
    private fun noPressureBytes(): ByteArray {
        val orig = KppFixtures.bytes(stem)
        var x = KppHelper.readPresetXml(orig)!!
        for (o in listOf("Size", "Opacity", "Flow")) {
            x = KppXml.set(x, "Pressure$o", "false")
            x = KppXml.set(x, "${o}UseCurve", "false")
        }
        return KppHelper.replacePresetXml(orig, x)
    }

    @Test
    fun `无压感的 kpp 导入后基线压感为 false，重置不会加压感`() {
        val id = BrushImportPipeline.importFile("flat.kpp", noPressureBytes(), presetDir).imported.single().id!!
        assertFalse(PresetReadback.baseline(id).pressureEnabled)
    }

    @Test
    fun `带压感的 kpp 导入后基线压感为 true`() {
        val id = BrushImportPipeline.importFile("$stem.kpp", KppFixtures.bytes(stem), presetDir).imported.single().id!!
        assertTrue(PresetReadback.baseline(id).pressureEnabled)
    }

    private fun stripBaseline(id: String) {
        val sf = File(PresetSnapshotStore.importedDir(id), "source.json")
        val j = org.json.JSONObject(sf.readText()).also { it.remove("appBaseline") }
        sf.setWritable(true)
        sf.writeText(j.toString(1))
        sf.setReadOnly()
        PresetReadback.clearForTest()
    }

    @Test
    fun `老快照和内置笔刷：无 appBaseline 读时推导，只对导入快照，不写盘；ABR 显式空基线原样保留`() {
        val flat = BrushImportPipeline.importFile("flat.kpp", noPressureBytes(), presetDir).imported.single().id!!
        val abr = BrushImportPipeline.importFile("$stem.kpp", KppFixtures.bytes(stem), presetDir).imported.single().id!!
        stripBaseline(flat)
        // 显式的空基线（ABR 导入的压感笔刷写 {}）必须原样保留
        val explicit = org.json.JSONObject(PresetSnapshotStore.source(abr)!!.toString()).also { it.put("appBaseline", org.json.JSONObject()) }
        File(PresetSnapshotStore.importedDir(abr), "source.json").apply { setWritable(true); writeText(explicit.toString(1)); setReadOnly() }
        PresetReadback.clearForTest()
        val srcBefore = File(PresetSnapshotStore.importedDir(flat), "source.json").readText()

        assertFalse(PresetReadback.baseline(flat).pressureEnabled)
        assertTrue(PresetReadback.baseline(abr).pressureEnabled)
        assertEquals("读取不改 source.json", srcBefore, File(PresetSnapshotStore.importedDir(flat), "source.json").readText())
    }

    @Test
    fun `旧记录的 pressureEnabled=true 是旧默认值，迁移不当作用户改动`() {
        val flat = BrushImportPipeline.importFile("flat.kpp", noPressureBytes(), presetDir).imported.single()
        LegacyBrushParamsMigrator.migrateAppOnly(flat.name, com.reverie.paint.core.BrushParams(pressureEnabled = true))
        assertFalse(BrushOverrideStore.get(flat.id!!).controls.containsKey(ControlId.PRESSURE_ENABLED.key))
    }

    @Test
    fun `删除后同名重导入、同 id 换快照：基线缓存都按新快照重算`() {
        val first = BrushImportPipeline.importFile("same.kpp", noPressureBytes(), presetDir).imported.single()
        assertFalse(PresetReadback.baseline(first.id!!).pressureEnabled)
        BrushImportPipeline.forget(com.reverie.paint.core.brushstore.BrushIdentity.idForName(first.name) ?: "")
        val again = BrushImportPipeline.importFile("same.kpp", KppFixtures.bytes(stem), presetDir).imported.single()
        assertTrue(PresetReadback.baseline(again.id!!).pressureEnabled)

        // 同一个 id 的快照被换掉（不经手动清缓存）：缓存键含快照 路径|大小|mtime，下一次读取重算
        val id = again.id!!
        val dir = PresetSnapshotStore.importedDir(id)
        dir.walkBottomUp().forEach { it.setWritable(true) }
        File(dir, "preset.kpp").apply { writeBytes(noPressureBytes()) }
        File(dir, "source.json").delete()
        assertFalse(PresetReadback.baseline(id).pressureEnabled)
    }
}
