/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * AdjustmentConfigCodec.kt - 调整层配置事件参数的纯 Kotlin 编解码
 *
 * L_ADJ_CONFIG 事件 arg 格式: "<type>|<p1>|<p2>|<p3>|<p4>|<lutHex>"
 * - type: 滤镜类型 id (reverieType, 0-34)
 * - p1-p4: 双精度参数, toString/toDouble 往返
 * - lutHex: 可选 LUT 字节的十六进制 (曲线 768B / 渐变映射 1024B); 无则空串
 */
package com.reverie.paint.model

object AdjustmentConfigCodec {

    data class Config(
        val type: Int,
        val p1: Double,
        val p2: Double,
        val p3: Double,
        val p4: Double,
        val lut: ByteArray?,
    )

    fun encode(
        type: Int,
        p1: Double,
        p2: Double,
        p3: Double,
        p4: Double,
        lut: ByteArray?,
    ): String {
        val hex = lut?.joinToString("") { "%02x".format(it) } ?: ""
        return "$type|$p1|$p2|$p3|$p4|$hex"
    }

    fun decode(arg: String): Config? {
        val parts = arg.split("|")
        if (parts.size < 6) return null
        val type = parts[0].toIntOrNull() ?: return null
        val p1 = parts[1].toDoubleOrNull() ?: return null
        val p2 = parts[2].toDoubleOrNull() ?: return null
        val p3 = parts[3].toDoubleOrNull() ?: return null
        val p4 = parts[4].toDoubleOrNull() ?: return null
        val hex = parts[5]
        val lut = if (hex.isEmpty()) {
            null
        } else {
            if (hex.length % 2 != 0) return null
            ByteArray(hex.length / 2) { i ->
                ((Character.digit(hex[i * 2], 16) shl 4) + Character.digit(hex[i * 2 + 1], 16)).toByte()
            }
        }
        return Config(type, p1, p2, p3, p4, lut)
    }

    /**
     * 解析 C++ getAdjustmentLayerConfig 返回的 JSON 快照:
     * {"type":N,"p1":x,"p2":x,"p3":x,"p4":x[,"lut":"<base64>"]}
     * 纯 Kotlin 手工提取 (model/ 无 Android/org.json 依赖), base64 自解码。
     */
    fun decodeJson(json: String): Config? {
        if (!json.startsWith("{") || !json.contains("\"type\"")) return null
        val type = extractNum(json, "type")?.toDoubleOrNull()?.toInt() ?: return null
        val p1 = extractNum(json, "p1")?.toDoubleOrNull() ?: return null
        val p2 = extractNum(json, "p2")?.toDoubleOrNull() ?: return null
        val p3 = extractNum(json, "p3")?.toDoubleOrNull() ?: return null
        val p4 = extractNum(json, "p4")?.toDoubleOrNull() ?: return null
        val b64 = extractStr(json, "lut")
        val lut = when {
            b64.isNullOrEmpty() -> null
            else -> base64Decode(b64) ?: return null
        }
        return Config(type, p1, p2, p3, p4, lut)
    }

    private fun extractNum(json: String, key: String): String? {
        val marker = "\"$key\":"
        val start = json.indexOf(marker)
        if (start < 0) return null
        var end = start + marker.length
        while (end < json.length && (json[end] == ' ')) end++
        var vEnd = end
        while (vEnd < json.length && (json[vEnd].isDigit() || json[vEnd] == '.' || json[vEnd] == '-' || json[vEnd] == '+' || json[vEnd] == 'e' || json[vEnd] == 'E')) vEnd++
        return json.substring(end, vEnd)
    }

    private fun extractStr(json: String, key: String): String? {
        val marker = "\"$key\":\""
        val start = json.indexOf(marker)
        if (start < 0) return null
        val valueStart = start + marker.length
        val end = json.indexOf('"', valueStart)
        if (end < 0) return null
        return json.substring(valueStart, end)
    }

    private fun base64Decode(s: String): ByteArray? {
        // 标准 base64 → 字节; 非法字符返回 null
        val rev = IntArray(128) { -1 }
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        for (i in alphabet.indices) rev[alphabet[i].code] = i
        val clean = s.filter { it.code < 128 && it != '=' && rev[it.code] >= 0 }
        if (clean.length % 4 == 1) return null
        val out = ByteArray(clean.length * 3 / 4 + (if (clean.length % 4 == 0) 0 else 1))
        var o = 0
        var i = 0
        while (i + 3 < clean.length) {
            val n = (rev[clean[i].code] shl 18) or (rev[clean[i + 1].code] shl 12) or
                (rev[clean[i + 2].code] shl 6) or rev[clean[i + 3].code]
            out[o++] = ((n shr 16) and 0xFF).toByte()
            out[o++] = ((n shr 8) and 0xFF).toByte()
            out[o++] = (n and 0xFF).toByte()
            i += 4
        }
        val rem = clean.length - i
        if (rem == 2) {
            val n = (rev[clean[i].code] shl 18) or (rev[clean[i + 1].code] shl 12)
            out[o++] = ((n shr 16) and 0xFF).toByte()
        } else if (rem == 3) {
            val n = (rev[clean[i].code] shl 18) or (rev[clean[i + 1].code] shl 12) or (rev[clean[i + 2].code] shl 6)
            out[o++] = ((n shr 16) and 0xFF).toByte()
            out[o++] = ((n shr 8) and 0xFF).toByte()
        }
        return if (o == out.size) out else out.copyOf(o)
    }
}
