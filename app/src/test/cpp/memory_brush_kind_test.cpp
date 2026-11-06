// SPDX-License-Identifier: GPL-3.0-or-later
#include "MemoryBrushKind.h"
#include <cstdio>
using namespace reverie;
static int fails = 0;
#define CHECK(c) do { if (!(c)) { std::printf("FAIL line %d: %s\n", __LINE__, #c); ++fails; } } while (0)
int main()
{
    CHECK(memoryBrushKindFor("a.png", false) == MemoryBrushKind::Png);
    CHECK(memoryBrushKindFor("A.PNG", false) == MemoryBrushKind::Png);
    CHECK(memoryBrushKindFor("b.gbr", false) == MemoryBrushKind::Gbr);
    CHECK(memoryBrushKindFor("c.gih", false) == MemoryBrushKind::Gih);   // 现状被当 PNG 解码
    CHECK(memoryBrushKindFor("d.SVG", false) == MemoryBrushKind::Svg);   // 现状被当 PNG 解码
    CHECK(memoryBrushKindFor("noext", true)  == MemoryBrushKind::Gbr);   // 沿用旧 isGbr 行为
    CHECK(memoryBrushKindFor("noext", false) == MemoryBrushKind::Png);
    CHECK(memoryBrushKindFor("x.tar.gih", true) == MemoryBrushKind::Gih);
    if (fails == 0) std::printf("ALL PASSED (memory_brush_kind_test)\n");
    return fails ? 1 : 0;
}
