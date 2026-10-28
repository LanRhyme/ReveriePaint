/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.reverie.paint.model.UndoScrubPacer

/** 泵被泵外单飞 (pendingHistoryOps 在途) 挡住后的重试间隔 (ms) */
private const val UNDO_BAR_PUMP_RETRY_MS = 50L

// —— 撤回条会话状态 ——
// ViewModel partial 模式的扩展函数无法给类加带 backing field 的属性, 按项目
// 惯例 (PaintViewModelLayers.kt 的 thumbRefreshJob 等) 用文件顶层状态; 单实例
// 引擎 + UI 单窗口假设 (架构铁律 §4.6) 下进程内唯一。

/** 撤回条是否可见 (UI 层在打开/关闭时写, 泵只在可见时工作) */
internal var undoBarVisible by mutableStateOf(false)

/** 滑杆当前位置镜像 = 还可撤销的步数 (供 Slider/计数文本观察) */
internal var undoBarPosition by mutableIntStateOf(0)

/** 滑杆刻度镜像 = 可撤销 + 可重做总长 */
internal var undoBarMax by mutableIntStateOf(0)

/** 最近一次撤回条交互时间戳 (自动隐藏定时器放 UI 层, 只读这个时间戳) */
internal var undoBarLastActivityAt by mutableLongStateOf(0L)

// 追赶簿记与泵内部状态 (非 Compose 状态: 仅主线程串行访问, 不参与重组)
private val undoScrubPacer = UndoScrubPacer()
private var undoBarPumpRetryPosted = false

/**
 * 记录撤回条最近活跃时间 (自动隐藏计时在 UI 层做, 这里只存时间戳)。
 */
internal fun PaintViewModel.onUndoBarActivity() {
    undoBarLastActivityAt = android.os.SystemClock.elapsedRealtime()
}

/**
 * 刷新滑杆刻度与位置镜像 (条出现/恢复、每步 settled、泵外历史变化后调用):
 * 用引擎真实计数对齐簿记。alignTo 保留用户拖动 target —— 若用 reset 覆盖, 松手后的
 * 最后几步会被簿记对齐吃掉而丢步。在途时只刷镜像不动簿记 (落定由
 * onStepDone 推进, 避免双计); settled 路径先 onStepDone 再到这里, 此时
 * 引擎计数已含刚落定的一步, 对齐结果与簿记推进一致。
 */
internal fun PaintViewModel.refreshUndoCounts() {
    val undoCount = ReverieCoreBridge.undoCount()
    val redoCount = ReverieCoreBridge.redoCount()
    if (!undoScrubPacer.hasPendingWork) {
        undoScrubPacer.alignTo(undoCount, redoCount)
    }
    undoBarPosition = undoScrubPacer.position
    undoBarMax = undoScrubPacer.max
}

/**
 * 拖动目标: 记录活跃、更新 pacer 目标并确保泵在跑。目标更新只改簿记状态,
 * 由泵逐步追赶 (≤1 步在途, 不丢步)。主线程调用。
 */
internal fun PaintViewModel.scrubTo(target: Int) {
    onUndoBarActivity()
    undoScrubPacer.dragTarget(target)
    pumpUndoBar()
}

/**
 * 撤回条可显示/泵可发步的条件。变换激活、QuickShape 编辑、笔画/手势进行中、
 * 3D 撤销路径接管、lasso 收点中时为 false —— 这些状态下 undo()/redo() 会走
 * 其他分支早退, settled 回调不会触发, 泵发步会停摆, 故一律不发。
 */
internal fun PaintViewModel.canShowUndoBar(): Boolean {
    if (isQuickShapeEditing) return false
    if (customUndoHook != null) return false
    if (isCurrentLayer3D && (canUndo3D || canRedo3D)) return false
    if (currentToolId == "lasso" && lassoMultiPoints.isNotEmpty()) return false
    val touchView = com.reverie.paint.ui.painting.canvas.CanvasTouchView.activeTouchView
        ?: return true
    return !touchView.isInteracting && !touchView.isTransformActive
}

/**
 * 追赶泵: 无在途步且目标未追平时发出下一步。关键正确性:
 * - 任何时刻在途的 undo/redo ≤ 1 —— pacer.hasPendingWork 挡本泵重复发步,
 *   pendingHistoryOps 检查尊重泵外单飞 (两者读写均在主线程, 检查与发步之间
 *   无竞态窗口);
 * - 被单飞挡住时延迟重试, 不丢追赶。
 * 只在主线程调用。
 */
private fun PaintViewModel.pumpUndoBar() {
    if (!undoBarVisible) return
    if (!canShowUndoBar()) return
    if (pendingHistoryOps.get() != 0) {
        scheduleUndoBarPumpRetry()
        return
    }
    val step = undoScrubPacer.nextStep()
    if (step == 0) {
        refreshUndoCounts()
        return
    }
    undoScrubPacer.beginStep(step)
    if (step > 0) {
        redo(settled = { onUndoBarStepSettled() })
    } else {
        undo(settled = { onUndoBarStepSettled() })
    }
}

/** 一步落定: 推进簿记 → 用真实边界刷新镜像 → 链式续步直到追平 (经 mainHandler 异步, 不占栈) */
private fun PaintViewModel.onUndoBarStepSettled() {
    undoScrubPacer.onStepDone()
    refreshUndoCounts()
    pumpUndoBar()
}

/**
 * 复活入口: 条恢复显示 (UI 把 [undoBarVisible] 写回 true) 后续跑被中断的
 * 追赶。pumpUndoBar 在不可见/不可显示时直接停车, 若此时 target 未追平,
 * 剩余步骤会悬到条再次可用 —— UI 的复活路径调用这里补上续跑: 无在途且
 * 未追平才发步 (有在途步则由 settled 落定链自行续跑, 不重复发)。
 * 只在主线程调用。
 */
internal fun PaintViewModel.resumeUndoBarPumpIfPending() {
    if (undoScrubPacer.nextStep() != 0) {
        pumpUndoBar()
    }
}

private fun PaintViewModel.scheduleUndoBarPumpRetry() {
    if (undoBarPumpRetryPosted) return
    undoBarPumpRetryPosted = true
    mainHandler.postDelayed({
        undoBarPumpRetryPosted = false
        pumpUndoBar()
    }, UNDO_BAR_PUMP_RETRY_MS)
}
