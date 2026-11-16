/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 笔身旋转口径 (AXIS_RZ → Krita/Qt 角度), 与 qtbase 提交 11238262 的
 * `remainder(toDegrees(rad) + 180, 360)` 一致。
 */
class BarrelRotationPolicyTest {

    private val rad = { deg: Double -> Math.toRadians(deg) }

    @Test
    fun `Android 零度指向下方 换算后是 180 度`() {
        assertEquals(180.0, BarrelRotationPolicy.fromRadians(0.0), 1e-9)
    }

    @Test
    fun `四个基准方位逐一对应`() {
        // 原始 0°(下) / 90° / 180°(上) / -90° → Qt 口径 180 / -90 / 0 / 90
        assertEquals(180.0, BarrelRotationPolicy.fromRadians(rad(0.0)), 1e-9)
        assertEquals(-90.0, BarrelRotationPolicy.fromRadians(rad(90.0)), 1e-9)
        assertEquals(0.0, BarrelRotationPolicy.fromRadians(rad(180.0)), 1e-9)
        assertEquals(90.0, BarrelRotationPolicy.fromRadians(rad(-90.0)), 1e-9)
    }

    @Test
    fun `跨过 180 度后翻符号 不产生突变`() {
        assertEquals(179.0, BarrelRotationPolicy.fromRadians(rad(-1.0)), 1e-9)
        assertEquals(-179.0, BarrelRotationPolicy.fromRadians(rad(1.0)), 1e-9)
    }

    @Test
    fun `多圈输入按 360 折算`() {
        assertEquals(
            BarrelRotationPolicy.fromRadians(rad(30.0)),
            BarrelRotationPolicy.fromRadians(rad(30.0 + 720.0)),
            1e-9,
        )
        assertEquals(
            BarrelRotationPolicy.fromRadians(rad(-45.0)),
            BarrelRotationPolicy.fromRadians(rad(-45.0 - 360.0)),
            1e-9,
        )
    }

    @Test
    fun `非有限值按无旋转输入处理`() {
        assertEquals(0.0, BarrelRotationPolicy.fromRadians(Double.NaN), 0.0)
        assertEquals(0.0, BarrelRotationPolicy.fromRadians(Double.POSITIVE_INFINITY), 0.0)
        assertEquals(0.0, BarrelRotationPolicy.fromRadians(Float.NaN), 0.0)
    }

    @Test
    fun `归一化结果落在 左开右闭 区间`() {
        for (deg in listOf(-720.0, -180.0, 0.0, 180.0, 359.0, 360.0, 721.0)) {
            val v = BarrelRotationPolicy.normalizeDegrees(deg)
            assertEquals("deg=$deg", true, v > -180.0 && v <= 180.0)
        }
        assertEquals(180.0, BarrelRotationPolicy.normalizeDegrees(-180.0), 1e-9)
    }
}
