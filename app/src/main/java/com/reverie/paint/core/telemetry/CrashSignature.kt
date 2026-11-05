/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.telemetry

import java.security.MessageDigest

/**
 * 崩溃去重签名。同一处代码的同一个错必须稳定得到同一签名 —— 这是服务端做聚合、
 * 决定修复优先级的唯一依据, 所以锚点里不能出现任何会变的东西:
 * 不要时间、不要 ASLR 后的绝对地址、不要设备型号、不要行号之外的随机量。
 *
 * 契约见 docs/TELEMETRY.md §6。
 */
object CrashSignature {

    private const val PREFIX = "revp1:"  // 算法改版时换前缀, 老的聚合视图不会被新算法污染
    private const val HEX_LEN = 32        // 128 bit 足够, 短一点省库

    fun of(kind: String, appCode: Int, anchor: String): String {
        val material = "$PREFIX$kind|$appCode|$anchor"
        val digest = MessageDigest.getInstance("SHA-256").digest(material.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(HEX_LEN)
        val hex = "0123456789abcdef"
        for (i in 0 until HEX_LEN / 2) {
            val b = digest[i].toInt() and 0xFF
            sb.append(hex[b ushr 4]).append(hex[b and 0x0F])
        }
        return sb.toString()
    }
}
