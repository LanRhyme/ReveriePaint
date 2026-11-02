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

    invalidateRedoState("transform-perspective");

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

    // 事务必须开在全部校验之后: 顺手 return 会把未提交的 KisTransaction 析构掉,
    // ~KisTransactionData -> purgeHistory() 连带清掉这台设备更早的撤销 revision,
    // 撤销栈从此错位(撤错帧/内容乱跳)。
    KisTransaction txn(kundo2_i18n("Perspective Transform"), device);

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
        QRect srcBounds = src->exactBounds();
        if (srcBounds.isEmpty() || !srcBounds.isValid()) srcBounds = src->extent();
        if (srcBounds.isEmpty() || !srcBounds.isValid()) srcBounds = QRect(0, 0, image->width(), image->height());
        tmp->makeCloneFromRough(src, srcBounds);

        KisPerspectiveTransformWorker worker(tmp, tf, false, 0);
        worker.setForceSubPixelTranslation(true);
        worker.run(KisPerspectiveTransformWorker::Bilinear);

        KisPainter painter(device);
        QRect mergeRect = tmp->exactBounds();
        if (mergeRect.isEmpty() || !mergeRect.isValid()) mergeRect = tmp->extent();
        if (!mergeRect.isEmpty() && mergeRect.isValid()) {
            painter.bitBlt(mergeRect.topLeft(), tmp, mergeRect);
            device->setDirty(mergeRect);
        } else {
            device->setDirty(QRect(0, 0, image->width(), image->height()));
        }
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

    invalidateRedoState("transform-mesh-warp");
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
        QRect srcBounds = src->exactBounds();
        if (srcBounds.isEmpty() || !srcBounds.isValid()) srcBounds = src->extent();
        if (srcBounds.isEmpty() || !srcBounds.isValid()) srcBounds = QRect(0, 0, image->width(), image->height());
        tmp->makeCloneFromRough(src, srcBounds);

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

    invalidateRedoState("transform-apply");
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
                                       double originX, double originY,
                                       bool copyOnly)
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
    RPC_LOG("applyTransformLayersEx: targets=%d devices=%d copyOnly=%d tx=%.1f ty=%.1f rot=%.2f",
            int(targets.size()), int(devices.size()), copyOnly ? 1 : 0, xtranslate, ytranslate, rotationRad);

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
    // 每个目标设备都会开一个暂定事务 (创建时就会清掉引擎的重做信息),
    // 循环前统一让重做区失效一次。
    invalidateRedoState("transform-layers");
    QVector<KUndo2Command *> children;
    for (KisPaintDeviceSP device : devices) {
        KisTransaction *txn = nullptr;
        if (activeSel) {
            QRect selBounds = m_selection->selectedExactRect().intersected(canvasRect);
            if (selBounds.isEmpty()) selBounds = shared;

            // 先把选区内容抠进临时设备, **确认抠到了东西** 再动原件。
            // 旧顺序是"建事务 → clearSelection → 贴回": 选区里一个像素都没有
            // (或只剩全透明) 时, 贴回去的是一块空气 —— 剪切语义下原件已经被抹掉,
            // 用户看到的就是「内容凭空消失」, 而且日志里一行都没有。
            KisPaintDeviceSP temp = new KisPaintDevice(image->colorSpace());
            {
                KisPainter p0(temp);
                p0.setSelection(m_selection);
                p0.bitBlt(selBounds.topLeft(), device, selBounds);
                p0.end();
            }
            if (temp->exactBounds().isEmpty()) {
                RPC_LOG("transformLayers: selection has no pixels, layer skipped "
                        "(copyOnly=%d sel=%d,%d %dx%d)",
                        copyOnly ? 1 : 0, selBounds.x(), selBounds.y(),
                        selBounds.width(), selBounds.height());
                continue;
            }

            // 重做区已在循环前由 invalidateRedoState("transform-layers") 作废
            txn = new KisTransaction(kundo2_i18n("Transform"), device, nullptr, -1, nullptr);
            if (!copyOnly) {
                device->clearSelection(m_selection);
            }

            KisTransformWorker workerSel(temp,
                                         xscale, yscale,
                                         xshear, yshear,
                                         rotationRad,
                                         effectiveTx, effectiveTy,
                                         0,
                                         KisFilterStrategyRegistry::instance()->value("Bicubic"));
            workerSel.run();

            // 变换后的副本必须与画布有交集才贴: 落在画布外时贴了也看不见,
            // 而剪切语义下原件已经没了 —— 这种情况留日志, 下次报告能直接定位。
            const QRect tempBounds = temp->exactBounds();
            if (!tempBounds.isEmpty() && tempBounds.intersects(canvasRect)) {
                KisPainter p2(device);
                p2.setCompositeOpId(COMPOSITE_OVER);
                p2.bitBlt(tempBounds.topLeft(), temp, tempBounds);
                p2.end();
            } else if (!copyOnly) {
                RPC_LOG("transformLayers: cut result falls outside canvas, content dropped "
                        "(sel=%d,%d %dx%d tx=%.1f ty=%.1f)",
                        selBounds.x(), selBounds.y(), selBounds.width(), selBounds.height(),
                        effectiveTx, effectiveTy);
            }
        } else {
            // 重做区已在循环前由 invalidateRedoState("transform-layers") 作废
            txn = new KisTransaction(kundo2_i18n("Transform"), device, nullptr, -1, nullptr);
            // Krita's transformAndMergeDevice pattern per device
            KisPaintDeviceSP src = new KisPaintDevice(*device);
            // 复制语义 (copyOnly) 下原件必须留着: 这一支是「选区在提交时已经不在
            // 引擎里」的兜底路径, 旧代码无条件 clear() → 整个图层被抹掉再贴回变换后的
            // 副本, 用户看到的就是「选了复制, 原处内容却没了」。
            if (!copyOnly) {
                device->clear();
            }

            KisPaintDeviceSP tmp = new KisPaintDevice(src->colorSpace());
            tmp->prepareClone(src);
            QRect srcBounds = src->exactBounds();
            if (srcBounds.isEmpty() || !srcBounds.isValid()) {
                srcBounds = src->extent();
            }
            if (srcBounds.isEmpty() || !srcBounds.isValid()) {
                srcBounds = canvasRect;
            }
            tmp->makeCloneFromRough(src, srcBounds);

            KisTransformWorker worker(tmp,
                                      xscale, yscale,
                                      xshear, yshear,
                                      rotationRad,
                                      effectiveTx, effectiveTy,
                                      0,
                                      KisFilterStrategyRegistry::instance()->value("Bicubic"));
            worker.run();

            KisPainter painter(device);
            QRect mergeRect = tmp->exactBounds();
            if (mergeRect.isEmpty() || !mergeRect.isValid()) {
                mergeRect = tmp->extent();
            }
            if (!mergeRect.isEmpty() && mergeRect.isValid()) {
                painter.bitBlt(mergeRect.topLeft(), tmp, mergeRect);
                device->setDirty(mergeRect);
            } else {
                device->setDirty(canvasRect);
            }
            painter.end();
        }
        device->setDirty();
        if (txn) {
            children << txn->endAndTake();
            delete txn;
        }
    }

    // 整个目标集都没写出任何像素 (选区里没内容 / 目标层全空): 不动文档,
    // 返回 false 让调用方能把「这次变换等于没发生」说出来, 而不是静默成功。
    if (children.isEmpty()) {
        RPC_LOG("transformLayers: nothing to transform (targets=%d activeSel=%d copyOnly=%d)",
                int(devices.size()), activeSel ? 1 : 0, copyOnly ? 1 : 0);
        return false;
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

bool ReverieCore::startTransformPreview(const QVector<int> &layers, QImage* outImage, bool copyOnly)
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
    m_previewDevices = devices;
    const bool activeSel = hasSelection();
    RPC_LOG("startTransformPreview: targets=%d devices=%d activeSel=%d copyOnly=%d",
            int(targets.size()), int(devices.size()), activeSel ? 1 : 0, copyOnly ? 1 : 0);

    for (KisPaintDeviceSP device : devices) {
        invalidateRedoState("transform-preview");
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

            // Cleanly erase selected area on device so it doesn't double-render, unless copyOnly
            if (!copyOnly) {
                device->clearSelection(m_selection);
            }
        } else {
            KisPainter p0(m_previewTempDevice);
            p0.bitBlt(bounds.topLeft(), device, bounds);
            p0.end();
            // 复制语义 (copyOnly) 下预览**绝不能**涂改原件: 「复制到当前图层」
            // 只要引擎侧选区为空就会走到这里, 一旦无条件 clear() 整层就被清空,
            // 屏幕上只剩那张预览位图在飘 —— 预览位图没送到 UI (返回失败/首帧未到)
            // 或者用户此时切走, 看到的就是「内容凭空消失」。
            if (!copyOnly) {
                device->clear();
            }
        }
        device->setDirty();
    }

    if (outImage) {
        const int iw = image->width();
        const int ih = image->height();
        QImage qimg(iw, ih, QImage::Format_RGBA8888_Premultiplied);
        qimg.fill(0);
        
        QRect ext = m_previewTempDevice->exactBounds().intersected(QRect(0, 0, iw, ih));
        if (!ext.isEmpty()) {
            QByteArray raw;
            raw.resize(size_t(ext.width()) * ext.height() * 4);
            m_previewTempDevice->readBytes(reinterpret_cast<quint8 *>(raw.data()), ext.x(), ext.y(), ext.width(), ext.height());
            quint8 *dst = qimg.bits() + size_t(ext.y()) * (iw * 4) + size_t(ext.x()) * 4;
            blitBgraToRgbaPremultipliedFast(reinterpret_cast<const quint8 *>(raw.constData()), ext.width() * 4,
                                           dst, iw * 4, ext.width(), ext.height());
        }
        *outImage = qimg;
    }

    recompositeProjection();
    markDirty();
    return true;
}

bool ReverieCore::isTransformPreviewOpen() const
{
    return m_previewTransaction != nullptr || !m_previewTransactions.isEmpty();
}

void ReverieCore::cancelTransformPreview()
{
    RPC_LOG("cancelTransformPreview: reverting %d preview txns", int(m_previewTransactions.size()));
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
    // 预览事务被 revert() 之后, 引擎的 cancelled 列表里会多出一条"不进撤销栈"的
    // 本地 revision; 撤销栈的重做区必须一起作废, 否则下一次重做会把它当目标滚进文档
    // (笔仙) 并解引用已释放的 KisMemento (SIGABRT)。
    invalidateRedoState("transform-preview-cancel");

    KisImageSP image = m_document;
    const QRect fullRect = image ? QRect(0, 0, image->width(), image->height()) : QRect();
    for (KisPaintDeviceSP dev : m_previewDevices) {
        if (dev) {
            if (!fullRect.isEmpty()) {
                dev->setDirty(fullRect);
            } else {
                dev->setDirty();
            }
        }
    }
    m_previewDevices.clear();
    
    KisPaintDeviceSP device = currentPaintDevice();
    if (device) {
        if (!fullRect.isEmpty()) {
            device->setDirty(fullRect);
        } else {
            device->setDirty();
        }
    }
    recompositeProjection();
    markDirty();
}



