/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.brushstore

import com.reverie.paint.core.BrushStoreVm
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** 缺口 5：.myb 与 .kpp 走同一套 快照 + identity + 覆盖 + 有效文件（单文件导入、bundle 内、磁盘遗留、复制、删除）。 */
class MybStoreTest {
    private lateinit var tmp: File
    private lateinit var presetDir: File
    private val myb = """{"version":3,"setting":{"radius_logarithm":{"base_value":2.1}}}""".toByteArray()

    @Before
    fun setUp() {
        tmp = Files.createTempDirectory("myb_store").toFile()
        BrushStoreBootstrap.resetForTest()
        BrushEditSession.resetForTest()
        BrushStoreVm.resetForTest()
        presetDir = File(tmp, "paintoppresets").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        BrushStoreVm.resetForTest()
        BrushEditSession.resetForTest()
        BrushStoreBootstrap.resetForTest()
        tmp.deleteRecursively()
    }

    private fun open(preexisting: Map<String, ByteArray> = emptyMap()) {
        preexisting.forEach { (n, b) -> File(presetDir, n).writeBytes(b) }
        assertTrue(BrushStoreBootstrap.run(tmp, presetDir, true, "1", { emptyMap() }, migrate = { true }))
    }

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z -> for ((n, b) in entries) { z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() } }
        return bos.toByteArray()
    }

    @Test
    fun `单文件导入 myb 有 id 快照 identity 和有效文件`() {
        open()
        val r = BrushImportPipeline.importFile("Soft Pencil.myb", myb, presetDir).imported.single()
        assertNotNull("myb 导入要分配 id", r.id)
        assertTrue(PresetSnapshotStore.has(r.id!!))
        val e = BrushIdentity.entry(r.id)!!
        assertEquals("myb", e.ext)
        assertEquals("Soft Pencil", e.name)
        assertNotNull(BrushStoreBootstrap.managedEntry(com.reverie.paint.core.brushstore.BrushIdentity.idForName("Soft Pencil") ?: ""))
        assertArrayEquals(myb, File(presetDir, "Soft Pencil.myb").readBytes())
        assertEquals("import", PresetSnapshotStore.source(r.id)!!.getString("provenance"))
    }

    @Test
    fun `不是 JSON 的 myb 不导入`() {
        open()
        val r = BrushImportPipeline.importFile("bad.myb", "not json".toByteArray(), presetDir)
        assertTrue(r.imported.isEmpty())
        assertFalse(File(presetDir, "bad.myb").exists())
    }

    @Test
    fun `bundle 里的 myb 与 kpp 一样进库`() {
        open()
        val stem = KppFixtures.all.first().first
        val r = BrushImportPipeline.importArchive(
            "Pack.bundle",
            zip("paintoppresets/$stem.kpp" to KppFixtures.bytes(stem), "paintoppresets/M.myb" to myb),
            presetDir, File(tmp, "brushes"), File(tmp, "patterns"),
        )
        assertEquals(2, r.imported.size)
        assertTrue(r.imported.all { it.id != null })
        assertEquals("Pack.bundle", BrushStoreBootstrap.managedEntry(com.reverie.paint.core.brushstore.BrushIdentity.idForName("M") ?: "")!!.pack)
    }

    @Test
    fun `磁盘上没登记的 myb 启动对账时收进库`() {
        open(mapOf("Legacy.myb" to myb))
        val e = BrushStoreBootstrap.managedEntry(com.reverie.paint.core.brushstore.BrushIdentity.idForName("Legacy") ?: "")
        assertNotNull("旧版直接落盘的 .myb 要被收养", e)
        assertEquals("myb", e!!.ext)
        assertArrayEquals(myb, PresetSnapshotStore.read(e.id))
        assertArrayEquals(myb, File(presetDir, "Legacy.myb").readBytes())
    }

    @Test
    fun `复制 myb 走库并保持扩展名`() {
        open()
        val src = BrushImportPipeline.importFile("A.myb", myb, presetDir).imported.single().id!!
        val dup = BrushImportPipeline.duplicate(src, "A 2", presetDir)
        assertNotNull(dup)
        assertEquals("myb", BrushIdentity.entry(dup!!)!!.ext)
        assertArrayEquals(myb, File(presetDir, "A 2.myb").readBytes())
    }

    @Test
    fun `myb 的 APP_ONLY 覆盖能保存LIVE 不保存`() {
        open()
        val id = BrushImportPipeline.importFile("B.myb", myb, presetDir).imported.single().id!!
        BrushStoreVm.mergeControl(id, ControlId.STREAMLINE, 0.4)
        assertEquals(0.4, (BrushOverrideStore.get(id).controls[ControlId.STREAMLINE.key] as Number).toDouble(), 1e-9)
        BrushStoreVm.mergeControl(id, ControlId.ANGLE, 33.0)
        assertNull("LIVE 控件不写进 myb 覆盖（引擎侧本次会话生效）", BrushOverrideStore.get(id).controls[ControlId.ANGLE.key])
    }

    @Test
    fun `删除 myb 时文件 identity 快照覆盖和旧缩略图一起清掉`() {
        open()
        val id = BrushImportPipeline.importFile("C.myb", myb, presetDir).imported.single().id!!
        File(presetDir, "C_prev.png").writeBytes(byteArrayOf(1))
        assertEquals(id, BrushImportPipeline.delete(com.reverie.paint.core.brushstore.BrushIdentity.idForName("C") ?: "", presetDir))
        assertNull(BrushIdentity.entry(id))
        assertFalse(PresetSnapshotStore.has(id))
        assertFalse(File(presetDir, "C.myb").exists())
        assertFalse(File(presetDir, "C_prev.png").exists())
    }

    @Test
    fun `重命名 myb 换有效文件名不动快照 撞名则拒绝`() {
        open()
        val id = BrushImportPipeline.importFile("D.myb", myb, presetDir).imported.single().id!!
        BrushImportPipeline.importFile("E.myb", myb, presetDir)
        assertFalse("撞已有名", BrushImportPipeline.rename(com.reverie.paint.core.brushstore.BrushIdentity.idForName("D") ?: "", "E", presetDir))
        assertTrue(File(presetDir, "D.myb").exists())
        assertTrue(BrushImportPipeline.rename(com.reverie.paint.core.brushstore.BrushIdentity.idForName("D") ?: "", "D renamed", presetDir))
        assertFalse(File(presetDir, "D.myb").exists())
        assertArrayEquals(myb, File(presetDir, "D renamed.myb").readBytes())
        assertEquals(id, BrushIdentity.idForName("D renamed"))
    }

    @Test
    fun `导出 myb 用有效字节和 myb 扩展名 bundle 里不放假预览图`() {
        open()
        BrushImportPipeline.importFile("F.myb", myb, presetDir)
        val exp = com.reverie.paint.core.BrushExportSource.effective(com.reverie.paint.core.brushstore.BrushIdentity.idForName("F") ?: "", presetDir)!!
        assertEquals("myb", exp.ext)
        assertArrayEquals(myb, exp.bytes)
        assertNull(exp.tipName)
        val staged = com.reverie.paint.core.BrushExportSource.stage("F", exp, File(tmp, "staging"))
        assertEquals("F.myb", staged.name)
        val bundle = com.reverie.paint.core.KritaBundleManager.exportBundle(
            bundleName = "B", groupName = "G", presets = listOf("F" to staged), tipAssets = emptyList(), customOutDir = File(tmp, "out"),
        )
        java.util.zip.ZipFile(bundle).use { z ->
            assertNotNull(z.getEntry("paintoppresets/F.myb"))
            assertNull(z.getEntry("paintoppresets/F.kpp"))
            assertNull("myb 不是 PNG，不能当 preview.png", z.getEntry("preview.png"))
            assertTrue(z.getInputStream(z.getEntry("META-INF/manifest.xml")).readBytes().toString(Charsets.UTF_8).contains("paintoppresets/F.myb"))
        }
        // 导出的 bundle 能再导入回来
        val back = BrushImportPipeline.importArchive("B.bundle", bundle.readBytes(), File(tmp, "p2").apply { mkdirs() }, File(tmp, "b2"), File(tmp, "pt2"))
        assertEquals("myb", back.imported.single().ext)
    }

    @Test
    fun `回放缓存 myb 按 myb 扩展名存 并能按 md5 找回`() {
        open()
        BrushImportPipeline.importFile("G.myb", myb, presetDir)
        val sel = PlaybackPresetCache.recordSelect(3, com.reverie.paint.core.brushstore.BrushIdentity.idForName("G") ?: "")!!
        val root = BrushStoreBootstrap.storeRootDir!!
        assertEquals("myb", PlaybackPresetCache.findCached(root, sel.md5)!!.extension)
        assertArrayEquals(myb, PlaybackPresetCache.findCached(root, sel.md5)!!.readBytes())
        assertEquals(PlaybackPresetCache.Resolution.EXACT, PlaybackPresetCache.resolve(sel))
        File(presetDir, "G.myb").delete()
        assertEquals(PlaybackPresetCache.Resolution.CACHED, PlaybackPresetCache.resolve(sel))
    }
}
