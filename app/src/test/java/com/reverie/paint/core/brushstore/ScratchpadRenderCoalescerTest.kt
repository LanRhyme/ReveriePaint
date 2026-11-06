/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.reverie.paint.core.brushstore

import com.reverie.paint.ui.painting.brush.ScratchpadRenderCoalescer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 试画板渲染合帧门闩的纯 JVM 单测: 用注入的 fake postFrame/enqueueRender 驱动,
 * 不依赖 Android (Choreographer/引擎)。
 */
class ScratchpadRenderCoalescerTest {

    /** 收集 postFrame 回调与入队的渲染完成回调, 手动驱动帧与渲染完成 */
    private class FakeHost {
        val postedFrames = ArrayList<() -> Unit>()
        val enqueuedRenders = ArrayList<() -> Unit>()

        val coalescer = ScratchpadRenderCoalescer(
            postFrame = { block -> postedFrames.add(block) },
            enqueueRender = { onDone -> enqueuedRenders.add(onDone) },
        )

        /** 触发所有已挂起的 VSync 回调 (一帧) */
        fun fireFrame() {
            val blocks = postedFrames.toList()
            postedFrames.clear()
            blocks.forEach { it() }
        }

        /** 完成所有在途渲染 (onDone) */
        fun completeRender() {
            val dones = enqueuedRenders.toList()
            enqueuedRenders.clear()
            dones.forEach { it() }
        }
    }

    @Test
    fun `同帧多次 requestRender 合帧为一次 postFrame 与一次 enqueueRender`() {
        val host = FakeHost()
        repeat(10) { host.coalescer.requestRender() }
        assertEquals(1, host.postedFrames.size)
        assertEquals(0, host.enqueuedRenders.size)

        host.fireFrame()
        assertEquals(1, host.enqueuedRenders.size)

        host.completeRender()
        // 渲染完成后无新脏标记, 不再补挂
        assertEquals(0, host.postedFrames.size)
    }

    @Test
    fun `在途期间丢弃积压请求 onDone 后补挂恰好一次`() {
        val host = FakeHost()
        host.coalescer.requestRender()
        host.fireFrame()
        assertEquals(1, host.enqueuedRenders.size)

        // 渲染在途期间再来 5 次请求: 不调度新帧, 积压丢弃
        repeat(5) { host.coalescer.requestRender() }
        assertEquals(0, host.postedFrames.size)
        host.fireFrame()
        assertEquals(1, host.enqueuedRenders.size)

        // onDone 后按积压脏标记补挂恰好一次
        host.completeRender()
        assertEquals(1, host.postedFrames.size)

        host.fireFrame()
        // completeRender 已消费第一批, 这里是补挂的那一次新入队
        assertEquals(1, host.enqueuedRenders.size)

        host.completeRender()
        assertEquals(0, host.postedFrames.size)
        assertEquals(0, host.enqueuedRenders.size)
    }

    @Test
    fun `onDirectRenderCompleted 后到来的 VSync 不再触发渲染`() {
        val host = FakeHost()
        host.coalescer.requestRender()
        // 抬笔/重放等同步渲染路径先取票据再消费掉待渲染标记
        val token = host.coalescer.newDirectRenderToken()
        host.coalescer.onDirectRenderCompleted(token)
        host.fireFrame()
        assertEquals(0, host.enqueuedRenders.size)
        assertEquals(0, host.postedFrames.size)

        // 直接渲染后紧接的新请求仍应正常合帧调度 (门闩未被卡死)
        host.coalescer.requestRender()
        assertEquals(1, host.postedFrames.size)
        host.fireFrame()
        assertEquals(1, host.enqueuedRenders.size)
        host.completeRender()
        assertTrue(host.postedFrames.isEmpty())
    }

    @Test
    fun `直接渲染入队后 after 执行前到来的新 requestRender 不被吞`() {
        val host = FakeHost()
        // 先有一批笔画中的合帧请求
        host.coalescer.requestRender()
        // 直接渲染 (抬笔) 入队时取票据
        val token = host.coalescer.newDirectRenderToken()
        // 入队后、after 执行前, 抬笔后立刻落笔的新请求到来 (代际 +1)
        host.coalescer.requestRender()
        // 旧票据不得清掉新请求的脏标记
        host.coalescer.onDirectRenderCompleted(token)
        host.fireFrame()
        assertEquals(1, host.enqueuedRenders.size)

        host.completeRender()
        // 补挂逻辑不受影响
        assertEquals(0, host.postedFrames.size)
    }

    @Test
    fun `dispose 后不再调度任何帧或渲染`() {
        val host = FakeHost()
        host.coalescer.requestRender()
        assertEquals(1, host.postedFrames.size)

        host.coalescer.dispose()
        host.fireFrame()
        assertEquals(0, host.enqueuedRenders.size)
        assertEquals(0, host.postedFrames.size)

        // dispose 后的新请求与在途 onDone 均不再触发调度
        host.coalescer.requestRender()
        assertEquals(0, host.postedFrames.size)
        host.completeRender()
        assertEquals(0, host.postedFrames.size)
        assertEquals(0, host.enqueuedRenders.size)
    }
}
