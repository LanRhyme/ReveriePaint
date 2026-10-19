/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UiPanelOcclusionTrackerTest {

    @Test
    fun `all 21 slots can be registered updated and cleared`() {
        val tracker = UiPanelOcclusionTracker()
        assertEquals(21, UiPanelSlot.COUNT)

        for (slot in UiPanelSlot.entries) {
            val token = tracker.nextToken()
            assertTrue(tracker.update(slot, token, 1f, 2f, 3f, 4f))
            assertTrue(tracker.isActive(slot))
            assertEquals(token, tracker.ownerToken(slot))
            assertEquals(1f, tracker.left(slot))
            assertEquals(4f, tracker.bottom(slot))
        }
        assertEquals(21, tracker.activeCount())
        assertEquals(21, tracker.snapshot().count)

        for (slot in UiPanelSlot.entries) {
            assertTrue(tracker.clear(slot, tracker.ownerToken(slot)))
            assertFalse(tracker.isActive(slot))
        }
        assertEquals(0, tracker.activeCount())
        assertEquals(0, tracker.snapshot().count)
    }

    @Test
    fun `newer owner token takes over and older owner keeps only its own entry`() {
        val tracker = UiPanelOcclusionTracker()
        val oldToken = tracker.nextToken()
        assertTrue(tracker.update(UiPanelSlot.TOP_BAR, oldToken, 0f, 0f, 100f, 100f))

        val newToken = tracker.nextToken()
        // 新节点直接成为当前 owner（共享槽位在退出动画期间可能新旧并存）。
        assertTrue(tracker.update(UiPanelSlot.TOP_BAR, newToken, 0f, 0f, 200f, 200f))
        assertEquals(newToken, tracker.ownerToken(UiPanelSlot.TOP_BAR))
        assertEquals(200f, tracker.right(UiPanelSlot.TOP_BAR))

        // 旧 owner 迟到的 update 只改自己的 bounds，不影响当前 owner。
        assertTrue(tracker.update(UiPanelSlot.TOP_BAR, oldToken, 0f, 0f, 50f, 50f))
        assertEquals(newToken, tracker.ownerToken(UiPanelSlot.TOP_BAR))
        assertEquals(200f, tracker.right(UiPanelSlot.TOP_BAR))

        // 裁剪快照取两者并集，旧 owner 的可见区域也必须被裁掉。
        val snapshot = tracker.snapshot()
        assertEquals(1, snapshot.count)
        assertEquals(0f, snapshot.left(0))
        assertEquals(0f, snapshot.top(0))
        assertEquals(200f, snapshot.right(0))
        assertEquals(200f, snapshot.bottom(0))

        // 旧 owner 的 clear 只移除自己的条目，新 owner 保持当前。
        assertTrue(tracker.clear(UiPanelSlot.TOP_BAR, oldToken))
        assertTrue(tracker.isActive(UiPanelSlot.TOP_BAR))
        assertEquals(newToken, tracker.ownerToken(UiPanelSlot.TOP_BAR))
        assertEquals(200f, tracker.right(UiPanelSlot.TOP_BAR))
    }

    @Test
    fun `shared slot clip snapshot covers union of all visible owners`() {
        val tracker = UiPanelOcclusionTracker()
        val tokenA = tracker.nextToken()
        assertTrue(tracker.update(UiPanelSlot.TOP_HUD_TOAST, tokenA, 0f, 0f, 100f, 100f))
        val tokenB = tracker.nextToken()
        assertTrue(tracker.update(UiPanelSlot.TOP_HUD_TOAST, tokenB, 200f, 300f, 400f, 500f))

        // bounds() 仍只报告当前 owner B。
        val out = FloatArray(4)
        assertTrue(tracker.bounds(UiPanelSlot.TOP_HUD_TOAST, out))
        assertEquals(200f, out[0])
        assertEquals(300f, out[1])
        assertEquals(400f, out[2])
        assertEquals(500f, out[3])

        // snapshot() 只有一个矩形，覆盖 A 与 B 的并集。
        val snapshot = tracker.snapshot()
        assertEquals(1, snapshot.count)
        assertEquals(0f, snapshot.left(0))
        assertEquals(0f, snapshot.top(0))
        assertEquals(400f, snapshot.right(0))
        assertEquals(500f, snapshot.bottom(0))

        // 两个 owner 各自的可见区域都参与命中。
        assertTrue(tracker.hitTest(50f, 50f))
        assertTrue(tracker.hitTest(350f, 450f))
        assertTrue(tracker.intersects(90f, 90f, 95f, 95f))
        assertTrue(tracker.intersects(390f, 490f, 395f, 495f))

        // B 清除后并集回退到 A。
        assertTrue(tracker.clear(UiPanelSlot.TOP_HUD_TOAST, tokenB))
        val fallback = tracker.snapshot()
        assertEquals(1, fallback.count)
        assertEquals(0f, fallback.left(0))
        assertEquals(0f, fallback.top(0))
        assertEquals(100f, fallback.right(0))
        assertEquals(100f, fallback.bottom(0))
        assertTrue(tracker.hitTest(50f, 50f))
        assertFalse(tracker.hitTest(350f, 450f))
    }

    @Test
    fun `shared slot falls back to previous owner after current clears`() {
        val tracker = UiPanelOcclusionTracker()
        val tokenA = tracker.nextToken()
        assertTrue(tracker.update(UiPanelSlot.BOTTOM_TOOL_PANEL, tokenA, 0f, 0f, 100f, 100f))
        val tokenB = tracker.nextToken()
        assertTrue(tracker.update(UiPanelSlot.BOTTOM_TOOL_PANEL, tokenB, 200f, 200f, 300f, 300f))
        assertEquals(tokenB, tracker.ownerToken(UiPanelSlot.BOTTOM_TOOL_PANEL))
        assertEquals(200f, tracker.left(UiPanelSlot.BOTTOM_TOOL_PANEL))
        // 当前 owner 的 bounds 是 B，但裁剪快照覆盖 A+B 的并集。
        val union = tracker.snapshot()
        assertEquals(1, union.count)
        assertEquals(0f, union.left(0))
        assertEquals(0f, union.top(0))
        assertEquals(300f, union.right(0))
        assertEquals(300f, union.bottom(0))

        // 非当前 owner 的更新不改变当前 bounds。
        assertTrue(tracker.update(UiPanelSlot.BOTTOM_TOOL_PANEL, tokenA, 10f, 10f, 110f, 110f))
        assertEquals(tokenB, tracker.ownerToken(UiPanelSlot.BOTTOM_TOOL_PANEL))
        assertEquals(200f, tracker.left(UiPanelSlot.BOTTOM_TOOL_PANEL))

        // 当前 owner 清除后自动回退到 A，并恢复 A 最新的 bounds 与并集。
        assertTrue(tracker.clear(UiPanelSlot.BOTTOM_TOOL_PANEL, tokenB))
        assertEquals(tokenA, tracker.ownerToken(UiPanelSlot.BOTTOM_TOOL_PANEL))
        assertEquals(10f, tracker.left(UiPanelSlot.BOTTOM_TOOL_PANEL))
        assertEquals(110f, tracker.right(UiPanelSlot.BOTTOM_TOOL_PANEL))
        val fallbackUnion = tracker.snapshot()
        assertEquals(10f, fallbackUnion.left(0))
        assertEquals(10f, fallbackUnion.top(0))
        assertEquals(110f, fallbackUnion.right(0))
        assertEquals(110f, fallbackUnion.bottom(0))
        assertTrue(tracker.isActive(UiPanelSlot.BOTTOM_TOOL_PANEL))

        assertTrue(tracker.clear(UiPanelSlot.BOTTOM_TOOL_PANEL, tokenA))
        assertFalse(tracker.isActive(UiPanelSlot.BOTTOM_TOOL_PANEL))
        assertEquals(0f, tracker.left(UiPanelSlot.BOTTOM_TOOL_PANEL), 0f)
        assertEquals(0f, tracker.right(UiPanelSlot.BOTTOM_TOOL_PANEL), 0f)
    }

    @Test
    fun `clear of a non-current owner leaves current owner masks untouched`() {
        val tracker = UiPanelOcclusionTracker()
        val tokenA = tracker.nextToken()
        tracker.update(UiPanelSlot.TOP_HUD_TOAST, tokenA, 0f, 0f, 100f, 100f)
        val tokenB = tracker.nextToken()
        tracker.update(UiPanelSlot.TOP_HUD_TOAST, tokenB, 0f, 0f, 200f, 200f)

        tracker.setEvaded(UiPanelSlot.TOP_HUD_TOAST, true)
        tracker.setUnclipped(UiPanelSlot.TOP_HUD_TOAST, true)

        assertTrue(tracker.clear(UiPanelSlot.TOP_HUD_TOAST, tokenA))
        assertTrue(tracker.isEvaded(UiPanelSlot.TOP_HUD_TOAST))
        assertTrue(tracker.isUnclipped(UiPanelSlot.TOP_HUD_TOAST))
        assertEquals(tokenB, tracker.ownerToken(UiPanelSlot.TOP_HUD_TOAST))

        // 当前 owner 清除时才清掩码。
        assertTrue(tracker.clear(UiPanelSlot.TOP_HUD_TOAST, tokenB))
        assertFalse(tracker.isEvaded(UiPanelSlot.TOP_HUD_TOAST))
        assertFalse(tracker.isUnclipped(UiPanelSlot.TOP_HUD_TOAST))
    }

    @Test
    fun `clear removes evading and unclipped bits for the slot`() {
        val tracker = UiPanelOcclusionTracker()
        val token = tracker.nextToken()
        tracker.update(UiPanelSlot.QUICK_COLOR, token, 0f, 0f, 100f, 100f)
        tracker.setEvaded(UiPanelSlot.QUICK_COLOR, true)
        tracker.setUnclipped(UiPanelSlot.QUICK_COLOR, true)
        assertTrue(tracker.isEvaded(UiPanelSlot.QUICK_COLOR))
        assertTrue(tracker.isUnclipped(UiPanelSlot.QUICK_COLOR))

        assertTrue(tracker.clear(UiPanelSlot.QUICK_COLOR, token))
        assertFalse(tracker.isEvaded(UiPanelSlot.QUICK_COLOR))
        assertFalse(tracker.isUnclipped(UiPanelSlot.QUICK_COLOR))
        assertEquals(0, tracker.evadedMask)
        assertEquals(0, tracker.unclippedMask)
    }

    @Test
    fun `snapshot drops unclipped slots and has no stale tail`() {
        val tracker = UiPanelOcclusionTracker()
        val slots = listOf(
            UiPanelSlot.TOP_BAR,
            UiPanelSlot.TOOL_RAIL,
            UiPanelSlot.QUICK_ACTION,
            UiPanelSlot.QUICK_BRUSH,
        )
        slots.forEachIndexed { index, slot ->
            val token = tracker.nextToken()
            val l = index * 100f
            tracker.update(slot, token, l, 0f, l + 50f, 50f)
        }
        assertEquals(4, tracker.snapshot().count)

        tracker.setUnclipped(UiPanelSlot.TOOL_RAIL, true)
        val three = tracker.snapshot()
        assertEquals(3, three.count)
        assertFalse(three.contains(150f, 25f))
        assertTrue(three.contains(50f, 25f))

        // 清掉中间槽位后，快照不得残留旧项。
        tracker.clear(UiPanelSlot.QUICK_ACTION, tracker.ownerToken(UiPanelSlot.QUICK_ACTION))
        val two = tracker.snapshot()
        assertEquals(2, two.count)
        assertFalse(two.contains(250f, 25f))
        assertTrue(two.contains(50f, 25f))
        assertTrue(two.contains(350f, 25f))
    }

    @Test
    fun `version increments on bounds and mask changes but not on identical update`() {
        val tracker = UiPanelOcclusionTracker()
        val token = tracker.nextToken()
        val v0 = tracker.version
        assertTrue(tracker.update(UiPanelSlot.TOP_BAR, token, 0f, 0f, 100f, 100f))
        val v1 = tracker.version
        assertTrue(v1 > v0)

        assertFalse(tracker.update(UiPanelSlot.TOP_BAR, token, 0f, 0f, 100f, 100f))
        assertEquals(v1, tracker.version)

        tracker.setEvaded(UiPanelSlot.TOP_BAR, true)
        assertTrue(tracker.version > v1)
        val v2 = tracker.version
        tracker.setEvaded(UiPanelSlot.TOP_BAR, true)
        assertEquals(v2, tracker.version)

        tracker.clear(UiPanelSlot.TOP_BAR, token)
        assertTrue(tracker.version > v2)
    }

    @Test
    fun `invalid bounds are ignored`() {
        val tracker = UiPanelOcclusionTracker()
        val token = tracker.nextToken()

        assertFalse(tracker.update(UiPanelSlot.TOP_BAR, token, Float.NaN, 0f, 10f, 10f))
        assertFalse(tracker.update(UiPanelSlot.TOP_BAR, token, 0f, 0f, Float.POSITIVE_INFINITY, 10f))
        assertFalse(tracker.update(UiPanelSlot.TOP_BAR, token, 10f, 0f, 10f, 10f))
        assertFalse(tracker.update(UiPanelSlot.TOP_BAR, token, 0f, 10f, 10f, 10f))
        assertFalse(tracker.isActive(UiPanelSlot.TOP_BAR))
    }

    @Test
    fun `invalid bounds remove an existing owner and reactivation works`() {
        val tracker = UiPanelOcclusionTracker()
        val token = tracker.nextToken()
        assertTrue(tracker.update(UiPanelSlot.TOP_BAR, token, 0f, 0f, 100f, 100f))
        assertTrue(tracker.isActive(UiPanelSlot.TOP_BAR))
        var version = tracker.version

        // 零尺寸: 面板折叠, owner 被移除, 槽位失活。
        assertTrue(tracker.update(UiPanelSlot.TOP_BAR, token, 10f, 10f, 10f, 50f))
        assertFalse(tracker.isActive(UiPanelSlot.TOP_BAR))
        assertEquals(0, tracker.snapshot().count)
        assertTrue(tracker.version > version)

        // 同一 token 之后的有效 update 重新登记。
        assertTrue(tracker.update(UiPanelSlot.TOP_BAR, token, 1f, 2f, 30f, 40f))
        assertTrue(tracker.isActive(UiPanelSlot.TOP_BAR))
        assertEquals(1, tracker.snapshot().count)

        // NaN 同样移除。
        version = tracker.version
        assertTrue(tracker.update(UiPanelSlot.TOP_BAR, token, Float.NaN, 2f, 30f, 40f))
        assertFalse(tracker.isActive(UiPanelSlot.TOP_BAR))
        assertEquals(0, tracker.snapshot().count)
        assertTrue(tracker.version > version)

        // -Inf 同样移除。
        assertTrue(tracker.update(UiPanelSlot.TOP_BAR, token, 1f, 2f, 30f, 40f))
        version = tracker.version
        assertTrue(tracker.update(UiPanelSlot.TOP_BAR, token, 1f, Float.NEGATIVE_INFINITY, 30f, 40f))
        assertFalse(tracker.isActive(UiPanelSlot.TOP_BAR))
        assertEquals(0, tracker.snapshot().count)
        assertTrue(tracker.version > version)
    }

    @Test
    fun `invalid bounds removal of current owner falls back and clears masks`() {
        val tracker = UiPanelOcclusionTracker()
        val tokenA = tracker.nextToken()
        assertTrue(tracker.update(UiPanelSlot.TOP_HUD_TOAST, tokenA, 0f, 0f, 100f, 100f))
        val tokenB = tracker.nextToken()
        assertTrue(tracker.update(UiPanelSlot.TOP_HUD_TOAST, tokenB, 200f, 200f, 300f, 300f))
        tracker.setEvaded(UiPanelSlot.TOP_HUD_TOAST, true)
        tracker.setUnclipped(UiPanelSlot.TOP_HUD_TOAST, true)

        assertTrue(tracker.update(UiPanelSlot.TOP_HUD_TOAST, tokenB, Float.NaN, 0f, 10f, 10f))
        assertEquals(tokenA, tracker.ownerToken(UiPanelSlot.TOP_HUD_TOAST))
        assertFalse(tracker.isEvaded(UiPanelSlot.TOP_HUD_TOAST))
        assertFalse(tracker.isUnclipped(UiPanelSlot.TOP_HUD_TOAST))
        assertEquals(0, tracker.unclippedMask)

        val snapshot = tracker.snapshot()
        assertEquals(1, snapshot.count)
        assertEquals(0f, snapshot.left(0))
        assertEquals(100f, snapshot.right(0))
        assertTrue(tracker.hitTest(50f, 50f))
        assertFalse(tracker.hitTest(250f, 250f))
    }

    @Test
    fun `hitTest and intersects respect unclipped slots`() {
        val tracker = UiPanelOcclusionTracker()
        val token = tracker.nextToken()
        tracker.update(UiPanelSlot.QUICK_LAYER, token, 100f, 100f, 200f, 200f)

        assertTrue(tracker.hitTest(150f, 150f))
        assertTrue(tracker.intersects(180f, 180f, 300f, 300f))
        assertFalse(tracker.hitTest(250f, 250f))

        tracker.setUnclipped(UiPanelSlot.QUICK_LAYER, true)
        assertFalse(tracker.hitTest(150f, 150f))
        assertFalse(tracker.intersects(180f, 180f, 300f, 300f))
    }

    @Test
    fun `ringState returns contact inside and warn outside`() {
        val tracker = UiPanelOcclusionTracker()
        val token = tracker.nextToken()
        tracker.update(UiPanelSlot.QUICK_BRUSH, token, 100f, 100f, 200f, 200f)

        // 面板内部 = CONTACT
        assertEquals(UiRingState.CONTACT, tracker.ringState(150f, 150f, 0f, 64f, 4f))
        // 距右边界 5px，contactR = 0 + 4 → 外圈
        assertEquals(UiRingState.WARN, tracker.ringState(205f, 150f, 0f, 64f, 4f))
        // 距右边界 50px，warnR = 64 → WARN
        assertEquals(UiRingState.WARN, tracker.ringState(250f, 150f, 0f, 64f, 4f))
        // 距右边界 100px → NONE
        assertEquals(UiRingState.NONE, tracker.ringState(300f, 150f, 0f, 64f, 4f))
    }

    @Test
    fun `ringState adds brush radius to both rings`() {
        val tracker = UiPanelOcclusionTracker()
        val token = tracker.nextToken()
        tracker.update(UiPanelSlot.QUICK_BRUSH, token, 100f, 100f, 200f, 200f)

        // contactR = 20 + 4 = 24；距右边界 20px → CONTACT
        assertEquals(UiRingState.CONTACT, tracker.ringState(220f, 150f, 20f, 64f, 4f))
        // warnR = 20 + 64 = 84；距右边界 80px → WARN
        assertEquals(UiRingState.WARN, tracker.ringState(280f, 150f, 20f, 64f, 4f))
        // 距右边界 90px → NONE
        assertEquals(UiRingState.NONE, tracker.ringState(290f, 150f, 20f, 64f, 4f))
    }

    @Test
    fun `ringState prefers contact over warn across slots`() {
        val tracker = UiPanelOcclusionTracker()
        val tokenA = tracker.nextToken()
        tracker.update(UiPanelSlot.QUICK_ACTION, tokenA, 0f, 0f, 100f, 100f)
        val tokenB = tracker.nextToken()
        tracker.update(UiPanelSlot.QUICK_COLOR, tokenB, 100f, 0f, 200f, 100f)

        // 点在 B 内部（CONTACT），同时距 A 右边界 0px（也是 CONTACT）。
        assertEquals(UiRingState.CONTACT, tracker.ringState(100f, 50f, 0f, 64f, 4f))
        // 点在 A 外圈、远离 B。
        assertEquals(UiRingState.WARN, tracker.ringState(140f, 160f, 0f, 64f, 4f))
    }

    @Test
    fun `unclipped slots are ignored by ringState`() {
        val tracker = UiPanelOcclusionTracker()
        val token = tracker.nextToken()
        tracker.update(UiPanelSlot.QUICK_BRUSH, token, 100f, 100f, 200f, 200f)
        tracker.setUnclipped(UiPanelSlot.QUICK_BRUSH, true)

        assertEquals(UiRingState.NONE, tracker.ringState(150f, 150f, 0f, 64f, 4f))
    }

    @Test
    fun `claiming a cleared slot resets stale evasion bits`() {
        val tracker = UiPanelOcclusionTracker()
        val token = tracker.nextToken()
        tracker.update(UiPanelSlot.TOP_BAR, token, 0f, 0f, 100f, 100f)
        tracker.setEvaded(UiPanelSlot.TOP_BAR, true)
        tracker.setUnclipped(UiPanelSlot.TOP_BAR, true)
        tracker.clear(UiPanelSlot.TOP_BAR, token)

        val newToken = tracker.nextToken()
        tracker.update(UiPanelSlot.TOP_BAR, newToken, 10f, 10f, 110f, 110f)
        assertFalse(tracker.isEvaded(UiPanelSlot.TOP_BAR))
        assertFalse(tracker.isUnclipped(UiPanelSlot.TOP_BAR))
        assertEquals(newToken, tracker.ownerToken(UiPanelSlot.TOP_BAR))
    }

    @Test
    fun `bounds copies four floats only when active`() {
        val tracker = UiPanelOcclusionTracker()
        val token = tracker.nextToken()
        tracker.update(UiPanelSlot.SLIDER_RAIL, token, 10f, 20f, 30f, 40f)

        val out = FloatArray(4)
        assertTrue(tracker.bounds(UiPanelSlot.SLIDER_RAIL, out))
        assertEquals(10f, out[0])
        assertEquals(20f, out[1])
        assertEquals(30f, out[2])
        assertEquals(40f, out[3])

        tracker.clear(UiPanelSlot.SLIDER_RAIL, token)
        assertFalse(tracker.bounds(UiPanelSlot.SLIDER_RAIL, out))
    }

    @Test
    fun `newer token takeover clears masks and restores snapshot`() {
        val tracker = UiPanelOcclusionTracker()
        val tokenA = tracker.nextToken()
        tracker.update(UiPanelSlot.QUICK_BRUSH, tokenA, 10f, 20f, 110f, 120f)
        tracker.setUnclipped(UiPanelSlot.QUICK_BRUSH, true)
        assertEquals(0, tracker.snapshot().count)

        val versionBefore = tracker.version
        val tokenB = tracker.nextToken()
        // 当前 owner 切换：即使 bounds 相同也要清掉旧 owner 的掩码并 bump 版本。
        assertTrue(tracker.update(UiPanelSlot.QUICK_BRUSH, tokenB, 10f, 20f, 110f, 120f))
        assertTrue(tracker.version > versionBefore)
        assertFalse(tracker.isUnclipped(UiPanelSlot.QUICK_BRUSH))
        val snapshot = tracker.snapshot()
        assertEquals(1, snapshot.count)
        assertTrue(snapshot.contains(50f, 50f))

        // B 清除后回退到 A，但旧 owner 的掩码不会被恢复。
        assertTrue(tracker.clear(UiPanelSlot.QUICK_BRUSH, tokenB))
        assertEquals(tokenA, tracker.ownerToken(UiPanelSlot.QUICK_BRUSH))
        assertFalse(tracker.isUnclipped(UiPanelSlot.QUICK_BRUSH))
        assertEquals(1, tracker.snapshot().count)
    }

    @Test
    fun `onChanged fires once per real state change only`() {
        val tracker = UiPanelOcclusionTracker()
        var calls = 0
        tracker.onChanged = { calls++ }

        val token = tracker.nextToken()
        assertTrue(tracker.update(UiPanelSlot.TOP_BAR, token, 0f, 0f, 100f, 100f))
        assertEquals(1, calls)

        // 相同 token、相同 bounds：无状态变化。
        assertFalse(tracker.update(UiPanelSlot.TOP_BAR, token, 0f, 0f, 100f, 100f))
        assertEquals(1, calls)

        // 零 token / 未登记 token 的非法 bounds：被拒绝，不得回调。
        assertFalse(tracker.update(UiPanelSlot.TOP_BAR, 0, 0f, 0f, 10f, 10f))
        val unregistered = tracker.nextToken()
        assertFalse(tracker.update(UiPanelSlot.TOP_BAR, unregistered, Float.NaN, 0f, 10f, 10f))
        assertEquals(1, calls)

        // 真实 mask 变化：一次。
        assertTrue(tracker.setEvaded(UiPanelSlot.TOP_BAR, true))
        assertEquals(2, calls)
        assertFalse(tracker.setEvaded(UiPanelSlot.TOP_BAR, true))
        assertEquals(2, calls)

        // 非当前 owner 清除：当前状态未变，不回调。
        val newer = tracker.nextToken()
        assertTrue(tracker.update(UiPanelSlot.TOP_BAR, newer, 0f, 0f, 200f, 200f))
        assertEquals(3, calls)
        assertTrue(tracker.clear(UiPanelSlot.TOP_BAR, token))
        assertEquals(3, calls)

        // 当前 owner 清除：一次。
        assertTrue(tracker.clear(UiPanelSlot.TOP_BAR, newer))
        assertEquals(4, calls)
    }

    @Test
    fun `clearAllEvasion clears both masks and increments version once`() {
        val tracker = UiPanelOcclusionTracker()
        val tokenA = tracker.nextToken()
        tracker.update(UiPanelSlot.TOP_BAR, tokenA, 0f, 0f, 100f, 100f)
        val tokenB = tracker.nextToken()
        tracker.update(UiPanelSlot.QUICK_BRUSH, tokenB, 200f, 0f, 300f, 100f)
        tracker.setEvaded(UiPanelSlot.TOP_BAR, true)
        tracker.setUnclipped(UiPanelSlot.QUICK_BRUSH, true)

        val versionBefore = tracker.version
        tracker.clearAllEvasion()
        assertEquals(0, tracker.evadedMask)
        assertEquals(0, tracker.unclippedMask)
        assertEquals(versionBefore + 1, tracker.version)
        assertTrue(tracker.isActive(UiPanelSlot.TOP_BAR))
        assertTrue(tracker.isActive(UiPanelSlot.QUICK_BRUSH))

        // 掩码已经全为 0：再次调用不得递增 version。
        tracker.clearAllEvasion()
        assertEquals(versionBefore + 1, tracker.version)
    }

    @Test
    fun `clearEvasion only clears the target slot bits`() {
        val tracker = UiPanelOcclusionTracker()
        val tokenA = tracker.nextToken()
        tracker.update(UiPanelSlot.TOP_BAR, tokenA, 0f, 0f, 100f, 100f)
        val tokenB = tracker.nextToken()
        tracker.update(UiPanelSlot.QUICK_BRUSH, tokenB, 200f, 0f, 300f, 100f)
        tracker.setEvaded(UiPanelSlot.TOP_BAR, true)
        tracker.setUnclipped(UiPanelSlot.TOP_BAR, true)
        tracker.setEvaded(UiPanelSlot.QUICK_BRUSH, true)
        tracker.setUnclipped(UiPanelSlot.QUICK_BRUSH, true)

        val versionBefore = tracker.version
        tracker.clearEvasion(UiPanelSlot.TOP_BAR)
        assertFalse(tracker.isEvaded(UiPanelSlot.TOP_BAR))
        assertFalse(tracker.isUnclipped(UiPanelSlot.TOP_BAR))
        assertTrue(tracker.isEvaded(UiPanelSlot.QUICK_BRUSH))
        assertTrue(tracker.isUnclipped(UiPanelSlot.QUICK_BRUSH))
        assertEquals(versionBefore + 1, tracker.version)

        // 该槽位已无位可清：不得再递增 version。
        tracker.clearEvasion(UiPanelSlot.TOP_BAR)
        assertEquals(versionBefore + 1, tracker.version)
    }

    @Test
    fun `all slots can be cleared and reopened with distinct bounds`() {
        val tracker = UiPanelOcclusionTracker()
        for (slot in UiPanelSlot.entries) {
            val token = tracker.nextToken()
            val base = slot.ordinal * 100f
            assertTrue(tracker.update(slot, token, base, base + 1f, base + 50f, base + 51f))
        }
        assertEquals(21, tracker.activeCount())
        assertEquals(21, tracker.snapshot().count)

        for (slot in UiPanelSlot.entries) {
            assertTrue(tracker.clear(slot, tracker.ownerToken(slot)))
        }
        assertEquals(0, tracker.activeCount())
        assertEquals(0, tracker.snapshot().count)

        for (slot in UiPanelSlot.entries) {
            val token = tracker.nextToken()
            val base = slot.ordinal * 100f + 10f
            assertTrue(tracker.update(slot, token, base, base + 2f, base + 60f, base + 62f))
            assertEquals(base, tracker.left(slot), 0f)
            assertEquals(base + 2f, tracker.top(slot), 0f)
            assertEquals(base + 60f, tracker.right(slot), 0f)
            assertEquals(base + 62f, tracker.bottom(slot), 0f)
        }
        assertEquals(21, tracker.activeCount())
        assertEquals(21, tracker.snapshot().count)

        val out = FloatArray(4)
        for (slot in UiPanelSlot.entries) {
            assertTrue(tracker.bounds(slot, out))
            val base = slot.ordinal * 100f + 10f
            assertEquals(base, out[0], 0f)
            assertEquals(base + 2f, out[1], 0f)
            assertEquals(base + 60f, out[2], 0f)
            assertEquals(base + 62f, out[3], 0f)
        }
    }

    @Test
    fun `same token with changed bounds updates and bumps version`() {
        val tracker = UiPanelOcclusionTracker()
        val token = tracker.nextToken()
        assertTrue(tracker.update(UiPanelSlot.TOP_BAR, token, 0f, 0f, 100f, 100f))
        val versionBefore = tracker.version

        assertTrue(tracker.update(UiPanelSlot.TOP_BAR, token, 10f, 20f, 110f, 120f))
        assertTrue(tracker.version > versionBefore)
        assertEquals(10f, tracker.left(UiPanelSlot.TOP_BAR), 0f)
        assertEquals(20f, tracker.top(UiPanelSlot.TOP_BAR), 0f)
        assertEquals(110f, tracker.right(UiPanelSlot.TOP_BAR), 0f)
        assertEquals(120f, tracker.bottom(UiPanelSlot.TOP_BAR), 0f)
    }

    @Test
    fun `snapshot honors explicit masks regardless of internal mask`() {
        val tracker = UiPanelOcclusionTracker()
        val token = tracker.nextToken()
        tracker.update(UiPanelSlot.TOP_BAR, token, 0f, 0f, 100f, 100f)
        assertFalse(tracker.isUnclipped(UiPanelSlot.TOP_BAR))
        assertFalse(tracker.isEvaded(UiPanelSlot.TOP_BAR))

        val excluded = tracker.snapshot(unclippedMask = UiPanelSlot.TOP_BAR.bit)
        assertEquals(0, excluded.count)

        // evaded 不参与排除：显式 evadedMask 仍然包含该槽位。
        val included = tracker.snapshot(evadedMask = UiPanelSlot.TOP_BAR.bit)
        assertEquals(1, included.count)
        assertTrue(included.contains(50f, 50f))
    }

    @Test
    fun `hitTest includes edges and corners but not just outside`() {
        val tracker = UiPanelOcclusionTracker()
        val token = tracker.nextToken()
        tracker.update(UiPanelSlot.QUICK_LAYER, token, 100f, 100f, 200f, 200f)

        // 四条边与四个角（边界相等视为命中）。
        assertTrue(tracker.hitTest(100f, 100f))
        assertTrue(tracker.hitTest(200f, 100f))
        assertTrue(tracker.hitTest(100f, 200f))
        assertTrue(tracker.hitTest(200f, 200f))
        assertTrue(tracker.hitTest(100f, 150f))
        assertTrue(tracker.hitTest(150f, 100f))
        assertTrue(tracker.hitTest(200f, 150f))
        assertTrue(tracker.hitTest(150f, 200f))

        // 外扩 0.01px 即不命中。
        assertFalse(tracker.hitTest(99.99f, 150f))
        assertFalse(tracker.hitTest(150f, 99.99f))
        assertFalse(tracker.hitTest(200.01f, 150f))
        assertFalse(tracker.hitTest(150f, 200.01f))

        // 已清除的槽位不再参与命中。
        tracker.clear(UiPanelSlot.QUICK_LAYER, token)
        assertFalse(tracker.hitTest(150f, 150f))
    }

    @Test
    fun `invalid update with zero token or non-finite bounds leaves state untouched`() {
        val tracker = UiPanelOcclusionTracker()
        val token = tracker.nextToken()
        val versionBefore = tracker.version

        assertFalse(tracker.update(UiPanelSlot.TOP_BAR, 0, 0f, 0f, 10f, 10f))
        val invalid = floatArrayOf(Float.NaN, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY)
        for (value in invalid) {
            assertFalse(tracker.update(UiPanelSlot.TOP_BAR, token, value, 0f, 10f, 10f))
            assertFalse(tracker.update(UiPanelSlot.TOP_BAR, token, 0f, value, 10f, 10f))
            assertFalse(tracker.update(UiPanelSlot.TOP_BAR, token, 0f, 0f, value, 10f))
            assertFalse(tracker.update(UiPanelSlot.TOP_BAR, token, 0f, 0f, 10f, value))
        }
        // 零尺寸。
        assertFalse(tracker.update(UiPanelSlot.TOP_BAR, token, 10f, 0f, 10f, 10f))
        assertFalse(tracker.update(UiPanelSlot.TOP_BAR, token, 0f, 10f, 10f, 10f))

        assertEquals(versionBefore, tracker.version)
        assertFalse(tracker.isActive(UiPanelSlot.TOP_BAR))
    }

    @Test
    fun `ringState prefers contact on B while A is only warn`() {
        val tracker = UiPanelOcclusionTracker()
        val tokenA = tracker.nextToken()
        tracker.update(UiPanelSlot.QUICK_ACTION, tokenA, 0f, 0f, 100f, 100f)
        val tokenB = tracker.nextToken()
        tracker.update(UiPanelSlot.QUICK_COLOR, tokenB, 150f, 0f, 250f, 100f)

        // (160,50): B 内部 CONTACT；A 距右边界 60px（warnR=64）只算 WARN。
        assertEquals(UiRingState.CONTACT, tracker.ringState(160f, 50f, 0f, 64f, 4f))
    }
}
