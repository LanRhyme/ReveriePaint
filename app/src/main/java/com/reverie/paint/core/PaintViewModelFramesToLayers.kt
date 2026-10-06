/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import android.graphics.Bitmap
import com.reverie.paint.R
import com.reverie.paint.model.FramesToLayersOps

// ============================================================
// 帧转图层: 一条轨道的若干帧 -> 每帧一个独立图层, 收进一个新建的组
//
// 与「图层转帧」相反的方向, 详见 FramesToLayersOps 的类注释。
// **全程不删层、不改已有图层**, 因此不会与引擎排队中的投影更新抢调度器
// (那正是「图层转帧」卡死的根因, 2026-10-05 真机取证)。
// ============================================================

/** 单帧 ARGB_8888 字节数。 */
private fun frameBytes(w: Int, h: Int): Long =
    if (w <= 0 || h <= 0) 0L else w.toLong() * h.toLong() * 4L

/**
 * 「帧转图层」的用户入口。
 *
 * ## 线程模型（改之前务必读完，这是本功能唯一真正难的地方）
 *
 * **整条链自始至终跑在渲染线程上，一环接一环，不绕主线程。**
 *
 * 踩过的坑：早先每环用 `runCore(after = { 下一环 })`，而 `runCore` 的 `after` 是
 * `mainHandler.post { after() }` —— 于是每一环都"渲染线程 → 主线程 → 渲染线程"绕一圈。
 * 真机表现为进度条永远 0%、一层都建不出来、utime 每 3 秒 +350（100% CPU 死循环）。
 * 手动连点「+」建 9 层不死，正是因为**没有这个往返**。
 *
 * ## 阶段划分（顺序不能改）
 *
 * 1. **结构阶段**：建组 → 连续建 N 个层。都是"改结构"，彼此可以连续做。
 * 2. **内容阶段**：逐个往已建好的层写像素。
 *
 * 为什么必须"先全建层、再全写像素"：写像素(`importKeyframeFromBitmap` → `setDirty`)
 * 会把一次投影更新排进引擎调度器，这时再做结构变更(`addLayer` 内部
 * `recompositeProjection`)就可能互等。两类操作彻底分开才不会撞锁。
 */
internal fun PaintViewModel.animationFramesToLayers(srcLayer: Int, times: List<Int>) {
    var toastRes = 0
    framesToLayersCreated = 0
    framesToLayersPlanned = 0
    framesToLayersNewIds.clear()
    runCore(render = false) {
        val plan = FramesToLayersOps.plan(
            srcLayer = srcLayer,
            times = times,
            keyframeTimes = ReverieCoreBridge.keyframeTimes(srcLayer).toList(),
            animatable = ReverieCoreBridge.layerAnimatable(srcLayer),
            locked = ReverieCoreBridge.layerLocked(srcLayer),
        )
        if (plan is FramesToLayersOps.Plan.Refused) {
            toastRes = when (plan.reason) {
                FramesToLayersOps.Reject.NO_FRAMES -> R.string.f2l_reject_no_frames
                FramesToLayersOps.Reject.TOO_MANY -> R.string.f2l_reject_too_many
                FramesToLayersOps.Reject.SOURCE_NOT_ANIMATABLE -> R.string.f2l_reject_not_animatable
                FramesToLayersOps.Reject.SOURCE_LOCKED -> R.string.f2l_reject_locked
                FramesToLayersOps.Reject.NOT_ANIMATED -> R.string.f2l_reject_no_frames
            }
            framesToLayersFinish(toastRes)
            return@runCore
        }
        val ready = plan as FramesToLayersOps.Plan.Ready
        framesToLayersPlanned = ready.times.size
        anim.framesToLayersDone = 0
        anim.framesToLayersTotal = ready.times.size
        // 从这里开始整条链都在渲染线程上, 不再回主线程
        framesToLayersMakeGroup(srcLayer, ready.times)
    }
}

/**
 * 结构阶段 A: 先建一个组收容。**必须在渲染线程调用。**
 *
 * 为什么建完组就能直接把层建进组里：`currentInsertPosition`(`ReverieCoreLayers.cpp`)
 * 的规则是——**当前选中层若是个组, 新层就插进这个组**；而 `addGroupLayer` 结束时
 * 会把 `m_currentLayer` 设成刚建好的组。于是"先建组、再连续 addLayer"天然就把
 * 所有新层装进组里了, **一个 move 操作都不需要**。
 *
 * 用户的原话是"要先给这些帧新增一个组用来放置，避免污染图层目录" —— 这一步就是它。
 */
private fun PaintViewModel.framesToLayersMakeGroup(srcLayer: Int, times: List<Int>) {
    framesToLayersSeqCounter++
    val name = appContext.getString(R.string.f2l_group, framesToLayersSeqCounter)
    val g = ReverieCoreBridge.addGroupLayer(name)
    if (g < 0) {
        framesToLayersFinish(R.string.f2l_reject_addgroup)
        return
    }
    // 读回组名(引擎可能因重名自动加了 " 2" 后缀), 然后把它设为**时间轴折叠**状态 ——
    // 拆完时间轴上就只剩组那一行, 子层不占行, 不污染视图。
    // 折叠从"组名 + 结构"推导, 不需要任何图层 id。
    val gname = ReverieCoreBridge.layerName(g)
    anim.timelineCollapsedGroups = anim.timelineCollapsedGroups + gname
    framesToLayersBuild(srcLayer, times, 0)
}

/** 结构阶段 B: 连续建 N 个层（会自动落进刚建的组里）。**必须在渲染线程调用。** */
private fun PaintViewModel.framesToLayersBuild(srcLayer: Int, times: List<Int>, index: Int) {
    if (index >= times.size) {
        framesToLayersFill(srcLayer, times, 0)
        return
    }
    val time = times[index]
    val before = ReverieCoreBridge.layerCount()
    ReverieCoreBridge.addLayer(FramesToLayersOps.layerName(time))
    val after = ReverieCoreBridge.layerCount()
    if (after <= before) {
        framesToLayersFinish(R.string.f2l_reject_addlayer)
        return
    }
    framesToLayersNewIds.add(Pair(after - 1, time))
    // 记下标即可 —— 新层是**连续**追加在队尾的, 后续插入只会在它**后面**长,
    // 不会把这批层之间的相对次序打乱。
    framesToLayersBuild(srcLayer, times, index + 1)
}

/** 内容阶段: 逐个往已建好的层里填像素。**必须在渲染线程调用。** */
private fun PaintViewModel.framesToLayersFill(srcLayer: Int, times: List<Int>, pick: Int) {
    if (pick >= times.size) {
        framesToLayersFinish(0)
        return
    }
    val pair = framesToLayersNewIds.getOrNull(pick)
    if (pair == null) {
        // 建层阶段没能建出这一层(理论上不该发生) —— 跳过, 继续填下一个
        framesToLayersFill(srcLayer, times, pick + 1)
        return
    }
    val (idx, time) = pair
    val transit = framesToLayersTransit ?: run {
        val w = coreW
        val h = coreH
        if (frameBytes(w, h) > 0L) {
            runCatching { Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888) }.getOrNull()
        } else {
            null
        }
    }
    if (transit == null) {
        framesToLayersFinish(R.string.f2l_reject_alloc)
        return
    }
    framesToLayersTransit = transit

    if (!ReverieCoreBridge.renderKeyframeFull(srcLayer, time, transit)) {
        framesToLayersFinish(R.string.f2l_reject_render, time + 1)
        return
    }
    // importKeyframeFromBitmap 需要有关键帧通道, 所以先开一条。
    // 引擎**没有**关闭图层动画的接口(只有 enableLayerAnimation 单向),
    // 所以新层会留下一条只有第 0 帧的轨道 —— 它不显示任何画面(单帧层就是常驻)。
    if (!ReverieCoreBridge.enableLayerAnimation(idx) ||
        !ReverieCoreBridge.importKeyframeFromBitmap(idx, 0, transit)
    ) {
        framesToLayersFinish(R.string.f2l_reject_write, time + 1)
        return
    }
    framesToLayersCreated++
    // 进度是 Compose 状态, 按本项目的既有约定回主线程写(引擎工作继续留在渲染线程)
    val done = framesToLayersCreated
    val total = times.size
    mainHandler.post { anim.framesToLayersDone = done; anim.framesToLayersTotal = total }
    framesToLayersFill(srcLayer, times, pick + 1)
}

/**
 * 收尾: 清进度、释放中转位图、刷新动画镜像、给提示。
 *
 * **它是唯一回到主线程的一步**（链本身跑在渲染线程上）。这么做有两个原因:
 * 1. 它要写 Compose 状态并弹提示, 按本项目既有约定这些都发生在主线程;
 * 2. 它之后**再没有任何引擎调用**, 所以"回主线程"不会造成之前那种往返撞锁。
 *
 * 值必须在**调用线程**先取好再 post —— 字段是跨线程读的, 不能留到主线程再读。
 */
private fun PaintViewModel.framesToLayersFinish(toastRes: Int, failArg: Any = 0) {
    val planned = framesToLayersPlanned
    framesToLayersNewIds.clear()
    releaseFramesToLayersTransit()
    syncAnimationFromNativeAfter()
    mainHandler.post {
        anim.framesToLayersTotal = 0
        anim.framesToLayersDone = 0
        if (toastRes != 0) {
            if (failArg is Int && failArg > 0) showActionToast(toastRes, null, failArg)
            else showActionToast(toastRes)
            return@post
        }
        // 时间轴折叠由"组名 + 结构"在绘制时推导, 这里不需要额外写状态
        // （组名已在建组那一步写进 anim.timelineCollapsedGroups）。
        val m = framesToLayersCreated
        showActionToast(
            if (FramesToLayersOps.isAllDone(m, planned)) R.string.f2l_toast_done
            else R.string.f2l_toast_partial,
            null,
            m,
        )
    }
}

/**
 * 释放帧转图层用的中转位图。
 *
 * 它跨整条链复用 —— 每次新建一个 20MB 的 Bitmap 再靠 GC 回收,
 * 拆几十帧就是几十次大对象分配, 在 6GB 设备上是实打实的 GC 压力。
 */
internal fun PaintViewModel.releaseFramesToLayersTransit() {
    framesToLayersTransit?.recycle()
    framesToLayersTransit = null
}
