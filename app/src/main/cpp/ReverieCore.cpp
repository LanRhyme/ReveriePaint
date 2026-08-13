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
#include <kis_gbr_brush.h>
#include <kis_png_brush.h>
#include <kis_imagepipe_brush.h>
#include <kis_svg_brush.h>

#include <QDebug>
#if defined(Q_OS_ANDROID)
#include <android/log.h>
#define RPC_LOG(...) __android_log_print(ANDROID_LOG_INFO, "ReverieCore", __VA_ARGS__)
#else
#define RPC_LOG(...) fprintf(stderr, __VA_ARGS__)
#endif
#include <algorithm>
#include <queue>
#include <QPainter>
#include <QFont>
#include <QFontMetrics>
#include <QLineF>

#include <kis_image.h>
#include <kis_undo_store.h>
#include <kis_undo_stores.h>
#include <kis_transaction.h>
#include <kundo2command.h>
#include <kundo2magicstring.h>
#include <commands/kis_image_layer_add_command.h>
#include <commands/kis_image_layer_remove_command.h>
#include <commands/kis_image_layer_move_command.h>
#include <commands/kis_node_opacity_command.h>
#include <commands/kis_node_compositeop_command.h>
#include <commands/KisNodeRenameCommand.h>
#include <kis_painter.h>
#include <layerstyles/kis_ls_utils.h>
#include <kis_selection_filters.h>
#include <kis_gaussian_kernel.h>
#include <kis_convolution_painter.h>
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
    // Krita-native undo store: create once per process, reset per document.
    // The store owns a KUndo2Stack of tile-level KisTransactionData /
    // node commands, so undo/redo is memory-efficient and covers every
    // operation type (strokes, fills, shapes, layer structure, attributes).
    delete m_strokeTxn;
    m_strokeTxn = nullptr;
    m_strokeTxnActive = false;
    if (!m_undoStore) {
        m_undoStore = new KisSurrogateUndoStore();
    }
    m_undoStore->clear();
    m_redoCount = 0;

    const KoColorSpace *cs = KoColorSpaceRegistry::instance()->rgb8();
    if (!cs) {
        return false;
    }

    // Create a standalone Krita image without KisDocument/KisPart (which
    // live in kritaui and need a full QApplication). KisImage's public
    // ctor is sufficient for a single-document painting engine.
    KisImageSP image = new KisImage(m_undoStore, width, height, cs,
                                    QStringLiteral("Untitled"));
    // setUndoStore re-wires the legacy + post-execution undo adapters so
    // image->undoAdapter()->addCommand() routes into our store
    image->setUndoStore(m_undoStore);
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


// KisImageChangeVisibilityCommand is not exported from libkritaimage (no
// KRITAIMAGE_EXPORT on its header), so provide a small local command with
// identical semantics: redo()/undo() flip the node's visibility and the
// caller marks the projection dirty.
class ReverieNodeVisibleCommand : public KUndo2Command
{
public:
    ReverieNodeVisibleCommand(KisNodeSP node, bool visible,
                              const KUndo2MagicString &text)
        : KUndo2Command(text)
        , m_node(node)
        , m_newVisible(visible)
        , m_oldVisible(node ? node->visible() : false)
    {
    }

    void redo() override
    {
        if (m_node) {
            m_node->setVisible(m_newVisible);
        }
    }

    void undo() override
    {
        if (m_node) {
            m_node->setVisible(m_oldVisible);
        }
    }

private:
    KisNodeSP m_node;
    bool m_newVisible;
    bool m_oldVisible;
};

/**
 * Undo/redo for selection changes, mirroring how Krita's selection tools
 * record edits: saves the previous selection object plus its pixel mask
 * bytes, restores them on undo, and re-applies the new selection on redo.
 */
class ReverieSelectionCommand : public KUndo2Command
{
public:
    ReverieSelectionCommand(ReverieCore *core, const KisSelectionSP &oldSel,
                            const QVector<quint8> &oldMask, int iw, int ih,
                            const KisSelectionSP &newSel)
        : KUndo2Command(kundo2_i18n("Selection"))
        , m_core(core)
        , m_oldSel(oldSel)
        , m_oldMask(oldMask)
        , m_iw(iw)
        , m_ih(ih)
        , m_newSel(newSel)
    {
    }

    void redo() override
    {
        if (m_core) {
            m_core->setSelection(m_newSel);
        }
    }

    void undo() override
    {
        if (!m_core) {
            return;
        }
        if (!m_oldSel) {
            // No selection before this edit
            m_core->setSelection(KisSelectionSP());
            return;
        }
        KisPixelSelectionSP ps = m_oldSel->pixelSelection();
        if (!m_oldMask.isEmpty()) {
            ps->writeBytes(m_oldMask.constData(), 0, 0, m_iw, m_ih);
        }
        m_core->setSelection(m_oldSel);
    }

private:
    ReverieCore *m_core;
    KisSelectionSP m_oldSel;
    QVector<quint8> m_oldMask;
    int m_iw;
    int m_ih;
    KisSelectionSP m_newSel;
};

// Snapshot the current selection's pixel mask (empty when there is none)
static QVector<quint8> readSelectionMaskBytes(const KisImageSP &image,
                                              const KisSelectionSP &sel)
{
    QVector<quint8> bytes;
    if (!image || !sel) {
        return bytes;
    }
    KisPixelSelectionSP ps = sel->pixelSelection();
    const int iw = image->width();
    const int ih = image->height();
    bytes.resize(size_t(iw) * ih);
    ps->readBytes(bytes.data(), 0, 0, iw, ih);
    return bytes;
}

// Krita's KisLsUtils::growSelectionUniform / applyGaussianWithTransaction are
// not exported from libkritaimage, but the filters they wrap are, so replicate
// the exact same calls (the logic Krita's layer styles use).
static QRect growSelectionUniformLocal(KisPixelSelectionSP selection, int growSize, const QRect &applyRect)
{
    QRect changeRect = applyRect;
    if (growSize > 0) {
        KisGrowSelectionFilter filter(growSize, growSize);
        changeRect = filter.changeRect(applyRect, selection->defaultBounds());
        filter.process(selection, applyRect);
    } else if (growSize < 0) {
        KisShrinkSelectionFilter filter(qAbs(growSize), qAbs(growSize), false);
        changeRect = filter.changeRect(applyRect, selection->defaultBounds());
        filter.process(selection, applyRect);
    }
    return changeRect;
}

static void applyGaussianLocal(KisPixelSelectionSP selection, const QRect &applyRect, qreal radius)
{
    KisGaussianKernel::applyGaussian(selection, applyRect, radius, radius,
                                     QBitArray(), 0, true, BORDER_IGNORE);
}

static KisSelectionSP selectionFromMask(const KisImageSP &image,
                                        const QVector<quint8> &mask);
static void setSelectionFromMask(ReverieCore *core, const KisImageSP &image,
                                 const QVector<quint8> &mask, int selMode);

// Morphological dilation: selected pixels spread out by 'radius' pixels
static void dilateMask(QVector<quint8> &mask, int w, int h, int radius)
{
    QVector<quint8> out = mask;
    for (int y = 0; y < h; ++y) {
        for (int x = 0; x < w; ++x) {
            if (mask[size_t(y) * w + x]) {
                const int x0 = qMax(0, x - radius), x1 = qMin(w - 1, x + radius);
                const int y0 = qMax(0, y - radius), y1 = qMin(h - 1, y + radius);
                for (int yy = y0; yy <= y1; ++yy) {
                    for (int xx = x0; xx <= x1; ++xx) {
                        out[size_t(yy) * w + xx] = 255;
                    }
                }
            }
        }
    }
    mask = out;
}

// Morphological erosion: deselect pixels within 'radius' of an unselected pixel
static void erodeMask(QVector<quint8> &mask, int w, int h, int radius)
{
    QVector<quint8> out = mask;
    for (int y = 0; y < h; ++y) {
        for (int x = 0; x < w; ++x) {
            if (!mask[size_t(y) * w + x]) {
                const int x0 = qMax(0, x - radius), x1 = qMin(w - 1, x + radius);
                const int y0 = qMax(0, y - radius), y1 = qMin(h - 1, y + radius);
                for (int yy = y0; yy <= y1; ++yy) {
                    for (int xx = x0; xx <= x1; ++xx) {
                        out[size_t(yy) * w + xx] = 0;
                    }
                }
            }
        }
    }
    mask = out;
}

// Approximate Gaussian feather via repeated box blurs (radius iterations)
static void blurMask(QVector<quint8> &mask, int w, int h, int radius)
{
    QVector<quint8> tmp = mask;
    for (int pass = 0; pass < qMax(1, radius); ++pass) {
        // horizontal blur
        for (int y = 0; y < h; ++y) {
            for (int x = 0; x < w; ++x) {
                int sum = 0, cnt = 0;
                for (int xx = qMax(0, x - 1); xx <= qMin(w - 1, x + 1); ++xx) {
                    sum += mask[size_t(y) * w + xx];
                    ++cnt;
                }
                tmp[size_t(y) * w + x] = quint8(sum / cnt);
            }
        }
        mask = tmp;
        // vertical blur
        for (int x = 0; x < w; ++x) {
            for (int y = 0; y < h; ++y) {
                int sum = 0, cnt = 0;
                for (int yy = qMax(0, y - 1); yy <= qMin(h - 1, y + 1); ++yy) {
                    sum += mask[size_t(yy) * w + x];
                    ++cnt;
                }
                tmp[size_t(y) * w + x] = quint8(sum / cnt);
            }
        }
        mask = tmp;
    }
}

// Merge a freshly created selection mask into the existing one according to
// the selection mode (replace / add / subtract / intersect)
static QVector<quint8> combineSelectionMasks(const QVector<quint8> &existing,
                                             const QVector<quint8> &added,
                                             int mode)
{
    const int n = existing.size();
    QVector<quint8> out(n, 0);
    for (int i = 0; i < n; ++i) {
        const bool a = existing[i] > 127;
        const bool b = added[i] > 127;
        switch (mode) {
        case ReverieCore::SelAdd:
            out[i] = (a || b) ? 255 : 0;
            break;
        case ReverieCore::SelSubtract:
            out[i] = (a && !b) ? 255 : 0;
            break;
        case ReverieCore::SelIntersect:
            out[i] = (a && b) ? 255 : 0;
            break;
        default:
            out[i] = added[i];
            break;
        }
    }
    return out;
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
        // Visibility is a structural change: KisNode::setVisible only
        // notifies the graph listener, it does not schedule a projection
        // recomposite. setDirty() -> requestProjectionUpdate does.
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
    QImage img(ext.size(), QImage::Format_ARGB32_Premultiplied);
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
    // Krita-native undo: move into the group at its bottom (index 0)
    pushUndoCommand(new KisImageLayerMoveCommand(
        m_document, node, group, quint32(0)));
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
    // Krita-native undo: wrap the pixel filter in a transaction
    KisTransaction txn(kundo2_i18n("Filter"), dev);
    QImage img(ext.size(), QImage::Format_ARGB32_Premultiplied);
    dev->readBytes(img.bits(), ext.x(), ext.y(), ext.width(), ext.height());
    switch (filterId) {
    case 0: {  // grayscale (RGBA8888 byte order: R,G,B,A)
        for (int y = 0; y < img.height(); ++y) {
            quint8 *line = img.scanLine(y);
            for (int x = 0; x < img.width(); ++x) {
                quint8 *px = line + x * 4;
                const int gray = (px[2] * 299 + px[1] * 587 + px[0] * 114) / 1000;
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
    if (m_document) {
        txn.commit(m_document->undoAdapter());
        m_redoCount = 0;
    }
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
    // Krita mechanism: selectionFromAlphaChannel copies the layer alpha
    const KisSelectionSP oldSel = m_selection;
    const QVector<quint8> oldMask = readSelectionMaskBytes(image, oldSel);
    KisLsUtils::selectionFromAlphaChannel(
        dev, sel, QRect(0, 0, image->width(), image->height()));
    setSelection(sel);
    pushUndoCommand(new ReverieSelectionCommand(this, oldSel, oldMask,
                                                image->width(), image->height(),
                                                m_selection));
    return true;
}

bool ReverieCore::hasSelection() const
{
    return bool(m_selection);
}

void ReverieCore::clearSelection()
{
    KisImageSP image = m_document;
    if (!image) {
        return;
    }
    const KisSelectionSP oldSel = m_selection;
    const QVector<quint8> oldMask = readSelectionMaskBytes(image, oldSel);
    setSelection(nullptr);
    pushUndoCommand(new ReverieSelectionCommand(this, oldSel, oldMask,
                                                image->width(), image->height(),
                                                m_selection));
}

void ReverieCore::selectAll()
{
    KisImageSP image = m_document;
    if (!image) {
        return;
    }
    KisSelectionSP sel = new KisSelection(
        new KisSelectionDefaultBounds(image->projection()),
        toQShared(new KisImageResolutionProxy(image)));
    const KisSelectionSP oldSel = m_selection;
    const QVector<quint8> oldMask = readSelectionMaskBytes(image, oldSel);
    sel->pixelSelection()->select(
        QRect(0, 0, image->width(), image->height()), OPACITY_OPAQUE_U8);
    setSelection(sel);
    pushUndoCommand(new ReverieSelectionCommand(this, oldSel, oldMask,
                                                image->width(), image->height(),
                                                m_selection));
}

void ReverieCore::invertSelection()
{
    KisImageSP image = m_document;
    if (!image) {
        return;
    }
    const int iw = image->width();
    const int ih = image->height();
    const KisSelectionSP oldSel = m_selection;
    const QVector<quint8> oldMask = readSelectionMaskBytes(image, oldSel);
    QVector<quint8> mask(size_t(iw) * ih, 0);
    if (m_selection) {
        KisPixelSelectionSP ps = m_selection->pixelSelection();
        QVector<quint8> selBytes(size_t(iw) * ih);
        ps->readBytes(selBytes.data(), 0, 0, iw, ih);
        for (int i = 0; i < iw * ih; ++i) {
            mask[i] = selBytes[i] > 127 ? 0 : quint8(255);
        }
    } else {
        // No selection yet -> invert of "nothing selected" is everything
        for (int i = 0; i < iw * ih; ++i) {
            mask[i] = 255;
        }
    }
    setSelectionFromMask(this, image, mask, int(m_selectionMode));
    pushUndoCommand(new ReverieSelectionCommand(this, oldSel, oldMask, iw, ih, m_selection));
    markDirty();
}

KisPixelSelectionSP ReverieCore::currentSelectionPixelSelection() const
{
    return m_selection ? m_selection->pixelSelection() : KisPixelSelectionSP();
}

void ReverieCore::setSelection(KisSelectionSP sel)
{
    // Deliberately NOT installing a KisSelectionMask on the layer tree:
    // that changes the projection composition (the mask would clip every
    // layer below it) and corrupts the render, which made brush strokes
    // ignore the selection while eraser strokes were clipped. Instead the
    // selection constrains painting at the KisPainter level (setSelection
    // applies to bltFixed/bitBlt), exactly like Krita's tools.
    m_selection = sel;
    markDirty();
}

void ReverieCore::featherSelection(int radius)
{
    KisImageSP image = m_document;
    if (!image || !m_selection || radius <= 0) {
        return;
    }
    const int iw = image->width();
    const int ih = image->height();
    const KisSelectionSP oldSel = m_selection;
    const QVector<quint8> oldMask = readSelectionMaskBytes(image, oldSel);
    // Krita's own Gaussian feather (same path layer styles use)
    applyGaussianLocal(
        m_selection->pixelSelection(), QRect(0, 0, iw, ih), radius);
    setSelection(m_selection);
    pushUndoCommand(new ReverieSelectionCommand(this, oldSel, oldMask, iw, ih, m_selection));
}

void ReverieCore::expandSelection(int px)
{
    KisImageSP image = m_document;
    if (!image || !m_selection || px <= 0) {
        return;
    }
    const int iw = image->width();
    const int ih = image->height();
    const KisSelectionSP oldSel = m_selection;
    const QVector<quint8> oldMask = readSelectionMaskBytes(image, oldSel);
    // Krita's grow filter (KisGrowSelectionFilter via KisLsUtils)
    growSelectionUniformLocal(
        m_selection->pixelSelection(), px, QRect(0, 0, iw, ih));
    setSelection(m_selection);
    pushUndoCommand(new ReverieSelectionCommand(this, oldSel, oldMask, iw, ih, m_selection));
}

void ReverieCore::contractSelection(int px)
{
    KisImageSP image = m_document;
    if (!image || !m_selection || px <= 0) {
        return;
    }
    const int iw = image->width();
    const int ih = image->height();
    const KisSelectionSP oldSel = m_selection;
    const QVector<quint8> oldMask = readSelectionMaskBytes(image, oldSel);
    // Krita's shrink filter (KisShrinkSelectionFilter via KisLsUtils)
    growSelectionUniformLocal(
        m_selection->pixelSelection(), -px, QRect(0, 0, iw, ih));
    setSelection(m_selection);
    pushUndoCommand(new ReverieSelectionCommand(this, oldSel, oldMask, iw, ih, m_selection));
}

void ReverieCore::smoothSelection(int radius)
{
    KisImageSP image = m_document;
    if (!image || !m_selection || radius <= 0) {
        return;
    }
    const int iw = image->width();
    const int ih = image->height();
    const KisSelectionSP oldSel = m_selection;
    const QVector<quint8> oldMask = readSelectionMaskBytes(image, oldSel);
    // Morphological close via Krita's filters: shrink removes specks,
    // growing back restores the bulk (KisShrink/GrowSelectionFilter)
    growSelectionUniformLocal(
        m_selection->pixelSelection(), -radius, QRect(0, 0, iw, ih));
    growSelectionUniformLocal(
        m_selection->pixelSelection(), radius, QRect(0, 0, iw, ih));
    setSelection(m_selection);
    pushUndoCommand(new ReverieSelectionCommand(this, oldSel, oldMask, iw, ih, m_selection));
}

QByteArray ReverieCore::selectionMask() const
{
    KisImageSP image = m_document;
    if (!image) {
        return QByteArray();
    }
    const int iw = image->width();
    const int ih = image->height();
    QByteArray mask(iw * ih, 0);
    if (m_selection) {
        KisPixelSelectionSP ps = m_selection->pixelSelection();
        QVector<quint8> bytes(size_t(iw) * ih);
        ps->readBytes(bytes.data(), 0, 0, iw, ih);
        int nonZero = 0;
        for (int i = 0; i < iw * ih; ++i) {
            mask[i] = bytes[i] > 127 ? char(255) : char(0);
            if (bytes[i] > 127) {
                ++nonZero;
            }
        }
        RPC_LOG("RPC selectionMask nonzero=%d of %d", nonZero, iw * ih);
    } else {
        RPC_LOG("RPC selectionMask null selection");
    }
    return mask;
}

QVector<quint32> ReverieCore::selectionOverlayScaled(int vw, int vh) const
{
    KisImageSP image = m_document;
    if (!image || !m_selection) {
        return {};
    }
    KisPixelSelectionSP ps = m_selection->pixelSelection();
    if (!ps) {
        return {};
    }
    const int iw = image->width();
    const int ih = image->height();
    vw = qMax(1, vw);
    vh = qMax(1, vh);
    // Sample the selection mask at the viewport stride: reading only the
    // needed rows/columns avoids the full-document mask readBytes that made
    // the overlay refresh slow (Krita renders the selection outline on the
    // canvas device at the current zoom; this is the equivalent here)
    QVector<quint32> out(size_t(vw) * vh, 0);
    QVector<quint8> row;
    row.resize(int(iw));
    const double stepY = double(ih) / vh;
    const double stepX = double(iw) / vw;
    bool any = false;
    for (int y = 0; y < vh; ++y) {
        const int srcY = qMin(ih - 1, int(y * stepY));
        ps->readBytes(row.data(), 0, srcY, iw, 1);
        const size_t dstOff = size_t(y) * vw;
        for (int x = 0; x < vw; ++x) {
            const int srcX = qMin(iw - 1, int(x * stepX));
            if (row[size_t(srcX)] != 0) {
                out[dstOff + x] = 0xFFFFFFFFu;
                any = true;
            }
        }
    }
    if (!any) {
        return {};
    }
    return out;
}

namespace {
void scanlineFillPolygon(const QVector<QPoint> &pts, int w, int h, QVector<bool> &mask);
}

QVector<quint32> ReverieCore::previewLassoOverlay(const QVector<QPoint> &points, int vw, int vh) const
{
    KisImageSP image = m_document;
    if (!image || points.size() < 3) {
        return {};
    }
    const int iw = image->width();
    const int ih = image->height();
    QVector<bool> mask;
    scanlineFillPolygon(points, iw, ih, mask);
    // Merge the drawn polygon into the CURRENT selection exactly like the
    // committed path does (setSelectionFromMask): otherwise the live fill
    // (a bare polygon) visibly differs from the committed selection in the
    // add / subtract / intersect modes, which is exactly what the user
    // reported ("real-time preview fill vs final selection differ")
    QVector<quint8> selMask(size_t(iw) * ih, 0);
    for (size_t i = 0; i < mask.size(); ++i) {
        selMask[i] = mask[i] ? 255 : 0;
    }
    if (m_selectionMode != SelReplace && m_selection) {
        QVector<quint8> existing(size_t(iw) * ih, 0);
        KisPixelSelectionSP ps = m_selection->pixelSelection();
        if (ps) {
            ps->readBytes(existing.data(), 0, 0, iw, ih);
            selMask = combineSelectionMasks(existing, selMask, m_selectionMode);
        }
    }
    vw = qMax(1, vw);
    vh = qMax(1, vh);
    QVector<quint32> out(size_t(vw) * vh, 0);
    const double stepX = double(iw) / vw;
    const double stepY = double(ih) / vh;
    bool any = false;
    for (int y = 0; y < vh; ++y) {
        const int srcY = qMin(ih - 1, int(y * stepY));
        const size_t dstOff = size_t(y) * vw;
        for (int x = 0; x < vw; ++x) {
            const int srcX = qMin(iw - 1, int(x * stepX));
            if (selMask[size_t(srcY) * iw + srcX]) {
                out[dstOff + x] = 0xFFFFFFFFu;
                any = true;
            }
        }
    }
    if (!any) {
        return {};
    }
    return out;
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
extern "C" void krita_register_colorsmudge_paintop();
extern "C" void krita_register_roundmarker_paintop();
extern "C" void krita_register_spray_paintop();
extern "C" void krita_register_sketch_paintop();
extern "C" void krita_register_deform_paintop();
extern "C" void krita_register_filter_paintop();
extern "C" void krita_register_grid_paintop();
extern "C" void krita_register_experiment_paintop();
extern "C" void krita_register_particle_paintop();
extern "C" void krita_register_curve_paintop();
extern "C" void krita_register_tangentnormal_paintop();
extern "C" void krita_register_hairy_paintop();
extern "C" void krita_register_hatching_paintop();

void ReverieCore::registerPaintOps()
{
    static bool done = false;
    if (!done) {
        // Implemented inside the cross-compiled paintop plugin libraries so
        // the KisSimplePaintOpFactory vtable layout matches libkritaimage's
        // view (instantiating the template in this module produced vtable
        // misalignment and crashes).
        krita_register_default_paintops();
        krita_register_colorsmudge_paintop();
        krita_register_roundmarker_paintop();
        krita_register_spray_paintop();
        krita_register_sketch_paintop();
        krita_register_deform_paintop();
        krita_register_filter_paintop();
        krita_register_grid_paintop();
        krita_register_experiment_paintop();
        krita_register_particle_paintop();
        krita_register_curve_paintop();
        krita_register_tangentnormal_paintop();
        krita_register_hairy_paintop();
        krita_register_hatching_paintop();
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

int ReverieCore::loadBrushResources(const QString &dirPath)
{
    // The shared resources interface: presets resolve their brush_definition
    // filename through it, so the loaded brush files must live here. It is
    // created once and reused by every loadBrushPreset call.
    if (!m_brushResources) {
        m_brushResources = KisResourcesInterfaceSP(new KisLocalStrokeResources());
    }
    QDir dir(dirPath);
    const QStringList files = dir.entryList(
        QStringList() << QStringLiteral("*.gbr") << QStringLiteral("*.gih")
                      << QStringLiteral("*.png") << QStringLiteral("*.svg"),
        QDir::Files, QDir::Name);
    int loaded = 0;
    for (const QString &base : files) {
        const QString fullPath = dir.filePath(base);
        KoResource *res = nullptr;
        if (base.endsWith(QLatin1String(".gbr"))) {
            res = new KisGbrBrush(base);
        } else if (base.endsWith(QLatin1String(".gih"))) {
            res = new KisImagePipeBrush(base);
        } else if (base.endsWith(QLatin1String(".png"))) {
            res = new KisPngBrush(base);
        } else if (base.endsWith(QLatin1String(".svg"))) {
            res = new KisSvgBrush(base);
        }
        if (!res) {
            continue;
        }
        QFile f(fullPath);
        if (f.open(QIODevice::ReadOnly)) {
            // The resource's filename() is the bare file name (matching the
            // filename attribute in presets' brush_definition), so we load
            // from the full path manually instead of KoResource::load().
            if (res->loadFromDevice(&f, m_brushResources)) {
                KisLocalStrokeResources *lr =
                    dynamic_cast<KisLocalStrokeResources *>(m_brushResources.data());
                if (lr) {
                    lr->addResource(KoResourceSP(res));
                    ++loaded;
                } else {
                    delete res;
                }
            } else {
                delete res;
            }
            f.close();
        } else {
            delete res;
        }
    }
    RPC_LOG("RPC loadBrushResources dir=%s loaded=%d", dirPath.toUtf8().constData(), loaded);
    return loaded;
}

bool ReverieCore::loadBrushPreset(int index)
{
    if (index < 0 || index >= m_presets.size()) {
        return false;
    }
    registerPaintOps();
    if (!m_brushResources) {
        m_brushResources = KisResourcesInterfaceSP(new KisLocalStrokeResources());
    }
    const QString path = m_presets[index].second;
    QFile f(path);
    if (!f.open(QIODevice::ReadOnly)) {
        return false;
    }
    KisPaintOpPresetSP preset(new KisPaintOpPreset(m_presets[index].first));
    const bool ok = preset->loadFromDevice(&f, m_brushResources);
    f.close();
    RPC_LOG("RPC loadBrushPreset idx=%d path=%s ok=%d", index, path.toUtf8().constData(), ok);
    if (!ok) {
        return false;
    }
    m_brushPreset = preset;
    m_brushPresetIndex = index;
    // Re-apply the user's current size / opacity / flow over the preset's
    // own values (they are stored per preset and would otherwise override)
    setBrushSize(m_brushSize);
    setBrushOpacity(m_brushOpacity);
    setBrushFlow(m_brushFlow);
    // Diagnostics: is the preset's brush resolved to a real brush resource
    // or did it fall back to the default auto_brush (circle)?
    KisBrushBasedPaintOpSettings *bs =
        dynamic_cast<KisBrushBasedPaintOpSettings *>(m_brushPreset->settings().data());
    if (bs) {
        KisBrushSP b = bs->brush();
        if (b) {
            const QImage tip = b->brushTipImage();
            RPC_LOG("RPC brushRESOLVED file=%s tip=%dx%d valid=%d spacing=%.3f",
                    b->filename().toUtf8().constData(),
                    tip.width(), tip.height(), b->valid() ? 1 : 0,
                    (double)b->spacing());
        } else {
            RPC_LOG("RPC brushNULL");
        }
    } else {
        RPC_LOG("RPC brushNOCAST");
    }
    return true;
}

QVector<double> ReverieCore::brushPresetDefaults(int index)
{
    if (index < 0 || index >= m_presets.size()) {
        return {20.0, 1.0, 1.0};
    }
    registerPaintOps();
    if (!m_brushResources) {
        m_brushResources = KisResourcesInterfaceSP(new KisLocalStrokeResources());
    }
    QFile f(m_presets[index].second);
    if (!f.open(QIODevice::ReadOnly)) {
        return {20.0, 1.0, 1.0};
    }
    KisPaintOpPresetSP preset(new KisPaintOpPreset(m_presets[index].first));
    const bool ok = preset->loadFromDevice(&f, m_brushResources);
    f.close();
    if (!ok) {
        return {20.0, 1.0, 1.0};
    }
    double size = 20.0;
    if (auto *bs = dynamic_cast<KisBrushBasedPaintOpSettings *>(preset->settings().data())) {
        size = bs->paintOpSize();
        if (!(size > 0.0) || size != size) {  // NaN / non-positive guard
            size = 20.0;
        }
    }
    const double opacity = preset->settings()->getDouble("OpacityValue", 1.0);
    const double flow = preset->settings()->getDouble("FlowValue", 1.0);
    return {size, opacity, flow};
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
    m_brushFlow = v;
    if (m_brushPreset && m_brushPreset->settings()) {
        m_brushPreset->settings()->setPaintOpFlow(v);
    }
}

void ReverieCore::setBrushSpacing(qreal v)
{
    if (m_brushPreset && m_brushPreset->settings()) {
        KisBrushBasedPaintOpSettings *bs =
            dynamic_cast<KisBrushBasedPaintOpSettings *>(m_brushPreset->settings().data());
        if (bs) {
            bs->setSpacing(v);
        }
    }
}

void ReverieCore::setBrushAngle(qreal v)
{
    if (m_brushPreset && m_brushPreset->settings()) {
        m_brushPreset->settings()->setPaintOpAngle(v);
    }
}

void ReverieCore::setBrushScatter(qreal v)
{
    if (m_brushPreset && m_brushPreset->settings()) {
        m_brushPreset->settings()->setPaintOpScatter(v);
    }
}

void ReverieCore::setBrushFade(qreal v)
{
    if (m_brushPreset && m_brushPreset->settings()) {
        m_brushPreset->settings()->setPaintOpFade(v);
    }
}

void ReverieCore::setBrushSoftness(qreal v)
{
    if (m_brushPreset && m_brushPreset->settings()) {
        m_brushPreset->settings()->setProperty("SoftnessValue", v);
    }
}

void ReverieCore::setBrushRatio(qreal v)
{
    if (m_brushPreset && m_brushPreset->settings()) {
        m_brushPreset->settings()->setProperty("RatioValue", v);
    }
}

void ReverieCore::setBrushSharpness(qreal v)
{
    if (m_brushPreset && m_brushPreset->settings()) {
        m_brushPreset->settings()->setProperty("SharpnessValue", v);
    }
}

void ReverieCore::setBrushRotation(qreal v)
{
    if (m_brushPreset && m_brushPreset->settings()) {
        m_brushPreset->settings()->setProperty("RotationValue", v);
    }
}

void ReverieCore::setBrushCompositeOp(const QString &op)
{
    if (m_brushPreset && m_brushPreset->settings()) {
        m_brushPreset->settings()->setPaintOpCompositeOp(op);
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
    // The stroke paints straight onto the layer device with per-dab opacity
    // (Krita-native); no temporary buffer is used.
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
        endStrokeBatch();
        m_strokeBatchOpen = false;
    }
    // Commit the Krita transaction: the tile snapshots taken at creation
    // are diffed and the undo command is pushed to the store.
    if (m_strokeTxnActive && m_document) {
        m_strokeTxn->commit(m_document->undoAdapter());
        delete m_strokeTxn;
        m_strokeTxn = nullptr;
        m_strokeTxnActive = false;
        m_redoCount = 0;
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
    // deferred Krita transaction is simply discarded: the tile snapshots
    // it recorded are freed and no undo command is pushed, so the partial
    // stroke is gone with no undo history impact.
    delete m_strokeTxn;
    m_strokeTxn = nullptr;
    m_strokeTxnActive = false;
    m_snapshotPending = false;
    m_strokeSamples.clear();
    endStrokeBatch();
    m_strokeBatchOpen = false;
    m_drawing = false;
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
    // Time-throttled flushing: one flush per touch-move saturates the render
    // thread with large brushes (big dabs + big dirty regions). Batch the
    // samples for ~8ms and flush once per batch; touchStrokeEnd always
    // flushes the remainder, so nothing is lost on pen-up.
    const qint64 now = QDateTime::currentMSecsSinceEpoch();
    if (now - m_lastFlushMs >= 8 || m_strokeSamples.size() >= 64) {
        m_lastFlushMs = now;
        flushStrokeBatch();
    }
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
    KisImageSP image = m_document;
    if (!image) {
        m_strokeSamples.clear();
        return;
    }
    const bool erasing = (m_toolMode == ToolEraser);

    // Krita-native: every stroke paints DIRECTLY onto the current layer
    // device. The projection then recomposites in real time, so brush
    // opacity/flow, layer opacity and blend mode are all applied live
    // (matching Krita) instead of only at pen-up via a temporary buffer.
    KisPaintDeviceSP target = currentPaintDevice();
    if (!target) {
        m_strokeSamples.clear();
        return;
    }

    // Krita-style: reuse one KisPainter for the whole stroke.
    if (!m_strokePainter || m_strokeDevice != (void *)target.data()) {
        endStrokeBatch();
        m_strokeDevice = (void *)target.data();
        // Deferred Krita undo: start the stroke transaction here (after the
        // device exists) on the first real flush. Taps and no-paint strokes
        // never reach this point, so they never create an undo command.
        if (m_snapshotPending && !m_strokeTxnActive) {
            delete m_strokeTxn;
            m_strokeTxn = new KisTransaction(
                kundo2_i18n("Stroke"), target);
            m_strokeTxnActive = true;
        }
        m_snapshotPending = false;
        m_strokePainter = new KisPainter(target);
        m_strokePainter->setFillStyle(KisPainter::FillStyleForegroundColor);
        m_strokePainter->setStrokeStyle(KisPainter::StrokeStyleBrush);
        // Eraser presets erase via their CompositeOp parameter (a)_Eraser_*
        // are paintbrush presets with CompositeOp=erase). Apply the preset's
        // effective composite op to the painter so the dab bitBlt actually
        // erases instead of painting over.
        if (m_brushPreset && m_brushPreset->settings()) {
            m_strokePainter->setCompositeOpId(
                m_brushPreset->settings()->effectivePaintOpCompositeOp());
        }
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
            // Create the op through the registry so the preset's own paintop
            // engine is used (paintbrush -> KisBrushOp, experimentbrush ->
            // KisExperimentPaintOp, roundmarker -> KisRoundMarkerOp, ...).
            // Hardcoding KisBrushOp made every preset render as a plain dab.
            m_strokeOp = KisPaintOpRegistry::instance()->paintOp(
                m_brushPreset, m_strokePainter,
                KisNodeSP(m_layers[layerIndex].node), image);
            if (!m_strokeOp) {
                // Fall back to the classic brush op if the engine is missing
                m_strokeOp = new KisBrushOp(m_brushPreset->settings(), m_strokePainter,
                                            KisNodeSP(m_layers[layerIndex].node), image);
            }
            const QPointF start =
                m_strokeSamples.isEmpty() ? m_strokeStartImg : m_strokeSamples.first().imgPos;
            delete m_strokeDistance;
            m_strokeDistance = new KisDistanceInformation(start, 0.0);
        }
    }
    // Re-sync the composite op on every flush so mid-stroke parameter
    // changes (blend-mode dropdown, eraser preset switch) take effect.
    if (m_brushPreset && m_brushPreset->settings()) {
        m_strokePainter->setCompositeOpId(
            m_brushPreset->settings()->effectivePaintOpCompositeOp());
    }
    if (m_selection) {
        m_strokePainter->setSelection(m_selection);
    } else {
        m_strokePainter->setSelection(KisSelectionSP());
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
    // Per-dab opacity (Krita behaviour): the brush op reads the preset's
    // opacity/flow internally; the painter-level opacity covers the fallback
    // dab loop and the eraser.
    painter.setOpacityF(qBound<qreal>(0.0, m_strokeOpacity, 1.0));
    // Composite op: the brush preset's own effective op wins (eraser presets
    // are paintbrush presets carrying CompositeOp=erase), the eraser tool
    // always erases, everything else uses normal. Previously this line
    // unconditionally overwrote the preset's composite op back to 'normal',
    // which is why eraser presets did not erase.
    QString compositeOp;
    if (m_brushPreset && m_brushPreset->settings()) {
        compositeOp = m_brushPreset->settings()->effectivePaintOpCompositeOp();
    }
    if (erasing) {
        compositeOp = QStringLiteral("erase");
    }
    if (compositeOp.isEmpty()) {
        compositeOp = QStringLiteral("normal");
    }
    painter.setCompositeOpId(compositeOp);

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
        // Propagate the tap dot to the projection immediately
        const int tw = int(m_brushSize) + 2;
        const QRect tr(int(p.x()) - tw, int(p.y()) - tw, 2 * tw, 2 * tw);
        target->setDirty(tr);
        markRegionDirty(tr);
        m_strokeSamples.clear();
        return;
    }

    QRect strokeDirty;
    RPC_LOG("RPC flush samples=%d preset=%d op=%d hadMove=%d brushSize=%.1f",
            m_strokeSamples.size(), m_brushPreset != nullptr, m_strokeOp != nullptr,
            m_strokeHadMove, double(m_brushSize));
    if (m_brushPreset && m_strokeOp) {
        // ---- Real Krita brush engine ----
        // Continuous paintLine through the samples (the op interpolates dabs
        // along the path itself, with the real spacing/softness/flow of the
        // preset). The async dab pipeline is driven synchronously: render
        // jobs ran inline via the fake executor at enqueue time, and these
        // update jobs bitBlt the finished dabs onto the target device.
        //
        // Selection constraint: engines like roundmarker/spray/sketch write
        // pixels DIRECTLY to the layer device (KisMarkerPainter and friends),
        // bypassing KisPainter::bitBlt/bltFixed, so painter->setSelection is
        // ignored by them (paintbrush/duplicate go through bltFixed and are
        // constrained natively). For those engines we snapshot the affected
        // box before painting and restore the pixels outside the selection
        // afterwards - the same net effect as a selection-clipped blit.
        const QString opId = m_brushPreset->paintOp().id();
        const bool engineBypassesSelection =
            opId != QLatin1String("paintbrush") && opId != QLatin1String("duplicate");
        QByteArray selClipBefore;
        QRect selClipBox;
        if (m_selection && engineBypassesSelection) {
            for (const StrokeSample &sm : m_strokeSamples) {
                const int w = int(m_brushSize) + 2;
                const QRect r(int(sm.imgPos.x()) - w, int(sm.imgPos.y()) - w,
                              2 * w, 2 * w);
                selClipBox = selClipBox.isNull() ? r : selClipBox.united(r);
            }
            selClipBox &= QRect(0, 0, image->width(), image->height());
            if (!selClipBox.isEmpty()) {
                const int ps = target->pixelSize();
                selClipBefore.resize(selClipBox.width() * selClipBox.height() * ps);
                target->readBytes(reinterpret_cast<quint8 *>(selClipBefore.data()),
                                  selClipBox.x(), selClipBox.y(),
                                  selClipBox.width(), selClipBox.height());
            }
        }
        for (int i = 1; i < m_strokeSamples.size(); ++i) {
            const StrokeSample &a = m_strokeSamples[i - 1];
            const StrokeSample &b = m_strokeSamples[i];
            m_strokeOp->paintLine(KisPaintInformation(a.imgPos, a.pressure),
                                  KisPaintInformation(b.imgPos, b.pressure),
                                  m_strokeDistance);
        }
        QVector<KisRunnableStrokeJobData *> jobs;
        m_strokeOp->doAsynchronousUpdate(jobs);
        RPC_LOG("RPC update jobs=%d first=(%.0f,%.0f) last=(%.0f,%.0f)",
                jobs.size(),
                double(m_strokeSamples.first().imgPos.x()),
                double(m_strokeSamples.first().imgPos.y()),
                double(m_strokeSamples.last().imgPos.x()),
                double(m_strokeSamples.last().imgPos.y()));
        for (auto *j : jobs) {
            j->run();
            delete j;
        }
        // Restore the pixels outside the selection for engines that bypass
        // KisPainter's selection clipping (see above)
        if (m_selection && engineBypassesSelection && !selClipBox.isEmpty() &&
            !selClipBefore.isEmpty()) {
            const int w = selClipBox.width();
            const int h = selClipBox.height();
            const int ps = target->pixelSize();
            QByteArray after;
            after.resize(size_t(w) * h * ps);
            target->readBytes(reinterpret_cast<quint8 *>(after.data()),
                              selClipBox.x(), selClipBox.y(), w, h);
            QByteArray maskB(size_t(w) * h, 0);
            m_selection->pixelSelection()->readBytes(
                reinterpret_cast<quint8 *>(maskB.data()),
                selClipBox.x(), selClipBox.y(), w, h);
            for (int yy = 0; yy < h; ++yy) {
                for (int xx = 0; xx < w; ++xx) {
                    if (maskB[size_t(yy) * w + xx] == 0) {
                        const int o = (yy * w + xx) * ps;
                        for (int k = 0; k < ps; ++k) {
                            after[o + k] = selClipBefore[o + k];
                        }
                    }
                }
            }
            target->writeBytes(reinterpret_cast<const quint8 *>(after.constData()),
                               selClipBox.x(), selClipBox.y(), w, h);
        }
        // Exact dirty propagation: the op's own rendering accumulates dirty
        // rects inside the painter - dab bitBlt for KisBrushOp, and the
        // fillPainterPath bitBlt (whole-path rects) for the special engines
        // like experimentbrush. Using these instead of the brushSize
        // neighbourhood fixes special brushes whose shape covers the whole
        // stroke path (they previously only showed after undo/new-layer,
        // which triggered a full recomposite).
        const QVector<QRect> exactDirty = painter.takeDirtyRegion();
        for (const QRect &r : exactDirty) {
            strokeDirty = strokeDirty.isNull() ? r : strokeDirty.united(r);
        }
        // Conservative fallback: the samples' neighbourhood, only for
        // engines that write the device directly (roundmarker's
        // KisMarkerPainter etc.) and never accumulate dirty rects in the
        // painter - for paintbrush/duplicate the exactDirty above is the
        // true changed area, and adding a 2*brushSize margin around every
        // sample inflates the projection recomposite region for big brushes.
        if (engineBypassesSelection || exactDirty.isEmpty()) {
            for (const StrokeSample &sm : m_strokeSamples) {
                const int w = int(m_brushSize) + 2;
                const QRect r(int(sm.imgPos.x()) - w, int(sm.imgPos.y()) - w,
                              2 * w, 2 * w);
                strokeDirty = strokeDirty.isNull() ? r : strokeDirty.united(r);
            }
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

    // All strokes now paint straight onto the layer: propagate the dirty
    // region so Krita's projection recomposites it immediately.
    if (!strokeDirty.isNull()) {
        target->setDirty(strokeDirty);
        markRegionDirty(strokeDirty);
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
            QImage devImg(ext.size(), QImage::Format_ARGB32_Premultiplied);
            QImage baseImg(ext.size(), QImage::Format_ARGB32_Premultiplied);
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

void ReverieCore::pushUndoCommand(KUndo2Command *cmd)
{
    if (!m_document || !cmd) {
        delete cmd;
        return;
    }
    // KisLegacyUndoAdapter::addCommand routes into our surrogate store
    // (installed via KisImage::setUndoStore); KUndo2Stack::push executes
    // the command's redo() (the change is already applied by the caller,
    // so redo() is a no-op for most commands) and clears redo state.
    m_document->undoAdapter()->addCommand(cmd);
    m_redoCount = 0;
}

bool ReverieCore::canUndo() const
{
    return m_undoStore && m_undoStore->presentCommand() != nullptr;
}

void ReverieCore::undo()
{
    if (!m_undoStore || !m_document || !canUndo()) {
        return;
    }
    m_undoStore->undo();
    ++m_redoCount;
    syncLayersFromImage();
    recompositeProjection();
    markDirty();
    m_snapshotPending = false;
}

void ReverieCore::redo()
{
    if (!m_undoStore || !m_document || !canRedo()) {
        return;
    }
    m_undoStore->redo();
    --m_redoCount;
    syncLayersFromImage();
    recompositeProjection();
    markDirty();
    m_snapshotPending = false;
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
    const qint64 tWfd = QDateTime::currentMSecsSinceEpoch();
    image->waitForDone();
    const qint64 wfdMs = QDateTime::currentMSecsSinceEpoch() - tWfd;
    const qint64 t0 = QDateTime::currentMSecsSinceEpoch();
    qint64 convMs = -1;
    const int iw = image->width();
    const int ih = image->height();
    static int s_lastIw = -1;
    static int s_lastIh = -1;
    if (iw != s_lastIw || ih != s_lastIh) {
        RPC_LOG("RPC renderToBuffer doc=%dx%d buf=%dx%d (size changed %dx%d -> %dx%d)",
                iw, ih, w, h, s_lastIw, s_lastIh, iw, ih);
        s_lastIw = iw;
        s_lastIh = ih;
    }
    // The Android bitmap is the VIEWPORT size (kept at the document aspect
    // ratio), not the full document size. Rendering a 1080x1920 document at
    // 1:1 made every frame a ~35-60ms full-region convertToQImage (measured
    // on desktop; ~3x slower on the phone), which large brushes turn into
    // obvious lag. The dirty document region is composited by Krita, then
    // downscaled once into the viewport buffer.
    if (m_displayImage.isNull() || m_displayImage.size() != QSize(w, h)) {
        // New document (or viewport resize): full redraw
        m_displayImage = QImage(w, h, QImage::Format_RGBA8888);
        m_dirtyRect = QRect(0, 0, iw, ih);
        m_bitmapInited = false;
    }
    const qreal sx = qreal(w) / iw;
    const qreal sy = qreal(h) / ih;

    // Re-composite only the dirty region. Krita's projection recomputes
    // exactly the tiles that changed, so a stroke costs a small convertToQImage
    // instead of a full-document pass every frame.
    m_lastDirty = QRect();
    if (!m_dirtyRect.isEmpty()) {
        const QRect r = m_dirtyRect.intersected(QRect(0, 0, iw, ih));
        if (!r.isEmpty()) {
            const qint64 tConv = QDateTime::currentMSecsSinceEpoch();
            QImage comp;
            {
                // Fast path: an RGB8 projection readBytes returns plain
                // R,G,B,A bytes, which can be wrapped directly into an
                // RGBA8888 QImage - convertToQImage's per-pixel colour
                // conversion costs ~30-40ms for a big dirty region and is
                // the real bottleneck (the projection recomposite itself is
                // <5ms). Fall back to convertToQImage for other colour
                // spaces (16-bit, CMYK, ...).
                KisPaintDeviceSP proj = image->projection();
                const KoColorSpace *pcs = proj->colorSpace();
                if (pcs && pcs->pixelSize() == 4 && pcs->channels().size() == 4) {
                    QByteArray raw;
                    raw.resize(r.width() * r.height() * 4);
                    proj->readBytes(reinterpret_cast<quint8 *>(raw.data()),
                                    r.x(), r.y(), r.width(), r.height());
                    // RGB8 readBytes returns B,G,R,A premultiplied bytes;
                    // wrap as ARGB32_Premultiplied (same memory layout) and
                    // let Qt's SIMD convertToFormat do the unpremultiply +
                    // byte-order fix - far cheaper than Krita's per-pixel
                    // colour conversion, which was the render bottleneck.
                    QImage wrapped(reinterpret_cast<uchar *>(raw.data()),
                                   r.width(), r.height(), r.width() * 4,
                                   QImage::Format_ARGB32_Premultiplied);
                    comp = wrapped.copy().convertToFormat(QImage::Format_RGBA8888);
                } else {
                    comp = image->convertToQImage(r.x(), r.y(), r.width(), r.height(), nullptr);
                }
            }
            convMs = QDateTime::currentMSecsSinceEpoch() - tConv;
            if (!comp.isNull()) {
                const QImage conv = comp.convertToFormat(QImage::Format_RGBA8888);
                const int vw = qMax(1, qRound(r.width() * sx));
                const int vh = qMax(1, qRound(r.height() * sy));
                // Fast downscale for the real-time preview; full resolution is
                // used for PNG export (savePng) and layer thumbnails.
                const QImage scaled = (conv.width() != vw || conv.height() != vh)
                        ? conv.scaled(vw, vh, Qt::IgnoreAspectRatio, Qt::FastTransformation)
                        : conv;
                const QRect vp(qRound(r.x() * sx), qRound(r.y() * sy), scaled.width(), scaled.height());
                const QRect clip = vp.intersected(QRect(0, 0, w, h));
                if (!clip.isEmpty()) {
                    for (int y = clip.top(); y <= clip.bottom(); ++y) {
                        memcpy(m_displayImage.scanLine(y) + clip.left() * 4,
                               scaled.constScanLine(y - vp.y()) + (clip.left() - vp.x()) * 4,
                               size_t(clip.width()) * 4);
                    }
                    m_lastDirty = clip;
                }
            }
        }
        m_dirtyRect = QRect();
    }

    // No separate stroke preview: strokes paint straight onto the layer, so
    // the projection recomposite below already reflects them in real time.

    // Copy into the Android bitmap buffer: full on the first render (or a
    // resize), then only the rows of the region that actually changed.
    if (!m_bitmapInited) {
        memcpy(buffer, m_displayImage.constBits(), size_t(w) * h * 4);
        m_bitmapInited = true;
    } else if (!m_lastDirty.isEmpty()) {
        const QRect r = m_lastDirty.intersected(QRect(0, 0, w, h));
        for (int y = r.top(); y <= r.bottom(); ++y) {
            memcpy(buffer + size_t(y) * w * 4 + size_t(r.left()) * 4,
                   m_displayImage.constScanLine(y) + r.left() * 4,
                   size_t(r.width()) * 4);
        }
    }
    {
        const qint64 elapsed = QDateTime::currentMSecsSinceEpoch() - t0;
        if (elapsed >= 30) {
            RPC_LOG("RPC renderSlow %lldms wfd=%lldms conv=%lldms dirty=%dx%d+%d+%d",
                    elapsed, wfdMs, convMs,
                    m_lastDirty.width(), m_lastDirty.height(),
                    m_lastDirty.x(), m_lastDirty.y());
        }
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
    // Krita-native undo: wrap the fill in a transaction (initial tiles are
    // snapshotted here, before any pixel changes)
    KisTransaction txn(kundo2_i18n("Fill"), device);
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
    QImage layerImg(iw, ih, QImage::Format_ARGB32_Premultiplied);
    {
        QVector<quint8> bytes(size_t(iw) * ih * 4);
        device->readBytes(bytes.data(), 0, 0, iw, ih);
        memcpy(layerImg.bits(), bytes.constData(), size_t(iw) * ih * 4);
    }

    const QRgb seed = layerImg.pixel(qBound(0, x, iw - 1), qBound(0, y, ih - 1));
    const int r0 = qRed(seed), g0 = qGreen(seed), b0 = qBlue(seed);
    const int tol = 24; // color tolerance

    const QRgb fill = qRgba(qColor.red(), qColor.green(), qColor.blue(), 255);

    // Active selection constrains the fill (Krita behaviour): the seed must
    // be inside the selection and only selected pixels are repainted.
    const QByteArray selMask = selectionMask();
    if (!selMask.isEmpty() && !selMask[size_t(y) * iw + x]) {
        return;
    }

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
        if (!selMask.isEmpty() && !selMask[size_t(py) * iw + px]) {
            continue;
        }
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
        txn.commit(image->undoAdapter());
        m_redoCount = 0;
    }
}

QString ReverieCore::pickColorAt(int x, int y, bool currentLayerOnly)
{
    KisImageSP image = m_document ? m_document : KisImageSP();
    if (!image) {
        return QString();
    }
    if (x < 0 || y < 0 || x >= image->width() || y >= image->height()) {
        return QString();
    }
    if (currentLayerOnly) {
        KisPaintDeviceSP dev = currentPaintDevice();
        if (!dev) return QString();
        // Use KoColor so the channel order is handled by the colour space
        // (readBytes returns the space's native byte order, which made the
        // manual hex construction swap R and B on the RGB8 space)
        KoColor c(dev->colorSpace());
        dev->pixel(x, y, &c);
        const QColor qc = c.toQColor();
        if (qc.alpha() == 0) return QString(); // transparent
        return QStringLiteral("#%1%2%3")
                .arg(qc.red(), 2, 16, QLatin1Char('0'))
                .arg(qc.green(), 2, 16, QLatin1Char('0'))
                .arg(qc.blue(), 2, 16, QLatin1Char('0'));
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

// Restore pixels outside the active selection after a QImage-based edit
// (drawShape / drawPolygon / gradientFill / moveLayerContent), so those
// tools are constrained to the selection exactly like Krita. 'edited' is the
// region-sized image, 'original' its pre-edit copy, 'selMask' the full
// document mask, and offsetX/offsetY locate 'edited' inside the document.
static void clipEditToSelection(QImage &edited, const QImage &original,
                                const QByteArray &selMask,
                                int offsetX, int offsetY)
{
    if (selMask.isEmpty() || edited.size() != original.size()) {
        return;
    }
    const int iw = edited.width();
    const int ih = edited.height();
    for (int y = 0; y < ih; ++y) {
        const int my = offsetY + y;
        const int stride = iw + offsetX;  // document width
        const uchar *m = reinterpret_cast<const uchar *>(selMask.constData()) +
                         size_t(my) * stride + offsetX;
        const uchar *src = original.constScanLine(y);
        uchar *dst = edited.scanLine(y);
        for (int x = 0; x < iw; ++x) {
            if (!m[x]) {
                memcpy(dst + x * 4, src + x * 4, 4);
            }
        }
    }
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
    // Krita-native undo: wrap the shape draw in a transaction
    KisTransaction txn(kundo2_i18n("Shape"), device);
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
    QImage layerImg(region.size(), QImage::Format_ARGB32_Premultiplied);
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

    const QImage originalImg = layerImg.copy();  // pre-edit copy for selection clip
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

    if (m_selection) {
        clipEditToSelection(layerImg, originalImg, selectionMask(), region.x(), region.y());
    }
    const qint32 rw = region.width();
    const qint32 rh = region.height();
    device->writeBytes(layerImg.constBits(), region.x(), region.y(), rw, rh);
    device->setDirty();
    markDirty();
    txn.commit(image->undoAdapter());
    m_redoCount = 0;
}

void ReverieCore::drawPolygon(const QVector<QPoint> &points, bool closed)
{
    if (points.size() < 2) {
        return;
    }
    KisImageSP image = m_document ? m_document : KisImageSP();
    if (!image) {
        return;
    }
    KisPaintDeviceSP device = currentPaintDevice();
    if (!device) {
        return;
    }
    // Krita-native undo: wrap the polygon draw in a transaction
    KisTransaction txn(kundo2_i18n("Shape"), device);
    QPainterPath path;
    path.moveTo(points.first());
    for (int i = 1; i < points.size(); ++i) {
        path.lineTo(points[i]);
    }
    if (closed) {
        path.closeSubpath();
    }
    const QRectF bb = path.boundingRect();
    const QRect region =
        bb.toAlignedRect()
            .adjusted(-int(m_brushSize), -int(m_brushSize),
                      int(m_brushSize), int(m_brushSize))
            .intersected(QRect(0, 0, image->width(), image->height()));
    if (region.isEmpty()) {
        return;
    }

    QImage layerImg(region.size(), QImage::Format_ARGB32_Premultiplied);
    {
        QVector<quint8> bytes(size_t(region.width()) * region.height() * 4);
        device->readBytes(bytes.data(), region.x(), region.y(),
                          region.width(), region.height());
        memcpy(layerImg.bits(), bytes.constData(),
               size_t(region.width()) * region.height() * 4);
    }
    QColor qColor(m_brushColor);
    if (!qColor.isValid()) {
        qColor = Qt::black;
    }
    qColor.setAlphaF(qBound<qreal>(0.0, m_brushOpacity, 1.0));
    const QImage originalImg = layerImg.copy();
    QPainter painter(&layerImg);
    painter.setRenderHint(QPainter::Antialiasing, true);
    painter.setCompositionMode(QPainter::CompositionMode_Source);
    QPen pen(qColor, qMax<qreal>(1.0, m_brushSize),
             Qt::SolidLine, Qt::RoundCap, Qt::RoundJoin);
    painter.setPen(pen);
    painter.setBrush(Qt::NoBrush);
    painter.translate(-region.topLeft());
    painter.drawPath(path);
    painter.end();
    if (m_selection) {
        clipEditToSelection(layerImg, originalImg, selectionMask(),
                            region.x(), region.y());
    }
    device->writeBytes(layerImg.constBits(), region.x(), region.y(),
                       region.width(), region.height());
    device->setDirty();
    markDirty();
    txn.commit(image->undoAdapter());
    m_redoCount = 0;
}

void ReverieCore::gradientFill(int x1, int y1, int x2, int y2)
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
    if (QPoint(x1, y1) == QPoint(x2, y2)) {
        return;
    }
    // Krita-native undo: wrap the gradient fill in a transaction
    KisTransaction txn(kundo2_i18n("Gradient"), device);
    QImage layerImg(iw, ih, QImage::Format_ARGB32_Premultiplied);
    {
        QVector<quint8> bytes(size_t(iw) * ih * 4);
        device->readBytes(bytes.data(), 0, 0, iw, ih);
        memcpy(layerImg.bits(), bytes.constData(), size_t(iw) * ih * 4);
    }
    QColor c1(m_brushColor);
    if (!c1.isValid()) {
        c1 = Qt::black;
    }
    QColor c2(m_brushSecondaryColor);
    if (!c2.isValid()) {
        c2 = Qt::transparent;
    }
    c1.setAlphaF(qBound<qreal>(0.0, m_brushOpacity, 1.0));
    c2.setAlphaF(qBound<qreal>(0.0, m_brushOpacity, 1.0));
    const QImage originalImg = layerImg.copy();
    QPainter painter(&layerImg);
    painter.setCompositionMode(QPainter::CompositionMode_Source);
    QLinearGradient grad(x1, y1, x2, y2);
    grad.setColorAt(0.0, c1);
    grad.setColorAt(1.0, c2);
    painter.fillRect(0, 0, iw, ih, grad);
    painter.end();
    if (m_selection) {
        clipEditToSelection(layerImg, originalImg, selectionMask(), 0, 0);
    }
    device->writeBytes(layerImg.constBits(), 0, 0, iw, ih);
    device->setDirty();
    markDirty();
    txn.commit(image->undoAdapter());
    m_redoCount = 0;
}

void ReverieCore::selectShape(int kind, int x1, int y1, int x2, int y2)
{
    KisImageSP image = m_document;
    if (!image) {
        return;
    }
    const int iw = image->width();
    const int ih = image->height();
    const KisSelectionSP oldSel = m_selection;
    const QVector<quint8> oldMask = readSelectionMaskBytes(image, oldSel);
    const int rx = qBound(0, qMin(x1, x2), iw - 1);
    const int ry = qBound(0, qMin(y1, y2), ih - 1);
    const int rw = qBound(1, qAbs(x2 - x1), iw - rx);
    const int rh = qBound(1, qAbs(y2 - y1), ih - ry);

    QVector<quint8> mask(size_t(iw) * ih, 0);
    if (kind == 1) {
        // Ellipse
        const double cx = rx + rw / 2.0;
        const double cy = ry + rh / 2.0;
        const double a = rw / 2.0;
        const double b = rh / 2.0;
        if (a > 0 && b > 0) {
            for (int y = ry; y < ry + rh; ++y) {
                for (int x = rx; x < rx + rw; ++x) {
                    const double dx = (x - cx) / a;
                    const double dy = (y - cy) / b;
                    if (dx * dx + dy * dy <= 1.0) {
                        mask[size_t(y) * iw + x] = 255;
                    }
                }
            }
        }
    } else {
        // Rect
        for (int y = ry; y < ry + rh; ++y) {
            memset(&mask[size_t(y) * iw + rx], 255, size_t(rw));
        }
    }
    setSelectionFromMask(this, image, mask, int(m_selectionMode));
    pushUndoCommand(new ReverieSelectionCommand(this, oldSel, oldMask, iw, ih, m_selection));
    markDirty();
}

void ReverieCore::selectPolygon(const QVector<QPoint> &points)
{
    lassoSelect(points);
}

static int colorDistance(const QRgb &a, const QRgb &b)
{
    const int dr = qRed(a) - qRed(b);
    const int dg = qGreen(a) - qGreen(b);
    const int db = qBlue(a) - qBlue(b);
    return dr * dr + dg * dg + db * db;
}

// Build a selection from a boolean mask (BFS / global scan results).
// Install a new selection from a mask, honouring the current merge mode
static void setSelectionFromMask(ReverieCore *core, const KisImageSP &image,
                                 const QVector<quint8> &mask,
                                 int selMode)
{
    RPC_LOG("RPC setSelectionFromMask mode=%d maskPixels=%d", selMode, mask.size());
    QVector<quint8> finalMask = mask;
    if (selMode != ReverieCore::SelReplace && core->hasSelection()) {
        QVector<quint8> existing(size_t(image->width()) * image->height(), 0);
        KisPixelSelectionSP ps = core->currentSelectionPixelSelection();
        if (ps) {
            ps->readBytes(existing.data(), 0, 0, image->width(), image->height());
        }
        finalMask = combineSelectionMasks(existing, mask, selMode);
    }
    core->setSelection(selectionFromMask(image, finalMask));
}

static KisSelectionSP selectionFromMask(const KisImageSP &image,
                                        const QVector<quint8> &mask)
{
    KisSelectionSP sel = new KisSelection(
        new KisSelectionDefaultBounds(image->projection()),
        toQShared(new KisImageResolutionProxy(image)));
    KisPixelSelectionSP ps = sel->pixelSelection();
    const int iw = image->width();
    const int ih = image->height();
    // Compact the mask into per-row spans to avoid per-pixel setPixel
    for (int y = 0; y < ih; ++y) {
        int x = 0;
        while (x < iw) {
            if (mask[size_t(y) * iw + x]) {
                int x0 = x;
                while (x < iw && mask[size_t(y) * iw + x]) {
                    ++x;
                }
                ps->select(QRect(x0, y, x - x0, 1), OPACITY_OPAQUE_U8);
            } else {
                ++x;
            }
        }
    }
    return sel;
}

void ReverieCore::selectContiguousAt(int x, int y, int tolerance)
{
    KisImageSP image = m_document;
    if (!image) {
        return;
    }
    const int iw = image->width();
    const int ih = image->height();
    const KisSelectionSP oldSel = m_selection;
    const QVector<quint8> oldMask = readSelectionMaskBytes(image, oldSel);
    if (x < 0 || y < 0 || x >= iw || y >= ih) {
        return;
    }
    // Krita magic-wand semantics: match against the visible composite
    // (projection), not a single layer's raw pixels
    KisPaintDeviceSP device = image->projection();
    image->waitForDone();
    // Lazy row loading: a magic-wand BFS only visits the connected region, so
    // a full-document projection read is wasteful (it also forces the whole
    // projection to be recomposited). Rows are read on demand and cached -
    // the equivalent of Krita reading only the tile the stroke touches
    QVector<quint8> bytes(size_t(iw) * ih * 4, 0);
    QVector<quint8> rowReady(size_t(ih), 0);
    auto ensureRow = [&](int ry) {
        if (ry < 0 || ry >= ih || rowReady[size_t(ry)]) {
            return;
        }
        rowReady[size_t(ry)] = 1;
        device->readBytes(bytes.data() + size_t(ry) * iw * 4, 0, ry, iw, 1);
    };
    ensureRow(y);
    const int tolSq = tolerance * tolerance;

    // Direct byte access (readBytes is B,G,R,A for the RGB8 space): avoids
    // per-pixel qRgba/colorDistance call overhead on the BFS
    const int sR = bytes[size_t(y * iw + x) * 4 + 2];
    const int sG = bytes[size_t(y * iw + x) * 4 + 1];
    const int sB = bytes[size_t(y * iw + x) * 4];

    // BFS flood fill with color tolerance (Krita's contiguous selection)
    QVector<quint8> mask(size_t(iw) * ih, 0);
    QVector<int> queue;
    queue.reserve(iw * ih / 4);
    const int start = y * iw + x;
    mask[start] = 255;
    queue.append(start);
    size_t head = 0;
    while (head < queue.size()) {
        const int cur = queue[head++];
        const int cx = cur % iw;
        const int cy = cur / iw;
        ensureRow(cy);
        if (cx > 0) {
            const int n = cur - 1;
            if (!mask[n]) {
                const int o = (n * 4);
                const int dr = bytes[o + 2] - sR, dg = bytes[o + 1] - sG, db = bytes[o] - sB;
                if (dr * dr + dg * dg + db * db <= tolSq) { mask[n] = 255; queue.append(n); }
            }
        }
        if (cx + 1 < iw) {
            const int n = cur + 1;
            if (!mask[n]) {
                const int o = (n * 4);
                const int dr = bytes[o + 2] - sR, dg = bytes[o + 1] - sG, db = bytes[o] - sB;
                if (dr * dr + dg * dg + db * db <= tolSq) { mask[n] = 255; queue.append(n); }
            }
        }
        if (cy > 0) {
            const int n = cur - iw;
            if (!mask[n]) {
                const int o = (n * 4);
                const int dr = bytes[o + 2] - sR, dg = bytes[o + 1] - sG, db = bytes[o] - sB;
                if (dr * dr + dg * dg + db * db <= tolSq) { mask[n] = 255; queue.append(n); }
            }
        }
        if (cy + 1 < ih) {
            const int n = cur + iw;
            if (!mask[n]) {
                const int o = (n * 4);
                const int dr = bytes[o + 2] - sR, dg = bytes[o + 1] - sG, db = bytes[o] - sB;
                if (dr * dr + dg * dg + db * db <= tolSq) { mask[n] = 255; queue.append(n); }
            }
        }
    }
    setSelectionFromMask(this, image, mask, int(m_selectionMode));
    pushUndoCommand(new ReverieSelectionCommand(this, oldSel, oldMask, iw, ih, m_selection));
    markDirty();
}

void ReverieCore::selectSimilarAt(int x, int y, int tolerance)
{
    KisImageSP image = m_document;
    if (!image) {
        return;
    }
    const int iw = image->width();
    const int ih = image->height();
    const KisSelectionSP oldSel = m_selection;
    const QVector<quint8> oldMask = readSelectionMaskBytes(image, oldSel);
    if (x < 0 || y < 0 || x >= iw || y >= ih) {
        return;
    }
    // Krita similar-color semantics: match against the visible composite
    KisPaintDeviceSP device = image->projection();
    image->waitForDone();
    QVector<quint8> bytes(size_t(iw) * ih * 4);
    device->readBytes(bytes.data(), 0, 0, iw, ih);
    const int tolSq = tolerance * tolerance;
    const int o0 = (y * iw + x) * 4;
    const int sR = bytes[o0 + 2];
    const int sG = bytes[o0 + 1];
    const int sB = bytes[o0];

    // Global scan: every pixel whose color is within tolerance (Krita's
    // similar-color selection, not connected-region limited). Direct byte
    // access avoids per-pixel qRgba/colorDistance overhead.
    QVector<quint8> mask(size_t(iw) * ih, 0);
    const size_t nPix = size_t(iw) * ih;
    for (size_t i = 0; i < nPix; ++i) {
        const int o = int(i * 4);
        const int dr = bytes[o + 2] - sR, dg = bytes[o + 1] - sG, db = bytes[o] - sB;
        if (dr * dr + dg * dg + db * db <= tolSq) {
            mask[i] = 255;
        }
    }
    setSelectionFromMask(this, image, mask, int(m_selectionMode));
    pushUndoCommand(new ReverieSelectionCommand(this, oldSel, oldMask, iw, ih, m_selection));
    markDirty();
}

void ReverieCore::moveLayerContent(int dx, int dy)
{
    KisImageSP image = m_document ? m_document : KisImageSP();
    if (!image || (dx == 0 && dy == 0)) {
        return;
    }
    KisPaintDeviceSP device = currentPaintDevice();
    if (!device) {
        return;
    }
    const int iw = image->width();
    const int ih = image->height();
    // Krita-native undo: wrap the content move in a transaction
    KisTransaction txn(kundo2_i18n("Move Content"), device);
    QImage src(iw, ih, QImage::Format_ARGB32_Premultiplied);
    {
        QVector<quint8> bytes(size_t(iw) * ih * 4);
        device->readBytes(bytes.data(), 0, 0, iw, ih);
        memcpy(src.bits(), bytes.constData(), size_t(iw) * ih * 4);
    }
    QImage out(iw, ih, QImage::Format_ARGB32_Premultiplied);
    out.fill(0);
    QPainter p(&out);
    p.setCompositionMode(QPainter::CompositionMode_Source);
    p.drawImage(dx, dy, src);
    p.end();
    if (m_selection) {
        clipEditToSelection(out, src, selectionMask(), 0, 0);
    }
    device->writeBytes(out.constBits(), 0, 0, iw, ih);
    device->setDirty();
    markDirty();
    txn.commit(image->undoAdapter());
    m_redoCount = 0;
}

void ReverieCore::cropCanvas(int x, int y, int w, int h)
{
    KisImageSP image = m_document;
    if (!image || w <= 0 || h <= 0) {
        return;
    }
    const QRect crop(qMax(0, x), qMax(0, y), w, h);
    image->resizeImage(crop);
    syncLayersFromImage();
    recompositeProjection();
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
    // Krita-native undo: wrap the text draw in a transaction
    KisTransaction txn(kundo2_i18n("Text"), device);
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

    QImage layerImg(region.size(), QImage::Format_ARGB32_Premultiplied);
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
    txn.commit(image->undoAdapter());
    m_redoCount = 0;
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

QVector<QPoint> ReverieCore::magneticLasso(const QPoint &from, const QPoint &to, int radius)
{
    KisImageSP image = m_document;
    if (!image) {
        return {};
    }
    const int iw = image->width();
    const int ih = image->height();
    const int r = qMax(2, radius);
    // Search region: bounding box of both points plus a margin, clipped to
    // the document (the magnetic path only ever lives inside the canvas)
    QRect region(QPoint(qMin(from.x(), to.x()), qMin(from.y(), to.y())),
                 QPoint(qMax(from.x(), to.x()), qMax(from.y(), to.y())));
    region = region.adjusted(-r, -r, r, r).intersected(QRect(0, 0, iw, ih));
    const int rw = region.width();
    const int rh = region.height();
    if (rw < 3 || rh < 3) {
        return {from, to};
    }

    // Composite the current projection so the edges match what the user sees
    image->waitForDone();
    KisPaintDeviceSP proj = image->projection();
    QVector<quint8> bytes(size_t(rw) * rh * 4);
    proj->readBytes(bytes.data(), region.x(), region.y(), rw, rh);

    // Luminance (readBytes returns B,G,R,A for the RGB8 space)
    QVector<quint8> gray(size_t(rw) * rh);
    for (int i = 0; i < rw * rh; ++i) {
        const quint8 b = bytes[size_t(i) * 4];
        const quint8 g = bytes[size_t(i) * 4 + 1];
        const quint8 r = bytes[size_t(i) * 4 + 2];
        gray[i] = quint8((int(r) * 299 + int(g) * 587 + int(b) * 114) / 1000);
    }
    // Sobel magnitude edge map (stand-in for Krita's LoG intensity)
    QVector<quint8> edge(size_t(rw) * rh, 0);
    int gmax = 1;
    for (int y = 1; y < rh - 1; ++y) {
        for (int x = 1; x < rw - 1; ++x) {
            const int i00 = (y - 1) * rw + x - 1, i01 = (y - 1) * rw + x, i02 = (y - 1) * rw + x + 1;
            const int i10 = y * rw + x - 1, i12 = y * rw + x + 1;
            const int i20 = (y + 1) * rw + x - 1, i21 = (y + 1) * rw + x, i22 = (y + 1) * rw + x + 1;
            const int gx = -gray[i00] - 2 * gray[i10] - gray[i20] + gray[i02] + 2 * gray[i12] + gray[i22];
            const int gy = -gray[i00] - 2 * gray[i01] - gray[i02] + gray[i20] + 2 * gray[i21] + gray[i22];
            const int mag = qMin(255, int(std::sqrt(double(gx * gx + gy * gy)) * 0.35));
            edge[y * rw + x] = quint8(mag);
            if (mag > gmax) {
                gmax = mag;
            }
        }
    }
    if (gmax > 1) {
        for (int i = 0; i < rw * rh; ++i) {
            edge[i] = quint8(int(edge[i]) * 255 / gmax);
        }
    }
    // Widen the edge band with a 3x3 maximum filter so the path snaps onto
    // edges even when the finger drifts a couple of pixels off them
    QVector<quint8> edgeDil = edge;
    for (int y = 1; y < rh - 1; ++y) {
        for (int x = 1; x < rw - 1; ++x) {
            quint8 mx = 0;
            for (int dy = -1; dy <= 1; ++dy) {
                for (int dx = -1; dx <= 1; ++dx) {
                    mx = qMax(mx, edge[(y + dy) * rw + x + dx]);
                }
            }
            edgeDil[y * rw + x] = mx;
        }
    }
    edge = edgeDil;

    // Snap the endpoints onto the nearest strong edge so the path hugs the
    // edge band instead of wandering through uniform areas (Krita's worker
    // effectively does the same: its graph only covers filtered edge tiles)
    const auto snapToEdge = [&](QPoint pt) -> QPoint {
        QPoint best = pt;
        int bestD = std::numeric_limits<int>::max();
        const int px = pt.x() - region.x();
        const int py = pt.y() - region.y();
        for (int dy = -r; dy <= r; ++dy) {
            for (int dx = -r; dx <= r; ++dx) {
                const int x = px + dx;
                const int y = py + dy;
                if (x < 0 || y < 0 || x >= rw || y >= rh) {
                    continue;
                }
                if (edge[y * rw + x] > 128) {
                    const int d = dx * dx + dy * dy;
                    if (d < bestD) {
                        bestD = d;
                        best = QPoint(x + region.x(), y + region.y());
                    }
                }
            }
        }
        return best;
    };
    const QPoint fromSnap = snapToEdge(from);
    const QPoint toSnap = snapToEdge(to);
    const QPoint start(fromSnap - region.topLeft());
    const QPoint goal(toSnap - region.topLeft());
    const int sIdx = start.y() * rw + start.x();
    const int gIdx = goal.y() * rw + goal.x();
    if (sIdx < 0 || gIdx < 0 || sIdx >= rw * rh || gIdx >= rw * rh) {
        return {from, to};
    }

    // Dijkstra over the 8-neighbour graph; edge weight follows Krita:
    // euclidean step + (255 - average edge intensity) so strong edges are
    // cheap and the path snaps to them
    const auto idx = [rw](int x, int y) { return y * rw + x; };
    QVector<double> gScore(size_t(rw) * rh, std::numeric_limits<double>::max());
    QVector<int> came(size_t(rw) * rh, -1);
    typedef std::pair<double, int> QP;
    std::priority_queue<QP, std::vector<QP>, std::greater<QP>> open;
    gScore[sIdx] = 0.0;
    open.push({0.0, sIdx});
    static const int dx8[8] = {-1, 0, 1, -1, 1, -1, 0, 1};
    static const int dy8[8] = {-1, -1, -1, 0, 0, 1, 1, 1};
    while (!open.empty()) {
        const double f = open.top().first;
        const int cur = open.top().second;
        open.pop();
        if (cur == gIdx) {
            break;
        }
        if (f > gScore[cur]) {
            continue;
        }
        const int cx = cur % rw;
        const int cy = cur / rw;
        for (int d = 0; d < 8; ++d) {
            const int nx = cx + dx8[d];
            const int ny = cy + dy8[d];
            if (nx < 0 || ny < 0 || nx >= rw || ny >= rh) {
                continue;
            }
            const int n = idx(nx, ny);
            const qreal w = std::sqrt(double(dx8[d] * dx8[d] + dy8[d] * dy8[d])) +
                            255.0 - (edge[cur] + edge[n]) / 2.0;
            const double ng = gScore[cur] + w;
            if (ng < gScore[n]) {
                gScore[n] = ng;
                came[n] = cur;
                open.push({ng, n});
            }
        }
    }
    // Rebuild the path back from the goal
    QVector<QPoint> path;
    int cur = gIdx;
    while (cur != -1) {
        path.push_front(QPoint(cur % rw + region.x(), cur / rw + region.y()));
        if (cur == sIdx) {
            break;
        }
        cur = came[cur];
    }
    if (path.isEmpty()) {
        return {from, to};
    }
    // Keep the original finger position as the segment start (the snapped
    // endpoint is inside the path); the next segment starts there too
    if (fromSnap != from) {
        path.prepend(from);
    }
    if (toSnap != to && !path.isEmpty() && path.last() != to) {
        path.append(to);
    }
    return path;
}

void ReverieCore::lassoSelect(const QVector<QPoint> &points)
{
    RPC_LOG("RPC lassoSelect points=%d", points.size());
    KisImageSP image = m_document;
    if (!image || points.size() < 3) {
        return;
    }
    const int iw = image->width();
    const int ih = image->height();
    const KisSelectionSP oldSel = m_selection;
    const QVector<quint8> oldMask = readSelectionMaskBytes(image, oldSel);
    QVector<bool> mask;
    scanlineFillPolygon(points, iw, ih, mask);
    QVector<quint8> selMask(size_t(iw) * ih, 0);
    for (int y = 0; y < ih; ++y) {
        for (int x = 0; x < iw; ++x) {
            if (mask[size_t(y) * iw + x]) {
                selMask[size_t(y) * iw + x] = 255;
            }
        }
    }
    setSelectionFromMask(this, image, selMask, int(m_selectionMode));
    pushUndoCommand(new ReverieSelectionCommand(this, oldSel, oldMask, iw, ih, m_selection));
    markDirty();
}

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

    QImage layerImg(iw, ih, QImage::Format_ARGB32_Premultiplied);
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

    QImage layerImg(iw, ih, QImage::Format_ARGB32_Premultiplied);
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
    // Krita-native undo: wrap the liquify displacement in a transaction
    KisTransaction txn(kundo2_i18n("Liquify"), device);

    QImage layerImg(iw, ih, QImage::Format_ARGB32_Premultiplied);
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
    txn.commit(image->undoAdapter());
    m_redoCount = 0;
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
    // Bulk blit: the layer device is RGB8 (KoBgrU8Traits, BGRA premultiplied
    // memory layout), which matches QImage::Format_ARGB32_Premultiplied
    // exactly - one writeBytes instead of a per-pixel setPixel loop (the
    // old loop took seconds on a phone for a 1080x1920 project)
    const QImage conv = img.convertToFormat(QImage::Format_ARGB32_Premultiplied);
    const int iw = conv.width();
    const int ih = conv.height();
    QVector<quint8> bytes(size_t(iw) * ih * 4);
    memcpy(bytes.data(), conv.constBits(), size_t(iw) * ih * 4);
    dev->writeBytes(reinterpret_cast<const quint8 *>(bytes.constData()), 0, 0, iw, ih);
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
        // Empty layer: return a transparent thumbnail so the cache refreshes.
        // Returning false here left stale index-keyed entries from before the
        // layer was cleared, showing old content on a blank layer.
        QImage out(w, h, QImage::Format_RGBA8888);
        out.fill(Qt::transparent);
        const int copyH = qMin(h, out.height());
        for (int y = 0; y < copyH; ++y) {
            memcpy(static_cast<char *>(dstPixels) + size_t(y) * dstStride,
                   out.constScanLine(y), size_t(w) * 4);
        }
        return true;
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
