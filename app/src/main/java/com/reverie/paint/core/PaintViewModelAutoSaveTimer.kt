/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.reverie.paint.model.*
import com.reverie.paint.perf.PerfHud
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// 拆分自 PaintViewModel.kt: 同域实现集中于此 (AGENTS.md §6 单文件行数上限)

internal fun PaintViewModel.notifyBrushModified() {
    brushModifiedEpoch++
}

internal fun PaintViewModel.onPaintingActivity() {
    val now = System.currentTimeMillis()
    if (lastActiveTimeMs == 0L || (now - lastActiveTimeMs) > IDLE_THRESHOLD_MS) {
        // Start or resume active drawing session
        lastActiveTimeMs = now
        lastTickTimeMs = now
    } else {
        // Continuously painting within 1-minute window
        lastActiveTimeMs = now
    }
}

internal fun PaintViewModel.tickPaintingTimer() {
    val now = System.currentTimeMillis()
    if (lastActiveTimeMs > 0L) {
        val deltaFromActive = now - lastActiveTimeMs
        if (deltaFromActive <= IDLE_THRESHOLD_MS) {
            // Within 1-minute active window
            if (lastTickTimeMs > 0L) {
                val dt = (now - lastTickTimeMs).coerceAtLeast(0L)
                if (dt in 1..2000) {
                    activeMillisAccumulator += dt
                    val addSecs = activeMillisAccumulator / 1000L
                    if (addSecs > 0) {
                        elapsedSeconds += addSecs
                        activeMillisAccumulator %= 1000L
                    }
                }
            }
            lastTickTimeMs = now
        } else {
            // Idle over 1 minute: pause timer
            lastTickTimeMs = now
        }
    } else {
        lastTickTimeMs = now
    }
}

internal fun PaintViewModel.startPaintingTimer() {
    if (lastAutoSaveTimeMs == 0L) {
        lastAutoSaveTimeMs = android.os.SystemClock.elapsedRealtime()
    }
    timerJob?.cancel()
    timerJob =
        viewModelScope.launch {
            while (isActive) {
                delay(1000L)
                if (currentPage == Page.PAINTING) {
                    tickPaintingTimer()
                    checkAutoSave()
                    // 标尺开启时顺带取一次保存/液化的引擎侧统计 (关闭时只剩一次布尔判断)。
                    // 必须走 runCore: 架构铁律要求引擎调用不经 UI 线程。这两条 JNI 只读
                    // C++ 侧的 relaxed 原子量、不牵动渲染, 所以 render = false。
                    if (PerfTrace.enabled) {
                        runCore(render = false) {
                            pollSaveStats()
                            pollLiquifyStats()
                            if (PerfHud.gridOverlayEnabled) pollLiquifyGrid()
                        }
                    }
                }
            }
        }
}

internal fun PaintViewModel.stopPaintingTimer() {
    tickPaintingTimer()
    timerJob?.cancel()
    timerJob = null
    lastActiveTimeMs = 0L
    lastTickTimeMs = 0L
    activeMillisAccumulator = 0L
}

internal fun PaintViewModel.checkAutoSave() {
    if (isQuickShapeEditing) return
    if (!autoSaveEnabled || isAutoSaving || isBlockingLoading) return
    if (currentPage != Page.PAINTING) return
    if (!hasUnsavedChanges()) return

    // 避免在用户正在落笔绘画或多指手势时保存，确保绘画热路径零干扰
    val touchView = com.reverie.paint.ui.painting.canvas.CanvasTouchView.activeTouchView
    if (touchView?.isInteracting == true || touchView?.isTransformActive == true) return

    // 无感化门控: 引擎队列非空 (笔画/滤镜/撤销在途) 或刚抬笔不久时不触发,
    // 防止 saveRevp 的秒级序列化排在下一笔前面造成卡顿/撤销迟滞。
    if (pendingCoreOps.get() > 0) return
    if (filterPreviewJob?.isActive == true) return
    val sinceStrokeEnd = android.os.SystemClock.elapsedRealtime() - lastStrokeEndElapsedMs
    if (lastStrokeEndElapsedMs != 0L && sinceStrokeEnd < 4_000L) return

    val now = android.os.SystemClock.elapsedRealtime()
    val intervalMs = autoSaveIntervalMinutes * 60 * 1000L
    if (lastAutoSaveTimeMs == 0L) {
        lastAutoSaveTimeMs = now
        return
    }

    val timeElapsed = now - lastAutoSaveTimeMs >= intervalMs
    val strokeThresholdReached = (strokesSinceLastAutoSave >= 60 || hasPendingMajorOp) && (now - lastAutoSaveTimeMs >= 60_000L)

    if (timeElapsed && sinceStrokeEnd >= 8_000L) {
        // 定期兜底自动保存 (默认 5 分钟，带轻量 Toast 提示，抬笔空闲 8 秒后静默触发)
        autoSaveProject(isPeriodic = true)
    } else if (strokeThresholdReached && sinceStrokeEnd >= 15_000L) {
        // 笔画或重大操作触发的空闲静默快照 (用户完全停笔休息 15 秒后触发，避免刚想画下一笔时撞上序列化)
        autoSaveProject(isPeriodic = false)
    }
}

internal fun PaintViewModel.onAppBackgrounded() {
    // 切后台时按键的 KeyUp 可能永远不会到: 丢掉挂起的"临时工具"状态, 避免下次进前台莫名其妙回切
    clearTemporaryToolHold()
    cancelQuickShape() // Restore the freehand draft before background backup is queued
    // 后台不再每秒唤醒: 计时与自动保存都只在绘画页前台有意义。
    // 必须放在下面几个早退之前 —— 否则"无未保存改动"时计时器会一直留在后台跑。
    stopPaintingTimer()
    stopSyncTimer()
    maybeExitBackup()
    if (!autoSaveEnabled || isAutoSaving || isBlockingLoading) return
    if (currentPage != Page.PAINTING) return
    // 只切过图层 (contentChanged=false) 也算"有东西没落盘": 这里静默存一次, 否则
    // "切到第 12 层 → 切后台/被杀"下次打开还停在第 5 层。刻意不把它并入
    // hasUnsavedChanges(): 那会让退出重新弹"未保存", 也会让 checkAutoSave 每点一次
    // 图层就拉响自动保存 —— 正是本 PR 要修的问题。
    if (!hasUnsavedChanges() && !selectionDirty) return

    // 软件切入后台时，立即触发后台静默自动保存
    autoSaveProject(isPeriodic = false)
}

/** 回到前台: 若仍停在绘画页, 恢复每秒计时与自动保存唤醒; 协同模式下立即探活防止后台断线后卡死。 */
internal fun PaintViewModel.onAppForegrounded() {
    if (currentPage == Page.PAINTING) startPaintingTimer()
    startSyncTimerIfNeeded()
    probeCollabConnectionOnForeground()
}

internal fun PaintViewModel.markModified() {
    isModified = true
}

internal fun PaintViewModel.markMajorOp() {
    hasPendingMajorOp = true
    isModified = true
}

internal fun PaintViewModel.hasUnsavedChanges(): Boolean {
    val strokesAdded = totalStrokes > initialStrokeCount
    return isModified || strokesAdded || ReverieCoreBridge.canUndo()
}

internal fun PaintViewModel.updatePerfHudEnabled(on: Boolean) {
    perfHudEnabled = on
    // 属性只在启动时打开一次 (PerfTrace.refreshFromSystemProp), 之后单方面为真;
    // 用户在这里显式关闭时必须能压过它, 否则表现为"标尺关不掉"。下次启动仍按属性打开。
    if (!on) PerfTrace.isEnabledByProp = false
    perfHudForcedByProp = PerfTrace.isEnabledByProp
    PerfTrace.enabled = on || PerfTrace.isEnabledByProp
    if (isAppContextReady()) {
        prefs
            .edit().putBoolean("perfHud", on).apply()
    }
}

internal fun PaintViewModel.updateLiquifyProxyPercent(pct: Int) {
    liquifyProxyPercent = pct
    LiquifyGpuPreview.proxyPercentOverride = pct
    if (isAppContextReady()) {
        prefs
            .edit().putInt("liquifyProxyPercent", pct).apply()
    }
}

internal fun PaintViewModel.updateLiquifyCoalesceSteps(steps: Int) {
    liquifyCoalesceSteps = steps
    PerfTrace.liquifyCoalesceOverride = steps
    if (isAppContextReady()) {
        prefs
            .edit().putInt("liquifyCoalesceSteps", steps).apply()
    }
}

internal fun PaintViewModel.updateLiquifyHostDraw(mode: Int) {
    liquifyHostDraw = mode
    LiquifyGpuPreview.hostDrawOverride = mode
    if (isAppContextReady()) {
        prefs
            .edit().putInt("liquifyHostDraw", mode).apply()
    }
}

internal fun PaintViewModel.updateLiquifyField(mode: Int) {
    liquifyField = mode
    LiquifyGlesPreview.fieldOverride = mode
    if (isAppContextReady()) {
        prefs
            .edit().putInt("liquifyField", mode).apply()
    }
}

/**
 * 取一次引擎侧"上一次保存"的阶段统计 (仅标尺开启时调用, 每秒一次)。保存慢的时候
 * 必须能看清是慢在快照、PNG 编码还是写盘, 否则只能盲改。
 *
 * **必须在引擎线程调用**(调用点见 `startPaintingTimer` 里的 `runCore`): 虽然 C++ 侧只做
 * `relaxed` 原子量读取、不触碰文档与投影, 但"引擎调用不经 UI 线程"是架构铁律, 不做例外。
 */
internal fun PaintViewModel.pollSaveStats() {
    if (!PerfTrace.enabled) return
    val s = ReverieCoreBridge.revpSaveStats() ?: return
    val profile = SaveProfile.fromStats(s) ?: return
    PerfTrace.saveStats(
        totalMs = profile.totalMs,
        snapshotMs = profile.snapshotMs,
        encodeMs = profile.encodeMs,
        writeMs = profile.writeMs,
        pngCount = profile.pngCount,
        pngBytes = profile.pngBytes,
        fileBytes = profile.fileBytes,
        wasAsync = profile.wasAsync,
        compositeMs = profile.compositeMs,
        layerCloneMs = profile.layerCloneMs,
        selMaskMs = profile.selMaskMs,
        convertMs = profile.convertMs,
        pngDeflateMs = profile.pngDeflateMs,
        zipWriteMs = profile.zipWriteMs,
        closeMs = profile.closeMs,
        reusedEntries = profile.reusedEntries,
        reusedBytes = profile.reusedBytes,
    )
}

/**
 * 取一次引擎侧"上一次液化 apply"的分段耗时 (仅标尺开启时调用, 每秒一次)。
 * 四段为 形变(Krita 网格) / 补洞(内存流量) / 回写图层 / 投影合成 —— 用来确定
 * "液化大笔刷卡顿"下一步该优化哪一段, 而不是凭感觉加线程。
 *
 * **必须在引擎线程调用**(调用点见 `startPaintingTimer` 里的 `runCore`)。
 */
internal fun PaintViewModel.pollLiquifyStats() {
    if (!PerfTrace.enabled) return
    // Phase 3 埋点 (docs/LIQUIFY-REBASE-INVESTIGATION.md §8): rebase / 节流两条物化边分开读。
    // 刻意放在下面 apply 的早退**之前** —— rebase 读数与"是否已有 apply"无关。
    ReverieCoreBridge.liquifyRebaseStats()?.let { PerfTrace.liquifyRebase(it) }
    val s = ReverieCoreBridge.liquifyStats() ?: return
    if (s.size < 10 || s[7] <= 0L) return // 还没做过液化
    PerfTrace.liquifyApply(s[0], s[1], s[2], s[3], s[4], s[5], s[6], s[7], s[8], s[9])
}

/**
 * 取一次液化网格快照 (仅"网格可视化"打开时调用, 每秒一次; **引擎线程**)。
 * 摘要进标尺 HUD 第 5 行, 原始网格交给 `PerfHud` 叠加绘制 —— 正式版里两者都不存在。
 */
internal fun PaintViewModel.pollLiquifyGrid() {
    val g = ReverieCoreBridge.liquifyGrid() ?: return
    if (g.size < 8) return
    val count = g[7].toInt()
    if (count <= 0) return
    PerfTrace.liquifyGrid(g[4].toInt(), g[5].toInt(), g[6].toInt(), count, g)
    PerfHud.setLiquifyGrid(g)
}

/**
 * Phase 5 · C3-2: 作废"源裁剪指纹", 让下一次取数**重新取一份源像素**。
 *
 * 为什么需要: 场通路的裁剪就是**整篇文档** ⇒ 两段手势的几何完全相同, 指纹也一样, 于是第二次
 * 手势会沿用上一段手势的源纹理 —— 而那份源早已被上一段手势的提交改过(画面会整体错位)。
 * 经典路径的裁剪每段手势都不同, 所以只在场通路里调它。
 */
internal fun PaintViewModel.invalidateLiquifySourceKey() {
    lqGpuCropKey = Long.MIN_VALUE
}

/**
 * Phase 2B: 取一次预览所需的"源裁剪 + 位移网格" (**引擎线程**, 调用点见 `doRender`)。
 *
 * 只有 GPU 诊断开关打开时才有内容, 且引擎此时既不生成也不叠加 CPU 预览:
 *  - 源裁剪(未形变)只在 rebase 时变 ⇒ 整段手势只上传一次纹理;
 *  - 位移网格每个 dab 都变 ⇒ 每次取(6~25KB 级小数组, 不做差分)。
 *
 * Phase 5 · C2: 取数链路**不变**, 只多一个消费端 —— 本次手势由 GLES 覆盖层画时,
 * 同一份 `crop/src/grid` 同时喂给 `LiquifyGlesPreview`(它自己的渲染线程上传纹理并出图);
 * AGSL 侧这一份仅用于"本帧文档脏区"基线(它不再建纹理, 见 `CanvasTouchView.drawCanvas`)。
 */
internal fun PaintViewModel.pollLiquifyGpuPreview() {
    if (liquifyPresentation.isPending(liquifyPresentationGesture)) return
    // GLES failure: retire the independent preview and restore document projection.
    // "要么 GPU 画, 要么引擎画"是这条线不可破的底线; 恢复动作必须在引擎线程做(JNI 时序由
    // ViewModel 保证), 只做一次 —— 失败后 LiquifyGlesPreview.failed 会挡掉后续手势的重试。
    if (LiquifyGlesPreview.failed && !lqGlesRecovered) {
        lqGlesRecovered = true
        LiquifyGlesPreview.clear()
        // The old preview base must be retired together with the failed surface.
        // 这里已经在引擎线程上, 可直接调 JNI。
        ReverieCoreBridge.setLiquifyPreviewBaseRect(0, 0, 0, 0)
        ReverieCoreBridge.setLiquifyPreviewHostDrawMode(2)
        return
    }
    if (!LiquifyGpuPreview.requested) {
        // HUD 读数: 走到这里 = 预览由引擎侧 CPU 叠加(没有 GPU 覆盖层接手)
        if (PerfTrace.enabled) PerfTrace.liquifyHost(PerfTrace.HOST_ENGINE)
        return
    }
    val crop = ReverieCoreBridge.liquifyPreviewSourceMeta() ?: return
    if (crop.size < 7) return
    if (crop[0] <= 0) {
        // Field source is prepared asynchronously after begin; do not cancel it mid-flight.
        if (LiquifyGlesPreview.fieldArmed) return
        // 没有源裁剪(超出面积预算 / 非 8bit BGRA / 手势结束): 摘掉覆盖层, 由引擎侧
        // CPU 预览兜底 —— 不能两边都不画
        LiquifyGpuPreview.clear()
        return
    }
    val seq = crop[6].toLong()
    if (seq == lqGpuPreviewSeq) return
    lqGpuPreviewSeq = seq
    val key = LiquifyGpuPreview.cropKeyOf(crop)
    val src =
        if (key != lqGpuCropKey) {
            lqGpuCropKey = key
            val need = crop[0] * crop[1] * 4
            lqSrcBufUseA = !lqSrcBufUseA
            var buf = if (lqSrcBufUseA) lqSrcBufA else lqSrcBufB
            if (buf == null || buf.size != need) {
                buf = ByteArray(need)
                if (lqSrcBufUseA) lqSrcBufA = buf else lqSrcBufB = buf
            }
            ReverieCoreBridge.liquifyPreviewSourcePixelsInto(buf)
            buf
        } else {
            null
        }
    val grid = ReverieCoreBridge.liquifyGrid()
    val useGles = LiquifyGlesPreview.requested
    // HUD 读数: 这一行是判断"设置里的预览方式生效了没"的唯一依据(见 PerfTrace.liquifyHost)
    if (PerfTrace.enabled) {
        PerfTrace.liquifyHost(if (useGles) PerfTrace.HOST_GLES else PerfTrace.HOST_AGSL)
    }
    // 脏区基线两条路都要: GLES 接管时 AGSL 侧不会被 draw(不会建纹理), 只贡献脏区计算
    LiquifyGpuPreview.update(crop, src, grid)
    if (useGles) {
        val underlay = if (src != null && LiquifyGlesPreview.fieldArmed) {
            ByteArray(src.size).also {
                if (!ReverieCoreBridge.liquifyPreviewUnderlayPixelsInto(it)) {
                    LiquifyGlesPreview.markFailed()
                }
            }
        } else null
        if (LiquifyGlesPreview.failed) return
        LiquifyGlesPreview.update(crop, src, grid, underlay)
        // 上报实际在画的那条路的读数(语义与 AGSL 侧同名字段一致)
        // Phase 3B: 源纹理上传次数(正常恒为 1; > 1 说明高速拖动中 rebase 过频)
        PerfTrace.liquifyUpload(LiquifyGlesPreview.sourceUploadCount)
        val tw = LiquifyGlesPreview.textureWidth
        val th = LiquifyGlesPreview.textureHeight
        PerfTrace.liquifyProxy(tw, th, tw.toLong() * th.toLong() * 4L)
        // 干预实验(§4.15): "暂存 / 网格上传"计数, 验证"上传 ≤ 1/帧"
        PerfTrace.liquifyPipeline(LiquifyGlesPreview.previewUpdates, LiquifyGlesPreview.gridUploads)
    } else {
        // Phase 3B: 上报源纹理上传次数(正常恒为 1; > 1 说明高速拖动中 rebase 过频)
        PerfTrace.liquifyUpload(LiquifyGpuPreview.sourceUploadCount)
        // 实验 A: 上报预览源纹理(代理)尺寸与占用, 便于对照不同代理分辨率
        PerfTrace.liquifyProxy(
            LiquifyGpuPreview.proxyWidth,
            LiquifyGpuPreview.proxyHeight,
            LiquifyGpuPreview.proxyUploadBytes,
        )
        // 干预实验(§4.15): 上报预览"暂存 / 网格上传"计数, 验证"上传 ≤ 1/帧"
        PerfTrace.liquifyPipeline(LiquifyGpuPreview.previewUpdates, LiquifyGpuPreview.gridUploads)
    }
    // 主机侧绘制模式下引擎不写显示缓冲(没有脏区), 所以"该重绘了"必须由这里发起。
    // Liquify V2 · Phase 2 (docs/LIQUIFY-V2-PLAN.md §4): 改走 CanvasTouchView 的**局部失效**
    // 入口 —— 它回到 UI 线程, 用"本帧文档脏区 ∪ 光标环前后位置"算一个最小重绘矩形,
    // 任一安全条件不满足时自行整屏回退。原来这里是无条件整屏 postInvalidate。
    com.reverie.paint.ui.painting.canvas.CanvasTouchView.activeTouchView
        ?.onLiquifyPreviewUpdated()
}
