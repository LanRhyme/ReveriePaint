/* ============================================================
 * ReverieCoreLayers.cpp - Layer list management: add/remove/reorder, visibility, blend modes, groups, masks, locking
 * (part of the ReverieCore module split; shared helpers live in
 * ReverieCoreInternal.h, public API in ReverieCore.h)
 * ============================================================ */
#include "ReverieCoreInternal.h"

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
    const LayerEntry &e = m_layers[index];
    return !(e.isGroup || e.background || e.locked);
}

KisPaintDeviceSP ReverieCore::layerPaintDeviceFor(const LayerEntry &e) const
{
    if (KisAdjustmentLayer *al = dynamic_cast<KisAdjustmentLayer *>(e.node)) {
        return al->original();
    } else if (KisPaintLayer *pl = dynamic_cast<KisPaintLayer *>(e.node)) {
        return pl->paintDevice();
    } else if (KisMask *m = dynamic_cast<KisMask *>(e.node)) {
        return m->paintDevice();
    } else if (KisLayer *l = dynamic_cast<KisLayer *>(e.node)) {
        return l->original() ? l->original() : (l->paintDevice() ? l->paintDevice() : l->projection());
    }
    return KisPaintDeviceSP();
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
static void currentInsertPosition(const QVector<ReverieCore::LayerEntry> &layers, int current, KisNodeSP &above, KisNodeSP &parent, KisImageSP image)
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
            return;
        }
    }
    above = KisNodeSP();
    parent = KisNodeSP(image->rootLayer());
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
    const QString layerName = name.isEmpty() ? defaultPaintLayerName(m_layers) : name;
    KisPaintLayerSP newLayer = new KisPaintLayer(image, layerName, 255, cs);
    if (!newLayer) {
        return -1;
    }

    // Insert directly above the current layer (inside its group if any)
    KisNodeSP above;
    KisNodeSP parent;
    currentInsertPosition(m_layers, m_currentLayer, above, parent, image);
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
    const QString groupName = name.isEmpty() ? QStringLiteral("图层组") : name;
    KisGroupLayerSP group = new KisGroupLayer(image, groupName, 255, image->colorSpace());
    if (!group) {
        return -1;
    }
    KisNodeSP above;
    KisNodeSP parent;
    currentInsertPosition(m_layers, m_currentLayer, above, parent, image);
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
        default: finalName = QString("图层 %1").arg(count); break;
        }
    }

    KisNodeSP above;
    KisNodeSP parent;
    currentInsertPosition(m_layers, m_currentLayer, above, parent, image);

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
    } else {
        const KoColorSpace *cs = image->colorSpace();
        KisPaintLayerSP paintLayer = new KisPaintLayer(image, finalName, 255, cs);
        if (type == LayerTypeFill) {
            QColor qc = QColor::fromRgba(fillColor);
            paintLayer->original()->fill(QRect(0, 0, image->width(), image->height()), KoColor(qc, cs));
            paintLayer->original()->setDirty();
            newNode = paintLayer;
        } else if (type == LayerTypeAdjustment) {
            newNode = paintLayer;
            m_nodeFilters[newNode.data()] = { true, 2, 10.0, 0, 0, 0, QByteArray() };
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

    KisNodeSP cloned = src.node->clone();
    if (!cloned) return -1;
    cloned->setName(src.name + QStringLiteral(" 副本"));

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
    m_nodeFilters.remove(m_layers[index].node);
    pushUndoCommand(new KisImageLayerRemoveCommand(image, KisNodeSP(m_layers[index].node)));
    syncLayersFromImage();
    recompositeProjection();
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
            m_document->setDefaultProjectionColor(visible ? KoColor(Qt::white, cs) : KoColor(Qt::transparent, cs));
        }
        m_layers[index].node->setDirty(
            QRect(0, 0, m_document->width(), m_document->height()));
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
    m_layers[index].node->setDirty(QRect(0, 0, m_document->width(), m_document->height()));
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
        m_layers[index].node->setDirty(QRect(0, 0, m_document->width(), m_document->height()));
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
        m_layers[index].node->setDirty(
            QRect(0, 0, m_document->width(), m_document->height()));
        markDirty();
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


