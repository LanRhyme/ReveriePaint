/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/* ============================================================
 * RenderLockGate.h - 图像被撤销宏锁住时, 帧准备/等待该怎么做 (纯逻辑, 无 Qt / Krita 依赖)
 *
 * 对称笔画在落笔时就开了撤销宏 (CanvasTouchView.safeBeginSymmetryUndoMacro ->
 * ReverieCore::beginUndoMacro -> KisLegacyUndoAdapter::beginMacro), 而 Krita 的
 * legacy 撤销适配器在第一层宏上会 KisImage::barrierLock(), 直到 endMacro 才 unlock。
 * KisImage::isIdle() 的默认参数 allowLocked=false 会把"被锁住"当成"忙",
 * 于是整笔期间 beginTileFrame / renderToBuffer 都走 wasAsyncBusy 分支:
 * 不合成脏区, 却照样把脏区取走清空, 瓦片拷的是没更新的旧投影。主笔逐批落的墨
 * 只进了图层设备, 没进投影, 抬笔后只剩最后一次 flush 的那一截。
 *
 * 锁住期间的两条硬约束 (kis_update_scheduler.cpp):
 *   1) 不要查询调度器是否空闲。KisImage::isIdle(true) 一定会调
 *      KisUpdateScheduler::isIdle(), 后者在队列空时 tryBarrierLock() + unlock(false),
 *      unlock 把 barrierLock 设下的 processingBlocked 清成 false 并 processQueues():
 *      图像还 locked(), 调度器却已放行, 之后入队的任务会有 worker 和我们并发写投影。
 *      所以锁住时只看 KisImage::locked() (无副作用), 未锁时才问 isIdle()。
 *   2) 不要 waitForDone。processingBlocked 时 processQueues() 直接返回,
 *      队列里只要有任务, KisUpdateScheduler::waitForDone 的 do/while 永远排不空 (卡死)。
 *      锁住期间入队的任务会在 endMacro -> unlock 时由调度器正常派发。
 *
 * 锁住时同步合成为什么安全: barrierLock 返回前已把队列排空、worker 跑完
 * (KisUpdateScheduler::barrierLock 的 do/while), 且 processingBlocked 一直保持 true
 * (前提是没人违反约束 1), 期间没有 worker 会写投影。
 * ============================================================ */
#ifndef REVERIE_RENDER_LOCK_GATE_H
#define REVERIE_RENDER_LOCK_GATE_H

namespace reverie {

struct RenderGate {
    // true: 先 image->waitForDone(), 本帧直接用 Krita 合并器的结果, 不再自己合成脏区。
    // false: 本帧由 compositeDirtyRegions 同步合成脏区。
    bool waitForMerger = false;
};

/**
 * imageLocked:   KisImage::locked() (无副作用)
 * schedulerIdle: 可调用对象, 返回 KisImage::isIdle()。只在未锁时调用, 锁住时绝不调用 (约束 1)。
 */
template <typename SchedulerIdleQuery>
inline RenderGate renderGateFor(bool imageLocked, SchedulerIdleQuery &&schedulerIdle)
{
    RenderGate g;
    if (imageLocked) {
        g.waitForMerger = false;   // 约束 2: 不等; 同步合成
        return g;
    }
    g.waitForMerger = !schedulerIdle();
    return g;
}

/** 本线程此刻能不能 image->waitForDone() (约束 2)。 */
inline bool mayWaitForImage(bool imageLocked)
{
    return !imageLocked;
}

} // namespace reverie

#endif // REVERIE_RENDER_LOCK_GATE_H
