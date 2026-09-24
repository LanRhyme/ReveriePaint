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
     */
    fun readPresetXml(kppBytes: ByteArray): String? {
        if (kppBytes.size < 8) return null
        for (i in 0 until 8) {
            if (kppBytes[i] != PNG_HEADER[i]) return null
        }
        var idx = 8
        while (idx + 12 <= kppBytes.size) {
            val length = ByteBuffer.wrap(kppBytes, idx, 4).order(ByteOrder.BIG_ENDIAN).int
            val chunkType = String(kppBytes, idx + 4, 4, Charsets.ISO_8859_1)
            val chunkDataStart = idx + 8
            val chunkDataEnd = chunkDataStart + length
            if (chunkDataEnd + 4 > kppBytes.size || length < 0) break

            if (chunkType == "zTXt") {
                var nullPos = -1
                for (p in chunkDataStart until chunkDataEnd) {
                    if (kppBytes[p] == 0.toByte()) {
                        nullPos = p
                        break
                    }
                }
                if (nullPos != -1 && nullPos + 2 <= chunkDataEnd) {
                    val keyword = String(kppBytes, chunkDataStart, nullPos - chunkDataStart, Charsets.ISO_8859_1)
                    if (keyword == "preset") {
                        val cmethod = kppBytes[nullPos + 1].toInt()
                        if (cmethod == 0) {
                            val compressedStart = nullPos + 2
                            val compressedLen = chunkDataEnd - compressedStart
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
                            return bos.toString(Charsets.UTF_8.name())
                        }
                    }
                }
            }
            idx += 12 + length
        }
        return null
    }

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
     * Updates an existing .kpp file (or bytes) by modifying parameters in its preset XML.
     * Returns a new byte array with the updated zTXt chunk.
     */
    fun updateKppBytes(
        kppBytes: ByteArray,
        presetName: String,
        params: BrushParams,
    ): ByteArray {
        if (kppBytes.size < 8) return kppBytes
        for (i in 0 until 8) {
            if (kppBytes[i] != PNG_HEADER[i]) return kppBytes
        }

        val originalXml = readPresetXml(kppBytes)
        val newXml = if (originalXml != null) {
            injectParamsIntoXml(originalXml, presetName, params)
        } else {
            buildMinimalPresetXml(presetName, params)
        }

        // Recompress XML
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION)
        val xmlBytes = newXml.toByteArray(Charsets.UTF_8)
        deflater.setInput(xmlBytes)
        deflater.finish()
        val deflatedOut = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (!deflater.finished()) {
            val count = deflater.deflate(buf)
            deflatedOut.write(buf, 0, count)
        }
        deflater.end()
        val compressed = deflatedOut.toByteArray()

        // Build new zTXt chunk data
        val keywordBytes = "preset".toByteArray(Charsets.ISO_8859_1)
        val newChunkData = ByteArray(keywordBytes.size + 2 + compressed.size)
        System.arraycopy(keywordBytes, 0, newChunkData, 0, keywordBytes.size)
        newChunkData[keywordBytes.size] = 0 // null separator
        newChunkData[keywordBytes.size + 1] = 0 // Deflate method
        System.arraycopy(compressed, 0, newChunkData, keywordBytes.size + 2, compressed.size)

        // CRC of chunk type + chunk data
        val crcCalculator = CRC32()
        crcCalculator.update("zTXt".toByteArray(Charsets.ISO_8859_1))
        crcCalculator.update(newChunkData)
        val crcVal = crcCalculator.value.toInt()

        val out = ByteArrayOutputStream()
        out.write(PNG_HEADER)

        var idx = 8
        var replaced = false
        while (idx + 12 <= kppBytes.size) {
            val length = ByteBuffer.wrap(kppBytes, idx, 4).order(ByteOrder.BIG_ENDIAN).int
            val chunkType = String(kppBytes, idx + 4, 4, Charsets.ISO_8859_1)
            val chunkDataStart = idx + 8
            val chunkDataEnd = chunkDataStart + length
            if (chunkDataEnd + 4 > kppBytes.size || length < 0) break

            var isPresetZtxt = false
            if (chunkType == "zTXt") {
                var nullPos = -1
                for (p in chunkDataStart until chunkDataEnd) {
                    if (kppBytes[p] == 0.toByte()) {
                        nullPos = p
                        break
                    }
                }
                if (nullPos != -1) {
                    val keyword = String(kppBytes, chunkDataStart, nullPos - chunkDataStart, Charsets.ISO_8859_1)
                    if (keyword == "preset") {
                        isPresetZtxt = true
                    }
                }
            }

            if (isPresetZtxt) {
                val lenBuf = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(newChunkData.size).array()
                out.write(lenBuf)
                out.write("zTXt".toByteArray(Charsets.ISO_8859_1))
                out.write(newChunkData)
                val crcBuf = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(crcVal).array()
                out.write(crcBuf)
                replaced = true
            } else {
                if (chunkType == "IEND" && !replaced) {
                    val lenBuf = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(newChunkData.size).array()
                    out.write(lenBuf)
                    out.write("zTXt".toByteArray(Charsets.ISO_8859_1))
                    out.write(newChunkData)
                    val crcBuf = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(crcVal).array()
                    out.write(crcBuf)
                    replaced = true
                }
                out.write(kppBytes, idx, 12 + length)
            }
            idx += 12 + length
        }

        return out.toByteArray()
    }

    /**
     * Injects or updates parameter tags in Krita preset XML.
     */
    fun injectParamsIntoXml(originalXml: String, presetName: String, params: BrushParams): String {
        var xml = originalXml

        // 1. Update <Preset name="..." paintopid="...">
        xml = xml.replace(Regex("""<Preset\s+name="[^"]*""""), """<Preset name="$presetName"""")
        if (params.paintOpId.isNotBlank() && params.paintOpId != "defaultpaintop") {
            xml = xml.replace(Regex("""paintopid="[^"]*""""), """paintopid="${params.paintOpId}"""")
            xml = updateParam(xml, "paintop", params.paintOpId)
        }

        // 2. Update paintopSize
        xml = updateParam(xml, "paintopSize", params.size.toString())

        // 3. Update Opacity and Flow
        xml = updateParam(xml, "OpacityValue", params.opacity.toString())
        xml = updateParam(xml, "FlowValue", params.flow.toString())

        // 4. Update Spacing
        xml = updateParam(xml, "Spacing", params.spacing.toString())

        // 5. Update Airbrush
        xml = updateParam(xml, "AirbrushOption/isAirbrushing", params.airbrush.toString())
        xml = updateParam(xml, "AirbrushOption/rate", params.airbrushRate.toString())

        // 6. Update Smudge
        xml = updateParam(xml, "ColorRateValue", params.smudgeRate.toString())
        xml = updateParam(xml, "SmudgeRateValue", params.smudgeLength.toString())

        // 7. Update CompositeOp
        if (params.compositeOp.isNotBlank()) {
            xml = updateParam(xml, "CompositeOp", params.compositeOp)
        }

        // 8. Update tipAsset
        if (params.tipAsset.isNotBlank()) {
            val tipFile = params.tipAsset
            val ext = tipFile.substringAfterLast(".").lowercase()
            val tipType = when (ext) {
                "gbr" -> "gbr_brush"
                "gih" -> "image_pipe_brush"
                "svg" -> "svg_brush"
                else -> "png_brush"
            }
            val brushDef = """<param type="string" name="brush_definition"><![CDATA[<Brush scale="1" type="$tipType" useAutoSpacing="0" BrushVersion="2" filename="$tipFile" spacing="${params.spacing}" angle="${params.angle}"/> ]]></param>"""
            if (xml.contains("""name="brush_definition"""")) {
                xml = xml.replace(Regex("""<param[^>]*name="brush_definition".*?</param>""", RegexOption.DOT_MATCHES_ALL), brushDef)
            } else {
                xml = xml.replace("</Preset>", " $brushDef\n</Preset>")
            }
        }

        return xml
    }

    private fun updateParam(xml: String, paramName: String, value: String): String {
        val pattern = Regex("""<param\s+type="[^"]*"\s+name="$paramName">.*?</param>""", RegexOption.DOT_MATCHES_ALL)
        val replacement = """<param type="string" name="$paramName"><![CDATA[$value]]></param>"""
        return if (pattern.containsMatchIn(xml)) {
            xml.replace(pattern, replacement)
        } else {
            xml.replace("</Preset>", " $replacement\n</Preset>")
        }
    }

    private fun buildMinimalPresetXml(presetName: String, params: BrushParams): String {
        val tipDef = if (params.tipAsset.isNotBlank()) {
            val ext = params.tipAsset.substringAfterLast(".").lowercase()
            val tipType = if (ext == "gbr") "gbr_brush" else "png_brush"
            """<param type="string" name="brush_definition"><![CDATA[<Brush scale="1" type="$tipType" useAutoSpacing="0" BrushVersion="2" filename="${params.tipAsset}" spacing="${params.spacing}" angle="${params.angle}"/> ]]></param>"""
        } else {
            """<param type="string" name="brush_definition"><![CDATA[<Brush scale="1" type="auto_brush" spacing="${params.spacing}" angle="${params.angle}"/> ]]></param>"""
        }

        return """<?xml version="1.0" encoding="UTF-8"?>
<Preset name="$presetName" paintopid="${if (params.paintOpId.isNotBlank()) params.paintOpId else "paintbrush"}">
  <param type="string" name="paintopSize"><![CDATA[${params.size}]]></param>
  <param type="string" name="OpacityValue"><![CDATA[${params.opacity}]]></param>
  <param type="string" name="FlowValue"><![CDATA[${params.flow}]]></param>
  <param type="string" name="Spacing"><![CDATA[${params.spacing}]]></param>
  <param type="string" name="CompositeOp"><![CDATA[${params.compositeOp}]]></param>
  <param type="string" name="AirbrushOption/isAirbrushing"><![CDATA[${params.airbrush}]]></param>
  <param type="string" name="AirbrushOption/rate"><![CDATA[${params.airbrushRate}]]></param>
  <param type="string" name="ColorRateValue"><![CDATA[${params.smudgeRate}]]></param>
  <param type="string" name="SmudgeRateValue"><![CDATA[${params.smudgeLength}]]></param>
  $tipDef
</Preset>"""
    }
}
