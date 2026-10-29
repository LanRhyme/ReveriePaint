/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

/**
 * 撤回条 (undo scrub bar) 的「目标位置追赶」簿记器。
 *
 * 纯 Kotlin 簿记, 不含任何线程/UI/引擎逻辑, 可 JVM 单测。
 *
 * 语义 (PencilLite 惯例): [position] = 还可撤销的步数, 取值 0..[max];
 * [max] = 可撤销步数 + 可重做步数。目标左移 (target < position) 需要出
 * undo 步, 右移 (target > position) 需要出 redo 步, 由泵逐步执行直到追平。
 *
 * 线程约定: 所有方法必须在同一线程串行调用 (ViewModel 层保证在主线程:
 * 拖动手势与 settled 回调经 mainHandler 都回到主线程), 本类不做同步。
 *
 * 与真实栈的一致性: 拖动中簿记与引擎真实栈可能短暂不一致 (单飞在途, 或
 * 泵外路径动过历史), 由 ViewModel 层在每步 settled 后用真实计数纠偏
 * ([alignTo], 追赶会话中保留用户拖动 target)。
 *
 * target 生命周期 = 「拖动会话 → 泵追平/被打断」:
 * - [dragTarget] 开启 (arm) 一次追赶会话, [disarmTarget] 或 [reset] 作废会话;
 * - armed (追赶中): [alignTo] 纠偏时保留 target (clamp 到新 max), 追赶不丢步;
 * - 未 armed: [alignTo] 会把 target 播种为 [position] —— 外部历史变化 (画新
 *   笔画/顶栏撤销等) 不会唤醒泵去追赶一个早已不存在的旧目标, 泵永不复活。
 */
class UndoScrubPacer {

    /** 历史总长 (可撤销 + 可重做), 0 表示尚无可浏览历史 */
    var max: Int = 0
        private set

    /** 当前历史位置 = 还可撤销的步数 (0..max) */
    var position: Int = 0
        private set

    /** 追赶目标 (0..max), 与 position 相等即追平 */
    var target: Int = 0
        private set

    /** 追赶会话是否活跃 (dragTarget 开启, disarmTarget/reset 作废); 未 armed 时 target 恒等于 position */
    private var targetArmed = false

    /** 在途步方向: +1 = 一次 redo 在途, -1 = 一次 undo 在途, 0 = 无在途 */
    private var pendingDirection: Int = 0

    /** 是否有一步在途 (已发出 undo/redo 但尚未 [onStepDone]); 在途时不再发新步 */
    val hasPendingWork: Boolean
        get() = pendingDirection != 0

    /**
     * 重置簿记。position 用真实可撤销步数初始化 (滑杆 progress = undoCount),
     * max = undoCount + redoCount。丢弃旧目标与在途状态, 并作废追赶会话
     * (开新会话, target 重新播种为 position)。
     */
    fun reset(undoCount: Int, redoCount: Int) {
        val u = undoCount.coerceAtLeast(0)
        val r = redoCount.coerceAtLeast(0)
        max = u + r
        position = u
        target = u
        targetArmed = false
        pendingDirection = 0
    }

    /** 设置追赶目标并开启会话, 越界 clamp 到 0..max; 在途时也可调用 (只改目标, 不发步) */
    fun dragTarget(target: Int) {
        targetArmed = true
        this.target = target.coerceIn(0, max)
    }

    /**
     * 作废追赶会话: target 立即归位 [position] (泵因此不再发步)。用于泵追平
     * (会话结束) 或追赶被打断 (条隐藏/笔画与变换接管) 时防止泵日后复活追赶。
     */
    fun disarmTarget() {
        targetArmed = false
        target = position
    }

    /**
     * 查询下一步动作 (纯查询, 无副作用): +1 = 执行一次 redo, -1 = 执行一次
     * undo, 0 = 已追平或在途, 无步可发。发出前须先 [beginStep] 标记在途。
     */
    fun nextStep(): Int = when {
        hasPendingWork -> 0
        target < position -> -1
        target > position -> +1
        else -> 0
    }

    /** 标记一步在途, direction 须为 [nextStep] 的返回值 (+1/-1); 重复标记被忽略 */
    fun beginStep(direction: Int) {
        if (hasPendingWork) return
        if (direction != 1 && direction != -1) return
        pendingDirection = direction
    }

    /**
     * 一步执行完成, 按发出时锁定的方向推进簿记 (发出后目标再被拖走也不影响
     * 本步方向), 推进结果 clamp 到 0..max。
     */
    fun onStepDone() {
        if (pendingDirection == 0) return
        position = (position + pendingDirection).coerceIn(0, max)
        pendingDirection = 0
    }

    /**
     * 用引擎真实计数对齐簿记: 重建 [max] 与 [position], 两态语义 ——
     * - 未 armed (无追赶会话): target 播种为对齐后的 [position], 外部历史变化
     *   (画新笔画/顶栏撤销) 绝不会唤醒泵;
     * - armed (追赶中): 保留用户意图 [target] (clamp 到新 max) —— 追赶中每步
     *   settled 后纠偏时, 用户的拖动目标不能被簿记对齐覆盖, 否则松手后的
     *   最后几步会丢步。
     * 与 [reset] 的区别: reset 丢弃会话 (target 播种), alignTo 在 armed 时保留
     * (追赶中纠偏)。在途时忽略 —— 在途步落定由 [onStepDone] 推进, 此处再对齐
     * 会双计。
     */
    fun alignTo(undoCount: Int, redoCount: Int) {
        if (hasPendingWork) return
        val u = undoCount.coerceAtLeast(0)
        val r = redoCount.coerceAtLeast(0)
        max = u + r
        position = u
        target = if (targetArmed) target.coerceIn(0, max) else position
    }
}
