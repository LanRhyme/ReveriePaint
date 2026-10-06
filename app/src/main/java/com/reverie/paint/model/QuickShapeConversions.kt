/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.reverie.paint.model

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/** Explicit draft edits; recognition never applies these constraints automatically. */
object QuickShapeConversions {
    enum class Target { PARALLELOGRAM, RECTANGLE, SQUARE }

    fun convert(shape: QuickShapeResult, target: Target): QuickShapeResult {
        val corners = when (shape.type) {
            QuickShapeType.RECTANGLE -> QuickShapeGeometry.corners(shape)
            QuickShapeType.QUADRILATERAL, QuickShapeType.CONTOUR -> {
                if (shape.points.size != 8) return shape
                shape.points.filterIndexed { i, _ -> i % 2 == 0 }
            }
            else -> return shape
        }
        if (corners.any { !it.x.isFinite() || !it.y.isFinite() }) return shape
        // The least-squares parallelogram averages each pair of opposite edges.
        val center = corners.fold(Point2D(0f, 0f)) { sum, p -> sum + p / 4f }
        val u = (corners[1] - corners[0]) / 4f + (corners[2] - corners[3]) / 4f
        val v = (corners[3] - corners[0]) / 4f + (corners[2] - corners[1]) / 4f
        val width = hypot(u.x, u.y)
        if (!width.isFinite() || width < 1f) return shape
        val axis = u / width
        val shear = v.x * axis.x + v.y * axis.y
        val height = abs(axis.x * v.y - axis.y * v.x)
        if (!shear.isFinite() || !height.isFinite() || height < 1f) return shape
        val rotation = atan2(axis.y, axis.x)
        val result = if (target == Target.PARALLELOGRAM) {
            val vertices = listOf(center - u - v, center + u - v, center + u + v, center - u + v)
            shape.copy(type = QuickShapeType.QUADRILATERAL, center = center,
                radiusX = width + abs(shear), radiusY = height, rotationRad = rotation,
                contourCurved = false, points = vertices.indices.flatMap { i ->
                    listOf(vertices[i], vertices[i] / 2f + vertices[(i + 1) % 4] / 2f)
                })
        } else {
            // Preserve the averaged first-edge direction and remove shear along that axis.
            val square = target == Target.SQUARE
            val half = maxOf(width, height)
            shape.copy(type = QuickShapeType.RECTANGLE, center = center, rotationRad = rotation,
                radiusX = if (square) half else width, radiusY = if (square) half else height,
                points = emptyList(), contourCurved = false)
        }
        return if (result.radiusX.isFinite() && result.radiusY.isFinite() &&
            QuickShapeGeometry.outline(result).all { it.x.isFinite() && it.y.isFinite() }) result else shape
    }
}
