/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

/**
 * 视口 LoD 的档位选择 (纯逻辑, 无 Android 依赖, 可 JVM 单测)。
 *
 * 记 `viewScale` = 画布在屏幕上的缩放 (1.0 = 100%, 0.12 = 缩到 12%)。显示瓦片的目标是
 * "一个瓦片像素正好覆盖一个屏幕像素", 于是文档像素 / 显示像素 ≈ 1 / viewScale。
 * 取 2 的幂 (1/2/4/8) 是为了让引擎侧的盒式平均退化成整齐的 2x2/4x4/8x8 分块, 也让
 * 网格几何保持整数关系。
 *
 * 取**向上**取整的幂: viewScale=0.3 时 1/0.3≈3.33, 用 4 档 (绘制时再缩 0.83) 比用 2 档
 * (要放大 1.67 倍, 糊) 好。
 */
object LodScalePolicy {
    /** 允许的最大档位 (文档像素 / 显示像素)。 */
    const val MAX_SCALE = 8

    /**
     * @param viewScale 画布当前屏幕缩放 (<=0 视为 1)。
     * @param maxScale 允许的最大档位 (会被夹到 [MAX_SCALE])。
     * @return 档位: 1 (1:1) / 2 / 4 / 8
     */
    fun choose(viewScale: Float, maxScale: Int = MAX_SCALE): Int {
        val cap = maxScale.coerceIn(1, MAX_SCALE)
        if (cap <= 1) return 1
        val z = viewScale.takeIf { it > 0f && it.isFinite() } ?: 1f
        if (z >= 1f) return 1
        // 需要的最小档位 = ceil(1/z) 向上取到 2 的幂
        var s = 1
        var needed = 1.0 / z.toDouble()
        if (needed < 1.0) needed = 1.0
        while (s < cap && s < needed - 1e-6) {
            s *= 2
        }
        return s
    }
}
