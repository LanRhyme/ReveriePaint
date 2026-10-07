/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.reverie.paint.core

import com.reverie.paint.R
import com.reverie.paint.model.PatternFillEvent

internal fun PaintViewModel.setFillLayerPattern(index: Int, pattern: FillPattern) {
    if (layers.getOrNull(index)?.locked != false || isLayerEffectivelyHidden(index)) return
    applyPatternFill(PatternFillEvent(layer = index, wholeLayer = true, png = pattern.png))
}

internal fun PaintViewModel.floodFillPattern(
    x: Float, y: Float, tolerance: Int, merged: Boolean, expand: Int, feather: Int, closeGap: Int,
) {
    val pattern = fillPattern ?: return
    val index = currentLayerIndex
    if (!x.isFinite() || !y.isFinite() || index < 0 || layers.getOrNull(index)?.locked != false) return
    applyPatternFill(PatternFillEvent(index, false, x.toInt(), y.toInt(), tolerance, merged,
        expand, feather, closeGap, fillOpacity.coerceIn(0.0, 1.0), fillCompositeOp, pattern.png))
}

private fun PaintViewModel.applyPatternFill(event: PatternFillEvent) {
    if (recorder.recording) recorder.patternFill(event)
    var success = false
    runCore(after = {
        if (success) notifyLayerChanged(pixelChanged = true)
        else showActionToast(R.string.pattern_fill_failed, R.drawable.ic_fill)
    }) {
        success = applyPatternFillLocked(event)
        if (success) ReverieCoreBridge.flushOnionSkinCaches()
    }
}

/** Render-thread only. Explicit layer index makes the event independent of brush context. */
internal fun applyPatternFillLocked(event: PatternFillEvent): Boolean {
    if (event.wholeLayer) return ReverieCoreBridge.setFillLayerPattern(event.layer, event.png)
    ReverieCoreBridge.setCurrentLayer(event.layer)
    val op = if (event.compositeOp == "difference") "diff" else event.compositeOp
    return ReverieCoreBridge.floodFillPatternAt(event.x, event.y, event.tolerance, event.sampleMerged,
        event.expand, event.feather, event.closeGap, event.opacity, op, event.png)
}
