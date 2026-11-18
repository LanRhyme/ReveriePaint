/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/** Flattened normalized polyline: [x0, y0, x1, y1, ...], radius 1, center (0, 0). */
data class FisheyeCurveMesh(
    val horizontalAngles: DoubleArray,
    val horizontal: List<FloatArray>,
    val verticalAngles: DoubleArray,
    val vertical: List<FloatArray>,
)

/**
 * Pure direction-vector generation and equidistant fisheye projection.
 *
 * The curves live on the front unit hemisphere; there is no room volume, near plane, or far plane.
 */
object FisheyeGuideGeometry {
    private val HALF_PI = PI / 2.0
    private val MAX_ANGLE_EPSILON = PI / 96.0
    private const val MAX_SUBDIVISION_DEPTH = 9

    fun normalizeCurveCount(count: Int): Int {
        val clamped = count.coerceIn(5, 25)
        return if (clamped % 2 == 0) (clamped + 1).coerceAtMost(25) else clamped
    }

    /** Equal-angle a values, symmetric around and explicitly including the central diameter. */
    fun curveAngles(count: Int): DoubleArray {
        val n = normalizeCurveCount(count)
        val maxA = HALF_PI - MAX_ANGLE_EPSILON
        return DoubleArray(n) { i ->
            if (i == n / 2) 0.0 else -maxA + (2.0 * maxA * i / (n - 1))
        }
    }

    /** 吸附候选与显示一致: a=0 的两条直径同样是合法曲线。 */
    fun snappingCurveAngles(count: Int): DoubleArray = curveAngles(count)

    /** 连续横弧参数: 过当前点的横弧族参数, 不量化到可见参考线。 */
    fun horizontalParameter(x: Double, y: Double, z: Double): Double = atan2(y, z)

    /** 连续纵弧参数: 过当前点的纵弧族参数, 不量化到可见参考线。 */
    fun verticalParameter(x: Double, y: Double, z: Double): Double = atan2(x, z)

    /**
     * 合并后的弧线吸附在起笔后按初始运笔方向选择横弧或纵弧。
     * 两条候选曲线都严格经过起笔点, 因此选择本身不会造成落点跳变。
     */
    fun chooseCurveFamily(
        x: Double,
        y: Double,
        z: Double,
        strokeDx: Double,
        strokeDy: Double,
        centerX: Double,
        centerY: Double,
        radius: Double,
    ): Int {
        val strokeLen = hypot(strokeDx, strokeDy)
        if (strokeLen <= 1e-9) return 0
        val aH = horizontalParameter(x, y, z)
        val aV = verticalParameter(x, y, z)
        val eps = 1e-3
        val limit = HALF_PI - 1e-4

        fun tangentScore(
            sample: (Double) -> Triple<Double, Double, Double>,
            base: Double,
        ): Double {
            val t = base.coerceIn(-limit, limit)
            val a = sample(t - eps)
            val b = sample(t + eps)
            val p0 = documentFromDirection(a.first, a.second, a.third, centerX, centerY, radius)
            val p1 = documentFromDirection(b.first, b.second, b.third, centerX, centerY, radius)
            val tx = (p1.x - p0.x).toDouble()
            val ty = (p1.y - p0.y).toDouble()
            val len = hypot(tx, ty)
            if (len <= 1e-9) return 0.0
            return kotlin.math.abs((tx / len) * (strokeDx / strokeLen) + (ty / len) * (strokeDy / strokeLen))
        }

        val hScore = tangentScore({ t -> horizontalDirection(aH, t) }, kotlin.math.asin(x.coerceIn(-1.0, 1.0)))
        val vScore = tangentScore({ t -> verticalDirection(aV, t) }, kotlin.math.asin(y.coerceIn(-1.0, 1.0)))
        return if (hScore >= vScore) 0 else 1
    }

    /** Orthographic equal-angle projection of one front-facing unit direction onto a unit disk. */
    fun projectDirection(x: Double, y: Double, z: Double): Point2D {
        if (!x.isFinite() || !y.isFinite() || !z.isFinite()) return Point2D(0f, 0f)
        val s = hypot(x, y)
        if (s <= 1e-12) return Point2D(0f, 0f)
        // The generated directions have z >= 0. Clamp tiny endpoint round-off; z == 0 is valid.
        val frontZ = z.coerceAtLeast(0.0)
        val theta = atan2(s, frontZ).coerceIn(0.0, HALF_PI)
        val r = theta / HALF_PI
        return Point2D((r * x / s).toFloat(), (r * y / s).toFloat())
    }

    fun horizontalDirection(a: Double, t: Double): Triple<Double, Double, Double> {
        val st = sin(t)
        val ct = cos(t)
        return Triple(st, sin(a) * ct, cos(a) * ct)
    }

    fun verticalDirection(a: Double, t: Double): Triple<Double, Double, Double> {
        val st = sin(t)
        val ct = cos(t)
        return Triple(sin(a) * ct, st, cos(a) * ct)
    }

    /** Inverse of the equidistant projection: document point -> front-hemisphere direction. */
    fun directionFromDocument(
        px: Double,
        py: Double,
        centerX: Double,
        centerY: Double,
        radius: Double,
    ): Triple<Double, Double, Double> {
        if (radius <= 1e-6) return Triple(0.0, 0.0, 1.0)
        val dx = px - centerX
        val dy = py - centerY
        val diskR = hypot(dx, dy) / radius
        val theta = diskR.coerceIn(0.0, 1.0) * HALF_PI
        if (diskR <= 1e-9) return Triple(0.0, 0.0, 1.0)
        val phi = atan2(dy, dx)
        val st = sin(theta)
        return Triple(st * cos(phi), st * sin(phi), cos(theta))
    }

    /** Snap onto the horizontal-family curve with parameter [a], where t = asin(x). */
    fun snapToHorizontalCurve(a: Double, x: Double, y: Double, z: Double): Triple<Double, Double, Double> {
        val t = kotlin.math.asin(x.coerceIn(-1.0, 1.0))
        return horizontalDirection(a, t)
    }

    /** Snap onto the vertical-family curve with parameter [a], where t = asin(y). */
    fun snapToVerticalCurve(a: Double, x: Double, y: Double, z: Double): Triple<Double, Double, Double> {
        val t = kotlin.math.asin(y.coerceIn(-1.0, 1.0))
        return verticalDirection(a, t)
    }

    /** Azimuth of a direction around the optical axis; used by the radial snap. */
    fun azimuthOf(x: Double, y: Double): Double = atan2(y, x)

    /**
     * 中心射线吸附: 固定方位角 phi, 只保留极角 theta。
     * 得到的直线一定穿过真实球心, 同时允许朝圆心或背离圆心绘制。
     */
    fun snapToCenterRay(phi: Double, x: Double, y: Double, z: Double): Triple<Double, Double, Double> {
        val theta = atan2(hypot(x, y), z.coerceAtLeast(0.0)).coerceIn(0.0, HALF_PI)
        val st = sin(theta)
        return Triple(st * cos(phi), st * sin(phi), cos(theta))
    }

    /** Equal-angle projection back to document coordinates. */
    fun documentFromDirection(
        x: Double,
        y: Double,
        z: Double,
        centerX: Double,
        centerY: Double,
        radius: Double,
    ): Point2D {
        val normalized = projectDirection(x, y, z)
        return Point2D(
            (centerX + normalized.x * radius).toFloat(),
            (centerY + normalized.y * radius).toFloat(),
        )
    }

    /** Nearest allowed curve parameter; angles are sorted and lie in (-pi/2, pi/2). */
    fun nearestCurveAngle(target: Double, angles: DoubleArray): Double {
        if (angles.isEmpty()) return 0.0
        var best = angles[0]
        var bestDistance = kotlin.math.abs(target - best)
        for (angle in angles) {
            val distance = kotlin.math.abs(target - angle)
            if (distance < bestDistance) {
                best = angle
                bestDistance = distance
            }
        }
        return best
    }

    /**
     * Generate both curve families. Subdivision is based on projected screen-space midpoint error;
     * the supplied screen radius should be quantized by [FisheyeGuideGeometryCache] during zoom.
     */
    fun build(
        horizontalCount: Int,
        verticalCount: Int,
        screenRadiusPx: Float,
        maxErrorPx: Float = 0.55f,
    ): FisheyeCurveMesh {
        val radiusPx = screenRadiusPx.takeIf { it.isFinite() }?.coerceAtLeast(1f) ?: 1f
        val errorPx = maxErrorPx.takeIf { it.isFinite() }?.coerceIn(0.25f, 2f) ?: 0.55f
        val hAngles = curveAngles(horizontalCount)
        val vAngles = curveAngles(verticalCount)
        val horizontal = hAngles.map { a -> buildCurve(a, radiusPx, errorPx, ::horizontalDirection) }
        val vertical = vAngles.map { a -> buildCurve(a, radiusPx, errorPx, ::verticalDirection) }
        return FisheyeCurveMesh(hAngles, horizontal, vAngles, vertical)
    }

    private fun buildCurve(
        a: Double,
        screenRadiusPx: Float,
        maxErrorPx: Float,
        direction: (Double, Double) -> Triple<Double, Double, Double>,
    ): FloatArray {
        val output = ArrayList<Float>(128)
        val start = -HALF_PI
        val end = HALF_PI

        fun point(t: Double): Point2D {
            val (x, y, z) = direction(a, t)
            return projectDirection(x, y, z)
        }

        fun appendPoint(p: Point2D) {
            output.add(p.x)
            output.add(p.y)
        }

        fun distanceToChordMidpoint(mid: Point2D, p0: Point2D, p1: Point2D): Float {
            val dx = mid.x - (p0.x + p1.x) * 0.5f
            val dy = mid.y - (p0.y + p1.y) * 0.5f
            return hypot(dx, dy) * screenRadiusPx
        }

        fun subdivide(t0: Double, p0: Point2D, t1: Double, p1: Point2D, depth: Int) {
            val tm = (t0 + t1) * 0.5
            val pm = point(tm)
            if (depth < MAX_SUBDIVISION_DEPTH &&
                distanceToChordMidpoint(pm, p0, p1) > maxErrorPx
            ) {
                subdivide(t0, p0, tm, pm, depth + 1)
                subdivide(tm, pm, t1, p1, depth + 1)
            } else {
                appendPoint(p1)
            }
        }

        val p0 = point(start)
        appendPoint(p0)
        subdivide(start, p0, end, point(end), 0)
        return output.toFloatArray()
    }
}

/**
 * Small synchronized LRU cache. Zoom scale is quantized to quarter-octave buckets so continuous
 * pinch gestures reuse geometry and rebuild only when the projected pixel-error budget changes.
 */
object FisheyeGuideGeometryCache {
    private data class Key(val horizontal: Int, val vertical: Int, val radiusOctaves: Int)

    private val cache = object : LinkedHashMap<Key, FisheyeCurveMesh>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, FisheyeCurveMesh>?): Boolean =
            size > 8
    }

    @Synchronized
    fun get(horizontalCount: Int, verticalCount: Int, screenRadiusPx: Float): FisheyeCurveMesh {
        val radius = screenRadiusPx.takeIf { it.isFinite() }?.coerceIn(1f, 8192f) ?: 1f
        val octaveQuarter = (log2(radius) * 4f).roundToInt()
        val key = Key(
            FisheyeGuideGeometry.normalizeCurveCount(horizontalCount),
            FisheyeGuideGeometry.normalizeCurveCount(verticalCount),
            octaveQuarter,
        )
        return cache.getOrPut(key) {
            val bucketRadius = 2.0.pow(octaveQuarter / 4.0).toFloat()
            FisheyeGuideGeometry.build(key.horizontal, key.vertical, bucketRadius)
        }
    }

    @Synchronized
    fun clear() = cache.clear()
}
