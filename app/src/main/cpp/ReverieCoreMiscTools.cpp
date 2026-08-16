/* ============================================================
 * ReverieCoreMiscTools.cpp - Misc tools: crop, draw text, liquify
 * (part of the ReverieCore module split; shared helpers live in
 * ReverieCoreInternal.h, public API in ReverieCore.h)
 * ============================================================ */
#include "ReverieCoreInternal.h"

void ReverieCore::cropCanvas(int x, int y, int w, int h)
{
    KisImageSP image = m_document;
    if (!image || w <= 0 || h <= 0) {
        return;
    }
    const QRect crop(qMax(0, x), qMax(0, y), w, h);
    image->resizeImage(crop);
    // resizeImage goes through KisProcessingApplicator (async stroke) - the
    // document size changes only after the stroke lands, so wait or the
    // size mirrors below read stale values (crop crash / wrong viewport)
    image->waitForDone();
    syncLayersFromImage();
    // Document size changed: keep the viewport/cache mirrors in sync or the
    // render pipeline reads stale dimensions (the crop crash)
    m_docWidth = image->width();
    m_docHeight = image->height();
    m_renderBufW = -1;
    m_renderBufH = -1;
    m_dirtyRect = QRect(0, 0, m_docWidth, m_docHeight);
    m_bitmapInited = false;
    m_lastDirty = QRect();
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

// Build the warp point sets for one liquify dab: a fixed boundary ring keeps
// the deformation local; the inner points move according to the mode:
//   0 推拉   - center point follows the finger delta
//   1 膨胀   - inner ring expands outward (bloat)
//   2 收缩   - inner ring pulls toward the center (pucker)
//   3 顺时针 - inner ring rotates CW around the center
//   4 逆时针 - inner ring rotates CCW around the center
// Effect magnitude scales with the finger movement distance (rate), so
// holding still applies nothing and faster strokes apply stronger warps.
static void buildLiquifyPoints(
    int fx,
    int fy,
    int tx,
    int ty,
    qreal radius,
    qreal strength,
    int mode,
    QVector<QPointF> &origPoints,
    QVector<QPointF> &transPoints)
{
    const qreal s = qBound<qreal>(0.05, strength, 2.0);
    const qreal dist = QLineF(QPointF(fx, fy), QPointF(tx, ty)).length();
    const qreal rate = qBound<qreal>(0.0, dist / qMax<qreal>(1.0, radius * 0.5), 1.0);

    // Fixed boundary ring: deformation decays to identity at the brush edge
    const int numBoundary = 16;
    for (int i = 0; i < numBoundary; ++i) {
        const qreal angle = (2.0 * M_PI * i) / numBoundary;
        const QPointF p(fx + radius * qCos(angle), fy + radius * qSin(angle));
        origPoints.append(p);
        transPoints.append(p);
    }

    if (mode == 0) {
        // Push: the center point is dragged by the finger delta
        origPoints.append(QPointF(fx, fy));
        transPoints.append(QPointF(fx + (tx - fx) * s, fy + (ty - fy) * s));
        return;
    }

    const int numInner = 12;
    if (mode == 1 || mode == 2) {
        // Bloat / pucker: inner ring moves radially out (bloat) or in
        // (pucker). The target radius stays inside the boundary ring so the
        // warp stays local.
        const qreal r0 = radius * 0.30;
        const qreal delta = radius * 0.55 * s * (0.25 + 0.75 * rate);
        const qreal r1 = (mode == 1) ? qMin(radius * 0.92, r0 + delta)
                                     : qMax(radius * 0.06, r0 - delta);
        for (int i = 0; i < numInner; ++i) {
            const qreal angle = (2.0 * M_PI * i) / numInner;
            origPoints.append(QPointF(fx + r0 * qCos(angle), fy + r0 * qSin(angle)));
            transPoints.append(QPointF(fx + r1 * qCos(angle), fy + r1 * qSin(angle)));
        }
        // Center stays fixed: the ring's radial move carries the middle
        origPoints.append(QPointF(fx, fy));
        transPoints.append(QPointF(fx, fy));
        return;
    }

    if (mode == 3 || mode == 4) {
        // Rotate CW / CCW: inner ring rotates around the center
        const qreal r = radius * 0.55;
        const qreal dir = (mode == 3) ? 1.0 : -1.0;
        const qreal rot = dir * s * (0.15 + 0.85 * rate) * (M_PI / 3.0);
        for (int i = 0; i < numInner; ++i) {
            const qreal angle = (2.0 * M_PI * i) / numInner;
            origPoints.append(QPointF(fx + r * qCos(angle), fy + r * qSin(angle)));
            transPoints.append(QPointF(fx + r * qCos(angle + rot), fy + r * qSin(angle + rot)));
        }
        // Center fixed: rotation pivots around it
        origPoints.append(QPointF(fx, fy));
        transPoints.append(QPointF(fx, fy));
        return;
    }

    // Unknown mode: identity (boundary ring only already added)
}

void ReverieCore::liquifyBegin()
{
    if (!m_document) {
        return;
    }
    KisPaintDeviceSP device = currentPaintDevice();
    if (!device) {
        return;
    }
    // One transaction per drag gesture: per-move commits flooded the undo
    // stack with dozens of tiny "Liquify" steps for a single drag
    if (!m_liquifyTxnActive) {
        delete m_liquifyTxn;
        m_liquifyTxn = new KisTransaction(kundo2_i18n("Liquify"), device, nullptr, -1, nullptr);
        m_liquifyTxnActive = true;
    }
}

void ReverieCore::liquifyEnd()
{
    if (m_liquifyTxn && m_document) {
        if (m_undoCaptureEnabled) {
            m_liquifyTxn->commit(m_document->undoAdapter());
            m_redoCount = 0;
        } else {
            // Replay mode: keep the pixels, drop the undo command
            m_liquifyTxn->end();
        }
    }
    delete m_liquifyTxn;
    m_liquifyTxn = nullptr;
    m_liquifyTxnActive = false;
}

void ReverieCore::liquifyCancel()
{
    if (m_liquifyTxn && m_document) {
        // Roll the whole drag back like touchStrokeCancel does for strokes
        m_liquifyTxn->revert();
        delete m_liquifyTxn;
        m_liquifyTxn = nullptr;
        m_liquifyTxnActive = false;
        if (KisPaintDeviceSP device = currentPaintDevice()) {
            device->setDirty();
        }
        recompositeProjection();
        markDirty();
    }
}

void ReverieCore::liquify(int fx, int fy, int tx, int ty, qreal strength, int mode)
{
    KisImageSP image = m_document ? m_document : KisImageSP();
    if (!image) return;
    KisPaintDeviceSP device = currentPaintDevice();
    if (!device) return;

    // Standalone calls (old recordings replayed without begin/end brackets,
    // or API misuse) still get a transaction of their own
    QScopedPointer<KisTransaction> ownTxn;
    if (!m_liquifyTxnActive) {
        ownTxn.reset(new KisTransaction(kundo2_i18n("Liquify"), device, nullptr, -1, nullptr));
    }

    const qreal radius = qMax<qreal>(8.0, m_liquifyBrushSize * 0.5);

    QVector<QPointF> origPoints;
    QVector<QPointF> transPoints;
    buildLiquifyPoints(fx, fy, tx, ty, radius, strength, mode, origPoints, transPoints);

    KisWarpTransformWorker worker(KisWarpTransformWorker::RIGID_TRANSFORM,
                                  origPoints, transPoints, 1.0, nullptr);

    // Isolate the warp to the brush neighbourhood: the fixed boundary ring
    // keeps the deformation local, but running the worker over the whole
    // device would still touch (and dirty) every tile
    QRect region(qRound(fx - radius * 1.5), qRound(fy - radius * 1.5),
                 qRound(radius * 3.0), qRound(radius * 3.0));
    region = region.intersected(QRect(0, 0, image->width(), image->height()));
    if (region.isEmpty()) {
        return;
    }

    KisPaintDeviceSP tempSrc = new KisPaintDevice(image->colorSpace());
    KisPainter p(tempSrc);
    p.setCompositeOpId(COMPOSITE_COPY);
    p.bitBlt(region.topLeft(), device, region);

    KisPaintDeviceSP tempDst = new KisPaintDevice(image->colorSpace());
    worker.run(tempSrc, tempDst);

    // Clear original region then blit back
    device->clear(region);
    KisPainter p2(device);
    p2.setCompositeOpId(COMPOSITE_COPY);
    p2.bitBlt(region.topLeft(), tempDst, region);

    device->setDirty(region);
    markRegionDirty(region);
    if (ownTxn) {
        if (m_undoCaptureEnabled) {
            ownTxn->commit(image->undoAdapter());
            m_redoCount = 0;
        } else {
            ownTxn->end();
        }
    }
}


