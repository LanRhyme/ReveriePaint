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
        QuickShapeType.ARC -> {
            val segments = ceil(abs(shape.arcSweepRad) * shape.radiusX / 4).toInt().coerceIn(16, 2048)
            (0..segments).map { i ->
                val a = shape.rotationRad + shape.arcSweepRad * i / segments
                shape.center + Point2D(shape.radiusX * cos(a), shape.radiusX * sin(a))
            }
        }
        QuickShapeType.CONTOUR, QuickShapeType.QUADRILATERAL -> QuickShapeBox.outline(shape)
        QuickShapeType.CURVE -> QuickShapeCurve.outline(shape)
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
    fun rotationHandle(shape: QuickShapeResult, boxHandles: Boolean = false): Int = when (shape.type) {
        QuickShapeType.RECTANGLE -> if (boxHandles) 8 else 1
        QuickShapeType.CONTOUR, QuickShapeType.QUADRILATERAL -> if (boxHandles) 8 else -1
        QuickShapeType.CIRCLE, QuickShapeType.ELLIPSE -> 1
        else -> -1
    }

    fun handles(shape: QuickShapeResult, boxHandles: Boolean = false): List<Point2D> = when (shape.type) {
        QuickShapeType.CONTOUR, QuickShapeType.QUADRILATERAL -> if (boxHandles) QuickShapeBox.handles(shape)
            else shape.points.filterIndexed { i, _ -> i % 2 == 0 }
        QuickShapeType.RECTANGLE -> if (boxHandles) QuickShapeBox.handles(shape) else listOf(
            localToDoc(shape, Point2D(shape.radiusX, shape.radiusY)),
            localToDoc(shape, Point2D(0f, -shape.radiusY * 1.4f)),
        )
        QuickShapeType.LINE, QuickShapeType.TRIANGLE, QuickShapeType.ARC, QuickShapeType.CURVE -> shape.points
        else -> listOf(
            localToDoc(shape, Point2D(shape.radiusX, shape.radiusY)),
            localToDoc(shape, Point2D(0f, -shape.radiusY * 1.4f)),
        )
    }

    fun setContourCurved(shape: QuickShapeResult, curved: Boolean): QuickShapeResult =
        QuickShapeBox.setCurved(shape, curved)

    fun snapLine(shape: QuickShapeResult, enabled: Boolean, fixedEndpoint: Int = 0): QuickShapeResult {
        if (!enabled || shape.type != QuickShapeType.LINE || shape.points.size != 2 || fixedEndpoint !in 0..1) return shape
        val a = shape.points[fixedEndpoint]; val b = shape.points[1 - fixedEndpoint]
        val delta = b - a; val length = hypot(delta.x, delta.y)
        if (!length.isFinite() || length < 0.001f) return shape
        val angle = atan2(delta.y, delta.x)
        val targets = intArrayOf(0, 30, 45, 60, 90, 120, 135, 150, 180, 210, 225, 240, 270, 300, 315, 330)
        val target = targets.map { it * PI.toFloat() / 180 }.minBy { abs(atan2(sin(it - angle), cos(it - angle))) }
        if (abs(atan2(sin(target - angle), cos(target - angle))) > 5f * PI.toFloat() / 180 + 0.000001f) return shape
        val points = shape.points.toMutableList()
        points[1 - fixedEndpoint] = a + Point2D(length * cos(target), length * sin(target))
        return shape.copy(points = points, center = (points[0] + points[1]) / 2f)
    }

    fun drag(shape: QuickShapeResult, handle: Int, from: Point2D, to: Point2D,
             snapLineAngles: Boolean = false, reshape: Boolean = false, boxHandles: Boolean = false,
             curvedContour: Boolean = false): QuickShapeResult {
        if (!from.x.isFinite() || !from.y.isFinite() || !to.x.isFinite() || !to.y.isFinite()) return shape
        if (from == to) return shape
        val delta = to - from
        if (!delta.x.isFinite() || !delta.y.isFinite()) return shape
        if (handle < 0) {
            return shape.copy(center = shape.center + delta, points = shape.points.map { it + delta })
        }
        if (shape.type == QuickShapeType.QUADRILATERAL || shape.type == QuickShapeType.CONTOUR) {
            if (!boxHandles && handle !in 0..3) return shape
            return QuickShapeBox.drag(shape, if (boxHandles) handle else handle * 2, from, to,
                reshape || !boxHandles, curvedContour && boxHandles)
        }
        if (boxHandles && shape.type == QuickShapeType.RECTANGLE) {
            return QuickShapeBox.drag(shape, handle, from, to, reshape, curvedContour)
        }
        if (shape.type == QuickShapeType.CURVE) {
            if (shape.points.size != 3 || handle !in 0..2) return shape
            val points = shape.points.toMutableList()
            points[handle] = points[handle] + delta
            return shape.copy(points = points, center = points.reduce { a, b -> a + b } / 3f)
        }
        if (shape.type == QuickShapeType.ARC) {
            if (handle !in 0..2 || shape.points.size != 3) return shape
            val points = shape.points.toMutableList()
            points[handle] = points[handle] + delta
            return QuickShapeArc.through(points[0], points[1], points[2]) ?: shape
        }
        if (shape.type == QuickShapeType.LINE || shape.type == QuickShapeType.TRIANGLE) {
            val points = shape.points.toMutableList()
            if (handle !in points.indices) return shape
            points[handle] = points[handle] + delta
            val edited = shape.copy(points = points, center = points.reduce { a, b -> a + b } / points.size.toFloat())
            return snapLine(edited, snapLineAngles, 1 - handle)
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
