/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

/**
 * 前缓冲真墨呈现 (续 #81) 在渲染线程一侧的接口。
 *
 * 实现在 UI 层 (画布视图持有), 通过 [PaintViewModel.realInkSink] 注册 —— core 只认这个接口,
 * 不引用 ui 包。除 [wantsHold] 可在任意线程读之外, 其余方法都在渲染线程 (reverie-render)
 * 上调用, 必须零分配。
 */
internal interface RealInkSink {
    /** 这一笔仍按住前缓冲, 且这一帧可以由前缓冲接住 (主画布不必逐帧失效)。 */
    fun wantsHold(): Boolean

    /**
     * 瓦片帧发布之后调用: 把本帧离散脏区 ([rects] 每块 `x, y, w, h`, 共 [count] 块) 夹进本帧写入
     * 范围 `[writtenLeft, writtenTop, writtenRight, writtenBottom)`, 从 [tiles] 取像素贴上前缓冲。
     * [anyFull] = 本帧有瓦片整块重写 (网格重建等), 真墨必须让位给主画布。
     *
     * 返回 true 表示前缓冲接住了这一帧, 调用方跳过主画布失效。
     */
    fun presentTileFrame(
        tiles: PublishedTiles,
        rects: IntArray,
        count: Int,
        writtenLeft: Int,
        writtenTop: Int,
        writtenRight: Int,
        writtenBottom: Int,
        anyFull: Boolean,
    ): Boolean

    /** 每帧末尾回报这一帧是否留在前缓冲上 (整图路径也要报), 驱动抬笔后的追赶清除。 */
    fun finishFrame(kept: Boolean)

    /** 这一笔已经交给引擎收尾 (touchStrokeEnd 之后), 松开前缓冲, 主画布追平。 */
    fun onStrokeCommitted()
}
