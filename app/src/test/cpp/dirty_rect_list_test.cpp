/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * dirty_rect_list_test.cpp - 验证 DirtyRectList 空洞比门控与防巨型包围盒膨胀
 */

#include <cassert>
#include <cstdio>
#include "DirtyRectList.h"

int main()
{
    using namespace reverie;

    // 1. 紧密重叠且空洞极小（例如同向移动一小段）-> 允许就地合并
    {
        DirtyRectList list;
        appendOrCoalesce(list, DirtyRect{10, 10, 20, 20});
        appendOrCoalesce(list, DirtyRect{15, 15, 20, 20}); // 相交且大部分重合
        assert(list.count == 1);
        assert(list.rects[0].x == 10 && list.rects[0].y == 10);
        assert(list.rects[0].w == 25 && list.rects[0].h == 25);
        std::printf("ok: tight overlap merges into single compact rect\n");
    }

    // 2. 斜向连续滑动（对角线移动），如果无脑并集会形成整屏巨型盒子（空洞比 >= 25%）
    // 门控应拒绝直接并入原槽，而是开辟新槽位保持紧凑
    {
        DirtyRectList list;
        appendOrCoalesce(list, DirtyRect{0, 0, 10, 10});
        // 沿对角线连续添加轻微相交的块 (8, 8, 10, 10), (16, 16, 10, 10)...
        appendOrCoalesce(list, DirtyRect{8, 8, 10, 10});
        // 并集是 (0,0,18,18), 面积 324. 两个原块面积 100+100=200, 重叠 4, hollow = 324 - 100 - 100 = 124.
        // hollow * 4 = 496 > 324, 空洞占比 > 25%!
        // 因此不应强行合并进槽位 0，而应该作为新槽位保存！
        assert(list.count == 2);
        std::printf("ok: diagonal sparse overlap does not expand into giant bounding box\n");
    }

    std::printf("ALL PASSED (dirty_rect_list_test)\n");
    return 0;
}
