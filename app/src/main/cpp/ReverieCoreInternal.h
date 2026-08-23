/*
 * ReverieCoreInternal - shared file-scope helpers for the ReverieCore split
 * implementation files.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
#ifndef REVERIECORE_INTERNAL_H
#define REVERIECORE_INTERNAL_H

#include "ReverieCore.h"

#include <QDir>
#include <QFile>
#include <QBuffer>
#include <QElapsedTimer>
#include <QJsonDocument>
#include <QJsonObject>
#include <QJsonArray>
#include <QDateTime>
#include <KoStore.h>
#include <KoStoreDevice.h>
#include <psd.h>
#include <psd_header.h>
#include <psd_utils.h>
#include <psd_resource_block.h>
#include <psd_resource_section.h>
#include <psd_layer_section.h>
#include <psd_colormode_block.h>
#include <psd_image_data.h>
#include <psd_saver.h>

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
#define RPC_LOG(...) do { fprintf(stderr, __VA_ARGS__); fflush(stderr); } while (0)
#endif
#include <algorithm>
#include <queue>
#include <kis_clone_layer.h>
#include <kis_transparency_mask.h>
#include <kis_filter_mask.h>
#include <kis_transform_mask.h>
#include <kis_selection_mask.h>
#include <kis_adjustment_layer.h>
#include <generator/kis_generator_layer.h>
#include <kis_fill_painter.h>
#include <kis_gradient_painter.h>
#include <kis_transform_worker.h>
#include <kis_warptransform_worker.h>
#include <kis_perspectivetransform_worker.h>
#include <kis_default_bounds.h>
#include <KoColor.h>
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
#include <kis_transform_worker.h>
#include <kis_filter_strategy.h>
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
#include <KoCompositeOpRegistry.h>
#include <kis_paint_layer.h>
#include <kis_group_layer.h>
#include <kis_layer.h>
#include <kis_node.h>
#include <KisInterstrokeDataTransactionWrapperFactory.h>
#include <KisInterstrokeDataFactory.h>
#include <KisInterstrokeData.h>
#include <KoBgrColorSpaceTraits.h>
#include <compositeops/KoCompositeOps.h>
#include <QThread>

// One undo step wrapping several per-device KisTransaction children (taken
// via KisTransaction::endAndTake). Krita's undo adapter pushes every
// addCommand separately, so multi-device edits need an explicit composite
// to stay a single undo step.
class ReverieCompositeCommand : public KUndo2Command
{
public:
    ReverieCompositeCommand(const KUndo2MagicString &text, const QVector<KUndo2Command *> &children)
        : KUndo2Command(text)
        , m_children(children)
    {
    }
    ~ReverieCompositeCommand() override { qDeleteAll(m_children); }
    void redo() override
    {
        for (KUndo2Command *c : m_children) c->redo();
    }
    void undo() override
    {
        for (int i = m_children.size() - 1; i >= 0; --i) m_children[i]->undo();
    }

private:
    QVector<KUndo2Command *> m_children;
};

class ReverieNodeVisibleCommand : public KUndo2Command
{public:
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
static inline QVector<quint8> readSelectionMaskBytes(const KisImageSP &image,
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
static inline QRect growSelectionUniformLocal(KisPixelSelectionSP selection, int growSize, const QRect &applyRect)
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

static inline void applyGaussianLocal(KisPixelSelectionSP selection, const QRect &applyRect, qreal radius)
{
    KisGaussianKernel::applyGaussian(selection, applyRect, radius, radius,
                                     QBitArray(), 0, true, BORDER_IGNORE);
}

static KisSelectionSP selectionFromMask(const KisImageSP &image,
                                        const QVector<quint8> &mask,
                                        bool fullSelect = false);
static void setSelectionFromMask(ReverieCore *core, const KisImageSP &image,
                                 const QVector<quint8> &mask, int selMode);

// Morphological dilation: selected pixels spread out by 'radius' pixels
static inline void dilateMask(QVector<quint8> &mask, int w, int h, int radius)
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
static inline void erodeMask(QVector<quint8> &mask, int w, int h, int radius)
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

// Chamfer 3-4 distance transform: dist[y][x] = min chamfer distance to any
// pixel where src[i]==255. O(w*h) two-pass; chamfer 3-4 approximates the
// Euclidean distance Krita's KisGrowSelectionFilter uses (its circular
// mask comes from computeBorder's sqrt formula)
static inline void chamferDist(const QVector<quint8> &src, QVector<qint32> &dist, int w, int h)
{
    const qint32 INF = 1 << 28;
    dist.fill(INF);
    for (size_t i = 0; i < src.size(); ++i) {
        if (src[i]) {
            dist[i] = 0;
        }
    }
    for (int y = 0; y < h; ++y) {
        for (int x = 0; x < w; ++x) {
            qint32 d = dist[size_t(y) * w + x];
            if (y > 0) {
                d = qMin(d, dist[size_t(y - 1) * w + x] + 3);
            }
            if (x > 0) {
                d = qMin(d, dist[size_t(y) * w + x - 1] + 3);
            }
            if (y > 0 && x > 0) {
                d = qMin(d, dist[size_t(y - 1) * w + x - 1] + 4);
            }
            if (y > 0 && x < w - 1) {
                d = qMin(d, dist[size_t(y - 1) * w + x + 1] + 4);
            }
            dist[size_t(y) * w + x] = d;
        }
    }
    for (int y = h - 1; y >= 0; --y) {
        for (int x = w - 1; x >= 0; --x) {
            qint32 d = dist[size_t(y) * w + x];
            if (y < h - 1) {
                d = qMin(d, dist[size_t(y + 1) * w + x] + 3);
            }
            if (x < w - 1) {
                d = qMin(d, dist[size_t(y) * w + x + 1] + 3);
            }
            if (y < h - 1 && x < w - 1) {
                d = qMin(d, dist[size_t(y + 1) * w + x + 1] + 4);
            }
            if (y < h - 1 && x > 0) {
                d = qMin(d, dist[size_t(y + 1) * w + x - 1] + 4);
            }
            dist[size_t(y) * w + x] = d;
        }
    }
}

// Circular grow matching KisGrowSelectionFilter's semantics (any selected
// pixel within the circular window selects the pixel) via a distance
// transform instead of the O(w*h*r) sliding window - O(w*h) total
static inline void dilateMaskFast(QVector<quint8> &mask, int w, int h, int radius)
{
    if (radius <= 0) {
        return;
    }
    QVector<qint32> dist(size_t(w) * h);
    chamferDist(mask, dist, w, h);
    const qint32 thr = 3 * radius;
    for (size_t i = 0; i < mask.size(); ++i) {
        mask[i] = (dist[i] <= thr) ? 255 : 0;
    }
}

// Circular erode: keep selected pixels whose distance to the nearest
// unselected pixel exceeds the radius (dual of grow)
static inline void erodeMaskFast(QVector<quint8> &mask, int w, int h, int radius)
{
    if (radius <= 0) {
        return;
    }
    QVector<quint8> inv(size_t(w) * h);
    for (size_t i = 0; i < mask.size(); ++i) {
        inv[i] = mask[i] ? 0 : 255;
    }
    QVector<qint32> dist(size_t(w) * h);
    chamferDist(inv, dist, w, h);
    const qint32 thr = 3 * radius;
    for (size_t i = 0; i < mask.size(); ++i) {
        mask[i] = (dist[i] > thr) ? 255 : 0;
    }
}

// Uniform (box) blur matching Krita's feather exactly: KisGaussianKernel
// createUniform2DKernel is a separable uniform kernel convolved with
// BORDER_IGNORE (pixels outside the rect read as 0 but the kernel sum stays
// the full window, so edges darken). Two separable passes with out-of-bounds
// reads as 0 and a final 1/win^2 normalization reproduce the 2D convolution;
// prefix sums keep it O(w*h) instead of the 41x41 kernel over 2M pixels
static inline void featherMask(QVector<quint8> &mask, int w, int h, int radius)
{
    if (radius <= 0) {
        return;
    }
    QVector<qreal> tmp(size_t(w) * h);
    QVector<int> ps(w + 1);
    for (int y = 0; y < h; ++y) {
        const quint8 *row = mask.constData() + size_t(y) * w;
        qreal *out = tmp.data() + size_t(y) * w;
        for (int x = 0; x < w; ++x) {
            ps[x + 1] = ps[x] + row[x];
        }
        for (int x = 0; x < w; ++x) {
            const int p0 = qBound(0, x - radius, w);
            const int p1 = qBound(0, x + radius + 1, w);
            out[x] = ps[p1] - ps[p0];
        }
    }
    const qreal norm = 1.0 / qreal((2 * radius + 1) * (2 * radius + 1));
    QVector<int> ps2(h + 1);
    for (int x = 0; x < w; ++x) {
        for (int y = 0; y < h; ++y) {
            ps2[y + 1] = ps2[y] + int(tmp[size_t(y) * w + x] + 0.5);
        }
        for (int y = 0; y < h; ++y) {
            const int p0 = qBound(0, y - radius, h);
            const int p1 = qBound(0, y + radius + 1, h);
            const qreal v = qreal(ps2[p1] - ps2[p0]) * norm;
            mask[size_t(y) * w + x] = quint8(qBound<qreal>(0.0, v, 255.0) + 0.5);
        }
    }
}


// Approximate Gaussian feather via repeated box blurs (radius iterations)
static inline void blurMask(QVector<quint8> &mask, int w, int h, int radius)
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
static inline QVector<quint8> combineSelectionMasks(const QVector<quint8> &existing,
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
static inline void flipDevice(KisPaintDeviceSP dev, bool horizontal)
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
template <typename F>
static inline void filterParallelFor(int start, int end, F &&func) {
    const int numThreads = std::max(1, std::min(int(QThread::idealThreadCount()), 8));
    if (numThreads <= 1 || (end - start) < 64) {
        func(start, end);
        return;
    }
    std::vector<std::future<void>> futures;
    futures.reserve(numThreads);
    const int chunkSize = (end - start + numThreads - 1) / numThreads;
    for (int t = 0; t < numThreads; ++t) {
        int s = start + t * chunkSize;
        int e = qMin(end, s + chunkSize);
        if (s < e) {
            futures.push_back(std::async(std::launch::async, [s, e, &func]() {
                func(s, e);
            }));
        }
    }
    for (auto &f : futures) {
        f.get();
    }
}

static inline void boxBlurH(const quint32 *src, quint32 *dst, int w, int h, int r) {
    filterParallelFor(0, h, [&](int startY, int endY) {
        const float invCount = 1.0f / float(2 * r + 1);
        for (int y = startY; y < endY; ++y) {
            const quint32 *srcRow = src + y * w;
            quint32 *dstRow = dst + y * w;
            int sumA = 0, sumRA = 0, sumGA = 0, sumBA = 0;
            for (int i = -r; i <= r; ++i) {
                int ix = qBound(0, i, w - 1);
                quint32 c = srcRow[ix];
                int a = (c >> 24) & 0xFF;
                int red = (c >> 16) & 0xFF;
                int green = (c >> 8) & 0xFF;
                int blue = c & 0xFF;
                sumA += a;
                sumRA += red * a;
                sumGA += green * a;
                sumBA += blue * a;
            }
            for (int x = 0; x < w; ++x) {
                if (sumA > 0) {
                    int finalA = int(sumA * invCount);
                    int finalR = qBound(0, sumRA / sumA, 255);
                    int finalG = qBound(0, sumGA / sumA, 255);
                    int finalB = qBound(0, sumBA / sumA, 255);
                    dstRow[x] = (quint32(finalA) << 24) |
                                (quint32(finalR) << 16) |
                                (quint32(finalG) << 8) |
                                quint32(finalB);
                } else {
                    dstRow[x] = 0;
                }
                int nextX = qBound(0, x + r + 1, w - 1);
                int prevX = qBound(0, x - r, w - 1);
                quint32 cNext = srcRow[nextX];
                quint32 cPrev = srcRow[prevX];
                int an = (cNext >> 24) & 0xFF, ap = (cPrev >> 24) & 0xFF;
                int rn = (cNext >> 16) & 0xFF, rp = (cPrev >> 16) & 0xFF;
                int gn = (cNext >> 8) & 0xFF, gp = (cPrev >> 8) & 0xFF;
                int bn = cNext & 0xFF, bp = cPrev & 0xFF;
                sumA += an - ap;
                sumRA += rn * an - rp * ap;
                sumGA += gn * an - gp * ap;
                sumBA += bn * an - bp * ap;
            }
        }
    });
}

static inline void boxBlurV(const quint32 *src, quint32 *dst, int w, int h, int r) {
    filterParallelFor(0, w, [&](int startX, int endX) {
        const float invCount = 1.0f / float(2 * r + 1);
        for (int x = startX; x < endX; ++x) {
            int sumA = 0, sumRA = 0, sumGA = 0, sumBA = 0;
            for (int i = -r; i <= r; ++i) {
                int iy = qBound(0, i, h - 1);
                quint32 c = src[iy * w + x];
                int a = (c >> 24) & 0xFF;
                int red = (c >> 16) & 0xFF;
                int green = (c >> 8) & 0xFF;
                int blue = c & 0xFF;
                sumA += a;
                sumRA += red * a;
                sumGA += green * a;
                sumBA += blue * a;
            }
            for (int y = 0; y < h; ++y) {
                if (sumA > 0) {
                    int finalA = int(sumA * invCount);
                    int finalR = qBound(0, sumRA / sumA, 255);
                    int finalG = qBound(0, sumGA / sumA, 255);
                    int finalB = qBound(0, sumBA / sumA, 255);
                    dst[y * w + x] = (quint32(finalA) << 24) |
                                     (quint32(finalR) << 16) |
                                     (quint32(finalG) << 8) |
                                     quint32(finalB);
                } else {
                    dst[y * w + x] = 0;
                }
                int nextY = qBound(0, y + r + 1, h - 1);
                int prevY = qBound(0, y - r, h - 1);
                quint32 cNext = src[nextY * w + x];
                quint32 cPrev = src[prevY * w + x];
                int an = (cNext >> 24) & 0xFF, ap = (cPrev >> 24) & 0xFF;
                int rn = (cNext >> 16) & 0xFF, rp = (cPrev >> 16) & 0xFF;
                int gn = (cNext >> 8) & 0xFF, gp = (cPrev >> 8) & 0xFF;
                int bn = cNext & 0xFF, bp = cPrev & 0xFF;
                sumA += an - ap;
                sumRA += rn * an - rp * ap;
                sumGA += gn * an - gp * ap;
                sumBA += bn * an - bp * ap;
            }
        }
    });
}
// Scanline polygon fill: paint mask into a w*h mask buffer.
static inline void scanlineFillPolygon(const QVector<QPoint> &pts, int w, int h, QVector<bool> &mask)
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


static inline QPointF centripetalCatmullRom(const QPointF &p0, const QPointF &p1,
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
static inline QRect layerDiffRect(const quint8 *a, const quint8 *b, int w, int h)
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
static inline void writeRegionToDevice(KisPaintDevice *dev, const quint8 *full,
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
static inline void blitBgraToRgbaFast(const quint8 *src, int srcStride, quint8 *dst, int dstStride, int width, int height)
{
#if defined(__ARM_NEON) || defined(__ARM_NEON__)
    static const uint8_t swapRBMask[16] = {
        2, 1, 0, 3,
        6, 5, 4, 7,
        10, 9, 8, 11,
        14, 13, 12, 15
    };
    const uint8x16_t mask = vld1q_u8(swapRBMask);

    for (int y = 0; y < height; ++y) {
        const uint8_t *s = src + y * srcStride;
        uint8_t *d = dst + y * dstStride;
        int x = 0;
        for (; x <= width - 4; x += 4) {
            uint8x16_t pix = vld1q_u8(s + x * 4);
            uint8x16_t swp = vqtbl1q_u8(pix, mask);
            vst1q_u8(d + x * 4, swp);
        }
        for (; x < width; ++x) {
            uint8_t b = s[x * 4 + 0];
            uint8_t g = s[x * 4 + 1];
            uint8_t r = s[x * 4 + 2];
            uint8_t a = s[x * 4 + 3];
            d[x * 4 + 0] = r;
            d[x * 4 + 1] = g;
            d[x * 4 + 2] = b;
            d[x * 4 + 3] = a;
        }
    }
#else
    for (int y = 0; y < height; ++y) {
        const quint8 *s = src + y * srcStride;
        quint8 *d = dst + y * dstStride;
        for (int x = 0; x < width; ++x) {
            quint8 b = s[x * 4 + 0];
            quint8 g = s[x * 4 + 1];
            quint8 r = s[x * 4 + 2];
            quint8 a = s[x * 4 + 3];
            d[x * 4 + 0] = r;
            d[x * 4 + 1] = g;
            d[x * 4 + 2] = b;
            d[x * 4 + 3] = a;
        }
    }
#endif
}
static inline void clipEditToSelection(QImage &edited, const QImage &original,
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
static inline int colorDistance(const QRgb &a, const QRgb &b)
{
    const int dr = qRed(a) - qRed(b);
    const int dg = qGreen(a) - qGreen(b);
    const int db = qBlue(a) - qBlue(b);
    return dr * dr + dg * dg + db * db;
}
static inline void setSelectionFromMask(ReverieCore *core, const KisImageSP &image,
                                 const QVector<quint8> &mask,
                                 int selMode)
{
    RPC_LOG("RPC setSelectionFromMask mode=%d maskPixels=%d", selMode, (int)mask.size());
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

static inline KisSelectionSP selectionFromMask(const KisImageSP &image,
                                        const QVector<quint8> &mask,
                                        bool fullSelect)
{
    KisSelectionSP sel = new KisSelection(
        new KisSelectionDefaultBounds(image->projection()),
        toQShared(new KisImageResolutionProxy(image)));
    KisPixelSelectionSP ps = sel->pixelSelection();
    const int iw = image->width();
    const int ih = image->height();
    if (fullSelect) {
        // Fast path: the whole document is selected (e.g. a magic-wand tap
        // on a uniform background). One rect select instead of a per-row
        // span scan over all 2M pixels.
        ps->select(QRect(0, 0, iw, ih), OPACITY_OPAQUE_U8);
        return sel;
    }
    // Write the mask directly: KisPixelSelection's device is alpha8 (one
    // byte per pixel), so the 0/255 mask is already in device format - one
    // writeBytes replaces thousands of ps->select calls (~1.7ms each - they
    // build a KisFillPainter and invalidate the thumbnail), which cost
    // seconds on the phone for complex masks
    QElapsedTimer st;
    st.start();
    ps->writeBytes(mask.constData(), 0, 0, iw, ih);
    ps->setDirty(QRect(0, 0, iw, ih));
    RPC_LOG("RPC selFromMask writeBytes time=%ldms", long(st.elapsed()));
    return sel;
}

// FX filter preview cases (19-34); defined in ReverieCoreFilterPreviewFx.cpp
void applyFilterFxCases(QImage &img, int w, int h, int filterType,
                        double p1, double p2, double p3, double p4);

// Paintop registration hooks exported by the bundled kritadefaultpaintops_static lib
extern "C" {
void krita_register_default_paintops();
void krita_register_colorsmudge_paintop();
void krita_register_roundmarker_paintop();
void krita_register_spray_paintop();
void krita_register_sketch_paintop();
void krita_register_deform_paintop();
void krita_register_filter_paintop();
void krita_register_grid_paintop();
void krita_register_experiment_paintop();
void krita_register_particle_paintop();
void krita_register_curve_paintop();
void krita_register_tangentnormal_paintop();
void krita_register_hairy_paintop();
void krita_register_hatching_paintop();
}

void registerCoreFilters();
void registerReverieRegistryFilters();
void registerReverieGenerators();

/** 新图层插入位置判定（定义于 ReverieCoreLayers.cpp，调整层等域复用） */
void currentInsertPosition(const QVector<ReverieCore::LayerEntry> &layers, int current,
                           KisNodeSP &above, KisNodeSP &parent, KisImageSP image);

#endif // REVERIECORE_INTERNAL_H
