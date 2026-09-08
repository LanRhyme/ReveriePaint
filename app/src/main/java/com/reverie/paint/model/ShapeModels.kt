/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import kotlin.math.*

/**
 * 形状图元类型
 */
enum class ShapeType(val id: String, val label: String) {
    LINE("line", "直线"),
    RECT("rect", "矩形"),
    ROUNDED_RECT("rounded_rect", "圆角矩形"),
    ELLIPSE("ellipse", "椭圆"),
    REGULAR_POLYGON("regular_polygon", "正多边形"),
    STAR("star", "星形"),
    POLYLINE("polyline", "折线"),
    POLYGON("polygon", "多边形"),
    BEZIER("bezier", "贝塞尔曲线"),
}

/**
 * 形状样式填充模式
 */
enum class ShapeFillMode(val id: Int, val label: String) {
    STROKE(0, "仅描边"),
    FILL(1, "仅填充"),
    STROKE_AND_FILL(2, "描边与填充"),
}

/**
 * 曲线与多段线控制节点
 */
data class ShapeNode(
    val pos: Point2D,
    val cpIn: Point2D = pos,
    val cpOut: Point2D = pos,
)

/**
 * 交互手柄类型标识
 */
object ShapeHandleId {
    const val NONE = -1
    const val CORNER_TL = -4
    const val CORNER_TR = -5
    const val CORNER_BR = -6
    const val CORNER_BL = -7
    const val ROTATE = -8
    const val CORNER_RADIUS = -9
    const val CENTER = -10
    const val LINE_P1 = -11
    const val LINE_P2 = -12
    const val STAR_INNER = -13
    const val STAR_OUTER = -14
    const val POLYGON_VERTEX = -15
    const val TRANSLATE_BODY = -99

    const val NODE_ANCHOR_BASE = 10000
    const val NODE_CP_IN_BASE = 20000
    const val NODE_CP_OUT_BASE = 30000
}

/**
 * 形状几何计算与手柄定位辅助工具
 */
object ShapeGeometry {

    /**
     * 等比尺寸约束 (1:1 正方形或正圆外接框)
     */
    fun constrainAspect(p1: Point2D, p2: Point2D): Point2D {
        val dx = p2.x - p1.x
        val dy = p2.y - p1.y
        val maxDim = maxOf(abs(dx), abs(dy))
        val signX = if (dx >= 0f) 1f else -1f
        val signY = if (dy >= 0f) 1f else -1f
        return Point2D(p1.x + maxDim * signX, p1.y + maxDim * signY)
    }

    /**
     * 计算两点确定的正规化外接矩形 (左上, 右下)
     */
    fun normalizeRect(p1: Point2D, p2: Point2D): Pair<Point2D, Point2D> {
        val left = minOf(p1.x, p2.x)
        val top = minOf(p1.y, p2.y)
        val right = maxOf(p1.x, p2.x)
        val bottom = maxOf(p1.y, p2.y)
        return Point2D(left, top) to Point2D(right, bottom)
    }

    /**
     * 生成正多边形顶点序列 (从顶部顶点展开)
     */
    fun generateRegularPolygon(
        center: Point2D,
        radius: Float,
        sides: Int,
        rotationDeg: Float = 0f,
    ): List<Point2D> {
        val clampedSides = sides.coerceIn(3, 32)
        val angleStep = (2.0 * PI / clampedSides).toFloat()
        val baseAngle = (-PI / 2.0).toFloat() + Math.toRadians(rotationDeg.toDouble()).toFloat()
        return (0 until clampedSides).map { i ->
            val angle = baseAngle + i * angleStep
            Point2D(
                center.x + radius * cos(angle),
                center.y + radius * sin(angle)
            )
        }
    }

    /**
     * 生成星形顶点序列 (内外径交替)
     */
    fun generateStar(
        center: Point2D,
        outerRadius: Float,
        innerRadius: Float,
        points: Int,
        rotationDeg: Float = 0f,
    ): List<Point2D> {
        val clampedPoints = points.coerceIn(3, 24)
        val totalVertices = clampedPoints * 2
        val angleStep = (PI / clampedPoints).toFloat()
        val baseAngle = (-PI / 2.0).toFloat() + Math.toRadians(rotationDeg.toDouble()).toFloat()
        return (0 until totalVertices).map { i ->
            val r = if (i % 2 == 0) outerRadius else innerRadius
            val angle = baseAngle + i * angleStep
            Point2D(
                center.x + r * cos(angle),
                center.y + r * sin(angle)
            )
        }
    }

    /**
     * 生成圆角矩形采样顶点序列
     */
    fun generateRoundedRectVertices(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        radius: Float,
        cornerSamples: Int = 6,
    ): List<Point2D> {
        val w = maxOf(0f, right - left)
        val h = maxOf(0f, bottom - top)
        val maxR = minOf(w, h) / 2f
        val r = radius.coerceIn(0f, maxR)
        if (r <= 0.5f) {
            return listOf(
                Point2D(left, top),
                Point2D(right, top),
                Point2D(right, bottom),
                Point2D(left, bottom),
            )
        }

        val result = ArrayList<Point2D>(cornerSamples * 4)
        val corners = listOf(
            Triple(Point2D(right - r, top + r), -PI.toFloat() / 2f, 0f),
            Triple(Point2D(right - r, bottom - r), 0f, PI.toFloat() / 2f),
            Triple(Point2D(left + r, bottom - r), PI.toFloat() / 2f, PI.toFloat()),
            Triple(Point2D(left + r, top + r), PI.toFloat(), PI.toFloat() * 1.5f),
        )

        for ((c, startA, endA) in corners) {
            val step = (endA - startA) / cornerSamples.toFloat()
            for (s in 0..cornerSamples) {
                val a = startA + s * step
                result.add(Point2D(c.x + r * cos(a), c.y + r * sin(a)))
            }
        }
        return result
    }

    /**
     * 围绕原点旋转点
     */
    fun rotatePoint(point: Point2D, center: Point2D, degrees: Float): Point2D {
        if (abs(degrees) < 0.001f) return point
        val rad = Math.toRadians(degrees.toDouble()).toFloat()
        val cosA = cos(rad)
        val sinA = sin(rad)
        val dx = point.x - center.x
        val dy = point.y - center.y
        return Point2D(
            center.x + dx * cosA - dy * sinA,
            center.y + dx * sinA + dy * cosA
        )
    }

    /**
     * 判定点是否位于多边形内部 (Ray-casting 射线交叉算法)
     */
    fun isPointInPolygon(point: Point2D, polygon: List<Point2D>): Boolean {
        if (polygon.size < 3) return false
        var inside = false
        var j = polygon.size - 1
        for (i in polygon.indices) {
            val pi = polygon[i]
            val pj = polygon[j]
            if ((pi.y > point.y) != (pj.y > point.y) &&
                point.x < (pj.x - pi.x) * (point.y - pi.y) / (pj.y - pi.y) + pi.x
            ) {
                inside = !inside
            }
            j = i
        }
        return inside
    }

    /**
     * 点到线段的最短距离
     */
    fun distanceToSegment(p: Point2D, a: Point2D, b: Point2D): Float {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val lenSq = dx * dx + dy * dy
        if (lenSq < 1e-6f) return hypot(p.x - a.x, p.y - a.y)
        val t = (((p.x - a.x) * dx + (p.y - a.y) * dy) / lenSq).coerceIn(0f, 1f)
        val projX = a.x + t * dx
        val projY = a.y + t * dy
        return hypot(p.x - projX, p.y - projY)
    }
}
