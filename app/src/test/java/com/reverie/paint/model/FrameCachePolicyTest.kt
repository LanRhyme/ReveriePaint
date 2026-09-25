/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [FrameCachePolicy] 的预算换算与淘汰顺序。
 *
 * 这段策略决定"大画幅播放会不会把堆吃光"以及"循环播放时先丢哪一帧",
 * 一旦回归会表现为播放卡顿（缓存永远不命中）或内存触顶。
 */
class FrameCachePolicyTest {

    private val mb = 1024L * 1024L

    @Test
    fun `预算取堆的十六分之一`() {
        // 4GB 堆 -> 256MB 但被上限夹住
        assertEquals(FrameCachePolicy.MAX_BUDGET_BYTES, FrameCachePolicy.budgetBytes(4096L * mb))
        // 1GB 堆 -> 64MB
        assertEquals(64L * mb, FrameCachePolicy.budgetBytes(1024L * mb))
    }

    @Test
    fun `预算夹在下限与上限之间`() {
        assertEquals(FrameCachePolicy.MIN_BUDGET_BYTES, FrameCachePolicy.budgetBytes(64L * mb))
        assertEquals(FrameCachePolicy.MIN_BUDGET_BYTES, FrameCachePolicy.budgetBytes(0L))
        assertEquals(FrameCachePolicy.MIN_BUDGET_BYTES, FrameCachePolicy.budgetBytes(-1L))
        assertEquals(FrameCachePolicy.MAX_BUDGET_BYTES, FrameCachePolicy.budgetBytes(1L shl 40))
    }

    @Test
    fun `未超预算且未超帧数时不淘汰`() {
        val frames = mapOf(0 to 4L * mb, 1 to 4L * mb, 2 to 4L * mb)
        assertTrue(FrameCachePolicy.framesToEvict(frames, 64L * mb, keepFrame = 1).isEmpty())
    }

    @Test
    fun `超预算时按距当前帧最远优先淘汰`() {
        // 每帧 10MB 共 40MB, 预算 25MB: 距离当前帧 5 最远的是 0 (距离 5),
        // 其次是 3 (距离 2), 丢到 20MB 才落回预算内
        val frames = mapOf(3 to 10L * mb, 4 to 10L * mb, 5 to 10L * mb, 0 to 10L * mb)
        val evicted = FrameCachePolicy.framesToEvict(frames, 25L * mb, keepFrame = 5)
        assertEquals(listOf(0, 3), evicted)
    }

    @Test
    fun `淘汰到预算内即停止`() {
        val frames = (0 until 6).associateWith { 10L * mb }
        val evicted = FrameCachePolicy.framesToEvict(frames, 30L * mb, keepFrame = 2)
        assertEquals(3, evicted.size)
        // 最近的 1/2/3 必须保留, 淘汰的是 0/4/5
        assertTrue(evicted.contains(0))
        assertTrue(evicted.contains(5))
        assertTrue(!evicted.contains(2))
    }

    @Test
    fun `帧数上限独立于字节预算生效`() {
        val frames = (0 until 6).associateWith { 1L }
        val evicted = FrameCachePolicy.framesToEvict(frames, 1024L * mb, keepFrame = 0, maxFrames = 4)
        assertEquals(2, evicted.size)
        // 最远的 5 与 4 先丢
        assertEquals(listOf(5, 4), evicted)
    }

    @Test
    fun `空缓存没有任何淘汰项`() {
        assertTrue(FrameCachePolicy.framesToEvict(emptyMap(), 64L * mb, keepFrame = 0).isEmpty())
    }
}
