// SPDX-License-Identifier: GPL-3.0-or-later
package com.reverie.paint.model

import kotlin.math.ceil

/** Public brush diameter, compact influence and travel/time-based strength. */
object LiquifyProfessional {
    const val PUSH_LEFT = 5
    const val PUSH_RIGHT = 6
    fun radius(size: Float): Float = size.coerceAtLeast(8f) * .5f
    fun fieldStep(size: Float): Int = LiquifyFieldResolution.step(0, 0, size / 4f)

    fun supportsFullField(width: Int, height: Int, size: Float): Boolean =
        LiquifyFieldResolution.step(width, height, size / 4f) == fieldStep(size)
    fun substeps(distance: Float, size: Float): Int =
        ceil(distance / (size.coerceAtLeast(8f) * .04f).coerceAtLeast(.2f) - .00001f)
            .toInt().coerceIn(1, 65536)
    fun gain(mode: Int, distance: Float, size: Float, strength: Float): Float {
        val s = if (strength.isFinite()) strength.coerceIn(0f, 1f) else 0f
        if (mode !in 1..4) return s
        return s * if (distance == 0f) {
            if (mode <= 2) 1.5f else 2f
        } else {
            distance / size.coerceAtLeast(8f) * if (mode <= 2) .8f else 1.2f
        }
    }
}
