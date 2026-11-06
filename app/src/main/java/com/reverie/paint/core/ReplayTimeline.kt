/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import com.reverie.paint.model.RecordingReader

/**
 * 回放空闲压缩上限 (ms)。录制的 dt 是真实墙钟间隔, 停笔、开菜单、切走 App 的
 * 空闲时间会原样进入时间线 (实测有占总时长 60%~86% 的单段空闲), 导致进度条
 * 大段区间画面不变、拖到中间一继续就跳到末尾。回放时每个事件 dt 封顶到该值。
 */
internal const val REPLAY_IDLE_CAP_MS = 1000L

/**
 * 回放"有效时间线"的纯逻辑 (无 Android / JNI 依赖, 便于 JVM 单测)。
 * step / seek / 导出 / 进度统一经由这里换算, 保证四者一致。
 */
internal object ReplayTimeline {
    /** 单个事件的有效 dt: 原始 dt 封顶到 [capMs]。 */
    fun effectiveDt(
        dt: Int,
        capMs: Long,
    ): Long = dt.toLong().coerceIn(0L, capMs)

    /**
     * 扫描整段事件流 (只跳 payload, 不派发) 计算有效总时长。
     * 解析异常、或 [expectedEventCount] >= 0 且与实际事件数不符 (疑似错位) 时返回 -1,
     * 调用方据此退回原始时间线。
     */
    fun effectiveTotalMs(
        events: ByteArray,
        version: Int,
        capMs: Long,
        expectedEventCount: Int = -1,
    ): Long {
        val r = RecordingReader(events)
        var total = 0L
        var count = 0
        try {
            while (r.remaining() > 0) {
                val type = r.u8()
                total += effectiveDt(r.varint(), capMs)
                skipReplayEventPayload(type, r, version)
                count++
            }
        } catch (e: Exception) {
            return -1L
        }
        if (expectedEventCount >= 0 && count != expectedEventCount) return -1L
        return total
    }

    /** 进度条位置 → 有效时间目标。 */
    fun targetMsFor(
        fraction: Float,
        totalMs: Long,
    ): Long = (fraction.coerceIn(0f, 1f).toDouble() * totalMs).toLong()

    /** 有效时间 → 进度 (0..1); totalMs<=0 视为已结束。 */
    fun progressOf(
        currentMs: Long,
        totalMs: Long,
    ): Float = if (totalMs > 0) (currentMs.toFloat() / totalMs).coerceIn(0f, 1f) else 1f

    /** 播放一个 tick 的目标有效时间; totalMs<=0 时为 Long.MAX_VALUE (逐事件推进)。 */
    fun stepTargetMs(
        currentMs: Long,
        stepSimMs: Long,
        totalMs: Long,
    ): Long = if (totalMs > 0) (currentMs + stepSimMs).coerceAtMost(totalMs) else Long.MAX_VALUE

    /** seek 结束后是否处于末尾: 拖到 100% 或事件已全部消费。 */
    fun isAtEnd(
        fraction: Float,
        remainingBytes: Int,
    ): Boolean = fraction >= 1f || remainingBytes <= 0

    /**
     * 从 [r] 当前位置推进到 [targetMs]，**只应用时间戳不晚于目标的事件**。
     *
     * 与 [advance] 的区别：playback 需要"跨过目标的那个事件也执行"（时间到了就画），
     * 而 seek 必须落在**准确的**位置上 —— 若沿用跨过规则，一次 seek 可能把目标之后
     * 很远的事件（如 16ms 之后的一次 undo）提前执行掉，画面就与进度条不符：
     * 拖到 25% 却出现 50% 之后才该有的内容，继续播放又从错误状态开始。
     */
    inline fun advanceExclusive(
        r: RecordingReader,
        startMs: Long,
        targetMs: Long,
        capMs: Long,
        onEvent: (type: Int, nowMs: Long) -> Unit,
    ): Long {
        var cur = startMs
        while (r.remaining() > 0) {
            val mark = r.pos
            val type = r.u8()
            val dt = effectiveDt(r.varint(), capMs)
            if (cur + dt > targetMs) {
                r.pos = mark // 停在边界前, 这条事件留给继续播放
                break
            }
            cur += dt
            onEvent(type, cur)
        }
        return cur
    }

    /**
     * 从 [r] 当前位置按有效时间线推进: 读出 type 与 dt, 累加有效 dt 后调用
     * [onEvent] (由它读取/派发 payload)。drainAll=true 时消费全部剩余事件;
     * 否则推进到有效时间 >= [targetMs] 为止 (跨过 target 的那个事件会被执行)。
     * singleEvent=true 时最多执行一个事件。返回推进后的有效时间。
     */
    inline fun advance(
        r: RecordingReader,
        startMs: Long,
        targetMs: Long,
        drainAll: Boolean,
        capMs: Long,
        singleEvent: Boolean = false,
        onEvent: (type: Int, nowMs: Long) -> Unit,
    ): Long {
        var cur = startMs
        while (r.remaining() > 0 && (drainAll || cur < targetMs)) {
            val type = r.u8()
            cur += effectiveDt(r.varint(), capMs)
            onEvent(type, cur)
            if (singleEvent) break
        }
        return cur
    }
}
