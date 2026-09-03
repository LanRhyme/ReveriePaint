/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/* ============================================================
 * ReverieCoreSelectionTools.cpp - Selection tools: magic wand, select-similar, contiguous fill, color-distance
 * (part of the ReverieCore module split; shared helpers live in
 * ReverieCoreInternal.h, public API in ReverieCore.h)
 * ============================================================ */
#include "ReverieCoreInternal.h"

void ReverieCore::selectShape(int kind, int x1, int y1, int x2, int y2)
{
    KisImageSP image = m_document;
    if (!image) return;
    const int iw = image->width();
    const int ih = image->height();
    const KisSelectionSP oldSel = m_selection;
    const QVector<quint8> oldMask = readSelectionMaskBytes(image, oldSel);
    const int rx = qBound(0, qMin(x1, x2), iw - 1);
    const int ry = qBound(0, qMin(y1, y2), ih - 1);
    const int rw = qBound(1, qAbs(x2 - x1), iw - rx);
    const int rh = qBound(1, qAbs(y2 - y1), ih - ry);

    QImage maskImg(iw, ih, QImage::Format_Alpha8);
    maskImg.fill(Qt::transparent);

    QPainter painter(&maskImg);
    painter.setRenderHint(QPainter::Antialiasing, true);
    painter.setBrush(Qt::white);
    painter.setPen(Qt::NoPen);

    QRect r(rx, ry, rw, rh);
    if (kind == 1) { // Ellipse
        painter.drawEllipse(r);
    } else { // Rect
        painter.drawRect(r);
    }
    painter.end();

    QVector<quint8> mask(size_t(iw) * ih);
    memcpy(mask.data(), maskImg.constBits(), size_t(iw) * ih);

    QVector<quint8> finalMask;
    const int selMode = qBound(0, (int)m_selectionMode, 3);
    if (selMode == 0) {
        finalMask = mask;
    } else {
        finalMask = combineSelectionMasks(oldMask, mask, selMode);
    }
    setSelection(selectionFromMask(image, finalMask, false));
    pushUndoCommand(new ReverieSelectionCommand(this, oldSel, oldMask, iw, ih, m_selection));
    markDirty();
}


void ReverieCore::selectPolygon(const QVector<QPoint> &points)
{
    if (points.size() < 3) return;
    KisImageSP image = m_document;
    if (!image) return;
    const int iw = image->width();
    const int ih = image->height();
    const KisSelectionSP oldSel = m_selection;
    const QVector<quint8> oldMask = readSelectionMaskBytes(image, oldSel);

    QImage maskImg(iw, ih, QImage::Format_Alpha8);
    maskImg.fill(Qt::transparent);

    QPainter painter(&maskImg);
    painter.setRenderHint(QPainter::Antialiasing, true);
    painter.setBrush(Qt::white);
    painter.setPen(Qt::NoPen);

    QPolygon poly;
    for (const QPoint &p : points) {
        poly << p;
    }
    painter.drawPolygon(poly);
    painter.end();

    QVector<quint8> mask(size_t(iw) * ih);
    memcpy(mask.data(), maskImg.constBits(), size_t(iw) * ih);

    QVector<quint8> finalMask;
    const int selMode = qBound(0, (int)m_selectionMode, 3);
    if (selMode == 0) {
        finalMask = mask;
    } else {
        finalMask = combineSelectionMasks(oldMask, mask, selMode);
    }
    setSelection(selectionFromMask(image, finalMask, false));
    pushUndoCommand(new ReverieSelectionCommand(this, oldSel, oldMask, iw, ih, m_selection));
    markDirty();
}



// Build a selection from a boolean mask (BFS / global scan results).
// Install a new selection from a mask, honouring the current merge mode

void ReverieCore::selectContiguousAt(int x, int y, int tolerance, bool sampleMerged, int expand, int feather, int closeGap)
{
    KisImageSP image = m_document;
    if (!image) {
        return;
    }
    const int iw = image->width();
    const int ih = image->height();
    if (x < 0 || y < 0 || x >= iw || y >= ih) {
        return;
    }

    KisPaintDeviceSP targetDevice = currentPaintDevice();
    if (!targetDevice) return;
    KisPaintDeviceSP sourceDevice = sampleMerged ? image->projection() : targetDevice;

    const KisSelectionSP oldSel = m_selection;
    const QVector<quint8> oldMask = readSelectionMaskBytes(image, oldSel);

    const int clampedTol = qBound(1, tolerance, 100);

    KisPixelSelectionSP newPixelSel = new KisPixelSelection(new KisSelectionDefaultBounds(targetDevice));
    KisFillPainter fillPainter(targetDevice);
    fillPainter.setWidth(iw);
    fillPainter.setHeight(ih);
    fillPainter.setFillThreshold(clampedTol);
    fillPainter.setOpacitySpread(100);
    fillPainter.setAntiAlias(true);
    fillPainter.setFeather(qBound(0, feather, 32));
    fillPainter.setCloseGap(qBound(0, closeGap, 32));
    fillPainter.setSizemod(qBound(-32, expand, 64));
    fillPainter.setUseCompositing(true);

    fillPainter.createFloodSelection(newPixelSel, x, y, sourceDevice,
        (m_selection ? m_selection->pixelSelection() : KisPixelSelectionSP()));

    QVector<quint8> mask(size_t(iw) * ih, 0);
    newPixelSel->readBytes(mask.data(), 0, 0, iw, ih);

    setSelectionFromMask(this, image, mask, int(m_selectionMode));
    pushUndoCommand(new ReverieSelectionCommand(this, oldSel, oldMask, iw, ih, m_selection));
    markDirty();
}

void ReverieCore::selectSimilarAt(int x, int y, int tolerance, bool sampleMerged)
{
    KisImageSP image = m_document;
    if (!image) {
        return;
    }
    const int iw = image->width();
    const int ih = image->height();
    const KisSelectionSP oldSel = m_selection;
    const QVector<quint8> oldMask = readSelectionMaskBytes(image, oldSel);
    if (x < 0 || y < 0 || x >= iw || y >= ih) {
        return;
    }
    // Match against the visible composite (projection) or current layer
    KisPaintDeviceSP device = sampleMerged ? image->projection() : currentPaintDevice();
    if (!device) return;
    image->waitForDone();
    QVector<quint8> bytes(size_t(iw) * ih * 4);
    device->readBytes(bytes.data(), 0, 0, iw, ih);
    const int tolSq = tolerance * tolerance;
    const int o0 = (y * iw + x) * 4;
    const int sR = bytes[o0 + 2];
    const int sG = bytes[o0 + 1];
    const int sB = bytes[o0];

    // Global scan: every pixel whose color is within tolerance (Krita's
    // similar-color selection, not connected-region limited). Direct byte
    // access avoids per-pixel qRgba/colorDistance overhead.
    QVector<quint8> mask(size_t(iw) * ih, 0);
    const size_t nPix = size_t(iw) * ih;
    size_t matched = 0;
    for (size_t i = 0; i < nPix; ++i) {
        const int o = int(i * 4);
        const int dr = bytes[o + 2] - sR, dg = bytes[o + 1] - sG, db = bytes[o] - sB;
        if (dr * dr + dg * dg + db * db <= tolSq) {
            mask[i] = 255;
            ++matched;
        }
    }
    if (matched == nPix) {
        this->setSelection(selectionFromMask(image, mask, true));
        pushUndoCommand(new ReverieSelectionCommand(this, oldSel, oldMask, iw, ih, m_selection));
        markDirty();
        return;
    }
    setSelectionFromMask(this, image, mask, int(m_selectionMode));
    pushUndoCommand(new ReverieSelectionCommand(this, oldSel, oldMask, iw, ih, m_selection));
    markDirty();
}

void ReverieCore::moveLayerContent(int dx, int dy)
{
    KisImageSP image = m_document;
    if (!image) return;
    KisPaintDeviceSP device = currentPaintDevice();
    if (!device) return;
    if (dx == 0 && dy == 0) return;

    const bool activeSel = hasSelection();

    if (activeSel) {
        // Krita MoveSelectionStrokeStrategy: cut selected pixels, shift them,
        // paste back, then shift the selection mask to follow.
        KisTransaction txn(kundo2_i18n("Move"), device);

        QRect selBounds = m_selection->selectedExactRect()
                              .intersected(QRect(0, 0, image->width(), image->height()));
        if (selBounds.isEmpty()) {
            return;
        }

        // 1. Extract selected pixels into a temporary device
        KisPaintDeviceSP temp = new KisPaintDevice(image->colorSpace());
        KisPainter gc(temp);
        gc.setSelection(m_selection);
        gc.bitBlt(selBounds.topLeft(), device, selBounds);
        gc.end();

        // 2. Clear the selected area on the original device
        device->clearSelection(m_selection);

        // 3. Shift the temp pixels by (dx, dy) and composite back
        KisPainter p2(device);
        p2.setCompositeOpId(COMPOSITE_OVER);
        QRect tempBounds = temp->exactBounds();
        p2.bitBlt(tempBounds.topLeft() + QPoint(dx, dy), temp, tempBounds);
        p2.end();

        // 4. Shift the selection mask to follow the moved pixels
        KisPixelSelectionSP ps = m_selection->pixelSelection();
        if (ps) {
            KisPaintDeviceSP selTemp = new KisPaintDevice(*ps);
            ps->clear();
            KisPainter selP(ps);
            QRect stBounds = selTemp->exactBounds();
            selP.bitBlt(stBounds.topLeft() + QPoint(dx, dy), selTemp, stBounds);
            selP.end();
            m_selection->updateProjection();
        }

        device->setDirty();
        recompositeProjection();
        markDirty();
        txn.commit(image->undoAdapter());
        m_redoCount = 0;
    } else {
        // Krita MoveNormalNodeStrategy: shift the paint device offset.
        // This is a metadata-only operation — no pixel resampling, no artifacts.
        // We wrap it in a KisTransaction so undo restores the original offset.
        KisTransaction txn(kundo2_i18n("Move"), device);
        device->moveTo(device->x() + dx, device->y() + dy);
        device->setDirty();
        recompositeProjection();
        markDirty();
        txn.commit(image->undoAdapter());
        m_redoCount = 0;
    }
}

// Move several layers' content at once (multi-select): one composite undo
// step. An empty list moves the current layer only (same behaviour as
// moveLayerContent). Selection handling mirrors the single-layer path; the
// selection mask itself shifts only once for the whole group.
void ReverieCore::moveLayerContentLayers(const QVector<int> &layers, int dx, int dy)
{
    KisImageSP image = m_document;
    if (!image) return;
    if (dx == 0 && dy == 0) return;

    // Resolve target devices (deduped, groups skipped)
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
    if (devices.isEmpty()) return;

    const bool activeSel = hasSelection();
    QVector<KUndo2Command *> children;
    bool selectionShifted = false;

    for (KisPaintDeviceSP device : devices) {
        KisTransaction *txn = new KisTransaction(kundo2_i18n("Move"), device, nullptr, -1, nullptr);
        if (activeSel) {
            // Krita MoveSelectionStrokeStrategy: cut selected pixels, shift
            // them, paste back
            QRect selBounds = m_selection->selectedExactRect()
                                  .intersected(QRect(0, 0, image->width(), image->height()));
            if (!selBounds.isEmpty()) {
                KisPaintDeviceSP temp = new KisPaintDevice(image->colorSpace());
                KisPainter gc(temp);
                gc.setSelection(m_selection);
                gc.bitBlt(selBounds.topLeft(), device, selBounds);
                gc.end();

                device->clearSelection(m_selection);

                KisPainter p2(device);
                p2.setCompositeOpId(COMPOSITE_OVER);
                QRect tempBounds = temp->exactBounds();
                p2.bitBlt(tempBounds.topLeft() + QPoint(dx, dy), temp, tempBounds);
                p2.end();
            }
        } else {
            // Krita MoveNormalNodeStrategy: metadata-only offset shift
            device->moveTo(device->x() + dx, device->y() + dy);
        }
        device->setDirty();
        children << txn->endAndTake();
        delete txn;
    }

    // Shift the selection mask once so it follows the moved pixels
    if (activeSel && !selectionShifted) {
        selectionShifted = true;
        KisPixelSelectionSP ps = m_selection->pixelSelection();
        if (ps) {
            KisPaintDeviceSP selTemp = new KisPaintDevice(*ps);
            ps->clear();
            KisPainter selP(ps);
            QRect stBounds = selTemp->exactBounds();
            selP.bitBlt(stBounds.topLeft() + QPoint(dx, dy), selTemp, stBounds);
            selP.end();
            m_selection->updateProjection();
        }
    }

    recompositeProjection();
    markDirty();
    if (m_undoCaptureEnabled) {
        image->undoAdapter()->addCommand(
            new ReverieCompositeCommand(kundo2_i18n("Move"), children));
        m_redoCount = 0;
    } else {
        qDeleteAll(children);
    }
}

QRect ReverieCore::contentBounds(const QVector<int> &layers)
{
    KisImageSP image = m_document;
    if (!image) {
        return QRect();
    }
    image->waitForDone();
    KisSelectionSP sel = image->globalSelection();
    if (sel && !sel->selectedRect().isEmpty()) {
        QRect sr = sel->selectedExactRect();
        if (!sr.isEmpty() && sr.isValid()) {
            return sr;
        }
    }
    // The edit target set (multi-select union, else the current layer) must
    // match applyTransformLayers so the rubber band + preview center agree
    // with the commit center
    QVector<int> targets = layers.isEmpty() ? QVector<int>{m_currentLayer} : layers;
    QRect unionRect;
    for (int idx : targets) {
        if (idx < 0 || idx >= m_layers.size()) continue;
        KisPaintDeviceSP dev = layerPaintDeviceFor(m_layers[idx]);
        if (!dev) continue;
        QRect eb = dev->exactBounds();
        if (!eb.isEmpty() && eb.isValid()) {
            unionRect = unionRect.isNull() ? eb : unionRect.united(eb);
        }
    }
    if (!unionRect.isNull()) {
        return unionRect;
    }
    // Fallback if the target layers are empty
    return QRect(0, 0, image->width(), image->height());
}

