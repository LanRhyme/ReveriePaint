// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
// 喷枪 tick 门控: 只在"最近无位移"时放行, 并给出 paintLine 需要的起点时间.
// 纯逻辑头文件, 不依赖 Qt / Krita, 便于 harness 直接 include.
#include <algorithm>

namespace reverie {

struct AirbrushTickGate {
    // 位移事件之后至少空闲这么久才允许 tick (Kotlin 侧定时器下限 8ms, 见 PaintViewModel.kt airbrushIntervalMs.coerceIn(8L,100L))
    static constexpr double kMinIdleMs = 8.0;

    double lastMotionMs = 0.0;   // 最近一次产生位移的输入事件时间 (ms)
    double lastTickMs = -1.0;    // 最近一次已放行 tick 的时间 (ms), <0 表示尚无

    void reset(double strokeStartMs) { lastMotionMs = strokeStartMs; lastTickMs = -1.0; }

    void onMotion(double nowMs) { lastMotionMs = std::max(lastMotionMs, nowMs); }

    // 放行时返回 true, 并写出本次 paintLine 的起点时间 startMs (终点 = nowMs).
    bool poll(double nowMs, double &startMs) {
        const double mark = std::max(lastMotionMs, lastTickMs);
        if (nowMs - mark < kMinIdleMs) return false;
        startMs = mark;
        lastTickMs = nowMs;
        return true;
    }
};

} // namespace reverie
