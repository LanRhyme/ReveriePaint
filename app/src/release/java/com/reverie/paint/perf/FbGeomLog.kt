/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.perf

import com.reverie.paint.model.CanvasPose

/**
 * 前缓冲真墨几何日志在**正式版里不存在**: 与 debug 版同签名的空实现
 * (理由同 [PerfHud]: release 未开 R8, 常量分支不会被消除)。
 */
@Suppress("UNUSED_PARAMETER")
internal object FbGeomLog {
    fun strokeBegin(strokeSeq: Long, held: Boolean, viewW: Int, viewH: Int, pose: CanvasPose?) {
    }

    fun presentFrame(
        pendingCount: Int,
        bakedCount: Int,
        originL: Int,
        originT: Int,
        reqW: Int,
        reqH: Int,
        slotW: Int,
        slotH: Int,
        screenRects: IntArray,
        bmpRects: IntArray,
        canvasBounds: IntArray,
        clippedCount: Int,
    ) {
    }
}
