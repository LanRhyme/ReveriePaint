/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 选区叠加层分辨率预算。
 *
 * 锁住的是"叠加层不按文档尺寸要像素": 6400x10240 按文档尺寸就是三份 250 MB
 * (C++ 缓冲 / JNI IntArray / Kotlin 位图), 真机实测一次套索让 Native Heap 涨 1.1 GB。
 */
class SelectionOverlayPolicyTest {
    @Test
    fun `65MP document is capped to the budget and keeps its aspect ratio`() {
        val (w, h) = SelectionOverlayPolicy.overlaySize(6400, 10240)
        assertTrue("像素必须落在预算内", w.toLong() * h <= SelectionOverlayPolicy.MAX_OVERLAY_PX)
        // 长宽比保持 (6400:10240 = 1:1.6)
        val aspect = h.toDouble() / w
        assertEquals(1.6, aspect, 0.02)
        // 不低于 1.4MP (别为了省内存把边界糊成一团)
        assertTrue(w.toLong() * h >= 1_400_000L)
    }

    @Test
    fun `small documents are never upscaled`() {
        assertEquals(1920 to 1080, SelectionOverlayPolicy.overlaySize(1920, 1080))
        assertEquals(1080 to 1920, SelectionOverlayPolicy.overlaySize(1080, 1920))
        assertEquals(2 to 2, SelectionOverlayPolicy.overlaySize(2, 2))
    }

    @Test
    fun `square, extreme and budget overrides behave`() {
        val (sqW, sqH) = SelectionOverlayPolicy.overlaySize(4096, 4096)
        assertEquals(sqW, sqH)
        assertTrue(sqW.toLong() * sqH <= SelectionOverlayPolicy.MAX_OVERLAY_PX)
        // 极端长条: 每边至少 2 像素
        val (w2, h2) = SelectionOverlayPolicy.overlaySize(100, 20000)
        assertTrue(w2 >= 2 && h2 >= 2)
        // 自定义预算 (低内存设备可以更省)
        val (w3, h3) = SelectionOverlayPolicy.overlaySize(6400, 10240, maxPx = 500_000)
        assertTrue(w3.toLong() * h3 <= 500_000L)
    }

    @Test
    fun `invalid documents fall back to one pixel`() {
        assertEquals(1 to 1, SelectionOverlayPolicy.overlaySize(0, 100))
        assertEquals(1 to 1, SelectionOverlayPolicy.overlaySize(100, -5))
    }
}
