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
    m_displayImage = QImage();
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

void ReverieCore::liquify(int fx, int fy, int tx, int ty, qreal strength, int mode)
{
    KisImageSP image = m_document ? m_document : KisImageSP();
    if (!image) return;
    KisPaintDeviceSP device = currentPaintDevice();
    if (!device) return;
    
    KisTransaction txn(kundo2_i18n("Liquify"), device);
    
    const qreal radius = qMax<qreal>(8.0, m_brushSize * 0.6);
    QVector<QPointF> origPoints;
    QVector<QPointF> transPoints;
    
    // Create points around the radius boundary that stay fixed
    const int numBoundaryPoints = 12;
    for (int i = 0; i < numBoundaryPoints; ++i) {
        double angle = (2.0 * M_PI * i) / numBoundaryPoints;
        QPointF p(fx + radius * cos(angle), fy + radius * sin(angle));
        origPoints.append(p);
        transPoints.append(p);
    }
    
    // Create the center point that moves
    origPoints.append(QPointF(fx, fy));
    
    // Scale movement by strength
    QPointF dt((tx - fx) * qBound<qreal>(0.05, strength, 2.0),
               (ty - fy) * qBound<qreal>(0.05, strength, 2.0));
    transPoints.append(QPointF(fx + dt.x(), fy + dt.y()));

    KisWarpTransformWorker worker(KisWarpTransformWorker::RIGID_TRANSFORM,
                                  origPoints, transPoints, 1.0, nullptr);
                                  
    // Warp transforms the whole device. We should limit it.
    // KisWarpTransformWorker doesn't limit bounds automatically, but
    // since outer points are fixed, the deformation is mostly local.
    // Still, running it on a 4K canvas could be slow.
    // For performance, we'll isolate the region.
    QRect region(fx - radius * 1.5, fy - radius * 1.5, radius * 3.0, radius * 3.0);
    region = region.intersected(device->exactBounds());
    
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

    device->setDirty();
    markDirty();
    txn.commit(image->undoAdapter());
    m_redoCount = 0;
}


