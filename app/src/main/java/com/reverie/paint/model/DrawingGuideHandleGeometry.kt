/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * 辅助线/对称控制柄的唯一几何真身 (纯 Kotlin 逻辑)。
 * 覆盖层绘制 (`CanvasGuidesOverlay`) 与触摸命中 (`CanvasTouchView`) 必须共享本对象的坐标与命中半径,
 * 避免显示缓冲缩放 (`bmpWidth != docWidth`) 时视觉柄与命中区漂移。
 */
object DrawingGuideHandleGeometry {
    /** 对称中心与旋转柄命中半径 (视觉实心 6~7dp, 光晕 12~13dp; 原 36dp 过宽导致误触)。 */
    const val SYMMETRY_HANDLE_HIT_RADIUS_DP = 20f

    /** 2D 透视灭点命中半径 (视觉实心 7dp, 光晕 12dp; 原 48dp 过宽导致误触)。 */
    const val PERSPECTIVE_VP_HIT_RADIUS_DP = 22f

    /** 3D 透视原点轨道柄命中半径 (原无距离判定导致全屏拦截)。 */
    const val PERSPECTIVE_3D_ORBIT_HIT_RADIUS_DP = 20f

    /** 对称轴旋转归零判定容差 (度)。 */
    const val SYMMETRY_AXIS_LOCK_TOLERANCE_DEG = 0.01f

    /** 3D 透视参考相机视距 (与 CanvasGuidesOverlay 保持一致)。 */
    const val PERSPECTIVE_3D_CAMERA_DISTANCE = 9.0f

    fun symmetryCenterDocPos(docWidth: Int, docHeight: Int, guide: DrawingGuideConfig): Point2D {
        return Point2D(
            x = docWidth * guide.symmetryCenterX,
            y = docHeight * guide.symmetryCenterY,
        )
    }

    fun symmetryRotationHandleDocPos(docWidth: Int, docHeight: Int, guide: DrawingGuideConfig): Point2D {
        val center = symmetryCenterDocPos(docWidth, docHeight, guide)
        val rotRad = (guide.symmetryRotationDeg % 360f) * (PI.toFloat() / 180f)
        val rotHandleDist = minOf(docWidth, docHeight) * 0.35f
        val rx = -sin(rotRad) * rotHandleDist
        val ry = cos(rotRad) * rotHandleDist
        return Point2D(center.x + rx, center.y + ry)
    }

    /**
     * 3D 透视原点轨道柄在文档坐标系下的位置。
     * 通过 [Perspective3DMath.project] 投影世界原点 (0,0,0), 若投影失败或越界则回退到画布中心。
     */
    fun perspective3dOrbitHandleDocPos(docWidth: Int, docHeight: Int, guide: DrawingGuideConfig): Point2D {
        val w = docWidth.coerceAtLeast(1).toFloat()
        val h = docHeight.coerceAtLeast(1).toFloat()
        val halfW = w * 0.5f
        val halfH = h * 0.5f
        val projected = Perspective3DMath.project(
            x = 0f,
            y = 0f,
            z = 0f,
            yawDeg = guide.perspectiveYawDeg,
            pitchDeg = guide.perspectivePitchDeg,
            rollDeg = guide.perspectiveRollDeg,
            fovDeg = guide.perspectiveFovDeg,
            distance = PERSPECTIVE_3D_CAMERA_DISTANCE,
            canvasW = w,
            canvasH = h,
        )
        if (projected != null &&
            projected.x.isFinite() &&
            projected.y.isFinite() &&
            abs(projected.x) <= halfW &&
            abs(projected.y) <= halfH
        ) {
            return Point2D(projected.x + halfW, projected.y + halfH)
        }
        return Point2D(halfW, halfH)
    }

    fun isWithinHitRadius(
        touchX: Float,
        touchY: Float,
        handleX: Float,
        handleY: Float,
        radiusDp: Float,
        density: Float,
    ): Boolean {
        val radiusPx = radiusDp * density.coerceAtLeast(0.5f)
        return hypot(touchX - handleX, touchY - handleY) < radiusPx
    }

    fun isAxisRotationZero(rotationDeg: Float): Boolean {
        if (!rotationDeg.isFinite()) return true
        val norm = ((rotationDeg % 180f) + 180f) % 180f
        return norm < SYMMETRY_AXIS_LOCK_TOLERANCE_DEG ||
            (180f - norm) < SYMMETRY_AXIS_LOCK_TOLERANCE_DEG
    }
}
