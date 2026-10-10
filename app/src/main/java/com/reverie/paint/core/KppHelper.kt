package com.reverie.paint.core

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.Inflater

object KppHelper {
    private val PNG_HEADER = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    )

    /**
     * Reads the preset XML text from a .kpp file (which is a PNG with a zTXt preset chunk).
     *
     * [stripEmbeddedResources] removes the base64 payload carried by embedded
     * `<resource>` elements (animated .gih tips, bundled .png textures) while keeping
     * the element itself. Those payloads are what make a preset file huge: the
     * "memileo Impasto" presets ship a ~24 MB animated tip and inflate to ~27 MB of
     * XML, yet attribute extraction only ever reads `<param>` scalars and
     * `<MaskGenerator>`. Stripping cuts that ~27 MB down to ~12 KB, so the dozens of
     * regexes below scan three orders of magnitude less text.
     *
     * Write paths (dedupe / update / thumbnail rewrite) MUST keep the default `false`,
     * otherwise the embedded tip would be dropped from the rewritten preset.
     */
    fun readPresetXml(kppBytes: ByteArray, stripEmbeddedResources: Boolean = false): String? {
        if (kppBytes.size < 8) return null
        for (i in 0 until 8) {
            if (kppBytes[i] != PNG_HEADER[i]) return null
        }
        var idx = 8
        while (idx + 12 <= kppBytes.size) {
            val length = ByteBuffer.wrap(kppBytes, idx, 4).order(ByteOrder.BIG_ENDIAN).int
            val chunkType = String(kppBytes, idx + 4, 4, Charsets.ISO_8859_1)
            val chunkDataStart = idx + 8
            // Accumulate in Long: `length` comes from the file and a corrupt one may be
            // 0x7FFFFFFF. With Int arithmetic `chunkDataStart + length` overflows negative,
            // the `> size` guard stops working, and the next round's
            // ByteBuffer.wrap(bytes, idx, 4) sees a negative offset and throws
            // IndexOutOfBoundsException. Reachable with brush packs downloaded off the web.
            val chunkDataEnd = chunkDataStart.toLong() + length.toLong()
            if (length < 0 || chunkDataEnd + 4 > kppBytes.size.toLong()) break
            val chunkDataEndI = chunkDataEnd.toInt()

            if (chunkType == "zTXt") {
                var nullPos = -1
                for (p in chunkDataStart until chunkDataEndI) {
                    if (kppBytes[p] == 0.toByte()) {
                        nullPos = p
                        break
                    }
                }
                if (nullPos != -1 && nullPos + 2 <= chunkDataEndI) {
                    val keyword = String(kppBytes, chunkDataStart, nullPos - chunkDataStart, Charsets.ISO_8859_1)
                    if (keyword == "preset") {
                        val cmethod = kppBytes[nullPos + 1].toInt()
                        if (cmethod == 0) {
                            val compressedStart = nullPos + 2
                            val compressedLen = chunkDataEndI - compressedStart
                            val inflater = Inflater(false)
                            inflater.setInput(kppBytes, compressedStart, compressedLen)
                            val bos = ByteArrayOutputStream()
                            val buf = ByteArray(4096)
                            while (!inflater.finished() && !inflater.needsInput()) {
                                val count = inflater.inflate(buf)
                                if (count > 0) bos.write(buf, 0, count)
                                else break
                            }
                            inflater.end()
                            return presetPayloadText(bos.toByteArray(), stripEmbeddedResources)
                        }
                    }
                }
            } else if (chunkType == "tEXt") {
                var nullPos = -1
                for (p in chunkDataStart until chunkDataEndI) {
                    if (kppBytes[p] == 0.toByte()) {
                        nullPos = p
                        break
                    }
                }
                if (nullPos != -1) {
                    val keyword = String(kppBytes, chunkDataStart, nullPos - chunkDataStart, Charsets.ISO_8859_1)
                    if (keyword == "preset") {
                        val textStart = nullPos + 1
                        val textLen = chunkDataEndI - textStart
                        return presetPayloadText(kppBytes, textStart, textLen, stripEmbeddedResources)
                    }
                }
            } else if (chunkType == "iTXt") {
                var nullPos = -1
                for (p in chunkDataStart until chunkDataEndI) {
                    if (kppBytes[p] == 0.toByte()) {
                        nullPos = p
                        break
                    }
                }
                if (nullPos != -1 && nullPos + 2 <= chunkDataEndI) {
                    val keyword = String(kppBytes, chunkDataStart, nullPos - chunkDataStart, Charsets.ISO_8859_1)
                    if (keyword == "preset") {
                        val compFlag = kppBytes[nullPos + 1].toInt()
                        val compMethod = kppBytes[nullPos + 2].toInt()
                        var p = nullPos + 3
                        while (p < chunkDataEndI && kppBytes[p] != 0.toByte()) p++
                        p++ // skip null
                        while (p < chunkDataEndI && kppBytes[p] != 0.toByte()) p++
                        p++ // skip null
                        val textStart = p
                        val textLen = chunkDataEndI - textStart
                        if (textLen >= 0 && textStart <= chunkDataEndI) {
                            if (compFlag == 1 && compMethod == 0) {
                                val inflater = Inflater(false)
                                inflater.setInput(kppBytes, textStart, textLen)
                                val bos = ByteArrayOutputStream()
                                val buf = ByteArray(8192)
                                while (!inflater.finished() && !inflater.needsInput()) {
                                    val count = inflater.inflate(buf)
                                    if (count > 0) bos.write(buf, 0, count)
                                    else break
                                }
                                inflater.end()
                                return presetPayloadText(bos.toByteArray(), stripEmbeddedResources)
                            } else if (compFlag == 0) {
                                return presetPayloadText(kppBytes, textStart, textLen, stripEmbeddedResources)
                            }
                        }
                    }
                }
            }
            idx += 12 + length
        }
        return null
    }

    /** Materializes the preset chunk payload as UTF-8 text, optionally dropping embedded payloads. */
    private fun presetPayloadText(raw: ByteArray, strip: Boolean): String =
        String(if (strip) stripEmbeddedResourcePayloads(raw) else raw, Charsets.UTF_8)

    /** Same as above for a payload region inside a larger buffer (uncompressed chunks). */
    private fun presetPayloadText(src: ByteArray, start: Int, len: Int, strip: Boolean): String =
        presetPayloadText(src.copyOfRange(start, start + len), strip)

    private val CDATA_OPEN = "<![CDATA[".toByteArray(Charsets.US_ASCII)
    private val CDATA_CLOSE = "]]>".toByteArray(Charsets.US_ASCII)
    private val RESOURCE_OPEN = "<resource".toByteArray(Charsets.US_ASCII)
    private val TAG_END = ">".toByteArray(Charsets.US_ASCII)

    /** Parsed-attribute memo for [parseKppFile]: path -> (mtime, size, attributes). */
    private const val PARSE_CACHE_MAX = 32
    private val parseCache = LinkedHashMap<String, Triple<Long, Long, KppParsedAttributes>>(16, 0.75f, true)

    private fun indexOfBytes(hay: ByteArray, from: Int, needle: ByteArray): Int {
        if (needle.isEmpty() || from < 0) return -1
        val last = hay.size - needle.size
        if (last < 0) return -1
        outer@ for (i in from..last) {
            for (j in needle.indices) {
                if (hay[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }

    /**
     * Drops the base64 body of every `<resource ...><![CDATA[...]]></resource>` element while
     * keeping the element and its attributes (including `filename=`, which callers read).
     *
     * Krita presets may embed the brush tip and textures directly in the .kpp; a single
     * "memileo Impasto" preset carries a ~24 MB animated .gih, so the inflated preset XML
     * reaches ~27 MB of which only ~12 KB is actual settings. Nothing in attribute
     * extraction reads those bodies, and copying them into a String is what made selecting
     * such a preset freeze the UI.
     */
    private fun stripEmbeddedResourcePayloads(src: ByteArray): ByteArray {
        var searchFrom = indexOfBytes(src, 0, RESOURCE_OPEN)
        if (searchFrom < 0) return src
        val out = ByteArrayOutputStream(src.size)
        var copiedUpTo = 0
        while (searchFrom >= 0) {
            val tagEnd = indexOfBytes(src, searchFrom, TAG_END)
            if (tagEnd < 0) break
            var payloadStart = tagEnd + 1
            while (payloadStart < src.size && isXmlSpace(src[payloadStart])) payloadStart++
            if (indexOfBytes(src, payloadStart, CDATA_OPEN) == payloadStart) {
                val bodyStart = payloadStart + CDATA_OPEN.size
                val bodyEnd = indexOfBytes(src, bodyStart, CDATA_CLOSE)
                if (bodyEnd > bodyStart) {
                    out.write(src, copiedUpTo, bodyStart - copiedUpTo)
                    out.write(CDATA_OPEN)
                    out.write(CDATA_CLOSE)
                    copiedUpTo = bodyEnd + CDATA_CLOSE.size
                    searchFrom = indexOfBytes(src, copiedUpTo, RESOURCE_OPEN)
                    continue
                }
            }
            searchFrom = indexOfBytes(src, tagEnd + 1, RESOURCE_OPEN)
        }
        if (copiedUpTo == 0) return src
        out.write(src, copiedUpTo, src.size - copiedUpTo)
        return out.toByteArray()
    }

    private fun isXmlSpace(b: Byte): Boolean =
        b == 0x20.toByte() || b == 0x09.toByte() || b == 0x0A.toByte() || b == 0x0D.toByte()

    /**
     * Extracts tip asset filename (e.g. "mooncake.png", "brush.gbr") from .kpp bytes.
     */
    fun extractTipAssetFilename(kppBytes: ByteArray): String? {
        val xml = readPresetXml(kppBytes) ?: return null
        val regex = Regex("""filename="([^"]+)"""")
        return regex.find(xml)?.groupValues?.getOrNull(1)
    }

    /**
     * Updates an existing .kpp file on disk with the specified brush parameters.
     */
    fun updateKppFile(kppFile: File, presetName: String, params: BrushParams): Boolean {
        return try {
            if (!kppFile.exists()) return false
            val bytes = kppFile.readBytes()
            val updated = updateKppBytes(bytes, presetName, params)
            kppFile.writeBytes(updated)
            true
        } catch (e: Exception) {
            android.util.Log.e("KppHelper", "Failed to update kpp file: ${kppFile.name}", e)
            false
        }
    }

    /**
     * Updates the PNG image raster of a .kpp file while preserving its preset XML metadata.
     * [newPngBytes] must be a valid PNG (e.g. from Bitmap.compress(PNG)).
     */
    fun updateKppThumbnail(kppFile: File, newPngBytes: ByteArray): Boolean {
        return try {
            if (!kppFile.exists()) return false
            val currentBytes = kppFile.readBytes()
            val xml = readPresetXml(currentBytes) ?: return false
            val updated = replacePresetXml(newPngBytes, xml)
            kppFile.writeBytes(updated)
            true
        } catch (e: Exception) {
            android.util.Log.e("KppHelper", "Failed to update thumbnail for: ${kppFile.name}", e)
            false
        }
    }

    /**
     * Updates an existing .kpp file (or bytes) by modifying parameters in its preset XML.
     * Returns a new byte array with the updated zTXt chunk.
     *
     * [tipScale] is only supplied by the ABR import path (= ABR diameter / longest tip side).
     * `null` means "the caller does not know the right value", in which case the scale already
     * present in the file is preserved. The brush workshop goes through that path, and it must
     * never be reset back to 1, otherwise every parameter edit snaps the size back to the raw
     * pixel size of the tip.
     */
    fun updateKppBytes(
        kppBytes: ByteArray,
        presetName: String,
        params: BrushParams,
        tipScale: Double? = null,
    ): ByteArray {
        if (kppBytes.size < 8) return kppBytes
        for (i in 0 until 8) {
            if (kppBytes[i] != PNG_HEADER[i]) return kppBytes
        }

        val originalXml = readPresetXml(kppBytes)
        val baseXml = originalXml ?: buildMinimalPresetXml(presetName, params, tipScale)
        val effectiveParams = if (originalXml == null) {
            params.copy(dynamicsCustomized = true)
        } else {
            params
        }
        val newXml = injectParamsIntoXml(baseXml, presetName, effectiveParams, tipScale)

        return replacePresetXml(kppBytes, newXml)
    }

    /**
     * 清除历史版本追加产生的重复参数键, 每个键只保留首次出现（Krita 原生写出的那一行）。
     *
     * 背景: 旧版 updateParam 的正则把属性顺序写死成 `type` 在前, 而 Krita 写出的是
     * `<param name="X" type="string">`（name 在前）, 导致一条都匹配不上、所有参数修改
     * 都被追加成重复键。引擎按文档序"后者覆盖前者"解析, 于是文件里新旧两套值谁生效
     * 全看追加顺序。这里把污染清掉。
     *
     * Returns true if the file was rewritten.
     */
    fun dedupePresetFile(kppFile: File): Boolean {
        return try {
            if (!kppFile.exists()) return false
            val bytes = kppFile.readBytes()
            val xml = readPresetXml(bytes) ?: return false
            val deduped = dedupeParamsXml(xml)
            if (deduped == xml) return false
            kppFile.writeBytes(replacePresetXml(bytes, deduped))
            true
        } catch (e: Exception) {
            android.util.Log.e("KppHelper", "dedupe failed: ${kppFile.name}", e)
            false
        }
    }

    internal fun dedupeParamsXml(xml: String): String {
        val paramRegex = Regex("""<param\b[^>]*\bname="([^"]+)"[^>]*>.*?</param>""", RegexOption.DOT_MATCHES_ALL)
        val seen = HashSet<String>()
        var changed = false
        val out = StringBuilder(xml.length)
        var last = 0
        for (m in paramRegex.findAll(xml)) {
            if (!seen.add(m.groupValues[1])) {
                out.append(xml, last, m.range.first)
                last = m.range.last + 1
                changed = true
            }
        }
        if (!changed) return xml
        out.append(xml, last, xml.length)
        return out.toString()
    }

    /** 用新的 preset XML 替换 .kpp 里的 zTXt preset 块, PNG 其余 chunk 原样保留。 */
    /**
     * Rewrites the preset text chunk of a .kpp, keeping every other PNG chunk as is.
     *
     * Two hard constraints imposed by the way Krita reads .kpp files:
     *
     * 1. **The preset / version text chunks must sit before the first IDAT.** Krita's
     *    `KisPaintOpPreset::loadFromDevice` calls `text("version")` / `text("preset")`
     *    *before* `reader.read()`, and at that moment Qt's `QPngHandler` only exposes
     *    pre-IDAT text chunks. Writing them after IDAT is the same as not writing them:
     *    it looks fine on the import pass (the parameters were applied to the in-memory
     *    preset directly) but **after a restart** the engine cannot read the preset out of
     *    the .kpp, falls back to `auto_brush`, and every imported brush turns into the same
     *    round dab.
     * 2. **The version chunk must not be dropped.** It selects the preset XML schema version
     *    (Krita 5.x writes 2.2). When the base image is a programmatically generated PNG —
     *    e.g. the preview produced by `encodePreviewPng` for an ABR import — it carries no
     *    text chunks at all, so one has to be added here.
     *
     * Hence the implementation drops any existing preset / version chunk and writes both back
     * ahead of the first IDAT, rather than replacing in place: the latter would perpetuate a
     * wrong position when the original chunk already sat after IDAT.
     */
    private fun replacePresetXml(kppBytes: ByteArray, newXml: String): ByteArray {
        val presetChunk = buildTextChunkData(
            keyword = "preset",
            payload = deflateXml(newXml),
            isCompressed = true,
        )
        // Reuse the version already present in the base image; only fall back to the default
        // when the base is a generated PNG that has no text chunk at all.
        val versionChunk = buildTextChunkData(
            keyword = "version",
            payload = (readExistingVersion(kppBytes) ?: PRESET_XML_VERSION).toByteArray(Charsets.ISO_8859_1),
            isCompressed = false,
        )

        val out = ByteArrayOutputStream()
        out.write(PNG_HEADER)

        var idx = 8
        var presetWritten = false
        var versionWritten = false
        while (idx + 12 <= kppBytes.size) {
            val length = ByteBuffer.wrap(kppBytes, idx, 4).order(ByteOrder.BIG_ENDIAN).int
            val chunkType = String(kppBytes, idx + 4, 4, Charsets.ISO_8859_1)
            val chunkDataStart = idx + 8
            // Long accumulation again: a corrupt 0x7FFFFFFF length would overflow the Int
            // sum into a negative value, defeating the `> size` guard, and the subsequent
            // out.write(kppBytes, idx, 12 + length) would throw IndexOutOfBounds.
            val chunkDataEnd = chunkDataStart.toLong() + length.toLong()
            if (length < 0 || chunkDataEnd + 4 > kppBytes.size.toLong()) break

            val keyword = if (chunkType == "zTXt" || chunkType == "tEXt" || chunkType == "iTXt") {
                readChunkKeyword(kppBytes, chunkDataStart, chunkDataEnd.toInt())
            } else null

            // Any chunk already claiming to be preset / version is dropped here and rewritten
            // ahead of the first IDAT below.
            if (keyword == "preset" || keyword == "version") {
                idx += 12 + length
                continue
            }

            // The first IDAT — or IEND, for an image-less PNG — is the point where the two text
            // chunks have to be emitted; see the KDoc above.
            if (!presetWritten && (chunkType == "IDAT" || chunkType == "IEND")) {
                if (!versionWritten) {
                    writeChunk(out, "tEXt", versionChunk)
                    versionWritten = true
                }
                writeChunk(out, "zTXt", presetChunk)
                presetWritten = true
            }

            out.write(kppBytes, idx, 12 + length)
            idx += 12 + length
        }

        // Fallback for a truncated PNG that has neither IDAT nor IEND.
        if (!presetWritten) {
            if (!versionWritten) writeChunk(out, "tEXt", versionChunk)
            writeChunk(out, "zTXt", presetChunk)
        }
        return out.toByteArray()
    }

    /** Deflates the preset XML, which is the payload of the zTXt chunk. */
    private fun deflateXml(xml: String): ByteArray {
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION)
        deflater.setInput(xml.toByteArray(Charsets.UTF_8))
        deflater.finish()
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (!deflater.finished()) {
            out.write(buf, 0, deflater.deflate(buf))
        }
        deflater.end()
        return out.toByteArray()
    }

    /** Builds the data section of a text chunk: keyword + 0x00 (+ method 0x00) + payload. */
    private fun buildTextChunkData(keyword: String, payload: ByteArray, isCompressed: Boolean): ByteArray {
        val kw = keyword.toByteArray(Charsets.ISO_8859_1)
        val head = if (isCompressed) 2 else 1
        val data = ByteArray(kw.size + head + payload.size)
        System.arraycopy(kw, 0, data, 0, kw.size)
        data[kw.size] = 0
        if (isCompressed) data[kw.size + 1] = 0
        System.arraycopy(payload, 0, data, kw.size + head, payload.size)
        return data
    }

    /** Writes a complete chunk: length + type + data + CRC32(type + data). */
    private fun writeChunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
        out.write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(data.size).array())
        val typeBytes = type.toByteArray(Charsets.ISO_8859_1)
        out.write(typeBytes)
        out.write(data)
        val crc = CRC32()
        crc.update(typeBytes)
        crc.update(data)
        out.write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(crc.value.toInt()).array())
    }

    /** Reads a text chunk's keyword (terminated by 0x00); null when it is not a text chunk. */
    private fun readChunkKeyword(png: ByteArray, from: Int, to: Int): String? {
        var p = from
        while (p < to && png[p] != 0.toByte()) p++
        if (p >= to) return null
        return String(png, from, p - from, Charsets.ISO_8859_1)
    }

    /** Reads the version text value already present in a .kpp (tEXt or iTXt); null when absent. */
    private fun readExistingVersion(png: ByteArray): String? {
        var idx = 8
        while (idx + 12 <= png.size) {
            val length = ByteBuffer.wrap(png, idx, 4).order(ByteOrder.BIG_ENDIAN).int
            val chunkType = String(png, idx + 4, 4, Charsets.ISO_8859_1)
            val start = idx + 8
            // Long accumulation, so a corrupt oversized length cannot overflow past the guard.
            val end = start.toLong() + length.toLong()
            if (length < 0 || end + 4 > png.size.toLong()) break
            if (chunkType == "tEXt" || chunkType == "iTXt") {
                val endI = end.toInt()
                var p = start
                while (p < endI && png[p] != 0.toByte()) p++
                if (p < endI && String(png, start, p - start, Charsets.ISO_8859_1) == "version") {
                    if (chunkType == "tEXt") {
                        val value = String(png, p + 1, endI - p - 1, Charsets.ISO_8859_1)
                        if (value.isNotBlank()) return value
                    } else if (p + 3 <= endI) {
                        val compFlag = png[p + 1].toInt()
                        var ip = p + 3
                        while (ip < endI && png[ip] != 0.toByte()) ip++
                        ip++ // skip lang
                        while (ip < endI && png[ip] != 0.toByte()) ip++
                        ip++ // skip trans_kw
                        if (compFlag == 0 && ip <= endI) {
                            val value = String(png, ip, endI - ip, Charsets.UTF_8)
                            if (value.isNotBlank()) return value
                        } else if (compFlag == 1 && ip <= endI) {
                            val inflater = Inflater(false)
                            inflater.setInput(png, ip, endI - ip)
                            val bos = ByteArrayOutputStream()
                            val buf = ByteArray(128)
                            while (!inflater.finished() && !inflater.needsInput()) {
                                val count = inflater.inflate(buf)
                                if (count > 0) bos.write(buf, 0, count) else break
                            }
                            inflater.end()
                            val value = bos.toString(Charsets.UTF_8.name())
                            if (value.isNotBlank()) return value
                        }
                    }
                }
            }
            idx += 12 + length
        }
        return null
    }

    /** Default used when the base image has no version chunk; matches what Krita 5.x writes. */
    private const val PRESET_XML_VERSION = "2.2"

    /**
     * Fade / Softness 语义（2026-09-29 按 248 个预设实测校准，勿再反）：
     * - hfade/vfade 是**实心率**：Eraser_hard/Pencil_2B 等 hfade=1.0，Eraser_Soft/Airbrush_Soft 等 hfade=0.0。
     *   实心核半径 = fade × softness × 笔刷半径（kis_circle_mask_generator.cpp:51-91，nf=n/(fade·s)²）。
     *   所以 fade=1 最锐利，fade=0 全羽化；softness=1 不改动，越小越糊。
     * - MaskGenerator 的属性必须读写**主 `brush_definition`**；`MaskingBrush/Preset/brush_definition`
     *   是掩膜子预设的副本且在文档序里靠前，改它引擎完全无感。
     */
    const val SOFTNESS_MIN = 0.1
    const val SOFTNESS_NEUTRAL = 1.0

    /** 需要读写曲线的动力学选项键 (与工作台 getBrushDynamicOption 的键同名) */
    val DYNAMIC_OPTION_KEYS =
        listOf("Size", "Opacity", "Flow", "Rotation", "Scatter", "Softness", "Spacing", "SmudgeRate")

    /** fade(实心率) 的"锐利"端值，也是未自定义笔刷的默认落点 */
    const val FADE_SOLID = 1.0

    private val mainBrushDefPattern =
        Regex("""<param\b[^>]*\bname="brush_definition"[^>]*>(?:<!\[CDATA\[)?(.*?)(?:\]\]>)?\s*</param>""", RegexOption.DOT_MATCHES_ALL)

    /** 主 brush_definition 的 CDATA 内容; 没有则退回整个 XML（兼容极老的生成文件） */
    private fun mainBrushDefinition(xml: String): String =
        mainBrushDefPattern.find(xml)?.groupValues?.get(1) ?: xml

    /**
     * `scale` is the **only** attribute that decides the brush size the engine reports.
     *
     * From Krita's `libs/brush/kis_scaling_size_brush.cpp`:
     * ```
     * qreal KisScalingSizeBrush::userEffectiveSize() const
     * { return qMax(this->width(), this->height()) * this->scale(); }
     * ```
     * and `KisBrushBasedPaintOpSettings::paintOpSize()` returns exactly that. So for
     * file-backed tips (`png_brush` / `gbr_brush`), writing `scale="1"` in `brush_definition`
     * is equivalent to "treat the raw tip pixel size as the brush size" — the `diameter`
     * declared by the ABR (say 80) is discarded and the engine reports 282 for a 282x282 tip.
     * Writing `diameter / max(w, h)` on import makes the reported size match the ABR.
     */
    private fun formatScale(v: Double): String {
        if (!v.isFinite() || v <= 0.0) return "1"
        val rounded = String.format(java.util.Locale.US, "%.6f", v)
        return if (rounded.contains('.')) rounded.trimEnd('0').trimEnd('.') else rounded
    }

    /** Existing scale of the main brush_definition, or Krita's default of 1 when absent */
    private fun existingBrushScale(xml: String): String =
        Regex("""<Brush\b[^>]*\bscale="([^"]*)"""")
            .find(mainBrushDefinition(xml))
            ?.groupValues?.get(1)
            ?.takeIf { it.isNotBlank() }
            ?: "1"

    /**
     * Data class holding parsed native attributes from preset XML.
     *
     * [fade] 与 [softness] 是两个不同的属性，不要混：
     * - [fade]      = brush_definition 里 MaskGenerator 的 hfade/vfade，**实心率**（1=锐利硬边，
     *                 0=全羽化），也是 Krita `KisPaintOpSettings::setPaintOpFade()` 写入的位置（取 max）。
     * - [softness]  = `SoftnessValue` curve option，绘制期的柔度乘数（1.0 = 不改动，越小越糊）。
     */
    data class KppParsedAttributes(
        val fade: Double? = null,
        val softness: Double? = null,
        val tipShape: Int? = null,
        val ratio: Double? = null,
        val spikes: Int? = null,
        val antiAliasing: Int? = null,
        val textureEnabled: Boolean? = null,
        val textureScale: Double? = null,
        val textureStrength: Double? = null,
        val textureMode: String? = null,
        val texturePattern: String? = null,
        val hueJitter: Double? = null,
        val satJitter: Double? = null,
        val valJitter: Double? = null,
        val secondaryMix: Double? = null,
        val maskingEnabled: Boolean? = null,
        val maskingCompositeOp: String? = null,
        val maskingSizeRatio: Double? = null,
        val maskingSpacing: Double? = null,
        val maskingTipAsset: String? = null,
        val rotationSensor: String? = null,
        val scatterSensor: String? = null,
        val sizeSensor: String? = null,
        val opacitySensor: String? = null,
        val flowSensor: String? = null,
        val pressureSize: Double? = null,
        val pressureOpacity: Double? = null,
        val pressureFlow: Double? = null,
        /**
         * 动力学曲线原文: optionKey(如 Size/Opacity/Flow/Rotation) -> `<Key>Sensor` param 全文。
         * 读回后灌回 brushDynamicOptions, UI 显示的曲线才与引擎实际生效的一致。
         */
        val sensorXml: Map<String, String> = emptyMap(),
    )

    /**
     * Parses native preset attributes directly from a .kpp file.
     *
     * Runs on the main thread on every brush selection, so the result is memoized per
     * file (keyed by path + mtime + size, i.e. invalidated exactly when the preset is
     * rewritten) and the embedded resource payloads are stripped before parsing.
     */
    fun parseKppFile(kppFile: File): KppParsedAttributes {
        if (!kppFile.exists()) return KppParsedAttributes()
        val key = kppFile.absolutePath
        val mtime = kppFile.lastModified()
        val size = kppFile.length()
        synchronized(parseCache) {
            val hit = parseCache[key]
            if (hit != null && hit.first == mtime && hit.second == size) {
                return hit.third
            }
        }
        val parsed = try {
            val xml = readPresetXml(kppFile.readBytes(), stripEmbeddedResources = true)
                ?: return KppParsedAttributes().also { storeParsed(key, mtime, size, it) }
            parseKppAttributes(xml)
        } catch (_: Exception) {
            KppParsedAttributes()
        }
        storeParsed(key, mtime, size, parsed)
        return parsed
    }

    private fun storeParsed(key: String, mtime: Long, size: Long, attrs: KppParsedAttributes) {
        synchronized(parseCache) {
            parseCache[key] = Triple(mtime, size, attrs)
            while (parseCache.size > PARSE_CACHE_MAX) {
                val oldest = parseCache.keys.firstOrNull() ?: break
                parseCache.remove(oldest)
            }
        }
    }

    /**
     * Parses native preset attributes directly from preset XML.
     */
    fun parseKppAttributes(xml: String): KppParsedAttributes {
        // MaskGenerator 属性只认主 brush_definition；MaskingBrush/Preset/brush_definition
        // 是掩膜副本且文档序靠前，先读它会把副本值当成笔尖真值
        val tipXml = mainBrushDefinition(xml)
        val hfade = Regex("""<MaskGenerator\b[^>]*\bhfade="([^"]+)"""").find(tipXml)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
        val vfade = Regex("""<MaskGenerator\b[^>]*\bvfade="([^"]+)"""").find(tipXml)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
        // Krita 的 paintOpFade() 取 hfade/vfade 的较大者，这里保持一致
        val fade = when {
            hfade != null && vfade != null -> maxOf(hfade, vfade)
            else -> hfade ?: vfade
        }
        val softness = Regex("""<param[^>]*name="SoftnessValue"[^>]*>(?:<!\[CDATA\[)?([^<\]]+)""").find(xml)?.groupValues?.getOrNull(1)?.toDoubleOrNull()

        val typeMatch = Regex("""<MaskGenerator\b[^>]*\btype="([^"]+)"""").find(tipXml)
        val tipShape = typeMatch?.groupValues?.getOrNull(1)?.let { if (it.equals("rect", ignoreCase = true)) 1 else 0 }

        val spikes = Regex("""<MaskGenerator\b[^>]*\bspikes="([^"]+)"""").find(tipXml)?.groupValues?.getOrNull(1)?.toIntOrNull()

        val ratio = Regex("""<MaskGenerator\b[^>]*\bratio="([^"]+)"""").find(tipXml)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
            ?: Regex("""<param[^>]*name="RatioValue"[^>]*>(?:<!\[CDATA\[)?([^<\]]+)""").find(xml)?.groupValues?.getOrNull(1)?.toDoubleOrNull()

        val aaMatch = Regex("""<MaskGenerator\b[^>]*\bantialiasEdges="([^"]+)"""").find(tipXml)?.groupValues?.getOrNull(1)
        val antiAliasing = aaMatch?.let { if (it == "0") 0 else 1 }

        val texMatch = Regex("""<param[^>]*name="Texture/Pattern/Enabled"[^>]*>(?:<!\[CDATA\[)?(true|false)""").find(xml)?.groupValues?.getOrNull(1)
        val textureEnabled = texMatch?.toBoolean()

        val texScale = Regex("""<param[^>]*name="Texture/Pattern/Scale"[^>]*>(?:<!\[CDATA\[)?([^<\]]+)""").find(xml)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
        val texStrength = Regex("""<param[^>]*name="Texture/Pattern/Strength"[^>]*>(?:<!\[CDATA\[)?([^<\]]+)""").find(xml)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
        val texModeRaw = Regex("""<param[^>]*name="Texture/Pattern/TexturingMode"[^>]*>(?:<!\[CDATA\[)?([^<\]]+)""").find(xml)?.groupValues?.getOrNull(1)?.trim()
        val textureMode = when (texModeRaw) {
            "0" -> "multiply"
            "1" -> "subtract"
            "4" -> "darken"
            "5" -> "overlay"
            "6" -> "dodge"
            "7" -> "burn"
            "10" -> "hard_light"
            "11" -> "soft_light"
            else -> if (texModeRaw != null) "multiply" else null
        }

        val texPattern = Regex("""<param[^>]*name="Texture/Pattern/(?:PatternFileName|Name)"[^>]*>(?:<!\[CDATA\[)?([^<\]]+)""").find(xml)?.groupValues?.getOrNull(1)?.trim()

        val hasPressureH = Regex("""<param[^>]*name="Pressureh"[^>]*>(?:<!\[CDATA\[)?(true|false)""").find(xml)?.groupValues?.getOrNull(1)?.toBoolean() ?: false
        val hasPressureS = Regex("""<param[^>]*name="Pressures"[^>]*>(?:<!\[CDATA\[)?(true|false)""").find(xml)?.groupValues?.getOrNull(1)?.toBoolean() ?: false
        val hasPressureV = Regex("""<param[^>]*name="Pressurev"[^>]*>(?:<!\[CDATA\[)?(true|false)""").find(xml)?.groupValues?.getOrNull(1)?.toBoolean() ?: false
        val hasPressureMix = Regex("""<param[^>]*name="PressureMix"[^>]*>(?:<!\[CDATA\[)?(true|false)""").find(xml)?.groupValues?.getOrNull(1)?.toBoolean() ?: false

        val hueJitter = if (hasPressureH) Regex("""<param[^>]*name="hValue"[^>]*>(?:<!\[CDATA\[)?([^<\]]+)""").find(xml)?.groupValues?.getOrNull(1)?.toDoubleOrNull() else 0.0
        val satJitter = if (hasPressureS) Regex("""<param[^>]*name="sValue"[^>]*>(?:<!\[CDATA\[)?([^<\]]+)""").find(xml)?.groupValues?.getOrNull(1)?.toDoubleOrNull() else 0.0
        val valJitter = if (hasPressureV) Regex("""<param[^>]*name="vValue"[^>]*>(?:<!\[CDATA\[)?([^<\]]+)""").find(xml)?.groupValues?.getOrNull(1)?.toDoubleOrNull() else 0.0
        val secondaryMix = if (hasPressureMix) Regex("""<param[^>]*name="MixValue"[^>]*>(?:<!\[CDATA\[)?([^<\]]+)""").find(xml)?.groupValues?.getOrNull(1)?.toDoubleOrNull() else 0.0

        // Masking Brush attributes
        val maskingEnabled = Regex("""<param[^>]*name="MaskingBrush/Enabled"[^>]*>(?:<!\[CDATA\[)?(true|false)""").find(xml)?.groupValues?.getOrNull(1)?.toBoolean()
        val maskingCompositeOp = Regex("""<param[^>]*name="MaskingBrush/MaskingCompositeOp"[^>]*>(?:<!\[CDATA\[)?([^<\]]+)""").find(xml)?.groupValues?.getOrNull(1)?.trim()
        val maskingSizeRatio = Regex("""<param[^>]*name="MaskingBrush/MasterSizeCoeff"[^>]*>(?:<!\[CDATA\[)?([^<\]]+)""").find(xml)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
        val maskingSpacing = Regex("""<param[^>]*name="MaskingBrush/Preset/Spacing"[^>]*>(?:<!\[CDATA\[)?([^<\]]+)""").find(xml)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
        val maskingBrushDef = Regex("""<param[^>]*name="MaskingBrush/Preset/brush_definition"[^>]*>(?:<!\[CDATA\[)?(.*?)(?:\]\]>)?\s*</param>""", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.getOrNull(1)
        val maskingTipAsset = maskingBrushDef?.let { Regex("""filename="([^"]+)"""").find(it)?.groupValues?.getOrNull(1) }

        // Sensor attributes
        val rotationSensor = Regex("""<param[^>]*name="RotationSensor"[^>]*>.*?<params\b[^>]*\bid="([^"]+)"""", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.getOrNull(1)
        val scatterSensor = Regex("""<param[^>]*name="ScatterSensor"[^>]*>.*?<params\b[^>]*\bid="([^"]+)"""", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.getOrNull(1)
        val sizeSensor = Regex("""<param[^>]*name="SizeSensor"[^>]*>.*?<params\b[^>]*\bid="([^"]+)"""", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.getOrNull(1)
        val opacitySensor = Regex("""<param[^>]*name="OpacitySensor"[^>]*>.*?<params\b[^>]*\bid="([^"]+)"""", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.getOrNull(1)
        val flowSensor = Regex("""<param[^>]*name="FlowSensor"[^>]*>.*?<params\b[^>]*\bid="([^"]+)"""", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.getOrNull(1)

        val poMatch = Regex("""<param[^>]*name="PressureOpacity"[^>]*>(?:<!\[CDATA\[)?(true|false)""").find(xml)?.groupValues?.getOrNull(1)
        val oucMatch = Regex("""<param[^>]*name="OpacityUseCurve"[^>]*>(?:<!\[CDATA\[)?(true|false)""").find(xml)?.groupValues?.getOrNull(1)
        val hasPressureOpacity = when {
            poMatch != null -> poMatch == "true" && oucMatch != "false"
            oucMatch != null -> oucMatch == "true" && (opacitySensor == null || opacitySensor == "pressure")
            else -> false
        }
        val pressureOpacity = if (hasPressureOpacity) 1.0 else 0.0

        val pfMatch = Regex("""<param[^>]*name="PressureFlow"[^>]*>(?:<!\[CDATA\[)?(true|false)""").find(xml)?.groupValues?.getOrNull(1)
        val fucMatch = Regex("""<param[^>]*name="FlowUseCurve"[^>]*>(?:<!\[CDATA\[)?(true|false)""").find(xml)?.groupValues?.getOrNull(1)
        val hasPressureFlow = when {
            pfMatch != null -> pfMatch == "true" && fucMatch != "false"
            fucMatch != null -> fucMatch == "true" && (flowSensor == null || flowSensor == "pressure")
            else -> false
        }
        val pressureFlow = if (hasPressureFlow) 1.0 else 0.0

        val psMatch = Regex("""<param[^>]*name="PressureSize"[^>]*>(?:<!\[CDATA\[)?(true|false)""").find(xml)?.groupValues?.getOrNull(1)
        val sucMatch = Regex("""<param[^>]*name="SizeUseCurve"[^>]*>(?:<!\[CDATA\[)?(true|false)""").find(xml)?.groupValues?.getOrNull(1)
        val hasPressureSize = when {
            psMatch != null -> psMatch == "true"
            sucMatch != null -> sucMatch == "true" && (sizeSensor == null || sizeSensor == "pressure")
            else -> true
        }
        val pressureSize = if (hasPressureSize) 1.0 else 0.0

        // 动力学曲线原文: Krita 把曲线放在 <Key>Sensor param 的 XML 里, 读回来灌进
        // brushDynamicOptions, 否则 UI 显示的曲线与引擎实际生效的不是同一条。
        val sensorXml = HashMap<String, String>()
        for (key in DYNAMIC_OPTION_KEYS) {
            val m = Regex(
                """<param[^>]*name="${key}Sensor"[^>]*>(?:<!\[CDATA\[)?(.*?)(?:\]\]>)?\s*</param>""",
                RegexOption.DOT_MATCHES_ALL,
            ).find(xml)
            val body = m?.groupValues?.getOrNull(1)?.trim()
            if (!body.isNullOrEmpty()) sensorXml[key] = body
        }

        return KppParsedAttributes(
            fade = fade,
            softness = softness,
            tipShape = tipShape,
            ratio = ratio,
            spikes = spikes,
            antiAliasing = antiAliasing,
            textureEnabled = textureEnabled,
            textureScale = texScale,
            textureStrength = texStrength,
            textureMode = textureMode,
            texturePattern = texPattern,
            hueJitter = hueJitter,
            satJitter = satJitter,
            valJitter = valJitter,
            secondaryMix = secondaryMix,
            maskingEnabled = maskingEnabled,
            maskingCompositeOp = maskingCompositeOp,
            maskingSizeRatio = maskingSizeRatio,
            maskingSpacing = maskingSpacing,
            maskingTipAsset = maskingTipAsset,
            rotationSensor = rotationSensor,
            scatterSensor = scatterSensor,
            sizeSensor = sizeSensor,
            opacitySensor = opacitySensor,
            flowSensor = flowSensor,
            sensorXml = sensorXml,
            pressureSize = pressureSize,
            pressureOpacity = pressureOpacity,
            pressureFlow = pressureFlow,
        )
    }

    /**
     * Injects or updates parameter tags in Krita preset XML.
     *
     * [tipScale] see [updateKppBytes]; `null` = keep the scale already in the file.
     */
    fun injectParamsIntoXml(
        originalXml: String,
        presetName: String,
        params: BrushParams,
        tipScale: Double? = null,
    ): String {
        var xml = originalXml

        // 1. Update <Preset name="..." paintopid="...">
        xml = xml.replace(Regex("""<Preset\s+name="[^"]*""""), """<Preset name="$presetName"""")
        val resolvedPaintOpId = when {
            params.paintOpId.isBlank() || params.paintOpId == "defaultpaintop" -> "paintbrush"
            else -> params.paintOpId
        }
        xml = xml.replace(Regex("""paintopid="[^"]*""""), """paintopid="$resolvedPaintOpId"""")
        xml = updateParam(xml, "paintop", resolvedPaintOpId)

        // 2. Update paintopSize
        xml = updateParam(xml, "paintopSize", params.size.toString())

        // 3. Update Opacity and Flow
        xml = updateParam(xml, "OpacityValue", params.opacity.toString())
        xml = updateParam(xml, "FlowValue", params.flow.toString())

        // 4. Update Spacing
        xml = updateParam(xml, "Spacing", params.spacing.toString())
        // 严禁写 SpacingValue: 那是 KisSpacingOption 的 extraScale 乘数 (Krita 原生恒为 1),
        // 把它写成笔刷间距等于把间距再乘一次 (0.24 -> 有效 0.058), 笔画直接变点状。
        // 与 ReverieCore::setBrushSpacing 的同一约束保持一致。

        // 5. Update Angle & Scatter
        xml = updateParam(xml, "paintopAngle", params.angle.toString())
        xml = updateParam(xml, "AngleValue", params.angle.toString())
        xml = updateParam(xml, "ScatterValue", params.scatter.toString())
        xml = updateParam(xml, "Scatter/strengthValue", params.scatter.toString())
        val hasScatter = params.dynamicsCustomized && params.scatter > 0.001
        xml = updateParam(xml, "PressureScatter", hasScatter.toString())
        xml = updateParam(xml, "Scatter/isChecked", hasScatter.toString())

        // 6. Update Softness, Ratio, Sharpness, Rotation
        // Softness 是 curve option: `KisStandardOption::apply()` 在 isChecked 为 false 时恒返回
        // 1.0(neutral)，只写 SoftnessValue 而不写 PressureSoftness 等于完全没写。
        // 量程 0.1~1.0，1.0 表示不改动笔尖羽化。写法与下方 Scatter 保持一致。
        val softness = params.softness.coerceIn(SOFTNESS_MIN, SOFTNESS_NEUTRAL)
        xml = updateParam(xml, "SoftnessValue", softness.toString())
        xml = updateParam(xml, "PressureSoftness", (softness < SOFTNESS_NEUTRAL - 0.001).toString())
        xml = updateParam(xml, "RatioValue", params.ratio.toString())
        xml = updateParam(xml, "SharpnessValue", params.sharpness.toString())
        xml = updateParam(xml, "RotationValue", params.rotation.toString())

        // 7. Update AntiAliasing
        val aa = params.antiAliasing > 0
        xml = updateParam(xml, "Antialiasing", aa.toString())
        xml = updateParam(xml, "antialiasEdges", aa.toString())

        // 8. Update Airbrush
        xml = updateParam(xml, "AirbrushOption/isAirbrushing", params.airbrush.toString())
        xml = updateParam(xml, "PaintOpSettings/isAirbrushing", params.airbrush.toString())
        xml = updateParam(xml, "AirbrushOption/rate", params.airbrushRate.toString())
        xml = updateParam(xml, "PaintOpSettings/rate", params.airbrushRate.toString())

        // 9. Update Smudge
        xml = updateParam(xml, "ColorRateValue", params.smudgeRate.toString())
        xml = updateParam(xml, "MixValue", params.smudgeRate.toString())
        xml = updateParam(xml, "SmudgeRateValue", params.smudgeLength.toString())

        // 10. Update CompositeOp
        if (params.compositeOp.isNotBlank()) {
            xml = updateParam(xml, "CompositeOp", params.compositeOp)
        }

        // 11. Dynamics
        if (params.dynamicsCustomized) {
            val curveStr = when (params.pressureCurve) {
                1 -> "0,0;0.25,0.5;0.75,0.9;1,1;"
                2 -> "0,0;0.25,0.1;0.75,0.5;1,1;"
                3 -> "0,0;0.25,0.1;0.75,0.9;1,1;"
                else -> "0,0;1,1;"
            }
            val sensorXml = """<!DOCTYPE params><params id="pressure"><curve>$curveStr</curve></params>"""
            val sizeSensorId = params.sizeSensor.ifBlank { "pressure" }
            val opacitySensorId = params.opacitySensor.ifBlank { "pressure" }
            val flowSensorId = params.flowSensor.ifBlank { "pressure" }

            val useSize = params.pressureEnabled && (params.pressureSize > 0.001)
            xml = updateParam(xml, "PressureSize", useSize.toString())
            xml = updateParam(xml, "SizeUseCurve", useSize.toString())
            xml = updateParam(xml, "SizeValue", params.pressureSize.toString())
            if (useSize) {
                if (params.speedSize > 0.001 && sizeSensorId == "pressure") {
                    val multiSensorXml = """<!DOCTYPE params><params id="sensorslist"><ChildSensor id="pressure"><curve>$curveStr</curve></ChildSensor><ChildSensor id="speed"/></params>"""
                    xml = updateParam(xml, "SizeSensor", multiSensorXml)
                } else {
                    val sizeSensorXml = """<!DOCTYPE params><params id="$sizeSensorId"><curve>$curveStr</curve></params>"""
                    xml = updateParam(xml, "SizeSensor", sizeSensorXml)
                }
            }

            val useOpacity = params.pressureEnabled && (params.pressureOpacity > 0.001)
            xml = updateParam(xml, "PressureOpacity", useOpacity.toString())
            xml = updateParam(xml, "OpacityUseCurve", useOpacity.toString())
            if (useOpacity) {
                val opacitySensorXml = """<!DOCTYPE params><params id="$opacitySensorId"><curve>$curveStr</curve></params>"""
                xml = updateParam(xml, "OpacitySensor", opacitySensorXml)
            }

            val useFlow = params.pressureEnabled && (params.pressureFlow > 0.001)
            xml = updateParam(xml, "PressureFlow", useFlow.toString())
            xml = updateParam(xml, "FlowUseCurve", useFlow.toString())
            if (useFlow) {
                val flowSensorXml = """<!DOCTYPE params><params id="$flowSensorId"><curve>$curveStr</curve></params>"""
                xml = updateParam(xml, "FlowSensor", flowSensorXml)
            }
        }

        // 12. Update Texture
        xml = updateParam(xml, "Texture/Pattern/Enabled", params.textureEnabled.toString())
        xml = updateParam(xml, "PressureTexture/Strength/", params.textureEnabled.toString())
        xml = updateParam(xml, "Texture/Pattern/Scale", params.textureScale.toString())
        xml = updateParam(xml, "Texture/Pattern/Strength", params.textureStrength.toString())
        val texModeCode = when (params.textureMode.lowercase()) {
            "subtract" -> "1"
            "darken" -> "4"
            "overlay" -> "5"
            "dodge" -> "6"
            "burn" -> "7"
            "hard_light" -> "10"
            "soft_light" -> "11"
            else -> "0"
        }
        xml = updateParam(xml, "Texture/Pattern/TexturingMode", texModeCode)
        if (params.texturePattern.isNotBlank()) {
            xml = updateParam(xml, "Texture/Pattern/PatternFileName", params.texturePattern)
            xml = updateParam(xml, "Texture/Pattern/Name", params.texturePattern)
        }

        // 13. Update Color Dynamics (HSV Jitters & Mix)
        val hasHue = params.hueJitter > 0.001
        xml = updateParam(xml, "Pressureh", hasHue.toString())
        xml = updateParam(xml, "hValue", params.hueJitter.toString())
        if (!originalXml.contains("""name="hSensor"""")) {
            xml = updateParam(xml, "Customh", "true")
            xml = updateParam(xml, "Curveh", "0,0;1,1;")
            xml = updateParam(xml, "hUseCurve", "true")
            xml = updateParam(xml, "hUseSameCurve", "true")
            if (hasHue) {
                xml = updateParam(xml, "hSensor", """<!DOCTYPE params><params id="fuzzy"><curve>0,0;1,1;</curve></params>""")
            }
        }

        val hasSat = params.satJitter > 0.001
        xml = updateParam(xml, "Pressures", hasSat.toString())
        xml = updateParam(xml, "sValue", params.satJitter.toString())
        if (!originalXml.contains("""name="sSensor"""")) {
            xml = updateParam(xml, "Customs", "true")
            xml = updateParam(xml, "Curves", "0,0;1,1;")
            xml = updateParam(xml, "sUseCurve", "true")
            xml = updateParam(xml, "sUseSameCurve", "true")
            if (hasSat) {
                xml = updateParam(xml, "sSensor", """<!DOCTYPE params><params id="fuzzy"><curve>0,0;1,1;</curve></params>""")
            }
        }

        val hasVal = params.valJitter > 0.001
        xml = updateParam(xml, "Pressurev", hasVal.toString())
        xml = updateParam(xml, "vValue", params.valJitter.toString())
        if (!originalXml.contains("""name="vSensor"""")) {
            xml = updateParam(xml, "Customv", "true")
            xml = updateParam(xml, "Curvev", "0,0;1,1;")
            xml = updateParam(xml, "vUseCurve", "true")
            xml = updateParam(xml, "vUseSameCurve", "true")
            if (hasVal) {
                xml = updateParam(xml, "vSensor", """<!DOCTYPE params><params id="fuzzy"><curve>0,0;1,1;</curve></params>""")
            }
        }

        val hasMix = params.secondaryMix > 0.001 || params.pressureColorMix
        xml = updateParam(xml, "PressureMix", hasMix.toString())
        xml = updateParam(xml, "MixValue", params.secondaryMix.toString())
        if (!originalXml.contains("""name="MixSensor"""")) {
            xml = updateParam(xml, "CurveMix", "0,0;1,1;")
            xml = updateParam(xml, "CustomMix", "true")
            xml = updateParam(xml, "MixUseCurve", "true")
            xml = updateParam(xml, "MixUseSameCurve", "true")
            if (hasMix) {
                val mixSensorId = if (params.pressureColorMix) "pressure" else "fuzzy"
                xml = updateParam(xml, "MixSensor", """<!DOCTYPE params><params id="$mixSensorId"><curve>0,0;1,1;</curve></params>""")
            }
        }

        // 14. Update Mirror & Rotation dynamics & Scatter sensor
        xml = updateParam(xml, "HorizontalMirrorEnabled", params.randomFlipX.toString())
        xml = updateParam(xml, "VerticalMirrorEnabled", params.randomFlipY.toString())
        xml = updateParam(xml, "PressureMirror", (params.randomFlipX || params.randomFlipY).toString())
        val useRotation = params.followDirection || params.rotationSensor.isNotBlank()
        xml = updateParam(xml, "PressureRotation", useRotation.toString())
        if (useRotation) {
            val rotSensorId = if (params.followDirection) "drawingangle" else params.rotationSensor.ifBlank { "drawingangle" }
            xml = updateParam(xml, "RotationSensor", """<!DOCTYPE params><params id="$rotSensorId"><curve>0,0;1,1;</curve></params>""")
        }
        if (hasScatter) {
            val scatSensorId = params.scatterSensor.ifBlank { "fuzzy" }
            xml = updateParam(xml, "ScatterSensor", """<!DOCTYPE params><params id="$scatSensorId"><curve>0,0;1,1;</curve></params>""")
        }

        // 15. Fade 没有独立参数键: Krita 的 Fade 就是 brush_definition 里 MaskGenerator 的
        //     hfade/vfade（KisPaintOpSettings::setPaintOpFade 写的就是它们），
        //     在第 16 步随笔尖定义一起同步，这里不再写 PressureFade / FadeValue 这类不存在的键。

        // 16. Update tipAsset & brush_definition (including MaskGenerator attributes)
        val tipTypeAttr = if (params.tipShape == 1) "rect" else "circle"
        val fadeVal = params.fade.coerceIn(0.0, 1.0)
        val aaVal = if (params.antiAliasing > 0) 1 else 0
        val spikesVal = params.spikes.coerceAtLeast(2)

        val brushAngleRad = String.format(java.util.Locale.US, "%.5f", Math.toRadians(params.angle))
        if (params.tipAsset.isNotBlank()) {
            val tipFile = params.tipAsset
            val ext = tipFile.substringAfterLast(".").lowercase()
            val tipType = when (ext) {
                "gbr" -> "gbr_brush"
                "gih" -> "image_pipe_brush"
                "svg" -> "svg_brush"
                else -> "png_brush"
            }
            // `scale` is what the engine actually reports as the brush size (see formatScale).
            // When the caller knows the right value (ABR import) we use it; otherwise we keep
            // whatever the file already had instead of forcing it back to 1.
            val scaleAttr = tipScale?.let { formatScale(it) } ?: existingBrushScale(xml)
            val brushDef = """<param type="string" name="brush_definition"><![CDATA[<Brush scale="$scaleAttr" type="$tipType" useAutoSpacing="0" BrushVersion="2" filename="$tipFile" spacing="${params.spacing}" angle="$brushAngleRad" brushApplication="0"/> ]]></param>"""
            if (mainBrushDefPattern.containsMatchIn(xml)) {
                xml = mainBrushDefPattern.replace(xml, brushDef)
            } else {
                xml = xml.replace("</Preset>", " $brushDef\n</Preset>")
            }
            xml = updateParam(xml, "requiredBrushFile", tipFile)
            xml = updateParam(xml, "requiredBrushFilesList", tipFile)
        } else if (mainBrushDefPattern.containsMatchIn(xml)) {
            // Retain original tip or auto_brush, and sync spacing/angle/MaskGenerator attrs.
            // 只动主 brush_definition 的 CDATA —— 文档序里排在它前面的
            // MaskingBrush/Preset/brush_definition 是掩膜子预设副本，改它引擎完全无感。
            val m = mainBrushDefPattern.find(xml)!!
            val inner = m.groupValues[1]
            var newInner = inner.replace(Regex("""<Brush\b([^>]*)>""")) { bm ->
                var attrs = bm.groupValues[1]
                attrs = if (attrs.contains("spacing=")) {
                    attrs.replace(Regex("""spacing="[^"]*""""), """spacing="${params.spacing}"""")
                } else {
                    """$attrs spacing="${params.spacing}""""
                }
                attrs = if (attrs.contains("angle=")) {
                    attrs.replace(Regex("""angle="[^"]*""""), """angle="$brushAngleRad"""")
                } else {
                    """$attrs angle="$brushAngleRad""""
                }
                "<Brush$attrs>"
            }
            if (newInner.contains("<MaskGenerator") || newInner.contains("""type="auto_brush"""")) {
                newInner = updateOrInsertMaskGenerator(newInner, params)
            }
            xml = xml.replaceRange(m.range, """<param type="string" name="brush_definition"><![CDATA[$newInner]]></param>""")
        } else {
            // No brush_definition at all, insert auto_brush
            val autoDef = """<param type="string" name="brush_definition"><![CDATA[<Brush scale="1" type="auto_brush" BrushVersion="2" spacing="${params.spacing}" angle="$brushAngleRad"> <MaskGenerator diameter="${params.size}" hfade="$fadeVal" vfade="$fadeVal" id="default" spikes="$spikesVal" type="$tipTypeAttr" ratio="${params.ratio}" antialiasEdges="$aaVal"/> </Brush> ]]></param>"""
            xml = xml.replace("</Preset>", " $autoDef\n</Preset>")
        }

        // 16.5 动力学曲线: Krita 把曲线存进 <Key>Sensor param 的 XML 里
        // (<!DOCTYPE params><params id="pressure"><curve>...</curve></params>),
        // 由 <Key>UseCurve 决定是否启用。原先这里什么都不写, 曲线编辑只活在内存里。
        for ((key, sensorXml) in params.dynamicOptions) {
            if (key.isBlank() || sensorXml.isBlank()) continue
            xml = updateParam(xml, "${key}Sensor", sensorXml)
            xml = updateParam(xml, "${key}UseCurve", "true")
        }

        // 17. Masking Brush (双重画笔/蒙版画笔)
        xml = updateParam(xml, "MaskingBrush/Enabled", params.maskingEnabled.toString())
        if (params.maskingEnabled) {
            xml = updateParam(xml, "MaskingBrush/MaskingCompositeOp", params.maskingCompositeOp)
            xml = updateParam(xml, "MaskingBrush/UseMasterSize", "true")
            xml = updateParam(xml, "MaskingBrush/MasterSizeCoeff", params.maskingSizeRatio.toString())
            xml = updateParam(xml, "MaskingBrush/Preset/Spacing", params.maskingSpacing.toString())
            xml = updateParam(xml, "MaskingBrush/Preset/paintopSize", (params.size * params.maskingSizeRatio).toString())

            val maskTipDef = if (params.maskingTipAsset.isNotBlank()) {
                val ext = params.maskingTipAsset.substringAfterLast(".").lowercase()
                val tipType = when (ext) {
                    "gbr" -> "gbr_brush"
                    "gih" -> "image_pipe_brush"
                    else -> "png_brush"
                }
                val maskFade = params.maskingFade.coerceIn(0.0, 1.0)
                val maskTipShape = if (params.maskingTipShape == 1) "rect" else "circle"
                // 采样笔尖同样要挂 MaskGenerator: 蒙版淡出/柔和就落在这里的
                // hfade/vfade。缺了它, 选了自定义蒙版笔尖时这两个滑块会"改了没变化"
                // (fade 写不进预设, 重载后被打回原值)。
                """<Brush scale="1" type="$tipType" useAutoSpacing="0" BrushVersion="2" filename="${params.maskingTipAsset}" spacing="${params.maskingSpacing}" angle="0.0" brushApplication="0"> <MaskGenerator diameter="${params.size * params.maskingSizeRatio}" hfade="$maskFade" vfade="$maskFade" id="default" spikes="2" type="$maskTipShape" ratio="1.0" antialiasEdges="1"/> </Brush>"""
            } else {
                val maskTipShape = if (params.maskingTipShape == 1) "rect" else "circle"
                val maskFade = params.maskingFade.coerceIn(0.0, 1.0)
                """<Brush scale="1" type="auto_brush" BrushVersion="2" spacing="${params.maskingSpacing}" angle="0.0"> <MaskGenerator diameter="${params.size * params.maskingSizeRatio}" hfade="$maskFade" vfade="$maskFade" id="default" spikes="2" type="$maskTipShape" ratio="1.0" antialiasEdges="1"/> </Brush>"""
            }
            xml = updateParam(xml, "MaskingBrush/Preset/brush_definition", maskTipDef)
        }

        return xml
    }

    private fun updateOrInsertMaskGenerator(xml: String, params: BrushParams): String {
        val tipType = if (params.tipShape == 1) "rect" else "circle"
        val fadeVal = params.fade.coerceIn(0.0, 1.0).toString()
        val aaVal = if (params.antiAliasing > 0) "1" else "0"
        val spikesVal = params.spikes.coerceAtLeast(2).toString()
        val ratioVal = params.ratio.toString()
        val sizeVal = params.size.toString()

        // MaskGenerator/@id 是笔尖类型（default / soft / gauss），由预设自身决定；
        // 这里刻意不按羽化数值去改写它，否则拖动 Fade 会把用户选的笔尖类型换掉。
        val maskGenRegex = Regex("""<MaskGenerator\b([^>]*)/>""")
        val m = maskGenRegex.find(xml)
        if (m != null) {
            var attrs = m.groupValues[1]
            fun replAttr(text: String, name: String, value: String): String {
                val p = Regex("""\b$name="[^"]*"""")
                return if (p.containsMatchIn(text)) {
                    text.replace(p, """$name="$value"""")
                } else {
                    """$text $name="$value""""
                }
            }
            attrs = replAttr(attrs, "type", tipType)
            attrs = replAttr(attrs, "hfade", fadeVal)
            attrs = replAttr(attrs, "vfade", fadeVal)
            attrs = replAttr(attrs, "ratio", ratioVal)
            attrs = replAttr(attrs, "spikes", spikesVal)
            attrs = replAttr(attrs, "antialiasEdges", aaVal)
            attrs = replAttr(attrs, "diameter", sizeVal)
            return xml.replaceRange(m.range, "<MaskGenerator$attrs/>")
        } else {
            val brushClose = "</Brush>"
            if (xml.contains(brushClose)) {
                val newGen = """ <MaskGenerator diameter="$sizeVal" hfade="$fadeVal" vfade="$fadeVal" id="default" spikes="$spikesVal" type="$tipType" ratio="$ratioVal" antialiasEdges="$aaVal"/> $brushClose"""
                return xml.replaceFirst(brushClose, newGen)
            }
            return xml
        }
    }

    private fun updateParam(xml: String, paramName: String, value: String): String {
        // Krita 写出的参数是 <param name="X" type="string">（name 在前），旧版本甚至没有 type。
        // 这里绝不能假设属性顺序：正则一旦匹配不上，修改就会被追加成重复键写到文件尾部，
        // 而引擎按文档序"后者覆盖前者"解析，谁生效完全看追加顺序 —— 表现就是"改了没反应"。
        val canonical = """<param type="string" name="$paramName"><![CDATA[$value]]></param>"""
        val pattern = paramPattern(paramName)
        val matches = pattern.findAll(xml).toList()
        if (matches.isEmpty()) {
            return xml.replace("</Preset>", " $canonical\n</Preset>")
        }
        val sb = StringBuilder(xml)
        // 从后往前替换：第一个同名参数就地收敛为规范写法，其余重复键全部删除
        for (i in matches.indices.reversed()) {
            val range = matches[i].range
            sb.replace(range.first, range.last + 1, if (i == 0) canonical else "")
        }
        return sb.toString()
    }

    private fun paramPattern(paramName: String): Regex =
        Regex("""<param\b[^>]*\bname="${Regex.escape(paramName)}"[^>]*>.*?</param>""", RegexOption.DOT_MATCHES_ALL)

    private fun buildMinimalPresetXml(
        presetName: String,
        params: BrushParams,
        tipScale: Double? = null,
    ): String {
        val resolvedOpId = when {
            params.paintOpId.isBlank() || params.paintOpId == "defaultpaintop" -> "paintbrush"
            else -> params.paintOpId
        }
        val tipShapeType = if (params.tipShape == 1) "rect" else "circle"
        val fadeVal = params.fade.coerceIn(0.0, 1.0)
        val aaVal = if (params.antiAliasing > 0) 1 else 0
        val spikesVal = params.spikes.coerceAtLeast(2)

        val brushAngleRad = String.format(java.util.Locale.US, "%.5f", Math.toRadians(params.angle))
        val tipDef = if (params.tipAsset.isNotBlank()) {
            val ext = params.tipAsset.substringAfterLast(".").lowercase()
            val tipType = if (ext == "gbr") "gbr_brush" else "png_brush"
            // Freshly built XML has no previous scale to preserve, so fall back to 1.
            val scaleAttr = tipScale?.let { formatScale(it) } ?: "1"
            """<param type="string" name="brush_definition"><![CDATA[<Brush scale="$scaleAttr" type="$tipType" useAutoSpacing="0" BrushVersion="2" filename="${params.tipAsset}" spacing="${params.spacing}" angle="$brushAngleRad" brushApplication="0"/> ]]></param>
  <param type="string" name="requiredBrushFile"><![CDATA[${params.tipAsset}]]></param>
  <param type="string" name="requiredBrushFilesList"><![CDATA[${params.tipAsset}]]></param>"""
        } else {
            """<param type="string" name="brush_definition"><![CDATA[<Brush scale="1" type="auto_brush" BrushVersion="2" spacing="${params.spacing}" angle="$brushAngleRad"> <MaskGenerator diameter="${params.size}" hfade="$fadeVal" vfade="$fadeVal" id="default" spikes="$spikesVal" type="$tipShapeType" ratio="${params.ratio}" antialiasEdges="$aaVal"/> </Brush> ]]></param>"""
        }

        val useSize = params.pressureEnabled && (params.pressureSize > 0.001)
        val useOpacity = params.pressureEnabled && (params.pressureOpacity > 0.001)
        val useFlow = params.pressureEnabled && (params.pressureFlow > 0.001)
        val hasScatter = params.scatter > 0.001
        val useRotation = params.followDirection || params.rotationSensor.isNotBlank()

        return """<?xml version="1.0" encoding="UTF-8"?>
<Preset name="$presetName" paintopid="$resolvedOpId">
  <param type="string" name="paintopSize"><![CDATA[${params.size}]]></param>
  <param type="string" name="OpacityValue"><![CDATA[${params.opacity}]]></param>
  <param type="string" name="FlowValue"><![CDATA[${params.flow}]]></param>
  <param type="string" name="Spacing"><![CDATA[${params.spacing}]]></param>
  <param type="string" name="paintopAngle"><![CDATA[${params.angle}]]></param>
  <param type="string" name="AngleValue"><![CDATA[${params.angle}]]></param>
  <param type="string" name="ScatterValue"><![CDATA[${params.scatter}]]></param>
  <param type="string" name="SoftnessValue"><![CDATA[${params.softness.coerceIn(SOFTNESS_MIN, SOFTNESS_NEUTRAL)}]]></param>
  <param type="string" name="PressureSoftness"><![CDATA[${params.softness.coerceIn(SOFTNESS_MIN, SOFTNESS_NEUTRAL) < SOFTNESS_NEUTRAL - 0.001}]]></param>
  <param type="string" name="RatioValue"><![CDATA[${params.ratio}]]></param>
  <param type="string" name="SharpnessValue"><![CDATA[${params.sharpness}]]></param>
  <param type="string" name="RotationValue"><![CDATA[${params.rotation}]]></param>
  <param type="string" name="CompositeOp"><![CDATA[${params.compositeOp}]]></param>
  <param type="string" name="AirbrushOption/isAirbrushing"><![CDATA[${params.airbrush}]]></param>
  <param type="string" name="AirbrushOption/rate"><![CDATA[${params.airbrushRate}]]></param>
  <param type="string" name="ColorRateValue"><![CDATA[${params.smudgeRate}]]></param>
  <param type="string" name="SmudgeRateValue"><![CDATA[${params.smudgeLength}]]></param>
  <param type="string" name="PressureSize"><![CDATA[$useSize]]></param>
  <param type="string" name="SizeUseCurve"><![CDATA[$useSize]]></param>
  <param type="string" name="SizeValue"><![CDATA[${params.pressureSize}]]></param>
  <param type="string" name="PressureOpacity"><![CDATA[$useOpacity]]></param>
  <param type="string" name="OpacityUseCurve"><![CDATA[$useOpacity]]></param>
  <param type="string" name="PressureFlow"><![CDATA[$useFlow]]></param>
  <param type="string" name="FlowUseCurve"><![CDATA[$useFlow]]></param>
  <param type="string" name="PressureScatter"><![CDATA[$hasScatter]]></param>
  <param type="string" name="Scatter/isChecked"><![CDATA[$hasScatter]]></param>
  <param type="string" name="PressureRotation"><![CDATA[$useRotation]]></param>
  <param type="string" name="HorizontalMirrorEnabled"><![CDATA[${params.randomFlipX}]]></param>
  <param type="string" name="VerticalMirrorEnabled"><![CDATA[${params.randomFlipY}]]></param>
  <param type="string" name="PressureMirror"><![CDATA[${params.randomFlipX || params.randomFlipY}]]></param>
  <param type="string" name="Antialiasing"><![CDATA[${params.antiAliasing > 0}]]></param>
  <param type="string" name="antialiasEdges"><![CDATA[${params.antiAliasing > 0}]]></param>
  $tipDef
</Preset>"""
    }
}
