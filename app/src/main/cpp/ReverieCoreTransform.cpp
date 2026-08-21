/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/* ============================================================
 * ReverieCoreTransform.cpp - Transforms: free/perspective/distort/warp mesh preview and commit
 * (part of the ReverieCore module split; shared helpers live in
 * ReverieCoreInternal.h, public API in ReverieCore.h)
 * ============================================================ */
#include "ReverieCoreInternal.h"

bool ReverieCore::applyPerspectiveTransform(
    double x0, double y0,
    double x1, double y1,
    double x2, double y2,
    double x3, double y3,
    double origX, double origY, double origW, double origH)
{
    KisImageSP image = m_document ? m_document : KisImageSP();
    if (!image) return false;
    KisPaintDeviceSP device = currentPaintDevice();
    if (!device) return false;

    if (!m_previewTransactions.isEmpty() || m_previewTransaction) {
        cancelTransformPreview();
    }

    KisTransaction txn(kundo2_i18n("Perspective Transform"), device);

    QRect bounds(qRound(origX), qRound(origY), qRound(origW), qRound(origH));
    if (bounds.isEmpty() || !bounds.isValid()) {
        bounds = device->exactBounds().intersected(QRect(0, 0, image->width(), image->height()));
    }
    if (bounds.isEmpty() || !bounds.isValid()) {
        bounds = QRect(0, 0, image->width(), image->height());
    }

    QPolygonF srcQuad;
    srcQuad << QPointF(bounds.topLeft())
            << QPointF(bounds.topRight())
            << QPointF(bounds.bottomRight())
            << QPointF(bounds.bottomLeft());

    QPolygonF dstQuad;
    dstQuad << QPointF(x0, y0)
            << QPointF(x1, y1)
            << QPointF(x2, y2)
            << QPointF(x3, y3);

    QTransform tf;
    if (!QTransform::quadToQuad(srcQuad, dstQuad, tf)) {
        return false;
    }
    if (!tf.isInvertible()) {
        return false;
    }
    if (tf.isIdentity()) {
        return true;
    }

    const bool activeSel = hasSelection();
    if (activeSel) {
        KisPaintDeviceSP temp = new KisPaintDevice(image->colorSpace());
        temp->setDefaultBounds(new KisDefaultBounds(image));
        QRect selBounds = m_selection->selectedExactRect().intersected(QRect(0, 0, image->width(), image->height()));
        if (selBounds.isEmpty()) selBounds = bounds;

        KisPainter p0(temp);
        p0.setSelection(m_selection);
        p0.bitBlt(selBounds.topLeft(), device, selBounds);
        p0.end();

        device->clearSelection(m_selection);

        KisPerspectiveTransformWorker workerSel(temp, tf, false, 0);
        workerSel.setForceSubPixelTranslation(true);
        workerSel.run(KisPerspectiveTransformWorker::Bilinear);

        KisPainter p2(device);
        p2.setCompositeOpId(COMPOSITE_OVER);
        QRect tempBounds = temp->exactBounds();
        p2.bitBlt(tempBounds.topLeft(), temp, tempBounds);
        p2.end();

        KisPerspectiveTransformWorker workerMask(m_selection->pixelSelection(), tf, false, 0);
        workerMask.setForceSubPixelTranslation(true);
        workerMask.run(KisPerspectiveTransformWorker::Bilinear);
        m_selection->updateProjection();
    } else {
        KisPaintDeviceSP src = new KisPaintDevice(*device);
        device->clear();

        KisPaintDeviceSP tmp = new KisPaintDevice(src->colorSpace());
        tmp->setDefaultBounds(new KisDefaultBounds(image));
        tmp->prepareClone(src);
        tmp->makeCloneFromRough(src, src->extent());

        KisPerspectiveTransformWorker worker(tmp, tf, false, 0);
        worker.setForceSubPixelTranslation(true);
        worker.run(KisPerspectiveTransformWorker::Bilinear);

        KisPainter painter(device);
        QRect mergeRect = tmp->extent();
        painter.bitBlt(mergeRect.topLeft(), tmp, mergeRect);
        painter.end();
    }

    device->setDirty();
    recompositeProjection();
    markDirty();
    txn.commit(image->undoAdapter());
    m_redoCount = 0;
    return true;
}

bool ReverieCore::applyWarpMeshTransform(
    const QVector<QPointF> &origPoints,
    const QVector<QPointF> &transfPoints,
    double origX, double origY, double origW, double origH)
{
    KisImageSP image = m_document ? m_document : KisImageSP();
    if (!image) return false;
    KisPaintDeviceSP device = currentPaintDevice();
    if (!device) return false;
    if (origPoints.size() < 4 || origPoints.size() != transfPoints.size()) return false;

    if (!m_previewTransactions.isEmpty() || m_previewTransaction) {
        cancelTransformPreview();
    }

    KisTransaction txn(kundo2_i18n("Mesh Warp Transform"), device);

    KisWarpTransformWorker worker(
        KisWarpTransformWorker::RIGID_TRANSFORM,
        origPoints,
        transfPoints,
        1.0,
        0
    );

    const bool activeSel = hasSelection();
    if (activeSel) {
        KisPaintDeviceSP temp = new KisPaintDevice(image->colorSpace());
        temp->setDefaultBounds(new KisDefaultBounds(image));
        QRect bounds(qRound(origX), qRound(origY), qRound(origW), qRound(origH));
        QRect selBounds = m_selection->selectedExactRect().intersected(QRect(0, 0, image->width(), image->height()));
        if (selBounds.isEmpty()) selBounds = bounds;

        KisPainter p0(temp);
        p0.setSelection(m_selection);
        p0.bitBlt(selBounds.topLeft(), device, selBounds);
        p0.end();

        device->clearSelection(m_selection);

        KisPaintDeviceSP dstTemp = new KisPaintDevice(image->colorSpace());
        dstTemp->setDefaultBounds(new KisDefaultBounds(image));
        worker.run(temp, dstTemp);

        KisPainter p2(device);
        p2.setCompositeOpId(COMPOSITE_OVER);
        QRect tempBounds = dstTemp->exactBounds();
        p2.bitBlt(tempBounds.topLeft(), dstTemp, tempBounds);
        p2.end();
    } else {
        KisPaintDeviceSP src = new KisPaintDevice(*device);
        device->clear();

        KisPaintDeviceSP tmp = new KisPaintDevice(src->colorSpace());
        tmp->setDefaultBounds(new KisDefaultBounds(image));
        tmp->prepareClone(src);
        tmp->makeCloneFromRough(src, src->extent());

        worker.run(tmp, device);
    }

    device->setDirty();
    recompositeProjection();
    markDirty();
    txn.commit(image->undoAdapter());
    m_redoCount = 0;
    return true;
}

bool ReverieCore::applyTransform(double xscale, double yscale,
                                 double xshear, double yshear,
                                 double rotationRad,
                                 double xtranslate, double ytranslate,
                                 double originX, double originY)
{
    KisImageSP image = m_document ? m_document : KisImageSP();
    if (!image) return false;
    KisPaintDeviceSP device = currentPaintDevice();
    if (!device) return false;

    if (!m_previewTransactions.isEmpty() || m_previewTransaction) {
        cancelTransformPreview();
    }

    KisTransaction txn(kundo2_i18n("Transform"), device);

    QRect bounds = device->exactBounds().intersected(QRect(0, 0, image->width(), image->height()));
    if (!bounds.isValid() || bounds.isEmpty()) bounds = QRect(0, 0, image->width(), image->height());

    QPointF center;
    if (originX >= 0 && originY >= 0) {
        center = QPointF(originX, originY);
    } else {
        center = bounds.center();
    }

    QTransform tf;
    tf.scale(xscale, yscale);
    tf.shear(0, yshear);
    tf.shear(xshear, 0);
    tf.rotateRadians(rotationRad);
    QPointF mappedC = tf.map(center);
    double effectiveTx = center.x() - mappedC.x() + xtranslate;
    double effectiveTy = center.y() - mappedC.y() + ytranslate;

    const bool activeSel = hasSelection();
    if (activeSel) {
        KisPaintDeviceSP temp = new KisPaintDevice(image->colorSpace());
        QRect selBounds = m_selection->selectedExactRect().intersected(QRect(0, 0, image->width(), image->height()));
        if (selBounds.isEmpty()) selBounds = bounds;

        // Extract selected area to temp
        KisPainter p0(temp);
        p0.setSelection(m_selection);
        p0.bitBlt(selBounds.topLeft(), device, selBounds);

        // Cleanly erase selected area from device
        device->clearSelection(m_selection);

        // Transform the extracted content
        KisTransformWorker workerSel(temp,
                                     xscale, yscale,
                                     xshear, yshear,
                                     rotationRad,
                                     effectiveTx, effectiveTy,
                                     0,
                                     KisFilterStrategyRegistry::instance()->value("Bicubic"));
        workerSel.run();

        // Composite transformed temp back onto device
        KisPainter p2(device);
        p2.setCompositeOpId(COMPOSITE_OVER);
        QRect tempBounds = temp->exactBounds();
        p2.bitBlt(tempBounds.topLeft(), temp, tempBounds);

        // Transform the selection mask
        KisTransformWorker workerMask(m_selection->pixelSelection(),
                                      xscale, yscale, xshear, yshear,
                                      rotationRad,
                                      effectiveTx, effectiveTy, 0,
                                      KisFilterStrategyRegistry::instance()->value("Bilinear"));
        workerMask.run();
        m_selection->updateProjection();
    } else {
        // Krita's pattern from transformAndMergeDevice:
        // 1. Clone the source device
        // 2. Clear the original
        // 3. Transform the clone into a new temp
        // 4. Merge back onto the cleared original
        KisPaintDeviceSP src = new KisPaintDevice(*device);
        device->clear();

        // Transform src → tmp
        KisPaintDeviceSP tmp = new KisPaintDevice(src->colorSpace());
        tmp->prepareClone(src);
        tmp->makeCloneFromRough(src, src->extent());

        KisTransformWorker worker(tmp,
                                  xscale, yscale,
                                  xshear, yshear,
                                  rotationRad,
                                  effectiveTx, effectiveTy,
                                  0,
                                  KisFilterStrategyRegistry::instance()->value("Bicubic"));
        worker.run();

        // Merge transformed result back onto the cleared device
        KisPainter painter(device);
        QRect mergeRect = tmp->extent();
        painter.bitBlt(mergeRect.topLeft(), tmp, mergeRect);
        painter.end();
    }

    device->setDirty();
    recompositeProjection();
    markDirty();
    txn.commit(image->undoAdapter());
    m_redoCount = 0;
    return true;
}

// Transform several layers as one group: the rotation/scale center is the
// UNION of the targets' content bounds (or the explicit origin), so the
// layers keep their relative alignment instead of each spinning around its
// own center. One composite undo step.
bool ReverieCore::applyTransformLayers(const QVector<int> &layers,
                                       double xscale, double yscale,
                                       double xshear, double yshear,
                                       double rotationRad,
                                       double xtranslate, double ytranslate,
                                       double originX, double originY)
{
    KisImageSP image = m_document ? m_document : KisImageSP();
    if (!image) return false;
    if (!m_previewTransactions.isEmpty() || m_previewTransaction) {
        cancelTransformPreview();
    }

    QVector<KisPaintDeviceSP> devices;
    QVector<int> targets = layers.isEmpty() ? QVector<int>{m_currentLayer} : layers;
    for (int idx : targets) {
        if (idx < 0 || idx >= m_layers.size()) continue;
        KisPaintDeviceSP dev = layerPaintDeviceFor(m_layers[idx]);
        if (!dev) continue;
        bool dup = false;
        for (const KisPaintDeviceSP &d : devices) {
            if (d.data() == dev.data()) {
                dup = true;
                break;
            }
        }
        if (!dup) devices.append(dev);
    }
    if (devices.isEmpty()) return false;

    const QRect canvasRect(0, 0, image->width(), image->height());
    QRect shared;
    for (const KisPaintDeviceSP &dev : devices) {
        const QRect b = dev->exactBounds().intersected(canvasRect);
        if (!b.isEmpty()) shared = shared.isNull() ? b : shared.united(b);
    }
    if (!shared.isValid() || shared.isEmpty()) shared = canvasRect;

    QPointF center;
    if (originX >= 0 && originY >= 0) {
        center = QPointF(originX, originY);
    } else {
        center = shared.center();
    }

    QTransform tf;
    tf.scale(xscale, yscale);
    tf.shear(0, yshear);
    tf.shear(xshear, 0);
    tf.rotateRadians(rotationRad);
    QPointF mappedC = tf.map(center);
    const double effectiveTx = center.x() - mappedC.x() + xtranslate;
    const double effectiveTy = center.y() - mappedC.y() + ytranslate;

    const bool activeSel = hasSelection();
    QVector<KUndo2Command *> children;
    for (KisPaintDeviceSP device : devices) {
        KisTransaction *txn = new KisTransaction(kundo2_i18n("Transform"), device, nullptr, -1, nullptr);
        if (activeSel) {
            QRect selBounds = m_selection->selectedExactRect().intersected(canvasRect);
            if (selBounds.isEmpty()) selBounds = shared;

            KisPaintDeviceSP temp = new KisPaintDevice(image->colorSpace());
            KisPainter p0(temp);
            p0.setSelection(m_selection);
            p0.bitBlt(selBounds.topLeft(), device, selBounds);

            device->clearSelection(m_selection);

            KisTransformWorker workerSel(temp,
                                         xscale, yscale,
                                         xshear, yshear,
                                         rotationRad,
                                         effectiveTx, effectiveTy,
                                         0,
                                         KisFilterStrategyRegistry::instance()->value("Bicubic"));
            workerSel.run();

            KisPainter p2(device);
            p2.setCompositeOpId(COMPOSITE_OVER);
            QRect tempBounds = temp->exactBounds();
            p2.bitBlt(tempBounds.topLeft(), temp, tempBounds);
        } else {
            // Krita's transformAndMergeDevice pattern per device
            KisPaintDeviceSP src = new KisPaintDevice(*device);
            device->clear();

            KisPaintDeviceSP tmp = new KisPaintDevice(src->colorSpace());
            tmp->prepareClone(src);
            tmp->makeCloneFromRough(src, src->extent());

            KisTransformWorker worker(tmp,
                                      xscale, yscale,
                                      xshear, yshear,
                                      rotationRad,
                                      effectiveTx, effectiveTy,
                                      0,
                                      KisFilterStrategyRegistry::instance()->value("Bicubic"));
            worker.run();

            KisPainter painter(device);
            QRect mergeRect = tmp->extent();
            painter.bitBlt(mergeRect.topLeft(), tmp, mergeRect);
            painter.end();
        }
        device->setDirty();
        children << txn->endAndTake();
        delete txn;
    }

    // Transform the selection mask once for the whole group
    if (activeSel && m_selection && m_selection->pixelSelection()) {
        KisTransformWorker workerMask(m_selection->pixelSelection(),
                                      xscale, yscale,
                                      xshear, yshear,
                                      rotationRad,
                                      effectiveTx, effectiveTy, 0,
                                      KisFilterStrategyRegistry::instance()->value("Bilinear"));
        workerMask.run();
        m_selection->updateProjection();
    }

    recompositeProjection();
    markDirty();
    if (m_undoCaptureEnabled) {
        image->undoAdapter()->addCommand(
            new ReverieCompositeCommand(kundo2_i18n("Transform"), children));
        m_redoCount = 0;
    } else {
        qDeleteAll(children);
    }
    return true;
}

bool ReverieCore::startTransformPreview(const QVector<int> &layers, QImage* outImage)
{
    KisImageSP image = m_document;
    if (!image) return false;

    if (!m_previewTransactions.isEmpty() || m_previewTransaction) {
        cancelTransformPreview();
    }

    // Resolve the edit target set (multi-select union, else the current
    // layer) - must match applyTransformLayers exactly so the preview shows
    // the SAME layers the commit will transform around the SAME center
    QVector<int> targets = layers.isEmpty() ? QVector<int>{m_currentLayer} : layers;
    QVector<KisPaintDeviceSP> devices;
    for (int idx : targets) {
        if (idx < 0 || idx >= m_layers.size()) continue;
        KisPaintDeviceSP dev = layerPaintDeviceFor(m_layers[idx]);
        if (!dev) continue;
        bool dup = false;
        for (const KisPaintDeviceSP &d : devices) {
            if (d.data() == dev.data()) {
                dup = true;
                break;
            }
        }
        if (!dup) devices.append(dev);
    }
    if (devices.isEmpty()) return false;

    m_previewTempDevice = new KisPaintDevice(image->colorSpace());
    const bool activeSel = hasSelection();

    for (KisPaintDeviceSP device : devices) {
        KisTransaction *txn = new KisTransaction(kundo2_i18n("Transform Preview"), device, nullptr, -1, nullptr);
        m_previewTransactions.append(txn);

        QRect bounds = device->exactBounds().intersected(QRect(0, 0, image->width(), image->height()));
        if (!bounds.isValid() || bounds.isEmpty()) bounds = QRect(0, 0, image->width(), image->height());

        if (activeSel) {
            QRect selBounds = m_selection->selectedExactRect().intersected(QRect(0, 0, image->width(), image->height()));
            if (selBounds.isEmpty()) selBounds = bounds;

            KisPainter p0(m_previewTempDevice);
            p0.setSelection(m_selection);
            p0.bitBlt(selBounds.topLeft(), device, selBounds);
            p0.end();

            // Cleanly erase selected area on device so it doesn't double-render
            device->clearSelection(m_selection);
        } else {
            KisPainter p0(m_previewTempDevice);
            p0.bitBlt(bounds.topLeft(), device, bounds);
            p0.end();
            device->clear();
        }
        device->setDirty();
    }

    if (outImage) {
        const int iw = image->width();
        const int ih = image->height();
        QImage qimg(iw, ih, QImage::Format_RGBA8888);
        qimg.fill(0);
        
        QRect ext = m_previewTempDevice->exactBounds().intersected(QRect(0, 0, iw, ih));
        if (!ext.isEmpty()) {
            QByteArray raw;
            raw.resize(size_t(ext.width()) * ext.height() * 4);
            m_previewTempDevice->readBytes(reinterpret_cast<quint8 *>(raw.data()), ext.x(), ext.y(), ext.width(), ext.height());
            quint8 *dst = qimg.bits() + size_t(ext.y()) * (iw * 4) + size_t(ext.x()) * 4;
            blitBgraToRgbaFast(reinterpret_cast<const quint8 *>(raw.constData()), ext.width() * 4,
                               dst, iw * 4, ext.width(), ext.height());
        }
        *outImage = qimg;
    }

    recompositeProjection();
    markDirty();
    return true;
}

void ReverieCore::cancelTransformPreview()
{
    for (KisTransaction *txn : m_previewTransactions) {
        if (txn) {
            txn->revert();
            delete txn;
        }
    }
    m_previewTransactions.clear();
    if (m_previewTransaction) {
        m_previewTransaction->revert();
        delete m_previewTransaction;
        m_previewTransaction = nullptr;
    }
    m_previewTempDevice = nullptr;
    
    KisPaintDeviceSP device = currentPaintDevice();
    if (device) {
        device->setDirty();
    }
    recompositeProjection();
    markDirty();
}



