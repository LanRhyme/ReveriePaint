/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * 前缓冲真墨呈现 (续 #81) 的纯几何与判定规则。没有 Android 依赖, 单测直接覆盖。
 *
 * 真墨的链路: 渲染线程写完显示瓦片后, 把本帧的离散脏区 (位图坐标) 按画布 pose 换算成屏幕
 * 矩形, 从瓦片取像素烤进屏幕分辨率的槽位图, 再由前缓冲覆盖层逐块贴上去。按住运笔期间主画布
 * 不再逐帧 invalidate, 抬笔后主画布追平、覆盖层清空。
 */
object RealInkPresent {
    /** 屏幕矩形每边外扩的像素, 吸收采样与取整误差。 */
    const val PAD_PX = 2

    /** 待贴离散脏区的块数上限 (同时是一个呈现包的矩形上限)。 */
    const val MAX_DISCRETE_RECTS = 8

    private const val RECT_STRIDE = 4

    // 合并判据: 并进去多出的空心面积 < 并后面积的 HOLLOW_NUM / HOLLOW_DEN 才合并。
    private const val HOLLOW_NUM = 1L
    private const val HOLLOW_DEN = 4L

    /**
     * 显示位图坐标矩形 -> 屏幕轴对齐包围盒, 与 `CanvasTouchView.onDraw` 同一套变换:
     *
     *     screen = center + R(rotation) * (scale * (sign * (p - half)))
     *
     * 与 `canvas.translate(center) -> rotate -> scale(scale) -> scale(sign)` 同序, 镜像先作用
     * 在"位图中心为原点"的偏移上。四角取包围盒 (旋转时只会偏大不会偏小), 外扩 [pad] 个屏幕
     * 像素吸收采样与取整误差, 再与 `[0, viewW) x [0, viewH)` 求交。
     *
     * 结果写进 [out] = [left, top, right, bottom] (right/bottom 为开区间)。交为空 (或入参
     * 矩形/视口退化) 时返回 false 并把 [out] 清零, 调用方不必先判断。
     */
    fun bmpRectToScreenBounds(
        pose: CanvasPose,
        l: Float,
        t: Float,
        r: Float,
        b: Float,
        viewW: Int,
        viewH: Int,
        pad: Int,
        out: IntArray,
    ): Boolean {
        if (r <= l || b <= t || viewW <= 0 || viewH <= 0) {
            clearRect(out)
            return false
        }
        val radians = Math.toRadians(pose.rotation.toDouble())
        val cosR = cos(radians).toFloat()
        val sinR = sin(radians).toFloat()
        val sx = pose.scale * pose.signX
        val sy = pose.scale * pose.signY
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (i in 0 until 4) {
            val x = if (i == 0 || i == 2) l else r
            val y = if (i < 2) t else b
            val ux = (x - pose.halfW) * sx
            val uy = (y - pose.halfH) * sy
            val px = ux * cosR - uy * sinR + pose.centerX
            val py = ux * sinR + uy * cosR + pose.centerY
            if (px < minX) minX = px
            if (px > maxX) maxX = px
            if (py < minY) minY = py
            if (py > maxY) maxY = py
        }
        val padF = pad.toFloat()
        val left = floor(minX - padF).toInt().coerceAtLeast(0)
        val top = floor(minY - padF).toInt().coerceAtLeast(0)
        val right = ceil(maxX + padF).toInt().coerceAtMost(viewW)
        val bottom = ceil(maxY + padF).toInt().coerceAtMost(viewH)
        if (right <= left || bottom <= top) {
            clearRect(out)
            return false
        }
        out[0] = left
        out[1] = top
        out[2] = right
        out[3] = bottom
        return true
    }

    /**
     * 烤屏幕槽位图时的**源位图**子矩形: 脏区每边外扩 `PAD_PX + 2` 个屏幕像素换算回位图像素
     * (屏幕像素 / scale)，再夹在位图内。
     *
     * 外扩是为了让 [bmpRectToScreenBounds] 侧按 [PAD_PX] 外扩 + floor/ceil 取整得到的屏幕
     * 矩形完整落在烤过的像素里 —— 边界差半像素就会把没烤到的槽位像素 (透明) 贴上去，
     * 在单缓冲上留下透明洞。多出的 2 像素吸收旋转时包围盒每轴的 `|cos| + |sin|` 放大。
     *
     * 结果写进 [out] = [left, top, right, bottom] (right/bottom 为开区间)；返回 false 表示传入的
     * 脏区本身为空或换算后为空 (调用方不必先判断)。
     */
    fun bakeSrcRect(
        bl: Int,
        bt: Int,
        br: Int,
        bb: Int,
        scale: Float,
        bmpW: Int,
        bmpH: Int,
        out: IntArray,
    ): Boolean {
        if (br <= bl || bb <= bt) {
            clearRect(out)
            return false
        }
        val padSrc = ceil((PAD_PX + 2) / scale.coerceAtLeast(0.001f)).toInt()
        out[0] = (bl - padSrc).coerceIn(0, bmpW)
        out[1] = (bt - padSrc).coerceIn(0, bmpH)
        out[2] = (br + padSrc).coerceIn(0, bmpW)
        out[3] = (bb + padSrc).coerceIn(0, bmpH)
        return !isEmptyRect(out)
    }

    /** 空矩形判据: `[left, top, right, bottom]` 且 right/bottom 为开区间。 */
    fun isEmptyRect(a: IntArray): Boolean = a[2] <= a[0] || a[3] <= a[1]

    /** 清零成空矩形。 */
    fun clearRect(a: IntArray) {
        a[0] = 0
        a[1] = 0
        a[2] = 0
        a[3] = 0
    }

    /**
     * 把 `[l, t, r, b]` 并进累积脏区 [acc] = `[left, top, right, bottom]`。
     * 累积区为空时直接取新矩形; 传入空矩形是空操作。
     */
    fun unionInto(acc: IntArray, l: Int, t: Int, r: Int, b: Int) {
        if (r <= l || b <= t) return
        if (isEmptyRect(acc)) {
            acc[0] = l
            acc[1] = t
            acc[2] = r
            acc[3] = b
            return
        }
        if (l < acc[0]) acc[0] = l
        if (t < acc[1]) acc[1] = t
        if (r > acc[2]) acc[2] = r
        if (b > acc[3]) acc[3] = b
    }

    /**
     * 把 `[l, t, r, b]` 追加进离散脏区列表 [rects], 返回新的块数。
     * [rects] 每块 4 个 int (l, t, r, b, right/bottom 为开区间), 最多 [MAX_DISCRETE_RECTS] 块。
     *
     * 规则按顺序:
     *  1. 空输入 (r<=l 或 b<=t) 原样返回 [count];
     *  2. 并进去只多出不到 25% 的空心面积: 并进空心最小的那块。相交但空心更大的不并;
     *  3. 否则追加; 已满 8 块时先并掉面积增益最小的一对再追加, 块数永远不超过 8。
     *
     * 不能因为相交就收成一个包围盒。笔一帧里的历史点沿斜线重叠, 一收就是带四个空角的大框,
     * SRC 会把空角挖成方条。两个角上的笔尖更不能收成整张画布。
     */
    fun appendDiscrete(rects: IntArray, count: Int, l: Int, t: Int, r: Int, b: Int): Int {
        if (r <= l || b <= t) return count
        val n = count.coerceIn(0, MAX_DISCRETE_RECTS)
        if (rects.size < RECT_STRIDE) return n

        // 2. 只在空心不到 25% 时合并。斜线上互相重叠的笔尖空心更大, 保持多块。
        val area = (r - l).toLong() * (b - t).toLong()
        var best = -1
        var bestHollow = Long.MAX_VALUE
        for (i in 0 until n) {
            val o = i * RECT_STRIDE
            val ul = if (l < rects[o]) l else rects[o]
            val ut = if (t < rects[o + 1]) t else rects[o + 1]
            val ur = if (r > rects[o + 2]) r else rects[o + 2]
            val ub = if (b > rects[o + 3]) b else rects[o + 3]
            val merged = (ur - ul).toLong() * (ub - ut).toLong()
            val hollow = merged - area - rectArea(rects, o)
            if (hollow * HOLLOW_DEN < merged * HOLLOW_NUM && hollow < bestHollow) {
                bestHollow = hollow
                best = i
            }
        }
        if (best >= 0) {
            val o = best * RECT_STRIDE
            if (l < rects[o]) rects[o] = l
            if (t < rects[o + 1]) rects[o + 1] = t
            if (r > rects[o + 2]) rects[o + 2] = r
            if (b > rects[o + 3]) rects[o + 3] = b
            return n
        }

        // 4. 追加; 满了先并掉增益最小的一对再追加。
        val m = if (n >= MAX_DISCRETE_RECTS) mergeCheapestPair(rects, n) else n
        if ((m + 1) * RECT_STRIDE > rects.size) return m
        val o = m * RECT_STRIDE
        rects[o] = l
        rects[o + 1] = t
        rects[o + 2] = r
        rects[o + 3] = b
        return m + 1
    }

    /** 矩形 `[rects[o], rects[o+1], rects[o+2], rects[o+3])` 的面积。 */
    private fun rectArea(rects: IntArray, o: Int): Long =
        (rects[o + 2] - rects[o]).toLong() * (rects[o + 3] - rects[o + 1]).toLong()

    /**
     * 并掉面积增益最小的一对 (并后面积 - 两块面积之和), 返回新的块数。
     * 增益最小 = 两块最贴, 并掉它多出来的空心最少。
     */
    private fun mergeCheapestPair(rects: IntArray, count: Int): Int {
        if (count < 2) return count
        var bi = 0
        var bj = 1
        var bestGain = Long.MAX_VALUE
        for (i in 0 until count) {
            val oi = i * RECT_STRIDE
            val ai = rectArea(rects, oi)
            for (j in i + 1 until count) {
                val oj = j * RECT_STRIDE
                val ul = if (rects[oi] < rects[oj]) rects[oi] else rects[oj]
                val ut = if (rects[oi + 1] < rects[oj + 1]) rects[oi + 1] else rects[oj + 1]
                val ur = if (rects[oi + 2] > rects[oj + 2]) rects[oi + 2] else rects[oj + 2]
                val ub = if (rects[oi + 3] > rects[oj + 3]) rects[oi + 3] else rects[oj + 3]
                val gain = (ur - ul).toLong() * (ub - ut).toLong() - ai - rectArea(rects, oj)
                if (gain < bestGain) {
                    bestGain = gain
                    bi = i
                    bj = j
                }
            }
        }
        val oi = bi * RECT_STRIDE
        val oj = bj * RECT_STRIDE
        if (rects[oj] < rects[oi]) rects[oi] = rects[oj]
        if (rects[oj + 1] < rects[oi + 1]) rects[oi + 1] = rects[oj + 1]
        if (rects[oj + 2] > rects[oi + 2]) rects[oi + 2] = rects[oj + 2]
        if (rects[oj + 3] > rects[oi + 3]) rects[oi + 3] = rects[oj + 3]
        for (k in bj until count - 1) {
            val dst = k * RECT_STRIDE
            val src = (k + 1) * RECT_STRIDE
            rects[dst] = rects[src]
            rects[dst + 1] = rects[src + 1]
            rects[dst + 2] = rects[src + 2]
            rects[dst + 3] = rects[src + 3]
        }
        return count - 1
    }

    /**
     * 运笔按住期间是否继续保留前缓冲。
     *
     * 只看"还在画 + 覆盖层能接住 + 没有让位给变换/面板", 不按像素量设门槛 ——
     * 槽位图按屏幕分辨率烤, 与笔刷/画布大小无关。
     */
    fun holdWhileStroking(
        strokeHeld: Boolean,
        overlayReady: Boolean,
        transformActive: Boolean,
        panelsOpen: Boolean,
    ): Boolean = strokeHeld && overlayReady && !transformActive && !panelsOpen

    /**
     * 贴失败之后这一帧还能不能把画面继续留在前缓冲上 (即抑制主画布上传)。
     *
     * 贴失败且待贴列表仍有积压时不能 keep —— 置顶层没有新像素, 主画布又不再 invalidate,
     * 按住不抬就是两头落空的断墨。这种情况必须返回 false, 让主画布兜底刷新, 积压留着下一帧再贴。
     *
     * 反之 [presented] 为 false 但列表已经空了 (这一帧没有新像素可贴, 或脏区整块在视口外)
     * 说明置顶层内容仍是最新的, 这时继续 keep —— 否则会退化成每帧整张上传主画布。
     */
    fun keepAfterPresent(
        holdWhileStroking: Boolean,
        presented: Boolean,
        inkStillPending: Boolean,
    ): Boolean = holdWhileStroking && (presented || !inkStillPending)
}

/**
 * 与画布 onDraw 同一套位图→屏幕变换的快照, UI 线程发布、渲染线程只读。
 *
 * [halfW]/[halfH] 是显示位图的一半 (位图空间的中心原点); [bmpW]/[bmpH] 是显示位图尺寸。
 */
data class CanvasPose(
    val centerX: Float,
    val centerY: Float,
    val scale: Float,
    val rotation: Float,
    val signX: Float,
    val signY: Float,
    val halfW: Float,
    val halfH: Float,
    val bmpW: Int,
    val bmpH: Int,
)
