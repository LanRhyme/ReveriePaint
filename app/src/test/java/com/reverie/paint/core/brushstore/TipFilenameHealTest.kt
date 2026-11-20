/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.brushstore

import com.reverie.paint.core.KppHelper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class TipFilenameHealTest {
    private lateinit var root: File
    private lateinit var storeRoot: File
    private lateinit var tipDir: File
    private lateinit var patternDir: File
    private lateinit var presetDir: File
    private lateinit var store: ResourceStore

    private val uuidFile =
        "7b30373665303166652d656462632d343536362d383432342d3734303930396666396136367d.gih"

    @Before
    fun setUp() {
        root = Files.createTempDirectory("tipheal").toFile()
        storeRoot = File(root, "brush_store").apply { mkdirs() }
        tipDir = File(root, "brushes").apply { mkdirs() }
        patternDir = File(root, "patterns").apply { mkdirs() }
        presetDir = File(root, "paintoppresets").apply { mkdirs() }
        store = ResourceStore(storeRoot, tipDir, patternDir)
        BrushStoreBootstrap.resetForTest()
        BrushEditSession.resetForTest()
    }

    @After
    fun tearDown() {
        BrushStoreBootstrap.resetForTest()
        BrushEditSession.resetForTest()
        root.walkBottomUp().forEach { it.setWritable(true) }
        root.deleteRecursively()
    }

    private fun fixtureStem(): String =
        KppFixtures.all.first { KppXml.mainBrushDef(it.second) != null }.first

    private fun kppWithMainTip(filename: String, md5: String): ByteArray {
        val xml0 = KppHelper.readPresetXml(KppFixtures.bytes(fixtureStem()), stripEmbeddedResources = true)!!
        val xml = KppXml.setBrushAttr(KppXml.setBrushAttr(xml0, "filename", filename), "md5sum", md5)
        return KppHelper.replacePresetXml(KppFixtures.bytes(fixtureStem()), xml)
    }

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            for ((n, b) in entries) {
                z.putNextEntry(ZipEntry(n))
                z.write(b)
                z.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    @Test
    fun `主笔尖 filename 按 md5 改指磁盘文件且幂等`() {
        val tipBytes = "pistols-revolver-unique-bytes-v1".toByteArray()
        val ref = store.register(tipBytes, ResourceStore.Kind.TIP, "pistolsrevolvers.gih")
        val input = kppWithMainTip(uuidFile, ref.md5)
        val typeBefore = KppXml.brushAttr(KppHelper.readPresetXml(input)!!, "type")

        val out = TipFilenameHeal.apply(input, tipDir, store)
        val xml = KppHelper.readPresetXml(out)!!
        assertEquals("pistolsrevolvers.gih", KppXml.brushAttr(xml, "filename"))
        assertEquals(ref.md5, KppXml.brushAttr(xml, "md5sum"))
        assertEquals(typeBefore, KppXml.brushAttr(xml, "type"))

        val again = TipFilenameHeal.apply(out, tipDir, store)
        assertEquals("pistolsrevolvers.gih", KppXml.brushAttr(KppHelper.readPresetXml(again)!!, "filename"))
        assertSame(out, again)
    }

    @Test
    fun `磁盘上已有同名 UUID 文件时不得改 filename`() {
        val tipBytes = "pistols-revolver-unique-bytes-v2".toByteArray()
        val ref = store.register(tipBytes, ResourceStore.Kind.TIP, "pistolsrevolvers.gih")
        File(tipDir, uuidFile).writeBytes(ByteArray(0))
        val input = kppWithMainTip(uuidFile, ref.md5)

        val out = TipFilenameHeal.apply(input, tipDir, store)
        assertEquals(uuidFile, KppXml.brushAttr(KppHelper.readPresetXml(out)!!, "filename"))
        assertSame(input, out)
    }

    @Test
    fun `md5 对不上任何已登记笔尖时返回原字节`() {
        val input = kppWithMainTip(uuidFile, "0123456789abcdef0123456789abcdef")
        val out = TipFilenameHeal.apply(input, tipDir, store)
        assertTrue(out.contentEquals(input))
        assertSame(input, out)
    }

    @Test
    fun `只改掩膜笔尖 filename 主笔尖不动`() {
        File(tipDir, "already_on_disk.gih").writeBytes(byteArrayOf(1, 2, 3))
        val maskBytes = "mask-stamp-unique-bytes-v1".toByteArray()
        val maskRef = store.register(maskBytes, ResourceStore.Kind.TIP, "maskstamp.gih")
        val xml0 = KppHelper.readPresetXml(KppFixtures.bytes(fixtureStem()), stripEmbeddedResources = true)!!
        val withMain = KppXml.setBrushAttr(xml0, "filename", "already_on_disk.gih")
        val maskDef =
            """<Brush type="png_brush" scale="1" filename="$uuidFile" md5sum="${maskRef.md5}" spacing="0.2" brushApplication="0"/>"""
        val xml = KppXml.set(withMain, KppXml.MASK_BRUSH_DEF, maskDef)
        val input = KppHelper.replacePresetXml(KppFixtures.bytes(fixtureStem()), xml)

        val out = TipFilenameHeal.apply(input, tipDir, store)
        val outXml = KppHelper.readPresetXml(out)!!
        assertEquals("already_on_disk.gih", KppXml.brushAttr(outXml, "filename"))
        val outMask = KppXml.get(outXml, KppXml.MASK_BRUSH_DEF)!!
        assertEquals("maskstamp.gih", KppXml.tagAttr(outMask, "Brush", "filename"))
        assertEquals(maskRef.md5, KppXml.tagAttr(outMask, "Brush", "md5sum"))
        assertEquals("png_brush", KppXml.tagAttr(outMask, "Brush", "type"))
        assertEquals("0.2", KppXml.tagAttr(outMask, "Brush", "spacing"))
    }

    @Test
    fun `导入后有效副本自愈且重置仍指向磁盘文件名快照保持 UUID`() {
        val gih = "pistols-revolver-bundle-bytes-v1".toByteArray()
        val md5 = BrushDiagnostics.md5(gih)
        val kpp = kppWithMainTip(uuidFile, md5)
        val archive = zip(
            "brushes/pistolsrevolvers.gih" to gih,
            "paintoppresets/revolvers brush.kpp" to kpp,
        )
        assertTrue(
            BrushStoreBootstrap.run(
                root,
                presetDir,
                "1",
                readAssets = { emptyMap() },
                migrate = { true },
            ),
        )
        val r = BrushImportPipeline.importArchive(
            "stamps.bundle",
            archive,
            presetDir,
            tipDir,
            patternDir,
        )
        val imported = r.imported.single()
        assertEquals("revolvers brush", imported.name)

        val snapXml = KppHelper.readPresetXml(PresetSnapshotStore.read(imported.id!!)!!, stripEmbeddedResources = true)!!
        assertEquals(uuidFile, KppXml.brushAttr(snapXml, "filename"))
        assertEquals(md5, KppXml.brushAttr(snapXml, "md5sum"))

        val effFile = File(presetDir, "revolvers brush.kpp")
        val effXml = KppHelper.readPresetXml(effFile.readBytes(), stripEmbeddedResources = true)!!
        assertEquals("pistolsrevolvers.gih", KppXml.brushAttr(effXml, "filename"))
        assertEquals(md5, KppXml.brushAttr(effXml, "md5sum"))
        assertNotEquals(uuidFile, KppXml.brushAttr(effXml, "filename"))

        BrushEditSession.reset(imported.id!!, presetDir)
        val afterReset = KppHelper.readPresetXml(effFile.readBytes(), stripEmbeddedResources = true)!!
        assertEquals("pistolsrevolvers.gih", KppXml.brushAttr(afterReset, "filename"))
        assertEquals(md5, KppXml.brushAttr(afterReset, "md5sum"))
        assertEquals(uuidFile, KppXml.brushAttr(PresetSnapshotStore.read(imported.id!!)!!.let {
            KppHelper.readPresetXml(it, stripEmbeddedResources = true)!!
        }, "filename"))
    }

    @Test
    fun `XML 子目录路径在裸文件 md5 一致时改成裸名`() {
        val tipBytes = "subdir-heal-unique-bytes-v1".toByteArray()
        val ref = store.register(tipBytes, ResourceStore.Kind.TIP, "foo.png")
        val input = kppWithMainTip("subdir/foo.png", ref.md5)

        val out = TipFilenameHeal.apply(input, tipDir, store)
        val xml = KppHelper.readPresetXml(out)!!
        assertEquals("foo.png", KppXml.brushAttr(xml, "filename"))
        assertEquals(ref.md5, KppXml.brushAttr(xml, "md5sum"))
    }

    @Test
    fun `裸文件存在但 md5 不同时原字节不动`() {
        val onDisk = "on-disk-foo-bytes-v1".toByteArray()
        store.register(onDisk, ResourceStore.Kind.TIP, "foo.png")
        val input = kppWithMainTip("subdir/foo.png", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")

        val out = TipFilenameHeal.apply(input, tipDir, store)
        assertTrue(out.contentEquals(input))
        assertSame(input, out)
        assertEquals("subdir/foo.png", KppXml.brushAttr(KppHelper.readPresetXml(out)!!, "filename"))
    }
}
