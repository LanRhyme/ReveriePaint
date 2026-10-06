/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.reverie.paint.model

import kotlin.math.*

/** Open quadratic curve with two endpoints and an interpolated, on-curve midpoint. */
internal object QuickShapeCurve {
    private fun at(a: Point2D, control: Point2D, b: Point2D, t: Float): Point2D {
        val u = 1f - t
        return a * (u * u) + control * (2f * u * t) + b * (t * t)
    }

    fun outline(shape: QuickShapeResult): List<Point2D> {
        if (shape.points.size != 3) return emptyList()
        val a = shape.points[0]; val mid = shape.points[1]; val b = shape.points[2]
        val control = mid * 2f - (a + b) / 2f
        val segments = (ceil((a.distanceTo(control) + control.distanceTo(b)) / 4).toInt()
            .coerceIn(16, 2048) + 1) / 2 * 2
        return (0..segments).map { at(a, control, b, it.toFloat() / segments) }
    }

    fun fit(points: List<Point2D>, length: Float, relaxed: Boolean): QuickShapeResult? {
        if (points.size < 16 || points.any { !it.x.isFinite() || !it.y.isFinite() }) return null
        val a = points.first(); val b = points.last(); val chord = b - a
        val distance = hypot(chord.x, chord.y)
        val span = hypot(points.maxOf { it.x } - points.minOf { it.x }, points.maxOf { it.y } - points.minOf { it.y })
        if (distance < span * 0.25f || span < 2f) return null
        val sides = points.map { (chord.x * (it.y - a.y) - chord.y * (it.x - a.x)) / distance }
        if (sides.max() > span * 0.035f && sides.min() < -span * 0.035f) return null // S curve
        if (sides.maxOf { abs(it) } < span * 0.035f) return null
        // A sharp corner must not be smoothed into a bow just because its three anchors fit.
        val window = max(2, points.size / 25)
        for (i in window until points.size - window) {
            val before = points[i] - points[i - window]; val after = points[i + window] - points[i]
            val product = hypot(before.x, before.y) * hypot(after.x, after.y)
            if (product > 0.001f && (before.x * after.x + before.y * after.y) / product < 0.3f) return null
        }
        val parameters = FloatArray(points.size) { it.toFloat() / (points.size - 1) }
        var control = (a + b) / 2f
        repeat(4) {
            var x = 0.0; var y = 0.0; var weights = 0.0
            for (i in 1 until points.lastIndex) {
                val t = parameters[i]; val u = 1f - t; val w = 2f * t * u
                val residual = points[i] - a * (u * u) - b * (t * t)
                x += w * residual.x; y += w * residual.y; weights += w * w
            }
            if (weights < 0.001) return null
            control = Point2D((x / weights).toFloat(), (y / weights).toFloat())
            if (!control.x.isFinite() || !control.y.isFinite()) return null
            for (i in 1 until points.lastIndex) {
                // Coarse closest-point search plus local refinement accommodates uneven curve speed.
                val sample = (0..32).minBy { at(a, control, b, it / 32f).distanceTo(points[i]) }
                var lo = max(0f, (sample - 1) / 32f); var hi = min(1f, (sample + 1) / 32f)
                repeat(10) {
                    val t1 = lo + (hi - lo) / 3; val t2 = hi - (hi - lo) / 3
                    if (at(a, control, b, t1).distanceTo(points[i]) < at(a, control, b, t2).distanceTo(points[i])) hi = t2
                    else lo = t1
                }
                parameters[i] = (lo + hi) / 2
            }
        }
        var squares = 0.0; var backwards = 0f
        for (i in points.indices) {
            val error = at(a, control, b, parameters[i]).distanceTo(points[i]) / span
            if (error > (if (relaxed) 0.11f else 0.08f)) return null
            squares += error * error
            if (i > 0) backwards += max(0f, parameters[i - 1] - parameters[i])
        }
        if (sqrt(squares / points.size) > (if (relaxed) 0.065 else 0.045) || backwards > 0.08f) return null
        val mid = at(a, control, b, 0.5f)
        val shape = QuickShapeResult(QuickShapeType.CURVE, listOf(a, mid, b), (a + mid + b) / 3f)
        val curveLength = outline(shape).zipWithNext().sumOf { (x, y) -> x.distanceTo(y).toDouble() }.toFloat()
        val ratio = if (relaxed) 0.8f..1.35f else 0.85f..1.2f
        return shape.takeIf { length / curveLength in ratio }
    }
}
