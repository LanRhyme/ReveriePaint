/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import android.os.SystemClock
import android.util.Log
import java.util.Arrays

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
 * 打开方式(三者任一):
 *  - 设置页 → 通用 → "性能标尺" 开关 (写入 `paint_prefs`, 重启后保持);
 *  - `adb shell setprop debug.reverie.perf 1` (见 [refreshFromSystemProp]);
 *  - 临时把 [enabled] 改成 true 出包。
 *
 * ## 分桶指标 (2026-09, 为"纹理重传 / 保存耗时"决策提供标尺)
 *
 * 打开后屏幕左上角会出现 HUD, 同时每秒往 logcat 打一行窗口摘要。指标定义:
 *  - `path full/incr/skip`: 引擎渲染路径分桶。`skip` = 引擎判定无脏区、返回 false
 *    (最省的一档); `full` = 缓冲重分配或有强制全量标记;
 *  - `flip`: 显示缓冲交换(翻转)次数。**每翻转一次, 下次绘制 HWUI 就要把整张
 *    Bitmap 纹理重传一次** —— 这是本项目最大的带宽开销, 故直接以
 *    "翻转次数 × 缓冲字节" 作为纹理重传的代理量(实测上限, 因为 HWUI 不做局部更新);
 *  - `脏比`: 本窗口写入的脏区像素 / 缓冲总像素。**这个数字就是 tile 化缓冲的收益上限**:
 *    脏比 5% 意味着分块上传最多能把每帧带宽降到 1/20;
 *  - `draw p95`: `CanvasTouchView.onDraw` 的耗时 p95 (UI 侧绘制命令录制, 不含 GPU 上传);
 *  - `save`: 上一次保存的 C++ 阶段耗时(快照/编码/写盘)与产物体积, 见
 *    `revpSaveStats`。
 */
object PerfTrace {

    /**
     * 开关。**默认关闭** —— 打开时每次 [span]/[tick] 都要取时间并查一次 HashMap,
     * 而 [tick] 每秒还会写若干条 `Log.d` (系统调用 + 文件写入), 120Hz 高刷下不是白送。
     */
    @Volatile
    var enabled: Boolean = false

    /**
     * 是否由系统属性 (`debug.reverie.perf`) 打开。设置页关闭标尺时不影响它 ——
     * 现场量测(adb 可用)与日常开关(设置页)是两条独立开关, 任一为真即为开。
     */
    @Volatile
    var isEnabledByProp: Boolean = false

    /** 慢操作阈值 (ms), 超过才打日志 */
    var slowMs: Long = 4L

    // ------------------------------------------------------------------
    // 渲染路径分桶 (固定数组 + @Synchronized, 热路径零分配)
    // ------------------------------------------------------------------
    const val PATH_FULL = 0
    const val PATH_INCR = 1
    const val PATH_SKIP = 2
    private const val PATH_N = 3
    private val PATH_NAMES = arrayOf("full", "incr", "skip")

    private val pathCount = LongArray(PATH_N)
    private val pathNanos = LongArray(PATH_N)
    private val pathPixels = LongArray(PATH_N)

    private var flipCount = 0L          // 缓冲翻转次数 (= 整张纹理重传次数的下限)
    private var flipBytes = 0L          // 翻转 × 缓冲字节
    private var scaledCount = 0L        // 走缩放回退路径的次数
    private var dirtyPixels = 0L        // 写入脏区像素
    private var bufferPixels = 0L       // 缓冲像素 (用于算脏比)

    // draw 耗时 p95: 环形缓冲 + 排序副本, 两者都是固定数组 ⇒ 零分配
    private const val RING = 256
    private val drawRing = LongArray(RING)
    private val drawSort = LongArray(RING)
    private var drawIdx = 0
    private var drawN = 0

    // 上一次保存 (C++ 侧阶段耗时, 单位 ms; 由 revpSaveStats 填入)
    private var saveTotalMs = -1L
    private var saveSnapshotMs = -1L
    private var saveEncodeMs = -1L
    private var saveWriteMs = -1L
    private var savePngCount = -1L
    private var savePngBytes = -1L
    private var saveFileBytes = -1L
    private var saveWasAsync = false

    private var windowStart = 0L
    private var hudCache = ""
    private var hudCacheMs = 0L

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

    // ------------------------------------------------------------------
    // 分桶记录 API (调用方只需在热路径上调用, 关闭时每次一次布尔判断)
    // ------------------------------------------------------------------

    /** 记录一帧引擎渲染落在哪条路径上。[pixels] 为本次实际写入的像素数(0 表示无写入)。 */
    @Synchronized
    fun renderPath(path: Int, nanos: Long, pixels: Long) {
        if (!enabled) return
        if (path < 0 || path >= PATH_N) return
        pathCount[path]++
        pathNanos[path] += nanos
        if (pixels > 0) {
            pathPixels[path] += pixels
            dirtyPixels += pixels
        }
        rollWindowLocked()
    }

    /** 缓冲区尺寸与文档不一致 (走了缩放回退路径) */
    @Synchronized
    fun renderScaled() {
        if (!enabled) return
        scaledCount++
    }

    /**
     * 显示缓冲翻转 (= 下一帧 HWUI 会整张纹理重传)。
     * [bytes] 为缓冲字节数, [bufferPixelsFrame] 用于算脏比的分母。
     */
    @Synchronized
    fun renderFlip(bytes: Long, bufferPixelsFrame: Long) {
        if (!enabled) return
        flipCount++
        flipBytes += bytes
        if (bufferPixelsFrame > 0L) bufferPixels = bufferPixelsFrame
        rollWindowLocked()
    }

    /** CanvasTouchView.onDraw 的耗时 (UI 线程) */
    @Synchronized
    fun drawFrame(nanos: Long) {
        if (!enabled) return
        drawRing[drawIdx] = nanos
        drawIdx = (drawIdx + 1) % RING
        if (drawN < RING) drawN++
        rollWindowLocked()
    }

    /** C++ 侧回报的上一次保存阶段耗时 (见 `revpSaveStats`) */
    @Synchronized
    fun saveStats(
        totalMs: Long,
        snapshotMs: Long,
        encodeMs: Long,
        writeMs: Long,
        pngCount: Long,
        pngBytes: Long,
        fileBytes: Long,
        wasAsync: Boolean,
    ) {
        if (!enabled) return
        saveTotalMs = totalMs
        saveSnapshotMs = snapshotMs
        saveEncodeMs = encodeMs
        saveWriteMs = writeMs
        savePngCount = pngCount
        savePngBytes = pngBytes
        saveFileBytes = fileBytes
        saveWasAsync = wasAsync
        hudCacheMs = 0L
    }

    // ------------------------------------------------------------------
    // 窗口汇总 / 输出
    // ------------------------------------------------------------------

    private fun rollWindowLocked() {
        val now = SystemClock.elapsedRealtime()
        if (windowStart == 0L) {
            windowStart = now
            return
        }
        val span = now - windowStart
        if (span < 1000L) return
        dumpLocked(now, span)
    }

    private fun dumpLocked(now: Long, spanMs: Long) {
        val sb = StringBuilder(192)
        sb.append("win ").append(spanMs).append("ms | ")
        for (i in 0 until PATH_N) {
            if (pathCount[i] == 0L) continue
            sb.append(PATH_NAMES[i]).append(' ').append(pathCount[i])
                .append("(").append(pathNanos[i] / pathCount[i] / 1_000_000).append("ms) ")
        }
        if (scaledCount > 0L) sb.append("| scaled ").append(scaledCount).append(' ')
        val dirtyPct = if (bufferPixels > 0L) dirtyPixels * 100.0 / bufferPixels else 0.0
        sb.append("| flip ").append(flipCount).append(' ')
        if (flipCount > 0L) {
            val perFrameMb = flipBytes / flipCount / 1048576.0
            val perSecMb = flipBytes / (spanMs / 1000.0) / 1048576.0
            sb.append("重传 ").append("%.1f".format(perFrameMb)).append("MB/帧 ")
                .append("%.0f".format(perSecMb)).append("MB/s ")
        }
        sb.append("脏比 ").append("%.1f".format(dirtyPct)).append("% ")
        if (drawN > 0) {
            sb.append("| draw p95 ").append("%.2f".format(p95Locked(drawRing, drawSort, drawN) / 1e6))
                .append("ms avg ").append("%.2f".format(avgLocked(drawRing, drawN) / 1e6)).append("ms")
        }
        Log.d("ReveriePerf", sb.toString())

        resetWindowLocked(now)
    }

    private fun resetWindowLocked(now: Long) {
        for (i in 0 until PATH_N) {
            pathCount[i] = 0L
            pathNanos[i] = 0L
            pathPixels[i] = 0L
        }
        flipCount = 0L
        flipBytes = 0L
        scaledCount = 0L
        dirtyPixels = 0L
        drawIdx = 0
        drawN = 0
        windowStart = now
    }

    private fun avgLocked(a: LongArray, n: Int): Double {
        if (n <= 0) return 0.0
        var s = 0L
        for (i in 0 until n) s += a[i]
        return s.toDouble() / n
    }

    private fun p95Locked(src: LongArray, scratch: LongArray, n: Int): Double {
        if (n <= 0) return 0.0
        System.arraycopy(src, 0, scratch, 0, n)
        Arrays.sort(scratch, 0, n)
        return scratch[((n - 1) * 95 / 100).coerceIn(0, n - 1)].toDouble()
    }

    /**
     * HUD 文本 (屏幕左上角)。最多每 250ms 重建一次, 避免每帧拼字符串。
     * 返回空串表示暂无数据。
     */
    @Synchronized
    fun hudText(): String {
        if (!enabled) return ""
        val now = SystemClock.elapsedRealtime()
        if (now - hudCacheMs < 250L && hudCache.isNotEmpty()) return hudCache
        hudCacheMs = now

        val span = if (windowStart == 0L) 0L else (now - windowStart).coerceAtLeast(1L)
        val sb = StringBuilder(160)
        // 第 1 行: 渲染路径分布 + 脏比
        sb.append("render ")
        for (i in 0 until PATH_N) {
            if (i > 0) sb.append('/')
            sb.append(PATH_NAMES[i]).append(' ').append(pathCount[i])
        }
        val dirtyPct = if (bufferPixels > 0L) dirtyPixels * 100.0 / bufferPixels else 0.0
        sb.append("  脏比 ").append("%.1f".format(dirtyPct)).append('%')
        if (scaledCount > 0L) sb.append("  scaled ").append(scaledCount)
        sb.append('\n')

        // 第 2 行: 纹理重传代理 + draw 耗时。
        // 窗口不足 500ms 时不报 fps —— 否则"窗口刚重置 + 1 次翻转"会被折算成 100+/s 的
        // 伪读数 (真机实测见过 flip 140/s 这种不可能的数字, 会误导判断)。
        val fps = if (span >= 500L) flipCount * 1000.0 / span else -1.0
        sb.append("flip ").append(if (fps < 0) "--" else "%.1f".format(fps)).append("/s")
        if (flipCount > 0L) {
            sb.append("  重传 ").append("%.1f".format(flipBytes / flipCount / 1048576.0)).append("MB/帧")
        }
        if (drawN > 0) {
            sb.append("  draw p95 ").append("%.2f".format(p95Locked(drawRing, drawSort, drawN) / 1e6)).append("ms")
        }
        sb.append('\n')

        // 第 3 行: 上一次保存
        sb.append("save ")
        if (saveTotalMs < 0) {
            sb.append("--")
        } else {
            sb.append(saveTotalMs).append("ms 快照 ").append(saveSnapshotMs)
                .append("/编码 ").append(saveEncodeMs).append("/写盘 ").append(saveWriteMs)
            sb.append(' ').append(savePngCount).append("PNG ")
                .append("%.0f".format(savePngBytes / 1048576.0)).append("MB→")
                .append("%.0f".format(saveFileBytes / 1048576.0)).append("MB")
            if (saveWasAsync) sb.append(" async")
        }
        hudCache = sb.toString()
        return hudCache
    }

    /** 允许用系统属性临时打开, 免去为了量一次数据重新出包 */
    fun refreshFromSystemProp() {
        isEnabledByProp = runCatching {
            SystemPropertiesCompat.getBoolean("debug.reverie.perf", false)
        }.getOrDefault(false)
        // 只负责"打开", 不负责关闭: 设置页里的开关可能已经把它打开了
        if (isEnabledByProp) {
            enabled = true
            Log.d("ReveriePerf", "PerfTrace enabled via debug.reverie.perf")
        }
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
