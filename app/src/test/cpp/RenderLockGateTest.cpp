/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/* ============================================================
 * RenderLockGateTest.cpp - 宿主机单测 (无 Qt / 无 Android / 无 Krita)
 *
 * 编译运行:
 *   wsl.exe -d Ubuntu-22.04 -- g++ -std=c++17 -I app/src/main/cpp \
 *       app/src/test/cpp/RenderLockGateTest.cpp -o /tmp/render_gate_test \
 *       && /tmp/render_gate_test
 *
 * 第二部分用一个最小的调度器模型复刻 kis_update_scheduler.cpp 里
 * barrierLock / tryBarrierLock / unlock / isIdle / waitForDone 对 processingBlocked
 * 的处理, 验证"锁住时绝不查询调度器"能让阻塞保持住, 而旧写法 isIdle(true) 会把它解开。
 * ============================================================ */
#include "RenderLockGate.h"

#include <cstdio>

using reverie::RenderGate;
using reverie::renderGateFor;
using reverie::mayWaitForImage;

static int g_failures = 0;
static int g_checks = 0;

static void check(bool ok, const char *what)
{
    ++g_checks;
    std::printf("  [%s] %s\n", ok ? "PASS" : "FAIL", what);
    if (!ok) {
        ++g_failures;
    }
}

// ---- kis_update_scheduler.cpp 的最小模型 (只模拟 processingBlocked 与队列长度) ----
struct FakeScheduler {
    int queued = 0;               // updatesQueue + strokesQueue 里的任务数
    bool processingBlocked = false;
    int dispatched = 0;           // 被 processQueues 派发出去的任务数

    void processQueues()          // L368-372: 被阻塞时直接返回
    {
        if (processingBlocked) return;
        dispatched += queued;
        queued = 0;
    }
    void barrierLock()            // L358-366
    {
        do {
            processingBlocked = false;
            processQueues();
            processingBlocked = true;
        } while (queued != 0);
    }
    bool tryBarrierLock()         // L341-356
    {
        if (queued != 0) return false;
        processingBlocked = true;
        return true;
    }
    void unlock()                 // L295-307
    {
        processingBlocked = false;
        processQueues();
    }
    bool isIdle()                 // L309-331: 队列空时 tryBarrierLock + unlock
    {
        if (queued != 0) return false;
        bool result = false;
        if (tryBarrierLock()) {
            result = true;
            unlock();
        }
        return result;
    }
    // L333-339 的 do/while; 返回 false 表示会无限空转 (这里只转有限次)
    bool waitForDone()
    {
        for (int i = 0; i < 1000; ++i) {
            processQueues();
            if (queued == 0) return true;
        }
        return false;
    }
};

struct FakeImage {
    FakeScheduler sched;
    int lockCount = 0;
    bool locked() const { return lockCount != 0; }
    void barrierLock() { if (!locked()) sched.barrierLock(); ++lockCount; }
    void unlock() { if (--lockCount == 0) sched.unlock(); }
    bool isIdle(bool allowLocked = false) { return (allowLocked || !locked()) && sched.isIdle(); }
};

int main()
{
    std::printf("renderGateFor\n");
    {
        int calls = 0;
        const RenderGate g = renderGateFor(true, [&] { ++calls; return true; });
        check(!g.waitForMerger, "locked -> composite synchronously, no waitForDone");
        check(calls == 0, "locked -> scheduler idle query is never called");
    }
    {
        int calls = 0;
        const RenderGate g = renderGateFor(true, [&] { ++calls; return false; });
        check(!g.waitForMerger, "locked + queued -> never waitForDone (would spin)");
        check(calls == 0, "locked + queued -> scheduler idle query is never called");
    }
    {
        int calls = 0;
        const RenderGate g = renderGateFor(false, [&] { ++calls; return false; });
        check(g.waitForMerger, "unlocked + busy -> waitForDone, merger result used as-is");
        check(calls == 1, "unlocked -> scheduler queried exactly once");
    }
    {
        const RenderGate g = renderGateFor(false, [] { return true; });
        check(!g.waitForMerger, "unlocked + idle -> composite synchronously");
    }

    std::printf("mayWaitForImage\n");
    check(!mayWaitForImage(true), "locked -> must not waitForDone");
    check(mayWaitForImage(false), "unlocked -> may waitForDone");

    std::printf("scheduler model\n");
    {
        // 旧写法 (P1): 锁住时每帧 isIdle(true) -> 调度器阻塞被解开
        FakeImage img;
        img.barrierLock();
        check(img.sched.processingBlocked, "barrierLock blocks processing");
        (void)img.isIdle(true);
        check(img.locked() && !img.sched.processingBlocked,
              "model: isIdle(true) while locked clears processingBlocked (the P1 defect)");
    }
    {
        // 新写法 (P1b): 对称整笔 60 帧, 期间有人入队 (setDirty / 切帧), 阻塞必须一直保持
        // 查询故意用会解除阻塞的 isIdle(true): gate 若在锁住时调用它, 下面的断言会失败。
        FakeImage img;
        img.barrierLock();
        int calls = 0;
        for (int frame = 0; frame < 60; ++frame) {
            const RenderGate g = renderGateFor(img.locked(), [&] { ++calls; return img.isIdle(true); });
            if (g.waitForMerger) img.sched.waitForDone();
            if (frame == 10) img.sched.queued += 3;          // 锁住期间入队
            if (mayWaitForImage(img.locked())) img.sched.waitForDone();
        }
        check(calls == 0, "P1b: no scheduler query during the whole locked stroke");
        check(img.sched.processingBlocked, "P1b: processingBlocked held for the whole locked stroke");
        check(img.sched.dispatched == 0, "P1b: nothing dispatched while locked (no concurrent writer)");
        img.unlock();
        check(!img.locked() && img.sched.queued == 0 && img.sched.dispatched == 3,
              "P1b: queued jobs dispatched on endMacro -> unlock");
        const RenderGate g = renderGateFor(img.locked(), [&] { return img.isIdle(); });
        check(!g.waitForMerger, "after unlock + drained -> back to synchronous composite");
    }
    {
        // 锁住 + 有任务时 waitForDone 会空转: 这就是 mayWaitForImage 存在的原因
        FakeImage img;
        img.barrierLock();
        img.sched.queued = 1;
        check(!img.sched.waitForDone(), "model: waitForDone while locked with queued job spins");
        check(!mayWaitForImage(img.locked()), "guard refuses that wait");
    }
    {
        // touchStrokeCancel: 锁住时回滚设备 (不派发任何任务) -> 关宏 -> setDirty + recomposite
        FakeImage img;
        img.barrierLock();
        img.sched.queued += 1;              // 锁住期间别处入队的任务 (如播放切帧)
        // revert 在这里: 锁住, 没有 worker
        check(img.sched.dispatched == 0, "cancel: nothing dispatched while reverting under lock");
        img.unlock();                       // endMacro
        check(img.sched.dispatched == 1, "cancel: queued job dispatched only after revert + endMacro");
        img.sched.queued += 2;              // setDirty, refreshGraphAsync
        check(mayWaitForImage(img.locked()) && img.sched.waitForDone(),
              "cancel: recomposite after endMacro -> waitForDone completes");
    }

    std::printf("\n%d checks, %d failures\n", g_checks, g_failures);
    return g_failures == 0 ? 0 : 1;
}
