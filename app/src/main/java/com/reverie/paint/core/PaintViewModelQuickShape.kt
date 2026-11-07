/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.reverie.paint.core

import com.reverie.paint.R
import com.reverie.paint.model.QuickShapeAction
import com.reverie.paint.model.QuickShapeGeometry
import com.reverie.paint.model.QuickShapePhase
import com.reverie.paint.model.QuickShapeReplay
import com.reverie.paint.model.QuickShapeResult
import kotlin.math.PI

internal data class QuickShapeDraft(
    val original: FloatArray,
    val layer: Int,
    val frame: Int,
    val width: Int,
    val height: Int,
    val documentCreatedTime: Long,
    val recognizedShape: QuickShapeResult,
    // 目标树扩展字段: loadProject/newDocument 用它判断打开的原生笔画是否需要 touchCancel;
    // gpu-render-v2 状态机不再读写它, 保留默认值以兼容既有调用点。
    var nativeOriginalActive: Boolean = true,
)

/**
 * 抬笔且速创形状识别成功时触发。
 * 原手绘笔画在底层原生引擎中保持未提交状态, 后续由 QuickShapeSession 状态机在预览/提交/取消时
 * 通过 Stash 事务与重放接管原笔迹的恢复与覆盖。
 * @return true 表示成功进入速创编辑态; false 表示前置条件未满足 (调用方应兜底提交原笔画)。
 */
internal fun PaintViewModel.beginQuickShape(shape: QuickShapeResult, original: FloatArray): Boolean {
    if (original.size < 12 || renderHandler == null) return false
    quickShapeCapture = null
    quickShapeDraft = QuickShapeDraft(
        original, currentLayerIndex, anim.currentTime, docWidth, docHeight,
        canvasCreatedTime, shape
    )
    stopAirbrush()
    disarmStrokeStartKick()
    activeStrokeToolOverride = null
    activeStrokePresetSwitched = false

    val snapped = QuickShapeGeometry.snapLine(shape, quickShapeAngleSnapEnabled)
    val active = if (quickShapeCurvedContourEnabled &&
        (snapped.type == com.reverie.paint.model.QuickShapeType.QUADRILATERAL || snapped.type == com.reverie.paint.model.QuickShapeType.CONTOUR)) {
        QuickShapeGeometry.setContourCurved(snapped, true)
    } else snapped
    activeQuickShape = active
    isQuickShapeEditing = true
    quickShapeSession.begin()
    quickShapeReplaySeed = -1
    requestQuickShapePreview(activeQuickShape ?: shape)
    return true
}

internal fun PaintViewModel.requestQuickShapePreview(shape: QuickShapeResult) {
    val draft = quickShapeDraft ?: return
    if (currentLayerIndex != draft.layer || anim.currentTime != draft.frame ||
        docWidth != draft.width || docHeight != draft.height || canvasCreatedTime != draft.documentCreatedTime
    ) {
        showActionToast(R.string.quick_shape_context_changed, R.drawable.ic_line)
        cancelQuickShape()
        return
    }
    val generation = quickShapePreviewGeneration.incrementAndGet()
    quickShapePreviewSnapshot.set(PaintViewModel.QuickShapePreviewSnapshot(generation, shape))
    quickShapePreviewRequest.set(shape)
    if (!quickShapePreviewQueued.compareAndSet(false, true)) return
    runCore(render = false, after = {
        quickShapePreviewQueued.set(false)
        if (quickShapePreviewGeneration.get() != generation && !quickShapeCommitting) {
            val next = quickShapePreviewRequest.get()
            if (next != null) requestQuickShapePreview(next)
        }
    }) {
        if (quickShapeCommitting) return@runCore
        if (quickShapeReplaySeed < 0) {
            quickShapeReplaySeed = ReverieCoreBridge.currentStrokeSeed()
        }
        val currentShape = quickShapePreviewRequest.get() ?: shape
        val currentGen = quickShapePreviewGeneration.get()
        executePreviewStep(currentGen, currentShape)
        scheduleRender(immediate = true)
    }
}

internal fun PaintViewModel.updateQuickShapePreview() {
    val shape = activeQuickShape ?: return
    requestQuickShapePreview(shape)
}

private fun PaintViewModel.executePreviewStep(generation: Int, targetShape: QuickShapeResult): Boolean {
    val draft = quickShapeDraft ?: return false
    val action = quickShapeSession.preview(generation)
    when (action) {
        QuickShapeAction.StashCancelOriginalReplay -> {
            val ok = ReverieCoreBridge.stashOpenStrokePatch()
            if (!ok) {
                quickShapeSession.stashFailed()
                mainHandler.post {
                    showActionToast(R.string.quick_shape_stroke_too_large, R.drawable.ic_alert_triangle)
                }
                return false
            }
            ReverieCoreBridge.touchStrokeCancel()
        }
        QuickShapeAction.CancelPreviewReplay -> {
            ReverieCoreBridge.touchStrokeCancel()
        }
        QuickShapeAction.RevertRestoreReplay -> {
            ReverieCoreBridge.revertOpenRestorePatch()
        }
        QuickShapeAction.ReplayOnly -> {
            // StashOnly 阶段：纯重放，绝不再调用 stash
        }
        QuickShapeAction.RefuseAndKeepOriginal -> {
            return false
        }
        else -> return false
    }

    val samples = QuickShapeReplay.samples(
        draft.original,
        targetShape,
        quickShapePerPointPressureEnabled,
        draft.recognizedShape
    )
    if (samples.size < 12) {
        handleReplayFailure(generation)
        return false
    }

    var replaySuccess = false
    try {
        ReverieCoreBridge.setStrokeReplaySeed(quickShapeReplaySeed)
        ReverieCoreBridge.setStrokeReplayClock(1500.0)
        ReverieCoreBridge.touchStrokeStartWithSensors(
            samples[0].toDouble(), samples[1].toDouble(), samples[2].toDouble(),
            samples[3].toDouble(), samples[4].toDouble(), 0.0
        )
        val batch = FloatArray(PaintViewModel.STROKE_BATCH_CAPACITY * 6)
        var offset = 6
        while (offset < samples.size) {
            val length = minOf(batch.size, samples.size - offset)
            samples.copyInto(batch, 0, offset, offset + length)
            ReverieCoreBridge.touchStrokeMoveBatch(batch, length / 6)
            offset += length
        }
        replaySuccess = true
    } catch (t: Throwable) {
        android.util.Log.e("ReverieCore", "QuickShape replay batch failed", t)
    } finally {
        ReverieCoreBridge.setStrokeReplayClock(0.0)
    }

    val hadInk = replaySuccess && ReverieCoreBridge.lastFlushHadInk()
    return if (hadInk) {
        quickShapeSession.replayFinished(hadInk = true, generation = generation)
        true
    } else {
        handleReplayFailure(generation)
        false
    }
}

private fun PaintViewModel.handleReplayFailure(generation: Int) {
    ReverieCoreBridge.touchStrokeCancel()
    val restored = ReverieCoreBridge.restoreStashedStrokePatchOpen()
    if (restored) {
        quickShapeSession.restoreSucceeded(generation)
    } else {
        quickShapeSession.restoreFailed() // 进入 StashOnly，绝不回 OriginalOpen
        android.util.Log.e("ReverieCore", "Failed to restore stashed stroke patch")
    }
    mainHandler.post {
        showActionToast(R.string.quick_shape_failed, R.drawable.ic_line)
    }
}


internal fun PaintViewModel.commitQuickShape() {
    val draft = quickShapeDraft ?: return
    val shape = activeQuickShape ?: return
    if (quickShapeCommitting || renderHandler == null) return
    if (currentLayerIndex != draft.layer || anim.currentTime != draft.frame ||
        docWidth != draft.width || docHeight != draft.height || canvasCreatedTime != draft.documentCreatedTime
    ) {
        showActionToast(R.string.quick_shape_context_changed, R.drawable.ic_line)
        cancelQuickShape()
        return
    }

    quickShapeCommitting = true
    var success = false

    runCore(render = false, after = {
        quickShapeCommitting = false
        if (success) {
            totalStrokes++
            strokesSinceLastAutoSave++
            lastStrokeEndElapsedMs = android.os.SystemClock.elapsedRealtime()
            activeQuickShape = null
            quickShapeDraft = null
            isQuickShapeEditing = false
            refreshLayerThumbs()
            triggerTimelapseCapture(isChanged = true)
        }
    }) {
        val gen = quickShapePreviewGeneration.get()
        val coreAction = quickShapeSession.commit(gen)
        when (coreAction) {
            QuickShapeAction.EndPreview -> {
                ReverieCoreBridge.touchStrokeEnd()
                ReverieCoreBridge.discardStashedStrokePatch()
                isModified = true
                onPaintingActivity()
                success = true
            }
            QuickShapeAction.ReplayThenEnd -> {
                val replayed = executePreviewStep(gen, shape)
                if (replayed && quickShapeSession.phase == QuickShapePhase.PreviewOpen) {
                    ReverieCoreBridge.touchStrokeEnd()
                    ReverieCoreBridge.discardStashedStrokePatch()
                    isModified = true
                    onPaintingActivity()
                    success = true
                } else {
                    success = false
                }
            }
            QuickShapeAction.EndOriginal -> {
                ReverieCoreBridge.touchStrokeEnd()
                isModified = true
                onPaintingActivity()
                success = true
            }
            else -> {
                success = false
            }
        }
        ReverieCoreBridge.setStrokeReplaySeed(-1)
        scheduleRender(immediate = true)
    }
}

/** Cancel means keep the freehand stroke, not silently delete the user's drawing. */
internal fun PaintViewModel.cancelQuickShape() {
    val draft = quickShapeDraft ?: return
    if (quickShapeCommitting || renderHandler == null) return

    quickShapeCommitting = true
    var closeEditor = false
    runCore(render = false, after = {
        quickShapeCommitting = false
        if (closeEditor) {
            totalStrokes++
            strokesSinceLastAutoSave++
            lastStrokeEndElapsedMs = android.os.SystemClock.elapsedRealtime()
            isModified = true
            onPaintingActivity()
            activeQuickShape = null
            quickShapeDraft = null
            isQuickShapeEditing = false
            refreshLayerThumbs()
        }
    }) {
        val coreAction = quickShapeSession.cancel()
        when (coreAction) {
            QuickShapeAction.EndOriginal -> {
                ReverieCoreBridge.touchStrokeEnd()
                closeEditor = true
            }
            QuickShapeAction.CancelPreviewCommitStash -> {
                ReverieCoreBridge.touchStrokeCancel()
                val restored = ReverieCoreBridge.restoreStashedStrokePatchOpen()
                if (restored) {
                    ReverieCoreBridge.commitStashedStrokePatch()
                    closeEditor = true
                } else {
                    quickShapeSession.restoreFailed() // 进入 StashOnly，保持编辑器打开
                }
            }
            QuickShapeAction.CommitRestore -> {
                ReverieCoreBridge.commitStashedStrokePatch()
                closeEditor = true
            }
            QuickShapeAction.RestoreAndCommitStash -> {
                val restored = ReverieCoreBridge.restoreStashedStrokePatchOpen()
                if (restored) {
                    ReverieCoreBridge.commitStashedStrokePatch()
                    closeEditor = true
                } else {
                    // 写回依然失败，继续留在 StashOnly，不关编辑器
                }
            }
            else -> {}
        }
        ReverieCoreBridge.setStrokeReplaySeed(-1)
        scheduleRender(immediate = true)
    }
}

/**
 * 胶囊轴向按钮: 精确摆到指定朝向 (水平 0 / 垂直 90 / 45 度), 不受识别期容差限制。
 * 改 activeQuickShape 并触发预览, 提交路径不变。
 */
internal fun PaintViewModel.snapQuickShapeToAngle(targetDeg: Float) {
    if (!targetDeg.isFinite()) return
    val shape = activeQuickShape ?: return
    val updated = QuickShapeGeometry.snappedToAngle(shape, targetDeg)
    if (updated != shape) {
        activeQuickShape = updated
        requestQuickShapePreview(updated)
    }
}

/** 胶囊旋转按钮: 逆时针为正, 单位度 */
internal fun PaintViewModel.rotateQuickShapeBy(deltaDeg: Float) {
    if (!deltaDeg.isFinite()) return
    val shape = activeQuickShape ?: return
    val updated = QuickShapeGeometry.rotatedBy(shape, deltaDeg * (PI.toFloat() / 180f))
    activeQuickShape = updated
    requestQuickShapePreview(updated)
}

/** 胶囊缩放按钮: factor > 1 放大 */
internal fun PaintViewModel.scaleQuickShapeBy(factor: Float) {
    if (!factor.isFinite() || factor <= 0f) return
    val shape = activeQuickShape ?: return
    val updated = QuickShapeGeometry.scaledBy(shape, factor)
    activeQuickShape = updated
    requestQuickShapePreview(updated)
}
