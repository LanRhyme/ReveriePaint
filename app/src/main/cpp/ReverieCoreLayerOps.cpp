/* ============================================================
 * ReverieCoreLayerOps.cpp - Layer operations: copy, merge, rasterize, flatten, flip, pass-through, solo, background
 * (part of the ReverieCore module split; shared helpers live in
 * ReverieCoreInternal.h, public API in ReverieCore.h)
 * ============================================================ */
#include "ReverieCoreInternal.h"

void ReverieCore::flipLayerHorizontal(int index)
{
    if (!isLayerEditable(index)) {
        return;
    }
    KisPaintDeviceSP dev = layerPaintDeviceFor(m_layers[index]);
    if (!dev) {
        return;
    }
    // Krita-native undo: wrap the pixel flip in a transaction
    KisTransaction txn(kundo2_i18n("FlipH"), dev);
    flipDevice(dev, true);
    markDirty();
    if (m_document) {
        txn.commit(m_document->undoAdapter());
        m_redoCount = 0;
    }
}

void ReverieCore::flipLayerVertical(int index)
{
    if (!isLayerEditable(index)) {
        return;
    }
    KisPaintDeviceSP dev = layerPaintDeviceFor(m_layers[index]);
    if (!dev) {
        return;
    }
    // Krita-native undo: wrap the pixel flip in a transaction
    KisTransaction txn(kundo2_i18n("FlipV"), dev);
    flipDevice(dev, false);
    markDirty();
    if (m_document) {
        txn.commit(m_document->undoAdapter());
        m_redoCount = 0;
    }
}

bool ReverieCore::mergeDown(int index)
{
    if (index <= 0 || index >= m_layers.size()) {
        return false;
    }
    LayerEntry &e = m_layers[index];
    if (e.isGroup || e.locked) {
        return false;
    }
    // Target: nearest paint layer below (groups cannot be bitBlt targets)
    int ti = index - 1;
    while (ti > 0 && m_layers[ti].isGroup) {
        --ti;
    }
    if (ti < 0 || m_layers[ti].isGroup || m_layers[ti].locked) {
        return false;
    }
    KisImageSP image = m_document;
    if (!image) {
        return false;
    }
    KisPaintDeviceSP src = layerPaintDeviceFor(e);
    KisPaintDeviceSP dst = layerPaintDeviceFor(m_layers[ti]);
    if (!src || !dst) {
        return false;
    }
    // Krita-native undo: the pixel merge is one transaction, the layer
    // removal is a remove command. Commit order matters: txn first, remove
    // second, so undo re-inserts the layer first, then restores dst pixels.
    KisTransaction txn(kundo2_i18n("Merge Down"), dst);
    const QRect ext = src->exactBounds();
    if (!ext.isEmpty()) {
        KisPainter painter(dst);
        painter.setOpacityF(qreal(e.node->opacity()) / 255.0);
        painter.setCompositeOpId(e.node->compositeOpId());
        painter.bitBlt(ext.x(), ext.y(), src, ext.x(), ext.y(), ext.width(), ext.height());
        dst->setDirty(ext);
    }
    KisNode *targetNode = m_layers[ti].node;
    txn.commit(image->undoAdapter());
    pushUndoCommand(new KisImageLayerRemoveCommand(image, KisNodeSP(e.node)));
    recompositeProjection();
    syncLayersFromImage();
    m_currentLayer = indexOfNode(targetNode);
    markDirty();
    return true;
}

bool ReverieCore::moveLayer(int fromIndex, int toIndex)
{
    if (fromIndex <= 0 || fromIndex >= m_layers.size()) {
        return false;  // background (index 0) is never moved
    }
    if (toIndex <= 0 || toIndex >= m_layers.size()) {
        return false;
    }
    if (fromIndex == toIndex) {
        return true;
    }
    const LayerEntry &src = m_layers[fromIndex];
    if (src.locked || src.background) {
        return false;
    }
    KisNodeSP node(src.node);
    const LayerEntry &dst = m_layers[toIndex];
    KisNodeSP parent(dst.node->parent() ? dst.node->parent() : m_document->root());
    // never move a group into its own subtree
    if (src.isGroup) {
        KisNodeSP p(dst.node->parent());
        while (p) {
            if (p == node) {
                return false;
            }
            p = p->parent();
        }
    }
    const quint32 index = parent->index(dst.node);
    if (!m_document->moveNode(node, parent, index)) {
        return false;
    }
    syncLayersFromImage();
    recompositeProjection();
    markDirty();
    return true;
}

bool ReverieCore::moveLayerAbove(int fromIndex, int aboveIndex)
{
    if (fromIndex <= 0 || fromIndex >= m_layers.size()) {
        return false;  // background (index 0) is never moved
    }
    if (aboveIndex < -1 || aboveIndex >= m_layers.size()) {
        return false;
    }
    if (aboveIndex >= 0 && fromIndex == aboveIndex) {
        return false;
    }
    const LayerEntry &src = m_layers[fromIndex];
    if (src.locked || src.background) {
        return false;
    }
    KisNodeSP node(src.node);
    KisNodeSP parent;
    KisNodeSP aboveNode;
    if (aboveIndex >= 0) {
        const LayerEntry &above = m_layers[aboveIndex];
        aboveNode = KisNodeSP(above.node);
        if (aboveNode == node) {
            return false;
        }
        parent = aboveNode->parent() ? aboveNode->parent() : m_document->root();
    } else {
        // aboveIndex == -1: move to the very top of the root.
        // moveNode's newIndex semantics are relative to the PRE-removal tree,
        // so it cannot express "land at index childCount"; remove + add to the
        // end instead (addNode(node, parent) appends at the visual top).
        parent = m_document->root();
        aboveNode = parent->lastChild();
        if (aboveNode == node) {
            return true;  // already at the top
        }
        if (src.isGroup) {
            KisNodeSP p = node->parent();
            while (p) {
                if (p == node) {
                    return false;
                }
                p = p->parent();
            }
        }
        // Krita-native undo: remove + re-add as two commands so undo
        // restores the original parent and slot exactly
        pushUndoCommand(new KisImageLayerRemoveCommand(m_document, node));
        pushUndoCommand(new KisImageLayerAddCommand(
            m_document, node, parent, KisNodeSP()));
        syncLayersFromImage();
        recompositeProjection();
        markDirty();
        return true;
    }
    if (parent == node) {
        return false;  // cannot move a group into its own subtree
    }
    // never move a group into its own subtree
    if (src.isGroup) {
        KisNodeSP p(parent);
        while (p) {
            if (p == node) {
                return false;
            }
            p = p->parent();
        }
    }
    // Krita-native undo: the move command redo() relocates the node
    pushUndoCommand(new KisImageLayerMoveCommand(
        m_document, node, parent, aboveNode));
    syncLayersFromImage();
    recompositeProjection();
    markDirty();
    return true;
}

bool ReverieCore::moveLayerToGroup(int fromIndex, int groupIndex)
{
    if (fromIndex <= 0 || fromIndex >= m_layers.size()) {
        return false;
    }
    if (groupIndex <= 0 || groupIndex >= m_layers.size()) {
        return false;
    }
    const LayerEntry &src = m_layers[fromIndex];
    const LayerEntry &grp = m_layers[groupIndex];
    if (src.locked || src.background || !grp.isGroup) {
        return false;
    }
    if (fromIndex == groupIndex) {
        return false;
    }
    KisNodeSP node(src.node);
    KisNodeSP group(grp.node);
    if (src.isGroup) {
        KisNodeSP p(group->parent());
        while (p) {
            if (p == node) {
                return false;
            }
            p = p->parent();
        }
    }
    pushUndoCommand(new KisImageLayerMoveCommand(
        m_document, node, group, group->childCount()));
    syncLayersFromImage();
    const int idx = indexOfNode(node.data());
    if (idx >= 0) m_currentLayer = idx;
    recompositeProjection();
    markDirty();
    return true;
}

bool ReverieCore::moveLayerRelative(int fromIndex, int targetIndex, bool placeAbove)
{
    if (fromIndex <= 0 || fromIndex >= m_layers.size()) return false;
    if (targetIndex < 0 || targetIndex >= m_layers.size()) return false;
    if (fromIndex == targetIndex) return false;

    const LayerEntry &src = m_layers[fromIndex];
    const LayerEntry &dst = m_layers[targetIndex];
    if (src.locked || src.background) return false;
    if (!src.node || !dst.node || !m_document) return false;

    KisNodeSP node(src.node);
    KisNodeSP target(dst.node);

    // Prevent moving a group into its own subtree
    if (src.isGroup) {
        KisNodeSP p(target);
        while (p) {
            if (p == node) return false;
            p = p->parent();
        }
    }

    KisNodeSP parent;
    quint32 newIndex = 0;

    if (dst.isGroup && !placeAbove) {
        // Drop into group at its very bottom (visual last child = lowest composition index 0)
        parent = target;
        newIndex = 0;
    } else {
        parent = target->parent() ? target->parent() : KisNodeSP(m_document->root());
        int targetIdxInParent = parent->index(target);
        if (targetIdxInParent < 0) targetIdxInParent = 0;

        if (placeAbove) {
            // Visual ABOVE target = Higher in composition stack
            newIndex = quint32(targetIdxInParent + 1);
        } else {
            // Visual BELOW target = Lower in composition stack (same slot, pushing target up)
            newIndex = quint32(targetIdxInParent);
        }
    }

    if (!parent) parent = m_document->root();

    pushUndoCommand(new KisImageLayerMoveCommand(m_document, node, parent, newIndex));
    syncLayersFromImage();
    const int idx = indexOfNode(node.data());
    if (idx >= 0) m_currentLayer = idx;
    recompositeProjection();
    markDirty();
    return true;
}

bool ReverieCore::moveLayerUp(int index)
{
    if (index <= 0 || index >= m_layers.size() - 1) return false;
    return moveLayer(index, index + 1);
}

bool ReverieCore::moveLayerDown(int index)
{
    if (index <= 1 || index >= m_layers.size()) return false;
    return moveLayer(index, index - 1);
}

bool ReverieCore::moveLayerOut(int index)
{
    if (index <= 0 || index >= m_layers.size()) return false;
    if (m_layers[index].depth <= 0) return false;
    KisImageSP image = m_document;
    if (!image) return false;
    KisNodeSP node(m_layers[index].node);
    if (!node) return false;

    KisNodeSP parent = node->parent();
    if (!parent) return false;

    pushUndoCommand(new KisImageLayerMoveCommand(image, node, image->rootLayer(), parent));
    syncLayersFromImage();
    const int idx = indexOfNode(node.data());
    if (idx >= 0) m_currentLayer = idx;
    recompositeProjection();
    markDirty();
    return true;
}

bool ReverieCore::addMaskToLayer(int layerIndex, int maskType)
{
    if (layerIndex <= 0 || layerIndex >= m_layers.size()) return false;
    KisImageSP image = m_document;
    if (!image) return false;
    KisNode *target = m_layers[layerIndex].node;
    if (!target) return false;

    KisMaskSP mask;
    const QString maskName = QString("蒙版 %1").arg(m_layers[layerIndex].name);

    if (maskType == MaskTypeTransparency) {
        KisTransparencyMaskSP tmask = new KisTransparencyMask(image, maskName);
        if (KisLayer *layer = dynamic_cast<KisLayer *>(target)) {
            tmask->initSelection(layer);
        }
        mask = tmask;
    } else if (maskType == MaskTypeFilter) {
        KisFilterMaskSP fmask = new KisFilterMask(image, maskName);
        if (KisLayer *layer = dynamic_cast<KisLayer *>(target)) {
            fmask->initSelection(layer);
        }
        mask = fmask;
    } else if (maskType == MaskTypeTransform) {
        KisTransformMaskSP txmask = new KisTransformMask(image, maskName);
        mask = txmask;
    } else if (maskType == MaskTypeSelection) {
        KisSelectionMaskSP smask = new KisSelectionMask(image);
        smask->setName(maskName);
        if (m_selection) {
            smask->initSelection(m_selection, dynamic_cast<KisLayer *>(target));
        }
        mask = smask;
    }

    if (mask) {
        pushUndoCommand(new KisImageLayerAddCommand(image, mask, KisNodeSP(target), KisNodeSP()));
        recompositeProjection();
        syncLayersFromImage();
        markDirty();
        return true;
    }
    return false;
}

bool ReverieCore::removeMask(int layerIndex)
{
    if (layerIndex <= 0 || layerIndex >= m_layers.size()) return false;
    KisImageSP image = m_document;
    if (!image) return false;
    KisNode *target = m_layers[layerIndex].node;
    if (!target) return false;

    KisNodeSP child = target->firstChild();
    while (child) {
        if (dynamic_cast<KisMask *>(child.data())) {
            pushUndoCommand(new KisImageLayerRemoveCommand(image, child));
            recompositeProjection();
            syncLayersFromImage();
            markDirty();
            return true;
        }
        child = child->nextSibling();
    }
    return false;
}

bool ReverieCore::rasterizeLayer(int index)
{
    if (index <= 0 || index >= m_layers.size()) return false;
    KisImageSP image = m_document;
    if (!image) return false;
    KisNodeSP node(m_layers[index].node);
    if (!node) return false;

    KisPaintLayerSP paintLayer = new KisPaintLayer(image, m_layers[index].name + QStringLiteral(" (栅格化)"), m_layers[index].node->opacity(), image->colorSpace());
    if (KisPaintDeviceSP dev = layerPaintDeviceFor(m_layers[index])) {
        KisPainter::copyAreaOptimized(QPoint(0, 0), dev, paintLayer->paintDevice(), dev->exactBounds());
        paintLayer->paintDevice()->setDirty();
    }
    paintLayer->setCompositeOpId(m_layers[index].node->compositeOpId());

    KisNodeSP parent = node->parent();
    KisNodeSP above = node->prevSibling();

    pushUndoCommand(new KisImageLayerRemoveCommand(image, node));
    pushUndoCommand(new KisImageLayerAddCommand(image, paintLayer, parent, above));
    recompositeProjection();
    syncLayersFromImage();
    const int idx = indexOfNode(paintLayer.data());
    if (idx >= 0) m_currentLayer = idx;
    markDirty();
    return true;
}

bool ReverieCore::flattenGroup(int index)
{
    if (index <= 0 || index >= m_layers.size()) return false;
    if (!m_layers[index].isGroup) return false;
    return rasterizeLayer(index);
}

bool ReverieCore::setGroupPassThrough(int index, bool passThrough)
{
    if (index <= 0 || index >= m_layers.size()) return false;
    if (KisGroupLayer *grp = dynamic_cast<KisGroupLayer *>(m_layers[index].node)) {
        grp->setPassThroughMode(passThrough);
        recompositeProjection();
        markDirty();
        return true;
    }
    return false;
}

bool ReverieCore::groupPassThrough(int index) const
{
    if (index <= 0 || index >= m_layers.size()) return false;
    if (KisGroupLayer *grp = dynamic_cast<KisGroupLayer *>(m_layers[index].node)) {
        return grp->passThroughMode();
    }
    return false;
}

// ---- Solo mode (render-filter only) ----
// Closing solo / switching solo targets never touches any layer state: the
// document is exactly as it was, so there is nothing to restore
void ReverieCore::restoreSolo()
{
    m_soloedNode = nullptr;
    m_soloKeepNodes.clear();
    m_soloRawMode = false;
    markDirty();
}

bool ReverieCore::soloRawMode() const
{
    return m_soloRawMode;
}

// Switch the soloed layer between its original look (常规) and the pure-color
// raw mode (取消所有效果): rendered with 100% opacity + Normal blend at
// composite time - the layer itself is never modified
void ReverieCore::toggleSoloRawMode()
{
    if (m_soloedNode) {
        m_soloRawMode = !m_soloRawMode;
        // Force a full-frame recomposite (the raw switch affects every pixel
        // of the soloed layer, not just the current dirty region)
        markDirty();
    }
}

int ReverieCore::soloedIndex() const
{
    if (!m_soloedNode) {
        return -1;
    }
    for (int i = 0; i < m_layers.size(); ++i) {
        if (m_layers[i].node == m_soloedNode) {
            return i;
        }
    }
    return -1;
}

// Rebuild the keep set (soloed layer + ancestor groups + descendants +
// background) from the current m_layers, keyed by node so layer ops that
// rebuild m_layers cannot invalidate it
void ReverieCore::computeSoloKeep()
{
    m_soloKeepNodes.clear();
    const int idx = soloedIndex();
    if (idx < 0) {
        return;
    }
    const int td = m_layers[idx].depth;
    m_soloKeepNodes.append(m_layers[idx].node);
    // Keep the background (index 0) visible: on mobile the white canvas is the
    // background layer, so hiding it turns the canvas into a transparent
    // checkerboard which reads as a broken render
    if (!m_layers.isEmpty() && m_layers[0].background) {
        m_soloKeepNodes.append(m_layers[0].node);
    }
    // Ancestors: nearest preceding entries with strictly decreasing depth
    int curDepth = td;
    for (int i = idx - 1; i >= 0 && curDepth > 0; --i) {
        if (m_layers[i].depth < curDepth) {
            m_soloKeepNodes.append(m_layers[i].node);
            curDepth = m_layers[i].depth;
        }
    }
    // Descendants: contiguous following entries with depth > td
    for (int i = idx + 1; i < m_layers.size() && m_layers[i].depth > td; ++i) {
        m_soloKeepNodes.append(m_layers[i].node);
    }
}

// Keep set as layer indices (used by the layer panel to gray out the rows
// that solo mode hides at render time)
QVector<int> ReverieCore::soloKeepIndices() const
{
    QVector<int> out;
    for (KisNode *n : m_soloKeepNodes) {
        for (int i = 0; i < m_layers.size(); ++i) {
            if (m_layers[i].node == n) {
                out.append(i);
                break;
            }
        }
    }
    return out;
}

void ReverieCore::soloLayer(int index)
{
    if (index < 0 || index >= m_layers.size()) {
        return;
    }
    if (m_layers[index].node == m_soloedNode) {
        // Tapping the soloed layer again closes solo mode
        restoreSolo();
        return;
    }
    // Solo another layer while one is active: switch the target
    restoreSolo();
    m_soloedNode = m_layers[index].node;
    m_soloRawMode = false;   // 默认常规：不改变目标层的效果
    computeSoloKeep();
    markDirty();
}

bool ReverieCore::layerSoloed(int index) const
{
    return index >= 0 && index < m_layers.size() && m_layers[index].node == m_soloedNode;
}

bool ReverieCore::soloActive() const
{
    return m_soloedNode != nullptr;
}

