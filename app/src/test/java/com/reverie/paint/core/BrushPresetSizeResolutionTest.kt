/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 守护"选中预设时笔刷尺寸"的取值规则。
 *
 * 真实故障：导入 ABR 后点开每一支新笔刷，尺寸都停在最小的 1，每次用都得重新调。
 * 根因是 Krita 的 `paintOpSize()` 用
 * `KIS_SAFE_ASSERT_RECOVER_RETURN_VALUE(this->brush(), 1.0)` —— 一旦预设里的笔刷
 * 没解析出来（`KisBrush::fromXML` 的兜底 auto_brush 默认 diameter 恰好也是 1.0），
 * 引擎会确定性回报 1.0；而 1.0 能让 `size > 0` 的兜底判断通过，于是 UI 就显示成 1。
 *
 * 这个文件钉死"引擎给哨兵值时用记忆值补回、其余情况一律尊重引擎"这条边界。
 */
class BrushPresetSizeResolutionTest {

    @Test
    fun `引擎回报哨兵值 1 时用记忆中的尺寸补回`() {
        assertEquals(
            "导入笔刷的尺寸被引擎报成 1 时必须回落到该预设记录的尺寸",
            50.0,
            resolvePresetSize(engineSize = 1.0, savedSize = 50.0),
            0.0,
        )
    }

    @Test
    fun `引擎给出正常尺寸时原样采用`() {
        assertEquals(282.0, resolvePresetSize(282.0, 50.0), 0.0)
        assertEquals(1.5, resolvePresetSize(1.5, 50.0), 0.0)
    }

    @Test
    fun `用户自己把尺寸调成 1 时不得被覆盖`() {
        // 记忆值 <= 1 说明"1"就是用户要的，不是哨兵值造成的假象
        assertEquals(1.0, resolvePresetSize(1.0, 1.0), 0.0)
        assertEquals(1.0, resolvePresetSize(1.0, 0.5), 0.0)
    }

    @Test
    fun `内置预设没有记忆值时行为完全不变`() {
        assertEquals(1.0, resolvePresetSize(1.0, null), 0.0)
        assertEquals(20.0, resolvePresetSize(20.0, null), 0.0)
    }

    @Test
    fun `非有限值不得流入引擎`() {
        assertEquals("记忆值是 NaN 时不能采用", 1.0, resolvePresetSize(1.0, Double.NaN), 0.0)
        assertEquals(
            "记忆值是无穷大时不能采用",
            1.0,
            resolvePresetSize(1.0, Double.POSITIVE_INFINITY),
            0.0,
        )
        assertEquals("引擎给 NaN 且有可信记忆值时用记忆值补回", 50.0, resolvePresetSize(Double.NaN, 50.0), 0.0)
    }
}
