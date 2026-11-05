/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun `dedupeParamsXml 保留每个键的首次出现并清掉追加的重复键`() {
        // 设备上真实出现的污染形态: Krita 原生值在前, 旧版追加的值在后;
        // 引擎按文档序取最后一个, 于是旧默认 softness≈0.5 + PressureSoftness=true 生效 → 边缘发糊
        val polluted = """<Preset name="Basic" paintopid="paintbrush">
  <param name="SoftnessValue" type="string"><![CDATA[1]]></param>
  <param name="PressureSoftness" type="string"><![CDATA[false]]></param>
  <param type="string" name="SoftnessValue"><![CDATA[0.4988]]></param>
  <param type="string" name="PressureSoftness"><![CDATA[true]]></param>
  <param type="string" name="OpacityValue"><![CDATA[1]]></param>
</Preset>"""

        val cleaned = KppHelper.dedupeParamsXml(polluted)

        assertEquals(1, Regex("""name="SoftnessValue"""").findAll(cleaned).count())
        assertEquals(1, Regex("""name="PressureSoftness"""").findAll(cleaned).count())
        assertTrue(cleaned.contains("""name="OpacityValue""""))
        assertFalse(cleaned.contains("0.4988"))
        // 保留的是首次出现 (Krita 原生值), 不是追加值
        assertFalse(cleaned.contains("""<![CDATA[true]]></param>"""))
    }

    @Test
    fun `readPresetXml and replacePresetXml support uncompressed tEXt chunks`() {
        // Build a PNG with a tEXt preset chunk
        val keyword = "preset"
        val xmlContent = """<Preset name="TextPreset" paintopid="paintbrush"></Preset>"""
        val textBytes = (keyword + "\u0000" + xmlContent).toByteArray(Charsets.ISO_8859_1)

        val crc = java.util.zip.CRC32()
        crc.update("tEXt".toByteArray(Charsets.ISO_8859_1))
        crc.update(textBytes)

        val bos = java.io.ByteArrayOutputStream()
        bos.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        // IHDR
        val ihdrData = ByteArray(13)
        bos.write(java.nio.ByteBuffer.allocate(4).putInt(13).array())
        bos.write("IHDR".toByteArray(Charsets.ISO_8859_1))
        bos.write(ihdrData)
        val ihdrCrc = java.util.zip.CRC32()
        ihdrCrc.update("IHDR".toByteArray(Charsets.ISO_8859_1))
        ihdrCrc.update(ihdrData)
        bos.write(java.nio.ByteBuffer.allocate(4).putInt(ihdrCrc.value.toInt()).array())

        // tEXt chunk
        bos.write(java.nio.ByteBuffer.allocate(4).putInt(textBytes.size).array())
        bos.write("tEXt".toByteArray(Charsets.ISO_8859_1))
        bos.write(textBytes)
        bos.write(java.nio.ByteBuffer.allocate(4).putInt(crc.value.toInt()).array())

        // IEND
        bos.write(java.nio.ByteBuffer.allocate(4).putInt(0).array())
        bos.write("IEND".toByteArray(Charsets.ISO_8859_1))
        val iendCrc = java.util.zip.CRC32()
        iendCrc.update("IEND".toByteArray(Charsets.ISO_8859_1))
        bos.write(java.nio.ByteBuffer.allocate(4).putInt(iendCrc.value.toInt()).array())

        val pngBytes = bos.toByteArray()
        val readXml = KppHelper.readPresetXml(pngBytes)
        assertEquals(xmlContent, readXml)
    }

    @Test
    fun `readPresetXml and replacePresetXml support compressed iTXt chunks`() {
        val keyword = "preset"
        val xmlContent = """<Preset name="ITxtPreset" paintopid="colorsmudge"> <param name="brush_definition"><![CDATA[<Brush filename="test.gih"/>]]></param> </Preset>"""
        val deflater = java.util.zip.Deflater()
        deflater.setInput(xmlContent.toByteArray(Charsets.UTF_8))
        deflater.finish()
        val deflatedBytes = ByteArrayOutputStream()
        val buf = ByteArray(1024)
        while (!deflater.finished()) {
            deflatedBytes.write(buf, 0, deflater.deflate(buf))
        }
        deflater.end()

        val itxtPayload = ByteArrayOutputStream()
        itxtPayload.write(keyword.toByteArray(Charsets.ISO_8859_1))
        itxtPayload.write(0) // null
        itxtPayload.write(1) // comp flag = 1 (compressed)
        itxtPayload.write(0) // comp method = 0 (deflate)
        itxtPayload.write("UTF-8".toByteArray(Charsets.ISO_8859_1))
        itxtPayload.write(0) // null lang
        itxtPayload.write("preset".toByteArray(Charsets.ISO_8859_1))
        itxtPayload.write(0) // null trans kw
        itxtPayload.write(deflatedBytes.toByteArray())

        val itxtData = itxtPayload.toByteArray()
        val crc = java.util.zip.CRC32()
        crc.update("iTXt".toByteArray(Charsets.ISO_8859_1))
        crc.update(itxtData)

        val bos = java.io.ByteArrayOutputStream()
        bos.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        // IHDR
        val ihdrData = ByteArray(13)
        bos.write(java.nio.ByteBuffer.allocate(4).putInt(13).array())
        bos.write("IHDR".toByteArray(Charsets.ISO_8859_1))
        bos.write(ihdrData)
        val ihdrCrc = java.util.zip.CRC32()
        ihdrCrc.update("IHDR".toByteArray(Charsets.ISO_8859_1))
        ihdrCrc.update(ihdrData)
        bos.write(java.nio.ByteBuffer.allocate(4).putInt(ihdrCrc.value.toInt()).array())

        // iTXt chunk
        bos.write(java.nio.ByteBuffer.allocate(4).putInt(itxtData.size).array())
        bos.write("iTXt".toByteArray(Charsets.ISO_8859_1))
        bos.write(itxtData)
        bos.write(java.nio.ByteBuffer.allocate(4).putInt(crc.value.toInt()).array())

        // IEND
        bos.write(java.nio.ByteBuffer.allocate(4).putInt(0).array())
        bos.write("IEND".toByteArray(Charsets.ISO_8859_1))
        val iendCrc = java.util.zip.CRC32()
        iendCrc.update("IEND".toByteArray(Charsets.ISO_8859_1))
        bos.write(java.nio.ByteBuffer.allocate(4).putInt(iendCrc.value.toInt()).array())

        val pngBytes = bos.toByteArray()
        val readXml = KppHelper.readPresetXml(pngBytes)
        assertEquals(xmlContent, readXml)
    }

    @Test
    fun `readExistingVersion and extractTipAssetFilename handle iTXt version and resources`() {
        val basePng = createMinimalPng()
        val xmlWithEmbedded = """<Preset name="TestITXt" paintopid="colorsmudge">
            <resources>
                <resource name="my_gih_brush" type="brushes" filename="my_gih_brush.gih"><![CDATA[dGVzdA==]]></resource>
            </resources>
        </Preset>"""
        val kppBytes = KppHelper.replacePresetXml(basePng, xmlWithEmbedded)
        val xml = KppHelper.readPresetXml(kppBytes)
        assertNotNull(xml)
        assertTrue(xml!!.contains("""filename="my_gih_brush.gih""""))
        val tip = KppHelper.extractTipAssetFilename(kppBytes)
        assertEquals("my_gih_brush.gih", tip)
    }

}
