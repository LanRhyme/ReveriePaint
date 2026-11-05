// SPDX-License-Identifier: GPL-3.0-or-later
#include "AirbrushTickGate.h"
#include <cstdio>
static int g_fail = 0;
#define CHECK(cond, msg) do { if (!(cond)) { printf("FAIL: %s\n", msg); ++g_fail; } else { printf("ok:   %s\n", msg); } } while (0)
int main() {
    reverie::AirbrushTickGate g;
    double start = -1;
    g.reset(100.0);
    CHECK(!g.poll(105.0, start), "blocked: only 5ms since stroke start/motion");
    g.onMotion(130.0);
    CHECK(!g.poll(133.3, start), "blocked: motion 3.3ms ago");
    CHECK(g.poll(140.0, start) && start == 130.0, "allowed after 10ms idle, start = last motion time");
    CHECK(!g.poll(143.0, start), "blocked: 3ms after previous tick");
    CHECK(g.poll(173.3, start) && start == 140.0, "next tick starts at previous tick time (no double counting)");
    g.onMotion(150.0); // 乱序/迟到的旧事件不得把 lastMotion 往回拨
    CHECK(g.lastMotionMs == 150.0, "onMotion keeps monotonic max");
    printf(g_fail ? "FAILED (%d)\n" : "ALL PASSED\n", g_fail);
    return g_fail ? 1 : 0;
}
