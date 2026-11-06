/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import com.reverie.paint.model.RecordedBrushOverrides
import com.reverie.paint.model.RecordingEvents.CONTEXT
import com.reverie.paint.model.RecordingEvents.CONTEXT_EXT
import com.reverie.paint.model.RecordingEvents.CONTEXT_FADE
import com.reverie.paint.model.RecordingEvents.FILTER
import com.reverie.paint.model.RecordingEvents.FILTER_LUT
import com.reverie.paint.model.RecordingEvents.LAYER_OP
import com.reverie.paint.model.RecordingEvents.L_ADD
import com.reverie.paint.model.RecordingEvents.L_ADD_GROUP
import com.reverie.paint.model.RecordingEvents.L_ADD_LAYER_TYPE
import com.reverie.paint.model.RecordingEvents.L_ADD_MASK
import com.reverie.paint.model.RecordingEvents.L_ALPHA_LOCKED
import com.reverie.paint.model.AdjustmentConfigCodec
import com.reverie.paint.model.RecordingEvents.L_ADD_MASK_TYPE
import com.reverie.paint.model.RecordingEvents.L_ADJ_CONFIG
import com.reverie.paint.model.RecordingEvents.L_APPLY_FILTER
import com.reverie.paint.model.RecordingEvents.L_BLEND
import com.reverie.paint.model.RecordingEvents.L_CANVAS_FLIP_H
import com.reverie.paint.model.RecordingEvents.L_CANVAS_FLIP_V
import com.reverie.paint.model.RecordingEvents.L_FILL_LAYER
import com.reverie.paint.model.RecordingEvents.L_ALPHA_INHERITED
import com.reverie.paint.model.RecordingEvents.L_CLEAR
import com.reverie.paint.model.RecordingEvents.L_CLIPPED
import com.reverie.paint.model.RecordingEvents.L_COLOR_LABEL
import com.reverie.paint.model.RecordingEvents.L_COPY
import com.reverie.paint.model.RecordingEvents.L_FLATTEN_GROUP
import com.reverie.paint.model.RecordingEvents.L_FLIP_H
import com.reverie.paint.model.RecordingEvents.L_FLIP_V
import com.reverie.paint.model.RecordingEvents.L_LOCKED
import com.reverie.paint.model.RecordingEvents.L_MERGE_DOWN
import com.reverie.paint.model.RecordingEvents.L_MOVE
import com.reverie.paint.model.RecordingEvents.L_MOVE_ABOVE
import com.reverie.paint.model.RecordingEvents.L_MOVE_DOWN
import com.reverie.paint.model.RecordingEvents.L_MOVE_OUT
import com.reverie.paint.model.RecordingEvents.L_MOVE_RELATIVE
import com.reverie.paint.model.RecordingEvents.L_MOVE_TO_GROUP
import com.reverie.paint.model.RecordingEvents.L_MOVE_UP
import com.reverie.paint.model.RecordingEvents.L_OPACITY
import com.reverie.paint.model.RecordingEvents.L_PASS_THROUGH
import com.reverie.paint.model.RecordingEvents.L_RASTERIZE
import com.reverie.paint.model.RecordingEvents.L_REMOVE
import com.reverie.paint.model.RecordingEvents.L_REMOVE_MASK
import com.reverie.paint.model.RecordingEvents.L_INVERT_MASK
import com.reverie.paint.model.RecordingEvents.L_APPLY_MASK
import com.reverie.paint.model.RecordingEvents.L_RENAME
import com.reverie.paint.model.RecordingEvents.L_SET_BG
import com.reverie.paint.model.RecordingEvents.L_SET_CURRENT
import com.reverie.paint.model.RecordingEvents.L_SOLO
import com.reverie.paint.model.RecordingEvents.L_STAMP
import com.reverie.paint.model.RecordingEvents.L_VISIBLE
import com.reverie.paint.model.RecordingEvents.STROKE_CANCEL
import com.reverie.paint.model.RecordingEvents.STROKE_END
import com.reverie.paint.model.RecordingEvents.STROKE_MOVE
import com.reverie.paint.model.RecordingEvents.STROKE_START
import com.reverie.paint.model.RecordingEvents.TOOL_OP
import com.reverie.paint.model.RecordingEvents.T_CLEAR_SELECTION
import com.reverie.paint.model.RecordingEvents.T_CONTIGUOUS
import com.reverie.paint.model.RecordingEvents.T_CONTIGUOUS_V2
import com.reverie.paint.model.RecordingEvents.T_CONTRACT
import com.reverie.paint.model.RecordingEvents.T_CROP
import com.reverie.paint.model.RecordingEvents.T_EXPAND
import com.reverie.paint.model.RecordingEvents.T_FEATHER
import com.reverie.paint.model.RecordingEvents.T_FILL
import com.reverie.paint.model.RecordingEvents.T_FILL_V2
import com.reverie.paint.model.RecordingEvents.T_FILL_V3
import com.reverie.paint.model.RecordingEvents.T_ENCLOSE_FILL
import com.reverie.paint.model.RecordingEvents.T_GRADIENT
import com.reverie.paint.model.RecordingEvents.T_GRADIENT_V2
import com.reverie.paint.model.RecordingEvents.T_GRADIENT_V3
import com.reverie.paint.model.RecordingEvents.T_REDO
import com.reverie.paint.model.RecordingEvents.T_CANVAS_COPY
import com.reverie.paint.model.RecordingEvents.T_CANVAS_CUT
import com.reverie.paint.model.RecordingEvents.T_CANVAS_PASTE
import com.reverie.paint.model.RecordingEvents.T_PRESET_SELECT
import com.reverie.paint.model.RecordingEvents.T_UNDO
import com.reverie.paint.model.RecordingEvents.T_INVERT_SELECTION
import com.reverie.paint.model.RecordingEvents.T_LASSO
import com.reverie.paint.model.RecordingEvents.T_LASSO_CLEAR
import com.reverie.paint.model.RecordingEvents.T_LASSO_FILL
import com.reverie.paint.model.RecordingEvents.T_LIQUIFY
import com.reverie.paint.model.RecordingEvents.T_LIQUIFY_BEGIN
import com.reverie.paint.model.RecordingEvents.T_LIQUIFY_CANCEL
import com.reverie.paint.model.RecordingEvents.T_LIQUIFY_END
import com.reverie.paint.model.RecordingEvents.T_LIQUIFY_LAYERS
import com.reverie.paint.model.RecordingEvents.T_LIQUIFY_SIZE
import com.reverie.paint.model.RecordingEvents.T_MOVE_CONTENT
import com.reverie.paint.model.RecordingEvents.T_MOVE_CONTENT_LAYERS
import com.reverie.paint.model.RecordingEvents.T_PERSPECTIVE
import com.reverie.paint.model.RecordingEvents.T_POLYGON
import com.reverie.paint.model.RecordingEvents.T_SELECT_ALL
import com.reverie.paint.model.RecordingEvents.T_SELECT_ALL_CANVAS
import com.reverie.paint.model.RecordingEvents.T_SELECT_MODE
import com.reverie.paint.model.RecordingEvents.T_SELECT_POLYGON
import com.reverie.paint.model.RecordingEvents.T_SELECT_SHAPE
import com.reverie.paint.model.RecordingEvents.T_SHAPE
import com.reverie.paint.model.RecordingEvents.T_SHAPE_STROKE_WIDTH
import com.reverie.paint.model.RecordingEvents.T_SIMILAR
import com.reverie.paint.model.RecordingEvents.T_SIMILAR_V2
import com.reverie.paint.model.RecordingEvents.T_SMOOTH
import com.reverie.paint.model.RecordingEvents.T_TEXT
import com.reverie.paint.model.RecordingEvents.T_TRANSFORM
import com.reverie.paint.model.RecordingEvents.T_TRANSFORM_LAYERS
import com.reverie.paint.model.RecordingEvents.T_WARP
import com.reverie.paint.model.RecordingReader
import java.io.File
import kotlin.math.roundToInt

/**
 * Playback session: the parsed recording for one project. All playback
 * state is mutated only on the render thread (except UI-facing Compose
 * state, which is safe to write from any thread via the snapshot system).
 */
class ReplaySession(
    val events: ByteArray,
    val docW: Int,
    val docH: Int,
    val snapshotFile: File?,
    /** 录制头里的原始墙钟总时长 (含停笔/切走等空闲停顿) */
    val rawTotalMs: Long,
    val eventCount: Int,
    val version: Int = 1,
) {
    /** 每个事件 dt 的压缩上限 (ms)。整段事件流扫描失败时退回 Long.MAX_VALUE,
     *  即不压缩, 与旧行为完全一致。 */
    internal val idleCapMs: Long

    /** 有效总时长 (每事件 dt 封顶 [REPLAY_IDLE_CAP_MS] 后的总和)。step / seek /
     *  导出 / 进度条 / 时间标签统一使用这条有效时间线, currentMs 也是有效时间。
     *  不改录制格式: 原始 dt 仍原样存盘, 只在回放时压缩。 */
    val totalMs: Long

    init {
        val eff = ReplayTimeline.effectiveTotalMs(events, version, REPLAY_IDLE_CAP_MS, eventCount)
        if (eff >= 0L) {
            idleCapMs = REPLAY_IDLE_CAP_MS
            totalMs = eff
        } else {
            idleCapMs = Long.MAX_VALUE
            totalMs = rawTotalMs
        }
    }

    var isPlaying by mutableStateOf(false)
        internal set
    var progress by mutableFloatStateOf(0f)
        internal set
    var elapsedMs by mutableLongStateOf(0L)
        internal set
    var speed by mutableFloatStateOf(1f)
        internal set

    internal val reader = RecordingReader(events)
    internal var currentMs = 0L
    internal var lastPatternPng: ByteArray? = null
    internal var pendingStep: Runnable? = null
    internal var lastProgressWallMs = 0L
    internal var lastStepWallMs = 0L

    // Zero-allocation primitive brush cursor state for timelapse replay export
    internal var cursorActive = false
    internal var cursorX = 0f
    internal var cursorY = 0f
    internal var cursorPressure = 0.5f
    internal var cursorBrushSize = 18f
    internal var cursorToolMode = 0
    internal var cursorIsEraser = false
    internal var cursorColorArgb = 0xFF1F1D2B.toInt()

    /** Monotonic token of the current playback chain. pause/seek/stop bump
     *  it so a step that was already running on the render thread (and is
     *  about to postDelayed its successor) cannot resurrect playback after a
     *  pause - removeCallbacks alone cannot stop the in-flight step. */
    internal var stepGen = 0

    /** seek 合并令牌: 每次 seekReplay 递增; 渲染线程只执行最新一次 seek,
     *  被取代的排队 seek 直接丢弃 (连续拖动不再排队多次整段重放)。 */
    @Volatile internal var seekGen = 0

    /** 渲染线程最近一次执行完成的 seekGen; 与 [seekGen] 不等即有 seek 在途。 */
    @Volatile internal var appliedSeekGen = 0

    /** Stop playback and free the session's temp snapshot file. The vm
     *  removes any pending step callback first (see [PaintViewModel.pauseReplay]). */
    fun stop() {
        stepGen++
        seekGen++ // 作废已排队的 seek: 快照即将删除, 不能再对已退出的会话重放
        pendingStep = null
        isPlaying = false
        snapshotFile?.delete()
    }

    companion object {
        /** Read the "recording" entry from a .revp and parse it. */
        fun load(
            projectFile: File,
            tempDir: File,
        ): ReplaySession? {
            val parsed = PaintRecorder.readRecordingEntry(projectFile) ?: return null
            var temp: File? = null
            if (parsed.snapshot != null) {
                try {
                    tempDir.mkdirs()
                    // Sniff the source format: PNG sources flatten on load, so
                    // the temp file must keep the .png extension for loadPng
                    val snap = parsed.snapshot
                    val isPng =
                        snap.size >= 8 &&
                            snap[0] == 0x89.toByte() &&
                            snap[1] == 'P'.code.toByte() &&
                            snap[2] == 'N'.code.toByte() &&
                            snap[3] == 'G'.code.toByte()
                    temp = File(tempDir, if (isPng) "replay_snapshot.png" else "replay_snapshot.revp")
                    temp.writeBytes(snap)
                } catch (e: Exception) {
                    android.util.Log.e("ReveriePaint", "replay snapshot write failed", e)
                    temp = null
                }
            }
            return ReplaySession(
                events = parsed.events,
                docW = parsed.docW,
                docH = parsed.docH,
                snapshotFile = temp,
                rawTotalMs = parsed.totalMs,
                eventCount = parsed.eventCount,
                version = parsed.version,
            )
        }
    }
}

// ---- Playback control (UI thread entry points) ----

/** Toggle play/resume; restarts from the beginning when finished. */
internal fun PaintViewModel.playReplay() {
    val s = replaySession ?: return
    s.lastStepWallMs = android.os.SystemClock.elapsedRealtime()
    // seek 在途时 progress 仍是旧值 (可能是上次播完的 1f), 不能据此误判为"重播"
    val seekInFlight = s.appliedSeekGen != s.seekGen
    if (s.progress >= 1f && !seekInFlight) {
        seekReplay(0f)
        s.isPlaying = true
        scheduleReplayStep(s)
    } else if (!s.isPlaying) {
        s.isPlaying = true
        scheduleReplayStep(s)
    }
}

internal fun PaintViewModel.pauseReplay() {
    val s = replaySession ?: return
    s.stepGen++ // invalidate the in-flight step chain
    s.isPlaying = false
    s.lastStepWallMs = 0L
    s.pendingStep?.let { renderHandler?.removeCallbacks(it) }
    s.pendingStep = null
}

internal fun PaintViewModel.setReplaySpeed(v: Float) {
    replaySession?.let { it.speed = v.coerceIn(0.1f, 128f) }
}

/**
 * Scrub to a fraction (0..1) of the playback; resets and fast-forwards.
 *
 * 连续 seek 用 [ReplaySession.seekGen] 合并: 只有最新一次真正执行; 会话已退出
 * (replaySession !== s) 时丢弃; seekLocked 异常被捕获, 不再从裸 post 抛出崩溃。
 * [onDone] 在主线程回调一次: applied=true 表示本次 seek 已执行完且仍是最新;
 * false 表示被更新的 seek 取代、会话已退出或执行异常。
 */
internal fun PaintViewModel.seekReplay(
    fraction: Float,
    onDone: ((applied: Boolean) -> Unit)? = null,
) {
    val s = replaySession ?: run { onDone?.invoke(false); return }
    pauseReplay()
    val h = renderHandler ?: run { onDone?.invoke(false); return }
    val gen = ++s.seekGen
    val f = fraction.coerceIn(0f, 1f)
    h.post {
        var applied = false
        if (gen == s.seekGen && replaySession === s) {
            try {
                seekLocked(s, f)
                applied = true
            } catch (e: Exception) {
                android.util.Log.e("ReveriePaint", "replay seek failed", e)
            } finally {
                s.appliedSeekGen = gen
            }
        }
        if (onDone != null) {
            mainHandler.post { onDone(applied && gen == s.seekGen) }
        }
    }
}

/** Leave the replay page (cancels playback, frees the session). */
internal fun PaintViewModel.exitReplay() {
    replaySession?.stop()
    replaySession = null
    // Restore normal undo capture in case playback was left mid-way, and
    // re-push the UI tool/brush state to native: playback applied recorded
    // CONTEXT events straight into the core (tool mode, preset, size/opacity/
    // flow, composite op, color, current layer), so without this the UI kept
    // showing "brush" while the core still erased with the recording's last
    // eraser context - the brush literally behaved like an eraser until the
    // user re-tapped the tool icon.
    renderHandler?.post {
        ReverieCoreBridge.setUndoLimit(maxUndoSteps)
        ReverieCoreBridge.setUndoCaptureEnabled(true)
        ReverieCoreBridge.clearUndoHistory()
        restoreBrushStateFromUi()
    }
    goHome()
}

/** Re-apply the current UI tool/brush state to native (render thread only;
 *  mirrors applyReplayContextLocked's bridge calls, sourced from UI state). */
/** Re-assert the preset's eraser metadata after loadBrushPreset (loading a
 *  preset resets the native override to "unknown", which would fall back to
 *  name heuristics for custom-named eraser presets). */
internal fun PaintViewModel.restoreBrushStateFromUi() {
    com.reverie.paint.core.brushstore.BrushApply.restoreFromUi(this)
    if (currentLayerIndex >= 0) {
        ReverieCoreBridge.setCurrentLayer(currentLayerIndex)
    }
}

// ---- Render-thread playback engine ----

private var currentReplayPreset = -1
private var currentReplayVersion = 1
private val replayPresetIndexMapping = HashMap<Int, Int>()

private fun PaintViewModel.replayPresetSelectLocked(
    idx: Int,
    name: String,
    v2: com.reverie.paint.core.brushstore.PlaybackPresetCache.Select? = null,
) {
    val probe = v2 ?: com.reverie.paint.core.brushstore.PlaybackPresetCache.Select(idx, name, "", "")
    val rt = com.reverie.paint.core.brushstore.BrushApply.replayTarget(probe, brushPresets)
    val target = rt.index
    if (target < 0) return
    if (idx >= 0) {
        replayPresetIndexMapping[idx] = target
    }
    if (currentReplayPreset != target) {
        if (currentReplayPreset >= 0) {
            ReverieCoreBridge.touchStrokeCancel()
        }
        currentReplayPreset = target
        val sel = v2 ?: run {
            val info = brushPresets.firstOrNull { it.index == target }
            com.reverie.paint.core.brushstore.PlaybackPresetCache.Select(
                name = info?.name ?: name,
                index = target,
                presetId = info?.id ?: "",
                md5 = "",
            )
        }
        val isEraser = rt.isEraser
        com.reverie.paint.core.brushstore.BrushApply.applyReplay(sel, isEraser)
    }
}

internal fun PaintViewModel.scheduleReplayStep(s: ReplaySession) {
    val h = renderHandler ?: return
    val gen = s.stepGen
    val r = Runnable { replayStepLocked(s, gen) }
    s.pendingStep = r
    h.post(r)
}

private fun PaintViewModel.replayStepLocked(
    s: ReplaySession,
    gen: Int,
) {
    s.pendingStep = null
    if (gen != s.stepGen || !s.isPlaying) return
    val r = s.reader
    val now = android.os.SystemClock.elapsedRealtime()
    val wallDt = if (s.lastStepWallMs == 0L) 16L else (now - s.lastStepWallMs).coerceIn(1L, 100L)
    s.lastStepWallMs = now

    val stepSimMs = (wallDt * s.speed).toLong().coerceAtLeast(1L)
    val targetMs = ReplayTimeline.stepTargetMs(s.currentMs, stepSimMs, s.totalMs)

    var hasEvents = false
    // Several samples (especially generated shapes) may share the final millisecond.
    // At the end, drain those zero-delta events too; otherwise currentMs == totalMs
    // prevents all later ticks from consuming the remaining moves / stroke-end.
    // 时间按有效时间线推进 (每事件 dt 封顶 idleCapMs), 与 seek / 导出一致。
    try {
        s.currentMs =
            ReplayTimeline.advance(
                r,
                startMs = s.currentMs,
                targetMs = targetMs,
                drainAll = targetMs >= s.totalMs,
                capMs = s.idleCapMs,
                singleEvent = s.totalMs == 0L,
            ) { type, nowMs ->
                s.currentMs = nowMs
                dispatchReplayLocked(s, type, r, render = false)
                hasEvents = true
            }
    } catch (t: Throwable) {
        // 录制流损坏/遇到未知事件时不能让渲染线程崩掉 (用户只是点了个回放)。
        // 停播 + 明确提示, 画面留在最后一个能渲染的状态。
        abortReplayLocked(s, t)
        return
    }

    if (r.remaining() <= 0) {
        finishReplayLocked(s)
        return
    }

    if (hasEvents) {
        scheduleRender(immediate = true)
    }

    updateReplayProgress(s)
    val h = renderHandler ?: return
    val next = Runnable { replayStepLocked(s, gen) }
    s.pendingStep = next
    h.postDelayed(next, 16L)
}

/**
 * 回放数据不可继续 (录制损坏 / 未知事件 / 越界) 时的收尾: 停播、复位、提示, 绝不抛到
 * 渲染线程外 —— 以前这里会让整个 App 崩掉, 用户只是点开一个录制而已。
 */
private fun PaintViewModel.abortReplayLocked(s: ReplaySession, t: Throwable) {
    android.util.Log.e("ReveriePaint", "replay aborted: recording unreadable", t)
    s.pendingStep = null
    s.stepGen++
    s.isPlaying = false
    s.lastStepWallMs = 0L
    try {
        ReverieCoreBridge.touchStrokeEnd()
        ReverieCoreBridge.clearUndoHistory()
        ReverieCoreBridge.setUndoCaptureEnabled(true)
    } catch (_: Throwable) {
    }
    mainHandler.post {
        if (replaySession === s) {
            showActionToast(
                com.reverie.paint.R.string.toast_project_no_replay_data,
                com.reverie.paint.R.drawable.ic_alert_triangle,
            )
        }
    }
    scheduleRender(immediate = true)
}

private fun PaintViewModel.finishReplayLocked(s: ReplaySession) {
    s.pendingStep = null
    s.stepGen++
    s.isPlaying = false
    s.progress = 1f
    s.elapsedMs = s.totalMs
    s.lastStepWallMs = 0L
    // A recording truncated mid-stroke (process death while painting) leaves
    // the stroke transaction open on the layer; closing it here is a no-op
    // when the last event already ended the stroke cleanly
    ReverieCoreBridge.touchStrokeEnd()
    // Replay leaves no undo footprint: drop the commands it would have
    // accumulated and re-enable normal undo capture for the next session
    ReverieCoreBridge.clearUndoHistory()
    ReverieCoreBridge.setUndoCaptureEnabled(true)
    scheduleRender(immediate = true)
    refreshLayerThumbs()
}

private fun PaintViewModel.updateReplayProgress(s: ReplaySession) {
    s.elapsedMs = s.currentMs
    s.progress = ReplayTimeline.progressOf(s.currentMs, s.totalMs)
}

/** Runs inside a runCore op (render thread, before any replay dispatch). */
internal fun PaintViewModel.resetReplayDocLocked(
    s: ReplaySession,
    scheduleInitialRender: Boolean = true,
) {
    currentReplayPreset = -1
    replayPresetIndexMapping.clear()
    s.lastPatternPng = null
    s.cursorActive = false
    s.cursorBrushSize = 18f
    s.cursorToolMode = 0
    s.cursorIsEraser = false
    s.cursorColorArgb = 0xFF1F1D2B.toInt()
    currentReplayVersion = s.version
    val snap = s.snapshotFile
    var ok =
        if (snap != null && snap.exists()) {
            if (snap.extension.equals("png", ignoreCase = true)) {
                ReverieCoreBridge.loadPng(snap.absolutePath)
            } else {
                ReverieCoreBridge.loadRevp(snap.absolutePath)
            }
        } else {
            false
        }
    if (!ok) {
        // 无快照, 或快照损坏/落盘失败: 兜底空白画布, 避免回放画在残留的旧文档上
        ok = ReverieCoreBridge.newDocument(s.docW, s.docH)
    }
    if (ok) {
        ReverieCoreBridge.setLiquifyProfile(false, .5)
        ReverieCoreBridge.setLiquifyPreviewHostDrawMode(2)
        coreW = ReverieCoreBridge.docWidth()
        coreH = ReverieCoreBridge.docHeight()
        // setRenderViewport applies the same 4096 GPU-texture clamp the live
        // canvas uses; assigning coreW/coreH directly broke huge documents
        renderW = -1
        renderH = -1
        setRenderViewport(coreW, coreH)
        ReverieCoreBridge.touchStrokeCancel()
        ReverieCoreBridge.resetStrokeCounter()
        // 撤销上限先按当前设置来 (旧录制没有会话边界事件, 至少与常规行为一致);
        // 录制里带 T_SESSION_START 时, 每个会话边界会再精确套用当时的值 + 清栈 ——
        // 撤销栈不跨会话, 而录制是跨会话累积的, 只有边界处清栈才能让
        // 每个 T_UNDO/T_REDO 命中的命令与当时一致 (否则复杂作品回放会掉内容)。
        // 退出回放时在 exitReplay() 中恢复正常 maxUndoSteps 并清空栈。
        ReverieCoreBridge.setUndoLimit(maxUndoSteps)
        ReverieCoreBridge.setUndoCaptureEnabled(true)
        ReverieCoreBridge.clearUndoHistory()
        // Paint the initial frame right away so the canvas isn't stale
        // while the first stroke event is still queued
        if (scheduleInitialRender) {
            scheduleRender(immediate = true)
        }
    }
}

/** Fast-forward on the render thread: dispatch events with no pacing. */
internal fun PaintViewModel.seekLocked(
    s: ReplaySession,
    fraction: Float,
) {
    val f = fraction.coerceIn(0f, 1f)
    val target = ReplayTimeline.targetMsFor(f, s.totalMs)
    val r = s.reader
    // 只往回拖才需要重载快照。往后拖可以直接从当前状态继续快进 —— 以前每次都
    // resetReplayDocLocked (整个工程重新 loadRevp), 大工程一次 seek 就是十几秒,
    // 用户感觉"拖动条根本没用" (issue: 回放的拖动条没用)。
    val backward = target < s.currentMs
    if (backward) {
        resetReplayDocLocked(s)
        r.pos = 0
        s.currentMs = 0
    }
    // Seeking to 100% must include every event at the final timestamp as well.
    // Seek fast-forwards the document state only: per-event renders are
    // suppressed (one immediate render at the end), otherwise dragging
    // the scrub bar queued hundreds of throttled renders and stalled.
    try {
        s.currentMs = if (f >= 1f) {
            // 拖到 100% 必须含最后时间戳上的全部事件
            ReplayTimeline.advance(
                r,
                startMs = s.currentMs,
                targetMs = target,
                drainAll = true,
                capMs = s.idleCapMs,
            ) { type, nowMs ->
                s.currentMs = nowMs
                dispatchReplayLocked(s, type, r, render = false)
            }
        } else {
            // 只应用时间戳不晚于目标的那些事件: 否则"跨过目标"的那条会被提前执行,
            // 画面与进度不符 (拖到 25% 却出现更晚才该有的内容)
            ReplayTimeline.advanceExclusive(
                r,
                startMs = s.currentMs,
                targetMs = target,
                capMs = s.idleCapMs,
            ) { type, nowMs ->
                s.currentMs = nowMs
                dispatchReplayLocked(s, type, r, render = false)
            }
        }
    } catch (t: Throwable) {
        abortReplayLocked(s, t)
        return
    }
    // 进度/时间取实际 (有效) currentMs, 不再直接写 fraction: 否则 UI 停在拖到的
    // 位置, 画面却已越过空闲间隔, 一点继续进度就跳变
    val atEnd = ReplayTimeline.isAtEnd(f, r.remaining())
    s.elapsedMs = if (atEnd) s.totalMs else s.currentMs.coerceAtMost(s.totalMs)
    s.progress = if (atEnd) 1f else ReplayTimeline.progressOf(s.currentMs, s.totalMs)
    s.lastProgressWallMs = android.os.SystemClock.elapsedRealtime()
    s.lastStepWallMs = android.os.SystemClock.elapsedRealtime()
    scheduleRender(immediate = true)
}

// ---- Event dispatch (render thread; direct bridge calls, no re-recording) ----

/**
 * 确定 T_SELECT_ALL 事件中是否存在可选的选区布尔运算模式 mode (u8)。
 * 旧版或 selectAllAction 录制时仅写入 u16(layer) 共 2 字节；从图层菜单触发的 selectionFromLayer
 * 则额外写入 mode 共 3 字节。如果此处盲目用 r.remaining() > 0 读入第 3 字节，会把后续紧邻的
 * 下一条事件的 type 字节吃掉，导致整段录制流偏移错位。
 * 该函数通过安全前瞻与试跳读判断 mode 字节是否存在：存在时消费并返回其值，不存在时返回 0 且游标不推进。
 */
internal fun resolveSelectAllMode(r: RecordingReader, version: Int): Int {
    if (r.remaining() <= 0) return 0
    val b0 = r.data[r.pos].toInt() and 0xFF
    if (b0 > 3) return 0
    if (r.remaining() == 1) return r.u8()

    val b1 = r.data[r.pos + 1].toInt() and 0xFF
    val validTypes = intArrayOf(
        STROKE_START, STROKE_MOVE, STROKE_END, STROKE_CANCEL,
        CONTEXT, CONTEXT_EXT, CONTEXT_FADE,
        LAYER_OP, TOOL_OP, FILTER, FILTER_LUT,
    )
    if (b1 !in validTypes) return 0

    val savedPos = r.pos
    val validIfMode = try {
        r.pos = savedPos + 1
        val nextType = r.u8()
        r.varint()
        skipReplayEventPayload(nextType, r, version)
        true
    } catch (_: Exception) {
        false
    }
    val validIfNotMode = try {
        r.pos = savedPos
        val nextType = r.u8()
        r.varint()
        skipReplayEventPayload(nextType, r, version)
        true
    } catch (_: Exception) {
        false
    } finally {
        r.pos = savedPos
    }

    return if (validIfMode && !validIfNotMode) {
        r.u8()
    } else if (!validIfMode && validIfNotMode) {
        0
    } else {
        r.u8()
    }
}

/**
 * Advance [r] over a single event's binary payload without invoking native C++
 * or allocating arrays/strings. Used by timelapse export to pre-scan visual
 * checkpoints in < 1ms without executing strokes or filters twice.
 */
internal fun skipReplayEventPayload(
    type: Int,
    r: RecordingReader,
    version: Int = 2,
) {
    when (type) {
        STROKE_START, STROKE_MOVE -> r.skip(12)
        STROKE_END, STROKE_CANCEL -> {}
        CONTEXT -> {
            r.skip(15) // toolMode u8, preset u16, size/opacity/flow 3*f32
            r.skipStr() // compositeOp
            r.skipStr() // color
            r.skip(2) // layer u16
        }
        CONTEXT_EXT -> {
            r.skip(36) // 9 * f32
            r.skipStr() // secondaryColor
            r.skip(5) // airbrushEnabled u8, airbrushRate f32
            if (version >= 2) r.skip(1) // overrides u8
        }
        CONTEXT_FADE -> r.skip(4)
        LAYER_OP -> {
            r.skip(3) // op u8, index u16
            r.skipStr() // arg
        }
        FILTER -> {
            r.skip(35) // index u16, filterType u8, p1..p4 4*f64
            r.skipStr() // name
        }
        FILTER_LUT -> {
            r.skip(3) // index u16, kind u8
            val len = r.u32()
            r.skip(len)
            r.skipStr() // name
        }
        TOOL_OP -> {
            val op = r.u8()
            when (op) {
                com.reverie.paint.model.RecordingEvents.T_PATTERN_FILL -> {
                    r.skip(29) // layer u32, wholeLayer u8, x u32, y u32, tol u8, merged u8, expand u32, feather u8, closeGap u8, opacity f64
                    r.skipStr() // compositeOp
                    val pngLen = r.u32()
                    r.skip(pngLen)
                }
                T_SHAPE -> r.skip(18) // kind u8, x1..y2 4*f32, filled u8
                T_POLYGON -> {
                    r.skip(1) // closed u8
                    val n = r.u16()
                    r.skip(n * 8)
                }
                T_FILL, T_CONTIGUOUS, T_SIMILAR -> r.skip(10) // x/y f32, tol u16
                T_FILL_V2, T_CONTIGUOUS_V2, T_SIMILAR_V2 -> r.skip(11) // x/y f32, tol u16, sampleMerged u8
                T_FILL_V3 -> {
                    r.skip(11)
                    r.skipStr() // color
                }
                com.reverie.paint.model.RecordingEvents.T_FILL_V4 -> {
                    r.skip(11)
                    r.skipStr() // color
                    r.skip(6) // expand i16, feather u16, closeGap u16
                }
                T_GRADIENT -> r.skip(17) // x1..y2 4*f32, type u8
                T_GRADIENT_V2 -> r.skip(19) // x1..y2 4*f32, type u8, repeat u8, reverse u8
                T_GRADIENT_V3 -> {
                    r.skip(24) // x1..y2 4*f32, type/repeat/reverse u8*3, opacity f32, dither u8
                    r.skipStr() // blendMode
                    val n = r.u16()
                    r.skip(n * 8) // 每个色标 pos f32 + color u32
                }
                T_UNDO, T_REDO, T_CANVAS_COPY, T_CANVAS_CUT, T_CANVAS_PASTE,
                T_CLEAR_SELECTION, T_SELECT_ALL_CANVAS, T_INVERT_SELECTION,
                T_LIQUIFY_BEGIN, T_LIQUIFY_END, T_LIQUIFY_CANCEL -> {}
                T_PRESET_SELECT -> {
                    r.skip(2) // idx u16
                    r.skipStr() // name
                }
                // #127 W-12 的 V2 变体：idx u16 + name + presetId + 有效字节 md5。
                // #128 的跳读函数晚于它合入，只认识了 V1，于是这几段字符串被当成后续事件的
                // type 字节读掉——不会抛错，只会静默错位：effectiveTotalMs 返回 -1（空闲压缩
                // 失效、回放退回墙钟），导出的可视检查点数随之算错。
                com.reverie.paint.model.RecordingEvents.T_PRESET_SELECT_V2 -> {
                    r.skip(2) // idx u16 (0xFFFF 哨兵)
                    r.skipStr() // name
                    r.skipStr() // presetId
                    r.skipStr() // 有效字节 md5
                }
                T_TEXT -> {
                    r.skip(12) // x/y/fs 3*f32
                    r.skipStr() // txt
                }
                T_LIQUIFY -> r.skip(21) // fx/fy/tx/ty 4*f32, mode u8, strength f32
                T_MOVE_CONTENT -> r.skip(8) // dx/dy 2*f32
                T_TRANSFORM -> r.skip(72) // 9 * f64
                T_PERSPECTIVE -> r.skip(96) // 12 * f64
                T_WARP -> {
                    val n = r.u16()
                    r.skip(n * 16 + 32) // orig n*8 + trans n*8 + ox/oy/ow/oh 4*f64
                }
                T_CROP -> r.skip(8) // x/y/w/h 4*u16
                T_SELECT_SHAPE -> r.skip(17) // kind u8, x1..y2 4*f32
                T_SELECT_POLYGON, T_LASSO, T_LASSO_FILL, T_LASSO_CLEAR -> {
                    val n = r.u16()
                    r.skip(n * 8)
                }
                T_SELECT_ALL -> {
                    r.skip(2)
                    resolveSelectAllMode(r, version)
                }
                T_FEATHER, T_EXPAND, T_CONTRACT, T_SMOOTH -> r.skip(2)
                T_SELECT_MODE -> r.skip(1)
                T_ENCLOSE_FILL -> {
                    val n = r.u16()
                    r.skip(n * 8 + 15) // n*8 + tol(2)+merged(1)+expand(2)+feather(2)+closeGap(2)+method(1)+color(4)+incContour(1)
                }
                T_LIQUIFY_SIZE, T_SHAPE_STROKE_WIDTH -> r.skip(4)
                com.reverie.paint.model.RecordingEvents.T_LIQUIFY_PROFILE -> r.skip(5) // professional u8, hardness f32
                T_LIQUIFY_LAYERS, T_MOVE_CONTENT_LAYERS, T_TRANSFORM_LAYERS -> {
                    val n = r.u16()
                    r.skip(n * 2)
                }
                com.reverie.paint.model.RecordingEvents.T_SESSION_START -> r.skip(2) // undoLimit u16
                // 未知子类型：payload 长度不可知，继续往下读一定错位（且不会报错）。
                // 明确失败好过静默算错——effectiveTotalMs 会降级为 -1，导出走 onError。
                else -> unknownReplayEvent("tool_op", op)
            }
        }
        else -> unknownReplayEvent("event", type)
    }
}

/**
 * 跳读时遇到未登记的事件类型或工具子类型。
 *
 * 只在字节流里出现、而 [skipReplayEventPayload] 没登记时触发。payload 长度无从得知，
 * 继续读会把 payload 当成下一条事件的 type，整段事件流从此错位——op 50
 * （`T_PRESET_SELECT_V2`）漏登记时就是这样：不抛错，只是导出帧数和空闲压缩时长全错。
 * 所以这里记一条诊断后直接抛，让调用方明确降级（effectiveTotalMs → -1、导出 → onError），
 * 下次新增事件类型忘了登记会立刻暴露，而不是悄悄坏掉。
 */
private fun unknownReplayEvent(scope: String, op: Int): Nothing {
    com.reverie.paint.core.brushstore.BrushDiagnostics.log(
        "replay_skip_unknown",
        "scope" to scope, "op" to op,
    )
    throw IllegalStateException("unknown replay $scope op=$op: payload length unknown, stream desynced")
}

internal fun PaintViewModel.dispatchReplayLocked(
    s: ReplaySession,
    type: Int,
    r: RecordingReader,
    render: Boolean = true,
) {
    when (type) {
        STROKE_START -> {
            val x = r.f32()
            val y = r.f32()
            val p = r.f32()
            val timeSec = if (s.totalMs > 0) (s.currentMs / 1000.0) else -1.0
            s.cursorActive = true
            s.cursorX = x
            s.cursorY = y
            s.cursorPressure = p
            ReverieCoreBridge.touchStrokeStartWithTime(x.toDouble(), y.toDouble(), p.toDouble(), timeSec)
        }

        STROKE_MOVE -> {
            val x = r.f32()
            val y = r.f32()
            val p = r.f32()
            val timeSec = if (s.totalMs > 0) (s.currentMs / 1000.0) else -1.0
            s.cursorActive = true
            s.cursorX = x
            s.cursorY = y
            s.cursorPressure = p
            ReverieCoreBridge.touchStrokeMoveWithTime(x.toDouble(), y.toDouble(), p.toDouble(), timeSec)
            // Grow the stroke on screen: throttled render per move point,
            // same pacing the live painter uses while drawing (skipped while
            // seeking - seekLocked renders once at the end)
            if (render) scheduleRender()
        }

        STROKE_END -> {
            s.cursorActive = false
            ReverieCoreBridge.touchStrokeEnd()
            if (render) scheduleRender(immediate = true)
        }

        STROKE_CANCEL -> {
            s.cursorActive = false
            ReverieCoreBridge.touchStrokeCancel()
        }

        CONTEXT -> {
            applyReplayContextLocked(
                com.reverie.paint.model.ReplayContext(
                    toolMode = r.u8(),
                    preset = r.u16().let { if (it == 0xFFFF) -1 else it },
                    size = r.f32(),
                    opacity = r.f32(),
                    flow = r.f32(),
                    compositeOp = r.str(),
                    color = r.str(),
                    layer = r.u16().let { if (it == 0xFFFF) -1 else it },
                ),
            )
        }

        CONTEXT_EXT -> {
            // Extended brush params following a CONTEXT; apply via the same
            // setters the UI uses. Field order must match captureContextExt.
            val softness = r.f32()
            val spacing = r.f32()
            val angle = r.f32()
            val scatter = r.f32()
            val rotation = r.f32()
            val ratio = r.f32()
            val sharpness = r.f32()
            val smudgeRate = r.f32()
            val smudgeLength = r.f32()
            val secondaryColor = r.str()
            val airbrushEnabled = r.u8() != 0
            val airbrushRate = r.f32()
            val overrides = if (currentReplayVersion >= 2) r.u8() else 0
            com.reverie.paint.core.brushstore.BrushApply.applyReplayExt(
                currentReplayPreset, overrides,
                softness.toDouble(), spacing.toDouble(), angle.toDouble(), scatter.toDouble(), rotation.toDouble(),
                ratio.toDouble(), sharpness.toDouble(), smudgeRate.toDouble(), smudgeLength.toDouble(),
                secondaryColor, airbrushEnabled, airbrushRate.toDouble(),
            )
        }

        CONTEXT_FADE -> {
            com.reverie.paint.core.brushstore.BrushApply.applyReplayFade(r.f32().toDouble())
        }

        LAYER_OP -> {
            dispatchLayerOpLocked(r.u8(), r.u16(), r.str())
        }

        TOOL_OP -> {
            dispatchToolOpLocked(r.u8(), r)
        }

        FILTER -> {
            val index = r.u16()
            val filterType = r.u8()
            val p1 = r.f64()
            val p2 = r.f64()
            val p3 = r.f64()
            val p4 = r.f64()
            val name = r.str()
            replayFilterLocked(index, filterType, p1, p2, p3, p4, name)
        }

        FILTER_LUT -> {
            val index = r.u16()
            val kind = r.u8()
            val len = r.u32()
            val bytes = r.readBytes(len)
            val name = r.str()
            replayFilterLutLocked(index, kind, bytes, name)
        }
    }
}

private fun PaintViewModel.applyReplayContextLocked(c: com.reverie.paint.model.ReplayContext) {
    val targetPreset = if (c.preset >= 0) (replayPresetIndexMapping[c.preset] ?: c.preset) else -1
    if (targetPreset >= 0 && currentReplayPreset != targetPreset) {
        if (currentReplayPreset >= 0) {
            ReverieCoreBridge.touchStrokeCancel()
        }
        currentReplayPreset = targetPreset
        val p = brushPresets.firstOrNull { it.index == targetPreset }
        val pName = p?.name ?: ""
        val pId = p?.id ?: ""
        val sel = com.reverie.paint.core.brushstore.PlaybackPresetCache.Select(
            name = pName,
            index = targetPreset,
            presetId = pId,
            md5 = "",
        )
        val isEraser = p != null && (
            p.group == "橡皮擦" ||
                p.name.startsWith("a)_Eraser", ignoreCase = true) ||
                p.name.contains("Eraser", ignoreCase = true)
        )
        com.reverie.paint.core.brushstore.BrushApply.applyReplay(sel, isEraser)
    }
    val presetInfo = if (targetPreset >= 0) brushPresets.firstOrNull { it.index == targetPreset } else null
    val isPresetEraser =
        presetInfo != null &&
            (
                presetInfo.group == "橡皮擦" ||
                    presetInfo.name.startsWith("a)_Eraser", ignoreCase = true) ||
                    presetInfo.name.contains("Eraser", ignoreCase = true)
            )
    replaySession?.let { s ->
        s.cursorToolMode = c.toolMode
        s.cursorIsEraser = c.toolMode == 1 || c.compositeOp.equals("erase", ignoreCase = true) || isPresetEraser
        s.cursorBrushSize = c.size.coerceAtLeast(2f)
        s.cursorColorArgb =
            runCatching {
                android.graphics.Color.parseColor(c.color) or 0xFF000000.toInt()
            }.getOrDefault(0xFF1F1D2B.toInt())
    }
    // size/opacity/flow 由 CONTEXT 事件无条件携带 (录制时的实时值), 必须在
    // loadBrushPreset 之后应用——预设加载会重置它们; preset=-1 (无预设会话)
    // 时旧版直接跳过导致回放丢失这三个参数
    com.reverie.paint.core.brushstore.BrushApply.applyReplayContext(
        c.toolMode, c.size.toDouble(), c.opacity.toDouble(), c.flow.toDouble(), c.compositeOp, c.color,
    )
    ReverieCoreBridge.setCurrentLayer(c.layer)
}

private fun PaintViewModel.dispatchLayerOpLocked(
    op: Int,
    i: Int,
    arg: String,
) {
    // 0xFFFF sentinel = emit 时的 index -1 (如"刚建好的当前层")
    val i = if (i == 0xFFFF) -1 else i
    when (op) {
        L_ADD -> {
            ReverieCoreBridge.addLayer("")
        }

        L_REMOVE -> {
            ReverieCoreBridge.removeLayer(i)
        }

        L_SET_CURRENT -> {
            ReverieCoreBridge.setCurrentLayer(i)
        }

        L_BLEND -> {
            ReverieCoreBridge.setLayerBlendMode(i, arg)
        }

        L_VISIBLE -> {
            // New recordings carry the target visibility in arg; fall back to
            // the legacy toggle for old recordings with an empty arg.
            ReverieCoreBridge.setLayerVisible(
                i,
                if (arg.isEmpty()) !ReverieCoreBridge.layerVisible(i) else arg == "1",
            )
        }

        L_OPACITY -> {
            ReverieCoreBridge.setLayerOpacity(i, arg.toDoubleOrNull() ?: 1.0)
        }

        L_LOCKED -> {
            ReverieCoreBridge.setLayerLocked(i, arg == "1")
        }

        L_ALPHA_LOCKED -> {
            ReverieCoreBridge.setLayerAlphaLocked(i, arg == "1")
        }

        L_CLIPPED -> {
            ReverieCoreBridge.setLayerClipped(i, arg == "1")
        }

        L_ALPHA_INHERITED -> {
            ReverieCoreBridge.setLayerAlphaInherited(i, arg == "1")
        }

        L_RENAME -> {
            ReverieCoreBridge.setLayerName(i, arg)
        }

        L_CLEAR -> {
            ReverieCoreBridge.clearLayer(i)
        }

        L_COPY -> {
            ReverieCoreBridge.copyLayer(i)
        }

        L_MOVE -> {
            ReverieCoreBridge.moveLayer(i, arg.toIntOrNull() ?: i)
        }

        L_MOVE_ABOVE -> {
            ReverieCoreBridge.moveLayerAbove(i, arg.toIntOrNull() ?: i)
        }

        L_MOVE_TO_GROUP -> {
            ReverieCoreBridge.moveLayerToGroup(i, arg.toIntOrNull() ?: i)
        }

        L_MOVE_RELATIVE -> {
            val parts = arg.split(":")
            val target = parts.getOrNull(0)?.toIntOrNull() ?: i
            val above = parts.getOrNull(1) == "1"
            ReverieCoreBridge.moveLayerRelative(i, target, above)
        }

        L_MOVE_UP -> {
            ReverieCoreBridge.moveLayerUp(i)
        }

        L_MOVE_DOWN -> {
            ReverieCoreBridge.moveLayerDown(i)
        }

        L_MOVE_OUT -> {
            ReverieCoreBridge.moveLayerOut(i)
        }

        L_MERGE_DOWN -> {
            ReverieCoreBridge.mergeDown(i)
        }

        L_FLIP_H -> {
            ReverieCoreBridge.flipLayerHorizontal(i)
        }

        L_FLIP_V -> {
            ReverieCoreBridge.flipLayerVertical(i)
        }

        L_CANVAS_FLIP_H -> {
            ReverieCoreBridge.flipCanvasHorizontal()
        }

        L_CANVAS_FLIP_V -> {
            ReverieCoreBridge.flipCanvasVertical()
        }

        L_FILL_LAYER -> {
            val color = arg.toIntOrNull()
            if (color != null) {
                ReverieCoreBridge.setFillLayerColor(i, color)
            } else {
                ReverieCoreBridge.fillLayer(i)
            }
        }

        L_STAMP -> {
            ReverieCoreBridge.stampVisibleLayers()
        }

        L_ADD_GROUP -> {
            ReverieCoreBridge.addGroupLayer("")
        }

        L_SET_BG -> {
            ReverieCoreBridge.setBackgroundColor(arg.toIntOrNull() ?: 0, true)
        }

        L_COLOR_LABEL -> {
            ReverieCoreBridge.setLayerColorLabel(i, arg.toIntOrNull() ?: 0)
        }

        L_PASS_THROUGH -> {
            ReverieCoreBridge.setGroupPassThrough(i, arg == "1")
        }

        L_RASTERIZE -> {
            ReverieCoreBridge.rasterizeLayer(i)
        }

        L_FLATTEN_GROUP -> {
            ReverieCoreBridge.flattenGroup(i)
        }

        L_ADD_MASK, L_ADD_MASK_TYPE -> {
            ReverieCoreBridge.addMaskToLayer(i, arg.toIntOrNull() ?: 0)
        }

        L_REMOVE_MASK -> {
            ReverieCoreBridge.removeMask(i)
        }

        L_INVERT_MASK -> {
            ReverieCoreBridge.invertMask(i)
        }

        L_APPLY_MASK -> {
            ReverieCoreBridge.applyMask(i)
        }

        L_ADD_LAYER_TYPE -> {
            val p = arg.split("|")
            ReverieCoreBridge.addLayerWithType(
                p.getOrNull(0) ?: "",
                p.getOrNull(1)?.toIntOrNull() ?: 0,
                p.getOrNull(2)?.toLongOrNull()?.toInt() ?: 0xFFFFFFFF.toInt(),
            )
        }

        L_ADJ_CONFIG -> {
            val c = AdjustmentConfigCodec.decode(arg)
            if (c != null) {
                // 创建流里初始配置以 index=-1 记录, 解析为"刚建好的当前层"
                val target = if (i < 0) ReverieCoreBridge.currentLayerIndex() else i
                ReverieCoreBridge.setAdjustmentLayerConfig(target, c.type, c.p1, c.p2, c.p3, c.p4, c.lut)
            }
        }

        L_SOLO -> {
            ReverieCoreBridge.soloLayer(i)
        }

        L_APPLY_FILTER -> {
            ReverieCoreBridge.applyFilter(i, arg.toIntOrNull() ?: 0)
        }
    }
}

private fun PaintViewModel.dispatchToolOpLocked(
    op: Int,
    r: RecordingReader,
) {
    when (op) {
        com.reverie.paint.model.RecordingEvents.T_PATTERN_FILL -> {
            val event = com.reverie.paint.model.PatternFillEvent.readFrom(r, replaySession?.lastPatternPng)
            replaySession?.lastPatternPng = event.png
            if (!applyPatternFillLocked(event)) {
                android.util.Log.w("ReverieReplay", "Pattern fill skipped: target is not editable")
            }
        }
        T_SHAPE -> {
            val kind = r.u8()
            val x1 = r.f32()
            val y1 = r.f32()
            val x2 = r.f32()
            val y2 = r.f32()
            val filled = r.u8() == 1
            ReverieCoreBridge.drawShape(kind, x1.toInt(), y1.toInt(), x2.toInt(), y2.toInt(), filled)
        }

        T_POLYGON -> {
            val closed = r.u8() == 1
            val pts = readPointsLocked(r)
            val xs = IntArray(pts.size) { pts[it].first }
            val ys = IntArray(pts.size) { pts[it].second }
            ReverieCoreBridge.drawPolygon(xs, ys, pts.size, closed)
        }

        T_FILL -> {
            val x = r.f32().toInt()
            val y = r.f32().toInt()
            val tol = r.u16()
            ReverieCoreBridge.floodFillAt(x, y, tol)
        }

        T_GRADIENT -> {
            val x1 = r.f32().toInt()
            val y1 = r.f32().toInt()
            val x2 = r.f32().toInt()
            val y2 = r.f32().toInt()
            val t = r.u8()
            ReverieCoreBridge.gradientFill(x1, y1, x2, y2, t)
        }

        T_FILL_V2 -> {
            val x = r.f32().toInt()
            val y = r.f32().toInt()
            val tol = r.u16()
            val sampleMerged = r.u8() != 0
            ReverieCoreBridge.floodFillAt(x, y, tol, sampleMerged)
        }

        T_FILL_V3 -> {
            val x = r.f32().toInt()
            val y = r.f32().toInt()
            val tol = r.u16()
            val sampleMerged = r.u8() != 0
            val color = r.str()
            com.reverie.paint.core.brushstore.BrushApply.engine.setBrushColor(color)
            ReverieCoreBridge.floodFillAt(x, y, tol, sampleMerged)
        }

        com.reverie.paint.model.RecordingEvents.T_FILL_V4 -> {
            val x = r.f32().toInt()
            val y = r.f32().toInt()
            val tol = r.u16()
            val sampleMerged = r.u8() != 0
            val color = r.str()
            val expand = r.u16().toShort().toInt()
            val feather = r.u16()
            val closeGap = r.u16()
            com.reverie.paint.core.brushstore.BrushApply.engine.setBrushColor(color)
            ReverieCoreBridge.floodFillAt(x, y, tol, sampleMerged, expand, feather, closeGap)
        }

        T_GRADIENT_V2 -> {
            val x1 = r.f32().toInt()
            val y1 = r.f32().toInt()
            val x2 = r.f32().toInt()
            val y2 = r.f32().toInt()
            val t = r.u8()
            val repeat = r.u8()
            val reverse = r.u8() != 0
            ReverieCoreBridge.gradientFill(x1, y1, x2, y2, t, repeat, reverse)
        }

        T_GRADIENT_V3 -> {
            val x1 = r.f32().toInt()
            val y1 = r.f32().toInt()
            val x2 = r.f32().toInt()
            val y2 = r.f32().toInt()
            val t = r.u8()
            val repeat = r.u8()
            val reverse = r.u8() != 0
            val opacity = r.f32()
            val dither = r.u8() != 0
            val blendMode = r.str()
            val numStops = r.u16()
            val pos = FloatArray(numStops)
            val cols = IntArray(numStops)
            for (i in 0 until numStops) {
                pos[i] = r.f32()
                cols[i] = r.u32().toInt()
            }
            ReverieCoreBridge.gradientFillV3(
                x1, y1, x2, y2, t, repeat, reverse,
                opacity, blendMode, dither, pos, cols
            )
        }

        T_UNDO -> {
            // The replay rebuilt the native undo stack stroke-by-stroke in the
            // same order as the live session, so a plain native undo pops the
            // exact transaction the user undid while recording.
            if (ReverieCoreBridge.canUndo()) {
                ReverieCoreBridge.undo()
                val nw = ReverieCoreBridge.docWidth()
                val nh = ReverieCoreBridge.docHeight()
                if (nw > 0 && nh > 0 && (nw != coreW || nh != coreH)) {
                    coreW = nw
                    coreH = nh
                    renderW = -1
                    renderH = -1
                    setRenderViewport(coreW, coreH)
                }
            }
        }

        T_CANVAS_COPY -> ReverieCoreBridge.copyCanvasToClipboard(false)
        T_CANVAS_CUT -> ReverieCoreBridge.copyCanvasToClipboard(true)
        T_CANVAS_PASTE -> ReverieCoreBridge.pasteCanvasClipboard()
        T_REDO -> {
            if (ReverieCoreBridge.canRedo()) {
                ReverieCoreBridge.redo()
                val nw = ReverieCoreBridge.docWidth()
                val nh = ReverieCoreBridge.docHeight()
                if (nw > 0 && nh > 0 && (nw != coreW || nh != coreH)) {
                    coreW = nw
                    coreH = nh
                    renderW = -1
                    renderH = -1
                    setRenderViewport(coreW, coreH)
                }
            }
        }

        T_PRESET_SELECT -> {
            val idx = r.u16()
            val name = r.str()
            replayPresetSelectLocked(if (idx == 0xFFFF) -1 else idx, name)
        }

        com.reverie.paint.model.RecordingEvents.T_PRESET_SELECT_V2 -> {
            val sel = com.reverie.paint.core.brushstore.PlaybackPresetCache.read(r)
            replayPresetSelectLocked(sel.index, sel.name, sel)
        }

        T_TEXT -> {
            val x = r.f32().toInt()
            val y = r.f32().toInt()
            val fs = r.f32().toDouble()
            val txt = r.str()
            ReverieCoreBridge.drawText(x, y, txt, fs)
        }

        T_LIQUIFY -> {
            val fx = r.f32()
            val fy = r.f32()
            val tx = r.f32()
            val ty = r.f32()
            val mode = r.u8()
            val strength = r.f32().toDouble()
            ReverieCoreBridge.liquifyAt(fx, fy, tx, ty, strength, mode)
        }

        T_MOVE_CONTENT -> {
            val dx = r.f32().toInt()
            val dy = r.f32().toInt()
            ReverieCoreBridge.cancelTransformPreview()
            val layers = pendingReplayLayers
            pendingReplayLayers = null
            if (layers != null) {
                ReverieCoreBridge.moveLayerContentLayers(layers, dx, dy)
            } else {
                ReverieCoreBridge.moveLayerContent(dx, dy)
            }
        }

        T_TRANSFORM -> {
            val a = DoubleArray(9) { r.f64() }
            val layers = pendingReplayLayers
            pendingReplayLayers = null
            if (layers != null) {
                ReverieCoreBridge.applyTransformLayers(
                    layers, a[0], a[1], a[2], a[3], a[4], a[5], a[6], a[7], a[8],
                )
            } else {
                ReverieCoreBridge.applyTransform(a[0], a[1], a[2], a[3], a[4], a[5], a[6], a[7], a[8])
            }
        }

        T_PERSPECTIVE -> {
            val a = DoubleArray(12) { r.f64() }
            ReverieCoreBridge.applyPerspectiveTransform(
                a[0],
                a[1],
                a[2],
                a[3],
                a[4],
                a[5],
                a[6],
                a[7],
                a[8],
                a[9],
                a[10],
                a[11],
            )
        }

        T_WARP -> {
            val n = r.u16()
            val orig = ArrayList<Offset>(n)
            for (k in 0 until n) {
                orig.add(Offset(r.f32(), r.f32()))
            }
            val trans = ArrayList<Offset>(n)
            for (k in 0 until n) {
                trans.add(Offset(r.f32(), r.f32()))
            }
            val ox = r.f64()
            val oy = r.f64()
            val ow = r.f64()
            val oh = r.f64()
            ReverieCoreBridge.applyWarpMeshTransform(
                DoubleArray(n) { orig[it].x.toDouble() },
                DoubleArray(n) { orig[it].y.toDouble() },
                DoubleArray(n) { trans[it].x.toDouble() },
                DoubleArray(n) { trans[it].y.toDouble() },
                n,
                ox,
                oy,
                ow,
                oh,
            )
        }

        T_CROP -> {
            val x = r.u16()
            val y = r.u16()
            val w = r.u16()
            val h = r.u16()
            ReverieCoreBridge.cropCanvas(x, y, w, h)
            coreW = ReverieCoreBridge.docWidth()
            coreH = ReverieCoreBridge.docHeight()
            // Same 4096 clamp as the live canvas (setRenderViewport)
            renderW = -1
            renderH = -1
            setRenderViewport(coreW, coreH)
        }

        T_SELECT_SHAPE -> {
            val kind = r.u8()
            val x1 = r.f32().toInt()
            val y1 = r.f32().toInt()
            val x2 = r.f32().toInt()
            val y2 = r.f32().toInt()
            ReverieCoreBridge.selectShape(kind, x1, y1, x2, y2)
        }

        T_SELECT_POLYGON -> {
            val pts = readPointsLocked(r)
            ReverieCoreBridge.selectPolygon(
                IntArray(pts.size) { pts[it].first },
                IntArray(pts.size) { pts[it].second },
                pts.size,
            )
        }

        T_LASSO -> {
            val pts = readPointsLocked(r)
            ReverieCoreBridge.lassoSelect(
                IntArray(pts.size) { pts[it].first },
                IntArray(pts.size) { pts[it].second },
                pts.size,
            )
        }

        T_CONTIGUOUS -> {
            val x = r.f32().toInt()
            val y = r.f32().toInt()
            val tol = r.u16()
            selectionTolerance = tol
            ReverieCoreBridge.selectContiguousAt(x, y, tol)
        }

        T_CONTIGUOUS_V2 -> {
            val x = r.f32().toInt()
            val y = r.f32().toInt()
            val tol = r.u16()
            selectionTolerance = tol
            ReverieCoreBridge.selectContiguousAt(x, y, tol, r.u8() != 0)
        }

        T_SIMILAR -> {
            val x = r.f32().toInt()
            val y = r.f32().toInt()
            val tol = r.u16()
            selectionTolerance = tol
            ReverieCoreBridge.selectSimilarAt(x, y, tol)
        }

        T_SIMILAR_V2 -> {
            val x = r.f32().toInt()
            val y = r.f32().toInt()
            val tol = r.u16()
            selectionTolerance = tol
            ReverieCoreBridge.selectSimilarAt(x, y, tol, r.u8() != 0)
        }

        T_CLEAR_SELECTION -> {
            ReverieCoreBridge.clearSelection()
        }

        T_SELECT_ALL -> {
            val layer = r.u16()
            val mode = resolveSelectAllMode(r, currentReplayVersion)
            ReverieCoreBridge.selectionFromLayer(layer, mode)
        }

        T_SELECT_ALL_CANVAS -> {
            ReverieCoreBridge.selectAll()
        }

        T_INVERT_SELECTION -> {
            ReverieCoreBridge.invertSelection()
        }

        T_FEATHER -> {
            ReverieCoreBridge.featherSelection(r.u16())
        }

        T_EXPAND -> {
            ReverieCoreBridge.expandSelection(r.u16())
        }

        T_CONTRACT -> {
            ReverieCoreBridge.contractSelection(r.u16())
        }

        T_SMOOTH -> {
            ReverieCoreBridge.smoothSelection(r.u16())
        }

        T_SELECT_MODE -> {
            ReverieCoreBridge.setSelectionMode(r.u8())
        }

        T_LASSO_FILL -> {
            val pts = readPointsLocked(r)
            ReverieCoreBridge.lassoFill(
                IntArray(pts.size) { pts[it].first },
                IntArray(pts.size) { pts[it].second },
                pts.size,
            )
        }

        T_LASSO_CLEAR -> {
            val pts = readPointsLocked(r)
            ReverieCoreBridge.lassoClear(
                IntArray(pts.size) { pts[it].first },
                IntArray(pts.size) { pts[it].second },
                pts.size,
            )
        }

        T_ENCLOSE_FILL -> {
            val count = r.u16()
            val xs = IntArray(count)
            val ys = IntArray(count)
            for (i in 0 until count) {
                xs[i] = r.f32().toInt()
                ys[i] = r.f32().toInt()
            }
            val tol = r.u16()
            val sampleMerged = r.u8() != 0
            val expand = r.i16()
            val feather = r.u16()
            val closeGap = r.u16()
            val regionSelectionMethod = r.u8()
            val regionColorArgb = r.u32()
            val includeContourRegions = r.u8() != 0
            ReverieCoreBridge.encloseAndFill(
                xs, ys, count, tol, sampleMerged, expand, feather, closeGap,
                regionSelectionMethod, regionColorArgb, includeContourRegions
            )
        }

        T_LIQUIFY_SIZE -> {
            ReverieCoreBridge.setLiquifyBrushSize(r.f32().toDouble())
        }

        com.reverie.paint.model.RecordingEvents.T_LIQUIFY_PROFILE -> {
            val professional = r.u8() != 0
            ReverieCoreBridge.setLiquifyProfile(professional, r.f32().toDouble())
        }

        T_LIQUIFY_BEGIN -> {
            val layers = pendingReplayLayers
            pendingReplayLayers = null
            ReverieCoreBridge.liquifyBegin(layers)
        }

        T_LIQUIFY_END -> {
            ReverieCoreBridge.liquifyEnd()
        }

        T_LIQUIFY_CANCEL -> {
            ReverieCoreBridge.liquifyCancel()
        }

        T_LIQUIFY_LAYERS, T_MOVE_CONTENT_LAYERS, T_TRANSFORM_LAYERS -> {
            // Multi-layer target set recorded before a BEGIN/MOVE_CONTENT:
            // remembered and consumed by the following op (single-target
            // recordings never contain this event)
            val n = r.u16()
            pendingReplayLayers = if (n > 0) IntArray(n) { r.u16() } else null
        }

        com.reverie.paint.model.RecordingEvents.T_SESSION_START -> {
            // 会话边界: 撤销栈不跨会话 (打开工程 = 全新栈), 而录制是跨会话累积的。
            // 回放这里必须同样清栈并套用录制时的撤销上限, 否则后续每个 T_UNDO/T_REDO
            // 都会命中与当时不同的命令 —— 轻则少几笔, 重则整层内容被回退掉
            // (issue: 复杂作品回放内容缺失/乱入; 诊断工具 scripts/replay-verify.py)。
            val limit = r.u16()
            ReverieCoreBridge.clearUndoHistory()
            ReverieCoreBridge.setUndoLimit(limit)
        }

        T_SHAPE_STROKE_WIDTH -> {
            ReverieCoreBridge.setShapeStrokeWidth(r.f32().toDouble())
        }
    }
}

private fun PaintViewModel.replayFilterLocked(
    index: Int,
    filterType: Int,
    p1: Double,
    p2: Double,
    p3: Double,
    p4: Double,
    name: String,
) {
    if (filterType == 0xFF) return // LUT-based filter, not rebuildable from scalars
    ReverieCoreBridge.beginFilterPreview(index)
    ReverieCoreBridge.applyFilterPreview(index, filterType, p1, p2, p3, p4)
    ReverieCoreBridge.commitFilter(index, name)
}

/** Replay a LUT-based filter commit (curves RGB or gradient map). */
private fun PaintViewModel.replayFilterLutLocked(
    index: Int,
    kind: Int,
    lutBytes: ByteArray,
    name: String,
) {
    if (kind == 0) {
        if (lutBytes.size < 768) return
        val r = ByteArray(256)
        val g = ByteArray(256)
        val b = ByteArray(256)
        System.arraycopy(lutBytes, 0, r, 0, 256)
        System.arraycopy(lutBytes, 256, g, 0, 256)
        System.arraycopy(lutBytes, 512, b, 0, 256)
        ReverieCoreBridge.beginFilterPreview(index)
        ReverieCoreBridge.applyCurvesLUTPreview(index, r, g, b)
    } else {
        if (lutBytes.size < 1024) return
        val lut = IntArray(256)
        for (i in 0 until 256) {
            lut[i] = (lutBytes[i * 4].toInt() and 0xFF) or
                ((lutBytes[i * 4 + 1].toInt() and 0xFF) shl 8) or
                ((lutBytes[i * 4 + 2].toInt() and 0xFF) shl 16) or
                ((lutBytes[i * 4 + 3].toInt() and 0xFF) shl 24)
        }
        ReverieCoreBridge.beginFilterPreview(index)
        ReverieCoreBridge.applyGradientMapPreview(index, lut)
    }
    ReverieCoreBridge.commitFilter(index, name)
}

private fun readPointsLocked(r: RecordingReader): List<Pair<Int, Int>> {
    val n = r.u16()
    val out = ArrayList<Pair<Int, Int>>(n)
    for (i in 0 until n) {
        val x = r.f32().roundToInt()
        val y = r.f32().roundToInt()
        out.add(x to y)
    }
    return out
}
