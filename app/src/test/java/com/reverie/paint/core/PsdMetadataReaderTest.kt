/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File

class PsdMetadataReaderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `read minimal valid psd header`() {
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)

        // 1. Header (26 bytes)
        dos.write(byteArrayOf(0x38, 0x42, 0x50, 0x53)) // "8BPS"
        dos.writeShort(1) // version 1
        dos.write(ByteArray(6)) // reserved
        dos.writeShort(4) // channels
        dos.writeInt(1920) // height
        dos.writeInt(1080) // width
        dos.writeShort(8) // depth
        dos.writeShort(3) // colorMode RGB

        // 2. Color mode length
        dos.writeInt(0)

        // 3. Image resources length
        dos.writeInt(0)

        // 4. Layer and mask length
        dos.writeInt(0)

        dos.flush()

        val file = tmp.newFile("test.psd")
        file.writeBytes(baos.toByteArray())

        val meta = PsdMetadataReader.readMetadata(file)
        assertNotNull(meta)
        assertEquals(1080, meta?.width)
        assertEquals(1920, meta?.height)
        assertEquals(300, meta?.dpi) // default dpi
        assertEquals(1, meta?.layerCount)
        assertEquals(4, meta?.channels)
        assertEquals(8, meta?.depth)
        assertEquals(3, meta?.colorMode)
        assertNull(meta?.thumbnailJpegBytes)
    }

    @Test
    fun `read psd with resolution and thumbnail and layers`() {
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)

        // 1. Header
        dos.write(byteArrayOf(0x38, 0x42, 0x50, 0x53))
        dos.writeShort(1)
        dos.write(ByteArray(6))
        dos.writeShort(4)
        dos.writeInt(800) // height
        dos.writeInt(1200) // width
        dos.writeShort(8)
        dos.writeShort(3)

        // 2. Color mode data
        dos.writeInt(0)

        // 3. Image Resources
        val resBaos = ByteArrayOutputStream()
        val resDos = DataOutputStream(resBaos)

        // Resource 1: ResolutionInfo (ID 1005)
        resDos.write(byteArrayOf(0x38, 0x42, 0x49, 0x4D)) // "8BIM"
        resDos.writeShort(1005)
        resDos.writeByte(0) // name len 0
        resDos.writeByte(0) // pad to even
        resDos.writeInt(16) // data size 16
        resDos.writeInt(600 shl 16) // 600 dpi
        resDos.writeShort(1) // pixels per inch
        resDos.writeShort(1) // width unit
        resDos.writeInt(600 shl 16) // vRes
        resDos.writeShort(1) // vRes unit
        resDos.writeShort(1) // height unit

        // Resource 2: Thumbnail (ID 1036)
        val mockJpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x12, 0x34, 0xFF.toByte(), 0xD9.toByte())
        resDos.write(byteArrayOf(0x38, 0x42, 0x49, 0x4D)) // "8BIM"
        resDos.writeShort(1036)
        resDos.writeByte(0)
        resDos.writeByte(0)
        val thumbDataSize = 28 + mockJpeg.size
        resDos.writeInt(thumbDataSize)
        resDos.writeInt(1) // format kJpegRGB
        resDos.writeInt(160) // width
        resDos.writeInt(120) // height
        resDos.writeInt(160 * 3) // widthbytes
        resDos.writeInt(thumbDataSize) // totalSize
        resDos.writeInt(mockJpeg.size) // compressedSize
        resDos.writeShort(24) // bpp
        resDos.writeShort(1) // planes
        resDos.write(mockJpeg)
        if (thumbDataSize % 2 != 0) resDos.writeByte(0) // pad

        resDos.flush()
        val resBytes = resBaos.toByteArray()

        dos.writeInt(resBytes.size)
        dos.write(resBytes)

        // 4. Layer and Mask Information Section
        val layerBaos = ByteArrayOutputStream()
        val layerDos = DataOutputStream(layerBaos)
        layerDos.writeInt(10) // layer info len
        layerDos.writeShort(-7) // 7 layers with alpha
        layerDos.write(ByteArray(8)) // padding
        layerDos.flush()
        val layerBytes = layerBaos.toByteArray()

        dos.writeInt(layerBytes.size)
        dos.write(layerBytes)

        dos.flush()

        val file = tmp.newFile("full.psd")
        file.writeBytes(baos.toByteArray())

        val meta = PsdMetadataReader.readMetadata(file)
        assertNotNull(meta)
        assertEquals(1200, meta?.width)
        assertEquals(800, meta?.height)
        assertEquals(600, meta?.dpi)
        assertEquals(7, meta?.layerCount)
        assertArrayEquals(mockJpeg, meta?.thumbnailJpegBytes)
    }

    @Test
    fun `read psb format with large dimensions`() {
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)

        // 1. Header
        dos.write(byteArrayOf(0x38, 0x42, 0x50, 0x53))
        dos.writeShort(2) // PSB version 2
        dos.write(ByteArray(6))
        dos.writeShort(4)
        dos.writeLong(40000L) // height
        dos.writeLong(30000L) // width
        dos.writeShort(8)
        dos.writeShort(3)

        // 2. Color mode length
        dos.writeInt(0)

        // 3. Image resources length
        dos.writeInt(0)

        // 4. Layer and mask length
        dos.writeLong(0L)

        dos.flush()

        val file = tmp.newFile("large.psb")
        file.writeBytes(baos.toByteArray())

        val meta = PsdMetadataReader.readMetadata(file)
        assertNotNull(meta)
        assertEquals(30000, meta?.width)
        assertEquals(40000, meta?.height)
    }

    @Test
    fun `invalid or truncated file returns null`() {
        val empty = tmp.newFile("empty.psd")
        assertNull(PsdMetadataReader.readMetadata(empty))

        val badSig = tmp.newFile("bad_sig.psd")
        badSig.writeBytes(byteArrayOf(0x00, 0x01, 0x02, 0x03, 0x04, 0x05))
        assertNull(PsdMetadataReader.readMetadata(badSig))
    }
}
