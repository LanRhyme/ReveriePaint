package com.reverie.paint.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * Pure Kotlin streaming parser for Adobe Photoshop .abr brush collection files.
 * Supports:
 * - Version 1 & 2 (Photoshop 1.0 - 6.0 sampled & computed brushes)
 * - Version 6+ (Photoshop 7.0 - CC 2026, 8BIM 'samp' and 'desc' ActionDescriptor blocks)
 * - PackBits RLE scanline decoding
 * - Deep parameter mapping (diameter, spacing, angle, roundness, scatter, pressure dynamics)
 * - Pure Kotlin RGBA PNG encoding for brush tips and preset preview thumbnails
 */
object AbrParser {

    data class AbrDecodedTip(
        val uuid: String,
        val index: Int,
        val width: Int,
        val height: Int,
        val depth: Int = 8,
        val data: ByteArray = ByteArray(0), // width * height bytes of 8-bit density (0 = empty, 255 = opaque)
        val thumbnail: ByteArray? = null, // Small downscaled density map for lightweight preview generation
        val thumbWidth: Int = 0,
        val thumbHeight: Int = 0,
        val sha256: String = "",
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is AbrDecodedTip) return false
            return uuid == other.uuid && index == other.index && width == other.width &&
                height == other.height && depth == other.depth && data.contentEquals(other.data) &&
                (thumbnail == null && other.thumbnail == null || thumbnail != null && other.thumbnail != null && thumbnail.contentEquals(other.thumbnail)) &&
                thumbWidth == other.thumbWidth && thumbHeight == other.thumbHeight && sha256 == other.sha256
        }

        override fun hashCode(): Int {
            var result = uuid.hashCode()
            result = 31 * result + index
            result = 31 * result + width
            result = 31 * result + height
            result = 31 * result + depth
            result = 31 * result + data.contentHashCode()
            result = 31 * result + (thumbnail?.contentHashCode() ?: 0)
            result = 31 * result + thumbWidth
            result = 31 * result + thumbHeight
            result = 31 * result + sha256.hashCode()
            return result
        }
    }

    data class AbrDualBrushInfo(
        val tipUuid: String? = null,
        val diameter: Double = 30.0,
        val spacing: Double = 0.25,
        val scatter: Double = 0.0,
        val compositeOp: String = "multiply",
        val flipX: Boolean = false,
        val flipY: Boolean = false,
    )

    data class AbrTextureInfo(
        val patternName: String = "",
        val patternUuid: String = "",
        val scale: Double = 1.0,
        val depth: Double = 0.5,
        val mode: String = "multiply",
        val invert: Boolean = false,
    )

    data class AbrColorDynamicsInfo(
        val hueJitter: Double = 0.0,
        val satJitter: Double = 0.0,
        val valJitter: Double = 0.0,
        val secondaryMix: Double = 0.0,
    )

    data class AbrPresetInfo(
        val name: String,
        val tipUuid: String? = null,
        val tipIndex: Int = -1,
        val diameter: Double = 30.0,
        val spacing: Double = 0.25, // fraction 0.01 .. 5.0
        val angle: Double = 0.0, // degrees 0 .. 360
        val roundness: Double = 1.0, // fraction 0.01 .. 1.0
        val hardness: Double = 1.0,
        val scatter: Double = 0.0,
        val pressureSize: Boolean = false,
        val pressureOpacity: Boolean = false,
        val pressureFlow: Boolean = false,
        val tiltSize: Boolean = false,
        val tiltOpacity: Boolean = false,
        val tiltFlow: Boolean = false,
        val tiltAngle: Boolean = false,
        val sizeJitter: Double = 0.0,
        val angleJitter: Double = 0.0,
        val roundnessJitter: Double = 0.0,
        val opacityJitter: Double = 0.0,
        val flowJitter: Double = 0.0,
        val minDiameterRatio: Double = 0.0,
        val minRoundness: Double = 0.0,
        val followDirection: Boolean = false,
        val flipX: Boolean = false,
        val flipY: Boolean = false,
        val isComputed: Boolean = false,
        val dualBrush: AbrDualBrushInfo? = null,
        val texture: AbrTextureInfo? = null,
        val colorDynamics: AbrColorDynamicsInfo? = null,
        val airbrush: Boolean = false,
        val compositeOp: String = "normal",
    )

    data class AbrParseResult(
        val version: Int,
        val subversion: Int,
        val tips: List<AbrDecodedTip>,
        val presets: List<AbrPresetInfo>,
    )

    private val SUBVERSION_HEADER_SKIP = mapOf(1 to 47, 2 to 301)

    /**
     * Highest ABR version this parser accepts.
     *
     * Versions after v6 only mean Photoshop added new features; the `8BIM` section skeleton
     * (`samp` / `desc`) is unchanged, so the version check is widened to a range instead of
     * enumerating versions one by one. Photoshop exports made after CS6 are mostly v7~v10,
     * and the earlier `6 ->` branch rejected all of them outright. A genuinely incompatible
     * version still yields an empty result (the caller falls back) rather than throwing.
     */
    const val MAX_SUPPORTED_VERSION = 10

    /**
     * Sanity check for tip bitmap dimensions.
     *
     * **A per-side limit alone is not enough**: `width * height * (depth / 8)` decides how big
     * a ByteArray gets allocated, and a corrupt or maliciously crafted ABR can declare
     * 16384x16384 — each side within bounds, yet a single 268MB allocation. On a device with a
     * 256MB per-app heap that is an instant crash, and because it is an `OutOfMemoryError`
     * (an `Error`, not an `Exception`) no caller-side try/catch can recover from it.
     *
     * The thresholds are deliberately loose so real tips are never rejected: the largest tip in
     * the biggest pack we measured (375 sampled tips) is 3480x3426 ≈ 11.9M pixels, and
     * Photoshop itself caps brushes at roughly 5000px. Per-side 8192 plus 64M total pixels
     * leaves more than a 5x margin over real-world data.
     */
    private fun plausibleTipDimensions(w: Int, h: Int): Boolean =
        w in 1..MAX_TIP_SIDE && h in 1..MAX_TIP_SIDE &&
            w.toLong() * h.toLong() <= MAX_TIP_PIXELS

    private const val MAX_TIP_SIDE = 8192
    private const val MAX_TIP_PIXELS = 64L * 1024 * 1024

    /**
     * Pairs an ABR preset with one of the parsed tips.
     *
     * **Only sampled (bitmap) brushes should bind a tip.** Photoshop's computed brushes have no
     * tip resource — their shape is described by diameter / roundness / hardness. The `samp`
     * section only stores sampled tips and computed presets carry no `sampledData` in `desc`,
     * so their [AbrPresetInfo.tipUuid] is null.
     *
     * The previous expression unconditionally fell back through
     * `tipsByUuid[uuid] ?: tipsByIndex[tipIndex] ?: tips.first()`, while a computed preset's
     * `tipIndex` happens to be filled with "list index + 1" — so **every computed preset got
     * bound to an unrelated sampled tip**. Measured on a real pack (375 tips / 493 presets),
     * 47 presets were affected. The damage is not limited to a wrong thumbnail: the emitted
     * .kpp turns into a `png_brush` pointing at someone else's tip, so the actual stroke is wrong.
     *
     * A sampled brush whose UUID cannot be resolved (should not happen with a well-formed file)
     * still falls back to the index, which at least belongs to a real tip. When nothing matches
     * at all we return null so the caller degrades to a round `auto_brush` instead of forcing an
     * unrelated tip upon it.
     */
    fun matchTipForPreset(
        preset: AbrPresetInfo,
        tipsByUuid: Map<String, AbrDecodedTip>,
        tipsByIndex: Map<Int, AbrDecodedTip>,
        allTips: List<AbrDecodedTip>,
    ): AbrDecodedTip? {
        if (preset.tipUuid == null) return null
        return tipsByUuid[preset.tipUuid]
            ?: tipsByIndex[preset.tipIndex]
            ?: allTips.firstOrNull()
    }

    fun computeSha256(data: ByteArray): String {
        if (data.isEmpty()) return ""
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val digest = md.digest(data)
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            val v = b.toInt() and 0xFF
            if (v < 16) sb.append('0')
            sb.append(Integer.toHexString(v))
        }
        return sb.toString()
    }

    /**
     * Parses an ABR stream into decoded tips and preset configurations.
     * If [onTipDecoded] is provided, each decoded tip is dispatched immediately and
     * the in-memory tip stores a lightweight thumbnail instead of the full raw pixel array,
     * drastically reducing peak heap memory usage to prevent OOM on large ABR files.
     */
    fun parse(
        input: InputStream,
        basePackName: String = "ABR",
        onTipDecoded: ((AbrDecodedTip) -> Unit)? = null,
    ): AbrParseResult {
        val bytes = input.readBytes()
        if (bytes.size < 4) {
            return AbrParseResult(0, 0, emptyList(), emptyList())
        }

        val dis = DataInputStream(ByteArrayInputStream(bytes))
        val version = dis.readUnsignedShort()

        return when (version) {
            1, 2 -> parseVersion12(bytes, version, basePackName, onTipDecoded)
            // v6 and later (v6 ~ v10) share the same `8BIM` section layout: `samp` holds the
            // sampled tips and `desc` holds the ActionDescriptor. Adobe exports after CS6 are
            // almost all v7~v10, so only accepting `6` meant those files produced zero presets.
            in 6..MAX_SUPPORTED_VERSION -> parseVersion6(bytes, basePackName, onTipDecoded)
            else -> AbrParseResult(version, 0, emptyList(), emptyList())
        }
    }

    fun generateThumbnail(tip: AbrDecodedTip, targetSize: Int = 150): Triple<ByteArray, Int, Int> {
        val srcW = tip.width
        val srcH = tip.height
        val srcData = tip.data
        if (srcW <= 0 || srcH <= 0 || srcData.isEmpty()) return Triple(ByteArray(0), 0, 0)

        val maxDim = maxOf(srcW, srcH)
        val scale = if (maxDim > targetSize) targetSize.toDouble() / maxDim else 1.0
        val dstW = maxOf(1, (srcW * scale).toInt())
        val dstH = maxOf(1, (srcH * scale).toInt())
        val thumb = ByteArray(dstW * dstH)

        for (y in 0 until dstH) {
            val sy = ((y.toDouble() / dstH) * srcH).toInt().coerceIn(0, srcH - 1)
            for (x in 0 until dstW) {
                val sx = ((x.toDouble() / dstW) * srcW).toInt().coerceIn(0, srcW - 1)
                thumb[y * dstW + x] = srcData[sy * srcW + sx]
            }
        }
        return Triple(thumb, dstW, dstH)
    }

    // ---------------------------------------------------------------------------------------------
    // Version 1 & 2 Parser
    // ---------------------------------------------------------------------------------------------

    private fun parseVersion12(
        bytes: ByteArray,
        version: Int,
        basePackName: String,
        onTipDecoded: ((AbrDecodedTip) -> Unit)? = null,
    ): AbrParseResult {
        val dis = DataInputStream(ByteArrayInputStream(bytes))
        dis.readUnsignedShort() // version
        val count = dis.readUnsignedShort()

        val tips = mutableListOf<AbrDecodedTip>()
        val presets = mutableListOf<AbrPresetInfo>()

        var cursor = 4
        for (i in 0 until count) {
            if (cursor + 6 > bytes.size) break
            val brushType = ByteBuffer.wrap(bytes, cursor, 2).order(ByteOrder.BIG_ENDIAN).short.toInt()
            val brushSize = ByteBuffer.wrap(bytes, cursor + 2, 4).order(ByteOrder.BIG_ENDIAN).int
            val nextBrush = cursor + 6 + brushSize

            cursor += 6
            if (brushType == 2) { // sampled brush
                if (cursor + 6 > bytes.size) {
                    cursor = nextBrush
                    continue
                }
                // Discard 4 misc bytes, read 2 spacing bytes
                val spacingVal = ByteBuffer.wrap(bytes, cursor + 4, 2).order(ByteOrder.BIG_ENDIAN).short.toInt()
                val spacing = if (spacingVal > 0) (spacingVal / 100.0).coerceIn(0.01, 5.0) else 0.25
                cursor += 6

                var brushName = "$basePackName ${i + 1}"
                if (version == 2) {
                    if (cursor + 4 <= bytes.size) {
                        val nameLen = ByteBuffer.wrap(bytes, cursor, 4).order(ByteOrder.BIG_ENDIAN).int
                        cursor += 4
                        if (nameLen > 0 && cursor + nameLen * 2 <= bytes.size) {
                            brushName = String(bytes, cursor, nameLen * 2, Charsets.UTF_16BE).trimEnd('\u0000')
                            cursor += nameLen * 2
                        }
                    }
                }

                // Discard 1 byte antialias + 8 bytes short bounds (9 bytes total)
                cursor += 9
                if (cursor + 19 <= bytes.size) {
                    val top = ByteBuffer.wrap(bytes, cursor, 4).order(ByteOrder.BIG_ENDIAN).int
                    val left = ByteBuffer.wrap(bytes, cursor + 4, 4).order(ByteOrder.BIG_ENDIAN).int
                    val bottom = ByteBuffer.wrap(bytes, cursor + 8, 4).order(ByteOrder.BIG_ENDIAN).int
                    val right = ByteBuffer.wrap(bytes, cursor + 12, 4).order(ByteOrder.BIG_ENDIAN).int
                    val depth = ByteBuffer.wrap(bytes, cursor + 16, 2).order(ByteOrder.BIG_ENDIAN).short.toInt()
                    val compression = bytes[cursor + 18].toInt()
                    cursor += 19

                    val width = right - left
                    val height = bottom - top

                    if (plausibleTipDimensions(width, height)) {
                        val tipData = if (compression == 1) {
                            decodePackBitsScanlines(bytes, cursor, height, width)
                        } else {
                            val dataLen = width * height * (depth / 8).coerceAtLeast(1)
                            if (cursor + dataLen <= bytes.size) {
                                bytes.copyOfRange(cursor, cursor + dataLen)
                            } else null
                        }

                        if (tipData != null) {
                            val uuid = "v${version}_tip_${i + 1}"
                            val sha = computeSha256(tipData)
                            val fullTip = AbrDecodedTip(
                                uuid = uuid,
                                index = i + 1,
                                width = width,
                                height = height,
                                depth = depth,
                                data = tipData,
                                sha256 = sha,
                            )
                            onTipDecoded?.invoke(fullTip)

                            val tipToKeep = if (onTipDecoded != null) {
                                val (thumb, tw, th) = generateThumbnail(fullTip)
                                AbrDecodedTip(
                                    uuid = uuid,
                                    index = i + 1,
                                    width = width,
                                    height = height,
                                    depth = depth,
                                    data = ByteArray(0),
                                    thumbnail = thumb,
                                    thumbWidth = tw,
                                    thumbHeight = th,
                                    sha256 = sha,
                                )
                            } else {
                                fullTip
                            }
                            tips.add(tipToKeep)
                            presets.add(
                                AbrPresetInfo(
                                    name = brushName.ifBlank { "$basePackName ${i + 1}" },
                                    tipUuid = uuid,
                                    tipIndex = i + 1,
                                    diameter = maxOf(width, height).toDouble(),
                                    spacing = spacing,
                                    angle = 0.0,
                                    roundness = 1.0,
                                )
                            )
                        }
                    }
                }
            }
            cursor = nextBrush
        }

        return AbrParseResult(version, 0, tips, presets)
    }

    // ---------------------------------------------------------------------------------------------
    // Version 6+ Parser (Photoshop 7.0 - CC)
    // ---------------------------------------------------------------------------------------------

    private fun parseVersion6(
        bytes: ByteArray,
        basePackName: String,
        onTipDecoded: ((AbrDecodedTip) -> Unit)? = null,
    ): AbrParseResult {
        var cursor = 0
        val version = ByteBuffer.wrap(bytes, cursor, 2).order(ByteOrder.BIG_ENDIAN).short.toInt()
        cursor += 2
        val subversion = ByteBuffer.wrap(bytes, cursor, 2).order(ByteOrder.BIG_ENDIAN).short.toInt()
        cursor += 2

        val tips = mutableListOf<AbrDecodedTip>()
        var descriptorRoot: ActionDescriptorNode? = null

        val fileSize = bytes.size
        while (cursor + 12 <= fileSize) {
            val tag = String(bytes, cursor, 4, Charsets.ISO_8859_1)
            if (tag != "8BIM") {
                // Seek to next 8BIM if possible
                var found = false
                for (p in cursor until fileSize - 8) {
                    if (bytes[p] == '8'.code.toByte() &&
                        bytes[p + 1] == 'B'.code.toByte() &&
                        bytes[p + 2] == 'I'.code.toByte() &&
                        bytes[p + 3] == 'M'.code.toByte()
                    ) {
                        cursor = p
                        found = true
                        break
                    }
                }
                if (!found) break
                continue
            }

            val key = String(bytes, cursor + 4, 4, Charsets.ISO_8859_1)
            val sectionLen = ByteBuffer.wrap(bytes, cursor + 8, 4).order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xFFFFFFFFL
            val sectionStart = cursor + 12
            val sectionEnd = (sectionStart + sectionLen).toInt().coerceAtMost(fileSize)

            if (key == "samp") {
                tips.addAll(parseSampSection(bytes, sectionStart, sectionEnd, subversion, onTipDecoded))
            } else if (key == "desc") {
                try {
                    val descDis = ByteCursor(bytes, sectionStart)
                    descDis.readInt32() // descriptor version, usually 16
                    descriptorRoot = readActionDescriptor(descDis)
                } catch (e: Exception) {
                    android.util.Log.w("AbrParser", "Failed to parse desc ActionDescriptor", e)
                }
            }

            cursor = sectionEnd
        }

        val presets = mutableListOf<AbrPresetInfo>()
        if (descriptorRoot != null) {
            presets.addAll(extractPresetsFromDescriptor(descriptorRoot, tips, basePackName))
        }

        // If no presets were extracted from 'desc' (or 'desc' was missing), fallback to sampled tips
        if (presets.isEmpty() && tips.isNotEmpty()) {
            for ((idx, tip) in tips.withIndex()) {
                presets.add(
                    AbrPresetInfo(
                        name = "$basePackName ${idx + 1}",
                        tipUuid = tip.uuid,
                        tipIndex = tip.index,
                        diameter = maxOf(tip.width, tip.height).toDouble(),
                        spacing = 0.25,
                        angle = 0.0,
                        roundness = 1.0,
                    )
                )
            }
        }

        return AbrParseResult(version, subversion, tips, presets)
    }

    private fun parseSampSection(
        bytes: ByteArray,
        sectionStart: Int,
        sectionEnd: Int,
        subversion: Int,
        onTipDecoded: ((AbrDecodedTip) -> Unit)? = null,
    ): List<AbrDecodedTip> {
        val tips = mutableListOf<AbrDecodedTip>()
        var cursor = sectionStart
        var index = 1

        val skipOffset = SUBVERSION_HEADER_SKIP[subversion] ?: 301

        while (cursor + 4 < sectionEnd) {
            val entryLen = ByteBuffer.wrap(bytes, cursor, 4).order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xFFFFFFFFL
            val entryDataStart = cursor + 4
            var paddedLen = entryLen
            if (paddedLen % 4L != 0L) {
                paddedLen += (4L - (paddedLen % 4L))
            }
            val nextEntryPos = (entryDataStart + paddedLen).toInt()

            if (entryDataStart >= sectionEnd) break

            val uuidLen = bytes[entryDataStart].toInt() and 0xFF
            val uuid = if (uuidLen > 0 && entryDataStart + 1 + uuidLen <= sectionEnd) {
                String(bytes, entryDataStart + 1, uuidLen, Charsets.US_ASCII)
            } else "tip_$index"

            val boundsStart = entryDataStart + skipOffset
            if (boundsStart + 19 <= sectionEnd && boundsStart + 19 <= nextEntryPos) {
                val top = ByteBuffer.wrap(bytes, boundsStart, 4).order(ByteOrder.BIG_ENDIAN).int
                val left = ByteBuffer.wrap(bytes, boundsStart + 4, 4).order(ByteOrder.BIG_ENDIAN).int
                val bottom = ByteBuffer.wrap(bytes, boundsStart + 8, 4).order(ByteOrder.BIG_ENDIAN).int
                val right = ByteBuffer.wrap(bytes, boundsStart + 12, 4).order(ByteOrder.BIG_ENDIAN).int
                val depth = ByteBuffer.wrap(bytes, boundsStart + 16, 2).order(ByteOrder.BIG_ENDIAN).short.toInt()
                val compress = bytes[boundsStart + 18].toInt()

                val width = right - left
                val height = bottom - top

                if (plausibleTipDimensions(width, height)) {
                    val pixelDataStart = boundsStart + 19
                    val pixelData = if (compress == 1) {
                        decodePackBitsScanlines(bytes, pixelDataStart, height, width)
                    } else {
                        val needed = width * height * (depth / 8).coerceAtLeast(1)
                        if (pixelDataStart + needed <= sectionEnd) {
                            bytes.copyOfRange(pixelDataStart, pixelDataStart + needed)
                        } else null
                    }

                    if (pixelData != null) {
                        val sha = computeSha256(pixelData)
                        val fullTip = AbrDecodedTip(
                            uuid = uuid,
                            index = index,
                            width = width,
                            height = height,
                            depth = depth,
                            data = pixelData,
                            sha256 = sha,
                        )
                        onTipDecoded?.invoke(fullTip)

                        val tipToKeep = if (onTipDecoded != null) {
                            val (thumb, tw, th) = generateThumbnail(fullTip)
                            AbrDecodedTip(
                                uuid = uuid,
                                index = index,
                                width = width,
                                height = height,
                                depth = depth,
                                data = ByteArray(0),
                                thumbnail = thumb,
                                thumbWidth = tw,
                                thumbHeight = th,
                                sha256 = sha,
                            )
                        } else {
                            fullTip
                        }
                        tips.add(tipToKeep)
                        index++
                    }
                }
            }

            cursor = if (nextEntryPos > cursor) nextEntryPos else (cursor + 4)
        }
        return tips
    }

    // ---------------------------------------------------------------------------------------------
    // PackBits RLE Scanline Decoder
    // ---------------------------------------------------------------------------------------------

    fun decodePackBitsScanlines(bytes: ByteArray, offset: Int, height: Int, width: Int): ByteArray? {
        val totalPixels = height * width
        val output = ByteArray(totalPixels)

        var cursor = offset
        if (cursor + height * 2 > bytes.size) return null

        val scanlineLengths = IntArray(height)
        for (i in 0 until height) {
            scanlineLengths[i] = ByteBuffer.wrap(bytes, cursor, 2).order(ByteOrder.BIG_ENDIAN).short.toInt() and 0xFFFF
            cursor += 2
        }

        for (h in 0 until height) {
            val lineLen = scanlineLengths[h]
            val lineEnd = cursor + lineLen
            if (lineEnd > bytes.size) break

            val lineStartOut = h * width
            val lineEndOut = lineStartOut + width
            var lineOutPos = lineStartOut

            while (cursor < lineEnd && lineOutPos < lineEndOut) {
                val n = bytes[cursor++].toInt()
                if (n in 0..127) {
                    val count = n + 1
                    val take = minOf(count, lineEnd - cursor, lineEndOut - lineOutPos)
                    if (take > 0) {
                        System.arraycopy(bytes, cursor, output, lineOutPos, take)
                        cursor += take
                        lineOutPos += take
                    }
                } else if (n in -127..-1) {
                    val count = -n + 1
                    if (cursor < lineEnd) {
                        val byteVal = bytes[cursor++]
                        val take = minOf(count, lineEndOut - lineOutPos)
                        for (c in 0 until take) {
                            output[lineOutPos++] = byteVal
                        }
                    }
                }
                // n == -128 is nop
            }
            cursor = lineEnd
        }

        return output
    }

    // ---------------------------------------------------------------------------------------------
    // ActionDescriptor Parser
    // ---------------------------------------------------------------------------------------------

    sealed class ActionDescriptorValue {
        data class Descriptor(val name: String, val classId: String, val items: Map<String, ActionDescriptorValue>) : ActionDescriptorValue()
        data class ValueList(val list: List<ActionDescriptorValue>) : ActionDescriptorValue()
        data class DoubleVal(val value: Double) : ActionDescriptorValue()
        data class UnitFloatVal(val unit: String, val value: Double) : ActionDescriptorValue()
        data class StringVal(val value: String) : ActionDescriptorValue()
        data class EnumVal(val typeId: String, val enumValue: String) : ActionDescriptorValue()
        data class IntVal(val value: Int) : ActionDescriptorValue()
        data class LongVal(val value: Long) : ActionDescriptorValue()
        data class BoolVal(val value: Boolean) : ActionDescriptorValue()
        data class RawBytes(val data: ByteArray) : ActionDescriptorValue()
    }

    data class ActionDescriptorNode(
        val name: String,
        val classId: String,
        val items: Map<String, ActionDescriptorValue>,
    )

    private class ByteCursor(val bytes: ByteArray, var pos: Int) {
        fun readInt32(): Int {
            val v = ByteBuffer.wrap(bytes, pos, 4).order(ByteOrder.BIG_ENDIAN).int
            pos += 4
            return v
        }

        fun readUInt32(): Long {
            return readInt32().toLong() and 0xFFFFFFFFL
        }

        fun readInt64(): Long {
            val v = ByteBuffer.wrap(bytes, pos, 8).order(ByteOrder.BIG_ENDIAN).long
            pos += 8
            return v
        }

        fun readDouble(): Double {
            val v = ByteBuffer.wrap(bytes, pos, 8).order(ByteOrder.BIG_ENDIAN).double
            pos += 8
            return v
        }

        fun readByte(): Byte {
            return bytes[pos++]
        }

        fun readBytes(len: Int): ByteArray {
            val arr = bytes.copyOfRange(pos, pos + len)
            pos += len
            return arr
        }

        fun readUnicodeString(): String {
            val charCount = readUInt32().toInt()
            val byteCount = charCount * 2
            if (byteCount <= 0 || pos + byteCount > bytes.size) return ""
            val s = String(bytes, pos, byteCount, Charsets.UTF_16BE).trimEnd('\u0000')
            pos += byteCount
            return s
        }

        fun readIdString(): String {
            val len = readUInt32().toInt()
            return if (len == 0) {
                readTypeTag()
            } else {
                val s = String(bytes, pos, len, Charsets.US_ASCII)
                pos += len
                s
            }
        }

        fun readTypeTag(): String {
            val s = String(bytes, pos, 4, Charsets.ISO_8859_1)
            pos += 4
            return s
        }

        fun readReference(): List<Any> {
            val count = readUInt32().toInt()
            val list = mutableListOf<Any>()
            for (i in 0 until count) {
                when (val refType = readTypeTag()) {
                    "prop" -> {
                        readUnicodeString()
                        readIdString()
                        readIdString()
                    }
                    "Clss" -> {
                        readUnicodeString()
                        readIdString()
                    }
                    "Enmr" -> {
                        readUnicodeString()
                        readIdString()
                        readIdString()
                        readIdString()
                    }
                    "rele" -> {
                        readUnicodeString()
                        readIdString()
                        readInt32()
                    }
                    "Idnt", "indx" -> readInt32()
                    "name" -> {
                        readUnicodeString()
                        readIdString()
                        readUnicodeString()
                    }
                    else -> {}
                }
            }
            return list
        }
    }

    private fun readActionDescriptor(c: ByteCursor): ActionDescriptorNode {
        val name = c.readUnicodeString()
        val classId = c.readIdString()
        val count = c.readUInt32().toInt()
        val items = mutableMapOf<String, ActionDescriptorValue>()

        for (i in 0 until count) {
            val key = c.readIdString()
            val typeTag = c.readTypeTag()
            val value = readDescriptorValue(c, typeTag)
            items[key] = value
        }

        return ActionDescriptorNode(name, classId, items)
    }

    private fun readDescriptorValue(c: ByteCursor, typeTag: String): ActionDescriptorValue {
        return when (typeTag) {
            "Objc", "GlbO" -> {
                val sub = readActionDescriptor(c)
                ActionDescriptorValue.Descriptor(sub.name, sub.classId, sub.items)
            }
            "VlLs" -> {
                val count = c.readUInt32().toInt()
                val list = mutableListOf<ActionDescriptorValue>()
                for (i in 0 until count) {
                    val itemType = c.readTypeTag()
                    list.add(readDescriptorValue(c, itemType))
                }
                ActionDescriptorValue.ValueList(list)
            }
            "doub" -> ActionDescriptorValue.DoubleVal(c.readDouble())
            "UntF" -> {
                val unit = c.readTypeTag()
                val value = c.readDouble()
                ActionDescriptorValue.UnitFloatVal(unit, value)
            }
            "TEXT" -> ActionDescriptorValue.StringVal(c.readUnicodeString())
            "enum" -> {
                val typeId = c.readIdString()
                val enumVal = c.readIdString()
                ActionDescriptorValue.EnumVal(typeId, enumVal)
            }
            "long" -> ActionDescriptorValue.IntVal(c.readInt32())
            "comp" -> ActionDescriptorValue.LongVal(c.readInt64())
            "bool" -> ActionDescriptorValue.BoolVal(c.readByte().toInt() != 0)
            "type", "GlbC" -> {
                c.readUnicodeString()
                c.readIdString()
                ActionDescriptorValue.StringVal("")
            }
            "alis", "tdta" -> {
                val len = c.readUInt32().toInt()
                ActionDescriptorValue.RawBytes(c.readBytes(len))
            }
            "obj " -> {
                c.readReference()
                ActionDescriptorValue.StringVal("")
            }
            else -> ActionDescriptorValue.StringVal("")
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Semantic Preset Extractor
    // ---------------------------------------------------------------------------------------------

    private fun extractDouble(v: ActionDescriptorValue?): Double? {
        return when (v) {
            is ActionDescriptorValue.DoubleVal -> v.value
            is ActionDescriptorValue.UnitFloatVal -> v.value
            is ActionDescriptorValue.IntVal -> v.value.toDouble()
            is ActionDescriptorValue.LongVal -> v.value.toDouble()
            else -> null
        }
    }

    private fun extractString(v: ActionDescriptorValue?): String? {
        return when (v) {
            is ActionDescriptorValue.StringVal -> v.value
            is ActionDescriptorValue.EnumVal -> v.enumValue
            else -> null
        }
    }

    private fun extractBool(v: ActionDescriptorValue?): Boolean {
        return when (v) {
            is ActionDescriptorValue.BoolVal -> v.value
            is ActionDescriptorValue.IntVal -> v.value != 0
            else -> false
        }
    }

    private fun extractItems(v: ActionDescriptorValue?): Map<String, ActionDescriptorValue> {
        return when (v) {
            is ActionDescriptorValue.Descriptor -> v.items
            else -> emptyMap()
        }
    }

    internal fun extractPresetsFromDescriptor(
        root: ActionDescriptorNode,
        tips: List<AbrDecodedTip>,
        basePackName: String,
    ): List<AbrPresetInfo> {
        val brshListVal = root.items["Brsh"] ?: return emptyList()
        val presets = mutableListOf<AbrPresetInfo>()

        val rawList = when (brshListVal) {
            is ActionDescriptorValue.ValueList -> brshListVal.list
            is ActionDescriptorValue.Descriptor -> listOf(brshListVal)
            else -> emptyList()
        }

        val tipsByUuid = tips.associateBy { it.uuid }

        for ((idx, itemVal) in rawList.withIndex()) {
            val itemDesc = itemVal as? ActionDescriptorValue.Descriptor ?: continue
            val items = itemDesc.items

            val presetName = extractString(items["Nm  "])?.takeIf { it.isNotBlank() } ?: "$basePackName ${idx + 1}"

            val tipDesc = itemDesc.items["Brsh"] as? ActionDescriptorValue.Descriptor
            val tipItems = tipDesc?.items ?: emptyMap()
            val isSampled = tipDesc?.classId == "sampledBrush"

            val diameter = extractDouble(tipItems["Dmtr"]) ?: 30.0
            val spacingPercent = extractDouble(tipItems["Spcn"]) ?: 25.0
            val spacing = (spacingPercent / 100.0).coerceIn(0.01, 5.0)
            val angleDeg = extractDouble(tipItems["Angl"]) ?: 0.0
            val roundnessPercent = extractDouble(tipItems["Rndn"]) ?: 100.0
            val roundness = (roundnessPercent / 100.0).coerceIn(0.01, 1.0)
            val hardnessPercent = extractDouble(tipItems["Hrdn"]) ?: 100.0
            val hardness = (hardnessPercent / 100.0).coerceIn(0.0, 1.0)
            val flipX = extractBool(tipItems["flipX"])
            val flipY = extractBool(tipItems["flipY"])

            val tipUuid = if (isSampled) extractString(tipItems["sampledData"]) else null

            // Blend Mode
            val rawMode = extractString(items["mode"]) ?: extractString(items["Md  "])
            val compOp = when (rawMode) {
                "Mltp" -> "multiply"
                "Drkn" -> "darken"
                "Lghn" -> "lighten"
                "Scrn" -> "screen"
                "Ovrl" -> "overlay"
                "SftL" -> "soft_light"
                "HrdL", "linearLight", "vividLight", "pinLight" -> "hard_light"
                "Dfrn" -> "difference"
                "linearBurn", "colorBurn" -> "burn"
                "linearDodge", "colorDodge" -> "dodge"
                "Clr " -> "color"
                "Lmns" -> "luminosity"
                "H   " -> "hue"
                "Strt" -> "saturation"
                else -> "normal"
            }

            // Airbrush
            val airbrush = extractBool(items["airbrush"]) || extractBool(items["buildUp"])

            // Shape Dynamics
            val useTipDynamics = extractBool(items["useTipDynamics"])
            var pressureSize = false
            var tiltSize = false
            var sizeJitter = 0.0
            var minDiameterRatio = 0.0
            var followDirection = false
            var tiltAngle = false
            var angleJitter = 0.0
            var roundnessJitter = 0.0
            var minRoundness = 0.0

            if (useTipDynamics) {
                val szVr = extractItems(items["szVr"])
                val szControl = extractDouble(szVr["bVTy"])?.toInt() ?: 0
                if (szControl == 2) pressureSize = true
                else if (szControl == 3) tiltSize = true

                sizeJitter = ((extractDouble(szVr["jitter"]) ?: 0.0) / 100.0).coerceIn(0.0, 1.0)
                val minD = extractDouble(items["minimumDiameter"]) ?: extractDouble(szVr["Mnm "])
                if (minD != null) {
                    minDiameterRatio = (minD / 100.0).coerceIn(0.0, 1.0)
                }

                val angVr = extractItems(items["angleDynamics"]).ifEmpty { extractItems(items["angVr"]) }
                val angControl = extractDouble(angVr["bVTy"])?.toInt() ?: 0
                if (angControl in 6..8) followDirection = true
                else if (angControl == 3) tiltAngle = true

                angleJitter = ((extractDouble(angVr["jitter"]) ?: 0.0) / 100.0).coerceIn(0.0, 1.0)

                val rndVr = extractItems(items["roundnessDynamics"]).ifEmpty { extractItems(items["rndVr"]) }
                roundnessJitter = ((extractDouble(rndVr["jitter"]) ?: 0.0) / 100.0).coerceIn(0.0, 1.0)
                val minR = extractDouble(rndVr["Mnm "])
                if (minR != null) {
                    minRoundness = (minR / 100.0).coerceIn(0.0, 1.0)
                }
            }

            // Scattering
            val useScatter = extractBool(items["useScatter"])
            var scatterVal = 0.0
            if (useScatter) {
                val scVr = extractItems(items["scatterDynamics"])
                val jf = extractDouble(scVr["jitter"]) ?: 0.0
                scatterVal = (jf / 100.0).coerceIn(0.0, 1.0)
            }

            // Transfer / Paint Dynamics
            val usePaintDynamics = extractBool(items["usePaintDynamics"]) ||
                extractBool(items["useTransfer"]) ||
                extractBool(items["useOtherDynamics"])
            var pressureOpacity = false
            var tiltOpacity = false
            var opacityJitter = 0.0
            var pressureFlow = false
            var tiltFlow = false
            var flowJitter = 0.0

            if (usePaintDynamics) {
                val opVr = extractItems(items["opVr"]).ifEmpty { extractItems(items["opacityDynamics"]) }
                val opControl = extractDouble(opVr["bVTy"])?.toInt() ?: 0
                if (opControl == 2) pressureOpacity = true
                else if (opControl == 3) tiltOpacity = true
                opacityJitter = ((extractDouble(opVr["jitter"]) ?: 0.0) / 100.0).coerceIn(0.0, 1.0)

                val prVr = extractItems(items["prVr"])
                    .ifEmpty { extractItems(items["flVr"]) }
                    .ifEmpty { extractItems(items["flowDynamics"]) }
                val flControl = extractDouble(prVr["bVTy"])?.toInt() ?: 0
                if (flControl == 2) pressureFlow = true
                else if (flControl == 3) tiltFlow = true
                flowJitter = ((extractDouble(prVr["jitter"]) ?: 0.0) / 100.0).coerceIn(0.0, 1.0)
            }

            // Dual Brush
            val useDualBrush = extractBool(items["useDualBrush"])
            val dualBrushInfo = if (useDualBrush) {
                val dualDesc = items["dualBrush"] as? ActionDescriptorValue.Descriptor
                if (dualDesc != null) {
                    val dualTipDesc = dualDesc.items["Brsh"] as? ActionDescriptorValue.Descriptor
                    val dTipItems = dualTipDesc?.items ?: emptyMap()
                    val dTipUuid = extractString(dTipItems["sampledData"])
                    val dDiam = extractDouble(dTipItems["Dmtr"]) ?: 30.0
                    val dSpcn = ((extractDouble(dTipItems["Spcn"]) ?: 25.0) / 100.0).coerceIn(0.01, 5.0)
                    val dScat = ((extractDouble(dualDesc.items["scatter"]) ?: 0.0) / 100.0).coerceIn(0.0, 1.0)
                    val dModeRaw = extractString(dualDesc.items["Md  "]) ?: "Mltp"
                    val dMode = when (dModeRaw) {
                        "Mltp" -> "multiply"
                        "Drkn" -> "darken"
                        "linearBurn", "colorBurn" -> "burn"
                        "linearDodge", "colorDodge" -> "dodge"
                        "Ovrl" -> "overlay"
                        else -> "multiply"
                    }
                    AbrDualBrushInfo(
                        tipUuid = dTipUuid,
                        diameter = dDiam,
                        spacing = dSpcn,
                        scatter = dScat,
                        compositeOp = dMode,
                        flipX = extractBool(dTipItems["flipX"]),
                        flipY = extractBool(dTipItems["flipY"]),
                    )
                } else null
            } else null

            // Texture
            val useTexture = extractBool(items["useTexture"])
            val textureInfo = if (useTexture) {
                val texDesc = items["texture"] as? ActionDescriptorValue.Descriptor
                if (texDesc != null) {
                    val patDesc = texDesc.items["Txtr"] as? ActionDescriptorValue.Descriptor
                    val patName = extractString(patDesc?.items?.get("Nm  ")) ?: ""
                    val patUuid = extractString(patDesc?.items?.get("Idnt")) ?: ""
                    val scale = ((extractDouble(texDesc.items["Scl "]) ?: 100.0) / 100.0).coerceIn(0.01, 10.0)
                    val depth = ((extractDouble(texDesc.items["textureDepth"]) ?: extractDouble(texDesc.items["Dpt "]) ?: 50.0) / 100.0).coerceIn(0.0, 1.0)
                    val modeRaw = extractString(texDesc.items["textureBlendMode"]) ?: extractString(texDesc.items["Md  "]) ?: "Mltp"
                    val mode = when (modeRaw) {
                        "Sbtr" -> "subtract"
                        "Drkn" -> "darken"
                        "Ovrl" -> "overlay"
                        "Ddg ", "colorDodge", "linearDodge" -> "dodge"
                        "Brn ", "colorBurn", "linearBurn" -> "burn"
                        "HrdL", "linearLight", "vividLight" -> "hard_light"
                        "SftL" -> "soft_light"
                        else -> "multiply"
                    }
                    val inv = extractBool(texDesc.items["InvT"])
                    AbrTextureInfo(
                        patternName = patName,
                        patternUuid = patUuid,
                        scale = scale,
                        depth = depth,
                        mode = mode,
                        invert = inv,
                    )
                } else null
            } else null

            // Color Dynamics
            val useColorDynamics = extractBool(items["useColorDynamics"])
            val colorDynamicsInfo = if (useColorDynamics) {
                val clDesc = (items["colorDynamics"] ?: items["clVr"]) as? ActionDescriptorValue.Descriptor
                if (clDesc != null) {
                    val hJ = ((extractDouble(clDesc.items["hJtr"]) ?: 0.0) / 100.0).coerceIn(0.0, 1.0)
                    val sJ = ((extractDouble(clDesc.items["sJtr"]) ?: 0.0) / 100.0).coerceIn(0.0, 1.0)
                    val bJ = ((extractDouble(clDesc.items["bJtr"]) ?: 0.0) / 100.0).coerceIn(0.0, 1.0)
                    val mix = ((extractDouble(clDesc.items["jitter"]) ?: 0.0) / 100.0).coerceIn(0.0, 1.0)
                    AbrColorDynamicsInfo(
                        hueJitter = hJ,
                        satJitter = sJ,
                        valJitter = bJ,
                        secondaryMix = mix,
                    )
                } else null
            } else null

            presets.add(
                AbrPresetInfo(
                    name = presetName,
                    tipUuid = tipUuid,
                    tipIndex = if (tipUuid != null && tipsByUuid.containsKey(tipUuid)) {
                        tipsByUuid[tipUuid]?.index ?: (idx + 1)
                    } else idx + 1,
                    diameter = diameter.coerceIn(1.0, 2000.0),
                    spacing = spacing,
                    angle = angleDeg,
                    roundness = roundness,
                    hardness = hardness,
                    scatter = scatterVal,
                    pressureSize = pressureSize,
                    pressureOpacity = pressureOpacity,
                    pressureFlow = pressureFlow,
                    tiltSize = tiltSize,
                    tiltOpacity = tiltOpacity,
                    tiltFlow = tiltFlow,
                    tiltAngle = tiltAngle,
                    sizeJitter = sizeJitter,
                    angleJitter = angleJitter,
                    roundnessJitter = roundnessJitter,
                    opacityJitter = opacityJitter,
                    flowJitter = flowJitter,
                    minDiameterRatio = minDiameterRatio,
                    minRoundness = minRoundness,
                    followDirection = followDirection,
                    flipX = flipX,
                    flipY = flipY,
                    isComputed = !isSampled,
                    dualBrush = dualBrushInfo,
                    texture = textureInfo,
                    colorDynamics = colorDynamicsInfo,
                    airbrush = airbrush,
                    compositeOp = compOp,
                )
            )
        }

        return presets
    }

    // ---------------------------------------------------------------------------------------------
    // Pure Kotlin PNG Encoder (Self-contained, JVM & Android compatible)
    // ---------------------------------------------------------------------------------------------

    /**
     * Encodes an 8-bit RGBA PNG for the brush tip where RGB = 0, 0, 0 and Alpha = density.
     */
    fun encodeTipPng(
        tip: AbrDecodedTip,
        roundness: Double = 1.0,
        flipX: Boolean = false,
        flipY: Boolean = false,
    ): ByteArray {
        val srcW = tip.width
        val srcH = tip.height
        val srcData = tip.data

        val squash = roundness.coerceIn(0.01, 1.0)
        val dstH = if (squash < 0.99) maxOf(1, (srcH * squash).toInt()) else srcH
        val dstW = srcW

        val rgbaBytes = ByteArray(dstW * dstH * 4)

        for (y in 0 until dstH) {
            val srcY = if (squash < 0.99) {
                ((y.toDouble() / dstH) * srcH).toInt().coerceIn(0, srcH - 1)
            } else y
            val effSrcY = if (flipY) (srcH - 1 - srcY) else srcY

            for (x in 0 until dstW) {
                val effSrcX = if (flipX) (srcW - 1 - x) else x
                val srcIdx = effSrcY * srcW + effSrcX
                val alpha = if (srcIdx in srcData.indices) srcData[srcIdx] else 0

                val dstIdx = (y * dstW + x) * 4
                rgbaBytes[dstIdx] = 0 // R
                rgbaBytes[dstIdx + 1] = 0 // G
                rgbaBytes[dstIdx + 2] = 0 // B
                rgbaBytes[dstIdx + 3] = alpha // A
            }
        }

        return encodeRgbaPng(dstW, dstH, rgbaBytes)
    }

    /**
     * Encodes a 200x200 RGBA PNG preview tile for the .kpp thumbnail.
     * Renders a soft light-gray card background with dark brush dab.
     */
    fun encodePreviewPng(
        tip: AbrDecodedTip?,
        diameter: Double = 30.0,
        roundness: Double = 1.0,
        width: Int = 200,
        height: Int = 200,
    ): ByteArray {
        val rgbaBytes = ByteArray(width * height * 4)

        // Background color: #D8D7D4 (RGB 216, 215, 212)
        val bgR = 216.toByte()
        val bgG = 215.toByte()
        val bgB = 212.toByte()
        val bgA = 255.toByte()

        for (i in 0 until width * height) {
            val idx = i * 4
            rgbaBytes[idx] = bgR
            rgbaBytes[idx + 1] = bgG
            rgbaBytes[idx + 2] = bgB
            rgbaBytes[idx + 3] = bgA
        }

        if (tip != null && tip.width > 0 && tip.height > 0) {
            val hasThumb = tip.thumbnail != null && tip.thumbnail.isNotEmpty() && tip.thumbWidth > 0 && tip.thumbHeight > 0
            val tipW = if (hasThumb) tip.thumbWidth else tip.width
            val tipH = if (hasThumb) tip.thumbHeight else tip.height
            val tipData = if (hasThumb) tip.thumbnail!! else tip.data

            val maxD = maxOf(tipW, tipH)
            val fitSize = 150.0 // target fit in 200x200 canvas
            val scale = minOf(1.0, fitSize / maxD)
            val renderW = maxOf(1, (tipW * scale).toInt())
            val renderH = maxOf(1, (tipH * scale * roundness.coerceIn(0.01, 1.0)).toInt())

            val startX = (width - renderW) / 2
            val startY = (height - renderH) / 2

            // Foreground stroke ink: #242220 (dark graphite)
            val inkR = 0x24
            val inkG = 0x22
            val inkB = 0x20

            for (ry in 0 until renderH) {
                val cy = startY + ry
                if (cy !in 0 until height) continue

                val sy = ((ry.toDouble() / renderH) * tipH).toInt().coerceIn(0, tipH - 1)

                for (rx in 0 until renderW) {
                    val cx = startX + rx
                    if (cx !in 0 until width) continue

                    val sx = ((rx.toDouble() / renderW) * tipW).toInt().coerceIn(0, tipW - 1)
                    val sIdx = sy * tipW + sx
                    val density = if (sIdx in tipData.indices) tipData[sIdx].toInt() and 0xFF else 0
                    if (density == 0) continue

                    val aF = density / 255.0
                    val dstIdx = (cy * width + cx) * 4

                    val curR = rgbaBytes[dstIdx].toInt() and 0xFF
                    val curG = rgbaBytes[dstIdx + 1].toInt() and 0xFF
                    val curB = rgbaBytes[dstIdx + 2].toInt() and 0xFF

                    rgbaBytes[dstIdx] = (curR * (1.0 - aF) + inkR * aF).toInt().toByte()
                    rgbaBytes[dstIdx + 1] = (curG * (1.0 - aF) + inkG * aF).toInt().toByte()
                    rgbaBytes[dstIdx + 2] = (curB * (1.0 - aF) + inkB * aF).toInt().toByte()
                }
            }
        } else {
            // Computed circular dab
            val cx = width / 2.0
            val cy = height / 2.0
            val radiusX = minOf(70.0, diameter.coerceAtLeast(8.0))
            val radiusY = radiusX * roundness.coerceIn(0.01, 1.0)

            val inkR = 0x24
            val inkG = 0x22
            val inkB = 0x20

            for (y in 0 until height) {
                for (x in 0 until width) {
                    val dx = (x - cx) / radiusX
                    val dy = (y - cy) / radiusY
                    val distSq = dx * dx + dy * dy
                    if (distSq <= 1.0) {
                        val edge = (1.0 - distSq) * 3.0
                        val aF = edge.coerceIn(0.0, 1.0)
                        val dstIdx = (y * width + x) * 4
                        val curR = rgbaBytes[dstIdx].toInt() and 0xFF
                        val curG = rgbaBytes[dstIdx + 1].toInt() and 0xFF
                        val curB = rgbaBytes[dstIdx + 2].toInt() and 0xFF
                        rgbaBytes[dstIdx] = (curR * (1.0 - aF) + inkR * aF).toInt().toByte()
                        rgbaBytes[dstIdx + 1] = (curG * (1.0 - aF) + inkG * aF).toInt().toByte()
                        rgbaBytes[dstIdx + 2] = (curB * (1.0 - aF) + inkB * aF).toInt().toByte()
                    }
                }
            }
        }

        return encodeRgbaPng(width, height, rgbaBytes)
    }

    private fun encodeRgbaPng(width: Int, height: Int, rgbaBytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()

        // 1. Signature
        out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))

        // 2. IHDR Chunk (13 bytes)
        val ihdrData = ByteArray(13)
        val wBuf = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(width).array()
        val hBuf = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(height).array()
        System.arraycopy(wBuf, 0, ihdrData, 0, 4)
        System.arraycopy(hBuf, 0, ihdrData, 4, 4)
        ihdrData[8] = 8 // bit depth
        ihdrData[9] = 6 // color type 6 (RGBA)
        ihdrData[10] = 0 // deflate
        ihdrData[11] = 0 // filter method 0
        ihdrData[12] = 0 // no interlace

        writeChunk(out, "IHDR", ihdrData)

        // 3. IDAT Chunk: raw scanlines with 0-filter prefix per line
        val rowSize = width * 4
        val rawScanlines = ByteArray(height * (rowSize + 1))
        for (y in 0 until height) {
            val destOffset = y * (rowSize + 1)
            rawScanlines[destOffset] = 0 // Filter None
            System.arraycopy(rgbaBytes, y * rowSize, rawScanlines, destOffset + 1, rowSize)
        }

        val deflater = Deflater(Deflater.BEST_SPEED)
        deflater.setInput(rawScanlines)
        deflater.finish()
        val deflatedBos = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (!deflater.finished()) {
            val count = deflater.deflate(buf)
            if (count > 0) deflatedBos.write(buf, 0, count)
        }
        deflater.end()

        writeChunk(out, "IDAT", deflatedBos.toByteArray())

        // 4. IEND Chunk
        writeChunk(out, "IEND", ByteArray(0))

        return out.toByteArray()
    }

    private fun writeChunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
        val lenBuf = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(data.size).array()
        out.write(lenBuf)
        val typeBytes = type.toByteArray(Charsets.ISO_8859_1)
        out.write(typeBytes)
        if (data.isNotEmpty()) {
            out.write(data)
        }

        val crc = CRC32()
        crc.update(typeBytes)
        if (data.isNotEmpty()) {
            crc.update(data)
        }
        val crcBuf = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(crc.value.toInt()).array()
        out.write(crcBuf)
    }
}
