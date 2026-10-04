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
        if (!to.x.isFinite() || !to.y.isFinite()) return shape
        if (handle < 0) {
            val d = to - from
            return shape.copy(center = shape.center + d, points = shape.points.map { it + d })
        }
        if (shape.type == QuickShapeType.LINE || shape.type == QuickShapeType.TRIANGLE) {
            val points = shape.points.toMutableList()
            if (handle !in points.indices) return shape
            points[handle] = to
            return shape.copy(points = points, center = points.reduce { a, b -> a + b } / points.size.toFloat())
        }
        if (handle == 1) {
            return shape.copy(rotationRad = atan2(to.y - shape.center.y, to.x - shape.center.x) + PI.toFloat() / 2)
        }
        val local = docToLocal(shape, to)
        val rx = abs(local.x).coerceIn(1f, 100000f)
        val ry = abs(local.y).coerceIn(1f, 100000f)
        return if (shape.type == QuickShapeType.CIRCLE) {
            val radius = hypot(local.x, local.y).div(sqrt(2f)).coerceIn(1f, 100000f)
            shape.copy(radiusX = radius, radiusY = radius)
        } else shape.copy(radiusX = rx, radiusY = ry)
    }
}
