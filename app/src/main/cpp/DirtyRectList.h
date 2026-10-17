/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/* ============================================================
 * DirtyRectList.h - 有界离散脏区列表
 *
 * 为什么需要: 一笔斜向运笔或对称工具在 4K 画布上真正落墨的像素可能只有几千个,
 * 但把 takeDirtyRegion() 出来的所有矩形 .united() 成一个大包围盒之后, 后续的
 * compositeLayersRange 与 PixelAlpha::toDisplayRows 就要按 3840x2160 处理,
 * 浪费 99% 的 CPU 合成算力。
 *
 * 本头文件只做"矩形集合的合并策略", 不含任何 Qt / Android / Krita 依赖,
 * 因此可以直接用宿主机 g++ 单测 (见 tests/native/dirty_rect_list_test.cpp)。
 * 引擎侧用 QVector<QRect> m_dirtyRects 镜像同一份数据 (m_dirtyRect 仍保留为
 * 总包围盒供 JNI 与既有调用方查询)。
 *
 * 合并规则 (按顺序):
 *   1. 空矩形直接丢弃
 *   2. 与已有矩形相交 -> 用并集替换该矩形
 *   3. 否则若并入某个已有矩形新增的空洞面积 < 并集面积的 25%, 并入空洞最小的那个
 *   4. 否则追加; 若追加会超过 maxCount, 先合并"面积增量最小"的一对再追加
 * 上限 8 块: 4K 画布上 8 块离散区域已能覆盖对称笔刷/多段笔迹的绝大多数形态,
 * 而固定上限保证热路径零分配。
 * ============================================================ */
#ifndef REVERIE_DIRTY_RECT_LIST_H
#define REVERIE_DIRTY_RECT_LIST_H

namespace reverie {

struct DirtyRect {
    int x = 0;
    int y = 0;
    int w = 0;
    int h = 0;
};

inline bool dirtyRectEmpty(const DirtyRect &r)
{
    return r.w <= 0 || r.h <= 0;
}

inline long long dirtyRectArea(const DirtyRect &r)
{
    return dirtyRectEmpty(r) ? 0LL : static_cast<long long>(r.w) * static_cast<long long>(r.h);
}

inline bool dirtyRectIntersects(const DirtyRect &a, const DirtyRect &b)
{
    if (dirtyRectEmpty(a) || dirtyRectEmpty(b)) {
        return false;
    }
    return a.x < b.x + b.w && b.x < a.x + a.w &&
           a.y < b.y + b.h && b.y < a.y + a.h;
}

inline DirtyRect dirtyRectUnited(const DirtyRect &a, const DirtyRect &b)
{
    if (dirtyRectEmpty(a)) return b;
    if (dirtyRectEmpty(b)) return a;
    const int x0 = a.x < b.x ? a.x : b.x;
    const int y0 = a.y < b.y ? a.y : b.y;
    const int x1 = (a.x + a.w) > (b.x + b.w) ? (a.x + a.w) : (b.x + b.w);
    const int y1 = (a.y + a.h) > (b.y + b.h) ? (a.y + a.h) : (b.y + b.h);
    DirtyRect u;
    u.x = x0;
    u.y = y0;
    u.w = x1 - x0;
    u.h = y1 - y0;
    return u;
}

/** 并集比两者之和多出来的"空洞"面积 (两个矩形不重叠时即中间的空白)。 */
inline long long dirtyRectHollow(const DirtyRect &a, const DirtyRect &b)
{
    return dirtyRectArea(dirtyRectUnited(a, b)) - dirtyRectArea(a) - dirtyRectArea(b);
}

struct DirtyRectList {
    static const int kCapacity = 8;
    DirtyRect rects[kCapacity];
    int count = 0;
};

inline void dirtyRectListAppend(DirtyRectList &list, const DirtyRect &r)
{
    if (list.count < DirtyRectList::kCapacity) {
        list.rects[list.count++] = r;
    }
}

inline void appendOrCoalesce(DirtyRectList &list, const DirtyRect &incoming,
                             int maxCount = DirtyRectList::kCapacity)
{
    if (dirtyRectEmpty(incoming)) {
        return;
    }
    if (maxCount < 1) {
        maxCount = 1;
    }
    if (maxCount > DirtyRectList::kCapacity) {
        maxCount = DirtyRectList::kCapacity;
    }

    // 1) 与已有矩形相交: 用并集替换它
    for (int i = 0; i < list.count; ++i) {
        if (dirtyRectIntersects(list.rects[i], incoming)) {
            list.rects[i] = dirtyRectUnited(list.rects[i], incoming);
            return;
        }
    }

    // 2) 并入"空洞最小且空洞 < 并集 25%"的候选矩形
    int best = -1;
    long long bestHollow = 0;
    for (int i = 0; i < list.count; ++i) {
        const long long merged = dirtyRectArea(dirtyRectUnited(list.rects[i], incoming));
        const long long hollow = merged - dirtyRectArea(list.rects[i]) - dirtyRectArea(incoming);
        if (hollow * 4 >= merged) {
            continue;
        }
        if (best < 0 || hollow < bestHollow) {
            best = i;
            bestHollow = hollow;
        }
    }
    if (best >= 0) {
        list.rects[best] = dirtyRectUnited(list.rects[best], incoming);
        return;
    }

    // 3) 追加会越界: 先合并面积增量最小的一对, 再追加 (永远不超过 maxCount)
    if (list.count >= maxCount) {
        int bi = -1;
        int bj = -1;
        long long bestIncrease = 0;
        for (int i = 0; i < list.count; ++i) {
            for (int j = i + 1; j < list.count; ++j) {
                const long long merged = dirtyRectArea(dirtyRectUnited(list.rects[i], list.rects[j]));
                const long long increase = merged - dirtyRectArea(list.rects[i]) - dirtyRectArea(list.rects[j]);
                if (bi < 0 || increase < bestIncrease) {
                    bi = i;
                    bj = j;
                    bestIncrease = increase;
                }
            }
        }
        if (bi >= 0) {
            list.rects[bi] = dirtyRectUnited(list.rects[bi], list.rects[bj]);
            list.rects[bj] = list.rects[list.count - 1];
            --list.count;
        }
    }
    dirtyRectListAppend(list, incoming);
}

} // namespace reverie

#endif // REVERIE_DIRTY_RECT_LIST_H
