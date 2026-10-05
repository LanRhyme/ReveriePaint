/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.reverie.paint.model

import kotlin.math.*

/** Geometry shared by recognition, editing and the native brush path. No screen coordinates. */
object QuickShapeGeometry {
    fun outline(shape: QuickShapeResult): List<Point2D> = when (shape.type) {
        QuickShapeType.CIRCLE, QuickShapeType.ELLIPSE -> {
            val segments = ceil(PI * max(shape.radiusX, shape.radiusY) / 2).toInt().coerceIn(64, 2048)
            (0..segments).map { i ->
                val a = 2 * PI.toFloat() * i / segments
                localToDoc(shape, Point2D(shape.radiusX * cos(a), shape.radiusY * sin(a)))
            }
        }
        QuickShapeType.RECTANGLE -> corners(shape).let { it + it.first() }
        QuickShapeType.TRIANGLE -> shape.points + shape.points.first()
        else -> shape.points
    }

    fun localToDoc(shape: QuickShapeResult, p: Point2D): Point2D {
        val c = cos(shape.rotationRad)
        val s = sin(shape.rotationRad)
        return shape.center + Point2D(p.x * c - p.y * s, p.x * s + p.y * c)
    }

    fun docToLocal(shape: QuickShapeResult, p: Point2D): Point2D {
        val d = p - shape.center
        val c = cos(shape.rotationRad)
        val s = sin(shape.rotationRad)
        return Point2D(d.x * c + d.y * s, -d.x * s + d.y * c)
    }

    fun corners(shape: QuickShapeResult): List<Point2D> = listOf(
        Point2D(-shape.radiusX, -shape.radiusY), Point2D(shape.radiusX, -shape.radiusY),
        Point2D(shape.radiusX, shape.radiusY), Point2D(-shape.radiusX, shape.radiusY),
    ).map { localToDoc(shape, it) }

    /** Last handle rotates a closed shape; line/triangle vertices are independently editable. */
    fun handles(shape: QuickShapeResult): List<Point2D> = when (shape.type) {
        QuickShapeType.LINE, QuickShapeType.TRIANGLE -> shape.points
        else -> listOf(
            localToDoc(shape, Point2D(shape.radiusX, shape.radiusY)),
            localToDoc(shape, Point2D(0f, -shape.radiusY * 1.4f)),
        )
    }

    fun drag(shape: QuickShapeResult, handle: Int, from: Point2D, to: Point2D): QuickShapeResult {
        if (!from.x.isFinite() || !from.y.isFinite() || !to.x.isFinite() || !to.y.isFinite()) return shape
        if (from == to) return shape
        val delta = to - from
        if (!delta.x.isFinite() || !delta.y.isFinite()) return shape
        if (handle < 0) {
            return shape.copy(center = shape.center + delta, points = shape.points.map { it + delta })
        }
        if (shape.type == QuickShapeType.LINE || shape.type == QuickShapeType.TRIANGLE) {
            val points = shape.points.toMutableList()
            if (handle !in points.indices) return shape
            points[handle] = points[handle] + delta
            return shape.copy(points = points, center = points.reduce { a, b -> a + b } / points.size.toFloat())
        }
        if (handle == 1) {
            val start = from - shape.center
            val end = to - shape.center
            if (hypot(start.x, start.y) < 0.001f || hypot(end.x, end.y) < 0.001f) return shape
            val angle = atan2(end.y, end.x) - atan2(start.y, start.x)
            return shape.copy(rotationRad = shape.rotationRad + atan2(sin(angle), cos(angle)))
        }
        if (handle != 0) return shape
        // The touch target is larger than the visible handle. Preserve the initial finger offset.
        val local = Point2D(shape.radiusX, shape.radiusY) + docToLocal(shape, to) - docToLocal(shape, from)
        val rx = abs(local.x).coerceIn(1f, 100000f)
        val ry = abs(local.y).coerceIn(1f, 100000f)
        return if (shape.type == QuickShapeType.CIRCLE) {
            val radius = hypot(local.x, local.y).div(sqrt(2f)).coerceIn(1f, 100000f)
            shape.copy(radiusX = radius, radiusY = radius)
        } else shape.copy(radiusX = rx, radiusY = ry)
    }
}
