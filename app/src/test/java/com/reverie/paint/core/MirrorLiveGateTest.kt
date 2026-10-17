package com.reverie.paint.core

import com.reverie.paint.core.MirrorLiveGate.EngineResult.ACCEPTED
import com.reverie.paint.core.MirrorLiveGate.EngineResult.REJECTED
import com.reverie.paint.core.MirrorLiveGate.EngineResult.STALE
import com.reverie.paint.core.MirrorLiveGate.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MirrorLiveGateTest {

    @Test
    fun offByDefaultAndForNonSymmetricStroke() {
        val g = MirrorLiveGate()
        assertEquals(State.OFF, g.state)
        assertFalse(g.isQueueing)
        g.begin(1, symmetryActive = false)
        assertFalse(g.isQueueing)
        assertFalse(g.onEngineResult(1, ACCEPTED))
        assertFalse(g.resolveAtPenUp())
    }

    @Test
    fun acceptedBatchGoesLiveAndPenUpSkipsReplay() {
        val g = MirrorLiveGate()
        g.begin(1, true)
        assertTrue(g.isQueueing)
        assertFalse(g.isLive)
        assertTrue(g.onEngineResult(1, ACCEPTED))
        assertTrue(g.isLive)
        assertTrue(g.resolveAtPenUp())
        // 收尾补点在抬笔后仍要送进引擎
        assertTrue(g.isQueueing)
        assertTrue(g.onEngineResult(1, ACCEPTED))
    }

    @Test
    fun rejectedFirstBatchFallsBackToReplay() {
        val g = MirrorLiveGate()
        g.begin(1, true)
        assertFalse(g.onEngineResult(1, REJECTED))
        assertEquals(State.FALLBACK, g.state)
        assertFalse(g.isQueueing)
        assertFalse(g.resolveAtPenUp())
    }

    @Test
    fun liveNeverFallsBackEvenIfEngineLaterRejects() {
        val g = MirrorLiveGate()
        g.begin(1, true)
        g.onEngineResult(1, ACCEPTED)
        assertTrue(g.onEngineResult(1, REJECTED))
        assertTrue(g.isLive)
        assertTrue(g.resolveAtPenUp())
    }

    @Test
    fun staleResultLeavesStateAndStopsSending() {
        val g = MirrorLiveGate()
        g.begin(1, true)
        assertFalse(g.onEngineResult(1, STALE))
        assertEquals(State.PENDING, g.state)
        g.onEngineResult(1, ACCEPTED)
        assertFalse(g.onEngineResult(1, STALE))
        assertEquals(State.LIVE, g.state)
    }

    @Test
    fun penUpBeforeEngineConfirmsChoosesReplayAndStopsQueue() {
        val g = MirrorLiveGate()
        g.begin(1, true)
        assertFalse(g.resolveAtPenUp())
        assertEquals(State.FALLBACK, g.state)
        assertFalse(g.isQueueing)
        // 抬笔之后 drain 不会再送墨
        assertFalse(g.onEngineResult(1, ACCEPTED))
        assertFalse(g.isLive)
    }

    @Test
    fun cancelStopsQueueAndNextStrokeResets() {
        val g = MirrorLiveGate()
        g.begin(1, true)
        g.onEngineResult(1, ACCEPTED)
        g.cancel()
        assertFalse(g.isQueueing)
        assertFalse(g.isLive)
        g.begin(2, true)
        assertEquals(State.PENDING, g.state)
        assertEquals(2, g.currentSeq)
    }

    /** F1: 快速连笔。上一笔 (1) 已 LIVE 且还没在渲染线程收尾, 下一笔 (2) 已落笔。 */
    @Test
    fun rapidRestrokePreviousStrokeResultDoesNotLeakIntoNext() {
        val g = MirrorLiveGate()
        g.begin(1, true)
        assertTrue(g.onEngineResult(1, ACCEPTED))
        assertTrue(g.resolveAtPenUp())
        g.begin(2, true)
        // 新笔不继承上一笔的 LIVE (旧实现里会被误标为已实时)
        assertEquals(State.PENDING, g.state)
        assertFalse(g.isLive)
        // 上一笔的状态仍可查, 它的收尾批照常按 LIVE 送
        assertEquals(State.LIVE, g.stateOf(1))
        assertTrue(g.onEngineResult(1, ACCEPTED))
        // 上一笔的应答不改下一笔
        assertEquals(State.PENDING, g.stateOf(2))
        // 下一笔被引擎拒绝 -> FALLBACK, 抬笔重放 (不丢墨)
        assertFalse(g.onEngineResult(2, REJECTED))
        assertEquals(State.FALLBACK, g.state)
        assertFalse(g.resolveAtPenUp())
        // 上一笔仍是 LIVE (不会被下一笔的拒绝拉回重放)
        assertEquals(State.LIVE, g.stateOf(1))
    }

    @Test
    fun rapidRestrokePreviousRejectDoesNotFallBackNext() {
        val g = MirrorLiveGate()
        g.begin(1, true)
        g.begin(2, true)
        assertFalse(g.onEngineResult(1, REJECTED))
        assertEquals(State.FALLBACK, g.stateOf(1))
        assertEquals(State.PENDING, g.state)
        assertTrue(g.onEngineResult(2, ACCEPTED))
        assertTrue(g.resolveAtPenUp())
    }

    @Test
    fun failedTouchStartKeepsGateOff() {
        val g = MirrorLiveGate()
        g.begin(1, true)
        g.onEngineResult(1, ACCEPTED)
        // touchStart 开头先 begin(seq, false), 图层检查拒绝后直接返回
        g.begin(2, false)
        assertFalse(g.isQueueing)
        assertFalse(g.isLive)
        assertEquals(State.OFF, g.stateOf(2))
    }

    @Test
    fun historyIsBoundedAndOldStrokesReadOff() {
        val g = MirrorLiveGate()
        for (s in 1..20) {
            g.begin(s, true)
            g.onEngineResult(s, ACCEPTED)
        }
        assertEquals(State.LIVE, g.stateOf(19))
        assertEquals(State.OFF, g.stateOf(1))
        assertEquals(State.OFF, g.stateOf(999))
    }

    /** s13-C5: 笔画中撤销/重做 (clearPendingStrokeSamples -> abort) 后抬笔不得重放镜像。 */
    @Test
    fun abortMidStrokeBlocksReplayAndStopsQueue() {
        val g = MirrorLiveGate()
        g.begin(1, true)
        assertTrue(g.onEngineResult(1, ACCEPTED))
        assertFalse(g.isAborted)
        g.abort()
        assertTrue(g.isAborted)
        assertFalse(g.isQueueing)
        assertFalse(g.isLive)
        // 抬笔: 不是实时 (resolve=false), 但已中止 -> 调用方不重放
        assertFalse(g.resolveAtPenUp())
        assertTrue(g.isAborted)
        // 迟到的引擎应答不会把中止的笔拉回 LIVE
        assertFalse(g.onEngineResult(1, ACCEPTED))
        assertFalse(g.isLive)
    }

    @Test
    fun abortWhilePendingAlsoBlocksReplay() {
        val g = MirrorLiveGate()
        g.begin(1, true)
        g.abort()
        assertFalse(g.resolveAtPenUp())
        assertTrue(g.isAborted)
    }

    @Test
    fun nextStrokeClearsAbort() {
        val g = MirrorLiveGate()
        g.begin(1, true)
        g.abort()
        g.begin(2, false)
        assertFalse(g.isAborted)
        g.begin(2, true)
        assertFalse(g.isAborted)
        assertEquals(State.PENDING, g.state)
        // 上一笔被中止的状态留在历史里是 OFF
        assertEquals(State.OFF, g.stateOf(1))
    }

    @Test
    fun plainCancelDoesNotMarkAborted() {
        val g = MirrorLiveGate()
        g.begin(1, true)
        g.cancel()
        assertFalse(g.isAborted)
    }

    @Test
    fun penUpWaitsForInFlightDrain() {
        val g = MirrorLiveGate()
        g.begin(1, true)
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val drain = Thread {
            g.withLock {
                entered.countDown()
                release.await()
                g.onEngineResult(1, ACCEPTED)
            }
        }
        drain.start()
        entered.await()
        var penUp: Boolean? = null
        val ui = Thread { penUp = g.resolveAtPenUp() }
        ui.start()
        Thread.sleep(50)
        // drain 持锁时抬笔判定必须等待, 否则会在墨已画下时误判为重放
        assertEquals(null, penUp)
        release.countDown()
        drain.join(2000)
        ui.join(2000)
        assertEquals(true, penUp)
    }
}
