/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

/**
 * 创作统计的"增量"计算 (纯逻辑, JVM 单测覆盖)。
 *
 * 为什么需要它: `elapsedSeconds` / `totalStrokes` 是**工程累计值**, 会随 `.revp` 持久化,
 * 重开工程后从存档值继续累加。而"今日活跃"只该记这一次真正新增的量。直接把累计值
 * 上报给每日统计, 保存一次加一遍、退出再加一遍, 一天就能算出十几个小时
 * (issue: 用户 "当日已画 15 小时 35 分 / 落笔 108 笔")。
 *
 * 调用方在打开工程/新建画布时把基线对齐到当前累计值, 每次上报后把基线推到最新,
 * 于是无论保存、自动保存还是退出触发, 都只记增量且不会重复计数。
 */
object CreationStatsDelta {

    /** 一次上报的增量 (负数一律夹到 0: 撤销/重置导致的回退不该产生负统计) */
    data class Delta(val strokes: Int, val seconds: Long) {
        val isEmpty: Boolean get() = strokes <= 0 && seconds <= 0L
    }

    fun since(
        totalStrokes: Int,
        totalSeconds: Long,
        reportedStrokes: Int,
        reportedSeconds: Long,
    ): Delta = Delta(
        strokes = (totalStrokes - reportedStrokes).coerceAtLeast(0),
        seconds = (totalSeconds - reportedSeconds).coerceAtLeast(0L),
    )
}
