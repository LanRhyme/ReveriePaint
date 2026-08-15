/* ============================================================
 * ReverieCoreRender.cpp - Rendering: composite projection to bitmap, flood fill, color pick, shapes
 * (part of the ReverieCore module split; shared helpers live in
 * ReverieCoreInternal.h, public API in ReverieCore.h)
 * ============================================================ */
#include "ReverieCoreInternal.h"

bool ReverieCore::renderToBuffer(quint8 *buffer, int w, int h)
{
    KisImageSP image = m_document;
    if (!image || !buffer || w <= 0 || h <= 0) {
        return false;
    }
    image->waitForDone();

    const int iw = image->width();
    const int ih = image->height();
    KisPaintDeviceSP proj = image->projection();
    if (!proj) {
        return false;
    }

    // 1:1 Native Resolution Rendering Path (Direct Krita GPU Engine Alignment)
    if (w == iw && h == ih) {
        if (!m_bitmapInited || m_dirtyRect.isNull() || m_dirtyRect == QRect(0, 0, iw, ih)) {
            // Full frame update
            QByteArray raw;
            raw.resize(size_t(iw) * ih * 4);
            proj->readBytes(reinterpret_cast<quint8 *>(raw.data()), 0, 0, iw, ih);
            blitBgraToRgbaFast(reinterpret_cast<const quint8 *>(raw.constData()), iw * 4,
                               buffer, w * 4, iw, ih);
            m_bitmapInited = true;
        } else {
            // Sub-region dirty update with exact pixel boundaries (0 rounding seams/misalignment)
            const QRect r = m_dirtyRect.intersected(QRect(0, 0, iw, ih));
            if (!r.isEmpty()) {
                QByteArray raw;
                raw.resize(size_t(r.width()) * r.height() * 4);
                proj->readBytes(reinterpret_cast<quint8 *>(raw.data()), r.x(), r.y(), r.width(), r.height());
                quint8 *dst = buffer + size_t(r.y()) * (w * 4) + size_t(r.x()) * 4;
                blitBgraToRgbaFast(reinterpret_cast<const quint8 *>(raw.constData()), r.width() * 4,
                                   dst, w * 4, r.width(), r.height());
            }
        }
        m_dirtyRect = QRect();
        return true;
    }

    // Scaled viewport fallback path (if buffer size != document size)
    const qreal sx = qreal(w) / iw;
    const qreal sy = qreal(h) / ih;

    if (m_displayImage.isNull() || m_displayImage.size() != QSize(w, h)) {
        m_displayImage = QImage(w, h, QImage::Format_RGBA8888);
        m_dirtyRect = QRect(0, 0, iw, ih);
        m_bitmapInited = false;
    }

    if (!m_dirtyRect.isEmpty()) {
        const QRect r = m_dirtyRect.intersected(QRect(0, 0, iw, ih));
        if (!r.isEmpty()) {
            QByteArray raw;
            raw.resize(size_t(r.width()) * r.height() * 4);
            proj->readBytes(reinterpret_cast<quint8 *>(raw.data()), r.x(), r.y(), r.width(), r.height());
            QImage subBgra(r.width(), r.height(), QImage::Format_RGBA8888);
            blitBgraToRgbaFast(reinterpret_cast<const quint8 *>(raw.constData()), r.width() * 4,
                               subBgra.bits(), r.width() * 4, r.width(), r.height());

            const int vw = qMax(1, qRound(r.width() * sx));
            const int vh = qMax(1, qRound(r.height() * sy));
            const QImage scaled = (subBgra.width() != vw || subBgra.height() != vh)
                    ? subBgra.scaled(vw, vh, Qt::IgnoreAspectRatio, Qt::SmoothTransformation)
                    : subBgra;
            const QRect vp(qRound(r.x() * sx), qRound(r.y() * sy), scaled.width(), scaled.height());
            const QRect clip = vp.intersected(QRect(0, 0, w, h));
            if (!clip.isEmpty()) {
                for (int y = clip.top(); y <= clip.bottom(); ++y) {
                    memcpy(m_displayImage.scanLine(y) + clip.left() * 4,
                           scaled.constScanLine(y - vp.y()) + (clip.left() - vp.x()) * 4,
                           size_t(clip.width()) * 4);
                }
            }
        }
        m_dirtyRect = QRect();
    }

    memcpy(buffer, m_displayImage.constBits(), size_t(w) * h * 4);
    return true;
}

void ReverieCore::floodFillAt(int x, int y, int tolerance)
{
    KisImageSP image = m_document ? m_document : KisImageSP();
    if (!image) return;
    if (x < 0 || y < 0 || x >= image->width() || y >= image->height()) return;
    KisPaintDeviceSP device = currentPaintDevice();
    if (!device) return;

    KisTransaction txn(kundo2_i18n("Fill"), device);
    
    KisFillPainter painter(device);
    painter.setFillThreshold(tolerance);
    if (m_selection) {
        painter.setSelection(m_selection);
    }
    
    QColor qColor(m_brushColor);
    if (!qColor.isValid()) qColor = Qt::black;
    qColor.setAlphaF(qBound<qreal>(0.0, m_brushOpacity, 1.0));
    KoColor koColor(qColor, image->colorSpace());
    painter.setPaintColor(koColor);
    painter.setOpacityF(m_brushOpacity);
    if (m_brushPreset && m_brushPreset->settings()) {
        painter.setCompositeOpId(m_brushPreset->settings()->effectivePaintOpCompositeOp());
    } else {
        painter.setCompositeOpId(COMPOSITE_OVER);
    }

    // fillColor will flood fill starting from x, y
    painter.fillColor(x, y, device);

    device->setDirty();
    markDirty();
    txn.commit(image->undoAdapter());
    m_redoCount = 0;
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
    KisPaintDeviceSP dev = currentLayerOnly ? currentPaintDevice() : image->projection();
    if (!dev) return QString();
    quint8 pixel[4] = {0, 0, 0, 0};
    dev->readBytes(pixel, x, y, 1, 1);
    if (pixel[3] == 0) return QString(); // transparent
    // KoBgrU8Traits: pixel[0]=B, pixel[1]=G, pixel[2]=R, pixel[3]=A
    return QStringLiteral("#%1%2%3")
            .arg(pixel[2], 2, 16, QLatin1Char('0'))
            .arg(pixel[1], 2, 16, QLatin1Char('0'))
            .arg(pixel[0], 2, 16, QLatin1Char('0'));
}

// Restore pixels outside the active selection after a QImage-based edit
// (drawShape / drawPolygon / gradientFill / moveLayerContent), so those
// tools are constrained to the selection exactly like Krita. 'edited' is the
// region-sized image, 'original' its pre-edit copy, 'selMask' the full
// document mask, and offsetX/offsetY locate 'edited' inside the document.

void ReverieCore::drawShape(int kind, int x1, int y1, int x2, int y2, bool filled)
{
    KisImageSP image = m_document ? m_document : KisImageSP();
    if (!image) return;
    KisPaintDeviceSP device = currentPaintDevice();
    if (!device) return;

    KisTransaction txn(kundo2_i18n("Shape"), device);
    KisPainter painter(device);

    KoColor paintColor(QColor(m_brushColor), image->colorSpace());
    painter.setPaintColor(paintColor);
    painter.setBackgroundColor(paintColor);
    painter.setOpacityF(m_brushOpacity);
    
    painter.setStrokeStyle(KisPainter::StrokeStyleBrush);
    painter.setFillStyle((filled || m_shapeFilled) ? KisPainter::FillStyleForegroundColor : KisPainter::FillStyleNone);
    
    if (m_selection) {
        painter.setSelection(m_selection);
    }
    
    const int layerIndex = qBound(0, m_currentLayer, (int)m_layers.size() - 1);
    if (m_brushPreset) {
        painter.setPaintOpPreset(m_brushPreset, KisNodeSP(m_layers[layerIndex].node), image);
        if (m_brushPreset->settings()) {
            painter.setCompositeOpId(m_brushPreset->settings()->effectivePaintOpCompositeOp());
        }
    }
    
    // We must run paint operations inside Krita's thread-safe executor logic if it uses stroke jobs
    // but for simple single-frame shapes, KisPainter handles it synchronously if we don't set a RunnableStrokeJobsInterface.
    // Wait, Krita's KisPainter requires a fake executor if we use paint ops asynchronously?
    // In Krita, KisToolShape uses KisPainter normally.
    painter.setRunnableStrokeJobsInterface(&m_fakeExecutor);
    
    QRectF r(QPointF(x1, y1), QPointF(x2, y2));
    r = r.normalized();

    switch (kind) {
    case 1: 
        painter.paintRect(r); 
        break;
    case 2: 
        painter.paintEllipse(r); 
        break;
    default: 
        // KisPainter::paintLine takes KisPaintInformation.
        painter.paintLine(KisPaintInformation(QPointF(x1, y1)), KisPaintInformation(QPointF(x2, y2)), nullptr);
        break;
    }

    device->setDirty();
    markDirty();
    txn.commit(image->undoAdapter());
    m_redoCount = 0;
}

void ReverieCore::drawPolygon(const QVector<QPoint> &points, bool closed)
{
    if (points.size() < 2) return;
    KisImageSP image = m_document ? m_document : KisImageSP();
    if (!image) return;
    KisPaintDeviceSP device = currentPaintDevice();
    if (!device) return;

    KisTransaction txn(kundo2_i18n("Shape"), device);
    KisPainter painter(device);
    
    KoColor paintColor(QColor(m_brushColor), image->colorSpace());
    painter.setPaintColor(paintColor);
    painter.setBackgroundColor(paintColor);
    painter.setOpacityF(m_brushOpacity);

    painter.setStrokeStyle(KisPainter::StrokeStyleBrush);
    painter.setFillStyle(m_shapeFilled ? KisPainter::FillStyleForegroundColor : KisPainter::FillStyleNone);
    
    if (m_selection) {
        painter.setSelection(m_selection);
    }
    
    const int layerIndex = qBound(0, m_currentLayer, (int)m_layers.size() - 1);
    if (m_brushPreset) {
        painter.setPaintOpPreset(m_brushPreset, KisNodeSP(m_layers[layerIndex].node), image);
        if (m_brushPreset->settings()) {
            painter.setCompositeOpId(m_brushPreset->settings()->effectivePaintOpCompositeOp());
        }
    }
    
    painter.setRunnableStrokeJobsInterface(&m_fakeExecutor);
    
    vQPointF pts;
    for (const QPoint &p : points) {
        pts.append(p);
    }

    if (closed) {
        painter.paintPolygon(pts);
    } else {
        painter.paintPolyline(pts);
    }

    device->setDirty();
    markDirty();
    txn.commit(image->undoAdapter());
    m_redoCount = 0;
}

void ReverieCore::gradientFill(int x1, int y1, int x2, int y2, int type)
{
    KisImageSP image = m_document ? m_document : KisImageSP();
    if (!image) return;
    KisPaintDeviceSP device = currentPaintDevice();
    if (!device) return;
    if (x1 == x2 && y1 == y2) return;

    KisTransaction txn(kundo2_i18n("Gradient"), device);

    const int iw = image->width();
    const int ih = image->height();
    QImage gradImg(iw, ih, QImage::Format_ARGB32_Premultiplied);
    gradImg.fill(Qt::transparent);

    QPainter qp(&gradImg);
    qp.setRenderHint(QPainter::Antialiasing, true);

    QColor fgColor(m_brushColor);
    if (!fgColor.isValid()) fgColor = Qt::black;
    fgColor.setAlphaF(qBound<qreal>(0.0, m_brushOpacity, 1.0));

    QColor bgColor(m_brushColor);
    bgColor.setAlphaF(0.0);

    QPointF p1(x1, y1);
    QPointF p2(x2, y2);

    if (type == 1) { // Radial
        qreal r = QLineF(p1, p2).length();
        if (r < 1.0) r = 1.0;
        QRadialGradient grad(p1, r);
        grad.setColorAt(0.0, fgColor);
        grad.setColorAt(1.0, bgColor);
        qp.setBrush(grad);
    } else if (type == 2) { // Conical
        qreal angle = -QLineF(p1, p2).angle();
        QConicalGradient grad(p1, angle);
        grad.setColorAt(0.0, fgColor);
        grad.setColorAt(1.0, bgColor);
        qp.setBrush(grad);
    } else { // Linear
        QLinearGradient grad(p1, p2);
        grad.setColorAt(0.0, fgColor);
        grad.setColorAt(1.0, bgColor);
        qp.setBrush(grad);
    }
    qp.setPen(Qt::NoPen);
    qp.drawRect(0, 0, iw, ih);
    qp.end();

    KisPaintDeviceSP tempSrc = new KisPaintDevice(image->colorSpace());
    tempSrc->convertFromQImage(gradImg, 0);

    KisPainter painter(device);
    if (m_selection) {
        painter.setSelection(m_selection);
    }
    painter.setOpacityF(m_brushOpacity);
    painter.setCompositeOpId(COMPOSITE_OVER);
    painter.bitBlt(QPoint(0, 0), tempSrc, QRect(0, 0, iw, ih));

    device->setDirty();
    markDirty();
    txn.commit(image->undoAdapter());
    m_redoCount = 0;
}


