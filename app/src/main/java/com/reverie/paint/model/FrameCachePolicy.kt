/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import kotlin.math.abs

/**
 * 帧 RAM 缓存的容量策略。
 *
 * 参考 SpeedyNote 的做法: 缓存容量按**内存预算**而不是固定帧数决定, 淘汰时优先
 * 丢掉"距当前帧最远"的条目 (循环播放时最远的那一帧最晚才会被用到)。
 *
 * 本项目原先的动画回放缓存写死 120 帧 —— 在 4096 画幅下单帧就是 64MB,
 * 120 帧意味着 7.7GB, 必然触顶; 即便 2048 画幅也是 1.9GB。改为字节预算后,
 * 大画幅自动只缓存少量帧, 小画幅仍能缓存上百帧。
 *
 * 纯 Kotlin 实现, 无 Android 依赖, 可 JVM 单测。
 */
object FrameCachePolicy {

    /** 预算下限: 保证小内存设备也能缓存若干帧 */
    const val MIN_BUDGET_BYTES: Long = 32L * 1024 * 1024

    /** 预算上限: 不让帧缓存独占大内存设备的堆 */
    const val MAX_BUDGET_BYTES: Long = 256L * 1024 * 1024

    /** 帧数上限: 小画幅下避免缓存上千帧 */
    const val MAX_FRAMES: Int = 240

    private const val HEAP_DIVISOR: Long = 16L

    /**
     * 由堆上限换算帧缓存预算: 取堆的 1/16, 夹在 [MIN_BUDGET_BYTES, MAX_BUDGET_BYTES]。
     * [maxHeapBytes] 非法 (<=0) 时取下限。
     */
    fun budgetBytes(maxHeapBytes: Long): Long {
        if (maxHeapBytes <= 0L) return MIN_BUDGET_BYTES
        return (maxHeapBytes / HEAP_DIVISOR).coerceIn(MIN_BUDGET_BYTES, MAX_BUDGET_BYTES)
    }

    /**
     * 选出需要淘汰的帧号, 按"距 [keepFrame] 最远优先"排序。
     *
     * [frameBytes] 为帧号 -> 该帧占用字节数。返回空列表表示当前用量在预算内
     * 且未超帧数上限。注意调用方必须自行保护正在显示的位图 (不能回收它)。
     */
    fun framesToEvict(
        frameBytes: Map<Int, Long>,
        budgetBytes: Long,
        keepFrame: Int,
        maxFrames: Int = MAX_FRAMES,
    ): List<Int> {
        if (frameBytes.isEmpty()) return emptyList()
        var total = 0L
        for (v in frameBytes.values) total += v
        var count = frameBytes.size
        val budget = if (budgetBytes <= 0L) MIN_BUDGET_BYTES else budgetBytes
        if (total <= budget && count <= maxFrames) return emptyList()

        val ordered = frameBytes.keys.sortedByDescending { abs(it - keepFrame) }
        val out = ArrayList<Int>()
        for (frame in ordered) {
            if (total <= budget && count <= maxFrames) break
            total -= frameBytes[frame] ?: 0L
            count -= 1
            out.add(frame)
        }
        return out
    }
}
