/* ============================================================
 * ReverieCoreDocument.cpp - Document lifecycle: create/open/resize/close, doc metrics and display pipeline
 * (part of the ReverieCore module split; shared helpers live in
 * ReverieCoreInternal.h, public API in ReverieCore.h)
 * ============================================================ */
#include "ReverieCoreInternal.h"

#include <future>
#include <QSet>

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

    // Release any previous document. KisImage destructor frees its owned undo store,
    // so m_undoStore must be reset to nullptr to prevent dangling pointer access.
    m_document.clear();
    m_undoStore = nullptr;

    // Reset the display pipeline: a new document (possibly same size as the
    // previous one) must not inherit stale display pixels or skip the first
    // full bitmap copy.
    m_renderBufW = -1;
    m_renderBufH = -1;
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
    // Krita-native undo store: fresh instance per document (owned by KisImage).
    delete m_strokeTxn;
    m_strokeTxn = nullptr;
    m_strokeTxnActive = false;
    m_undoStore = new KisSurrogateUndoStore();
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

    image->setDefaultProjectionColor(KoColor(Qt::white, cs));

    // Background layer (transparent, locked): index 0, controls projection background
    KisPaintLayerSP bg = new KisPaintLayer(image, QStringLiteral("背景"), 255, cs);
    if (!bg) {
        return false;
    }
    bg->original()->fill(QRect(0, 0, width, height), KoColor(Qt::transparent, cs));
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

void ReverieCore::setBackgroundColor(quint32 color, bool commit)
{
    KisImageSP image = m_document;
    if (!image || m_layers.isEmpty()) return;
    KisPaintDeviceSP dev = layerPaintDeviceFor(m_layers[0]);
    if (!dev) return;
    const KoColorSpace *cs = image->colorSpace();
    QColor qc = QColor::fromRgba(color);
    KoColor koColor(qc, cs);

    if (commit) {
        KisTransaction txn(kundo2_i18n("Change Background Color"), dev);
        dev->fill(QRect(0, 0, image->width(), image->height()), koColor);
        dev->setDirty();
        txn.commit(image->undoAdapter());
    } else {
        dev->fill(QRect(0, 0, image->width(), image->height()), koColor);
        dev->setDirty();
    }
    recompositeProjection();
    markDirty();
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
    // Purge any stale nodes that no longer exist in the document
    QSet<KisNode *> currentNodes;
    for (const auto &e : m_layers) {
        if (e.node) currentNodes.insert(e.node);
    }
    for (auto it = m_nodeFilters.begin(); it != m_nodeFilters.end(); ) {
        if (!currentNodes.contains(it.key())) {
            it = m_nodeFilters.erase(it);
        } else {
            ++it;
        }
    }
    // Update any active filter layers from underlying composite
    for (int i = 0; i < m_layers.size(); ++i) {
        KisNode *node = m_layers[i].node;
        if (!node) continue;
        if (m_nodeFilters.contains(node) && m_nodeFilters[node].hasFilter && m_filterBackupIndex != i) {
            const auto &cfg = m_nodeFilters[node];
            KisPaintDeviceSP dev = layerPaintDeviceFor(m_layers[i]);
            if (dev) {
                const int w = m_docWidth;
                const int h = m_docHeight;
                const QRect full(0, 0, w, h);
                KisPaintDeviceSP srcDev(new KisPaintDevice(image->colorSpace()));
                srcDev->fill(full, KoColor(Qt::transparent, image->colorSpace()));
                compositeRange(srcDev, 0, i, full);

                QImage img(w, h, QImage::Format_ARGB32_Premultiplied);
                srcDev->readBytes(img.bits(), 0, 0, w, h);

                if (cfg.filterType == 13 && cfg.lut.size() >= 768) {
                    const quint8 *r = reinterpret_cast<const quint8 *>(cfg.lut.constData());
                    const quint8 *g = r + 256;
                    const quint8 *b = g + 256;
                    filterParallelFor(0, h, [&](int startY, int endY) {
                        for (int y = startY; y < endY; ++y) {
                            quint8 *line = img.scanLine(y);
                            for (int x = 0; x < w; ++x) {
                                quint8 *px = line + x * 4;
                                if (px[3] == 0) continue;
                                px[2] = r[px[2]];
                                px[1] = g[px[1]];
                                px[0] = b[px[0]];
                            }
                        }
                    });
                } else if (cfg.filterType == 30 && cfg.lut.size() >= int(256 * sizeof(quint32))) {
                    const quint32 *gLut = reinterpret_cast<const quint32 *>(cfg.lut.constData());
                    filterParallelFor(0, h, [&](int startY, int endY) {
                        for (int y = startY; y < endY; ++y) {
                            quint8 *line = img.scanLine(y);
                            for (int x = 0; x < w; ++x) {
                                quint8 *px = line + x * 4;
                                if (px[3] == 0) continue;
                                int lum = (px[2] * 299 + px[1] * 587 + px[0] * 114) / 1000;
                                quint32 gCol = gLut[qBound(0, lum, 255)];
                                int gr = (gCol >> 16) & 0xFF;
                                int gg = (gCol >> 8) & 0xFF;
                                int gb = gCol & 0xFF;
                                int ga = (gCol >> 24) & 0xFF;
                                px[2] = quint8(gr);
                                px[1] = quint8(gg);
                                px[0] = quint8(gb);
                                px[3] = quint8((px[3] * ga) / 255);
                            }
                        }
                    });
                } else if (cfg.filterType >= 0) {
                    processFilterImage(cfg.filterType, cfg.p1, cfg.p2, cfg.p3, cfg.p4, img, w, h);
                }

                dev->writeBytes(img.constBits(), 0, 0, w, h);
                dev->setDirty(full);
            }
        }
    }
    image->refreshGraphAsync();
    image->waitForDone();
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
            if (KisLayer *l = dynamic_cast<KisLayer *>(node.data())) {
                LayerEntry entry;
                entry.node = node.data();
                entry.visible = l->visible();
                entry.name = l->name();
                entry.depth = depth;
                entry.isGroup = isGroup;
                entry.locked = l->userLocked();
                if (KisPaintLayer *pl = dynamic_cast<KisPaintLayer *>(l)) {
                    entry.alphaLocked = pl->alphaLocked();
                } else {
                    entry.alphaLocked = false;
                }
                entry.colorLabel = l->colorLabelIndex();
                entry.clipped = l->alphaChannelDisabled();
                entry.background = m_layers.isEmpty();  // first layer = bg
                m_layers.append(entry);
            } else if (KisMask *m = dynamic_cast<KisMask *>(node.data())) {
                LayerEntry entry;
                entry.node = node.data();
                entry.visible = m->visible();
                entry.name = m->name();
                entry.depth = depth;
                entry.isGroup = false;
                entry.locked = m->userLocked();
                entry.alphaLocked = false;
                entry.colorLabel = m->colorLabelIndex();
                entry.clipped = false;
                entry.background = false;
                m_layers.append(entry);
            }
            if (node->childCount() > 0) {
                walk(node, depth + 1);
            }
            node = node->nextSibling();
        }
    };
    walk(root, 0);
    // Prune thumbnail cache entries whose layer node is gone (deleted or
    // replaced layers must not leak the tiny cached thumbs or serve stale
    // pixels to a recycled index)
    QSet<KisNode *> liveNodes;
    for (const LayerEntry &e : m_layers) {
        liveNodes.insert(e.node);
    }
    for (auto it = m_thumbCache.begin(); it != m_thumbCache.end();) {
        if (!liveNodes.contains(it.key())) {
            it = m_thumbCache.erase(it);
        } else {
            ++it;
        }
    }
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
    // Solo mode is a node-keyed render filter: if the soloed node was removed
    // by this resync, close solo; otherwise refresh the keep set so layer
    // add/remove/move cannot desync the composite
    if (m_soloedNode) {
        if (soloedIndex() < 0) {
            restoreSolo();
        } else {
            computeSoloKeep();
        }
    }
}

void ReverieCore::bumpLayerThumbGen(KisNode *node)
{
    KisNode *n = node;
    while (n) {
        auto it = m_thumbCache.find(n);
        if (it != m_thumbCache.end()) {
            ++it->gen;
        }
        KisNodeSP p = n->parent();
        n = p ? p.data() : nullptr;
    }
}

