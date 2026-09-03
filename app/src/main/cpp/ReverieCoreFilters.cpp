/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/* ============================================================
 * ReverieCoreFilters.cpp - Filter entry points: applyFilter, beginFilterPreview, commitFilter, cancelFilter
 * (part of the ReverieCore module split; shared helpers live in
 * ReverieCoreInternal.h, public API in ReverieCore.h)
 * ============================================================ */
#include "ReverieCoreInternal.h"

KisPaintDeviceSP ReverieCore::findFilterBackup(int index) const
{
    for (const auto &b : m_filterBackups) {
        if (b.index == index) return b.device;
    }
    return nullptr;
}

void ReverieCore::applyFilterMulti(const QVector<int> &indices, int filterId)
{
    if (indices.isEmpty() || !m_document) return;
    QVector<KUndo2Command *> children;

    for (int index : indices) {
        if (!isLayerEditable(index)) continue;
        KisPaintDeviceSP dev = layerPaintDeviceFor(m_layers[index]);
        if (!dev) continue;

        const QRect ext = dev->extent().intersected(QRect(0, 0, m_docWidth, m_docHeight));
        if (ext.isEmpty()) continue;

        KisTransaction txn(kundo2_i18n("Filter"), dev);
        QImage origImg(ext.size(), QImage::Format_ARGB32_Premultiplied);
        dev->readBytes(origImg.bits(), ext.x(), ext.y(), ext.width(), ext.height());
        QImage img = origImg;

        switch (filterId) {
        case 0: {  // grayscale
            for (int y = 0; y < img.height(); ++y) {
                quint8 *line = img.scanLine(y);
                for (int x = 0; x < img.width(); ++x) {
                    quint8 *px = line + x * 4;
                    const int gray = (px[2] * 299 + px[1] * 587 + px[0] * 114) / 1000;
                    px[0] = quint8(gray); px[1] = quint8(gray); px[2] = quint8(gray);
                }
            }
            break;
        }
        case 1: {  // invert
            for (int y = 0; y < img.height(); ++y) {
                quint8 *line = img.scanLine(y);
                for (int x = 0; x < img.width(); ++x) {
                    quint8 *px = line + x * 4;
                    px[0] = 255 - px[0]; px[1] = 255 - px[1]; px[2] = 255 - px[2];
                }
            }
            break;
        }
        case 2: {  // blur 3x3
            QImage tmp = img;
            const int w = img.width(), h = img.height();
            for (int y = 0; y < h; ++y) {
                quint8 *d = img.scanLine(y);
                for (int x = 0; x < w; ++x) {
                    int r = 0, g = 0, b = 0, a = 0, cnt = 0;
                    for (int dy = -1; dy <= 1; ++dy) {
                        for (int dx = -1; dx <= 1; ++dx) {
                            const int yy = y + dy, xx = x + dx;
                            if (xx >= 0 && xx < w && yy >= 0 && yy < h) {
                                const quint8 *p = tmp.constScanLine(yy) + xx * 4;
                                r += p[0]; g += p[1]; b += p[2]; a += p[3];
                                ++cnt;
                            }
                        }
                    }
                    if (cnt > 0) {
                        quint8 *px = d + x * 4;
                        px[0] = quint8(r / cnt); px[1] = quint8(g / cnt);
                        px[2] = quint8(b / cnt); px[3] = quint8(a / cnt);
                    }
                }
            }
            break;
        }
        case 3: {  // sharpen
            QImage tmp = img;
            const int w = img.width(), h = img.height();
            for (int y = 0; y < h; ++y) {
                quint8 *d = img.scanLine(y);
                for (int x = 0; x < w; ++x) {
                    int r = 0, g = 0, b = 0, a = 0;
                    for (int dy = -1; dy <= 1; ++dy) {
                        for (int dx = -1; dx <= 1; ++dx) {
                            const int yy = qBound(0, y + dy, h - 1);
                            const int xx = qBound(0, x + dx, w - 1);
                            const quint8 *p = tmp.constScanLine(yy) + xx * 4;
                            const int k = (dx == 0 && dy == 0) ? 9 : -1;
                            r += k * p[0]; g += k * p[1]; b += k * p[2]; a += k * p[3];
                        }
                    }
                    quint8 *px = d + x * 4;
                    px[0] = quint8(qBound(0, r, 255)); px[1] = quint8(qBound(0, g, 255));
                    px[2] = quint8(qBound(0, b, 255)); px[3] = quint8(qBound(0, a, 255));
                }
            }
            break;
        }
        default:
            break;
        }
        if (hasSelection()) {
            blendWithSelectionMask(img, origImg, m_selection, ext.x(), ext.y(), ext.width(), ext.height());
        }
        dev->writeBytes(img.constBits(), ext.x(), ext.y(), ext.width(), ext.height());
        dev->setDirty(ext);
        KUndo2Command *childCmd = txn.endAndTake();
        if (childCmd) {
            children.append(childCmd);
        }
    }
    if (!children.isEmpty()) {
        pushUndoCommand(new ReverieCompositeCommand(kundo2_i18n("Filter"), children));
    }
    recompositeProjection();
    markDirty();
}

void ReverieCore::applyFilter(int index, int filterId)
{
    applyFilterMulti(QVector<int>{index}, filterId);
}

void ReverieCore::beginFilterPreviewMulti(const QVector<int> &indices)
{
    m_filterBackups.clear();
    for (int index : indices) {
        if (!isLayerEditable(index)) continue;
        KisPaintDeviceSP dev = layerPaintDeviceFor(m_layers[index]);
        if (!dev) continue;

        KisPaintDeviceSP backup = new KisPaintDevice(dev->colorSpace());
        KisPainter::copyAreaOptimized(QPoint(0, 0), dev, backup, QRect(0, 0, m_docWidth, m_docHeight));
        m_filterBackups.append(FilterBackupEntry{index, backup, dev->extent()});
    }
}

void ReverieCore::beginFilterPreview(int index)
{
    beginFilterPreviewMulti(QVector<int>{index});
}

void ReverieCore::commitFilterMulti(const QVector<int> &indices, const QString &filterName)
{
    if (m_filterBackups.isEmpty()) {
        return;
    }
    if (m_document) {
        QVector<KUndo2Command *> children;
        const QRect docRect(0, 0, m_docWidth, m_docHeight);
        for (int index : indices) {
            if (!isLayerEditable(index)) continue;
            KisPaintDeviceSP dev = layerPaintDeviceFor(m_layers[index]);
            KisPaintDeviceSP backup = findFilterBackup(index);
            if (!dev || !backup) continue;
            children.append(new ReverieFilterCommitCommand(dev, backup, docRect));
        }
        if (!children.isEmpty()) {
            pushUndoCommand(new ReverieCompositeCommand(kundo2_i18n(filterName.toUtf8().constData()), children));
        }
    }
    m_filterBackups.clear();
    markDirty();
}

void ReverieCore::commitFilter(int index, const QString &filterName)
{
    commitFilterMulti(QVector<int>{index}, filterName);
}

void ReverieCore::cancelFilterMulti(const QVector<int> &indices)
{
    for (int index : indices) {
        if (!isLayerEditable(index)) continue;
        KisPaintDeviceSP dev = layerPaintDeviceFor(m_layers[index]);
        KisPaintDeviceSP backup = findFilterBackup(index);
        if (dev && backup) {
            KisPainter::copyAreaOptimized(QPoint(0, 0), backup, dev, QRect(0, 0, m_docWidth, m_docHeight));
            dev->setDirty(QRect(0, 0, m_docWidth, m_docHeight));
        }
    }
    m_filterBackups.clear();
    recompositeProjection();
    markDirty();
}

void ReverieCore::cancelFilter(int index)
{
    cancelFilterMulti(QVector<int>{index});
}

