/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import kotlin.math.hypot

/** Ordered document-space input with a per-frame dab budget. Batching preserves turns,
 * reversals and pressure; an in-progress segment keeps its interpolation across frames.
 * The reusable ring grows only when a gesture exceeds its previously reserved capacity. */
class LiquifyInteractionSession {

    /** 每帧最多推进的补点数; 0 = 关闭合并(逐点立即处理, 与历史行为一致)。 */
    var maxDabsPerFlush: Int = 0
        private set

    /** 是否处于合并(每帧推进)模式。 */
    val coalescing: Boolean get() = maxDabsPerFlush > 0

    private var points = FloatArray(1024 * 3)
    private var sequences = LongArray(1024)
    private var head = 0
    private var count = 0
    private var remainingSteps = 0
    private var segmentTotalSteps = 0
    private var segmentStepX = 0f
    private var segmentStepY = 0f
    private var segmentScale = 1f
    var planPressureFactor = 1f
        private set

    // ---- 位置: [renderedX/Y] = 已推进到的位置; [targetX/Y] = 最新输入位置 ----
    var renderedX: Float = 0f
        private set
    var renderedY: Float = 0f
        private set
    var targetX: Float = 0f
        private set
    var targetY: Float = 0f
        private set

    /** 是否有尚未追平的最新目标。 */
    var hasPending: Boolean = false
        private set

    // ---- 序号: backlog 指标 (AGENTS.md 未含, 见优化方案 §20) ----
    /** 输入事件序号: 每个提交的位置自增一次。 */
    var inputSequence: Long = 0L
        private set

    /** 已推进到的输入序号: 追平 [inputSequence] 时二者相等, 差额即 backlog。 */
    var renderedSequence: Long = 0L
        private set

    /** backlog: 距上一次推进以来累积的输入事件数 (采样时读)。 */
    val lag: Long get() = inputSequence - renderedSequence

    /** 上一帧推进后仍 **未提交** 的补点数; > 0 表示这一帧还没追上最新位置。 */
    var backlogDabs: Int = 0
        private set

    // ---- 累计计数(每次手势清零, 供标尺统计) ----
    /** 本次手势累计提交的输入位置数。 */
    var inputCount: Long = 0L
        private set

    /** 本次手势累计推进帧数。 */
    var flushCount: Long = 0L
        private set

    /** 本次手势累计提交的补点数。 */
    var dabCount: Long = 0L
        private set

    /** 已形成的 Operation 批次数(一次推进 = 一批增量形变)。 */
    var operationCount: Long = 0L
        private set

    /** 上一次推进覆盖的输入事件数(合并倍率的分子)。 */
    var lastFlushInputs: Int = 0
        private set


    // ---- 本帧推进计划(由 [prepareFlush] 填充, 全为原生类型 ⇒ 零分配) ----
    /** 本帧实际推进的补点数。 */
    var planSteps: Int = 0
        private set

    /** 整段位移所需的总补点数。 */
    var planTotalSteps: Int = 0
        private set

    /** 强度折算系数(细分前后总形变量一致, 见 [LiquifyPath.substepStrengthScale])。 */
    var planStrengthScale: Float = 1f
        private set

    var planStepX: Float = 0f
        private set
    var planStepY: Float = 0f
        private set
    var planStartX: Float = 0f
        private set
    var planStartY: Float = 0f
        private set

    /**
     * 手势开始: 以落笔点为已渲染基准。
     *
     * @param maxDabsPerFlush 每帧最多推进的补点数(0 = 关闭合并)
     */
    fun begin(x: Float, y: Float, maxDabsPerFlush: Int) {
        head = 0
        count = 0
        remainingSteps = 0
        renderedX = x
        renderedY = y
        targetX = x
        targetY = y
        hasPending = false
        inputSequence = 0L
        renderedSequence = 0L
        inputCount = 0L
        flushCount = 0L
        dabCount = 0L
        operationCount = 0L
        lastFlushInputs = 0
        backlogDabs = 0
        this.maxDabsPerFlush = maxDabsPerFlush
    }

    /** 手势结束/取消: 丢弃待推进目标(位置保留, 便于调用方读取末态)。 */
    fun reset() {
        head = 0
        count = 0
        remainingSteps = 0
        hasPending = false
        targetX = renderedX
        targetY = renderedY
        backlogDabs = 0
    }

    /** 提交一个输入位置(`ACTION_MOVE` 或历史点)。 */
    fun submitTarget(x: Float, y: Float, pressureFactor: Float = 1f) {
        if (!x.isFinite() || !y.isFinite() || !pressureFactor.isFinite()) return
        if (count == sequences.size) {
            val capacity = sequences.size
            val grown = FloatArray(points.size * 2)
            val seq = LongArray(capacity * 2)
            for (i in 0 until count) {
                val old = (head+i) % capacity
                points.copyInto(grown,i*3,old*3,old*3+3)
                seq[i] = sequences[old]
            }
            points = grown
            sequences = seq
            head = 0
        }
        targetX = x
        targetY = y
        inputSequence++
        inputCount++
        val slot = (head+count) % sequences.size
        points[slot*3] = x
        points[slot*3+1] = y
        points[slot*3+2] = pressureFactor.coerceIn(0f,1f)
        sequences[slot] = inputSequence
        count++
        hasPending = true
    }

    /**
     * 规划本帧推进。填充 [planSteps] / [planTotalSteps] / [planStrengthScale] / [planStepX,Y]
     * / [planStartX,Y], 不产生任何分配。
     *
     * @param brushSize    引擎侧笔刷尺寸(决定补点间距)
     * @param mode         液化模式(影响强度折算的幅度曲线)
     * @param forceFull    true = 不受每帧上限约束(抬笔补齐 / 关闭合并时的逐点路径)
     * @return false 表示无需推进(没有待推进目标 / 位移归零)
     */
    fun prepareFlush(brushSize: Float, mode: Int, forceFull: Boolean, budget: Int = Int.MAX_VALUE): Boolean {
        planSteps = 0
        if (!brushSize.isFinite() || budget <= 0) return false
        while (count > 0 && remainingSteps == 0) {
            val dx = points[head*3] - renderedX
            val dy = points[head*3+1] - renderedY
            val dist = hypot(dx,dy)
            if (!dist.isFinite() || dist <= 0f) { consumePoint(); continue }
            segmentTotalSteps = LiquifyPath.substepCount(dist,brushSize)
            remainingSteps = segmentTotalSteps
            segmentStepX = dx / segmentTotalSteps
            segmentStepY = dy / segmentTotalSteps
            segmentScale = LiquifyPath.substepStrengthScale(dist,brushSize,segmentTotalSteps,mode)
        }
        hasPending = count > 0
        if (!hasPending) { backlogDabs = 0; return false }
        val cap = if (forceFull || maxDabsPerFlush <= 0) budget else minOf(budget,maxDabsPerFlush)
        planSteps = minOf(remainingSteps,cap)
        planTotalSteps = segmentTotalSteps
        planStrengthScale = segmentScale
        planPressureFactor = points[head*3+2]
        planStepX = segmentStepX
        planStepY = segmentStepY
        planStartX = renderedX
        planStartY = renderedY
        return true
    }

    private fun consumePoint() {
        renderedSequence = sequences[head]
        head = (head+1) % sequences.size
        count--
    }

    fun advanceFlush(steps: Int) {
        require(steps in 1..planSteps)
        renderedX = planStartX + planStepX * steps
        renderedY = planStartY + planStepY * steps
        remainingSteps -= steps
        lastFlushInputs = 0
        if (remainingSteps == 0) {
            renderedX = points[head*3]
            renderedY = points[head*3+1]
            consumePoint()
            lastFlushInputs = 1
        }
        dabCount += steps
        flushCount++
        operationCount++
        hasPending = count > 0
        backlogDabs = remainingSteps + maxOf(0,count - if (remainingSteps>0) 1 else 0)
    }

    companion object {
        /** 小于该位移视为已到达目标(与历史 coalescing 路径的 0.5px 阈值一致)。 */
        const val MIN_ADVANCE_PX = 0.5f
    }
}
