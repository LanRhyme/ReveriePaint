/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.painting.canvas

import android.content.Context
import android.os.SystemClock
import android.view.MotionEvent
import androidx.compose.ui.geometry.Offset
import com.reverie.paint.core.*
import com.reverie.paint.model.*
import kotlin.math.ceil
import kotlin.math.floor

// 拆分自 CanvasTouchView.kt: 同域实现集中于此 (AGENTS.md §6 单文件行数上限)

/**
 * 液化沿路径推进: 位移大于笔刷影响半径时拆成多个补点, 让相邻形变搭接,
 * 消除快速拖动时的断线。强度按 [LiquifyPath.substepStrengthScale] 折算,
 * 保证细分前后总形变量一致 (引擎侧幅度曲线对每个 dab 有固定底)。
 *
 * 关闭合并(latest-state-wins)时的逐点路径: 每个输入点立即**全量**推进, 与历史行为一致。
 */
internal fun CanvasTouchView.liquifyAlongPath(v: PaintViewModel, to: Offset) {
    liquifySession.submitTarget(to.x, to.y, liquifyPressureFactor())
    liquifyFlushNow(v, forceFull = true)
}

/**
 * Phase 3A/3B: 把会话里待推进的位移段提交给引擎 —— 液化的**唯一 JNI 提交点**。
 *
 * 调度全在 [LiquifyInteractionSession] 里(纯逻辑), 这里只按它的推进计划跑 JNI 循环:
 * 步长与强度折算始终按"整段"口径, 因此分帧只改节奏、不改总量; 抬笔 forceFull 精确落点。
 *
 * @param forceFull true = 抬笔补齐 / 关闭合并的逐点路径(不受每帧补点上限约束)
 */
/** Runs when HWUI consumes a preview buffer, even while the pen is stationary. */
internal fun CanvasTouchView.onLiquifyFramePresented(timestamp: Long) {
    if (!liquifyFieldGesture || liquifyWholeLayerRect != null) return
    val v = vm ?: return
    if (LiquifyGlesPreview.copyPresentedDrawRect(timestamp, liquifyCommittedScratch) &&
        liquifyBasePolicy.shouldAdvance(liquifyCommittedScratch, 1)) {
        v.liquifyPreviewBase(liquifyCommittedScratch[0], liquifyCommittedScratch[1],
            liquifyCommittedScratch[2], liquifyCommittedScratch[3])
    }
}

internal fun CanvasTouchView.liquifyFlushNow(v: PaintViewModel, forceFull: Boolean) {
    if (liquifyFieldGesture && (!LiquifyGlesPreview.requested || LiquifyGlesPreview.failed ||
        !LiquifyGlesPreview.alive || LiquifyGlesPreview.dabsDropped > 0L)) {
        // Source preparation and GL allocation complete asynchronously. If
        // either fails, recover now instead of leaving the whole drag invisible
        // until pen-up. Native has not received any of these recorded dabs yet.
        liquifyFieldGesture = false
        v.liquifyUseEnginePreview()
        v.replayLiquifyFieldDabs(liquifyDabBuf, liquifyDabCount, FIELD_DAB_STRIDE)
        liquifyDabCount = 0
        liquifyWholeLayerRect = null
        liquifyBasePolicy.reset()
        liquifyDrawRect = null
    }
    var batchCount = 0
    var totalSteps = 0
    var consumedInputs = 0
    var dirtyL = Float.POSITIVE_INFINITY
    var dirtyT = Float.POSITIVE_INFINITY
    var dirtyR = Float.NEGATIVE_INFINITY
    var dirtyB = Float.NEGATIVE_INFINITY
    var budget = if (forceFull || !liquifySession.coalescing) Int.MAX_VALUE
        else liquifySession.maxDabsPerFlush
    while (budget > 0 && liquifySession.prepareFlush(
            liquifyBrushSize, liquifyMode, forceFull, budget, professional = true,
        )) {
        val steps = liquifySession.planSteps
        val strength = liquifyStrength * liquifySession.planStrengthScale * liquifySession.planPressureFactor
        val stepX = liquifySession.planStepX
        val stepY = liquifySession.planStepY
        var px = liquifySession.planStartX
        var py = liquifySession.planStartY
        for (i in 0 until steps) {
            val nx = px + stepX
            val ny = py + stepY
            if (strength <= 0f) {
                px = nx
                py = ny
                continue
            }
            if (liquifyFieldGesture) {
                // Phase 5 · C3-2: 场通路 —— **拖动期一个 dab 都不进引擎**。真机实测(200px 笔刷)
                // 原本每秒要 1.07s 的原生工作: 逐 dab 网格形变 550ms + rebase 物化 517ms, 全在这里消失。
                // 参数只记进本地列表: 抬笔回读若失败, 就靠这份列表重放给引擎, 形变一点不丢。
                recordLiquifyFieldDab(px, py, nx, ny, liquifyMode, strength)
                val radius = liquifySupportRadius()
                dirtyL = minOf(dirtyL, minOf(px, nx) - radius)
                dirtyT = minOf(dirtyT, minOf(py, ny) - radius)
                dirtyR = maxOf(dirtyR, maxOf(px, nx) + radius)
                dirtyB = maxOf(dirtyB, maxOf(py, ny) + radius)
                // 录制流必须与"逐 dab 提交"完全一致 —— 回放走的是经典路径
                v.recordLiquifyDab(px, py, nx, ny, liquifyMode, strength)
            } else {
                batchCount = appendLiquifyBatch(px, py, nx, ny, strength, batchCount)
            }
            // Phase 5 · C3: 同一个补点再推一份给 GLES 常驻位移场 (docs/LIQUIFY-C3-FIELD-PLAN.md §2.2)。
            // 这里**不新增任何 JNI** —— 参数本来就在手上; 场通路下这是唯一的消费者(引擎那边一个都不收),
            // 场未 armed(开关关/由别的路径画)时 pushDab 只是一次 volatile 读, 不进热路径。
            // Each dab composes a bounded inverse map; a whole-segment additive kernel is not equivalent.
            LiquifyGlesPreview.pushDab(px, py, nx, ny, liquifyMode, strength, liquifyBrushSize)
            px = nx
            py = ny
        }
        liquifySession.advanceFlush(steps)
        totalSteps += steps
        consumedInputs += liquifySession.lastFlushInputs
        budget -= steps
    }
    if (totalSteps == 0) return
    LiquifyGlesPreview.publishDabs()
    // 相位 8(背景色线框闪烁修复): 覆盖层的绘制矩形与引擎的"预览基座"矩形必须是**同一个整数
    // 矩形**, 且基座只能跟在覆盖层**已上屏**的帧后面推进 ——
    //   · 两者不一致 ⇒ 差值那圈要么被挖掉却没人补(露画布背景 = 背景色线框), 要么没被挖却在
    //     叠加(半透明内容叠两次 = 深色描边);
    //   · 基座领先 ⇒ 挖掉的那圈在覆盖层画上去之前就是背景 ⇒ 拖拽时每帧闪一次线框。
    if (liquifyFieldGesture) {
        val rect = liquifyWholeLayerRect ?: LiquifyPath.previewRect(
            lqAffectedL, lqAffectedT, lqAffectedR, lqAffectedB, v.docWidth, v.docHeight,
        )
        if (rect != null && (liquifyDrawRect == null || !liquifyDrawRect.contentEquals(rect))) {
            liquifyDrawRect = rect
            // 普通单层由同一 GPU 帧完整替换；复杂场景在 HWUI 消费对应帧后推进底图。
            LiquifyGlesPreview.pushDrawRect(
                rect[0].toFloat(), rect[1].toFloat(), rect[2].toFloat(), rect[3].toFloat(),
            )
        }
    }

    if (dirtyL.isFinite()) invalidateLiquifyFieldDab(
        (dirtyL + dirtyR) * 0.5f, (dirtyT + dirtyB) * 0.5f,
        maxOf(dirtyR - dirtyL, dirtyB - dirtyT) * 0.5f,
    )
    if (batchCount > 0) {
        // 一次 runCore + 一次 JNI 提交全部补点(native 内按序循环): 顺序与逐点路径完全一致,
        // 但 Handler 消息、渲染调度与跨语言边界都只发生一次。
        v.liquifyBatch(liquifyBatchBuf, batchCount)
    }
    // 合并倍率: 本帧覆盖的输入事件数 / 实际补点数
    PerfTrace.liquifySchedule(consumedInputs, totalSteps)
    // backlog 指标: 滞后事件数 + 仍未提交的补点数(见 PerfTrace.liquifyFlow)
    PerfTrace.liquifyFlow(liquifySession.lag, liquifySession.backlogDabs)
    // Phase 6(量化验收): 端到端输入延迟 —— 事件时间 → 本次提交进入引擎的滞后。
    // 目标: 中端机高强度液化 P95 ≤ 2 帧(33ms); 峰值 > 100ms 即"被肉眼感知的掉帧"。
    val lagMs = if (lastLiquifyEventTimeMs > 0L) {
        (android.os.SystemClock.uptimeMillis() - lastLiquifyEventTimeMs).coerceAtLeast(0L)
    } else {
        0L
    }
    PerfTrace.liquifyLatency(lagMs, if (liquifyPressureActive) smoothedPressure else 0f)
}

/**
 * 液化强度的手写笔压力系数(0..1)，手指保持固定强度。
 *
 * 只对**笔**输入生效(与 CSP/SAI2 在触摸屏上的口径一致: 手指 pressure 在多数机型上是
 * 1.0 或触摸面积噪声, 参与调制会让强度乱跳)。`setprop debug.reverie.lqpressure 0` 关闭。
 */
internal fun CanvasTouchView.liquifyPressureFactor(): Float {
    if (!liquifyPressureActive) return 1f
    if (PerfTrace.debugPropInt("debug.reverie.lqpressure", 1) == 0) return 1f
    return smoothedPressure.coerceIn(0f, 1f)
}

/**
 * 尝试进入"场通路"。前置: 本段手势由 GLES 覆盖层画、且场已 armed。
 *
 * 成功 ⇒ 引擎只准备一份"未形变的源像素"(整篇文档, 每段手势一次), 之后的 dab 全部只喂 GPU 场;
 * 失败(超预算 / 非 8bit BGRA / 文档尺寸未知)返回 false, 调用方原样走经典逐 dab 路径。
 */
internal fun CanvasTouchView.beginLiquifyFieldGesture(v: PaintViewModel): Boolean {
    if (!LiquifyGlesPreview.requested || !LiquifyGlesPreview.fieldArmed) return false
    val dw = v.docWidth
    val dh = v.docHeight
    if (dw <= 0 || dh <= 0) return false
    // Keep small brushes precise on large documents. The sparse native field
    // can retain fine nodes; a full GPU texture must otherwise downsample them.
    if (!com.reverie.paint.model.LiquifyProfessional.supportsFullField(dw, dh, liquifyBrushSize) ||
        LiquifyGlesPreview.fieldRes > com.reverie.paint.model.LiquifyProfessional.fieldStep(liquifyBrushSize)) {
        return false
    }
    // 稳定性硬预算: 源纹理 + 场纹理都是"整篇文档"级别的大块内存(CPU/GPU 各留一份),
    // 这里同时叠加"堆安全预算"(堆上限 / 每像素 32B, 见 [MemoryBudget]) —— 低内存设备压到
    // 1/4; 超预算直接回经典路径, 宁可慢一点, 也不能一次手势把进程推到 OOM 边缘。
    val budget = fieldPathBudgetPx()
    val sceneBudget = minOf(budget, Runtime.getRuntime().maxMemory() / 48L)
    if (sceneBudget <= 0L || dw.toLong() * dh.toLong() > sceneBudget) return false
    LiquifyGlesPreview.configureFieldResolution(dw, dh, liquifyBrushSize)
    if (!v.liquifyFieldSource(0, 0, dw, dh)) return false
    // A single ordinary layer can be presented entirely by GLES. Its underlay
    // is drawn in the same GPU buffer, so transparent holes cannot reveal old paint.
    val only = v.layers.lastOrNull { it.visible }
    liquifyWholeLayerRect = if (only != null && only.index == v.currentLayerIndex &&
        only.visible && only.depth == 0 && only.nodeType == 0 && !only.isGroup && !only.isStrokeLayer &&
        !only.clipped && !only.alphaLocked && only.opacity == 1.0 &&
        only.blendMode == "normal" && !v.hasSelection && !v.anim.enabled && !v.pixelGridEnabled)
        intArrayOf(0, 0, dw, dh) else null
    // 画布尺寸先看瓦片网格 (1:1 路径下它才是画布真身), 再看整图位图。
    val sceneGrid = v.displayTiles.drawable()?.grid
    val sceneBitmap = if (sceneGrid != null) null else (v.displayBitmap ?: docBitmap)
    val sceneW = sceneGrid?.width ?: (sceneBitmap?.width ?: 0)
    val sceneH = sceneGrid?.height ?: (sceneBitmap?.height ?: 0)
    if (sceneW <= 0 || sceneH <= 0) liquifyWholeLayerRect = null
    LiquifyGlesPreview.configureOpaqueScene(
        if (liquifyWholeLayerRect != null) sceneW else 0,
        if (liquifyWholeLayerRect != null) sceneH else 0)
    liquifyDabCount = 0
    liquifyBasePolicy.reset()
    liquifyDrawRect = null
    lqAffectedL = Float.MAX_VALUE
    lqAffectedT = Float.MAX_VALUE
    lqAffectedR = -Float.MAX_VALUE
    lqAffectedB = -Float.MAX_VALUE
    return true
}

/**
 * 记一个补点(场通路): 追加进本地列表 + 扩大受影响矩形 + 把绘制/失效范围同步给覆盖层。
 *
 * 受影响矩形 = 各补点**影响圆**的并集 —— 场的位移只在圆内非零, 所以"绘制范围""失效范围"
 * "抬笔回读范围"是同一个矩形, 一次算清三处都用它。
 */
internal fun CanvasTouchView.recordLiquifyFieldDab(
    px: Float,
    py: Float,
    nx: Float,
    ny: Float,
    mode: Int,
    strength: Float,
) {
    if ((liquifyDabCount + 1) * FIELD_DAB_STRIDE > liquifyDabBuf.size) {
        val grown = FloatArray(maxOf(liquifyDabBuf.size * 2, FIELD_DAB_STRIDE * 256))
        System.arraycopy(liquifyDabBuf, 0, grown, 0, liquifyDabBuf.size)
        liquifyDabBuf = grown
    }
    val b = liquifyDabCount * FIELD_DAB_STRIDE
    liquifyDabBuf[b] = px
    liquifyDabBuf[b + 1] = py
    liquifyDabBuf[b + 2] = nx
    liquifyDabBuf[b + 3] = ny
    liquifyDabBuf[b + 4] = mode.toFloat()
    liquifyDabBuf[b + 5] = strength
    liquifyDabBuf[b + 6] = liquifyBrushSize
    liquifyDabCount++
    val r = liquifySupportRadius()
    lqAffectedL = minOf(lqAffectedL, minOf(px, nx) - r)
    lqAffectedT = minOf(lqAffectedT, minOf(py, ny) - r)
    lqAffectedR = maxOf(lqAffectedR, maxOf(px, nx) + r)
    lqAffectedB = maxOf(lqAffectedB, maxOf(py, ny) + r)
    // 绘制矩形不在这里推: 它必须与引擎的"预览基座"是同一个**整数**矩形, 由 liquifyFlushNow
    // 每帧统一推一次(浮点矩形与基座矩形差出一圈就会露背景或叠两次)。
}

/** 预览基座的推进门槛(文档像素): 影响半径的 1/8, 夹到 [4, 64]。 */
internal fun CanvasTouchView.liquifySupportRadius(): Float = liquifyBrushSize.coerceAtLeast(8f) * .5f +
    com.reverie.paint.model.LiquifyProfessional.fieldStep(liquifyBrushSize) + 2f

internal fun CanvasTouchView.liquifyBaseAdvanceStepPx(): Int =
    (liquifySupportRadius() / 8f).toInt().coerceIn(4, 64)

/**
 * 场通路的局部失效: 位移只在本 dab 的影响圆里变化, 所以只失效**该圆** ∪ 光标环前后位置。
 * 判据与 [scheduleLiquifyInvalidate] 同源(任一安全条件不满足就整屏), 只是脏区来源换成补点。
 */
internal fun CanvasTouchView.invalidateLiquifyFieldDab(cx: Float, cy: Float, radius: Float) {
    val v = vm ?: return
    if (!partialInvalidateEnabled || !canLiquifyPartialInvalidate(v)) {
        postInvalidate()
        return
    }
    ensureViewTransform()
    var left = Float.MAX_VALUE
    var top = Float.MAX_VALUE
    var right = -Float.MAX_VALUE
    var bottom = -Float.MAX_VALUE
    for (i in 0 until 4) {
        viewTransform.docToScreen(
            if (i == 0 || i == 2) cx - radius else cx + radius,
            if (i < 2) cy - radius else cy + radius,
            lqPointScratch,
        )
        val sx = lqPointScratch[0]
        val sy = lqPointScratch[1]
        if (sx < left) left = sx
        if (sx > right) right = sx
        if (sy < top) top = sy
        if (sy > bottom) bottom = sy
    }
    left -= 8f
    top -= 8f
    right += 8f
    bottom += 8f
    if (lqRingValid) {
        left = minOf(left, lqRingCx - lqRingR - 2f)
        top = minOf(top, lqRingCy - lqRingR - 2f)
        right = maxOf(right, lqRingCx + lqRingR + 2f)
        bottom = maxOf(bottom, lqRingCy + lqRingR + 2f)
    }
    if (hasLocalCursorPos) {
        val r = if (lqRingValid) lqRingR else 0f
        left = minOf(left, localCursorX - r - 2f)
        top = minOf(top, localCursorY - r - 2f)
        right = maxOf(right, localCursorX + r + 2f)
        bottom = maxOf(bottom, localCursorY + r + 2f)
    }
    val l = left.toInt().coerceIn(0, viewW)
    val t = top.toInt().coerceIn(0, viewH)
    val rr = right.toInt().coerceIn(0, viewW)
    val bb = bottom.toInt().coerceIn(0, viewH)
    if (rr <= l || bb <= t) return
    postInvalidate(l, t, rr, bb)
}

/**
 * 抬笔提交(场通路): 回读覆盖层的形变结果 → 一次性写回图层。
 *
 * 任何一步失败(覆盖层不在 / 回读超时 / 范围为空)都**回退经典路径**: 把记录下来的补点按序
 * 重放给引擎再 materialize ⇒ 形变一点不丢, 只是慢一些(而且重放走引擎线程, 不挡 UI)。
 */
internal fun CanvasTouchView.commitLiquifyField(v: PaintViewModel) {
    liquifyFieldGesture = false
    val rect = fieldCommitRect(v)
    if (liquifyDabCount > 0) {
        // 回读 + 写回 + 收口整段都在引擎线程上跑(见该方法的注释), UI 线程不阻塞
        // A rejected readback rectangle still has dabs to replay. Ending the
        // native transaction directly would discard the entire GPU gesture.
        v.liquifyFieldEndFromOverlay(
            rect ?: intArrayOf(0, 0, 0, 0), liquifyDabBuf, liquifyDabCount, FIELD_DAB_STRIDE,
        )
    } else {
        // 没有有效范围(纯点按): 走经典收口
        v.liquifyEnd()
    }
    liquifyDabCount = 0
    liquifyBasePolicy.reset()
    liquifyDrawRect = null
}

/**
 * 受影响矩形 → 文档整数矩形(夹到文档内; 空 ⇒ null = 回退经典路径)。
 *
 * 也会挡住"回读矩形过大": 回读要一次性分配 `w*h*4` 字节并过一次 JNI, 超预算就回退
 * **重放补点** —— 那条路是流式的, 不额外吃大块内存(体积换稳定性)。
 */
internal fun CanvasTouchView.fieldCommitRect(v: PaintViewModel): IntArray? {
    if (liquifyDabCount <= 0 || lqAffectedR <= lqAffectedL || lqAffectedB <= lqAffectedT) return null
    val x0 = floor(lqAffectedL).toInt().coerceAtLeast(0)
    val y0 = floor(lqAffectedT).toInt().coerceAtLeast(0)
    val x1 = ceil(lqAffectedR).toInt().coerceAtMost(v.docWidth)
    val y1 = ceil(lqAffectedB).toInt().coerceAtMost(v.docHeight)
    if (x1 <= x0 || y1 <= y0) return null
    val w = x1 - x0
    val h = y1 - y0
    val budget = fieldPathBudgetPx()
    if (budget <= 0L || w.toLong() * h.toLong() > budget) return null
    // 回读一次要三份同尺寸缓冲(Java 数组 / Direct 缓冲 / FBO 纹理) —— 再卡一道字节预算
    if (!MemoryBudget.commitBytesWithinBudget(w, h)) return null
    return intArrayOf(x0, y0, w, h)
}

/** C3-2: 低内存设备判定(用系统自己的分级, 而不是猜总内存)。 */
internal fun CanvasTouchView.isLowRamDevice(): Boolean = try {
    val am = context.getSystemService(android.content.Context.ACTIVITY_SERVICE)
        as? android.app.ActivityManager
    am?.isLowRamDevice == true
} catch (_: Throwable) {
    false
}

/**
 * 场通路当前生效的文档像素预算(动态)。
 *
 * 取较小值: 历史硬上限(4M px) 与 [MemoryBudget] 的堆安全预算(堆上限 / 每像素 32B)。
 * `0` = 这台设备上不可用 ⇒ 调用方一律回退经典逐 dab 路径。
 *
 * 为什么不能只看 `isLowRamDevice`: 它只覆盖 Android Go 级别的设备; 普通 4~6GB 手机上
 * Java 堆上限也可能只有 192~256MB, 而此时场通路(源/场/回读各一份)照样能把进程推到 OOM。
 */
internal fun CanvasTouchView.fieldPathBudgetPx(): Long {
    val maxHeap = try {
        Runtime.getRuntime().maxMemory()
    } catch (_: Throwable) {
        -1L
    }
    val lowRam = isLowRamDevice()
    val hard = if (lowRam) FIELD_PATH_MAX_PX_LOW_RAM else FIELD_PATH_MAX_PX
    return minOf(hard, MemoryBudget.fieldPathBudgetPx(maxHeap, lowRam)).coerceAtLeast(0L)
}

/**
 * 追加一个补点到批量提交缓冲(按需扩容, 复用), 返回新的补点计数。
 *
 * 缓冲只增不减: 一段手势内的补点数有界(移动距离 / 笔刷半径), 不增长就意味着不会反复分配。
 */
internal fun CanvasTouchView.appendLiquifyBatch(
    px: Float,
    py: Float,
    nx: Float,
    ny: Float,
    strength: Float,
    count: Int,
): Int {
    if ((count + 1) * LIQUIFY_BATCH_STRIDE > liquifyBatchBuf.size) {
        val grown = FloatArray(maxOf(liquifyBatchBuf.size * 2, LIQUIFY_BATCH_STRIDE * 64))
        System.arraycopy(liquifyBatchBuf, 0, grown, 0, liquifyBatchBuf.size)
        liquifyBatchBuf = grown
    }
    // 打包走 model 层的纯函数(与 JNI liquifyDabs 同序), 布局由 LiquifyModeConsistencyTest 守门
    return LiquifyPath.packDab(liquifyBatchBuf, count, px, py, nx, ny, strength, liquifyMode)
}

/**
 * Phase 3B: latest-state-wins 的"一帧推进"。
 *
 * 每帧最多推进 [LiquifyInteractionSession.maxDabsPerFlush] 个补点, 方向永远指向**最新**位置;
 * 没追完下一帧继续。与"逐个历史点立即处理"的差别只有两点: ①同一帧内多个输入事件被合并成
 * 一段(中间位置丢弃); ②单帧阻塞时间有上界 —— 不会再出现"一个事件里 10 个历史点 × 每个
 * 41ms"这种排队。
 */
internal fun CanvasTouchView.flushLiquifyPending() {
    liquifyFlushPosted = false
    val v = vm ?: return
    // Phase 6(稳定性 v2): 分帧物化推进 —— 每次最多消费 4ms 的落盘量, 把此前
    // 单次 126ms 的 rebase 尖峰摊到各帧。放在背压判断**之前**: 停手后即使不再新增
    // 补点(或引擎忙), 已积压的物化也必须继续推进, 否则会留下"半物化"的陈旧像素。
    if (!liquifyFieldGesture) v.tickLiquifyMaterialize()
    val materializePending = v.liquifyMaterializePending
    // 引擎背压: 队列里还积压着上一次推进(阈值见 LIQUIFY_ENGINE_BACKLOG_LIMIT)时,
    // 本帧**不再新增补点** —— 会话保留"最新位置", 下一帧继续追。
    // 高压拖动下这是把"队列雪崩 / 抬笔后画面还在继续变形"掐掉的关键一步。
    if (v.pendingCoreOps.get() > LIQUIFY_ENGINE_BACKLOG_LIMIT) {
        if ((liquifySession.hasPending || materializePending) && !liquifyFlushPosted) {
            liquifyFlushPosted = true
            postOnAnimation(liquifyFlushRunnable)
        }
        return
    }
    liquifyFlushNow(v, forceFull = false)
    if ((liquifySession.hasPending || materializePending) && !liquifyFlushPosted) {
        // 还没追上最新位置(或还有待落盘的行带): 下一帧继续推进
        liquifyFlushPosted = true
        postOnAnimation(liquifyFlushRunnable)
    }
}

/**
 * Phase 6: 液化笔压平滑(指数滑动, 系数 0.35 与笔刷路径同族)。
 *
 * 手指/鼠标输入直接跳过 —— Android 的手指 pressure 在多数机型上是 1.0 或噪声,
 * 参与调制只会让强度随触摸面积乱跳(这正是 SAI2/CSP 在触摸屏上的做法: 只认笔压)。
 */
internal fun CanvasTouchView.updateLiquifyPressure(event: MotionEvent, pointerIndex: Int) {
    val tt = event.getToolType(pointerIndex)
    if (tt != MotionEvent.TOOL_TYPE_STYLUS && tt != MotionEvent.TOOL_TYPE_ERASER) return
    val p = event.getPressure(pointerIndex).coerceIn(0f, 1f)
    val dt = if (lastLiquifyEventTimeMs > 0L) {
        (event.eventTime - lastLiquifyEventTimeMs).coerceIn(0L, 100L).toFloat()
    } else 0f
    val alpha = if (!liquifyPressureActive) 1f else 1f - kotlin.math.exp(-dt / 19.35f)
    smoothedPressure += (p - smoothedPressure) * alpha
    smoothedPressure = smoothedPressure.coerceIn(0f, 1f)
    liquifyPressureActive = true
}
