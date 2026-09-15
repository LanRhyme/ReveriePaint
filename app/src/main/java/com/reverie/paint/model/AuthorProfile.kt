/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

/**
 * 作者档案 (Author Profile) 数据模型
 * 遵循 Calligra / Krita Dublin Core 规范
 */
data class AuthorProfile(
    val enabled: Boolean = true,
    val name: String = "",
    val nickname: String = "",
    val organization: String = "",
    val email: String = "",
    val website: String = "",
    val copyright: String = "",
) {
    fun isEmpty(): Boolean {
        return name.isBlank() &&
            nickname.isBlank() &&
            organization.isBlank() &&
            email.isBlank() &&
            website.isBlank() &&
            copyright.isBlank()
    }

    fun isNotEmpty(): Boolean = !isEmpty()

    /** 导出为紧凑 JSON 格式 */
    fun toJson(): String {
        fun escape(s: String): String {
            val sb = StringBuilder()
            for (c in s) {
                when (c) {
                    '\\' -> sb.append("\\\\")
                    '"' -> sb.append("\\\"")
                    '\b' -> sb.append("\\b")
                    '\u000C' -> sb.append("\\f")
                    '\n' -> sb.append("\\n")
                    '\r' -> sb.append("\\r")
                    '\t' -> sb.append("\\t")
                    else -> sb.append(c)
                }
            }
            return sb.toString()
        }

        return """{"enabled":$enabled,"name":"${escape(name)}","nickname":"${escape(nickname)}","organization":"${escape(organization)}","email":"${escape(email)}","website":"${escape(website)}","copyright":"${escape(copyright)}"}"""
    }

    companion object {
        /** 从 JSON 解析 AuthorProfile，纯 Kotlin 实现，容错解析 */
        fun fromJson(json: String?): AuthorProfile {
            if (json.isNullOrBlank()) return AuthorProfile()

            fun extractString(key: String): String {
                val pattern = Regex(""""$key"\s*:\s*"((?:[^"\\]|\\.)*)"""")
                val match = pattern.find(json) ?: return ""
                val raw = match.groupValues[1]
                val sb = StringBuilder()
                var i = 0
                while (i < raw.length) {
                    val c = raw[i]
                    if (c == '\\' && i + 1 < raw.length) {
                        val next = raw[i + 1]
                        when (next) {
                            '\\' -> sb.append('\\')
                            '"' -> sb.append('"')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            else -> sb.append(next)
                        }
                        i += 2
                    } else {
                        sb.append(c)
                        i++
                    }
                }
                return sb.toString()
            }

            fun extractBool(key: String, default: Boolean = true): Boolean {
                val pattern = Regex(""""$key"\s*:\s*(true|false)""")
                val match = pattern.find(json) ?: return default
                return match.groupValues[1] == "true"
            }

            return AuthorProfile(
                enabled = extractBool("enabled", default = true),
                name = extractString("name"),
                nickname = extractString("nickname"),
                organization = extractString("organization"),
                email = extractString("email"),
                website = extractString("website"),
                copyright = extractString("copyright"),
            )
        }
    }
}
