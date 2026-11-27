/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class ReleaseNotesLocalizerTest {
    @Test
    fun `shouldPreferChinese maps zh variants and cat to chinese and others to english`() {
        assertTrue(ReleaseNotesLocalizer.shouldPreferChinese(Locale.SIMPLIFIED_CHINESE))
        assertTrue(ReleaseNotesLocalizer.shouldPreferChinese(Locale.TRADITIONAL_CHINESE))
        assertTrue(ReleaseNotesLocalizer.shouldPreferChinese(Locale.forLanguageTag("zh-HK")))
        assertTrue(ReleaseNotesLocalizer.shouldPreferChinese(Locale.forLanguageTag("cat")))

        assertFalse(ReleaseNotesLocalizer.shouldPreferChinese(Locale.ENGLISH))
        assertFalse(ReleaseNotesLocalizer.shouldPreferChinese(Locale.JAPANESE))
        assertFalse(ReleaseNotesLocalizer.shouldPreferChinese(Locale.KOREA))
    }

    @Test
    fun `selectLanguageSection extracts preferred section regardless of section order`() {
        val bilingual = """
            ## English

            ### Features
            - Feature A

            ---

            ## 简体中文

            ### 新增特性 (Features)
            - 特性 A
        """.trimIndent()

        assertEquals(
            "### Features\n- Feature A",
            ReleaseNotesLocalizer.selectLanguageSection(bilingual, preferChinese = false),
        )
        assertEquals(
            "### 新增特性 (Features)\n- 特性 A",
            ReleaseNotesLocalizer.selectLanguageSection(bilingual, preferChinese = true),
        )

        val reversed = """
            ## 简体中文

            ### 修复
            - 修复 B

            ---

            ## English

            ### Bug Fixes
            - Fix B
        """.trimIndent()

        assertEquals(
            "### Bug Fixes\n- Fix B",
            ReleaseNotesLocalizer.selectLanguageSection(reversed, preferChinese = false),
        )
        assertEquals(
            "### 修复\n- 修复 B",
            ReleaseNotesLocalizer.selectLanguageSection(reversed, preferChinese = true),
        )
    }

    @Test
    fun `selectLanguageSection works on Atom feed HTML converted by MarkdownParser`() {
        val atomHtml = """
            <h2>English</h2>
            <h3>Features</h3>
            <ul><li><strong>Brush</strong>: Faster stroke rendering</li></ul>
            <hr/>
            <h2>简体中文</h2>
            <h3>新增特性</h3>
            <ul><li><strong>画笔</strong>: 渲染速度更快</li></ul>
        """.trimIndent()

        val md = MarkdownParser.htmlToMarkdown(atomHtml)
        val enOnly = ReleaseNotesLocalizer.selectLanguageSection(md, preferChinese = false)
        val zhOnly = ReleaseNotesLocalizer.selectLanguageSection(md, preferChinese = true)

        assertTrue(enOnly.contains("Faster stroke rendering"))
        assertFalse(enOnly.contains("渲染速度更快"))
        assertTrue(zhOnly.contains("渲染速度更快"))
        assertFalse(zhOnly.contains("Faster stroke rendering"))
    }

    @Test
    fun `selectLanguageSection returns trimmed body for legacy single language releases`() {
        val legacy = "\n### 新增特性\n- 旧版本单语日志\n"
        assertEquals("### 新增特性\n- 旧版本单语日志", ReleaseNotesLocalizer.selectLanguageSection(legacy, preferChinese = false))
        assertEquals("### 新增特性\n- 旧版本单语日志", ReleaseNotesLocalizer.selectLanguageSection(legacy, preferChinese = true))
    }

    @Test
    fun `mergeNewerReleaseNotes localizes each release before merging`() {
        val releases = listOf(
            "v1.11.1" to "## English\n\n- EN 111\n\n---\n\n## 简体中文\n\n- 中文 111",
            "v1.11.0" to "### 新增特性\n- Legacy ZH 110",
            "v1.10.0" to "## English\n\n- Old",
        )

        val enMerged = ReleaseNotesAggregator.mergeNewerReleaseNotes(releases, "1.10.0", preferChinese = false)
        assertTrue(enMerged.contains("## v1.11.1\n\n- EN 111"))
        assertFalse(enMerged.contains("中文 111"))
        assertTrue(enMerged.contains("## v1.11.0\n\n### 新增特性\n- Legacy ZH 110"))
        assertFalse(enMerged.contains("1.10.0"))

        val zhMerged = ReleaseNotesAggregator.mergeNewerReleaseNotes(releases, "1.10.0", preferChinese = true)
        assertTrue(zhMerged.contains("## v1.11.1\n\n- 中文 111"))
        assertFalse(zhMerged.contains("EN 111"))
    }

    @Test
    fun `stripDocumentHeader removes title and language switcher while preserving first version heading`() {
        val raw = """
            # ReveriePaint Changelog

            [English](CHANGELOG.md) | [简体中文](CHANGELOG_ZH.md)

            ## [v1.11.1] - 2026-11-26

            ### Features
            - Item 1
        """.trimIndent()

        val stripped = ReleaseNotesLocalizer.stripDocumentHeader(raw)
        assertTrue(stripped.startsWith("## [v1.11.1] - 2026-11-26"))
        assertFalse(stripped.contains("[English]"))
        assertFalse(stripped.contains("# ReveriePaint Changelog"))
    }
}
