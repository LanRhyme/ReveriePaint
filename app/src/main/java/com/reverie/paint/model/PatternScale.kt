/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.reverie.paint.model

import kotlin.math.roundToInt

/** Dimensions refer to the imported library image, never to a previous resized result. */
object PatternScale {
    const val MIN_PERCENT = 10f

    fun maxPercent(width: Int, height: Int): Float {
        require(width in 1..2048 && height in 1..2048)
        return minOf(400f, 2048f * 100 / maxOf(width, height))
    }

    fun size(width: Int, height: Int, percent: Float): Pair<Int, Int> {
        require(percent.isFinite())
        val scale = percent.coerceIn(MIN_PERCENT, maxPercent(width, height)) / 100f
        return (width * scale).roundToInt().coerceIn(1, 2048) to
            (height * scale).roundToInt().coerceIn(1, 2048)
    }
}
