/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.reverie.paint.model

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot

/** Arc-length pressure mapping, used once on commit, never in the touch sampling loop. */
object QuickShapePressure {
    /** Uniform distance samples for an open stroke; closed shapes additionally need [stroke] alignment. */
    fun resample(original: FloatArray, targetCount: Int): FloatArray {
        if (targetCount <= 0) return FloatArray(0)
        val source = sourcePath(original) ?: return FloatArray(targetCount) { 1f }
        return FloatArray(targetCount) { i ->
            pressure(original, source, if (targetCount == 1) 0.0 else i.toDouble() / (targetCount - 1))
        }
    }

    /**
     * Six-tuples ready for the existing brush/recording path. Keep source pressure knots and target
     * vertices as well as regular distance samples: endpoints alone cannot represent a pressure peak.
     * Align against the original recognition, so moving/rotating the edited shape cannot remap pressure.
     * After a non-rigid edit, correspondence is by normalized perimeter, not by absolute coordinates.
     */
    fun stroke(original: FloatArray, recognized: QuickShapeResult, edited: QuickShapeResult): FloatArray {
        val source = sourcePath(original) ?: return FloatArray(0)
        val target = Path.create(QuickShapeGeometry.outline(edited)) ?: return FloatArray(0)
        if (source.length <= 0.0 || target.length <= 0.0) return FloatArray(0)
        val reference = Path.create(QuickShapeGeometry.outline(recognized)) ?: return FloatArray(0)
        val closed = recognized.type in CLOSED_TYPES && edited.type in CLOSED_TYPES
        val start = if (closed) reference.nearestProgress(source.points.first()) else 0.0
        val direction = if (closed && alignmentError(source, reference, start, -1) <
            alignmentError(source, reference, start, 1)) -1 else 1

        // Bounded by capture size + geometry vertices + 4097 regular samples.
        val segments = ceil(target.length / 4.0).coerceIn(1.0, 4096.0).toInt()
        val knots = DoubleArray(source.points.size + target.points.size + segments + 1)
        var count = 0
        for (distance in source.distances) knots[count++] = distance / source.length
        for (distance in target.distances) {
            val u = distance / target.length
            knots[count++] = if (closed) wrap(direction * (u - start)) else u
        }
        for (i in 0..segments) knots[count++] = i.toDouble() / segments
        knots.sort()
        var unique = 0
        for (i in knots.indices) {
            if (unique == 0 || knots[i] - knots[unique - 1] > 1e-10) knots[unique++] = knots[i]
        }
        val out = FloatArray(unique * 6)
        for (i in 0 until unique) {
            val t = knots[i]
            val u = if (closed) wrap(start + direction * t) else t
            val point = target.at(u)
            out[i * 6] = point.x
            out[i * 6 + 1] = point.y
            out[i * 6 + 2] = pressure(original, source, t)
            out[i * 6 + 3] = original[3]
            out[i * 6 + 4] = original[4]
        }
        return out
    }

    /** Preserve the existing uniform-pressure behavior when the option is disabled. */
    fun average(original: FloatArray): Float {
        val count = original.size / 6
        if (count <= 0) return 1f
        var sum = 0.0
        for (i in 0 until count) sum += original[i * 6 + 2]
        return (sum / count).toFloat().coerceIn(0.01f, 1f)
    }

    private fun sourcePath(original: FloatArray): Path? {
        if (original.isEmpty() || original.size % 6 != 0) return null
        val count = original.size / 6
        if ((0 until count).any { !original[it * 6 + 2].isFinite() }) return null
        return Path.create(List(count) { Point2D(original[it * 6], original[it * 6 + 1]) })
    }

    private fun pressure(original: FloatArray, path: Path, t: Double): Float {
        if (path.points.size == 1 || path.length == 0.0) return original[2].coerceIn(0.01f, 1f)
        val distance = t.coerceIn(0.0, 1.0) * path.length
        val end = path.segmentEnd(distance)
        val span = path.distances[end] - path.distances[end - 1]
        val fraction = if (span == 0.0) 0f else ((distance - path.distances[end - 1]) / span).toFloat()
        val a = original[(end - 1) * 6 + 2]
        val b = original[end * 6 + 2]
        return (a + (b - a) * fraction).coerceIn(0.01f, 1f)
    }

    private fun alignmentError(source: Path, reference: Path, start: Double, direction: Int): Double {
        var error = 0.0
        for (i in 1..16) {
            val t = i / 17.0
            val a = source.at(t)
            val b = reference.at(wrap(start + direction * t))
            val dx = a.x.toDouble() - b.x
            val dy = a.y.toDouble() - b.y
            error += dx * dx + dy * dy
        }
        return error
    }

    private fun wrap(t: Double): Double = t - floor(t)

    private class Path(val points: List<Point2D>, val distances: DoubleArray) {
        val length = distances.last()

        fun segmentEnd(distance: Double): Int {
            var low = 1
            var high = points.lastIndex
            while (low < high) {
                val mid = (low + high) ushr 1
                if (distances[mid] < distance) low = mid + 1 else high = mid
            }
            return low
        }

        fun at(t: Double): Point2D {
            if (length == 0.0 || points.size == 1) return points.first()
            val distance = t.coerceIn(0.0, 1.0) * length
            val end = segmentEnd(distance)
            val span = distances[end] - distances[end - 1]
            val fraction = if (span == 0.0) 0f else ((distance - distances[end - 1]) / span).toFloat()
            return points[end - 1] + (points[end] - points[end - 1]) * fraction
        }

        fun nearestProgress(point: Point2D): Double {
            if (length == 0.0) return 0.0
            var best = Double.POSITIVE_INFINITY
            var progress = 0.0
            for (i in 1 until points.size) {
                val a = points[i - 1]
                val dx = points[i].x.toDouble() - a.x
                val dy = points[i].y.toDouble() - a.y
                val squaredLength = dx * dx + dy * dy
                if (squaredLength == 0.0) continue
                val t = (((point.x - a.x) * dx + (point.y - a.y) * dy) / squaredLength).coerceIn(0.0, 1.0)
                val ex = point.x - (a.x + t * dx)
                val ey = point.y - (a.y + t * dy)
                val error = ex * ex + ey * ey
                if (error < best) {
                    best = error
                    progress = (distances[i - 1] + t * (distances[i] - distances[i - 1])) / length
                }
            }
            return progress
        }

        companion object {
            fun create(points: List<Point2D>): Path? {
                if (points.isEmpty() || points.any { !it.x.isFinite() || !it.y.isFinite() }) return null
                val distances = DoubleArray(points.size)
                for (i in 1 until points.size) {
                    distances[i] = distances[i - 1] + hypot(points[i].x.toDouble() - points[i - 1].x,
                        points[i].y.toDouble() - points[i - 1].y)
                }
                return Path(points, distances)
            }
        }
    }

    private val CLOSED_TYPES = setOf(QuickShapeType.CIRCLE, QuickShapeType.ELLIPSE, QuickShapeType.RECTANGLE,
        QuickShapeType.TRIANGLE, QuickShapeType.QUADRILATERAL, QuickShapeType.CONTOUR)
}
