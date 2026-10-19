/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 50% 运笔避让状态机与阴影孔径裁剪的纯 JVM 单测。
 *
 * 覆盖：32dp 进入 / 40dp 离开双向滞后、多面板独立判定、阴影外扩只扩大
 * 裁剪孔径而不虚增触发距离、渐隐态绝不撤除孔径、脏区交集提取与全量复位。
 */
class UiPanelEvasionStateMachineTest {

    @Test
    fun `enter_at_32dp_plus_brushRadius_sets_evaded_true`() {
        val tracker = trackerWith(UiPanelSlot.QUICK_ACTION, 100f, 100f, 200f, 200f)

        // 面板右边界 200，笔刷半径 10：enter = 32 + 10 = 42。
        assertTrue(tracker.evaluateSlotEvasion(
            UiPanelSlot.QUICK_ACTION, 242f, 150f, 242f, 150f, enterRadiusPx = 42f, exitRadiusPx = 42f,
        ))

        val fresh = trackerWith(UiPanelSlot.QUICK_ACTION, 100f, 100f, 200f, 200f)
        assertFalse(fresh.evaluateSlotEvasion(
            UiPanelSlot.QUICK_ACTION, 242.1f, 150f, 242.1f, 150f, enterRadiusPx = 42f, exitRadiusPx = 42f,
        ))
    }

    @Test
    fun `hysteresis_band_between_32dp_and_40dp_preserves_previous_state`() {
        val tracker = trackerWith(UiPanelSlot.QUICK_ACTION, 100f, 100f, 200f, 200f)
        val slot = UiPanelSlot.QUICK_ACTION

        // 未进入态：36px 处于 32..40 滞后带，保持 false。
        assertFalse(tracker.evaluateSlotEvasion(slot, 236f, 150f, 236f, 150f, 32f, 40f))

        // 进入 30px 触发 true。
        assertTrue(tracker.evaluateSlotEvasion(slot, 230f, 150f, 230f, 150f, 32f, 40f))

        // 回退到 36px：滞后带内保持 true，不抖动。
        assertTrue(tracker.evaluateSlotEvasion(slot, 236f, 150f, 236f, 150f, 32f, 40f))

        // 继续移动到 40.1px：无需抬笔立即变为 false。
        assertFalse(tracker.evaluateSlotEvasion(slot, 240.1f, 150f, 240.1f, 150f, 32f, 40f))
    }

    @Test
    fun `point_inside_panel_has_zero_distance_and_enters_evasion`() {
        val tracker = trackerWith(UiPanelSlot.QUICK_ACTION, 100f, 100f, 200f, 200f)

        assertTrue(tracker.evaluateSlotEvasion(
            UiPanelSlot.QUICK_ACTION, 150f, 150f, 150f, 150f, 32f, 40f,
        ))
    }

    @Test
    fun `multi_panel_independent_evaluation`() {
        val tracker = UiPanelOcclusionTracker()
        tracker.update(UiPanelSlot.QUICK_ACTION, tracker.nextToken(), 100f, 100f, 200f, 200f)
        tracker.update(UiPanelSlot.QUICK_COLOR, tracker.nextToken(), 260f, 100f, 360f, 200f)

        // 靠近 A（20px）、远离 B（40px，尚未进入 32px 圈）。
        assertTrue(tracker.evaluateSlotEvasion(
            UiPanelSlot.QUICK_ACTION, 220f, 150f, 220f, 150f, 32f, 40f,
        ))
        assertFalse(tracker.evaluateSlotEvasion(
            UiPanelSlot.QUICK_COLOR, 220f, 150f, 220f, 150f, 32f, 40f,
        ))

        // 两者同时处于避让范围：A 在滞后带内保持 true，B 进入。
        assertTrue(tracker.evaluateSlotEvasion(
            UiPanelSlot.QUICK_ACTION, 240f, 150f, 240f, 150f, 32f, 40f,
        ))
        assertTrue(tracker.evaluateSlotEvasion(
            UiPanelSlot.QUICK_COLOR, 240f, 150f, 240f, 150f, 32f, 40f,
        ))

        // 中途离开 A 靠近 B：A 立即恢复 false，B 保持 true。
        assertFalse(tracker.evaluateSlotEvasion(
            UiPanelSlot.QUICK_ACTION, 280f, 150f, 280f, 150f, 32f, 40f,
        ))
        assertTrue(tracker.evaluateSlotEvasion(
            UiPanelSlot.QUICK_COLOR, 280f, 150f, 280f, 150f, 32f, 40f,
        ))
    }

    @Test
    fun `shadow_outset_expands_clip_snapshot_without_inflating_32dp_trigger_distance`() {
        val tracker = UiPanelOcclusionTracker()
        tracker.update(
            UiPanelSlot.QUICK_COLOR, tracker.nextToken(),
            100f, 100f, 200f, 200f,
            padH = 18f, padT = 12f, padB = 26f,
        )

        // 孔径 = 本体 + 阴影外扩：完整保护底部 26px 软阴影。
        val snapshot = tracker.snapshot()
        assertEquals(1, snapshot.count)
        assertEquals(82f, snapshot.left(0), 0f)
        assertEquals(88f, snapshot.top(0), 0f)
        assertEquals(218f, snapshot.right(0), 0f)
        assertEquals(226f, snapshot.bottom(0), 0f)

        // 32dp 距离仍以本体右边界 200 计算：232 触发，250（200 + 18 + 32）不触发。
        val fresh = trackerWith(UiPanelSlot.QUICK_COLOR, 100f, 100f, 200f, 200f)
        assertTrue(fresh.evaluateSlotEvasion(
            UiPanelSlot.QUICK_COLOR, 232f, 150f, 232f, 150f, 32f, 40f,
        ))
        assertFalse(fresh.evaluateSlotEvasion(
            UiPanelSlot.QUICK_COLOR, 250f, 150f, 250f, 150f, 32f, 40f,
        ))
    }

    @Test
    fun `evaded_panel_remains_in_clip_snapshot`() {
        val tracker = trackerWith(UiPanelSlot.QUICK_COLOR, 100f, 100f, 200f, 200f)

        tracker.setEvaded(UiPanelSlot.QUICK_COLOR, true)
        assertTrue(tracker.isEvaded(UiPanelSlot.QUICK_COLOR))

        val snapshot = tracker.snapshot()
        assertEquals(1, snapshot.count)
        assertEquals(100f, snapshot.left(0), 0f)
        assertEquals(200f, snapshot.right(0), 0f)
    }

    @Test
    fun `clip_snapshot_accumulate_intersection_computes_exact_scissor_rect`() {
        val tracker = UiPanelOcclusionTracker()
        tracker.update(
            UiPanelSlot.QUICK_COLOR, tracker.nextToken(),
            100f, 100f, 200f, 200f,
            padH = 18f, padT = 12f, padB = 26f,
        )
        tracker.update(UiPanelSlot.QUICK_ACTION, tracker.nextToken(), 400f, 400f, 500f, 500f)
        val snapshot = tracker.snapshot()

        val out = IntArray(4)
        fun resetOut() {
            out[0] = Int.MAX_VALUE
            out[1] = Int.MAX_VALUE
            out[2] = Int.MIN_VALUE
            out[3] = Int.MIN_VALUE
        }

        resetOut()
        assertFalse(snapshot.accumulateIntersection(0, 0, 50, 50, out))

        resetOut()
        assertTrue(snapshot.accumulateIntersection(200, 200, 300, 300, out))
        assertEquals(200, out[0])
        assertEquals(200, out[1])
        assertEquals(218, out[2])
        assertEquals(226, out[3])

        // 同时命中两个孔径：输出所有交集的最小外接矩形。
        resetOut()
        assertTrue(snapshot.accumulateIntersection(190, 190, 450, 450, out))
        assertEquals(190, out[0])
        assertEquals(190, out[1])
        assertEquals(450, out[2])
        assertEquals(450, out[3])
    }

    @Test
    fun `clear_all_evasion_resets_every_slot_immediately`() {
        val tracker = UiPanelOcclusionTracker()
        tracker.update(UiPanelSlot.QUICK_ACTION, tracker.nextToken(), 100f, 100f, 200f, 200f)
        tracker.update(UiPanelSlot.PINNED_COLOR_PANEL, tracker.nextToken(), 300f, 300f, 400f, 400f)
        tracker.setEvaded(UiPanelSlot.QUICK_ACTION, true)
        tracker.setEvaded(UiPanelSlot.PINNED_COLOR_PANEL, true)

        tracker.clearAllEvasion()

        assertFalse(tracker.isEvaded(UiPanelSlot.QUICK_ACTION))
        assertFalse(tracker.isEvaded(UiPanelSlot.PINNED_COLOR_PANEL))
        // 复位渐隐不得丢失已登记的本体/孔径 bounds。
        val snapshot = tracker.snapshot()
        assertEquals(2, snapshot.count)
        assertTrue(tracker.hitTest(150f, 150f))
        assertTrue(tracker.hitTest(350f, 350f))
    }

    @Test
    fun `inactive_or_non_evadable_slot_never_evades`() {
        val tracker = UiPanelOcclusionTracker()
        assertFalse(tracker.evaluateSlotEvasion(
            UiPanelSlot.QUICK_ACTION, 0f, 0f, 0f, 0f, 32f, 40f,
        ))

        tracker.update(UiPanelSlot.TOP_BAR, tracker.nextToken(), 0f, 0f, 100f, 100f)
        assertFalse(tracker.evaluateSlotEvasion(
            UiPanelSlot.TOP_BAR, 50f, 50f, 50f, 50f, 32f, 40f,
        ))
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
