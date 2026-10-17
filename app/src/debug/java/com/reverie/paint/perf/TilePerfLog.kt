/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.perf

import android.util.Log
import com.reverie.paint.core.PerfTrace

/**
 * 显示瓦片帧的分段耗时日志 (开发/量测用, **debug 构建专属**; release 源集是同签名空实现)。
 *
 * 只在 [PerfTrace.enabled] 打开时记录 (`setprop debug.reverie.perf 1` 或性能标尺),
 * 每 [LOG_EVERY] 帧一条, tag `ReveriePerf`。分段口径:
 *  - composite = 等异步合并 + 引擎逐块合成脏区
 *  - tiles     = 逐瓦片写像素 (含必要时的整块旧像素拷贝)
 *  - present   = 发布网格/快照 + 失效画布
 * 只在渲染线程调用。
 */
internal object TilePerfLog {
    private const val LOG_EVERY = 60

    private var frames = 0
    private var fullFrames = 0
    private var compositeNs = 0L
    private var tilesNs = 0L
    private var presentNs = 0L
    private var pixels = 0L

    fun frame(
        compositeNs: Long,
        tilesNs: Long,
        presentNs: Long,
        hits: Int,
        dirtyCount: Int,
        wrotePixels: Long,
        anyFull: Boolean,
    ) {
        if (!PerfTrace.enabled) return
        frames++
        if (anyFull) fullFrames++
        this.compositeNs += compositeNs
        this.tilesNs += tilesNs
        this.presentNs += presentNs
        pixels += wrotePixels
        if (frames < LOG_EVERY) return
        val us = 1000L * frames
        Log.i(
            "ReveriePerf",
            "TilePerf: frames=$frames full=$fullFrames lastHits=$hits lastDirty=$dirtyCount " +
                "avgPx=${pixels / frames} composite=${this.compositeNs / us}us " +
                "tiles=${this.tilesNs / us}us present=${this.presentNs / us}us",
        )
        frames = 0
        fullFrames = 0
        this.compositeNs = 0L
        this.tilesNs = 0L
        this.presentNs = 0L
        pixels = 0L
    }
}
