// SPDX-License-Identifier: GPL-3.0-or-later
// 回归测试: airbrush tick 改走 paintLine 门控后, dab 序列必须与 Krita 参考一致
#include "dab_core.h"
#include <cstdio>
#include <cstdlib>
static int g_fail = 0;
#define CHECK(cond, msg) do { if (!(cond)) { printf("FAIL: %s\n", msg); ++g_fail; } else { printf("ok:   %s\n", msg); } } while (0)

static int countTicks(const Sim& s) { int n = 0; for (auto& d : s.dabs) if (d.src == 'T') ++n; return n; }
static double minGap(const Sim& s) {
    double m = 1e9;
    for (size_t i = 1; i < s.dabs.size(); ++i)
        m = std::min(m, std::hypot(s.dabs[i].x - s.dabs[i-1].x, s.dabs[i].y - s.dabs[i-1].y));
    return m;
}
int main() {
    const double dt = 4.0, sp = 10.0, iv = 33.3;
    // 1) 匀速 0.5px/ms: 现状 tick 会多出 dab, 修复后必须与 Krita 完全一致且无 tick dab
    {
        auto ev = makeLine(0.5, 0, 400, dt, {100, 100}, 0, 1, 0);
        Sim k = runKrita(ev, sp, true, iv);
        Sim bad = runRP(ev, {sp, true, iv, 1.5, 16, iv, false});
        Sim fix = runRP(ev, {sp, true, iv, 1.5, 16, iv, true});
        CHECK(bad.dabs.size() > k.dabs.size() + 5, "baseline: ungated tick injects extra dabs (reproduces bug)");
        CHECK(fix.dabs.size() == k.dabs.size(), "fixed: dab count equals Krita (uniform motion)");
        CHECK(countTicks(fix) == 0, "fixed: no tick dabs while pen is moving");
        CHECK(minGap(fix) >= sp - 1e-6, "fixed: min gap >= spacing while moving");
    }
    // 2) 慢速 0.05px/ms: 现状 dab 密度是 Krita 的 ~2 倍
    {
        auto ev = makeLine(0.05, 0, 1200, dt, {100, 100}, 0, 1, 0);
        Sim k = runKrita(ev, sp, true, iv);
        Sim bad = runRP(ev, {sp, true, iv, 1.5, 16, iv, false});
        Sim fix = runRP(ev, {sp, true, iv, 1.5, 16, iv, true});
        CHECK(bad.dabs.size() > k.dabs.size() * 3 / 2, "baseline: slow stroke dab density ~2x Krita (reproduces bug)");
        CHECK(std::abs((int)fix.dabs.size() - (int)k.dabs.size()) <= 1, "fixed: slow stroke dab count within 1 of Krita");
    }
    // 3) 停笔: 先走100ms, 停300ms(事件持续到达但无位移), 再走100ms
    {
        auto ev = makeLine(0.5, 0, 100, dt, {100, 100}, 0, 1, 0);
        Pt endp = ev.back().p;
        for (double t = 104; t <= 400; t += dt) ev.push_back({endp, t});
        for (double t = 404; t <= 500; t += dt) ev.push_back({{endp.x + 0.5 * (t - 404), 100}, t});
        Sim k = runKrita(ev, sp, true, iv);
        Sim bad = runRP(ev, {sp, true, iv, 1.5, 16, iv, false});
        Sim fix = runRP(ev, {sp, true, iv, 1.5, 16, iv, true});
        CHECK(bad.dabs.size() >= k.dabs.size() + 10, "baseline: pause stacks >=10 extra dabs (reproduces bug)");
        CHECK(std::abs((int)fix.dabs.size() - (int)k.dabs.size()) <= 3, "fixed: pause dab count within 3 of Krita airbrush");
    }
    printf(g_fail ? "FAILED (%d)\n" : "ALL PASSED\n", g_fail);
    return g_fail ? 1 : 0;
}
