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
}

bool ReverieCore::paintPanelQuad(int layerIndex, const QVector<QPointF> &corners, quint32 argb, bool eraseFill)
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
    if (eraseFill) {
        painter.setCompositeOpId(COMPOSITE_ERASE);
    }

    vQPointF pts;
    pts.reserve(corners.size());
    for (const QPointF &p : corners) {
        pts.append(p);
    }
    painter.paintPolygon(pts);

    device->setDirty();
    markDirty();
    txn.commit(image->undoAdapter());
    m_redoCount = 0;
    return true;
}
