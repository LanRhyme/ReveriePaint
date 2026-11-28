/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.reverie.paint.model

/**
 * Composition guides shown only while an animation project is active.
 * Colors are high-saturation ARGB; renderers apply 50% alpha.
 */
enum class CompositionGuide(
    val id: String,
    val colorArgb: Int,
) {
    NINE_GRID("nine_grid", 0xFF00E5FF.toInt()),
    CENTER("center", 0xFFFF2D95.toInt()),
    GOLDEN_RATIO("golden_ratio", 0xFFFFD600.toInt()),
    DIAGONALS("diagonals", 0xFF00E676.toInt()),
    SAFE_FRAME("safe_frame", 0xFFFF9100.toInt()),
    HORIZON("horizon", 0xFF7C4DFF.toInt()),
    ;

    companion object {
        fun fromId(id: String): CompositionGuide? = entries.firstOrNull { it.id == id }
    }
}

/** Persistence codec for [CompositionGuide] selections. Unknown ids are ignored. */
internal object CompositionGuidesCodec {
    fun encode(enabled: Set<CompositionGuide>): String =
        CompositionGuide.entries.filter { it in enabled }.joinToString(",") { it.id }

    fun decode(raw: String?): Set<CompositionGuide> {
        if (raw.isNullOrBlank()) return emptySet()
        return raw.split(',').mapNotNull { CompositionGuide.fromId(it.trim()) }.toSet()
    }
}

/**
 * Pure geometry in bitmap space with the origin at the top-left.
 * [forEachLine] avoids allocating a line list every frame.
 */
internal object CompositionGuideGeometry {
    const val GOLDEN_LOW = 0.382f
    const val GOLDEN_HIGH = 0.618f
    const val SAFE_OUTER_INSET = 0.05f
    const val SAFE_INNER_INSET = 0.10f

    inline fun forEachLine(
        enabled: Set<CompositionGuide>,
        horizon: Float,
        width: Float,
        height: Float,
        action: (guide: CompositionGuide, x1: Float, y1: Float, x2: Float, y2: Float, dashed: Boolean) -> Unit,
    ) {
        if (width <= 0f || height <= 0f) return

        if (CompositionGuide.NINE_GRID in enabled) {
            val thirdW = width / 3f
            val thirdH = height / 3f
            action(CompositionGuide.NINE_GRID, thirdW, 0f, thirdW, height, false)
            action(CompositionGuide.NINE_GRID, thirdW * 2f, 0f, thirdW * 2f, height, false)
            action(CompositionGuide.NINE_GRID, 0f, thirdH, width, thirdH, false)
            action(CompositionGuide.NINE_GRID, 0f, thirdH * 2f, width, thirdH * 2f, false)
        }
        if (CompositionGuide.CENTER in enabled) {
            action(CompositionGuide.CENTER, width / 2f, 0f, width / 2f, height, false)
            action(CompositionGuide.CENTER, 0f, height / 2f, width, height / 2f, false)
        }
        if (CompositionGuide.GOLDEN_RATIO in enabled) {
            action(CompositionGuide.GOLDEN_RATIO, width * GOLDEN_LOW, 0f, width * GOLDEN_LOW, height, false)
            action(CompositionGuide.GOLDEN_RATIO, width * GOLDEN_HIGH, 0f, width * GOLDEN_HIGH, height, false)
            action(CompositionGuide.GOLDEN_RATIO, 0f, height * GOLDEN_LOW, width, height * GOLDEN_LOW, false)
            action(CompositionGuide.GOLDEN_RATIO, 0f, height * GOLDEN_HIGH, width, height * GOLDEN_HIGH, false)
        }
        if (CompositionGuide.DIAGONALS in enabled) {
            action(CompositionGuide.DIAGONALS, 0f, 0f, width, height, false)
            action(CompositionGuide.DIAGONALS, width, 0f, 0f, height, false)
        }
        if (CompositionGuide.SAFE_FRAME in enabled) {
            val outerX = width * SAFE_OUTER_INSET
            val outerY = height * SAFE_OUTER_INSET
            val innerX = width * SAFE_INNER_INSET
            val innerY = height * SAFE_INNER_INSET
            // Action-safe frame.
            action(CompositionGuide.SAFE_FRAME, outerX, outerY, width - outerX, outerY, false)
            action(CompositionGuide.SAFE_FRAME, width - outerX, outerY, width - outerX, height - outerY, false)
            action(CompositionGuide.SAFE_FRAME, width - outerX, height - outerY, outerX, height - outerY, false)
            action(CompositionGuide.SAFE_FRAME, outerX, height - outerY, outerX, outerY, false)
            // Title-safe frame.
            action(CompositionGuide.SAFE_FRAME, innerX, innerY, width - innerX, innerY, true)
            action(CompositionGuide.SAFE_FRAME, width - innerX, innerY, width - innerX, height - innerY, true)
            action(CompositionGuide.SAFE_FRAME, width - innerX, height - innerY, innerX, height - innerY, true)
            action(CompositionGuide.SAFE_FRAME, innerX, height - innerY, innerX, innerY, true)
        }
        if (CompositionGuide.HORIZON in enabled) {
            val y = height * horizon.coerceIn(0f, 1f)
            action(CompositionGuide.HORIZON, 0f, y, width, y, true)
        }
    }
}
