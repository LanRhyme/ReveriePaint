/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.round
import kotlin.math.sin

/**
 * 变换微调按钮长按连发与渐进加速策略。
 */
object NudgeAcceleration {
    const val INITIAL_REPEAT_DELAY_MS = 280L
    const val REPEAT_INTERVAL_MS = 50L

    fun stepPxForHoldMs(heldMs: Long): Float = when {
        heldMs < 300L -> 1f
        heldMs < 900L -> 2f
        heldMs < 1700L -> 5f
        else -> 10f
    }
}

data class TransformSnapResult(
    val tx: Float,
    val ty: Float,
    val snappedAxisAngleDeg: Float? = null,
    val snappedCenterX: Boolean = false,
    val snappedCenterY: Boolean = false,
    val snappedToGrid: Boolean = false,
)

/**
 * 变换工具吸附计算：支持 2D 网格对齐、0°/45°/90°/135° 轴向锁定与画布中线磁吸。
 */
object TransformSnap {
    fun snapTranslation(
        startTx: Float,
        startTy: Float,
        rawDx: Float,
        rawDy: Float,
        boundsCenterX: Float,
        boundsCenterY: Float,
        docWidth: Int,
        docHeight: Int,
        snapThresholdDoc: Float,
        gridGuide: DrawingGuideConfig? = null,
    ): TransformSnapResult {
        val halfW = docWidth / 2f
        val halfH = docHeight / 2f

        if (gridGuide != null && gridGuide.mode == GuideMode.GRID_2D && gridGuide.gridSize >= 4f) {
            val rawCx = boundsCenterX + startTx + rawDx
            val rawCy = boundsCenterY + startTy + rawDy
            val ox = gridGuide.gridOffsetX.coerceIn(0f, 1f) * docWidth
            val oy = gridGuide.gridOffsetY.coerceIn(0f, 1f) * docHeight
            val rad = Math.toRadians(gridGuide.gridRotationDeg.toDouble())
            val cosA = cos(rad)
            val sinA = sin(rad)
            val rx = (rawCx - ox).toDouble()
            val ry = (rawCy - oy).toDouble()
            val u = rx * cosA + ry * sinA
            val v = -rx * sinA + ry * cosA
            val step = gridGuide.gridSize.toDouble()
            val su = round(u / step) * step
            val sv = round(v / step) * step
            val scx = (ox + su * cosA - sv * sinA).toFloat()
            val scy = (oy + su * sinA + sv * cosA).toFloat()
            return TransformSnapResult(
                tx = scx - boundsCenterX,
                ty = scy - boundsCenterY,
                snappedCenterX = abs(scx - halfW) < 0.5f,
                snappedCenterY = abs(scy - halfH) < 0.5f,
                snappedToGrid = true,
            )
        }

        if (hypot(rawDx, rawDy) < 1e-3f) {
            return TransformSnapResult(tx = startTx, ty = startTy)
        }

        val rawDeg = Math.toDegrees(atan2(rawDy.toDouble(), rawDx.toDouble()))
        val snappedDeg = round(rawDeg / 45.0) * 45.0
        val snappedRad = Math.toRadians(snappedDeg)
        val ux = cos(snappedRad).toFloat()
        val uy = sin(snappedRad).toFloat()
        val proj = rawDx * ux + rawDy * uy
        val dx = proj * ux
        val dy = proj * uy

        val axisRaw = (((snappedDeg % 180.0) + 180.0) % 180.0).toFloat()
        val axisDeg = if (axisRaw < 1e-3f || abs(axisRaw - 180f) < 1e-3f) 0f else axisRaw

        val thr = snapThresholdDoc.coerceAtLeast(0f)
        var finalTx = startTx + dx
        var finalTy = startTy + dy
        var hitCx = false
        var hitCy = false

        when {
            abs(axisDeg - 0f) < 0.5f -> {
                if (abs((boundsCenterX + finalTx) - halfW) <= thr) {
                    finalTx = halfW - boundsCenterX
                    hitCx = true
                }
            }
            abs(axisDeg - 90f) < 0.5f -> {
                if (abs((boundsCenterY + finalTy) - halfH) <= thr) {
                    finalTy = halfH - boundsCenterY
                    hitCy = true
                }
            }
            abs(axisDeg - 45f) < 0.5f || abs(axisDeg - 135f) < 0.5f -> {
                if (abs((boundsCenterX + finalTx) - halfW) <= thr &&
                    abs((boundsCenterY + finalTy) - halfH) <= thr
                ) {
                    finalTx = halfW - boundsCenterX
                    finalTy = halfH - boundsCenterY
                    hitCx = true
                    hitCy = true
                }
            }
        }

        return TransformSnapResult(
            tx = finalTx,
            ty = finalTy,
            snappedAxisAngleDeg = axisDeg,
            snappedCenterX = hitCx,
            snappedCenterY = hitCy,
            snappedToGrid = false,
        )
    }

    fun snapRotationDeg(rawRotationDeg: Float, stepDeg: Float = 15f): Float {
        if (stepDeg <= 0f) return rawRotationDeg
        var snapped = (round((rawRotationDeg / stepDeg).toDouble()) * stepDeg).toFloat() % 360f
        if (snapped > 180f) {
            snapped -= 360f
        } else if (snapped <= -180f) {
            snapped += 360f
        }
        return if (snapped == 0f) 0f else snapped
    }
}
