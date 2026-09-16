/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import kotlin.math.abs

/**
 * 时间轴帧块推挤重排布局计算结果。
 */
data class TimelineDragLayout(
    val movingSpan: Int,
    val targetSlot: Int,
    val targetStartFrame: Int,
    /** 原帧时间 -> 重排后目标起始帧号 */
    val blockTargetFrames: Map<Int, Int>,
)

object TimelineReorderHelper {

    /**
     * 计算拖拽过程中的推挤避让目标槽位与各帧目标起始帧。
     *
     * @param times 当前轨道关键帧时间列表 (升序)
     * @param fromTime 被拖拽帧的起始时间
     * @param dragDx 拖拽在横向上的累计像素位移
     * @param frameW 单帧的像素宽度
     */
    fun computeDragLayout(
        times: List<Int>,
        fromTime: Int,
        dragDx: Float,
        frameW: Float,
    ): TimelineDragLayout {
        val fromIdx = times.indexOf(fromTime)
        if (fromIdx < 0 || times.isEmpty()) {
            return TimelineDragLayout(1, 0, fromTime, emptyMap())
        }

        val spans = times.indices.map { i ->
            if (i + 1 < times.size) (times[i + 1] - times[i]).coerceAtLeast(1) else 1
        }
        val movingSpan = spans[fromIdx]

        // 剔除被拖拽块后的剩余块列表
        data class RemBlock(val origTime: Int, val span: Int)
        val remBlocks = ArrayList<RemBlock>(times.size - 1)
        for (i in times.indices) {
            if (i != fromIdx) {
                remBlocks.add(RemBlock(times[i], spans[i]))
            }
        }

        val startT = times.first()
        val n = remBlocks.size // 剩余块数量，共 n + 1 个候选插槽 (0..n)

        // 拖拽块在时间轴上的中心坐标 (帧为单位)
        val dragCenterFrame = fromTime + movingSpan / 2f + dragDx / frameW

        var bestSlot = 0
        var minDiff = Float.MAX_VALUE
        var runningSpanBefore = 0
        val candidateStartFrames = IntArray(n + 1)

        for (k in 0..n) {
            val slotStart = startT + runningSpanBefore
            candidateStartFrames[k] = slotStart
            val slotCenter = slotStart + movingSpan / 2f
            val diff = abs(slotCenter - dragCenterFrame)
            if (diff < minDiff) {
                minDiff = diff
                bestSlot = k
            }
            if (k < n) {
                runningSpanBefore += remBlocks[k].span
            }
        }

        val targetStartFrame = candidateStartFrames[bestSlot]

        // 计算所有剩余块在当前插入槽下的目标起始帧
        val targetMap = HashMap<Int, Int>(remBlocks.size)
        var cur = startT
        for (k in 0..n) {
            if (k == bestSlot) {
                cur += movingSpan
            }
            if (k < n) {
                targetMap[remBlocks[k].origTime] = cur
                cur += remBlocks[k].span
            }
        }

        return TimelineDragLayout(
            movingSpan = movingSpan,
            targetSlot = bestSlot,
            targetStartFrame = targetStartFrame,
            blockTargetFrames = targetMap,
        )
    }

    data class ReorderResult(
        val oldTimes: List<Int>,
        val newTimes: List<Int>,
        val landingTime: Int,
    )

    /**
     * 计算执行推挤重排后的完整新时间序列。
     */
    fun computeReorderedTimes(
        times: List<Int>,
        fromTime: Int,
        targetSlot: Int,
    ): ReorderResult? {
        val fromIdx = times.indexOf(fromTime)
        if (fromIdx < 0 || times.size <= 1) return null

        val spans = times.indices.map { i ->
            if (i + 1 < times.size) (times[i + 1] - times[i]).coerceAtLeast(1) else 1
        }

        data class Block(val origTime: Int, val span: Int)
        val blocks = times.indices.map { Block(times[it], spans[it]) }.toMutableList()
        val movingBlock = blocks.removeAt(fromIdx)

        val safeSlot = targetSlot.coerceIn(0, blocks.size)
        blocks.add(safeSlot, movingBlock)

        val startT = times.first()
        val newTimes = ArrayList<Int>(blocks.size)
        var cur = startT
        var landingTime = startT
        for (b in blocks) {
            newTimes.add(cur)
            if (b.origTime == fromTime) {
                landingTime = cur
            }
            cur += b.span
        }
        val oldTimes = blocks.map { it.origTime }
        return ReorderResult(oldTimes, newTimes, landingTime)
    }
}
