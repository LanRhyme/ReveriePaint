/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.telemetry

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi

/**
 * ANR 采集。Java 的 `UncaughtExceptionHandler` 抓不到 ANR, 自研 signal handler 也抓不到,
 * 但 Android 11+ 的系统历史退出原因里有主线程 trace —— 这是唯一免费且可靠的来源。
 *
 * 只在启动时读一次(一次 binder 调用), 不参与任何热路径。
 */
internal object AnrCollector {

    private const val MAX_ENTRIES = 10
    private const val MAX_TRACE_CHARS = 64 * 1024

    data class AnrEntry(val timestampMs: Long, val trace: String)

    fun isSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    @RequiresApi(Build.VERSION_CODES.R)
    fun collect(context: Context, sinceMs: Long): List<AnrEntry> {
        val am = context.getSystemService(ActivityManager::class.java) ?: return emptyList()
        val reasons = runCatching {
            am.getHistoricalProcessExitReasons(null, 0, MAX_ENTRIES)
        }.getOrNull() ?: return emptyList()

        return reasons.asSequence()
            .filter { it.reason == android.app.ApplicationExitInfo.REASON_ANR }
            .filter { it.timestamp > sinceMs }
            .sortedBy { it.timestamp }
            .mapNotNull { info ->
                val trace = runCatching {
                    info.traceInputStream?.use { stream ->
                        // 不用 readNBytes(API 33+), 手写循环以兼容 minSdk 23
                        val out = java.io.ByteArrayOutputStream(8192)
                        val buf = ByteArray(8192)
                        while (out.size() < MAX_TRACE_CHARS) {
                            val n = stream.read(buf, 0, minOf(buf.size, MAX_TRACE_CHARS - out.size()))
                            if (n <= 0) break
                            out.write(buf, 0, n)
                        }
                        String(out.toByteArray(), Charsets.UTF_8)
                    }
                }.getOrNull()
                if (trace.isNullOrBlank()) {
                    // trace 拿不到时只上报"发生过 ANR"这件事本身, 也有价值(能对上机型/版本)
                    AnrEntry(info.timestamp, info.description.orEmpty())
                } else {
                    AnrEntry(info.timestamp, trace)
                }
            }
            .toList()
    }
}
