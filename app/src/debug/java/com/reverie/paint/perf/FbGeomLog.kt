/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.perf

import android.os.SystemClock
import android.util.Log
import com.reverie.paint.core.PerfTrace
import com.reverie.paint.core.stylus.FrontBufferProbe
import com.reverie.paint.model.CanvasPose

/**
 * 前缓冲真墨的几何日志 (开发/真机定位用, **debug 构建专属**; release 源集是同签名空实现)。
 *
 * 缺墨类问题 (按住运笔时某块没有笔迹、抬笔才恢复) 的成因很多: 槽位图没清、贴图矩形没烤到、
 * 坐标被夹掉、槽位原点错位、设备不保留前缓冲内容…… 需要把"脏区 → 屏幕矩形 → 槽位原点/尺寸"
 * 整条链路打出来再和肉眼现象对齐。只在 [PerfTrace.enabled] 打开时记录, 逐帧日志限频 100ms:
 *
 *     adb shell setprop debug.reverie.perf 1
 *     adb logcat -s FbGeomLog:I FrontBufferProbe:I
 */
internal object FbGeomLog {
    private const val TAG = "FbGeomLog"
    private const val FRAME_MIN_INTERVAL_MS = 100L
    private const val MAX_RECTS_LOGGED = 4

    private var lastFrameMs = 0L
    private var frameSeq = 0L

    /** 每一笔落笔时一行: 设备能力 + 视图尺寸 + 当前 pose。UI 线程调用。 */
    fun strokeBegin(strokeSeq: Long, held: Boolean, viewW: Int, viewH: Int, pose: CanvasPose?) {
        if (!PerfTrace.enabled) return
        lastFrameMs = 0L
        frameSeq = 0L
        val sb = StringBuilder(192)
        sb.append("ev=stroke_begin seq=").append(strokeSeq)
        sb.append(" held=").append(held)
        sb.append(" preserve=").append(FrontBufferProbe.preservedContentState())
        sb.append(" view=").append(viewW).append('x').append(viewH)
        if (pose != null) {
            sb.append(" pose[cx=").append(fmt(pose.centerX))
            sb.append(",cy=").append(fmt(pose.centerY))
            sb.append(",s=").append(fmt(pose.scale))
            sb.append(",rot=").append(fmt(pose.rotation))
            sb.append(",sign=").append(pose.signX.toInt()).append('/').append(pose.signY.toInt())
            sb.append(",bmp=").append(pose.bmpW).append('x').append(pose.bmpH)
            sb.append(']')
        }
        Log.i(TAG, sb.toString())
    }

    /** 每一帧真墨贴图一行 (限频)。渲染线程调用。 */
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
        if (!PerfTrace.enabled) return
        val now = SystemClock.uptimeMillis()
        if (lastFrameMs != 0L && now - lastFrameMs < FRAME_MIN_INTERVAL_MS) return
        lastFrameMs = now
        frameSeq++
        val sb = StringBuilder(320)
        sb.append("ev=ink_frame#").append(frameSeq)
        sb.append(" pending=").append(pendingCount)
        sb.append(" baked=").append(bakedCount)
        sb.append(" clipped=").append(clippedCount)
        sb.append(" origin=").append(originL).append(',').append(originT)
        sb.append(" req=").append(reqW).append('x').append(reqH)
        sb.append(" slot=").append(slotW).append('x').append(slotH)
        sb.append(" canvas=[").append(canvasBounds[0]).append(',').append(canvasBounds[1]).append(',')
            .append(canvasBounds[2]).append(',').append(canvasBounds[3]).append(']')
        val n = minOf(bakedCount, MAX_RECTS_LOGGED)
        for (i in 0 until n) {
            val d = i * 4
            sb.append(" r").append(i)
            sb.append("[bmp=").append(bmpRects[d]).append(',').append(bmpRects[d + 1])
            sb.append('-').append(bmpRects[d + 2]).append(',').append(bmpRects[d + 3])
            sb.append(" scr=").append(screenRects[d]).append(',').append(screenRects[d + 1])
            sb.append('-').append(screenRects[d + 2]).append(',').append(screenRects[d + 3])
            sb.append(']')
        }
        if (bakedCount > n) sb.append(" (+").append(bakedCount - n).append(" more)")
        Log.i(TAG, sb.toString())
    }

    private fun fmt(v: Float): String = String.format(java.util.Locale.US, "%.2f", v)
}
