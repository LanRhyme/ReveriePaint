/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets

class AbrSourceTest {
    @get:Rule val tempFolder = TemporaryFolder()

    @Test
    fun testByteArraySourceReadOperations() {
        val sample = byteArrayOf(0x12, 0x34, 0x56, 0x78.toByte(), 0x01, 0x02, 0x03, 0x04)
        val source = AbrSource.fromBytes(sample)
        assertEquals(8L, source.size)
        assertEquals(0x12, source.u8(0))
        assertEquals(0x1234, source.u16(0))
        assertEquals(0x12345678, source.i32(0))
        val slice = source.sliceBytes(2, 4)
        assertEquals(4, slice.size)
        assertEquals(0x56.toByte(), slice[0])
        source.close()
    }

    @Test
    fun testPagedChannelSourceWindowSliding() {
        val file = tempFolder.newFile("paged_test.bin")
        FileOutputStream(file).use { fos ->
            val buf = ByteArray(128 * 1024) { (it and 0xFF).toByte() }
            fos.write(buf)
        }
        val source = AbrSource.fromFile(file, preferMmap = false)
        assertTrue(source is PagedChannelSource)
        assertEquals(128L * 1024L, source.size)
        // Cross page boundary read
        assertEquals((65535 and 0xFF), source.u8(65535))
        assertEquals((65536 and 0xFF), source.u8(65536))
        source.close()
    }

    @Test
    fun testMappedFileSourceAndUnmap() {
        val file = tempFolder.newFile("mmap_test.bin")
        FileOutputStream(file).use { fos ->
            fos.write(byteArrayOf(0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07))
        }
        val source = AbrSource.fromFile(file, preferMmap = true)
        assertTrue(source is MappedFileSource)
        assertEquals(8L, source.size)
        assertEquals(0x00010203, source.i32(0))
        assertEquals(0x04050607, source.i32(4))
        source.close()
        // Verify file can be deleted after unmap
        assertTrue(file.delete() || !file.exists())
    }

    @Test
    fun testDirectBufferUnmapperNullAndNonDirect() {
        assertEquals(false, DirectBufferUnmapper.unmap(null))
        val heapBuf = java.nio.ByteBuffer.allocate(16)
        assertEquals(false, DirectBufferUnmapper.unmap(heapBuf))
        val directBuf = java.nio.ByteBuffer.allocateDirect(16)
        assertTrue(DirectBufferUnmapper.unmap(directBuf))
    }

    @Test(expected = EOFException::class)
    fun testByteArraySourceOutOfBounds() {
        val source = AbrSource.fromBytes(byteArrayOf(1, 2))
        source.u8(2)
    }

    @Test
    fun testStringAndPrimitiveReads() {
        val text = "AbrTest"
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        val source = AbrSource.fromBytes(bytes)
        assertEquals(text, source.readString(0, bytes.size, StandardCharsets.UTF_8))
        assertEquals("", source.readString(0, 0, StandardCharsets.UTF_8))
        assertEquals("", source.readString(0, 100, StandardCharsets.UTF_8))
    }
}
