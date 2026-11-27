/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class LocaleResolutionTest {
    @Test
    fun `explicit saved codes select exact locale regardless of case or system list`() {
        val sys = listOf(Locale.KOREA, Locale.US)
        assertEquals(Locale.SIMPLIFIED_CHINESE, LocaleResolution.resolveLocale("zh-CN", sys))
        assertEquals(Locale.SIMPLIFIED_CHINESE, LocaleResolution.resolveLocale("ZH-cn", sys))
        assertEquals(Locale.TRADITIONAL_CHINESE, LocaleResolution.resolveLocale("zh-TW", sys))
        assertEquals(Locale.ENGLISH, LocaleResolution.resolveLocale("en", listOf(Locale.SIMPLIFIED_CHINESE)))
        assertEquals(Locale.JAPANESE, LocaleResolution.resolveLocale("ja", sys))
        assertEquals(Locale.forLanguageTag("cat"), LocaleResolution.resolveLocale("cat", sys))
    }

    @Test
    fun `follow system picks english when en-US precedes zh-CN`() {
        val resolved = LocaleResolution.resolveLocale(
            savedCode = "",
            systemLocales = listOf(Locale.US, Locale.SIMPLIFIED_CHINESE),
        )
        assertEquals(Locale.ENGLISH, resolved)
    }

    @Test
    fun `follow system picks simplified chinese when zh-CN precedes en-US`() {
        val resolved = LocaleResolution.resolveLocale(
            savedCode = "",
            systemLocales = listOf(Locale.SIMPLIFIED_CHINESE, Locale.US),
        )
        assertEquals(Locale.SIMPLIFIED_CHINESE, resolved)
    }

    @Test
    fun `follow system maps HK MO TW and Hant script to traditional chinese`() {
        assertEquals(
            Locale.TRADITIONAL_CHINESE,
            LocaleResolution.resolveLocale("", listOf(Locale.forLanguageTag("zh-HK"))),
        )
        assertEquals(
            Locale.TRADITIONAL_CHINESE,
            LocaleResolution.resolveLocale("", listOf(Locale.forLanguageTag("zh-MO"))),
        )
        assertEquals(
            Locale.TRADITIONAL_CHINESE,
            LocaleResolution.resolveLocale("", listOf(Locale.TRADITIONAL_CHINESE)),
        )
        assertEquals(
            Locale.TRADITIONAL_CHINESE,
            LocaleResolution.resolveLocale("", listOf(Locale.forLanguageTag("zh-Hant-US"))),
        )
    }

    @Test
    fun `follow system maps SG Hans and bare zh to simplified chinese`() {
        assertEquals(
            Locale.SIMPLIFIED_CHINESE,
            LocaleResolution.resolveLocale("", listOf(Locale.forLanguageTag("zh-SG"))),
        )
        assertEquals(
            Locale.SIMPLIFIED_CHINESE,
            LocaleResolution.resolveLocale("", listOf(Locale.forLanguageTag("zh-Hans-US"))),
        )
        assertEquals(
            Locale.SIMPLIFIED_CHINESE,
            LocaleResolution.resolveLocale("", listOf(Locale.CHINESE)),
        )
    }

    @Test
    fun `follow system picks japanese and falls back unsupported locales to english`() {
        assertEquals(Locale.JAPANESE, LocaleResolution.resolveLocale("", listOf(Locale.JAPAN)))
        assertEquals(Locale.ENGLISH, LocaleResolution.resolveLocale("", listOf(Locale.KOREA)))
        assertEquals(Locale.ENGLISH, LocaleResolution.resolveLocale("", listOf(Locale.FRANCE, Locale.GERMANY)))
        assertEquals(Locale.ENGLISH, LocaleResolution.resolveLocale("", emptyList()))
        assertEquals(Locale.JAPANESE, LocaleResolution.resolveLocale("", listOf(Locale.FRANCE, Locale.JAPAN)))
    }

    @Test
    fun `isChineseLocale matches chinese language variants only`() {
        assertTrue(LocaleResolution.isChineseLocale(Locale.SIMPLIFIED_CHINESE))
        assertTrue(LocaleResolution.isChineseLocale(Locale.TRADITIONAL_CHINESE))
        assertTrue(LocaleResolution.isChineseLocale(Locale.forLanguageTag("zh-HK")))
        assertFalse(LocaleResolution.isChineseLocale(Locale.ENGLISH))
        assertFalse(LocaleResolution.isChineseLocale(Locale.JAPANESE))
        assertFalse(LocaleResolution.isChineseLocale(Locale.forLanguageTag("cat")))
    }
}
