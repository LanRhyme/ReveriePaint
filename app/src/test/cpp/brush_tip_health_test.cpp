// SPDX-License-Identifier: GPL-3.0-or-later
#include "BrushTipHealth.h"
#include <cstdio>
static int g_fail = 0;
#define CHECK(cond, msg) do { if (!(cond)) { printf("FAIL: %s\n", msg); ++g_fail; } else { printf("ok:   %s\n", msg); } } while (0)
int main() {
    using reverie::isDegenerateTip;
    CHECK(isDegenerateTip(1, 1, true),   "1x1 valid tip is degenerate");
    CHECK(isDegenerateTip(0, 0, false),  "0x0 invalid tip is degenerate");
    CHECK(isDegenerateTip(256, 256, false), "invalid large tip is degenerate");
    CHECK(isDegenerateTip(256, 1, true), "256x1 line tip is degenerate");
    CHECK(!isDegenerateTip(256, 64, true), "256x64 flat tip is healthy");
    CHECK(!isDegenerateTip(2, 2, true),  "2x2 tip is not flagged (boundary)");
    printf(g_fail ? "FAILED (%d)\n" : "ALL PASSED\n", g_fail);
    return g_fail ? 1 : 0;
}
