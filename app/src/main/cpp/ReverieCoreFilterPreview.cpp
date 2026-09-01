/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/* ============================================================
 * ReverieCoreFilterPreview.cpp - Filter preview dispatch (color filters 0-18) on the backup device
 * (part of the ReverieCore module split; shared helpers live in
 * ReverieCoreInternal.h, public API in ReverieCore.h)
 * ============================================================ */
#include "ReverieCoreInternal.h"
#include "ReverieCoreFilterKernels.h"


void ReverieCore::ensureFilterBuffers(int width, int height)
{
    const size_t needed = static_cast<size_t>(width) * height * 4;
    if (static_cast<size_t>(m_filterWorkBuffer.size()) < needed) {
        m_filterWorkBuffer.resize(needed);
    }
    if (static_cast<size_t>(m_filterOrigBuffer.size()) < needed) {
        m_filterOrigBuffer.resize(needed);
    }
}

void ReverieCore::applyFilterPreviewMulti(const QVector<int> &indices, int filterType, double p1, double p2, double p3, double p4)
{
    if (m_filterBackups.isEmpty()) {
        beginFilterPreviewMulti(indices);
    }
    const int w = m_docWidth;
    const int h = m_docHeight;
    ensureFilterBuffers(w, h);

    quint8 *workBytes = m_filterWorkBuffer.data();
    quint8 *origBytes = m_filterOrigBuffer.data();

    for (int index : indices) {
        if (!isLayerEditable(index)) continue;
        KisPaintDeviceSP dev = layerPaintDeviceFor(m_layers[index]);
        KisPaintDeviceSP backup = findFilterBackup(index);
        if (!dev || !backup) continue;

        backup->readBytes(origBytes, 0, 0, w, h);
        memcpy(workBytes, origBytes, static_cast<size_t>(w) * h * 4);

        QImage img(workBytes, w, h, QImage::Format_ARGB32_Premultiplied);
        const QImage origImg(origBytes, w, h, QImage::Format_ARGB32_Premultiplied);

        reverieApplyScalarKernel(img, filterType, p1, p2, p3, p4);

        if (hasSelection()) {
            blendWithSelectionMask(img, origImg, m_selection, 0, 0, w, h);
        }

        dev->writeBytes(img.constBits(), 0, 0, w, h);
        dev->setDirty(QRect(0, 0, w, h));
    }
    recompositeProjection();
    markDirty();
}

void ReverieCore::applyFilterPreview(int index, int filterType, double p1, double p2, double p3, double p4)
{
    applyFilterPreviewMulti(QVector<int>{index}, filterType, p1, p2, p3, p4);
}

void ReverieCore::applyCurvesLUTPreviewMulti(const QVector<int> &indices, const quint8 *lutR, const quint8 *lutG, const quint8 *lutB)
{
    if (!lutR || !lutG || !lutB) return;
    if (m_filterBackups.isEmpty()) {
        beginFilterPreviewMulti(indices);
    }
    const int w = m_docWidth;
    const int h = m_docHeight;
    ensureFilterBuffers(w, h);

    quint8 *workBytes = m_filterWorkBuffer.data();
    quint8 *origBytes = m_filterOrigBuffer.data();

    for (int index : indices) {
        if (!isLayerEditable(index)) continue;
        KisPaintDeviceSP dev = layerPaintDeviceFor(m_layers[index]);
        KisPaintDeviceSP backup = findFilterBackup(index);
        if (!dev || !backup) continue;

        backup->readBytes(origBytes, 0, 0, w, h);
        memcpy(workBytes, origBytes, static_cast<size_t>(w) * h * 4);

        QImage img(workBytes, w, h, QImage::Format_ARGB32_Premultiplied);
        const QImage origImg(origBytes, w, h, QImage::Format_ARGB32_Premultiplied);

        reverieApplyCurvesLutKernel(img, lutR, lutG, lutB);

        if (hasSelection()) {
            blendWithSelectionMask(img, origImg, m_selection, 0, 0, w, h);
        }

        dev->writeBytes(img.constBits(), 0, 0, w, h);
        dev->setDirty(QRect(0, 0, w, h));
    }
    recompositeProjection();
    markDirty();
}

void ReverieCore::applyCurvesLUTPreview(int index, const quint8 *lutR, const quint8 *lutG, const quint8 *lutB)
{
    applyCurvesLUTPreviewMulti(QVector<int>{index}, lutR, lutG, lutB);
}

void ReverieCore::applyGradientMapPreviewMulti(const QVector<int> &indices, const quint32 *gradientLut256)
{
    if (!gradientLut256) return;
    if (m_filterBackups.isEmpty()) {
        beginFilterPreviewMulti(indices);
    }
    const int w = m_docWidth;
    const int h = m_docHeight;
    ensureFilterBuffers(w, h);

    quint8 *workBytes = m_filterWorkBuffer.data();
    quint8 *origBytes = m_filterOrigBuffer.data();

    for (int index : indices) {
        if (!isLayerEditable(index)) continue;
        KisPaintDeviceSP dev = layerPaintDeviceFor(m_layers[index]);
        KisPaintDeviceSP backup = findFilterBackup(index);
        if (!dev || !backup) continue;

        backup->readBytes(origBytes, 0, 0, w, h);
        memcpy(workBytes, origBytes, static_cast<size_t>(w) * h * 4);

        QImage img(workBytes, w, h, QImage::Format_ARGB32_Premultiplied);
        const QImage origImg(origBytes, w, h, QImage::Format_ARGB32_Premultiplied);

        reverieApplyGradientMapKernel(img, reinterpret_cast<const qint32 *>(gradientLut256));

        if (hasSelection()) {
            blendWithSelectionMask(img, origImg, m_selection, 0, 0, w, h);
        }

        dev->writeBytes(img.constBits(), 0, 0, w, h);
        dev->setDirty(QRect(0, 0, w, h));
    }
    recompositeProjection();
    markDirty();
}

void ReverieCore::applyGradientMapPreview(int index, const quint32 *gradientLut256)
{
    applyGradientMapPreviewMulti(QVector<int>{index}, gradientLut256);
}

