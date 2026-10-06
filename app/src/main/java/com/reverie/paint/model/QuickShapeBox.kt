/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.reverie.paint.model

import kotlin.math.*

/** Eight perimeter handles clockwise from top-left, followed by a separate rotation handle. */
internal object QuickShapeBox {
    private fun hasVertices(shape: QuickShapeResult) =
        shape.type == QuickShapeType.CONTOUR || shape.type == QuickShapeType.QUADRILATERAL

    private fun perimeter(shape: QuickShapeResult): List<Point2D> {
        if (hasVertices(shape)) return shape.points
        val corners = QuickShapeGeometry.corners(shape)
        return corners.indices.flatMap { i -> listOf(corners[i], (corners[i] + corners[(i + 1) % 4]) / 2f) }
    }

    fun handles(shape: QuickShapeResult): List<Point2D> = perimeter(shape) +
        QuickShapeGeometry.localToDoc(shape, Point2D(0f, -shape.radiusY * 1.4f))

    fun outline(shape: QuickShapeResult): List<Point2D> {
        val points = shape.points
        if (points.size != 8) return emptyList()
        if (!shape.contourCurved) return listOf(points[0], points[2], points[4], points[6], points[0])
        val out = ArrayList<Point2D>()
        for (edge in 0..3) {
            val a = points[edge * 2]; val mid = points[edge * 2 + 1]; val b = points[(edge * 2 + 2) % 8]
            // The handle lies ON the curve, not at the off-curve Bezier control point.
            val control = mid * 2f - (a + b) / 2f
            val segments = (ceil((a.distanceTo(control) + control.distanceTo(b)) / 4).toInt()
                .coerceIn(16, 512) + 1) / 2 * 2
            for (i in 0 until segments) {
                val t = i.toFloat() / segments; val u = 1f - t
                out.add(a * (u * u) + control * (2f * u * t) + b * (t * t))
            }
        }
        out.add(points[0])
        return out
    }

    fun setCurved(shape: QuickShapeResult, curved: Boolean): QuickShapeResult {
        if (!hasVertices(shape) || shape.points.size != 8) return shape
        val points = shape.points.toMutableList()
        if (!curved) straighten(points)
        return shape.copy(type = QuickShapeType.CONTOUR, points = points, contourCurved = curved)
    }

    private fun straighten(points: MutableList<Point2D>) {
        for (i in 1..7 step 2) points[i] = (points[i - 1] + points[(i + 1) % 8]) / 2f
    }

    fun drag(shape: QuickShapeResult, handle: Int, from: Point2D, to: Point2D,
             reshape: Boolean, curvedContour: Boolean): QuickShapeResult {
        if (handle !in 0..8) return shape
        val controls = perimeter(shape)
        if (controls.size != 8) return shape
        if (handle == 8) {
            val a = from - shape.center; val b = to - shape.center
            if (hypot(a.x, a.y) < 0.001f || hypot(b.x, b.y) < 0.001f) return shape
            val raw = atan2(b.y, b.x) - atan2(a.y, a.x)
            val angle = atan2(sin(raw), cos(raw))
            val rotated = shape.copy(rotationRad = shape.rotationRad + angle)
            return if (hasVertices(shape)) rotated.copy(points = controls.map {
                QuickShapeGeometry.localToDoc(rotated, QuickShapeGeometry.docToLocal(shape, it))
            }) else rotated
        }
        if (reshape) {
            val points = controls.toMutableList()
            val delta = to - from
            val curved = if (shape.type == QuickShapeType.CONTOUR) shape.contourCurved else curvedContour
            if (curved) {
                points[handle] = points[handle] + delta
                if (handle % 2 == 0) {
                    // Keep the adjacent off-curve quadratic controls fixed. The visible midpoints
                    // move by one quarter of the corner delta, avoiding the old opposing bulge.
                    for (i in listOf((handle + 7) % 8, (handle + 1) % 8)) points[i] = points[i] + delta / 4f
                }
            } else {
                if (handle % 2 == 0) points[handle] = points[handle] + delta
                else {
                    // A straight edge handle moves both endpoints; it cannot create a bent edge.
                    for (i in listOf(handle - 1, (handle + 1) % 8)) points[i] = points[i] + delta
                }
                straighten(points)
            }
            return shape.copy(type = QuickShapeType.CONTOUR, points = points, contourCurved = curved)
        }
        val delta = QuickShapeGeometry.docToLocal(shape, to) - QuickShapeGeometry.docToLocal(shape, from)
        if (hasVertices(shape)) {
            // Use the actual deformed handles as anchors, not the rectangle's old bounding frame.
            val anchor = QuickShapeGeometry.docToLocal(shape, controls[(handle + 4) % 8])
            val active = QuickShapeGeometry.docToLocal(shape, controls[handle])
            fun factor(distance: Float, movement: Float, radius: Float): Float {
                if (abs(distance) < 0.001f || radius < 0.001f) return 1f
                return ((distance + movement) / distance).coerceIn(1f / radius, 100000f / radius)
            }
            val sx = if (handle == 1 || handle == 5) 1f else factor(active.x - anchor.x, delta.x, shape.radiusX)
            val sy = if (handle == 3 || handle == 7) 1f else factor(active.y - anchor.y, delta.y, shape.radiusY)
            fun scale(p: Point2D): Point2D {
                val local = QuickShapeGeometry.docToLocal(shape, p)
                return QuickShapeGeometry.localToDoc(shape,
                    anchor + Point2D((local.x - anchor.x) * sx, (local.y - anchor.y) * sy))
            }
            return shape.copy(center = scale(shape.center), radiusX = shape.radiusX * sx,
                radiusY = shape.radiusY * sy, points = controls.map(::scale))
        }
        var left = -shape.radiusX; var right = shape.radiusX
        var top = -shape.radiusY; var bottom = shape.radiusY
        if (handle in listOf(0, 6, 7)) left = (left + delta.x).coerceIn(right - 200000f, right - 2f)
        if (handle in 2..4) right = (right + delta.x).coerceIn(left + 2f, left + 200000f)
        if (handle in 0..2) top = (top + delta.y).coerceIn(bottom - 200000f, bottom - 2f)
        if (handle in 4..6) bottom = (bottom + delta.y).coerceIn(top + 2f, top + 200000f)
        val center = QuickShapeGeometry.localToDoc(shape, Point2D((left + right) / 2, (top + bottom) / 2))
        return shape.copy(center = center, radiusX = (right - left) / 2, radiusY = (bottom - top) / 2)
    }
}
