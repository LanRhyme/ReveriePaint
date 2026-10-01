/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AbrParserTest {

    @Test
    fun `decodePackBitsScanlines decodes literal and repeated runs correctly`() {
        // Test 2 scanlines of width 4:
        // Line 0: literal 2 bytes [10, 20] (n=1) + repeat 2 bytes [30] (n=-1, val=30) -> [10, 20, 30, 30]
        // Length of line 0 PackBits data: 1 (header n=1) + 2 + 1 (header n=-1) + 1 (val=30) -> 5 bytes
        // Line 1: repeat 4 bytes [99] -> n = -3 (repeats -(-3)+1 = 4 times) -> 2 bytes
        val height = 2
        val width = 4

        val line0 = byteArrayOf(
            0x01.toByte(), 10, 20,      // 2 literals: n=1
            (-1).toByte(), 30,          // 2 repeats: n=-1 (val=30)
        )
        val line1 = byteArrayOf(
            (-3).toByte(), 99           // 4 repeats: n=-3 (val=99)
        )

        val lengthsBuf = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN)
        lengthsBuf.putShort(line0.size.toShort())
        lengthsBuf.putShort(line1.size.toShort())

        val combined = lengthsBuf.array() + line0 + line1
        val decoded = AbrParser.decodePackBitsScanlines(combined, 0, height, width)

        assertNotNull(decoded)
        assertEquals(8, decoded!!.size)
        // Line 0
        assertEquals(10, decoded[0].toInt())
        assertEquals(20, decoded[1].toInt())
        assertEquals(30, decoded[2].toInt())
        assertEquals(30, decoded[3].toInt())
        // Line 1
        assertEquals(99, decoded[4].toInt())
        assertEquals(99, decoded[5].toInt())
        assertEquals(99, decoded[6].toInt())
        assertEquals(99, decoded[7].toInt())
    }

    @Test
    fun `encodeTipPng generates valid PNG header and chunks`() {
        val dummyTip = AbrParser.AbrDecodedTip(
            uuid = "test-uuid",
            index = 1,
            width = 4,
            height = 4,
            depth = 8,
            data = ByteArray(16) { (it * 16).toByte() },
        )

        val pngBytes = AbrParser.encodeTipPng(dummyTip)
        assertTrue(pngBytes.size > 20)

        // Verify PNG signature: 89 50 4E 47 0D 0A 1A 0A
        val expectedHeader = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        for (i in 0 until 8) {
            assertEquals(expectedHeader[i], pngBytes[i])
        }

        // Verify IHDR is present
        val ihdrType = String(pngBytes, 12, 4, Charsets.ISO_8859_1)
        assertEquals("IHDR", ihdrType)
    }

    @Test
    fun `encodePreviewPng generates valid preview thumbnail PNG`() {
        val dummyTip = AbrParser.AbrDecodedTip(
            uuid = "test-uuid",
            index = 1,
            width = 32,
            height = 32,
            depth = 8,
            data = ByteArray(32 * 32) { 128.toByte() },
        )

        val previewBytes = AbrParser.encodePreviewPng(dummyTip, diameter = 32.0, roundness = 1.0)
        assertTrue(previewBytes.size > 50)

        // Verify PNG signature
        assertEquals(0x89.toByte(), previewBytes[0])
        assertEquals(0x50.toByte(), previewBytes[1])
        assertEquals(0x4E.toByte(), previewBytes[2])
        assertEquals(0x47.toByte(), previewBytes[3])
    }

    @Test
    fun `parse real ABR file extracts tips and presets accurately`() {
        val file = File("/home/lanrhyme/Projects/krita-source/libs/brush/tests/data/brushes_by_mar_ka_d338ela.abr")
        if (!file.exists()) return

        val result = FileInputStream(file).use { input ->
            AbrParser.parse(input, basePackName = "MarKa")
        }

        assertEquals(6, result.version)
        assertEquals(2, result.subversion)
        assertTrue("Tips count should be around 31", result.tips.size >= 30)
        assertTrue("Presets count should be around 36", result.presets.size >= 30)

        val firstPreset = result.presets.first()
        assertNotNull(firstPreset.name)
        assertTrue("Diameter should be positive", firstPreset.diameter > 0.0)
        assertTrue("Spacing should be valid fraction", firstPreset.spacing in 0.01..5.0)

        // Test tip PNG generation for one of the extracted tips
        val firstSampledTip = result.tips.first()
        val tipPng = AbrParser.encodeTipPng(firstSampledTip)
        assertTrue(tipPng.isNotEmpty())
        assertEquals(0x89.toByte(), tipPng[0])

        // Test preview PNG generation
        val previewPng = AbrParser.encodePreviewPng(firstSampledTip, firstPreset.diameter, firstPreset.roundness)
        assertTrue(previewPng.isNotEmpty())
        assertEquals(0x89.toByte(), previewPng[0])
    }

    // ---------------------------------------------------------------------------------------------
    // v7 ~ v10 version dispatch
    //
    // Packs exported after Photoshop CS6 are usually v7~v10, while the '8BIM' section skeleton
    // has not changed since v6. These cases synthesise the ABR by hand so they do not depend on
    // any external sample file (the real-file case above returns early on a machine without it).
    // ---------------------------------------------------------------------------------------------

    private fun be16(v: Int) = byteArrayOf((v ushr 8 and 0xFF).toByte(), (v and 0xFF).toByte())

    private fun be32(v: Int) = byteArrayOf(
        (v ushr 24 and 0xFF).toByte(), (v ushr 16 and 0xFF).toByte(),
        (v ushr 8 and 0xFF).toByte(), (v and 0xFF).toByte(),
    )

    /** '8BIM' + 4-char section name + int32 length + payload */
    private fun section(name: String, payload: ByteArray): ByteArray {
        require(name.length == 4)
        return "8BIM".toByteArray(Charsets.ISO_8859_1) +
            name.toByteArray(Charsets.ISO_8859_1) +
            be32(payload.size) + payload
    }

    /**
     * One subversion=2 samp record: 1-byte name length 36 + UUID + 264 filler bytes
     * + top/left/bottom/right + int16 depth + byte compression + pixel payload.
     */
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

    /** Synthesises an ABR with a single sampled tip at the given version. */
    private fun buildAbr(
        version: Int,
        uuid: String,
        width: Int,
        height: Int,
        gray: ByteArray,
        compression: Int = 0,
    ): ByteArray {
        val rec = sampRecord(uuid, width, height, gray, compression)
        // A samp section prefixes every record with an int32 payloadSize.
        val samp = be32(rec.size) + rec
        return be16(version) + be16(2) + section("samp", samp)
    }

    @Test
    fun `v7 to v10 packs parse into tips instead of being rejected by version dispatch`() {
        val uuid = "9f74ac31-602c-11e0-a1b2-0002a5d5c51b"
        val gray = ByteArray(4 * 3) { (it * 5 + 7).toByte() }

        for (version in 7..AbrParser.MAX_SUPPORTED_VERSION) {
            val abr = buildAbr(version, uuid, 4, 3, gray)
            val result = ByteArrayInputStream(abr).use { AbrParser.parse(it, basePackName = "pack") }

            assertEquals("v$version should parse rather than return empty", version, result.version)
            assertEquals("v$version should yield one tip", 1, result.tips.size)
            assertEquals("v$version tip width", 4, result.tips[0].width)
            assertEquals("v$version tip height", 3, result.tips[0].height)
            assertTrue("v$version tip pixels", result.tips[0].data.isNotEmpty())
            assertTrue("v$version should produce a preset", result.presets.isNotEmpty())
        }
    }

    @Test
    fun `a version newer than the supported range returns empty without throwing`() {
        val uuid = "9f74ac31-602c-11e0-a1b2-0002a5d5c51b"
        val abr = buildAbr(AbrParser.MAX_SUPPORTED_VERSION + 1, uuid, 2, 2, ByteArray(4))
        val result = ByteArrayInputStream(abr).use { AbrParser.parse(it, basePackName = "future") }
        assertTrue("too-new version should return no tips", result.tips.isEmpty())
        assertTrue("too-new version should return no presets", result.presets.isEmpty())
    }

    @Test
    fun `v6 still takes the same parsing path after widening the version check`() {
        val uuid = "9f74ac31-602c-11e0-a1b2-0002a5d5c51b"
        val gray = ByteArray(2 * 2) { 0x40 }
        val abr = buildAbr(6, uuid, 2, 2, gray)
        val result = ByteArrayInputStream(abr).use { AbrParser.parse(it, basePackName = "v6") }

        assertEquals(6, result.version)
        assertEquals(2, result.subversion)
        assertEquals(1, result.tips.size)
    }

    @Test
    fun `the reported version is the real file version and not a hardcoded 6`() {
        val uuid = "9f74ac31-602c-11e0-a1b2-0002a5d5c51b"
        val abr = buildAbr(10, uuid, 2, 2, ByteArray(4))
        val result = ByteArrayInputStream(abr).use { AbrParser.parse(it, basePackName = "v10") }
        assertEquals(10, result.version)
        // Asserting the version field alone would also pass on the old dispatch, which for
        // anything other than v6 returned AbrParseResult(version, 0, emptyList(), emptyList()).
        // The tip count is what proves the file was actually parsed.
        assertEquals("v10 must really be parsed, not just echo its version", 1, result.tips.size)
        assertEquals(2, result.subversion)
    }

    // ---------------------------------------------------------------------------------------------
    // Hardening
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `an absurdly large declared tip is rejected instead of allocating hundreds of MB`() {
        // A PackBits tip declaring 16384x16384 passes a per-side check alone, and
        // decodePackBitsScanlines allocates ByteArray(height * width) as its very first
        // statement - 268MB, thrown as OutOfMemoryError, which no try/catch(Exception) catches.
        val uuid = "9f74ac31-602c-11e0-a1b2-0002a5d5c51b"
        val abr = buildAbr(6, uuid, 16384, 16384, ByteArray(8), compression = 1)
        val result = ByteArrayInputStream(abr).use { AbrParser.parse(it, basePackName = "corrupt") }
        assertTrue("implausible tip dimensions must not yield a tip", result.tips.isEmpty())
    }

    @Test
    fun `a computed preset never binds a sampled tip`() {
        // Computed presets carry no sampledData, so their tipUuid stays null - but their
        // tipIndex is filled with "index + 1", which used to match an unrelated sampled tip.
        val tip = AbrParser.AbrDecodedTip(
            uuid = "tip-uuid",
            index = 3,
            width = 8,
            height = 8,
            depth = 8,
            data = ByteArray(64),
        )
        val computed = AbrParser.AbrPresetInfo(
            name = "computed",
            tipUuid = null,
            tipIndex = 3, // would resolve to tipsByIndex[3] == tip
            diameter = 40.0,
            isComputed = true,
        )

        val matched = AbrParser.matchTipForPreset(
            computed,
            tipsByUuid = mapOf("tip-uuid" to tip),
            tipsByIndex = mapOf(3 to tip),
            allTips = listOf(tip),
        )
        assertEquals("a computed preset must not bind someone else's tip", null, matched)

        val sampled = computed.copy(tipUuid = "tip-uuid", isComputed = false)
        assertEquals(
            "a sampled preset still resolves its own tip",
            tip,
            AbrParser.matchTipForPreset(sampled, mapOf("tip-uuid" to tip), mapOf(3 to tip), listOf(tip)),
        )
    }
}
