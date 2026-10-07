/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import android.util.Log
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * 诊断行为轨迹追踪器 (Breadcrumbs)。
 * 使用轻量环形缓冲区记录用户最近的 25 条关键操作（如工具切换、图层操作、撤销重做、滤镜调用等），
 * 并同步推送到 Native C++ 层的信号安全静态缓冲区，确保无论发生 Java 崩溃还是 Native 信号段错误，
 * 均能在日志中还原崩溃前操作链路。
 */
object Breadcrumbs {
    private const val TAG = "Breadcrumbs"
    private const val MAX_CRUMBS = 100

    private val queue = ArrayDeque<String>(MAX_CRUMBS)
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
    private val lock = Any()

    /**
     * 记录一条行为轨迹
     */
    fun record(category: String, message: String) {
        val timestamp = synchronized(timeFormat) {
            timeFormat.format(Date())
        }
        val entry = "[$timestamp] [$category] $message"

        synchronized(lock) {
            if (queue.size >= MAX_CRUMBS) {
                queue.pollFirst()
            }
            queue.addLast(entry)
        }

        // 同步推送至 Native C++ 信号捕获器缓冲区
        try {
            ReverieCoreBridge.addNativeBreadcrumb(category, message)
        } catch (_: Throwable) {
            // Native 库尚未加载或不可用时静默忽略
        }
    }

    /**
     * 更新当前应用与画布状态快照至 Native 静态缓冲区
     */
    fun updateState(stateJson: String) {
        try {
            ReverieCoreBridge.updateNativeCrashState(stateJson)
        } catch (_: Throwable) {
            // 静默忽略
        }
    }

    /**
     * 导出所有记录的轨迹（供 Java 崩溃日志使用）
     */
    fun dump(): List<String> {
        return synchronized(lock) {
            queue.toList()
        }
    }

    fun clear() {
        synchronized(lock) {
            queue.clear()
        }
    }
}
