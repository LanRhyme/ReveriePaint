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
#include <QHash>
#include <QMutex>
#include <QSet>

#include <kis_types.h>
#include <brushengine/kis_paintop.h>
#include <KisResourcesInterface.h>
#include <KisFakeRunnableStrokeJobsExecutor.h>
// 需要完整类型: m_strokeOnionCache 是 QHash<int, KisPaintDeviceSP>, 其
// 析构/拷贝会触碰 KisPaintDevice 的成员 (kis_shared_ptr.h 的 QSharedPointer
// 析构需要完整类型), 仅靠前置声明会报 "member access into incomplete type"
#include <kis_paint_device.h>

class KisBrush;
typedef QSharedPointer<KisBrush> KisBrushSP;
class KisImage;
class KisPaintLayer;
class KisPainter;
class KisDistanceInformation;

class KisSurrogateUndoStore;
class KisTransaction;
class KUndo2Command;
class KoStore;

class ReverieCore
{
public:
    ReverieCore();
    ~ReverieCore();

    // Document
    bool newDocument(int width, int height, bool infiniteCanvas = false);
    bool isInfiniteCanvas() const { return m_infiniteCanvas; }
    void setInfiniteCanvas(bool infinite) { m_infiniteCanvas = infinite; }
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
    // Opacity change without the undo stack (slider drag preview - the release
    // commit goes through setLayerOpacity above, collapsing the drag into one
    // undo step)
    void setLayerOpacityDirect(int index, qreal opacity);
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
    int layerNodeType(int index) const;
    int layerDepth(int index) const;
    bool layerBackground(int index) const;
    void setBackgroundColor(quint32 color, bool commit = true);
    // Clipping mask (self-implemented: Krita only has inherit-opacity):
    // content painted on a clipped layer is masked by the next layer's alpha
    bool layerClipped(int index) const;
    void setLayerClipped(int index, bool clipped);
    void flipLayerHorizontal(int index);
    void flipLayerVertical(int index);
    // Canvas flip: mirror every paintable layer (incl. background/locked and
    // masks) around the document centre as one undo step
    void flipCanvasHorizontal();
    void flipCanvasVertical();
    // Fill the whole layer with the current foreground colour (selection-aware)
    void fillLayer(int index);
    bool mergeDown(int index);   // composite onto the layer below, remove self
    bool moveLayer(int fromIndex, int toIndex);            // move layer to another row's position (cross-parent ok)
    bool moveLayerAbove(int fromIndex, int aboveIndex);   // move layer above the given layer (exact sibling semantics)
    bool moveLayerToGroup(int fromIndex, int groupIndex);  // move layer to the top of a group
    bool moveLayerRelative(int fromIndex, int targetIndex, bool placeAbove); // move layer relative to target layer in hierarchy
    // Solo (独显, FolioLayers logic): toggle solo for one layer; soloing a
    // layer hides every other layer, tapping the soloed layer again restores.
    // Solo mode is PURELY a render-time filter: it never touches the layer
    // state (visibility/opacity/blend/inheritAlpha stay untouched), so closing
    // solo restores the document exactly as it was, and solo can never corrupt
    // the canvas render or the undo stack
    void soloLayer(int index);
    bool layerSoloed(int index) const;
    bool soloActive() const;
    void restoreSolo();
    bool soloRawMode() const;
    void toggleSoloRawMode();
    // Keep set as layer indices (layer panel uses it to gray out hidden rows)
    QVector<int> soloKeepIndices() const;
    // Render-time filtered composite (solo mode) - returns the composite of
    // the soloed layer plus its ancestor groups, descendants and background
    KisPaintDeviceSP compositeSoloProjection();
    int currentLayerIndex() const { return m_currentLayer; }
    // Recursive solo composite of [startIdx, endIdx): groups composite their
    // keep-set children into a temp device then apply their own opacity/blend
    void compositeSoloRange(KisPaintDeviceSP out, int startIdx, int endIdx, const QRect &full);
    // Direct sub-region layer compositing for zero-latency in-stroke rendering
    void compositeLayersRange(KisPaintDeviceSP out, int startIdx, int endIdx, const QRect &r);
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
    // LayerEntry.nodeType 值域 (与 Kotlin LayerUiState.nodeType 镜像同步)
    enum {
        NodeTypePaint = 0,
        NodeTypeGroup = 1,
        NodeTypeFill = 2,       // KisGeneratorLayer
        NodeTypeAdjustment = 3, // KisAdjustmentLayer
        NodeTypeVector = 4,
        NodeTypeClone = 5,
        NodeTypeTransparencyMask = 10,
        NodeTypeFilterMask = 11,
        NodeTypeTransformMask = 12,
        NodeTypeSelectionMask = 13
    };
    bool addLayerWithType(const QString &name, int type, quint32 fillColor = 0xFFFFFFFF);
    // 非破坏性调整图层 (KisAdjustmentLayer, 滤镜 ID reverie-f<type>)
    bool createAdjustmentLayer(const QString &name, int filterType,
                               double p1 = 0.0, double p2 = 0.0, double p3 = 0.0, double p4 = 0.0,
                               const QByteArray &lut = QByteArray());
    bool previewAdjustmentLayerConfig(int index, int filterType,
                                      double p1, double p2, double p3, double p4,
                                      const QByteArray &lut = QByteArray());
    bool setAdjustmentLayerConfig(int index, int filterType,
                                  double p1, double p2, double p3, double p4,
                                  const QByteArray &lut = QByteArray(),
                                  bool recordUndo = true,
                                  const QString &origConfigJson = QString());
    QString getAdjustmentLayerConfig(int index); // JSON; 非调整层返回空串
    // 原生填充层换色 (KisGeneratorLayer + reverie-solid-color); 非填充层返回 false
    bool setFillLayerColor(int index, quint32 colorArgb);
    bool addMaskToLayer(int layerIndex, int maskType);
    bool removeMask(int layerIndex);
    bool rasterizeLayer(int index);
    bool flattenGroup(int index);
    bool setGroupPassThrough(int index, bool passThrough);
    bool groupPassThrough(int index) const;
    bool moveLayerUp(int index);
    bool moveLayerDown(int index);
    bool moveLayerOut(int index);

    // ================= Animation (frame-by-frame: layers as tracks) ========
    // 动画文档: 每个位图图层 = 一条轨道, 每条轨道可持有多个关键帧(帧).
    // 帧数据由 Krita 的 KisRasterKeyframeChannel 持有 (每个关键帧一份
    // KisPaintDevice, 瓦片+脏区复用文档既有的投影失效机制), 显式长度语义 =
    // 一个关键帧持续到下一个关键帧 (末帧持续到无穷).
    // 当前时间由 KisImageAnimationInterface 统一管理, 渲染路径无需改动:
    // 画面刷新仍走 renderToBuffer / renderPendingDirty.
    //
    // 铁律: 所有动画操作必须由 Kotlin 侧单 reverie-render 线程串行调用,
    // C++ 侧不额外加锁 (与文档其余部分一致).
    //
    // 轨道可动画的前提: 图层持有 KisPaintDevice (位图/克隆/填充层).
    // group / adjustment / 背景层不支持, 与 Krita 桌面行为一致.

    // --- Document: 时间与播放范围 ---
    bool animationEnabled() const;                 // 任一轨道已启用动画
    int animationCurrentTime() const;              // 当前帧号 (从 0 开始)
    void setAnimationCurrentTime(int time, bool recordUndo = false);
    int animationFramerate() const;
    void setAnimationFramerate(int fps);
    int animationLength() const;                   // 末关键帧帧号 + 1, 空文档为 0
    void animationPlaybackRange(int *start, int *end) const;
    void setAnimationPlaybackRange(int start, int end);

    // --- Onion skin: 洋葱皮 ---
    // 全局配置 (KisImageConfig) + 应用到所有动画位图图层并刷新投影。
    // enabled=false 仅关闭图层开关, prev/next 仍写入配置
    // maxOpacity: 最近帧的不透明度上限 (0~255), 默认 160 (全 255 会盖过当前帧)
    // tintFactor: 着色强度 (0~100), 0 = 不着色
    // tintBackwardArgb / tintForwardArgb: 过去/未来帧的着色色板 (ARGB, alpha 忽略)。
    //   Krita 支持前后帧分别配色, 用颜色区分时间方向 (默认 红=过去 / 绿=未来)。
    //   传 0 表示保留当前配置值, 便于老调用方零侵入。
    void configureOnionSkin(bool enabled, int prev, int next, int maxOpacity,
                            int tintFactor, int tintBackwardArgb = 0,
                            int tintForwardArgb = 0);
    // 是否有任一图层开着洋葱皮 (打开动画项目后 UI 同步开关状态用)
    bool anyLayerOnionSkin() const;
    // 读回当前全局洋葱皮配置 (打开项目后 UI 还原色板/强度用)。
    // 输出参数按 ARGB 传回, 与 configureOnionSkin 的入参同一格式。
    // 播放期间临时压掉洋葱皮: 只动 per-layer node property (渲染门, 经
    // applyOnionSkinGate), **不碰 KisImageConfig** (不写盘)。压掉前的各层
    // 状态记在 m_onionSuppressedPrev (仅供抑制窗口内的序列化读取), 恢复
    // 时由渲染门按"当前层 + 全局开关"重放 (播放期间可能换过轨道, 按表
    // 还原会把门留在旧层); .revp/autosave 序列化必须走
    // onionSkinLogicalEnabled(), 否则播放窗口里的 autosave 会把 false 存档。
    void setOnionSkinSuppressed(bool suppressed);
    // 序列化用: 抑制期间返回压掉前的逻辑状态, 平时返回 node property
    bool onionSkinLogicalEnabled(const KisPaintLayer *pl) const;
    // 把洋葱皮"渲染门" (per-layer node property) 落实到各图层: 目标值 =
    // m_onionSkinActive && !m_onionSkinSuppressed && (i == m_currentLayer)
    // —— 洋葱皮只渲染当前选中轨道, 播放抑制期间全部压掉。
    // configureOnionSkin 传 true (配置内容变了, 未变化的层也要刷缓存与脏区);
    // 换层 / 结构重排 / 抑制开关传 false (只搬开关, 未变化的层零开销)。
    void applyOnionSkinGate(bool refreshCaches);
    void onionSkinTintColors(int *backwardArgb, int *forwardArgb) const;
    int onionSkinTintFactor() const;   // 0~255 (Krita 原生值, 非百分比)
    // 丢弃所有洋葱皮图层的缓存并重算脏区。
    //
    // **每次改动任何一帧的像素后都必须调**: 洋葱皮缓存的失效判据只有
    // (currentTime, configSeqNo, channelHash), 不含帧内像素改动, 不调就会
    // 看到旧的邻帧叠影。内部先判 hasAnyOnionSkinLayer() 提前退出, 没开
    // 洋葱皮时开销可忽略。
    void flushOnionSkinCaches();
    bool hasAnyOnionSkinLayer() const;

    // --- Import: 导入 ---
    // 把位图像素 (ARGB_8888 premultiplied) 作为关键帧写入 [layerIndex,time];
    // 帧不存在时自动创建。像素走 rawY 同源通道, 不触碰文档 currentTime
    bool importKeyframeFromBitmap(int layerIndex, int time, int w, int h, void *pixels, int stride);
    // 导入资源 (保存 .revp 时写入 assets/<name>), 加载 .revp 时还原
    void storeRevAsset(const QString &name, const QByteArray &data);
    QVector<QString> revAssetNames() const;
    QByteArray revAssetBytes(const QString &name) const;

    // --- Track: 图层即轨道 ---
    bool layerAnimated(int index) const;           // 已有 keyframe channel
    bool layerAnimatable(int index) const;         // 具备开启动画的资格
    bool enableLayerAnimation(int index);          // 开启动画 (幂等; Krita 自动补 frame 0)

    // --- Keyframe: 帧 ---
    bool hasKeyframe(int layerIndex, int time) const;
    int keyframeCount(int layerIndex) const;
    QVector<int> keyframeTimes(int layerIndex) const;   // 升序
    bool addKeyframe(int layerIndex, int time);             // 空白帧
    bool addDuplicateKeyframe(int layerIndex, int time);    // 复制前一帧内容
    bool removeKeyframe(int layerIndex, int time);
    bool copyKeyframe(int layerIndex, int fromTime, int toTime);   // 独立像素副本
    bool cloneKeyframe(int layerIndex, int fromTime, int toTime);  // 共享同一份像素
    bool moveKeyframe(int layerIndex, int fromTime, int toTime);
    int previousKeyframeTime(int layerIndex, int time) const;   // 无则 -1
    int nextKeyframeTime(int layerIndex, int time) const;       // 无则 -1
    int keyframeDuration(int layerIndex, int time) const;       // 到下一帧跨度, 末帧 -1
    // 一拍N: 重排整条轨道, 首帧原位, 后续每 duration 帧一个; 单步撤销
    bool setAllKeyframesDuration(int layerIndex, int duration);
    // 一拍N (选中帧版): 只重排选中的帧, 区间内未选中帧保持原间距整体后移
    bool setSelectedKeyframesDuration(int layerIndex, const QVector<int> &selectedTimes, int duration);

    // 把 [time] 位置关键帧的画面渲染成缩略图 (RGBA, w*h*4 字节, 行跨距 dstStride)。
    // 与 renderLayerThumb 的区别: 后者总是画图层的"当前"内容, 而时间轴需要
    // 让每个帧块显示它自己那一帧。实现走 KisRasterKeyframeChannel::writeToDevice
    // 把目标帧拷进一块临时设备再缩放, **不改变文档的 currentTime**, 因此
    // 不会让画布跳帧, 也不需要额外的同步/还原步骤。
    // time < 0 或该位置无关键帧时回退到当前帧内容。
    bool renderKeyframeThumb(int layerIndex, int time, int w, int h, void *dstPixels, int dstStride);

    // 帧缩略图缓存代际自增: 笔画落笔 / 关键帧增删改后调用, 使 UI 侧
    // (layerIndex, time) 缓存整体失效。
    //
    // 注意: 这里**不要**顺手调 flushOnionSkinCaches()。两者虽然触发条件
    // 有重叠, 但代价完全不同 —— 缩略图代际只是 ++ 一个计数器, 而 flush 要
    // 对每个图层算 calculateFullExtent (遍历整条轨道所有关键帧) 并丢弃缓存
    // 迫使下一帧重新合成。绑在一起会让新建文档 / 加载 .revp / 批量导入这类
    // 会连续触发 11 次的路径反复全量重算。
    quint64 keyframeThumbGen() const { return m_keyframeThumbGen; }
    void bumpKeyframeThumbGen() {
        ++m_keyframeThumbGen;
        // 关键帧结构变了 -> 洋葱皮要合成哪几帧也变了。这里只作废**笔触叠加**
        // 用的自持缓存 (O(1) 的 clear), 不碰 Krita 侧 KisOnionSkinCache ——
        // 后者由 channelHash 自然失效, 强行 flush 会触发全量重合成。
        invalidateStrokeOnionCache();
    }

    // 帧缩略图**精准失效**: 单帧内容变化 (落笔 / 帧增删改 / 复制) 时只作废
    // 这一个 (图层, 帧) 的缓存, 其余帧照常复用。全局代际 (bumpKeyframeThumbGen)
    // 只留给文档级/结构级变化 (加载 / 导入 / undo / 图层增删重排 / 整轨重排)
    // —— 缓存键含图层索引, 结构变化会让索引错位, 必须整体失效。
    //
    // 背景 (性能): 旧实现里抬笔也走全局 bump, 时间轴打开时每画一笔就把
    // 所有图层所有帧的缩略图全部重渲染 (每张 = new KisPaintDevice +
    // writeToDevice + convertToQImage + smooth scale), 是逐帧作画最大的
    // 隐藏开销。UI 侧经 takeDirtyKeyframeThumbs() 取走脏帧并只重渲染它们。
    void dirtyKeyframeThumb(int layerIndex, int time);
    // 取走并清空脏帧集合, 交替 [layer0, time0, layer1, time1, ...]。
    // 只能在 reverie-render 线程调用 (与所有写入方同线程串行)。
    QVector<int> takeDirtyKeyframeThumbs();

    // Filters (interactive preview & commit, single & multi-layer)
    void applyFilter(int index, int filterId);
    void applyFilterMulti(const QVector<int> &indices, int filterId);
    void beginFilterPreview(int index);
    void beginFilterPreviewMulti(const QVector<int> &indices);
    void applyFilterPreview(int index, int filterType, double p1, double p2, double p3, double p4);
    void applyFilterPreviewMulti(const QVector<int> &indices, int filterType, double p1, double p2, double p3, double p4);
    void applyCurvesLUTPreview(int index, const quint8 *lutR, const quint8 *lutG, const quint8 *lutB);
    void applyCurvesLUTPreviewMulti(const QVector<int> &indices, const quint8 *lutR, const quint8 *lutG, const quint8 *lutB);
    void applyGradientMapPreview(int index, const quint32 *gradientLut256);
    void applyGradientMapPreviewMulti(const QVector<int> &indices, const quint32 *gradientLut256);
    void commitFilter(int index, const QString &filterName);
    void commitFilterMulti(const QVector<int> &indices, const QString &filterName);
    void cancelFilter(int index);
    void cancelFilterMulti(const QVector<int> &indices);

    // Selection: build a pixel selection from the layer's alpha channel and
    // constrain painting to it (KisSelection, Krita mechanism)
    bool selectionFromLayer(int index);
    bool hasSelection() const;
    void clearSelection();
    void selectAll();
    void invertSelection();
    int copySelectionToNewLayer(bool cut);
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
    void setToolMode(int mode);
    int toolMode() const { return int(m_toolMode); }

    // Public data structure: one entry per layer/group in tree traversal
    // order (bottom -> top). Used by file-scope helpers in ReverieCore.cpp.
    // Pre-solo snapshot of one layer (FolioLayers behavior: closing solo mode
    // must restore visible/opacity/blend/inheritAlpha exactly)
    struct SoloBackup {
        bool visible = true;
        quint8 opacity = 255;
        QString blendMode = QStringLiteral("normal");
        bool inheritAlpha = false;
    };
    struct LayerEntry {
        KisNode *node = nullptr;      // paint layer or group layer
        bool visible = true;
        QString name;
        int depth = 0;                // group nesting depth (UI indent)
        bool isGroup = false;
        int nodeType = 0;             // NodeType* 值域 (paint/group/fill/adjustment/clone + 四种 mask)
        bool locked = false;          // full lock: no editing at all
        bool alphaLocked = false;     // preserve alpha (transparency lock)
        int colorLabel = 0;           // color label index 0-9
        bool clipped = false;         // clipping mask onto the layer below
        bool background = false;      // background layer (index 0)
        QVector<SoloBackup> soloPrev; // snapshot before solo (FolioLayers)
    };

    // Fill the current layer's region (or whole layer) with the brush color
    void floodFillAt(int x, int y, int tolerance = 16, bool sampleMerged = true, int expand = 0, int feather = 0, int closeGap = 4);

    // Draw a shape: 0=line, 1=rect, 2=ellipse between two points
    void drawShape(int kind, int x1, int y1, int x2, int y2, bool filled = false);
    void setShapeStrokeWidth(qreal w) { m_shapeStrokeWidth = w; }
    qreal shapeStrokeWidth() const { return m_shapeStrokeWidth; }
    void setShapeFilled(bool f) { m_shapeFilled = f; }
    bool shapeFilled() const { return m_shapeFilled; }
    // kind 0=line, 1=rect, 2=ellipse, 3=closed polygon, 4=polyline
    void drawPolygon(const QVector<QPoint> &points, bool closed);
    void gradientFill(int x1, int y1, int x2, int y2, int type = 0, int repeat = 0, bool reverse = false);
    void selectShape(int kind, int x1, int y1, int x2, int y2);
    void selectPolygon(const QVector<QPoint> &points);
    void lassoSelect(const QVector<QPoint> &points);
    // Magnetic lasso: edge-snapping path from 'from' to 'to' (Krita's
    // KisMagneticWorker logic, self-contained A* over a Sobel edge map)
    QVector<QPoint> magneticLasso(const QPoint &from, const QPoint &to, int radius);
    void selectContiguousAt(int x, int y, int tolerance = 24, bool sampleMerged = true, int expand = 0, int feather = 0, int closeGap = 4);
    void selectSimilarAt(int x, int y, int tolerance = 24, bool sampleMerged = true);
    void moveLayerContent(int dx, int dy);
    // Krita transform tool: apply scale/shear/rotation/translate around the
    // content bounding-box centre. With an active selection only the selected
    // pixels transform (KisToolTransform semantics). Uses Krita's own
    // KisTransformWorker (SC*S*R*T order) for the no-selection case.
    bool applyTransform(double xscale, double yscale, double xshear,
                        double yshear, double rotationRad,
                        double xtranslate, double ytranslate,
                        double originX = -1.0, double originY = -1.0);
    // Transform several layers as one group around the union-bounds center
    // (multi-select); empty list = current layer only
    bool applyTransformLayers(const QVector<int> &layers,
                              double xscale, double yscale, double xshear,
                              double yshear, double rotationRad,
                              double xtranslate, double ytranslate,
                              double originX = -1.0, double originY = -1.0,
                              bool copyOnly = false);
    bool applyPerspectiveTransform(double x0, double y0,
                                   double x1, double y1,
                                   double x2, double y2,
                                   double x3, double y3,
                                   double origX, double origY, double origW, double origH);
    bool applyWarpMeshTransform(const QVector<QPointF> &origPoints,
                                const QVector<QPointF> &transfPoints,
                                double origX, double origY, double origW, double origH);
    // Content bounding box of the edit target set (multi-select union, else
    // the current layer) in document coords (for the transform tool's rubber
    // band). Empty (w<=0) when the target layer is empty.
    QRect contentBounds(const QVector<int> &layers = QVector<int>());
    void cropCanvas(int x, int y, int w, int h);
    
    // Transform preview mechanism (extracts target pixels and hides them in C++)
    bool startTransformPreview(const QVector<int> &layers, QImage* outImage, bool copyOnly = false);
    void cancelTransformPreview();

    // Draw text at (x, y) with the current brush color/size
    void drawText(int x, int y, const QString &text, qreal fontSize);

    // Stamp an RGBA8888 bitmap onto current layer with source-over alpha blending
    void stampBitmap(int x, int y, int bw, int bh, const void *rgbaPixels);

    // Lasso region ops: fill or clear the polygon defined by points
    void lassoFill(const QVector<QPoint> &points);
    void lassoClear(const QVector<QPoint> &points);

    // Liquify: warp within the brush radius at (fx,fy) toward (tx,ty).
    // mode: 0 推拉, 1 膨胀, 2 收缩, 3 顺时针, 4 逆时针
    // An empty/absent layer list liquifies the current layer only; multiple
    // layers (multi-select) warp together as ONE undo step.
    void liquify(int fx, int fy, int tx, int ty, qreal strength = 0.9, int mode = 0);
    void liquifyBegin(const QVector<int> &layers = QVector<int>());
    void liquifyEnd();
    void liquifyCancel();
    void setLiquifyBrushSize(qreal size) { m_liquifyBrushSize = size; }
    qreal liquifyBrushSize() const { return m_liquifyBrushSize; }

    // Move the content of several layers at once (one undo step). An empty
    // list moves the current layer only.
    void moveLayerContentLayers(const QVector<int> &layers, int dx, int dy);

private:
    void resetLiquifyWorker();
    void liquifyApplyLocked(const QRect &deltaRect);

public:

    // Brush
    void setBrushSize(qreal size);
    qreal brushSize() const { return m_brushSize; }
    /** 当前预设 Size 压感曲线求值：pressure∈[0,1] → 尺寸比例∈[0,1]（光标环同源缩放） */
    float brushPressureFraction(float pressure);
    void setBrushColor(const QColor &c);
    void setBrushSecondaryColor(const QColor &c);
    void setBrushColorName(const QString &colorName);
    void setBrushOpacity(qreal opacity);
    qreal brushOpacity() const { return m_brushOpacity; }
    QColor brushColor() const { return m_brushColor; }

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
    void beginUndoMacro(const QString &text = QString());
    void endUndoMacro();
    // Replay support: while undo capture is disabled, strokes and ops apply
    // normally but push no undo commands (no memory growth, no history),
    // and clearUndoHistory drops all recorded commands. The document image
    // is untouched by both.
    void setUndoCaptureEnabled(bool on) { m_undoCaptureEnabled = on; }
    void clearUndoHistory();
    // Returns true when this call flushed a batch and painted new ink (used
    // by the Kotlin transport to render only after real paint work).
    bool touchStrokeMove(qreal x, qreal y, qreal pressure);
    // Flush the pending stroke start as an ink dot when no movement arrived
    // yet (hold-still / slow-start latency fix). No-op once the stroke moved.
    // Returns true when a dot was painted.
    bool touchStrokeKickIdle();
    bool flushStrokeBatch();
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
    void setBrushSmudgeRate(qreal v);
    void setBrushSmudgeLength(qreal v);
    void setBrushAirbrush(bool enabled, qreal rate);
    bool strokeAirbrushTick();
    void setBrushSpacing(qreal v);
    void setBrushAngle(qreal v);
    void setBrushScatter(qreal v);
    void setBrushFade(qreal v);
    void setBrushSoftness(qreal v);
    void setBrushRatio(qreal v);
    void setBrushSharpness(qreal v);
    void setBrushRotation(qreal v);
    void setBrushCompositeOp(const QString &op);
    void setPresetIsEraser(bool eraser);
    bool setBrushTipAsset(const QString &assetName);
    bool hasPendingStrokeSamples() const { return m_strokeSamples.size() > m_strokeCarryCount; }
    int currentBrushPreset() const { return m_brushPresetIndex; }

    // Rendering: fill the given RGBA buffer (w*h*4 bytes, stride w*4)
    // with the composited document. Returns true when the buffer was
    // (re)written; false reports a no-op (nothing changed since the last
    // render) so the caller can skip the display flip. forceFull forces a
    // full-frame write, used when a freshly allocated buffer is handed in.
    bool renderToBuffer(quint8 *buffer, int w, int h, bool forceFull = false);

    // Sample the composited color at document-space coordinates;
    // returns "#rrggbb" or empty if outside the document.
    QString pickColorAt(int x, int y, bool currentLayerOnly = false);

    // Export functions
    bool savePng(const QString &path);
    bool exportJpg(const QString &path, int quality = 90);
    bool exportPsd(const QString &path);
    // 兼容 KRA 语义的图层树元数据 (定义于 ReverieCoreLayerIO.cpp)
    void writeLayersXml(QString *out);
    static bool loadLayersXmlTree(const QByteArray &xmlData, KisImageSP image, KoStore *store, bool *bgVisible);
    bool saveRevp(const QString &path, const QString &extraMetaJson = QString(),                  const QByteArray &recordingBlob = QByteArray());
    bool saveRevpAsync(const QString &path, const QString &extraMetaJson = QString(),                       const QByteArray &recordingBlob = QByteArray());
    bool loadRevp(const QString &path);
    bool loadPsd(const QString &path);
    bool saveKra(const QString &path);

    // 作者档案配置 (Krita / Dublin Core 兼容)
    struct AuthorProfile {
        bool enabled = false;
        QString name;
        QString nickname;
        QString organization;
        QString email;
        QString website;
        QString copyright;

        bool isEmpty() const {
            return name.trimmed().isEmpty() &&
                   nickname.trimmed().isEmpty() &&
                   organization.trimmed().isEmpty() &&
                   email.trimmed().isEmpty() &&
                   website.trimmed().isEmpty() &&
                   copyright.trimmed().isEmpty();
        }
    };
    void setAuthorProfile(const QString &jsonStr);
    const AuthorProfile &authorProfile() const { return m_authorProfile; }

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
    // Returns true when a flush painted ink in this call.
    bool appendStrokeSample(const QPointF &imgPos, qreal pressure);
    void endStrokeBatch();

    struct StrokeSample {
        QPointF imgPos;
        qreal pressure = 1.0;
    };



    KisImageSP m_document;
    QVector<LayerEntry> m_layers;   // bottom -> top, tree traversal order
    // Layer thumbnail cache: keyed by node (survives index shifts from
    // add/remove/move). gen is bumped by markDirty (doc-wide ops) or
    // bumpLayerThumbGen (strokes); renderLayerThumb re-blits the cached
    // thumb while gen/bounds/size are unchanged instead of re-converting
    // the whole layer to QImage on every panel refresh.
    struct ThumbCache {
        quint64 gen = 0;
        QImage img;
        quint64 imgGen = 0;
        QRect bounds;
    };
    QHash<KisNode *, ThumbCache> m_thumbCache;
    // 帧缩略图 (时间轴专用): 每次 renderKeyframeThumb 都可能换一块目标帧,
    // 因此缓存键含帧号, 且用代际号整体失效 (笔画/关键帧变更时自增),
    // 避免逐帧比较内容变化。
    struct KeyframeThumbCache {
        quint64 gen = 0;
        QImage img;
    };
    QHash<quint64, KeyframeThumbCache> m_keyframeThumbCache;
    quint64 m_keyframeThumbGen = 1;
    // 精准失效集合: 与全局代际互补 (见 dirtyKeyframeThumb 注释)
    QSet<quint64> m_dirtyKeyframeThumbs;
    // 播放期洋葱皮抑制 (见 setOnionSkinSuppressed)。prev 的 key 用图层
    // 指针: 播放期间结构不变指针稳定; 换文档后旧指针自然不再匹配任何
    // 当前层, 恢复 no-op, 残留条目在下次 suppress(true) 时清掉。
    bool m_onionSkinSuppressed = false;
    QHash<KisPaintLayer *, bool> m_onionSuppressedPrev;
    // 洋葱皮全局开关的生效值 (configureOnionSkin 写入), applyOnionSkinGate
    // 据此 + 当前层索引算每层的目标状态; 换层/结构重排时重放渲染门。
    bool m_onionSkinActive = false;

    // 导入资源 (音频/视频等二进制), 保存 .revp 时写入 assets/ 条目
    QMap<QString, QByteArray> m_revAssets;
    int m_currentLayer = 0;
    KisSelectionSP m_selection;     // optional active selection
    SelMode m_selectionMode = SelReplace;
    // ---- Solo mode state (render-filter only, never mutates layers) ----
    // The soloed node and its keep set are tracked by node pointer, so layer
    // add/remove/move (which rebuild m_layers) can never invalidate them
    KisNode *m_soloedNode = nullptr;
    QVector<KisNode *> m_soloKeepNodes;
    bool m_soloRawMode = false;      // solo raw mode (pure color) on/off
    int soloedIndex() const;         // current index of m_soloedNode, -1 if gone
    void computeSoloKeep();          // rebuild m_soloKeepNodes from m_layers

    KisTransaction *m_previewTransaction = nullptr;
    KisPaintDeviceSP m_previewTempDevice;
    // Multi-layer preview: one transaction per targeted device so a
    // cancel/commit reverts every hidden layer (Kotlin passes the multi-
    // selected set to startTransformPreview). m_previewTransaction stays for
    // the single-layer path.
    QVector<KisTransaction *> m_previewTransactions;

    // Brush state
    qreal m_brushSize = 20.0;
    qreal m_shapeStrokeWidth = 4.0;   // shape tools independent stroke width
    bool m_shapeFilled = false;       // shape tools fill with the brush color
    qreal m_liquifyBrushSize = 60.0;   // liquify independent brush size
    // Multi-layer liquify session state. Each target layer keeps its own
    // pristine source copy + grid worker (same displacement ops applied to
    // every worker: they are content-independent); all targets flush as one
    // throttled writeback and one composite undo command.
    struct LiquifyTarget {
        KisPaintDeviceSP device;
        KisPaintDeviceSP src;
        KisPaintDeviceSP dst;
        class KisLiquifyTransformWorker *worker = nullptr;
        KisTransaction *txn = nullptr;
        QRect bounds;
    };
    QVector<LiquifyTarget> m_liquifyTargets;
    bool m_liquifyTxnActive = false;   // a bracketed drag session is open
    // The grid worker runs over a LOCAL rect (brush neighbourhood):
    // run() copies the whole bounds complement, so a full-canvas worker
    // cost a full-canvas copy per dab. Rebased when the brush wanders out.
    QRect m_liquifyWorkerBounds;
    qint64 m_liquifyLastApplyMs = 0;
    // Union of dab influence rects not yet written back to the layer
    QRect m_liquifyPendingDelta;
    // Adaptive writeback pacing (grows when a single apply overruns)
    qint64 m_liquifyApplyIntervalMs = 20;
    QColor m_brushColor = Qt::black;
    QColor m_brushSecondaryColor = Qt::white;
    qreal m_brushOpacity = 1.0;
    qreal m_brushFlow = 1.0;
    qreal m_brushSpacing = 0.1;
    QString m_brushTipAsset;
    ToolMode m_toolMode = ToolBrush;

    // Krita brush engine state
    KisPaintOpPresetSP m_brushPreset;
    KisResourcesInterfaceSP m_brushResources;
    QHash<QString, KisBrushSP> m_loadedBrushes;
    QVector<QPair<QString, QString>> m_presets;  // name -> path
    int m_brushPresetIndex = -1;
    int m_presetIsEraserOverride = -1; // -1 unknown (use name heuristic), 0 false, 1 true
    // Size 压感曲线缓存（预设切换时失效；光标环每帧查询用）
    mutable QMutex m_sizeCurveMutex;
    const void *m_sizeCurveOwner = nullptr;   // settings 指针，变更即重解析
    QVector<QPointF> m_sizeCurveCache;        // 归一化控制点（空 = 无压感响应）
    bool m_sizeUseCurveCache = false;
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
    // How many leading samples of m_strokeSamples were retained from the
    // previous flush (their segments are already painted). Segments before
    // this index must NOT be painted again: re-dabbing the retained joint
    // doubled the opacity there (darker bands for paint, over-erased bands
    // for the eraser) at every 8ms flush boundary.
    int m_strokeCarryCount = 0;
    qint64 m_lastFlushMs = 0;
    // True when touchStrokeKickIdle already painted the stroke-start dot and
    // cleared the sample list: touchStrokeEnd must not re-append the start
    // point (it would dab the same spot a second time, doubling ink density).
    bool m_idleKickPainted = false;
    KisPainter *m_strokePainter = nullptr;
    void *m_strokeDevice = nullptr;
    bool m_strokeBatchOpen = false;
    QPointF m_strokeStartImg;
    QRectF m_accumulatedStrokeBounds;
    qreal m_lastPressure = 1.0;
    // Rendering: the last composited dirty region, used to copy only the
    // changed rows into the Android bitmap (m_bitmapInited gates the first
    // full copy).
    QRect m_lastDirty;
    bool m_bitmapInited = false;
    // Region actually written by the most recent renderToBuffer call, in
    // RENDER-BUFFER coordinates (empty when the render was a no-op). The
    // Kotlin rotation copies this region into the other non-displayed
    // buffers so every buffer stays incremental without full re-renders.
    QRect m_lastWrittenRect;
    bool m_infiniteCanvas = false;

public:
    /** Region written by the last successful renderToBuffer (buffer coords). */
    QRect lastWrittenRect() const { return m_lastWrittenRect; }

    /** Dirty content exists but the projection recomposite is still running. */
    bool renderPendingDirty() const;

private:
    QColor m_strokeColor;
    qreal m_strokeOpacity = 1.0;
    bool m_drawing = false;

    // ------------------------------------------------------------------
    // 笔触进行中的洋葱皮叠加 (onion skin during stroke)
    //
    // 为什么需要: renderToBuffer 在 m_drawing 期间走 compositeLayersRange,
    // 那是为了绕开 Krita 异步调度器而自研的快速合成路径, 只 blit 图层当前帧
    // 的 paintDevice —— **它不知道洋葱皮的存在**。结果是每次落笔, 笔迹经过
    // 的区域里洋葱皮会被"擦掉", 抬笔走回 Krita 完整投影才恢复, 表现为洋葱皮
    // 随笔画闪烁/缺一块。
    //
    // 为什么不能直接调 Krita 的投影: 那正是 m_drawing 要绕开的开销。
    // 折中做法是自己持有一份洋葱皮投影, 只在脏区内叠加:
    //   - 缓存按 (图层, currentTime, configSeqNo, channelHash) 失效, 与 Krita
    //     侧 KisOnionSkinCache 同构;
    //   - 落笔期间 currentTime 不变、configSeqNo 不变、channelHash 不变
    //     (笔画只改像素不改关键帧结构), 所以整个笔画期间只会合成一次;
    //   - 只在脏区 r 上 bitBlt, 代价与笔迹面积成正比而非文档面积。
    // ------------------------------------------------------------------
    QHash<int, KisPaintDeviceSP> m_strokeOnionCache;  // 图层索引 -> 洋葱皮投影
    int m_strokeOnionCacheTime = -1;                  // 缓存对应的 currentTime
    int m_strokeOnionCacheSeq = -1;                   // 缓存对应的 configSeqNo
    bool m_strokeOnionCacheDirty = true;              // 强制失效标记
    QHash<int, QRect> m_strokeOnionCacheExtent;       // 图层索引 -> 合成范围

    // 每个需要洋葱皮的图层复用的"邻帧叠影 + 本层内容"拼装设备。
    //
    // 为什么必须复用: compositeLayersRange 每次渲染**每个图层**都要拼一次,
    // 而它被调用的频率是渲染帧率 (笔画期间 ~8ms 一次)。原来每次 new 一个
    // KisPaintDevice 再 clear(), 光 tile manager 的构造/析构就够把 UI 线程
    // 拖出掉帧 —— 用户反馈的"绘制到洋葱皮区域就会卡"就是这里。
    // 复用后只剩 memcpy 级别的 clear + bitBlt。
    KisPaintDeviceSP m_strokeMergeScratch;

    /** 取(必要时建)某图层在笔触叠加用的洋葱皮投影; 未开洋葱皮返回空 */
    KisPaintDeviceSP strokeOnionProjection(int layerIndex);

    /** 该图层洋葱皮投影的覆盖范围 (缓存命中时 O(1)); 无洋葱皮返回空矩形 */
    QRect strokeOnionExtent(int layerIndex);

    /** 复用的拼装设备; 保证 [r] 范围是干净的 */
    KisPaintDeviceSP strokeMergeScratch(const QRect &r);

    /** 丢弃笔触洋葱皮缓存 (切帧 / 改配置 / 关键帧结构变化时调) */
    void invalidateStrokeOnionCache() {
        m_strokeOnionCache.clear();
        m_strokeOnionCacheTime = -1;
        m_strokeOnionCacheSeq = -1;
        m_strokeOnionCacheDirty = true;
        m_strokeOnionCacheExtent.clear();
    }
    // Smudge engine state (colorsmudge paintop)
    qreal m_smudgeRate = 0.5;   // color mixing rate -> ColorRateValue/MixValue
    qreal m_smudgeLength = 0.5; // smudge length -> SmudgeRateValue
    // Airbrush hold-still state (Krita PaintOpSettings/isAirbrushing + /rate)
    bool m_airbrushEnabled = false;
    qreal m_airbrushRate = 1.0; // dabs per second

    // Document size
    int m_docWidth = 0;
    int m_docHeight = 0;

    // Render cache: the size of the buffer the last render wrote into plus
    // the dirty region that still needs re-compositing. Krita's projection
    // recomputes only the tiles that changed; we re-run convertToQImage on
    // the dirty region only and copy the rest from the persistent display
    // buffer (m_renderBufW/H mismatch or forceFull triggers a full rewrite).
    int m_renderBufW = -1;
    int m_renderBufH = -1;
    QRect m_dirtyRect;
    QByteArray m_subRegionBuffer;
    void markBlendChanged(int index);  // blend-only change: targeted thumb invalidation
    void flipCanvasCommon(bool horizontal); // flipCanvasHorizontal/Vertical shared body

    void markDirty() {
        markRegionDirty(QRect(0, 0, m_docWidth, m_docHeight));
        // A document-wide content change invalidates every layer thumbnail:
        // filters/transforms/undo-redo/layer ops cannot say which layer
        // changed, so conservatively mark all cached thumbs stale.
        for (auto it = m_thumbCache.begin(); it != m_thumbCache.end(); ++it) {
            ++it->gen;
        }
    }
    void markRegionDirty(const QRect &r) {
        m_dirtyRect = m_dirtyRect.isNull() ? r : m_dirtyRect.united(r);
        if (m_dirtyCb) m_dirtyCb(m_dirtyCtx);
    }
    // A stroke paints one layer: bump the thumbnail generation of the painted
    // node AND every ancestor group (their thumbs show the merged composite,
    // so editing a child must also refresh the group thumb). Strokes are the
    // dominant thumbnail-refresh trigger, so this keeps refreshes per-layer
    // cheap instead of re-converting every layer on each stroke end.
    // (defined in ReverieCoreDocument.cpp: needs the complete KisNode type)
    void bumpLayerThumbGen(KisNode *node);

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
    int m_macroDepth = 0;  // nested macro transaction depth
    bool m_undoCaptureEnabled = true; // false during replay (no history growth)
    // Deferred stroke transaction: created at the first real flush (after
    // the stroke device exists), committed at stroke end, discarded on
    // cancel - taps and no-paint strokes never create an undo command.
    KisTransaction *m_strokeTxn = nullptr;
    bool m_strokeTxnActive = false;
    // 调整层配置组装 (ReverieCoreAdjustment.cpp)
    static KisFilterConfigurationSP reverieMakeConfig(int filterType, double p1, double p2, double p3, double p4,
                                                      const QByteArray &lut = QByteArray());
    // 纯色填充层配置组装 (ReverieCoreGenerators.cpp)
    static KisFilterConfigurationSP reverieMakeSolidColorConfig(quint32 rgba);

    // Filter backup devices for non-destructive live preview (single & multi-layer)
    struct FilterBackupEntry {
        int index;
        KisPaintDeviceSP device;
        QRect ext;
    };
    QVector<FilterBackupEntry> m_filterBackups;
    KisPaintDeviceSP findFilterBackup(int index) const;

    // Reusable buffers for zero-allocation filter preview
    QVector<quint8> m_filterWorkBuffer;
    QVector<quint8> m_filterOrigBuffer;
    void ensureFilterBuffers(int width, int height);

    // Wrap a command push through the image's undo adapter and clear redo
    void pushUndoCommand(KUndo2Command *cmd);

    AuthorProfile m_authorProfile;

    void (*m_dirtyCb)(void *) = nullptr;
    void *m_dirtyCtx = nullptr;
};

#endif // REVERIECORE_H
