/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 变换预览位图预算。
 *
 * 锁住的是"预览位图不能按文档尺寸要像素": 6400x10240 上 250 MB 位图会被 HWUI 拒绝绘制
 * (真机实测直接崩), 且按文档坐标画框的交互并不需要这个分辨率。
 */
class TransformPreviewPolicyTest {
    @Test
    fun `65MP document is capped to 4096 long side`() {
        val (w, h) = TransformPreviewPolicy.previewSize(6400, 10240)
        assertEquals(4096, h)
        assertEquals(2560, w)
        // 42 MB 量级, 远离 HWUI 单次绘制上限
        assertTrue(w.toLong() * h * 4L <= 64L * 1024 * 1024)
        // 长宽比保持
        assertEquals(1.6, h.toDouble() / w, 0.01)
    }

    @Test
    fun `small documents keep their size`() {
        assertEquals(2048 to 2048, TransformPreviewPolicy.previewSize(2048, 2048))
        assertEquals(3392 to 2400, TransformPreviewPolicy.previewSize(3392, 2400))
        assertEquals(4096 to 4096, TransformPreviewPolicy.previewSize(4096, 4096))
    }

    @Test
    fun `wide and extreme canvases are capped on the long side only`() {
        val (w, h) = TransformPreviewPolicy.previewSize(20000, 1000)
        assertEquals(4096, w)
        assertEquals(204, h)
        val custom = TransformPreviewPolicy.previewSize(6400, 10240, maxDim = 1024)
        assertEquals(1024, custom.second)
    }

    @Test
    fun `invalid sizes fall back to one pixel`() {
        assertEquals(1 to 1, TransformPreviewPolicy.previewSize(0, 100))
        assertEquals(1 to 1, TransformPreviewPolicy.previewSize(100, -1))
    }
}
