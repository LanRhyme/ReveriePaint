/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.reverie.paint.model

import kotlin.math.*

/** Conservative recognition: an unsupported or ambiguous stroke stays freehand. */
internal object QuickShapeRecognition {
    fun fit(input: List<Point2D>, recognizeArcs: Boolean, relaxed: Boolean,
            recognizeQuadrilaterals: Boolean, recognizeCurves: Boolean): QuickShapeResult? {
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
        if (direct > span * 0.22f || length < span * 2f) {
            if (recognizeCurves) QuickShapeCurve.fit(points, length, relaxed)?.let { return it }
            return if (recognizeArcs) QuickShapeArc.fit(points, length, relaxed) else null
        }
        val loop = points.dropLast(1) + first
        // Closed RDP must split at two distinct endpoints, never simplify the zero-length seam.
        val split = loop.indices.maxBy { loop[it].distanceTo(first) }
        val corners = removeStraightCorners(
            simplify(loop.take(split + 1), span * 0.035f).dropLast(1) +
                simplify(loop.drop(split), span * 0.035f).dropLast(1),
            span * 0.035f,
        )
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
        if (recognizeQuadrilaterals) {
            val tolerances = if (relaxed) listOf(0.035f, 0.05f, 0.07f) else listOf(0.035f)
            for (tolerance in tolerances) {
                val four = if (tolerance == 0.035f) corners else removeStraightCorners(
                    simplify(loop.take(split + 1), span * tolerance).dropLast(1) +
                        simplify(loop.drop(split), span * tolerance).dropLast(1), span * tolerance)
                if (four.size != 4 || !simpleQuadrilateral(four) ||
                    length / perimeter(four) !in 0.85f..1.25f || pathError(points, four) > span * 0.027f) continue
                val angle = atan2(four[1].y - four[0].y, four[1].x - four[0].x)
                val frame = orientedBox(four, angle, QuickShapeType.QUADRILATERAL)
                val handles = four.indices.flatMap { listOf(four[it], (four[it] + four[(it + 1) % 4]) / 2f) }
                return frame.copy(points = handles)
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
        if (relaxed) {
            // Try coarser corner simplification only after the original strict polygon checks.
            // Keep convexity, area and whole-path checks: a random quadrilateral is not a rectangle.
            for (tolerance in listOf(0.035f, 0.05f, 0.07f, 0.09f)) {
                val four = removeStraightCorners(
                    simplify(loop.take(split + 1), span * tolerance).dropLast(1) +
                        simplify(loop.drop(split), span * tolerance).dropLast(1), span * tolerance)
                if (four.size != 4 || !convex(four) || length / perimeter(four) !in 0.85f..1.3f) continue
                val edges = four.indices.map { four[(it + 1) % 4] - four[it] }
                if (edges.indices.any {
                    val a = edges[it]; val b = edges[(it + 1) % 4]
                    abs(a.x * b.x + a.y * b.y) / max(0.001f, hypot(a.x, a.y) * hypot(b.x, b.y)) > 0.5f
                }) continue
                val box = edges.map { orientedBox(points, atan2(it.y, it.x), QuickShapeType.RECTANGLE) }
                    .minBy { pathError(points, QuickShapeGeometry.corners(it)) }
                if (area / (4f * box.radiusX * box.radiusY) in 0.72f..1.15f &&
                    pathError(points, QuickShapeGeometry.corners(box)) < span * 0.055f) return box
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

    /** RDP pins both split endpoints, even when the pen starts in the middle of an edge. */
    private fun removeStraightCorners(points: List<Point2D>, epsilon: Float): List<Point2D> {
        val corners = points.toMutableList()
        while (corners.size > 3) {
            val index = corners.indices.minBy { i ->
                distanceToSegment(corners[i], corners[(i + corners.size - 1) % corners.size],
                    corners[(i + 1) % corners.size])
            }
            if (distanceToSegment(corners[index], corners[(index + corners.size - 1) % corners.size],
                    corners[(index + 1) % corners.size]) > epsilon) break
            corners.removeAt(index)
        }
        return corners
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
    private fun simpleQuadrilateral(p: List<Point2D>): Boolean {
        fun cross(a: Point2D, b: Point2D, c: Point2D): Float =
            (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x)
        fun intersects(a: Point2D, b: Point2D, c: Point2D, d: Point2D): Boolean =
            cross(a, b, c) * cross(a, b, d) <= 0f && cross(c, d, a) * cross(c, d, b) <= 0f
        return !intersects(p[0], p[1], p[2], p[3]) && !intersects(p[1], p[2], p[3], p[0])
    }

    private fun convex(p: List<Point2D>): Boolean {
        val crosses = p.indices.map {
            val a = p[(it + 1) % p.size] - p[it]
            val b = p[(it + 2) % p.size] - p[(it + 1) % p.size]
            a.x * b.y - a.y * b.x
        }
        return crosses.all { it > 0 } || crosses.all { it < 0 }
    }
}
