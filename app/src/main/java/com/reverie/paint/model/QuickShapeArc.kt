/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.reverie.paint.model

import kotlin.math.*

/** Three-point circle geometry, followed by full-stroke validation for recognition. */
internal object QuickShapeArc {
    fun through(a: Point2D, b: Point2D, c: Point2D): QuickShapeResult? {
        if (listOf(a, b, c).any { !it.x.isFinite() || !it.y.isFinite() }) return null
        // Translate before squaring to keep large document coordinates numerically stable.
        val bx = (b.x - a.x).toDouble(); val by = (b.y - a.y).toDouble()
        val cx = (c.x - a.x).toDouble(); val cy = (c.y - a.y).toDouble()
        val scale = max(hypot(bx, by), hypot(cx, cy))
        val cross = bx * cy - by * cx
        if (scale < 0.001 || abs(cross) < scale * scale * 0.001) return null
        val bb = bx * bx + by * by; val cc = cx * cx + cy * cy
        val ux = (bb * cy - cc * by) / (2 * cross)
        val uy = (bx * cc - cx * bb) / (2 * cross)
        val radius = hypot(ux, uy)
        if (!radius.isFinite() || radius !in 1.0..100000.0) return null
        val center = Point2D((a.x + ux).toFloat(), (a.y + uy).toFloat())
        val start = atan2(-uy, -ux)
        fun positive(angle: Double): Double = (angle % (2 * PI) + 2 * PI) % (2 * PI)
        val mid = positive(atan2(by - uy, bx - ux) - start)
        val end = positive(atan2(cy - uy, cx - ux) - start)
        val sweep = if (mid < end) end else end - 2 * PI
        if (abs(sweep) < PI / 12 || abs(sweep) > PI * 1.85) return null
        val r = radius.toFloat()
        return QuickShapeResult(QuickShapeType.ARC, listOf(a, b, c), center, r, r,
            start.toFloat(), sweep.toFloat())
    }

    /** Fit the whole uniformly sampled stroke so a shaky endpoint does not define the entire circle. */
    private fun leastSquares(points: List<Point2D>): QuickShapeResult? {
        val mx = points.sumOf { it.x.toDouble() } / points.size
        val my = points.sumOf { it.y.toDouble() } / points.size
        var xx = 0.0; var yy = 0.0; var xy = 0.0; var xq = 0.0; var yq = 0.0
        for (p in points) {
            val x = p.x - mx; val y = p.y - my; val q = x * x + y * y
            xx += x * x; yy += y * y; xy += x * y; xq += x * q; yq += y * q
        }
        val determinant = xx * yy - xy * xy
        if (determinant <= (xx + yy) * (xx + yy) * 1e-6) return null
        val cx = (xq * yy - yq * xy) / (2 * determinant)
        val cy = (yq * xx - xq * xy) / (2 * determinant)
        val radius = sqrt((xx + yy) / points.size + cx * cx + cy * cy)
        if (!radius.isFinite() || radius !in 1.0..100000.0) return null
        val center = Point2D((mx + cx).toFloat(), (my + cy).toFloat())
        fun project(p: Point2D): Point2D {
            val angle = atan2(p.y - center.y, p.x - center.x)
            return center + Point2D(radius.toFloat() * cos(angle), radius.toFloat() * sin(angle))
        }
        return through(project(points.first()), project(points[points.size / 2]), project(points.last()))
    }

    fun fit(points: List<Point2D>, length: Float, relaxed: Boolean = false): QuickShapeResult? {
        if (points.size < 6) return null
        val arc = (if (relaxed) leastSquares(points) else
            through(points.first(), points[points.size / 2], points.last())) ?: return null
        // Shallow curves are ambiguous with lines; nearly closed curves use circle recognition.
        if (abs(arc.arcSweepRad) < (if (relaxed) PI / 6 else PI / 4) || abs(arc.arcSweepRad) > PI * 1.8) return null
        var squares = 0.0
        var previous = atan2(points[0].y - arc.center.y, points[0].x - arc.center.x)
        var travelledAngle = 0f
        var backwards = 0f
        val direction = sign(arc.arcSweepRad)
        for ((i, p) in points.withIndex()) {
            val radius = hypot(p.x - arc.center.x, p.y - arc.center.y)
            val error = abs(radius / arc.radiusX - 1f)
            if (!error.isFinite() || error > (if (relaxed) 0.18f else 0.08f)) return null
            squares += error * error
            val angle = atan2(p.y - arc.center.y, p.x - arc.center.x)
            if (i > 0) {
                val step = atan2(sin(angle - previous), cos(angle - previous)) * direction
                travelledAngle += step
                if (step < 0f) backwards -= step
            }
            previous = angle
        }
        if (sqrt(squares / points.size) > (if (relaxed) 0.075 else 0.035) ||
            backwards > (if (relaxed) 0.18f else 0.08f)) return null
        if (abs(travelledAngle - abs(arc.arcSweepRad)) > 0.08f) return null
        val lengthRange = if (relaxed) 0.8f..1.3f else 0.92f..1.08f
        if (length / (arc.radiusX * abs(arc.arcSweepRad)) !in lengthRange) return null
        return arc
    }
}
