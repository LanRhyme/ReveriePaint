/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * ReverieCoreLayerIO.cpp - 兼容 KRA 语义的图层树序列化 (layers.xml)
 *
 * 保存时把 m_layers(树先序)按 depth 重组为嵌套 XML, 加载端照
 * KisKraLoader 的语义重建非破坏节点树(调整层/蒙版/生成器层)。
 * 节点数据文件沿用 layer_%03d.png 命名规则(序号 = m_layers 下标,
 * group 无数据文件), 与 saveRevp/saveKra 的既有条目一一对应。
 */
#include "ReverieCoreInternal.h"
#include <QXmlStreamWriter>
#include <QBuffer>
#include <kis_adjustment_layer.h>
#include <kis_filter_mask.h>
#include <filter/kis_filter_configuration.h>

namespace {

const char *nodeElementName(int nodeType)
{
    switch (nodeType) {
    case ReverieCore::NodeTypeGroup: return "group";
    case ReverieCore::NodeTypeFill: return "generatorlayer";
    case ReverieCore::NodeTypeAdjustment: return "adjustmentlayer";
    case ReverieCore::NodeTypeClone: return "clonelayer";
    case ReverieCore::NodeTypeTransparencyMask: return "transparencymask";
    case ReverieCore::NodeTypeFilterMask: return "filtermask";
    case ReverieCore::NodeTypeTransformMask: return "transformmask";
    case ReverieCore::NodeTypeSelectionMask: return "selectionmask";
    default: return "paintlayer";
    }
}

void writeCommonAttrs(QXmlStreamWriter &w, const ReverieCore::LayerEntry &e)
{
    KisNode *node = e.node;
    w.writeAttribute("name", e.name);
    w.writeAttribute("visible", e.visible ? "1" : "0");
    w.writeAttribute("opacity", QString::number(node ? node->opacity() : 255));
    if (KisLayer *l = dynamic_cast<KisLayer *>(node)) {
        w.writeAttribute("compositeop", l->compositeOpId());
    }
    w.writeAttribute("locked", e.locked ? "1" : "0");
    w.writeAttribute("alpha_locked", e.alphaLocked ? "1" : "0");
    w.writeAttribute("inherit_alpha", e.clipped ? "1" : "0");
    w.writeAttribute("color_label", QString::number(e.colorLabel));
    w.writeAttribute("x", QString::number(node ? int(node->x()) : 0));
    w.writeAttribute("y", QString::number(node ? int(node->y()) : 0));
}

// reverie 注册表滤镜配置 → XML 属性 (filter/p1..p4/lut_b64)
void writeFilterAttrs(QXmlStreamWriter &w, const KisFilterConfigurationSP cfg)
{
    if (!cfg) return;
    QVariant v;
    const int type = cfg->getProperty("reverieType", v) ? v.toInt() : 0;
    w.writeAttribute("filter", QString("reverie-f%1").arg(type));
    for (int i = 1; i <= 4; ++i) {
        const double pi = cfg->getProperty(QString("p%1").arg(i), v) ? v.toDouble() : 0.0;
        w.writeAttribute(QString("p%1").arg(i), QString::number(pi, 'g', 10));
    }
    if (cfg->getProperty("lut", v)) {
        const QByteArray lut = v.toByteArray();
        if (!lut.isEmpty()) {
            w.writeAttribute("lut_b64", QString::fromLatin1(lut.toBase64()));
        }
    }
}

} // namespace

void ReverieCore::writeLayersXml(QString *out)
{
    if (!out) return;
    QXmlStreamWriter w(out);
    w.setAutoFormatting(true);
    w.setAutoFormattingIndent(1);
    w.writeStartDocument();
    w.writeStartElement("layers");

    // m_layers 为树先序; 用 openDepth 栈把扁平序列重组为嵌套结构:
   // 遇到深度 d 的节点前, 先闭合所有已打开且深度 >= d 的节点。
    int openDepth = -1;
    for (int i = 0; i < m_layers.size(); ++i) {
        const LayerEntry &e = m_layers[i];
        while (openDepth >= e.depth) {
            w.writeEndElement();
            --openDepth;
        }
        w.writeStartElement(nodeElementName(e.nodeType));
        writeCommonAttrs(w, e);
        if (!e.isGroup && e.nodeType != NodeTypeAdjustment) {
            // 数据文件名与 saveRevp/saveKra 的逐层 PNG 循环一致(含跳组空洞)
            w.writeAttribute("filename", QString("layer_%1.png").arg(i, 3, 10, QChar('0')));
        }
        if (e.nodeType == NodeTypeAdjustment) {
            if (KisAdjustmentLayer *al = dynamic_cast<KisAdjustmentLayer *>(e.node)) {
                writeFilterAttrs(w, al->filter());
            }
        } else if (e.nodeType == NodeTypeFilterMask) {
            if (KisFilterMask *fm = dynamic_cast<KisFilterMask *>(e.node)) {
                writeFilterAttrs(w, fm->filter());
            }
        }
        openDepth = e.depth;
    }
    while (openDepth >= 0) {
        w.writeEndElement();
        --openDepth;
    }

    w.writeEndElement(); // layers
    w.writeEndDocument();
}
