package com.reverie.paint.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MirrorSampleQueueTest {

    private fun pt(x: Float) = floatArrayOf(x, x + 0.5f, 1f, 0f, 0f, 0f, x / 100f)

    private fun xs(a: FloatArray?): List<Float> =
        a?.let { arr -> (0 until arr.size / 7).map { arr[it * 7] } } ?: emptyList()

    @Test
    fun takeReturnsOnlyRequestedStrokeAndExactSize() {
        val q = MirrorSampleQueue(2, 16)
        q.push(0, 1, pt(1f))
        q.push(0, 1, pt(2f))
        val out = q.take(0, 1, null)
        assertEquals(14, out!!.size)
        assertArrayEquals(pt(1f) + pt(2f), out, 0f)
        assertEquals(0, q.size(0))
        assertNull(q.take(0, 1, null))
    }

    /** F1: 上一笔 (1) 收尾 drain 时下一笔 (2) 的点已入队: 只取 1, 2 原样保留。 */
    @Test
    fun rapidRestrokeKeepsNextStrokeSamples() {
        val q = MirrorSampleQueue(2, 16)
        q.push(0, 1, pt(1f))
        q.push(1, 1, pt(10f))
        q.push(0, 2, pt(3f))
        q.push(0, 2, pt(4f))
        q.push(1, 2, pt(11f))
        assertEquals(listOf(1f), xs(q.take(0, 1, null)))
        assertEquals(listOf(10f), xs(q.take(1, 1, null)))
        assertEquals(2, q.size(0))
        assertEquals(1, q.size(1))
        // 下一笔打开后才送, 顺序不变
        assertEquals(listOf(3f, 4f), xs(q.take(0, 2, null)))
        assertEquals(listOf(11f), xs(q.take(1, 2, null)))
    }

    @Test
    fun takeDropsOlderStaleSamples() {
        val q = MirrorSampleQueue(1, 16)
        q.push(0, 1, pt(1f))
        q.push(0, 2, pt(2f))
        q.push(0, 3, pt(3f))
        assertEquals(listOf(2f), xs(q.take(0, 2, null)))
        // 1 被丢弃, 3 保留
        assertEquals(1, q.size(0))
        assertEquals(listOf(3f), xs(q.take(0, 3, null)))
    }

    @Test
    fun takeWithNoMatchStillCompactsAndReturnsNull() {
        val q = MirrorSampleQueue(1, 16)
        q.push(0, 1, pt(1f))
        q.push(0, 3, pt(3f))
        assertNull(q.take(0, 2, null))
        assertEquals(1, q.size(0))
        assertEquals(listOf(3f), xs(q.take(0, 3, null)))
    }

    @Test
    fun discardUpToKeepsNewerStrokes() {
        val q = MirrorSampleQueue(2, 16)
        q.push(0, 1, pt(1f))
        q.push(0, 2, pt(2f))
        q.push(1, 2, pt(5f))
        q.push(1, 3, pt(6f))
        q.discardUpTo(2)
        assertEquals(0, q.size(0))
        assertEquals(listOf(6f), xs(q.take(1, 3, null)))
    }

    @Test
    fun reuseBufferWhenSizeMatches() {
        val q = MirrorSampleQueue(1, 16)
        q.push(0, 1, pt(1f))
        val first = q.take(0, 1, null)!!
        q.push(0, 1, pt(2f))
        val second = q.take(0, 1, first)
        assertSame(first, second)
        assertEquals(listOf(2f), xs(second))
    }

    @Test
    fun fullQueueDropsNewestAndCounts() {
        val q = MirrorSampleQueue(1, 2)
        assertTrue(q.push(0, 1, pt(1f)))
        assertTrue(q.push(0, 1, pt(2f)))
        assertFalse(q.push(0, 1, pt(3f)))
        assertEquals(1L, q.droppedCount)
        assertEquals(listOf(1f, 2f), xs(q.take(0, 1, null)))
        assertTrue(q.push(0, 1, pt(4f)))
    }

    @Test
    fun outOfRangeBranchIsIgnored() {
        val q = MirrorSampleQueue(2, 4)
        assertFalse(q.push(-1, 1, pt(1f)))
        assertFalse(q.push(2, 1, pt(1f)))
        assertNull(q.take(5, 1, null))
        assertEquals(0, q.size(9))
    }

    @Test
    fun clearEmptiesAllBranches() {
        val q = MirrorSampleQueue(2, 4)
        q.push(0, 1, pt(1f))
        q.push(1, 2, pt(2f))
        q.clear()
        assertEquals(0, q.size(0))
        assertEquals(0, q.size(1))
    }
}
