/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownParserTest {

    @Test
    fun `parse block elements correctly`() {
        val markdown = """
            # Header 1
            ## Header 2
            ### Header 3
            
            ---
            
            > This is a quote
            
            - Bullet item 1
            - Bullet item 2
            
            1. First step
            2. Second step
            
            ```kotlin
            val a = 1
            ```
            
            Regular paragraph.
        """.trimIndent()

        val blocks = MarkdownParser.parse(markdown)
        assertEquals(11, blocks.size)

        assertEquals(MarkdownBlock.Header(1, "Header 1"), blocks[0])
        assertEquals(MarkdownBlock.Header(2, "Header 2"), blocks[1])
        assertEquals(MarkdownBlock.Header(3, "Header 3"), blocks[2])
        assertEquals(MarkdownBlock.HorizontalRule, blocks[3])
        assertEquals(MarkdownBlock.BlockQuote("This is a quote"), blocks[4])
        assertEquals(MarkdownBlock.ListItem(ordered = false, number = null, indent = 0, text = "Bullet item 1"), blocks[5])
        assertEquals(MarkdownBlock.ListItem(ordered = false, number = null, indent = 0, text = "Bullet item 2"), blocks[6])
        assertEquals(MarkdownBlock.ListItem(ordered = true, number = 1, indent = 0, text = "First step"), blocks[7])
        assertEquals(MarkdownBlock.ListItem(ordered = true, number = 2, indent = 0, text = "Second step"), blocks[8])
        assertEquals(MarkdownBlock.CodeBlock("kotlin", "val a = 1"), blocks[9])
        assertEquals(MarkdownBlock.Paragraph("Regular paragraph."), blocks[10])
    }

    @Test
    fun `parse changelog real case`() {
        val changelog = """
            ## [v1.3.5] - 2026-09-28
            
            ### 新增特性 (Features)
            - **液化工具**: 引入 `RGBA16F` 位移场与 GLES (TextureView + EGL) 覆盖层
            - **参考图**: 支持长按吸色
        """.trimIndent()

        val blocks = MarkdownParser.parse(changelog)
        assertEquals(4, blocks.size)
        assertEquals(MarkdownBlock.Header(2, "[v1.3.5] - 2026-09-28"), blocks[0])
        assertEquals(MarkdownBlock.Header(3, "新增特性 (Features)"), blocks[1])
        assertTrue(blocks[2] is MarkdownBlock.ListItem)
        assertTrue(blocks[3] is MarkdownBlock.ListItem)

        val inlineParts = MarkdownParser.parseInline((blocks[2] as MarkdownBlock.ListItem).text)
        assertEquals(4, inlineParts.size)
        assertEquals(InlineStyleType.BOLD, inlineParts[0].type)
        assertEquals("液化工具", inlineParts[0].text)
        assertNull(inlineParts[1].type)
        assertEquals(": 引入 ", inlineParts[1].text)
        assertEquals(InlineStyleType.CODE, inlineParts[2].type)
        assertEquals("RGBA16F", inlineParts[2].text)
        assertNull(inlineParts[3].type)
        assertEquals(" 位移场与 GLES (TextureView + EGL) 覆盖层", inlineParts[3].text)
    }

    @Test
    fun `parse inline links and mixed styles`() {
        val text = "Check [ReveriePaint](https://github.com/LanRhyme/ReveriePaint) for ***details*** and ~~old~~ code."
        val parts = MarkdownParser.parseInline(text)
        assertEquals(7, parts.size)

        assertEquals("Check ", parts[0].text)
        assertNull(parts[0].type)

        assertEquals("ReveriePaint", parts[1].text)
        assertEquals(InlineStyleType.LINK, parts[1].type)
        assertEquals("https://github.com/LanRhyme/ReveriePaint", parts[1].linkUrl)

        assertEquals(" for ", parts[2].text)
        assertNull(parts[2].type)

        assertEquals("details", parts[3].text)
        assertEquals(InlineStyleType.BOLD_ITALIC, parts[3].type)

        assertEquals(" and ", parts[4].text)
        assertNull(parts[4].type)

        assertEquals("old", parts[5].text)
        assertEquals(InlineStyleType.STRIKE, parts[5].type)

        assertEquals(" code.", parts[6].text)
        assertNull(parts[6].type)
    }

    @Test
    fun `convert and parse atom feed html content`() {
        val html = """
            <h3>新增特性 (Features)</h3>
            <ul>
            <li><strong>笔刷最大尺寸跟随画布</strong>: 支持自适应缩放</li>
            <li><strong>三指编辑浮动菜单</strong>: 支持快捷操作 (by <a href="https://github.com/fisHarly0">@fisHarly0</a> in <a href="https://github.com/LanRhyme/ReveriePaint/pull/19">#19</a>)</li>
            </ul>
            <h3>缺陷修复 (Bug Fixes)</h3>
            <ul>
            <li><strong>触控穿透修复</strong>: 修复异常问题</li>
            </ul>
        """.trimIndent()

        val blocks = MarkdownParser.parse(html)
        assertEquals(5, blocks.size)
        assertEquals(MarkdownBlock.Header(3, "新增特性 (Features)"), blocks[0])
        assertTrue(blocks[1] is MarkdownBlock.ListItem)
        assertTrue(blocks[2] is MarkdownBlock.ListItem)
        assertEquals(MarkdownBlock.Header(3, "缺陷修复 (Bug Fixes)"), blocks[3])
        assertTrue(blocks[4] is MarkdownBlock.ListItem)

        val item1 = blocks[1] as MarkdownBlock.ListItem
        val parts1 = MarkdownParser.parseInline(item1.text)
        assertEquals(InlineStyleType.BOLD, parts1[0].type)
        assertEquals("笔刷最大尺寸跟随画布", parts1[0].text)

        val item2 = blocks[2] as MarkdownBlock.ListItem
        val parts2 = MarkdownParser.parseInline(item2.text)
        assertEquals(InlineStyleType.BOLD, parts2[0].type)
        assertEquals("三指编辑浮动菜单", parts2[0].text)
        assertTrue(parts2.any { it.type == InlineStyleType.LINK && it.text == "@fisHarly0" })
        assertTrue(parts2.any { it.type == InlineStyleType.LINK && it.text == "#19" })
    }

    @Test
    fun `parse escaped literal newlines`() {
        val raw = "### 新增特性\\n- **特性1**: 内容1\\n- **特性2**: 内容2"
        val blocks = MarkdownParser.parse(raw)
        assertEquals(3, blocks.size)
        assertEquals(MarkdownBlock.Header(3, "新增特性"), blocks[0])
        assertTrue(blocks[1] is MarkdownBlock.ListItem)
        assertTrue(blocks[2] is MarkdownBlock.ListItem)
    }

    @Test
    fun `bold text does not trigger false italic`() {
        val text = "**测试加粗**: 正常内容"
        val parts = MarkdownParser.parseInline(text)
        assertEquals(2, parts.size)
        assertEquals(InlineStyleType.BOLD, parts[0].type)
        assertEquals("测试加粗", parts[0].text)
        assertNull(parts[1].type)
        assertEquals(": 正常内容", parts[1].text)
    }
}
