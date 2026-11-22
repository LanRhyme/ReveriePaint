/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.reverie.paint.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.util.TreeMap

class AnimationCelPlanTest {
    /** A held-exposure model: matches the native keyframe contract, including its last-key guard. */
    private class Track(vararg keys: Pair<Int, String>) {
        val frames = TreeMap<Int, String>().apply { putAll(keys.toMap()) }
        fun at(time: Int): String = frames.floorEntry(time)?.value.orEmpty()
        fun edit(start: Int, end: Int = start + 1, erase: Boolean = false, preserve: Boolean = false) {
            val edits = AnimationCelPlan.edits(frames.keys.toList(), AnimationCelPlan.Range(start, end), erase, preserve)
            for (edit in edits) when (edit) {
                is AnimationCelPlan.Edit.Blank -> frames.putIfAbsent(edit.time, "")
                is AnimationCelPlan.Edit.Copy -> {
                    check(edit.from in frames)
                    frames[edit.to] = frames.getValue(edit.from)
                }
                is AnimationCelPlan.Edit.Remove -> {
                    check(frames.size > 1)
                    check(frames.remove(edit.time) != null)
                }
            }
        }
    }

    @Test fun `adding layer at frame 11 leaves all other frames blank`() {
        val track = Track(0 to "")
        track.edit(11)
        track.frames[11] = "drawing eleven"
        for (time in 0..20) assertEquals(if (time == 11) "drawing eleven" else "", track.at(time))
    }

    @Test fun `reuse frame 12 and delete it without changing frame 11`() {
        val track = Track(0 to "")
        track.edit(11)
        track.frames[11] = "eleven"
        track.edit(12)
        track.frames[12] = "twelve"
        track.edit(12, erase = true)
        assertEquals("eleven", track.at(11))
        assertEquals("", track.at(12))
        assertEquals("", track.at(13))
    }

    @Test fun `deleting middle cel never exposes preceding artwork`() {
        val track = Track(0 to "first", 11 to "eleven", 12 to "twelve", 13 to "next")
        track.edit(12, erase = true)
        assertEquals("eleven", track.at(11))
        assertEquals("", track.at(12))
        assertEquals("next", track.at(13))
    }

    @Test fun `delete inside legacy held drawing preserves both neighbours`() {
        val track = Track(0 to "original", 20 to "later")
        track.edit(11, erase = true)
        for (time in 0..19) assertEquals(if (time == 11) "" else "original", track.at(time))
        assertEquals("later", track.at(20))
    }

    @Test fun `editing held drawing copies both boundaries before painting`() {
        val track = Track(0 to "original")
        track.edit(11, preserve = true)
        assertEquals("original", track.at(11))
        track.frames[11] = "edited"
        assertEquals("original", track.at(10))
        assertEquals("original", track.at(12))
    }

    @Test fun `first and only key can be removed without losing the track`() {
        val track = Track(0 to "original")
        track.edit(0, erase = true)
        assertEquals("", track.at(0))
        assertEquals("original", track.at(1))
        assertFalse(track.frames.isEmpty())
    }

    @Test fun `stretched shot clears only its interval and preserves internal timing`() {
        val track = Track(0 to "before", 11 to "shot", 13 to "detail", 20 to "next")
        track.edit(11, 16, erase = true)
        for (time in 11..15) assertEquals("", track.at(time))
        assertEquals("before", track.at(10))
        assertEquals("detail", track.at(16))
        assertEquals("next", track.at(20))
        assertEquals("", track.frames[13])
    }

    @Test fun `already bounded cell produces no extra undo operation on every pen down`() {
        assertEquals(emptyList<AnimationCelPlan.Edit>(), AnimationCelPlan.edits(
            listOf(0, 11, 12), AnimationCelPlan.Range(11, 12), erase = false, preserveDrawing = true,
        ))
    }

    @Test fun `末尾新建帧不再补多余边界键`() {
        assertEquals(
            listOf(AnimationCelPlan.Edit.Blank(1)),
            AnimationCelPlan.edits(
                times = listOf(0),
                range = AnimationCelPlan.Range(1, 2),
                erase = false,
                preserveDrawing = false,
                protectTail = false,
            ),
        )
        // 中间插入仍保留边界, 后续已有帧不会被覆盖
        assertEquals(
            listOf(AnimationCelPlan.Edit.Blank(2)),
            AnimationCelPlan.edits(
                times = listOf(0, 5),
                range = AnimationCelPlan.Range(2, 3),
                erase = false,
                preserveDrawing = false,
                protectTail = false,
            ),
        )
    }

    @Test fun `shot lookup includes start excludes end and supports subtitle gaps`() {
        val shot = StoryboardFrame(SrtCue(0, 1000, "caption"), 11, 16)
        assertEquals(AnimationCelPlan.Range(11, 16), AnimationCelPlan.range(11, listOf(shot)))
        assertEquals(AnimationCelPlan.Range(11, 16), AnimationCelPlan.range(15, listOf(shot)))
        assertEquals(AnimationCelPlan.Range(16, 17), AnimationCelPlan.range(16, listOf(shot)))
        assertEquals(AnimationCelPlan.Range(5, 6), AnimationCelPlan.range(5, emptyList()))
    }

    @Test fun `all frame deletions preserve every pixel outside their interval`() {
        for (start in 0..25) {
            val track = Track(0 to "A", 5 to "B", 12 to "C", 20 to "D")
            val original = (0..30).map(track::at)
            track.edit(start, erase = true)
            for (time in 0..30) assertEquals("delete $start, sample $time",
                if (time == start) "" else original[time], track.at(time))
        }
    }
}
