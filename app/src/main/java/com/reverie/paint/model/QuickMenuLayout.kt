/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.reverie.paint.model

import kotlin.math.abs

/**
 * 快捷菜单自适应布局参数
 *
 * @property xRatio 相对中心点的 X 偏移比例 (乘以列间距 dx = width + gap)
 * @property yRatio 相对中心点的 Y 偏移比例 (乘以行间距 dy = step)
 */
data class QuickMenuSlotOffset(val xRatio: Float, val yRatio: Float)

object QuickMenuLayout {
    /**
     * 根据选项数量 count (1..6) 返回每个选项的目标角度 (度, 范围 [-180, 180])。
     * - 0° 为正右方 (3点钟方向)
     * - 90° 为正下方 (6点钟方向)
     * - -90° 为正上方 (12点钟方向)
     * - ±180° 为正左方 (9点钟方向)
     *
     * 无论选项数量为多少，都严格自适应均匀或对称分布，并与手指滑动方向完全对齐。
     */
    fun getAngles(count: Int): FloatArray = when (count) {
        1 -> floatArrayOf(-90f)
        2 -> floatArrayOf(180f, 0f)
        3 -> floatArrayOf(-90f, 30f, 150f)
        4 -> floatArrayOf(-90f, 0f, 90f, 180f)
        5 -> floatArrayOf(-90f, -18f, 54f, 126f, -162f)
        6 -> floatArrayOf(-90f, -30f, 30f, 90f, 150f, -150f)
        else -> if (count <= 0) floatArrayOf() else FloatArray(count) { i ->
            var a = -90f + i * (360f / count)
            while (a > 180f) a -= 360f
            while (a < -180f) a += 360f
            a
        }
    }

    /**
     * 根据选项数量 count (1..6) 返回每个胶囊在 Radial 容器中相对中心点的布局偏移比例。
     */
    fun getSlotOffsets(count: Int): List<QuickMenuSlotOffset> = when (count) {
        1 -> listOf(QuickMenuSlotOffset(0f, -1f))
        2 -> listOf(QuickMenuSlotOffset(-1f, 0f), QuickMenuSlotOffset(1f, 0f))
        3 -> listOf(
            QuickMenuSlotOffset(0f, -1f),
            QuickMenuSlotOffset(1f, 0.5f),
            QuickMenuSlotOffset(-1f, 0.5f),
        )
        4 -> listOf(
            QuickMenuSlotOffset(0f, -1f),
            QuickMenuSlotOffset(1f, 0f),
            QuickMenuSlotOffset(0f, 1f),
            QuickMenuSlotOffset(-1f, 0f),
        )
        5 -> listOf(
            QuickMenuSlotOffset(0f, -1f),
            QuickMenuSlotOffset(1f, -0.35f),
            QuickMenuSlotOffset(0.65f, 0.85f),
            QuickMenuSlotOffset(-0.65f, 0.85f),
            QuickMenuSlotOffset(-1f, -0.35f),
        )
        6 -> listOf(
            QuickMenuSlotOffset(0f, -1f),
            QuickMenuSlotOffset(1f, -0.5f),
            QuickMenuSlotOffset(1f, 0.5f),
            QuickMenuSlotOffset(0f, 1f),
            QuickMenuSlotOffset(-1f, 0.5f),
            QuickMenuSlotOffset(-1f, -0.5f),
        )
        else -> (0 until count).map { i ->
            val rad = Math.toRadians((-90.0 + i * (360.0 / count)))
            QuickMenuSlotOffset(kotlin.math.cos(rad).toFloat(), kotlin.math.sin(rad).toFloat())
        }
    }

    /**
     * 根据当前滑动的物理角度 deg (atan2(dy, dx) 得出，[-180, 180]) 计算选中的选项索引 (0 until count)。
     * 计算 deg 与各选项目标角度的最小角距离，返回最贴近的选项索引。
     */
    fun findHoverIndex(deg: Float, count: Int): Int {
        if (count <= 0) return -1
        val angles = getAngles(count)
        var bestIndex = 0
        var minDiff = Float.MAX_VALUE
        for (i in angles.indices) {
            val target = angles[i]
            var diff = abs(deg - target) % 360f
            if (diff > 180f) diff = 360f - diff
            if (diff < minDiff) {
                minDiff = diff
                bestIndex = i
            }
        }
        return bestIndex
    }
}
