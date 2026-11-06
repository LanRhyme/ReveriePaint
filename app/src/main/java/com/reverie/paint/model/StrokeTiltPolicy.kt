/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

/**
 * 笔画倾角直通策略 (无状态, 热路径零分配)。
 *
 * 单位: 度, 幅值 = 自垂直倾角 (Android AXIS_TILT 口径), 范围 [-60, 60]。
 * 硬件无倾角 (手指 / 零倾角笔) 时下发 0, 与 Krita 对无倾角输入的处理一致
 * (kis_paint_information.cc 对 (0,0) 取固定朝向与 tiltElevation = acos(0))。
 * 非有限值视为 0, 防止 NaN 污染下游。
 */
object StrokeTiltPolicy {
    const val MAX_TILT_DEG = 60.0

    fun component(v: Double): Double =
        if (v.isFinite()) v.coerceIn(-MAX_TILT_DEG, MAX_TILT_DEG) else 0.0

    fun component(v: Float): Float {
        val m = MAX_TILT_DEG.toFloat()
        return if (v.isFinite()) v.coerceIn(-m, m) else 0f
    }
}
