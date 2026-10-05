/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 动画前景 / 背景帧 的纯逻辑测试。
 *
 * 这里覆盖的是"判定"部分 —— 真机验证只能确认某一次操作的结果, 判定分支是否
 * 走对了得靠这些用例。
 */
class AnimationLayerOpsTest {

    // ==================== 资源命名空间 ====================

    @Test
    fun `标记资源被识别, 普通音频不被误判`() {
        assertTrue(AnimationLayerOps.isMarkerAsset("reverie.anim.bg"))
        assertTrue(AnimationLayerOps.isMarkerAsset("reverie.anim.fg"))
        assertFalse(AnimationLayerOps.isMarkerAsset("song.mp3"))
        assertFalse(AnimationLayerOps.isMarkerAsset("reverie"))
        assertFalse(AnimationLayerOps.isMarkerAsset(""))
    }

    @Test
    fun `音频列表必须滤掉标记, 但一个音频都不能漏`() {
        val names = listOf(
            "song.mp3",
            AnimationLayerOps.ASSET_BACKGROUND,
            "voice.wav",
            AnimationLayerOps.ASSET_FOREGROUND,
        )
        assertEquals(listOf("song.mp3", "voice.wav"), AnimationLayerOps.audioAssetsOf(names))
    }

    @Test
    fun `没有标记时音频列表原样保留顺序`() {
        val names = listOf("a.mp3", "b.wav", "c.m4a")
        assertEquals(names, AnimationLayerOps.audioAssetsOf(names))
    }

    /**
     * 这条是回归护栏: QMap 按名排序, `reverie.anim.bg` 会排在多数音频文件名之前。
     * 现有代码取的是 assets.first()，不过滤的话真音频会被标记顶掉。
     */
    @Test
    fun `标记排在音频之前时音频仍然取得到`() {
        val sorted = listOf(
            AnimationLayerOps.ASSET_BACKGROUND,
            AnimationLayerOps.ASSET_FOREGROUND,
            "zebra.mp3",
        )
        assertEquals("zebra.mp3", AnimationLayerOps.audioAssetsOf(sorted).first())
    }

    // ==================== 标记值编解码 ====================

    @Test
    fun `标记下标编解码往返一致`() {
        for (i in listOf(0, 1, 7, 999)) {
            assertEquals(i, AnimationLayerOps.decodeMarkerIndex(AnimationLayerOps.encodeMarkerIndex(i)))
        }
    }

    @Test
    fun `损坏或缺失的标记值一律当作没有标记`() {
        assertEquals(-1, AnimationLayerOps.decodeMarkerIndex(null))
        assertEquals(-1, AnimationLayerOps.decodeMarkerIndex(""))
        assertEquals(-1, AnimationLayerOps.decodeMarkerIndex("   "))
        assertEquals(-1, AnimationLayerOps.decodeMarkerIndex("abc"))
        assertEquals(-1, AnimationLayerOps.decodeMarkerIndex("-1"))
        assertEquals(-1, AnimationLayerOps.decodeMarkerIndex("3.5"))
    }

    /**
     * 清标记写的是空数组, 不能写 -1 —— encodeMarkerIndex 会把负数夹成 0,
     * 于是"清除"反而会造出一个指向第 0 层的背景标记。这条守住那个坑。
     */
    @Test
    fun `清除标记写空数组后回读必须是无效值`() {
        assertEquals(-1, AnimationLayerOps.decodeMarkerIndex(ByteArray(0).decodeToString()))
    }

    // ==================== 图层 id 解析 ====================

    @Test
    fun `图层 id 越界返回无图层而不是抛异常`() {
        val ids = listOf(100L, 200L, 300L)
        assertEquals(100L, AnimationLayerOps.layerIdAt(ids, 0))
        assertEquals(300L, AnimationLayerOps.layerIdAt(ids, 2))
        assertEquals(AnimationLayerOps.NO_LAYER, AnimationLayerOps.layerIdAt(ids, -1))
        assertEquals(AnimationLayerOps.NO_LAYER, AnimationLayerOps.layerIdAt(ids, 3))
        assertEquals(AnimationLayerOps.NO_LAYER, AnimationLayerOps.layerIdAt(emptyList(), 0))
    }

    @Test
    fun `图层被删除后按 id 反查得到 -1`() {
        val ids = listOf(100L, 200L, 300L)
        assertEquals(1, AnimationLayerOps.indexOfLayerId(ids, 200L))
        assertEquals(-1, AnimationLayerOps.indexOfLayerId(ids, 999L))
    }

    @Test
    fun `无图层 id 不参与反查`() {
        val ids = listOf(100L, 200L)
        assertEquals(-1, AnimationLayerOps.indexOfLayerId(ids, AnimationLayerOps.NO_LAYER))
    }
}
