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
        depth: Int = 8,
    ): ByteArray {
        require(uuid.length == 36)
        return byteArrayOf(36) +
            uuid.toByteArray(Charsets.US_ASCII) +
            ByteArray(264) +
            be32(0) + be32(0) + be32(height) + be32(width) +
            be16(depth) + byteArrayOf(compression.toByte()) +
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
        depth: Int = 8,
    ): ByteArray {
        val rec = sampRecord(uuid, width, height, gray, compression, depth)
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

    // ---------------------------------------------------------------------------------------------
    // 16-bit sampled tips (Photoshop deep brushes, e.g. 带画包 #0 十九号画笔)
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `16-bit uncompressed tip decodes big-endian density into 8-bit alpha`() {
        val uuid = "9f74ac31-602c-11e0-a1b2-0002a5d5c51b"
        // 2x2 像素、每像素 2 字节大端：v = 0 / 32768 / 16384 / 8192
        val pixels16 = be16(0) + be16(32768) + be16(16384) + be16(8192)
        val abr = buildAbr(6, uuid, 2, 2, pixels16, depth = 16)
        val result = ByteArrayInputStream(abr).use { AbrParser.parse(it, basePackName = "deep16") }

        assertEquals("16-bit 笔尖应解析出 1 个 tip", 1, result.tips.size)
        val tip = result.tips[0]
        assertEquals("depth 字段照常记录原始位深", 16, tip.depth)
        assertEquals("输出资产口径仍是 8-bit（width*height 字节）", 4, tip.data.size)
        // alpha8 = (v*255 + 16384) / 32768（整型四舍五入）
        assertEquals(0, tip.data[0].toInt() and 0xFF)
        assertEquals(255, tip.data[1].toInt() and 0xFF)
        assertEquals(128, tip.data[2].toInt() and 0xFF)
        assertEquals(64, tip.data[3].toInt() and 0xFF)
    }

    @Test
    fun `16-bit PackBits tip decodes per-line big-endian pairs into 8-bit alpha`() {
        val uuid = "9f74ac31-602c-11e0-a1b2-0002a5d5c51b"
        // 每行解压后应为 width*2 = 4 字节（2 像素）：v = 32768/16384 与 8192/256
        val line0 = byteArrayOf(0x03) + be16(32768) + be16(16384)   // literal 4 bytes
        val line1 = byteArrayOf(0x03) + be16(8192) + be16(256)      // literal 4 bytes
        val payload = be16(line0.size) + be16(line1.size) + line0 + line1
        val abr = buildAbr(6, uuid, 2, 2, payload, compression = 1, depth = 16)
        val result = ByteArrayInputStream(abr).use { AbrParser.parse(it, basePackName = "rle16") }

        assertEquals(1, result.tips.size)
        val tip = result.tips[0]
        assertEquals(16, tip.depth)
        assertEquals(4, tip.data.size)
        assertEquals(255, tip.data[0].toInt() and 0xFF)
        assertEquals(128, tip.data[1].toInt() and 0xFF)
        assertEquals(64, tip.data[2].toInt() and 0xFF)
        assertEquals(2, tip.data[3].toInt() and 0xFF)
    }

    @Test
    fun `16-bit PackBits line shorter than width*2 falls back to the 8-bit path`() {
        val uuid = "9f74ac31-602c-11e0-a1b2-0002a5d5c51b"
        // 行解压只有 2 字节（< width*2 = 4）：16-bit 校验失败 → 回退 8-bit 老路径（每行 2 字节恰好填满）
        val line0 = byteArrayOf(0x01, 0x40, 0x00) // literal 2 bytes: 0x40, 0x00
        val line1 = byteArrayOf(0xFE.toByte(), 0x7f) // repeat 0x7f x2
        val payload = be16(line0.size) + be16(line1.size) + line0 + line1
        val abr = buildAbr(6, uuid, 2, 2, payload, compression = 1, depth = 16)
        val result = ByteArrayInputStream(abr).use { AbrParser.parse(it, basePackName = "rle16fallback") }

        assertEquals("校验失败回退老路径仍应产出 tip 而不抛", 1, result.tips.size)
        assertEquals(4, result.tips[0].data.size)
        assertEquals(0x40, result.tips[0].data[0].toInt() and 0xFF)
        assertEquals(0x00, result.tips[0].data[1].toInt() and 0xFF)
        assertEquals(0x7f, result.tips[0].data[2].toInt() and 0xFF)
        assertEquals(0x7f, result.tips[0].data[3].toInt() and 0xFF)
    }

    @Test
    fun `a corrupt desc section is skipped without throwing even without android util Log`() {
        val uuid = "9f74ac31-602c-11e0-a1b2-0002a5d5c51b"
        // desc payload 截断（int32 都读不满）：解析失败走 Log 守卫——JVM 单测无 android.util.Log
        // 桥（"not mocked" 会抛 RuntimeException），守卫后不得外溢
        val abr = be16(6) + be16(2) + section("desc", byteArrayOf(0x10, 0x00, 0x00)) +
            section("samp", be32(0))
        val result = ByteArrayInputStream(abr).use { AbrParser.parse(it, basePackName = "corruptdesc") }
        assertTrue("坏 desc 只跳过，不出预设", result.presets.isEmpty())
    }

    // ---------------------------------------------------------------------------------------------
    // R4: 最小直径的取值优先级（szVr['Mnm ']>0 优先；Mnm 缺失或为 0（抖动子控制缺省）时
    // 顶层 minimumDiameter 是真值——带画包 #1 十九号（顶层 70）与 #48 肌理-粗糙（顶层 72）
    // 的真实描述符布局相同，旧规则"Mnm 恒优先"把 Mnm=0 误当完整压感范围）
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `szVr minimum diameter wins over the legacy top-level value only when above zero`() {
        fun presetWith(szVrMnm: Double?, topLevelMin: Double?): AbrParser.AbrPresetInfo {
            val szItems = mutableMapOf<String, AbrParser.ActionDescriptorValue>(
                "bVTy" to AbrParser.ActionDescriptorValue.DoubleVal(2.0), // 钢笔压力
            )
            szVrMnm?.let { szItems["Mnm "] = AbrParser.ActionDescriptorValue.DoubleVal(it) }
            val items = mutableMapOf<String, AbrParser.ActionDescriptorValue>(
                "Nm  " to AbrParser.ActionDescriptorValue.StringVal("M"),
                "useTipDynamics" to AbrParser.ActionDescriptorValue.BoolVal(true),
                "szVr" to AbrParser.ActionDescriptorValue.Descriptor("", "brVr", szItems),
            )
            topLevelMin?.let { items["minimumDiameter"] = AbrParser.ActionDescriptorValue.DoubleVal(it) }
            val root = AbrParser.ActionDescriptorNode(
                "", "root",
                mapOf(
                    "Brsh" to AbrParser.ActionDescriptorValue.ValueList(
                        listOf(AbrParser.ActionDescriptorValue.Descriptor("", "preset", items)),
                    ),
                ),
            )
            return AbrParser.extractPresetsFromDescriptor(root, emptyList(), "T").single()
        }

        assertEquals(
            "szVr.Mnm=0.0 是抖动子控制缺省，不是完整压感范围 → 吃顶层 minimumDiameter 70",
            0.70, presetWith(szVrMnm = 0.0, topLevelMin = 70.0).minDiameterRatio, 1e-9,
        )
        assertEquals("szVr.Mnm>0 优先于顶层", 0.40, presetWith(szVrMnm = 40.0, topLevelMin = 70.0).minDiameterRatio, 1e-9)
        assertEquals("块内无 Mnm → 顶层 minimumDiameter 兜底", 0.70, presetWith(szVrMnm = null, topLevelMin = 70.0).minDiameterRatio, 1e-9)
        assertEquals("两者都无 → 缺省 0", 0.0, presetWith(szVrMnm = null, topLevelMin = null).minDiameterRatio, 1e-9)
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

    @Test
    fun `computeSha256 returns consistent hex hash for tip deduplication`() {
        val data1 = byteArrayOf(1, 2, 3, 4, 5)
        val data2 = byteArrayOf(1, 2, 3, 4, 5)
        val data3 = byteArrayOf(5, 4, 3, 2, 1)

        val hash1 = AbrParser.computeSha256(data1)
        val hash2 = AbrParser.computeSha256(data2)
        val hash3 = AbrParser.computeSha256(data3)

        assertEquals(64, hash1.length)
        assertEquals(hash1, hash2)
        assertTrue(hash1 != hash3)
    }

    @Test
    fun `extractPresetsFromDescriptor extracts tilt, jitter, dualBrush, texture, colorDynamics and airbrush`() {
        val tip = AbrParser.AbrDecodedTip(
            uuid = "sample-uuid-123",
            index = 1,
            width = 64,
            height = 64,
            depth = 8,
            data = ByteArray(4096),
        )

        val tipDesc = AbrParser.ActionDescriptorNode(
            name = "",
            classId = "sampledBrush",
            items = mapOf(
                "Dmtr" to AbrParser.ActionDescriptorValue.DoubleVal(60.0),
                "Spcn" to AbrParser.ActionDescriptorValue.DoubleVal(15.0),
                "sampledData" to AbrParser.ActionDescriptorValue.StringVal("sample-uuid-123"),
            ),
        )

        val szVrDesc = AbrParser.ActionDescriptorNode(
            name = "",
            classId = "brVr",
            items = mapOf(
                "bVTy" to AbrParser.ActionDescriptorValue.IntVal(3), // Pen Tilt
                "jitter" to AbrParser.ActionDescriptorValue.DoubleVal(25.0),
                "Mnm " to AbrParser.ActionDescriptorValue.DoubleVal(10.0),
            ),
        )

        val angVrDesc = AbrParser.ActionDescriptorNode(
            name = "",
            classId = "brVr",
            items = mapOf(
                "bVTy" to AbrParser.ActionDescriptorValue.IntVal(7), // Direction
                "jitter" to AbrParser.ActionDescriptorValue.DoubleVal(40.0),
            ),
        )

        val dualBrshDesc = AbrParser.ActionDescriptorNode(
            name = "",
            classId = "sampledBrush",
            items = mapOf(
                "Dmtr" to AbrParser.ActionDescriptorValue.DoubleVal(30.0),
                "Spcn" to AbrParser.ActionDescriptorValue.DoubleVal(20.0),
                "sampledData" to AbrParser.ActionDescriptorValue.StringVal("dual-tip-uuid"),
            ),
        )

        val dualDesc = AbrParser.ActionDescriptorNode(
            name = "",
            classId = "dualBrush",
            items = mapOf(
                "Brsh" to AbrParser.ActionDescriptorValue.Descriptor(dualBrshDesc.name, dualBrshDesc.classId, dualBrshDesc.items),
                "Md  " to AbrParser.ActionDescriptorValue.StringVal("Mltp"),
                "scatter" to AbrParser.ActionDescriptorValue.DoubleVal(15.0),
            ),
        )

        val texDesc = AbrParser.ActionDescriptorNode(
            name = "",
            classId = "texture",
            items = mapOf(
                "Txtr" to AbrParser.ActionDescriptorValue.Descriptor("", "", mapOf(
                    "Nm  " to AbrParser.ActionDescriptorValue.StringVal("Canvas Pattern"),
                    "Idnt" to AbrParser.ActionDescriptorValue.StringVal("pat-uuid-99"),
                )),
                "Scl " to AbrParser.ActionDescriptorValue.DoubleVal(150.0),
                "textureDepth" to AbrParser.ActionDescriptorValue.DoubleVal(75.0),
                "textureBlendMode" to AbrParser.ActionDescriptorValue.StringVal("Ovrl"),
                "InvT" to AbrParser.ActionDescriptorValue.BoolVal(true),
            ),
        )

        val cdDesc = AbrParser.ActionDescriptorNode(
            name = "",
            classId = "colorDynamics",
            items = mapOf(
                "hJtr" to AbrParser.ActionDescriptorValue.DoubleVal(10.0),
                "sJtr" to AbrParser.ActionDescriptorValue.DoubleVal(20.0),
                "bJtr" to AbrParser.ActionDescriptorValue.DoubleVal(30.0),
                "jitter" to AbrParser.ActionDescriptorValue.DoubleVal(50.0),
            ),
        )

        val presetItem = AbrParser.ActionDescriptorNode(
            name = "",
            classId = "preset",
            items = mapOf(
                "Nm  " to AbrParser.ActionDescriptorValue.StringVal("Rich Dynamics Preset"),
                "Brsh" to AbrParser.ActionDescriptorValue.Descriptor(tipDesc.name, tipDesc.classId, tipDesc.items),
                "useTipDynamics" to AbrParser.ActionDescriptorValue.BoolVal(true),
                "szVr" to AbrParser.ActionDescriptorValue.Descriptor(szVrDesc.name, szVrDesc.classId, szVrDesc.items),
                "angleDynamics" to AbrParser.ActionDescriptorValue.Descriptor(angVrDesc.name, angVrDesc.classId, angVrDesc.items),
                "useDualBrush" to AbrParser.ActionDescriptorValue.BoolVal(true),
                "dualBrush" to AbrParser.ActionDescriptorValue.Descriptor(dualDesc.name, dualDesc.classId, dualDesc.items),
                "useTexture" to AbrParser.ActionDescriptorValue.BoolVal(true),
                "texture" to AbrParser.ActionDescriptorValue.Descriptor(texDesc.name, texDesc.classId, texDesc.items),
                "useColorDynamics" to AbrParser.ActionDescriptorValue.BoolVal(true),
                "colorDynamics" to AbrParser.ActionDescriptorValue.Descriptor(cdDesc.name, cdDesc.classId, cdDesc.items),
                "airbrush" to AbrParser.ActionDescriptorValue.BoolVal(true),
                "mode" to AbrParser.ActionDescriptorValue.StringVal("Mltp"),
            ),
        )

        val rootNode = AbrParser.ActionDescriptorNode(
            name = "",
            classId = "root",
            items = mapOf(
                "Brsh" to AbrParser.ActionDescriptorValue.ValueList(listOf(
                    AbrParser.ActionDescriptorValue.Descriptor(presetItem.name, presetItem.classId, presetItem.items)
                )),
            ),
        )

        val presets = AbrParser.extractPresetsFromDescriptor(rootNode, listOf(tip), "TestPack")
        assertEquals(1, presets.size)

        val p = presets[0]
        assertEquals("Rich Dynamics Preset", p.name)
        assertEquals(60.0, p.diameter, 0.01)
        assertEquals(0.15, p.spacing, 0.01)
        assertTrue(p.tiltSize)
        assertEquals(0.25, p.sizeJitter, 0.01)
        assertTrue(p.followDirection)
        assertEquals(0.40, p.angleJitter, 0.01)
        assertTrue(p.airbrush)
        assertEquals("multiply", p.compositeOp)

        // Dual Brush assertions
        assertNotNull(p.dualBrush)
        assertEquals("dual-tip-uuid", p.dualBrush!!.tipUuid)
        assertEquals(30.0, p.dualBrush!!.diameter, 0.01)
        assertEquals("multiply", p.dualBrush!!.compositeOp)

        // Texture assertions
        assertNotNull(p.texture)
        assertEquals("Canvas Pattern", p.texture!!.patternName)
        assertEquals(1.5, p.texture!!.scale, 0.01)
        assertEquals(0.75, p.texture!!.depth, 0.01)
        assertEquals("overlay", p.texture!!.mode)
        assertTrue(p.texture!!.invert)

        // Color Dynamics assertions
        assertNotNull(p.colorDynamics)
        assertEquals(0.10, p.colorDynamics!!.hueJitter, 0.01)
        assertEquals(0.20, p.colorDynamics!!.satJitter, 0.01)
        assertEquals(0.30, p.colorDynamics!!.valJitter, 0.01)
        assertEquals(0.50, p.colorDynamics!!.secondaryMix, 0.01)
    }
}
