/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

object AssistedDrawingGeometry {
    /** 屏幕空间方向锁定阈值 (dp)：过滤 S-Pen 落笔微抖，跨画布分辨率/缩放一致判定 */
    const val DIRECTION_LOCK_THRESHOLD_DP = 6f

    /** 短划提交门限 (dp)：抬笔时尚未达 6dp 但移动超过此值时仍按锁定方向落笔，否则仅留起点 */
    const val SHORT_STROKE_COMMIT_DP = 2f

    const val ISO_COS_30 = 0.8660254f
    const val ISO_SIN_30 = 0.5f

    private val GRID_ROTATION_SNAP_DEGS = floatArrayOf(0f, 15f, 30f, 45f, 60f, 90f, 120f, 135f, 150f, 180f)

    data class LockedAssistRay(
        val dirX: Float,
        val dirY: Float,
        val forwardSign: Float,
        val vanishingPoint: Point2D? = null,
    )

    fun isDirectionalAssistActive(guide: DrawingGuideConfig): Boolean {
        if (!guide.assistedDrawing) return false
        return when (guide.mode) {
            GuideMode.GRID_2D, GuideMode.ISOMETRIC -> true
            GuideMode.PERSPECTIVE -> !guide.perspective3d
            else -> false
        }
    }

    fun normalizeGridRotationDeg(deg: Float): Float {
        if (!deg.isFinite()) return 0f
        val norm = ((deg % 180f) + 180f) % 180f
        return if (norm >= 179.999f) 0f else norm
    }

    fun snapGridRotationHandleDeg(rawDeg: Float): Float {
        var deg = normalizeGridRotationDeg(rawDeg)
        for (snapDeg in GRID_ROTATION_SNAP_DEGS) {
            if (abs(deg - snapDeg) <= 3f) {
                deg = if (snapDeg >= 180f) 0f else snapDeg
                break
            }
        }
        return deg
    }

    fun grid2dAxes(rotationDeg: Float): Array<Point2D> {
        val rotRad = normalizeGridRotationDeg(rotationDeg) * (PI.toFloat() / 180f)
        val c = cos(rotRad)
        val s = sin(rotRad)
        return arrayOf(
            Point2D(c, s),
            Point2D(-s, c),
        )
    }

    fun isometricAxes(rotationDeg: Float): Array<Point2D> {
        val rotRad = normalizeGridRotationDeg(rotationDeg) * (PI.toFloat() / 180f)
        val c = cos(rotRad)
        val s = sin(rotRad)
        fun rot(bx: Float, by: Float): Point2D = Point2D(bx * c - by * s, bx * s + by * c)
        return arrayOf(
            rot(ISO_COS_30, ISO_SIN_30),
            rot(ISO_COS_30, -ISO_SIN_30),
            rot(0f, 1f),
        )
    }

    fun selectLinearAssistRay(
        guide: DrawingGuideConfig,
        firstPt: Point2D,
        currentPt: Point2D,
        docWidth: Int,
        docHeight: Int,
    ): LockedAssistRay? {
        val dx = currentPt.x - firstPt.x
        val dy = currentPt.y - firstPt.y
        if (hypot(dx, dy) <= 1e-4f) return null

        var minErr = Float.MAX_VALUE
        var bestUx = 1f
        var bestUy = 0f
        var bestVp: Point2D? = null

        fun consider(ux: Float, uy: Float, vp: Point2D? = null) {
            val err = abs(-dx * uy + dy * ux)
            if (err < minErr) {
                minErr = err
                bestUx = ux
                bestUy = uy
                bestVp = vp
            }
        }

        when (guide.mode) {
            GuideMode.GRID_2D -> {
                for (axis in grid2dAxes(guide.gridRotationDeg)) {
                    consider(axis.x, axis.y)
                }
            }
            GuideMode.ISOMETRIC -> {
                for (axis in isometricAxes(guide.gridRotationDeg)) {
                    consider(axis.x, axis.y)
                }
            }
            GuideMode.PERSPECTIVE -> {
                if (guide.perspective3d || guide.perspectiveFisheye) return null
                consider(1f, 0f)
                consider(0f, 1f)
                val vps = if (guide.perspectiveVanishingPoints.isEmpty()) {
                    listOf(Point2D(docWidth * 0.5f, docHeight * 0.35f))
                } else {
                    guide.perspectiveVanishingPoints
                }
                for (vp in vps) {
                    val vx = vp.x - firstPt.x
                    val vy = vp.y - firstPt.y
                    val vLen = hypot(vx, vy)
                    if (vLen > 0.001f) {
                        consider(vx / vLen, vy / vLen, vp)
                    }
                }
            }
            else -> return null
        }

        val dot = dx * bestUx + dy * bestUy
        val forwardSign = if (dot >= 0f) 1f else -1f
        return LockedAssistRay(bestUx, bestUy, forwardSign, bestVp)
    }

    fun projectPointOntoRay(origin: Point2D, ray: LockedAssistRay, pt: Point2D): Point2D {
        val t = (pt.x - origin.x) * ray.dirX + (pt.y - origin.y) * ray.dirY
        return Point2D(origin.x + ray.dirX * t, origin.y + ray.dirY * t)
    }

    fun projectPreLockForwardT(
        origin: Point2D,
        ray: LockedAssistRay,
        pt: Point2D,
        minForwardT: Float,
        maxForwardT: Float,
    ): Float {
        val rawForwardT = ((pt.x - origin.x) * ray.dirX + (pt.y - origin.y) * ray.dirY) * ray.forwardSign
        return rawForwardT.coerceIn(minForwardT, maxForwardT.coerceAtLeast(minForwardT))
    }
}
