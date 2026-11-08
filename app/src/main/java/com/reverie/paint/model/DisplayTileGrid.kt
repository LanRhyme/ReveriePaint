/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

/**
 * 512 显示瓦片网格的**纯几何** (无 Android 依赖, 可 JVM 单测)。
 *
 * 整张显示位图 (w x h) 换成覆盖全文档的固定网格, 瓦片边长 [TILE]。边角瓦片按实际
 * 余数分配尺寸, 总像素严格等于 `w * h` —— 换网格不额外膨胀显存, 但每帧只有脏区命中的
 * 那几张瓦片会被 `lockPixels` 重写, 其余瓦片的 `Bitmap` 引用原封不动, HWUI 的纹理缓存
 * 直接命中。
 *
 * 坐标一律是**文档/显示位图像素** (1:1 渲染路径下两者相同)。矩形用 `[left, top, right,
 * bottom)`, right/bottom 为开区间。
 */
class DisplayTileGrid(
    val width: Int,
    val height: Int,
    val tileSize: Int = TILE,
) {
    init {
        require(tileSize > 0) { "tileSize must be positive" }
    }

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
