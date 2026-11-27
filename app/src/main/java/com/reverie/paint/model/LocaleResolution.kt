/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import java.util.Locale

/**
 * Pure locale resolution helpers for mapping stored language preferences and
 * system [java.util.Locale] priority lists onto the app's supported resource locales.
 */
object LocaleResolution {
    private val TRADITIONAL_CHINESE_REGIONS = setOf("TW", "HK", "MO")
    private val CAT_LOCALE: Locale = Locale.forLanguageTag("cat")

    fun resolveLocale(savedCode: String, systemLocales: List<Locale>): Locale {
        when (savedCode.trim().lowercase(Locale.ROOT)) {
            "zh-cn" -> return Locale.SIMPLIFIED_CHINESE
            "zh-tw" -> return Locale.TRADITIONAL_CHINESE
            "en" -> return Locale.ENGLISH
            "ja" -> return Locale.JAPANESE
            "cat" -> return CAT_LOCALE
        }

        for (locale in systemLocales) {
            when (locale.language.lowercase(Locale.ROOT)) {
                "en" -> return Locale.ENGLISH
                "ja" -> return Locale.JAPANESE
                "cat" -> return CAT_LOCALE
                "zh" -> {
                    val isHant = locale.script.equals("Hant", ignoreCase = true) ||
                        locale.country.uppercase(Locale.ROOT) in TRADITIONAL_CHINESE_REGIONS
                    return if (isHant) Locale.TRADITIONAL_CHINESE else Locale.SIMPLIFIED_CHINESE
                }
            }
        }
        return Locale.ENGLISH
    }

    fun isChineseLocale(locale: Locale): Boolean {
        return locale.language.equals("zh", ignoreCase = true)
    }
}
