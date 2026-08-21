/* ============================================================
 * ReverieCoreRender.cpp - Rendering: composite projection to bitmap, flood fill, color pick, shapes
 * (part of the ReverieCore module split; shared helpers live in
 * ReverieCoreInternal.h, public API in ReverieCore.h)
 * ============================================================ */
#include "ReverieCoreInternal.h"

bool ReverieCore::renderToBuffer(quint8 *buffer, int w, int h, bool forceFull)
{
    KisImageSP image = m_document;
    if (!image || !buffer || w <= 0 || h <= 0) {
        return false;
    }
    // Non-blocking: during fast strokes the async projection recomposite is
    // often still running; blocking here (waitForDone) stalled the render
    // thread with all queued input ops behind it - felt as lag while
    // scribbling. Skip the frame instead; the caller re-schedules while the
    // dirty rect is still pending (see renderPendingDirty).
    if (!image->isIdle()) {
        return false;
    }

    const int iw = image->width();
    const int ih = image->height();

    // Solo mode is a pure render-time filter: composite only the keep layers
    // (soloed + ancestors + descendants + background) into a fresh device and
    // read from that instead of the full projection. No layer state is ever
    // modified, so closing solo restores the document exactly and solo can
    // never corrupt the canvas render or the undo stack.
    KisPaintDeviceSP proj;
    if (m_soloedNode) {
        proj = compositeSoloProjection();
    } else if (!m_nodeFilters.isEmpty()) {
        proj = new KisPaintDevice(image->colorSpace());
        const QRect full(0, 0, iw, ih);
        proj->fill(full, KoColor(Qt::transparent, image->colorSpace()));
        compositeRange(proj, 0, m_layers.size(), full);
    } else {
        proj = image->projection();
    }
    if (!proj) {
        return false;
    }

    // The Kotlin side renders into one persistent buffer and reallocates it
    // only on document/viewport size changes. A reallocation (forceFull, set
    // whenever a fresh buffer is handed in) or a different buffer size
    // invalidates the incremental state kept for the previous buffer: force
    // a full-frame rewrite and re-init the dirty tracking.
    const bool bufReset = forceFull || m_renderBufW != w || m_renderBufH != h;
    if (bufReset) {
        m_renderBufW = w;
        m_renderBufH = h;
        m_bitmapInited = false;
        m_dirtyRect = QRect(0, 0, iw, ih);
    }

    // 1:1 Native Resolution Rendering Path (Direct Krita GPU Engine Alignment)
    if (w == iw && h == ih) {
        // Solo mode or dynamic filter mode always re-composites the full frame:
        if (!m_nodeFilters.isEmpty() || m_soloedNode || !m_bitmapInited || m_dirtyRect == QRect(0, 0, iw, ih)) {
            // Full frame update: direct in-place read and SIMD conversion
            proj->readBytes(buffer, 0, 0, iw, ih);
            blitBgraToRgbaFast(buffer, iw * 4, buffer, w * 4, iw, ih);
            m_bitmapInited = true;
            m_lastWrittenRect = QRect(0, 0, w, h);
            m_dirtyRect = QRect();
            return true;
        } else if (m_dirtyRect.isNull()) {
            // Nothing painted since the last render and the buffer already
            // holds a complete frame: skip the write and report a no-op so
            // the caller can drop the (identical) display flip instead of
            // re-drawing the canvas for unchanged pixels.
            m_dirtyRect = QRect();
            m_lastWrittenRect = QRect();
            return false;
        } else {
            // Sub-region dirty update with exact pixel boundaries (0 rounding seams/misalignment)
            const QRect r = m_dirtyRect.intersected(QRect(0, 0, iw, ih));
            if (!r.isEmpty()) {
                const size_t req = size_t(r.width()) * r.height() * 4;
                if (size_t(m_subRegionBuffer.size()) < req) {
                    m_subRegionBuffer.resize(req);
                }
                proj->readBytes(reinterpret_cast<quint8 *>(m_subRegionBuffer.data()), r.x(), r.y(), r.width(), r.height());
                quint8 *dst = buffer + size_t(r.y()) * (w * 4) + size_t(r.x()) * 4;
                blitBgraToRgbaFast(reinterpret_cast<const quint8 *>(m_subRegionBuffer.constData()), r.width() * 4,
                                   dst, w * 4, r.width(), r.height());
                m_lastWrittenRect = r;
            } else {
                m_lastWrittenRect = QRect();
            }
        }
        m_dirtyRect = QRect();
        return true;
    }

    // Scaled viewport fallback path (if buffer size != document size). The
    // display buffer persists across frames exactly like the 1:1 path, so the
    // scaled dirty region is blitted straight into it — the old full-frame
    // staging copy out of m_displayImage (w*h*4 bytes per render, plus the
    // same again resident for a 4096px doc) is gone.
    const qreal sx = qreal(w) / iw;
    const qreal sy = qreal(h) / ih;

    if (m_dirtyRect.isEmpty()) {
        // Nothing changed since the last render. bufReset above always sets a
        // full dirty rect, so an empty rect here means the buffer is complete:
        // report a no-op so the caller skips the display flip.
        m_lastWrittenRect = QRect();
        return false;
    }

    const QRect r = m_dirtyRect.intersected(QRect(0, 0, iw, ih));
    if (!r.isEmpty()) {
        // Expand the read by 1px on every side (clamped to the image): smooth
        // scaling a bare dirty rect gives its border pixels no neighbours, so
        // every dirty blit produced wrongly-weighted edge pixels that showed
        // up as seams/ghosting between successive incremental updates.
        const QRect rs = r.adjusted(-1, -1, 1, 1).intersected(QRect(0, 0, iw, ih));
        const size_t req = size_t(rs.width()) * rs.height() * 4;
        if (size_t(m_subRegionBuffer.size()) < req) {
            m_subRegionBuffer.resize(req);
        }
        proj->readBytes(reinterpret_cast<quint8 *>(m_subRegionBuffer.data()), rs.x(), rs.y(), rs.width(), rs.height());
        QImage subBgra(rs.width(), rs.height(), QImage::Format_RGBA8888);
        blitBgraToRgbaFast(reinterpret_cast<const quint8 *>(m_subRegionBuffer.constData()), rs.width() * 4,
                           subBgra.bits(), rs.width() * 4, rs.width(), rs.height());

        // Map BOTH edges of a rect through the same round(edge*scale) rule so
        // consecutive dirty blits always agree on where each pixel boundary
        // lands. The old code rounded x and width independently, which let
        // neighbouring blits drift by 1px and leave stale rows/columns between
        // them.
        const auto mapX = [&](int v) { return qRound(v * sx); };
        const auto mapY = [&](int v) { return qRound(v * sy); };
        const int vw = mapX(rs.x() + rs.width()) - mapX(rs.x());
        const int vh = mapY(rs.y() + rs.height()) - mapY(rs.y());
        const QImage scaled = (subBgra.width() != vw || subBgra.height() != vh)
                ? subBgra.scaled(vw, vh, Qt::IgnoreAspectRatio, Qt::SmoothTransformation)
                : subBgra;
        if (!scaled.isNull()) {
            // The 1px pad's scaled footprint inside the scaled image
            const int padL = mapX(r.x()) - mapX(rs.x());
            const int padT = mapY(r.y()) - mapY(rs.y());
            const int padR = mapX(rs.x() + rs.width()) - mapX(r.x() + r.width());
            const int padB = mapY(rs.y() + rs.height()) - mapY(r.y() + r.height());
            const int sx0 = qBound(0, padL, scaled.width());
            const int sy0 = qBound(0, padT, scaled.height());
            const int sw = qMax(0, scaled.width() - sx0 - qMax(0, padR));
            const int sh = qMax(0, scaled.height() - sy0 - qMax(0, padB));
            const QRect vp(mapX(r.x()), mapY(r.y()), sw, sh);
            const QRect clip = vp.intersected(QRect(0, 0, w, h));
            if (!clip.isEmpty()) {
                for (int y = clip.top(); y <= clip.bottom(); ++y) {
                    memcpy(buffer + size_t(y) * (w * 4) + clip.left() * 4,
                           scaled.constScanLine(y - vp.y() + sy0) + (clip.left() - vp.x() + sx0) * 4,
                           size_t(clip.width()) * 4);
                }
                m_lastWrittenRect = clip;
            } else {
                m_lastWrittenRect = QRect();
            }
        } else {
            m_lastWrittenRect = QRect();
        }
    } else {
        m_lastWrittenRect = QRect();
    }
    m_dirtyRect = QRect();
    return true;
}

// True when a render was skipped because the async recomposite is still
// running but dirty content is waiting (the Kotlin side retries in ~8ms).
bool ReverieCore::renderPendingDirty() const
{
    return m_document && !m_dirtyRect.isNull();
}

void ReverieCore::floodFillAt(int x, int y, int tolerance, bool sampleMerged)
{
    KisImageSP image = m_document ? m_document : KisImageSP();
    if (!image) return;
    if (x < 0 || y < 0 || x >= image->width() || y >= image->height()) return;
    KisPaintDeviceSP targetDevice = currentPaintDevice();
    if (!targetDevice) return;
    KisPaintDeviceSP srcDevice = sampleMerged ? image->projection() : targetDevice;

    KisTransaction txn(kundo2_i18n("Fill"), targetDevice);
    
    KisFillPainter painter(targetDevice);
    painter.setFillThreshold(tolerance);
    if (m_selection) {
        painter.setSelection(m_selection);
    }
    
    QColor qColor(m_brushColor);
    if (!qColor.isValid()) qColor = Qt::black;
    qColor.setAlphaF(qBound<qreal>(0.0, m_brushOpacity, 1.0));
    KoColor koColor(qColor, image->colorSpace());
    painter.setPaintColor(koColor);
    painter.setOpacityF(m_brushOpacity);
    if (m_brushPreset && m_brushPreset->settings()) {
        painter.setCompositeOpId(m_brushPreset->settings()->effectivePaintOpCompositeOp());
    } else {
        painter.setCompositeOpId(COMPOSITE_OVER);
    }

    // fillColor will flood fill starting from x, y sampling from srcDevice
    painter.fillColor(x, y, srcDevice);

    targetDevice->setDirty();
    markDirty();
    txn.commit(image->undoAdapter());
    m_redoCount = 0;
}


QString ReverieCore::pickColorAt(int x, int y, bool currentLayerOnly)
{
    KisImageSP image = m_document ? m_document : KisImageSP();
    if (!image) {
        return QString();
    }
    if (x < 0 || y < 0 || x >= image->width() || y >= image->height()) {
        return QString();
    }
    KisPaintDeviceSP dev = currentLayerOnly ? currentPaintDevice() : image->projection();
    if (!dev) return QString();
    quint8 pixel[4] = {0, 0, 0, 0};
    dev->readBytes(pixel, x, y, 1, 1);
    if (pixel[3] == 0) return QString(); // transparent
    // KoBgrU8Traits: pixel[0]=B, pixel[1]=G, pixel[2]=R, pixel[3]=A
    return QStringLiteral("#%1%2%3")
            .arg(pixel[2], 2, 16, QLatin1Char('0'))
            .arg(pixel[1], 2, 16, QLatin1Char('0'))
            .arg(pixel[0], 2, 16, QLatin1Char('0'));
}

void ReverieCore::drawShape(int kind, int x1, int y1, int x2, int y2, bool filled)
{
    KisImageSP image = m_document ? m_document : KisImageSP();
    if (!image) return;
    KisPaintDeviceSP device = currentPaintDevice();
    if (!device) return;

    KisTransaction txn(kundo2_i18n("Shape"), device);
    KisPainter painter(device);
    
    KoColor paintColor(QColor(m_brushColor), image->colorSpace());
    painter.setPaintColor(paintColor);
    painter.setBackgroundColor(paintColor);
    painter.setOpacityF(m_brushOpacity);
    
    painter.setStrokeStyle(KisPainter::StrokeStyleBrush);
    painter.setFillStyle(filled ? KisPainter::FillStyleForegroundColor : KisPainter::FillStyleNone);
    
    if (m_selection) {
        painter.setSelection(m_selection);
    }
    
    const int layerIndex = qBound(0, m_currentLayer, (int)m_layers.size() - 1);
    if (m_brushPreset) {
        painter.setPaintOpPreset(m_brushPreset, KisNodeSP(m_layers[layerIndex].node), image);
        if (m_brushPreset->settings()) {
            painter.setCompositeOpId(m_brushPreset->settings()->effectivePaintOpCompositeOp());
        }
    }
    
    painter.setRunnableStrokeJobsInterface(&m_fakeExecutor);
    
    QRect rect(qMin(x1, x2), qMin(y1, y2), qAbs(x2 - x1), qAbs(y2 - y1));
    if (kind == 0) { // Line
        painter.drawLine(QPointF(x1, y1), QPointF(x2, y2));
    } else if (kind == 1) { // Rect
        painter.paintRect(rect);
    } else if (kind == 2) { // Ellipse
        painter.paintEllipse(rect);
    }

    device->setDirty();
    markDirty();
    txn.commit(image->undoAdapter());
    m_redoCount = 0;
}

void ReverieCore::drawPolygon(const QVector<QPoint> &points, bool closed)
{
    KisImageSP image = m_document ? m_document : KisImageSP();
    if (!image) return;
    KisPaintDeviceSP device = currentPaintDevice();
    if (!device) return;
    if (points.size() < 2) return;

    KisTransaction txn(kundo2_i18n("Polygon"), device);
    KisPainter painter(device);
    
    KoColor paintColor(QColor(m_brushColor), image->colorSpace());
    painter.setPaintColor(paintColor);
    painter.setBackgroundColor(paintColor);
    painter.setOpacityF(m_brushOpacity);

    painter.setStrokeStyle(KisPainter::StrokeStyleBrush);
    painter.setFillStyle(m_shapeFilled ? KisPainter::FillStyleForegroundColor : KisPainter::FillStyleNone);
    
    if (m_selection) {
        painter.setSelection(m_selection);
    }
    
    const int layerIndex = qBound(0, m_currentLayer, (int)m_layers.size() - 1);
    if (m_brushPreset) {
        painter.setPaintOpPreset(m_brushPreset, KisNodeSP(m_layers[layerIndex].node), image);
        if (m_brushPreset->settings()) {
            painter.setCompositeOpId(m_brushPreset->settings()->effectivePaintOpCompositeOp());
        }
    }
    
    painter.setRunnableStrokeJobsInterface(&m_fakeExecutor);
    
    vQPointF pts;
    for (const QPoint &p : points) {
        pts.append(p);
    }

    if (closed) {
        painter.paintPolygon(pts);
    } else {
        painter.paintPolyline(pts);
    }

    device->setDirty();
    markDirty();
    txn.commit(image->undoAdapter());
    m_redoCount = 0;
}

void ReverieCore::gradientFill(int x1, int y1, int x2, int y2, int type, int repeat, bool reverse)
{
    KisImageSP image = m_document ? m_document : KisImageSP();
    if (!image) return;
    KisPaintDeviceSP device = currentPaintDevice();
    if (!device) return;
    if (x1 == x2 && y1 == y2) return;

    KisTransaction txn(kundo2_i18n("Gradient"), device);

    const int iw = image->width();
    const int ih = image->height();
    QImage gradImg(iw, ih, QImage::Format_ARGB32_Premultiplied);
    gradImg.fill(Qt::transparent);

    QPainter qp(&gradImg);
    qp.setRenderHint(QPainter::Antialiasing, true);

    QColor fgColor(m_brushColor);
    if (!fgColor.isValid()) fgColor = Qt::black;
    fgColor.setAlphaF(qBound<qreal>(0.0, m_brushOpacity, 1.0));

    QColor bgColor(m_brushSecondaryColor);
    if (!bgColor.isValid()) {
        bgColor = QColor(m_brushColor);
        bgColor.setAlphaF(0.0);
    } else {
        bgColor.setAlphaF(qBound<qreal>(0.0, m_brushOpacity, 1.0));
    }

    if (reverse) {
        std::swap(fgColor, bgColor);
    }

    QPointF p1(x1, y1);
    QPointF p2(x2, y2);

    QGradient::Spread spread = QGradient::PadSpread;
    if (repeat == 1) spread = QGradient::RepeatSpread;
    else if (repeat == 2) spread = QGradient::ReflectSpread;

    if (type == 1) { // Radial
        qreal r = QLineF(p1, p2).length();
        if (r < 1.0) r = 1.0;
        QRadialGradient grad(p1, r);
        grad.setSpread(spread);
        grad.setColorAt(0.0, fgColor);
        grad.setColorAt(1.0, bgColor);
        qp.setBrush(grad);
    } else if (type == 2) { // Conical
        qreal angle = -QLineF(p1, p2).angle();
        QConicalGradient grad(p1, angle);
        grad.setColorAt(0.0, fgColor);
        grad.setColorAt(1.0, bgColor);
        qp.setBrush(grad);
    } else { // Linear
        QLinearGradient grad(p1, p2);
        grad.setSpread(spread);
        grad.setColorAt(0.0, fgColor);
        grad.setColorAt(1.0, bgColor);
        qp.setBrush(grad);
    }
    qp.setPen(Qt::NoPen);
    qp.drawRect(0, 0, iw, ih);
    qp.end();

    KisPaintDeviceSP tempSrc = new KisPaintDevice(image->colorSpace());
    tempSrc->convertFromQImage(gradImg, 0);

    KisPainter painter(device);
    if (m_selection) {
        painter.setSelection(m_selection);
    }
    painter.setOpacityF(m_brushOpacity);
    painter.setCompositeOpId(COMPOSITE_OVER);
    painter.bitBlt(QPoint(0, 0), tempSrc, QRect(0, 0, iw, ih));

    device->setDirty();
    markDirty();
    txn.commit(image->undoAdapter());
    m_redoCount = 0;
}



// Solo mode: composite ONLY the keep layers (soloed + ancestors + descendants
// + background) into a fresh device, in document stack order. Pure render-time
// filter - no layer state (visible/opacity/blend/inheritAlpha) is ever
// modified, so closing solo restores the document exactly and solo can never
// corrupt the canvas render or the undo stack.
// Recursive solo composite of [startIdx, endIdx). Leaf layers are drawn
void ReverieCore::compositeRange(KisPaintDeviceSP out, int startIdx, int endIdx, const QRect &full)
{
    int i = startIdx;
    while (i < endIdx) {
        if (i < 0 || i >= m_layers.size()) {
            ++i;
            continue;
        }
        const LayerEntry &e = m_layers[i];
        if (!e.visible || !e.node) {
            if (e.isGroup) {
                int j = i + 1;
                while (j < endIdx && j < m_layers.size() && m_layers[j].depth > e.depth) {
                    ++j;
                }
                i = j;
            } else {
                ++i;
            }
            continue;
        }
        if (m_nodeFilters.contains(e.node) && m_nodeFilters[e.node].hasFilter) {
            const auto &cfg = m_nodeFilters[e.node];
            const int w = full.width();
            const int h = full.height();
            QImage img(w, h, QImage::Format_ARGB32_Premultiplied);
            out->readBytes(img.bits(), 0, 0, w, h);

            if (cfg.filterType == 13 && cfg.lut.size() >= 768) {
                const quint8 *r = reinterpret_cast<const quint8 *>(cfg.lut.constData());
                const quint8 *g = r + 256;
                const quint8 *b = g + 256;
                filterParallelFor(0, h, [&](int startY, int endY) {
                    for (int y = startY; y < endY; ++y) {
                        quint8 *line = img.scanLine(y);
                        for (int x = 0; x < w; ++x) {
                            quint8 *px = line + x * 4;
                            if (px[3] == 0) continue;
                            px[2] = r[px[2]];
                            px[1] = g[px[1]];
                            px[0] = b[px[0]];
                        }
                    }
                });
            } else if (cfg.filterType == 30 && cfg.lut.size() >= int(256 * sizeof(quint32))) {
                const quint32 *gLut = reinterpret_cast<const quint32 *>(cfg.lut.constData());
                filterParallelFor(0, h, [&](int startY, int endY) {
                    for (int y = startY; y < endY; ++y) {
                        quint8 *line = img.scanLine(y);
                        for (int x = 0; x < w; ++x) {
                            quint8 *px = line + x * 4;
                            if (px[3] == 0) continue;
                            int lum = (px[2] * 299 + px[1] * 587 + px[0] * 114) / 1000;
                            quint32 gCol = gLut[qBound(0, lum, 255)];
                            int gr = (gCol >> 16) & 0xFF;
                            int gg = (gCol >> 8) & 0xFF;
                            int gb = gCol & 0xFF;
                            int ga = (gCol >> 24) & 0xFF;
                            px[2] = quint8(gr);
                            px[1] = quint8(gg);
                            px[0] = quint8(gb);
                            px[3] = quint8((px[3] * ga) / 255);
                        }
                    }
                });
            } else if (cfg.filterType >= 0) {
                processFilterImage(cfg.filterType, cfg.p1, cfg.p2, cfg.p3, cfg.p4, img, w, h);
            }
            out->writeBytes(img.constBits(), 0, 0, w, h);
            ++i;
            continue;
        }
        if (e.isGroup) {
            int j = i + 1;
            while (j < endIdx && j < m_layers.size() && m_layers[j].depth > e.depth) {
                ++j;
            }
            KisPaintDeviceSP tmp(new KisPaintDevice(m_document->colorSpace()));
            tmp->fill(full, KoColor(Qt::transparent, m_document->colorSpace()));
            compositeRange(tmp, i + 1, j, full);
            KisPainter painter(out);
            painter.setOpacityF(qreal(e.node->opacity()) / 255.0);
            painter.setCompositeOpId(e.node->compositeOpId());
            painter.bitBlt(0, 0, tmp, 0, 0, full.width(), full.height());
            painter.end();
            i = j;
        } else {
            KisPaintDeviceSP dev = layerPaintDeviceFor(e);
            if (dev) {
                KisPainter painter(out);
                painter.setOpacityF(qreal(e.node->opacity()) / 255.0);
                painter.setCompositeOpId(e.node->compositeOpId());
                painter.bitBlt(0, 0, dev, 0, 0, full.width(), full.height());
                painter.end();
            }
            ++i;
        }
    }
}

void ReverieCore::compositeSoloRange(KisPaintDeviceSP out, int startIdx, int endIdx, const QRect &full)
{
    int i = startIdx;
    while (i < endIdx) {
        const LayerEntry &e = m_layers[i];
        if (e.isGroup && e.node) {
            // Find the group's span: entries with depth > e.depth
            int j = i + 1;
            while (j < endIdx && m_layers[j].depth > e.depth) {
                ++j;
            }
            KisPaintDeviceSP tmp(new KisPaintDevice(m_document->colorSpace()));
            tmp->fill(full, KoColor(Qt::transparent, m_document->colorSpace()));
            compositeSoloRange(tmp, i + 1, j, full);
            KisPainter painter(out);
            painter.setOpacityF(qreal(e.node->opacity()) / 255.0);
            painter.setCompositeOpId(e.node->compositeOpId());
            painter.bitBlt(0, 0, tmp, 0, 0, full.width(), full.height());
            painter.end();
            i = j;
        } else {
            // Leaf: composite only if it belongs to the solo keep set
            if (e.node && m_soloKeepNodes.contains(e.node)) {
                KisPaintDeviceSP dev = layerPaintDeviceFor(e);
                if (dev) {
                    KisPainter painter(out);
                    if (m_soloRawMode && e.node == m_soloedNode) {
                        // 取消所有效果：纯净原色（100% 不透明 + Normal 混合）
                        painter.setOpacityF(1.0);
                        painter.setCompositeOpId(QStringLiteral("normal"));
                    } else {
                        painter.setOpacityF(qreal(e.node->opacity()) / 255.0);
                        painter.setCompositeOpId(e.node->compositeOpId());
                    }
                    painter.bitBlt(0, 0, dev, 0, 0, full.width(), full.height());
                    painter.end();
                }
            }
            ++i;
        }
    }
}

KisPaintDeviceSP ReverieCore::compositeSoloProjection()
{
    KisImageSP image = m_document;
    if (!image || !m_soloedNode) {
        return KisPaintDeviceSP();
    }
    // Keep-set group projections must be fresh: mark them dirty (no state
    // change) and wait for the async recomposite before reading anything
    for (KisNode *n : m_soloKeepNodes) {
        for (const LayerEntry &e : m_layers) {
            if (e.node == n && e.isGroup && e.node) {
                e.node->setDirty(QRect(0, 0, image->width(), image->height()));
            }
        }
    }
    image->waitForDone();

    KisPaintDeviceSP out(new KisPaintDevice(image->colorSpace()));
    const QRect full(0, 0, image->width(), image->height());
    out->fill(full, KoColor(Qt::transparent, image->colorSpace()));
    compositeSoloRange(out, 0, m_layers.size(), full);
    return out;
}
