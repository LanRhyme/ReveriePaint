/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.reverie.paint.model.LocaleResolution
import java.util.Locale

enum class AppLanguage(
    val code: String,
    val nativeName: String,
    val englishName: String,
) {
    FOLLOW_SYSTEM("", "跟随系统", "Follow System"),
    ZH_CN("zh-CN", "简体中文", "Simplified Chinese"),
    EN("en", "English", "English"),
    JA("ja", "日本語", "Japanese"),
    ZH_TW("zh-TW", "繁體中文 (台灣)", "Traditional Chinese (Taiwan)"),
    CAT("cat", "猫猫语", "Cat Language");

    companion object {
        fun fromCode(code: String): AppLanguage {
            return entries.firstOrNull { it.code.equals(code, ignoreCase = true) } ?: FOLLOW_SYSTEM
        }
    }
}

/**
 * 全局应用语言管理，支持系统语言跟随与应用内即时切换。
 */
object LanguageManager {
    private const val PREFS_NAME = "paint_prefs"
    private const val KEY_APP_LANGUAGE = "app_language"

    var currentLanguage by mutableStateOf(AppLanguage.FOLLOW_SYSTEM)
        private set

    fun init(context: Context) {
        val savedCode = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_APP_LANGUAGE, "") ?: ""
        currentLanguage = AppLanguage.fromCode(savedCode)
        val targetLocale = getLocale(currentLanguage, context)
        Locale.setDefault(targetLocale)
        applyLocaleToResources(context.applicationContext.resources, targetLocale)
    }

    private fun readSystemLocales(context: Context?): List<Locale> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && context != null) {
            try {
                val appLocales = context.getSystemService(LocaleManager::class.java)?.applicationLocales
                if (appLocales != null && !appLocales.isEmpty) {
                    return List(appLocales.size()) { appLocales[it] }
                }
            } catch (_: Exception) {
            }
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val sysLocales = Resources.getSystem().configuration.locales
            List(sysLocales.size()) { sysLocales[it] }
        } else {
            @Suppress("DEPRECATION")
            listOfNotNull(Resources.getSystem().configuration.locale)
        }
    }

    private fun applyLocaleToConfiguration(config: Configuration, locale: Locale) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            config.setLocales(LocaleList(locale))
        } else {
            @Suppress("DEPRECATION")
            config.setLocale(locale)
        }
    }

    private fun applyLocaleToResources(res: Resources, locale: Locale) {
        val config = Configuration(res.configuration)
        applyLocaleToConfiguration(config, locale)
        @Suppress("DEPRECATION")
        res.updateConfiguration(config, res.displayMetrics)
    }

    fun getLocale(language: AppLanguage = currentLanguage, context: Context? = null): Locale {
        return LocaleResolution.resolveLocale(language.code, readSystemLocales(context))
    }

    fun isChinese(language: AppLanguage = currentLanguage): Boolean {
        return LocaleResolution.isChineseLocale(getLocale(language))
    }

    fun setLanguage(activity: Activity, language: AppLanguage) {
        currentLanguage = language
        activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_APP_LANGUAGE, language.code)
            .apply()

        // Android 13+ (API 33+) 优先使用系统原生 Per-App Language 接口
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                val localeManager = activity.getSystemService(LocaleManager::class.java)
                if (localeManager != null) {
                    val locales = if (language.code.isEmpty()) {
                        LocaleList.getEmptyLocaleList()
                    } else {
                        LocaleList.forLanguageTags(language.code)
                    }
                    localeManager.applicationLocales = locales
                }
            } catch (_: Exception) {
            }
        }

        // 更新应用资源配置以达到即时热刷新 (FOLLOW_SYSTEM 时传 null 读取最新系统语言列表)
        val targetLocale = getLocale(language, context = null)
        Locale.setDefault(targetLocale)

        applyLocaleToResources(activity.resources, targetLocale)
        applyLocaleToResources(activity.applicationContext.resources, targetLocale)

        // 重启 Activity 完成完整重构
        activity.recreate()
    }

    fun wrapContext(baseContext: Context): Context {
        val savedCode = baseContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_APP_LANGUAGE, "") ?: ""
        val lang = AppLanguage.fromCode(savedCode)
        val targetLocale = getLocale(lang, baseContext)

        Locale.setDefault(targetLocale)

        val config = Configuration(baseContext.resources.configuration)
        applyLocaleToConfiguration(config, targetLocale)
        return baseContext.createConfigurationContext(config)
    }
}
