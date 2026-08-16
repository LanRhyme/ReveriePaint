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
// PRISTINE source copy. Warping the already-warped layer per move instead
// (the original approach) resampled the same pixels over and over, which
// pulled seams and blank lines through the content.
//
// PERFORMANCE: run() clears dst and fast-copies the ENTIRE complement of the
// strokes sub-grid (a full-canvas copy on every call!). Running that per
// pointer move saturated the render thread on large documents. The worker is
// therefore constructed over a LOCAL rect around the brush (src clone is
// local too), rebased when the brush wanders out (flushing the accumulated
// warp into the layer first), and the run+writeback is throttled to ~40fps.
namespace
{
// min writeback interval; adapts upward if a single apply is slower
const qint64 LIQUIFY_APPLY_MIN_INTERVAL_MS = 20;
}

void ReverieCore::resetLiquifyWorker()
{
    delete m_liquifyWorker;
    m_liquifyWorker = nullptr;
    m_liquifySrcDevice.clear();
    m_liquifyDstDevice.clear();
    m_liquifyWorkerBounds = QRect();
    m_liquifyPendingDelta = QRect();
    m_liquifyApplyIntervalMs = LIQUIFY_APPLY_MIN_INTERVAL_MS;
}

// Re-run the accumulated warp and write back only the DELTA region: older
// grid displacements never change afterwards (build-up), so output pixels
// only change within the newest dabs' influence. Writing back the whole
// accumulated strokes region instead made every apply cost (and recomposite)
// grow linearly with drag length - the lag the user felt on long drags.
void ReverieCore::liquifyApplyLocked(const QRect &deltaRect)
{
    if (!m_liquifyWorker || !m_document) {
        return;
    }
    KisPaintDeviceSP device = currentPaintDevice();
    if (!device) {
        return;
    }
    const qint64 t0 = QDateTime::currentMSecsSinceEpoch();
    m_liquifyDstDevice->clear();
    m_liquifyWorker->run(m_liquifySrcDevice, m_liquifyDstDevice);
    const qint64 tRun = QDateTime::currentMSecsSinceEpoch();
    QRect area = deltaRect.intersected(m_liquifyWorkerBounds)
                     .intersected(QRect(0, 0, m_document->width(), m_document->height()));
    if (!area.isEmpty()) {
        KisPainter p(device);
        p.setCompositeOpId(COMPOSITE_COPY);
        p.bitBlt(area.topLeft(), m_liquifyDstDevice, area);
        p.end();
        device->setDirty(area);
        markRegionDirty(area);
    }
    m_liquifyLastApplyMs = QDateTime::currentMSecsSinceEpoch();
    const qint64 elapsed = m_liquifyLastApplyMs - t0;
    // Adaptive pacing: if one apply exceeds the frame budget, back off (up
    // to ~15fps) so the render thread always keeps serving input
    m_liquifyApplyIntervalMs =
        qBound<qint64>(LIQUIFY_APPLY_MIN_INTERVAL_MS,
                       qMax<qint64>(elapsed * 2, LIQUIFY_APPLY_MIN_INTERVAL_MS),
                       64);
    RPC_LOG("liquify apply run=%dms total=%dms area=%dx%d bounds=%dx%d int=%d",
            int(tRun - t0), int(elapsed),
            area.width(), area.height(),
            m_liquifyWorkerBounds.width(), m_liquifyWorkerBounds.height(),
            int(m_liquifyApplyIntervalMs));
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
    if (m_liquifyTxnActive) {
        return;
    }
    delete m_liquifyTxn;
    m_liquifyTxn = new KisTransaction(kundo2_i18n("Liquify"), device, nullptr, -1, nullptr);
    m_liquifyTxnActive = true;
    // The grid worker needs the dab position - it is created lazily by the
    // first liquify() call (and rebased whenever the brush wanders out)
    resetLiquifyWorker();
}

void ReverieCore::liquifyEnd()
{
    // Flush any grid displacement that is still under the apply throttle
    if (m_liquifyWorker && !m_liquifyPendingDelta.isNull()) {
        liquifyApplyLocked(m_liquifyPendingDelta);
        m_liquifyPendingDelta = QRect();
    }
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
    resetLiquifyWorker();
}

void ReverieCore::liquifyCancel()
{
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
    resetLiquifyWorker();
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
    if (!m_liquifyTxnActive) {
        liquifyBegin();
        if (!m_liquifyTxnActive) {
            return;
        }
    }

    const qreal s = qBound<qreal>(0.05, strength, 2.0);
    // KisLiquifyPaintop passes the brush diameter as sigma (gaussian falloff)
    const qreal size = qMax<qreal>(8.0, m_liquifyBrushSize);

    // Local grid: rebase when the brush is about to leave the inner margin
    // (the worker's run() copies the whole bounds complement, so the bounds
    // must stay local or every dab costs a full-bounds copy)
    const bool workerUsable = m_liquifyWorker != nullptr;
    bool needRebase = !workerUsable;
    if (workerUsable) {
        const int margin = qMax(40, qRound(size * 0.7));
        const QRect inner = m_liquifyWorkerBounds.adjusted(margin, margin, -margin, -margin);
        if (!inner.contains(QPoint(tx, ty))) {
            needRebase = true;
        }
    }
    if (needRebase) {
        if (m_liquifyWorker) {
            liquifyApplyLocked(m_liquifyPendingDelta.isNull() ? m_liquifyWorkerBounds
                                                              : m_liquifyPendingDelta);
            m_liquifyPendingDelta = QRect();
        }
        // Tight bounds: only the brush neighbourhood + the gaussian influence
        // radius (3 sigma) must fit; anything larger only adds copy cost
        const int R = qMax<int>(192, qRound(size * 1.9));
        QRect bounds(tx - R, ty - R, 2 * R, 2 * R);
        bounds = bounds.intersected(QRect(0, 0, image->width(), image->height()));
        if (bounds.isEmpty()) {
            resetLiquifyWorker();
            if (ownBracket) liquifyEnd();
            return;
        }
        m_liquifyWorkerBounds = bounds;
        m_liquifySrcDevice = new KisPaintDevice(device->colorSpace());
        m_liquifySrcDevice->makeCloneFrom(device, bounds);
        m_liquifyDstDevice = new KisPaintDevice(device->colorSpace());
        delete m_liquifyWorker;
        // pixelPrecision 16: quarter the polygon count vs 8 with no visible
        // quality difference for smooth liquify warps
        m_liquifyWorker = new KisLiquifyTransformWorker(bounds, nullptr, 16);
    }

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

    // Accumulate the delta region of dabs not yet written back (build-up
    // displacements never change once applied, so only new dabs' influence
    // needs the re-transformed pixels)
    const int infl = qRound(size * 3.2) + 8;
    const QRect dab(qMin(fx, tx) - infl, qMin(fy, ty) - infl,
                    qAbs(tx - fx) + 2 * infl, qAbs(ty - fy) + 2 * infl);
    m_liquifyPendingDelta =
        m_liquifyPendingDelta.isNull() ? dab : m_liquifyPendingDelta.united(dab);

    // Throttled writeback: the grid update is cheap, the re-transform +
    // layer copy is not - pace it adaptively and flush once at gesture end
    const qint64 now = QDateTime::currentMSecsSinceEpoch();
    if (ownBracket || now - m_liquifyLastApplyMs >= m_liquifyApplyIntervalMs) {
        liquifyApplyLocked(m_liquifyPendingDelta);
        m_liquifyPendingDelta = QRect();
    }

    if (ownBracket) {
        liquifyEnd();
    }
}


