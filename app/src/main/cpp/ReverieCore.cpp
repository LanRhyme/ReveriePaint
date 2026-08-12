/*
 * ReverieCore - painting engine implementation
 *
 * Reuses Krita's KisDocument/KisImage/KisPaintLayer/KisPainter exactly
 * like the original CanvasWidget, but without any QWidget dependency.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

#include "ReverieCore.h"

#include <QDir>
#include <QFile>
#include <QBuffer>

#include <brushengine/kis_paintop_preset.h>
#include <brushengine/kis_paintop_registry.h>
#include <brushengine/kis_paint_information.h>
#include <kis_distance_information.h>
#include <KisLocalStrokeResources.h>
#include <KisFakeRunnableStrokeJobsExecutor.h>
#include <KisRunnableStrokeJobData.h>
#include <kis_brush_based_paintop_settings.h>
#include <kis_brushop.h>

#include <QDebug>
#include <algorithm>
#include <QPainter>
#include <QFont>
#include <QFontMetrics>
#include <QLineF>

#include <kis_image.h>
#include <kis_undo_store.h>
#include <kis_painter.h>
#include <KoColorSpaceRegistry.h>
#include <KoColorSpace.h>
#include <kis_paint_device.h>
#include <kis_refresh_subtree_walker.h>
#include <kis_async_merger.h>
#include <kis_group_layer.h>
#include <kis_selection.h>
#include <kis_pixel_selection.h>
#include <kis_default_bounds.h>
#include <KisImageResolutionProxy.h>
#include <kis_node_facade.h>
#include <kis_layer.h>
#include <kis_base_node.h>
#include <KoCompositeOpRegistry.h>
#include <kis_paint_layer.h>
#include <kis_group_layer.h>
#include <kis_layer.h>
#include <kis_node.h>

ReverieCore::ReverieCore()
{
}

ReverieCore::~ReverieCore()
{
    endStrokeBatch();
    m_layers.clear();
    m_document.clear(); // KisImageSP releases the image
}

bool ReverieCore::newDocument(int width, int height)
{
    if (width <= 0 || height <= 0) {
        return false;
    }

    // Release any previous document. m_document is a KisSharedPtr: calling
    // delete on the raw pointer would corrupt its refcount (double-free on
    // destruction), so let the shared pointer release it instead.
    m_document.clear();
    // Reset the display pipeline: a new document (possibly same size as the
    // previous one) must not inherit stale display pixels or skip the first
    // full bitmap copy.
    m_displayImage = QImage();
    m_dirtyRect = QRect();
    m_bitmapInited = false;
    m_lastDirty = QRect();
    // Reset ALL per-document engine state. g_core is a process-lifetime
    // singleton, so when the Activity is recreated on top of a live process
    // (Android task restore) a new document must not inherit the previous
    // document's stroke buffer / painter / undo snapshots - that left stale
    // devices bound and made painting fail on the second open.
    endStrokeBatch();               // delete m_strokePainter
    m_strokeDevice = nullptr;
    m_strokeBuffer = nullptr;       // rebuilt lazily on next stroke
    m_strokeSamples.clear();
    m_strokeHadMove = false;
    m_strokeBatchOpen = false;
    m_drawing = false;
    m_snapshotPending = false;
    m_strokeStartImg = QPointF();
    m_lastPressure = 1.0;
    m_strokeColor = QColor();
    m_strokeOpacity = 1.0;
    m_undoStack.clear();
    m_redoStack.clear();

    const KoColorSpace *cs = KoColorSpaceRegistry::instance()->rgb8();
    if (!cs) {
        return false;
    }

    // Create a standalone Krita image without KisDocument/KisPart (which
    // live in kritaui and need a full QApplication). KisImage's public
    // ctor is sufficient for a single-document painting engine.
    KisImageSP image = new KisImage(nullptr /* undoStore */, width, height, cs,
                                    QStringLiteral("Untitled"));
    if (!image) {
        return false;
    }
    image->setResolution(72.0, 72.0);

    // Background layer (white, opaque, locked): index 0, cannot be painted
    // on, deleted, renamed or moved. Hiding it reveals the transparent grid.
    KisPaintLayerSP bg = new KisPaintLayer(image, QStringLiteral("背景"), 255, cs);
    if (!bg) {
        return false;
    }
    KoColor white(QColor(Qt::white), cs);
    bg->original()->fill(QRect(0, 0, width, height), white);
    bg->original()->setDirty();
    bg->setUserLocked(true);
    bg->setAlphaLocked(true);
    image->addNode(bg, image->rootLayer());

    // First paint layer above the background (Krita-style 颜料图层)
    KisPaintLayerSP paint = new KisPaintLayer(image, QStringLiteral("颜料图层 1"), 255, cs);
    if (!paint) {
        m_document = image.data();
        m_docWidth = width;
        m_docHeight = height;
        syncLayersFromImage();
        markDirty();
        return true;
    }
    paint->original()->fill(QRect(0, 0, width, height), KoColor(Qt::transparent, cs));
    paint->original()->setDirty();
    image->addNode(paint, image->rootLayer());

    m_document = image.data();
    m_docWidth = width;
    m_docHeight = height;
    // Must run AFTER m_document is set (recompositeProjection reads it)
    recompositeProjection();
    syncLayersFromImage();
    m_currentLayer = 1;
    markDirty();
    return true;
}

void ReverieCore::fillBackground(const QString &colorName)
{
    KisImageSP image = m_document;
    if (!image || m_layers.isEmpty()) {
        return;
    }
    const KoColorSpace *cs = image->colorSpace();
    QColor c(colorName);
    if (!c.isValid()) {
        c = Qt::white;
    }
    KoColor koColor(c, cs);
    // Fill the topmost paintable layer (never the locked background)
    KisPaintDeviceSP dev;
    for (int i = m_layers.size() - 1; i >= 0; --i) {
        if (!m_layers[i].isGroup && !m_layers[i].background && !m_layers[i].locked) {
            dev = layerPaintDeviceFor(m_layers[i]);
            break;
        }
    }
    if (!dev) {
        return;
    }
    dev->fill(QRect(0, 0, image->width(), image->height()), koColor);
    dev->setDirty();
    markDirty();
}

void ReverieCore::clearCanvas()
{
    fillBackground(QStringLiteral("#ffffff"));
}

void ReverieCore::setBrushColorName(const QString &colorName)
{
    QColor c(colorName);
    if (c.isValid()) {
        m_brushColor = c;
    }
}

// ---------------------------------------------------------------------------
// Layer system
// ---------------------------------------------------------------------------

// Force a full synchronous recomposite of the root projection.
//
// Krita recomputes its projection only for dirty regions propagated through
// the node graph. Node-structure changes (add/remove layer, visibility, blend
// mode) rebuild the root projection device as empty without marking it dirty,
// so convertToQImage would read transparent black afterwards. This is the
// same refresh-walker + async-merger pair Krita uses internally to regenerate
// a projection synchronously.
// Structural changes (KisImage::addNode) do NOT schedule a native projection
// recomposite: KisImage::nodeHasBeenAdded only bumps a sequence number, and
// requestProjectionUpdate(root,...) does not rebuild the root projection for
// a new child. A full walker+merger pass is required after addNode. Content
// changes (device setDirty -> requestProjectionUpdate -> waitForDone) are
// handled by the native scheduler and must NOT go through this path.
void ReverieCore::recompositeProjection()
{
    KisImageSP image = m_document;
    if (!image) {
        return;
    }
    const QRect full(0, 0, image->width(), image->height());
    KisRefreshSubtreeWalker walker(full);
    walker.collectRects(image->rootLayer(), full);
    KisAsyncMerger merger;
    merger.startMerge(walker);
}

void ReverieCore::syncLayersFromImage()
{
    m_layers.clear();
    KisImageSP image = m_document;
    if (!image) {
        return;
    }
    KisNodeSP root = image->rootLayer();
    if (!root) {
        return;
    }
    std::function<void(KisNodeSP, int)> walk = [&](KisNodeSP parent, int depth) {
        KisNodeSP node = parent->firstChild();
        while (node) {
            const bool isGroup = dynamic_cast<KisGroupLayer *>(node.data()) != nullptr;
            if (KisPaintLayer *pl = dynamic_cast<KisPaintLayer *>(node.data())) {
                LayerEntry entry;
                entry.node = node.data();
                entry.visible = pl->visible();
                entry.name = pl->name();
                entry.depth = depth;
                entry.isGroup = false;
                entry.locked = pl->userLocked();
                entry.alphaLocked = pl->alphaLocked();
                entry.colorLabel = pl->colorLabelIndex();
                entry.clipped = false;  // our own flag, not a Krita property
                entry.background = m_layers.isEmpty();  // first paint layer = bg
                m_layers.append(entry);
            } else if (isGroup) {
                // Group layers participate in the layer list (depth, name,
                // visibility) so the UI can nest and the index space stays
                // a full tree traversal
                LayerEntry entry;
                entry.node = node.data();
                entry.visible = node->visible();
                entry.name = node->name();
                entry.depth = depth;
                entry.isGroup = true;
                entry.locked = node->userLocked();
                entry.colorLabel = node->colorLabelIndex();
                m_layers.append(entry);
            }
            if (node->childCount() > 0) {
                walk(node, depth + 1);
            }
            node = node->nextSibling();
        }
    };
    walk(root, 0);
    if (!m_layers.isEmpty()) {
        m_layers[0].background = true;
        // Background is always fully opaque + alpha-locked
        m_layers[0].locked = true;
        m_layers[0].alphaLocked = true;
        m_layers[0].clipped = false;
    }
    if (m_currentLayer >= m_layers.size()) {
        m_currentLayer = m_layers.isEmpty() ? 0 : m_layers.size() - 1;
    }
    if (m_soloedLayer >= m_layers.size()) {
        m_soloedLayer = -1;
    }
}

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
    KisPaintLayer *pl = e.isGroup ? nullptr : dynamic_cast<KisPaintLayer *>(e.node);
    return pl ? pl->paintDevice() : KisPaintDeviceSP();
}


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
    const KoColorSpace *cs = image->colorSpace();
    const QString layerName = name.isEmpty() ? defaultPaintLayerName(m_layers) : name;
    KisPaintLayerSP newLayer = new KisPaintLayer(image, layerName, 255, cs);
    if (!newLayer) {
        return -1;
    }
    newLayer->original()->fill(QRect(0, 0, image->width(), image->height()),
                               KoColor(Qt::transparent, cs));
    newLayer->original()->setDirty();

    // Insert directly above the current layer (inside its group if any)
    KisNodeSP above;
    KisNodeSP parent;
    currentInsertPosition(m_layers, m_currentLayer, above, parent, image);
    if (!image->addNode(newLayer, parent, above)) {
        return -1;
    }
    recompositeProjection();
    syncLayersFromImage();
    m_currentLayer = indexOfNode(newLayer.data());
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
    if (!image->addNode(group, parent, above)) {
        return -1;
    }
    recompositeProjection();
    syncLayersFromImage();
    m_currentLayer = indexOfNode(group.data());
    markDirty();
    return m_currentLayer;
}

int ReverieCore::copyLayer(int index)
{
    if (index <= 0 || index >= m_layers.size()) {
        return -1;  // background cannot be copied
    }
    LayerEntry &src = m_layers[index];
    KisPaintLayer *sl = src.isGroup ? nullptr : dynamic_cast<KisPaintLayer *>(src.node);
    if (!sl) {
        return -1;  // groups are not copied in the MVP
    }
    KisImageSP image = m_document;
    if (!image) {
        return -1;
    }
    KisPaintLayerSP nl = new KisPaintLayer(image, src.name + QStringLiteral(" 副本"), 255, image->colorSpace());
    if (!nl) {
        return -1;
    }
    // Copy pixels: reuse Krita's clone helper over the content bounds
    const QRect ext = sl->paintDevice()->extent();
    if (!ext.isEmpty()) {
        nl->original()->makeCloneFrom(sl->paintDevice(), ext);
        nl->original()->setDirty(ext);
    }
    nl->setOpacity(sl->opacity());
    nl->setCompositeOpId(sl->compositeOpId());
    nl->setVisible(src.visible);
    nl->setAlphaLocked(src.alphaLocked);
    nl->setUserLocked(src.locked);
    nl->setColorLabelIndex(src.colorLabel);

    KisNodeSP above = KisNodeSP(src.node);
    KisNodeSP parent = above ? above->parent() : KisNodeSP(image->rootLayer());
    if (!image->addNode(nl, parent, above)) {
        return -1;
    }
    recompositeProjection();
    syncLayersFromImage();
    m_currentLayer = indexOfNode(nl.data());
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
    image->removeNode(KisNodeSP(m_layers[index].node));
    recompositeProjection();
    syncLayersFromImage();
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
    dev->clear();
    dev->setDirty();
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
        m_layers[index].node->setName(name);
        m_layers[index].name = name;
    }
}

void ReverieCore::setLayerVisible(int index, bool visible)
{
    if (index < 0 || index >= m_layers.size()) {
        return;
    }
    if (m_layers[index].visible != visible) {
        m_layers[index].visible = visible;
        if (m_layers[index].node) {
            m_layers[index].node->setVisible(visible);
            // Visibility is a structural change: KisNode::setVisible only
            // notifies the graph listener, it does not schedule a projection
            // recomposite. setDirty() -> requestProjectionUpdate does.
            m_layers[index].node->setDirty(
                QRect(0, 0, m_document->width(), m_document->height()));
        }
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
    m_layers[index].node->setOpacity(o);
    m_layers[index].node->setDirty(QRect(0, 0, m_document->width(), m_document->height()));
    markDirty();
}

void ReverieCore::setLayerBlendMode(int index, const QString &opId)
{
    if (index <= 0 || index >= m_layers.size()) {
        return;  // background is always 'normal'
    }
    if (m_layers[index].node) {
        m_layers[index].node->setCompositeOpId(opId);
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
    return m_layers[index].clipped;
}

void ReverieCore::setLayerClipped(int index, bool clipped)
{
    if (index <= 0 || index >= m_layers.size()) {
        return;
    }
    m_layers[index].clipped = clipped;
    markDirty();
}

static void flipDevice(KisPaintDeviceSP dev, bool horizontal)
{
    // exactBounds = actual content bounds (extent() includes the 256px
    // default bounds with garbage beyond the document edge)
    const QRect ext = dev->exactBounds();
    if (ext.isEmpty()) {
        return;
    }
    // Krita readBytes returns device-native RGBA bytes; RGBA8888 matches
    // that byte order exactly (ARGB32_Premultiplied would swap R/B)
    QImage img(ext.size(), QImage::Format_RGBA8888);
    dev->readBytes(img.bits(), ext.x(), ext.y(), ext.width(), ext.height());
    img = horizontal ? img.mirrored(true, false) : img.mirrored(false, true);
    dev->writeBytes(img.constBits(), ext.x(), ext.y(), ext.width(), ext.height());
    dev->setDirty(ext);
}

void ReverieCore::flipLayerHorizontal(int index)
{
    if (!isLayerEditable(index)) {
        return;
    }
    KisPaintDeviceSP dev = layerPaintDeviceFor(m_layers[index]);
    if (!dev) {
        return;
    }
    flipDevice(dev, true);
    markDirty();
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
    flipDevice(dev, false);
    markDirty();
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
    if (ti < 0 || m_layers[ti].isGroup || m_layers[ti].locked || m_layers[ti].background) {
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
    const QRect ext = src->exactBounds();
    if (!ext.isEmpty()) {
        KisPainter painter(dst);
        painter.setOpacityF(qreal(e.node->opacity()) / 255.0);
        painter.setCompositeOpId(e.node->compositeOpId());
        painter.bitBlt(ext.x(), ext.y(), src, ext.x(), ext.y(), ext.width(), ext.height());
        dst->setDirty(ext);
    }
    KisNode *targetNode = m_layers[ti].node;
    image->removeNode(KisNodeSP(e.node));
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
        KisNodeSP oldParent = node->parent();
        int oldIndex = oldParent ? oldParent->index(node) : -1;
        if (!m_document->removeNode(node)) {
            return false;
        }
        if (!m_document->addNode(node, parent)) {
            Q_UNUSED(oldParent);
            Q_UNUSED(oldIndex);
            return false;
        }
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
    if (!m_document->moveNode(node, parent, aboveNode)) {
        return false;
    }
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
    // never move a group into its own subtree
    if (src.isGroup) {
        KisNodeSP p(group->parent());
        while (p) {
            if (p == node) {
                return false;
            }
            p = p->parent();
        }
    }
    if (!m_document->moveNode(node, group, 0)) {
        return false;
    }
    syncLayersFromImage();
    recompositeProjection();
    markDirty();
    return true;
}

void ReverieCore::soloLayer(int index)
{
    if (index < 0 || index >= m_layers.size()) {
        return;
    }
    if (index == m_soloedLayer) {
        // Restore the pre-solo visibility (FolioLayers behavior)
        for (int i = 0; i < m_layers.size(); ++i) {
            if (i < m_layers[i].soloPrev.size()) {
                setLayerVisible(i, m_layers[i].soloPrev[i]);
            }
        }
        m_soloedLayer = -1;
    } else {
        // Record the current visibility of every layer, then show only this
        // one (the background is hidden too, revealing the transparent grid)
        for (LayerEntry &e : m_layers) {
            e.soloPrev.clear();
            e.soloPrev.append(e.visible);
        }
        m_soloedLayer = index;
        for (int i = 0; i < m_layers.size(); ++i) {
            if (i != index) {
                setLayerVisible(i, false);
            }
        }
    }
}

bool ReverieCore::layerSoloed(int index) const
{
    return index >= 0 && index == m_soloedLayer;
}

void ReverieCore::applyFilter(int index, int filterId)
{
    if (!isLayerEditable(index)) {
        return;
    }
    KisPaintDeviceSP dev = layerPaintDeviceFor(m_layers[index]);
    if (!dev) {
        return;
    }
    const QRect ext = dev->exactBounds();
    if (ext.isEmpty()) {
        return;
    }
    QImage img(ext.size(), QImage::Format_RGBA8888);
    dev->readBytes(img.bits(), ext.x(), ext.y(), ext.width(), ext.height());
    switch (filterId) {
    case 0: {  // grayscale (RGBA8888 byte order: R,G,B,A)
        for (int y = 0; y < img.height(); ++y) {
            quint8 *line = img.scanLine(y);
            for (int x = 0; x < img.width(); ++x) {
                quint8 *px = line + x * 4;
                const int gray = (px[0] * 299 + px[1] * 587 + px[2] * 114) / 1000;
                px[0] = quint8(gray); px[1] = quint8(gray); px[2] = quint8(gray);
            }
        }
        break;
    }
    case 1: {  // invert (keep alpha)
        for (int y = 0; y < img.height(); ++y) {
            quint8 *line = img.scanLine(y);
            for (int x = 0; x < img.width(); ++x) {
                quint8 *px = line + x * 4;
                px[0] = 255 - px[0]; px[1] = 255 - px[1]; px[2] = 255 - px[2];
            }
        }
        break;
    }
    case 2: {  // box blur 3x3, two passes
        QImage tmp = img;
        const int w = img.width(), h = img.height();
        for (int pass = 0; pass < 2; ++pass) {
            for (int y = 0; y < h; ++y) {
                quint8 *d = img.scanLine(y);
                for (int x = 0; x < w; ++x) {
                    int r = 0, g = 0, b = 0, a = 0, nn = 0;
                    for (int dy = -1; dy <= 1; ++dy) {
                        for (int dx = -1; dx <= 1; ++dx) {
                            const int yy = qBound(0, y + dy, h - 1);
                            const int xx = qBound(0, x + dx, w - 1);
                            const quint8 *p = tmp.constScanLine(yy) + xx * 4;
                            r += p[0]; g += p[1]; b += p[2]; a += p[3];
                            ++nn;
                        }
                    }
                    quint8 *px = d + x * 4;
                    px[0] = quint8(r / nn); px[1] = quint8(g / nn);
                    px[2] = quint8(b / nn); px[3] = quint8(a / nn);
                }
            }
            tmp = img;
        }
        break;
    }
    case 3: {  // sharpen 3x3
        QImage tmp = img;
        const int w = img.width(), h = img.height();
        for (int y = 0; y < h; ++y) {
            quint8 *d = img.scanLine(y);
            for (int x = 0; x < w; ++x) {
                int r = 0, g = 0, b = 0, a = 0;
                for (int dy = -1; dy <= 1; ++dy) {
                    for (int dx = -1; dx <= 1; ++dx) {
                        const int yy = qBound(0, y + dy, h - 1);
                        const int xx = qBound(0, x + dx, w - 1);
                        const quint8 *p = tmp.constScanLine(yy) + xx * 4;
                        const int k = (dx == 0 && dy == 0) ? 9 : -1;
                        r += k * p[0]; g += k * p[1]; b += k * p[2]; a += k * p[3];
                    }
                }
                quint8 *px = d + x * 4;
                px[0] = quint8(qBound(0, r, 255)); px[1] = quint8(qBound(0, g, 255));
                px[2] = quint8(qBound(0, b, 255)); px[3] = quint8(qBound(0, a, 255));
            }
        }
        break;
    }
    default:
        return;
    }
    dev->writeBytes(img.constBits(), ext.x(), ext.y(), ext.width(), ext.height());
    dev->setDirty(ext);
    markDirty();
}

bool ReverieCore::selectionFromLayer(int index)
{
    if (index < 0 || index >= m_layers.size() || m_layers[index].isGroup) {
        return false;
    }
    KisImageSP image = m_document;
    if (!image) {
        return false;
    }
    KisPaintDeviceSP dev = layerPaintDeviceFor(m_layers[index]);
    if (!dev) {
        return false;
    }
    KisSelectionSP sel = new KisSelection(
        new KisSelectionDefaultBounds(image->projection()),
        toQShared(new KisImageResolutionProxy(image)));
    KisPixelSelectionSP ps = sel->pixelSelection();
    // Copy the layer's alpha channel into the selection (Krita mechanism:
    // KisPixelSelection::copyAlphaFrom)
    ps->copyAlphaFrom(dev, dev->extent());
    m_selection = sel;
    markDirty();
    return true;
}

bool ReverieCore::hasSelection() const
{
    return bool(m_selection);
}

void ReverieCore::clearSelection()
{
    m_selection = nullptr;
    markDirty();
}

// ---------------------------------------------------------------------------
// Painting
// ---------------------------------------------------------------------------

KisPaintDeviceSP ReverieCore::currentPaintDevice()
{
    KisImageSP image = m_document;
    if (!image || m_layers.isEmpty()) {
        return KisPaintDeviceSP();
    }
    const int idx = qBound(0, m_currentLayer, m_layers.size() - 1);
    LayerEntry &entry = m_layers[idx];
    // Background and locked layers are never paintable
    if (entry.isGroup || entry.background || entry.locked) {
        return KisPaintDeviceSP();
    }
    return layerPaintDeviceFor(entry);
}

// ---------------------------------------------------------------------------
// Krita brush engine
// ---------------------------------------------------------------------------

extern "C" void krita_register_default_paintops();

void ReverieCore::registerPaintOps()
{
    static bool done = false;
    if (!done) {
        // Implemented inside the cross-compiled kritadefaultpaintops_static
        // library so the KisSimplePaintOpFactory vtable layout matches
        // libkritaimage's view (instantiating the template in this module
        // produced vtable misalignment and crashes).
        krita_register_default_paintops();
        done = true;
    }
}

int ReverieCore::loadBrushPresetsFromDir(const QString &dirPath)
{
    registerPaintOps();
    QDir dir(dirPath);
    const QStringList kpps = dir.entryList(QStringList() << QStringLiteral("*.kpp"),
                                           QDir::Files, QDir::Name);
    m_presets.clear();
    for (const QString &f : kpps) {
        QString name = f;
        name.chop(4);  // strip ".kpp"
        m_presets.append(qMakePair(name, dir.filePath(f)));
    }
    return m_presets.size();
}

bool ReverieCore::loadBrushPreset(int index)
{
    if (index < 0 || index >= m_presets.size()) {
        return false;
    }
    registerPaintOps();
    const QString path = m_presets[index].second;
    QFile f(path);
    if (!f.open(QIODevice::ReadOnly)) {
        return false;
    }
    KisResourcesInterfaceSP ri(new KisLocalStrokeResources());
    KisPaintOpPresetSP preset(new KisPaintOpPreset(m_presets[index].first));
    const bool ok = preset->loadFromDevice(&f, ri);
    f.close();
    if (!ok) {
        return false;
    }
    m_brushPreset = preset;
    m_brushResources = ri;
    m_brushPresetIndex = index;
    // Re-apply the user's current size / opacity / flow over the preset's
    // own values (they are stored per preset and would otherwise override)
    setBrushSize(m_brushSize);
    setBrushOpacity(m_brushOpacity);
    setBrushFlow(1.0);
    return true;
}

int ReverieCore::brushPresetCount() const
{
    return m_presets.size();
}

QString ReverieCore::brushPresetName(int index) const
{
    if (index < 0 || index >= m_presets.size()) {
        return QString();
    }
    return m_presets[index].first;
}

QString ReverieCore::brushPresetPath(int index) const
{
    if (index < 0 || index >= m_presets.size()) {
        return QString();
    }
    return m_presets[index].second;
}

QByteArray ReverieCore::brushPresetThumbData(int index) const
{
    // The .kpp files ARE PNG thumbnails with an embedded "preset" zTXt chunk;
    // return the raw bytes so the UI can decode them directly.
    if (index < 0 || index >= m_presets.size()) {
        return QByteArray();
    }
    QFile f(m_presets[index].second);
    if (!f.open(QIODevice::ReadOnly)) {
        return QByteArray();
    }
    return f.readAll();
}

void ReverieCore::setBrushSize(qreal v)
{
    m_brushSize = v;
    if (m_brushPreset && m_brushPreset->settings()) {
        KisBrushBasedPaintOpSettings *bs =
            dynamic_cast<KisBrushBasedPaintOpSettings *>(m_brushPreset->settings().data());
        if (bs) {
            bs->setPaintOpSize(v);
        }
    }
}

void ReverieCore::setBrushOpacity(qreal v)
{
    m_brushOpacity = v;
    if (m_brushPreset && m_brushPreset->settings()) {
        m_brushPreset->settings()->setPaintOpOpacity(v);
    }
}

void ReverieCore::setBrushFlow(qreal v)
{
    if (m_brushPreset && m_brushPreset->settings()) {
        m_brushPreset->settings()->setPaintOpFlow(v);
    }
}

void ReverieCore::touchStrokeStart(qreal x, qreal y, qreal pressure)
{
    if (!m_document) {
        return;
    }
    // Defer the undo snapshot to the first real flush: reading every layer
    // here costs a full-document read per touch-down, which is felt as lag
    // when starting strokes. Nothing is painted at down time anyway.
    m_snapshotPending = true;
    m_drawing = true;
    m_strokeBatchOpen = true;
    m_lastPressure = pressure;
    m_strokeColor = m_brushColor;
    m_strokeOpacity = m_brushOpacity;
    // The stroke is painted at full strength into a temporary buffer; the
    // opacity is applied once at commit (see commitStrokeToLayer).
    if (!m_strokeBuffer) {
        m_strokeBuffer = new KisPaintDevice(m_document->colorSpace());
    } else {
        m_strokeBuffer->clear();
    }
    m_strokeStartImg = QPointF(x, y);
    m_strokeSamples.clear();
    m_strokeHadMove = false;
    // The stroke starts at the finger-down position: append it as the first
    // sample so the down -> first-move segment is drawn. Otherwise the first
    // flush sees one sample and paints a dot, and the stroke start is cut off
    // (Android can move several px before the first move event arrives).
    StrokeSample s;
    s.imgPos = m_strokeStartImg;
    s.pressure = pressure;
    m_strokeSamples.append(s);
}

void ReverieCore::touchStrokeMove(qreal x, qreal y, qreal pressure)
{
    if (!m_drawing || !m_strokeBatchOpen) {
        return;
    }
    const QPointF imgPos(x, y);
    const QPointF lastPos = m_strokeSamples.isEmpty()
            ? m_strokeStartImg
            : m_strokeSamples.last().imgPos;
    if (imgPos != lastPos) {
        appendStrokeSample(imgPos, pressure);
    }
}

void ReverieCore::touchStrokeEnd()
{
    if (m_strokeBatchOpen) {
        if (m_strokeSamples.isEmpty()) {
            StrokeSample s;
            s.imgPos = m_strokeStartImg;
            s.pressure = m_lastPressure;
            m_strokeSamples.append(s);
        }
        flushStrokeBatch();
        if (m_toolMode != ToolEraser) {
            commitStrokeToLayer();
        }
        endStrokeBatch();
        m_strokeBatchOpen = false;
    }
    m_drawing = false;
}

void ReverieCore::touchStrokeCancel()
{
    if (!m_document || !m_strokeBatchOpen) {
        m_drawing = false;
        m_strokeSamples.clear();
        m_strokeBatchOpen = false;
        return;
    }

    // A second finger must cancel, not commit, the partial stroke. The
    // snapshot was pushed by touchStrokeStart, so restore and remove that
    // snapshot without touching the redo stack.
    m_strokeSamples.clear();
    endStrokeBatch();
    if (m_strokeBuffer) {
        m_strokeBuffer->clear();
    }
    m_strokeBatchOpen = false;
    m_drawing = false;

    // No pixels were painted (the deferred snapshot was never taken), so
    // there is nothing to restore - the top undo entry belongs to an
    // earlier stroke.
    if (m_snapshotPending) {
        m_snapshotPending = false;
        return;
    }
    if (!m_undoStack.isEmpty() && !m_layers.isEmpty() && m_document) {
        const int w = m_document->width();
        const int h = m_document->height();
        QByteArray cur;
        for (const LayerEntry &entry : m_layers) {
            KisPaintDeviceSP dev = layerPaintDeviceFor(entry);
            if (!dev) continue;
            QByteArray layerBytes(w * h * 4, Qt::Uninitialized);
            dev->readBytes(reinterpret_cast<quint8 *>(layerBytes.data()), 0, 0, w, h);
            cur.append(layerBytes);
        }
        applySnapshot(m_undoStack.takeLast(), cur);
    }
}

void ReverieCore::appendStrokeSample(const QPointF &imgPos, qreal pressure)
{
    // Krita-style spacing sampling: only emit a dab when the stylus moved
    // ~20% of the brush diameter.
    const qreal spacing = qMax<qreal>(1.5, m_brushSize * 0.20);
    if (!m_strokeSamples.isEmpty()) {
        const QPointF last = m_strokeSamples.last().imgPos;
        const qreal dist = QLineF(last, imgPos).length();
        if (dist < spacing) {
            m_strokeSamples.last().pressure = pressure;
            return;
        }
    }
    m_strokeHadMove = true;
    StrokeSample s;
    s.imgPos = imgPos;
    s.pressure = pressure;
    m_strokeSamples.append(s);
    flushStrokeBatch();
}

// Centripetal Catmull-Rom spline point: evaluates the curve through
// P0,P1,P2,P3 at u in [0,1] (u=0 at P1, u=1 at P2). Centripetal
// parameterisation prevents the overshoot "hooks" that uniform Catmull-Rom
// produces on sharply curving strokes.
static QPointF centripetalCatmullRom(const QPointF &p0, const QPointF &p1,
                                     const QPointF &p2, const QPointF &p3,
                                     qreal u)
{
    const qreal t0 = 0.0;
    const qreal t1 = t0 + std::sqrt(QLineF(p0, p1).length());
    const qreal t2 = t1 + std::sqrt(QLineF(p1, p2).length());
    const qreal t3 = t2 + std::sqrt(QLineF(p2, p3).length());
    // Degenerate intervals: coincident control points (end segments use
    // P0=P1 / P3=P2, and duplicate samples can occur at high zoom) make a
    // parameter interval zero. The division then yields NaN dab positions,
    // and Krita's bezier subdivider (getBezierCurvePoints) recurses forever
    // on NaN -> stack overflow (SIGSEGV on arm64). Fall back to the plain
    // linear point instead.
    if (!(t1 > t0) || !(t2 > t1) || !(t3 > t2)) {
        return (u < 0.5) ? p1 : p2;
    }
    const qreal t = t1 + (t2 - t1) * u;
    const QPointF a1 = (t1 - t) / (t1 - t0) * p0 + (t - t0) / (t1 - t0) * p1;
    const QPointF a2 = (t2 - t) / (t2 - t1) * p1 + (t - t1) / (t2 - t1) * p2;
    const QPointF a3 = (t3 - t) / (t3 - t2) * p2 + (t - t2) / (t3 - t2) * p3;
    const QPointF b1 = (t2 - t) / (t2 - t0) * a1 + (t - t0) / (t2 - t0) * a2;
    const QPointF b2 = (t3 - t) / (t3 - t1) * a2 + (t - t1) / (t3 - t1) * a3;
    return (t2 - t) / (t2 - t1) * b1 + (t - t1) / (t2 - t1) * b2;
}

void ReverieCore::flushStrokeBatch()
{
    if (m_strokeSamples.isEmpty()) {
        return;
    }
    if (m_snapshotPending) {
        snapshotForUndo();
        m_snapshotPending = false;
    }

    KisImageSP image = m_document;
    if (!image) {
        m_strokeSamples.clear();
        return;
    }
    const bool erasing = (m_toolMode == ToolEraser);

    // The eraser paints DIRECTLY onto the layer with the erase composite op:
    // a temporary buffer would hold transparent pixels and erasing with a
    // transparent source clears nothing (gemini's buffer did exactly that).
    // Brush/smudge paint at full strength into the temporary buffer and the
    // stroke opacity is applied once at commit.
    KisPaintDeviceSP target = erasing ? currentPaintDevice() : m_strokeBuffer;
    if (!target) {
        m_strokeSamples.clear();
        return;
    }

    // Krita-style: reuse one KisPainter for the whole stroke.
    if (!m_strokePainter || m_strokeDevice != (void *)target.data()) {
        endStrokeBatch();
        m_strokeDevice = (void *)target.data();
        m_strokePainter = new KisPainter(target);
        m_strokePainter->setFillStyle(KisPainter::FillStyleForegroundColor);
        m_strokePainter->setStrokeStyle(KisPainter::StrokeStyleBrush);
        // Constrain the whole stroke to the active selection (if any)
        if (m_selection) {
            m_strokePainter->setSelection(m_selection);
        }
        // Real Krita brush engine: construct the brush op once per stroke
        // and drive its async dab pipeline synchronously (the fake executor
        // runs the rendering jobs inline, exactly like Krita's own tests).
        if (m_brushPreset && m_strokePainter) {
            m_strokePainter->setRunnableStrokeJobsInterface(&m_fakeExecutor);
            const int layerIndex = qBound(0, m_currentLayer, m_layers.size() - 1);
            m_strokeOp = new KisBrushOp(m_brushPreset->settings(), m_strokePainter,
                                        KisNodeSP(m_layers[layerIndex].node), image);
            const QPointF start =
                m_strokeSamples.isEmpty() ? m_strokeStartImg : m_strokeSamples.first().imgPos;
            delete m_strokeDistance;
            m_strokeDistance = new KisDistanceInformation(start, 0.0);
        }
    }
    KisPainter &painter = *m_strokePainter;
    const KoColorSpace *cs = image->colorSpace();
    QColor qColor(m_strokeColor);
    if (!qColor.isValid()) {
        qColor = Qt::black;
    }
    KoColor koColor(qColor, cs);
    painter.setPaintColor(koColor);
    painter.setBackgroundColor(koColor);
    // Eraser opacity is applied per dab (like Krita); brush/smudge apply
    // it once at commit.
    painter.setOpacityF(erasing ? qBound<qreal>(0.0, m_strokeOpacity, 1.0) : 1.0);
    painter.setCompositeOpId(erasing ? QStringLiteral("erase")
                                     : QStringLiteral("normal"));

    // Genuine tap only (no movement): paint a round dot. KisPainter::drawLine
    // with identical start/end returns immediately, so use paintEllipse
    // (fills with the foreground color) sized to the brush diameter. A
    // trailing single sample of a real stroke is NOT a dot.
    if (m_strokeSamples.size() == 1 && !m_strokeHadMove) {
        const QPointF p = m_strokeSamples.first().imgPos;
        const qreal pressure =
            qBound<qreal>(0.0, m_strokeSamples.first().pressure, 1.0);
        if (m_brushPreset && m_strokeOp) {
            // Krita dab for a genuine tap (paintAt = single dab at pos)
            m_strokeOp->paintAt(KisPaintInformation(p, pressure), m_strokeDistance);
            QVector<KisRunnableStrokeJobData *> jobs;
            m_strokeOp->doAsynchronousUpdate(jobs);
            for (auto *j : jobs) {
                j->run();
                delete j;
            }
        } else {
            // 15% brush-size floor: a light pressure must never shrink the
            // dab below a visible dot (the old floor of 1px made light
            // strokes disappear into dotted artifacts)
            qreal w = m_brushSize * pressure;
            w = qMax(w, qMax<qreal>(1.0, m_brushSize * 0.15));
            painter.paintEllipse(QRectF(p.x() - w / 2.0, p.y() - w / 2.0, w, w));
        }
        m_strokeSamples.clear();
        return;
    }

    QRect strokeDirty;
    if (m_brushPreset && m_strokeOp) {
        // ---- Real Krita brush engine ----
        // Continuous paintLine through the samples (the op interpolates dabs
        // along the path itself, with the real spacing/softness/flow of the
        // preset). The async dab pipeline is driven synchronously: render
        // jobs ran inline via the fake executor at enqueue time, and these
        // update jobs bitBlt the finished dabs onto the target device.
        for (int i = 1; i < m_strokeSamples.size(); ++i) {
            const StrokeSample &a = m_strokeSamples[i - 1];
            const StrokeSample &b = m_strokeSamples[i];
            m_strokeOp->paintLine(KisPaintInformation(a.imgPos, a.pressure),
                                  KisPaintInformation(b.imgPos, b.pressure),
                                  m_strokeDistance);
        }
        QVector<KisRunnableStrokeJobData *> jobs;
        m_strokeOp->doAsynchronousUpdate(jobs);
        for (auto *j : jobs) {
            j->run();
            delete j;
        }
        // Approximate dirty region: the exact dab rects live inside the op,
        // so we conservatively mark the samples' neighbourhood.
        for (const StrokeSample &sm : m_strokeSamples) {
            const int w = int(m_brushSize) + 2;
            const QRect r(int(sm.imgPos.x()) - w, int(sm.imgPos.y()) - w,
                          2 * w, 2 * w);
            strokeDirty = strokeDirty.isNull() ? r : strokeDirty.united(r);
        }
    } else {
        // ---- Fallback: classic round-dab loop (no preset loaded) ----
        const auto addDab = [&](const QPointF &p, qreal w) {
            painter.paintEllipse(QRectF(p.x() - w / 2.0, p.y() - w / 2.0, w, w));
            const QRect r(int(p.x()) - int(w) - 1, int(p.y()) - int(w) - 1,
                          2 * int(w) + 2, 2 * int(w) + 2);
            strokeDirty = strokeDirty.isNull() ? r : strokeDirty.united(r);
        };
        QPointF prev = m_strokeSamples.first().imgPos;
        qreal prevP = m_strokeSamples.first().pressure;
        qreal prevW = m_brushSize * qBound<qreal>(0.0, prevP, 1.0);
        prevW = qMax(prevW, qMax<qreal>(1.0, m_brushSize * 0.15));
        addDab(prev, prevW);
        for (int i = 1; i < m_strokeSamples.size(); ++i) {
            const QPointF cur = m_strokeSamples[i].imgPos;
            const qreal curP = m_strokeSamples[i].pressure;
            const QPointF p0 = (i >= 2) ? m_strokeSamples[i - 2].imgPos : prev + (prev - cur);
            const QPointF p1 = prev;
            const QPointF p2 = cur;
            const QPointF p3 = (i + 1 < m_strokeSamples.size()) ? m_strokeSamples[i + 1].imgPos
                                                                : cur + (cur - prev);
            const qreal segLen = QLineF(prev, cur).length();
            qreal segW = m_brushSize * qBound<qreal>(0.0, (prevP + curP) / 2.0, 1.0);
            segW = qMax(segW, qMax<qreal>(1.0, m_brushSize * 0.15));
            const qreal dabSpacing = qMax<qreal>(1.5, segW * 0.2);
            const int n = qMax(1, int(qCeil(segLen / dabSpacing)));
            for (int j = 1; j <= n; ++j) {
                const qreal t = qreal(j) / n;
                const QPointF p = centripetalCatmullRom(p0, p1, p2, p3, t);
                const qreal pMid = prevP + (curP - prevP) * t;
                qreal width = m_brushSize * qBound<qreal>(0.0, pMid, 1.0);
                width = qMax(width, qMax<qreal>(1.0, m_brushSize * 0.15));
                addDab(p, width);
            }
            prev = cur;
            prevP = curP;
        }
    }
    // Keep the last TWO samples as the next segment's context. A single
    // trailing sample made every flush a 2-sample batch whose only segment
    // is the degenerate first segment (P0==P1) - it painted just its
    // endpoints, producing dotted strokes with small brush widths.
    QVector<StrokeSample> trailing;
    if (m_strokeSamples.size() >= 2) {
        trailing << m_strokeSamples.at(m_strokeSamples.size() - 2)
                 << m_strokeSamples.last();
    } else if (!m_strokeSamples.isEmpty()) {
        trailing << m_strokeSamples.last();
    }
    m_strokeSamples.clear();
    for (const StrokeSample &t : trailing) {
        m_strokeSamples.append(t);
    }

    if (erasing) {
        // Eraser painted straight onto the layer: propagate the dirty region
        // so the projection recomposites it immediately.
        if (!strokeDirty.isNull()) {
            target->setDirty(strokeDirty);
            markRegionDirty(strokeDirty);
        }
    } else {
        // Nothing is composited here: the stroke lives in the temporary
        // buffer and is displayed by renderToBuffer while drawing. The
        // layer device is marked dirty once, in commitStrokeToLayer, when
        // the stroke is placed with its final opacity.
        (void)strokeDirty;
    }
}

// Place the finished stroke from the temporary buffer onto the current
// layer, applying the stroke opacity exactly once. Eraser uses the erase
// composite op so the stroke genuinely clears layer pixels.
void ReverieCore::commitStrokeToLayer()
{
    if (!m_strokeBuffer || !m_document) {
        return;
    }
    KisPaintDeviceSP device = currentPaintDevice();
    if (!device) {
        m_strokeBuffer->clear();
        return;
    }
    const QRect ext = m_strokeBuffer->exactBounds();
    if (ext.isEmpty()) {
        m_strokeBuffer->clear();
        return;
    }
    qreal opacity = qBound<qreal>(0.0, m_strokeOpacity, 1.0);
    // Smudge: a translucent smearing pass (MVP approximation of the real
    // smudge brush which pushes color along the stroke path).
    if (m_toolMode == ToolSmudge) {
        opacity = qMin<qreal>(opacity, 0.12);
    }
    KisPainter painter(device);
    painter.setOpacityF(opacity);
    painter.setCompositeOpId(QStringLiteral("normal"));
    // Transparency lock: preserve the existing alpha by masking the alpha
    // channel out of the write (Krita's KisPaintLayer::setAlphaLocked uses
    // the same channelFlags mechanism)
    const LayerEntry &cur = m_layers[qBound(0, m_currentLayer, m_layers.size() - 1)];
    if (cur.alphaLocked) {
        painter.setChannelFlags(device->colorSpace()->channelFlags(true, false));
    }
    // Active selection: constrain the stroke to the selection mask
    if (m_selection) {
        painter.setSelection(m_selection);
    }
    painter.bitBlt(ext.x(), ext.y(), m_strokeBuffer,
                   ext.x(), ext.y(), ext.width(), ext.height());
    // Clipping mask (self-implemented): keep only the pixels that sit on top
    // of the next paint layer's opaque area. Krita only has inherit-opacity,
    // so we mask the freshly committed stroke region against the base layer.
    if (cur.clipped && !cur.isGroup) {
        KisPaintDeviceSP base;
        for (int i = m_currentLayer - 1; i >= 0; --i) {
            if (!m_layers[i].isGroup) {
                base = layerPaintDeviceFor(m_layers[i]);
                break;
            }
        }
        if (base) {
            QImage devImg(ext.size(), QImage::Format_RGBA8888);
            QImage baseImg(ext.size(), QImage::Format_RGBA8888);
            device->readBytes(devImg.bits(), ext.x(), ext.y(), ext.width(), ext.height());
            base->readBytes(baseImg.bits(), ext.x(), ext.y(), ext.width(), ext.height());
            for (int y = 0; y < devImg.height(); ++y) {
                quint8 *d = devImg.scanLine(y);
                const quint8 *b = baseImg.constScanLine(y);
                for (int x = 0; x < devImg.width(); ++x) {
                    quint8 *dp = d + x * 4;
                    const quint8 *bp = b + x * 4;
                    dp[3] = quint8(int(dp[3]) * int(bp[3]) / 255);
                }
            }
            device->writeBytes(devImg.constBits(), ext.x(), ext.y(),
                               ext.width(), ext.height());
        }
    }
    // Krita's dirty propagation: mark the region dirty on the layer device
    // so its projection leaf recomposites it.
    device->setDirty(ext);
    markRegionDirty(ext);
    m_strokeBuffer->clear();
}

void ReverieCore::endStrokeBatch()
{
    delete m_strokePainter;
    m_strokePainter = nullptr;
    m_strokeDevice = nullptr;
    // The brush op pins the painter's device; drop it first, then the
    // distance accumulator.
    m_strokeOp = nullptr;
    delete m_strokeDistance;
    m_strokeDistance = nullptr;
}

// ---------------------------------------------------------------------------
// Undo / redo
// ---------------------------------------------------------------------------

// Bounding box of the pixels that differ between two w*h RGBA buffers.
// Row-wise memcmp first (cheap), then a per-pixel pass only over the changed
// rows. Returns an empty QRect when the buffers are identical.
static QRect layerDiffRect(const quint8 *a, const quint8 *b, int w, int h)
{
    const int rowBytes = w * 4;
    int yMin = -1;
    int yMax = -1;
    const quint8 *pa = a;
    const quint8 *pb = b;
    for (int y = 0; y < h; ++y) {
        if (memcmp(pa, pb, rowBytes) != 0) {
            if (yMin < 0) yMin = y;
            yMax = y;
        }
        pa += rowBytes;
        pb += rowBytes;
    }
    if (yMin < 0) {
        return QRect();
    }
    int xMin = w;
    int xMax = -1;
    for (int y = yMin; y <= yMax; ++y) {
        const quint32 *a32 = reinterpret_cast<const quint32 *>(a + y * rowBytes);
        const quint32 *b32 = reinterpret_cast<const quint32 *>(b + y * rowBytes);
        for (int x = 0; x < w; ++x) {
            if (a32[x] != b32[x]) {
                if (x < xMin) xMin = x;
                if (x > xMax) xMax = x;
            }
        }
    }
    return (xMin >= w) ? QRect() : QRect(xMin, yMin, xMax - xMin + 1, yMax - yMin + 1);
}

// Copy only the (x,y,w,h) sub-region out of a full-document RGBA buffer and
// write it into the layer device, then mark exactly that region dirty so the
// projection recomposites locally instead of doing a full pass.
static void writeRegionToDevice(KisPaintDevice *dev, const quint8 *full,
                                int docW, int docH, const QRect &r)
{
    if (!dev || r.isNull()) return;
    const int rowBytes = docW * 4;
    QByteArray sub(r.width() * r.height() * 4, Qt::Uninitialized);
    quint8 *dst = reinterpret_cast<quint8 *>(sub.data());
    const quint8 *src = full + r.y() * rowBytes + r.x() * 4;
    for (int y = 0; y < r.height(); ++y) {
        memcpy(dst, src, r.width() * 4);
        dst += r.width() * 4;
        src += rowBytes;
    }
    dev->writeBytes(reinterpret_cast<const quint8 *>(sub.constData()),
                    r.x(), r.y(), r.width(), r.height());
    dev->setDirty(r);
}

void ReverieCore::applySnapshot(const QByteArray &snap, const QByteArray &curBytes)
{
    KisImageSP image = m_document ? m_document : KisImageSP();
    if (!image || m_layers.isEmpty()) return;
    const int w = image->width();
    const int h = image->height();
    const int layerCount = m_layers.size();
    const int expected = w * h * 4 * layerCount;
    if (snap.size() != expected || curBytes.size() != expected) return;
    const quint8 *snapP = reinterpret_cast<const quint8 *>(snap.constData());
    const quint8 *curP = reinterpret_cast<const quint8 *>(curBytes.constData());
    QRect all;
    for (int i = 0; i < layerCount; ++i) {
        KisPaintDeviceSP dev = layerPaintDeviceFor(m_layers[i]);
        if (!dev) continue;
        const QRect diff = layerDiffRect(curP, snapP, w, h);
        if (!diff.isNull()) {
            writeRegionToDevice(dev.data(), snapP, w, h, diff);
            all = all.isNull() ? diff : all.united(diff);
        }
        curP += size_t(w) * h * 4;
        snapP += size_t(w) * h * 4;
    }
    if (!all.isNull()) {
        markRegionDirty(all);
    }
}

void ReverieCore::snapshotForUndo()
{
    KisImageSP image = m_document ? m_document : KisImageSP();
    if (!image || m_layers.isEmpty()) {
        return;
    }
    const int w = image->width();
    const int h = image->height();
    QByteArray bytes;
    for (const LayerEntry &entry : m_layers) {
        KisPaintDeviceSP dev = layerPaintDeviceFor(entry);
        if (!dev) continue;
        QByteArray layerBytes(w * h * 4, Qt::Uninitialized);
        dev->readBytes(reinterpret_cast<quint8 *>(layerBytes.data()), 0, 0, w, h);
        bytes.append(layerBytes);
    }
    m_undoStack.append(bytes);
    // Cap the stack: 32 snapshots * layers * ~8MB each (1080x1920). This is
    // heavy but acceptable for an MVP; a real implementation would use
    // Krita's KisTransaction + KisSurrogateUndoStore.
    if (m_undoStack.size() > 32) {
        m_undoStack.removeFirst();
    }
    m_redoStack.clear();
}

void ReverieCore::undo()
{
    if (m_undoStack.isEmpty()) {
        return;
    }
    KisImageSP image = m_document ? m_document : KisImageSP();
    if (!image || m_layers.isEmpty()) {
        return;
    }
    const int w = image->width();
    const int h = image->height();
    const int layerCount = m_layers.size();

    // Snapshot current state into redo
    QByteArray cur;
    for (const LayerEntry &entry : m_layers) {
        KisPaintDeviceSP dev = layerPaintDeviceFor(entry);
        if (!dev) continue;
        QByteArray layerBytes(w * h * 4, Qt::Uninitialized);
        dev->readBytes(reinterpret_cast<quint8 *>(layerBytes.data()), 0, 0, w, h);
        cur.append(layerBytes);
    }
    m_redoStack.append(cur);

    // Restore the undo snapshot (must match layer count)
    const QByteArray snap = m_undoStack.takeLast();
    applySnapshot(snap, cur);
}

void ReverieCore::redo()
{
    if (m_redoStack.isEmpty()) {
        return;
    }
    KisImageSP image = m_document ? m_document : KisImageSP();
    if (!image || m_layers.isEmpty()) {
        return;
    }
    const int w = image->width();
    const int h = image->height();
    const int layerCount = m_layers.size();

    QByteArray cur;
    for (const LayerEntry &entry : m_layers) {
        KisPaintDeviceSP dev = layerPaintDeviceFor(entry);
        if (!dev) continue;
        QByteArray layerBytes(w * h * 4, Qt::Uninitialized);
        dev->readBytes(reinterpret_cast<quint8 *>(layerBytes.data()), 0, 0, w, h);
        cur.append(layerBytes);
    }
    m_undoStack.append(cur);

    const QByteArray snap = m_redoStack.takeLast();
    applySnapshot(snap, cur);
}

// ---------------------------------------------------------------------------
// Rendering
// ---------------------------------------------------------------------------

bool ReverieCore::renderToBuffer(quint8 *buffer, int w, int h)
{
    KisImageSP image = m_document;
    if (!image || !buffer) {
        return false;
    }
    image->waitForDone();
    const int iw = image->width();
    const int ih = image->height();
    if (m_displayImage.isNull() || m_displayImage.size() != QSize(iw, ih)) {
        // New document (or resized): full redraw
        m_displayImage = QImage(iw, ih, QImage::Format_RGBA8888);
        m_dirtyRect = QRect(0, 0, iw, ih);
        m_bitmapInited = false;
    }

    // Re-composite only the dirty region. Krita's projection recomputes
    // exactly the tiles that changed, so a stroke costs a small convertToQImage
    // instead of a full-document pass every frame.
    m_lastDirty = QRect();
    if (!m_dirtyRect.isEmpty()) {
        const QRect r = m_dirtyRect.intersected(QRect(0, 0, iw, ih));
        if (!r.isEmpty()) {
            const QImage comp = image->convertToQImage(r.x(), r.y(), r.width(), r.height(), nullptr);
            if (!comp.isNull()) {
                const QImage conv = comp.convertToFormat(QImage::Format_RGBA8888);
                for (int y = 0; y < conv.height() && (r.y() + y) < ih; ++y) {
                    memcpy(m_displayImage.scanLine(r.y() + y) + r.x() * 4,
                           conv.constScanLine(y), size_t(conv.width()) * 4);
                }
            }
            m_lastDirty = r;
        }
        m_dirtyRect = QRect();
    }

    // Live stroke preview: while drawing, composite the in-progress stroke
    // buffer over the document projection (with the stroke opacity applied,
    // so what the user sees is what gets committed). KisPaintDevice's own
    // convertToQImage handles the color-space/byte-order conversion.
    if (m_drawing && m_strokeBuffer) {
        const QRect ext = m_strokeBuffer->exactBounds();
        if (!ext.isEmpty()) {
            const QRect cr = ext.intersected(QRect(0, 0, iw, ih));
            if (!cr.isEmpty()) {
                const QImage bufImg = m_strokeBuffer->convertToQImage(
                    nullptr, cr.x(), cr.y(), cr.width(), cr.height());
                if (!bufImg.isNull()) {
                    const QImage conv = bufImg.convertToFormat(QImage::Format_RGBA8888);
                    QPainter p(&m_displayImage);
                    p.setCompositionMode(QPainter::CompositionMode_SourceOver);
                    p.setOpacity(m_strokeOpacity);
                    p.drawImage(cr.topLeft(), conv);
                    p.end();
                }
                m_lastDirty = m_lastDirty.isNull() ? cr : m_lastDirty.united(cr);
            }
        }
    }

    // Copy into the Android bitmap buffer: full on the first render (or a
    // resize), then only the rows of the region that actually changed. The
    // compositing was already done regionally above, so this avoids a
    // full ~8MB memcpy on every frame.
    if (w == iw && h == ih) {
        if (!m_bitmapInited) {
            memcpy(buffer, m_displayImage.constBits(), size_t(iw) * ih * 4);
            m_bitmapInited = true;
        } else if (!m_lastDirty.isEmpty()) {
            const QRect r = m_lastDirty.intersected(QRect(0, 0, iw, ih));
            for (int y = r.top(); y <= r.bottom(); ++y) {
                memcpy(buffer + size_t(y) * w * 4 + size_t(r.left()) * 4,
                       m_displayImage.constScanLine(y) + r.left() * 4,
                       size_t(r.width()) * 4);
            }
        }
    } else {
        const QImage scaled = m_displayImage.scaled(w, h, Qt::IgnoreAspectRatio, Qt::SmoothTransformation);
        memcpy(buffer, scaled.constBits(), size_t(w) * h * 4);
        m_bitmapInited = true;
    }
    return true;
}

void ReverieCore::floodFillAt(int x, int y)
{
    KisImageSP image = m_document ? m_document : KisImageSP();
    if (!image) {
        return;
    }
    if (x < 0 || y < 0 || x >= image->width() || y >= image->height()) {
        return;
    }
    KisPaintDeviceSP device = currentPaintDevice();
    if (!device) {
        return;
    }
    const KoColorSpace *cs = image->colorSpace();
    QColor qColor(m_brushColor);
    if (!qColor.isValid()) {
        qColor = Qt::black;
    }
    KoColor koColor(qColor, cs);

    // Connected-region flood fill: read the current layer, BFS over pixels
    // similar to the seed color (within a tolerance), replace them with the
    // brush color. This mirrors the fill tool behaviour without needing
    // KisFillTool (which lives in kritaui).
    const int iw = image->width();
    const int ih = image->height();
    QImage layerImg(iw, ih, QImage::Format_RGBA8888);
    {
        QVector<quint8> bytes(size_t(iw) * ih * 4);
        device->readBytes(bytes.data(), 0, 0, iw, ih);
        memcpy(layerImg.bits(), bytes.constData(), size_t(iw) * ih * 4);
    }

    const QRgb seed = layerImg.pixel(qBound(0, x, iw - 1), qBound(0, y, ih - 1));
    const int r0 = qRed(seed), g0 = qGreen(seed), b0 = qBlue(seed);
    const int tol = 24; // color tolerance

    const QRgb fill = qRgba(qColor.red(), qColor.green(), qColor.blue(), 255);

    // BFS
    QVector<QPoint> stack;
    QVector<bool> visited(size_t(iw) * ih, false);
    stack.append(QPoint(x, y));
    visited[size_t(y) * iw + x] = true;
    int touched = 0;
    while (!stack.isEmpty()) {
        const QPoint p = stack.takeLast();
        const int px = p.x(), py = p.y();
        if (px < 0 || px >= iw || py < 0 || py >= ih) continue;
        const QRgb c = layerImg.pixel(px, py);
        if (qAbs(qRed(c) - r0) > tol || qAbs(qGreen(c) - g0) > tol || qAbs(qBlue(c) - b0) > tol) {
            continue;
        }
        layerImg.setPixel(px, py, fill);
        ++touched;
        const QPoint neighbors[] = { QPoint(px + 1, py), QPoint(px - 1, py),
                                     QPoint(px, py + 1), QPoint(px, py - 1) };
        for (const QPoint &n : neighbors) {
            if (n.x() < 0 || n.x() >= iw || n.y() < 0 || n.y() >= ih) continue;
            const size_t idx = size_t(n.y()) * iw + n.x();
            if (!visited[idx]) {
                visited[idx] = true;
                stack.append(n);
            }
        }
    }

    if (touched > 0) {
        device->writeBytes(layerImg.constBits(), 0, 0, iw, ih);
        device->setDirty();
        markDirty();
    }
}

QString ReverieCore::pickColorAt(int x, int y)
{
    KisImageSP image = m_document ? m_document : KisImageSP();
    if (!image) {
        return QString();
    }
    if (x < 0 || y < 0 || x >= image->width() || y >= image->height()) {
        return QString();
    }
    const QImage img = image->convertToQImage(0, 0, image->width(), image->height(), nullptr);
    if (img.isNull() || x >= img.width() || y >= img.height()) {
        return QString();
    }
    const QRgb c = img.pixel(x, y);
    return QStringLiteral("#%1%2%3")
            .arg(qRed(c), 2, 16, QLatin1Char('0'))
            .arg(qGreen(c), 2, 16, QLatin1Char('0'))
            .arg(qBlue(c), 2, 16, QLatin1Char('0'));
}

void ReverieCore::drawShape(int kind, int x1, int y1, int x2, int y2)
{
    KisImageSP image = m_document ? m_document : KisImageSP();
    if (!image) {
        return;
    }
    KisPaintDeviceSP device = currentPaintDevice();
    if (!device) {
        return;
    }
    const int w = image->width();
    const int h = image->height();
    // Bounds of the shape region
    QRect region(QPoint(qMin(x1, x2), qMin(y1, y2)), QPoint(qMax(x1, x2), qMax(y1, y2)));
    region = region.adjusted(-int(m_brushSize), -int(m_brushSize),
                             int(m_brushSize), int(m_brushSize))
                 .intersected(QRect(0, 0, w, h));
    if (region.isEmpty()) {
        return;
    }

    // Read the current layer content into a QImage, draw the shape with
    // QPainter (supports line/rect/ellipse), then write it back.
    QImage layerImg(region.size(), QImage::Format_RGBA8888);
    {
        const qint32 rw = region.width();
        const qint32 rh = region.height();
        QVector<quint8> bytes(size_t(rw) * rh * 4);
        device->readBytes(bytes.data(), region.x(), region.y(), rw, rh);
        // Copy bytes (Krita RGBA order) into QImage; QImage ARGB32 is
        // byte-order RGBA on little-endian, so a straight memcpy works.
        memcpy(layerImg.bits(), bytes.constData(), size_t(rw) * rh * 4);
    }

    const KoColorSpace *cs = image->colorSpace();
    QColor qColor(m_brushColor);
    if (!qColor.isValid()) {
        qColor = Qt::black;
    }
    qColor.setAlphaF(qBound<qreal>(0.0, m_brushOpacity, 1.0));
    const qreal penWidth = qMax<qreal>(1.0, m_brushSize);

    QPainter painter(&layerImg);
    painter.setRenderHint(QPainter::Antialiasing, true);
    painter.setCompositionMode(QPainter::CompositionMode_Source);
    QPen pen(qColor, penWidth, Qt::SolidLine, Qt::RoundCap, Qt::RoundJoin);
    painter.setPen(pen);
    painter.setBrush(Qt::NoBrush);

    const QPointF p1(x1 - region.x(), y1 - region.y());
    const QPointF p2(x2 - region.x(), y2 - region.y());
    const QRectF r(QRectF(p1, p2).normalized());
    switch (kind) {
    case 1: painter.drawRect(r); break;            // rectangle
    case 2: painter.drawEllipse(r); break;         // ellipse
    default: painter.drawLine(p1, p2); break;      // line
    }
    painter.end();

    const qint32 rw = region.width();
    const qint32 rh = region.height();
    device->writeBytes(layerImg.constBits(), region.x(), region.y(), rw, rh);
    device->setDirty();
    markDirty();
}

void ReverieCore::drawText(int x, int y, const QString &text, qreal fontSize)
{
    KisImageSP image = m_document ? m_document : KisImageSP();
    if (!image || text.isEmpty()) {
        return;
    }
    KisPaintDeviceSP device = currentPaintDevice();
    if (!device) {
        return;
    }
    const int w = image->width();
    const int h = image->height();

    QFont font;
    font.setPointSizeF(fontSize);
    QFontMetrics fm(font);
    const QRect bounds = fm.boundingRect(text).adjusted(-8, -8, 8, 8);
    const QRect region = bounds.translated(x, y).intersected(QRect(0, 0, w, h));
    if (region.isEmpty()) {
        return;
    }

    QImage layerImg(region.size(), QImage::Format_RGBA8888);
    {
        QVector<quint8> bytes(size_t(region.width()) * region.height() * 4);
        device->readBytes(bytes.data(), region.x(), region.y(), region.width(), region.height());
        memcpy(layerImg.bits(), bytes.constData(), size_t(region.width()) * region.height() * 4);
    }

    QColor qColor(m_brushColor);
    if (!qColor.isValid()) {
        qColor = Qt::black;
    }
    qColor.setAlphaF(qBound<qreal>(0.0, m_brushOpacity, 1.0));

    QPainter painter(&layerImg);
    painter.setRenderHint(QPainter::Antialiasing, true);
    painter.setCompositionMode(QPainter::CompositionMode_Source);
    painter.setFont(font);
    painter.setPen(qColor);
    painter.drawText(QPoint(x - region.x(), y - region.y()), text);
    painter.end();

    device->writeBytes(layerImg.constBits(), region.x(), region.y(),
                       region.width(), region.height());
    device->setDirty();
    markDirty();
}

namespace {
// Scanline polygon fill: paint mask into a w*h mask buffer.
void scanlineFillPolygon(const QVector<QPoint> &pts, int w, int h, QVector<bool> &mask)
{
    mask.fill(false, size_t(w) * h);
    if (pts.size() < 3) {
        return;
    }
    int ymin = INT_MAX, ymax = -INT_MAX;
    for (const QPoint &p : pts) {
        ymin = qMin(ymin, p.y());
        ymax = qMax(ymax, p.y());
    }
    ymin = qMax(0, ymin);
    ymax = qMin(h - 1, ymax);
    for (int y = ymin; y <= ymax; ++y) {
        // Collect x intersections with polygon edges
        QVector<int> xs;
        for (int i = 0; i < pts.size(); ++i) {
            const QPoint &a = pts[i];
            const QPoint &b = pts[(i + 1) % pts.size()];
            if ((a.y() <= y && b.y() > y) || (b.y() <= y && a.y() > y)) {
                const qreal t = qreal(y - a.y()) / qreal(b.y() - a.y());
                xs.append(int(a.x() + t * (b.x() - a.x())));
            }
        }
        std::sort(xs.begin(), xs.end());
        for (int i = 0; i + 1 < xs.size(); i += 2) {
            const int x0 = qMax(0, xs[i]);
            const int x1 = qMin(w - 1, xs[i + 1]);
            for (int x = x0; x <= x1; ++x) {
                mask[size_t(y) * w + x] = true;
            }
        }
    }
}
} // namespace

void ReverieCore::lassoFill(const QVector<QPoint> &points)
{
    KisImageSP image = m_document ? m_document : KisImageSP();
    if (!image) {
        return;
    }
    KisPaintDeviceSP device = currentPaintDevice();
    if (!device) {
        return;
    }
    const int iw = image->width();
    const int ih = image->height();
    QVector<bool> mask;
    scanlineFillPolygon(points, iw, ih, mask);

    QImage layerImg(iw, ih, QImage::Format_RGBA8888);
    {
        QVector<quint8> bytes(size_t(iw) * ih * 4);
        device->readBytes(bytes.data(), 0, 0, iw, ih);
        memcpy(layerImg.bits(), bytes.constData(), size_t(iw) * ih * 4);
    }
    QColor qColor(m_brushColor);
    if (!qColor.isValid()) {
        qColor = Qt::black;
    }
    const QRgb fill = qRgba(qColor.red(), qColor.green(), qColor.blue(), 255);
    bool touched = false;
    for (int y = 0; y < ih; ++y) {
        for (int x = 0; x < iw; ++x) {
            if (mask[size_t(y) * iw + x]) {
                layerImg.setPixel(x, y, fill);
                touched = true;
            }
        }
    }
    if (touched) {
        device->writeBytes(layerImg.constBits(), 0, 0, iw, ih);
        device->setDirty();
        markDirty();
    }
}

void ReverieCore::lassoClear(const QVector<QPoint> &points)
{
    KisImageSP image = m_document ? m_document : KisImageSP();
    if (!image) {
        return;
    }
    KisPaintDeviceSP device = currentPaintDevice();
    if (!device) {
        return;
    }
    const int iw = image->width();
    const int ih = image->height();
    QVector<bool> mask;
    scanlineFillPolygon(points, iw, ih, mask);

    QImage layerImg(iw, ih, QImage::Format_RGBA8888);
    {
        QVector<quint8> bytes(size_t(iw) * ih * 4);
        device->readBytes(bytes.data(), 0, 0, iw, ih);
        memcpy(layerImg.bits(), bytes.constData(), size_t(iw) * ih * 4);
    }
    bool touched = false;
    for (int y = 0; y < ih; ++y) {
        for (int x = 0; x < iw; ++x) {
            if (mask[size_t(y) * iw + x]) {
                layerImg.setPixel(x, y, 0x00000000); // transparent
                touched = true;
            }
        }
    }
    if (touched) {
        device->writeBytes(layerImg.constBits(), 0, 0, iw, ih);
        device->setDirty();
        markDirty();
    }
}

void ReverieCore::liquify(int fx, int fy, int tx, int ty)
{
    KisImageSP image = m_document ? m_document : KisImageSP();
    if (!image) {
        return;
    }
    KisPaintDeviceSP device = currentPaintDevice();
    if (!device) {
        return;
    }
    const int iw = image->width();
    const int ih = image->height();

    QImage layerImg(iw, ih, QImage::Format_RGBA8888);
    {
        QVector<quint8> bytes(size_t(iw) * ih * 4);
        device->readBytes(bytes.data(), 0, 0, iw, ih);
        memcpy(layerImg.bits(), bytes.constData(), size_t(iw) * ih * 4);
    }
    QImage result = layerImg.copy();

    const qreal radius = qMax<qreal>(8.0, m_brushSize * 0.6);
    const qreal radius2 = radius * radius;
    const int dx = tx - fx;
    const int dy = ty - fy;
    const QPoint center(fx, fy);

    const int x0 = qMax(0, int(fx - radius));
    const int x1 = qMin(iw - 1, int(fx + radius));
    const int y0 = qMax(0, int(fy - radius));
    const int y1 = qMin(ih - 1, int(fy + radius));

    for (int y = y0; y <= y1; ++y) {
        for (int x = x0; x <= x1; ++x) {
            const qreal r2 = qreal((x - fx) * (x - fx) + (y - fy) * (y - fy));
            if (r2 > radius2) {
                continue;
            }
            // Falloff: strongest at center, zero at edge
            const qreal falloff = 1.0 - qSqrt(r2 / radius2);
            const qreal strength = falloff * 0.9;
            const int sx = qBound(0, x - int(dx * strength), iw - 1);
            const int sy = qBound(0, y - int(dy * strength), ih - 1);
            result.setPixel(x, y, layerImg.pixel(sx, sy));
        }
    }

    device->writeBytes(result.constBits(), 0, 0, iw, ih);
    device->setDirty();
    markDirty();
}

bool ReverieCore::savePng(const QString &path)
{
    KisImageSP image = m_document ? m_document : KisImageSP();
    if (!image) {
        return false;
    }
    const QImage img = image->convertToQImage(0, 0, image->width(), image->height(), nullptr);
    if (img.isNull()) {
        return false;
    }
    return img.save(path, "PNG");
}

bool ReverieCore::loadPng(const QString &path)
{
    QImage img(path);
    if (img.isNull()) {
        return false;
    }
    if (!newDocument(img.width(), img.height())) {
        return false;
    }
    // Blit the loaded pixels into the topmost paintable layer (never the
    // locked background, which stays white)
    KisImageSP image = m_document;
    if (!image || m_layers.size() < 2) {
        return false;
    }
    const LayerEntry &dest = m_layers[m_layers.size() - 1];
    KisPaintDeviceSP dev = dest.isGroup ? KisPaintDeviceSP()
                                        : layerPaintDeviceFor(dest);
    if (!dev) {
        return false;
    }
    const KoColorSpace *cs = image->colorSpace();
    const QImage conv = img.convertToFormat(QImage::Format_ARGB32);
    for (int y = 0; y < conv.height(); ++y) {
        for (int x = 0; x < conv.width(); ++x) {
            const QRgb px = conv.pixel(x, y);
            KoColor kc(QColor(qRed(px), qGreen(px), qBlue(px), qAlpha(px)), cs);
            dev->setPixel(x, y, kc);
        }
    }
    dev->setDirty();
    markDirty();
    return true;
}

bool ReverieCore::renderLayerThumb(int index, int w, int h, void *dstPixels, int dstStride)
{
    if (!m_document || index < 0 || index >= m_layers.size()) {
        return false;
    }
    KisPaintDeviceSP dev = layerPaintDeviceFor(m_layers[index]);
    if (!dev) {
        return false;
    }
    const QRect ext = dev->exactBounds();
    if (ext.isEmpty()) {
        return false;
    }
    QImage full = dev->convertToQImage(nullptr, ext.x(), ext.y(), ext.width(), ext.height());
    if (full.isNull()) {
        return false;
    }
    QImage out(w, h, QImage::Format_RGBA8888);
    out.fill(Qt::transparent);
    const QImage scaled =
        full.scaled(w, h, Qt::KeepAspectRatio, Qt::SmoothTransformation);
    if (!scaled.isNull()) {
        QPainter p(&out);
        p.drawImage(QPointF((w - scaled.width()) / 2.0, (h - scaled.height()) / 2.0), scaled);
        p.end();
    }
    const int copyH = qMin(h, out.height());
    for (int y = 0; y < copyH; ++y) {
        memcpy(static_cast<char *>(dstPixels) + size_t(y) * dstStride,
               out.constScanLine(y), size_t(w) * 4);
    }
    return true;
}

int ReverieCore::docWidth() const
{
    return m_docWidth;
}

int ReverieCore::docHeight() const
{
    return m_docHeight;
}
