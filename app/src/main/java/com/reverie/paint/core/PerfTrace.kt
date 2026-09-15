/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import android.os.SystemClock
import android.util.Log

/**
 * 轻量性能打点。用于定位"交互发涩 / 掉帧"这类无法靠读代码确定的问题。
 *
 * 设计要点:
 *  - **默认关闭**: [enabled] 为 false 时所有函数体只剩一次布尔判断, 热路径
 *    可以放心埋点, 不埋点就永远不知道瓶颈在哪、也永远不敢确定修好了没有;
 *  - 用 tag `ReveriePerf` 统一过滤: `adb logcat -s ReveriePerf`;
 *  - [span] 只在超过 [slowMs] 时打印, 避免刷屏掩盖真正的问题;
 *  - [rate] 用来量"每秒被调用多少次" —— 很多性能问题不是单次慢, 而是
 *    单次不慢但被调用了上千次 (例如手势期间每帧重启一个 effect)。
 *
 * 打开方式: 临时把 [enabled] 改成 true 出包, 或在不改代码的前提下用
 * `adb shell setprop debug.reverie.perf 1` (见 [refreshFromSystemProp])。
 */
object PerfTrace {

    /**
     * 开关。**当前默认打开** —— 用户反馈"交互发涩/掉帧"但现象无法靠读代码定位,
     * 需要先拿到实测数据。等瓶颈确认并修复后改回 false。
     *
     * 打开时的开销: 热路径上多一次 `SystemClock.elapsedRealtimeNanos()` 调用,
     * 以及超阈值时一次 `Log.d`。对定位阶段可接受。
     */
    var enabled: Boolean = true

    /** 慢操作阈值 (ms), 超过才打日志 */
    var slowMs: Long = 4L

    fun log(msg: String) {
        if (!enabled) return
        Log.d("ReveriePerf", msg)
    }

    /**
     * 量一段同步代码的耗时, 超过 [threshold] ms 才打印。
     *
     * 用法: `PerfTrace.span("renderThumb") { doWork() }`
     *
     * 用 `inline` + `crossinline` 让 lambda 内联, 避免每次调用分配一个
     * lambda 对象 —— 这本来就是要在热路径上用的工具, 自己不该再制造开销。
     */
    inline fun <T> span(name: String, threshold: Long = slowMs, block: () -> T): T {
        if (!enabled) return block()
        val t0 = SystemClock.elapsedRealtimeNanos()
        val r = block()
        val ms = (SystemClock.elapsedRealtimeNanos() - t0) / 1_000_000.0
        if (ms >= threshold) {
            Log.d("ReveriePerf", "$name ${"%.2f".format(ms)}ms")
        }
        return r
    }

    /**
     * 频率计数器: 按 [windowMs] 聚合, 每个窗口结束打印一次"本窗口调用次数"。
     * 专治"单次很快但被高频调用"的问题 —— 这类问题看单次耗时永远查不出来。
     */
    private val counters = HashMap<String, LongArray>() // name -> [windowStart, count, totalNs]

    fun tick(name: String, windowMs: Long = 1000L) {
        if (!enabled) return
        val now = SystemClock.elapsedRealtime()
        val slot = counters.getOrPut(name) { longArrayOf(now, 0L, 0L) }
        slot[1]++
        if (now - slot[0] >= windowMs) {
            val n = slot[1]
            val avgUs = if (n > 0) slot[2] / n / 1000.0 else 0.0
            Log.d("ReveriePerf", "$name ${n} 次/${now - slot[0]}ms (平均 ${"%.2f".format(avgUs)}us)")
            slot[0] = now
            slot[1] = 0L
            slot[2] = 0L
        }
    }

    /** [tick] 的计时版: 额外累计耗时, 输出平均单次耗时 */
    fun tickNanos(name: String, nanos: Long, windowMs: Long = 1000L) {
        if (!enabled) return
        val now = SystemClock.elapsedRealtime()
        val slot = counters.getOrPut(name) { longArrayOf(now, 0L, 0L) }
        slot[1]++
        slot[2] += nanos
        if (now - slot[0] >= windowMs) {
            val n = slot[1]
            val avgUs = if (n > 0) slot[2] / n / 1000.0 else 0.0
            Log.d("ReveriePerf", "$name ${n} 次/${now - slot[0]}ms (平均 ${"%.2f".format(avgUs)}us)")
            slot[0] = now
            slot[1] = 0L
            slot[2] = 0L
        }
    }

    /** 允许用系统属性临时打开, 免去为了量一次数据重新出包 */
    fun refreshFromSystemProp() {
        enabled = runCatching {
            SystemPropertiesCompat.getBoolean("debug.reverie.perf", false)
        }.getOrDefault(false)
        if (enabled) Log.d("ReveriePerf", "PerfTrace enabled via debug.reverie.perf")
    }
}

/** 隔离 SystemProperties 的反射, 失败就当作 false (非 rooted/非 debug 设备) */
private object SystemPropertiesCompat {
    private val getBooleanMethod = runCatching {
        Class.forName("android.os.SystemProperties")
            .getMethod("getBoolean", String::class.java, Boolean::class.javaPrimitiveType)
    }.getOrNull()

    fun getBoolean(key: String, def: Boolean): Boolean {
        val m = getBooleanMethod ?: return def
        return runCatching { m.invoke(null, key, def) as? Boolean ?: def }.getOrDefault(def)
    }
}
