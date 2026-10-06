/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「帧转图层」的判定逻辑测试。
 *
 * 这个功能的风险在**引擎调度**上(见 FramesToLayersOps 类注释), 纯逻辑这层
 * 守的是"不该拆的时候拦住、该拆的时候别漏"。
 */
class FramesToLayersOpsTest {

    @Test
    fun `选中的帧会去重并按升序排列`() {
        val p = FramesToLayersOps.plan(
            srcLayer = 3,
            times = listOf(5, 1, 5, 0, 1),
            keyframeTimes = listOf(0, 1, 5),
            animatable = true,
            locked = false,
        ) as FramesToLayersOps.Plan.Ready
        assertEquals(listOf(0, 1, 5), p.times)
        // 源轨道下标必须原样带出去, 不能被写死成 0 —— 0 是背景层, 取不到画面
        assertEquals(3, p.srcLayer)
    }

    /**
     * 回归护栏: 引擎报的 keyframeTimes 可能有重复(理论上不该), 但我们不能假设。
     * 去重必须发生, 否则同一帧会被拆成两个图层。
     */
    @Test
    fun `引擎报的帧号有重复时也不会拆出两层`() {
        val p = FramesToLayersOps.plan(
            srcLayer = 3,
            times = listOf(0, 1, 2),
            keyframeTimes = listOf(0, 1, 1, 2, 2),
            animatable = true,
            locked = false,
        ) as FramesToLayersOps.Plan.Ready
        assertEquals(listOf(0, 1, 2), p.times)
    }

    @Test
    fun `选中的帧在源轨道上不存在时被剔除`() {
        val p = FramesToLayersOps.plan(
            srcLayer = 3,
            times = listOf(0, 3, 7),
            keyframeTimes = listOf(0, 5),
            animatable = true,
            locked = false,
        ) as FramesToLayersOps.Plan.Ready
        // 3 与 7 不存在, 只剩 0
        assertEquals(listOf(0), p.times)
    }

    @Test
    fun `一个有效帧都没有时拒绝`() {
        val p = FramesToLayersOps.plan(
            srcLayer = 3,
            times = listOf(3, 7),
            keyframeTimes = listOf(0, 5),
            animatable = true,
            locked = false,
        ) as FramesToLayersOps.Plan.Refused
        assertEquals(FramesToLayersOps.Reject.NO_FRAMES, p.reason)
    }

    @Test
    fun `负数帧号被当成没选, 不进计划`() {
        val p = FramesToLayersOps.plan(
            srcLayer = 3,
            times = listOf(-1, 0, -5),
            keyframeTimes = listOf(0, 1),
            animatable = true,
            locked = false,
        ) as FramesToLayersOps.Plan.Ready
        assertEquals(listOf(0), p.times)
    }

    @Test
    fun `超过上限时拒绝并报出第几帧越界`() {
        val many = (0..FramesToLayersOps.MAX_OUTPUT_LAYERS).toList() // 多一个
        val p = FramesToLayersOps.plan(
            srcLayer = 3,
            times = many,
            keyframeTimes = many,
            animatable = true,
            locked = false,
        ) as FramesToLayersOps.Plan.Refused
        assertEquals(FramesToLayersOps.Reject.TOO_MANY, p.reason)
        assertEquals(FramesToLayersOps.MAX_OUTPUT_LAYERS, p.offender)
    }

    @Test
    fun `恰好等于上限时放行`() {
        val exact = (0 until FramesToLayersOps.MAX_OUTPUT_LAYERS).toList()
        val p = FramesToLayersOps.plan(3, exact, exact, animatable = true, locked = false)
        assertTrue(p is FramesToLayersOps.Plan.Ready)
    }

    @Test
    fun `组或调整层取不到画面, 锁定层也拆不了`() {
        val notAnimatable = FramesToLayersOps.plan(3, listOf(0), listOf(0), animatable = false, locked = false)
        assertEquals(
            FramesToLayersOps.Reject.SOURCE_NOT_ANIMATABLE,
            (notAnimatable as FramesToLayersOps.Plan.Refused).reason,
        )
        val locked = FramesToLayersOps.plan(3, listOf(0), listOf(0), animatable = true, locked = true)
        assertEquals(
            FramesToLayersOps.Reject.SOURCE_LOCKED,
            (locked as FramesToLayersOps.Plan.Refused).reason,
        )
    }

    /**
     * 判定顺序: 帧的问题优先于图层的问题。
     * 用户选了一堆不存在的帧时, 告诉他"帧无效"比告诉他"层锁定了"更贴近他要修的东西。
     */
    @Test
    fun `帧无效时先报帧, 哪怕图层也不可拆`() {
        val p = FramesToLayersOps.plan(3, listOf(9), listOf(0), animatable = false, locked = true)
        assertEquals(
            FramesToLayersOps.Reject.NO_FRAMES,
            (p as FramesToLayersOps.Plan.Refused).reason,
        )
    }

    /**
     * 图层名补零是为了让图层面板按字典序 = 帧序; 这条守住格式不塌。
     */
    @Test
    fun `图层名两位补零, 超过 99 不补`() {
        assertEquals("帧 00", FramesToLayersOps.layerName(0))
        assertEquals("帧 09", FramesToLayersOps.layerName(9))
        assertEquals("帧 42", FramesToLayersOps.layerName(42))
        assertEquals("帧 100", FramesToLayersOps.layerName(100))
    }

    /**
     * 回归护栏(2026-10-05 真机踩到): UI 上"不选任何帧"就是"拆全部"的意思,
     * 之前把空集当成"没选帧"直接拒绝, 用户点菜单永远得到"没有可拆的帧"。
     */
    @Test
    fun `空集表示整条轨道全拆, 不是拒绝`() {
        val p = FramesToLayersOps.plan(
            srcLayer = 2,
            times = emptyList(),
            keyframeTimes = listOf(0, 3, 7),
            animatable = true,
            locked = false,
        ) as FramesToLayersOps.Plan.Ready
        assertEquals(listOf(0, 3, 7), p.times)
        assertEquals(2, p.srcLayer)
    }

    /** 但引擎一个关键帧都没有时, 空集也不能变成"拆 0 个" —— 那是错的手感。 */
    @Test
    fun `轨道本身没有关键帧时仍然拒绝`() {
        val p = FramesToLayersOps.plan(
            srcLayer = 1,
            times = emptyList(),
            keyframeTimes = emptyList(),
            animatable = true,
            locked = false,
        ) as FramesToLayersOps.Plan.Refused
        assertEquals(FramesToLayersOps.Reject.NO_FRAMES, p.reason)
    }

    // ==================== 编排层的推进判据 ====================
    // 这一节是 2026-10-05 连出 bug 之后补的: 「什么时候收尾」原先写在
    // ViewModel 的链里, 改错一次就只拆出第一帧, 而纯判定那边测不到它。

    @Test
    fun `最后一环之后收尾而不是继续`() {
        assertEquals(FramesToLayersOps.Next.FINISH, FramesToLayersOps.nextOf(2, 3, false))
        assertEquals(FramesToLayersOps.Next.CONTINUE, FramesToLayersOps.nextOf(1, 3, false))
        assertEquals(FramesToLayersOps.Next.CONTINUE, FramesToLayersOps.nextOf(0, 3, false))
    }

    @Test
    fun `单环的任务第一环就收尾`() {
        assertEquals(FramesToLayersOps.Next.FINISH, FramesToLayersOps.nextOf(0, 1, false))
    }

    /**
     * 回归护栏: 失败优先于"是不是最后一环"。
     * 先判 index 的话, 最后一环失败会被当成正常收尾, 用户就看不到"中途失败"的提示。
     */
    @Test
    fun `失败时一律中止, 哪怕正好是最后一环`() {
        assertEquals(FramesToLayersOps.Next.ABORT, FramesToLayersOps.nextOf(2, 3, true))
        assertEquals(FramesToLayersOps.Next.ABORT, FramesToLayersOps.nextOf(0, 1, true))
        assertEquals(FramesToLayersOps.Next.ABORT, FramesToLayersOps.nextOf(0, 5, true))
    }

    /**
     * 回归护栏: 收尾只在链尾触发一次。
     * 原实现把收尾放在"入口之后的 mainHandler.post"里, 与链毫无同步关系 ——
     * 结果进度条一闪就没、只拆出第一帧、提示还报"已拆出 1 个"。
     * 这条钉住"index 走满 total 时恰好 FINISH 一次"。
     */
    @Test
    fun `链恰好推进 total 次, 不多不少一次收尾`() {
        val total = 5
        var finishes = 0
        for (i in 0 until total) {
            if (FramesToLayersOps.nextOf(i, total, false) == FramesToLayersOps.Next.FINISH) finishes++
        }
        assertEquals(1, finishes)
    }

    @Test
    fun `全部完成才报完成, 少一个就是中止`() {
        assertTrue(FramesToLayersOps.isAllDone(3, 3))
        assertTrue(FramesToLayersOps.isAllDone(4, 3))
        assertFalse(FramesToLayersOps.isAllDone(2, 3))
        assertFalse(FramesToLayersOps.isAllDone(0, 3))
    }

    // ==================== 时间轴折叠：从组结构推导 ====================
    // 这一节替代了原先"记一组图层 id"的做法: 真机日志证明 layerId() 给不出
    // 互不相同的值 (collapse ids=... setSize=1), 按 id 匹配那条路走不通。

    @Test
    fun `折叠的组其子层不再占行, 遇到同深度就停`() {
        // 背景(0) / 组(0) / 组内两层(1,1) / 颜料层(0)
        val got = FramesToLayersOps.hiddenIndices(
            indices = listOf(0, 1, 2, 3, 4),
            depths = listOf(0, 0, 1, 1, 0),
            names = listOf("背景", "帧转图层 1", "帧 00", "帧 01", "颜料图层 1"),
            isGroup = listOf(false, true, false, false, false),
            collapsedNames = setOf("帧转图层 1"),
        )
        assertEquals(setOf(2, 3), got)
    }

    @Test
    fun `展开状态下不隐藏任何行`() {
        val got = FramesToLayersOps.hiddenIndices(
            indices = listOf(0, 1, 2),
            depths = listOf(0, 0, 1),
            names = listOf("背景", "组", "帧 00"),
            isGroup = listOf(false, true, false),
            collapsedNames = emptySet(),
        )
        assertEquals(emptySet<Int>(), got)
    }

    /**
     * 回归护栏: **普通图层即使用着和组一样的名字也不该被折叠** ——
     * 只靠名字匹配会把用户自己改名的图层连带藏掉。
     */
    @Test
    fun `同名但不是组的不参与折叠`() {
        val got = FramesToLayersOps.hiddenIndices(
            indices = listOf(0, 1, 2, 3),
            depths = listOf(0, 0, 1, 1),
            names = listOf("背景", "帧转图层 1", "帧转图层 1", "颜料"),
            isGroup = listOf(false, false, false, false),
            collapsedNames = setOf("帧转图层 1"),
        )
        assertEquals(emptySet<Int>(), got)
    }

    @Test
    fun `多个组同时折叠时各自隐藏自己的子层`() {
        val got = FramesToLayersOps.hiddenIndices(
            indices = listOf(0, 1, 2, 3, 4, 5),
            depths = listOf(0, 0, 1, 0, 1, 0),
            names = listOf("背景", "组A", "帧 00", "组B", "帧 05", "颜料"),
            isGroup = listOf(false, true, false, true, false, false),
            collapsedNames = setOf("组A", "组B"),
        )
        assertEquals(setOf(2, 4), got)
    }

    /**
     * 嵌套: 外层组折叠时, 内层组及其子层一起隐藏 ——
     * 不能因为内层没在折叠集合里就把它露出来。
     */
    @Test
    fun `外层折叠会连内层组一起隐藏`() {
        val got = FramesToLayersOps.hiddenIndices(
            indices = listOf(0, 1, 2, 3, 4),
            depths = listOf(0, 0, 1, 2, 2),
            names = listOf("背景", "外层", "内层", "帧 00", "帧 01"),
            isGroup = listOf(false, true, true, false, false),
            collapsedNames = setOf("外层"),
        )
        assertEquals(setOf(2, 3, 4), got)
    }
}
