/* ============================================================
 * ReverieCoreMiscTools.cpp - Misc tools: crop, draw text, liquify
 * (part of the ReverieCore module split; shared helpers live in
 * ReverieCoreInternal.h, public API in ReverieCore.h)
 * ============================================================ */
#include "ReverieCoreInternal.h"
#include "kis_liquify_transform_worker.h"

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

// Liquify via Krita's own KisLiquifyTransformWorker (libkritaimage, the same
// worker the transform tool's liquify mode drives through kis_liquify_paintop).
// Architecture mirrors Krita's: ONE persistent grid worker accumulates every
// dab's displacement (build-up mode), and each update re-transforms the
// PRISTINE source copy made at gesture start. Warping the already-warped
// layer per move instead (previous approach) resampled the same pixels over
// and over, which pulled seams and blank lines through the content.
void ReverieCore::liquifyBegin()
{
    if (!m_document) {
        return;
    }
    KisPaintDeviceSP device = currentPaintDevice();
    if (!device) {
        return;
    }
    if (m_liquifyTxnActive) {
        return;
    }
    delete m_liquifyTxn;
    m_liquifyTxn = new KisTransaction(kundo2_i18n("Liquify"), device, nullptr, -1, nullptr);
    m_liquifyTxnActive = true;
    m_liquifySrcDevice = new KisPaintDevice(device->colorSpace());
    m_liquifySrcDevice->makeCloneFrom(device, device->extent());
    m_liquifyDstDevice = new KisPaintDevice(device->colorSpace());
    delete m_liquifyWorker;
    m_liquifyWorker = new KisLiquifyTransformWorker(
        QRect(0, 0, m_document->width(), m_document->height()), nullptr, 8);
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
    delete m_liquifyWorker;
    m_liquifyWorker = nullptr;
    m_liquifySrcDevice.clear();
    m_liquifyDstDevice.clear();
}

void ReverieCore::liquifyCancel()
{
    const bool hadTxn = m_liquifyTxn != nullptr;
    if (m_liquifyTxn && m_document) {
        // Roll the whole drag back like touchStrokeCancel does for strokes
        m_liquifyTxn->revert();
        delete m_liquifyTxn;
        if (KisPaintDeviceSP device = currentPaintDevice()) {
            device->setDirty();
        }
        recompositeProjection();
        markDirty();
    }
    m_liquifyTxn = nullptr;
    m_liquifyTxnActive = false;
    delete m_liquifyWorker;
    m_liquifyWorker = nullptr;
    m_liquifySrcDevice.clear();
    m_liquifyDstDevice.clear();
    Q_UNUSED(hadTxn);
}

void ReverieCore::liquify(int fx, int fy, int tx, int ty, qreal strength, int mode)
{
    KisImageSP image = m_document ? m_document : KisImageSP();
    if (!image) return;
    KisPaintDeviceSP device = currentPaintDevice();
    if (!device) return;

    // Standalone calls (old recordings replayed without begin/end brackets)
    // get an implicit one-shot bracket around this dab
    const bool ownBracket = !m_liquifyTxnActive || !m_liquifyWorker;
    if (ownBracket) {
        liquifyBegin();
        if (!m_liquifyTxnActive || !m_liquifyWorker) {
            return;
        }
    }

    const qreal s = qBound<qreal>(0.05, strength, 2.0);
    // KisLiquifyPaintop passes the brush diameter as sigma (gaussian falloff)
    const qreal size = qMax<qreal>(8.0, m_liquifyBrushSize);
    const QPointF base(fx, fy);
    const qreal dist = QLineF(QPointF(fx, fy), QPointF(tx, ty)).length();
    // Effect magnitude follows how far the finger moved this dab: holding
    // still applies nothing, faster strokes apply stronger deformation
    const qreal rate = qBound<qreal>(0.0, dist / size, 1.0);
    const qreal amp = 0.2 + 0.8 * rate;

    switch (mode) {
    case 1:
        // 膨胀: grid points move away from the brush center
        m_liquifyWorker->scalePoints(base, 0.35 * s * amp, size, false, 1.0);
        break;
    case 2:
        // 收缩: grid points move toward the brush center
        m_liquifyWorker->scalePoints(base, -0.35 * s * amp, size, false, 1.0);
        break;
    case 3:
        // 顺时针
        m_liquifyWorker->rotatePoints(base, 0.6 * s * amp, size, false, 1.0);
        break;
    case 4:
        // 逆时针
        m_liquifyWorker->rotatePoints(base, -0.6 * s * amp, size, false, 1.0);
        break;
    default:
        // 推拉: pixels follow the finger delta
        m_liquifyWorker->translatePoints(
            base, QPointF((tx - fx) * s, (ty - fy) * s), size, false, 1.0);
        break;
    }

    // Re-run the ACCUMULATED warp from the pristine copy and copy the
    // changed area back into the layer
    m_liquifyDstDevice->clear();
    m_liquifyWorker->run(m_liquifySrcDevice, m_liquifyDstDevice);
    QRect area = m_liquifyWorker
                     ->approxChangeRect(m_liquifyWorker->accumulatedStrokesBounds().toAlignedRect())
                     .intersected(QRect(0, 0, image->width(), image->height()));
    if (!area.isEmpty()) {
        KisPainter p(device);
        p.setCompositeOpId(COMPOSITE_COPY);
        p.bitBlt(area.topLeft(), m_liquifyDstDevice, area);
        p.end();
        device->setDirty(area);
        markRegionDirty(area);
    }

    if (ownBracket) {
        liquifyEnd();
    }
}


