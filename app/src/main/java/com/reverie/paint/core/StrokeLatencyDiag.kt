/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import com.reverie.paint.model.RenderScheduleMode
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 运笔延迟与前缓冲状态内存环形缓冲区。
 *
 * 诊断报告生成时系统 logcat（仅留最后 500 行）往往已被退出画布/系统日志冲刷，
 * 丢失绘画瞬间的 `StrokePerf` 与 `RealInkController` 记录。这里用固定容量的
 * 原生数组在进程内保留最近样本，供 [DiagnosticsManager] 离线提取分析。
 */
internal object StrokeLatencyDiag {
    const val PERF_CAP = 32
    const val EVENT_CAP = 24

    private val lock = Any()

    // ---- StrokePerf 环形缓冲（零额外对象分配）----
    private val uptimeArr = LongArray(PERF_CAP)
    private val nArr = IntArray(PERF_CAP)
    private val mergedArr = IntArray(PERF_CAP)
    private val modeIdArr = IntArray(PERF_CAP)
    private val queueWaitArr = LongArray(PERF_CAP)
    private val kritaArr = LongArray(PERF_CAP)
    private val renderArr = LongArray(PERF_CAP)
    private val e2eArr = LongArray(PERF_CAP)
    private val effectiveDelayArr = LongArray(PERF_CAP)
    private val presentWaitArr = LongArray(PERF_CAP)
    private val frontierErrTenthsArr = IntArray(PERF_CAP)
    private val realInkHeldArr = BooleanArray(PERF_CAP)
    private var perfHead = 0
    private var perfCount = 0

    // ---- RealInk 生命周期事件与计数 ----
    private val eventUptimeArr = LongArray(EVENT_CAP)
    private val eventTextArr = arrayOfNulls<String>(EVENT_CAP)
    private var eventHead = 0
    private var eventCount = 0

    private var armTotal = 0
    private var armHeld = 0
    private var armFallback = 0
    private var midStrokeRejects = 0
    private var bigAreaSkips = 0
    private var lastArmReason = "none"

    private fun nowUptimeMs(): Long = try {
        android.os.SystemClock.uptimeMillis()
    } catch (_: Throwable) {
        System.currentTimeMillis()
    }

    fun recordStrokePerf(
        n: Int,
        merged: Int,
        modeId: Int,
        queueWaitMs: Long,
        kritaMs: Long,
        renderMs: Long,
        e2eMs: Long,
        effectiveDelayMs: Long,
        presentWaitP50Ms: Long,
        frontierErrPx: Float,
        realInkHeld: Boolean,
        uptimeMs: Long = nowUptimeMs(),
    ) {
        val tenths = if (frontierErrPx.isFinite()) (frontierErrPx * 10f).roundToInt() else 0
        synchronized(lock) {
            val slot = if (perfCount < PERF_CAP) {
                (perfHead + perfCount) % PERF_CAP
            } else {
                val s = perfHead
                perfHead = (perfHead + 1) % PERF_CAP
                s
            }
            if (perfCount < PERF_CAP) perfCount++
            uptimeArr[slot] = uptimeMs
            nArr[slot] = n
            mergedArr[slot] = merged
            modeIdArr[slot] = modeId
            queueWaitArr[slot] = queueWaitMs
            kritaArr[slot] = kritaMs
            renderArr[slot] = renderMs
            e2eArr[slot] = e2eMs
            effectiveDelayArr[slot] = effectiveDelayMs
            presentWaitArr[slot] = presentWaitP50Ms
            frontierErrTenthsArr[slot] = tenths
            realInkHeldArr[slot] = realInkHeld
        }
    }

    fun recordRealInkArm(
        held: Boolean,
        reason: String,
        uptimeMs: Long = nowUptimeMs(),
    ) {
        synchronized(lock) {
            armTotal++
            if (held) {
                armHeld++
            } else {
                armFallback++
            }
            lastArmReason = reason
            appendEventLocked("arm held=$held reason=$reason", uptimeMs)
        }
    }

    fun recordRealInkEvent(
        event: String,
        uptimeMs: Long = nowUptimeMs(),
    ) {
        synchronized(lock) {
            if (event.startsWith("hold_reject:")) {
                midStrokeRejects++
            } else if (event.startsWith("skip_big_area:")) {
                bigAreaSkips++
            }
            appendEventLocked(event, uptimeMs)
        }
    }

    private fun appendEventLocked(event: String, uptimeMs: Long) {
        val slot = if (eventCount < EVENT_CAP) {
            (eventHead + eventCount) % EVENT_CAP
        } else {
            val s = eventHead
            eventHead = (eventHead + 1) % EVENT_CAP
            s
        }
        if (eventCount < EVENT_CAP) eventCount++
        eventUptimeArr[slot] = uptimeMs
        eventTextArr[slot] = event
    }

    fun formatForDiagnostics(nowMs: Long = nowUptimeMs()): String {
        val sb = StringBuilder()
        synchronized(lock) {
            sb.append("RealInk Stats: arms=").append(armTotal)
                .append(" held=").append(armHeld)
                .append(" fallback=").append(armFallback)
                .append(" midRejects=").append(midStrokeRejects)
                .append(" bigAreaSkips=").append(bigAreaSkips)
                .append(" lastArmReason=").append(lastArmReason)
                .append('\n')

            sb.append("Recent RealInk Events (").append(eventCount).append("):\n")
            if (eventCount == 0) {
                sb.append("  (none)\n")
            } else {
                for (i in 0 until eventCount) {
                    val idx = (eventHead + i) % EVENT_CAP
                    val ageMs = (nowMs - eventUptimeArr[idx]).coerceAtLeast(0L)
                    sb.append("  [-").append(ageMs).append("ms] ")
                        .append(eventTextArr[idx] ?: "")
                        .append('\n')
                }
            }

            sb.append("Recent StrokePerf Samples (").append(perfCount).append("):\n")
            if (perfCount == 0) {
                sb.append("  (none)\n")
            } else {
                for (i in 0 until perfCount) {
                    val idx = (perfHead + i) % PERF_CAP
                    val ageMs = (nowMs - uptimeArr[idx]).coerceAtLeast(0L)
                    val modeName = RenderScheduleMode.fromId(modeIdArr[idx]).name
                    val errPx = frontierErrTenthsArr[idx] / 10.0f
                    sb.append("  [-").append(ageMs).append("ms] ")
                        .append("held=").append(realInkHeldArr[idx])
                        .append(" mode=").append(modeName)
                        .append(" n=").append(nArr[idx])
                        .append(" merged=").append(mergedArr[idx])
                        .append(" queueWait=").append(queueWaitArr[idx]).append("ms")
                        .append(" krita=").append(kritaArr[idx]).append("ms")
                        .append(" render=").append(renderArr[idx]).append("ms")
                        .append(" e2e=").append(e2eArr[idx]).append("ms")
                        .append(" effDelay=").append(effectiveDelayArr[idx]).append("ms")
                        .append(" presentWait=").append(presentWaitArr[idx]).append("ms")
                        .append(" frontierErr=").append(String.format(Locale.US, "%.1f", errPx)).append("px")
                        .append('\n')
                }
            }
        }
        return sb.toString()
    }

    fun resetForTest() {
        synchronized(lock) {
            perfHead = 0
            perfCount = 0
            eventHead = 0
            eventCount = 0
            eventTextArr.fill(null)
            armTotal = 0
            armHeld = 0
            armFallback = 0
            midStrokeRejects = 0
            bigAreaSkips = 0
            lastArmReason = "none"
        }
    }
}
