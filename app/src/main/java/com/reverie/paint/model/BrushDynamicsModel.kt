/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import com.reverie.paint.R
import kotlin.math.max
import kotlin.math.min

/**
 * 曲线控制点，范围严格归一化于 [0.0, 1.0]
 */
data class CurvePoint(
    val x: Float,
    val y: Float,
) {
    init {
        require(x in 0f..1f && y in 0f..1f) {
            "CurvePoint coordinates must be within [0, 1], got x=$x, y=$y"
        }
    }

    companion object {
        fun of(x: Float, y: Float): CurvePoint {
            return CurvePoint(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f))
        }
    }
}

/**
 * 常用曲线预设模板
 */
enum class CurvePreset(val titleRes: Int) {
    LINEAR(R.string.brush_curve_preset_linear),
    SOFT(R.string.brush_curve_preset_soft),
    HARD(R.string.brush_curve_preset_hard),
    S_CURVE(R.string.brush_curve_preset_scurve),
    PEAK(R.string.brush_curve_preset_peak),
    STEPS(R.string.brush_curve_preset_steps);

    fun createPoints(): List<CurvePoint> = when (this) {
        LINEAR -> listOf(CurvePoint(0f, 0f), CurvePoint(1f, 1f))
        SOFT -> listOf(
            CurvePoint(0f, 0f),
            CurvePoint(0.25f, 0.5f),
            CurvePoint(0.75f, 0.9f),
            CurvePoint(1f, 1f),
        )
        HARD -> listOf(
            CurvePoint(0f, 0f),
            CurvePoint(0.25f, 0.1f),
            CurvePoint(0.75f, 0.5f),
            CurvePoint(1f, 1f),
        )
        S_CURVE -> listOf(
            CurvePoint(0f, 0f),
            CurvePoint(0.25f, 0.1f),
            CurvePoint(0.75f, 0.9f),
            CurvePoint(1f, 1f),
        )
        PEAK -> listOf(
            CurvePoint(0f, 0f),
            CurvePoint(0.5f, 1f),
            CurvePoint(1f, 0f),
        )
        STEPS -> listOf(
            CurvePoint(0f, 0f),
            CurvePoint(0.48f, 0f),
            CurvePoint(0.52f, 1f),
            CurvePoint(1f, 1f),
        )
    }
}

/**
 * Krita 笔刷核心动态传感器全集
 */
enum class BrushSensor(
    val id: String,
    val titleRes: Int,
    val iconRes: Int,
) {
    PRESSURE("pressure", R.string.brush_sensor_pressure, R.drawable.ic_hand),
    SPEED("speed", R.string.brush_sensor_speed, R.drawable.ic_line),
    DRAWING_ANGLE("drawingangle", R.string.brush_sensor_drawingangle, R.drawable.ic_rotate_cw),
    TILT_ELEVATION("declination", R.string.brush_sensor_tilt_elevation, R.drawable.ic_pencil),
    TILT_DIRECTION("ascension", R.string.brush_sensor_tilt_direction, R.drawable.ic_rotate_ccw),
    TILT_X("xtilt", R.string.brush_sensor_tilt_x, R.drawable.ic_flip_h),
    TILT_Y("ytilt", R.string.brush_sensor_tilt_y, R.drawable.ic_flip_v),
    ROTATION("rotation", R.string.brush_sensor_rotation, R.drawable.ic_refresh),
    TANGENTIAL_PRESSURE("tangentialpressure", R.string.brush_sensor_tangential_pressure, R.drawable.ic_sliders),
    FADE("fade", R.string.brush_sensor_fade, R.drawable.ic_droplet),
    DISTANCE("distance", R.string.brush_sensor_distance, R.drawable.ic_repeat_loop),
    TIME("time", R.string.brush_sensor_time, R.drawable.ic_clock),
    FUZZY("fuzzy", R.string.brush_sensor_fuzzy, R.drawable.ic_grid);

    companion object {
        fun fromId(id: String): BrushSensor {
            val normalized = id.trim().lowercase()
            return when (normalized) {
                "declination", "tilt-elevation", "tiltelevation" -> TILT_ELEVATION
                "ascension", "tilt-direction", "tiltdirection" -> TILT_DIRECTION
                "xtilt", "tilt-x", "tiltx" -> TILT_X
                "ytilt", "tilt-y", "tilty" -> TILT_Y
                else -> entries.firstOrNull { it.id == normalized } ?: PRESSURE
            }
        }
    }
}

/**
 * 单项参数的动态响应配置
 */
data class DynamicOptionConfig(
    val optionKey: String,
    val enabled: Boolean = false,
    val sensorId: String = BrushSensor.PRESSURE.id,
    val points: List<CurvePoint> = CurvePreset.LINEAR.createPoints(),
    val strength: Float = 1.0f,
) {
    /**
     * 将控制点序列化为 Krita 标准格式 (e.g. "0,0;0.5,0.7;1,1;")
     */
    fun toKritaCurveString(): String {
        if (points.isEmpty()) return "0,0;1,1;"
        val sorted = points.sortedBy { it.x }
        val sb = StringBuilder()
        for (pt in sorted) {
            sb.append(String.format(java.util.Locale.US, "%.3f,%.3f;", pt.x, pt.y))
        }
        return sb.toString()
    }

    /**
     * 对齐 Krita KisCubicCurve / KisCubicSpline 的自然三次样条插值评估
     * 当只有 2 个控制点时，严格为纯线性插值 (直线)；当 >=3 个点时通过 Thomas 算法求解连续三次样条
     */
    fun evaluate(x: Float): Float {
        val clampedX = x.coerceIn(0f, 1f)
        if (points.isEmpty()) return clampedX
        val sorted = points.sortedBy { it.x }
        if (clampedX <= sorted.first().x) return sorted.first().y
        if (clampedX >= sorted.last().x) return sorted.last().y

        val n = sorted.size - 1
        // 2 个点时，严格按纯直线段线性计算，绝不加入人造平滑 / S 弯曲
        if (n == 1) {
            val p0 = sorted[0]
            val p1 = sorted[1]
            val dx = p1.x - p0.x
            if (dx <= 0.0001f) return p0.y
            val t = (clampedX - p0.x) / dx
            return (p0.y + (p1.y - p0.y) * t).coerceIn(0f, 1f)
        }

        // >= 3 个点: 求解 Krita 自然三次样条 (自然边界条件 c[0]=0, c[n]=0)
        val h = FloatArray(n)
        val a = FloatArray(n + 1)
        for (i in 0 until n) {
            h[i] = maxOf(1e-5f, sorted[i + 1].x - sorted[i].x)
            a[i] = sorted[i].y
        }
        a[n] = sorted[n].y

        val triB = FloatArray(n - 1)
        val triF = FloatArray(n - 1)
        val triA = FloatArray(n - 1)

        for (i in 0 until n - 1) {
            triB[i] = 2f * (h[i] + h[i + 1])
            triF[i] = 6f * ((a[i + 2] - a[i + 1]) / h[i + 1] - (a[i + 1] - a[i]) / h[i])
        }
        for (i in 1 until n - 1) {
            triA[i] = h[i]
        }

        val size = n - 1
        val cInner = FloatArray(size)
        if (size == 1) {
            cInner[0] = triF[0] / triB[0]
        } else {
            val alpha = FloatArray(size)
            val beta = FloatArray(size)
            alpha[1] = -triA[0] / triB[0]
            beta[1] = triF[0] / triB[0]
            for (i in 1 until size - 1) {
                val denom = triA[i - 1] * alpha[i] + triB[i]
                alpha[i + 1] = -triA[i] / denom
                beta[i + 1] = (triF[i] - triA[i - 1] * beta[i]) / denom
            }
            val lastDenom = triB[size - 1] + triA[size - 1] * alpha[size - 1]
            cInner[size - 1] = (triF[size - 1] - triA[size - 1] * beta[size - 1]) / lastDenom
            for (i in size - 2 downTo 0) {
                cInner[i] = alpha[i + 1] * cInner[i + 1] + beta[i + 1]
            }
        }

        val c = FloatArray(n + 1)
        for (i in 0 until size) {
            c[i + 1] = cInner[i]
        }

        val d = FloatArray(n)
        val b = FloatArray(n)
        for (i in 0 until n) {
            d[i] = (c[i + 1] - c[i]) / h[i]
            b[i] = (a[i + 1] - a[i]) / h[i] - 0.5f * c[i] * h[i] - (1f / 6f) * d[i] * h[i] * h[i]
        }

        var seg = 0
        while (seg < n - 1 && sorted[seg + 1].x < clampedX) {
            seg++
        }

        val dx = clampedX - sorted[seg].x
        val y = a[seg] + b[seg] * dx + 0.5f * c[seg] * dx * dx + (1f / 6f) * d[seg] * dx * dx * dx
        return y.coerceIn(0f, 1f)
    }

    companion object {
        fun defaultFor(optionKey: String): DynamicOptionConfig {
            return DynamicOptionConfig(
                optionKey = optionKey,
                enabled = false,
                sensorId = BrushSensor.PRESSURE.id,
                points = CurvePreset.LINEAR.createPoints(),
                strength = 1.0f,
            )
        }

        /**
         * 从 Krita 格式反序列化点列表
         */
        fun parseKritaCurve(curveStr: String): List<CurvePoint> {
            val trimmed = curveStr.trim()
            if (trimmed.isBlank()) return CurvePreset.LINEAR.createPoints()
            val tokens = trimmed.split(';').map { it.trim() }.filter { it.isNotEmpty() }
            val parsed = mutableListOf<CurvePoint>()
            for (t in tokens) {
                val parts = t.split(',')
                if (parts.size == 2) {
                    val x = parts[0].toFloatOrNull()
                    val y = parts[1].toFloatOrNull()
                    if (x != null && y != null) {
                        parsed.add(CurvePoint.of(x, y))
                    }
                }
            }
            if (parsed.size < 2) return CurvePreset.LINEAR.createPoints()
            return parsed.sortedBy { it.x }
        }
    }
}
