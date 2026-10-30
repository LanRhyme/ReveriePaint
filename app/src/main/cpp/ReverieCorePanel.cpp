/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * ReverieCorePanel.cpp - 漫画分格 (comic panel) 域
 *
 * 分格 = 图层组容器: 组的首子层是一张"裁剪蒙版"颜料层 (复用 Layer-as-Mask
 * 架构, 侧表 key reverie_is_transparency_mask, 白=透光 黑=遮挡), 组内其余
 * 子层是格内容。格元数据 (四角坐标/样式 JSON) 存节点侧表
 * (reverie::NodeExtra::panelJson, key "reverie_panel"), 本文件提供其 JNI 可达
 * 的读写入口, 以及与用户笔刷预设/选区无关的专用实线绘制 (paintPanelQuad,
 * 栅格化格边框/填充/蒙版形状, 不依赖 m_brushPreset)。
 *
 * 组合成的蒙版消费在 ReverieCoreRender.cpp 的组分支 (见 applyGroupMask 调制块)。
 */
#include "ReverieCoreInternal.h"
#include "ReverieCoreLayerExtras.h"

#include <QColor>
#include <QPointF>

namespace {

// setPanelMeta 的撤销命令: 交换节点侧表上的分格元数据 (reverie_panel)。
// 持 KisNodeSP 而非图层索引 —— 侧表按节点指针寻址, 撤销期间图层重排/增删
// 不会让命令指错节点; 节点对象由 shared pointer 保活不存在悬垂 (节点被移出
// 文档后命令仍安全, 写回侧表属无害 no-op, 不影响画布像素)。范本参考
// ReverieCoreLayers.cpp 的 SetStrokeParamsCommand。
class SetPanelMetaCommand : public KUndo2Command {
public:
    SetPanelMetaCommand(KisNodeSP node, const QString &oldJson, const QString &newJson)
        : KUndo2Command(kundo2_i18n("Change Panel Meta"))
        , m_node(node)
        , m_oldJson(oldJson)
        , m_newJson(newJson)
    {}

    void undo() override {
        if (m_node) {
            reverie::setLayerProperty(&*m_node, "reverie_panel", m_oldJson);
        }
    }
    void redo() override {
        if (m_node) {
            reverie::setLayerProperty(&*m_node, "reverie_panel", m_newJson);
        }
    }

private:
    KisNodeSP m_node;
    QString m_oldJson;
    QString m_newJson;
};

} // namespace

QString ReverieCore::panelMeta(int layerIndex) const
{
    if (layerIndex < 0 || layerIndex >= m_layers.size() || !m_layers[layerIndex].node) {
        return QString();
    }
    return reverie::layerProperty(m_layers[layerIndex].node, "reverie_panel").toString();
}

void ReverieCore::setPanelMeta(int layerIndex, const QString &json)
{
    if (layerIndex < 0 || layerIndex >= m_layers.size() || !m_layers[layerIndex].node) {
        return;
    }
    KisNodeSP node(m_layers[layerIndex].node);
    const QString oldJson = reverie::layerProperty(&*node, "reverie_panel").toString();
    if (oldJson == json) {
        return;
    }
    // 经撤销栈写入: pushUndoCommand 会执行命令的 redo(); Kotlin 形状/样式宏
    // (beginUndoMacro..endUndoMacro) 打开期间命令入当前宏, 撤销整步一起回滚。
    // 末尾的直接写回兜底 (撤销捕获关闭/无 undoAdapter 等路径)。
    pushUndoCommand(new SetPanelMetaCommand(node, oldJson, json));
    reverie::setLayerProperty(&*node, "reverie_panel", json);
    // 同步 panelGroup 缓存位 (LayerEntry, 合成热路径 ReverieCoreRender.cpp 只读缓存
    // 位不再查侧表): 新建格的时序是 addMaskToLayer 先触发 syncLayersFromImage —— 此时
    // 本组还没有 reverie_panel 元数据, 缓存位被缓存为 false; 元数据在本函数才写入且
    // 不触发 sync。不同步缓存位的话, 新建格后到下一次结构 sync 之前合成会忽略蒙版
    // (格内容不裁剪)。跨格容器的 CROSS_MARKER_JSON 经 applyPanelMetaEvent 走同一入口,
    // 一并修复。节点指针校验防止期间图层重排导致索引指错条目。
    if (m_layers[layerIndex].node == node.data()) {
        m_layers[layerIndex].panelGroup = !json.isEmpty();
    }
}

bool ReverieCore::paintPanelQuad(int layerIndex, const QVector<QPointF> &corners, quint32 argb, bool eraseFill,
                                 bool antiAlias)
{
    KisImageSP image = m_document ? m_document : KisImageSP();
    if (!image) return false;
    if (layerIndex < 0 || layerIndex >= m_layers.size() || corners.size() < 3) {
        return false;
    }
    // 锁定层 no-op (与 clearLayer 的 isLayerEditable 门对齐): 用户在图层面板
    // 锁定该层后, 分格工具不再写入其像素
    if (!isLayerEditable(layerIndex)) {
        return false;
    }
    KisPaintLayer *pl = dynamic_cast<KisPaintLayer *>(m_layers[layerIndex].node);
    if (!pl) return false;
    KisPaintDeviceSP device = pl->paintDevice();
    if (!device) return false;

    KisTransaction txn(kundo2_i18n("Panel Shape"), device);
    KisPainter painter(device);

    // 分格绘制是工具行为: 不读 m_brushPreset/m_brushColor/m_selection, 也不套
    // alphaLocked 通道锁 —— 目标层是分格自己的蒙版/边框层, 与用户笔刷状态解耦。
    KoColor paintColor(QColor::fromRgba(static_cast<QRgb>(argb)), device->colorSpace());
    painter.setPaintColor(paintColor);
    painter.setFillStyle(KisPainter::FillStyleForegroundColor);
    painter.setStrokeStyle(KisPainter::StrokeStyleNone);
    painter.setOpacityF(1.0);
    // KisPainter 默认开启多边形填充抗锯齿 (kis_painter_p.h antiAliasPolygonFill=true),
    // AA 填充在边界像素只按覆盖率部分写入 (残留 white·c(1-c)); 老 quad 清除/涂黑场景
    // 传 antiAlias=false 走硬边, 量化可能残留有界 1px 边缘 (调用方 Kotlin 侧配合
    // 老 quad 外扩 1px 后严格覆盖)
    painter.setAntiAliasPolygonFill(antiAlias);
    if (eraseFill) {
        painter.setCompositeOpId(COMPOSITE_ERASE);
    }

    vQPointF pts;
    pts.reserve(corners.size());
    for (const QPointF &p : corners) {
        pts.append(p);
    }
    painter.paintPolygon(pts);

    // bbox 标脏 (格操作热路径): 一次格操作会连续多次 paintPanelQuad (边框/填充/
    // 蒙版形状 5-6 次), 整幅 markDirty() 会触发全画布重合成 —— 多格文档上表现为
    // 150-350ms 卡顿。按本次多边形参数算 bbox (+4px 外扩, 覆盖像素笔触与相邻
    // 边界), 只重合成格框附近区域; device->setDirty() 保留 (Krita 原生 projection
    // 一致性)。静态底图由 markDirty(QRect) 只作废相交宏瓦片; 缩略图只刷被绘制
    // 层与其祖先组 (与运笔 bumpLayerThumbGen 同口径), 不再全量作废。
    qreal minX = pts.at(0).x();
    qreal maxX = minX;
    qreal minY = pts.at(0).y();
    qreal maxY = minY;
    for (int i = 1; i < pts.size(); ++i) {
        minX = qMin(minX, pts.at(i).x());
        maxX = qMax(maxX, pts.at(i).x());
        minY = qMin(minY, pts.at(i).y());
        maxY = qMax(maxY, pts.at(i).y());
    }
    const QRect bbox(qFloor(minX) - 4, qFloor(minY) - 4,
                     qCeil(maxX) - qFloor(minX) + 8, qCeil(maxY) - qFloor(minY) + 8);

    device->setDirty();
    markDirty(bbox);
    bumpLayerThumbGen(pl);
    txn.commit(image->undoAdapter());
    m_redoCount = 0;
    return true;
}
