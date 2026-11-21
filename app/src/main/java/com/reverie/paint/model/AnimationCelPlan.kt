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

    /**
     * @param protectTail 是否为"被替换的尾部曝光"补边界键。
     *   普通动画轨道在末尾新建帧时没有后续内容需要保护, 补边界会在时间轴上
     *   多出一格, 看起来像一次加了两帧; 这类情况传 false。
     *   字幕分镜 (固定时长) 和中间插入必须保持 true。
     */
    fun edits(
        times: List<Int>,
        range: Range,
        erase: Boolean,
        preserveDrawing: Boolean,
        protectTail: Boolean = true,
    ): List<Edit> {
        require(range.start >= 0 && range.endExclusive > range.start)
        val keys = times.sorted()
        val result = mutableListOf<Edit>()
        if (protectTail && range.endExclusive !in keys) {
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
