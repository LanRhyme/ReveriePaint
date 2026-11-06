/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.reverie.paint.core.brushstore

import com.reverie.paint.core.KppHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64

/**
 * 任务书 R4-C 第 6 项：单文件 .kpp 内嵌 `<resources>`（Krita 5.0+ base64）资源守恒——
 * importFile 先按 bundle 导入同一套把 brushes/patterns 解码落盘 ResourceStore（撞名改名 → rawKeys
 * 回写引用），再剥离内嵌载荷建快照；否则内嵌资源只活在 kpp 载荷里，笔尖/纹理拿不到磁盘文件。
 */
class SingleKppEmbeddedResourcesTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var presetDir: File

    @Before
    fun setUp() {
        val f = tempFolder.root
        presetDir = File(f, "paintoppresets").apply { mkdirs() }
        BrushStoreBootstrap.resetForTest()
        BrushStoreBootstrap.openStores(File(f, "brush_store").apply { mkdirs() })
    }

    private fun minimalPng(): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        val ihdrData = ByteArray(13)
        ihdrData[3] = 1
        ihdrData[7] = 1
        ihdrData[8] = 8
        ihdrData[9] = 6
        val ihdrLen = java.nio.ByteBuffer.allocate(4).putInt(13).array()
        out.write(ihdrLen)
        out.write("IHDR".toByteArray(Charsets.ISO_8859_1))
        out.write(ihdrData)
        val ihdrCrc = java.util.zip.CRC32().apply {
            update("IHDR".toByteArray(Charsets.ISO_8859_1))
            update(ihdrData)
        }
        out.write(java.nio.ByteBuffer.allocate(4).putInt(ihdrCrc.value.toInt()).array())
        out.write(java.nio.ByteBuffer.allocate(4).putInt(0).array())
        out.write("IEND".toByteArray(Charsets.ISO_8859_1))
        val iendCrc = java.util.zip.CRC32().apply { update("IEND".toByteArray(Charsets.ISO_8859_1)) }
        out.write(java.nio.ByteBuffer.allocate(4).putInt(iendCrc.value.toInt()).array())
        return out.toByteArray()
    }

    private val tipBytes = minimalPng()
    private val patternBytes = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
    private val maskTipBytes = byteArrayOf(0x50, 0x4E, 0x47, 0x32)
    private val tipB64 = Base64.getEncoder().encodeToString(tipBytes)

    private fun kppXml(): String =
        """<Preset paintopid="paintbrush" embedded_resources="3" name="Emb"> <resources>""" +
            """ <resource type="brushes" md5sum="aaa" filename="Emb_Tip.png" name="Emb Tip"><![CDATA[$tipB64]]></resource>""" +
            """ <resource type="patterns" md5sum="bbb" filename="Emb_Pat.png" name="Emb_Pat.png"><![CDATA[${Base64.getEncoder().encodeToString(patternBytes)}]]></resource>""" +
            """ <resource type="brushes" md5sum="ccc" filename="Emb_MaskTip.png" name="Emb Mask Tip"><![CDATA[${Base64.getEncoder().encodeToString(maskTipBytes)}]]></resource>""" +
            """ </resources>""" +
            """ <param type="string" name="brush_definition"><![CDATA[<Brush type="png_brush" scale="1" filename="Emb_Tip.png" spacing="0.1"/>]]></param>""" +
            """ <param type="string" name="MaskingBrush/Preset/brush_definition"><![CDATA[<Brush type="png_brush" scale="1" filename="Emb_MaskTip.png" spacing="0.1"/>]]></param>""" +
            """ <param type="string" name="Texture/Pattern/PatternFileName"><![CDATA[Emb_Pat.png]]></param>""" +
            """ <param type="string" name="Texture/Pattern/Name"><![CDATA[Emb_Pat.png]]></param>""" +
            """</Preset>"""

    private fun kppBytes(): ByteArray = KppHelper.replacePresetXml(minimalPng(), kppXml())

    @Test
    fun `内嵌 brushes patterns 导入落盘 ResourceStore 且快照剥离载荷`() {
        val r = BrushImportPipeline.importFile("Emb.kpp", kppBytes(), presetDir)
        assertTrue("导入成功", r.imported.isNotEmpty())
        assertTrue("无撞名不应有改名", r.resourceRenames.isEmpty())

        val store = BrushStoreBootstrap.resourceStore!!
        val tipRef = store.resolveByName("Emb_Tip.png", ResourceStore.Kind.TIP)
        assertNotNull("笔尖应登记", tipRef)
        assertTrue(tipBytes.contentEquals(store.fileFor(tipRef!!).readBytes()))
        val patRef = store.resolveByName("Emb_Pat.png", ResourceStore.Kind.PATTERN)
        assertNotNull("纹理应登记", patRef)
        assertTrue(patternBytes.contentEquals(store.fileFor(patRef!!).readBytes()))

        val id = r.imported.single().id!!
        val snap = PresetSnapshotStore.read(id)!!
        assertFalse("快照应剥离内嵌载荷", KppHelper.hasEmbeddedResources(snap))
        val snapXml = KppHelper.readPresetXml(snap)!!
        assertTrue("剥离保留 <resources> 元素骨架", snapXml.contains("<resources>"))
        assertFalse("base64 载荷应被剥离", snapXml.contains(tipB64))
        assertEquals("引用未改名时保持原名", "Emb_Tip.png", KppXml.brushAttr(snapXml, "filename"))
        assertEquals("Emb_Pat.png", KppXml.get(snapXml, "Texture/Pattern/PatternFileName"))
    }

    @Test
    fun `内嵌资源撞名时改名并经 rawKeys 回写引用`() {
        val store = BrushStoreBootstrap.resourceStore!!
        store.register(byteArrayOf(9, 9, 9, 9), ResourceStore.Kind.TIP, "Emb_Tip.png")

        val r = BrushImportPipeline.importFile("Emb.kpp", kppBytes(), presetDir)
        val newName = r.resourceRenames["Emb_Tip.png"]
        assertNotNull("撞名应触发改名", newName)
        assertNotNull(store.resolveByName(newName!!, ResourceStore.Kind.TIP))
        assertTrue(store.dirFor(ResourceStore.Kind.TIP).let { d -> File(d, newName).exists() })

        val effXml = KppHelper.readPresetXml(File(presetDir, "Emb.kpp").readBytes())!!
        assertEquals("有效副本笔尖引用应改写为新名", newName, KppXml.brushAttr(effXml, "filename"))
    }

    @Test
    fun `内嵌 pattern 撞名改名后纹理引用经 rawKeys 回写有效副本`() {
        val store = BrushStoreBootstrap.resourceStore!!
        store.register(byteArrayOf(7, 7, 7, 7), ResourceStore.Kind.PATTERN, "Emb_Pat.png")

        val r = BrushImportPipeline.importFile("Emb.kpp", kppBytes(), presetDir)
        val newName = r.resourceRenames["Emb_Pat.png"]
        assertNotNull("纹理撞名应触发改名", newName)
        assertNotNull(store.resolveByName(newName!!, ResourceStore.Kind.PATTERN))

        val effXml = KppHelper.readPresetXml(File(presetDir, "Emb.kpp").readBytes())!!
        assertEquals("PatternFileName 应回写为新名", newName, KppXml.get(effXml, "Texture/Pattern/PatternFileName"))
        assertEquals("Name 应回写为新名", newName, KppXml.get(effXml, "Texture/Pattern/Name"))
        // 快照不动：仍指向原名（重置语义，引用改写只进有效副本）
        val snapXml = KppHelper.readPresetXml(PresetSnapshotStore.read(r.imported.single().id!!)!!)!!
        assertEquals("Emb_Pat.png", KppXml.get(snapXml, "Texture/Pattern/PatternFileName"))
    }

    @Test
    fun `副笔刷内嵌笔尖撞名改名后 MaskingBrush 定义引用同步回写`() {
        val store = BrushStoreBootstrap.resourceStore!!
        store.register(byteArrayOf(5, 5, 5, 5), ResourceStore.Kind.TIP, "Emb_MaskTip.png")

        val r = BrushImportPipeline.importFile("Emb.kpp", kppBytes(), presetDir)
        val newName = r.resourceRenames["Emb_MaskTip.png"]
        assertNotNull("副笔刷笔尖撞名应触发改名", newName)
        assertNotNull(store.resolveByName(newName!!, ResourceStore.Kind.TIP))

        val effXml = KppHelper.readPresetXml(File(presetDir, "Emb.kpp").readBytes())!!
        val maskDef = KppXml.get(effXml, KppXml.MASK_BRUSH_DEF)!!
        assertEquals("副笔刷定义的笔尖引用应改写为新名", newName, KppXml.tagAttr(maskDef, "Brush", "filename"))
        // 主笔尖未撞名：保持原名
        assertEquals("Emb_Tip.png", KppXml.brushAttr(effXml, "filename"))
    }

    @Test
    fun `无内嵌资源的 kpp 导入保持快照字节不变`() {
        val bytes = KppFixtures.bytes("b)_Basic-5_Size_Opacity")
        val r = BrushImportPipeline.importFile("b)_Basic-5_Size_Opacity.kpp", bytes, presetDir)
        assertTrue(r.resourceRenames.isEmpty())
        val id = r.imported.single().id!!
        assertTrue("无内嵌资源不应 strip", bytes.contentEquals(PresetSnapshotStore.read(id)!!))
    }

    @Test
    fun `parseEmbeddedResources 解析 CDATA 换行 base64 并跳过未知类型与空载荷`() {
        val wrappedB64 = tipB64.chunked(76).joinToString("\n")
        val xml = """<Preset><resources>""" +
            """<resource type="brushes" filename="A.png"><![CDATA[$wrappedB64]]></resource>""" +
            """<resource type="gradients" filename="g.svg"><![CDATA[eA==]]></resource>""" +
            """<resource type="patterns" filename="p.png"></resource>""" +
            """</resources></Preset>"""
        val out = BrushImportPipeline.parseEmbeddedResources(KppHelper.replacePresetXml(minimalPng(), xml))
        assertEquals(1, out.size)
        assertEquals("brushes", out[0].kind)
        assertEquals("A.png", out[0].name)
        assertTrue(tipBytes.contentEquals(out[0].bytes))
    }
}
