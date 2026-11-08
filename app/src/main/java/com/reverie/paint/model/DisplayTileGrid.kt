/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

/**
 * 512 显示瓦片网格的**纯几何** (无 Android 依赖, 可 JVM 单测)。
 *
 * 网格覆盖整份文档, 瓦片边长 [TILE] (显示像素)。每帧只有脏区命中的那几张瓦片会被
 * `lockPixels` 重写, 其余瓦片的 `Bitmap` 引用原封不动, HWUI 的纹理缓存直接命中。
 *
 * **两级坐标**:
 *  - 文档坐标 (`docW x docH`, 场景/手势/覆盖层统一用它);
 *  - 显示坐标 (`width x height` = 文档像素 / [docScale]), 即瓦片位图的像素空间。
 * [docScale] = 1 时两者相同 (1:1 路径); > 1 时是**视口 LoD**: 缩放视图下按 1/2、
 * 1/4、1/8 合成, 显示显存与每帧纹理上传都按 `docScale²` 下降, 代价是这一层不含
 * 文档的全部像素细节 (放大回来会切回 1:1 重建网格)。
 *
 * 矩形用 `[left, top, right, bottom)`, right/bottom 为开区间。
 */
class DisplayTileGrid(
    /** 文档宽 (文档像素)。 */
    val docW: Int,
    /** 文档高 (文档像素)。 */
    val docH: Int,
    val tileSize: Int = TILE,
    /** 文档像素 / 显示像素; 1 = 1:1, 2/4/8 = LoD。 */
    val docScale: Int = 1,
) {
    init {
        require(tileSize > 0) { "tileSize must be positive" }
        require(docScale > 0) { "docScale must be positive" }
    }

    /** 显示像素宽 (瓦片位图空间的宽度)。 */
    val width: Int = if (docScale <= 1) docW else if (docW <= 0) 0 else (docW + docScale - 1) / docScale

    /** 显示像素高。 */
    val height: Int = if (docScale <= 1) docH else if (docH <= 0) 0 else (docH + docScale - 1) / docScale

    val cols: Int = if (width > 0) (width + tileSize - 1) / tileSize else 0

    val rows: Int = if (height > 0) (height + tileSize - 1) / tileSize else 0

    val tileCount: Int get() = cols * rows

    /** 网格是否可用 (空文档没有瓦片)。 */
    val isEmpty: Boolean get() = tileCount == 0

    fun tileLeft(tx: Int): Int = tx * tileSize

    fun tileTop(ty: Int): Int = ty * tileSize

    /** 第 [tx] 列的瓦片宽: 整列 [tileSize], 末列取余数。 */
    fun tileWidth(tx: Int): Int = (width - tx * tileSize).coerceAtMost(tileSize)

    /** 第 [ty] 行的瓦片高: 整行 [tileSize], 末行取余数。 */
    fun tileHeight(ty: Int): Int = (height - ty * tileSize).coerceAtMost(tileSize)

    fun tileIndex(tx: Int, ty: Int): Int = ty * cols + tx

    fun contains(tx: Int, ty: Int): Boolean = tx in 0 until cols && ty in 0 until rows

    /** 包含文档像素 `(x, y)` 的瓦片序号; 越界返回 -1。 */
    fun tileIndexAt(x: Int, y: Int): Int {
        if (x < 0 || y < 0 || x >= width || y >= height) return -1
        return tileIndex(x / tileSize, y / tileSize)
    }

    /** 瓦片矩形写进 [out] = `[left, top, right, bottom)`; 越界瓦片清零。 */
    fun tileRectInto(tx: Int, ty: Int, out: IntArray) {
        if (!contains(tx, ty)) {
            out[0] = 0
            out[1] = 0
            out[2] = 0
            out[3] = 0
            return
        }
        out[0] = tileLeft(tx)
        out[1] = tileTop(ty)
        out[2] = out[0] + tileWidth(tx)
        out[3] = out[1] + tileHeight(ty)
    }

    /** [tileRectInto] 的分配版, 只给测试与非热路径用。 */
    fun tileRect(tx: Int, ty: Int): IntArray {
        val out = IntArray(4)
        tileRectInto(tx, ty, out)
        return out
    }

    /** 第 [tx] 列瓦片在**文档**坐标下的左边界。 */
    fun docLeft(tx: Int): Int = tileLeft(tx) * docScale

    /** 第 [ty] 行瓦片在**文档**坐标下的上边界。 */
    fun docTop(ty: Int): Int = tileTop(ty) * docScale

    /** 第 [tx] 列瓦片在文档坐标下的宽度 (末列裁到 docW)。 */
    fun docTileWidth(tx: Int): Int {
        val l = docLeft(tx)
        return (docW - l).coerceIn(0, tileWidth(tx) * docScale)
    }

    /** 第 [ty] 行瓦片在文档坐标下的高度 (末行裁到 docH)。 */
    fun docTileHeight(ty: Int): Int {
        val t = docTop(ty)
        return (docH - t).coerceIn(0, tileHeight(ty) * docScale)
    }

    /** 瓦片 [tx,ty] 的文档矩形写进 [out] = `[l,t,r,b)`。 */
    fun docTileRectInto(tx: Int, ty: Int, out: IntArray) {
        if (!contains(tx, ty)) {
            out[0] = 0; out[1] = 0; out[2] = 0; out[3] = 0
            return
        }
        out[0] = docLeft(tx)
        out[1] = docTop(ty)
        out[2] = out[0] + docTileWidth(tx)
        out[3] = out[1] + docTileHeight(ty)
    }

    /**
     * 与**文档**矩形相交的瓦片 (LoD 下 [tilesIntersecting] 要的是显示坐标, 这里直接吃文档
     * 坐标: 脏区从引擎来就是文档坐标)。
     */
    fun tilesIntersectingDoc(
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        out: IntArray,
    ): Int {
        if (docScale == 1) return tilesIntersecting(left, top, right, bottom, out)
        if (right <= left || bottom <= top || isEmpty) return 0
        val l = left.coerceAtLeast(0) / docScale
        val t = top.coerceAtLeast(0) / docScale
        val r = ((right.coerceAtMost(docW) + docScale - 1) / docScale)
        val b = ((bottom.coerceAtMost(docH) + docScale - 1) / docScale)
        if (r <= l || b <= t) return 0
        return tilesIntersecting(l, t, r, b, out)
    }

    /**
     * 与文档矩形 `[left, top, right, bottom)` 相交的瓦片, 按行主序写进 [out] (每瓦片两个
     * int: `tx, ty`), 返回瓦片数。矩形与文档不相交 (或本身为空) 时返回 0 且不写 [out]。
     *
     * 调用方给足 `2 * tileCount` 的容量即可 (热路径复用数组, 不分配)。
     */
    fun tilesIntersecting(
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        out: IntArray,
    ): Int {
        if (right <= left || bottom <= top || isEmpty) return 0
        val l = left.coerceAtLeast(0)
        val t = top.coerceAtLeast(0)
        val r = right.coerceAtMost(width)
        val b = bottom.coerceAtMost(height)
        if (r <= l || b <= t) return 0
        val tx0 = l / tileSize
        val ty0 = t / tileSize
        // 开区间右/下边界: 正好落在瓦片边界上的矩形不该多命中一列/一行。
        val tx1 = (r - 1) / tileSize
        val ty1 = (b - 1) / tileSize
        var n = 0
        for (ty in ty0..ty1) {
            for (tx in tx0..tx1) {
                out[n * 2] = tx
                out[n * 2 + 1] = ty
                n++
            }
        }
        return n
    }

    companion object {
        /** 瓦片边长 (像素)。512 是 HWUI 纹理上传粒度与 4K 脏区大小的折中。 */
        const val TILE = 512

        /**
         * 瓦片网格默认显存预算 (字节)。超预算的画布回退整图双缓冲路径。
         *
         * 320MB ≈ 80MP 文档 (6400x10240 = 65.5MP / 250MB 仍在预算内)。这个数字不是"性能最优",
         * 而是"比整图路径更省的上限": 同样一份 6400x10240 文档, 整图双缓冲要 front + back 各
         * 250MB, 再加上 2048 分块的 20 张副本 ~313MB (实测真机直接进 swap); 瓦片网格只有一份
         * 250MB, 而且每帧只重写脏区命中的那 1MB 瓦片。
         */
        const val DEFAULT_TILE_BUDGET_BYTES = 320L * 1024L * 1024L

        /** 整张显示瓦片的字节数 (ARGB_8888)。瓦片严格铺满文档, 故等于 `w * h * 4`。 */
        fun displayBytes(width: Int, height: Int): Long {
            if (width <= 0 || height <= 0) return 0L
            return width.toLong() * height.toLong() * 4L
        }

        /**
         * 这一帧是否走瓦片路径。只有文档与显示缓冲 1:1 时才走: 缩放回退路径的映射与网格
         * 不同源; 动画播放按帧缓存整图; 回放页直接画整图。
         *
         * **大画布 (超过 HWUI 100MB 单图阈值) 同样走瓦片路径**: 单张瓦片固定 512x512 = 1MB,
         * 与 HWUI 的单图上限无关; 而整图路径在超大画布上要为 front/back 各留一份全幅位图,
         * 再叠一份 2048 分块的「整图副本」(见 `LargeBitmapTilingPolicy`), 且每帧要把脏区
         * 从全幅位图 memcpy 进 16MB 的分块 —— 用户实测 6400x10240 (256MB) 文档在整图路径下
         * 基本无法操作。这里改为只按**总显存预算**判定 ([DisplayTileGrid.displayBytes] <=
         * [budgetBytes]), 超过预算 (例如 10000x20000) 才退回整图路径。
         */
        fun shouldUse(
            renderW: Int,
            renderH: Int,
            coreW: Int,
            coreH: Int,
            animationPlaying: Boolean,
            replayPage: Boolean,
            budgetBytes: Long = DEFAULT_TILE_BUDGET_BYTES,
        ): Boolean {
            if (renderW <= 0 || renderH <= 0) return false
            if (renderW != coreW || renderH != coreH) return false
            if (animationPlaying || replayPage) return false
            if (budgetBytes <= 0L) return false
            return displayBytes(renderW, renderH) <= budgetBytes
        }
    }
}
