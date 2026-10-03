// SPDX-License-Identifier: GPL-3.0-or-later
package com.reverie.paint.model

/** Same node spacing for GLES, native materialization and recorded CPU replay. */
object LiquifyFieldResolution {
    const val MAX_PIXELS = 2_000_000L

    fun step(width: Int, height: Int, brushSize: Float): Int {
        var step = 1
        val size = if (brushSize.isFinite()) brushSize.coerceAtLeast(8f) else 8f
        while (step < 16 && step * 2 <= size / 16f) step *= 2
        if (width <= 0 || height <= 0) return step
        while (step < 16 &&
            ((width.toLong() + step - 1) / step) * ((height.toLong() + step - 1) / step) > MAX_PIXELS) {
            step *= 2
        }
        return step
    }
}
