/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.perf

/**
 * 显示瓦片帧的分段耗时日志在**正式版里不存在**: 与 debug 版同签名的空实现
 * (理由同 [PerfHud]: release 未开 R8, 常量分支不会被消除)。
 */
internal object TilePerfLog {
    @Suppress("UNUSED_PARAMETER")
    fun frame(
        compositeNs: Long,
        tilesNs: Long,
        presentNs: Long,
        hits: Int,
        dirtyCount: Int,
        wrotePixels: Long,
        anyFull: Boolean,
    ) {
    }
}
