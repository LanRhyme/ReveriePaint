/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.brushstore

import com.reverie.paint.core.BrushPresetInfo
import org.junit.Assert.assertEquals
import org.junit.Test

/** 回放选笔的目标序号 / 橡皮擦标志：新录制按 id 找（改名不影响），旧录制（没有 md5）仍按名字、再按序号 */
class ReplayTargetTest {
    private fun p(index: Int, id: String, name: String, group: String = "") =
        BrushPresetInfo(index = index, id = id, name = name, thumbBytes = ByteArray(0), group = group)

    @Test
    fun `新录制 改名后的笔按 id 找到 不会落到旧序号上的另一支笔`() {
        val now = listOf(p(3, "imp:other", "别的笔"), p(9, "imp:x", "改过名的笔"))
        val sel = PlaybackPresetCache.Select(index = 3, name = "旧名字", presetId = "imp:x", md5 = "abc")
        assertEquals(BrushApply.ReplayTarget(9, false), BrushApply.replayTarget(sel, now))
    }

    @Test
    fun `新录制 橡皮擦标志取自按 id 找到的那支笔 不是旧序号上的笔`() {
        val now = listOf(p(3, "imp:other", "a)_Eraser-Soft"), p(9, "imp:x", "我的笔", group = "橡皮擦"))
        val sel = PlaybackPresetCache.Select(index = 3, name = "旧名字", presetId = "imp:x", md5 = "abc")
        assertEquals(BrushApply.ReplayTarget(9, true), BrushApply.replayTarget(sel, now))
        val sel2 = PlaybackPresetCache.Select(index = 3, name = "我的笔", presetId = "imp:gone", md5 = "abc")
        // 那支笔已经不在了：沿用录制的序号，橡皮擦标志按录制的名字判断，不看序号上现在是谁
        assertEquals(BrushApply.ReplayTarget(3, false), BrushApply.replayTarget(sel2.copy(name = "旧名字"), listOf(p(3, "imp:other", "a)_Eraser-Soft"))))
    }

    @Test
    fun `旧录制 没有 md5 仍按名字再按序号 行为不变`() {
        val now = listOf(p(3, "imp:other", "别的笔"), p(9, "imp:x", "同名笔"))
        assertEquals(BrushApply.ReplayTarget(9, false), BrushApply.replayTarget(PlaybackPresetCache.Select(3, "同名笔", "", ""), now))
        assertEquals(BrushApply.ReplayTarget(3, false), BrushApply.replayTarget(PlaybackPresetCache.Select(3, "已删除的笔", "", ""), now))
        val eraser = listOf(p(3, "imp:e", "a)_Eraser-Soft"))
        assertEquals(BrushApply.ReplayTarget(3, true), BrushApply.replayTarget(PlaybackPresetCache.Select(3, "", "", ""), eraser))
    }
}
