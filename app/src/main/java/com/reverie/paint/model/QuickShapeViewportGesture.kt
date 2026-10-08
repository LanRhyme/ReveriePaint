/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.reverie.paint.model

import kotlin.math.hypot

/** Incremental viewport pinch; changing either pointer only establishes a new baseline. */
class QuickShapeViewportGesture {
    private var active = false
    private var firstId = 0L
    private var secondId = 0L
    private var previousX = 0f
    private var previousY = 0f
    private var previousDistance = 1f

    var zoom = 1f
        private set
    var panX = 0f
        private set
    var panY = 0f
        private set

    fun reset() { active = false }

    /** Returns false when rebasing or rejecting input; the caller must leave the viewport unchanged. */
    fun update(
        firstPointerId: Long, firstX: Float, firstY: Float,
        secondPointerId: Long, secondX: Float, secondY: Float,
        viewWidth: Int, viewHeight: Int,
        currentZoom: Float, currentPanX: Float, currentPanY: Float,
        locked: Boolean,
    ): Boolean {
        if (firstPointerId == secondPointerId || viewWidth <= 0 || viewHeight <= 0 ||
            !firstX.isFinite() || !firstY.isFinite() || !secondX.isFinite() || !secondY.isFinite() ||
            !currentZoom.isFinite() || currentZoom <= 0f ||
            !currentPanX.isFinite() || !currentPanY.isFinite()) {
            reset()
            return false
        }
        val centerX = (firstX + secondX) * 0.5f
        val centerY = (firstY + secondY) * 0.5f
        val distance = hypot(secondX - firstX, secondY - firstY).coerceAtLeast(1f)
        val samePair = active &&
            ((firstPointerId == firstId && secondPointerId == secondId) ||
                (firstPointerId == secondId && secondPointerId == firstId))
        var changed = false
        if (samePair) {
            // Match the canvas lock: zoom stays fixed while panning remains available.
            val nextZoom = if (locked) currentZoom
                else (currentZoom * (distance / previousDistance)).coerceIn(0.02f, 128f)
            val ratio = nextZoom / currentZoom
            // Uniform scaling in screen space preserves the anchor even on a rotated/flipped canvas.
            val nextPanX = centerX - (previousX - viewWidth * 0.5f - currentPanX) * ratio - viewWidth * 0.5f
            val nextPanY = centerY - (previousY - viewHeight * 0.5f - currentPanY) * ratio - viewHeight * 0.5f
            if (!nextZoom.isFinite() || !nextPanX.isFinite() || !nextPanY.isFinite()) {
                reset()
                return false
            }
            zoom = nextZoom
            panX = nextPanX
            panY = nextPanY
            changed = true
        }
        active = true
        firstId = firstPointerId
        secondId = secondPointerId
        previousX = centerX
        previousY = centerY
        previousDistance = distance
        return changed
    }
}
