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
}
