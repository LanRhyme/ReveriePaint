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
#include "ReverieCoreLayerExtras.h"
#include <functional>
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
        // 穿透混合模式 (仅组有语义): 不序列化则保存重开后退回隔离组
        if (KisGroupLayer *g = dynamic_cast<KisGroupLayer *>(node)) {
            w.writeAttribute("pass_through", g->passThroughMode() ? "1" : "0");
        }
    }
    w.writeAttribute("locked", e.locked ? "1" : "0");
    w.writeAttribute("alpha_locked", e.alphaLocked ? "1" : "0");
    w.writeAttribute("clipped", e.clipped ? "1" : "0");
    w.writeAttribute("inherit_alpha", e.alphaInherited ? "1" : "0");
    w.writeAttribute("color_label", QString::number(e.colorLabel));
    w.writeAttribute("x", QString::number(node ? int(node->x()) : 0));
    w.writeAttribute("y", QString::number(node ? int(node->y()) : 0));
    w.writeAttribute("background", e.background ? "1" : "0");
    if (node && reverie::layerProperty(&*node, "reverie_is_fill").toBool()) {
        w.writeAttribute("is_fill", "1");
        if (reverie::layerProperty(&*node, "reverie_fill_color").isValid()) {
            w.writeAttribute("fill_color", QString::number(reverie::layerProperty(&*node, "reverie_fill_color").toUInt()));
        }
    }
    if (e.isStrokeLayer || e.nodeType == ReverieCore::NodeTypeStroke) {
        w.writeAttribute("is_stroke", "1");
        w.writeAttribute("stroke_size", QString::number(e.strokeSize));
        w.writeAttribute("stroke_color", QString::number(e.strokeColor));
        w.writeAttribute("stroke_pos", QString::number(e.strokePosition));
        w.writeAttribute("stroke_opacity", QString::number(e.strokeOpacity));
    }
    if (e.nodeType == ReverieCore::NodeTypeTransparencyMask || (node && reverie::layerProperty(&*node, "reverie_is_transparency_mask").toBool())) {
        w.writeAttribute("is_transparency_mask", "1");
    }
    // 分格元数据 (四角/样式 JSON): QString 原样存取, 转义交给 QXmlStreamWriter;
    // 空串 = 非分格, 不写属性 (与 is_fill 等键的"去 reverie_ 前缀"属性命名一致)
    if (node) {
        const QString panelJson = reverie::layerProperty(&*node, "reverie_panel").toString();
        if (!panelJson.isEmpty()) {
            w.writeAttribute("panel", panelJson);
        }
    }
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

// 写一个节点的开始标签 + 属性 (不含子节点)。i 是它在 m_layers 里的下标, 供 filename 使用。
static void writeLayerElementAttributes(QXmlStreamWriter &w, const ReverieCore::LayerEntry &e, int i)
{
    // 预填色颜料层仍保存像素，不能按 generator 重建而丢失后续笔画
    const bool paintFill = e.node && reverie::layerProperty(&*e.node, "reverie_is_fill").toBool();
    w.writeStartElement(paintFill ? "paintlayer" : nodeElementName(e.nodeType));
    writeCommonAttrs(w, e);
    if (!e.isGroup && e.nodeType != ReverieCore::NodeTypeAdjustment) {
        // 数据文件名与 saveRevp/saveKra 的逐层 PNG 循环一致(含跳组空洞)
        w.writeAttribute("filename", QString("layer_%1.png").arg(i, 3, 10, QChar('0')));
    }
    if (e.nodeType == ReverieCore::NodeTypeAdjustment) {
        if (KisAdjustmentLayer *al = dynamic_cast<KisAdjustmentLayer *>(e.node)) {
            writeFilterAttrs(w, al->filter());
        }
    } else if (e.nodeType == ReverieCore::NodeTypeFill) {
        if (KisGeneratorLayer *gl = dynamic_cast<KisGeneratorLayer *>(e.node)) {
            QVariant v;
            const QColor c = (gl->filter() && gl->filter()->getProperty("color", v))
                ? v.value<QColor>() : QColor(Qt::white);
            w.writeAttribute("color_argb", QString::number(c.rgba()));
            if (gl->filter() && gl->filter()->getProperty("pattern_png", v)) {
                w.writeAttribute("pattern_png", QString::fromLatin1(v.toByteArray().toBase64()));
            }
        }
    } else if (e.nodeType == ReverieCore::NodeTypeFilterMask) {
        if (KisFilterMask *fm = dynamic_cast<KisFilterMask *>(e.node)) {
            writeFilterAttrs(w, fm->filter());
        }
    }
}

void ReverieCore::writeLayersXml(QString *out)
{
    if (!out) return;
    QXmlStreamWriter w(out);
    w.setAutoFormatting(true);
    w.setAutoFormattingIndent(1);
    w.writeStartDocument();
    w.writeStartElement("layers");

    // 嵌套关系**直接照节点树的父子关系写**, 不再用 LayerEntry::depth 反推。
    // 原因: depth 是给 UI 缩进用的显示深度, 与真实父子关系并不一致 —— 透明度蒙版在
    // m_layers 里是同层兄弟 (syncLayersFromImage 把它标成 depth+1 只是为了缩进显示)。
    // 用 depth 重组会把蒙版写成前一个图层的子节点, 层级一旦错位, "遇到深度 d 先闭合
    // 所有 depth>=d" 的闭合次数就会比开标签多, 把 </layers> 提前关掉, 于是后面的
    // <paintlayer> 落到根元素外面 —— 存档直接变成非法 XML, 重开时后续图层丢失、
    // 分组回退 (issue: 用户 "衣服" 图层跑在 </layers> 之后)。
    // filename 仍取 m_layers 下标, 与 saveRevp/saveKra 的逐层 PNG 循环一一对应。
    QHash<KisNode *, int> indexOfNode;
    indexOfNode.reserve(m_layers.size());
    for (int i = 0; i < m_layers.size(); ++i) {
        if (m_layers[i].node) {
            indexOfNode.insert(m_layers[i].node, i);
        }
    }

    std::function<void(KisNodeSP)> writeChildren = [&](KisNodeSP parent) {
        for (KisNodeSP node = parent->firstChild(); node; node = node->nextSibling()) {
            const auto it = indexOfNode.constFind(node.data());
            const bool known = (it != indexOfNode.constEnd());
            if (known) {
                writeLayerElementAttributes(w, m_layers[it.value()], it.value());
            }
            if (node->childCount() > 0) {
                writeChildren(node);
            }
            if (known) {
                w.writeEndElement();
            }
        }
    };

    KisImageSP image = m_document;
    if (image && image->rootLayer()) {
        writeChildren(image->rootLayer());
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

} // namespace

bool ReverieCore::loadLayersXmlTree(const QByteArray &xmlData, KisImageSP image, KoStore *store, bool *bgVisible, bool *outHealed)
{
    if (bgVisible) *bgVisible = false;
    if (outHealed) *outHealed = false;
    if (xmlData.isEmpty() || !image || !store) return false;

    QXmlStreamReader r(xmlData);
    while (!r.atEnd() && !r.isStartElement()) {
        r.readNext();
    }
    if (r.name() != QLatin1String("layers")) return false;

    const KoColorSpace *cs = image->colorSpace();
    QVector<KisNodeSP> parents;
    parents.append(image->rootLayer());
    // 元素嵌套栈 (只存元素名): transparencymask 需要知道自己是不是某个 group
    // 元素的直接子元素 (writeLayersXml 把组级蒙版嵌套写进宿主 group 元素内部,
    // 而子图层的蒙版嵌套写在子图层元素内, 二者靠此区分)。
    QVector<QString> elemStack;
    elemStack.append(QStringLiteral("layers"));
    // 与 parents 平行: 每个打开 group 最近重挂的组级蒙版, 多蒙版按 XML 次序叠放
    QVector<KisNodeSP> groupMaskAnchor;
    bool any = false;
    bool bg = false;
    // PNG 解码延迟到解析完成后多核并行 (见 LayerPixelLoader, ReverieCoreInternal.h)
    LayerPixelLoader loader;

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
            const bool isClipped = a.value("clipped") == QLatin1String("1");
            const bool isInherited = a.value("inherit_alpha") == QLatin1String("1");
            if (isClipped) {
                l->enableClippingLayer(true);
            } else if (isInherited) {
                l->disableAlphaChannel(true);
            }
            const QString op = a.value("compositeop").toString();
            if (!op.isEmpty()) l->setCompositeOpId(op);
            // 穿透混合模式恢复 (仅组有语义, writeCommonAttrs 写入)
            if (KisGroupLayer *g = dynamic_cast<KisGroupLayer *>(node.data())) {
                g->setPassThroughMode(a.value("pass_through") == QLatin1String("1"));
            }
            // alpha_locked 仅颜料层子类支持
            if (KisPaintLayer *pl = dynamic_cast<KisPaintLayer *>(l)) {
                pl->setAlphaLocked(a.value("alpha_locked") == QLatin1String("1"));
            }
        }
        if (a.value("background") == QLatin1String("1")) {
            bg = visible;
        }
        if (dynamic_cast<KisPaintLayer *>(node.data()) && a.value("is_fill") == QLatin1String("1")) {
            reverie::setLayerProperty(&*node, "reverie_is_fill", true);
            bool okColor = false;
            const quint32 fCol = a.value("fill_color").toString().toUInt(&okColor);
            if (okColor) {
                reverie::setLayerProperty(&*node, "reverie_fill_color", fCol);
            }
        }
        if (a.value("is_stroke") == QLatin1String("1")) {
            reverie::setLayerProperty(&*node, "reverie_is_stroke", true);
            reverie::setLayerProperty(&*node, "reverie_stroke_size", attrInt("stroke_size", 6));
            bool okColor = false;
            const quint32 sCol = a.value("stroke_color").toString().toUInt(&okColor);
            reverie::setLayerProperty(&*node, "reverie_stroke_color", okColor ? sCol : 0xFF000000u);
            reverie::setLayerProperty(&*node, "reverie_stroke_pos", attrInt("stroke_pos", 0));
            reverie::setLayerProperty(&*node, "reverie_stroke_opacity", attrInt("stroke_opacity", 100));
        }
        if (a.value("is_transparency_mask") == QLatin1String("1")) {
            reverie::setLayerProperty(&*node, "reverie_is_transparency_mask", true);
        }
        // 分格元数据回填侧表 (空缺 = 非分格, 不设键); QXmlStreamReader 已自动反转义
        const QString panelJson = a.value("panel").toString();
        if (!panelJson.isEmpty()) {
            reverie::setLayerProperty(&*node, "reverie_panel", panelJson);
        }
    };

    while (!r.atEnd()) {
        r.readNext();
        if (r.isStartElement()) {
            const QStringView name = r.name();
            // 先记嵌套栈再走分支: 分支内 continue (如滤镜缺失放弃节点) 不会破坏栈
            const bool insideGroupElement = elemStack.last() == QLatin1String("group");
            elemStack.append(name.toString());
            const QString nodeName = r.attributes().value("name").toString();
            KisNodeSP node;
            bool isGroup = false;
            // 有些分支需要在 stage 之前就应用属性 (见 paintlayer 分支的 offset 时序说明)
            bool attrsApplied = false;
            // transparencymask 出现在 group 元素内部 (直接子元素) 时置位: 见挂载点
            bool remountMaskAboveGroup = false;

            if (name == QLatin1String("paintlayer")) {
                KisPaintLayerSP pl = new KisPaintLayer(image, nodeName.isEmpty() ? QStringLiteral("图层") : nodeName, 255, cs);
                // 必须先应用 x/y 等属性再 stage: stage 可能在批次满 8 层时立即 flush,
                // 而 LayerPixelLoader 写回用的是画布坐标 (convertFromQImage)。
                // 若 offset 晚于写像素生效, 内容会被再平移一次 offset (见 t35 实证)。
                applyCommonAttrs(pl);
                attrsApplied = true;
                const QString fn = r.attributes().value("filename").toString();
                QByteArray png;
                if (!fn.isEmpty() && store->open(fn)) {
                    png = readStoreEntryBytes(store);
                    store->close();
                }
                if (!png.isEmpty()) {
                    PendingLayerPixels job;
                    job.dev = pl->original();
                    job.pngBytes = png;
                    job.hasPng = true;
                    job.hasData = true;
                    loader.stage(job);
                }
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
            } else if (name == QLatin1String("generatorlayer")) {
                // 原生 generator 填充层重建; color 缺失回退白色
                const QXmlStreamAttributes a = r.attributes();
                bool okColor = false;
                const quint32 colorArgb = a.value("color_argb").toUInt(&okColor, 10);
                const auto encodedPattern = a.value("pattern_png");
                KisFilterConfigurationSP gcfg;
                if (!encodedPattern.isEmpty() && encodedPattern.size() <= 24 * 1024 * 1024) {
                    gcfg = reverieMakePatternConfig(QByteArray::fromBase64(encodedPattern.toLatin1()));
                } else if (encodedPattern.isEmpty()) {
                    gcfg = reverieMakeSolidColorConfig(okColor ? colorArgb : 0xFFFFFFFFu);
                }
                if (gcfg) {
                    node = new KisGeneratorLayer(image, nodeName, gcfg, nullptr);
                } else {
                    // A damaged/oversized pattern must not turn white or leave a partial tree.
                    // Use the layer's saved raster backup, just like unsupported clone layers.
                    KisPaintLayerSP fallback = new KisPaintLayer(image, nodeName, 255, cs);
                    // offset 必须先于像素写回 (见 paintlayer 分支说明)
                    applyCommonAttrs(fallback);
                    attrsApplied = true;
                    const QString filename = a.value("filename").toString();
                    if (!filename.isEmpty() && store->open(filename)) {
                        PendingLayerPixels job;
                        job.dev = fallback->original();
                        job.pngBytes = readStoreEntryBytes(store);
                        job.hasPng = !job.pngBytes.isEmpty();
                        job.hasData = job.hasPng;
                        store->close();
                        loader.stage(job);
                    }
                    node = fallback;
                }
            } else if (name == QLatin1String("clonelayer")) {
                // v1 限制: 克隆层源关系未序列化, 回退为颜料层保结构
                const QString fn = r.attributes().value("filename").toString();
                QByteArray png;
                if (!fn.isEmpty() && store->open(fn)) {
                    png = readStoreEntryBytes(store);
                    store->close();
                }
                KisPaintLayerSP pl = new KisPaintLayer(image, nodeName, 255, cs);
                // offset 必须先于像素写回 (见 paintlayer 分支说明)
                applyCommonAttrs(pl);
                attrsApplied = true;
                if (!png.isEmpty()) {
                    PendingLayerPixels job;
                    job.dev = pl->original();
                    job.pngBytes = png;
                    job.hasPng = true;
                    job.hasData = true;
                    loader.stage(job);
                }
                node = pl;
            } else if (name == QLatin1String("transparencymask")) {
                const QString fn = r.attributes().value("filename").toString();
                QByteArray png;
                if (!fn.isEmpty() && store->open(fn)) {
                    png = readStoreEntryBytes(store);
                    store->close();
                }
                // 在现代架构中，透明度蒙版作为 KisPaintLayer 挂载在图层树中
                KisPaintLayerSP tmask = new KisPaintLayer(image, nodeName.isEmpty() ? QStringLiteral("蒙版") : nodeName, 255, cs);
                reverie::setLayerProperty(&*tmask, "reverie_is_transparency_mask", true);
                // 直接子元素于 group => 旧存档的组级蒙版 (组的宿主蒙版), 见挂载点
                remountMaskAboveGroup = insideGroupElement;
                // offset 必须先于像素写回 (见 paintlayer 分支说明)
                applyCommonAttrs(tmask);
                attrsApplied = true;
                if (!png.isEmpty()) {
                    PendingLayerPixels job;
                    job.dev = tmask->original();
                    job.pngBytes = png;
                    job.hasPng = true;
                    job.hasData = true;
                    loader.stage(job);
                } else {
                    tmask->paintDevice()->fill(QRect(0, 0, image->width(), image->height()), KoColor(Qt::white, cs));
                }
                node = tmask;
            } else if (name == QLatin1String("filtermask")) {
                KisFilterMaskSP fm = new KisFilterMask(image, nodeName);
                if (KisLayer *parentLayer = dynamic_cast<KisLayer *>(parents.last().data())) {
                    fm->initSelection(parentLayer);
                }
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
                if (!attrsApplied) {
                    applyCommonAttrs(node);
                }
                if (parents.isEmpty()) {
                    parents.append(image->rootLayer());
                }
                if (remountMaskAboveGroup && parents.size() > 1) {
                    // 旧存档兼容 (必须): writeLayersXml 按深度嵌套, 组级透明蒙版
                    // (depth = 宿主组 + 1) 被写进宿主 group 元素内部; 若照元素
                    // 层级挂成组的子层, 重载后 walk 会给它 group 深度 + 2, 合成器
                    // 的 depth == e.depth + 1 查找 (ReverieCoreRender.cpp 主循环)
                    // 失配, 分格裁剪全部失效。此处按运行时 addMaskToLayer 的挂载
                    // 语义重挂为组的同级上方兄弟 (同一父节点, 组的视觉上方);
                    // 蒙版自身像素已照常从其 layer 文件暂存加载。
                    // groupMaskAnchor 保持多蒙版 (对同一组连续添加) 的 XML 次序。
                    KisNodeSP hostGroup = parents.last();
                    KisNodeSP hostParent = parents.at(parents.size() - 2);
                    KisNodeSP above = groupMaskAnchor.last();
                    if (!above) {
                        above = hostGroup;
                    }
                    image->addNode(node, hostParent, above);
                    groupMaskAnchor.last() = node;
                } else {
                    image->addNode(node, parents.last());
                }
                if (isGroup) {
                    parents.append(node);
                    groupMaskAnchor.append(KisNodeSP());
                }
                any = true;
            }
        } else if (r.isEndElement()) {
            if (!elemStack.isEmpty()) {
                elemStack.removeLast();
            }
            if (r.name() == QLatin1String("group") && parents.size() > 1) {
                parents.removeLast();
                groupMaskAnchor.removeLast();
            } else if (r.name() == QLatin1String("layers")) {
                // 宽容解析: 旧版本写坏过的存档里, <paintlayer> 可能落在 </layers> 之后
                // (根元素提前闭合, 见 writeLayersXml 的注释)。这里**不 break**: 把父链
                // 退回根节点继续读, 这些游离节点按顶层图层收下 —— 用户丢的图层还能救回来,
                // 只是分组归属需要重新整理, 并置 healed 让上层知道这次是自愈加载。
                if (!parents.isEmpty() && parents.size() > 1) {
                    parents = QVector<KisNodeSP>();
                    parents.append(image->rootLayer());
                    if (outHealed) *outHealed = true;
                } else if (parents.isEmpty()) {
                    parents.append(image->rootLayer());
                }
            }
        }
    }
    // 树解析完成, 统一并行解码 + 串行写回全部暂存图层
    loader.flush();
    if (r.hasError()) {
        qWarning() << "loadLayersXmlTree: XML parsing error:" << r.errorString() << ", retained recovered nodes:" << any;
        if (outHealed) *outHealed = true;
        if (!any) return false;
    }

    if (bgVisible) *bgVisible = bg;
    return any;
}
