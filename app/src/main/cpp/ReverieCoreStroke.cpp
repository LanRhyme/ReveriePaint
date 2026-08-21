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
    // The stroke paints straight onto the layer device with per-dab opacity
    // (Krita-native); no temporary buffer is used.
    m_strokeStartImg = QPointF(x, y);
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
    endStrokeBatch();
    m_strokeBatchOpen = false;
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
        flushStrokeBatch();
    }
}

// Centripetal Catmull-Rom spline point: evaluates the curve through
// P0,P1,P2,P3 at u in [0,1] (u=0 at P1, u=1 at P2). Centripetal
// parameterisation prevents the overshoot "hooks" that uniform Catmull-Rom
// produces on sharply curving strokes.

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
    const bool erasing = (m_toolMode == ToolEraser);

    // Krita-native: every stroke paints DIRECTLY onto the current layer
    // device. The projection then recomposites in real time, so brush
    // opacity/flow, layer opacity and blend mode are all applied live
    // (matching Krita) instead of only at pen-up via a temporary buffer.
    KisPaintDeviceSP target = currentPaintDevice();
    if (!target) {
        m_strokeSamples.clear();
        return;
    }

    // Krita-style: reuse one KisPainter for the whole stroke.
    if (!m_strokePainter || m_strokeDevice != (void *)target.data()) {
        endStrokeBatch();
        m_strokeDevice = (void *)target.data();
        // Deferred Krita undo: start the stroke transaction here (after the
        // device exists) on the first real flush. Taps and no-paint strokes
        // never reach this point, so they never create an undo command.
        if (m_snapshotPending && !m_strokeTxnActive && m_undoCaptureEnabled) {
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
        // Eraser presets erase via their CompositeOp parameter (a)_Eraser_*
        // are paintbrush presets with CompositeOp=erase). Apply the preset's
        // effective composite op to the painter so the dab bitBlt actually
        // erases instead of painting over.
        if (m_brushPreset && m_brushPreset->settings()) {
            m_strokePainter->setCompositeOpId(
                m_brushPreset->settings()->effectivePaintOpCompositeOp());
        }
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
                if (!target->interstrokeData() || !factory->isCompatible(target->interstrokeData().data())) {
                    KUndo2Command *cmd = target->createChangeInterstrokeDataCommand(toQShared(factory->create(target)));
                    if (cmd) {
                        cmd->redo();
                        delete cmd;
                    }
                }
            }
            m_strokePainter->setRunnableStrokeJobsInterface(&m_fakeExecutor);
            const int layerIndex = qBound(0, m_currentLayer, m_layers.size() - 1);
            // Create the op through the registry so the preset's own paintop
            // engine is used (paintbrush -> KisBrushOp, experimentbrush ->
            // KisExperimentPaintOp, roundmarker -> KisRoundMarkerOp, ...).
            // Hardcoding KisBrushOp made every preset render as a plain dab.
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
    if (m_brushPreset && m_brushPreset->settings()) {
        m_strokePainter->setCompositeOpId(
            m_brushPreset->settings()->effectivePaintOpCompositeOp());
    }
    if (m_selection) {
        m_strokePainter->setSelection(m_selection);
    } else {
        m_strokePainter->setSelection(KisSelectionSP());
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
    // Per-dab opacity (Krita behaviour): the brush op reads the preset's
    // opacity/flow internally; the painter-level opacity covers the fallback
    // dab loop and the eraser.
    painter.setOpacityF(qBound<qreal>(0.0, m_strokeOpacity, 1.0));
    // Composite op: the brush preset's own effective op wins (eraser presets
    // are paintbrush presets carrying CompositeOp=erase), the eraser tool
    // always erases, everything else uses normal. Previously this line
    // unconditionally overwrote the preset's composite op back to 'normal',
    // which is why eraser presets did not erase.
    QString compositeOp;
    if (m_brushPreset && m_brushPreset->settings()) {
        compositeOp = m_brushPreset->settings()->effectivePaintOpCompositeOp();
    }
    if (erasing) {
        compositeOp = QStringLiteral("erase");
    }
    if (compositeOp.isEmpty()) {
        compositeOp = QStringLiteral("normal");
    }
    painter.setCompositeOpId(compositeOp);

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
            // 15% brush-size floor: a light pressure must never shrink the
            // dab below a visible dot (the old floor of 1px made light
            // strokes disappear into dotted artifacts)
            qreal w = m_brushSize * pressure;
            w = qMax(w, qMax<qreal>(1.0, m_brushSize * 0.15));
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
        return;
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
        const bool engineBypassesSelection =
            opId != QLatin1String("paintbrush") && opId != QLatin1String("duplicate");
        QByteArray selClipBefore;
        QRect selClipBox;
        if (m_selection && engineBypassesSelection) {
            for (const StrokeSample &sm : m_strokeSamples) {
                const int w = int(m_brushSize) + 2;
                const QRect r(int(sm.imgPos.x()) - w, int(sm.imgPos.y()) - w,
                              2 * w, 2 * w);
                selClipBox = selClipBox.isNull() ? r : selClipBox.united(r);
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
        // Exact dirty propagation: the op's own rendering accumulates dirty
        // rects inside the painter - dab bitBlt for KisBrushOp, and the
        // fillPainterPath bitBlt (whole-path rects) for the special engines
        // like experimentbrush. Using these instead of the brushSize
        // neighbourhood fixes special brushes whose shape covers the whole
        // stroke path (they previously only showed after undo/new-layer,
        // which triggered a full recomposite).
        const QVector<QRect> exactDirty = painter.takeDirtyRegion();
        for (const QRect &r : exactDirty) {
            strokeDirty = strokeDirty.isNull() ? r : strokeDirty.united(r);
        }
        // Conservative fallback: the samples' neighbourhood, only for
        // engines that write the device directly (roundmarker's
        // KisMarkerPainter etc.) and never accumulate dirty rects in the
        // painter - for paintbrush/duplicate the exactDirty above is the
        // true changed area, and adding a 2*brushSize margin around every
        // sample inflates the projection recomposite region for big brushes.
        if (engineBypassesSelection || exactDirty.isEmpty()) {
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
        prevW = qMax(prevW, qMax<qreal>(1.0, m_brushSize * 0.15));
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
            segW = qMax(segW, qMax<qreal>(1.0, m_brushSize * 0.15));
            const qreal dabSpacing = qMax<qreal>(1.5, segW * 0.2);
            const int n = qMax(1, int(qCeil(segLen / dabSpacing)));
            for (int j = 1; j <= n; ++j) {
                const qreal t = qreal(j) / n;
                const QPointF p = centripetalCatmullRom(p0, p1, p2, p3, t);
                const qreal pMid = prevP + (curP - prevP) * t;
                qreal width = m_brushSize * qBound<qreal>(0.0, pMid, 1.0);
                width = qMax(width, qMax<qreal>(1.0, m_brushSize * 0.15));
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
        markRegionDirty(strokeDirty);
        bumpLayerThumbGen(m_layers[m_currentLayer].node);
    }
}

// Place the finished stroke from the temporary buffer onto the current
// layer, applying the stroke opacity exactly once. Eraser uses the erase
// composite op so the stroke genuinely clears layer pixels.
void ReverieCore::commitStrokeToLayer()
{
    if (!m_strokeBuffer || !m_document) {
        return;
    }
    KisPaintDeviceSP device = currentPaintDevice();
    if (!device) {
        m_strokeBuffer->clear();
        return;
    }
    const QRect ext = m_strokeBuffer->exactBounds();
    if (ext.isEmpty()) {
        m_strokeBuffer->clear();
        return;
    }
    qreal opacity = qBound<qreal>(0.0, m_strokeOpacity, 1.0);
    // Smudge: a translucent smearing pass (MVP approximation of the real
    // smudge brush which pushes color along the stroke path).
    if (m_toolMode == ToolSmudge) {
        opacity = qMin<qreal>(opacity, 0.12);
    }
    KisPainter painter(device);
    painter.setOpacityF(opacity);
    painter.setCompositeOpId(QStringLiteral("normal"));
    // Transparency lock: preserve the existing alpha by masking the alpha
    // channel out of the write (Krita's KisPaintLayer::setAlphaLocked uses
    // the same channelFlags mechanism)
    const LayerEntry &cur = m_layers[qBound(0, m_currentLayer, m_layers.size() - 1)];
    if (cur.alphaLocked) {
        painter.setChannelFlags(device->colorSpace()->channelFlags(true, false));
    }
    // Active selection: constrain the stroke to the selection mask
    if (m_selection) {
        painter.setSelection(m_selection);
    }
    painter.bitBlt(ext.x(), ext.y(), m_strokeBuffer,
                   ext.x(), ext.y(), ext.width(), ext.height());
    // Mark the region dirty on the layer device and recomposite
    device->setDirty(ext);
    recompositeProjection();
    markRegionDirty(ext);
    bumpLayerThumbGen(m_layers[m_currentLayer].node);
    m_strokeBuffer->clear();
}

void ReverieCore::endStrokeBatch()
{
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

// Fast ARM NEON SIMD blitter to convert Krita's native BGRA/ARGB32 projection bytes
// to Android Bitmap RGBA_8888 byte format with hardware vector acceleration.

