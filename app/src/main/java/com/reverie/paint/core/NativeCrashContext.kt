/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * 把"崩的那一刻在干什么"持续喂给原生信号处理器。
 *
 * 原生侧的状态快照是一块静态缓冲区, 只能在崩溃处理里读; 而画布尺寸、图层数、当前页面/工具
 * 都是随用户操作变化的。如果只在启动时灌一次(历史实现), native 崩溃的这份上下文永远是刚启动时的样子,
 * 上报出来的 doc 字段全是 0。
 *
 * 这里做两件事:
 *  1. [attach] 启动时灌一次基线
 *  2. [requestRefresh] 在用户操作后按需刷新 —— 由 [Breadcrumbs.record] 统一触发,
 *     因为那恰好覆盖了"切工具 / 加删图层 / 打开工程 / 切页面"这些关键点
 *
 * 刷新的代价很小(拼一段字符串 + 一次 JNI 字符串拷贝), 但仍然限流到 1.5 秒一次、
 * 且内容没变就不重复写, 避免在快速连续操作时做无谓的工作。
 */
internal object NativeCrashContext {

    private const val MIN_INTERVAL_MS = 1500L

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var lastPushAt = 0L

    @Volatile
    private var lastPayload: String = ""

    fun attach(context: Context) {
        appContext = context.applicationContext
        push(force = true)
    }

    /** 用户刚做了一次操作, 如果距上次刷新够久就再推一次(切到主线程做, 避免读 Compose 状态跨线程) */
    fun requestRefresh() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastPushAt < MIN_INTERVAL_MS) return
        lastPushAt = now
        if (Looper.myLooper() == Looper.getMainLooper()) {
            push()
        } else {
            mainHandler.post { push() }
        }
    }

    private fun push(force: Boolean = false) {
        val context = appContext ?: return
        val payload = runCatching { CrashHandler.buildNativeState(context) }.getOrNull() ?: return
        if (!force && payload == lastPayload) return
        lastPayload = payload
        runCatching { Breadcrumbs.updateState(payload) }
    }
}
