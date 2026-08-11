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
#include <QPainter>
#include <QFont>
#include <QFontMetrics>
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

void ReverieCore::setLayerBlendMode(int index, const QString &opId)
{
    if (index < 0 || index >= m_layers.size()) {
        return;
    }
    if (m_layers[index].layer) {
        m_layers[index].layer->setCompositeOpId(opId);
        markDirty();
    }
}

QString ReverieCore::layerBlendMode(int index) const
{
    if (index < 0 || index >= m_layers.size() || !m_layers[index].layer) {
        return QStringLiteral("normal");
    }
    return m_layers[index].layer->compositeOpId();
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
    snapshotForUndo();
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
    qreal opacity = qBound<qreal>(0.0, m_strokeOpacity, 1.0);
    // Smudge: a translucent smearing pass (MVP approximation of the real
    // smudge brush which pushes color along the stroke path).
    if (m_toolMode == ToolSmudge) {
        opacity = qMin<qreal>(opacity, 0.12);
    }

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
    // Eraser composites with the erase op (transparent); brush uses normal.
    // KisPainter's API takes the composite op id string.
    painter.setCompositeOpId(m_toolMode == ToolEraser
        ? QStringLiteral("erase")
        : QStringLiteral("normal"));

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
            // Soft-edge dab: draw a small line between a and b with a soft
            // brush tip via KisPainter's drawLine (hard) replaced by a
            // soft dab using the painter's dab functionality is complex;
            // MVP uses drawLine with a slightly larger width and relies on
            // the antialiased edge. For a softer look we could render dabs
            // via QRadialGradient into the device - deferred.
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
// Undo / redo
// ---------------------------------------------------------------------------

void ReverieCore::snapshotForUndo()
{
    KisImageSP image = m_document ? m_document : KisImageSP();
    if (!image || m_layers.isEmpty()) {
        return;
    }
    const int w = image->width();
    const int h = image->height();
    QByteArray bytes;
    for (const LayerEntry &entry : m_layers) {
        if (!entry.layer) continue;
        QByteArray layerBytes(w * h * 4, Qt::Uninitialized);
        entry.layer->original()->readBytes(
            reinterpret_cast<quint8 *>(layerBytes.data()), 0, 0, w, h);
        bytes.append(layerBytes);
    }
    m_undoStack.append(bytes);
    // Cap the stack: 32 snapshots * layers * ~8MB each (1080x1920). This is
    // heavy but acceptable for an MVP; a real implementation would use
    // Krita's KisTransaction + KisSurrogateUndoStore.
    if (m_undoStack.size() > 32) {
        m_undoStack.removeFirst();
    }
    m_redoStack.clear();
}

void ReverieCore::undo()
{
    if (m_undoStack.isEmpty()) {
        return;
    }
    KisImageSP image = m_document ? m_document : KisImageSP();
    if (!image || m_layers.isEmpty()) {
        return;
    }
    const int w = image->width();
    const int h = image->height();
    const int layerCount = m_layers.size();

    // Snapshot current state into redo
    QByteArray cur;
    for (const LayerEntry &entry : m_layers) {
        if (!entry.layer) continue;
        QByteArray layerBytes(w * h * 4, Qt::Uninitialized);
        entry.layer->original()->readBytes(
            reinterpret_cast<quint8 *>(layerBytes.data()), 0, 0, w, h);
        cur.append(layerBytes);
    }
    m_redoStack.append(cur);

    // Restore the undo snapshot (must match layer count)
    const QByteArray snap = m_undoStack.takeLast();
    const int expected = w * h * 4 * layerCount;
    if (snap.size() == expected) {
        const quint8 *p = reinterpret_cast<const quint8 *>(snap.constData());
        for (int i = 0; i < layerCount; ++i) {
            if (!m_layers[i].layer) continue;
            m_layers[i].layer->original()->writeBytes(p, 0, 0, w, h);
            m_layers[i].layer->original()->setDirty();
            p += size_t(w) * h * 4;
        }
    }
    markDirty();
}

void ReverieCore::redo()
{
    if (m_redoStack.isEmpty()) {
        return;
    }
    KisImageSP image = m_document ? m_document : KisImageSP();
    if (!image || m_layers.isEmpty()) {
        return;
    }
    const int w = image->width();
    const int h = image->height();
    const int layerCount = m_layers.size();

    QByteArray cur;
    for (const LayerEntry &entry : m_layers) {
        if (!entry.layer) continue;
        QByteArray layerBytes(w * h * 4, Qt::Uninitialized);
        entry.layer->original()->readBytes(
            reinterpret_cast<quint8 *>(layerBytes.data()), 0, 0, w, h);
        cur.append(layerBytes);
    }
    m_undoStack.append(cur);

    const QByteArray snap = m_redoStack.takeLast();
    const int expected = w * h * 4 * layerCount;
    if (snap.size() == expected) {
        const quint8 *p = reinterpret_cast<const quint8 *>(snap.constData());
        for (int i = 0; i < layerCount; ++i) {
            if (!m_layers[i].layer) continue;
            m_layers[i].layer->original()->writeBytes(p, 0, 0, w, h);
            m_layers[i].layer->original()->setDirty();
            p += size_t(w) * h * 4;
        }
    }
    markDirty();
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
    // Cache the composited full-res image; re-composite only when the
    // projection extent (content bounds) or document size changed. This
    // avoids re-scanning every layer on every frame during a stroke.
    if (m_renderCache.isNull() || m_renderDirty) {
        m_renderCache = image->convertToQImage(0, 0, image->width(), image->height(), nullptr);
        m_renderDirty = false;
    }
    if (m_renderCache.isNull()) {
        return false;
    }
    QImage target(m_renderCache);
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

void ReverieCore::floodFillAt(int x, int y)
{
    KisImageSP image = m_document ? m_document : KisImageSP();
    if (!image) {
        return;
    }
    if (x < 0 || y < 0 || x >= image->width() || y >= image->height()) {
        return;
    }
    KisPaintDeviceSP device = currentPaintDevice();
    if (!device) {
        return;
    }
    const KoColorSpace *cs = image->colorSpace();
    QColor qColor(m_brushColor);
    if (!qColor.isValid()) {
        qColor = Qt::black;
    }
    KoColor koColor(qColor, cs);

    // MVP: whole-layer fill with the brush color. A proper connected-region
    // flood fill needs the fill tool engine (KisFillTool) which lives in
    // kritaui; we keep the engine lean and fill the whole layer.
    Q_UNUSED(x); Q_UNUSED(y);
    device->fill(QRect(0, 0, image->width(), image->height()), koColor);
    device->setDirty();
    markDirty();
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

void ReverieCore::drawShape(int kind, int x1, int y1, int x2, int y2)
{
    KisImageSP image = m_document ? m_document : KisImageSP();
    if (!image) {
        return;
    }
    KisPaintDeviceSP device = currentPaintDevice();
    if (!device) {
        return;
    }
    const int w = image->width();
    const int h = image->height();
    // Bounds of the shape region
    QRect region(QPoint(qMin(x1, x2), qMin(y1, y2)), QPoint(qMax(x1, x2), qMax(y1, y2)));
    region = region.adjusted(-int(m_brushSize), -int(m_brushSize),
                             int(m_brushSize), int(m_brushSize))
                 .intersected(QRect(0, 0, w, h));
    if (region.isEmpty()) {
        return;
    }

    // Read the current layer content into a QImage, draw the shape with
    // QPainter (supports line/rect/ellipse), then write it back.
    QImage layerImg(region.size(), QImage::Format_ARGB32_Premultiplied);
    {
        const qint32 rw = region.width();
        const qint32 rh = region.height();
        QVector<quint8> bytes(size_t(rw) * rh * 4);
        device->readBytes(bytes.data(), region.x(), region.y(), rw, rh);
        // Copy bytes (Krita RGBA order) into QImage; QImage ARGB32 is
        // byte-order RGBA on little-endian, so a straight memcpy works.
        memcpy(layerImg.bits(), bytes.constData(), size_t(rw) * rh * 4);
    }

    const KoColorSpace *cs = image->colorSpace();
    QColor qColor(m_brushColor);
    if (!qColor.isValid()) {
        qColor = Qt::black;
    }
    qColor.setAlphaF(qBound<qreal>(0.0, m_brushOpacity, 1.0));
    const qreal penWidth = qMax<qreal>(1.0, m_brushSize);

    QPainter painter(&layerImg);
    painter.setRenderHint(QPainter::Antialiasing, true);
    painter.setCompositionMode(QPainter::CompositionMode_Source);
    QPen pen(qColor, penWidth, Qt::SolidLine, Qt::RoundCap, Qt::RoundJoin);
    painter.setPen(pen);
    painter.setBrush(Qt::NoBrush);

    const QPointF p1(x1 - region.x(), y1 - region.y());
    const QPointF p2(x2 - region.x(), y2 - region.y());
    const QRectF r(QRectF(p1, p2).normalized());
    switch (kind) {
    case 1: painter.drawRect(r); break;            // rectangle
    case 2: painter.drawEllipse(r); break;         // ellipse
    default: painter.drawLine(p1, p2); break;      // line
    }
    painter.end();

    const qint32 rw = region.width();
    const qint32 rh = region.height();
    device->writeBytes(layerImg.constBits(), region.x(), region.y(), rw, rh);
    device->setDirty();
    markDirty();
}

void ReverieCore::drawText(int x, int y, const QString &text, qreal fontSize)
{
    KisImageSP image = m_document ? m_document : KisImageSP();
    if (!image || text.isEmpty()) {
        return;
    }
    KisPaintDeviceSP device = currentPaintDevice();
    if (!device) {
        return;
    }
    const int w = image->width();
    const int h = image->height();

    QFont font;
    font.setPointSizeF(fontSize);
    QFontMetrics fm(font);
    const QRect bounds = fm.boundingRect(text).adjusted(-8, -8, 8, 8);
    const QRect region = bounds.translated(x, y).intersected(QRect(0, 0, w, h));
    if (region.isEmpty()) {
        return;
    }

    QImage layerImg(region.size(), QImage::Format_ARGB32_Premultiplied);
    {
        QVector<quint8> bytes(size_t(region.width()) * region.height() * 4);
        device->readBytes(bytes.data(), region.x(), region.y(), region.width(), region.height());
        memcpy(layerImg.bits(), bytes.constData(), size_t(region.width()) * region.height() * 4);
    }

    QColor qColor(m_brushColor);
    if (!qColor.isValid()) {
        qColor = Qt::black;
    }
    qColor.setAlphaF(qBound<qreal>(0.0, m_brushOpacity, 1.0));

    QPainter painter(&layerImg);
    painter.setRenderHint(QPainter::Antialiasing, true);
    painter.setCompositionMode(QPainter::CompositionMode_Source);
    painter.setFont(font);
    painter.setPen(qColor);
    painter.drawText(QPoint(x - region.x(), y - region.y()), text);
    painter.end();

    device->writeBytes(layerImg.constBits(), region.x(), region.y(),
                       region.width(), region.height());
    device->setDirty();
    markDirty();
}

bool ReverieCore::savePng(const QString &path)
{
    KisImageSP image = m_document ? m_document : KisImageSP();
    if (!image) {
        return false;
    }
    const QImage img = image->convertToQImage(0, 0, image->width(), image->height(), nullptr);
    if (img.isNull()) {
        return false;
    }
    return img.save(path, "PNG");
}

bool ReverieCore::loadPng(const QString &path)
{
    QImage img(path);
    if (img.isNull()) {
        return false;
    }
    if (!newDocument(img.width(), img.height())) {
        return false;
    }
    // Blit the loaded pixels into the background layer
    KisImageSP image = m_document;
    if (!image || m_layers.isEmpty()) {
        return false;
    }
    KisPaintDeviceSP dev = m_layers.first().layer->original();
    const KoColorSpace *cs = image->colorSpace();
    const QImage conv = img.convertToFormat(QImage::Format_ARGB32);
    for (int y = 0; y < conv.height(); ++y) {
        for (int x = 0; x < conv.width(); ++x) {
            const QRgb px = conv.pixel(x, y);
            KoColor kc(QColor(qRed(px), qGreen(px), qBlue(px), qAlpha(px)), cs);
            dev->setPixel(x, y, kc);
        }
    }
    dev->setDirty();
    markDirty();
    return true;
}

int ReverieCore::docWidth() const
{
    return m_docWidth;
}

int ReverieCore::docHeight() const
{
    return m_docHeight;
}
