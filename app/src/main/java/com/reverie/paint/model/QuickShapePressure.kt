/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.reverie.paint.model

/**
 * 速创形状的压感重采样。
 *
 * 原笔迹以六元组 `[x, y, pressure, tiltX, tiltY, ...]` 存储；成形后的轮廓点数通常
 * 与原笔迹不同。这里按归一化位置线性插值，让形状延续原笔迹的轻重变化，而不是整条等宽。
 */
object QuickShapePressure {
    /** 把 [original] 的逐点压感重采样到 [targetCount] 个点；无有效输入时返回全 1 */
    fun resample(original: FloatArray, targetCount: Int): FloatArray {
        if (targetCount <= 0) return FloatArray(0)
        val count = original.size / 6
        if (count <= 0) return FloatArray(targetCount) { 1f }
        if (count == 1) return FloatArray(targetCount) { original[2].coerceIn(0.01f, 1f) }
        val out = FloatArray(targetCount)
        val last = (count - 1).toFloat()
        for (i in 0 until targetCount) {
            val t = if (targetCount == 1) 0f else i / (targetCount - 1).toFloat()
            val pos = t * last
            val i0 = pos.toInt().coerceIn(0, count - 1)
            val i1 = (i0 + 1).coerceAtMost(count - 1)
            val frac = (pos - i0).coerceIn(0f, 1f)
            val a = original[i0 * 6 + 2]
            val b = original[i1 * 6 + 2]
            out[i] = (a + (b - a) * frac).coerceIn(0.01f, 1f)
        }
        return out
    }

    /** 原笔迹的平均压感（保持既有行为，供开关关闭时使用） */
    fun average(original: FloatArray): Float {
        val count = original.size / 6
        if (count <= 0) return 1f
        var sum = 0.0
        for (i in 0 until count) sum += original[i * 6 + 2]
        return (sum / count).toFloat().coerceIn(0.01f, 1f)
    }
}
