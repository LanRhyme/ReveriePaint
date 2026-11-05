package com.reverie.paint.core

import java.io.ByteArrayOutputStream

object KppHelper {
    /**
     * Reads the preset XML text from a .kpp file (a PNG with a preset text chunk; see [PngChunks]).
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
        val payload = PngChunks.readText(kppBytes, "preset") ?: return null
        return presetPayloadText(payload, stripEmbeddedResources)
    }

    /** Materializes the preset chunk payload as UTF-8 text, optionally dropping embedded payloads. */
    private fun presetPayloadText(raw: ByteArray, strip: Boolean): String =
        String(if (strip) stripEmbeddedResourcePayloads(raw) else raw, Charsets.UTF_8)

    private val CDATA_OPEN = "<![CDATA[".toByteArray(Charsets.US_ASCII)
    private val CDATA_CLOSE = "]]>".toByteArray(Charsets.US_ASCII)
    private val RESOURCE_OPEN = "<resource".toByteArray(Charsets.US_ASCII)
    private val TAG_END = ">".toByteArray(Charsets.US_ASCII)

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
        val out = ByteArrayOutputStream(minOf(src.size, 64 * 1024))
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
                    out.write(src, copiedUpTo, payloadStart - copiedUpTo)
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

    private fun hasEmbeddedPayload(src: ByteArray): Boolean {
        var searchFrom = indexOfBytes(src, 0, RESOURCE_OPEN)
        while (searchFrom >= 0) {
            val tagEnd = indexOfBytes(src, searchFrom, TAG_END)
            if (tagEnd < 0) break
            var payloadStart = tagEnd + 1
            while (payloadStart < src.size && isXmlSpace(src[payloadStart])) payloadStart++
            if (indexOfBytes(src, payloadStart, CDATA_OPEN) == payloadStart) {
                val bodyStart = payloadStart + CDATA_OPEN.size
                val bodyEnd = indexOfBytes(src, bodyStart, CDATA_CLOSE)
                if (bodyEnd > bodyStart) return true
            }
            searchFrom = indexOfBytes(src, tagEnd + 1, RESOURCE_OPEN)
        }
        return false
    }

    /** Checks if the preset chunk contains non-empty embedded resource payloads (<resource ...><![CDATA[...]]></resource>). */
    fun hasEmbeddedResources(kppBytes: ByteArray): Boolean {
        val payload = PngChunks.readText(kppBytes, "preset") ?: return false
        return hasEmbeddedPayload(payload)
    }

    /**
     * Strips base64 payload from embedded resources inside the preset XML chunk.
     * If the preset doesn't contain embedded resources, returns [kppBytes] directly with 0 copy.
     */
    fun stripKpp(kppBytes: ByteArray): ByteArray {
        val payload = PngChunks.readText(kppBytes, "preset") ?: return kppBytes
        if (!hasEmbeddedPayload(payload)) return kppBytes
        val strippedXml = presetPayloadText(payload, strip = true)
        return replacePresetXml(kppBytes, strippedXml)
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

    /** 用新的 preset XML 替换 .kpp 里的 preset 文本块(写成 iTXt/UTF-8), PNG 其余 chunk 原样保留。 */
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
    internal fun replacePresetXml(kppBytes: ByteArray, newXml: String): ByteArray {
        // Reuse the version already present in the base image (tEXt / zTXt / iTXt); only fall
        // back to the default when the base is a generated PNG that has no text chunk at all.
        val version = PngChunks.readText(kppBytes, "version")?.toString(Charsets.UTF_8)?.takeIf { it.isNotBlank() }
        return PngChunks.replaceTexts(
            kppBytes,
            drop = setOf("preset", "version"),
            add = listOf(
                PngChunks.text("version", (version ?: PRESET_XML_VERSION).toByteArray(Charsets.ISO_8859_1)),
                PngChunks.itxt("preset", newXml),
            ),
        )
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

    /** fade(实心率) 的"锐利"端值，也是未自定义笔刷的默认落点 */
    const val FADE_SOLID = 1.0
}
