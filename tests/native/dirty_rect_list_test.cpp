/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/* ============================================================
 * dirty_rect_list_test.cpp - DirtyRectList 宿主机单测 (无 Qt / 无 Android / 无 Krita)
 *
 * 编译运行 (仓库根目录):
 *   g++ -std=c++17 -O2 -Wall -Wextra tests/native/dirty_rect_list_test.cpp \
 *       -o build/dirty-rect-list && ./build/dirty-rect-list
 * ============================================================ */
#include "../../app/src/main/cpp/DirtyRectList.h"

#include <cstdio>
#include <cstdlib>

using reverie::DirtyRect;
using reverie::DirtyRectList;
using reverie::appendOrCoalesce;
using reverie::dirtyRectArea;

static int g_failures = 0;
static int g_checks = 0;

static void check(bool ok, const char *what)
{
    ++g_checks;
    if (ok) {
        std::printf("  [PASS] %s\n", what);
    } else {
        std::printf("  [FAIL] %s\n", what);
        ++g_failures;
    }
}

static DirtyRect rect(int x, int y, int w, int h)
{
    DirtyRect r;
    r.x = x;
    r.y = y;
    r.w = w;
    r.h = h;
    return r;
}

static long long totalArea(const DirtyRectList &l)
{
    long long sum = 0;
    for (int i = 0; i < l.count; ++i) {
        sum += dirtyRectArea(l.rects[i]);
    }
    return sum;
}

// 3840x2160 平面上两个角上的 10x10 脏区必须保持两块, 而不是膨胀成
// 一个 829 万像素的 3840x2160 总包围盒。
static void testTwoCornersStaySplit()
{
    std::printf("two corner 10x10 rects on a 3840x2160 plane stay two rects\n");
    DirtyRectList l;
    appendOrCoalesce(l, rect(0, 0, 10, 10), 8);
    appendOrCoalesce(l, rect(3830, 2150, 10, 10), 8);
    check(l.count == 2, "count == 2");
    check(totalArea(l) == 200, "area sum == 200 (not 3840*2160)");
}

static void testOverlappingMerge()
{
    std::printf("overlapping rects become one\n");
    DirtyRectList l;
    appendOrCoalesce(l, rect(100, 100, 50, 50), 8);
    appendOrCoalesce(l, rect(120, 120, 50, 50), 8);
    check(l.count == 1, "count == 1");
    check(l.rects[0].x == 100 && l.rects[0].y == 100 &&
          l.rects[0].w == 70 && l.rects[0].h == 70, "union is (100,100,70,70)");
}

// 空洞 = 并集面积 - 两块面积之和。1000 / 21000 = 4.8% < 25% -> 合并
static void testHollowUnder25PercentMerges()
{
    std::printf("a hollow merge under 25%% merges\n");
    DirtyRectList l;
    appendOrCoalesce(l, rect(0, 0, 100, 100), 8);
    appendOrCoalesce(l, rect(110, 0, 100, 100), 8);
    check(l.count == 1, "count == 1");
    check(l.rects[0].x == 0 && l.rects[0].y == 0 &&
          l.rects[0].w == 210 && l.rects[0].h == 100, "union is (0,0,210,100)");
}

// 空洞 = 10000 / 30000 = 33.3% > 25% -> 保持两块
static void testHollowOver25PercentStaysSplit()
{
    std::printf("a hollow merge over 25%% stays split\n");
    DirtyRectList l;
    appendOrCoalesce(l, rect(0, 0, 100, 100), 8);
    appendOrCoalesce(l, rect(200, 0, 100, 100), 8);
    check(l.count == 2, "count == 2");
}

// 第 9 块必须先把"面积增量最小"的一对并起来, 列表永远不超过 8
static void testNinthRectCollapsesCheapestPair()
{
    std::printf("the 9th disjoint rect collapses the cheapest pair and never grows past 8\n");
    DirtyRectList l;
    bool neverOverCap = true;
    for (int i = 0; i < 9; ++i) {
        appendOrCoalesce(l, rect(i * 400, i * 200, 10, 10), 8);
        if (l.count > 8) {
            neverOverCap = false;
        }
    }
    check(neverOverCap, "count never exceeded 8");
    check(l.count == 8, "count == 8 after the 9th rect");

    bool collapsed = false;
    for (int i = 0; i < l.count; ++i) {
        if (dirtyRectArea(l.rects[i]) == 410LL * 210LL) {
            collapsed = true;
        }
    }
    check(collapsed, "cheapest pair (delta 400x200) merged into a 410x210 box");
}

static void testEmptyInputIsNoOp()
{
    std::printf("empty input is a no-op\n");
    DirtyRectList l;
    appendOrCoalesce(l, rect(10, 10, 20, 20), 8);
    const int before = l.count;
    appendOrCoalesce(l, rect(0, 0, 0, 0), 8);
    appendOrCoalesce(l, rect(5, 5, -4, 10), 8);
    appendOrCoalesce(l, rect(5, 5, 10, 0), 8);
    check(l.count == before, "count unchanged");
    check(l.rects[0].x == 10 && l.rects[0].y == 10, "existing rect untouched");
}

int main()
{
    testTwoCornersStaySplit();
    testOverlappingMerge();
    testHollowUnder25PercentMerges();
    testHollowOver25PercentStaysSplit();
    testNinthRectCollapsesCheapestPair();
    testEmptyInputIsNoOp();

    std::printf("\n%d checks, %d failures\n", g_checks, g_failures);
    return g_failures == 0 ? 0 : 1;
}
