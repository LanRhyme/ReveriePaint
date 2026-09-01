/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/* ============================================================
 * ReverieCoreStroke.cpp - Stroke batching: touchStart/Move/End/Cancel, undo command push, stroke blending
 * (part of the ReverieCore module split; shared helpers live in
 * ReverieCoreInternal.h, public API in ReverieCore.h)
 * ============================================================ */
#include "ReverieCoreInternal.h"

void ReverieCore::touchStrokeStart(qreal x, qreal y, qreal pressure)
{
    if (!m_document) {
        return;
    }
    // Defer the undo snapshot to the first real flush: reading every layer
    // here costs a full-document read per touch-down, which is felt as lag
    // when starting strokes. Nothing is painted at down time anyway.
    m_snapshotPending = true;
    m_drawing = true;
    m_strokeBatchOpen = true;
    m_lastPressure = pressure;
    m_strokeColor = m_brushColor;
    m_strokeOpacity = m_brushOpacity;
    m_idleKickPainted = false;
    // The stroke paints straight onto the layer device with per-dab opacity
    // (Krita-native); no temporary buffer is used.
    m_strokeStartImg = QPointF(x, y);
    m_accumulatedStrokeBounds = QRectF(x, y, 1.0, 1.0);
    m_strokeSamples.clear();
    m_strokeCarryCount = 0;
    m_strokeHadMove = false;
    // The stroke starts at the finger-down position: append it as the first
    // sample so the down -> first-move segment is drawn. Otherwise the first
    // flush sees one sample and paints a dot, and the stroke start is cut off
    // (Android can move several px before the first move event arrives).
    StrokeSample s;
    s.imgPos = m_strokeStartImg;
    s.pressure = pressure;
    m_strokeSamples.append(s);
}

bool ReverieCore::touchStrokeMove(qreal x, qreal y, qreal pressure)
{
    if (!m_drawing || !m_strokeBatchOpen) {
        return false;
    }
    const QPointF imgPos(x, y);
    m_accumulatedStrokeBounds = m_accumulatedStrokeBounds.united(QRectF(x, y, 1.0, 1.0));
    const QPointF lastPos = m_strokeSamples.isEmpty()
            ? m_strokeStartImg
            : m_strokeSamples.last().imgPos;
    if (imgPos != lastPos) {
        return appendStrokeSample(imgPos, pressure);
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
        pl->setTemporaryTarget(nullptr);
        if (!ext.isEmpty()) {
            if (m_document && m_undoCaptureEnabled) {
                delete m_strokeTxn;
                m_strokeTxn = new KisTransaction(kundo2_i18n("Stroke"), pl->paintDevice());
                m_strokeTxnActive = true;
            }
            KisPainter gc(pl->paintDevice());
            gc.setOpacityF(qBound<qreal>(0.0, m_strokeOpacity, 1.0));
            QString compOp = QStringLiteral("normal");
            if (m_brushPreset && m_brushPreset->settings()) {
                compOp = m_brushPreset->settings()->effectivePaintOpCompositeOp();
            }
            if (m_toolMode == ToolEraser) {
                compOp = QStringLiteral("erase");
            }
            gc.setCompositeOpId(compOp);
            if (m_selection) {
                gc.setSelection(m_selection);
            }
            gc.bitBlt(ext.topLeft(), tempTarget, ext);
            gc.end();
            pl->paintDevice()->setDirty(ext);
            markRegionDirty(ext);
            bumpLayerThumbGen(pl);
        }
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
    // mode the transaction is dropped instead (stroke content stays).
    if (m_strokeTxnActive && m_document) {
        if (m_undoCaptureEnabled) {
            m_strokeTxn->commit(m_document->undoAdapter());
        } else {
            delete m_strokeTxn;
        }
        m_strokeTxn = nullptr;
        m_strokeTxnActive = false;
        m_redoCount = 0;
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
    m_snapshotPending = false;
    m_strokeSamples.clear();
    m_strokeCarryCount = 0;
    m_idleKickPainted = false;
    endStrokeBatch();
    m_strokeBatchOpen = false;
    m_drawing = false;
}

bool ReverieCore::appendStrokeSample(const QPointF &imgPos, qreal pressure)
{
    QString opId;
    if (m_brushPreset) {
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
    const qreal spacing = isPathEngine ? 1.5 : 0.75;
    if (!m_strokeSamples.isEmpty()) {
        const QPointF last = m_strokeSamples.last().imgPos;
        const qreal dist = QLineF(last, imgPos).length();
        if (dist < spacing) {
            m_strokeSamples.last().pressure = pressure;
            return false;
        }
    }
    m_strokeHadMove = true;
    StrokeSample s;
    s.imgPos = imgPos;
    s.pressure = pressure;
    m_strokeSamples.append(s);
    // Time-throttled flushing: one flush per touch-move saturates the render
    // thread with large brushes (big dabs + big dirty regions). Batch the
    // samples for ~8ms and flush once per batch; touchStrokeEnd always
    // flushes the remainder, so nothing is lost on pen-up.
    const qint64 now = QDateTime::currentMSecsSinceEpoch();
    if (now - m_lastFlushMs >= 8 || m_strokeSamples.size() >= 64) {
        m_lastFlushMs = now;
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
            m_brushPreset->name().startsWith(QLatin1String("a)_")) ||
            m_brushPreset->name().contains(QLatin1String("Eraser"), Qt::CaseInsensitive)
        );
    }
    const bool erasing = (m_toolMode == ToolEraser) || isEraserPreset;

    const QString effectiveOp = erasing ? QStringLiteral("erase") :
        (m_brushPreset && m_brushPreset->settings() && m_brushPreset->settings()->getString("CompositeOp") != QLatin1String("erase")
            ? m_brushPreset->settings()->getString("CompositeOp")
            : QStringLiteral("normal"));

    if (m_brushPreset && m_brushPreset->settings()) {
        m_brushPreset->settings()->setEraserMode(erasing);
    }

    // Krita indirect painting check:
    // Non-incremental brushes (like experimentbrush / Shape_fill, sketch, curve)
    // must paint onto an indirect temporary target to avoid COMPOSITE_COPY
    // erasing/mosaic-clipping the existing layer pixels behind the stroke!
    // NOTE: Erasing MUST always paint directly on the layer's device, because erasing
    // removes pixels from the underlying layer directly. Painting "erase" onto an empty
    // indirect target does nothing.
    const bool needsIndirect = !erasing && m_brushPreset && m_brushPreset->settings() &&
        !m_brushPreset->settings()->paintIncremental();

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

    RPC_LOG("RPC stroke mode=%d isEraser=%d erasing=%d compOp=%s color=(%d,%d,%d,%d) opacity=%.2f preset=%s",
            int(m_toolMode), isEraserPreset, erasing,
            painterCompOp.toUtf8().constData(),
            qColor.red(), qColor.green(), qColor.blue(), qColor.alpha(),
            m_strokeOpacity,
            m_brushPreset ? m_brushPreset->name().toUtf8().constData() : "null");

    // Krita-style: reuse one KisPainter for the whole stroke.
    if (!m_strokePainter || m_strokeDevice != (void *)target.data()) {
        endStrokeBatch();
        m_strokeDevice = (void *)target.data();
        // Deferred Krita undo: for direct painting, start transaction on the layer.
        if (!needsIndirect && m_snapshotPending && !m_strokeTxnActive && m_undoCaptureEnabled) {
            delete m_strokeTxn;
            KisInterstrokeDataFactory *interstrokeDataFactory = nullptr;
            if (m_brushPreset) {
                interstrokeDataFactory = KisPaintOpRegistry::instance()->createInterstrokeDataFactory(m_brushPreset);
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
        m_strokePainter = new KisPainter(target);
        m_strokePainter->setFillStyle(KisPainter::FillStyleForegroundColor);
        m_strokePainter->setStrokeStyle(KisPainter::StrokeStyleBrush);
        m_strokePainter->setCompositeOpId(painterCompOp);
        m_strokePainter->setOpacityF(needsIndirect ? 1.0 : qBound<qreal>(0.0, m_strokeOpacity, 1.0));
        m_strokePainter->setPaintColor(koColor);
        m_strokePainter->setBackgroundColor(koBgColor);

        // Constrain the whole stroke to the active selection (if any)
        if (m_selection) {
            m_strokePainter->setSelection(m_selection);
        }
        // Real Krita brush engine: construct the brush op once per stroke
        // and drive its async dab pipeline synchronously (the fake executor
        // runs the rendering jobs inline, exactly like Krita's own tests).
        if (m_brushPreset && m_strokePainter) {
            std::unique_ptr<KisInterstrokeDataFactory> factory(
                KisPaintOpRegistry::instance()->createInterstrokeDataFactory(m_brushPreset));
            if (factory) {
                KUndo2Command *cmd = target->createChangeInterstrokeDataCommand(toQShared(factory->create(target)));
                if (cmd) {
                    cmd->redo();
                    delete cmd;
                }
            }
            m_strokePainter->setRunnableStrokeJobsInterface(&m_fakeExecutor);
            const int layerIndex = qBound(0, m_currentLayer, m_layers.size() - 1);
            // Create the op through the registry so the preset's own paintop
            // engine is used (paintbrush -> KisBrushOp, experimentbrush ->
            // KisExperimentPaintOp, roundmarker -> KisRoundMarkerOp, ...).
            m_strokeOp = KisPaintOpRegistry::instance()->paintOp(
                m_brushPreset, m_strokePainter,
                KisNodeSP(m_layers[layerIndex].node), image);
            if (!m_strokeOp) {
                // Fall back to the classic brush op if the engine is missing
                m_strokeOp = new KisBrushOp(m_brushPreset->settings(), m_strokePainter,
                                            KisNodeSP(m_layers[layerIndex].node), image);
            }
            const QPointF start =
                m_strokeSamples.isEmpty() ? m_strokeStartImg : m_strokeSamples.first().imgPos;
            delete m_strokeDistance;
            m_strokeDistance = new KisDistanceInformation(start, 0.0);
        }
    }
    // Re-sync the composite op on every flush so mid-stroke parameter
    // changes (blend-mode dropdown, eraser preset switch) take effect.
    m_strokePainter->setCompositeOpId(painterCompOp);
    m_strokePainter->setOpacityF(needsIndirect ? 1.0 : qBound<qreal>(0.0, m_strokeOpacity, 1.0));
    m_strokePainter->setPaintColor(koColor);
    m_strokePainter->setBackgroundColor(koBgColor);
    if (m_selection) {
        m_strokePainter->setSelection(m_selection);
    } else {
        m_strokePainter->setSelection(KisSelectionSP());
    }
    KisPainter &painter = *m_strokePainter;

    // Genuine tap only (no movement): paint a round dot. KisPainter::drawLine
    // with identical start/end returns immediately, so use paintEllipse
    // (fills with the foreground color) sized to the brush diameter. A
    // trailing single sample of a real stroke is NOT a dot.
    if (m_strokeSamples.size() == 1 && !m_strokeHadMove) {
        const QPointF p = m_strokeSamples.first().imgPos;
        const qreal pressure =
            qBound<qreal>(0.0, m_strokeSamples.first().pressure, 1.0);
        if (m_brushPreset && m_strokeOp) {
            // Krita dab for a genuine tap (paintAt = single dab at pos)
            m_strokeOp->paintAt(KisPaintInformation(p, pressure), m_strokeDistance);
            QVector<KisRunnableStrokeJobData *> jobs;
            m_strokeOp->doAsynchronousUpdate(jobs);
            for (auto *j : jobs) {
                j->run();
                delete j;
            }
        } else {
            // Pressure floor is only a safety net against the dab fully
            // disappearing (Krita lets the Size curve decide the minimum
            // dab); the old 15% floor badly flattened light-pressure strokes.
            qreal w = m_brushSize * pressure;
            w = qMax(w, qMax<qreal>(1.0, m_brushSize * 0.02));
            painter.paintEllipse(QRectF(p.x() - w / 2.0, p.y() - w / 2.0, w, w));
        }
        // Propagate the tap dot to the projection immediately
        const int tw = int(m_brushSize) + 2;
        const QRect tr(int(p.x()) - tw, int(p.y()) - tw, 2 * tw, 2 * tw);
        target->setDirty(tr);
        markRegionDirty(tr);
        bumpLayerThumbGen(m_layers[m_currentLayer].node);
        m_strokeSamples.clear();
        m_strokeCarryCount = 0;
        return true;
    }

    QRect strokeDirty;
    RPC_LOG("RPC flush samples=%d preset=%d op=%d hadMove=%d brushSize=%.1f",
            m_strokeSamples.size(), m_brushPreset != nullptr, m_strokeOp != nullptr,
            m_strokeHadMove, double(m_brushSize));
    if (m_brushPreset && m_strokeOp) {
        // ---- Real Krita brush engine ----
        // Continuous paintLine through the samples (the op interpolates dabs
        // along the path itself, with the real spacing/softness/flow of the
        // preset). The async dab pipeline is driven synchronously: render
        // jobs ran inline via the fake executor at enqueue time, and these
        // update jobs bitBlt the finished dabs onto the target device.
        //
        // Selection constraint: engines like roundmarker/spray/sketch write
        // pixels DIRECTLY to the layer device (KisMarkerPainter and friends),
        // bypassing KisPainter::bitBlt/bltFixed, so painter->setSelection is
        // ignored by them (paintbrush/duplicate go through bltFixed and are
        // constrained natively). For those engines we snapshot the affected
        // box before painting and restore the pixels outside the selection
        // afterwards - the same net effect as a selection-clipped blit.
        const QString opId = m_brushPreset->paintOp().id();
        const bool isPathEngine = (opId == QLatin1String("experimentbrush") ||
                                   opId == QLatin1String("curvebrush") ||
                                   opId == QLatin1String("sketchbrush") ||
                                   opId == QLatin1String("gridbrush") ||
                                   opId == QLatin1String("particlebrush"));
        const bool engineBypassesSelection =
            opId != QLatin1String("paintbrush") && opId != QLatin1String("duplicate");
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
        // Segments up to the carry index were painted by the previous flush:
        // start at the first NEW segment so the retained joint is not dabbed a
        // second time (which doubled opacity/erase there every flush boundary).
        const int firstNewSegment = qBound(1, m_strokeCarryCount, qMax(1, m_strokeSamples.size()));
        for (int i = firstNewSegment; i < m_strokeSamples.size(); ++i) {
            const StrokeSample &a = m_strokeSamples[i - 1];
            const StrokeSample &b = m_strokeSamples[i];
            m_strokeOp->paintLine(KisPaintInformation(a.imgPos, a.pressure),
                                  KisPaintInformation(b.imgPos, b.pressure),
                                  m_strokeDistance);
        }
        QVector<KisRunnableStrokeJobData *> jobs;
        m_strokeOp->doAsynchronousUpdate(jobs);
        RPC_LOG("RPC update jobs=%d first=(%.0f,%.0f) last=(%.0f,%.0f)",
                jobs.size(),
                double(m_strokeSamples.first().imgPos.x()),
                double(m_strokeSamples.first().imgPos.y()),
                double(m_strokeSamples.last().imgPos.x()),
                double(m_strokeSamples.last().imgPos.y()));
        for (auto *j : jobs) {
            j->run();
            delete j;
        }
        // Restore the pixels outside the selection for engines that bypass
        // KisPainter's selection clipping (see above)
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
        // Exact dirty propagation: path engines like experimentbrush (Shape_fill)
        // continually fill a polygon spanning across the entire stroke history.
        // We MUST include the whole accumulated stroke bounding box so the live
        // projection updates every pixel of the filled shape on screen.
        if (isPathEngine) {
            const int margin = int(m_brushSize) + 8;
            const QRect pathDirty = m_accumulatedStrokeBounds.toAlignedRect().adjusted(-margin, -margin, margin, margin);
            strokeDirty = strokeDirty.isNull() ? pathDirty : strokeDirty.united(pathDirty);
        }
        const QVector<QRect> exactDirty = painter.takeDirtyRegion();
        for (const QRect &r : exactDirty) {
            strokeDirty = strokeDirty.isNull() ? r : strokeDirty.united(r);
        }
        // Conservative fallback: the samples' neighbourhood for direct-write engines
        if (!isPathEngine && (engineBypassesSelection || exactDirty.isEmpty())) {
            for (const StrokeSample &sm : m_strokeSamples) {
                const int w = int(m_brushSize) + 2;
                const QRect r(int(sm.imgPos.x()) - w, int(sm.imgPos.y()) - w,
                              2 * w, 2 * w);
                strokeDirty = strokeDirty.isNull() ? r : strokeDirty.united(r);
            }
        }
    } else {
        // ---- Fallback: classic round-dab loop (no preset loaded) ----
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

    // All strokes now paint straight onto the layer: propagate the dirty
    // region so Krita's projection recomposites it immediately.
    if (!strokeDirty.isNull()) {
        target->setDirty(strokeDirty);
        if (pl && pl->hasTemporaryTarget()) {
            pl->setDirty(strokeDirty);
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
    delete m_strokePainter;
    m_strokePainter = nullptr;
    m_strokeDevice = nullptr;
    // The brush op pins the painter's device; drop it first, then the
    // distance accumulator.
    m_strokeOp = nullptr;
    delete m_strokeDistance;
    m_strokeDistance = nullptr;
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
    // (hundreds of replay commands would otherwise eat tile-snapshot memory)
    if (!m_undoCaptureEnabled) {
        delete cmd;
        return;
    }
    // KisLegacyUndoAdapter::addCommand routes into our surrogate store
    // (installed via KisImage::setUndoStore); KUndo2Stack::push executes
    // the command's redo() (the change is already applied by the caller,
    // so redo() is a no-op for most commands) and clears redo state.
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

bool ReverieCore::canUndo() const
{
    return m_undoStore && m_undoStore->presentCommand() != nullptr;
}

void ReverieCore::undo()
{
    if (!m_undoStore || !m_document || !canUndo()) {
        return;
    }
    m_undoStore->undo();
    ++m_redoCount;
    syncLayersFromImage();
    for (int i = 0; i < m_layers.size(); ++i) {
        if (KisLayer *l = dynamic_cast<KisLayer *>(m_layers[i].node)) {
            if (KisPaintDeviceSP dev = l->paintDevice()) {
                dev->setDirty();
            }
        }
        bumpLayerThumbGen(m_layers[i].node);
    }
    recompositeProjection();
    markDirty();
    m_snapshotPending = false;
}

void ReverieCore::redo()
{
    if (!m_undoStore || !m_document || !canRedo()) {
        return;
    }
    m_undoStore->redo();
    --m_redoCount;
    syncLayersFromImage();
    for (int i = 0; i < m_layers.size(); ++i) {
        if (KisLayer *l = dynamic_cast<KisLayer *>(m_layers[i].node)) {
            if (KisPaintDeviceSP dev = l->paintDevice()) {
                dev->setDirty();
            }
        }
        bumpLayerThumbGen(m_layers[i].node);
    }
    recompositeProjection();
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
    if (!m_strokePainter && !m_strokeSamples.isEmpty()) {
        // The painter/op pipeline is created lazily by the first flush. A
        // pure hold-still stroke never flushes on its own, so force one:
        // the single-sample path paints the initial dot AND leaves
        // m_strokePainter/m_strokeOp ready for subsequent ticks.
        flushStrokeBatch();
    }
    if (!m_strokePainter || !m_strokeOp) {
        return false;
    }
    const QPointF p = m_strokeSamples.isEmpty() ? m_strokeStartImg : m_strokeSamples.last().imgPos;
    const qreal pressure = m_strokeSamples.isEmpty()
        ? 1.0
        : qBound<qreal>(0.0, m_strokeSamples.last().pressure, 1.0);
    m_strokeOp->paintAt(KisPaintInformation(p, pressure), m_strokeDistance);
    QVector<KisRunnableStrokeJobData *> jobs;
    m_strokeOp->doAsynchronousUpdate(jobs);
    for (auto *j : jobs) {
        j->run();
        delete j;
    }
    KisPaintLayer *pl = (m_currentLayer >= 0 && m_currentLayer < m_layers.size())
        ? dynamic_cast<KisPaintLayer *>(m_layers[m_currentLayer].node)
        : nullptr;
    KisPaintDeviceSP target =
        (pl && pl->hasTemporaryTarget()) ? pl->temporaryTarget() : currentPaintDevice();
    if (target) {
        const int tw = int(m_brushSize) + 2;
        const QRect tr(int(p.x()) - tw, int(p.y()) - tw, 2 * tw, 2 * tw);
        target->setDirty(tr);
        markRegionDirty(tr);
    }
    return true;
}

// Fast ARM NEON SIMD blitter to convert Krita's native BGRA/ARGB32 projection bytes
// to Android Bitmap RGBA_8888 byte format with hardware vector acceleration.

