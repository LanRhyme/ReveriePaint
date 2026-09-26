/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/* ============================================================
 * ReverieCoreRender.cpp - Rendering: composite projection to bitmap, flood fill, color pick, shapes
 * (part of the ReverieCore module split; shared helpers live in
 * ReverieCoreInternal.h, public API in ReverieCore.h)
 * ============================================================ */
#include "ReverieCoreInternal.h"
#include "ReverieCoreFilterKernels.h"
#include <android/log.h>
#include <kis_image_animation_interface.h>

// ============================================================
// 笔触进行中的洋葱皮
// ============================================================

KisPaintDeviceSP ReverieCore::strokeOnionProjection(int layerIndex)
{
    KisImageSP image = m_document;
    if (!image || layerIndex < 0 || layerIndex >= m_layers.size()) return KisPaintDeviceSP();

    KisPaintLayer *pl = dynamic_cast<KisPaintLayer *>(m_layers[layerIndex].node);
    if (!pl || !pl->onionSkinEnabled()) return KisPaintDeviceSP();

    KisPaintDeviceSP src = pl->paintDevice();
    if (!src) return KisPaintDeviceSP();

    // 关键帧总数 <= 1 时 Krita 自己也不画洋葱皮 (copyOriginalToProjection 的同款
    // 判据), 这里保持一致, 免得出现"引擎投影没有、笔触叠加却有"的双标
    KisRasterKeyframeChannel *channel =
        dynamic_cast<KisRasterKeyframeChannel *>(src->keyframeChannel());
    if (!channel || channel->keyframeCount() <= 1) return KisPaintDeviceSP();

    const int time = image->animationInterface()->currentTime();
    const int seq = KisOnionSkinCompositor::instance()->configSeqNo();

    // 缓存校验: 时间 / 配置代际 / 通道结构三者任一变化就重算。
    // 笔画只改像素, 这三样在整个笔画期间都不动, 所以一笔只合成一次。
    if (m_strokeOnionCacheDirty || time != m_strokeOnionCacheTime ||
        seq != m_strokeOnionCacheSeq) {
        m_strokeOnionCache.clear();
        m_strokeOnionCacheTime = time;
        m_strokeOnionCacheSeq = seq;
        m_strokeOnionCacheDirty = false;
    }

    auto it = m_strokeOnionCache.constFind(layerIndex);
    if (it != m_strokeOnionCache.constEnd()) {
        return it.value();
    }

    KisOnionSkinCompositor *compositor = KisOnionSkinCompositor::instance();
    const QRect extent = compositor->calculateExtent(src);
    m_strokeOnionCacheExtent.insert(layerIndex, extent);
    if (extent.isEmpty()) {
        m_strokeOnionCache.insert(layerIndex, KisPaintDeviceSP());
        return KisPaintDeviceSP();
    }

    KisPaintDeviceSP skins(new KisPaintDevice(src->colorSpace()));
    compositor->composite(src, skins, extent);
    m_strokeOnionCache.insert(layerIndex, skins);
    return skins;
}

// 洋葱皮投影的覆盖范围, 供 compositeLayersRange 快速跳过"脏区碰不到洋葱皮"
// 的绝大多数情况 (笔画在当前位置画, 邻帧叠影往往在别处)。
QRect ReverieCore::strokeOnionExtent(int layerIndex)
{
    return m_strokeOnionCacheExtent.value(layerIndex, QRect());
}

// 复用的拼装设备。每次使用前必须把 [r] 范围清干净:
//   - 上一图层/上一帧在这里留下的像素不清掉就会叠出重影
//     (用户看到的"多渲染几层洋葱皮"正是旧像素没清);
//   - 但 clear 只需覆盖这次的脏区 r, 不必清整块画布。
// 复用设备本身省掉的是 KisPaintDevice 的构造/析构 (tile manager 那一坨),
// 那才是掉帧的主因; clear(r) 走的是 tile 级 memset, 相对廉价。
KisPaintDeviceSP ReverieCore::strokeMergeScratch(const QRect &r)
{
    if (!m_strokeMergeScratch || !m_document) {
        m_strokeMergeScratch = new KisPaintDevice(m_document->colorSpace());
    }
    m_strokeMergeScratch->clear(r);
    return m_strokeMergeScratch;
}

KisPaintDeviceSP ReverieCore::strokeOutScratch(const QRect &r)
{
    if (!m_strokeOutScratch || !m_document) {
        m_strokeOutScratch = new KisPaintDevice(m_document->colorSpace());
    }
    m_strokeOutScratch->clear(r);
    return m_strokeOutScratch;
}

bool ReverieCore::renderToBuffer(quint8 *buffer, int w, int h, bool forceFull)
{
    KisImageSP image = m_document;
    if (!image || !buffer || w <= 0 || h <= 0) {
        return false;
    }

    const int iw = image->width();
    const int ih = image->height();

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

    // Solo mode is a pure render-time filter: composite only the keep layers
    // (soloed + ancestors + descendants + background) into a fresh device and
    // read from that instead of the full projection. No layer state is ever
    // modified, so closing solo restores the document exactly and solo can
    // never corrupt the canvas render or the undo stack.
    KisPaintDeviceSP proj;
    if (m_soloedNode) {
        proj = compositeSoloProjection();
    } else {
        proj = image->projection();
        // 注: 液化事务的投影同步合成已挪到 liquifyApplyLocked (按 20~64ms 节流) ——
        // 放在渲染路径上会让"每个输入事件一次的渲染"都承担一次大区域合成, 大笔刷下
        // 直接吃满渲染线程 (真机表现为严重卡顿)。渲染路径因此只保留笔画这一支。
        // 上游 1.3.3: 有可见描边图层时同样必须走同步合成, 否则笔迹与描边不同步。
        bool hasVisibleStrokeLayer = false;
        for (const LayerEntry &le : m_layers) {
            if (le.visible && (le.isStrokeLayer || le.nodeType == NodeTypeStroke)) {
                hasVisibleStrokeLayer = true;
                break;
            }
        }
        if (m_drawing || hasVisibleStrokeLayer) {
            // Non-blocking in-stroke rendering: bypass Krita background scheduler completely.
            // Synchronously composite the exact dirty sub-region across visible layers in <0.05ms.
            const QRect r = m_dirtyRect.intersected(QRect(0, 0, iw, ih));
            if (!r.isEmpty()) {
                proj->clear(r);
                compositeLayersRange(proj, 0, m_layers.size(), r);
            }
        } else {
            // When not actively drawing a stroke (undo, layer toggle, filters), ensure projection is settled
            if (!image->isIdle()) {
                image->waitForDone();
            }
        }
    }
    if (!proj) {
        return false;
    }

    // 1:1 Native Resolution Rendering Path (Direct Krita GPU Engine Alignment)
    if (w == iw && h == ih) {
        // Solo mode always re-composites the full frame: the filtered
        // composite is rebuilt every call, so a dirty sub-region read would
        // only refresh part of the raw-mode switch and leave the rest stale
        if (m_soloedNode || !m_bitmapInited || m_dirtyRect == QRect(0, 0, iw, ih)) {
            // Full frame update: direct in-place read and SIMD conversion
            proj->readBytes(buffer, 0, 0, iw, ih);
            blitBgraToRgbaFast(buffer, iw * 4, buffer, w * 4, iw, ih);
            m_bitmapInited = true;
            m_lastWrittenRect = QRect(0, 0, w, h);
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
        // Phase 2A-2: 预览叠加 (只有在 debug 开关打开且预览有内容时才非空) —— 把低分辨率形变
        // 预览混进刚写好的缓冲区域, 于是旋转/缩放/平移都沿用画布自身的变换。
        // Phase 2B: 主机侧(AGSL)绘制时引擎不叠加, 否则会和 GPU 覆盖层叠两次。
        if (!m_liquifyPreviewOut.isEmpty() && !m_lastWrittenRect.isNull() && !liquifyPreviewHostDraw()) {
            blendLiquifyPreview(buffer, w, h, m_lastWrittenRect);
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
        // 直接以复用的脏区缓冲为 QImage 后端, 并就地做 BGRA→RGBA swizzle
        // (1:1 路径一直是这么做的, in-place 安全)。旧实现每帧多一次 rs 大小的
        // QImage 分配 + 整块 bits() 拷贝, 缩放视图下这是每帧一次的堆分配。
        quint8 *scratch = reinterpret_cast<quint8 *>(m_subRegionBuffer.data());
        proj->readBytes(scratch, rs.x(), rs.y(), rs.width(), rs.height());
        QImage subBgra(scratch, rs.width(), rs.height(), rs.width() * 4, QImage::Format_RGBA8888);
        blitBgraToRgbaFast(scratch, rs.width() * 4, scratch, rs.width() * 4, rs.width(), rs.height());

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
                if (!m_liquifyPreviewOut.isEmpty() && !liquifyPreviewHostDraw()) {
                    blendLiquifyPreview(buffer, w, h, clip);
                }
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

void ReverieCore::floodFillAt(int x, int y, int tolerance, bool sampleMerged, int expand, int feather, int closeGap)
{
    KisImageSP image = m_document ? m_document : KisImageSP();
    if (!image) return;
    if (x < 0 || y < 0 || x >= image->width() || y >= image->height()) return;
    KisPaintDeviceSP targetDevice = currentPaintDevice();
    if (!targetDevice) return;
    KisPaintDeviceSP srcDevice = sampleMerged ? image->projection() : targetDevice;

    const int clampedTol = qBound(1, tolerance, 100);

    // 填充日志按需开启: 每次填充一次 logcat 写入 + 两次 exactBounds() 全瓦片扫描,
    // 而填充是绘画软件的高频操作, 生产构建不该付这份代价 (`setprop debug.reverie.trace 1` 打开)
    const bool traceFill = rpDebugFlag("debug.reverie.trace", "REVERIE_TRACE", false);
    if (traceFill) {
        KoColor seedCol = srcDevice->pixel(QPoint(x, y));
        QColor qSeed;
        seedCol.toQColor(&qSeed);
        const QRect beforeBounds = targetDevice->exactBounds();
        __android_log_print(ANDROID_LOG_INFO, "RP_FILL",
            "floodFillAt START: pt=(%d, %d), tol=%d, exp=%d, fth=%d, gap=%d, merged=%d, seedRGBA=(%d,%d,%d,%d), targetBefore=(%d,%d,%d,%d)",
            x, y, clampedTol, expand, feather, closeGap, sampleMerged ? 1 : 0,
            qSeed.red(), qSeed.green(), qSeed.blue(), qSeed.alpha(),
            beforeBounds.x(), beforeBounds.y(), beforeBounds.width(), beforeBounds.height());
    }

    KisTransaction txn(kundo2_i18n("Fill"), targetDevice);
    
    KisFillPainter painter(targetDevice);
    painter.setWidth(image->width());
    painter.setHeight(image->height());
    painter.setCareForSelection(true);
    painter.setUseCompositing(true);
    painter.setOpacitySpread(100);
    painter.setAntiAlias(true);
    painter.setFillThreshold(clampedTol);
    painter.setSizemod(qBound(-32, expand, 64));
    painter.setFeather(qBound(0, feather, 32));
    painter.setCloseGap(qBound(0, closeGap, 32));
    if (m_selection) {
        painter.setSelection(m_selection);
    }
    KisPaintLayer *pl = (m_currentLayer >= 0 && m_currentLayer < m_layers.size())
        ? dynamic_cast<KisPaintLayer *>(m_layers[m_currentLayer].node)
        : nullptr;
    if (pl && pl->alphaLocked()) {
        painter.setChannelFlags(pl->channelLockFlags());
    } else {
        painter.setChannelFlags(QBitArray());
    }
    
    QColor qColor(m_brushColor);
    if (!qColor.isValid()) qColor = Qt::black;
    KoColor koColor(qColor, image->colorSpace());
    painter.setPaintColor(koColor);
    painter.setOpacityF(m_brushOpacity);
    painter.setCompositeOpId(COMPOSITE_OVER);

    // fillColor will flood fill starting from x, y sampling from srcDevice
    painter.fillColor(x, y, srcDevice);

    targetDevice->setDirty();
    recompositeProjection();
    markDirty();
    txn.commit(image->undoAdapter());
    m_redoCount = 0;

    if (traceFill) {
        const QRect afterBounds = targetDevice->exactBounds();
        __android_log_print(ANDROID_LOG_INFO, "RP_FILL",
            "floodFillAt END: targetAfter=(%d,%d,%d,%d)",
            afterBounds.x(), afterBounds.y(), afterBounds.width(), afterBounds.height());
    }
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
    KisPaintLayer *pl = (m_currentLayer >= 0 && m_currentLayer < m_layers.size())
        ? dynamic_cast<KisPaintLayer *>(m_layers[m_currentLayer].node)
        : nullptr;
    if (pl && pl->alphaLocked()) {
        painter.setChannelFlags(pl->channelLockFlags());
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
    KisPaintLayer *pl = (m_currentLayer >= 0 && m_currentLayer < m_layers.size())
        ? dynamic_cast<KisPaintLayer *>(m_layers[m_currentLayer].node)
        : nullptr;
    if (pl && pl->alphaLocked()) {
        painter.setChannelFlags(pl->channelLockFlags());
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
    KisPaintLayer *pl = (m_currentLayer >= 0 && m_currentLayer < m_layers.size())
        ? dynamic_cast<KisPaintLayer *>(m_layers[m_currentLayer].node)
        : nullptr;
    if (pl && pl->alphaLocked()) {
        painter.setChannelFlags(pl->channelLockFlags());
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
// only when they are in the solo keep set; groups composite their keep-set
// children into a temp device and then apply the group's own opacity/blend,
// so group nesting (and group opacity) stays correct and a soloed child is
// never drawn twice (once via its ancestor's projection, once directly).
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
            KisLayer *layer = dynamic_cast<KisLayer *>(e.node);
            if (layer && !layer->channelFlags().isEmpty()) {
                painter.setChannelFlags(layer->channelFlags());
            }
            painter.bitBlt(0, 0, tmp, 0, 0, full.width(), full.height());
            painter.end();
            i = j;
        } else {
            // Leaf: composite only if it belongs to the solo keep set
            if (e.node && m_soloKeepNodes.contains(e.node)) {
                if (!m_soloRawMode && (e.isStrokeLayer || e.nodeType == NodeTypeStroke)) {
                    compositeStrokeLayer(out, e, full);
                } else {
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
                            KisLayer *layer = dynamic_cast<KisLayer *>(e.node);
                            if (layer && !layer->channelFlags().isEmpty()) {
                                painter.setChannelFlags(layer->channelFlags());
                            }
                        }
                        painter.bitBlt(0, 0, dev, 0, 0, full.width(), full.height());
                        painter.end();
                    }
                }
            }
            ++i;
        }
    }
}

void ReverieCore::compositeLayersRange(KisPaintDeviceSP out, int startIdx, int endIdx, const QRect &r)
{
    if (!out || r.isEmpty() || !m_document) {
        return;
    }
    int i = startIdx;
    while (i < endIdx) {
        if (i < 0 || i >= m_layers.size()) break;
        const LayerEntry &e = m_layers[i];
        if (!e.visible || !e.node) {
            if (e.isGroup) {
                int j = i + 1;
                while (j < endIdx && m_layers[j].depth > e.depth) {
                    ++j;
                }
                i = j;
            } else {
                ++i;
            }
            continue;
        }

        if (e.isGroup) {
            int j = i + 1;
            while (j < endIdx && m_layers[j].depth > e.depth) {
                ++j;
            }
            KisPaintDeviceSP tmp(new KisPaintDevice(m_document->colorSpace()));
            tmp->clear(r);
            compositeLayersRange(tmp, i + 1, j, r);
            KisPainter painter(out);
            painter.setOpacityF(qreal(e.node->opacity()) / 255.0);
            painter.setCompositeOpId(e.node->compositeOpId());
            KisLayer *layer = dynamic_cast<KisLayer *>(e.node);
            if (layer && !layer->channelFlags().isEmpty()) {
                painter.setChannelFlags(layer->channelFlags());
            }
            painter.bitBlt(r.topLeft(), tmp, r);
            painter.end();
            i = j;
        } else if (e.nodeType == NodeTypeAdjustment) {
            KisAdjustmentLayer *adj = dynamic_cast<KisAdjustmentLayer *>(e.node);
            const quint8 op = e.node->opacity();
            KisFilterConfigurationSP config = adj ? adj->filter() : nullptr;
            if (adj && config && op > 0) {
                QVariant v;
                const int type = config->getProperty("reverieType", v) ? v.toInt() : 0;
                const double p1 = config->getProperty("p1", v) ? v.toDouble() : 0.0;
                const double p2 = config->getProperty("p2", v) ? v.toDouble() : 0.0;
                const double p3 = config->getProperty("p3", v) ? v.toDouble() : 0.0;
                const double p4 = config->getProperty("p4", v) ? v.toDouble() : 0.0;
                const bool hasLut = config->getProperty("lut", v);
                const QByteArray lut = hasLut ? v.toByteArray() : QByteArray();

                const int margin = reverieFilterMargin(type);
                const QRect docRect(0, 0, m_document->width(), m_document->height());
                const QRect work = r.adjusted(-margin, -margin, margin, margin).intersected(docRect);

                if (!work.isEmpty()) {
                    QImage img(work.width(), work.height(), QImage::Format_ARGB32_Premultiplied);
                    if (!img.isNull()) {
                        out->readBytes(img.bits(), work.x(), work.y(), work.width(), work.height());

                        QImage origCopy;
                        if (op < 255) {
                            origCopy = img.copy();
                        }

                        if (type == 13 && lut.size() >= 768) {
                            const quint8 *base = reinterpret_cast<const quint8 *>(lut.constData());
                            reverieApplyCurvesLutKernel(img, base, base + 256, base + 512);
                        } else if (type == 30 && lut.size() >= 1024) {
                            qint32 gradientLut[256];
                            memcpy(gradientLut, lut.constData(), sizeof(gradientLut));
                            reverieApplyGradientMapKernel(img, gradientLut);
                        } else {
                            reverieApplyScalarKernel(img, type, p1, p2, p3, p4);
                        }

                        if (op < 255 && !origCopy.isNull()) {
                            const int h = img.height();
                            const int w = img.width();
                            const int alpha = op;
                            const int invAlpha = 255 - alpha;
                            for (int y = 0; y < h; ++y) {
                                quint8 *dstP = img.scanLine(y);
                                const quint8 *srcP = origCopy.constScanLine(y);
                                for (int x = 0; x < w * 4; ++x) {
                                    dstP[x] = static_cast<quint8>((dstP[x] * alpha + srcP[x] * invAlpha) / 255);
                                }
                            }
                        }

                        if (margin == 0) {
                            out->writeBytes(img.constBits(), work.x(), work.y(), work.width(), work.height());
                        } else {
                            const QRect targetR = r.intersected(docRect);
                            if (!targetR.isEmpty()) {
                                const int sx = targetR.x() - work.x();
                                const int sy = targetR.y() - work.y();
                                QImage cropped(targetR.width(), targetR.height(), img.format());
                                for (int row = 0; row < cropped.height(); ++row) {
                                    memcpy(cropped.scanLine(row),
                                           img.scanLine(sy + row) + sx * 4,
                                           size_t(cropped.width()) * 4);
                                }
                                out->writeBytes(cropped.constBits(), targetR.x(), targetR.y(), cropped.width(), cropped.height());
                            }
                        }
                    }
                }
            }
            ++i;
        } else if (e.isStrokeLayer || e.nodeType == NodeTypeStroke) {
            compositeStrokeLayer(out, e, r);
            ++i;
            continue;
        } else {

            KisPaintDeviceSP dev = layerPaintDeviceFor(e);
            if (dev) {
                // 有洋葱皮时先在**复用的**临时设备里拼出"邻帧叠影 + 当前帧内容"
                // 再整体叠进 out。
                //
                // 为什么不能直接对 out 用 behind: out 里已经有更下面的图层,
                // behind 会把洋葱皮压到它们**底下**, 于是洋葱皮被下层挡住 ——
                // 正确语义是"洋葱皮只在本图层内容之下", 不能越过图层边界。
                // Krita 的做法也是给每个需要洋葱皮的图层单独建投影
                // (KisPaintLayer::needProjection + copyOriginalToProjection)。
                //
                // 性能要点 (历史教训): 这条路径每渲染帧每图层都要走一次
                // (笔画期间 ~8ms 一轮)。早期实现每次 new KisPaintDevice +
                // clear(r) + 3 次 bitBlt, tile manager 的构造/析构直接把渲染
                // 线程拖出掉帧, 用户表现为"绘制到洋葱皮区域就会卡"。现在:
                //   1. 脏区碰不到洋葱皮范围就直接走普通单 blit 路径
                //      (最常见 —— 笔画落在当前帧位置, 邻帧叠影往往在别处);
                //   2. 拼装设备复用, 省掉 KisPaintDevice 构造/析构;
                //   3. 合成范围来自缓存, 不再每次问 src->extent()
                //      (那是 O(脏瓦片数) 的遍历)。
                KisPaintDeviceSP src = nullptr;
                if (KisPaintLayer *plOnion = dynamic_cast<KisPaintLayer *>(e.node)) {
                    if (plOnion->onionSkinEnabled()) {
                        // 返回 nullptr 表示"这个图层此刻没有可显示的邻帧"
                        // (只有一个关键帧 / 配置全关), 走普通路径。
                        //
                        // 注意: extent 表是 strokeOnionProjection 里的**惰性缓存**,
                        // invalidateStrokeOnionCache() (切帧/改配置/结构变化) 会把
                        // 它整个清掉。条目不存在时必须先调一次 strokeOnionProjection
                        // 把 extent/投影算出来 —— 否则就是"extent 空 → 不调用 →
                        // 永远空"的死锁, 表现为切帧后笔画期间洋葱皮整个消失
                        // (抬笔走 Krita 投影路径才回来)。无通道/单帧图层在这里
                        // 提前退出且不落条目, 但那几次虚调用 + 计数没有分配,
                        // 每渲染帧付出完全可接受。
                        if (!m_strokeOnionCacheExtent.contains(i)) {
                            strokeOnionProjection(i);
                        }
                        const QRect onionExt = strokeOnionExtent(i);
                        if (!onionExt.isEmpty() && onionExt.intersects(r)) {
                            src = strokeOnionProjection(i);
                        }
                    }
                }

                // 当前帧内容 + 可选临时目标 (中转绘制) 的叠加函数。
                //
                // 洋葱皮存在时: 先在复用的 scratch 里拼出
                //   [洋葱皮 (behind)] <- [当前帧内容] <- [临时目标]
                // 再整体叠进 out。否则直接单次 bitBlt 进 out (保持原快路径,
                // 这是绝大多数图层的常态, 零额外开销)。
                const bool hasTemp = [&] {
                    KisPaintLayer *pl = dynamic_cast<KisPaintLayer *>(e.node);
                    return pl && pl->hasTemporaryTarget();
                }();

                if (!src && !hasTemp) {
                    // 最热路径: 无洋葱皮、无中转绘制 —— 一次 bitBlt 完事
                    KisPainter painter(out);
                    painter.setOpacityF(qreal(e.node->opacity()) / 255.0);
                    painter.setCompositeOpId(e.node->compositeOpId());
                    KisLayer *layer = dynamic_cast<KisLayer *>(e.node);
                    if (layer && !layer->channelFlags().isEmpty()) {
                        painter.setChannelFlags(layer->channelFlags());
                    }
                    painter.bitBlt(r.topLeft(), dev, r);
                    painter.end();
                } else {
                    KisPaintDeviceSP scratch = strokeMergeScratch(r);
                    if (src) {
                        KisPainter onionPainter(scratch);
                        onionPainter.setCompositeOpId(QStringLiteral("behind"));
                        onionPainter.bitBlt(r.topLeft(), src, r);
                        onionPainter.end();
                    }
                    {
                        KisPainter basePainter(scratch);
                        basePainter.setCompositeOpId(QStringLiteral("normal"));
                        basePainter.bitBlt(r.topLeft(), dev, r);
                        basePainter.end();
                    }
                    if (hasTemp) {
                        KisPaintLayer *pl = dynamic_cast<KisPaintLayer *>(e.node);
                        KisPaintDeviceSP tempTarget = pl ? pl->temporaryTarget() : nullptr;
                        if (tempTarget) {
                            KisPainter tempPainter(scratch);
                            if (pl) {
                                pl->setupTemporaryPainter(&tempPainter);
                            } else {
                                tempPainter.setOpacityF(qBound<qreal>(0.0, m_strokeOpacity, 1.0));
                            }
                            if (tempPainter.compositeOpId().isEmpty()) {
                                tempPainter.setCompositeOpId(QStringLiteral("normal"));
                            }
                            if (m_toolMode == ToolEraser) {
                                tempPainter.setCompositeOpId(QStringLiteral("erase"));
                            }
                            if (m_selection) {
                                tempPainter.setSelection(m_selection);
                            }
                            tempPainter.bitBlt(r.topLeft(), tempTarget, r);
                            tempPainter.end();
                        }
                    }
                    KisPainter painter(out);
                    painter.setOpacityF(qreal(e.node->opacity()) / 255.0);
                    painter.setCompositeOpId(e.node->compositeOpId());
                    KisLayer *layer = dynamic_cast<KisLayer *>(e.node);
                    if (layer && !layer->channelFlags().isEmpty()) {
                        painter.setChannelFlags(layer->channelFlags());
                    }
                    painter.bitBlt(r.topLeft(), scratch, r);
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

namespace {
// 1D squared Euclidean distance transform (Felzenszwalb & Huttenlocher, PAMI 2012)
static inline void edt1d(const float *f, float *d, int *v, float *z, int n)
{
    int k = 0;
    v[0] = 0;
    z[0] = -1e20f;
    z[1] = 1e20f;
    for (int q = 1; q < n; ++q) {
        float s = ((f[q] + float(q * q)) - (f[v[k]] + float(v[k] * v[k]))) / (2.0f * float(q - v[k]));
        while (s <= z[k]) {
            --k;
            s = ((f[q] + float(q * q)) - (f[v[k]] + float(v[k] * v[k]))) / (2.0f * float(q - v[k]));
        }
        ++k;
        v[k] = q;
        z[k] = s;
        z[k + 1] = 1e20f;
    }
    k = 0;
    for (int q = 0; q < n; ++q) {
        while (z[k + 1] < float(q)) {
            ++k;
        }
        float dq = float(q - v[k]);
        d[q] = dq * dq + f[v[k]];
    }
}

struct EdtWorkspace {
    std::vector<float> colBuf;
    std::vector<float> fCol;
    std::vector<float> dCol;
    std::vector<float> dRow;
    std::vector<int> vBuf;
    std::vector<float> zBuf;

    void ensureCapacity(int w, int h) {
        const int maxDim = std::max(w, h);
        if (int(vBuf.size()) < maxDim + 2) vBuf.resize(maxDim + 2);
        if (int(zBuf.size()) < maxDim + 2) zBuf.resize(maxDim + 2);
        if (int(fCol.size()) < h) fCol.resize(h);
        if (int(dCol.size()) < h) dCol.resize(h);
        if (int(dRow.size()) < w) dRow.resize(w);
        if (int(colBuf.size()) < w * h) colBuf.resize(w * h);
    }
};

static void computeEDT2D(const float *fIn, float *dOut, int w, int h, EdtWorkspace &ws)
{
    ws.ensureCapacity(w, h);

    // Pass 1: Along each column
    for (int x = 0; x < w; ++x) {
        for (int y = 0; y < h; ++y) {
            ws.fCol[y] = fIn[y * w + x];
        }
        edt1d(ws.fCol.data(), ws.dCol.data(), ws.vBuf.data(), ws.zBuf.data(), h);
        for (int y = 0; y < h; ++y) {
            ws.colBuf[y * w + x] = ws.dCol[y];
        }
    }

    // Pass 2: Along each row
    for (int y = 0; y < h; ++y) {
        const float *fRow = ws.colBuf.data() + y * w;
        edt1d(fRow, ws.dRow.data(), ws.vBuf.data(), ws.zBuf.data(), w);
        for (int x = 0; x < w; ++x) {
            dOut[y * w + x] = ws.dRow[x];
        }
    }
}
} // namespace

void ReverieCore::compositeStrokeLayer(KisPaintDeviceSP out, const LayerEntry &e, const QRect &r)
{
    KisPaintLayer *pl = dynamic_cast<KisPaintLayer *>(e.node);
    if (!pl || !out || r.isEmpty() || !m_document) return;

    KisPaintDeviceSP dev = pl->paintDevice();
    if (!dev) return;

    const bool hasTemp = pl->hasTemporaryTarget();
    QRect bounds = dev->exactBounds();
    if (hasTemp && pl->temporaryTarget()) {
        bounds = bounds.united(pl->temporaryTarget()->exactBounds());
    }
    if (bounds.isEmpty()) return;

    const int sz = qBound(1, e.strokeSize, 100);
    const int pos = qBound(0, e.strokePosition, 2);
    const int op = qBound(0, e.strokeOpacity, 100);
    const quint32 col = e.strokeColor;

    const int pad = sz + 3;
    const QRect docRect(0, 0, m_document->width(), m_document->height());
    const QRect strokeBounds = bounds.adjusted(-pad, -pad, pad, pad).intersected(docRect);
    const QRect targetRect = r.intersected(strokeBounds);
    if (targetRect.isEmpty()) return;

    const QRect readRect = targetRect.adjusted(-pad, -pad, pad, pad).intersected(docRect);
    if (readRect.isEmpty()) return;

    // Merge base layer + temporary in-progress stroke into a scratch device for padded read rect
    KisPaintDeviceSP scratch = strokeMergeScratch(readRect);
    {
        KisPainter basePainter(scratch);
        basePainter.setCompositeOpId(QStringLiteral("normal"));
        basePainter.bitBlt(readRect.topLeft(), dev, readRect);
        basePainter.end();
    }
    if (hasTemp) {
        KisPaintDeviceSP tempTarget = pl->temporaryTarget();
        if (tempTarget) {
            KisPainter tempPainter(scratch);
            pl->setupTemporaryPainter(&tempPainter);
            if (tempPainter.compositeOpId().isEmpty()) {
                tempPainter.setCompositeOpId(QStringLiteral("normal"));
            }
            if (m_toolMode == ToolEraser) {
                tempPainter.setCompositeOpId(QStringLiteral("erase"));
            }
            if (m_selection) {
                tempPainter.setSelection(m_selection);
            }
            tempPainter.bitBlt(readRect.topLeft(), tempTarget, readRect);
            tempPainter.end();
        }
    }

    if (sz <= 0 || op <= 0) {
        KisPainter painter(out);
        painter.setOpacityF(qreal(pl->opacity()) / 255.0);
        painter.setCompositeOpId(pl->compositeOpId());
        if (!pl->channelFlags().isEmpty()) painter.setChannelFlags(pl->channelFlags());
        painter.bitBlt(targetRect.topLeft(), scratch, targetRect);
        painter.end();
        return;
    }

    const int rw = readRect.width();
    const int rh = readRect.height();
    const size_t pixelCount = size_t(rw) * rh;

    QImage baseImg(rw, rh, QImage::Format_ARGB32_Premultiplied);
    scratch->readBytes(baseImg.bits(), readRect.x(), readRect.y(), rw, rh);

    thread_local EdtWorkspace edtWs;
    thread_local std::vector<float> fInBuf;
    thread_local std::vector<float> sqDistOut;
    thread_local std::vector<float> sqDistIn;
    if (fInBuf.size() < pixelCount) fInBuf.resize(pixelCount);
    if (sqDistOut.size() < pixelCount) sqDistOut.resize(pixelCount);
    if (sqDistIn.size() < pixelCount) sqDistIn.resize(pixelCount);

    const quint8 *baseData = baseImg.constBits();

    // 1. Outside distance field: sub-pixel continuous distance from background to foreground boundary
    if (pos == 0 || pos == 2) {
        for (size_t k = 0; k < pixelCount; ++k) {
            const float a = float(baseData[k * 4 + 3]) / 255.0f;
            if (a >= 0.5f) {
                fInBuf[k] = 0.0f;
            } else if (a > 0.0f) {
                const float diff = 0.5f - a;
                fInBuf[k] = diff * diff;
            } else {
                fInBuf[k] = 1e8f;
            }
        }
        computeEDT2D(fInBuf.data(), sqDistOut.data(), rw, rh, edtWs);
    }

    // 2. Inside distance field: sub-pixel continuous distance from foreground to background boundary
    if (pos == 1 || pos == 2) {
        for (size_t k = 0; k < pixelCount; ++k) {
            const float a = float(baseData[k * 4 + 3]) / 255.0f;
            if (a <= 0.5f) {
                fInBuf[k] = 0.0f;
            } else if (a < 1.0f) {
                const float diff = a - 0.5f;
                fInBuf[k] = diff * diff;
            } else {
                fInBuf[k] = 1e8f;
            }
        }
        computeEDT2D(fInBuf.data(), sqDistIn.data(), rw, rh, edtWs);
    }

    const float sR = float((col >> 16) & 0xFF);
    const float sG = float((col >> 8) & 0xFF);
    const float sB = float(col & 0xFF);
    const float opF = float(op) / 100.0f;
    const float radius = float(sz);

    const int tw = targetRect.width();
    const int th = targetRect.height();
    const int offsetX = targetRect.x() - readRect.x();
    const int offsetY = targetRect.y() - readRect.y();

    QImage composited(tw, th, QImage::Format_ARGB32_Premultiplied);

    for (int y = 0; y < th; ++y) {
        quint8 *dstPix = composited.scanLine(y);
        const int srcY = offsetY + y;
        const int srcRowOffset = srcY * rw;
        for (int x = 0; x < tw; ++x) {
            const int srcX = offsetX + x;
            const int srcIdx = srcRowOffset + srcX;
            const quint8 *basePix = baseData + srcIdx * 4;

            const float bB = float(basePix[0]);
            const float bG = float(basePix[1]);
            const float bR = float(basePix[2]);
            const float bA = float(basePix[3]) / 255.0f;

            quint8 *outP = dstPix + x * 4;

            if (pos == 0) { // Outside
                const float d = std::sqrt(sqDistOut[srcIdx]);
                float stA = 0.0f;
                if (d <= radius - 0.5f) {
                    stA = 1.0f;
                } else if (d < radius + 0.5f) {
                    stA = (radius + 0.5f) - d;
                }
                const float a_stroke = stA * opF;
                const float a_base = bA;
                const float a_out = a_base + a_stroke * (1.0f - a_base);

                if (a_out <= 0.001f) {
                    outP[0] = 0;
                    outP[1] = 0;
                    outP[2] = 0;
                    outP[3] = 0;
                } else {
                    const float w_base = a_base / a_out;
                    const float w_stroke = (a_stroke * (1.0f - a_base)) / a_out;

                    const float outB = bB * w_base + sB * w_stroke;
                    const float outG = bG * w_base + sG * w_stroke;
                    const float outR = bR * w_base + sR * w_stroke;

                    outP[0] = static_cast<quint8>(qBound(0.0f, outB + 0.5f, 255.0f));
                    outP[1] = static_cast<quint8>(qBound(0.0f, outG + 0.5f, 255.0f));
                    outP[2] = static_cast<quint8>(qBound(0.0f, outR + 0.5f, 255.0f));
                    outP[3] = static_cast<quint8>(qBound(0.0f, a_out * 255.0f + 0.5f, 255.0f));
                }
            } else if (pos == 1) { // Inside
                const float a_base = bA;
                if (a_base <= 0.001f) {
                    outP[0] = 0;
                    outP[1] = 0;
                    outP[2] = 0;
                    outP[3] = 0;
                } else {
                    const float d = std::sqrt(sqDistIn[srcIdx]);
                    float stA = 0.0f;
                    if (d <= radius - 0.5f) {
                        stA = 1.0f;
                    } else if (d < radius + 0.5f) {
                        stA = (radius + 0.5f) - d;
                    }
                    const float a_stroke = stA * opF;

                    const float outB = sB * a_stroke + bB * (1.0f - a_stroke);
                    const float outG = sG * a_stroke + bG * (1.0f - a_stroke);
                    const float outR = sR * a_stroke + bR * (1.0f - a_stroke);

                    outP[0] = static_cast<quint8>(qBound(0.0f, outB + 0.5f, 255.0f));
                    outP[1] = static_cast<quint8>(qBound(0.0f, outG + 0.5f, 255.0f));
                    outP[2] = static_cast<quint8>(qBound(0.0f, outR + 0.5f, 255.0f));
                    outP[3] = static_cast<quint8>(qBound(0.0f, a_base * 255.0f + 0.5f, 255.0f));
                }
            } else { // Center
                const float halfRadius = radius * 0.5f;
                const float dOut = std::sqrt(sqDistOut[srcIdx]);
                const float dIn = std::sqrt(sqDistIn[srcIdx]);

                float stAOut = 0.0f;
                if (dOut <= halfRadius - 0.5f) stAOut = 1.0f;
                else if (dOut < halfRadius + 0.5f) stAOut = (halfRadius + 0.5f) - dOut;
                const float a_out = stAOut * opF;

                const float a_base = bA;
                float innerB = bB, innerG = bG, innerR = bR;
                if (a_base > 0.001f) {
                    float stAIn = 0.0f;
                    if (dIn <= halfRadius - 0.5f) stAIn = 1.0f;
                    else if (dIn < halfRadius + 0.5f) stAIn = (halfRadius + 0.5f) - dIn;
                    const float a_in = stAIn * opF;
                    innerB = sB * a_in + bB * (1.0f - a_in);
                    innerG = sG * a_in + bG * (1.0f - a_in);
                    innerR = sR * a_in + bR * (1.0f - a_in);
                }

                const float a_total = a_base + a_out * (1.0f - a_base);
                if (a_total <= 0.001f) {
                    outP[0] = 0;
                    outP[1] = 0;
                    outP[2] = 0;
                    outP[3] = 0;
                } else {
                    const float w_base = a_base / a_total;
                    const float w_stroke = (a_out * (1.0f - a_base)) / a_total;

                    const float finalB = innerB * w_base + sB * w_stroke;
                    const float finalG = innerG * w_base + sG * w_stroke;
                    const float finalR = innerR * w_base + sR * w_stroke;

                    outP[0] = static_cast<quint8>(qBound(0.0f, finalB + 0.5f, 255.0f));
                    outP[1] = static_cast<quint8>(qBound(0.0f, finalG + 0.5f, 255.0f));
                    outP[2] = static_cast<quint8>(qBound(0.0f, finalR + 0.5f, 255.0f));
                    outP[3] = static_cast<quint8>(qBound(0.0f, a_total * 255.0f + 0.5f, 255.0f));
                }
            }
        }
    }

    KisPaintDeviceSP tempDev = strokeOutScratch(targetRect);
    tempDev->writeBytes(composited.constBits(), targetRect.x(), targetRect.y(), tw, th);

    KisPainter painter(out);
    painter.setOpacityF(qreal(pl->opacity()) / 255.0);
    painter.setCompositeOpId(pl->compositeOpId());
    if (!pl->channelFlags().isEmpty()) painter.setChannelFlags(pl->channelFlags());
    painter.bitBlt(targetRect.topLeft(), tempDev, targetRect);
    painter.end();
}
