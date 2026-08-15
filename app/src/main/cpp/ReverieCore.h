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
#include <brushengine/kis_paintop.h>
#include <KisResourcesInterface.h>
#include <KisFakeRunnableStrokeJobsExecutor.h>

class KisImage;
class KisPaintLayer;
class KisPainter;
class KisDistanceInformation;

class KisSurrogateUndoStore;
class KisTransaction;
class KUndo2Command;

class ReverieCore
{
public:
    ReverieCore();
    ~ReverieCore();

    // Document
    bool newDocument(int width, int height);
    void fillBackground(const QString &colorName);
    void clearCanvas();

    // ================= Layers (full Krita KisNode-based system) ==========
    // Layer index 0 is the background layer: white, alpha-locked, locked,
    // not paintable / deletable / renamable / movable. New paint layers are
    // created above it. The layer list is a tree traversal order (bottom to
    // top); group layers contain children reported with layerDepth() > 0.
    int addLayer(const QString &name = QString());       // returns new index
    int addGroupLayer(const QString &name = QString());  // returns new index
    void removeLayer(int index);
    int copyLayer(int index);                            // returns new index
    int stampVisibleLayers();                            // returns new layer index
    void clearLayer(int index);
    void setCurrentLayer(int index);
    int layerCount() const { return m_layers.size(); }
    QString layerName(int index) const;
    void setLayerName(int index, const QString &name);
    bool layerVisible(int index) const;
    void setLayerVisible(int index, bool visible);
    bool layerLocked(int index) const;
    void setLayerLocked(int index, bool locked);
    bool layerAlphaLocked(int index) const;
    void setLayerAlphaLocked(int index, bool locked);
    qreal layerOpacity(int index) const;
    void setLayerOpacity(int index, qreal opacity);
    // Blend mode: Krita composite op id (full KoCompositeOpRegistry set:
    // normal, multiply, screen, overlay, darken, lighten, dodge, burn,
    // linear_burn, linear_dodge, difference, add, subtract, divide,
    // hard_light, soft_light, vivid_light, pin_light, linear light,
    // exclusion, hue, saturation, color, value, ...)
    void setLayerBlendMode(int index, const QString &opId);
    QString layerBlendMode(int index) const;
    int layerColorLabel(int index) const;
    void setLayerColorLabel(int index, int label);
    bool layerIsGroup(int index) const;
    int layerDepth(int index) const;
    bool layerBackground(int index) const;
    // Clipping mask (self-implemented: Krita only has inherit-opacity):
    // content painted on a clipped layer is masked by the next layer's alpha
    bool layerClipped(int index) const;
    void setLayerClipped(int index, bool clipped);
    void flipLayerHorizontal(int index);
    void flipLayerVertical(int index);
    bool mergeDown(int index);   // composite onto the layer below, remove self
    bool moveLayer(int fromIndex, int toIndex);            // move layer to another row's position (cross-parent ok)
    bool moveLayerAbove(int fromIndex, int aboveIndex);   // move layer above the given layer (exact sibling semantics)
    bool moveLayerToGroup(int fromIndex, int groupIndex);  // move layer to the top of a group
    bool moveLayerRelative(int fromIndex, int targetIndex, bool placeAbove); // move layer relative to target layer in hierarchy
    // Solo (独显, FolioLayers logic): toggle solo for one layer; soloing a
    // layer hides every other layer, tapping the soloed layer again restores
    void soloLayer(int index);
    bool layerSoloed(int index) const;
    int currentLayerIndex() const { return m_currentLayer; }
    // Multi-layer type creation
    enum LayerType {
        LayerTypePaint = 0,
        LayerTypeGroup = 1,
        LayerTypeFill = 2,
        LayerTypeAdjustment = 3,
        LayerTypeVector = 4,
        LayerTypeClone = 5
    };
    enum MaskType {
        MaskTypeTransparency = 0,
        MaskTypeFilter = 1,
        MaskTypeTransform = 2,
        MaskTypeSelection = 3
    };
    bool addLayerWithType(const QString &name, int type, quint32 fillColor = 0xFFFFFFFF);
    bool addMaskToLayer(int layerIndex, int maskType);
    bool removeMask(int layerIndex);
    bool rasterizeLayer(int index);
    bool flattenGroup(int index);
    bool setGroupPassThrough(int index, bool passThrough);
    bool groupPassThrough(int index) const;
    bool moveLayerUp(int index);
    bool moveLayerDown(int index);
    bool moveLayerOut(int index);

    // Filters (interactive preview & commit)
    void applyFilter(int index, int filterId);
    void beginFilterPreview(int index);
    void applyFilterPreview(int index, int filterType, double p1, double p2, double p3, double p4);
    void commitFilter(int index, const QString &filterName);
    void cancelFilter(int index);

    // Selection: build a pixel selection from the layer's alpha channel and
    // constrain painting to it (KisSelection, Krita mechanism)
    bool selectionFromLayer(int index);
    bool hasSelection() const;
    void clearSelection();
    void selectAll();
    void invertSelection();
    // Selection merge mode: 0=replace, 1=add, 2=subtract, 3=intersect
    enum SelMode { SelReplace, SelAdd, SelSubtract, SelIntersect };
    void setSelectionMode(int mode) { m_selectionMode = SelMode(mode); }
    int selectionMode() const { return int(m_selectionMode); }
    void featherSelection(int radius);
    void expandSelection(int px);
    void contractSelection(int px);
    void smoothSelection(int radius);
    // Internal helpers used by file-scope selection functions
    KisPixelSelectionSP currentSelectionPixelSelection() const;
    void setSelection(KisSelectionSP sel);
    // Export the selection mask (1 byte per pixel, 0/255) for the UI overlay
    QByteArray selectionMask() const;

    /** Selection mask downsampled to the viewport (ARGB 0xFFFFFFFF / 0
     *  per pixel), built on the render thread for the overlay. */
    QVector<quint32> selectionOverlayScaled(int vw, int vh) const;

    /** Live lasso preview: scanline-fill the polygon into a mask and
     *  downsample it to the viewport without touching the committed
     *  selection (Krita's selection tools preview the growing selection
     *  while the pointer moves). */
    QVector<quint32> previewLassoOverlay(const QVector<QPoint> &points, int vw, int vh) const;

    // Tool mode: the complete Krita tool set. Brush-family modes drive the
    // stroke composite (eraser -> erase even with a plain brush preset);
    // the others are dispatched from Kotlin/Compose with their own logic.
    enum ToolMode {
        ToolBrush, ToolEraser, ToolFill, ToolSmudge,
        ToolGradient,          // 4
        ToolSelectRect,        // 5
        ToolSelectEllipse,     // 6
        ToolSelectPolygon,     // 7
        ToolSelectSimilar,     // 8
        ToolPolygon,           // 9
        ToolPolyline,          // 10
        ToolMove,              // 11
        ToolCrop,              // 12
        ToolTransform,         // 13
    };
    void setToolMode(int mode) { m_toolMode = ToolMode(mode); }
    int toolMode() const { return int(m_toolMode); }

    // Public data structure: one entry per layer/group in tree traversal
    // order (bottom -> top). Used by file-scope helpers in ReverieCore.cpp.
    struct LayerEntry {
        KisNode *node = nullptr;      // paint layer or group layer
        bool visible = true;
        QString name;
        int depth = 0;                // group nesting depth (UI indent)
        bool isGroup = false;
        bool locked = false;          // full lock: no editing at all
        bool alphaLocked = false;     // preserve alpha (transparency lock)
        int colorLabel = 0;           // color label index 0-9
        bool clipped = false;         // clipping mask onto the layer below
        bool background = false;      // background layer (index 0)
        QVector<bool> soloPrev;       // visibility before solo (FolioLayers)
    };

    // Fill the current layer's region (or whole layer) with the brush color
    void floodFillAt(int x, int y, int tolerance = 24);

    // Draw a shape: 0=line, 1=rect, 2=ellipse between two points
    void drawShape(int kind, int x1, int y1, int x2, int y2, bool filled = false);
    void setShapeStrokeWidth(qreal w) { m_shapeStrokeWidth = w; }
    qreal shapeStrokeWidth() const { return m_shapeStrokeWidth; }
    void setShapeFilled(bool f) { m_shapeFilled = f; }
    bool shapeFilled() const { return m_shapeFilled; }
    // kind 0=line, 1=rect, 2=ellipse, 3=closed polygon, 4=polyline
    void drawPolygon(const QVector<QPoint> &points, bool closed);
    void gradientFill(int x1, int y1, int x2, int y2, int type = 0);
    void selectShape(int kind, int x1, int y1, int x2, int y2);
    void selectPolygon(const QVector<QPoint> &points);
    void lassoSelect(const QVector<QPoint> &points);
    // Magnetic lasso: edge-snapping path from 'from' to 'to' (Krita's
    // KisMagneticWorker logic, self-contained A* over a Sobel edge map)
    QVector<QPoint> magneticLasso(const QPoint &from, const QPoint &to, int radius);
    void selectContiguousAt(int x, int y, int tolerance = 24);
    void selectSimilarAt(int x, int y, int tolerance = 24);
    void moveLayerContent(int dx, int dy);
    // Krita transform tool: apply scale/shear/rotation/translate around the
    // content bounding-box centre. With an active selection only the selected
    // pixels transform (KisToolTransform semantics). Uses Krita's own
    // KisTransformWorker (SC*S*R*T order) for the no-selection case.
    bool applyTransform(double xscale, double yscale, double xshear,
                        double yshear, double rotationRad,
                        double xtranslate, double ytranslate,
                        double originX = -1.0, double originY = -1.0);
    bool applyPerspectiveTransform(double x0, double y0,
                                   double x1, double y1,
                                   double x2, double y2,
                                   double x3, double y3,
                                   double origX, double origY, double origW, double origH);
    bool applyWarpMeshTransform(const QVector<QPointF> &origPoints,
                                const QVector<QPointF> &transfPoints,
                                double origX, double origY, double origW, double origH);
    // Content bounding box of the current layer in document coords (for the
    // transform tool's rubber band). Empty (w<=0) when the layer is empty.
    QRect contentBounds();
    void cropCanvas(int x, int y, int w, int h);
    
    // Transform preview mechanism (extracts target pixels and hides them in C++)
    bool startTransformPreview(QImage* outImage);
    void cancelTransformPreview();

    // Draw text at (x, y) with the current brush color/size
    void drawText(int x, int y, const QString &text, qreal fontSize);

    // Lasso region ops: fill or clear the polygon defined by points
    void lassoFill(const QVector<QPoint> &points);
    void lassoClear(const QVector<QPoint> &points);

    // Liquify: push pixels within the brush radius from (fx,fy) to (tx,ty)
    void liquify(int fx, int fy, int tx, int ty, qreal strength = 0.9, int mode = 0);
    void setLiquifyBrushSize(qreal size) { m_liquifyBrushSize = size; }
    qreal liquifyBrushSize() const { return m_liquifyBrushSize; }

    // Brush
    void setBrushSize(qreal size);
    qreal brushSize() const { return m_brushSize; }
    void setBrushColor(const QColor &c) { m_brushColor = c; }
    void setBrushSecondaryColor(const QColor &c) { m_brushSecondaryColor = c; }
    void setBrushColorName(const QString &colorName);
    void setBrushOpacity(qreal opacity);
    qreal brushOpacity() const { return m_brushOpacity; }
    QColor brushColor() const { return m_brushColor; }

    void commitStrokeToLayer();
    void recompositeProjection();

    // Strokes (touch input; coordinates in document space)
    void touchStrokeStart(qreal x, qreal y, qreal pressure);

    // Application-level undo/redo via per-stroke layer snapshots.
    // Krita's command stack needs the full KisTransaction pipeline; for the
    // MVP we snapshot the current layer before each stroke and restore on
    // undo/redo. (KisSurrogateUndoStore still backs image-level commands.)
    bool canUndo() const;
    bool canRedo() const { return m_redoCount > 0; }
    void undo();
    void redo();
    void touchStrokeMove(qreal x, qreal y, qreal pressure);
    void touchStrokeEnd();
    void touchStrokeCancel();

    // ---- Krita brush engine (KisPaintOpPreset / KisBrushOp) ----
    // Registers the bundled Krita paintop factories (must be called once
    // before any preset is used). Implemented in register_paintops.cpp
    // inside the cross-compiled kritadefaultpaintops_static library so the
    // factory vtables match libkritaimage's view.
    static void registerPaintOps();
    // Scans a directory for .kpp presets and loads them lazily
    int loadBrushPresetsFromDir(const QString &dirPath);
    // Scans a directory for brush resource files (.gbr/.gih/.png/.svg) and
    // loads them into the shared KisLocalStrokeResources so preset
    // brush_definition lookups (bestMatch by filename) can resolve them.
    // Must be called before loadBrushPreset. Returns the count loaded.
    int loadBrushResources(const QString &dirPath);
    bool loadBrushPreset(int index);
    int brushPresetCount() const;
    QVector<double> brushPresetDefaults(int index);
    QString brushPresetName(int index) const;
    QString brushPresetPath(int index) const;
    QByteArray brushPresetThumbData(int index) const;
    void setBrushFlow(qreal v);
    void setBrushSpacing(qreal v);
    void setBrushAngle(qreal v);
    void setBrushScatter(qreal v);
    void setBrushFade(qreal v);
    void setBrushSoftness(qreal v);
    void setBrushRatio(qreal v);
    void setBrushSharpness(qreal v);
    void setBrushRotation(qreal v);
    void setBrushCompositeOp(const QString &op);
    int currentBrushPreset() const { return m_brushPresetIndex; }

    // Rendering: fill the given RGBA buffer (w*h*4 bytes, stride w*4)
    // with the composited document. Returns true on success.
    bool renderToBuffer(quint8 *buffer, int w, int h);

    // Sample the composited color at document-space coordinates;
    // returns "#rrggbb" or empty if outside the document.
    QString pickColorAt(int x, int y, bool currentLayerOnly = false);

    // Export functions
    bool savePng(const QString &path);
    bool exportJpg(const QString &path, int quality = 90);
    bool exportPsd(const QString &path);
    bool saveRevp(const QString &path, const QString &extraMetaJson = QString());
    bool loadRevp(const QString &path);
    bool saveKra(const QString &path);

    // Render a single layer's content into an RGBA buffer (w*h*4 bytes,
    // row stride dstStride) as a thumbnail: transparent background, keep
    // aspect ratio, centered. Returns true on success.
    bool renderLayerThumb(int index, int w, int h, void *dstPixels, int dstStride);

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
    KisPaintDeviceSP layerPaintDeviceFor(const LayerEntry &e) const;
    bool isLayerEditable(int index) const;   // background/locked check
    int indexOfNode(KisNode *node) const;
    // Force a full synchronous recomposite of the root projection. Krita's
    // projection updates are driven by dirty-region propagation, which does
    // not cover node-structure changes (add/remove layer, visibility, blend
    // mode): after those the root projection device is rebuilt empty and
    // convertToQImage returns transparent black. Krita itself uses the
    // refresh-walker + async-merger pair for exactly this case.
    void appendStrokeSample(const QPointF &imgPos, qreal pressure);
    void flushStrokeBatch();
    void endStrokeBatch();

    struct StrokeSample {
        QPointF imgPos;
        qreal pressure = 1.0;
    };



    KisImageSP m_document;
    QVector<LayerEntry> m_layers;   // bottom -> top, tree traversal order
    int m_currentLayer = 0;
    KisSelectionSP m_selection;     // optional active selection
    SelMode m_selectionMode = SelReplace;
    int m_soloedLayer = -1;          // currently soloed layer, -1 if none

    KisTransaction *m_previewTransaction = nullptr;
    KisPaintDeviceSP m_previewTempDevice;

    // Brush state
    qreal m_brushSize = 20.0;
    qreal m_shapeStrokeWidth = 4.0;   // shape tools independent stroke width
    bool m_shapeFilled = false;       // shape tools fill with the brush color
    qreal m_liquifyBrushSize = 60.0;   // liquify independent brush size
    QColor m_brushColor = Qt::black;
    QColor m_brushSecondaryColor = Qt::white;
    qreal m_brushOpacity = 1.0;
    qreal m_brushFlow = 1.0;
    ToolMode m_toolMode = ToolBrush;

    // Krita brush engine state
    KisPaintOpPresetSP m_brushPreset;
    KisResourcesInterfaceSP m_brushResources;
    QVector<QPair<QString, QString>> m_presets;  // name -> path
    int m_brushPresetIndex = -1;
    // In-progress stroke op + distance accumulator (lives across flushes)
    KisPaintOpSP m_strokeOp;
    KisDistanceInformation *m_strokeDistance = nullptr;
    // Synchronous executor for the async dab-rendering pipeline (Krita uses
    // this in its own tests; on-device it keeps dab rendering deterministic)
    KisFakeRunnableStrokeJobsExecutor m_fakeExecutor;

    // Stroke batching
    QVector<StrokeSample> m_strokeSamples;
    // True once the finger moved beyond the start point; a single-sample
    // flush is only a dot when this is false (a genuine tap). Trailing
    // samples of a real stroke must never render as dots.
    bool m_strokeHadMove = false;
    qint64 m_lastFlushMs = 0;
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

    // Deferred snapshot: taken on the first real flush of a stroke, not at
    // touch-down, so a pure tap or an instantly-cancelled stroke never pays
    // the full-document read cost.
    bool m_snapshotPending = false;
    // Krita-native undo/redo: KisSurrogateUndoStore + KisTransaction +
    // libs/image/commands node commands. The store is installed on the
    // KisImage via setUndoStore; every modifying operation is wrapped in a
    // KisTransaction or a node command pushed through the image's undo
    // adapter, so undo/redo restores Krita's own tile-level snapshots
    // (memory-efficient) and covers strokes, fills, shapes, layer
    // add/remove/move and layer attributes - not just brush strokes.
    KisSurrogateUndoStore *m_undoStore = nullptr;
    int m_redoCount = 0;   // redo depth tracked locally (store hides it)
    // Deferred stroke transaction: created at the first real flush (after
    // the stroke device exists), committed at stroke end, discarded on
    // cancel - taps and no-paint strokes never create an undo command.
    KisTransaction *m_strokeTxn = nullptr;
    bool m_strokeTxnActive = false;
    // Filter backup device for non-destructive live preview
    KisPaintDeviceSP m_filterBackupDevice;
    int m_filterBackupIndex = -1;
    QRect m_filterBackupExt;

    // Wrap a command push through the image's undo adapter and clear redo
    void pushUndoCommand(KUndo2Command *cmd);

    void (*m_dirtyCb)(void *) = nullptr;
    void *m_dirtyCtx = nullptr;
};

#endif // REVERIECORE_H
