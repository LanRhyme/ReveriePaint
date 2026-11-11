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

    // ==================== 时间轴轨道编排测试 ====================

    private data class DummyLayer(
        val index: Int,
        val name: String,
        val depth: Int,
        val isGroup: Boolean,
    )

    @Test
    fun `扁平图层按视觉堆叠从顶到底排列`() {
        // 自底向上输入: index 0 底层, index 2 顶层
        val layers = listOf(
            DummyLayer(0, "背景", 0, false),
            DummyLayer(1, "图层 1", 0, false),
            DummyLayer(2, "图层 2", 0, false),
        )
        val tracks = AnimationLayerOps.buildTimelineTracks(
            allLayers = layers,
            depthOf = { it.depth },
            isGroupOf = { it.isGroup },
            nameOf = { it.name },
            collapsedNames = emptySet(),
        )
        assertEquals(listOf("图层 2", "图层 1", "背景"), tracks.map { it.name })
    }

    @Test
    fun `图层组作为容器组头优先展示, 子图层紧随其下`() {
        // 自底向上:
        // 0: 背景 (d=0)
        // 1: 组 1 (d=0, isGroup)
        // 2: 子层 A (d=1)
        // 3: 子层 B (d=1)
        // 4: 顶层 (d=0)
        val layers = listOf(
            DummyLayer(0, "背景", 0, false),
            DummyLayer(1, "组 1", 0, true),
            DummyLayer(2, "子层 A", 1, false),
            DummyLayer(3, "子层 B", 1, false),
            DummyLayer(4, "顶层", 0, false),
        )
        val tracks = AnimationLayerOps.buildTimelineTracks(
            allLayers = layers,
            depthOf = { it.depth },
            isGroupOf = { it.isGroup },
            nameOf = { it.name },
            collapsedNames = emptySet(),
        )
        // 预期输出: 顶层 -> 组 1 -> 子层 B -> 子层 A -> 背景
        assertEquals(listOf("顶层", "组 1", "子层 B", "子层 A", "背景"), tracks.map { it.name })
    }

    @Test
    fun `空图层组被自动过滤, 不占时间轴轨道`() {
        // 组 1 内部没有任何非组图层
        val layers = listOf(
            DummyLayer(0, "背景", 0, false),
            DummyLayer(1, "空组", 0, true),
            DummyLayer(2, "图层 1", 0, false),
        )
        val tracks = AnimationLayerOps.buildTimelineTracks(
            allLayers = layers,
            depthOf = { it.depth },
            isGroupOf = { it.isGroup },
            nameOf = { it.name },
            collapsedNames = emptySet(),
        )
        assertEquals(listOf("图层 1", "背景"), tracks.map { it.name })
    }

    @Test
    fun `仅包含空子组的父组同样被视为整体空组过滤`() {
        val layers = listOf(
            DummyLayer(0, "背景", 0, false),
            DummyLayer(1, "空父组", 0, true),
            DummyLayer(2, "空子组", 1, true),
            DummyLayer(3, "图层 1", 0, false),
        )
        val tracks = AnimationLayerOps.buildTimelineTracks(
            allLayers = layers,
            depthOf = { it.depth },
            isGroupOf = { it.isGroup },
            nameOf = { it.name },
            collapsedNames = emptySet(),
        )
        assertEquals(listOf("图层 1", "背景"), tracks.map { it.name })
    }

    @Test
    fun `折叠的图层组保留组头, 子图层不占行`() {
        val layers = listOf(
            DummyLayer(0, "背景", 0, false),
            DummyLayer(1, "角色组", 0, true),
            DummyLayer(2, "线稿", 1, false),
            DummyLayer(3, "上色", 1, false),
            DummyLayer(4, "前景", 0, false),
        )
        val tracks = AnimationLayerOps.buildTimelineTracks(
            allLayers = layers,
            depthOf = { it.depth },
            isGroupOf = { it.isGroup },
            nameOf = { it.name },
            collapsedNames = setOf("角色组"),
        )
        assertEquals(listOf("前景", "角色组", "背景"), tracks.map { it.name })
    }

    @Test
    fun `findChildLayers 准确提取组内全部非组后代`() {
        val layers = listOf(
            DummyLayer(0, "背景", 0, false),
            DummyLayer(1, "角色组", 0, true),
            DummyLayer(2, "身体子组", 1, true),
            DummyLayer(3, "躯干", 2, false),
            DummyLayer(4, "头部", 2, false),
            DummyLayer(5, "特效", 1, false),
            DummyLayer(6, "顶层", 0, false),
        )
        val roleChildren = AnimationLayerOps.findChildLayers(
            allLayers = layers,
            groupIndex = 1,
            depthOf = { it.depth },
            isGroupOf = { it.isGroup },
        )
        assertEquals(listOf("躯干", "头部", "特效"), roleChildren.map { it.name })

        val bodyChildren = AnimationLayerOps.findChildLayers(
            allLayers = layers,
            groupIndex = 2,
            depthOf = { it.depth },
            isGroupOf = { it.isGroup },
        )
        assertEquals(listOf("躯干", "头部"), bodyChildren.map { it.name })
    }

    @Test
    fun `查找活动关键帧能够正确命中当前或之前的最近关键帧`() {
        val times = listOf(0, 5, 10, 20)
        assertEquals(0, AnimationLayerOps.findActiveKeyframe(times, 0))
        assertEquals(0, AnimationLayerOps.findActiveKeyframe(times, 3))
        assertEquals(5, AnimationLayerOps.findActiveKeyframe(times, 5))
        assertEquals(5, AnimationLayerOps.findActiveKeyframe(times, 9))
        assertEquals(10, AnimationLayerOps.findActiveKeyframe(times, 10))
        assertEquals(20, AnimationLayerOps.findActiveKeyframe(times, 35))
        assertEquals(null, AnimationLayerOps.findActiveKeyframe(times, -1))
        assertEquals(null, AnimationLayerOps.findActiveKeyframe(emptyList(), 5))
    }
}

