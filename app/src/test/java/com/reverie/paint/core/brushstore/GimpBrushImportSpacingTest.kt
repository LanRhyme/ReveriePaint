/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.brushstore

import com.reverie.paint.core.KppHelper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files

/** gbr/gih 头里的间距必须带进新预设（Krita KisGbrBrush::load → setSpacing(bh.spacing / 100.0)）。 */
class GimpBrushImportSpacingTest {
    private fun u32(o: ByteArrayOutputStream, v: Long) {
        o.write(((v shr 24) and 0xFF).toInt()); o.write(((v shr 16) and 0xFF).toInt())
        o.write(((v shr 8) and 0xFF).toInt()); o.write((v and 0xFF).toInt())
    }

    /** GIMP brush v2：header_size, version, w, h, bytes, 'GIMP', spacing, name\0, pixels */
    private fun gbr(spacing: Long, version: Long = 2): ByteArray {
        val name = "t\u0000".toByteArray()
        val o = ByteArrayOutputStream()
        val hdr = if (version >= 2) 28 + name.size else 20 + name.size
        u32(o, hdr.toLong()); u32(o, version); u32(o, 2); u32(o, 2); u32(o, 1)
        if (version >= 2) { u32(o, 0x47494D50L); u32(o, spacing) }
        o.write(name); o.write(byteArrayOf(0, 1, 2, 3))
        return o.toByteArray()
    }

    @Test fun `gbr spacing 50 percent reads as 0_5`() {
        assertEquals(0.5, GimpBrushHeader.spacing(gbr(50), "a.gbr")!!, 1e-9)
    }

    @Test fun `gbr spacing 1000 is the upper bound and 1001 is rejected like Krita`() {
        assertEquals(10.0, GimpBrushHeader.spacing(gbr(1000), "a.gbr")!!, 1e-9)
        assertNull(GimpBrushHeader.spacing(gbr(1001), "a.gbr"))
    }

    @Test fun `version 1 gbr has no spacing field`() {
        assertNull(GimpBrushHeader.spacing(gbr(50, version = 1), "a.gbr"))
    }

    @Test fun `gih takes the first embedded brush`() {
        val o = ByteArrayOutputStream()
        o.write("pipe\n2 ncells:2 cellwidth:2 cellheight:2 step:10 dim:1 rank0:2 placement:random\n".toByteArray())
        o.write(gbr(25)); o.write(gbr(80))
        assertEquals(0.25, GimpBrushHeader.spacing(o.toByteArray(), "x.GIH")!!, 1e-9)
    }

    @Test fun `garbage and truncated input return null`() {
        assertNull(GimpBrushHeader.spacing(ByteArray(0), "a.gbr"))
        assertNull(GimpBrushHeader.spacing(ByteArray(10) { 1 }, "a.gbr"))
        assertNull(GimpBrushHeader.spacing("no newline".toByteArray(), "a.gih"))
        assertNull(GimpBrushHeader.spacing(gbr(50).copyOf(22), "a.gbr"))
    }

    @Test fun `create with gbr spacing writes it into the new preset and without keeps the base`() {
        val root = Files.createTempDirectory("gbr_sp").toFile()
        val presetDir = File(root, "paintoppresets").apply { mkdirs() }
        val stem = "b)_Basic-5_Size_Opacity"
        try {
            BrushStoreBootstrap.resetForTest(); BrushEditSession.resetForTest()
            assertTrue(BrushStoreBootstrap.run(root, presetDir, true, "1", { mapOf("$stem.kpp" to KppFixtures.bytes(stem)) }, migrate = { true }))
            fun spacingOf(id: String): Double {
                val xml = KppHelper.readPresetXml(File(presetDir, "${BrushIdentity.entry(id)!!.name}.kpp").readBytes(), stripEmbeddedResources = true)!!
                return PresetPropertyMap.readParams(xml).spacing
            }
            val plain = BrushImportPipeline.create("builtin:$stem", "plain", presetDir, tipAsset = "x.gbr")
            val with = BrushImportPipeline.create("builtin:$stem", "withSp", presetDir, tipAsset = "x.gbr", spacing = 0.37)
            assertNotNull(plain); assertNotNull(with)
            assertEquals(0.37, spacingOf(with!!), 1e-6)
            assertNotEquals(0.37, spacingOf(plain!!), 1e-6)
        } finally {
            BrushEditSession.resetForTest(); BrushStoreBootstrap.resetForTest()
            root.walkBottomUp().forEach { it.setWritable(true) }; root.deleteRecursively()
        }
    }
}
