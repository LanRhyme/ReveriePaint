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
#include <QXmlStreamReader>
#include <QBuffer>
#include <QImage>
#include <kis_adjustment_layer.h>
#include <kis_filter_mask.h>
#include <kis_group_layer.h>
#include <kis_paint_layer.h>
#include <kis_image.h>
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
    w.writeAttribute("background", e.background ? "1" : "0");
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

// ---------- 加载端 ----------

namespace {

// 与 IO.cpp 的 readAllStoreBytes 同策略: 已知大小一次读, 否则 64KB 分块(上限 64MB)
QByteArray readStoreEntryBytes(KoStore *store)
{
    QByteArray data;
    if (!store) return data;
    const qint64 total = store->size();
    if (total > 0) {
        data = store->read(total);
        if (data.size() == total) {
            return data;
        }
    }
    // Fallback: size 未知或不完整时分块读, 上限 64MB (与 IO.cpp readAllStoreBytes 同构)
    char buf[65536];
    while (true) {
        const qint64 n = store->read(buf, sizeof(buf));
        if (n <= 0) break;
        data.append(buf, int(n));
        if (data.size() > 64 * 1024 * 1024) break;
    }
    return data;
}

void paintDeviceFromPng(KisPaintDeviceSP dev, const QByteArray &pngBytes)
{
    if (!dev || pngBytes.isEmpty()) return;
    QImage img;
    if (!img.loadFromData(pngBytes, "PNG")) return;
    dev->clear();
    dev->convertFromQImage(img, nullptr);
    dev->setDirty();
}

} // namespace

bool ReverieCore::loadLayersXmlTree(const QByteArray &xmlData, KisImageSP image, KoStore *store, bool *bgVisible)
{
    if (bgVisible) *bgVisible = false;
    if (xmlData.isEmpty() || !image || !store) return false;

    QXmlStreamReader r(xmlData);
    while (!r.atEnd() && !r.isStartElement()) {
        r.readNext();
    }
    if (r.name() != QLatin1String("layers")) return false;

    const KoColorSpace *cs = image->colorSpace();
    QVector<KisNodeSP> parents;
    parents.append(image->rootLayer());
    bool any = false;
    bool bg = false;

    auto applyCommonAttrs = [&](KisNodeSP node) {
        const QXmlStreamAttributes a = r.attributes();
        auto attrInt = [&a](const char *key, int dflt) {
            bool ok = false;
            const int v = a.value(key).toString().toInt(&ok);
            return ok ? v : dflt;
        };
        const bool visible = a.value("visible") != QLatin1String("0");
        node->setVisible(visible);
        node->setOpacity(quint8(qBound(0, attrInt("opacity", 255), 255)));
        node->setUserLocked(a.value("locked") == QLatin1String("1"));
        node->setColorLabelIndex(attrInt("color_label", 0));
        node->setX(attrInt("x", 0));
        node->setY(attrInt("y", 0));
        if (KisLayer *l = dynamic_cast<KisLayer *>(node.data())) {
            l->disableAlphaChannel(a.value("inherit_alpha") == QLatin1String("1"));
            const QString op = a.value("compositeop").toString();
            if (!op.isEmpty()) l->setCompositeOpId(op);
            // alpha_locked 仅颜料层子类支持
            if (KisPaintLayer *pl = dynamic_cast<KisPaintLayer *>(l)) {
                pl->setAlphaLocked(a.value("alpha_locked") == QLatin1String("1"));
            }
        }
        if (a.value("background") == QLatin1String("1")) {
            bg = visible;
        }
    };

    while (!r.atEnd()) {
        r.readNext();
        if (r.isStartElement()) {
            const QStringView name = r.name();
            const QString nodeName = r.attributes().value("name").toString();
            KisNodeSP node;
            bool isGroup = false;

            if (name == QLatin1String("paintlayer")) {
                KisPaintLayerSP pl = new KisPaintLayer(image, nodeName.isEmpty() ? QStringLiteral("图层") : nodeName, 255, cs);
                const QString fn = r.attributes().value("filename").toString();
                QByteArray png;
                if (!fn.isEmpty() && store->open(fn)) {
                    png = readStoreEntryBytes(store);
                    store->close();
                }
                paintDeviceFromPng(pl->original(), png);
                node = pl;
            } else if (name == QLatin1String("group")) {
                node = new KisGroupLayer(image, nodeName.isEmpty() ? QStringLiteral("图层组") : nodeName, 255, cs);
                isGroup = true;
            } else if (name == QLatin1String("adjustmentlayer")) {
                const QXmlStreamAttributes a = r.attributes();
                auto attrDbl = [&a](const char *key) {
                    bool ok = false;
                    const double v = a.value(key).toString().toDouble(&ok);
                    return ok ? v : 0.0;
                };
                const int type = a.value("filter").toString().remove(QLatin1String("reverie-f")).toInt();
                const double p1 = attrDbl("p1");
                const double p2 = attrDbl("p2");
                const double p3 = attrDbl("p3");
                const double p4 = attrDbl("p4");
                const QByteArray lut = QByteArray::fromBase64(a.value("lut_b64").toLatin1());
                KisFilterConfigurationSP cfg = reverieMakeConfig(type, p1, p2, p3, p4, lut);
                if (!cfg) continue; // 滤镜缺失则放弃该节点(不中断整树)
                node = new KisAdjustmentLayer(image, nodeName, cfg, nullptr);
            } else if (name == QLatin1String("clonelayer") || name == QLatin1String("generatorlayer")) {
                // v1 限制: 克隆层源关系与生成器参数未序列化, 回退为颜料层保结构(T10 后 generator 走原生重建)
                const QString fn = r.attributes().value("filename").toString();
                QByteArray png;
                if (!fn.isEmpty() && store->open(fn)) {
                    png = readStoreEntryBytes(store);
                    store->close();
                }
                KisPaintLayerSP pl = new KisPaintLayer(image, nodeName, 255, cs);
                paintDeviceFromPng(pl->original(), png);
                node = pl;
            } else if (name == QLatin1String("transparencymask")) {
                node = new KisTransparencyMask(image, nodeName);
            } else if (name == QLatin1String("filtermask")) {
                KisFilterMaskSP fm = new KisFilterMask(image, nodeName);
                const QXmlStreamAttributes a = r.attributes();
                auto attrDbl = [&a](const char *key) {
                    bool ok = false;
                    const double v = a.value(key).toString().toDouble(&ok);
                    return ok ? v : 0.0;
                };
                const int type = a.value("filter").toString().remove(QLatin1String("reverie-f")).toInt();
                const QByteArray lut = QByteArray::fromBase64(a.value("lut_b64").toLatin1());
                KisFilterConfigurationSP cfg = reverieMakeConfig(type,
                                                                 attrDbl("p1"),
                                                                 attrDbl("p2"),
                                                                 attrDbl("p3"),
                                                                 attrDbl("p4"),
                                                                 lut);
                if (cfg) fm->setFilter(cfg);
                node = fm;
            } else if (name == QLatin1String("transformmask")) {
                node = new KisTransformMask(image, nodeName);
            } else if (name == QLatin1String("selectionmask")) {
                node = new KisSelectionMask(image, nodeName);
            }

            if (node) {
                applyCommonAttrs(node);
                image->addNode(node, parents.last());
                if (isGroup) parents.append(node);
                any = true;
            }
        } else if (r.isEndElement()) {
            if (r.name() == QLatin1String("group") && parents.size() > 1) {
                parents.removeLast();
            } else if (r.name() == QLatin1String("layers")) {
                break;
            }
        }
    }
    if (r.hasError()) return false;

    if (bgVisible) *bgVisible = bg;
    return any;
}
