/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import java.io.EOFException
import java.io.File
import java.io.InputStream
import java.nio.channels.FileChannel

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

    /** Krita Scattering/Amount 上限（KisScatterOptionData.cpp: std::make_pair(0.0, 5.0)） */
    internal const val SCATTER_MAX = 5.0
    internal const val MAX_SCATTER_COUNT = 16

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
        val rawMode: String = "",
        val flipX: Boolean = false,
        val flipY: Boolean = false,
        /** 双重画笔 `bothAxes`：false 只沿笔画法线散布（KisScatterOption AxisY）；缺省沿用引擎默认双轴 */
        val scatterBothAxes: Boolean = true,
        /** 双重画笔 `Cnt `（每步印章数）；Krita masking brush 每个主 dab 只盖一个遮罩，只记录不应用 */
        val count: Int = 1,
        /** 双重画笔笔尖角度（度，`dualBrush.Brsh.Angl`）；写进 masking brush_definition 的 angle（KisBrush d->angle，叠加到 dab 旋转上） */
        val angle: Double = 0.0,
        /** 双重画笔笔尖圆度（0..1，`Rndn`）；masking brush 无法表达（只记录并写日志） */
        val roundness: Double = 1.0,
    )

    data class AbrTextureInfo(
        val patternName: String = "",
        val patternUuid: String = "",
        val scale: Double = 1.0,
        val depth: Double = 0.5,
        val mode: String = "multiply",
        val rawMode: String = "",
        val invert: Boolean = false,
        /** PS 纹理亮度 -150..150（textureBrightness / Brgh） */
        val brightness: Int = 0,
        /** PS 纹理对比度 -50..100（textureContrast / Cntr） */
        val contrast: Int = 0,
        /** 纹理「深度抖动 → 控制」= 钢笔压力（textureDepthDynamics.bVTy==2）：深度随压力在 minimumDepth..depth 间变化 */
        val depthPressure: Boolean = false,
        /** PS 纹理「最小深度」minimumDepth（0..1，压感 0 时的深度占比） */
        val depthMin: Double = 0.0,
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
        /** useScatter 时取 `bothAxes`；没开散布保持引擎默认（双轴） */
        val scatterBothAxes: Boolean = true,
        /** `toolOptions{brushPreset=true}` 里的 Opct / flow（0..1）；缺省 1.0 */
        val toolOpacity: Double = 1.0,
        val toolFlow: Double = 1.0,
        val pressureRoundness: Boolean = false,
        val tiltRoundness: Boolean = false,
        val pressureSize: Boolean = false,
        val pressureOpacity: Boolean = false,
        val pressureFlow: Boolean = false,
        val tiltSize: Boolean = false,
        val tiltOpacity: Boolean = false,
        val tiltFlow: Boolean = false,
        val tiltAngle: Boolean = false,
        /** PS 角度控制原始值（1=渐隐 4=笔轮 5=笔身旋转 在 Krita/本应用无对应传感器，仅记录） */
        val angleControl: Int = 0,
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
        /** 形状动态顶层 flipX/flipY（Flip X/Y Jitter，每个印章随机翻转），不是笔尖静态翻转（那是 Brsh.flipX/flipY） */
        val flipXJitter: Boolean = false,
        val flipYJitter: Boolean = false,
        /** scatterDynamics.bVTy==2：散布量受钢笔压力控制（压力小散得少） */
        val scatterPressure: Boolean = false,
        /** 散布面板 `Cnt `（每步印章数，useScatter 时有效），1..16 */
        val scatterCount: Int = 1,
        /** 传递面板不透明度/流量压感 `Mnm `（压感 0 时的最小值，0..1） */
        val opacityMin: Double = 0.0,
        val flowMin: Double = 0.0,
        val isComputed: Boolean = false,
        val dualBrush: AbrDualBrushInfo? = null,
        val texture: AbrTextureInfo? = null,
        val colorDynamics: AbrColorDynamicsInfo? = null,
        val airbrush: Boolean = false,
        val compositeOp: String = "normal",
        /** PS 原生工具类（toolOptions.classId）："SmTl"（涂抹）、"MixB"（混合器）、"PbTl"（画笔）；缺省空字符串 */
        val toolClass: String = "",
        /** PS 涂抹强度（toolOptions.Prs，0..100 折算为 0.0..1.0）；缺省 1.0 */
        val smudgeStrength: Double = 1.0,
        /** PS 混合器画笔参数（0.0..1.0） */
        val wetness: Double = 0.0,
        val mix: Double = 0.0,
        val dryness: Double = 0.0,
    )

    data class AbrPatternResource(
        val uuid: String,
        val name: String,
        val pngBytes: ByteArray,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is AbrPatternResource) return false
            return uuid == other.uuid && name == other.name && pngBytes.contentEquals(other.pngBytes)
        }

        override fun hashCode(): Int {
            var result = uuid.hashCode()
            result = 31 * result + name.hashCode()
            result = 31 * result + pngBytes.contentHashCode()
            return result
        }
    }

    data class AbrParseResult(
        val version: Int,
        val subversion: Int,
        val tips: List<AbrDecodedTip>,
        val presets: List<AbrPresetInfo>,
        val patterns: List<AbrPatternResource> = emptyList(),
        /** 输入超过 [MAX_IMPORT_BYTES], 没有解析 (调用方应给用户明确提示) */
        val tooLarge: Boolean = false,
    )

    /**
     * 单个画笔包允许导入的最大字节数。
     *
     * ABR 会被整体读进内存解析, 而 Android 的 Java 堆上限通常只有 512MB; 线上有 130MB /
     * 343MB 的单次分配直接把进程打崩 (b90269b9e4 / 1692c1904d)。64MB 足够覆盖绝大多数
     * 商店画笔包, 再大的包请用户先在桌面端拆分。
     */
    @Deprecated("Use AbrSource or File/FileChannel overloads without memory limit")
    const val MAX_IMPORT_BYTES = 64L * 1024 * 1024

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

    internal const val MAX_RESYNC_SCAN_BYTES = 64L * 1024L
    internal const val MAX_DESCRIPTOR_STRING_CHARS = 65536
    internal const val MAX_DESCRIPTOR_RAW_BYTES = 16 * 1024 * 1024

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

    fun parse(
        file: File,
        basePackName: String = "ABR",
        onPatternDecoded: ((AbrPatternResource) -> Unit)? = null,
        onTipDecoded: ((AbrDecodedTip) -> Unit)? = null,
    ): AbrParseResult {
        if (!file.isFile || file.length() < 4L) {
            return AbrParseResult(0, 0, emptyList(), emptyList())
        }
        return AbrSource.fromFile(file).use { source ->
            parseSource(source, basePackName, onPatternDecoded, onTipDecoded)
        }
    }

    fun parse(
        channel: FileChannel,
        basePackName: String = "ABR",
        onPatternDecoded: ((AbrPatternResource) -> Unit)? = null,
        onTipDecoded: ((AbrDecodedTip) -> Unit)? = null,
    ): AbrParseResult {
        if (channel.size() < 4L) {
            return AbrParseResult(0, 0, emptyList(), emptyList())
        }
        return AbrSource.fromChannel(channel).use { source ->
            parseSource(source, basePackName, onPatternDecoded, onTipDecoded)
        }
    }

    internal fun parse(
        source: AbrSource,
        basePackName: String = "ABR",
        onPatternDecoded: ((AbrPatternResource) -> Unit)? = null,
        onTipDecoded: ((AbrDecodedTip) -> Unit)? = null,
    ): AbrParseResult = parseSource(source, basePackName, onPatternDecoded, onTipDecoded)

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
    ): AbrParseResult = parse(input, -1L, basePackName, onTipDecoded)

    /**
     * 带长度提示的解析入口: 已知长度时一次分配到位, 避免 `readBytes()` 的双份峰值。
     * [sizeHint] <= 0 表示未知。
     */
    fun parse(
        input: InputStream,
        sizeHint: Long,
        basePackName: String = "ABR",
        onTipDecoded: ((AbrDecodedTip) -> Unit)? = null,
    ): AbrParseResult {
        if (input is java.io.FileInputStream) {
            return parse(input.channel, basePackName, onPatternDecoded = null, onTipDecoded = onTipDecoded)
        }
        val bytes = input.readBytes()
        if (bytes.size < 4) {
            return AbrParseResult(0, 0, emptyList(), emptyList())
        }
        return AbrSource.fromBytes(bytes).use { source ->
            parseSource(source, basePackName, onPatternDecoded = null, onTipDecoded = onTipDecoded)
        }
    }

    private fun parseSource(
        source: AbrSource,
        basePackName: String,
        onPatternDecoded: ((AbrPatternResource) -> Unit)?,
        onTipDecoded: ((AbrDecodedTip) -> Unit)?,
    ): AbrParseResult {
        if (source.size < 4L) {
            return AbrParseResult(0, 0, emptyList(), emptyList())
        }
        val version = source.u16(0L)
        return when (version) {
            1, 2 -> parseVersion12(source, version, basePackName, onTipDecoded)
            in 6..MAX_SUPPORTED_VERSION -> parseVersion6(source, basePackName, onPatternDecoded, onTipDecoded)
            else -> AbrParseResult(version, 0, emptyList(), emptyList())
        }
    }

    /**
     * 只读出 v6+ 文件的 `desc` 根描述符（不解 samp 位图），供键覆盖审计工具使用。
     * 非 v6+、无 desc 或解析失败返回 null；不影响导入链路。
     */
    internal fun readDescriptorRoot(bytes: ByteArray): ActionDescriptorNode? =
        AbrSource.fromBytes(bytes).use { readDescriptorRoot(it) }

    internal fun readDescriptorRoot(source: AbrSource): ActionDescriptorNode? {
        if (source.size < 12L) return null
        val version = source.u16(0L)
        if (version !in 6..MAX_SUPPORTED_VERSION) return null
        var cursor = 4L
        while (cursor + 12L <= source.size) {
            if (source.readString(cursor, 4, Charsets.ISO_8859_1) != "8BIM") return null
            val key = source.readString(cursor + 4L, 4, Charsets.ISO_8859_1)
            val len = source.u32(cursor + 8L)
            val start = cursor + 12L
            val end = (start + len).coerceAtMost(source.size)
            if (key == "desc") {
                return try {
                    val c = SourceCursor(source, start, end)
                    c.readInt32()
                    readActionDescriptor(c)
                } catch (e: Exception) {
                    null
                }
            }
            cursor = if ((len and 1L) == 1L && end < source.size) end + 1L else end
        }
        return null
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
        source: AbrSource,
        version: Int,
        basePackName: String,
        onTipDecoded: ((AbrDecodedTip) -> Unit)? = null,
    ): AbrParseResult {
        val count = source.u16(2L)

        val tips = mutableListOf<AbrDecodedTip>()
        val presets = mutableListOf<AbrPresetInfo>()

        var cursor = 4L
        for (i in 0 until count) {
            if (cursor + 6L > source.size) break
            val brushType = source.i16(cursor).toInt()
            val brushSize = source.i32(cursor + 2L)
            val nextBrush = cursor + 6L + brushSize.toLong()
            if (nextBrush <= cursor) break

            cursor += 6L
            if (brushType == 2) { // sampled brush
                if (cursor + 6L > source.size) {
                    cursor = nextBrush
                    continue
                }
                // Discard 4 misc bytes, read 2 spacing bytes
                val spacingVal = source.i16(cursor + 4L).toInt()
                val spacing = if (spacingVal > 0) (spacingVal / 100.0).coerceIn(0.01, 5.0) else 0.25
                cursor += 6L

                var brushName = "$basePackName ${i + 1}"
                if (version == 2) {
                    if (cursor + 4L <= nextBrush && cursor + 4L <= source.size) {
                        val nameLen = source.i32(cursor)
                        cursor += 4L
                        if (nameLen in 1..4096 && cursor + nameLen * 2L <= nextBrush && cursor + nameLen * 2L <= source.size) {
                            brushName = source.readString(cursor, nameLen * 2, Charsets.UTF_16BE).trimEnd('\u0000')
                            cursor += nameLen * 2L
                        }
                    }
                }

                // Discard 1 byte antialias + 8 bytes short bounds (9 bytes total)
                cursor += 9L
                if (cursor + 19L <= source.size) {
                    val top = source.i32(cursor)
                    val left = source.i32(cursor + 4L)
                    val bottom = source.i32(cursor + 8L)
                    val right = source.i32(cursor + 12L)
                    val depth = source.i16(cursor + 16L).toInt()
                    val compression = source.get(cursor + 18L).toInt()
                    cursor += 19L

                    val width = right - left
                    val height = bottom - top

                    if (plausibleTipDimensions(width, height)) {
                        val tipData = if (compression == 1) {
                            decodePackBitsScanlines(source, cursor, nextBrush.coerceAtMost(source.size), height, width)
                        } else {
                            val dataLen = width * height * (depth / 8).coerceAtLeast(1)
                            if (cursor + dataLen <= source.size) {
                                val raw = source.sliceBytes(cursor, dataLen)
                                if (depth == 16) tipDataTo8Bit(raw, width, height, depth) else raw
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
        source: AbrSource,
        basePackName: String,
        onPatternDecoded: ((AbrPatternResource) -> Unit)? = null,
        onTipDecoded: ((AbrDecodedTip) -> Unit)? = null,
    ): AbrParseResult {
        var cursor = 0L
        val version = source.u16(cursor)
        cursor += 2L
        val subversion = source.u16(cursor)
        cursor += 2L

        val tips = mutableListOf<AbrDecodedTip>()
        val patterns = mutableListOf<AbrPatternResource>()
        var descriptorRoot: ActionDescriptorNode? = null

        val fileSize = source.size
        while (cursor + 12L <= fileSize) {
            val tag = source.readString(cursor, 4, Charsets.ISO_8859_1)
            if (tag != "8BIM") {
                if (cursor == 4L) {
                    // 非 ABR 文件在头部魔数不匹配时快速早退
                    break
                }
                // Seek to next 8BIM if possible, 有界扫描避免大文件假死
                var found = false
                val scanLimit = minOf(fileSize - 3L, cursor + MAX_RESYNC_SCAN_BYTES)
                for (p in cursor until scanLimit) {
                    if (source.get(p) == '8'.code.toByte() &&
                        source.get(p + 1L) == 'B'.code.toByte() &&
                        source.get(p + 2L) == 'I'.code.toByte() &&
                        source.get(p + 3L) == 'M'.code.toByte()
                    ) {
                        cursor = p
                        found = true
                        break
                    }
                }
                if (!found) break
                continue
            }

            val key = source.readString(cursor + 4L, 4, Charsets.ISO_8859_1)
            val sectionLen = source.u32(cursor + 8L)
            val sectionStart = cursor + 12L
            val sectionEnd = (sectionStart + sectionLen).coerceAtMost(fileSize)

            if (key == "samp") {
                tips.addAll(parseSampSection(source, sectionStart, sectionEnd, subversion, onTipDecoded))
            } else if (key == "desc") {
                try {
                    val descDis = SourceCursor(source, sectionStart, sectionEnd)
                    descDis.readInt32() // descriptor version, usually 16
                    descriptorRoot = readActionDescriptor(descDis)
                } catch (e: Exception) {
                    runCatching { android.util.Log.w("AbrParser", "Failed to parse desc ActionDescriptor", e) }
                }
            } else if (isPattSectionKey(key)) {
                val decoded = parsePattSection(source, sectionStart, sectionEnd, onPatternDecoded)
                if (onPatternDecoded == null) {
                    patterns.addAll(decoded)
                }
            }

            cursor = if ((sectionLen and 1L) == 1L && sectionEnd < fileSize) sectionEnd + 1L else sectionEnd
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

        return AbrParseResult(version, subversion, tips, presets, patterns)
    }

    private fun parseSampSection(
        source: AbrSource,
        sectionStart: Long,
        sectionEnd: Long,
        subversion: Int,
        onTipDecoded: ((AbrDecodedTip) -> Unit)? = null,
    ): List<AbrDecodedTip> {
        val tips = mutableListOf<AbrDecodedTip>()
        var cursor = sectionStart
        var index = 1

        val skipOffset = SUBVERSION_HEADER_SKIP[subversion] ?: 301

        while (cursor + 4L < sectionEnd) {
            val entryLen = source.u32(cursor)
            val entryDataStart = cursor + 4L
            var paddedLen = entryLen
            if (paddedLen % 4L != 0L) {
                paddedLen += (4L - (paddedLen % 4L))
            }
            val nextEntryPos = entryDataStart + paddedLen

            if (entryDataStart >= sectionEnd) break

            val uuidLen = source.u8(entryDataStart)
            val uuid = if (uuidLen > 0 && entryDataStart + 1L + uuidLen <= sectionEnd) {
                source.readString(entryDataStart + 1L, uuidLen, Charsets.US_ASCII)
            } else "tip_$index"

            val boundsStart = entryDataStart + skipOffset
            if (boundsStart + 19L <= sectionEnd && boundsStart + 19L <= nextEntryPos) {
                val top = source.i32(boundsStart)
                val left = source.i32(boundsStart + 4L)
                val bottom = source.i32(boundsStart + 8L)
                val right = source.i32(boundsStart + 12L)
                val depth = source.i16(boundsStart + 16L).toInt()
                val compress = source.get(boundsStart + 18L).toInt()

                val width = right - left
                val height = bottom - top

                if (plausibleTipDimensions(width, height)) {
                    val pixelDataStart = boundsStart + 19L
                    val pixelData = if (compress == 1) {
                        decodePackBitsScanlines(source, pixelDataStart, nextEntryPos.coerceAtMost(sectionEnd), height, width)
                    } else {
                        val needed = width * height * (depth / 8).coerceAtLeast(1)
                        if (pixelDataStart + needed <= sectionEnd) {
                            val raw = source.sliceBytes(pixelDataStart, needed)
                            if (depth == 16) tipDataTo8Bit(raw, width, height, depth) else raw
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

            cursor = if (nextEntryPos > cursor) nextEntryPos else (cursor + 4L)
        }
        return tips
    }

    internal fun tipDataTo8Bit(raw: ByteArray, width: Int, height: Int, depth: Int): ByteArray {
        if (depth <= 8 || raw.isEmpty()) return raw
        val totalPixels = width * height
        val out = ByteArray(totalPixels)
        val bytesPerPixel = (depth / 8).coerceAtLeast(1)
        val limit = minOf(totalPixels, raw.size / bytesPerPixel)
        for (i in 0 until limit) {
            out[i] = raw[i * bytesPerPixel]
        }
        return out
    }

    private fun isPattSectionKey(key: String): Boolean =
        key.equals("patt", ignoreCase = true) || key == "Pat " || key == "Pat2" || key == "Pat3"

    /**
     * Photoshop patt: each pattern starts with an Int32 length. imageMode at p+8 is Int32,
     * not a short — a short read shifts every later field by 2 bytes.
     * One bad pattern is skipped; the next starts at p+4+patternLength, 4-byte aligned.
     */
    private fun parsePattSection(
        source: AbrSource,
        sectionStart: Long,
        sectionEnd: Long,
        onPatternDecoded: ((AbrPatternResource) -> Unit)? = null,
    ): List<AbrPatternResource> {
        val out = if (onPatternDecoded == null) mutableListOf<AbrPatternResource>() else null
        var p = sectionStart
        while (p + 4L <= sectionEnd) {
            val patternLength = try {
                source.i32(p)
            } catch (_: Exception) {
                break
            }
            val endLong = p + 4L + patternLength.toLong()
            if (patternLength <= 0 || endLong > sectionEnd) break
            val patternEnd = endLong
            try {
                parseOnePattern(source, p, patternEnd)?.let { pat ->
                    if (onPatternDecoded != null) {
                        onPatternDecoded.invoke(pat)
                    } else {
                        out?.add(pat)
                    }
                }
            } catch (_: Exception) {
                // 单个图案抛错只跳过该图案
            }
            var next = patternEnd
            val mis = (next and 3L).toInt()
            if (mis != 0) next += (4 - mis).toLong()
            if (next <= p) break
            p = next
        }
        return out ?: emptyList()
    }

    private fun parseOnePattern(source: AbrSource, p: Long, patternEnd: Long): AbrPatternResource? {
        if (p + 16L > patternEnd) return null
        if (source.i32(p + 4L) != 1) return null
        val imageMode = source.i32(p + 8L)
        if (imageMode == 2) return null
        val height = source.u16(p + 12L)
        val width = source.u16(p + 14L)
        if (!plausibleTipDimensions(width, height)) return null

        var cursor = p + 16L
        val nameChars = source.i32(cursor)
        cursor += 4L
        if (nameChars < 0 || nameChars > 4096) return null
        val nameBytes = nameChars * 2
        if (cursor + nameBytes.toLong() > patternEnd) return null
        val name = if (nameChars == 0) {
            ""
        } else {
            source.readString(cursor, nameBytes, Charsets.UTF_16BE).trimEnd('\u0000')
        }
        cursor += nameBytes.toLong()
        if (cursor >= patternEnd) return null
        val uuidLen = source.u8(cursor)
        cursor += 1L
        if (cursor + uuidLen > patternEnd) return null
        val uuid = if (uuidLen == 0) "" else source.readString(cursor, uuidLen, Charsets.US_ASCII)
        cursor += uuidLen.toLong()

        if (cursor + 28L > patternEnd) return null
        val vmalVersion = source.i32(cursor)
        cursor += 4L
        source.i32(cursor) // vmalLength，必须读掉；通道头是定长的，不用它跳转
        cursor += 4L
        if (vmalVersion != 3) return null
        cursor += 16L // top, left, bottom, right
        val maxChannels = source.i32(cursor)
        cursor += 4L
        if (maxChannels <= 0 || maxChannels > 24) return null

        for (channel in 0 until maxChannels) {
            if (cursor + 4L > patternEnd) return null
            val isWritten = source.i32(cursor)
            cursor += 4L
            if (isWritten == 0) continue
            if (cursor + 4L > patternEnd) return null
            val channelLength = source.i32(cursor)
            cursor += 4L
            if (channelLength == 0) continue
            val channelEndLong = cursor + channelLength.toLong()
            if (channelLength < 0 || channelEndLong > patternEnd || channelEndLong > source.size) {
                return null
            }
            val channelEnd = channelEndLong
            val headerBytes = 4 + 16 + 2 + 1
            if (cursor + headerBytes > channelEnd) {
                cursor = channelEnd
                continue
            }
            val pixelDepth = source.i32(cursor)
            cursor += 4L
            val cTop = source.i32(cursor)
            cursor += 4L
            val cLeft = source.i32(cursor)
            cursor += 4L
            val cBottom = source.i32(cursor)
            cursor += 4L
            val cRight = source.i32(cursor)
            cursor += 4L
            val channelDepth = source.u16(cursor)
            cursor += 2L
            val compression = source.u8(cursor)
            cursor += 1L
            if (pixelDepth < 0 || channelDepth < 0) {
                cursor = channelEnd
                continue
            }
            val dataStart = cursor

            var cw = cRight - cLeft
            var chh = cBottom - cTop
            if (!plausibleTipDimensions(cw, chh)) {
                cw = width
                chh = height
            }
            if (!plausibleTipDimensions(cw, chh)) {
                cursor = channelEnd
                continue
            }
            val pixels = when (compression) {
                0 -> {
                    val need = cw.toLong() * chh.toLong()
                    if (dataStart + need > channelEnd) null
                    else source.sliceBytes(dataStart, need.toInt())
                }
                1 -> decodePackBitsScanlines(source, dataStart, channelEnd, chh, cw)?.takeIf { it.size >= cw * chh }
                else -> null
            }
            if (pixels != null) {
                return AbrPatternResource(uuid, name, encodeGrayPng(cw, chh, pixels))
            }
            cursor = channelEnd
        }
        return null
    }

    // ---------------------------------------------------------------------------------------------
    // PackBits RLE Scanline Decoder
    // ---------------------------------------------------------------------------------------------

    fun decodePackBitsScanlines(bytes: ByteArray, offset: Int, height: Int, width: Int): ByteArray? =
        AbrSource.fromBytes(bytes).use {
            decodePackBitsScanlines(it, offset.toLong(), bytes.size.toLong(), height, width)
        }

    internal fun decodePackBitsScanlines(
        source: AbrSource,
        offset: Long,
        limit: Long,
        height: Int,
        width: Int,
    ): ByteArray? {
        if (height <= 0 || width <= 0 || !plausibleTipDimensions(width, height)) return null
        val totalPixels = height * width
        val output = ByteArray(totalPixels)
        val safeLimit = minOf(limit, source.size)

        var cursor = offset
        if (cursor < 0L || cursor + height * 2L > safeLimit) return null

        val scanlineLengths = IntArray(height)
        for (i in 0 until height) {
            scanlineLengths[i] = source.u16(cursor)
            cursor += 2L
        }

        for (h in 0 until height) {
            val lineLen = scanlineLengths[h].toLong()
            val lineEnd = cursor + lineLen
            if (lineEnd > safeLimit) break

            val lineStartOut = h * width
            val lineEndOut = lineStartOut + width
            var lineOutPos = lineStartOut

            while (cursor < lineEnd && lineOutPos < lineEndOut) {
                val n = source.get(cursor++).toInt()
                if (n in 0..127) {
                    val count = n + 1
                    val take = minOf(count.toLong(), lineEnd - cursor, (lineEndOut - lineOutPos).toLong()).toInt()
                    if (take > 0) {
                        source.copyTo(cursor, output, lineOutPos, take)
                        cursor += take.toLong()
                        lineOutPos += take
                    }
                } else if (n in -127..-1) {
                    val count = -n + 1
                    if (cursor < lineEnd) {
                        val byteVal = source.get(cursor++)
                        val take = minOf(count, lineEndOut - lineOutPos)
                        if (take > 0) {
                            java.util.Arrays.fill(output, lineOutPos, lineOutPos + take, byteVal)
                            lineOutPos += take
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

    internal class SourceCursor(
        val source: AbrSource,
        var pos: Long,
        val limit: Long = source.size,
    ) {
        fun readInt32(): Int {
            if (pos + 4L > limit) throw EOFException("Int32 past limit: pos=$pos limit=$limit")
            val v = source.i32(pos)
            pos += 4L
            return v
        }

        fun readUInt32(): Long {
            return readInt32().toLong() and 0xFFFFFFFFL
        }

        fun readInt64(): Long {
            if (pos + 8L > limit) throw EOFException("Int64 past limit: pos=$pos limit=$limit")
            val v = source.i64(pos)
            pos += 8L
            return v
        }

        fun readDouble(): Double {
            if (pos + 8L > limit) throw EOFException("Double past limit: pos=$pos limit=$limit")
            val v = source.f64(pos)
            pos += 8L
            return v
        }

        fun readByte(): Byte {
            if (pos >= limit) throw EOFException("Byte past limit: pos=$pos limit=$limit")
            return source.get(pos++)
        }

        fun readBytes(len: Int): ByteArray {
            if (len < 0 || len > MAX_DESCRIPTOR_RAW_BYTES || pos + len.toLong() > limit) {
                throw EOFException("readBytes out of bounds or too large: len=$len pos=$pos limit=$limit")
            }
            val arr = source.sliceBytes(pos, len)
            pos += len.toLong()
            return arr
        }

        fun readUnicodeString(): String {
            val charCount = readUInt32()
            if (charCount == 0L) return ""
            if (charCount > MAX_DESCRIPTOR_STRING_CHARS) {
                throw EOFException("UnicodeString charCount exceeds limit: $charCount > $MAX_DESCRIPTOR_STRING_CHARS")
            }
            val byteCount = charCount * 2L
            if (pos + byteCount > limit) {
                throw EOFException("UnicodeString past limit: pos=$pos, byteCount=$byteCount, limit=$limit")
            }
            val s = source.readString(pos, byteCount.toInt(), Charsets.UTF_16BE).trimEnd('\u0000')
            pos += byteCount
            return s
        }

        fun readIdString(): String {
            val len = readUInt32()
            return if (len == 0L) {
                readTypeTag()
            } else {
                if (len !in 1L..4096L) {
                    throw EOFException("IdString len out of bounds (1..4096): $len")
                }
                if (pos + len > limit) {
                    throw EOFException("IdString past limit: pos=$pos, len=$len, limit=$limit")
                }
                val s = source.readString(pos, len.toInt(), Charsets.US_ASCII)
                pos += len
                s
            }
        }

        fun readTypeTag(): String {
            if (pos + 4L > limit) throw EOFException("TypeTag past limit: pos=$pos limit=$limit")
            val s = source.readString(pos, 4, Charsets.ISO_8859_1)
            pos += 4L
            return s
        }

        fun readReference(): List<Any> {
            val count = readUInt32().toInt().coerceAtMost(4096)
            val list = mutableListOf<Any>()
            for (i in 0 until count) {
                if (pos >= limit) break
                when (readTypeTag()) {
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

    private fun readActionDescriptor(c: SourceCursor): ActionDescriptorNode {
        val name = c.readUnicodeString()
        val classId = c.readIdString()
        val count = c.readUInt32().toInt()
        val items = mutableMapOf<String, ActionDescriptorValue>()

        for (i in 0 until count) {
            if (c.pos >= c.limit) break
            val key = c.readIdString()
            val typeTag = c.readTypeTag()
            val value = readDescriptorValue(c, typeTag)
            items[key] = value
        }

        return ActionDescriptorNode(name, classId, items)
    }

    private fun readDescriptorValue(c: SourceCursor, typeTag: String): ActionDescriptorValue {
        return when (typeTag) {
            "Objc", "GlbO" -> {
                val sub = readActionDescriptor(c)
                ActionDescriptorValue.Descriptor(sub.name, sub.classId, sub.items)
            }
            "VlLs" -> {
                val count = c.readUInt32().toInt()
                val list = mutableListOf<ActionDescriptorValue>()
                for (i in 0 until count) {
                    if (c.pos >= c.limit) break
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

            // 形状动态顶层 flipX/flipY 才是 Flip X/Y Jitter（与 Brsh.flipX/flipY 静态笔尖翻转不同）
            val useTipDynamicsForFlip = extractBool(items["useTipDynamics"])
            val flipXJitter = useTipDynamicsForFlip && extractBool(items["flipX"])
            val flipYJitter = useTipDynamicsForFlip && extractBool(items["flipY"])

            val tipUuid = if (isSampled) extractString(tipItems["sampledData"]) else null

            // Blend Mode：Photoshop 随预设保存的工具选项（toolOptions.brushPreset=true）里的 Md  才是真实位置；
            // 预设顶层的 mode / Md   只是旧布局兜底。映射表见 AbrBlendModes（逐项对照 Krita ASL 序列化器）
            val toolOptionsForMode = extractItems(items["toolOptions"])
            val rawMode = (if (extractBool(toolOptionsForMode["brushPreset"])) extractString(toolOptionsForMode["Md  "]) else null)
                ?: extractString(items["mode"]) ?: extractString(items["Md  "])
            val compOp = AbrBlendModes.brush(rawMode).id

            // Airbrush
            // `Rpt `＝Photoshop 画笔面板的「喷枪/Build-up」开关（推测：实心喷枪 #22、炭笔类预设 Rpt=true 印证它是 Airbrush；未用 PS 逐项核对）
            val airbrush = extractBool(items["airbrush"]) || extractBool(items["buildUp"]) || extractBool(items["Rpt "])

            // Shape Dynamics
            val useTipDynamics = extractBool(items["useTipDynamics"])
            var pressureSize = false
            var tiltSize = false
            var sizeJitter = 0.0
            var minDiameterRatio = 0.0
            var followDirection = false
            var tiltAngle = false
            var angleControlRaw = 0
            var angleJitter = 0.0
            var roundnessJitter = 0.0
            var minRoundness = 0.0
            var pressureRoundness = false
            var tiltRoundness = false

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
                angleControlRaw = angControl
                if (angControl in 6..8) followDirection = true
                else if (angControl == 3) tiltAngle = true

                angleJitter = ((extractDouble(angVr["jitter"]) ?: 0.0) / 100.0).coerceIn(0.0, 1.0)

                val rndVr = extractItems(items["roundnessDynamics"]).ifEmpty { extractItems(items["rndVr"]) }
                roundnessJitter = ((extractDouble(rndVr["jitter"]) ?: 0.0) / 100.0).coerceIn(0.0, 1.0)
                val rndControl = extractDouble(rndVr["bVTy"])?.toInt() ?: 0
                if (rndControl == 2) pressureRoundness = true
                else if (rndControl == 3) tiltRoundness = true
                val minR = extractDouble(items["minimumRoundness"]) ?: extractDouble(rndVr["Mnm "])
                if (minR != null) {
                    minRoundness = (minR / 100.0).coerceIn(0.0, 1.0)
                }
            }

            // 最小圆度顶层键：Photoshop 新版把 minimumRoundness 写在 brushPreset 顶层
            // （与 useTipDynamics 块无关），旧格式才在 rndVr["Mnm "]（块内兜底，上文已读）。
            extractDouble(items["minimumRoundness"])?.let { topMinRoundness ->
                minRoundness = (topMinRoundness / 100.0).coerceIn(0.0, 1.0)
            }

            // Scattering
            val useScatter = extractBool(items["useScatter"])
            var scatterVal = 0.0
            // Photoshop bothAxes=false 只沿垂直笔画方向散布；没开散布时保持引擎默认双轴
            val scatterBothAxes = !useScatter || extractBool(items["bothAxes"])
            var scatterCount = 1
            var scatterPressure = false
            if (useScatter) {
                val scVr = extractItems(items["scatterDynamics"])
                scatterPressure = extractDouble(scVr["bVTy"])?.toInt() == 2
                val jf = extractDouble(scVr["jitter"]) ?: 0.0
                // PS 散布 0..1000%；Krita Scattering/Amount 范围 0..5（KisScatterOptionData.cpp），按 Krita 上限截断
                scatterVal = (jf / 100.0).coerceIn(0.0, SCATTER_MAX)
                scatterCount = (extractDouble(items["Cnt "]) ?: 1.0).toInt().coerceIn(1, MAX_SCATTER_COUNT)
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
            var opacityMin = 0.0
            var flowMin = 0.0

            if (usePaintDynamics) {
                val opVr = extractItems(items["opVr"]).ifEmpty { extractItems(items["opacityDynamics"]) }
                val opControl = extractDouble(opVr["bVTy"])?.toInt() ?: 0
                if (opControl == 2) pressureOpacity = true
                else if (opControl == 3) tiltOpacity = true
                opacityJitter = ((extractDouble(opVr["jitter"]) ?: 0.0) / 100.0).coerceIn(0.0, 1.0)
                opacityMin = ((extractDouble(opVr["Mnm "]) ?: 0.0) / 100.0).coerceIn(0.0, 1.0)

                val prVr = extractItems(items["prVr"])
                    .ifEmpty { extractItems(items["flVr"]) }
                    .ifEmpty { extractItems(items["flowDynamics"]) }
                val flControl = extractDouble(prVr["bVTy"])?.toInt() ?: 0
                if (flControl == 2) pressureFlow = true
                else if (flControl == 3) tiltFlow = true
                flowJitter = ((extractDouble(prVr["jitter"]) ?: 0.0) / 100.0).coerceIn(0.0, 1.0)
                flowMin = ((extractDouble(prVr["Mnm "]) ?: 0.0) / 100.0).coerceIn(0.0, 1.0)
            }

            // 工具选项：Include Tool Settings（brushPreset=true）时随预设保存的不透明度 / 流量 / 工具类型
            val toolOptionsDesc = items["toolOptions"] as? ActionDescriptorValue.Descriptor
            val toolClass = toolOptionsDesc?.classId ?: ""
            val toolOptions = toolOptionsDesc?.items ?: extractItems(items["toolOptions"])
            val useToolOptions = extractBool(toolOptions["brushPreset"])
            val toolOpacity = if (useToolOptions) ((extractDouble(toolOptions["Opct"]) ?: 100.0) / 100.0).coerceIn(0.0, 1.0) else 1.0
            val toolFlow = if (useToolOptions) ((extractDouble(toolOptions["flow"]) ?: 100.0) / 100.0).coerceIn(0.0, 1.0) else 1.0
            val smudgeStrength = ((extractDouble(toolOptions["Prs"]) ?: 100.0) / 100.0).coerceIn(0.0, 1.0)
            val wetness = (extractDouble(toolOptions["wetness"]) ?: 0.0).coerceIn(0.0, 1.0)
            val mix = (extractDouble(toolOptions["mix"]) ?: 0.0).coerceIn(0.0, 1.0)
            val dryness = (extractDouble(toolOptions["dryness"]) ?: 0.0).coerceIn(0.0, 1.0)

            // Dual Brush
            // useDualBrush 开关在 Photoshop 新格式里写在嵌套 dualBrush 描述符内部，
            // 旧格式写在顶层；两处任一为 true 即启用，参数仍从嵌套描述符读取。
            val dualDesc = items["dualBrush"] as? ActionDescriptorValue.Descriptor
            val useDualBrush = extractBool(dualDesc?.items?.get("useDualBrush")) || extractBool(items["useDualBrush"])
            val dualBrushInfo = if (useDualBrush) {
                if (dualDesc != null) {
                    val dualTipDesc = dualDesc.items["Brsh"] as? ActionDescriptorValue.Descriptor
                    val dTipItems = dualTipDesc?.items ?: emptyMap()
                    val dTipUuid = extractString(dTipItems["sampledData"])
                    val dDiam = extractDouble(dTipItems["Dmtr"]) ?: 30.0
                    val dSpcn = ((extractDouble(dTipItems["Spcn"]) ?: 25.0) / 100.0).coerceIn(0.01, 5.0)
                    // 双重画笔散布在嵌套 scatterDynamics.jitter（useScatter 控制），混合模式键是 BlnM
                    val dScat = if (extractBool(dualDesc.items["useScatter"])) {
                        ((extractDouble(extractItems(dualDesc.items["scatterDynamics"])["jitter"]) ?: 0.0) / 100.0).coerceIn(0.0, SCATTER_MAX)
                    } else 0.0
                    val dualScatterOn = extractBool(dualDesc.items["useScatter"])
                    val dModeRaw = extractString(dualDesc.items["BlnM"]) ?: extractString(dualDesc.items["Md  "]) ?: "Mltp"
                    val dMode = AbrBlendModes.dual(dModeRaw, AbrBlendModes.Policy.PRECISION).id
                    AbrDualBrushInfo(
                        tipUuid = dTipUuid,
                        diameter = dDiam,
                        spacing = dSpcn,
                        scatter = dScat,
                        compositeOp = dMode,
                        rawMode = dModeRaw,
                        // 双重画笔面板的 `Flip`＝逐印章随机水平翻转（推测：只翻水平轴）；笔尖自带 flipX/flipY 仍按旧语义
                        flipX = extractBool(dTipItems["flipX"]) || extractBool(dualDesc.items["Flip"]),
                        flipY = extractBool(dTipItems["flipY"]),
                        scatterBothAxes = !dualScatterOn || extractBool(dualDesc.items["bothAxes"]),
                        count = (extractDouble(dualDesc.items["Cnt "]) ?: 1.0).toInt().coerceIn(1, MAX_SCATTER_COUNT),
                        angle = extractDouble(dTipItems["Angl"]) ?: 0.0,
                        roundness = ((extractDouble(dTipItems["Rndn"]) ?: 100.0) / 100.0).coerceIn(0.01, 1.0),
                    )
                } else null
            } else null

            // Texture
            // Photoshop 新版把纹理键（useTexture/textureBlendMode/textureScale/textureDepth/
            // Txtr/InvT）直接写在 brushPreset 描述符顶层；旧格式才有嵌套 "texture" 子描述符。
            // 嵌套子描述符存在且自带 useTexture 时以其开关为准，否则读顶层开关；
            // 其余键在两种布局里统一从 texItems（嵌套优先、顶层兜底）读取。
            val nestedTexDesc = items["texture"] as? ActionDescriptorValue.Descriptor
            val texItems: Map<String, ActionDescriptorValue> = nestedTexDesc?.items ?: items
            val useTexture = if (nestedTexDesc != null && nestedTexDesc.items.containsKey("useTexture")) {
                extractBool(nestedTexDesc.items["useTexture"])
            } else {
                extractBool(items["useTexture"])
            }
            val textureInfo = if (useTexture) {
                val patDesc = texItems["Txtr"] as? ActionDescriptorValue.Descriptor
                val patName = extractString(patDesc?.items?.get("Nm  ")) ?: ""
                val patUuid = extractString(patDesc?.items?.get("Idnt")) ?: ""
                val scale = ((extractDouble(texItems["textureScale"]) ?: extractDouble(texItems["Scl "]) ?: 100.0) / 100.0).coerceIn(0.01, 10.0)
                val depth = ((extractDouble(texItems["textureDepth"]) ?: extractDouble(texItems["Dpt "]) ?: 50.0) / 100.0).coerceIn(0.0, 1.0)
                val modeRaw = extractString(texItems["textureBlendMode"]) ?: extractString(texItems["Md  "]) ?: "Mltp"
                val mode = AbrBlendModes.texture(modeRaw, AbrBlendModes.Policy.PRECISION).id
                val inv = extractBool(texItems["InvT"])
                val brightness = (extractDouble(texItems["textureBrightness"]) ?: extractDouble(texItems["Brgh"]) ?: 0.0).toInt().coerceIn(-150, 150)
                val contrast = (extractDouble(texItems["textureContrast"]) ?: extractDouble(texItems["Cntr"]) ?: 0.0).toInt().coerceIn(-50, 100)
                AbrTextureInfo(
                    patternName = patName,
                    patternUuid = patUuid,
                    scale = scale,
                    depth = depth,
                    mode = mode,
                    rawMode = modeRaw,
                    invert = inv,
                    brightness = brightness,
                    contrast = contrast,
                    depthPressure = (extractItems(texItems["textureDepthDynamics"]).takeIf { it.isNotEmpty() }
                        ?: extractItems(items["textureDepthDynamics"]))["bVTy"]?.let { extractDouble(it) }?.toInt() == 2,
                    depthMin = ((extractDouble(texItems["minimumDepth"]) ?: extractDouble(items["minimumDepth"]) ?: 0.0) / 100.0).coerceIn(0.0, 1.0),
                )
            } else null

            // Color Dynamics
            val useColorDynamics = extractBool(items["useColorDynamics"])
            val colorDynamicsInfo = if (useColorDynamics) {
                // 真实 PS 预设把色彩动态直接写在预设顶层：H=色相抖动、Strt=饱和度抖动、Brgh=亮度抖动、
                // purity=纯度、clVr.jitter=前景/背景抖动；旧版夹具才有 colorDynamics/clVr 内的 hJtr/sJtr/bJtr，保留兜底。
                val clDesc = (items["colorDynamics"] ?: items["clVr"]) as? ActionDescriptorValue.Descriptor
                fun pct(top: String, legacy: String): Double =
                    (extractDouble(items[top]) ?: extractDouble(clDesc?.items?.get(legacy)) ?: 0.0)
                val hJ = (pct("H   ", "hJtr") / 100.0).coerceIn(0.0, 1.0)
                val sJ = (pct("Strt", "sJtr") / 100.0).coerceIn(0.0, 1.0)
                val bJ = (pct("Brgh", "bJtr") / 100.0).coerceIn(0.0, 1.0)
                val mix = ((extractDouble(clDesc?.items?.get("jitter")) ?: 0.0) / 100.0).coerceIn(0.0, 1.0)
                if (hJ > 0.0 || sJ > 0.0 || bJ > 0.0 || mix > 0.0) {
                    AbrColorDynamicsInfo(hueJitter = hJ, satJitter = sJ, valJitter = bJ, secondaryMix = mix)
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
                    scatterBothAxes = scatterBothAxes,
                    toolOpacity = toolOpacity,
                    toolFlow = toolFlow,
                    pressureRoundness = pressureRoundness,
                    tiltRoundness = tiltRoundness,
                    pressureSize = pressureSize,
                    pressureOpacity = pressureOpacity,
                    pressureFlow = pressureFlow,
                    tiltSize = tiltSize,
                    tiltOpacity = tiltOpacity,
                    tiltFlow = tiltFlow,
                    tiltAngle = tiltAngle,
                    angleControl = angleControlRaw,
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
                    flipXJitter = flipXJitter,
                    flipYJitter = flipYJitter,
                    scatterCount = scatterCount,
                    scatterPressure = scatterPressure,
                    opacityMin = opacityMin,
                    flowMin = flowMin,
                    isComputed = !isSampled,
                    dualBrush = dualBrushInfo,
                    texture = textureInfo,
                    colorDynamics = colorDynamicsInfo,
                    airbrush = airbrush,
                    compositeOp = compOp,
                    toolClass = toolClass,
                    smudgeStrength = smudgeStrength,
                    wetness = wetness,
                    mix = mix,
                    dryness = dryness,
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

    /** 8-bit grayscale PNG (color type 0). First successful patt channel. */
    private fun encodeGrayPng(width: Int, height: Int, gray: ByteArray): ByteArray =
        PngChunks.encodeImage(width, height, 0, gray)

    private fun encodeRgbaPng(width: Int, height: Int, rgbaBytes: ByteArray): ByteArray =
        PngChunks.encodeImage(width, height, 6, rgbaBytes)
}

/**
 * 有上限地读完整条流, 避免 `readBytes()` 的"整份数据 + 一次 toByteArray 拷贝"双份峰值。
 *
 * - 已知长度: 一次分配到位, 不再增长 (常见路径, 峰值 = 文件大小)
 * - 未知长度: 倍增增长, 一旦超过 [limit] 立即放弃 (峰值 <= 2 × limit)
 *
 * 超过上限返回 null; 调用方把它映射成"包太大"的提示。
 */
@Suppress("DEPRECATION")
internal fun readImportBytes(
    input: InputStream,
    sizeHint: Long = -1L,
    limit: Long = AbrParser.MAX_IMPORT_BYTES,
): ByteArray? {
    if (sizeHint in 1..limit) {
        val exact = ByteArray(sizeHint.toInt())
        var offset = 0
        while (offset < exact.size) {
            val read = input.read(exact, offset, exact.size - offset)
            if (read < 0) break
            offset += read
        }
        return if (offset == exact.size) exact else exact.copyOf(offset)
    }
    if (sizeHint > limit) return null

    var buffer = ByteArray(1 shl 20)
    var size = 0
    while (true) {
        if (size == buffer.size) {
            if (buffer.size.toLong() >= limit) {
                // 刚好读满上限: 再探一个字节, 确认是 EOF 而不是还有数据
                return if (input.read() < 0) buffer else null
            }
            buffer = buffer.copyOf(minOf(buffer.size.toLong() * 2, limit).toInt())
        }
        val read = input.read(buffer, size, buffer.size - size)
        if (read < 0) break
        size += read
    }
    return if (size == buffer.size) buffer else buffer.copyOf(size)
}
