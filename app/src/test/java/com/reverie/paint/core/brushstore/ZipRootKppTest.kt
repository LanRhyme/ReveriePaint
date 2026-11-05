/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.brushstore

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
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
 * zip 结构识别（执行文档 W-08）：根目录 .kpp/.myb、paintoppresets/、嵌套 bundle（≤2 层）、
 * 嵌套 .abr 与超深层记为 skipped，__MACOSX / 隐藏文件 / `../` 忽略。预设用真实内置 kpp。
 */
class ZipRootKppTest {
    private lateinit var root: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("ziproot").toFile()
        BrushStoreBootstrap.resetForTest()
    }

    @After
    fun tearDown() {
        BrushStoreBootstrap.resetForTest()
        root.walkBottomUp().forEach { it.setWritable(true) }
        root.deleteRecursively()
    }

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z -> for ((n, b) in entries) { z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() } }
        return bos.toByteArray()
    }

    private val stems by lazy { KppFixtures.all.map { it.first }.take(5) }

    @Test
    fun parsesRootSubdirAndNestedArchives() {
        val (a, b, c, d, e) = stems
        val myb = "{\"settings\":{}}".toByteArray()
        val deep = zip("x.kpp" to KppFixtures.bytes(a))
        val lvl2 = zip("paintoppresets/$d.kpp" to KppFixtures.bytes(d), "deeper.zip" to deep)
        val lvl1 = zip("$c.kpp" to KppFixtures.bytes(c), "inner2.bundle" to lvl2)
        val outer = zip(
            "$a.kpp" to KppFixtures.bytes(a),
            "paintoppresets/$b.kpp" to KppFixtures.bytes(b),
            "Root.myb" to myb,
            "inner.bundle" to lvl1,
            "sub/abr/pack.abr" to byteArrayOf(0),
            "__MACOSX/paintoppresets/._$e.kpp" to byteArrayOf(0),
            ".hidden.kpp" to byteArrayOf(0),
            "other/$e.kpp" to KppFixtures.bytes(e),
            "brushes/t.png" to byteArrayOf(1),
            "patterns/p.png" to byteArrayOf(2),
            "paintoppresets/${a}_prev.png" to byteArrayOf(3),
        )
        val p = BrushImportPipeline.parseArchive(outer, "Outer.zip")
        assertEquals(listOf(a, b, "Root", c, d), p.presets.map { it.wantedName })
        assertEquals(listOf("kpp", "kpp", "myb", "kpp", "kpp"), p.presets.map { it.ext })
        assertEquals(listOf("brushes/t.png", "patterns/p.png"), p.resources.map { "${it.kind}/${it.name}" })
        assertEquals(listOf("deeper.zip", "sub/abr/pack.abr"), p.skipped)
        assertArrayEquals(KppFixtures.bytes(a), p.presets[0].bytes)
    }

    @Test
    fun rootKppZipImportsIntoV2AndMybIsManagedToo() {
        val presetDir = File(root, "paintoppresets").apply { mkdirs() }
        assertTrue(BrushStoreBootstrap.run(root, presetDir, true, "1", { emptyMap() }, migrate = { true }))
        val (a, b) = stems
        val r = BrushImportPipeline.importArchive(
            "Root Pack.zip",
            zip("$a.kpp" to KppFixtures.bytes(a), "$b.kpp" to KppFixtures.bytes(b), "M.myb" to "{}".toByteArray()),
            presetDir, File(root, "brushes"), File(root, "patterns"),
        )
        assertEquals(listOf(a, b, "M"), r.imported.map { it.name })
        for (n in listOf(a, b)) {
            val e = BrushStoreBootstrap.managedEntry(com.reverie.paint.core.brushstore.BrushIdentity.idForName(n) ?: "")
            assertNotNull("根目录 kpp 应归 v2 管: $n", e)
            assertEquals("Root Pack.zip", e!!.pack)
            assertArrayEquals(KppFixtures.bytes(n), File(presetDir, "$n.kpp").readBytes())
        }
        assertEquals("myb", BrushStoreBootstrap.managedEntry(com.reverie.paint.core.brushstore.BrushIdentity.idForName("M") ?: "")?.ext)
        assertArrayEquals("{}".toByteArray(), File(presetDir, "M.myb").readBytes())
    }
}
