/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.brushstore

import com.reverie.paint.core.KppHelper
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files

class ThumbOverrideTest {
    private lateinit var root: File
    private lateinit var presetDir: File
    private val stem = "b)_Basic-5_Size_Opacity"
    private val id = "builtin:$stem"

    @Before
    fun setUp() {
        root = Files.createTempDirectory("thumb_test").toFile()
        presetDir = File(root, "paintoppresets").apply { mkdirs() }
        BrushStoreBootstrap.resetForTest()
        BrushEditSession.resetForTest()
        val stems = listOf(stem, "c)_Pencil-3_Large_4B")
        assertTrue(
            BrushStoreBootstrap.run(
                root,
                presetDir,
                true,
                "1",
                { stems.associate { "$it.kpp" to KppFixtures.bytes(it) } },
                migrate = { true },
            ),
        )
    }

    @After
    fun tearDown() {
        BrushEditSession.resetForTest()
        BrushStoreBootstrap.resetForTest()
        root.walkBottomUp().forEach { it.setWritable(true) }
        root.deleteRecursively()
    }

    private fun createCustomPng(marker: Byte): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        val ihdrData = ByteArray(13)
        ihdrData[3] = 2
        ihdrData[7] = 2
        ihdrData[8] = 8
        ihdrData[9] = 6
        ihdrData[12] = marker
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
        val iendCrc = java.util.zip.CRC32().apply {
            update("IEND".toByteArray(Charsets.ISO_8859_1))
        }
        out.write(java.nio.ByteBuffer.allocate(4).putInt(iendCrc.value.toInt()).array())
        return out.toByteArray()
    }

    private fun effFile(name: String = stem): File = File(presetDir, "$name.kpp")

    @Test
    fun `build with null thumb returns same snapshot instance when overrides empty`() {
        val snap = KppFixtures.bytes(stem)
        val res = EffectivePresetBuilder.build(snap, "kpp", BrushOverrideStore.Overrides(), null)
        assertSame(snap, res)
    }

    @Test
    fun `materialize with only thumb uses custom thumbnail and snapshot xml`() {
        val thumb = createCustomPng(0x11)
        BrushThumbStore.put(id, thumb)

        val res = EffectivePresetBuilder.materialize(id, presetDir)
        assertNotNull(res)
        assertTrue(res!!.written)

        val eff = effFile().readBytes()
        val snap = PresetSnapshotStore.read(id)!!
        val expectedXml = KppHelper.readPresetXml(snap, false)!!
        val actualXml = KppHelper.readPresetXml(eff, false)!!
        assertEquals(expectedXml, actualXml)

        val expectedKpp = KppHelper.replacePresetXml(thumb, expectedXml)
        assertArrayEquals(expectedKpp, eff)
    }

    @Test
    fun `materialize with thumb and controls updates xml on top of custom thumbnail`() {
        val thumb = createCustomPng(0x22)
        BrushThumbStore.put(id, thumb)
        BrushOverrideStore.replace(id, BrushOverrideStore.Overrides(controls = mapOf("opacity" to 0.4)))

        val res = EffectivePresetBuilder.materialize(id, presetDir)
        assertNotNull(res)

        val eff = effFile().readBytes()
        val snap = PresetSnapshotStore.read(id)!!
        val expectedBuilt = EffectivePresetBuilder.build(snap, "kpp", BrushOverrideStore.get(id), null)
        val expectedXml = KppHelper.readPresetXml(expectedBuilt, false)!!
        val expectedKpp = KppHelper.replacePresetXml(thumb, expectedXml)
        assertArrayEquals(expectedKpp, eff)
    }

    @Test
    fun `reset retains custom thumbnail`() {
        val thumb = createCustomPng(0x33)
        BrushThumbStore.put(id, thumb)
        BrushOverrideStore.replace(id, BrushOverrideStore.Overrides(controls = mapOf("opacity" to 0.4)))
        EffectivePresetBuilder.materialize(id, presetDir)

        val resetRes = BrushEditSession.reset(id, presetDir)
        assertNotNull(resetRes)

        val eff = effFile().readBytes()
        val snap = PresetSnapshotStore.read(id)!!
        val expectedXml = KppHelper.readPresetXml(snap, false)!!
        val expectedKpp = KppHelper.replacePresetXml(thumb, expectedXml)
        assertArrayEquals(expectedKpp, eff)
        assertArrayEquals(thumb, BrushThumbStore.get(id))
    }

    @Test
    fun `duplicate copies custom thumbnail to new id and forget deletes thumbnail file`() {
        val thumb = createCustomPng(0x44)
        BrushThumbStore.put(id, thumb)
        EffectivePresetBuilder.materialize(id, presetDir)

        val dupName = "$stem 副本"
        val dupId = BrushImportPipeline.duplicate(id, dupName, presetDir)
        assertNotNull(dupId)

        assertArrayEquals(thumb, BrushThumbStore.get(dupId!!))
        val dupEff = effFile(dupName).readBytes()
        val snap = PresetSnapshotStore.read(dupId)!!
        val expectedXml = KppHelper.readPresetXml(snap, false)!!
        val expectedKpp = KppHelper.replacePresetXml(thumb, expectedXml)
        assertArrayEquals(expectedKpp, dupEff)

        val thumbFile = File(File(root, "brush_store/thumbs"), "${BrushIdentity.dirNameFor(dupId)}.png")
        assertTrue(thumbFile.exists())

        BrushImportPipeline.forget(com.reverie.paint.core.brushstore.BrushIdentity.idForName(dupName) ?: "")
        assertNull(BrushThumbStore.get(dupId))
        assertFalse("forget 之后 thumbs/<dir>.png 不应存在", thumbFile.exists())
    }

    @Test
    fun `retiring factory preset copies thumbnail to new id and deletes old builtin thumbnail`() {
        val thumb = createCustomPng(0x55)
        BrushThumbStore.put(id, thumb)

        val oldThumbFile = File(File(root, "brush_store/thumbs"), "${BrushIdentity.dirNameFor(id)}.png")
        assertTrue(oldThumbFile.exists())

        val stemsV2 = listOf("c)_Pencil-3_Large_4B")
        BrushStoreBootstrap.resetForTest()
        assertTrue(
            BrushStoreBootstrap.run(
                root,
                presetDir,
                true,
                "v2",
                { stemsV2.associate { "$it.kpp" to KppFixtures.bytes(it) } },
                migrate = { true },
            ),
        )

        assertFalse("旧出厂 id 缩略图文件应被删", oldThumbFile.exists())
        assertNull(BrushThumbStore.get(id))

        val retiredEntry = BrushIdentity.entryForName(stem)
        assertNotNull(retiredEntry)
        assertEquals(BrushIdentity.Kind.IMPORTED, retiredEntry!!.kind)
        assertArrayEquals(thumb, BrushThumbStore.get(retiredEntry.id))

        val eff = effFile(stem).readBytes()
        val snap = PresetSnapshotStore.read(retiredEntry.id)!!
        val expectedXml = KppHelper.readPresetXml(snap, false)!!
        val expectedKpp = KppHelper.replacePresetXml(thumb, expectedXml)
        assertArrayEquals(expectedKpp, eff)
    }
}
