/*
 * ReverieCore - the painting engine, extracted from CanvasWidget
 *
 * Wraps a Krita KisDocument + KisImage and provides the painting/layer
 * API used by the Compose UI through JNI. No QWidget dependencies: the
 * composited result is exposed as raw RGBA pixels for the Android bitmap.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

#ifndef REVERIECORE_H
#define REVERIECORE_H

#include <QImage>
#include <QPointF>
#include <QVector>
#include <QColor>
#include <QRect>
#include <QString>

#include <kis_types.h>

class KisImage;
class KisPaintLayer;
class KisPainter;

class ReverieCore
{
public:
    ReverieCore();
    ~ReverieCore();

    // Document
    bool newDocument(int width, int height);
    void fillBackground(const QString &colorName);
    void clearCanvas();

    // Layers (Krita KisPaintLayer based)
    void addLayer(const QString &name = QStringLiteral("图层"));
    void removeLayer(int index);
    void setCurrentLayer(int index);
    int layerCount() const { return m_layers.size(); }
    QString layerName(int index) const;
    void setLayerVisible(int index, bool visible);
    bool layerVisible(int index) const;
    int currentLayerIndex() const { return m_currentLayer; }

    // Tool mode (drives how strokes composite)
    enum ToolMode { ToolBrush, ToolEraser, ToolFill };
    void setToolMode(int mode) { m_toolMode = ToolMode(mode); }
    int toolMode() const { return int(m_toolMode); }

    // Fill the current layer's region (or whole layer) with the brush color
    void floodFillAt(int x, int y);

    // Draw a shape: 0=line, 1=rect, 2=ellipse between two points
    void drawShape(int kind, int x1, int y1, int x2, int y2);

    // Brush
    void setBrushSize(qreal size) { m_brushSize = size; }
    qreal brushSize() const { return m_brushSize; }
    void setBrushColor(const QColor &c) { m_brushColor = c; }
    void setBrushColorName(const QString &colorName);
    void setBrushOpacity(qreal opacity) { m_brushOpacity = opacity; }
    qreal brushOpacity() const { return m_brushOpacity; }
    QColor brushColor() const { return m_brushColor; }

    // Strokes (touch input; coordinates in document space)
    void touchStrokeStart(qreal x, qreal y, qreal pressure);

    // Application-level undo/redo via per-stroke layer snapshots.
    // Krita's command stack needs the full KisTransaction pipeline; for the
    // MVP we snapshot the current layer before each stroke and restore on
    // undo/redo. (KisSurrogateUndoStore still backs image-level commands.)
    bool canUndo() const { return !m_undoStack.isEmpty(); }
    bool canRedo() const { return !m_redoStack.isEmpty(); }
    void undo();
    void redo();
    void touchStrokeMove(qreal x, qreal y, qreal pressure);
    void touchStrokeEnd();

    // Rendering: fill the given RGBA buffer (w*h*4 bytes, stride w*4)
    // with the composited document. Returns true on success.
    bool renderToBuffer(quint8 *buffer, int w, int h);

    // Sample the composited color at document-space coordinates;
    // returns "#rrggbb" or empty if outside the document.
    QString pickColorAt(int x, int y);

    // Export the composited document to a PNG file. Returns true on success.
    bool savePng(const QString &path);

    // Load a PNG into a new document (single background layer).
    bool loadPng(const QString &path);
    int docWidth() const;
    int docHeight() const;

    // Called when a stroke modified content so the UI can repaint
    void setDirtyCallback(void (*cb)(void *ctx), void *ctx) {
        m_dirtyCb = cb;
        m_dirtyCtx = ctx;
    }

private:
    void syncLayersFromImage();
    KisPaintDeviceSP currentPaintDevice();
    void appendStrokeSample(const QPointF &imgPos, qreal pressure);
    void flushStrokeBatch();
    void endStrokeBatch();
    void markDirty() { if (m_dirtyCb) m_dirtyCb(m_dirtyCtx); }

    struct StrokeSample {
        QPointF imgPos;
        qreal pressure = 1.0;
    };

    struct LayerEntry {
        KisPaintLayer *layer = nullptr;
        bool visible = true;
        QString name;
    };

    KisImageSP m_document;
    QVector<LayerEntry> m_layers;   // bottom -> top
    int m_currentLayer = 0;

    // Brush state
    qreal m_brushSize = 20.0;
    QColor m_brushColor = Qt::black;
    qreal m_brushOpacity = 1.0;
    ToolMode m_toolMode = ToolBrush;

    // Stroke batching
    QVector<StrokeSample> m_strokeSamples;
    KisPainter *m_strokePainter = nullptr;
    void *m_strokeDevice = nullptr;
    bool m_strokeBatchOpen = false;
    QPointF m_strokeStartImg;
    qreal m_lastPressure = 1.0;
    QColor m_strokeColor;
    qreal m_strokeOpacity = 1.0;
    bool m_drawing = false;

    // Document size
    int m_docWidth = 0;
    int m_docHeight = 0;

    // Undo/redo snapshot stacks (serialized layer bytes per stroke)
    QVector<QByteArray> m_undoStack;
    QVector<QByteArray> m_redoStack;
    void snapshotForUndo();

    void (*m_dirtyCb)(void *) = nullptr;
    void *m_dirtyCtx = nullptr;
};

#endif // REVERIECORE_H
