/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.telemetry

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * 遥测的本地状态: 开关、安装标识、上报水位、退避与配额。
 *
 * 单独用 `reverie_telemetry` 这个 prefs 文件, 不塞进 `paint_prefs`:
 * 用户关掉上报时必须能一次性、干净地清掉这里的全部内容(含 installId)。
 */
internal class TelemetryPrefs(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** 默认开启; 用户关掉后置 false */
    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, true)
        set(value) {
            prefs.edit().putBoolean(KEY_ENABLED, value).apply()
            if (!value) wipeUploadState()
        }

    /** 服务端回 401/403/410 后置 true; 用户重新打开开关才会清除 */
    var serverDisabled: Boolean
        get() = prefs.getBoolean(KEY_SERVER_DISABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_SERVER_DISABLED, value).apply()

    /** 首启告知条是否已展示过 */
    var noticeSeen: Boolean
        get() = prefs.getBoolean(KEY_NOTICE_SEEN, false)
        set(value) = prefs.edit().putBoolean(KEY_NOTICE_SEEN, value).apply()

    /**
     * 随机安装标识, 仅用于服务端配额与去重。首次读取时生成;
     * 关闭上报会删除它, 再次打开会得到新值(不可回溯关联)。
     */
    val installId: String
        get() = prefs.getString(KEY_INSTALL_ID, null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString(KEY_INSTALL_ID, it).apply()
        }

    var failCount: Int
        get() = prefs.getInt(KEY_FAIL_COUNT, 0)
        set(value) = prefs.edit().putInt(KEY_FAIL_COUNT, value).apply()

    var nextAttemptAtMs: Long
        get() = prefs.getLong(KEY_NEXT_ATTEMPT, 0L)
        set(value) = prefs.edit().putLong(KEY_NEXT_ATTEMPT, value).apply()

    var lastAnrTs: Long
        get() = prefs.getLong(KEY_LAST_ANR, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_ANR, value).apply()

    // ------------------------------------------------------------ 配额

    /** 当日已成功上报条数(UTC 自然日, 与服务端窗口对齐) */
    fun todayCount(): Int {
        val today = dayKey()
        if (prefs.getString(KEY_DAY_KEY, null) != today) return 0
        return prefs.getInt(KEY_DAY_COUNT, 0)
    }

    fun bumpToday() {
        val today = dayKey()
        val current = if (prefs.getString(KEY_DAY_KEY, null) == today) prefs.getInt(KEY_DAY_COUNT, 0) else 0
        prefs.edit().putString(KEY_DAY_KEY, today).putInt(KEY_DAY_COUNT, current + 1).apply()
    }

    // ------------------------------------------------------------ 水位

    fun isUploaded(fileName: String): Boolean =
        prefs.getStringSet(KEY_UPLOADED, emptySet())?.contains(fileName) == true

    fun markUploaded(fileName: String) {
        val next = (prefs.getStringSet(KEY_UPLOADED, emptySet()) ?: emptySet())
            .toMutableSet()
            .apply { add(fileName) }
        // 只留最近 100 条, 防止无限增长
        val trimmed = if (next.size > MAX_UPLOADED_MARKS) next.toList().takeLast(MAX_UPLOADED_MARKS).toSet() else next
        prefs.edit().putStringSet(KEY_UPLOADED, trimmed).apply()
    }

    /** 关闭上报时清空全部可以关联到用户的内容 */
    fun wipeUploadState() {
        prefs.edit()
            .remove(KEY_INSTALL_ID)
            .remove(KEY_UPLOADED)
            .remove(KEY_DAY_KEY)
            .remove(KEY_DAY_COUNT)
            .remove(KEY_FAIL_COUNT)
            .remove(KEY_NEXT_ATTEMPT)
            .remove(KEY_LAST_ANR)
            .apply()
    }

    /** 重新打开上报时调用: 清掉服务端停用标记, 让用户的选择优先 */
    fun reviveAfterUserEnable() {
        serverDisabled = false
        failCount = 0
        nextAttemptAtMs = 0L
    }

    private fun dayKey(): String {
        val fmt = SimpleDateFormat("yyyyMMdd", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(Date())
    }

    private companion object {
        const val FILE = "reverie_telemetry"
        const val KEY_ENABLED = "enabled"
        const val KEY_SERVER_DISABLED = "server_disabled"
        const val KEY_NOTICE_SEEN = "notice_seen"
        const val KEY_INSTALL_ID = "install_id"
        const val KEY_UPLOADED = "uploaded"
        const val KEY_DAY_KEY = "day_key"
        const val KEY_DAY_COUNT = "day_count"
        const val KEY_FAIL_COUNT = "fail_count"
        const val KEY_NEXT_ATTEMPT = "next_attempt_at"
        const val KEY_LAST_ANR = "last_anr_ts"
        const val MAX_UPLOADED_MARKS = 100
    }
}
