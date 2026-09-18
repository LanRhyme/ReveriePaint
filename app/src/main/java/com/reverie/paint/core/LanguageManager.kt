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
import java.util.Locale

enum class AppLanguage(
    val code: String,
    val nativeName: String,
    val englishName: String,
) {
    FOLLOW_SYSTEM("", "跟随系统", "Follow System"),
    ZH_CN("zh-CN", "简体中文", "Simplified Chinese"),
    EN("en", "English", "English");

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
    }

    fun getLocale(language: AppLanguage = currentLanguage): Locale {
        return when (language) {
            AppLanguage.ZH_CN -> Locale.SIMPLIFIED_CHINESE
            AppLanguage.EN -> Locale.ENGLISH
            AppLanguage.FOLLOW_SYSTEM -> {
                val sysLocales = Resources.getSystem().configuration.locales
                if (!sysLocales.isEmpty) sysLocales[0] else Locale.getDefault()
            }
        }
    }

    fun isChinese(language: AppLanguage = currentLanguage): Boolean {
        return getLocale(language).language.startsWith("zh")
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

        // 更新应用资源配置以达到即时热刷新
        val targetLocale = getLocale(language)
        Locale.setDefault(targetLocale)

        val res = activity.resources
        val config = Configuration(res.configuration)
        config.setLocale(targetLocale)
        @Suppress("DEPRECATION")
        res.updateConfiguration(config, res.displayMetrics)

        val appRes = activity.applicationContext.resources
        val appConfig = Configuration(appRes.configuration)
        appConfig.setLocale(targetLocale)
        @Suppress("DEPRECATION")
        appRes.updateConfiguration(appConfig, appRes.displayMetrics)

        // 重启 Activity 完成完整重构
        activity.recreate()
    }

    fun wrapContext(baseContext: Context): Context {
        val savedCode = baseContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_APP_LANGUAGE, "") ?: ""
        val lang = AppLanguage.fromCode(savedCode)
        val targetLocale = getLocale(lang)

        Locale.setDefault(targetLocale)

        val config = Configuration(baseContext.resources.configuration)
        config.setLocale(targetLocale)
        return baseContext.createConfigurationContext(config)
    }
}
