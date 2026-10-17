/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

#ifndef REVERIE_STROKE_PRESENT_FAST_PATH_H
#define REVERIE_STROKE_PRESENT_FAST_PATH_H

#include "ReverieCoreInternal.h"
#include <kis_group_layer.h>

/**
 * 运笔期间静态底图 m_staticUnderlay 可以冻结的上界 b: [0, b) 在整笔期间不会变。
 * 返回值 <= 0 表示没有可冻结的下层, 或存在会跨图层取样的特殊节点, 需要走全栈合成。
 *
 * 从当前层 k 向上回溯到它所属的顶层 (depth == 0) 非剪贴基底单元起点 b:
 *   - 运笔只改 k 这一层, k 一定落在 [b, size) 里, [0, b) 在整笔期间静止;
 *   - compositeLayersRange 对 [b, size) 的合成只读取 [0, b) 的最终结果。
 * 所以先把 [0, b) 的合成结果 COPY 进输出, 再合成 [b, size), 与从 0 全量合成逐像素一致;
 * 与 k 是否 Alpha 锁、是否正片叠底/滤色/擦除、是否剪贴蒙版、上方是否有可见线稿层都无关。
 *
 * 描边图层 / 调整图层 / 克隆图层 / 穿透组会读写别的层的合成结果, 只要文档里有就不冻结。
 */
inline int strokeStaticUnderlayStop(const ReverieCore &core)
{
    if (!core.m_drawing || !core.m_document) {
        return 0;
    }
    const int k = core.m_currentLayer;
    if (k <= 0 || k >= core.m_layers.size()) {
        return 0;
    }

    const ReverieCore::LayerEntry &cur = core.m_layers.at(k);
    if (!cur.node || !cur.visible) {
        return 0;
    }

    for (int i = 0; i < core.m_layers.size(); ++i) {
        const ReverieCore::LayerEntry &e = core.m_layers.at(i);
        if (!e.node) {
            continue;
        }
        if (e.isStrokeLayer || e.nodeType == ReverieCore::NodeTypeStroke ||
            e.nodeType == ReverieCore::NodeTypeAdjustment || e.nodeType == ReverieCore::NodeTypeClone) {
            return 0;
        }
        if (e.isGroup) {
            const KisGroupLayer *grp = dynamic_cast<const KisGroupLayer *>(e.node);
            if (grp && grp->passThroughMode()) {
                return 0;
            }
        }
    }

    // 先回溯到包含 k 的顶层节点, 再跳过剪贴蒙版链回到它的基底图层
    int b = k;
    while (b > 0 && core.m_layers.at(b).depth > 0) {
        --b;
    }
    while (b > 0 && core.m_layers.at(b).clipped) {
        --b;
        while (b > 0 && core.m_layers.at(b).depth > 0) {
            --b;
        }
    }
    return b;
}

#endif // REVERIE_STROKE_PRESENT_FAST_PATH_H
