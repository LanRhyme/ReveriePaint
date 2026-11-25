/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

/**
 * Pure geometry for the Procreate-style split-circle comparison bubble shown while
 * dragging inside a 2D color picker area. Keeps the bubble above the reticle,
 * within horizontal panel bounds, and bounded near the top without clipping strokes.
 */
internal object ColorCompareBubbleGeometry {

    data class Pose(
        val cx: Float,
        val cy: Float,
        val radius: Float,
        val alpha: Float,
    )

    private const val MIN_RADIUS_SCALE = 0.35f

    fun compute(
        reticleX: Float,
        reticleY: Float,
        areaWidth: Float,
        areaHeight: Float,
        bubbleRadiusPx: Float,
        bubbleOuterRadiusPx: Float,
        liftOffsetPx: Float,
        topOverflowAllowancePx: Float,
        progress: Float,
    ): Pose {
        val t = if (progress.isFinite()) progress.coerceIn(0f, 1f) else 0f
        if (!areaWidth.isFinite() || !areaHeight.isFinite() || !bubbleRadiusPx.isFinite() ||
            areaWidth <= 0f || areaHeight <= 0f || bubbleRadiusPx <= 0f
        ) {
            val safeX = if (reticleX.isFinite()) reticleX else 0f
            val safeY = if (reticleY.isFinite()) reticleY else 0f
            return Pose(safeX, safeY, 0f, 0f)
        }
        val rx = if (reticleX.isFinite()) reticleX else areaWidth * 0.5f
        val ry = if (reticleY.isFinite()) reticleY else areaHeight * 0.5f
        val outerBase = if (bubbleOuterRadiusPx.isFinite()) bubbleOuterRadiusPx else bubbleRadiusPx
        val outerR = maxOf(bubbleRadiusPx, outerBase)
        val lift = if (liftOffsetPx.isFinite()) liftOffsetPx.coerceAtLeast(0f) else 0f
        val topAllowance = if (topOverflowAllowancePx.isFinite()) topOverflowAllowancePx.coerceAtLeast(0f) else 0f

        val minX = outerR
        val maxX = (areaWidth - outerR).coerceAtLeast(minX)
        val targetX = rx.coerceIn(minX, maxX)

        val minY = -topAllowance + outerR
        val maxY = (areaHeight - outerR).coerceAtLeast(minY)
        val targetY = (ry - lift).coerceIn(minY, maxY)

        val cx = rx + (targetX - rx) * t
        val cy = ry + (targetY - ry) * t
        val radius = bubbleRadiusPx * (MIN_RADIUS_SCALE + (1f - MIN_RADIUS_SCALE) * t)
        return Pose(cx = cx, cy = cy, radius = radius, alpha = t)
    }
}
