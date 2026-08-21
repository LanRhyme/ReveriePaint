/* ============================================================
 * ReverieCoreFilters.cpp - Filter entry points: applyFilter, beginFilterPreview, commitFilter, cancelFilter
 * (part of the ReverieCore module split; shared helpers live in
 * ReverieCoreInternal.h, public API in ReverieCore.h)
 * ============================================================ */
#include "ReverieCoreInternal.h"

void ReverieCore::applyFilter(int index, int filterId)
{
    if (!isLayerEditable(index)) {
        return;
    }
    KisPaintDeviceSP dev = layerPaintDeviceFor(m_layers[index]);
    if (!dev) {
        return;
    }
    const QRect ext = dev->exactBounds();
    if (ext.isEmpty()) {
        return;
    }
    // Krita-native undo: wrap the pixel filter in a transaction
    KisTransaction txn(kundo2_i18n("Filter"), dev);
    QImage img(ext.size(), QImage::Format_ARGB32_Premultiplied);
    dev->readBytes(img.bits(), ext.x(), ext.y(), ext.width(), ext.height());
    switch (filterId) {
    case 0: {  // grayscale (RGBA8888 byte order: R,G,B,A)
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
    case 1: {  // invert (keep alpha)
        for (int y = 0; y < img.height(); ++y) {
            quint8 *line = img.scanLine(y);
            for (int x = 0; x < img.width(); ++x) {
                quint8 *px = line + x * 4;
                px[0] = 255 - px[0]; px[1] = 255 - px[1]; px[2] = 255 - px[2];
            }
        }
        break;
    }
    case 2: {  // box blur 3x3, two passes
        QImage tmp = img;
        const int w = img.width(), h = img.height();
        for (int pass = 0; pass < 2; ++pass) {
            for (int y = 0; y < h; ++y) {
                quint8 *d = img.scanLine(y);
                for (int x = 0; x < w; ++x) {
                    int r = 0, g = 0, b = 0, a = 0, nn = 0;
                    for (int dy = -1; dy <= 1; ++dy) {
                        for (int dx = -1; dx <= 1; ++dx) {
                            const int yy = qBound(0, y + dy, h - 1);
                            const int xx = qBound(0, x + dx, w - 1);
                            const quint8 *p = tmp.constScanLine(yy) + xx * 4;
                            r += p[0]; g += p[1]; b += p[2]; a += p[3];
                            ++nn;
                        }
                    }
                    quint8 *px = d + x * 4;
                    px[0] = quint8(r / nn); px[1] = quint8(g / nn);
                    px[2] = quint8(b / nn); px[3] = quint8(a / nn);
                }
            }
            tmp = img;
        }
        break;
    }
    case 3: {  // sharpen 3x3
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
        return;
    }
    dev->writeBytes(img.constBits(), ext.x(), ext.y(), ext.width(), ext.height());
    dev->setDirty(ext);
    markDirty();
    if (m_document) {
        txn.commit(m_document->undoAdapter());
        m_redoCount = 0;
    }
}

void ReverieCore::beginFilterPreview(int index)
{
    if (index < 0 || index >= m_layers.size()) return;
    KisImageSP image = m_document;
    if (!image) return;
    KisPaintDeviceSP dev = layerPaintDeviceFor(m_layers[index]);
    if (!dev) return;
    m_filterBackupIndex = index;
    m_filterBackupDevice = new KisPaintDevice(image->colorSpace());

    const QRect full(0, 0, m_docWidth, m_docHeight);
    m_filterBackupExt = dev->exactBounds();
    const bool isFilterLayer = m_layers[index].name.contains(QStringLiteral("滤镜")) || m_filterBackupExt.isEmpty();
    if (isFilterLayer) {
        // Composite all visible layers strictly below this adjustment layer (0 up to index)
        m_filterBackupDevice->fill(full, KoColor(Qt::transparent, image->colorSpace()));
        compositeRange(m_filterBackupDevice, 0, index, full);
        m_filterBackupExt = full;
    } else {
        KisPainter::copyAreaOptimized(QPoint(0, 0), dev, m_filterBackupDevice, full);
    }
}


void ReverieCore::commitFilter(int index, const QString &filterName)
{
    if (!isLayerEditable(index) || !m_filterBackupDevice) {
        m_filterBackupDevice = nullptr;
        return;
    }
    KisPaintDeviceSP dev = layerPaintDeviceFor(m_layers[index]);
    if (!dev) {
        m_filterBackupDevice = nullptr;
        return;
    }
    if (m_document) {
        KisTransaction txn(kundo2_i18n(filterName.toUtf8().constData()), dev);
        txn.commit(m_document->undoAdapter());
        m_redoCount = 0;
    }
    m_filterBackupDevice = nullptr;
    m_filterBackupIndex = -1;
    markDirty();
}

void ReverieCore::cancelFilter(int index)
{
    if (index >= 0 && index < m_layers.size() && m_filterBackupDevice && m_filterBackupIndex == index) {
        KisPaintDeviceSP dev = layerPaintDeviceFor(m_layers[index]);
        if (dev) {
            KisPainter::copyAreaOptimized(QPoint(0, 0), m_filterBackupDevice, dev, QRect(0, 0, m_docWidth, m_docHeight));
            dev->setDirty(QRect(0, 0, m_docWidth, m_docHeight));
            recompositeProjection();
            markDirty();
        }
    }
    m_filterBackupDevice = nullptr;
    m_filterBackupIndex = -1;
}

