/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/* ============================================================
 * ReverieCoreStroke.cpp - Stroke batching: touchStart/Move/End/Cancel, undo command push, stroke blending
 * (part of the ReverieCore module split; shared helpers live in
 * ReverieCoreInternal.h, public API in ReverieCore.h)
 * ============================================================ */
#include "ReverieCoreInternal.h"
#include "ReverieCoreUndoStore.h"
#include <strokes/KisMaskingBrushRenderer.h>
#include <strokes/KisMaskedFreehandStrokePainter.h>
#include <KisFreehandStrokeInfo.h>
#include <KoCompositeOpRegistry.h>
#include <filter/kis_filter_registry.h>
#include <cmath>

void ReverieCore::touchStrokeStart(qreal x, qreal y, qreal pressure, qreal tiltX, qreal tiltY, qreal rotation, qreal timeSeconds)
{
    if (!m_document || std::isnan(x) || std::isnan(y) || !std::isfinite(x) || !std::isfinite(y)) {
        return;
    }
    if (std::isnan(pressure) || !std::isfinite(pressure) || pressure < 0.0) {
        pressure = 1.0;
    }
    if (std::isnan(tiltX) || !std::isfinite(tiltX)) tiltX = 0.0;
    if (std::isnan(tiltY) || !std::isfinite(tiltY)) tiltY = 0.0;
    if (std::isnan(rotation) || !std::isfinite(rotation)) rotation = 0.0;
    // Safety cleanup: if a previous stroke was left uncommitted or unclosed, finish it now
    if (m_strokeBatchOpen) {
        touchStrokeEnd();
    }
    endStrokeBatch();
    if (m_strokeTxn) {
        m_strokeTxn->revert();
        delete m_strokeTxn;
        m_strokeTxn = nullptr;
        m_strokeTxnActive = false;
    }

    // Defer the undo snapshot to the first real flush: reading every layer
    // here costs a full-document read per touch-down, which is felt as lag
    // when starting strokes. Nothing is painted at down time anyway.
    m_snapshotPending = true;
    m_drawing = true;
    m_strokeBatchOpen = true;
    m_lastPressure = pressure;
    m_lastTiltX = tiltX;
    m_lastTiltY = tiltY;
    m_lastRotation = rotation;
    m_strokeColor = m_brushColor;
    m_strokeOpacity = m_brushOpacity;
    m_idleKickPainted = false;
    m_strokeTimer.restart();
    m_strokeCounter++;
    const int strokeSeed = int(quint32(m_strokeCounter) * 1664525u + 1013904223u);
    m_randomSource = new KisRandomSource(strokeSeed);
    m_perStrokeRandomSource = new KisPerStrokeRandomSource();
    // The stroke paints straight onto the layer device with per-dab opacity
    // (Krita-native); no temporary buffer is used.
    m_strokeStartImg = QPointF(x, y);
    m_accumulatedStrokeBounds = QRectF(x, y, 1.0, 1.0);
    m_strokeSamples.clear();
    m_strokeCarryCount = 0;
    m_strokeHadMove = false;
    const qreal startTime = (timeSeconds >= 0.0) ? timeSeconds : 0.0;
    m_lastSimulatedFlushTime = startTime;
    // The stroke starts at the finger-down position: append it as the first
    // sample so the down -> first-move segment is drawn. Otherwise the first
    // flush sees one sample and paints a dot, and the stroke start is cut off
    // (Android can move several px before the first move event arrives).
    StrokeSample s;
    s.imgPos = m_strokeStartImg;
    s.pressure = pressure;
    s.tiltX = tiltX;
    s.tiltY = tiltY;
    s.rotation = rotation;
    s.time = startTime;
    m_strokeSamples.append(s);
}

bool ReverieCore::touchStrokeMove(qreal x, qreal y, qreal pressure, qreal tiltX, qreal tiltY, qreal rotation, qreal timeSeconds)
{
    if (!m_drawing || !m_strokeBatchOpen || std::isnan(x) || std::isnan(y) || !std::isfinite(x) || !std::isfinite(y)) {
        return false;
    }
    if (std::isnan(pressure) || !std::isfinite(pressure) || pressure < 0.0) {
        pressure = 1.0;
    }
    if (std::isnan(tiltX) || !std::isfinite(tiltX)) tiltX = 0.0;
    if (std::isnan(tiltY) || !std::isfinite(tiltY)) tiltY = 0.0;
    if (std::isnan(rotation) || !std::isfinite(rotation)) rotation = 0.0;
    const QPointF imgPos(x, y);
    m_accumulatedStrokeBounds = m_accumulatedStrokeBounds.united(QRectF(x, y, 1.0, 1.0));
    const QPointF lastPos = m_strokeSamples.isEmpty()
            ? m_strokeStartImg
            : m_strokeSamples.last().imgPos;
    if (imgPos != lastPos) {
        return appendStrokeSample(imgPos, pressure, tiltX, tiltY, rotation, timeSeconds);
    }
    return false;
}

bool ReverieCore::touchStrokeKickIdle()
{
    // Called shortly after touchStrokeStart from Kotlin when nothing moved
    // yet: paint the stroke-start dot right away so pen-down gives instant
    // ink feedback (hold-still / very slow start previously showed nothing
    // until pen-up). Once the stroke has real movement this is a no-op - the
    // normal flush path already covers it and the undo snapshot cost stays
    // deferred to the first real flush.
    if (!m_strokeBatchOpen || !m_document || m_strokeHadMove) {
        return false;
    }
    if (flushStrokeBatch()) {
        m_idleKickPainted = true;
        return true;
    }
    return false;
}

void ReverieCore::touchStrokeEnd()
{
    if (m_strokeBatchOpen) {
        if (m_strokeSamples.isEmpty()) {
            // The idle kick already painted the start dot: do not re-append
            // and re-dab it on pen-up (double ink at the same spot).
            if (!m_idleKickPainted) {
                StrokeSample s;
                s.imgPos = m_strokeStartImg;
                s.pressure = m_lastPressure;
                s.tiltX = m_lastTiltX;
                s.tiltY = m_lastTiltY;
                s.rotation = m_lastRotation;
                m_strokeSamples.append(s);
            }
        }
        flushStrokeBatch();
        endStrokeBatch();
        m_strokeBatchOpen = false;
    }

    // Finalize Indirect Painting (for Wash mode / Shape_fill / experimentbrush):
    // Blend the completed temporary scratch target onto the actual layer device
    // inside a single undo transaction.
    KisPaintLayer *pl = (m_currentLayer >= 0 && m_currentLayer < m_layers.size())
        ? dynamic_cast<KisPaintLayer *>(m_layers[m_currentLayer].node)
        : nullptr;
    if (pl && pl->hasTemporaryTarget()) {
        KisPaintDeviceSP tempTarget = pl->temporaryTarget();
        const QRect ext = tempTarget->exactBounds();
        if (!ext.isEmpty()) {
            if (m_document && m_undoCaptureEnabled) {
                if (m_strokeTxn) {
                    m_strokeTxn->revert();
                    delete m_strokeTxn;
                    m_strokeTxn = nullptr;
                    m_strokeTxnActive = false;
                }
                m_strokeTxn = new KisTransaction(kundo2_i18n("Stroke"), pl->paintDevice());
                m_strokeTxnActive = true;
            }
            KisPainter gc(pl->paintDevice());
            pl->setupTemporaryPainter(&gc);
            if (gc.compositeOpId().isEmpty()) {
                gc.setCompositeOpId(QStringLiteral("normal"));
            }
            if (m_toolMode == ToolEraser) {
                gc.setCompositeOpId(QStringLiteral("erase"));
            }
            if (m_selection) {
                gc.setSelection(m_selection);
            }
            gc.setChannelFlags(pl->alphaLocked() ? pl->channelLockFlags() : QBitArray());
            gc.bitBlt(ext.topLeft(), tempTarget, ext);
            gc.end();
            markRegionDirty(ext);
            bumpLayerThumbGen(pl);
        }
        pl->setTemporaryTarget(nullptr);
        pl->setTemporaryChannelFlags(QBitArray());
        tempTarget->clear();
    }

    // Canvas Boundary & Memory Optimization:
    // When not in infinite canvas mode, automatically crop out-of-bounds tiles
    // from the layer's paint device INSIDE the transaction so it's fully tracked by undo.
    if (!m_infiniteCanvas && m_document) {
        const QRect canvasBounds(0, 0, m_document->width(), m_document->height());
        KisPaintDeviceSP dev = pl ? pl->paintDevice() : currentPaintDevice();
        if (dev) {
            const QRect ext = dev->extent();
            if (!ext.isEmpty() && !canvasBounds.contains(ext)) {
                dev->crop(canvasBounds);
            }
        }
    }

    // Commit the Krita transaction: the tile snapshots taken at creation
    // are diffed and the undo command is pushed to the store. In replay
    // mode the transaction is finalized cleanly without growing undo history.
    if (m_strokeTxnActive && m_document) {
        if (m_undoCaptureEnabled) {
            m_strokeTxn->commit(m_document->undoAdapter());
        } else {
            KUndo2Command *cmd = m_strokeTxn->endAndTake();
            delete cmd;
        }
        m_strokeTxn = nullptr;
        m_strokeTxnActive = false;
        m_redoCount = 0;
    }

    // Propagate final dirty region for fast synchronous compositing.
    // Do NOT trigger Krita's background async scheduler (endDev->setDirty) which
    // clears projection tiles asynchronously and races with rendering, creating
    // dirty block artifacts and visual flickering during continuous painting.
    KisPaintDeviceSP endDev = pl ? pl->paintDevice() : currentPaintDevice();
    if (endDev && m_document) {
        int strokeMargin = 0;
        if (m_currentLayer >= 0 && m_currentLayer < m_layers.size() && m_layers[m_currentLayer].isStrokeLayer) {
            strokeMargin = m_layers[m_currentLayer].strokeSize + 4;
        }
        const int margin = qMax(int(m_brushSize * 2.0), 32) + 16 + strokeMargin;
        const QRect totalDirty = m_accumulatedStrokeBounds.toAlignedRect().adjusted(
            -margin, -margin, margin, margin).intersected(
            QRect(0, 0, m_document->width(), m_document->height()));
        if (!totalDirty.isEmpty()) {
            markRegionDirty(totalDirty);
        }
    }

    m_drawing = false;
}

void ReverieCore::touchStrokeCancel()
{
    if (!m_document || !m_strokeBatchOpen) {
        m_drawing = false;
        m_strokeSamples.clear();
        m_strokeBatchOpen = false;
        return;
    }

    KisPaintLayer *pl = (m_currentLayer >= 0 && m_currentLayer < m_layers.size())
        ? dynamic_cast<KisPaintLayer *>(m_layers[m_currentLayer].node)
        : nullptr;
    if (pl && pl->hasTemporaryTarget()) {
        KisPaintDeviceSP tempTarget = pl->temporaryTarget();
        const QRect ext = tempTarget->exactBounds();
        pl->setTemporaryTarget(nullptr);
        tempTarget->clear();
        if (!ext.isEmpty()) {
            pl->setDirty(ext);
            markRegionDirty(ext);
            recompositeProjection();
            markDirty();
        }
    }

    // A second finger must cancel, not commit, the partial stroke. The
    // partial dabs of earlier 8ms flushes are already on the layer device,
    // so the Krita transaction must be REVERTED (tile snapshots written back)
    // before being discarded - deleting it outright left the half stroke
    // painted on the layer with no undo command, i.e. a ghost stroke.
    if (m_strokeTxn) {
        m_strokeTxn->revert();
        delete m_strokeTxn;
        m_strokeTxn = nullptr;
        m_strokeTxnActive = false;
        // The reverted tiles changed the device back: recomposite so the
        // ghost stroke disappears from the projection/display too
        if (KisImageSP image = m_document) {
            if (KisPaintDeviceSP target = currentPaintDevice()) {
                target->setDirty();
            }
            recompositeProjection();
            markDirty();
        }
    }
    while (m_macroDepth > 0) {
        if (m_document && m_document->undoAdapter()) {
            m_document->undoAdapter()->endMacro();
        }
        m_macroDepth--;
    }
    m_snapshotPending = false;
    m_strokeSamples.clear();
    m_strokeCarryCount = 0;
    m_idleKickPainted = false;
    endStrokeBatch();
    m_strokeBatchOpen = false;
    m_drawing = false;
}

bool ReverieCore::appendStrokeSample(const QPointF &imgPos, qreal pressure, qreal tiltX, qreal tiltY, qreal rotation, qreal timeSeconds)
{
    if (std::isnan(tiltX) || !std::isfinite(tiltX)) tiltX = 0.0;
    if (std::isnan(tiltY) || !std::isfinite(tiltY)) tiltY = 0.0;
    if (std::isnan(rotation) || !std::isfinite(rotation)) rotation = 0.0;

    QString opId;
    if (m_toolMode == ToolSmudge) {
        opId = QStringLiteral("colorsmudge");
    } else if (m_brushPreset) {
        opId = m_brushPreset->paintOp().id();
    }
    const bool isPathEngine = (opId == QLatin1String("experimentbrush") ||
                               opId == QLatin1String("curvebrush") ||
                               opId == QLatin1String("sketchbrush") ||
                               opId == QLatin1String("gridbrush"));
    // Krita emits dabs via KisDistanceInformation at the preset's own spacing;
    // this outer filter only gates SAMPLE emission into the batch, so keep it
    // small and fixed. The old max(1.5px, 20% diameter) left a blind zone of
    // up to 20% of the brush diameter during slow strokes (ink only appeared
    // on pen-up). Path engines keep fine 1.5px sampling for smooth contours.
    // For standard brushes, adaptively scale spacing slightly with brush size
    // (capped at 2.5px) to prevent sample flooding on large brushes (e.g. 500px).
    const qreal spacing = isPathEngine
        ? 1.5
        : qBound<qreal>(0.75, m_brushSize * 0.01, 2.5);
    if (!m_strokeSamples.isEmpty()) {
        const QPointF last = m_strokeSamples.last().imgPos;
        const qreal dist = QLineF(last, imgPos).length();
        if (dist < spacing) {
            m_strokeSamples.last().pressure = pressure;
            m_strokeSamples.last().tiltX = tiltX;
            m_strokeSamples.last().tiltY = tiltY;
            m_strokeSamples.last().rotation = rotation;
            return false;
        }
    }
    m_strokeHadMove = true;
    StrokeSample s;
    s.imgPos = imgPos;
    s.pressure = pressure;
    s.tiltX = tiltX;
    s.tiltY = tiltY;
    s.rotation = rotation;
    if (timeSeconds >= 0.0) {
        s.time = timeSeconds;
    } else {
        s.time = m_strokeTimer.elapsed() / 1000.0;
    }
    m_strokeSamples.append(s);
    // 144Hz / 120Hz 高刷新率自适应刷新门槛：实时绘制 4ms / 32 样本；
    // 回放模式按记录的仿真相对时间步进 (>8ms 或 32 样本)，确保 1x/32x/seek 分批完全确定性对齐。
    const qint64 now = QDateTime::currentMSecsSinceEpoch();
    const bool shouldFlush = (timeSeconds >= 0.0)
        ? ((s.time - m_lastSimulatedFlushTime >= 0.008) || m_strokeSamples.size() >= 32)
        : (now - m_lastFlushMs >= 4 || m_strokeSamples.size() >= 32);
    if (shouldFlush) {
        m_lastFlushMs = now;
        if (timeSeconds >= 0.0) {
            m_lastSimulatedFlushTime = s.time;
        }
        return flushStrokeBatch();
    }
    return false;
}

// Centripetal Catmull-Rom spline point: evaluates the curve through
// P0,P1,P2,P3 at u in [0,1] (u=0 at P1, u=1 at P2). Centripetal
// parameterisation prevents the overshoot "hooks" that uniform Catmull-Rom
// produces on sharply curving strokes.

bool ReverieCore::flushStrokeBatch()
{
    if (m_strokeSamples.isEmpty()) {
        return false;
    }
    KisImageSP image = m_document;
    if (!image) {
        m_strokeSamples.clear();
        return false;
    }
    bool isEraserPreset;
    if (m_presetIsEraserOverride >= 0) {
        isEraserPreset = m_presetIsEraserOverride == 1;
    } else {
        isEraserPreset = m_brushPreset && (
            m_brushPreset->name().startsWith(QLatin1String("a)_Eraser"), Qt::CaseInsensitive) ||
            m_brushPreset->name().contains(QLatin1String("Eraser"), Qt::CaseInsensitive)
        );
    }
    const bool erasing = (m_toolMode == ToolEraser) || ((m_toolMode != ToolSmudge) && isEraserPreset);

    QString effectiveOp = QStringLiteral("normal");
    if (erasing) {
        effectiveOp = QStringLiteral("erase");
    } else if (m_brushPreset && m_brushPreset->settings()) {
        effectiveOp = m_brushPreset->settings()->effectivePaintOpCompositeOp();
        if (effectiveOp.isEmpty() || effectiveOp == QLatin1String("erase")) {
            effectiveOp = QStringLiteral("normal");
        }
    }

    if (m_brushPreset && m_brushPreset->settings()) {
        m_brushPreset->settings()->setEraserMode(erasing);
    }

    // Krita indirect painting check:
    // Non-incremental brushes (like experimentbrush / Shape_fill, sketch, curve)
    // must paint onto an indirect temporary target to avoid COMPOSITE_COPY
    // erasing/mosaic-clipping the existing layer pixels behind the stroke!
    // In erasing mode, the non-incremental brush paints its opaque/anti-aliased
    // dab onto the temporary target, which is then composited onto the layer
    // with COMPOSITE_ERASE (effectiveOp).
    // Note: ToolSmudge and presets with colorsmudge engine must ALWAYS paint directly to canvas to blend underlying pixels.
    const bool isSmudgeOp = (m_toolMode == ToolSmudge) ||
        (m_brushPreset && m_brushPreset->paintOp().id() == QStringLiteral("colorsmudge"));

    // 读取图层既有像素的引擎 (deform 形变/移动、filter 卷积滤波、duplicate 克隆采样)
    // 一旦被间接绘制重定向到全新的空白 tempTarget, 读到的全是透明像素, 落笔等于空操作。
    // 这些引擎必须始终直接作用于 currentPaintDevice()。
    const QString paintOpId = m_brushPreset ? m_brushPreset->paintOp().id() : QString();
    const bool readsLayerPixels = isSmudgeOp ||
        paintOpId == QStringLiteral("deformbrush") ||
        paintOpId == QStringLiteral("filter") ||
        paintOpId == QStringLiteral("duplicate");

    const bool hasMasking = !isSmudgeOp && m_brushPreset && m_brushPreset->hasMaskingPreset();

    const bool needsIndirect = (!readsLayerPixels && m_brushPreset && m_brushPreset->settings() &&
        !m_brushPreset->settings()->paintIncremental()) || hasMasking;

    KisPaintLayer *pl = (m_currentLayer >= 0 && m_currentLayer < m_layers.size())
        ? dynamic_cast<KisPaintLayer *>(m_layers[m_currentLayer].node)
        : nullptr;

    const QString painterCompOp = needsIndirect
        ? (m_brushPreset && m_brushPreset->settings() ? m_brushPreset->settings()->indirectPaintingCompositeOp() : QStringLiteral("alphadarken"))
        : effectiveOp;

    KisPaintDeviceSP target;
    if (needsIndirect && pl) {
        if (!pl->hasTemporaryTarget()) {
            KisPaintDeviceSP tempTarget = pl->paintDevice()->createCompositionSourceDevice();
            tempTarget->setParentNode(pl);
            pl->setTemporaryTarget(tempTarget);
            pl->setTemporaryCompositeOp(effectiveOp);
            pl->setTemporaryOpacity(qBound<qreal>(0.0, m_strokeOpacity, 1.0));
            pl->setTemporarySelection(m_selection);
            pl->setTemporaryChannelFlags(pl->alphaLocked() ? pl->channelLockFlags() : QBitArray());
        }
        target = pl->temporaryTarget();
    } else {
        target = currentPaintDevice();
    }

    if (!target) {
        m_strokeSamples.clear();
        return false;
    }

    const KoColorSpace *cs = image->colorSpace();
    QColor qColor(m_strokeColor);
    if (!qColor.isValid()) {
        qColor = Qt::black;
    }
    KoColor koColor(qColor, cs);

    QColor qBgColor(m_brushSecondaryColor);
    if (!qBgColor.isValid()) {
        qBgColor = Qt::white;
    }
    KoColor koBgColor(qBgColor, cs);

    // Krita-style: reuse one KisPainter for the whole stroke.
    if (m_snapshotPending || (!m_strokePainter && !m_maskedStrokePainter) || m_strokeDevice != (void *)target.data()) {
        endStrokeBatch();
        m_strokeDevice = (void *)target.data();
        // 特效笔刷 (deform/filter/spray/particle...) 无效果的排查埋点: 一条笔画只打一次,
        // 输出引擎 id / 增量绘制判定 / 间接绘制判定 / 绘制目标是否为临时图层。
        const QRect tgtExtent = target ? target->extent() : QRect();
        RPC_LOG("RPC strokeSetup op=%s incr=%d masking=%d indirect=%d tempTarget=%d compOp=%s size=%.1f tgtExtent=%d,%d %dx%d",
                paintOpId.isEmpty() ? "none" : paintOpId.toUtf8().constData(),
                (m_brushPreset && m_brushPreset->settings() && m_brushPreset->settings()->paintIncremental()) ? 1 : 0,
                hasMasking ? 1 : 0,
                needsIndirect ? 1 : 0,
                (needsIndirect && pl) ? 1 : 0,
                painterCompOp.toUtf8().constData(),
                (double)m_brushSize,
                tgtExtent.x(), tgtExtent.y(), tgtExtent.width(), tgtExtent.height());
        // 滤镜笔刷专属校验: KisFilterOp 在 KisFilterRegistry 查不到滤镜时会静默空转
        // (paintAt 直接 return, 一笔不出墨), 这里把查库结果暴露出来。
        if (paintOpId == QLatin1String("filter") && m_brushPreset && m_brushPreset->settings()) {
            const QString fid = m_brushPreset->settings()->getString(QStringLiteral("Filter/id"));
            KisFilterSP f = fid.isEmpty() ? KisFilterSP() : KisFilterRegistry::instance()->get(fid);
            RPC_LOG("RPC filterOp id=%s found=%d", fid.isEmpty() ? "empty" : fid.toUtf8().constData(), f ? 1 : 0);
        }
        // Deferred Krita undo: for direct painting, start transaction on the layer.
        if (!needsIndirect && m_snapshotPending && !m_strokeTxnActive && m_undoCaptureEnabled) {
            if (m_strokeTxn) {
                m_strokeTxn->revert();
                delete m_strokeTxn;
                m_strokeTxn = nullptr;
            }
            KisInterstrokeDataFactory *interstrokeDataFactory = nullptr;
            const bool isColorSmudge = (m_toolMode == ToolSmudge) ||
                (m_brushPreset && m_brushPreset->paintOp().id() == QStringLiteral("colorsmudge"));
            if (m_brushPreset) {
                if (isColorSmudge) {
                    KisPaintOpFactory *f = KisPaintOpRegistry::instance()->value(QStringLiteral("colorsmudge"));
                    if (f) {
                        interstrokeDataFactory = f->createInterstrokeDataFactory(m_brushPreset->settings(), m_brushPreset->resourcesInterface());
                    }
                }
                if (!interstrokeDataFactory) {
                    interstrokeDataFactory = KisPaintOpRegistry::instance()->createInterstrokeDataFactory(m_brushPreset);
                }
            }
            KisInterstrokeDataTransactionWrapperFactory *wrapper = nullptr;
            if (interstrokeDataFactory) {
                wrapper = new KisInterstrokeDataTransactionWrapperFactory(interstrokeDataFactory, true);
            }
            m_strokeTxn = new KisTransaction(
                kundo2_i18n("Stroke"), target, nullptr, -1, wrapper);
            m_strokeTxnActive = true;
        }
        m_snapshotPending = false;

        const int layerIndex = qBound(0, m_currentLayer, m_layers.size() - 1);
        const QPointF start =
            m_strokeSamples.isEmpty() ? m_strokeStartImg : m_strokeSamples.first().imgPos;

        // 双笔刷蒙版链路: 渲染器与蒙版预设任一不可用都不能硬闯 —— 否则
        // KisPaintDevice(nullptr) / setPaintOpPreset(nullptr) 会直接崩进程。
        // 兜底时退回普通单笔刷链路, 该笔画退化为无水渍效果但绝不闪退。
        KisPaintOpPresetSP maskingPreset;
        if (hasMasking && m_brushPreset) {
            const QString maskingCompOpId = m_brushPreset->settings()
                ? m_brushPreset->settings()->maskingBrushCompositeOp()
                : QStringLiteral("alphadarken");
            m_maskingBrushRenderer = new KisMaskingBrushRenderer(target, maskingCompOpId);
            if (!m_maskingBrushRenderer->strokeDevice() || !m_maskingBrushRenderer->maskDevice()) {
                delete m_maskingBrushRenderer;
                m_maskingBrushRenderer = nullptr;
                RPC_LOG("RPC masking: renderer devices unavailable, fall back to plain stroke");
            } else {
                maskingPreset = m_brushPreset->createMaskingPreset();
                if (!maskingPreset) {
                    delete m_maskingBrushRenderer;
                    m_maskingBrushRenderer = nullptr;
                    RPC_LOG("RPC masking: createMaskingPreset null, fall back to plain stroke");
                }
            }
        }
        if (m_maskingBrushRenderer && maskingPreset) {
            KisDistanceInformation startDist(start, 0.0);
            m_strokeInfo = new KisFreehandStrokeInfo(startDist);
            m_maskInfo = new KisFreehandStrokeInfo(startDist);

            KisPainter *strokeP = m_strokeInfo->painter;
            strokeP->begin(m_maskingBrushRenderer->strokeDevice(), nullptr);
            strokeP->setRunnableStrokeJobsInterface(&m_fakeExecutor);
            strokeP->setFillStyle(KisPainter::FillStyleForegroundColor);
            strokeP->setStrokeStyle(KisPainter::StrokeStyleBrush);
            strokeP->setCompositeOpId(QStringLiteral("alphadarken"));
            strokeP->setOpacityToUnit();
            strokeP->setPaintColor(koColor);
            strokeP->setBackgroundColor(koBgColor);
            strokeP->setChannelFlags(QBitArray());
            strokeP->setPaintOpPreset(m_brushPreset, KisNodeSP(m_layers[layerIndex].node), image);

            KisPainter *maskP = m_maskInfo->painter;
            maskP->begin(m_maskingBrushRenderer->maskDevice(), nullptr);
            maskP->setRunnableStrokeJobsInterface(&m_fakeExecutor);
            maskP->setFillStyle(KisPainter::FillStyleForegroundColor);
            maskP->setStrokeStyle(KisPainter::StrokeStyleBrush);
            maskP->setCompositeOpId(QStringLiteral("alphadarken"));
            maskP->setOpacityToUnit();
            maskP->setPaintColor(KoColor(Qt::white, maskP->device()->colorSpace()));
            maskP->setBackgroundColor(KoColor(Qt::black, maskP->device()->colorSpace()));
            maskP->setChannelFlags(QBitArray());
            maskP->setPaintOpPreset(maskingPreset, KisNodeSP(m_layers[layerIndex].node), image);

            m_maskedStrokePainter = new KisMaskedFreehandStrokePainter(m_strokeInfo, m_maskInfo);
        } else {
            m_strokePainter = new KisPainter(target);
            m_strokePainter->setFillStyle(KisPainter::FillStyleForegroundColor);
            m_strokePainter->setStrokeStyle(KisPainter::StrokeStyleBrush);
            m_strokePainter->setCompositeOpId(painterCompOp);
            m_strokePainter->setOpacityF(needsIndirect ? 1.0 : (m_brushPreset ? 1.0 : qBound<qreal>(0.0, m_strokeOpacity, 1.0)));
            m_strokePainter->setPaintColor(koColor);
            m_strokePainter->setBackgroundColor(koBgColor);

            // Constrain the whole stroke to the active selection (if any)
            if (m_selection) {
                m_strokePainter->setSelection(m_selection);
            }
            m_strokePainter->setChannelFlags(pl && pl->alphaLocked() ? pl->channelLockFlags() : QBitArray());
            // Real Krita brush engine: construct the brush op once per stroke
            // and drive its async dab pipeline synchronously (the fake executor
            // runs the rendering jobs inline, exactly like Krita's own tests).
            if (m_brushPreset && m_strokePainter) {
                const bool isColorSmudge = (m_toolMode == ToolSmudge) ||
                    (m_brushPreset->paintOp().id() == QStringLiteral("colorsmudge"));
                KisPaintOpFactory *smudgeFactory =
                    isColorSmudge ? KisPaintOpRegistry::instance()->value(QStringLiteral("colorsmudge")) : nullptr;

                std::unique_ptr<KisInterstrokeDataFactory> factory;
                if (smudgeFactory) {
                    factory.reset(smudgeFactory->createInterstrokeDataFactory(m_brushPreset->settings(), m_brushPreset->resourcesInterface()));
                }
                if (!factory) {
                    factory.reset(KisPaintOpRegistry::instance()->createInterstrokeDataFactory(m_brushPreset));
                }
                if (factory) {
                    KUndo2Command *cmd = target->createChangeInterstrokeDataCommand(toQShared(factory->create(target)));
                    if (cmd) {
                        cmd->redo();
                        delete cmd;
                    }
                }
                m_strokePainter->setRunnableStrokeJobsInterface(&m_fakeExecutor);
                if (smudgeFactory) {
                    m_strokeOp = smudgeFactory->createOp(m_brushPreset->settings(), m_strokePainter,
                                                         KisNodeSP(m_layers[layerIndex].node), image);
                }
                if (!m_strokeOp) {
                    m_strokeOp = KisPaintOpRegistry::instance()->paintOp(
                        m_brushPreset, m_strokePainter,
                        KisNodeSP(m_layers[layerIndex].node), image);
                }
                // 最后一档兜底: 引擎未注册时静默退化成 KisBrushOp 会让笔迹完全不对
                // 且毫无提示 (本机 mypaintbrush / dynabrush 就是这种情况)。这里显式
                // 打一条警告, 让"这支笔为什么画得不对"可以直接从 logcat 看出来。
                const bool engineFellBack = !m_strokeOp;
                if (!m_strokeOp) {
                    m_strokeOp = new KisBrushOp(m_brushPreset->settings(), m_strokePainter,
                                                KisNodeSP(m_layers[layerIndex].node), image);
                }
                if (engineFellBack) {
                    RPC_LOG("RPC strokeOp UNSUPPORTED engine=%s -> fell back to KisBrushOp (engine not registered in this build)",
                            m_brushPreset->paintOp().id().toUtf8().constData());
                }
                const char *actualOpName = m_strokeOp ? typeid(*m_strokeOp).name() : "null";
                RPC_LOG("RPC strokeOp created: presetId=%s, actualOp=%s",
                        m_brushPreset->paintOp().id().toUtf8().constData(),
                        actualOpName);
                delete m_strokeDistance;
                m_strokeDistance = new KisDistanceInformation(start, 0.0);
            }
        }
    }
    // Re-sync the composite op on every flush so mid-stroke parameter
    // changes (blend-mode dropdown, eraser preset switch) take effect.
    if (m_strokePainter) {
        m_strokePainter->setCompositeOpId(painterCompOp);
        m_strokePainter->setOpacityF(needsIndirect ? 1.0 : (m_brushPreset ? 1.0 : qBound<qreal>(0.0, m_strokeOpacity, 1.0)));
        m_strokePainter->setPaintColor(koColor);
        m_strokePainter->setBackgroundColor(koBgColor);
        if (m_selection) {
            m_strokePainter->setSelection(m_selection);
        } else {
            m_strokePainter->setSelection(KisSelectionSP());
        }
    }

    // Genuine tap only (no movement): paint a round dot.
    if (m_strokeSamples.size() == 1 && !m_strokeHadMove) {
        if (m_idleKickPainted) {
            // Already painted by touchStrokeKickIdle, do not re-dab on pen-up
            m_strokeSamples.clear();
            m_strokeCarryCount = 0;
            return false;
        }
        const StrokeSample &first = m_strokeSamples.first();
        const QPointF p = first.imgPos;
        const qreal pressure =
            qBound<qreal>(0.0, first.pressure, 1.0);
        QRect strokeDirty;
        if (m_maskedStrokePainter) {
            KisPaintInformation info(p, pressure, first.tiltX, first.tiltY, first.rotation, 0.0, 1.0, first.time, 0.0);
            if (m_randomSource) info.setRandomSource(m_randomSource);
            if (m_perStrokeRandomSource) info.setPerStrokeRandomSource(m_perStrokeRandomSource);
            m_maskedStrokePainter->paintAt(info);
            while (true) {
                QVector<KisRunnableStrokeJobData *> jobs;
                auto result = m_maskedStrokePainter->doAsynchronousUpdate(jobs);
                for (auto *j : jobs) {
                    j->run();
                    delete j;
                }
                if (jobs.isEmpty() || !result.second) {
                    break;
                }
            }
            if (m_maskingBrushRenderer) {
                const QVector<QRect> exactDirty = m_maskedStrokePainter->takeDirtyRegion();
                for (const QRect &r : exactDirty) {
                    m_maskingBrushRenderer->updateProjection(r);
                    strokeDirty = strokeDirty.isNull() ? r : strokeDirty.united(r);
                }
            }
        } else if (m_brushPreset && m_strokeOp) {
            // Krita dab for a genuine tap (paintAt = single dab at pos)
            KisPaintInformation info(p, pressure, first.tiltX, first.tiltY, first.rotation, 0.0, 1.0, first.time, 0.0);
            if (m_randomSource) info.setRandomSource(m_randomSource);
            if (m_perStrokeRandomSource) info.setPerStrokeRandomSource(m_perStrokeRandomSource);
            m_strokeOp->paintAt(info, m_strokeDistance);
            while (true) {
                QVector<KisRunnableStrokeJobData *> jobs;
                auto result = m_strokeOp->doAsynchronousUpdate(jobs);
                for (auto *j : jobs) {
                    j->run();
                    delete j;
                }
                if (jobs.isEmpty() || !result.second) {
                    break;
                }
            }
        } else if (m_strokePainter) {
            qreal w = m_brushSize * pressure;
            w = qMax(w, qMax<qreal>(1.0, m_brushSize * 0.02));
            m_strokePainter->paintEllipse(QRectF(p.x() - w / 2.0, p.y() - w / 2.0, w, w));
        }
        // Propagate the tap dot to the projection immediately
        int tw = int(m_brushSize) + 2;
        if (m_currentLayer >= 0 && m_currentLayer < m_layers.size() && m_layers[m_currentLayer].isStrokeLayer) {
            tw += m_layers[m_currentLayer].strokeSize + 4;
        }
        const QRect tr = QRect(int(p.x()) - tw, int(p.y()) - tw, 2 * tw, 2 * tw).intersected(
            QRect(0, 0, m_docWidth, m_docHeight));
        markRegionDirty(strokeDirty.isNull() ? tr : strokeDirty);
        bumpLayerThumbGen(m_layers[m_currentLayer].node);
        m_strokeCarryCount = 1;
        return true;
    }

    QRect strokeDirty;
    if (m_maskedStrokePainter) {
        const int firstNewSegment = qBound(1, m_strokeCarryCount, qMax(1, m_strokeSamples.size()));
        for (int i = firstNewSegment; i < m_strokeSamples.size(); ++i) {
            const StrokeSample &a = m_strokeSamples[i - 1];
            const StrokeSample &b = m_strokeSamples[i];
            const qreal dist = QLineF(a.imgPos, b.imgPos).length();
            const qreal dt = qMax<qreal>(1e-4, b.time - a.time);
            // Speed in px/ms to align with Krita's sensor scale
            const qreal speed = dist / (dt * 1000.0);
            KisPaintInformation infoA(a.imgPos, a.pressure, a.tiltX, a.tiltY, a.rotation, 0.0, 1.0, a.time, speed);
            KisPaintInformation infoB(b.imgPos, b.pressure, b.tiltX, b.tiltY, b.rotation, 0.0, 1.0, b.time, speed);
            if (m_randomSource) {
                infoA.setRandomSource(m_randomSource);
                infoB.setRandomSource(m_randomSource);
            }
            if (m_perStrokeRandomSource) {
                infoA.setPerStrokeRandomSource(m_perStrokeRandomSource);
                infoB.setPerStrokeRandomSource(m_perStrokeRandomSource);
            }
            m_maskedStrokePainter->paintLine(infoA, infoB);
        }
        while (true) {
            QVector<KisRunnableStrokeJobData *> jobs;
            auto result = m_maskedStrokePainter->doAsynchronousUpdate(jobs);
            for (auto *j : jobs) {
                j->run();
                delete j;
            }
            if (jobs.isEmpty() || !result.second) {
                break;
            }
        }
        if (m_maskingBrushRenderer) {
            const QVector<QRect> exactDirty = m_maskedStrokePainter->takeDirtyRegion();
            for (const QRect &r : exactDirty) {
                m_maskingBrushRenderer->updateProjection(r);
                strokeDirty = strokeDirty.isNull() ? r : strokeDirty.united(r);
            }
        }
    } else if (m_brushPreset && m_strokeOp && m_strokePainter) {
        // ---- Real Krita brush engine ----
        KisPainter &painter = *m_strokePainter;
        const QString opId = (m_toolMode == ToolSmudge) ? QStringLiteral("colorsmudge") : m_brushPreset->paintOp().id();
        const bool isPathEngine = (opId == QLatin1String("experimentbrush") ||
                                   opId == QLatin1String("curvebrush") ||
                                   opId == QLatin1String("sketchbrush") ||
                                   opId == QLatin1String("gridbrush") ||
                                   opId == QLatin1String("particlebrush"));
        const bool engineBypassesSelection =
            opId != QLatin1String("paintbrush") && opId != QLatin1String("duplicate") && opId != QLatin1String("colorsmudge");
        QByteArray selClipBefore;
        QRect selClipBox;
        if (m_selection && engineBypassesSelection) {
            if (isPathEngine) {
                const int margin = int(m_brushSize) + 8;
                selClipBox = m_accumulatedStrokeBounds.toAlignedRect().adjusted(-margin, -margin, margin, margin);
            } else {
                for (const StrokeSample &sm : m_strokeSamples) {
                    const int w = int(m_brushSize) + 2;
                    const QRect r(int(sm.imgPos.x()) - w, int(sm.imgPos.y()) - w,
                                  2 * w, 2 * w);
                    selClipBox = selClipBox.isNull() ? r : selClipBox.united(r);
                }
            }
            selClipBox &= QRect(0, 0, image->width(), image->height());
            if (!selClipBox.isEmpty()) {
                const int ps = target->pixelSize();
                selClipBefore.resize(selClipBox.width() * selClipBox.height() * ps);
                target->readBytes(reinterpret_cast<quint8 *>(selClipBefore.data()),
                                  selClipBox.x(), selClipBox.y(),
                                  selClipBox.width(), selClipBox.height());
            }
        }
        const int firstNewSegment = qBound(1, m_strokeCarryCount, qMax(1, m_strokeSamples.size()));
        for (int i = firstNewSegment; i < m_strokeSamples.size(); ++i) {
            const StrokeSample &a = m_strokeSamples[i - 1];
            const StrokeSample &b = m_strokeSamples[i];
            const qreal dist = QLineF(a.imgPos, b.imgPos).length();
            const qreal dt = qMax<qreal>(1e-4, b.time - a.time);
            // Speed in px/ms to align with Krita's sensor scale
            const qreal speed = dist / (dt * 1000.0);
            KisPaintInformation infoA(a.imgPos, a.pressure, a.tiltX, a.tiltY, a.rotation, 0.0, 1.0, a.time, speed);
            KisPaintInformation infoB(b.imgPos, b.pressure, b.tiltX, b.tiltY, b.rotation, 0.0, 1.0, b.time, speed);
            if (m_randomSource) {
                infoA.setRandomSource(m_randomSource);
                infoB.setRandomSource(m_randomSource);
            }
            if (m_perStrokeRandomSource) {
                infoA.setPerStrokeRandomSource(m_perStrokeRandomSource);
                infoB.setPerStrokeRandomSource(m_perStrokeRandomSource);
            }
            m_strokeOp->paintLine(infoA, infoB, m_strokeDistance);
        }
        while (true) {
            QVector<KisRunnableStrokeJobData *> jobs;
            auto result = m_strokeOp->doAsynchronousUpdate(jobs);
            for (auto *j : jobs) {
                j->run();
                delete j;
            }
            if (jobs.isEmpty() || !result.second) {
                break;
            }
        }
        // Restore the pixels outside the selection for engines that bypass
        // KisPainter's selection clipping
        if (m_selection && engineBypassesSelection && !selClipBox.isEmpty() &&
            !selClipBefore.isEmpty()) {
            const int w = selClipBox.width();
            const int h = selClipBox.height();
            const int ps = target->pixelSize();
            QByteArray after;
            after.resize(size_t(w) * h * ps);
            target->readBytes(reinterpret_cast<quint8 *>(after.data()),
                              selClipBox.x(), selClipBox.y(), w, h);
            QByteArray maskB(size_t(w) * h, 0);
            m_selection->pixelSelection()->readBytes(
                reinterpret_cast<quint8 *>(maskB.data()),
                selClipBox.x(), selClipBox.y(), w, h);
            for (int yy = 0; yy < h; ++yy) {
                for (int xx = 0; xx < w; ++xx) {
                    if (maskB[size_t(yy) * w + xx] == 0) {
                        const int o = (yy * w + xx) * ps;
                        for (int k = 0; k < ps; ++k) {
                            after[o + k] = selClipBefore[o + k];
                        }
                    }
                }
            }
            target->writeBytes(reinterpret_cast<const quint8 *>(after.constData()),
                               selClipBox.x(), selClipBox.y(), w, h);
        }
        if (isPathEngine) {
            const int margin = int(m_brushSize) + 8;
            const QRect pathDirty = m_accumulatedStrokeBounds.toAlignedRect().adjusted(-margin, -margin, margin, margin);
            strokeDirty = strokeDirty.isNull() ? pathDirty : strokeDirty.united(pathDirty);
        }
        const QVector<QRect> exactDirty = painter.takeDirtyRegion();
        for (const QRect &r : exactDirty) {
            strokeDirty = strokeDirty.isNull() ? r : strokeDirty.united(r);
        }
        if (!isPathEngine && (engineBypassesSelection || exactDirty.isEmpty())) {
            const int startIndex = qMax(0, firstNewSegment - 1);
            if (startIndex < m_strokeSamples.size()) {
                qreal minX = m_strokeSamples[startIndex].imgPos.x();
                qreal maxX = minX;
                qreal minY = m_strokeSamples[startIndex].imgPos.y();
                qreal maxY = minY;
                for (int si = startIndex + 1; si < m_strokeSamples.size(); ++si) {
                    const qreal sx = m_strokeSamples[si].imgPos.x();
                    const qreal sy = m_strokeSamples[si].imgPos.y();
                    if (sx < minX) minX = sx;
                    if (sx > maxX) maxX = sx;
                    if (sy < minY) minY = sy;
                    if (sy > maxY) maxY = sy;
                }
                const int w = int(m_brushSize) + 2;
                const QRect r(int(minX) - w, int(minY) - w,
                              int(maxX - minX) + 2 * w, int(maxY - minY) + 2 * w);
                strokeDirty = strokeDirty.isNull() ? r : strokeDirty.united(r);
            }
        }
    } else if (m_strokePainter) {
        // ---- Fallback: classic round-dab loop (no preset loaded) ----
        KisPainter &painter = *m_strokePainter;
        const auto addDab = [&](const QPointF &p, qreal w) {
            painter.paintEllipse(QRectF(p.x() - w / 2.0, p.y() - w / 2.0, w, w));
            const QRect r(int(p.x()) - int(w) - 1, int(p.y()) - int(w) - 1,
                          2 * int(w) + 2, 2 * int(w) + 2);
            strokeDirty = strokeDirty.isNull() ? r : strokeDirty.united(r);
        };
        QPointF prev = m_strokeSamples.first().imgPos;
        qreal prevP = m_strokeSamples.first().pressure;
        qreal prevW = m_brushSize * qBound<qreal>(0.0, prevP, 1.0);
        prevW = qMax(prevW, qMax<qreal>(1.0, m_brushSize * 0.02));
        if (m_strokeCarryCount == 0) {
            addDab(prev, prevW);
        }
        for (int i = qMax(1, m_strokeCarryCount); i < m_strokeSamples.size(); ++i) {
            const QPointF cur = m_strokeSamples[i].imgPos;
            const qreal curP = m_strokeSamples[i].pressure;
            const QPointF p0 = (i >= 2) ? m_strokeSamples[i - 2].imgPos : prev + (prev - cur);
            const QPointF p1 = prev;
            const QPointF p2 = cur;
            const QPointF p3 = (i + 1 < m_strokeSamples.size()) ? m_strokeSamples[i + 1].imgPos
                                                                : cur + (cur - prev);
            const qreal segLen = QLineF(prev, cur).length();
            qreal segW = m_brushSize * qBound<qreal>(0.0, (prevP + curP) / 2.0, 1.0);
            segW = qMax(segW, qMax<qreal>(1.0, m_brushSize * 0.02));
            const qreal dabSpacing = qMax<qreal>(1.5, segW * 0.2);
            const int n = qMax(1, int(qCeil(segLen / dabSpacing)));
            for (int j = 1; j <= n; ++j) {
                const qreal t = qreal(j) / n;
                const QPointF p = centripetalCatmullRom(p0, p1, p2, p3, t);
                const qreal pMid = prevP + (curP - prevP) * t;
                qreal width = m_brushSize * qBound<qreal>(0.0, pMid, 1.0);
                width = qMax(width, qMax<qreal>(1.0, m_brushSize * 0.02));
                addDab(p, width);
            }
            prev = cur;
            prevP = curP;
        }
    }
    // Keep the last TWO samples as the next segment's context. A single
    // trailing sample made every flush a 2-sample batch whose only segment
    // is the degenerate first segment (P0==P1) - it painted just its
    // endpoints, producing dotted strokes with small brush widths.
    QVector<StrokeSample> trailing;
    if (m_strokeSamples.size() >= 2) {
        trailing << m_strokeSamples.at(m_strokeSamples.size() - 2)
                 << m_strokeSamples.last();
    } else if (!m_strokeSamples.isEmpty()) {
        trailing << m_strokeSamples.last();
    }
    m_strokeSamples.clear();
    for (const StrokeSample &t : trailing) {
        m_strokeSamples.append(t);
    }
    m_strokeCarryCount = trailing.size();

    // 排查埋点: 整批 flush 一个脏区都没产出 = 该引擎这一批完全没落墨。
    // 只在异常时打, 正常笔画不刷屏。
    if (strokeDirty.isNull()) {
        const QRect ext = target ? target->extent() : QRect();
        RPC_LOG("RPC strokeNoInk op=%s samples=%d size=%.1f compOp=%s devExtent=%d,%d %dx%d",
                paintOpId.isEmpty() ? "none" : paintOpId.toUtf8().constData(),
                (int)m_strokeSamples.size(),
                (double)m_brushSize,
                painterCompOp.toUtf8().constData(),
                ext.x(), ext.y(), ext.width(), ext.height());
    }

    // Hot path: propagate the dirty region for fast synchronous compositing without
    // scheduling background jobs in Krita's thread pool during active stroke
    if (!strokeDirty.isNull()) {
        if (m_currentLayer >= 0 && m_currentLayer < m_layers.size() && m_layers[m_currentLayer].isStrokeLayer) {
            const int extra = m_layers[m_currentLayer].strokeSize + 4;
            strokeDirty = strokeDirty.adjusted(-extra, -extra, extra, extra).intersected(
                QRect(0, 0, m_docWidth, m_docHeight));
        }
        markRegionDirty(strokeDirty);
        bumpLayerThumbGen(m_layers[m_currentLayer].node);
    }
    return !strokeDirty.isNull();
}

void ReverieCore::endStrokeBatch()
{
    if (KisPaintDeviceSP target = currentPaintDevice()) {
        if (target->interstrokeData()) {
            KUndo2Command *cmd = target->createChangeInterstrokeDataCommand(KisInterstrokeDataSP());
            if (cmd) {
                cmd->redo();
                delete cmd;
            }
        }
    }
    delete m_maskedStrokePainter;
    m_maskedStrokePainter = nullptr;
    delete m_strokeInfo;
    m_strokeInfo = nullptr;
    delete m_maskInfo;
    m_maskInfo = nullptr;
    delete m_maskingBrushRenderer;
    m_maskingBrushRenderer = nullptr;

    delete m_strokePainter;
    m_strokePainter = nullptr;
    m_strokeDevice = nullptr;
    // The brush op pins the painter's device; drop it first, then the
    // distance accumulator.
    m_strokeOp = nullptr;
    delete m_strokeDistance;
    m_strokeDistance = nullptr;
    m_randomSource.clear();
    m_perStrokeRandomSource.clear();
}

// ---------------------------------------------------------------------------
// Undo / redo
// ---------------------------------------------------------------------------

// Bounding box of the pixels that differ between two w*h RGBA buffers.
// Row-wise memcmp first (cheap), then a per-pixel pass only over the changed
// rows. Returns an empty QRect when the buffers are identical.

void ReverieCore::pushUndoCommand(KUndo2Command *cmd)
{
    if (!m_document || !cmd) {
        delete cmd;
        return;
    }
    // Replay mode: ops apply normally but must not grow undo history
    // (hundreds of replay commands would otherwise eat tile-snapshot memory).
    // Structural commands (KisImageLayerAddCommand, KisImageLayerRemoveCommand,
    // KisNodeRenameCommand, etc.) execute their actual mutation in redo().
    if (!m_undoCaptureEnabled) {
        cmd->redo();
        delete cmd;
        return;
    }
    if (!m_document->undoAdapter()) {
        cmd->redo();
        delete cmd;
        return;
    }
    // KisLegacyUndoAdapter::addCommand routes into our surrogate store
    // (installed via KisImage::setUndoStore); KUndo2Stack::push executes
    // the command's redo() and clears redo state.
    m_document->undoAdapter()->addCommand(cmd);
    m_redoCount = 0;
}

void ReverieCore::clearUndoHistory()
{
    if (!m_undoStore) {
        return;
    }
    m_undoStore->clear();
    m_redoCount = 0;
}

void ReverieCore::setUndoLimit(int limit)
{
    // 0 = 无上限 (KUndo2QStack 语义); Kotlin 侧正常只会传 10..200
    m_undoLimit = qMax(0, limit);
    if (m_undoStore) {
        m_undoStore->setUndoLimit(m_undoLimit);
    }
}

void ReverieCore::beginUndoMacro(const QString &text)
{
    if (m_document && m_document->undoAdapter() && m_undoCaptureEnabled) {
        QString macroName = text.isEmpty() ? QStringLiteral("Stroke") : text;
        m_document->undoAdapter()->beginMacro(kundo2_i18n(macroName.toUtf8().constData()));
        m_macroDepth++;
    }
}

void ReverieCore::endUndoMacro()
{
    if (m_document && m_document->undoAdapter() && m_macroDepth > 0) {
        m_document->undoAdapter()->endMacro();
        m_macroDepth--;
    }
}

bool ReverieCore::canUndo() const
{
    return m_undoStore && m_undoStore->canUndo();
}

bool ReverieCore::canRedo() const
{
    return m_undoStore && m_undoStore->canRedo();
}

void ReverieCore::undo()
{
    if (m_strokeBatchOpen) {
        touchStrokeCancel();
    }
    if (!m_undoStore || !m_document || !canUndo()) {
        return;
    }

    QVector<KisNode *> oldNodes;
    oldNodes.reserve(m_layers.size());
    for (const auto &e : m_layers) {
        oldNodes.append(e.node);
    }
    QVector<bool> oldVisibilities;
    oldVisibilities.reserve(m_layers.size());
    for (const auto &e : m_layers) {
        oldVisibilities.append(e.visible);
    }

    const KUndo2Command *cmd = m_undoStore->presentCommand();
    const bool isStructuralCmd = dynamic_cast<const KisImageLayerAddCommand *>(cmd) != nullptr ||
                                 dynamic_cast<const KisImageLayerRemoveCommand *>(cmd) != nullptr ||
                                 dynamic_cast<const KisImageLayerMoveCommand *>(cmd) != nullptr;

    m_undoStore->undo();
    ++m_redoCount;
    m_document->waitForDone();
    syncLayersFromImage();

    const int newW = m_document->width();
    const int newH = m_document->height();
    const bool sizeChanged = (newW != m_docWidth || newH != m_docHeight);
    if (sizeChanged) {
        m_docWidth = newW;
        m_docHeight = newH;
        m_renderBufW = -1;
        m_renderBufH = -1;
        m_dirtyRect = QRect(0, 0, m_docWidth, m_docHeight);
        m_bitmapInited = false;
        m_lastDirty = QRect();
    }

    bool structureChanged = sizeChanged || isStructuralCmd || oldNodes.size() != m_layers.size();
    if (!structureChanged) {
        for (int i = 0; i < oldNodes.size(); ++i) {
            if (oldNodes[i] != m_layers[i].node || oldVisibilities[i] != m_layers[i].visible) {
                structureChanged = true;
                break;
            }
        }
    }

    if (structureChanged) {
        recompositeProjection();
    }
    markDirty();
    m_snapshotPending = false;
}

void ReverieCore::redo()
{
    if (m_strokeBatchOpen) {
        touchStrokeCancel();
    }
    if (!m_undoStore || !m_document || !canRedo()) {
        return;
    }

    QVector<KisNode *> oldNodes;
    oldNodes.reserve(m_layers.size());
    for (const auto &e : m_layers) {
        oldNodes.append(e.node);
    }
    QVector<bool> oldVisibilities;
    oldVisibilities.reserve(m_layers.size());
    for (const auto &e : m_layers) {
        oldVisibilities.append(e.visible);
    }

    m_undoStore->redo();
    --m_redoCount;
    m_document->waitForDone();
    syncLayersFromImage();

    const int newW = m_document->width();
    const int newH = m_document->height();
    const bool sizeChanged = (newW != m_docWidth || newH != m_docHeight);
    if (sizeChanged) {
        m_docWidth = newW;
        m_docHeight = newH;
        m_renderBufW = -1;
        m_renderBufH = -1;
        m_dirtyRect = QRect(0, 0, m_docWidth, m_docHeight);
        m_bitmapInited = false;
        m_lastDirty = QRect();
    }

    bool structureChanged = sizeChanged || oldNodes.size() != m_layers.size();
    if (!structureChanged) {
        for (int i = 0; i < oldNodes.size(); ++i) {
            if (oldNodes[i] != m_layers[i].node || oldVisibilities[i] != m_layers[i].visible) {
                structureChanged = true;
                break;
            }
        }
    }

    if (structureChanged) {
        recompositeProjection();
    }
    markDirty();
    m_snapshotPending = false;
}

// ---------------------------------------------------------------------------
// Rendering
// ---------------------------------------------------------------------------
#if defined(__ARM_NEON) || defined(__ARM_NEON__)
#include <arm_neon.h>
#endif

// Airbrush hold-still tick: paint one dab at the current stroke position
// (last sample, or the start point when nothing has moved yet). Runs on the
// render thread while a stroke batch is open; reuses the tap-dot painting
// path (paintAt + inline job execution + conservative dirty rect). Returns
// false when no stroke is active so Kotlin can stop its timer.
bool ReverieCore::strokeAirbrushTick()
{
    if (!m_strokeBatchOpen || !m_document) {
        return false;
    }
    if (!m_strokePainter && !m_maskedStrokePainter && !m_strokeSamples.isEmpty()) {
        // The painter/op pipeline is created lazily by the first flush. A
        // pure hold-still stroke never flushes on its own, so force one:
        // the single-sample path paints the initial dot AND leaves
        // m_strokePainter/m_strokeOp/m_maskedStrokePainter ready for subsequent ticks.
        flushStrokeBatch();
    }
    if ((!m_strokePainter || !m_strokeOp) && !m_maskedStrokePainter) {
        return false;
    }
    const StrokeSample *lastSample = m_strokeSamples.isEmpty() ? nullptr : &m_strokeSamples.last();
    const QPointF p = lastSample ? lastSample->imgPos : m_strokeStartImg;
    const qreal pressure = lastSample
        ? qBound<qreal>(0.0, lastSample->pressure, 1.0)
        : 1.0;
    const qreal tiltX = lastSample ? lastSample->tiltX : m_lastTiltX;
    const qreal tiltY = lastSample ? lastSample->tiltY : m_lastTiltY;
    const qreal rotation = lastSample ? lastSample->rotation : m_lastRotation;
    KisPaintInformation info(p, pressure, tiltX, tiltY, rotation, 0.0, 1.0, m_strokeTimer.elapsed() / 1000.0, 0.0);
    if (m_randomSource) info.setRandomSource(m_randomSource);
    if (m_perStrokeRandomSource) info.setPerStrokeRandomSource(m_perStrokeRandomSource);

    QRect tickDirty;
    if (m_maskedStrokePainter) {
        m_maskedStrokePainter->paintAt(info);
        while (true) {
            QVector<KisRunnableStrokeJobData *> jobs;
            auto result = m_maskedStrokePainter->doAsynchronousUpdate(jobs);
            for (auto *j : jobs) {
                j->run();
                delete j;
            }
            if (jobs.isEmpty() || !result.second) {
                break;
            }
        }
        if (m_maskingBrushRenderer) {
            const QVector<QRect> exactDirty = m_maskedStrokePainter->takeDirtyRegion();
            for (const QRect &r : exactDirty) {
                m_maskingBrushRenderer->updateProjection(r);
                tickDirty = tickDirty.isNull() ? r : tickDirty.united(r);
            }
        }
    } else {
        m_strokeOp->paintAt(info, m_strokeDistance);
        while (true) {
            QVector<KisRunnableStrokeJobData *> jobs;
            auto result = m_strokeOp->doAsynchronousUpdate(jobs);
            for (auto *j : jobs) {
                j->run();
                delete j;
            }
            if (jobs.isEmpty() || !result.second) {
                break;
            }
        }
        const QVector<QRect> exactDirty = m_strokePainter->takeDirtyRegion();
        for (const QRect &r : exactDirty) {
            tickDirty = tickDirty.isNull() ? r : tickDirty.united(r);
        }
    }

    KisPaintLayer *pl = (m_currentLayer >= 0 && m_currentLayer < m_layers.size())
        ? dynamic_cast<KisPaintLayer *>(m_layers[m_currentLayer].node)
        : nullptr;
    KisPaintDeviceSP target =
        (pl && pl->hasTemporaryTarget()) ? pl->temporaryTarget() : currentPaintDevice();
    if (target) {
        if (tickDirty.isNull()) {
            const int tw = qMax(int(m_brushSize * 2.0), 32) + 16;
            tickDirty = QRect(int(p.x()) - tw, int(p.y()) - tw, 2 * tw, 2 * tw);
        }
        if (m_currentLayer >= 0 && m_currentLayer < m_layers.size() && m_layers[m_currentLayer].isStrokeLayer) {
            const int extra = m_layers[m_currentLayer].strokeSize + 4;
            tickDirty = tickDirty.adjusted(-extra, -extra, extra, extra).intersected(
                QRect(0, 0, m_docWidth, m_docHeight));
        }
        markRegionDirty(tickDirty);
    }
    return true;
}

// Fast ARM NEON SIMD blitter to convert Krita's native BGRA/ARGB32 projection bytes
// to Android Bitmap RGBA_8888 byte format with hardware vector acceleration.

