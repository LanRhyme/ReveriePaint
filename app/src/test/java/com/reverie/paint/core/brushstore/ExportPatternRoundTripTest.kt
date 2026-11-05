/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.brushstore

import com.reverie.paint.core.BrushExportSource
import com.reverie.paint.core.KritaBundleManager
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile

/**
 * 导出 → 导入往返：带纹理图案的预设导出成 bundle 时，patterns/ 里必须带上图案文件，
 * 导入侧（BrushImportPipeline.parseArchive 早就认 patterns/）才能把它落进图案库；以前只导出了笔尖，纹理在另一台设备上丢失。
 * 用真实内置 kpp 与 assets/patterns 里的真实图案。
 */
class ExportPatternRoundTripTest {
    private lateinit var root: File
    private lateinit var presetDir: File
    private val assetsRoot: File by lazy { KppFixtures.dir.parentFile }
    private val assets: (String) -> ByteArray? = { p -> File(assetsRoot, p).takeIf { it.isFile }?.readBytes() }

    /** 第一个引用了存在于 assets/patterns 的图案的内置预设 */
    private val withPattern: Pair<String, String> by lazy {
        KppFixtures.all.map { it.first }.firstNotNullOf { s ->
            com.reverie.paint.core.brushstore.PlaybackPresetCache.referencedPatternNames(KppFixtures.bytes(s))
                .firstOrNull { File(assetsRoot, "patterns/$it").isFile }?.let { s to it }
        }
    }

    @Before
    fun setUp() {
        root = Files.createTempDirectory("export_pattern").toFile()
        presetDir = File(root, "paintoppresets").apply { mkdirs() }
        BrushStoreBootstrap.resetForTest()
        BrushEditSession.resetForTest()
        val (stem, _) = withPattern
        assertTrue(BrushStoreBootstrap.run(root, presetDir, true, "1", { mapOf("$stem.kpp" to KppFixtures.bytes(stem)) }, migrate = { true }))
    }

    @After
    fun tearDown() {
        BrushStoreBootstrap.resetForTest()
        root.walkBottomUp().forEach { it.setWritable(true) }
        root.deleteRecursively()
    }

    @Test
    fun exportedBundleCarriesPatternAndImportRestoresIt() {
        val (stem, pat) = withPattern
        val exp = BrushExportSource.effective(BrushIdentity.builtinId(stem), presetDir, assets)!!
        assertEquals(listOf(pat), exp.patternNames)

        val staging = File(root, "cache/export_staging")
        val staged = BrushExportSource.stage(stem, exp, staging)
        val patFiles = BrushExportSource.patternFiles(exp, staging, File(root, "patterns"), assets)
        assertEquals(listOf(pat), patFiles.map { it.name })

        val bundle = KritaBundleManager.exportBundle(
            bundleName = "P", groupName = "G", presets = listOf(stem to staged), tipAssets = emptyList(),
            patternAssets = patFiles, customOutDir = File(root, "out"),
        )
        ZipFile(bundle).use { z ->
            assertNotNull("bundle 里没有 patterns/$pat", z.getEntry("patterns/$pat"))
            assertArrayEquals(File(assetsRoot, "patterns/$pat").readBytes(), z.getInputStream(z.getEntry("patterns/$pat")).readBytes())
            assertTrue(z.getInputStream(z.getEntry("META-INF/manifest.xml")).readBytes().decodeToString().contains("""manifest:full-path="patterns/$pat""""))
        }

        // 导入：图案经 ResourceStore 落进图案库（本机库里没有该文件时才能证明是 bundle 带来的）
        val store = BrushStoreBootstrap.resourceStore!!
        File(store.dirFor(ResourceStore.Kind.PATTERN), pat).delete()
        val r = BrushImportPipeline.importArchive("P.bundle", bundle.readBytes(), presetDir, store.dirFor(ResourceStore.Kind.TIP), store.dirFor(ResourceStore.Kind.PATTERN))
        assertTrue(r.imported.isNotEmpty())
        assertTrue(File(store.dirFor(ResourceStore.Kind.PATTERN), pat).isFile)
    }
}
