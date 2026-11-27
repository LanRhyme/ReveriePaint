/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import org.json.JSONObject
import java.text.BreakIterator
import java.util.Locale

/**
 * 自定义工具栏图标配置模型：支持字符/Emoji、内置矢量图标与本地相册图片。
 */
sealed class CustomToolIcon {
    data class Text(val glyph: String) : CustomToolIcon()
    data class Builtin(val iconKey: String) : CustomToolIcon()
    data class Image(val fileName: String, val tintWithTheme: Boolean = false) : CustomToolIcon()
}

/**
 * 纯 Kotlin 的字形规范化与 JSON 编解码器（支持 JVM 单元测试与无丢帧 Emoji 截断）。
 */
object CustomToolIconCodec {
    private const val KEY_TYPE = "t"
    private const val KEY_VALUE = "v"
    private const val KEY_TINT = "m"

    private const val TYPE_TEXT = "text"
    private const val TYPE_BUILTIN = "builtin"
    private const val TYPE_IMAGE = "image"

    fun normalizeGlyph(raw: String, maxClusters: Int = 2): String {
        if (raw.isEmpty() || maxClusters <= 0) return ""
        val sanitized = buildString(raw.length) {
            var i = 0
            while (i < raw.length) {
                val cp = Character.codePointAt(raw, i)
                val charCount = Character.charCount(cp)
                if (!Character.isISOControl(cp) && cp != '\n'.code && cp != '\r'.code) {
                    appendCodePoint(cp)
                }
                i += charCount
            }
        }.trim()
        if (sanitized.isEmpty()) return ""

        val iterator = BreakIterator.getCharacterInstance(Locale.ROOT)
        iterator.setText(sanitized)
        val first = iterator.first()
        var end = first
        var count = 0
        while (count < maxClusters) {
            val next = iterator.next()
            if (next == BreakIterator.DONE) break
            end = next
            count++
        }
        return sanitized.substring(first, end)
    }

    fun countGraphemeClusters(text: String): Int {
        if (text.isEmpty()) return 0
        val iterator = BreakIterator.getCharacterInstance(Locale.ROOT)
        iterator.setText(text)
        var count = 0
        iterator.first()
        while (iterator.next() != BreakIterator.DONE) {
            count++
        }
        return count
    }

    fun isLikelyColorEmoji(text: String): Boolean {
        var i = 0
        while (i < text.length) {
            val cp = Character.codePointAt(text, i)
            if (cp >= 0x1F000 ||
                cp == 0xFE0F ||
                cp == 0x2B50 ||
                cp == 0x2728 ||
                cp in 0x231A..0x231B
            ) {
                return true
            }
            i += Character.charCount(cp)
        }
        return false
    }

    fun encodeMap(icons: Map<String, CustomToolIcon>): String {
        val root = JSONObject()
        for ((toolId, icon) in icons) {
            if (toolId.isBlank()) continue
            val entry = JSONObject()
            when (icon) {
                is CustomToolIcon.Text -> {
                    val clean = normalizeGlyph(icon.glyph)
                    if (clean.isEmpty()) continue
                    entry.put(KEY_TYPE, TYPE_TEXT)
                    entry.put(KEY_VALUE, clean)
                }
                is CustomToolIcon.Builtin -> {
                    if (icon.iconKey.isBlank()) continue
                    entry.put(KEY_TYPE, TYPE_BUILTIN)
                    entry.put(KEY_VALUE, icon.iconKey.trim())
                }
                is CustomToolIcon.Image -> {
                    if (icon.fileName.isBlank()) continue
                    entry.put(KEY_TYPE, TYPE_IMAGE)
                    entry.put(KEY_VALUE, icon.fileName.trim())
                    if (icon.tintWithTheme) {
                        entry.put(KEY_TINT, true)
                    }
                }
            }
            root.put(toolId, entry)
        }
        return root.toString()
    }

    fun decodeMap(raw: String?): Map<String, CustomToolIcon> {
        if (raw.isNullOrBlank()) return emptyMap()
        return try {
            val root = JSONObject(raw)
            val result = LinkedHashMap<String, CustomToolIcon>()
            val keys = root.keys()
            while (keys.hasNext()) {
                val toolId = keys.next()
                if (toolId.isBlank()) continue
                val obj = root.optJSONObject(toolId) ?: continue
                val type = obj.optString(KEY_TYPE, "")
                val value = obj.optString(KEY_VALUE, "")
                when (type) {
                    TYPE_TEXT -> {
                        val glyph = normalizeGlyph(value)
                        if (glyph.isNotEmpty()) {
                            result[toolId] = CustomToolIcon.Text(glyph)
                        }
                    }
                    TYPE_BUILTIN -> {
                        val iconKey = value.trim()
                        if (iconKey.isNotEmpty()) {
                            result[toolId] = CustomToolIcon.Builtin(iconKey)
                        }
                    }
                    TYPE_IMAGE -> {
                        val fileName = value.trim()
                        if (fileName.isNotEmpty() && !fileName.contains('/') && !fileName.contains("..")) {
                            result[toolId] = CustomToolIcon.Image(
                                fileName = fileName,
                                tintWithTheme = obj.optBoolean(KEY_TINT, false),
                            )
                        }
                    }
                }
            }
            result
        } catch (_: Exception) {
            emptyMap()
        }
    }
}
