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
    // Blend mode: Krita composite op id ("normal", "multiply", "screen",
    // "overlay", "darken", "lighten", "difference", "add", "erase", ...)
    void setLayerBlendMode(int index, const QString &opId);
    QString layerBlendMode(int index) const;
    int currentLayerIndex() const { return m_currentLayer; }

    // Tool mode (drives how strokes composite)
    enum ToolMode { ToolBrush, ToolEraser, ToolFill, ToolSmudge };
    void setToolMode(int mode) { m_toolMode = ToolMode(mode); }
    int toolMode() const { return int(m_toolMode); }

    // Fill the current layer's region (or whole layer) with the brush color
    void floodFillAt(int x, int y);

    // Draw a shape: 0=line, 1=rect, 2=ellipse between two points
    void drawShape(int kind, int x1, int y1, int x2, int y2);

    // Draw text at (x, y) with the current brush color/size
    void drawText(int x, int y, const QString &text, qreal fontSize);

    // Lasso region ops: fill or clear the polygon defined by points
    void lassoFill(const QVector<QPoint> &points);
    void lassoClear(const QVector<QPoint> &points);

    // Liquify: push pixels within the brush radius from (fx,fy) to (tx,ty)
    void liquify(int fx, int fy, int tx, int ty);

    // Brush
    void setBrushSize(qreal size) { m_brushSize = size; }
    qreal brushSize() const { return m_brushSize; }
    void setBrushColor(const QColor &c) { m_brushColor = c; }
    void setBrushColorName(const QString &colorName);
    void setBrushOpacity(qreal opacity) { m_brushOpacity = opacity; }
    qreal brushOpacity() const { return m_brushOpacity; }
    QColor brushColor() const { return m_brushColor; }

    void commitStrokeToLayer();

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
    void touchStrokeCancel();

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
    // Force a full synchronous recomposite of the root projection. Krita's
    // projection updates are driven by dirty-region propagation, which does
    // not cover node-structure changes (add/remove layer, visibility, blend
    // mode): after those the root projection device is rebuilt empty and
    // convertToQImage returns transparent black. Krita itself uses the
    // refresh-walker + async-merger pair for exactly this case.
    void recompositeProjection();
    void appendStrokeSample(const QPointF &imgPos, qreal pressure);
    void flushStrokeBatch();
    void endStrokeBatch();

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
    // True once the finger moved beyond the start point; a single-sample
    // flush is only a dot when this is false (a genuine tap). Trailing
    // samples of a real stroke must never render as dots.
    bool m_strokeHadMove = false;
    KisPainter *m_strokePainter = nullptr;
    void *m_strokeDevice = nullptr;
    // Temporary device holding the in-progress stroke at full strength.
    // The stroke opacity is applied ONCE when the stroke is committed to
    // the layer (Krita applies opacity per dab, so overlapping dabs would
    // accumulate towards opaque; a single commit pass gives the exact
    // opacity the user set, like Procreate / 画世界).
    KisPaintDeviceSP m_strokeBuffer;
    bool m_strokeBatchOpen = false;
    QPointF m_strokeStartImg;
    qreal m_lastPressure = 1.0;
    // Rendering: the last composited dirty region, used to copy only the
    // changed rows into the Android bitmap (m_bitmapInited gates the first
    // full copy).
    QRect m_lastDirty;
    bool m_bitmapInited = false;
    QColor m_strokeColor;
    qreal m_strokeOpacity = 1.0;
    bool m_drawing = false;

    // Document size
    int m_docWidth = 0;
    int m_docHeight = 0;

    // Render cache: the full-document display image (RGBA8888) plus the
    // dirty region that still needs re-compositing. Krita's projection
    // recomputes only the tiles that changed; we re-run convertToQImage on
    // the dirty region only and copy the rest from the cached image.
    QImage m_displayImage;
    QRect m_dirtyRect;
    void markDirty() {
        markRegionDirty(QRect(0, 0, m_docWidth, m_docHeight));
    }
    void markRegionDirty(const QRect &r) {
        m_dirtyRect = m_dirtyRect.isNull() ? r : m_dirtyRect.united(r);
        if (m_dirtyCb) m_dirtyCb(m_dirtyCtx);
    }

    // Undo/redo snapshot stacks (serialized layer bytes per stroke)
    QVector<QByteArray> m_undoStack;
    QVector<QByteArray> m_redoStack;
    // Deferred snapshot: taken on the first real flush of a stroke, not at
    // touch-down, so a pure tap or an instantly-cancelled stroke never pays
    // the full-document read cost.
    bool m_snapshotPending = false;
    void snapshotForUndo();
    // Restore a snapshot, writing back only the regions that differ from the
    // current layer pixels and recompositing those regions locally (no full
    // document pass). curBytes must be the current serialized layer state.
    void applySnapshot(const QByteArray &snap, const QByteArray &curBytes);

    void (*m_dirtyCb)(void *) = nullptr;
    void *m_dirtyCtx = nullptr;
};

#endif // REVERIECORE_H
