/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.brushstore

import com.reverie.paint.core.KppHelper
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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

/**
 * 导入撞名与资源命名空间（执行文档 W-08），全部基于真实内置 kpp / 笔尖 / 纹理：
 * - 268 个内置预设原样再导入：全部改名，内置文件逐字节不变，导入的有效文件 == 原件，重置后仍 == 原件；
 * - 同一个包再导入一次得到 "名字 (包名) 2"；
 * - bundle 里同名不同内容的笔尖/纹理改名 `<包名>__<原名>`，有效副本引用被改写、快照不变、重置保留改名；同内容复用；
 * - 删除（forget）后重启不会复活；复制 = 源快照 + 源覆盖拷贝；v2 关闭时只落盘改写过的文件。
 */
class ImportCollisionRenameTest {
    private lateinit var root: File
    private lateinit var presetDir: File
    private lateinit var brushDir: File
    private lateinit var patternDir: File

    private val assetsRoot: File by lazy { KppFixtures.dir.parentFile }

    @Before
    fun setUp() {
        root = Files.createTempDirectory("import").toFile()
        presetDir = File(root, "paintoppresets").apply { mkdirs() }
        brushDir = File(root, "brushes").apply { mkdirs() }
        patternDir = File(root, "patterns").apply { mkdirs() }
        BrushStoreBootstrap.resetForTest()
        BrushEditSession.resetForTest()
    }

    @After
    fun tearDown() {
        BrushStoreBootstrap.resetForTest()
        root.walkBottomUp().forEach { it.setWritable(true) }
        root.deleteRecursively()
    }

    private fun boot(builtins: List<String>) {
        val assets = builtins.associate { "$it.kpp" to KppFixtures.bytes(it) }
        BrushStoreBootstrap.resetForTest()
        assertTrue(BrushStoreBootstrap.run(root, presetDir, true, "1", { assets }, migrate = { true }))
    }

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z -> for ((n, b) in entries) { z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() } }
        return bos.toByteArray()
    }

    private fun effXml(name: String): String =
        KppHelper.readPresetXml(File(presetDir, "$name.kpp").readBytes(), stripEmbeddedResources = true)!!

    @Test
    fun reimportingEveryBuiltinRenamesAndNeverOverwrites() {
        val stems = KppFixtures.all.map { it.first }
        assertEquals(268, stems.size)
        boot(stems)
        for (s in stems) {
            val r = BrushImportPipeline.importFile("$s.kpp", KppFixtures.bytes(s), presetDir).imported.single()
            assertEquals("$s (导入)", r.name)
            assertNotNull(r.id)
        }
        for (s in stems) {
            assertArrayEquals("内置被覆盖: $s", KppFixtures.bytes(s), File(presetDir, "$s.kpp").readBytes())
            val e = BrushIdentity.entryForName("$s (导入)")!!
            assertEquals(BrushIdentity.Kind.IMPORTED, e.kind)
            assertArrayEquals(KppFixtures.bytes(s), File(presetDir, "$s (导入).kpp").readBytes())
            assertArrayEquals(KppFixtures.bytes(s), PresetSnapshotStore.read(e.id))
            assertEquals("import", PresetSnapshotStore.source(e.id)!!.getString("provenance"))
            BrushEditSession.reset(e.id, presetDir)
            assertArrayEquals(KppFixtures.bytes(s), File(presetDir, "$s (导入).kpp").readBytes())
        }
        // 第二次导入同名
        val s = stems.first()
        assertEquals("$s (导入) 2", BrushImportPipeline.importFile("$s.kpp", KppFixtures.bytes(s), presetDir).imported.single().name)
        // 重启后 identity 保留、materialize 不改字节
        boot(stems)
        assertArrayEquals(KppFixtures.bytes(s), File(presetDir, "$s (导入) 2.kpp").readBytes())
        assertEquals(268 * 2 + 1, BrushIdentity.all().size)
    }

    @Test
    fun caseInsensitiveCollisionAndBatchInternalCollision() {
        val s = KppFixtures.all.first().first
        boot(listOf(s))
        val b = KppFixtures.bytes(s)
        val bundle = zip("paintoppresets/${s.uppercase()}.kpp" to b, "${s.uppercase()}.kpp" to b)
        val r = BrushImportPipeline.importArchive("Pack.bundle", bundle, presetDir, brushDir, patternDir)
        assertEquals(listOf("${s.uppercase()} (Pack)", "${s.uppercase()} (Pack) 2"), r.imported.map { it.name })
    }

    private fun tipCandidate(): Pair<String, String> = KppFixtures.all.firstNotNullOf { (stem, xml) ->
        KppXml.brushAttr(xml, "filename")?.takeIf { it.isNotBlank() && File(assetsRoot, "brushes/$it").isFile }?.let { stem to it }
    }

    private fun patternCandidate(): Pair<String, String> = KppFixtures.all.firstNotNullOf { (stem, xml) ->
        KppXml.get(xml, "Texture/Pattern/Name")?.takeIf { it.isNotBlank() && File(assetsRoot, "patterns/$it").isFile }?.let { stem to it }
    }

    @Test
    fun differentTipWithSameNameIsNamespacedAndReferencesRewritten() {
        val (stem, tip) = tipCandidate()
        boot(listOf(stem))
        val realTip = File(assetsRoot, "brushes/$tip").readBytes()
        File(brushDir, tip).writeBytes(realTip)
        val otherTip = realTip + byteArrayOf(1, 2, 3)
        val kpp = KppFixtures.bytes(stem)
        val bundle = zip("paintoppresets/$stem.kpp" to kpp, "brushes/$tip" to otherTip)
        val r = BrushImportPipeline.importArchive("My Pack.bundle", bundle, presetDir, brushDir, patternDir)

        val renamed = "My_Pack__$tip"
        assertEquals(mapOf(tip to renamed), r.resourceRenames)
        assertArrayEquals("已有笔尖不得被覆盖", realTip, File(brushDir, tip).readBytes())
        assertArrayEquals(otherTip, File(brushDir, renamed).readBytes())
        val imp = r.imported.single()
        assertEquals("$stem (My Pack)", imp.name)
        assertEquals(renamed, KppXml.brushAttr(effXml(imp.name), "filename"))
        assertArrayEquals("快照不变", kpp, PresetSnapshotStore.read(imp.id!!))
        // 重置保留改名，且不算"已修改"
        BrushEditSession.reset(imp.id!!, presetDir)
        assertEquals(renamed, KppXml.brushAttr(effXml(imp.name), "filename"))
        assertTrue(BrushOverrideStore.get(imp.id!!).controls.isEmpty())
        assertFalse(BrushOverrideStore.get(imp.id!!).rawKeys.isEmpty())
        // 叠加会重写 brush_definition 的控件后，改名仍在
        BrushOverrideStore.replace(
            imp.id!!,
            BrushOverrideStore.get(imp.id!!).copy(controls = mapOf("opacity" to 0.37)),
        )
        EffectivePresetBuilder.materialize(imp.id!!, presetDir)
        assertEquals(renamed, KppXml.brushAttr(effXml(imp.name), "filename"))

        // 同内容：复用，不改名
        val again = BrushImportPipeline.importArchive("Other.bundle", zip("paintoppresets/$stem.kpp" to kpp, "brushes/$tip" to realTip), presetDir, brushDir, patternDir)
        assertTrue(again.resourceRenames.isEmpty())
        assertArrayEquals(kpp, File(presetDir, "${again.imported.single().name}.kpp").readBytes())
    }

    @Test
    fun differentPatternWithSameNameIsNamespacedAndMd5Cleared() {
        val (stem, pat) = patternCandidate()
        boot(listOf(stem))
        val realPat = File(assetsRoot, "patterns/$pat").readBytes()
        File(patternDir, pat).writeBytes(realPat)
        val kpp = KppFixtures.bytes(stem)
        val xml0 = KppFixtures.xml(stem)
        val r = BrushImportPipeline.importArchive(
            "Tex.zip", zip("paintoppresets/$stem.kpp" to kpp, "patterns/$pat" to realPat + byteArrayOf(9)),
            presetDir, brushDir, patternDir,
        )
        val renamed = "Tex__$pat"
        val x = effXml(r.imported.single().name)
        assertEquals(renamed, KppXml.get(x, "Texture/Pattern/PatternFileName"))
        assertEquals(renamed, KppXml.get(x, "Texture/Pattern/Name"))
        assertTrue(KppXml.get(xml0, "Texture/Pattern/PatternFileName")!!.endsWith("/$pat"))
        if (KppXml.get(xml0, "Texture/Pattern/PatternMD5") != null) assertEquals("", KppXml.get(x, "Texture/Pattern/PatternMD5"))
        if (KppXml.get(xml0, "Texture/Pattern/PatternMD5Sum") != null) assertEquals("", KppXml.get(x, "Texture/Pattern/PatternMD5Sum"))
    }

    @Test
    fun forgetPreventsResurrectionAndDuplicateCopiesOverrides() {
        val s = KppFixtures.all.first().first
        boot(listOf(s))
        val imp = BrushImportPipeline.importFile("$s.kpp", KppFixtures.bytes(s), presetDir).imported.single()
        BrushOverrideStore.replace(imp.id!!, BrushOverrideStore.Overrides(controls = mapOf("opacity" to 0.5)))
        EffectivePresetBuilder.materialize(imp.id!!, presetDir)

        val dupName = BrushImportPipeline.uniqueCopyName(imp.name, BrushImportPipeline.takenNames(presetDir))
        assertNotEquals(imp.name, dupName)
        val dupId = BrushImportPipeline.duplicate(imp.id!!, dupName, presetDir)!!
        assertArrayEquals(PresetSnapshotStore.read(imp.id!!), PresetSnapshotStore.read(dupId))
        assertEquals(BrushOverrideStore.get(imp.id!!), BrushOverrideStore.get(dupId))
        assertArrayEquals(File(presetDir, "${imp.name}.kpp").readBytes(), File(presetDir, "$dupName.kpp").readBytes())

        File(presetDir, "${imp.name}.kpp").delete()
        BrushImportPipeline.forget(com.reverie.paint.core.brushstore.BrushIdentity.idForName(imp.name) ?: "")
        assertNull(BrushIdentity.entryForName(imp.name))
        assertTrue(BrushOverrideStore.get(imp.id!!).isEmpty())
        boot(listOf(s))
        assertFalse("删除的导入预设在重启后复活", File(presetDir, "${imp.name}.kpp").exists())
        assertTrue(File(presetDir, "$dupName.kpp").exists())
        // 内置不可 forget
        BrushImportPipeline.forget(com.reverie.paint.core.brushstore.BrushIdentity.idForName(s) ?: "")
        assertNotNull(BrushIdentity.entryForName(s))
    }
}
