/*
 * 图层附加属性的存储与访问 (见 ReverieCoreLayerExtras.h)
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

#include "ReverieCoreLayerExtras.h"

#include "kis_node.h"
#include "kis_types.h"

#include <QObject>

namespace reverie {
namespace {

QHash<const KisNode *, NodeExtra> &extras()
{
    static QHash<const KisNode *, NodeExtra> table;
    return table;
}

/// 已挂上 destroyed 监听的节点, 避免重复连接
QSet<const KisNode *> &watched()
{
    static QSet<const KisNode *> set;
    return set;
}

/**
 * 节点销毁时自动清掉条目。
 * 必须这么做: 撤销"删除图层"会复用同一节点指针, 但撤回栈被清空后节点真被销毁,
 * 之后新建图层若恰好分配到同一地址, 残留条目就会把旧标记(描边/填充)套到新层上。
 */
void watchNode(const KisNode *node)
{
    auto *obj = const_cast<KisNode *>(node);
    if (!obj || watched().contains(node)) return;
    watched().insert(node);
    QObject::connect(obj, &QObject::destroyed, obj, [](QObject *o) {
        const auto *n = static_cast<const KisNode *>(reinterpret_cast<const void *>(o));
        extras().remove(n);
        watched().remove(n);
    });
}

} // namespace

QVariant layerProperty(const KisNode *node, const char *key)
{
    if (!node || !key) return QVariant();
    const auto it = extras().constFind(node);
    const NodeExtra def;
    const NodeExtra &e = (it == extras().constEnd()) ? def : it.value();

    const QByteArray k(key);
    if (k == "reverie_is_fill") return e.isFill;
    if (k == "reverie_fill_color") return e.hasFillColor ? QVariant(e.fillColor) : QVariant();
    if (k == "reverie_is_stroke") return e.isStroke;
    if (k == "reverie_stroke_size") return e.strokeSize;
    if (k == "reverie_stroke_color") return e.strokeColor;
    if (k == "reverie_stroke_pos") return e.strokePosition;
    if (k == "reverie_stroke_opacity") return e.strokeOpacity;
    if (k == "reverie_is_transparency_mask") return e.isTransparencyMask;
    if (k == "reverie_panel") return e.panelJson;
    return QVariant();
}

void setLayerProperty(const KisNode *node, const char *key, const QVariant &value)
{
    if (!node || !key) return;
    watchNode(node);
    NodeExtra &e = extras()[node];

    const QByteArray k(key);
    if (k == "reverie_is_fill") {
        e.isFill = value.toBool();
    } else if (k == "reverie_fill_color") {
        e.fillColor = value.toUInt();
        e.hasFillColor = true;
    } else if (k == "reverie_is_stroke") {
        e.isStroke = value.toBool();
    } else if (k == "reverie_stroke_size") {
        e.strokeSize = value.toInt();
    } else if (k == "reverie_stroke_color") {
        e.strokeColor = value.toUInt();
    } else if (k == "reverie_stroke_pos") {
        e.strokePosition = value.toInt();
    } else if (k == "reverie_stroke_opacity") {
        e.strokeOpacity = value.toInt();
    } else if (k == "reverie_is_transparency_mask") {
        e.isTransparencyMask = value.toBool();
    } else if (k == "reverie_panel") {
        e.panelJson = value.toString();
    }
}

void copyLayerProperties(const KisNode *from, const KisNode *to)
{
    if (!from || !to) return;
    if (const auto it = extras().constFind(from); it != extras().constEnd()) {
        watchNode(to);
        extras()[to] = it.value();
    }

    // 子层按位置一一对应 (按名字找会在重名时错位)
    KisNodeSP fromChild = from->firstChild();
    KisNodeSP toChild = to->firstChild();
    while (fromChild && toChild) {
        copyLayerProperties(fromChild.data(), toChild.data());
        fromChild = fromChild->nextSibling();
        toChild = toChild->nextSibling();
    }
}

void pruneLayerProperties(const QSet<const KisNode *> &alive)
{
    auto &table = extras();
    for (auto it = table.begin(); it != table.end();) {
        if (!alive.contains(it.key())) {
            it = table.erase(it);
        } else {
            ++it;
        }
    }
}

void clearLayerProperties()
{
    extras().clear();
    watched().clear();
}

} // namespace reverie
