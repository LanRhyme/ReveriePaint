/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.reverie.paint.model

import kotlin.math.*

/** Conservative recognition: an unsupported or ambiguous stroke stays freehand. */
internal object QuickShapeRecognition {
    fun fit(input: List<Point2D>): QuickShapeResult? {
        if (input.size < 6 || input.any { !it.x.isFinite() || !it.y.isFinite() }) return null
        val clean = input.filterIndexed { i, p -> i == 0 || p.distanceTo(input[i - 1]) > 0.001f }
        if (clean.size < 6) return null
        val length = clean.zipWithNext().sumOf { (a, b) -> a.distanceTo(b).toDouble() }.toFloat()
        val span = hypot(clean.maxOf { it.x } - clean.minOf { it.x }, clean.maxOf { it.y } - clean.minOf { it.y })
        if (span < 2f || !length.isFinite() || length > span * 8) return null
        // Equal arc-length samples avoid bias from pausing at a corner or slowing down on an arc.
        val points = resample(clean, length, 128)
        val first = points.first()
        val last = points.last()
        val direct = first.distanceTo(last)
        if (direct > span * 0.8f && length / direct < 1.18f) {
            val errors = points.map { distanceToSegment(it, first, last) }
            if (errors.max() < span * 0.065f && rms(errors) < span * 0.025f) {
                return QuickShapeResult(QuickShapeType.LINE, listOf(first, last), (first + last) / 2f)
            }
        }
        if (direct > span * 0.22f || length < span * 2f) return null
        val loop = points.dropLast(1) + first
        // Closed RDP must split at two distinct endpoints, never simplify the zero-length seam.
        val split = loop.indices.maxBy { loop[it].distanceTo(first) }
        val corners = simplify(loop.take(split + 1), span * 0.035f).dropLast(1) +
            simplify(loop.drop(split), span * 0.035f).dropLast(1)
        val area = abs(loop.zipWithNext().sumOf { (a, b) ->
            a.x.toDouble() * b.y - b.x.toDouble() * a.y
        } / 2).toFloat()
        if (area < span * span * 0.06f) return null
        if (corners.size == 3 && convex(corners)) {
            val perimeter = perimeter(corners)
            if (length / perimeter in 0.9f..1.12f && pathError(points, corners) < span * 0.027f) {
                return QuickShapeResult(QuickShapeType.TRIANGLE, corners, corners.reduce { a, b -> a + b } / 3f)
            }
        }
        if (corners.size == 4 && convex(corners)) {
            val edges = corners.indices.map { corners[(it + 1) % 4] - corners[it] }
            val squareAngles = edges.indices.all {
                val a = edges[it]; val b = edges[(it + 1) % 4]
                abs(a.x * b.x + a.y * b.y) / max(0.001f, hypot(a.x, a.y) * hypot(b.x, b.y)) < 0.25f
            }
            if (squareAngles && length / perimeter(corners) in 0.9f..1.12f) {
                val angle = atan2(edges[0].y, edges[0].x)
                val box = orientedBox(points, angle, QuickShapeType.RECTANGLE)
                if (pathError(points, QuickShapeGeometry.corners(box)) < span * 0.035f) return box
            }
        }
        val mean = points.reduce { a, b -> a + b } / points.size.toFloat()
        var xx = 0f; var yy = 0f; var xy = 0f
        for (p in points) {
            val d = p - mean
            xx += d.x * d.x; yy += d.y * d.y; xy += d.x * d.y
        }
        val ellipse = orientedBox(points, 0.5f * atan2(2f * xy, xx - yy), QuickShapeType.ELLIPSE)
        if (min(ellipse.radiusX, ellipse.radiusY) < span * 0.08f) return null
        val errors = points.map {
            val p = QuickShapeGeometry.docToLocal(ellipse, it)
            abs(hypot(p.x / ellipse.radiusX, p.y / ellipse.radiusY) - 1f)
        }
        val outlineLength = perimeter(QuickShapeGeometry.outline(ellipse).dropLast(1))
        if (rms(errors) > 0.065f || errors.max() > 0.18f || length / outlineLength !in 0.85f..1.15f) return null
        // Winding/area prevents a retraced loop or figure eight from masquerading as an ellipse.
        if (area / (PI.toFloat() * ellipse.radiusX * ellipse.radiusY) !in 0.8f..1.2f) return null
        return if (ellipse.radiusX / ellipse.radiusY in 0.88f..1.14f) {
            val r = (ellipse.radiusX + ellipse.radiusY) / 2f
            ellipse.copy(type = QuickShapeType.CIRCLE, radiusX = r, radiusY = r, rotationRad = 0f)
        } else ellipse
    }

    private fun orientedBox(points: List<Point2D>, angle: Float, type: QuickShapeType): QuickShapeResult {
        val c = cos(angle); val s = sin(angle)
        val local = points.map { Point2D(it.x * c + it.y * s, -it.x * s + it.y * c) }
        val minX = local.minOf { it.x }; val maxX = local.maxOf { it.x }
        val minY = local.minOf { it.y }; val maxY = local.maxOf { it.y }
        val x = (minX + maxX) / 2; val y = (minY + maxY) / 2
        return QuickShapeResult(type, emptyList(), Point2D(x * c - y * s, x * s + y * c),
            (maxX - minX) / 2, (maxY - minY) / 2, angle)
    }

    private fun resample(points: List<Point2D>, length: Float, count: Int): List<Point2D> {
        var segment = 1
        var traversed = 0f
        return (0 until count).map { i ->
            val target = length * i / (count - 1)
            while (segment < points.lastIndex && traversed + points[segment - 1].distanceTo(points[segment]) < target) {
                traversed += points[segment - 1].distanceTo(points[segment]); segment++
            }
            val a = points[segment - 1]; val b = points[segment]
            a + (b - a) * ((target - traversed) / a.distanceTo(b)).coerceIn(0f, 1f)
        }
    }

    private fun simplify(points: List<Point2D>, epsilon: Float): List<Point2D> {
        if (points.size <= 2) return points
        val index = (1 until points.lastIndex).maxBy { distanceToSegment(points[it], points.first(), points.last()) }
        if (distanceToSegment(points[index], points.first(), points.last()) <= epsilon) return listOf(points.first(), points.last())
        return simplify(points.take(index + 1), epsilon).dropLast(1) + simplify(points.drop(index), epsilon)
    }

    private fun distanceToSegment(p: Point2D, a: Point2D, b: Point2D): Float {
        val d = b - a
        val t = (((p.x - a.x) * d.x + (p.y - a.y) * d.y) / max(0.000001f, d.x * d.x + d.y * d.y)).coerceIn(0f, 1f)
        return p.distanceTo(a + d * t)
    }

    private fun rms(values: List<Float>): Float = sqrt(values.sumOf { (it * it).toDouble() } / values.size).toFloat()
    private fun perimeter(p: List<Point2D>): Float = p.indices.sumOf { p[it].distanceTo(p[(it + 1) % p.size]).toDouble() }.toFloat()
    private fun pathError(points: List<Point2D>, corners: List<Point2D>): Float = rms(points.map { p ->
        corners.indices.minOf { distanceToSegment(p, corners[it], corners[(it + 1) % corners.size]) }
    })
    private fun convex(p: List<Point2D>): Boolean {
        val crosses = p.indices.map {
            val a = p[(it + 1) % p.size] - p[it]
            val b = p[(it + 2) % p.size] - p[(it + 1) % p.size]
            a.x * b.y - a.y * b.x
        }
        return crosses.all { it > 0 } || crosses.all { it < 0 }
    }
}
