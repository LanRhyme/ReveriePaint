/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

sealed interface MarkdownBlock {
    data class Header(val level: Int, val text: String) : MarkdownBlock
    data class ListItem(val ordered: Boolean, val number: Int?, val indent: Int, val text: String) : MarkdownBlock
    data class BlockQuote(val text: String) : MarkdownBlock
    data class CodeBlock(val language: String, val code: String) : MarkdownBlock
    data object HorizontalRule : MarkdownBlock
    data class Paragraph(val text: String) : MarkdownBlock
}

enum class InlineStyleType {
    BOLD,
    ITALIC,
    BOLD_ITALIC,
    CODE,
    STRIKE,
    LINK,
}

data class InlinePart(
    val type: InlineStyleType?,
    val text: String,
    val linkUrl: String? = null,
)

object MarkdownParser {

    private val headerRegex = Regex("""^(#{1,6})\s+(.*)$""")
    private val hrRegex = Regex("""^(\-{3,}|\*{3,}|_{3,})\s*$""")
    private val quoteRegex = Regex("""^>\s?(.*)$""")
    private val unorderedListRegex = Regex("""^(\s*)([-*+])\s+(.*)$""")
    private val orderedListRegex = Regex("""^(\s*)(\d+)\.\s+(.*)$""")

    private val inlineCodeRegex = Regex("""`([^`\n]+)`""")
    private val linkRegex = Regex("""\[([^\]\n]+)\]\(((?:https?://|mailto:)[^\s)]+)\)""")
    private val boldItalicRegex = Regex("""\*\*\*([^*\n]+)\*\*\*""")
    private val boldRegex = Regex("""(?<!\*)\*\*([^*\n]+)\*\*(?!\*)|(?<!_)__([^_\n]+)__(?!_)""")
    private val italicRegex = Regex("""(?<!\*)\*([^*\n]+)\*(?!\*)|(?<!\w)_([^_\n]+)_(?!\w)""")
    private val strikeRegex = Regex("""~~([^~\n]+)~~""")

    fun isLikelyHtml(text: String): Boolean {
        return text.contains("<h1", ignoreCase = true) ||
            text.contains("<h2", ignoreCase = true) ||
            text.contains("<h3", ignoreCase = true) ||
            text.contains("<h4", ignoreCase = true) ||
            text.contains("<ul", ignoreCase = true) ||
            text.contains("<ol", ignoreCase = true) ||
            text.contains("<li", ignoreCase = true) ||
            text.contains("<p", ignoreCase = true) ||
            text.contains("<div", ignoreCase = true) ||
            text.contains("<br", ignoreCase = true) ||
            text.contains("<content", ignoreCase = true)
    }

    /**
     * 将包含 HTML 标签的更新日志/发布说明转为规范 Markdown
     */
    fun htmlToMarkdown(html: String): String {
        if (html.isBlank()) return ""
        var t = html.replace("\r\n", "\n").replace('\r', '\n')

        // 1. 代码块
        t = t.replace(Regex("""<pre><code[^>]*>([\s\S]*?)</code></pre>""", RegexOption.IGNORE_CASE)) {
            "\n```\n${it.groupValues[1].trim()}\n```\n"
        }
        t = t.replace(Regex("""<pre[^>]*>([\s\S]*?)</pre>""", RegexOption.IGNORE_CASE)) {
            "\n```\n${it.groupValues[1].trim()}\n```\n"
        }

        // 2. 标题 (h1..h6)
        for (level in 6 downTo 1) {
            val hashes = "#".repeat(level)
            t = t.replace(Regex("""<h$level[^>]*>([\s\S]*?)</h$level>""", RegexOption.IGNORE_CASE)) {
                "\n$hashes ${it.groupValues[1].trim()}\n"
            }
        }

        // 3. 行内代码与等宽文本
        t = t.replace(Regex("""<(?:code|tt)[^>]*>([\s\S]*?)</(?:code|tt)>""", RegexOption.IGNORE_CASE)) {
            "`${it.groupValues[1].trim()}`"
        }

        // 4. 加粗
        t = t.replace(Regex("""<(?:strong|b)[^>]*>([\s\S]*?)</(?:strong|b)>""", RegexOption.IGNORE_CASE)) {
            "**${it.groupValues[1].trim()}**"
        }

        // 5. 斜体
        t = t.replace(Regex("""<(?:em|i)[^>]*>([\s\S]*?)</(?:em|i)>""", RegexOption.IGNORE_CASE)) {
            "*${it.groupValues[1].trim()}*"
        }

        // 6. 删除线
        t = t.replace(Regex("""<(?:del|s|strike)[^>]*>([\s\S]*?)</(?:del|s|strike)>""", RegexOption.IGNORE_CASE)) {
            "~~${it.groupValues[1].trim()}~~"
        }

        // 7. 超链接
        t = t.replace(Regex("""<a\s+[^>]*href=["']([^"']+)["'][^>]*>([\s\S]*?)</a>""", RegexOption.IGNORE_CASE)) {
            val url = it.groupValues[1].trim()
            val text = it.groupValues[2].trim()
            if (text.isNotEmpty()) "[$text]($url)" else url
        }

        // 8. 列表项
        t = t.replace(Regex("""<li[^>]*>([\s\S]*?)</li>""", RegexOption.IGNORE_CASE)) {
            "\n- ${it.groupValues[1].trim()}\n"
        }
        t = t.replace(Regex("""</?(?:ul|ol)[^>]*>""", RegexOption.IGNORE_CASE), "\n")

        // 9. 段落与换行
        t = t.replace(Regex("""<p[^>]*>([\s\S]*?)</p>""", RegexOption.IGNORE_CASE)) {
            "\n${it.groupValues[1].trim()}\n"
        }
        t = t.replace(Regex("""<blockquote[^>]*>([\s\S]*?)</blockquote>""", RegexOption.IGNORE_CASE)) {
            "\n> ${it.groupValues[1].trim()}\n"
        }
        t = t.replace(Regex("""<hr\s*/?>""", RegexOption.IGNORE_CASE), "\n---\n")
        t = t.replace(Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE), "\n")

        // 10. 清除其余未识别 HTML 标签
        t = t.replace(Regex("""<[^>]+>"""), "")

        // 11. 反转义常见实体
        t = t.replace("&quot;", "\"")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&nbsp;", " ")

        // 12. 合并多余空行并整理换行
        val lines = t.split('\n')
        val cleanedLines = mutableListOf<String>()
        var prevBlank = false
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                if (!prevBlank) {
                    cleanedLines.add("")
                    prevBlank = true
                }
            } else {
                cleanedLines.add(trimmed)
                prevBlank = false
            }
        }

        return cleanedLines.joinToString("\n").trim()
    }

    fun parse(markdown: String): List<MarkdownBlock> {
        val blocks = mutableListOf<MarkdownBlock>()
        val preprocessed = if (isLikelyHtml(markdown)) {
            htmlToMarkdown(markdown)
        } else {
            markdown
        }
        val lines = preprocessed.replace("\r\n", "\n").replace('\r', '\n').replace("\\n", "\n").split('\n')

        var inCodeBlock = false
        var codeLang = ""
        val codeLines = mutableListOf<String>()

        for (rawLine in lines) {
            val trimmed = rawLine.trim()

            if (inCodeBlock) {
                if (trimmed.startsWith("```")) {
                    blocks.add(MarkdownBlock.CodeBlock(codeLang, codeLines.joinToString("\n")))
                    codeLines.clear()
                    inCodeBlock = false
                } else {
                    codeLines.add(rawLine)
                }
                continue
            }

            if (trimmed.startsWith("```")) {
                inCodeBlock = true
                codeLang = trimmed.removePrefix("```").trim()
                codeLines.clear()
                continue
            }

            if (trimmed.isEmpty()) {
                continue
            }

            val hrMatch = hrRegex.matchEntire(trimmed)
            if (hrMatch != null) {
                blocks.add(MarkdownBlock.HorizontalRule)
                continue
            }

            val headerMatch = headerRegex.matchEntire(trimmed)
            if (headerMatch != null) {
                val level = headerMatch.groupValues[1].length
                val title = headerMatch.groupValues[2].trim()
                blocks.add(MarkdownBlock.Header(level, title))
                continue
            }

            val quoteMatch = quoteRegex.matchEntire(rawLine.trimStart())
            if (quoteMatch != null) {
                blocks.add(MarkdownBlock.BlockQuote(quoteMatch.groupValues[1].trim()))
                continue
            }

            val uListMatch = unorderedListRegex.matchEntire(rawLine)
            if (uListMatch != null) {
                val spaces = uListMatch.groupValues[1].length
                val content = uListMatch.groupValues[3].trim()
                blocks.add(MarkdownBlock.ListItem(ordered = false, number = null, indent = spaces / 2, text = content))
                continue
            }

            val oListMatch = orderedListRegex.matchEntire(rawLine)
            if (oListMatch != null) {
                val spaces = oListMatch.groupValues[1].length
                val number = oListMatch.groupValues[2].toIntOrNull()
                val content = oListMatch.groupValues[3].trim()
                blocks.add(MarkdownBlock.ListItem(ordered = true, number = number, indent = spaces / 2, text = content))
                continue
            }

            blocks.add(MarkdownBlock.Paragraph(trimmed))
        }

        if (inCodeBlock) {
            blocks.add(MarkdownBlock.CodeBlock(codeLang, codeLines.joinToString("\n")))
        }

        return blocks
    }

    private data class MatchCandidate(
        val range: IntRange,
        val type: InlineStyleType,
        val text: String,
        val url: String? = null,
    )

    fun parseInline(text: String): List<InlinePart> {
        if (text.isEmpty()) return emptyList()

        val parts = mutableListOf<InlinePart>()
        var cursor = 0

        while (cursor < text.length) {
            var earliest: MatchCandidate? = null

            fun checkCandidate(
                match: MatchResult?,
                type: InlineStyleType,
                textExtractor: (MatchResult) -> String,
                urlExtractor: ((MatchResult) -> String)? = null,
            ) {
                if (match != null) {
                    val range = match.range
                    if (range.first >= cursor) {
                        if (earliest == null || range.first < earliest!!.range.first) {
                            earliest = MatchCandidate(
                                range = range,
                                type = type,
                                text = textExtractor(match),
                                url = urlExtractor?.invoke(match),
                            )
                        }
                    }
                }
            }

            checkCandidate(inlineCodeRegex.find(text, cursor), InlineStyleType.CODE, { it.groupValues[1] })
            checkCandidate(linkRegex.find(text, cursor), InlineStyleType.LINK, { it.groupValues[1] }, { it.groupValues[2] })
            checkCandidate(boldItalicRegex.find(text, cursor), InlineStyleType.BOLD_ITALIC, { it.groupValues[1] })
            checkCandidate(boldRegex.find(text, cursor), InlineStyleType.BOLD, { m ->
                if (m.groupValues[1].isNotEmpty()) m.groupValues[1] else m.groupValues[2]
            })
            checkCandidate(italicRegex.find(text, cursor), InlineStyleType.ITALIC, { m ->
                if (m.groupValues[1].isNotEmpty()) m.groupValues[1] else m.groupValues[2]
            })
            checkCandidate(strikeRegex.find(text, cursor), InlineStyleType.STRIKE, { it.groupValues[1] })

            if (earliest == null) {
                parts.add(InlinePart(null, text.substring(cursor)))
                break
            } else {
                val candidate = earliest!!
                if (candidate.range.first > cursor) {
                    parts.add(InlinePart(null, text.substring(cursor, candidate.range.first)))
                }
                parts.add(InlinePart(candidate.type, candidate.text, candidate.url))
                cursor = candidate.range.last + 1
            }
        }

        return parts
    }
}
