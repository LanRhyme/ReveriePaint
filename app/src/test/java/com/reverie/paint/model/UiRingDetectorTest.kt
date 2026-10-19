/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import org.junit.Assert.assertEquals
import org.junit.Test

class UiRingDetectorTest {

    @Test
    fun `point inside panel is contact`() {
        val tracker = trackerWith(UiPanelSlot.QUICK_ACTION, 100f, 100f, 200f, 200f)

        assertEquals(UiRingState.CONTACT, tracker.ringState(150f, 150f, 0f, 64f, 4f))
    }

    @Test
    fun `point on the contact boundary is contact`() {
        val tracker = trackerWith(UiPanelSlot.QUICK_ACTION, 100f, 100f, 200f, 200f)

        // 距右边界正好 4px
        assertEquals(UiRingState.CONTACT, tracker.ringState(204f, 150f, 0f, 64f, 4f))
        // 距右边界 4.01px 进入外圈
        assertEquals(UiRingState.WARN, tracker.ringState(204.01f, 150f, 0f, 64f, 4f))
    }

    @Test
    fun `point on the warn boundary is warn`() {
        val tracker = trackerWith(UiPanelSlot.QUICK_ACTION, 100f, 100f, 200f, 200f)

        assertEquals(UiRingState.WARN, tracker.ringState(264f, 150f, 0f, 64f, 4f))
        assertEquals(UiRingState.NONE, tracker.ringState(264.01f, 150f, 0f, 64f, 4f))
    }

    @Test
    fun `point outside both rings is none`() {
        val tracker = trackerWith(UiPanelSlot.QUICK_ACTION, 100f, 100f, 200f, 200f)

        assertEquals(UiRingState.NONE, tracker.ringState(500f, 500f, 0f, 64f, 4f))
    }

    @Test
    fun `brush radius expands both rings`() {
        val tracker = trackerWith(UiPanelSlot.QUICK_ACTION, 100f, 100f, 200f, 200f)

        // brush=20, contact=4 → contactR=24；距边界 24px 仍是 contact
        assertEquals(UiRingState.CONTACT, tracker.ringState(224f, 150f, 20f, 64f, 4f))
        // warnR=84；距边界 84px 仍是 warn
        assertEquals(UiRingState.WARN, tracker.ringState(284f, 150f, 20f, 64f, 4f))
        assertEquals(UiRingState.NONE, tracker.ringState(284.01f, 150f, 20f, 64f, 4f))
    }

    @Test
    fun `eraser override larger brush radius is reflected`() {
        val tracker = trackerWith(UiPanelSlot.QUICK_ACTION, 100f, 100f, 200f, 200f)

        // 模拟 eraser 的有效尺寸更大: brush=60 → contactR=64
        assertEquals(UiRingState.CONTACT, tracker.ringState(260f, 150f, 60f, 64f, 4f))
        // 普通笔 brush=10 → contactR=14，同一点是 WARN
        assertEquals(UiRingState.WARN, tracker.ringState(260f, 150f, 10f, 64f, 4f))
    }

    @Test
    fun `contact wins over warn across multiple slots`() {
        val tracker = UiPanelOcclusionTracker()
        tracker.update(UiPanelSlot.QUICK_ACTION, tracker.nextToken(), 0f, 0f, 100f, 100f)
        tracker.update(UiPanelSlot.QUICK_BRUSH, tracker.nextToken(), 300f, 0f, 400f, 100f)

        // 点距 A 右边界 10px（WARN），同时在 B 内部（CONTACT）
        assertEquals(UiRingState.CONTACT, tracker.ringState(350f, 50f, 0f, 64f, 4f))
    }

    @Test
    fun `unclipped slot is ignored`() {
        val tracker = trackerWith(UiPanelSlot.QUICK_ACTION, 100f, 100f, 200f, 200f)
        tracker.setUnclipped(UiPanelSlot.QUICK_ACTION, true)

        assertEquals(UiRingState.NONE, tracker.ringState(150f, 150f, 0f, 64f, 4f))
    }

    @Test
    fun `cleared slot is ignored`() {
        val tracker = UiPanelOcclusionTracker()
        val token = tracker.nextToken()
        tracker.update(UiPanelSlot.QUICK_ACTION, token, 100f, 100f, 200f, 200f)
        tracker.clear(UiPanelSlot.QUICK_ACTION, token)

        assertEquals(UiRingState.NONE, tracker.ringState(150f, 150f, 0f, 64f, 4f))
    }

    private fun trackerWith(
        slot: UiPanelSlot,
        l: Float,
        t: Float,
        r: Float,
        b: Float,
    ): UiPanelOcclusionTracker = UiPanelOcclusionTracker().also {
        it.update(slot, it.nextToken(), l, t, r, b)
    }
}
