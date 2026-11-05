/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.brushstore

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class PatternRefKeysTest {
    private lateinit var root: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("pattern_keys_test").toFile()
    }

    @After
    fun tearDown() {
        root.walkBottomUp().forEach { it.setWritable(true) }
        root.deleteRecursively()
    }

    private val sampleXml = """
        <Preset name="test" paintopid="defaultpaintop">
            <param name="Texture/Pattern/PatternFileName" type="string"><![CDATA[/path/to/old.png]]></param>
            <param name="Texture/Pattern/Name" type="string"><![CDATA[old.png]]></param>
            <param name="Texture/Pattern/PatternMD5Sum" type="string"><![CDATA[oldmd5sum]]></param>
            <param name="Texture/Pattern/PatternMD5" type="string"><![CDATA[oldbase64md5]]></param>
            <param name="Texture/Pattern/Pattern" type="string"><![CDATA[embedded_png_bytes]]></param>
        </Preset>
    """.trimIndent()

    @Test
    fun `consistency across patternRefKeys, writeTexturePattern, and rewriteRefs with empty md5`() {
        val newFile = "new_pattern.png"
        val ref = ResourceStore.Ref(md5 = "", file = newFile, kind = ResourceStore.Kind.PATTERN)
        val expectedKeys = PresetPropertyMap.patternRefKeys(ref)

        assertEquals("Texture/Pattern/PatternFileName should match", newFile, expectedKeys["Texture/Pattern/PatternFileName"])
        assertEquals("Texture/Pattern/Name should match", newFile, expectedKeys["Texture/Pattern/Name"])
        assertEquals("Texture/Pattern/PatternMD5Sum should be empty", "", expectedKeys["Texture/Pattern/PatternMD5Sum"])
        assertEquals("Texture/Pattern/PatternMD5 should be empty", "", expectedKeys["Texture/Pattern/PatternMD5"])

        // writeTexturePattern 输出
        val writtenXml = PresetPropertyMap.writeTexturePattern(sampleXml, ref)
        assertEquals("PatternFileName in xml", newFile, KppXml.get(writtenXml, "Texture/Pattern/PatternFileName"))
        assertEquals("Name in xml", newFile, KppXml.get(writtenXml, "Texture/Pattern/Name"))
        assertEquals("PatternMD5 in xml should be empty string", "", KppXml.get(writtenXml, "Texture/Pattern/PatternMD5"))
        assertEquals("PatternMD5Sum in xml should be empty string", "", KppXml.get(writtenXml, "Texture/Pattern/PatternMD5Sum"))
        assertNull("Embedded Pattern should be removed", KppXml.get(writtenXml, "Texture/Pattern/Pattern"))

        // rewriteRefs 的 rawKeys
        val rawKeys = BrushImportPipeline.rewriteRefs(sampleXml, mapOf("old.png" to newFile))
        val patternRawKeys = rawKeys.filterKeys { it.startsWith("Texture/Pattern/") }

        assertEquals("rewriteRefs and patternRefKeys should cover same key set", expectedKeys.keys, patternRawKeys.keys)
        assertEquals("FileName in rawKeys", newFile, patternRawKeys["Texture/Pattern/PatternFileName"])
        assertEquals("Name in rawKeys", newFile, patternRawKeys["Texture/Pattern/Name"])
        assertEquals("PatternMD5Sum in rawKeys", "", patternRawKeys["Texture/Pattern/PatternMD5Sum"])
        assertEquals("PatternMD5 in rawKeys", "", patternRawKeys["Texture/Pattern/PatternMD5"])
    }

    @Test
    fun `two write paths produce unified empty PatternMD5`() {
        val store = ResourceStore(root)
        val patternBytes = byteArrayOf(10, 20, 30, 40)
        val ref = store.register(patternBytes, ResourceStore.Kind.PATTERN, "unified_pattern.png")

        // 1. writeTexturePattern 输出
        val writtenXml = PresetPropertyMap.writeTexturePattern(sampleXml, ref)
        val writtenPatternMd5 = KppXml.get(writtenXml, "Texture/Pattern/PatternMD5")

        // 2. rewriteRefs 输出
        val rewriteRaw = BrushImportPipeline.rewriteRefs(sampleXml, mapOf("old.png" to ref.file))
        val rewritePatternMd5 = rewriteRaw["Texture/Pattern/PatternMD5"]

        // 统一为写空串，两处输出的 PatternMD5 完全相同且等于空串
        assertEquals("writeTexturePattern sets PatternMD5 to empty string", "", writtenPatternMd5)
        assertEquals("rewriteRefs sets PatternMD5 to empty string", "", rewritePatternMd5)

        assertEquals("writeTexturePattern output matches rewriteRefs", writtenPatternMd5, rewritePatternMd5)
    }
}
