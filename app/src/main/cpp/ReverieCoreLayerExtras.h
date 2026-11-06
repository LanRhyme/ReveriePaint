/*
 * 图层附加属性 (NodeExtra) —— 取代原先挂在 KisNode QObject 上的 reverie_* 动态属性
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * 为什么独立成自由函数 + 一张按节点索引的侧表:
 *  1. 这些标记/参数原先与 m_layers 里的 LayerEntry 形成两套 schema, 两边都能改;
 *  2. QObject 动态属性不随 KisNode::clone()/栅格化复制, 只能靠调用点手工搬运, 容易漏
 *     (例如 reverie_is_transparency_mask 之前就没被搬运);
 *  3. 有些调用点 (如 LayerIO 的 XML 写出) 不是 ReverieCore 成员, 需要不依赖实例的访问器。
 *
 * 语义与动态属性保持一致: 未设置时 layerProperty 返回无效 QVariant。
 */
#ifndef REVERIECORELAYEREXTRAS_H
#define REVERIECORELAYEREXTRAS_H

#include <QHash>
#include <QSet>
#include <QString>
#include <QVariant>
#include <QtGlobal>

class KisNode;

namespace reverie {

struct NodeExtra {
    bool isFill = false;
    bool hasFillColor = false;
    quint32 fillColor = 0xFFFFFFFFu;
    bool isStroke = false;
    quint32 strokeColor = 0xFF000000u;
    int strokeSize = 6;
    int strokePosition = 0;
    int strokeOpacity = 100;
    bool isTransparencyMask = false;
    // 漫画分格元数据 (四角/样式 JSON), 侧表 key "reverie_panel"; 空串 = 非分格。
    // copyLayerProperties 做整结构复制 (extras()[to] = it.value()), 本字段自动随拷,
    // 无需在调用点特判; clearLayerProperties/pruneLayerProperties 按条目整体清理, 同样无需特判。
    QString panelJson;
};

QVariant layerProperty(const KisNode *node, const char *key);
void setLayerProperty(const KisNode *node, const char *key, const QVariant &value);

/** 把 from 的附加属性(含各级子层, 按位置对应)复制到 to */
void copyLayerProperties(const KisNode *from, const KisNode *to);

/** 丢弃不在 alive 集合里的节点条目 (谨慎: 撤销删层会复用同一节点指针, 不要每次 sync 都裁) */
void pruneLayerProperties(const QSet<const KisNode *> &alive);

/** 文档关闭/切换时整体清空 (条目按节点指针索引, 换文档后旧指针可能被复用) */
void clearLayerProperties();

} // namespace reverie

#endif // REVERIECORELAYEREXTRAS_H
