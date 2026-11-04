/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.telemetry

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.reverie.paint.core.Breadcrumbs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 主线程卡顿看门狗 —— 专治「ANR 上报里看不到卡点」。
 *
 * 背景：ANR 的堆栈来自系统 tombstone 转储，真机上经常拿不到（线上多次见到
 * `libdebuggerd_client: failed to read status response from tombstoned`，
 * 或者转储里主线程已经空闲在 nativePollOnce）。这时遥测里只有"崩了"这一个事实，
 * 没有任何可用线索，等于白上报。
 *
 * 做法：主线程每 [BEAT_MS] 打卡一次；后台线程发现打卡停了 [STALL_MS] 以上，
 * 就抓主线程当前栈顶的**第一条 app 代码帧**，记成一条面包屑
 *   `[HH:MM:SS.mmm] [MainThread] stall 4200ms canvas.CanvasTouchView.onTouchEvent`
 * 并同时落盘一份 —— 因为 ANR 是系统 kill 整个进程，命中后本进程的记录来不及上报
 * （下次启动读到的是新会话的面包屑），只能由 [TelemetryReporter] 在下次启动对着 ANR
 * 时间窗把它取出来拼进报告。
 *
 * 只记录"应用自己的代码帧"，且只留 类名.方法名，不含文件路径/行号/任何用户内容
 * （与 docs/TELEMETRY.md §4 禁止清单一致）。
 *
 * 开销：主线程每 500ms 一次空 Runnable；后台线程每 500ms 唤醒一次（正常时只读一个
 * volatile，不分配）。
 */
object MainThreadWatchdog {
    private const val TAG = "MainStall"

    /** 主线程打卡周期 */
    private const val BEAT_MS = 500L

    /** 打卡停多久算卡顿（系统 ANR 阈值 5s，这里提前抓，保证一定先于 kill 落盘） */
    private const val STALL_MS = 2_500L

    /** 一条卡的帧描述上限（服务端 crumb 是短标签） */
    private const val MAX_FRAME_CHARS = 64

    /** 一分钟最多记几次，防止持续卡顿把面包屑刷满 */
    private const val MAX_RECORDS_PER_MINUTE = 3

    private const val PREFS = "revp_anr_diag"
    private const val KEY_LAST_STALL_LINE = "last_stall_line"
    private const val KEY_LAST_STALL_AT = "last_stall_at"

    @Volatile
    private var started = false

    /** 卡顿判定状态机（纯逻辑，单测见 StallDetectorTest） */
    private val detector = StallDetector(STALL_MS, MAX_RECORDS_PER_MINUTE)

    private var appContext: Context? = null

    private val mainHandler: Handler by lazy { Handler(Looper.getMainLooper()) }
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    private val beat =
        object : Runnable {
            override fun run() {
                detector.onBeat(SystemClock.uptimeMillis())
                mainHandler.postDelayed(this, BEAT_MS)
            }
        }

    fun start(context: Context) {
        if (started) return
        started = true
        appContext = context.applicationContext
        mainHandler.post(beat)
        Thread(::watchLoop, "revp-main-watchdog").apply { isDaemon = true }.start()
        Log.i(TAG, "watchdog started (beat=${BEAT_MS}ms stall=${STALL_MS}ms)")
    }

    private fun watchLoop() {
        while (started) {
            try {
                Thread.sleep(BEAT_MS)
                val now = SystemClock.uptimeMillis()
                if (detector.poll(now)) {
                    recordStall(detector.stalledMs(now))
                }
            } catch (_: InterruptedException) {
                return
            } catch (t: Throwable) {
                Log.w(TAG, "watchdog loop: ${t.message}")
            }
        }
    }

    private fun recordStall(stalledMs: Long) {
        val frame = topAppFrame()
        val message = "stall ${stalledMs}ms $frame"
        val line = "[${timeFormat.format(Date())}] [MainThread] $message"
        Log.w(TAG, line)
        // 进程内可见（Java/native 崩溃报告的 crumbs 段都会带上）
        runCatching { Breadcrumbs.record("MainThread", message) }
        // 落盘一份，供下次启动的 ANR 上报取用
        runCatching {
            appContext
                ?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                ?.edit()
                ?.putString(KEY_LAST_STALL_LINE, line)
                ?.putLong(KEY_LAST_STALL_AT, System.currentTimeMillis())
                ?.apply()
        }
    }

    /**
     * 主线程栈里第一条应用代码帧，规约成 `包名末段.类名.方法名`（去行号/文件路径）。
     * 拿不到应用帧时退回栈顶帧的类名+方法名（同样是代码标识，不含用户内容）。
     */
    private fun topAppFrame(): String =
        runCatching {
            val stack = Looper.getMainLooper().thread.stackTrace
            if (stack.isEmpty()) return "unknown"
            // 跳过看门狗自己的帧: 极端情况下(例如进程被 SIGSTOP 冻住再恢复)主线程恰好停在
            // 本类的打卡代码里, 那样的帧对定位毫无价值
            val frame =
                stack.firstOrNull {
                    it.className.startsWith(APP_PACKAGE) && !it.className.contains("MainThreadWatchdog")
                } ?: stack.firstOrNull { it.className.startsWith(APP_PACKAGE) } ?: stack[0]
            val cls =
                frame.className
                    .removePrefix("$APP_PACKAGE.")
                    .takeIf { it.isNotEmpty() }
                    ?: frame.className
            "$cls.${frame.methodName}".take(MAX_FRAME_CHARS)
        }.getOrElse { "unknown" }

    /**
     * 取"上一次进程被 kill 之前"落盘的主线程卡点；与 ANR 时刻对不上（早于 [anrAtMs] 之前
     * 10 分钟以上的陈旧记录）就返回 null，避免把无关卡顿塞进本次 ANR。
     *
     * 返回的是与 [Breadcrumbs] 同格式的一行，可直接混进面包屑列表。
     */
    internal fun lastStallLineFor(context: Context, anrAtMs: Long): String? =
        runCatching {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val line = prefs.getString(KEY_LAST_STALL_LINE, null)?.takeIf { it.isNotBlank() } ?: return null
            val at = prefs.getLong(KEY_LAST_STALL_AT, 0L)
            if (!isStallWithinWindow(at, anrAtMs)) return null
            line
        }.getOrNull()

    /**
     * 落盘卡点是否属于这次 ANR：允许比 ANR 早最多 10 分钟（系统从超时到 dump 到 kill 有延迟），
     * 或晚 1 分钟（时钟/采集误差）；更早的陈旧记录一律不认。
     */
    internal fun isStallWithinWindow(stallAtMs: Long, anrAtMs: Long): Boolean {
        if (stallAtMs <= 0L || anrAtMs <= 0L) return false
        return stallAtMs >= anrAtMs - STALL_LOOKBACK_MS && stallAtMs <= anrAtMs + STALL_LOOKAHEAD_MS
    }

    internal const val STALL_LOOKBACK_MS = 10 * 60 * 1000L
    internal const val STALL_LOOKAHEAD_MS = 60 * 1000L

    private const val APP_PACKAGE = "com.reverie.paint"
}

/**
 * 卡顿判定：主线程打卡 + 后台轮询，两者共享这一个状态机。
 *
 * 之所以单独成类，是因为它的边界条件（第一次打卡之前、同一轮卡顿重复轮询、限流窗口）
 * 都是纯数学，可以在 JVM 单测里把时序钉死，不必靠真机碰运气。
 */
internal class StallDetector(
    private val stallMs: Long,
    private val maxPerWindow: Int = 3,
    private val windowMs: Long = 60_000L,
) {
    private var lastBeatAt = 0L

    /** 本轮卡顿是否已经记过（打卡恢复后自动解除） */
    private var recorded = false

    private var windowStartAt = 0L
    private var windowCount = 0

    fun onBeat(now: Long) {
        lastBeatAt = now
        recorded = false
    }

    /** true 表示"这次卡顿该记一条"；同一轮卡顿只返回一次 true */
    fun poll(now: Long): Boolean {
        if (lastBeatAt <= 0L || recorded) return false
        if (now - lastBeatAt < stallMs) return false
        recorded = true
        if (now - windowStartAt > windowMs) {
            windowStartAt = now
            windowCount = 0
        }
        if (windowCount >= maxPerWindow) return false
        ++windowCount
        return true
    }

    fun stalledMs(now: Long): Long = now - lastBeatAt
}
