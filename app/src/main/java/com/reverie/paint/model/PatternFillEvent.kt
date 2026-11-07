/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.reverie.paint.model

/** Self-contained pattern operation. */
data class PatternFillEvent(
    val layer: Int,
    val wholeLayer: Boolean,
    val x: Int = 0,
    val y: Int = 0,
    val tolerance: Int = 16,
    val sampleMerged: Boolean = true,
    val expand: Int = 0,
    val feather: Int = 0,
    val closeGap: Int = 4,
    val opacity: Double = 1.0,
    val compositeOp: String = "normal",
    val png: ByteArray,
) {
    init {
        require(layer >= 0 && opacity.isFinite() && opacity in 0.0..1.0)
        require(png.isNotEmpty() && png.size <= MAX_PATTERN_BYTES)
        require(compositeOp.length in 1..64)
    }

    companion object {
        const val MAX_PATTERN_BYTES = 16 * 1024 * 1024
        const val MAX_PATTERN_DIMENSION = 2048
    }
}
