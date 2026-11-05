/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.telemetry

import com.reverie.paint.core.DiagnosticsManager

/**
 * 上报前的最后一道脱敏。纯 Kotlin、不依赖 Android, 便于 JVM 单测。
 *
 * 三层:
 *  1. 复用 [DiagnosticsManager.sanitize] 的凭据规则 (Bearer/Basic/密码/token)
 *  2. 用户可见路径整体替换 (外部存储、Android/data 目录)
 *  3. 文件名收敛成 `*.<ext>` —— 这一条针对异常 message 与 logcat 里最常见的泄漏形态:
 *     `.revp` 工程名、导出图文件名、参考图路径
 *
 * 契约见 docs/TELEMETRY.md §4 禁止清单。
 */
object TelemetryScrubber {

    private const val EXT_GROUP = "(revp|kra|ora|psd|png|jpe?g|webp|bmp|gif|tiff?|mp4|svg)"

    /** 用户外部存储路径: 整段替换, 避免目录名也泄漏 */
    private val USER_STORAGE_PATH = Regex("""/storage/emulated/\d+/[^\s"',;:()\[\]]*""")

    /** 可移动存储上的应用私有目录 */
    private val EXTERNAL_APPDATA_PATH =
        Regex("""/storage/[^\s"',;:()\[\]]*/Android/(data|media)/[^\s"',;:()\[\]]*""")

    /**
     * 裸文件名 (含已知扩展名) —— 保留扩展名, 名字收敛成 `*`。
     *
     * 用 `\p{L}\p{N}` 而不是 `\w`: Android 的 java.util.regex 不支持 JDK 的 `(?U)`
     * (UNICODE_CHARACTER_CLASS) 内联标志, 用 `(?U)` 会在真机上抛
     * `PatternSyntaxException` —— 而 JVM 单测是好的, 这类差异只能靠真机暴露。
     * `\p{L}` 覆盖中日韩字母, 且两端都能编译。
     */
    private val FILE_NAME = Regex("""[\p{L}\p{N}_\-.]+\.$EXT_GROUP\b""", RegexOption.IGNORE_CASE)

    /** 内网地址: 保留协议头, 主机段收敛, 避免暴露用户的 NAS / Tailscale 名称 */
    private val LAN_URL = Regex(
        """(?i)\b(https?://)(?:10\.\d{1,3}\.\d{1,3}\.\d{1,3}|192\.168\.\d{1,3}\.\d{1,3}|"""
            + """172\.(?:1[6-9]|2\d|3[01])\.\d{1,3}\.\d{1,3}|127\.0\.0\.1|localhost)[^\s"'<>]*""",
    )
    private val LAN_HOSTNAME = Regex("""(?i)\b(https?://)[\w.-]+\.(?:lan|local|internal|home)\b[^\s"'<>]*""")

    /**
     * 对任意文本做脱敏。对已脱敏文本再跑一次结果不变(幂等)。
     */
    fun scrub(raw: String): String {
        if (raw.isEmpty()) return raw
        var s = DiagnosticsManager.sanitize(raw)
        s = USER_STORAGE_PATH.replace(s, "/storage/emulated/0/<redacted>")
        s = EXTERNAL_APPDATA_PATH.replace(s, "/storage/<redacted>/Android/<redacted>")
        s = LAN_URL.replace(s) { it.groupValues[1] + "<lan>" }
        s = LAN_HOSTNAME.replace(s) { it.groupValues[1] + "<lan>" }
        s = FILE_NAME.replace(s) { "*." + it.groupValues[1].lowercase() }
        return s
    }

    /** 截断到上限, 超出时在尾部留标记(便于服务端一眼看出被截过) */
    fun cap(raw: String, maxChars: Int): String {
        if (raw.length <= maxChars) return raw
        val mark = "\n…[truncated ${raw.length - maxChars} chars]"
        return raw.substring(0, (maxChars - mark.length).coerceAtLeast(0)) + mark
    }
}
