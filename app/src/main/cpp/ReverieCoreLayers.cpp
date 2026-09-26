/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/* ============================================================
 * ReverieCoreLayers.cpp - Layer list management: add/remove/reorder, visibility, blend modes, groups, masks, locking
 * (part of the ReverieCore module split; shared helpers live in
 * ReverieCoreInternal.h, public API in ReverieCore.h)
 * ============================================================ */
#include "ReverieCoreInternal.h"
#include <QSet>

int ReverieCore::indexOfNode(KisNode *node) const
{
    for (int i = 0; i < m_layers.size(); ++i) {
        if (m_layers[i].node == node) {
            return i;
        }
    }
    return -1;
}

bool ReverieCore::isLayerEditable(int index) const
{
    if (index < 0 || index >= m_layers.size()) {
        return false;
    }
    const auto &e = m_layers[index];
    return !(e.isGroup || e.background || e.locked || e.nodeType == NodeTypeAdjustment);
}

KisPaintDeviceSP ReverieCore::layerPaintDeviceFor(const LayerEntry &entry) const
{
    if (!entry.node || entry.nodeType == NodeTypeAdjustment) {
        return nullptr;
    }
    if (KisPaintLayer *pl = dynamic_cast<KisPaintLayer *>(entry.node)) {
        return pl->paintDevice();
    }
    if (dynamic_cast<KisAdjustmentLayer *>(entry.node)) {
        return nullptr;
    }
    if (KisMask *m = dynamic_cast<KisMask *>(entry.node)) {
        return m->paintDevice();
    }
    if (KisLayer *l = dynamic_cast<KisLayer *>(entry.node)) {
        return l->projection();
    }
    return nullptr;
}


// KisImageChangeVisibilityCommand is not exported from libkritaimage (no
// KRITAIMAGE_EXPORT on its header), so provide a small local command with
// identical semantics: redo()/undo() flip the node's visibility and the
// caller marks the projection dirty.

static QString defaultPaintLayerName(const QVector<ReverieCore::LayerEntry> &layers)
{
    int n = 1;
    for (const auto &e : layers) {
        // parse trailing number of "颜料图层 N"
        const int sp = e.name.lastIndexOf(QLatin1Char(' '));
        if (sp >= 0) {
            bool ok = false;
            const int num = e.name.mid(sp + 1).toInt(&ok);
            if (ok) {
                n = qMax(n, num + 1);
            }
        }
    }
    return QStringLiteral("颜料图层 %1").arg(n);
}


// Insert position above the current layer: inside the current group when the
// current layer IS a group, otherwise directly above it at the same level.
void currentInsertPosition(const QVector<ReverieCore::LayerEntry> &layers, int current, KisNodeSP &above, KisNodeSP &parent, KisImageSP image)
{
    if (current >= 0 && current < int(layers.size())) {
        const auto &e = layers[current];
        if (e.node) {
            if (e.isGroup) {
                above = KisNodeSP();
                parent = KisNodeSP(e.node);
                return;
            }
            above = KisNodeSP(e.node);
            parent = above->parent();
            if (parent) {
                return;
            }
        }
    }
    above = KisNodeSP();
    parent = image ? KisNodeSP(image->rootLayer()) : KisNodeSP();
}
int ReverieCore::addLayer(const QString &name)
{
    KisImageSP image = m_document;
    if (!image) {
        return -1;
    }
    if (!m_previewTransactions.isEmpty() || m_previewTransaction) {
        cancelTransformPreview();
    }
    const KoColorSpace *cs = image->colorSpace();
    QString layerName = name.isEmpty() ? defaultPaintLayerName(m_layers) : name;

    // Ensure unique name to prevent duplicate layer name crashes in UI
    QSet<QString> existingNames;
    for (const LayerEntry &l : m_layers) {
        existingNames.insert(l.name);
    }
    if (existingNames.contains(layerName)) {
        int n = 2;
        while (existingNames.contains(layerName + QStringLiteral(" ") + QString::number(n))) {
            n++;
        }
        layerName = layerName + QStringLiteral(" ") + QString::number(n);
    }

    KisPaintLayerSP newLayer = new KisPaintLayer(image, layerName, 255, cs);
    if (!newLayer) {
        return -1;
    }

    // Insert directly above the current layer (inside its group if any)
    KisNodeSP above;
    KisNodeSP parent;
    currentInsertPosition(m_layers, m_currentLayer, above, parent, image);
    if (!parent) {
        parent = KisNodeSP(image->rootLayer());
    }
    if (!parent) {
        return -1;
    }

    // Krita-native undo: push a layer-add command through the undo adapter.
    // KUndo2Stack::push executes redo() (which performs the addNode).
    pushUndoCommand(new KisImageLayerAddCommand(image, newLayer, parent, above));
    recompositeProjection();
    syncLayersFromImage();
    const int idx = indexOfNode(newLayer.data());
    if (idx < 0) {
        return -1;
    }
    m_currentLayer = idx;
    markDirty();
    return m_currentLayer;
}

int ReverieCore::addGroupLayer(const QString &name)
{
    KisImageSP image = m_document;
    if (!image) {
        return -1;
    }
    QString groupName = name.isEmpty() ? QStringLiteral("图层组") : name;
    QSet<QString> existingGroupNames;
    for (const LayerEntry &l : m_layers) {
        existingGroupNames.insert(l.name);
    }
    if (existingGroupNames.contains(groupName)) {
        int n = 2;
        while (existingGroupNames.contains(groupName + QStringLiteral(" ") + QString::number(n))) {
            n++;
        }
        groupName = groupName + QStringLiteral(" ") + QString::number(n);
    }
    KisGroupLayerSP group = new KisGroupLayer(image, groupName, 255, image->colorSpace());
    if (!group) {
        return -1;
    }
    KisNodeSP above;
    KisNodeSP parent;
    currentInsertPosition(m_layers, m_currentLayer, above, parent, image);
    if (!parent) {
        parent = KisNodeSP(image->rootLayer());
    }
    if (!parent) {
        return -1;
    }
    pushUndoCommand(new KisImageLayerAddCommand(image, group, parent, above));
    recompositeProjection();
    syncLayersFromImage();
    const int idx = indexOfNode(group.data());
    if (idx < 0) {
        return -1;
    }
    m_currentLayer = idx;
    markDirty();
    return m_currentLayer;
}

bool ReverieCore::addLayerWithType(const QString &name, int type, quint32 fillColor)
{
    KisImageSP image = m_document;
    if (!image) {
        return false;
    }
    if (!m_previewTransactions.isEmpty() || m_previewTransaction) {
        cancelTransformPreview();
    }
    const int count = m_layers.size();
    QString finalName = name;
    if (finalName.isEmpty()) {
        switch (type) {
        case LayerTypePaint: finalName = QString("颜料图层 %1").arg(count); break;
        case LayerTypeGroup: finalName = QString("图层组 %1").arg(count); break;
        case LayerTypeFill: finalName = QString("填充图层 %1").arg(count); break;
        case LayerTypeAdjustment: finalName = QString("调整图层 %1").arg(count); break;
        case LayerTypeVector: finalName = QString("矢量图层 %1").arg(count); break;
        case LayerTypeClone: finalName = QString("克隆图层 %1").arg(count); break;
        case LayerTypeStroke: finalName = QString("描边图层 %1").arg(count); break;
        default: finalName = QString("图层 %1").arg(count); break;
        }
    }
    QSet<QString> existingTypeNames;
    for (const LayerEntry &l : m_layers) {
        existingTypeNames.insert(l.name);
    }
    if (existingTypeNames.contains(finalName)) {
        int n = 2;
        while (existingTypeNames.contains(finalName + QStringLiteral(" ") + QString::number(n))) {
            n++;
        }
        finalName = finalName + QStringLiteral(" ") + QString::number(n);
    }

    KisNodeSP above;
    KisNodeSP parent;
    currentInsertPosition(m_layers, m_currentLayer, above, parent, image);
    if (!parent) {
        parent = KisNodeSP(image->rootLayer());
    }
    if (!parent) {
        return false;
    }

    KisNodeSP newNode;
    if (type == LayerTypeGroup) {
        newNode = new KisGroupLayer(image, finalName, 255, image->colorSpace());
    } else if (type == LayerTypeClone) {
        const int selIdx = qBound(0, m_currentLayer, m_layers.size() - 1);
        KisLayerSP srcLayer = dynamic_cast<KisLayer *>(m_layers[selIdx].node);
        if (srcLayer) {
            newNode = new KisCloneLayer(srcLayer, image, finalName, 255);
        } else {
            newNode = new KisPaintLayer(image, finalName, 255, image->colorSpace());
        }
    } else if (type == LayerTypeStroke) {
        const KoColorSpace *cs = image->colorSpace();
        KisPaintLayerSP paintLayer = new KisPaintLayer(image, finalName, 255, cs);
        KisPSDLayerStyleSP style(new KisPSDLayerStyle());
        style->setName(QStringLiteral("StrokeStyle"));
        style->setEnabled(true);
        style->stroke()->setEffectEnabled(true);
        style->stroke()->setSize(6);
        style->stroke()->setColor(KoColor(QColor::fromRgba(0xFF000000), cs));
        style->stroke()->setPosition(psd_stroke_outside);
        style->stroke()->setOpacity(100);
        style->stroke()->setBlendMode(COMPOSITE_OVER);
        paintLayer->setLayerStyle(style);
        newNode = paintLayer;
    } else {
        const KoColorSpace *cs = image->colorSpace();
        KisPaintLayerSP paintLayer = new KisPaintLayer(image, finalName, 255, cs);
        if (type == LayerTypeFill) {
            // 回滚至稳定行为: 预填色颜料层 (generator 填充层待真机问题解决后再启用)
            QColor qc = QColor::fromRgba(fillColor);
            paintLayer->original()->fill(QRect(0, 0, image->width(), image->height()), KoColor(qc, cs));
            paintLayer->original()->setDirty();
            newNode = paintLayer;
        } else if (type == LayerTypeAdjustment) {
            // 兼容旧录制/旧入口: 建中性 reverie-f0 配置的真调整层
            // (带参创建走 createAdjustmentLayer); 注册表不可用时回退旧伪实现
            if (KisFilterConfigurationSP cfg = reverieMakeConfig(0, 0.0, 0.0, 0.0, 0.0, QByteArray())) {
                newNode = new KisAdjustmentLayer(image, finalName, cfg, nullptr);
            } else {
                paintLayer->setCompositeOpId(QStringLiteral("overlay"));
                newNode = paintLayer;
            }
        } else {
            newNode = paintLayer;
        }
    }

    pushUndoCommand(new KisImageLayerAddCommand(image, newNode, parent, above));
    recompositeProjection();
    syncLayersFromImage();
    const int idx = indexOfNode(newNode.data());
    if (idx >= 0) {
        m_currentLayer = idx;
    }
    markDirty();
    return true;
}

int ReverieCore::copyLayer(int index)
{
    if (index <= 0 || index >= m_layers.size()) {
        return -1;  // background cannot be copied
    }
    LayerEntry &src = m_layers[index];
    if (!src.node) return -1;
    KisImageSP image = m_document;
    if (!image) return -1;

    // 关键1: 等待正在进行的渲染或重投影完成, 避免 clone 瓦片时并发冲突
    image->waitForDone();

    KisNodeSP cloned = src.node->clone();
    if (!cloned) return -1;
    cloned->setImage(image);

    // 关键2: 计算全局唯一的副本名称, 避免连续复制时重名造成索引错乱
    QSet<QString> existingNames;
    for (const LayerEntry &l : m_layers) {
        existingNames.insert(l.name);
    }
    QString candidateName = src.name + QStringLiteral(" 副本");
    if (existingNames.contains(candidateName)) {
        int copySeq = 2;
        while (existingNames.contains(src.name + QStringLiteral(" 副本 ") + QString::number(copySeq))) {
            copySeq++;
        }
        candidateName = src.name + QStringLiteral(" 副本 ") + QString::number(copySeq);
    }
    cloned->setName(candidateName);

    KisNodeSP above = KisNodeSP(src.node);
    KisNodeSP parent = above ? above->parent() : KisNodeSP(image->rootLayer());
    pushUndoCommand(new KisImageLayerAddCommand(image, cloned, parent, above));
    recompositeProjection();
    syncLayersFromImage();
    const int idx = indexOfNode(cloned.data());
    if (idx >= 0) {
        m_currentLayer = idx;
    }
    markDirty();
    return m_currentLayer;
}

int ReverieCore::copySelectionToNewLayer(bool cut)
{
    KisImageSP image = m_document;
    if (!image) return -1;
    if (!hasSelection()) return -1;
    if (m_currentLayer < 0 || m_currentLayer >= m_layers.size()) return -1;

    LayerEntry &src = m_layers[m_currentLayer];
    KisPaintDeviceSP srcDev = layerPaintDeviceFor(src);
    if (!srcDev) return -1;

    const QRect canvasRect(0, 0, image->width(), image->height());
    QRect selBounds = m_selection->selectedExactRect().intersected(canvasRect);
    if (selBounds.isEmpty() || !selBounds.isValid()) return -1;

    const KoColorSpace *cs = image->colorSpace();
    QString newName = QStringLiteral("选区 ") + QString::number(m_layers.size());
    KisPaintLayerSP newLayer = new KisPaintLayer(image, newName, 255, cs);
    if (!newLayer) return -1;

    KisPaintDeviceSP dstDev = newLayer->paintDevice();
    if (!dstDev) return -1;

    // Copy selected pixels from srcDev to dstDev
    KisPainter p(dstDev);
    p.setSelection(m_selection);
    p.bitBlt(selBounds.topLeft(), srcDev, selBounds);
    p.end();
    dstDev->setDirty(selBounds);

    if (cut) {
        KisTransaction *cutTxn = new KisTransaction(kundo2_i18n("Cut Selection"), srcDev, nullptr, -1, nullptr);
        srcDev->clearSelection(m_selection);
        srcDev->setDirty(selBounds);
        pushUndoCommand(cutTxn->endAndTake());
        delete cutTxn;
    }

    KisNodeSP above;
    KisNodeSP parent;
    currentInsertPosition(m_layers, m_currentLayer, above, parent, image);
    pushUndoCommand(new KisImageLayerAddCommand(image, newLayer, parent, above));

    image->waitForDone();
    recompositeProjection();
    syncLayersFromImage();

    const int idx = indexOfNode(newLayer.data());
    if (idx >= 0) {
        m_currentLayer = idx;
    }
    markDirty();
    return m_currentLayer;
}

int ReverieCore::stampVisibleLayers()
{
    KisImageSP image = m_document;
    if (!image) {
        return -1;
    }
    recompositeProjection();
    KisPaintDeviceSP proj = image->projection();
    if (!proj) {
        return -1;
    }
    KisPaintLayerSP nl = new KisPaintLayer(image, QStringLiteral("盖印可见图层"), 255, image->colorSpace());
    if (!nl) {
        return -1;
    }
    const QRect bounds(0, 0, image->width(), image->height());
    nl->original()->makeCloneFrom(proj, bounds);
    nl->original()->setDirty(bounds);

    KisNodeSP above;
    KisNodeSP parent;
    currentInsertPosition(m_layers, m_currentLayer, above, parent, image);
    pushUndoCommand(new KisImageLayerAddCommand(image, nl, parent, above));
    recompositeProjection();
    syncLayersFromImage();
    const int idx = indexOfNode(nl.data());
    if (idx < 0) {
        return -1;
    }
    m_currentLayer = idx;
    markDirty();
    return m_currentLayer;
}

void ReverieCore::removeLayer(int index)
{
    if (index <= 0 || index >= m_layers.size()) {
        return;  // background (0) is protected
    }
    // Groups CAN be deleted (removeNode removes the whole subtree), but
    // locked layers cannot. isLayerEditable() also excludes groups, so it
    // must not gate deletion.
    const LayerEntry &e = m_layers[index];
    if (e.background || e.locked) {
        return;
    }
    KisImageSP image = m_document;
    if (!image || !m_layers[index].node) {
        return;
    }
    // Krita-native undo: a layer-remove command (undo re-inserts the node)
    pushUndoCommand(new KisImageLayerRemoveCommand(image, KisNodeSP(m_layers[index].node)));
    recompositeProjection();
    syncLayersFromImage();
    if (m_currentLayer >= m_layers.size()) {
        m_currentLayer = qMax(0, m_layers.size() - 1);
    } else if (m_currentLayer == index) {
        m_currentLayer = qMax(0, index - 1);
    }
    markDirty();
}

void ReverieCore::clearLayer(int index)
{
    if (!isLayerEditable(index)) {
        return;
    }
    KisPaintDeviceSP dev = layerPaintDeviceFor(m_layers[index]);
    if (!dev) {
        return;
    }
    // Krita-native undo: wrap the pixel clear in a transaction
    KisTransaction txn(kundo2_i18n("Clear"), dev);
    dev->clear();
    dev->setDirty();
    if (m_document) {
        txn.commit(m_document->undoAdapter());
        m_redoCount = 0;
    }
    // Content became empty: dirty-region projection leaf updates do not
    // recomposite regions whose content is now empty, so the cleared layer
    // would keep showing its old pixels until a later full recomposite.
    // Force a full subtree walk + merge so the projection matches.
    recompositeProjection();
    markDirty();
}

void ReverieCore::setCurrentLayer(int index)
{
    if (index < 0 || index >= m_layers.size() || index == m_currentLayer) {
        return;
    }
    m_currentLayer = index;
    if (m_onionSkinActive) {
        // 洋葱皮只渲染当前选中轨道: 换层后把渲染门搬到新层 (旧层擦掉叠影,
        // 新层画出叠影)。笔触洋葱皮缓存按图层索引作 key, 换层后同一索引
        // 指向别的图层, 必须整体作废。
        applyOnionSkinGate(false);
        invalidateStrokeOnionCache();
        markRegionDirty(QRect(0, 0, m_docWidth, m_docHeight));
    }
}

QString ReverieCore::layerName(int index) const
{
    if (index < 0 || index >= m_layers.size()) {
        return QString();
    }
    return m_layers[index].name;
}

void ReverieCore::setLayerName(int index, const QString &name)
{
    if (index <= 0 || index >= m_layers.size() || name.isEmpty()) {
        return;  // background cannot be renamed
    }
    if (m_layers[index].node) {
        const QString oldName = m_layers[index].name;
        pushUndoCommand(new KisNodeRenameCommand(
            KisNodeSP(m_layers[index].node), oldName, name));
        m_layers[index].name = name;
    }
}

void ReverieCore::setLayerVisible(int index, bool visible)
{
    if (index < 0 || index >= m_layers.size()) {
        return;
    }
    if (m_layers[index].visible != visible && m_layers[index].node) {
        // Krita-native undo: the visibility command redo() flips the flag
        // (local command - KisImageChangeVisibilityCommand is not exported)
        pushUndoCommand(new ReverieNodeVisibleCommand(
            KisNodeSP(m_layers[index].node), visible,
            kundo2_i18n("Layer Visibility")));
        m_layers[index].visible = visible;
        if (m_layers[index].background && m_document) {
            const KoColorSpace *cs = m_document->colorSpace();
            if (visible) {
                m_document->setDefaultProjectionColor(KoColor(m_backgroundColor, cs));
            } else {
                m_document->setDefaultProjectionColor(KoColor(Qt::transparent, cs));
            }
        }
        recompositeProjection();
        markDirty();
    }
}

bool ReverieCore::layerVisible(int index) const
{
    if (index < 0 || index >= m_layers.size()) {
        return true;
    }
    return m_layers[index].visible;
}

bool ReverieCore::layerLocked(int index) const
{
    if (index < 0 || index >= m_layers.size()) {
        return false;
    }
    return m_layers[index].locked;
}

void ReverieCore::setLayerLocked(int index, bool locked)
{
    if (index <= 0 || index >= m_layers.size()) {
        return;  // background is always locked
    }
    if (m_layers[index].node) {
        m_layers[index].node->setUserLocked(locked);
        m_layers[index].locked = locked;
    }
}

bool ReverieCore::layerAlphaLocked(int index) const
{
    if (index < 0 || index >= m_layers.size()) {
        return false;
    }
    return m_layers[index].alphaLocked;
}

void ReverieCore::setLayerAlphaLocked(int index, bool locked)
{
    if (index <= 0 || index >= m_layers.size()) {
        return;  // background is always alpha-locked
    }
    KisPaintLayer *pl = m_layers[index].isGroup ? nullptr
                                               : dynamic_cast<KisPaintLayer *>(m_layers[index].node);
    if (pl) {
        pl->setAlphaLocked(locked);
        m_layers[index].alphaLocked = locked;
        if (!locked) {
            pl->setTemporaryChannelFlags(QBitArray());
            if (m_strokePainter && index == m_currentLayer) {
                m_strokePainter->setChannelFlags(QBitArray());
            }
        }
    }
}

qreal ReverieCore::layerOpacity(int index) const
{
    if (index < 0 || index >= m_layers.size() || !m_layers[index].node) {
        return 1.0;
    }
    return qreal(m_layers[index].node->opacity()) / 255.0;
}

void ReverieCore::setLayerOpacity(int index, qreal opacity)
{
    if (index <= 0 || index >= m_layers.size() || !m_layers[index].node) {
        return;  // background stays opaque
    }
    const quint8 o = quint8(qBound<qreal>(0.0, opacity, 1.0) * 255.0);
    // Krita-native undo: the opacity command redo() applies the value
    pushUndoCommand(new KisNodeOpacityCommand(KisNodeSP(m_layers[index].node), o));
    if (m_layers[index].nodeType == NodeTypeAdjustment) {
        recompositeProjection();
    } else {
        m_layers[index].node->setDirty(QRect(0, 0, m_document->width(), m_document->height()));
    }
    markDirty();
}

// Opacity change WITHOUT pushing an undo command - used while the user is
// dragging the opacity slider (many values per second). The slider commit on
// release goes through setLayerOpacity() above, so the whole drag collapses
// into a single undo step
void ReverieCore::setLayerOpacityDirect(int index, qreal opacity)
{
    if (index <= 0 || index >= m_layers.size() || !m_layers[index].node) {
        return;  // background stays opaque
    }
    const quint8 o = quint8(qBound<qreal>(0.0, opacity, 1.0) * 255.0);
    if (m_layers[index].node->opacity() != o) {
        m_layers[index].node->setOpacity(o);
        if (m_layers[index].nodeType == NodeTypeAdjustment) {
            recompositeProjection();
        } else {
            m_layers[index].node->setDirty(QRect(0, 0, m_document->width(), m_document->height()));
        }
        markDirty();
    }
}

void ReverieCore::setLayerBlendMode(int index, const QString &opId)
{
    if (index <= 0 || index >= m_layers.size()) {
        return;  // background is always 'normal'
    }
    if (m_layers[index].node) {
        // Krita-native undo: the composite-op command redo() applies the op
        pushUndoCommand(new KisNodeCompositeOpCommand(KisNodeSP(m_layers[index].node), opId));
        if (m_layers[index].nodeType == NodeTypeAdjustment) {
            recompositeProjection();
        } else {
            m_layers[index].node->setDirty(
                QRect(0, 0, m_document->width(), m_document->height()));
        }
        markBlendChanged(index);
    }
}

QString ReverieCore::layerBlendMode(int index) const
{
    if (index < 0 || index >= m_layers.size() || !m_layers[index].node) {
        return QStringLiteral("normal");
    }
    return m_layers[index].node->compositeOpId();
}

int ReverieCore::layerColorLabel(int index) const
{
    if (index < 0 || index >= m_layers.size()) {
        return 0;
    }
    return m_layers[index].colorLabel;
}

void ReverieCore::setLayerColorLabel(int index, int label)
{
    if (index < 0 || index >= m_layers.size() || !m_layers[index].node) {
        return;
    }
    m_layers[index].node->setColorLabelIndex(label);
    m_layers[index].colorLabel = label;
}

bool ReverieCore::layerIsGroup(int index) const
{
    if (index < 0 || index >= m_layers.size()) {
        return false;
    }
    return m_layers[index].isGroup;
}

int ReverieCore::layerNodeType(int index) const
{
    if (index < 0 || index >= m_layers.size()) {
        return -1;
    }
    return m_layers[index].nodeType;
}

int ReverieCore::layerDepth(int index) const
{
    if (index < 0 || index >= m_layers.size()) {
        return 0;
    }
    return m_layers[index].depth;
}

bool ReverieCore::layerBackground(int index) const
{
    if (index < 0 || index >= m_layers.size()) {
        return false;
    }
    return m_layers[index].background;
}

bool ReverieCore::layerClipped(int index) const
{
    if (index < 0 || index >= m_layers.size()) {
        return false;
    }
    if (KisLayer *layer = dynamic_cast<KisLayer *>(m_layers[index].node)) {
        return layer->alphaChannelDisabled();
    }
    return m_layers[index].clipped;
}

void ReverieCore::setLayerClipped(int index, bool clipped)
{
    if (index <= 0 || index >= m_layers.size()) {
        return;
    }
    m_layers[index].clipped = clipped;
    if (KisLayer *layer = dynamic_cast<KisLayer *>(m_layers[index].node)) {
        layer->disableAlphaChannel(clipped);
        m_layers[index].node->setDirty(QRect(0, 0, m_document->width(), m_document->height()));
    }
    recompositeProjection();
    markDirty();
}

bool ReverieCore::isLayerStroke(int index) const
{
    if (index < 0 || index >= m_layers.size()) return false;
    const LayerEntry &e = m_layers[index];
    if (e.isStrokeLayer || e.nodeType == NodeTypeStroke) return true;
    if (KisLayer *layer = dynamic_cast<KisLayer *>(e.node)) {
        return layer->layerStyle() && layer->layerStyle()->stroke() && layer->layerStyle()->stroke()->effectEnabled();
    }
    return false;
}

bool ReverieCore::setLayerStrokeParams(int index, int size, quint32 color, int position, int opacity)
{
    if (index <= 0 || index >= m_layers.size()) return false;
    KisImageSP image = m_document;
    if (!image) return false;
    KisLayer *layer = dynamic_cast<KisLayer *>(m_layers[index].node);
    if (!layer) return false;

    size = qBound(1, size, 100);
    opacity = qBound(0, opacity, 100);
    position = qBound(0, position, 2);

    KisPSDLayerStyleSP oldStyle = layer->layerStyle();
    KisPSDLayerStyleSP newStyle;
    if (oldStyle) {
        newStyle = toQShared(new KisPSDLayerStyle(*oldStyle));
    } else {
        newStyle = toQShared(new KisPSDLayerStyle());
        newStyle->setName(QStringLiteral("StrokeStyle"));
    }
    newStyle->setEnabled(true);
    newStyle->stroke()->setEffectEnabled(true);
    newStyle->stroke()->setSize(size);
    newStyle->stroke()->setColor(KoColor(QColor::fromRgba(color), image->colorSpace()));
    newStyle->stroke()->setPosition(static_cast<psd_stroke_position>(position));
    newStyle->stroke()->setOpacity(opacity);
    newStyle->stroke()->setBlendMode(COMPOSITE_OVER);

    pushUndoCommand(new KisSetLayerStyleCommand(KisLayerSP(layer), oldStyle, newStyle));
    recompositeProjection();
    syncLayersFromImage();
    markDirty();
    return true;
}

bool ReverieCore::getLayerStrokeParams(int index, int &size, quint32 &color, int &position, int &opacity) const
{
    if (index < 0 || index >= m_layers.size()) return false;
    const LayerEntry &e = m_layers[index];
    if (KisLayer *layer = dynamic_cast<KisLayer *>(e.node)) {
        KisPSDLayerStyleSP style = layer->layerStyle();
        if (style && style->stroke() && style->stroke()->effectEnabled()) {
            const psd_layer_effects_stroke *st = style->stroke();
            size = static_cast<int>(st->size());
            position = static_cast<int>(st->position());
            opacity = static_cast<int>(st->opacity());
            QColor qc = st->color().toQColor();
            color = qc.isValid() ? qc.rgba() : 0xFF000000;
            return true;
        }
    }
    if (e.isStrokeLayer || e.nodeType == NodeTypeStroke) {
        size = e.strokeSize;
        color = e.strokeColor;
        position = e.strokePosition;
        opacity = e.strokeOpacity;
        return true;
    }
    return false;
}

bool ReverieCore::rasterizeLayerStroke(int index)
{
    if (index <= 0 || index >= m_layers.size()) return false;
    KisImageSP image = m_document;
    if (!image) return false;
    KisLayer *layer = dynamic_cast<KisLayer *>(m_layers[index].node);
    if (!layer || !layer->layerStyle() || layer->layerStyle()->isEmpty()) return false;

    KisPaintDeviceSP dev = layer->paintDevice();
    if (!dev) return false;

    image->waitForDone();

    const quint8 origOpacity = layer->opacity();
    layer->setOpacity(255);

    KisPaintDeviceSP styledDev(new KisPaintDevice(image->colorSpace()));
    styledDev->clear();
    const QRect fullRect(0, 0, image->width(), image->height());
    layer->projectionPlane()->recalculate(fullRect, KisNodeSP(layer), KisRenderPassFlags());
    KisPainter p(styledDev);
    layer->projectionPlane()->apply(&p, fullRect);
    p.end();

    layer->setOpacity(origOpacity);

    beginUndoMacro(QStringLiteral("栅格化描边"));
    KisTransaction txn(kundo2_i18n("Rasterize Stroke"), dev);
    dev->clear();
    const QRect bounds = styledDev->exactBounds();
    if (!bounds.isEmpty()) {
        KisPainter::copyAreaOptimized(bounds.topLeft(), styledDev, dev, bounds);
    }
    dev->setDirty(bounds);
    txn.commit(image->undoAdapter());

    pushUndoCommand(new KisSetLayerStyleCommand(KisLayerSP(layer), layer->layerStyle(), nullptr));
    endUndoMacro();

    recompositeProjection();
    syncLayersFromImage();
    markDirty();
    return true;
}


