/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import kotlin.math.hypot

/**
 * Phase 3A: 液化交互态会话 —— "输入 → 预览" 的调度状态机 (latest-state-wins)。
 *
 * 背景 (见 docs/RENDER-OPTIMIZATION.md §4.11):
 *  一次 `ACTION_MOVE` 可以携带多个历史点。手越快 / 笔刷越大, "每个历史点 × 每个补点都立刻提交"
 *  带来的积压越严重 —— 旧实现表现为预览越来越落后。本类把原先散落在触摸视图里的临时字段
 *  (`liquifyPrevPos` / `liquifyPendingTo` / `liquifyInputSinceFlush` / `liquifyMaxDabsPerFlush`)
 *  收敛成单一纯状态机: 交互态只保留 **最新目标位置**, 中间位置丢弃; 每帧最多推进
 *  [maxDabsPerFlush] 个补点, 方向始终指向最新位置, 没追完下一帧继续。
 *
 * 为什么不做 "操作栈重放": 形变的真身在 C++ 引擎的 Krita 网格里, 每个 dab 都是对网格的
 *  **增量** 累加 (见 ReverieCoreMiscTools.cpp)。因此 Kotlin 侧不需要保存历史操作, 只需记住
 *  "从已渲染位置到最新目标还剩多少距离"。本类只负责调度与计数, 不持有像素、不调 JNI ——
 *  纯逻辑, 可单测 (AGENTS.md §8); 单一职责, 与引擎解耦 (AGENTS.md §5)。
 *
 * 正确性底线: 单帧推进量有上界, 但总量口径不变 (补点规则与强度折算复用 [LiquifyPath])。
 *  抬笔时由调用方以 `forceFull = true` 一次性补齐剩余段, 保证最终形变不受调度节奏影响 ——
 *  抬笔后仍由 Krita 全精度 materialize, 交互态只是近似 (AGENTS.md §4)。
 *
 * 零分配: 每帧的推进计划全部落在此类的原生类型字段上 ([planSteps] / [planStepX] ...), 热路径
 *  上不创建任何对象 (AGENTS.md §4 铁律 4)。
 */
class LiquifyInteractionSession {

    /** 每帧最多推进的补点数; 0 = 关闭合并(逐点立即处理, 与历史行为一致)。 */
    var maxDabsPerFlush: Int = 0
        private set

    /** 是否处于合并(每帧推进)模式。 */
    val coalescing: Boolean get() = maxDabsPerFlush > 0

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

    private var lastFlushSeq = 0L
    private var pendingFlushInputs = 0

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
        renderedX = x
        renderedY = y
        targetX = x
        targetY = y
        hasPending = false
        inputSequence = 0L
        renderedSequence = 0L
        lastFlushSeq = 0L
        inputCount = 0L
        flushCount = 0L
        dabCount = 0L
        operationCount = 0L
        lastFlushInputs = 0
        pendingFlushInputs = 0
        backlogDabs = 0
        this.maxDabsPerFlush = maxDabsPerFlush
    }

    /** 手势结束/取消: 丢弃待推进目标(位置保留, 便于调用方读取末态)。 */
    fun reset() {
        hasPending = false
        targetX = renderedX
        targetY = renderedY
        backlogDabs = 0
        pendingFlushInputs = 0
    }

    /** 提交一个输入位置(`ACTION_MOVE` 或历史点)。 */
    fun submitTarget(x: Float, y: Float) {
        targetX = x
        targetY = y
        hasPending = true
        inputSequence++
        inputCount++
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
    fun prepareFlush(brushSize: Float, mode: Int, forceFull: Boolean): Boolean {
        if (!hasPending) return false
        val dx = targetX - renderedX
        val dy = targetY - renderedY
        val dist = hypot(dx, dy)
        if (dist <= 0f) return false
        if (!forceFull && dist < MIN_ADVANCE_PX) {
            // 位移过小: 直接吸附到目标, 避免空转(与历史 coalescing 路径一致)
            renderedX = targetX
            renderedY = targetY
            hasPending = false
            backlogDabs = 0
            settleSequenceLocked()
            return false
        }
        val full = LiquifyPath.substepCount(dist, brushSize)
        if (full <= 0) return false
        val chased = if (forceFull) full else LiquifyPath.chaseSubsteps(dist, brushSize, maxDabsPerFlush)
        val steps = if (chased <= 0) full else chased
        planTotalSteps = full
        planSteps = steps
        planStrengthScale = LiquifyPath.substepStrengthScale(dist, brushSize, full, mode)
        planStepX = dx / full
        planStepY = dy / full
        planStartX = renderedX
        planStartY = renderedY
        pendingFlushInputs = (inputSequence - lastFlushSeq).toInt()
        return true
    }

    /** 推进完成(调用方已按 [planSteps] 提交 JNI): 更新位置与计数。 */
    fun advanceFlush(steps: Int) {
        renderedX = planStartX + planStepX * steps
        renderedY = planStartY + planStepY * steps
        dabCount += steps
        flushCount++
        operationCount++
        lastFlushInputs = pendingFlushInputs
        pendingFlushInputs = 0
        backlogDabs = planTotalSteps - steps
        renderedSequence = inputSequence
        lastFlushSeq = inputSequence
        if (backlogDabs <= 0) {
            hasPending = false
        }
    }

    /** 吸附/收尾: 让 [renderedSequence] 追上输入, 使 [lag] 归零。 */
    private fun settleSequenceLocked() {
        renderedSequence = inputSequence
        lastFlushSeq = inputSequence
        lastFlushInputs = pendingFlushInputs
        pendingFlushInputs = 0
    }

    companion object {
        /** 小于该位移视为已到达目标(与历史 coalescing 路径的 0.5px 阈值一致)。 */
        const val MIN_ADVANCE_PX = 0.5f
    }
}
