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

/**
 * 作者档案预设项 (支持多档案配置与切换)
 */
data class AuthorProfilePreset(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String = "",
    val profile: AuthorProfile = AuthorProfile(),
) {
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

        return """{"id":"${escape(id)}","name":"${escape(name)}","profile":${profile.toJson()}}"""
    }

    companion object {
        fun fromJson(json: String?): AuthorProfilePreset? {
            if (json.isNullOrBlank()) return null
            val id = extractJsonString(json, "id")
            val name = extractJsonString(json, "name")
            val profileJson = extractNestedObject(json, "profile")
            val profile = if (profileJson != null) AuthorProfile.fromJson(profileJson) else AuthorProfile()
            if (id.isBlank() && name.isBlank() && profile.isEmpty()) return null
            return AuthorProfilePreset(
                id = id.ifBlank { java.util.UUID.randomUUID().toString() },
                name = name,
                profile = profile,
            )
        }

        fun listToJson(presets: List<AuthorProfilePreset>): String {
            return "[" + presets.joinToString(",") { it.toJson() } + "]"
        }

        fun listFromJson(json: String?): List<AuthorProfilePreset> {
            if (json.isNullOrBlank()) return emptyList()
            val objects = splitJsonObjects(json)
            return objects.mapNotNull { fromJson(it) }
        }

        private fun extractJsonString(json: String, key: String): String {
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

        private fun extractNestedObject(json: String, key: String): String? {
            val keyIndex = json.indexOf("\"$key\"")
            if (keyIndex == -1) return null
            val colonIndex = json.indexOf(':', keyIndex)
            if (colonIndex == -1) return null
            val openBrace = json.indexOf('{', colonIndex)
            if (openBrace == -1) return null
            var depth = 0
            var inString = false
            var escape = false
            for (i in openBrace until json.length) {
                val c = json[i]
                if (escape) {
                    escape = false
                    continue
                }
                if (c == '\\') {
                    escape = true
                    continue
                }
                if (c == '"') {
                    inString = !inString
                    continue
                }
                if (!inString) {
                    if (c == '{') depth++
                    else if (c == '}') {
                        depth--
                        if (depth == 0) {
                            return json.substring(openBrace, i + 1)
                        }
                    }
                }
            }
            return null
        }

        private fun splitJsonObjects(arrayJson: String): List<String> {
            val trimmed = arrayJson.trim()
            if (!trimmed.startsWith("[") || !trimmed.endsWith("]")) return emptyList()
            val content = trimmed.substring(1, trimmed.length - 1).trim()
            if (content.isEmpty()) return emptyList()

            val result = mutableListOf<String>()
            var depth = 0
            var inString = false
            var escape = false
            var start = -1

            for (i in content.indices) {
                val c = content[i]
                if (escape) {
                    escape = false
                    continue
                }
                if (c == '\\') {
                    escape = true
                    continue
                }
                if (c == '"') {
                    inString = !inString
                    continue
                }
                if (!inString) {
                    if (c == '{') {
                        if (depth == 0) start = i
                        depth++
                    } else if (c == '}') {
                        depth--
                        if (depth == 0 && start != -1) {
                            result.add(content.substring(start, i + 1))
                            start = -1
                        }
                    }
                }
            }
            return result
        }
    }
}

