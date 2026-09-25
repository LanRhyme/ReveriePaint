/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.ByteArrayOutputStream
import java.util.zip.ZipFile

class KppHelperTest {

    private fun createMinimalPng(): ByteArray {
        val out = ByteArrayOutputStream()
        // PNG header
        out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        // IHDR chunk (13 bytes)
        val ihdrData = ByteArray(13)
        ihdrData[3] = 1 // width = 1
        ihdrData[7] = 1 // height = 1
        ihdrData[8] = 8 // bit depth
        ihdrData[9] = 6 // color type (RGBA)
        val ihdrLen = java.nio.ByteBuffer.allocate(4).putInt(13).array()
        out.write(ihdrLen)
        out.write("IHDR".toByteArray(Charsets.ISO_8859_1))
        out.write(ihdrData)
        val ihdrCrc = java.util.zip.CRC32().apply {
            update("IHDR".toByteArray(Charsets.ISO_8859_1))
            update(ihdrData)
        }
        out.write(java.nio.ByteBuffer.allocate(4).putInt(ihdrCrc.value.toInt()).array())

        // IEND chunk
        out.write(java.nio.ByteBuffer.allocate(4).putInt(0).array())
        out.write("IEND".toByteArray(Charsets.ISO_8859_1))
        val iendCrc = java.util.zip.CRC32().apply {
            update("IEND".toByteArray(Charsets.ISO_8859_1))
        }
        out.write(java.nio.ByteBuffer.allocate(4).putInt(iendCrc.value.toInt()).array())
        return out.toByteArray()
    }

    @Test
    fun `injectParamsIntoXml correctly injects custom tip and properties`() {
        val originalXml = """<Preset name="Test" paintopid="paintbrush"> <param type="string" name="paintopSize"><![CDATA[10]]></param> </Preset>"""
        val params = BrushParams(
            size = 45.0,
            opacity = 0.8,
            flow = 0.9,
            spacing = 0.25,
            tipAsset = "mooncake.png",
            airbrush = true,
            airbrushRate = 50.0,
            smudgeRate = 0.7,
            smudgeLength = 0.6,
        )

        val updatedXml = KppHelper.injectParamsIntoXml(originalXml, "MooncakeBrush", params)
        assertTrue(updatedXml.contains("""<Preset name="MooncakeBrush""""))
        assertTrue(updatedXml.contains("""<param type="string" name="paintopSize"><![CDATA[45.0]]></param>"""))
        assertTrue(updatedXml.contains("""<param type="string" name="OpacityValue"><![CDATA[0.8]]></param>"""))
        assertTrue(updatedXml.contains("""<param type="string" name="FlowValue"><![CDATA[0.9]]></param>"""))
        assertTrue(updatedXml.contains("""<param type="string" name="Spacing"><![CDATA[0.25]]></param>"""))
        assertTrue(updatedXml.contains("""filename="mooncake.png""""))
        assertTrue(updatedXml.contains("""<param type="string" name="AirbrushOption/isAirbrushing"><![CDATA[true]]></param>"""))
        assertTrue(updatedXml.contains("""<param type="string" name="ColorRateValue"><![CDATA[0.7]]></param>"""))
    }

    @Test
    fun `updateKppBytes roundtrips and updates preset XML in PNG`() {
        val basePng = createMinimalPng()
        val params = BrushParams(
            size = 60.0,
            spacing = 0.15,
            tipAsset = "custom_star.gbr",
        )

        val kppBytes = KppHelper.updateKppBytes(basePng, "StarBrush", params)
        val readXml = KppHelper.readPresetXml(kppBytes)
        assertNotNull(readXml)
        assertTrue(readXml!!.contains("""name="StarBrush""""))
        assertTrue(readXml.contains("""filename="custom_star.gbr""""))
        assertTrue(readXml.contains("""paintopSize"><![CDATA[60.0]]>"""))

        val extractedTip = KppHelper.extractTipAssetFilename(kppBytes)
        assertEquals("custom_star.gbr", extractedTip)
    }

    @Test
    fun `exportBundle creates valid krita bundle structure`() {
        val tempDir = File.createTempFile("bundle_test_", "").apply { delete(); mkdirs() }
        try {
            val presetKpp = File(tempDir, "Mooncake.kpp").apply {
                writeBytes(createMinimalPng())
            }
            val tipFile = File(tempDir, "mooncake.png").apply {
                writeBytes(ByteArray(10) { 1 })
            }

            val bundleOut = File(tempDir, "TestBundle.bundle")
            val zos = java.util.zip.ZipOutputStream(bundleOut.outputStream())

            // Mimic KritaBundleManager logic
            val mimetypeBytes = "application/x-krita-bundle".toByteArray(Charsets.US_ASCII)
            val crc = java.util.zip.CRC32().apply { update(mimetypeBytes) }
            val mEntry = java.util.zip.ZipEntry("mimetype").apply {
                method = java.util.zip.ZipEntry.STORED
                size = mimetypeBytes.size.toLong()
                compressedSize = mimetypeBytes.size.toLong()
                this.crc = crc.value
            }
            zos.putNextEntry(mEntry)
            zos.write(mimetypeBytes)
            zos.closeEntry()

            val pEntry = java.util.zip.ZipEntry("paintoppresets/Mooncake.kpp")
            zos.putNextEntry(pEntry)
            presetKpp.inputStream().use { it.copyTo(zos) }
            zos.closeEntry()

            val bEntry = java.util.zip.ZipEntry("brushes/mooncake.png")
            zos.putNextEntry(bEntry)
            tipFile.inputStream().use { it.copyTo(zos) }
            zos.closeEntry()

            val tagEntry = java.util.zip.ZipEntry("Custom.tag")
            zos.putNextEntry(tagEntry)
            zos.write("[Desktop Entry]\nType=Tag\nName=Custom\n".toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            zos.close()

            ZipFile(bundleOut).use { zip ->
                val entries = zip.entries().toList().map { it.name }
                assertEquals("mimetype", entries[0])
                val mEntryCheck = zip.getEntry("mimetype")
                assertEquals(java.util.zip.ZipEntry.STORED, mEntryCheck.method)
                assertTrue(entries.contains("paintoppresets/Mooncake.kpp"))
                assertTrue(entries.contains("brushes/mooncake.png"))
                assertTrue(entries.contains("Custom.tag"))
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
