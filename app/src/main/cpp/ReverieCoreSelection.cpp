#include "ReverieCoreInternal.h"

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
    return m_selection && !m_selection->selectedRect().isEmpty() && !m_selection->selectedExactRect().isEmpty();
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
    // Krita's feather is a uniform (box) kernel (KisGaussianKernel
    // createUniform2DKernel); run the same separable uniform blur on the raw
    // alpha8 mask at O(w*h) instead of a full 2D convolution
    QVector<quint8> mask = readSelectionMaskBytes(image, m_selection);
    featherMask(mask, iw, ih, radius);
    KisSelectionSP sel = selectionFromMask(image, mask);
    setSelection(sel);
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
    // Same sliding-window grow semantics as KisGrowSelectionFilter, but
    // O(w*h) prefix-sum scans on the raw mask
    QVector<quint8> mask = readSelectionMaskBytes(image, m_selection);
    dilateMaskFast(mask, iw, ih, px);
    KisSelectionSP sel = selectionFromMask(image, mask);
    setSelection(sel);
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
    // Same sliding-window shrink semantics as KisShrinkSelectionFilter
    QVector<quint8> mask = readSelectionMaskBytes(image, m_selection);
    erodeMaskFast(mask, iw, ih, px);
    KisSelectionSP sel = selectionFromMask(image, mask);
    setSelection(sel);
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
    // Morphological close: erode removes specks, dilate restores the bulk
    QVector<quint8> mask = readSelectionMaskBytes(image, m_selection);
    erodeMaskFast(mask, iw, ih, radius);
    dilateMaskFast(mask, iw, ih, radius);
    KisSelectionSP sel = selectionFromMask(image, mask);
    setSelection(sel);
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
    if (!m_selection) {
        RPC_LOG("RPC selectionMask null selection");
        // No selection: return an EMPTY array so callers (floodFillAt,
        // applyTransform, clipEditToSelection) treat the operation as
        // unconstrained. Returning the full zero-filled document mask made
        // them read the seed pixel as "not selected" and abort the op.
        return QByteArray();
    }
    KisPixelSelectionSP ps = m_selection->pixelSelection();
    QByteArray mask(iw * ih, 0);
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
    // Read the selection mask in 256-row chunks (Krita's tile size) and
    // sample rows out of the cached chunk: the old per-row readBytes made
    // one call per sampled row (~960 on 1080x1920 at viewport height)
    QVector<quint8> chunk;
    int chunkBase = -1;
    const int chunkH = 256;
    const double stepY = double(ih) / vh;
    const double stepX = double(iw) / vw;
    bool any = false;
    for (int y = 0; y < vh; ++y) {
        const int srcY = qMin(ih - 1, int(y * stepY));
        const int b = srcY / chunkH;
        if (b != chunkBase) {
            chunkBase = b;
            chunk.resize(size_t(iw) * chunkH);
            const int ty = b * chunkH;
            const int h = qMin(chunkH, ih - ty);
            ps->readBytes(chunk.data(), 0, ty, iw, h);
        }
        const int rowOff = (srcY - chunkBase * chunkH) * iw;
        const size_t dstOff = size_t(y) * vw;
        for (int x = 0; x < vw; ++x) {
            const int srcX = qMin(iw - 1, int(x * stepX));
            if (chunk[size_t(rowOff) + srcX] != 0) {
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

