/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.brushstore

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 验证子目录笔尖与纹理的解压及相对路径改名重写 (Ink_Pro_Artist 等大包真实案例)。
 */
class SubdirectoryBrushImportTest {
    private lateinit var root: File
    private lateinit var presetDir: File
    private lateinit var brushDir: File
    private lateinit var patternDir: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("subdir_test").toFile()
        presetDir = File(root, "paintoppresets").apply { mkdirs() }
        brushDir = File(root, "brushes").apply { mkdirs() }
        patternDir = File(root, "patterns").apply { mkdirs() }
        BrushStoreBootstrap.resetForTest()
        BrushStoreBootstrap.run(root, presetDir, true, "1", { emptyMap() }, migrate = { true })
    }

    @After
    fun tearDown() {
        BrushStoreBootstrap.resetForTest()
        root.walkBottomUp().forEach { it.setWritable(true) }
        root.deleteRecursively()
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
    fun testSubdirectoryTipAndPatternExtractedAndRewritten() {
        val kppStem = KppFixtures.all.first().first
        val origKpp = KppFixtures.bytes(kppStem)
        
        // 构造一个包含子目录笔尖引用的 preset XML
        val xmlWithSubdir = """
            <Preset name="Test Subdir" paintopid="paintbrush">
                <param name="brush_definition" type="string"><![CDATA[<Brush filename="brush tips for DA/DA_Ink stamp gradient.png" spacing="0.1" type="png_brush"/>]]></param>
                <param name="requiredBrushFile" type="string"><![CDATA[brush tips for DA/DA_Ink stamp gradient.png]]></param>
                <param name="Texture/Pattern/PatternFileName" type="string"><![CDATA[textures/noise.png]]></param>
            </Preset>
        """.trimIndent()
        val customKpp = com.reverie.paint.core.KppHelper.replacePresetXml(origKpp, xmlWithSubdir)

        val tipBytes = "FAKE_PNG_TIP_DATA".toByteArray()
        val patBytes = "FAKE_PNG_PAT_DATA".toByteArray()

        val bundleBytes = zip(
            "paintoppresets/Test Subdir.kpp" to customKpp,
            "brushes/brush tips for DA/DA_Ink stamp gradient.png" to tipBytes,
            "patterns/textures/noise.png" to patBytes,
        )

        val bundleFile = File(root, "test.bundle").apply { writeBytes(bundleBytes) }
        val result = BrushImportPipeline.importArchive(
            archiveFile = bundleFile,
            presetDir = presetDir,
            brushDir = brushDir,
            patternDir = patternDir,
        )

        assertEquals(1, result.imported.size)

        // 验证子目录文件已平铺保存到目标目录
        val extractedTip = File(brushDir, "DA_Ink stamp gradient.png")
        assertTrue("Subdirectory tip must be extracted to brushes/", extractedTip.exists())
        assertEquals("FAKE_PNG_TIP_DATA", extractedTip.readText())

        val extractedPat = File(patternDir, "noise.png")
        assertTrue("Subdirectory pattern must be extracted to patterns/", extractedPat.exists())
        assertEquals("FAKE_PNG_PAT_DATA", extractedPat.readText())

        // 验证相对路径改名映射已生成
        val renames = result.resourceRenames
        assertEquals("DA_Ink stamp gradient.png", renames["brush tips for DA/DA_Ink stamp gradient.png"])
        assertEquals("noise.png", renames["textures/noise.png"])

        // 验证最终落盘的 preset XML 已把相对路径重写为平铺名
        val materializedFile = File(presetDir, "Test Subdir.kpp")
        assertTrue("Materialized kpp must exist", materializedFile.exists())
        val materializedXml = com.reverie.paint.core.KppHelper.readPresetXml(materializedFile.readBytes())
        assertNotNull(materializedXml)
        assertTrue(
            "Materialized XML must have rewritten filename",
            materializedXml!!.contains("filename=\"DA_Ink stamp gradient.png\"")
        )
        assertEquals(
            "DA_Ink stamp gradient.png",
            KppXml.get(materializedXml, "requiredBrushFile")
        )
    }
}
