/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import android.graphics.Bitmap
import com.reverie.paint.model.DisplayTileGrid
import java.util.concurrent.atomic.AtomicIntegerArray
import java.util.concurrent.atomic.AtomicReferenceArray

/**
 * 一份已发布的显示瓦片网格: 几何与瓦片数组成对发布, onDraw 读到的两者必然属于同一份网格。
 *
 * 瓦片数组用 [AtomicReferenceArray]: 渲染线程 set 元素、UI 线程 onDraw get 元素, 每次 set 自带
 * volatile 语义 —— 普通数组的元素写在 ARM 上没有 happens-before 保证, onDraw 可能读到旧引用。
 */
internal class PublishedTiles(
    val grid: DisplayTileGrid,
    val tiles: AtomicReferenceArray<Bitmap?>,
) {
    /**
     * 每张瓦片当前位图自换入以来有没有交给过 HWUI: [TILE_UNDRAWN] / [TILE_DRAWN] /
     * [TILE_WRITING] (渲染线程正在就地写)。没交给过的位图不在任何 DisplayList 里,
     * 渲染线程可以就地增量写, 省掉后备位图与整块拷贝 (运笔按住前缓冲时主画布不重绘, 最常见)。
     */
    val drawnByView = AtomicIntegerArray(grid.tileCount)

    /** UI 线程: 这张瓦片要交给 HWUI 了 (onDraw 画它之前调用)。 */
    fun markDrawn(idx: Int) {
        if (idx in 0 until drawnByView.length()) drawnByView.set(idx, TILE_DRAWN)
    }

    companion object {
        const val TILE_UNDRAWN = 0
        const val TILE_DRAWN = 1
        const val TILE_WRITING = 2
    }
}

/**
 * 512 显示瓦片网格的状态 (见 [PaintViewModel.doRenderTiles])。
 *
 * 线程分工:
 *  - 除 [requestDiscard] 外的一切写操作只在渲染线程 (reverie-render) 上发生;
 *  - UI 线程只读 [drawable] 返回的发布快照, 以及调用 [requestDiscard]。
 *
 * 双缓冲铁律在瓦片粒度上成立: 交给过 HWUI 的瓦片位图绝不会被就地重写。
 * 1. 写时复制 (COW): 一张瓦片要更新且已被 UI 线程标记为 TILE_DRAWN 时，渲染线程必须从后备池取新位图，
 *    写完后原子换进 tiles 数组;
 * 2. 3 级多帧冷却队列 (Stage 0 -> Stage 1 -> Stage 2 -> spareReady):
 *    Android HWUI 架构下 DisplayList 提交到 RenderThread 实际消费存在 2~3 帧时延。换下的旧位图
 *    必须在冷却阶段完整推移至少 2 帧，确认 HWUI 已释放纹理引用后，才允许进入 spareReady 复用；
 * 3. CAS 就地写例外: 仅当瓦片自换入以来从未被 onDraw 读取（保持 TILE_UNDRAWN，不存于任何 DisplayList）时，
 *    允许通过 CAS 原子抢占为 TILE_WRITING 并原地增量写入。
 */
internal class DisplayTileState {
    /** 当前发布给 onDraw 的网格; null = 画布走整图路径 (或网格尚未建立)。 */
    @Volatile
    var published: PublishedTiles? = null
        private set

    // 重建中的网格: 尺寸/文档变化时新网格先在这里填像素, 全部写完之后才在帧末发布 ——
    // 未填像素的新网格一旦被 onDraw 读到, 画面上就是"画布裂成几块"。只在渲染线程读写。
    var staging: PublishedTiles? = null
        private set

    // 换文档时 UI 线程要求"立刻别再画旧网格": UI 线程只自增 [discardRequested],
    // 真正的清理投递到渲染线程, 完成后由渲染线程把 [discardHandled] 追平。
    // 两者不等期间 onDraw 不画瓦片, 所以网格本身始终只被渲染线程修改。
    @Volatile
    private var discardRequested = 0

    @Volatile
    private var discardHandled = 0

    val spareReady = ArrayDeque<Bitmap>()
    // 3 级多帧冷却队列：彻底覆盖 HWUI DisplayList 提交到 RenderThread 实际消费的 2~3 帧时延窗口。
    // 新换下的位图先进入 stage0, 随后逐帧推移 stage0 -> stage1 -> stage2 -> spareReady。
    // 确保任何从 COW 换下的位图在冷却区至少经历 2 次完整帧迭代才允许被重写复用。
    val spareCoolingStage0 = ArrayList<Bitmap>(SPARE_CAP)
    val spareCoolingStage1 = ArrayList<Bitmap>(SPARE_CAP)
    val spareCoolingStage2 = ArrayList<Bitmap>(SPARE_CAP)

    /** 退休一张位图进入冷却期 (从 stage0 开始)。 */
    fun retireToCooling(b: Bitmap) {
        if (!b.isRecycled) {
            spareCoolingStage0.add(b)
        }
    }

    // 瓦片渲染重试连续失败计数 (用于熔断防护，避免 OOM 期间全速空转烧 CPU)
    var consecutiveRetryFailures = 0

    // 网格每帧复用的一次性缓冲 (热路径零分配)。
    var needsFullWrite = BooleanArray(0)
        private set
    var touched = BooleanArray(0)
        private set
    var hitList = IntArray(0)
        private set
    var hitScratch = IntArray(0)
        private set
    val dirtyRects = IntArray(DIRTY_CAP * 4)
    val written = IntArray(4)

    // 参考窗口需要"一张整图": 瓦片路径下按需拼进快照。两张交替使用, 渲染线程
    // 永远不写 UI 当前可能在画的那一张 (双缓冲)。
    private val snapshots = arrayOfNulls<Bitmap>(2)
    private var snapshotFlip = 0
    var lastSnapshotMs = 0L

    /** UI 线程读: 可以画的网格快照; 换文档的清理尚未完成时返回 null。 */
    fun drawable(): PublishedTiles? {
        if (discardHandled != discardRequested) return null
        val p = published ?: return null
        return if (p.grid.isEmpty) null else p
    }

    /** 渲染线程: 是否持有任何网格 (已发布或暂存)。 */
    val hasAnyGrid: Boolean get() = published != null || staging != null

    /** UI 线程: 要求作废当前网格, 返回本次请求的代际号 (交给渲染线程的清理任务回填)。 */
    fun requestDiscard(): Int {
        val gen = discardRequested + 1
        discardRequested = gen
        return gen
    }

    /** 渲染线程: 清理完成后回填代际号。 */
    fun markDiscardHandled(gen: Int) {
        if (gen - discardHandled > 0) discardHandled = gen
    }

    /** 渲染线程: 开一份新的暂存网格并重置账本。 */
    fun beginStaging(grid: DisplayTileGrid, tiles: AtomicReferenceArray<Bitmap?>) {
        val count = grid.tileCount
        staging = PublishedTiles(grid, tiles)
        needsFullWrite = BooleanArray(count) { true }
        touched = BooleanArray(count)
        hitList = IntArray(count)
        hitScratch = IntArray(2 * count)
        spareReady.clear()
        spareCoolingStage0.clear()
        spareCoolingStage1.clear()
        spareCoolingStage2.clear()
    }

    /** 渲染线程: 暂存网格的像素全部写好, 发布给 onDraw。 */
    fun publishStaging() {
        val s = staging ?: return
        published = s
        staging = null
    }

    /** 渲染线程: 已有完整网格时丢掉多余的暂存。 */
    fun dropStaging() {
        staging = null
    }

    /** 渲染线程: 丢掉全部网格与池 (切回整图路径 / 换文档)。 */
    fun clear() {
        published = null
        staging = null
        needsFullWrite = BooleanArray(0)
        touched = BooleanArray(0)
        hitList = IntArray(0)
        hitScratch = IntArray(0)
        spareReady.clear()
        spareCoolingStage0.clear()
        spareCoolingStage1.clear()
        spareCoolingStage2.clear()
        snapshots[0] = null
        snapshots[1] = null
        consecutiveRetryFailures = 0
    }

    /** 渲染线程: 每帧开头推移多帧冷却阶段，最老的 stage2 晋升进入 spareReady。 */
    fun promoteCooledSpares() {
        if (!spareCoolingStage2.isEmpty()) {
            for (b in spareCoolingStage2) {
                if (spareReady.size < SPARE_CAP && !b.isRecycled) {
                    spareReady.addLast(b)
                }
            }
            spareCoolingStage2.clear()
        }
        if (!spareCoolingStage1.isEmpty()) {
            spareCoolingStage2.addAll(spareCoolingStage1)
            spareCoolingStage1.clear()
        }
        if (!spareCoolingStage0.isEmpty()) {
            spareCoolingStage1.addAll(spareCoolingStage0)
            spareCoolingStage0.clear()
        }
    }

    /** 渲染线程: 从后备池取一张尺寸匹配的位图; 池里没有就新分配 (OOM 返回 null)。 */
    fun obtainSpare(w: Int, h: Int): Bitmap? {
        for (i in 0 until spareReady.size) {
            val b = spareReady[i]
            if (!b.isRecycled && b.width == w && b.height == h) {
                spareReady.removeAt(i)
                return b
            }
        }
        return try {
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        } catch (_: OutOfMemoryError) {
            null
        }
    }

    /** 渲染线程: 取下一张可写的快照位图 (不是 UI 当前可能在画的那张)。 */
    fun nextSnapshotTarget(w: Int, h: Int): Bitmap? {
        snapshotFlip = 1 - snapshotFlip
        var snap = snapshots[snapshotFlip]
        if (snap == null || snap.isRecycled || snap.width != w || snap.height != h) {
            snap =
                try {
                    Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                } catch (_: OutOfMemoryError) {
                    null
                }
            snapshots[snapshotFlip] = snap
        }
        return snap
    }

    companion object {
        /** 一帧最多带回的离散脏区块数 (与引擎侧 DirtyRectList 容量一致)。 */
        const val DIRTY_CAP = 8

        /** 后备池容量。 */
        const val SPARE_CAP = 24

        /** 参考窗口快照在连续出帧时的最小间隔。 */
        const val SNAPSHOT_MIN_MS = 250L
    }
}
