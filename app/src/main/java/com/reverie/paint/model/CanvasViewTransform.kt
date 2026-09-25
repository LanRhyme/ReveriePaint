/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * 画布视图变换: 文档坐标 / 渲染位图坐标 <-> 视图(屏幕)坐标。
 *
 * 与 ui 层 [imageToWidget] / [widgetToImage] 使用同一套公式 (见 CanvasView.kt),
 * 区别在于:
 *  - 视图参数缓存在对象里, 三角函数只在参数变化时算一次;
 *  - 结果通过 `out` 数组写出, 调用方每帧批量变换点 (镜像笔迹 / 对称光标 /
 *    脏区失效) 时不产生任何分配 (AGENTS.md §4 热路径零分配)。
 *
 * 纯 Kotlin 实现, 无 Android 依赖, 可 JVM 单测。
 */
class CanvasViewTransform {

    // ---- 输入参数 (上一次 update 的值) ----
    private var viewW = 1f
    private var viewH = 1f
    private var panX = 0f
    private var panY = 0f
    private var zoom = 1f
    private var fitScale = 1f
    private var rotation = 0f
    private var bmpW = 1
    private var bmpH = 1
    private var docW = 1
    private var docH = 1

    // ---- 派生量 (update 时重算) ----
    private var scale = 1f
    private var cosR = 1f
    private var sinR = 0f
    private var bmpPerDocX = 1f
    private var bmpPerDocY = 1f
    private var docPerBmpX = 1f
    private var docPerBmpY = 1f
    private var centerX = 0f
    private var centerY = 0f
    private var halfBmpW = 0f
    private var halfBmpH = 0f

    /** 当前视图是否可视为"无旋转" (局部失效等场景要求轴对齐) */
    val isAxisAligned: Boolean get() = abs(rotation) < 0.5f

    /** 当前生效的缩放 (zoom * fitScale, 已做下限保护) */
    val currentScale: Float get() = scale

    /**
     * 刷新视图参数。返回 true 表示参数确实变化并重算了派生量,
     * 调用方据此决定是否要重建依赖矩阵的缓存。
     */
    fun update(
        viewW: Int,
        viewH: Int,
        panX: Float,
        panY: Float,
        zoom: Float,
        fitScale: Float,
        rotation: Float,
        bmpW: Int,
        bmpH: Int,
        docW: Int,
        docH: Int,
    ): Boolean {
        val bw = maxOf(1, bmpW)
        val bh = maxOf(1, bmpH)
        val dw = maxOf(1, docW)
        val dh = maxOf(1, docH)
        val same =
            this.viewW == viewW.toFloat() &&
                this.viewH == viewH.toFloat() &&
                this.panX == panX &&
                this.panY == panY &&
                this.zoom == zoom &&
                this.fitScale == fitScale &&
                this.rotation == rotation &&
                this.bmpW == bw &&
                this.bmpH == bh &&
                this.docW == dw &&
                this.docH == dh
        if (same) return false

        this.viewW = viewW.toFloat()
        this.viewH = viewH.toFloat()
        this.panX = panX
        this.panY = panY
        this.zoom = zoom
        this.fitScale = fitScale
        this.rotation = rotation
        this.bmpW = bw
        this.bmpH = bh
        this.docW = dw
        this.docH = dh

        scale = (zoom * fitScale).coerceAtLeast(0.001f)
        val radians = Math.toRadians(rotation.toDouble())
        cosR = cos(radians).toFloat()
        sinR = sin(radians).toFloat()
        bmpPerDocX = bw.toFloat() / dw
        bmpPerDocY = bh.toFloat() / dh
        docPerBmpX = dw.toFloat() / bw
        docPerBmpY = dh.toFloat() / bh
        centerX = this.viewW / 2f + panX
        centerY = this.viewH / 2f + panY
        halfBmpW = bw / 2f
        halfBmpH = bh / 2f
        return true
    }

    /** 文档坐标 -> 屏幕坐标。out[0]=x, out[1]=y */
    fun docToScreen(x: Float, y: Float, out: FloatArray) {
        val bx = x * bmpPerDocX - halfBmpW
        val by = y * bmpPerDocY - halfBmpH
        val sx = bx * scale
        val sy = by * scale
        out[0] = sx * cosR - sy * sinR + centerX
        out[1] = sx * sinR + sy * cosR + centerY
    }

    /** 渲染位图坐标 -> 屏幕坐标。out[0]=x, out[1]=y */
    fun bitmapToScreen(x: Float, y: Float, out: FloatArray) {
        val sx = (x - halfBmpW) * scale
        val sy = (y - halfBmpH) * scale
        out[0] = sx * cosR - sy * sinR + centerX
        out[1] = sx * sinR + sy * cosR + centerY
    }

    /** 屏幕坐标 -> 渲染位图坐标。out[0]=x, out[1]=y */
    fun screenToBitmap(sx: Float, sy: Float, out: FloatArray) {
        val dx = sx - centerX
        val dy = sy - centerY
        // 逆旋转: 等价于按 -rotation 旋转 (cos(-r)=cos(r), sin(-r)=-sin(r))
        val ux = dx * cosR + dy * sinR
        val uy = -dx * sinR + dy * cosR
        out[0] = ux / scale + halfBmpW
        out[1] = uy / scale + halfBmpH
    }

    /** 屏幕坐标 -> 文档坐标。out[0]=x, out[1]=y */
    fun screenToDoc(sx: Float, sy: Float, out: FloatArray) {
        val dx = sx - centerX
        val dy = sy - centerY
        // 逆旋转: 等价于按 -rotation 旋转 (cos(-r)=cos(r), sin(-r)=-sin(r))
        val ux = dx * cosR + dy * sinR
        val uy = -dx * sinR + dy * cosR
        out[0] = (ux / scale + halfBmpW) * docPerBmpX
        out[1] = (uy / scale + halfBmpH) * docPerBmpY
    }

    /**
     * 渲染位图矩形 -> 屏幕轴对齐包围盒 (int)。
     *
     * 外扩两处, 缺一都会留下残影:
     *  - 位图空间按 `1 + 1/scale` 外扩: 画布位图走 GPU 采样 (放大时是双线性),
     *    脏区**之外**的源像素会参与采样, 影响范围随放大倍数增长 —— 只外扩 1 个
     *    屏幕像素的话, 8 倍放大时脏区边上会留 8 像素宽的旧像素;
     *  - 屏幕空间再各外扩 1px, 吸收 floor/ceil 取整误差。
     *
     * 旋转时取四角包围盒 —— 只会偏大不会偏小, 因此作为 invalidate 区域安全。
     * out[0]=left, out[1]=top, out[2]=right, out[3]=bottom (right/bottom 为开区间)。
     */
    fun bitmapRectToScreenBounds(left: Float, top: Float, right: Float, bottom: Float, out: IntArray) {
        val pad = 1f + 1f / scale
        val pl = left - pad
        val pt = top - pad
        val pr = right + pad
        val pb = bottom + pad
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (i in 0 until 4) {
            val x = if (i == 0 || i == 2) pl else pr
            val y = if (i < 2) pt else pb
            val sx = (x - halfBmpW) * scale
            val sy = (y - halfBmpH) * scale
            val px = sx * cosR - sy * sinR + centerX
            val py = sx * sinR + sy * cosR + centerY
            if (px < minX) minX = px
            if (px > maxX) maxX = px
            if (py < minY) minY = py
            if (py > maxY) maxY = py
        }
        out[0] = floor(minX).toInt() - 1
        out[1] = floor(minY).toInt() - 1
        out[2] = ceil(maxX).toInt() + 1
        out[3] = ceil(maxY).toInt() + 1
    }
}
