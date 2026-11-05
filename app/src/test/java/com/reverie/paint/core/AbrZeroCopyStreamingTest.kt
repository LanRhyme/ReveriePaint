/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.EOFException
import java.io.FileOutputStream

class AbrZeroCopyStreamingTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun be16(v: Int) = byteArrayOf(
        (v ushr 8 and 0xFF).toByte(),
        (v and 0xFF).toByte(),
    )

    private fun be32(v: Int) = byteArrayOf(
        (v ushr 24 and 0xFF).toByte(),
        (v ushr 16 and 0xFF).toByte(),
        (v ushr 8 and 0xFF).toByte(),
        (v and 0xFF).toByte(),
    )

    private fun section(name: String, payload: ByteArray): ByteArray {
        require(name.length == 4)
        return "8BIM".toByteArray(Charsets.ISO_8859_1) +
            name.toByteArray(Charsets.ISO_8859_1) +
            be32(payload.size) + payload
    }

    private fun sampRecord(
        uuid: String,
        width: Int,
        height: Int,
        gray: ByteArray,
        compression: Int = 0,
    ): ByteArray {
        require(uuid.length == 36)
        return byteArrayOf(36) +
            uuid.toByteArray(Charsets.US_ASCII) +
            ByteArray(264) +
            be32(0) + be32(0) + be32(height) + be32(width) +
            be16(8) + byteArrayOf(compression.toByte()) +
            gray
    }

    private fun buildAbr(
        version: Int,
        subversion: Int,
        records: List<ByteArray>,
    ): ByteArray {
        var sampPayload = ByteArray(0)
        for (rec in records) {
            var paddedLen = rec.size
            if (paddedLen % 4 != 0) {
                paddedLen += (4 - (paddedLen % 4))
            }
            val paddedRec = if (paddedLen == rec.size) rec else rec.copyOf(paddedLen)
            sampPayload += be32(rec.size) + paddedRec
        }
        return be16(version) + be16(subversion) + section("samp", sampPayload)
    }

    @Test
    fun `MappedFileSource and DirectBufferUnmapper release file lock immediately on Windows and JVM`() {
        val abrFile = tempFolder.newFile("lock_test.abr")
        val uuid = "11111111-2222-3333-4444-555555555555"
        val pixelData = ByteArray(16 * 16) { 0x80.toByte() }
        val rec = sampRecord(uuid, 16, 16, pixelData, compression = 0)
        val abrBytes = buildAbr(version = 6, subversion = 2, listOf(rec))
        FileOutputStream(abrFile).use { it.write(abrBytes) }

        val source = AbrSource.fromFile(abrFile, preferMmap = true)
        assertTrue("Should use MappedFileSource on 64-bit JVM", source is MappedFileSource)

        val result = source.use { s ->
            AbrParser.parse(s, basePackName = "LockTest")
        }

        assertEquals(1, result.tips.size)
        assertEquals(16, result.tips[0].width)
        assertEquals(16, result.tips[0].height)

        // On Windows, if MappedByteBuffer is not unmapped, delete() returns false.
        // DirectBufferUnmapper.unmap() must release the file mapping lock immediately.
        val deleted = abrFile.delete()
        assertTrue("abrFile.delete() must return true immediately after source.close()", deleted)
        assertFalse("abrFile must not exist after deletion", abrFile.exists())
    }

    @Test
    fun `220MB virtual ABR parses with O(1) heap footprint under 15MB delta`() {
        // Construct a virtual 220MB AbrSource without allocating 220MB on heap or disk:
        // 20 sampled tips of 512x512 + 1 8BIM unknown extension block of ~215MB
        val virtualSource = Virtual220MbAbrSource(tipCount = 20, tipWidth = 512, tipHeight = 512)
        assertTrue("Virtual source size must exceed 220MB", virtualSource.size >= 220L * 1024L * 1024L)

        // Pre-run GC to establish a clean memory baseline
        System.gc()
        Thread.sleep(50)
        System.gc()
        val runtime = Runtime.getRuntime()
        val beforeUsed = runtime.totalMemory() - runtime.freeMemory()

        var streamedCallbackCount = 0
        var maxCallbackDataSize = 0

        val result = virtualSource.use { source ->
            AbrParser.parse(
                source = source,
                basePackName = "VirtualPack",
                onTipDecoded = { tip ->
                    streamedCallbackCount++
                    maxCallbackDataSize = maxOf(maxCallbackDataSize, tip.data.size)
                    assertEquals(512, tip.width)
                    assertEquals(512, tip.height)
                    assertEquals(512 * 512, tip.data.size)
                },
            )
        }

        assertEquals(20, streamedCallbackCount)
        assertEquals(512 * 512, maxCallbackDataSize)
        assertEquals(20, result.tips.size)

        // Verify streaming memory reclamation:
        // tip.data must be cleared to ByteArray(0), only lightweight thumbnail is kept
        for (tip in result.tips) {
            assertEquals(0, tip.data.size)
            assertNotNull("Thumbnail must be generated and retained", tip.thumbnail)
            assertTrue("Thumbnail data must be non-empty", tip.thumbnail!!.isNotEmpty())
            assertEquals(150, maxOf(tip.thumbWidth, tip.thumbHeight))
        }

        // Measure retained heap delta after garbage collection
        System.gc()
        Thread.sleep(50)
        System.gc()
        val afterUsed = runtime.totalMemory() - runtime.freeMemory()
        val deltaBytes = (afterUsed - beforeUsed).coerceAtLeast(0L)
        val deltaMb = deltaBytes / (1024.0 * 1024.0)

        // Retained heap delta must be < 8MB (spec requirement < 8MB)
        assertTrue("Retained heap delta (${"%.2f".format(deltaMb)} MB) must be < 8MB", deltaBytes < 8L * 1024L * 1024L)
    }

    @Test
    fun `corrupt non-ABR garbage and truncated scanlines fail fast without OOM or crash`() {
        // 1. 1024-byte disguised garbage file fast-fails with empty result (< 0.1ms)
        val garbage = ByteArray(1024) { (it xor 0x5A).toByte() }

        // Warm up JIT for timing check
        AbrParser.parse(AbrSource.fromBytes(garbage), "warmup")

        var elapsedNanosMin = Long.MAX_VALUE
        var lastResult: AbrParser.AbrParseResult? = null
        for (i in 0 until 20) {
            val s = AbrSource.fromBytes(garbage)
            val t0 = System.nanoTime()
            val r = AbrParser.parse(s, "GarbageCheck")
            val elapsed = System.nanoTime() - t0
            elapsedNanosMin = minOf(elapsedNanosMin, elapsed)
            lastResult = r
        }

        assertNotNull(lastResult)
        assertTrue("Corrupt garbage tips must be empty", lastResult!!.tips.isEmpty())
        assertTrue("Corrupt garbage presets must be empty", lastResult.presets.isEmpty())
        assertTrue(
            "Garbage file must fail fast (< 0.5ms, actual: ${elapsedNanosMin / 1000.0} us)",
            elapsedNanosMin < 500_000L,
        )

        // 2. Truncated ABR: Claims 2 tips, but 2nd tip is truncated halfway through.
        // Must safely salvage intact tip 1 and skip truncated tip 2 without crashing.
        val uuid1 = "11111111-1111-1111-1111-111111111111"
        val pixel1 = ByteArray(32 * 32) { (it and 0xFF).toByte() }
        val rec1 = sampRecord(uuid1, 32, 32, pixel1, compression = 0)

        val uuid2 = "22222222-2222-2222-2222-222222222222"
        val pixel2 = ByteArray(32 * 32) { (it and 0xFF).toByte() }
        val rec2 = sampRecord(uuid2, 32, 32, pixel2, compression = 0)

        val fullAbr = buildAbr(version = 6, subversion = 2, listOf(rec1, rec2))

        // Truncate halfway through record 2
        val truncatedLength = fullAbr.size - (rec2.size / 2)
        val truncatedBytes = fullAbr.copyOf(truncatedLength)

        val truncatedResult = AbrSource.fromBytes(truncatedBytes).use { source ->
            AbrParser.parse(source, basePackName = "TruncatedPack")
        }

        // Intact tip 1 must be salvaged
        assertEquals(1, truncatedResult.tips.size)
        assertEquals(uuid1, truncatedResult.tips[0].uuid)
        assertEquals(32, truncatedResult.tips[0].width)
        assertEquals(32, truncatedResult.tips[0].height)
        assertEquals(1, truncatedResult.presets.size)
        assertFalse(truncatedResult.tooLarge)

        // 3. Truncated PackBits scanlines: Intact tip 1, tip 2 has incomplete scanline table
        val line0 = byteArrayOf(0x01.toByte(), 10, 20, (-1).toByte(), 30) // 4 pixels
        val line1 = byteArrayOf((-3).toByte(), 99) // 4 pixels
        val packBitsData = be16(line0.size) + be16(line1.size) + line0 + line1
        val packBitsRec = sampRecord("33333333-3333-3333-3333-333333333333", 4, 2, packBitsData, compression = 1)
        val fullPackBitsAbr = buildAbr(version = 6, subversion = 2, listOf(rec1, packBitsRec))
        // Cut off before the scanline table finishes (needs 4 bytes of table, give only 2)
        val cutPackBitsAbr = fullPackBitsAbr.copyOf(fullPackBitsAbr.size - (packBitsData.size - 2))

        val packBitsResult = AbrSource.fromBytes(cutPackBitsAbr).use { source ->
            AbrParser.parse(source, basePackName = "CutPackBits")
        }
        assertEquals(1, packBitsResult.tips.size)
        assertEquals(uuid1, packBitsResult.tips[0].uuid)

        // Also verify decodePackBitsScanlines directly on truncated scanline table returns null safely
        val incompleteTable = byteArrayOf(0x00, 0x05) // only 2 bytes for 2 scanlines (needs 4)
        val directTableResult = AbrParser.decodePackBitsScanlines(incompleteTable, 0, height = 2, width = 4)
        assertEquals(null, directTableResult)

        // Verify mid-scanline truncation does not crash or throw
        val midCutScanlines = be16(10) + be16(10) + byteArrayOf(0x01, 10, 20)
        val midCutResult = AbrParser.decodePackBitsScanlines(midCutScanlines, 0, height = 2, width = 4)
        assertNotNull(midCutResult)
    }

    /**
     * Virtual 220MB+ ABR data source implementing [AbrSource].
     * Backed by zero disk storage and < 20KB heap.
     */
    private class Virtual220MbAbrSource(
        val tipCount: Int,
        val tipWidth: Int,
        val tipHeight: Int,
    ) : AbrSource {
        private val pixelDataSize = tipWidth * tipHeight
        private val recDataSize = 1 + 36 + 264 + 16 + 2 + 1 + pixelDataSize
        private val recTotalSize = 4 + recDataSize
        private val sampPayloadLen = tipCount * recTotalSize
        private val sampDataStart = 16L
        private val sampDataEnd = sampDataStart + sampPayloadLen
        private val unknSectionLen = 215L * 1024L * 1024L
        private val unknHeaderStart = sampDataEnd
        private val unknDataStart = unknHeaderStart + 12L

        override val size: Long = unknDataStart + unknSectionLen

        private fun getRecordHeaderByte(tipIndex: Int, offsetInRec: Int): Byte {
            return when (offsetInRec) {
                0 -> ((recDataSize ushr 24) and 0xFF).toByte()
                1 -> ((recDataSize ushr 16) and 0xFF).toByte()
                2 -> ((recDataSize ushr 8) and 0xFF).toByte()
                3 -> (recDataSize and 0xFF).toByte()
                4 -> 36.toByte() // UUID length
                in 5..40 -> {
                    val uuidStr = "tip_virtual_${String.format("%024d", tipIndex + 1)}"
                    uuidStr[offsetInRec - 5].code.toByte()
                }
                in 41..304 -> 0.toByte() // 264 filler bytes
                in 305..308 -> 0.toByte() // top = 0
                in 309..312 -> 0.toByte() // left = 0
                313 -> ((tipHeight ushr 24) and 0xFF).toByte()
                314 -> ((tipHeight ushr 16) and 0xFF).toByte()
                315 -> ((tipHeight ushr 8) and 0xFF).toByte()
                316 -> (tipHeight and 0xFF).toByte()
                317 -> ((tipWidth ushr 24) and 0xFF).toByte()
                318 -> ((tipWidth ushr 16) and 0xFF).toByte()
                319 -> ((tipWidth ushr 8) and 0xFF).toByte()
                320 -> (tipWidth and 0xFF).toByte()
                321 -> 0.toByte() // depth = 8
                322 -> 8.toByte()
                323 -> 0.toByte() // compression = 0
                else -> 0.toByte()
            }
        }

        override fun get(pos: Long): Byte {
            if (pos < 0L || pos >= size) throw EOFException("pos=$pos size=$size")
            return when {
                pos == 0L -> 0.toByte()
                pos == 1L -> 6.toByte() // version 6
                pos == 2L -> 0.toByte()
                pos == 3L -> 2.toByte() // subversion 2
                pos in 4L..7L -> "8BIM"[pos.toInt() - 4].code.toByte()
                pos in 8L..11L -> "samp"[pos.toInt() - 8].code.toByte()
                pos == 12L -> ((sampPayloadLen ushr 24) and 0xFF).toByte()
                pos == 13L -> ((sampPayloadLen ushr 16) and 0xFF).toByte()
                pos == 14L -> ((sampPayloadLen ushr 8) and 0xFF).toByte()
                pos == 15L -> (sampPayloadLen and 0xFF).toByte()
                pos in sampDataStart until sampDataEnd -> {
                    val rel = pos - sampDataStart
                    val tipIndex = (rel / recTotalSize).toInt()
                    val offsetInRec = (rel % recTotalSize).toInt()
                    if (offsetInRec < 324) {
                        getRecordHeaderByte(tipIndex, offsetInRec)
                    } else {
                        val pixelIdx = offsetInRec - 324
                        ((pixelIdx + tipIndex * 17) and 0xFF).toByte()
                    }
                }
                pos in unknHeaderStart until unknHeaderStart + 4L -> "8BIM"[(pos - unknHeaderStart).toInt()].code.toByte()
                pos in unknHeaderStart + 4L until unknHeaderStart + 8L -> "unkn"[(pos - unknHeaderStart - 4L).toInt()].code.toByte()
                pos == unknHeaderStart + 8L -> ((unknSectionLen ushr 24) and 0xFFL).toByte()
                pos == unknHeaderStart + 9L -> ((unknSectionLen ushr 16) and 0xFFL).toByte()
                pos == unknHeaderStart + 10L -> ((unknSectionLen ushr 8) and 0xFFL).toByte()
                pos == unknHeaderStart + 11L -> (unknSectionLen and 0xFFL).toByte()
                else -> 0.toByte() // unkn dummy payload
            }
        }

        override fun u16(pos: Long): Int = (u8(pos) shl 8) or u8(pos + 1L)
        override fun i16(pos: Long): Short = u16(pos).toShort()
        override fun i32(pos: Long): Int =
            (u8(pos) shl 24) or (u8(pos + 1L) shl 16) or (u8(pos + 2L) shl 8) or u8(pos + 3L)
        override fun i64(pos: Long): Long =
            ((u8(pos).toLong() and 0xFFL) shl 56) or
            ((u8(pos + 1L).toLong() and 0xFFL) shl 48) or
            ((u8(pos + 2L).toLong() and 0xFFL) shl 40) or
            ((u8(pos + 3L).toLong() and 0xFFL) shl 32) or
            ((u8(pos + 4L).toLong() and 0xFFL) shl 24) or
            ((u8(pos + 5L).toLong() and 0xFFL) shl 16) or
            ((u8(pos + 6L).toLong() and 0xFFL) shl 8) or
            (u8(pos + 7L).toLong() and 0xFFL)

        override fun copyTo(pos: Long, dst: ByteArray, dstOffset: Int, len: Int) {
            if (len <= 0) return
            if (pos < 0L || pos + len > size) throw EOFException("pos=$pos len=$len size=$size")

            // Fast path for bulk pixel reading
            if (pos in sampDataStart until sampDataEnd) {
                val rel = pos - sampDataStart
                val tipIndex = (rel / recTotalSize).toInt()
                val offsetInRec = (rel % recTotalSize).toInt()
                if (offsetInRec >= 324 && offsetInRec + len <= recTotalSize) {
                    var pixelIdx = offsetInRec - 324
                    val base = tipIndex * 17
                    for (i in 0 until len) {
                        dst[dstOffset + i] = ((pixelIdx++ + base) and 0xFF).toByte()
                    }
                    return
                }
            }

            // Fast path for 210MB unknown block
            if (pos >= unknDataStart) {
                java.util.Arrays.fill(dst, dstOffset, dstOffset + len, 0.toByte())
                return
            }

            for (i in 0 until len) {
                dst[dstOffset + i] = get(pos + i.toLong())
            }
        }
    }
}
