/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.reverie.paint.model

/** Edits are bounded before changing pixels, so Krita's held exposure cannot leak to neighbours. */
internal object AnimationCelPlan {
    data class Range(val start: Int, val endExclusive: Int)

    fun range(time: Int, shots: List<StoryboardFrame>): Range {
        require(time in 0 until Int.MAX_VALUE)
        val shot = shots.firstOrNull { time >= it.start && time < it.endExclusive }
        return if (shot != null) Range(shot.start, shot.endExclusive) else Range(time, time + 1)
    }

    sealed interface Edit {
        data class Blank(val time: Int) : Edit
        data class Copy(val from: Int, val to: Int) : Edit
        data class Remove(val time: Int) : Edit
    }

    /** Preserve the exposure after the edited cell BEFORE replacing its source key. */
    fun edits(times: List<Int>, range: Range, erase: Boolean, preserveDrawing: Boolean): List<Edit> {
        require(range.start >= 0 && range.endExclusive > range.start)
        val keys = times.sorted()
        val result = mutableListOf<Edit>()
        if (range.endExclusive !in keys) {
            val afterSource = keys.lastOrNull { it < range.endExclusive }
            result += if (afterSource != null) Edit.Copy(afterSource, range.endExclusive)
                else Edit.Blank(range.endExclusive)
        }
        if (erase) {
            // Include internal keys when deleting a stretched storyboard shot, but keep its timing.
            for (key in keys.filter { it >= range.start && it < range.endExclusive }) {
                result += Edit.Remove(key)
                result += Edit.Blank(key)
            }
            if (range.start !in keys) result += Edit.Blank(range.start)
        } else if (range.start !in keys) {
            val source = keys.lastOrNull { it < range.start }
            result += if (preserveDrawing && source != null) Edit.Copy(source, range.start)
                else Edit.Blank(range.start)
        }
        return result
    }
}
