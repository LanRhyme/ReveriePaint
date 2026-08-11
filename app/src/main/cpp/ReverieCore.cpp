/*
 * ReverieCore - painting engine implementation
 *
 * Reuses Krita's KisDocument/KisImage/KisPaintLayer/KisPainter exactly
 * like the original CanvasWidget, but without any QWidget dependency.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

#include "ReverieCore.h"

#include <QDebug>
#include <QLineF>

#include <kis_image.h>
#include <kis_undo_store.h>
#include <kis_painter.h>
#include <KoColorSpaceRegistry.h>
#include <KoColorSpace.h>
#include <kis_paint_device.h>
#include <kis_paint_layer.h>
#include <kis_group_layer.h>
#include <kis_layer.h>
#include <kis_node.h>

ReverieCore::ReverieCore()
{
}

ReverieCore::~ReverieCore()
{
    endStrokeBatch();
    m_layers.clear();
    m_document.clear(); // KisImageSP releases the image
}

bool ReverieCore::newDocument(int width, int height)
{
    if (width <= 0 || height <= 0) {
        return false;
    }

    if (m_document) {
        delete m_document;
        m_document = nullptr;
    }

    const KoColorSpace *cs = KoColorSpaceRegistry::instance()->rgb8();
    if (!cs) {
        return false;
    }

    // Create a standalone Krita image without KisDocument/KisPart (which
    // live in kritaui and need a full QApplication). KisImage's public
    // ctor is sufficient for a single-document painting engine.
    KisImageSP image = new KisImage(nullptr /* undoStore */, width, height, cs,
                                    QStringLiteral("Untitled"));
    if (!image) {
        return false;
    }
    image->setResolution(72.0, 72.0);

    // Background paint layer (white, opaque)
    KisPaintLayerSP bg = new KisPaintLayer(image, QStringLiteral("背景"), 255, cs);
    if (!bg) {
        return false;
    }
    KoColor white(QColor(Qt::white), cs);
    bg->original()->fill(QRect(0, 0, width, height), white);
    bg->original()->setDirty();
    image->addNode(bg, image->rootLayer());

    m_document = image.data();
    m_docWidth = width;
    m_docHeight = height;
    syncLayersFromImage();
    markDirty();
    return true;
}

void ReverieCore::fillBackground(const QString &colorName)
{
    KisImageSP image = m_document;
    if (!image || m_layers.isEmpty()) {
        return;
    }
    const KoColorSpace *cs = image->colorSpace();
    QColor c(colorName);
    if (!c.isValid()) {
        c = Qt::white;
    }
    KoColor koColor(c, cs);
    KisPaintDeviceSP dev = m_layers.first().layer->original();
    dev->fill(QRect(0, 0, image->width(), image->height()), koColor);
    dev->setDirty();
    markDirty();
}

void ReverieCore::clearCanvas()
{
    fillBackground(QStringLiteral("#ffffff"));
}

void ReverieCore::setBrushColorName(const QString &colorName)
{
    QColor c(colorName);
    if (c.isValid()) {
        m_brushColor = c;
    }
}

// ---------------------------------------------------------------------------
// Layer system
// ---------------------------------------------------------------------------

void ReverieCore::syncLayersFromImage()
{
    m_layers.clear();
    KisImageSP image = m_document;
    if (!image) {
        return;
    }
    KisNodeSP root = image->rootLayer();
    if (!root) {
        return;
    }
    std::function<void(KisNodeSP)> walk = [&](KisNodeSP parent) {
        KisNodeSP node = parent->firstChild();
        while (node) {
            if (KisPaintLayer *pl = dynamic_cast<KisPaintLayer *>(node.data())) {
                LayerEntry entry;
                entry.layer = pl;
                entry.visible = pl->visible();
                entry.name = pl->name();
                m_layers.append(entry);
            }
            if (node->childCount() > 0) {
                walk(node);
            }
            node = node->nextSibling();
        }
    };
    walk(root);
    if (m_currentLayer >= m_layers.size()) {
        m_currentLayer = m_layers.isEmpty() ? 0 : m_layers.size() - 1;
    }
}

void ReverieCore::addLayer(const QString &name)
{
    KisImageSP image = m_document;
    if (!image) {
        return;
    }
    const KoColorSpace *cs = image->colorSpace();
    const int w = image->width();
    const int h = image->height();

    KisPaintLayerSP newLayer = new KisPaintLayer(image, name, 255, cs);
    if (!newLayer) {
        return;
    }
    // Give the new layer a real (transparent) device so painting works
    newLayer->original()->fill(QRect(0, 0, w, h), KoColor(Qt::transparent, cs));
    newLayer->original()->setDirty();

    if (!image->addNode(newLayer, image->rootLayer())) {
        return;
    }

    LayerEntry entry;
    entry.layer = newLayer.data();
    entry.visible = true;
    entry.name = name;
    m_layers.append(entry);
    m_currentLayer = m_layers.size() - 1;
    markDirty();
}

void ReverieCore::removeLayer(int index)
{
    if (m_layers.size() <= 1 || index < 0 || index >= m_layers.size()) {
        return;
    }
    KisImageSP image = m_document;
    if (!image) {
        return;
    }
    LayerEntry &entry = m_layers[index];
    if (entry.layer) {
        KisNodeSP node = entry.layer;
        image->removeNode(node);
    }
    m_layers.removeAt(index);
    if (m_currentLayer >= m_layers.size()) {
        m_currentLayer = m_layers.size() - 1;
    }
    markDirty();
}

void ReverieCore::setCurrentLayer(int index)
{
    if (index < 0 || index >= m_layers.size() || index == m_currentLayer) {
        return;
    }
    m_currentLayer = index;
}

QString ReverieCore::layerName(int index) const
{
    if (index < 0 || index >= m_layers.size()) {
        return QString();
    }
    return m_layers[index].name;
}

void ReverieCore::setLayerVisible(int index, bool visible)
{
    if (index < 0 || index >= m_layers.size()) {
        return;
    }
    if (m_layers[index].visible != visible) {
        m_layers[index].visible = visible;
        if (m_layers[index].layer) {
            m_layers[index].layer->setVisible(visible);
        }
        markDirty();
    }
}

bool ReverieCore::layerVisible(int index) const
{
    if (index < 0 || index >= m_layers.size()) {
        return true;
    }
    return m_layers[index].visible;
}

// ---------------------------------------------------------------------------
// Painting
// ---------------------------------------------------------------------------

KisPaintDeviceSP ReverieCore::currentPaintDevice()
{
    KisImageSP image = m_document;
    if (!image || m_layers.isEmpty()) {
        return KisPaintDeviceSP();
    }
    const int idx = qBound(0, m_currentLayer, m_layers.size() - 1);
    LayerEntry &entry = m_layers[idx];
    if (!entry.layer) {
        return KisPaintDeviceSP();
    }
    return entry.layer->original();
}

void ReverieCore::touchStrokeStart(qreal x, qreal y, qreal pressure)
{
    if (!m_document) {
        return;
    }
    m_drawing = true;
    m_strokeBatchOpen = true;
    m_lastPressure = pressure;
    m_strokeColor = m_brushColor;
    m_strokeOpacity = m_brushOpacity;
    m_strokeStartImg = QPointF(x, y);
    m_strokeSamples.clear();
}

void ReverieCore::touchStrokeMove(qreal x, qreal y, qreal pressure)
{
    if (!m_drawing || !m_strokeBatchOpen) {
        return;
    }
    const QPointF imgPos(x, y);
    const QPointF lastPos = m_strokeSamples.isEmpty()
            ? m_strokeStartImg
            : m_strokeSamples.last().imgPos;
    if (imgPos != lastPos) {
        appendStrokeSample(imgPos, pressure);
    }
}

void ReverieCore::touchStrokeEnd()
{
    if (m_strokeBatchOpen) {
        if (m_strokeSamples.isEmpty()) {
            StrokeSample s;
            s.imgPos = m_strokeStartImg;
            s.pressure = m_lastPressure;
            m_strokeSamples.append(s);
        }
        flushStrokeBatch();
        endStrokeBatch();
        m_strokeBatchOpen = false;
    }
    m_drawing = false;
}

void ReverieCore::appendStrokeSample(const QPointF &imgPos, qreal pressure)
{
    // Krita-style spacing sampling: only emit a dab when the stylus moved
    // ~20% of the brush diameter.
    const qreal spacing = qMax<qreal>(1.5, m_brushSize * 0.20);
    if (!m_strokeSamples.isEmpty()) {
        const QPointF last = m_strokeSamples.last().imgPos;
        const qreal dist = QLineF(last, imgPos).length();
        if (dist < spacing) {
            m_strokeSamples.last().pressure = pressure;
            return;
        }
    }
    StrokeSample s;
    s.imgPos = imgPos;
    s.pressure = pressure;
    m_strokeSamples.append(s);
    flushStrokeBatch();
}

void ReverieCore::flushStrokeBatch()
{
    if (m_strokeSamples.isEmpty()) {
        return;
    }

    KisImageSP image = m_document;
    if (!image) {
        m_strokeSamples.clear();
        return;
    }
    KisPaintDeviceSP device = currentPaintDevice();
    if (!device) {
        m_strokeSamples.clear();
        return;
    }
    const qreal opacity = qBound<qreal>(0.0, m_strokeOpacity, 1.0);

    // Krita-style: reuse one KisPainter for the whole stroke
    if (!m_strokePainter || m_strokeDevice != (void *)device.data()) {
        endStrokeBatch();
        m_strokeDevice = (void *)device.data();
        m_strokePainter = new KisPainter(device);
        m_strokePainter->setFillStyle(KisPainter::FillStyleForegroundColor);
        m_strokePainter->setStrokeStyle(KisPainter::StrokeStyleBrush);
    }
    KisPainter &painter = *m_strokePainter;
    const KoColorSpace *cs = image->colorSpace();
    QColor qColor(m_strokeColor);
    if (!qColor.isValid()) {
        qColor = Qt::black;
    }
    KoColor koColor(qColor, cs);
    painter.setPaintColor(koColor);
    painter.setBackgroundColor(koColor);
    painter.setOpacityF(opacity);

    // Subdivide each segment at ~brush-spacing resolution so pressure
    // ramps smoothly (Krita's KisDistanceInformation behaviour)
    const qreal subSpacing = qMax<qreal>(2.0, m_brushSize * 0.5);
    QPointF prev = m_strokeSamples.first().imgPos;
    qreal prevP = m_strokeSamples.first().pressure;
    for (int i = 1; i < m_strokeSamples.size(); ++i) {
        const QPointF cur = m_strokeSamples[i].imgPos;
        const qreal curP = m_strokeSamples[i].pressure;
        const qreal segLen = QLineF(prev, cur).length();
        const int n = qMax(1, int(qCeil(segLen / subSpacing)));
        for (int j = 0; j < n; ++j) {
            const qreal t0 = qreal(j) / n;
            const qreal t1 = qreal(j + 1) / n;
            const QPointF a(prev.x() + (cur.x() - prev.x()) * t0,
                            prev.y() + (cur.y() - prev.y()) * t0);
            const QPointF b(prev.x() + (cur.x() - prev.x()) * t1,
                            prev.y() + (cur.y() - prev.y()) * t1);
            const qreal pMid = prevP + (curP - prevP) * (t0 + t1) / 2.0;
            const qreal width = qMax<qreal>(1.0, m_brushSize * qBound<qreal>(0.0, pMid, 1.0));
            painter.drawLine(a, b, width, true);
        }
        prev = cur;
        prevP = curP;
    }
    m_strokeSamples.clear();
    markDirty();
}

void ReverieCore::endStrokeBatch()
{
    delete m_strokePainter;
    m_strokePainter = nullptr;
    m_strokeDevice = nullptr;
}

// ---------------------------------------------------------------------------
// Rendering
// ---------------------------------------------------------------------------

bool ReverieCore::renderToBuffer(quint8 *buffer, int w, int h)
{
    KisImageSP image = m_document;
    if (!image || !buffer) {
        return false;
    }
    // Composite via the projection (handles layers + visibility)
    const QImage img = image->convertToQImage(0, 0, image->width(), image->height(), nullptr);
    if (img.isNull()) {
        return false;
    }
    // Scale into the buffer (buffer is the Android bitmap, ABGR/ARGB layout)
    QImage target(img);
    if (target.width() != w || target.height() != h) {
        target = target.scaled(w, h, Qt::IgnoreAspectRatio, Qt::SmoothTransformation);
    }
    const QImage conv = target.convertToFormat(QImage::Format_RGBA8888);
    if (conv.size() != QSize(w, h)) {
        return false;
    }
    memcpy(buffer, conv.constBits(), size_t(w) * h * 4);
    return true;
}

QString ReverieCore::pickColorAt(int x, int y)
{
    KisImageSP image = m_document ? m_document : KisImageSP();
    if (!image) {
        return QString();
    }
    if (x < 0 || y < 0 || x >= image->width() || y >= image->height()) {
        return QString();
    }
    const QImage img = image->convertToQImage(0, 0, image->width(), image->height(), nullptr);
    if (img.isNull() || x >= img.width() || y >= img.height()) {
        return QString();
    }
    const QRgb c = img.pixel(x, y);
    return QStringLiteral("#%1%2%3")
            .arg(qRed(c), 2, 16, QLatin1Char('0'))
            .arg(qGreen(c), 2, 16, QLatin1Char('0'))
            .arg(qBlue(c), 2, 16, QLatin1Char('0'));
}

int ReverieCore::docWidth() const
{
    return m_docWidth;
}

int ReverieCore::docHeight() const
{
    return m_docHeight;
}
