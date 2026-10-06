/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.reverie.paint.core

import com.reverie.paint.R
import com.reverie.paint.model.QuickShapeGeometry
import com.reverie.paint.model.QuickShapeResult

internal data class QuickShapeDraft(
    val original: FloatArray,
    val layer: Int,
    val frame: Int,
    val width: Int,
    val height: Int,
    val documentCreatedTime: Long,
    var nativeOriginalActive: Boolean = true,
)

/** Called only at pen-up: the ordinary stroke is still an uncommitted native transaction. */
internal fun PaintViewModel.beginQuickShape(shape: QuickShapeResult, original: FloatArray) {
    if (original.size < 12 || renderHandler == null) return
    quickShapeCapture = null
    quickShapeDraft = QuickShapeDraft(original, currentLayerIndex, anim.currentTime, docWidth, docHeight, canvasCreatedTime)
    stopAirbrush()
    disarmStrokeStartKick()
    activeQuickShape = QuickShapeGeometry.snapLine(shape, quickShapeAngleSnapEnabled)
    isQuickShapeEditing = true
}

internal fun PaintViewModel.commitQuickShape() = finishQuickShape(restoreOriginal = false)

/** Cancel means keep the freehand stroke, not silently delete the user's drawing. */
internal fun PaintViewModel.cancelQuickShape() = finishQuickShape(restoreOriginal = true)

private fun PaintViewModel.finishQuickShape(restoreOriginal: Boolean) {
    val draft = quickShapeDraft ?: return
    val shape = activeQuickShape ?: return
    if (quickShapeCommitting || renderHandler == null) return
    // The editor blocks tool/layer/frame changes; guard external document changes as well.
    if (currentLayerIndex != draft.layer || anim.currentTime != draft.frame ||
        docWidth != draft.width || docHeight != draft.height || canvasCreatedTime != draft.documentCreatedTime) {
        showActionToast(R.string.quick_shape_context_changed, R.drawable.ic_line)
        activeQuickShape = null
        quickShapeDraft = null
        isQuickShapeEditing = false
        return
    }
    if (restoreOriginal && draft.nativeOriginalActive) {
        // Keep the original native transaction (including random brush texture, smoothing and taper).
        // Replaying the captured samples here would not reliably reproduce the same pixels.
        quickShapeCommitting = true
        touchEnd()
        runCore(render = false, after = {
            quickShapeCommitting = false
            activeQuickShape = null
            quickShapeDraft = null
            isQuickShapeEditing = false
        }) {}
        return
    }
    val samples = if (restoreOriginal) draft.original else {
        val path = QuickShapeGeometry.outline(shape)
        if (path.size < 2 || path.any { !it.x.isFinite() || !it.y.isFinite() }) return
        val pressure = (draft.original.indices.step(6).sumOf { draft.original[it + 2].toDouble() } /
            (draft.original.size / 6)).toFloat().coerceIn(0.01f, 1f)
        FloatArray(path.size * 6).also { out ->
            path.forEachIndexed { i, p ->
                out[i * 6] = p.x; out[i * 6 + 1] = p.y; out[i * 6 + 2] = pressure
                out[i * 6 + 3] = draft.original[3]; out[i * 6 + 4] = draft.original[4]
            }
        }
    }
    quickShapeCommitting = true
    if (draft.nativeOriginalActive) {
        touchCancel()
        draft.nativeOriginalActive = false
    }
    isModified = true
    onPaintingActivity()
    var success = false
    runCore(after = {
        quickShapeCommitting = false
        if (success) {
            totalStrokes++
            strokesSinceLastAutoSave++
            lastStrokeEndElapsedMs = android.os.SystemClock.elapsedRealtime()
            activeQuickShape = null
            quickShapeDraft = null
            isQuickShapeEditing = false
            refreshLayerThumbs()
        } else showActionToast(R.string.quick_shape_failed, R.drawable.ic_line)
    }) {
        try {
            ReverieCoreBridge.setToolMode(0)
            ReverieCoreBridge.touchStrokeStartWithSensors(samples[0].toDouble(), samples[1].toDouble(),
                samples[2].toDouble(), samples[3].toDouble(), samples[4].toDouble(), 0.0)
            // A single render-thread operation avoids overflowing the UI stroke queue on long curves.
            val batch = FloatArray(PaintViewModel.STROKE_BATCH_CAPACITY * 6)
            var offset = 6
            while (offset < samples.size) {
                val length = minOf(batch.size, samples.size - offset)
                samples.copyInto(batch, 0, offset, offset + length)
                ReverieCoreBridge.touchStrokeMoveBatch(batch, length / 6)
                offset += length
            }
            ReverieCoreBridge.touchStrokeEnd()
            // Reuse the established stroke event format; no new .revp or replay opcode.
            if (recorder.recording) {
                recorder.strokeStart(samples[0], samples[1], samples[2])
                for (i in 6 until samples.size step 6) recorder.strokeMove(samples[i], samples[i + 1], samples[i + 2])
                recorder.strokeEnd()
            }
            success = true
        } catch (t: Throwable) {
            ReverieCoreBridge.touchStrokeCancel()
            android.util.Log.e("ReverieCore", "QuickShape brush commit failed", t)
        }
        scheduleRender(immediate = true)
    }
}
