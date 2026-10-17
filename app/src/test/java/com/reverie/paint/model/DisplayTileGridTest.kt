/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 显示瓦片网格的纯几何。
 *
 * 这几个断言锁住的是"每帧只重传脏区瓦片"的前提: 网格划分、边角余数、边界命中数。一旦
 * 划错, 表现是画布边缘少一条或每帧多传一列瓦片 (带宽回归), 都不会抛异常, 所以必须单测。
 */
class DisplayTileGridTest {

    private fun grid4k() = DisplayTileGrid(3840, 2160)

    @Test
    fun `4k canvas splits into 40 tiles`() {
        val g = grid4k()
        assertEquals(8, g.cols)
        assertEquals(5, g.rows)
        assertEquals(40, g.tileCount)
    }

    @Test
    fun `tile grid covers exactly the document with no gaps or overlap`() {
        val g = grid4k()
        var pixels = 0L
        for (ty in 0 until g.rows) {
            for (tx in 0 until g.cols) {
                pixels += g.tileWidth(tx).toLong() * g.tileHeight(ty)
            }
        }
        assertEquals(3840L * 2160L, pixels)
    }

    @Test
    fun `corner tile is the remainder size`() {
        val g = grid4k()
        // 末列: 3840 - 7*512 = 256; 末行: 2160 - 4*512 = 112
        assertEquals(256, g.tileWidth(7))
        assertEquals(112, g.tileHeight(4))
        val rect = g.tileRect(7, 4)
        assertEquals(3584, rect[0])
        assertEquals(2048, rect[1])
        assertEquals(3840, rect[2])
        assertEquals(2160, rect[3])
    }

    @Test
    fun `interior tile keeps the full tile size`() {
        val g = grid4k()
        assertEquals(512, g.tileWidth(0))
        assertEquals(512, g.tileHeight(0))
        val rect = g.tileRect(1, 2)
        assertEquals(512, rect[0])
        assertEquals(1024, rect[1])
        assertEquals(1024, rect[2])
        assertEquals(1536, rect[3])
    }

    @Test
    fun `a small dab inside one cell hits exactly one tile`() {
        val g = grid4k()
        val out = IntArray(g.tileCount * 2)
        val n = g.tilesIntersecting(100, 100, 110, 110, out)
        assertEquals(1, n)
        assertEquals(0, out[0])
        assertEquals(0, out[1])
    }

    @Test
    fun `a dab straddling a tile boundary hits two tiles`() {
        val g = grid4k()
        val out = IntArray(g.tileCount * 2)
        // 横跨 x = 512 的竖缝
        val n = g.tilesIntersecting(500, 100, 520, 110, out)
        assertEquals(2, n)
        assertEquals(0, out[0])
        assertEquals(0, out[1])
        assertEquals(1, out[2])
        assertEquals(0, out[3])
    }

    @Test
    fun `a dab straddling both boundaries hits four tiles`() {
        val g = grid4k()
        val out = IntArray(g.tileCount * 2)
        val n = g.tilesIntersecting(500, 500, 520, 520, out)
        assertEquals(4, n)
    }

    @Test
    fun `a rect flush against a tile edge does not spill into the next tile`() {
        val g = grid4k()
        val out = IntArray(g.tileCount * 2)
        // [0, 512) 正好是第一列的整列, 不该命中第二列
        assertEquals(1, g.tilesIntersecting(0, 0, 512, 512, out))
    }

    @Test
    fun `a rect outside the document hits nothing`() {
        val g = grid4k()
        val out = IntArray(g.tileCount * 2)
        assertEquals(0, g.tilesIntersecting(-200, -200, -10, -10, out))
        assertEquals(0, g.tilesIntersecting(3900, 0, 4000, 100, out))
        assertEquals(0, g.tilesIntersecting(0, 2200, 100, 2300, out))
        assertEquals(0, g.tilesIntersecting(100, 100, 100, 200, out))
    }

    @Test
    fun `a partially out of bounds rect is clipped to the document`() {
        val g = grid4k()
        val out = IntArray(g.tileCount * 2)
        // 越过右下角: 只该命中角落那一张
        val n = g.tilesIntersecting(3800, 2100, 4000, 2300, out)
        assertEquals(1, n)
        assertEquals(7, out[0])
        assertEquals(4, out[1])
    }

    @Test
    fun `a full document rect hits every tile`() {
        val g = grid4k()
        val out = IntArray(g.tileCount * 2)
        assertEquals(40, g.tilesIntersecting(0, 0, 3840, 2160, out))
    }

    @Test
    fun `a non multiple document still covers every pixel`() {
        val g = DisplayTileGrid(1000, 700)
        assertEquals(2, g.cols)
        assertEquals(2, g.rows)
        assertEquals(4, g.tileCount)
        assertEquals(488, g.tileWidth(1))
        assertEquals(188, g.tileHeight(1))
        val out = IntArray(g.tileCount * 2)
        assertEquals(4, g.tilesIntersecting(0, 0, 1000, 700, out))
    }

    @Test
    fun `an empty document has no tiles and no hits`() {
        val g = DisplayTileGrid(0, 0)
        assertTrue(g.isEmpty)
        assertEquals(0, g.tileCount)
        assertEquals(0, g.tilesIntersecting(0, 0, 10, 10, IntArray(8)))
        assertFalse(g.contains(0, 0))
    }

    @Test
    fun `tileIndexAt maps pixels to their tile and rejects out of range`() {
        val g = grid4k()
        assertEquals(0, g.tileIndexAt(0, 0))
        assertEquals(1, g.tileIndexAt(512, 0))
        assertEquals(g.tileIndex(7, 4), g.tileIndexAt(3839, 2159))
        assertEquals(-1, g.tileIndexAt(3840, 0))
        assertEquals(-1, g.tileIndexAt(-1, 5))
    }

    @Test
    fun `tile path is used only for 1to1 rendering of normal sized canvases`() {
        assertTrue(DisplayTileGrid.shouldUse(3840, 2160, 3840, 2160, false, false))
        // 缩放回退
        assertFalse(DisplayTileGrid.shouldUse(4096, 2048, 8192, 4096, false, false))
        // 动画播放 / 回放页
        assertFalse(DisplayTileGrid.shouldUse(3840, 2160, 3840, 2160, true, false))
        assertFalse(DisplayTileGrid.shouldUse(3840, 2160, 3840, 2160, false, true))
        // 超过 100MB 的整图仍走 2048 分块路径
        assertFalse(DisplayTileGrid.shouldUse(6000, 6000, 6000, 6000, false, false))
        assertFalse(DisplayTileGrid.shouldUse(0, 0, 0, 0, false, false))
    }
}
