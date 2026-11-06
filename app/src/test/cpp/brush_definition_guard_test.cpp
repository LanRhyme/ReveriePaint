// SPDX-License-Identifier: GPL-3.0-or-later
#include "BrushDefinitionGuard.h"
#include <cstdio>
using namespace reverie;
static int fails = 0;
#define CHECK(c) do { if (!(c)) { std::printf("FAIL line %d: %s\n", __LINE__, #c); ++fails; } } while (0)
int main()
{
    const std::string png = "<Brush type=\"png_brush\" filename=\"wc_tip.png\" md5sum=\"abc\" spacing=\"0.08\" useAutoSpacing=\"1\"/>";
    const std::string gbr = "<!DOCTYPE x>\n<Brush BrushVersion=\"2\" type=\"gbr_brush\" filename=\"a.gbr\"/>";
    const std::string autob = "<Brush type=\"auto_brush\" spacing=\"1\"><MaskGenerator diameter=\"1\"/></Brush>";
    CHECK(brushDefType(png) == "png_brush");
    CHECK(brushDefType(gbr) == "gbr_brush");
    CHECK(brushDefType(autob) == "auto_brush");
    CHECK(brushDefType("") == "");
    CHECK(brushDefType("<Brush spacing=\"1\"/>") == "");
    CHECK(brushDefDowngraded(png, autob));          // 绑定落空被 BrushWriter 回写 → 必须检测到
    CHECK(brushDefDowngraded(gbr, autob));
    CHECK(!brushDefDowngraded(png, png));           // 正常: 未变化
    CHECK(!brushDefDowngraded(autob, autob));       // 本来就是自动笔刷: 不干预
    CHECK(!brushDefDowngraded("", autob));          // 无快照: 不干预
    CHECK(!brushDefDowngraded(png, ""));            // 清空不算降级 (交给其它路径)
    if (fails == 0) std::printf("ALL PASSED (brush_definition_guard_test)\n");
    return fails ? 1 : 0;
}
