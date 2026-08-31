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


void ReverieCore::applyFilterPreview(int index, int filterType, double p1, double p2, double p3, double p4)
{
    if (!isLayerEditable(index)) return;
    if (!m_filterBackupDevice || m_filterBackupIndex != index) {
        beginFilterPreview(index);
    }
    KisPaintDeviceSP dev = layerPaintDeviceFor(m_layers[index]);
    if (!dev || !m_filterBackupDevice) return;

    const int w = m_docWidth;
    const int h = m_docHeight;
    QImage origImg(w, h, QImage::Format_ARGB32_Premultiplied);
    m_filterBackupDevice->readBytes(origImg.bits(), 0, 0, w, h);
    QImage img = origImg;

    reverieApplyScalarKernel(img, filterType, p1, p2, p3, p4);

    if (hasSelection()) {
        blendWithSelectionMask(img, origImg, m_selection, 0, 0, w, h);
    }

    dev->writeBytes(img.constBits(), 0, 0, w, h);
    dev->setDirty(QRect(0, 0, w, h));
    recompositeProjection();
    markDirty();
}

void ReverieCore::applyCurvesLUTPreview(int index, const quint8 *lutR, const quint8 *lutG, const quint8 *lutB)
{
    if (!isLayerEditable(index) || !lutR || !lutG || !lutB) return;
    if (!m_filterBackupDevice || m_filterBackupIndex != index) {
        beginFilterPreview(index);
    }
    KisPaintDeviceSP dev = layerPaintDeviceFor(m_layers[index]);
    if (!dev || !m_filterBackupDevice) return;

    const int w = m_docWidth;
    const int h = m_docHeight;
    QImage origImg(w, h, QImage::Format_ARGB32_Premultiplied);
    m_filterBackupDevice->readBytes(origImg.bits(), 0, 0, w, h);
    QImage img = origImg;

    reverieApplyCurvesLutKernel(img, lutR, lutG, lutB);

    if (hasSelection()) {
        blendWithSelectionMask(img, origImg, m_selection, 0, 0, w, h);
    }

    dev->writeBytes(img.constBits(), 0, 0, w, h);
    dev->setDirty(QRect(0, 0, w, h));
    recompositeProjection();
    markDirty();
}

void ReverieCore::applyGradientMapPreview(int index, const quint32 *gradientLut256)
{
    if (!isLayerEditable(index) || !gradientLut256) return;
    if (!m_filterBackupDevice || m_filterBackupIndex != index) {
        beginFilterPreview(index);
    }
    KisPaintDeviceSP dev = layerPaintDeviceFor(m_layers[index]);
    if (!dev || !m_filterBackupDevice) return;

    const int w = m_docWidth;
    const int h = m_docHeight;
    QImage origImg(w, h, QImage::Format_ARGB32_Premultiplied);
    m_filterBackupDevice->readBytes(origImg.bits(), 0, 0, w, h);
    QImage img = origImg;

    reverieApplyGradientMapKernel(img, reinterpret_cast<const qint32 *>(gradientLut256));

    if (hasSelection()) {
        blendWithSelectionMask(img, origImg, m_selection, 0, 0, w, h);
    }

    dev->writeBytes(img.constBits(), 0, 0, w, h);
    dev->setDirty(QRect(0, 0, w, h));
    recompositeProjection();
    markDirty();
}

