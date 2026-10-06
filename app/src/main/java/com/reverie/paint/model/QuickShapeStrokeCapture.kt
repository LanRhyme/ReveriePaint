/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.reverie.paint.model

import kotlin.math.hypot

/** Bounded, allocation-free collection of the effective samples actually sent to the brush engine. */
class QuickShapeStrokeCapture(private val capacity: Int = 8192) {
    private val data = FloatArray(capacity * 6)
    var count = 0
        private set
    var overflowed = false
        private set
    var travelled = 0f
        private set
    private var anchorX = 0f
    private var anchorY = 0f
    var lastMovementMs = 0L
        private set

    fun begin(screenX: Float, screenY: Float, nowMs: Long) {
        count = 0; overflowed = false; travelled = 0f
        anchorX = screenX; anchorY = screenY; lastMovementMs = nowMs
    }

    fun moved(screenX: Float, screenY: Float, nowMs: Long, slopPx: Float): Boolean {
        val distance = hypot(screenX - anchorX, screenY - anchorY)
        if (distance <= slopPx) return false
        travelled += distance
        anchorX = screenX; anchorY = screenY; lastMovementMs = nowMs
        return true
    }

    fun append(x: Float, y: Float, pressure: Float, tiltX: Float, tiltY: Float) {
        if (overflowed) return
        if (count == capacity || !x.isFinite() || !y.isFinite() || !pressure.isFinite()) {
            overflowed = true
            return
        }
        val i = count++ * 6
        data[i] = x; data[i + 1] = y; data[i + 2] = pressure
        data[i + 3] = tiltX; data[i + 4] = tiltY; data[i + 5] = 0f
    }

    fun recognize(recognizeArcs: Boolean = false, relaxed: Boolean = false,
                  recognizeQuadrilaterals: Boolean = false, recognizeCurves: Boolean = false): QuickShapeResult? =
        if (overflowed) null else QuickShapeFitter.fit(
            (0 until count).map { Point2D(data[it * 6], data[it * 6 + 1]) },
            recognizeArcs, relaxed, recognizeQuadrilaterals, recognizeCurves)

    fun snapshot(): FloatArray = data.copyOf(count * 6)
}
