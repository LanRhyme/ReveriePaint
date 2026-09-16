/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class TimelineReorderHelperTest {

    @Test
    fun `drag to the right accurately lands at target slot without off-by-one`() {
        // 4 帧，每帧 1 拍 (times: 0, 1, 2, 3)
        val times = listOf(0, 1, 2, 3)
        val frameW = 100f

        // 将第 0 帧向右拖动 2 格 (dragDx = +200px)
        val layout = TimelineReorderHelper.computeDragLayout(times, fromTime = 0, dragDx = 200f, frameW = frameW)
        // 候选插槽应为 2 (落在第 2 帧原位)
        assertEquals(2, layout.targetSlot)
        assertEquals(2, layout.targetStartFrame)
        // 其余帧避让：原 1 移到 0，原 2 移到 1，原 3 保持在 3
        assertEquals(0, layout.blockTargetFrames[1])
        assertEquals(1, layout.blockTargetFrames[2])
        assertEquals(3, layout.blockTargetFrames[3])

        // 执行推挤重排
        val result = TimelineReorderHelper.computeReorderedTimes(times, fromTime = 0, targetSlot = 2)
        assertNotNull(result)
        val res = result!!
        // 移动块落地起始时间必为 2，而非提前一格的 1
        assertEquals(2, res.landingTime)
        assertEquals(listOf(0, 1, 2, 3), res.newTimes)
    }

    @Test
    fun `drag to the left accurately lands at target slot without drift`() {
        val times = listOf(0, 1, 2, 3)
        val frameW = 100f

        // 将第 3 帧向左拖动 2 格 (dragDx = -200px)
        val layout = TimelineReorderHelper.computeDragLayout(times, fromTime = 3, dragDx = -200f, frameW = frameW)
        assertEquals(1, layout.targetSlot)
        assertEquals(1, layout.targetStartFrame)
        assertEquals(0, layout.blockTargetFrames[0])
        assertEquals(2, layout.blockTargetFrames[1])
        assertEquals(3, layout.blockTargetFrames[2])

        val res = TimelineReorderHelper.computeReorderedTimes(times, fromTime = 3, targetSlot = 1)!!
        assertEquals(1, res.landingTime)
        assertEquals(listOf(0, 1, 2, 3), res.newTimes)
    }

    @Test
    fun `reorder with variable spans keeps contiguous sequence`() {
        // times: 0 (span 2), 2 (span 3), 5 (span 1)
        val times = listOf(0, 2, 5)
        val frameW = 100f

        // 将第 0 帧 (span 2) 向右拖动 3 帧 (dragDx = +300px)
        val layout = TimelineReorderHelper.computeDragLayout(times, fromTime = 0, dragDx = 300f, frameW = frameW)
        assertEquals(1, layout.targetSlot)
        assertEquals(3, layout.targetStartFrame) // 前面是原帧 2 (span 3)，所以从 3 开始
        assertEquals(0, layout.blockTargetFrames[2]) // 原帧 2 移到 0
        assertEquals(5, layout.blockTargetFrames[5]) // 原帧 5 移到 3 + 2 = 5

        val res = TimelineReorderHelper.computeReorderedTimes(times, fromTime = 0, targetSlot = 1)!!
        assertEquals(3, res.landingTime)
        assertEquals(listOf(0, 3, 5), res.newTimes)
        assertEquals(1, res.newLastHold)
    }

    @Test
    fun `drag unselected middle frame left and right accurately reorders`() {
        // times: 0, 1, 2, 3
        val times = listOf(0, 1, 2, 3)
        val frameW = 100f

        // 拖拽未选中的第 2 帧向左 1 格 (dragDx = -100px)
        val layoutLeft = TimelineReorderHelper.computeDragLayout(times, fromTime = 2, dragDx = -100f, frameW = frameW)
        assertEquals(1, layoutLeft.targetSlot)
        assertEquals(1, layoutLeft.targetStartFrame)
        val resLeft = TimelineReorderHelper.computeReorderedTimes(times, fromTime = 2, targetSlot = 1)!!
        assertEquals(1, resLeft.landingTime)
        assertEquals(listOf(0, 1, 2, 3), resLeft.newTimes)
        assertEquals(listOf(0, 2, 1, 3), resLeft.oldTimes)

        // 拖拽未选中的第 1 帧向右 2 格 (dragDx = +200px)
        val layoutRight = TimelineReorderHelper.computeDragLayout(times, fromTime = 1, dragDx = 200f, frameW = frameW)
        assertEquals(3, layoutRight.targetSlot)
        assertEquals(3, layoutRight.targetStartFrame)
        val resRight = TimelineReorderHelper.computeReorderedTimes(times, fromTime = 1, targetSlot = 3)!!
        assertEquals(3, resRight.landingTime)
        assertEquals(listOf(0, 1, 2, 3), resRight.newTimes)
        assertEquals(listOf(0, 2, 3, 1), resRight.oldTimes)
    }

    @Test
    fun `reorder preserves lastFrameHold correctly when tail frame changes`() {
        // times: 0 (span 2), 2 (span 3), 5 (span 4), lastHold = 4
        val times = listOf(0, 2, 5)
        val lastHold = 4

        // 将末尾帧 (fromTime = 5, span = 4) 移动到最前端 (slot 0)
        // 此时新序列为: [5 (span 4), 0 (span 2), 2 (span 3)]
        // 原第 2 帧 (span 3) 成为新的末尾帧，其 hold 时长应正确更新为 3
        val resFront = TimelineReorderHelper.computeReorderedTimes(times, fromTime = 5, targetSlot = 0, lastHold = lastHold)!!
        assertEquals(0, resFront.landingTime)
        assertEquals(listOf(0, 4, 6), resFront.newTimes)
        assertEquals(3, resFront.newLastHold)

        // 将首帧 (fromTime = 0, span = 2) 移动到末尾 (slot 2)
        // 此时新序列为: [2 (span 3), 5 (span 4), 0 (span 2)]
        // 原第 0 帧 (span 2) 成为新的末尾帧，其 hold 时长应正确更新为 2
        val resBack = TimelineReorderHelper.computeReorderedTimes(times, fromTime = 0, targetSlot = 2, lastHold = lastHold)!!
        assertEquals(7, resBack.landingTime)
        assertEquals(listOf(0, 3, 7), resBack.newTimes)
        assertEquals(2, resBack.newLastHold)
    }
}
