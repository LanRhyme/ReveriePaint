/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/* ============================================================
 * ReverieCoreTransform.cpp - Transforms: free/perspective/distort/warp mesh preview and commit
 * (part of the ReverieCore module split; shared helpers live in
 * ReverieCoreInternal.h, public API in ReverieCore.h)
 * ============================================================ */
#include "ReverieCoreInternal.h"
#include "RenderLockGate.h"
#include <cmath>

namespace {
class ScopedImageBarrierLock {
public:
    explicit ScopedImageBarrierLock(KisImageSP image) : m_image(image)
    {
        if (m_image) {
            if (reverie::mayWaitForImage(m_image->locked())) {
                m_image->waitForDone();
            }
            m_image->barrierLock(m_image->locked());
        }
    }
    ~ScopedImageBarrierLock() { release(); }
    ScopedImageBarrierLock(const ScopedImageBarrierLock &) = delete;
    ScopedImageBarrierLock &operator=(const ScopedImageBarrierLock &) = delete;
    void release()
    {
        if (m_image) {
            m_image->unlock();
            m_image = KisImageSP();
        }
    }

private:
    KisImageSP m_image;
};

static inline bool hasValidTransformScale(double xs, double ys)
{
    return std::isfinite(xs) && std::isfinite(ys) && std::abs(xs) >= 1e-2 && std::abs(ys) >= 1e-2;
}
} // namespace

void ReverieCore::collectTransformTargets(const QVector<int> &layers,
                                          QVector<KisPaintDeviceSP> &outDevices,
                                          QVector<int> &outLayerIndices) const
{
    outDevices.clear();
    outLayerIndices.clear();
    const QVector<int> targets = layers.isEmpty() ? QVector<int>{m_currentLayer} : layers;
    for (int idx : targets) {
        if (idx < 0 || idx >= m_layers.size()) continue;
        if (!collabCanEditLayerIndex(idx)) continue;
        const LayerEntry &e = m_layers[idx];
        if (!e.node || e.isGroup || e.background || e.locked) continue;
        if (e.nodeType == NodeTypeAdjustment || e.nodeType == NodeTypeClone ||
            e.nodeType == NodeTypeFilterMask || e.nodeType == NodeTypeTransformMask ||
            e.nodeType == NodeTypeSelectionMask) {
            continue;
        }
        KisPaintDeviceSP dev;
        if (KisPaintLayer *pl = dynamic_cast<KisPaintLayer *>(e.node)) {
            dev = pl->paintDevice();
        } else if (KisMask *m = dynamic_cast<KisMask *>(e.node)) {
            dev = m->paintDevice();
        }
        if (!dev) continue;
        bool dup = false;
        for (const KisPaintDeviceSP &d : outDevices) {
            if (d.data() == dev.data()) {
                dup = true;
                break;
            }
        }
        if (!dup) {
            outDevices.append(dev);
            outLayerIndices.append(idx);
        }
    }
}

bool ReverieCore::applyPerspectiveTransform(
    double x0, double y0,
    double x1, double y1,
    double x2, double y2,
    double x3, double y3,
    double origX, double origY, double origW, double origH)
{
    return applyPerspectiveTransformLayers(
        QVector<int>{m_currentLayer}, x0, y0, x1, y1, x2, y2, x3, y3, origX, origY, origW, origH);
}

bool ReverieCore::applyPerspectiveTransformLayers(
    const QVector<int> &layers,
    double x0, double y0,
    double x1, double y1,
    double x2, double y2,
    double x3, double y3,
    double origX, double origY, double origW, double origH)
{
    KisImageSP image = m_document ? m_document : KisImageSP();
    if (!image) return false;

    // 必须在任何早期校验 return 之前先把预览长事务回滚关掉,
    // 否则图层组/不可编辑层一旦在下游早退, 预览像素被清空且 m_previewTransactions
    // 残留开着并永久拦截 ReverieCore::undo()。
    if (!m_previewTransactions.isEmpty() || m_previewTransaction) {
        cancelTransformPreview();
    }

    QVector<KisPaintDeviceSP> devices;
    QVector<int> deviceLayerIndices;
    collectTransformTargets(layers, devices, deviceLayerIndices);
    if (devices.isEmpty()) return false;

    QVector<KisPaintDeviceSP> deviceCollabBefore;
    deviceCollabBefore.reserve(deviceLayerIndices.size());
    for (int idx : deviceLayerIndices) {
        deviceCollabBefore.append(collabSnapshotLayerBefore(idx));
    }

    const QRect canvasRect(0, 0, image->width(), image->height());
    QRect bounds(qRound(origX), qRound(origY), qRound(origW), qRound(origH));
    if (bounds.isEmpty() || !bounds.isValid()) {
        for (const KisPaintDeviceSP &dev : devices) {
            const QRect b = dev->exactBounds().intersected(canvasRect);
            if (!b.isEmpty()) bounds = bounds.isNull() ? b : bounds.united(b);
        }
    }
    if (bounds.isEmpty() || !bounds.isValid()) {
        bounds = canvasRect;
    }

    const double bx = (origW > 0 && origH > 0) ? origX : bounds.x();
    const double by = (origW > 0 && origH > 0) ? origY : bounds.y();
    const double bw = (origW > 0 && origH > 0) ? origW : bounds.width();
    const double bh = (origW > 0 && origH > 0) ? origH : bounds.height();

    QPolygonF srcQuad;
    srcQuad << QPointF(bx, by)
            << QPointF(bx + bw, by)
            << QPointF(bx + bw, by + bh)
            << QPointF(bx, by + bh);

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
    invalidateRedoState("transform-perspective");
    QVector<KUndo2Command *> children;
    QVector<QPair<int, KisPaintDeviceSP>> collabBatch;
    {
        ScopedImageBarrierLock lock(image);
        for (int di = 0; di < devices.size(); ++di) {
            KisPaintDeviceSP device = devices[di];
            KisTransaction *txn = nullptr;
            if (activeSel) {
                QRect selBounds = m_selection->selectedExactRect().intersected(canvasRect);
                if (selBounds.isEmpty()) selBounds = bounds;

                KisPaintDeviceSP temp = new KisPaintDevice(image->colorSpace());
                temp->setDefaultBounds(new KisDefaultBounds(image));
                {
                    KisPainter p0(temp);
                    p0.setSelection(m_selection);
                    p0.bitBlt(selBounds.topLeft(), device, selBounds);
                    p0.end();
                }
                if (temp->exactBounds().isEmpty()) {
                    continue;
                }

                txn = new KisTransaction(kundo2_i18n("Perspective Transform"), device, nullptr, -1, nullptr);
                device->clearSelection(m_selection);

                KisPerspectiveTransformWorker workerSel(temp, tf, false, 0);
                workerSel.setForceSubPixelTranslation(true);
                workerSel.run(KisPerspectiveTransformWorker::Bilinear);

                const QRect tempBounds = temp->exactBounds();
                if (!tempBounds.isEmpty() && tempBounds.intersects(canvasRect)) {
                    KisPainter p2(device);
                    p2.setCompositeOpId(COMPOSITE_OVER);
                    p2.bitBlt(tempBounds.topLeft(), temp, tempBounds);
                    p2.end();
                }
            } else {
                if (device->exactBounds().isEmpty()) {
                    continue;
                }
                txn = new KisTransaction(kundo2_i18n("Perspective Transform"), device, nullptr, -1, nullptr);
                KisPaintDeviceSP src = new KisPaintDevice(*device);
                device->clear();

                KisPaintDeviceSP tmp = new KisPaintDevice(src->colorSpace());
                tmp->setDefaultBounds(new KisDefaultBounds(image));
                tmp->prepareClone(src);
                QRect srcBounds = src->exactBounds();
                if (srcBounds.isEmpty() || !srcBounds.isValid()) srcBounds = src->extent();
                if (srcBounds.isEmpty() || !srcBounds.isValid()) srcBounds = canvasRect;
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
                    device->setDirty(canvasRect);
                }
                painter.end();
            }
            device->setDirty();
            if (txn) {
                children << txn->endAndTake();
                delete txn;
                if (deviceCollabBefore[di]) {
                    collabBatch.append(qMakePair(deviceLayerIndices[di], deviceCollabBefore[di]));
                }
            }
        }
        if (!children.isEmpty() && activeSel && m_selection && m_selection->pixelSelection()) {
            KisPerspectiveTransformWorker workerMask(m_selection->pixelSelection(), tf, false, 0);
            workerMask.setForceSubPixelTranslation(true);
            workerMask.run(KisPerspectiveTransformWorker::Bilinear);
            m_selection->updateProjection();
        }
        lock.release();
    }

    if (children.isEmpty()) {
        return false;
    }

    recompositeProjection();
    markDirty();
    if (m_undoCaptureEnabled) {
        image->undoAdapter()->addCommand(
            new ReverieCompositeCommand(kundo2_i18n("Perspective Transform"), children));
        m_redoCount = 0;
    } else {
        qDeleteAll(children);
    }
    if (!collabBatch.isEmpty()) {
        collabRecordLayerPixelBatch(collabBatch);
    }
    return true;
}

bool ReverieCore::applyWarpMeshTransform(
    const QVector<QPointF> &origPoints,
    const QVector<QPointF> &transfPoints,
    double origX, double origY, double origW, double origH)
{
    return applyWarpMeshTransformLayers(
        QVector<int>{m_currentLayer}, origPoints, transfPoints, origX, origY, origW, origH);
}

bool ReverieCore::applyWarpMeshTransformLayers(
    const QVector<int> &layers,
    const QVector<QPointF> &origPoints,
    const QVector<QPointF> &transfPoints,
    double origX, double origY, double origW, double origH)
{
    KisImageSP image = m_document ? m_document : KisImageSP();
    if (!image) return false;

    if (!m_previewTransactions.isEmpty() || m_previewTransaction) {
        cancelTransformPreview();
    }

    if (origPoints.size() < 4 || origPoints.size() != transfPoints.size()) return false;

    QVector<KisPaintDeviceSP> devices;
    QVector<int> deviceLayerIndices;
    collectTransformTargets(layers, devices, deviceLayerIndices);
    if (devices.isEmpty()) return false;

    QVector<KisPaintDeviceSP> deviceCollabBefore;
    deviceCollabBefore.reserve(deviceLayerIndices.size());
    for (int idx : deviceLayerIndices) {
        deviceCollabBefore.append(collabSnapshotLayerBefore(idx));
    }

    const QRect canvasRect(0, 0, image->width(), image->height());
    const bool activeSel = hasSelection();
    invalidateRedoState("transform-mesh-warp");

    KisWarpTransformWorker worker(
        KisWarpTransformWorker::RIGID_TRANSFORM,
        origPoints,
        transfPoints,
        1.0,
        0
    );

    QVector<KUndo2Command *> children;
    QVector<QPair<int, KisPaintDeviceSP>> collabBatch;
    {
        ScopedImageBarrierLock lock(image);
        for (int di = 0; di < devices.size(); ++di) {
            KisPaintDeviceSP device = devices[di];
            KisTransaction *txn = nullptr;
            if (activeSel) {
                QRect bounds(qRound(origX), qRound(origY), qRound(origW), qRound(origH));
                QRect selBounds = m_selection->selectedExactRect().intersected(canvasRect);
                if (selBounds.isEmpty()) selBounds = bounds;

                KisPaintDeviceSP temp = new KisPaintDevice(image->colorSpace());
                temp->setDefaultBounds(new KisDefaultBounds(image));
                {
                    KisPainter p0(temp);
                    p0.setSelection(m_selection);
                    p0.bitBlt(selBounds.topLeft(), device, selBounds);
                    p0.end();
                }
                if (temp->exactBounds().isEmpty()) {
                    continue;
                }

                txn = new KisTransaction(kundo2_i18n("Mesh Warp Transform"), device, nullptr, -1, nullptr);
                device->clearSelection(m_selection);

                KisPaintDeviceSP dstTemp = new KisPaintDevice(image->colorSpace());
                dstTemp->setDefaultBounds(new KisDefaultBounds(image));
                worker.run(temp, dstTemp);

                const QRect tempBounds = dstTemp->exactBounds();
                if (!tempBounds.isEmpty() && tempBounds.intersects(canvasRect)) {
                    KisPainter p2(device);
                    p2.setCompositeOpId(COMPOSITE_OVER);
                    p2.bitBlt(tempBounds.topLeft(), dstTemp, tempBounds);
                    p2.end();
                }
            } else {
                if (device->exactBounds().isEmpty()) {
                    continue;
                }
                txn = new KisTransaction(kundo2_i18n("Mesh Warp Transform"), device, nullptr, -1, nullptr);
                KisPaintDeviceSP src = new KisPaintDevice(*device);
                device->clear();

                KisPaintDeviceSP tmp = new KisPaintDevice(src->colorSpace());
                tmp->setDefaultBounds(new KisDefaultBounds(image));
                tmp->prepareClone(src);
                QRect srcBounds = src->exactBounds();
                if (srcBounds.isEmpty() || !srcBounds.isValid()) srcBounds = src->extent();
                if (srcBounds.isEmpty() || !srcBounds.isValid()) srcBounds = canvasRect;
                tmp->makeCloneFromRough(src, srcBounds);

                worker.run(tmp, device);
            }
            device->setDirty();
            if (txn) {
                children << txn->endAndTake();
                delete txn;
                if (deviceCollabBefore[di]) {
                    collabBatch.append(qMakePair(deviceLayerIndices[di], deviceCollabBefore[di]));
                }
            }
        }
        if (!children.isEmpty() && activeSel && m_selection && m_selection->pixelSelection()) {
            KisPixelSelectionSP sel = m_selection->pixelSelection();
            KisPaintDeviceSP selSrc = new KisPaintDevice(*sel);
            sel->clear();
            worker.run(selSrc, sel);
            m_selection->updateProjection();
        }
        lock.release();
    }

    if (children.isEmpty()) {
        return false;
    }

    recompositeProjection();
    markDirty();
    if (m_undoCaptureEnabled) {
        image->undoAdapter()->addCommand(
            new ReverieCompositeCommand(kundo2_i18n("Mesh Warp Transform"), children));
        m_redoCount = 0;
    } else {
        qDeleteAll(children);
    }
    if (!collabBatch.isEmpty()) {
        collabRecordLayerPixelBatch(collabBatch);
    }
    return true;
}

/**
 * 变换轴心兜底: 有选区时, 轴心必须落在选区范围内。
 *
 * 为什么需要: 翻转/旋转/缩放都是"绕轴心"的 —— 轴心错位时内容会被甩到以错轴心镜像
 * 的位置(纯平移不受影响, 所以"拖动后按完成"从来不出问题)。轴心来自 UI 的变换框,
 * 而变换框是 contentBounds() = 编辑目标层的像素包围盒 ∩ 选区; 多选目标集、空图层、
 * 选区刚刚重建等情况下它可能退化成整层甚至整画布, 于是轴心跑到选区外。
 * 这里把轴心拉回选区中心 —— 至少保证"选区内容的轴心是它自己的中心", 并留痕方便
 * 下次从日志确认是不是这条路径。
 */
void ReverieCore::clampTransformPivotToSelection(QPointF &center, const char *where)
{
    if (!m_document || !m_selection || !hasSelection()) return;
    const QRect sel = m_selection->selectedExactRect().intersected(
        QRect(0, 0, m_document->width(), m_document->height()));
    if (sel.isEmpty() || !sel.isValid()) return;
    if (sel.adjusted(-2, -2, 2, 2).contains(center.toPoint())) return;
    RPC_LOG("RPC transformPivotClamped where=%s origin=%.1f,%.1f sel=%d,%d %dx%d -> %.1f,%.1f",
            where ? where : "?",
            center.x(), center.y(),
            sel.x(), sel.y(), sel.width(), sel.height(),
            sel.center().x() + 0.0, sel.center().y() + 0.0);
    center = QPointF(sel.center());
}

bool ReverieCore::applyTransform(double xscale, double yscale,
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
    if (!hasValidTransformScale(xscale, yscale) || !std::isfinite(xshear) || !std::isfinite(yshear) ||
        !std::isfinite(rotationRad) || !std::isfinite(xtranslate) || !std::isfinite(ytranslate)) {
        return false;
    }
    if (!collabCanEditLayerIndex(m_currentLayer)) return false;
    KisPaintDeviceSP device = currentPaintDevice();
    if (!device) return false;

    invalidateRedoState("transform-apply");
    KisPaintDeviceSP collabBefore = collabSnapshotLayerBefore(m_currentLayer);
    KisTransaction txn(kundo2_i18n("Transform"), device);

    QRect bounds = device->exactBounds().intersected(QRect(0, 0, image->width(), image->height()));
    if (!bounds.isValid() || bounds.isEmpty()) bounds = QRect(0, 0, image->width(), image->height());

    QPointF center;
    if (originX >= 0 && originY >= 0) {
        center = QPointF(originX, originY);
    } else {
        center = bounds.center();
        clampTransformPivotToSelection(center, "applyTransform");
    }

    QTransform SC = QTransform::fromScale(xscale, yscale);
    QTransform S;
    S.shear(0, yshear);
    S.shear(xshear, 0);
    QTransform R;
    R.rotateRadians(rotationRad);
    QTransform tfWorkerNoTrans = SC * S * R;
    QPointF mappedC = tfWorkerNoTrans.map(center);
    double effectiveTx = center.x() - mappedC.x() + xtranslate;
    double effectiveTy = center.y() - mappedC.y() + ytranslate;

    const bool activeSel = hasSelection();
    {
        ScopedImageBarrierLock lock(image);
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
        lock.release();
    }
    recompositeProjection();
    markDirty();
    finishPixelTransaction(txn);
    collabRecordLayerPixelEdit(m_currentLayer, collabBefore, QRect());
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
    if (!hasValidTransformScale(xscale, yscale) || !std::isfinite(xshear) || !std::isfinite(yshear) ||
        !std::isfinite(rotationRad) || !std::isfinite(xtranslate) || !std::isfinite(ytranslate)) {
        return false;
    }

    QVector<KisPaintDeviceSP> devices;
    QVector<int> deviceLayerIndices;
    collectTransformTargets(layers, devices, deviceLayerIndices);
    if (devices.isEmpty()) return false;

    QVector<KisPaintDeviceSP> deviceCollabBefore;
    deviceCollabBefore.reserve(deviceLayerIndices.size());
    for (int idx : deviceLayerIndices) {
        deviceCollabBefore.append(collabSnapshotLayerBefore(idx));
    }
    RPC_LOG("applyTransformLayersEx: reqTargets=%d devices=%d copyOnly=%d tx=%.1f ty=%.1f rot=%.2f sx=%.3f sy=%.3f",
            int(layers.size()), int(devices.size()), copyOnly ? 1 : 0,
            xtranslate, ytranslate, rotationRad, xscale, yscale);

    const QRect canvasRect(0, 0, image->width(), image->height());
    QRect nonMaskShared;
    QRect shared;
    for (int di = 0; di < devices.size(); ++di) {
        const QRect b = devices[di]->exactBounds().intersected(canvasRect);
        if (b.isEmpty()) continue;
        shared = shared.isNull() ? b : shared.united(b);
        const int lidx = deviceLayerIndices[di];
        if (lidx >= 0 && lidx < m_layers.size() && m_layers[lidx].nodeType != NodeTypeTransparencyMask) {
            nonMaskShared = nonMaskShared.isNull() ? b : nonMaskShared.united(b);
        }
    }
    if (!nonMaskShared.isNull()) shared = nonMaskShared;
    if (!shared.isValid() || shared.isEmpty()) shared = canvasRect;

    QPointF center;
    if (originX >= 0 && originY >= 0) {
        center = QPointF(originX, originY);
    } else {
        center = shared.center();
        clampTransformPivotToSelection(center, "applyTransformLayers");
    }

    QTransform SC = QTransform::fromScale(xscale, yscale);
    QTransform S;
    S.shear(0, yshear);
    S.shear(xshear, 0);
    QTransform R;
    R.rotateRadians(rotationRad);
    QTransform tfWorkerNoTrans = SC * S * R;
    QPointF mappedC = tfWorkerNoTrans.map(center);
    const double effectiveTx = center.x() - mappedC.x() + xtranslate;
    const double effectiveTy = center.y() - mappedC.y() + ytranslate;

    const bool activeSel = hasSelection();
    // 每个目标设备都会开一个暂定事务 (创建时就会清掉引擎的重做信息),
    // 循环前统一让重做区失效一次。
    invalidateRedoState("transform-layers");
    QVector<KUndo2Command *> children;
    QVector<QPair<int, KisPaintDeviceSP>> collabBatch;
    {
        ScopedImageBarrierLock lock(image);
        for (int di = 0; di < devices.size(); ++di) {
            KisPaintDeviceSP device = devices[di];
            KisTransaction *txn = nullptr;
            if (activeSel) {
                QRect selBounds = m_selection->selectedExactRect().intersected(canvasRect);
                if (selBounds.isEmpty()) selBounds = shared;

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
                if (device->exactBounds().isEmpty()) {
                    continue;
                }
                txn = new KisTransaction(kundo2_i18n("Transform"), device, nullptr, -1, nullptr);
                KisPaintDeviceSP src = new KisPaintDevice(*device);
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
                if (deviceCollabBefore[di]) {
                    collabBatch.append(qMakePair(deviceLayerIndices[di], deviceCollabBefore[di]));
                }
            }
        }

        // Transform the selection mask once for the whole group
        if (!children.isEmpty() && activeSel && m_selection && m_selection->pixelSelection()) {
            KisTransformWorker workerMask(m_selection->pixelSelection(),
                                          xscale, yscale,
                                          xshear, yshear,
                                          rotationRad,
                                          effectiveTx, effectiveTy, 0,
                                          KisFilterStrategyRegistry::instance()->value("Bilinear"));
            workerMask.run();
            m_selection->updateProjection();
        }
        lock.release();
    }

    // 整个目标集都没写出任何像素 (选区里没内容 / 目标层全空): 不动文档,
    // 返回 false 让调用方能把「这次变换等于没发生」说出来, 而不是静默成功。
    if (children.isEmpty()) {
        RPC_LOG("transformLayers: nothing to transform (targets=%d activeSel=%d copyOnly=%d)",
                int(devices.size()), activeSel ? 1 : 0, copyOnly ? 1 : 0);
        return false;
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
    if (!collabBatch.isEmpty()) {
        collabRecordLayerPixelBatch(collabBatch);
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

    QVector<KisPaintDeviceSP> devices;
    QVector<int> deviceLayerIndices;
    collectTransformTargets(layers, devices, deviceLayerIndices);
    if (devices.isEmpty()) return false;

    m_previewTempDevice = new KisPaintDevice(image->colorSpace());
    m_previewDevices = devices;
    const QRect docRect(0, 0, image->width(), image->height());
    const bool activeSel = hasSelection();
    RPC_LOG("startTransformPreview: reqTargets=%d devices=%d activeSel=%d copyOnly=%d",
            int(layers.size()), int(devices.size()), activeSel ? 1 : 0, copyOnly ? 1 : 0);

    invalidateRedoState("transform-preview");
    {
        ScopedImageBarrierLock lock(image);
        // 先按完整层级合成 (混合模式、不透明度、剪切链、嵌套组、描边特效、蒙版)
        // 提取预览位图, 再开启暂定事务清空原设备, 避免边抠边清空破坏合成结果。
        if (activeSel) {
            KisPaintDeviceSP fullPreview = new KisPaintDevice(image->colorSpace());
            compositeTransformPreview(fullPreview, deviceLayerIndices, docRect);
            QRect selBounds = m_selection->selectedExactRect().intersected(docRect);
            if (!selBounds.isEmpty()) {
                KisPainter p0(m_previewTempDevice);
                p0.setSelection(m_selection);
                p0.bitBlt(selBounds.topLeft(), fullPreview, selBounds);
                p0.end();
            }
        } else {
            compositeTransformPreview(m_previewTempDevice, deviceLayerIndices, docRect);
        }

        for (KisPaintDeviceSP device : devices) {
            KisTransaction *txn = new KisTransaction(kundo2_i18n("Transform Preview"), device, nullptr, -1, nullptr);
            m_previewTransactions.append(txn);
            if (!copyOnly) {
                if (activeSel) {
                    device->clearSelection(m_selection);
                } else {
                    device->clear();
                }
            }
            device->setDirty();
        }
        lock.release();
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
    KisImageSP image = m_document;
    {
        ScopedImageBarrierLock lock(image);
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
        lock.release();
    }
    recompositeProjection();
    markDirty();
}



