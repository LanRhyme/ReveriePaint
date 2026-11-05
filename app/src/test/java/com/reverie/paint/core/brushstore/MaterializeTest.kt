/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.brushstore

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** materialize / 启动编排（W-04）：快照不变、有效文件随覆盖变化、清空覆盖后回到快照字节 */
class MaterializeTest {
    private lateinit var tmp: File
    private lateinit var presetDir: File
    private val stems = listOf("b)_Basic-5_Size_Opacity", "c)_Pencil-3_Large_4B", "y)_Texture_Reptile")

    @Before
    fun setUp() {
        tmp = Files.createTempDirectory("mat").toFile()
        presetDir = File(tmp, "paintoppresets").apply { mkdirs() }
        BrushStoreBootstrap.resetForTest()
    }

    @After
    fun tearDown() {
        tmp.walkBottomUp().forEach { it.setWritable(true) }
        tmp.deleteRecursively()
        BrushStoreBootstrap.resetForTest()
    }

    private fun assets(): Map<String, ByteArray> = stems.associate { "$it.kpp" to KppFixtures.bytes(it) }

    @Test
    fun `启动编排安装出厂快照、注册内置并生成有效文件`() {
        val ok = BrushStoreBootstrap.run(tmp, presetDir, installStamp = "1", readAssets = ::assets, migrate = { true })
        assertTrue(ok)
        for (s in stems) {
            assertEquals("builtin:$s", BrushIdentity.idForName(s))
            assertArrayEquals(KppFixtures.bytes(s), File(presetDir, "$s.kpp").readBytes())
        }
        // 再次启动：stamp 未变不读资产
        val ok2 = BrushStoreBootstrap.run(tmp, presetDir, "1", readAssets = { error("不应读资产") }, migrate = { true })
        assertTrue(ok2)
    }

    @Test
    fun `迁移未完成时不进入就绪`() {
        assertFalse(BrushStoreBootstrap.run(tmp, presetDir, "1", ::assets, migrate = { false }))
    }

    @Test
    fun `覆盖改变有效文件不改快照，清空覆盖回到快照字节，未变时不重写`() {
        BrushStoreBootstrap.run(tmp, presetDir, "1", ::assets, migrate = { true })
        val id = "builtin:${stems[0]}"
        val snapMd5 = BrushDiagnostics.md5(PresetSnapshotStore.read(id)!!)
        val xml = com.reverie.paint.core.KppHelper.readPresetXml(PresetSnapshotStore.read(id)!!, true)!!
        BrushOverrideStore.replace(id, OverrideFixtures.overrides(OverrideFixtures.editsFor(xml)))
        val r1 = EffectivePresetBuilder.materialize(id, presetDir)!!
        assertTrue(r1.written)
        assertNotEquals(snapMd5, r1.md5)
        assertEquals(snapMd5, BrushDiagnostics.md5(PresetSnapshotStore.read(id)!!))
        assertFalse(EffectivePresetBuilder.materialize(id, presetDir)!!.written)
        BrushOverrideStore.clear(id)
        val r2 = EffectivePresetBuilder.materialize(id, presetDir)!!
        assertTrue(r2.written)
        assertEquals(snapMd5, r2.md5)
        assertEquals(snapMd5, BrushDiagnostics.md5(File(presetDir, "${stems[0]}.kpp").readBytes()))
        assertFalse(File(presetDir, "${stems[0]}.kpp.tmp").exists())
    }
}
