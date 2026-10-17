/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/* ============================================================
 * ReverieCoreRender.cpp - Rendering: composite projection to bitmap, flood fill, color pick, shapes
 * (part of the ReverieCore module split; shared helpers live in
 * ReverieCoreInternal.h, public API in ReverieCore.h)
 * ============================================================ */
#include "ReverieCoreInternal.h"
#include "PixelAlpha.h"
#include "ReverieCoreFilterKernels.h"
#include "ReverieCoreColorSpaceHook.h"
#include "StrokePresentFastPath.h"
#include <KisEncloseAndFillPainter.h>
#include <android/log.h>
#include <kis_image_animation_interface.h>

namespace {
// 逐块处理的输入: 优先离散列表, 列表为空时退回总包围盒单块。
inline int dirtyRegionCount(const QVector<QRect> &list, const QRect &fallback)
{
    if (!list.isEmpty()) {
        return list.size();
    }
    return fallback.isEmpty() ? 0 : 1;
}

inline QRect dirtyRegionAt(const QVector<QRect> &list, const QRect &fallback, int i)
{
    return list.isEmpty() ? fallback : list.at(i);
}
} // namespace

// ============================================================
// 离散脏区列表
// ============================================================

void ReverieCore::addDirtyRegion(const QRect &r)
{
    if (r.isEmpty()) {
        return;
    }
    const int docW = m_document ? m_document->width() : m_docWidth;
    const int docH = m_document ? m_document->height() : m_docHeight;
    if (docW <= 0 || docH <= 0) {
        return;
    }
    const QRect docRect(0, 0, docW, docH);
    const QRect clipped = r.intersected(docRect);
    if (clipped.isEmpty()) {
        return;
    }
    // 整幅画布脏: 列表收敛成这一块 (其余块都是它的子集, 留着只会重复合成)
    if (clipped == docRect) {
        m_dirtyRects.clear();
        m_dirtyRects.append(clipped);
        return;
    }

    reverie::DirtyRectList list;
    list.count = qMin(m_dirtyRects.size(), reverie::DirtyRectList::kCapacity);
    for (int i = 0; i < list.count; ++i) {
        const QRect &q = m_dirtyRects.at(i);
        list.rects[i] = reverie::DirtyRect{q.x(), q.y(), q.width(), q.height()};
    }
    reverie::appendOrCoalesce(list,
                              reverie::DirtyRect{clipped.x(), clipped.y(), clipped.width(), clipped.height()},
                              reverie::DirtyRectList::kCapacity);
    m_dirtyRects.resize(list.count);
    for (int i = 0; i < list.count; ++i) {
        const reverie::DirtyRect &d = list.rects[i];
        m_dirtyRects[i] = QRect(d.x, d.y, d.w, d.h);
    }
}

// ============================================================
// 运笔静态底图
// ============================================================

void ReverieCore::bakeStaticUnderlay(const QRect &r)
{
    const int stopIdx = strokeStaticUnderlayStop(*this);
    if (!m_document || r.isEmpty() || stopIdx <= 0) {
        return;
    }

    // 底图记的是 [0, stopIdx) 那摞下层的像素: 上界换了 (或换了文档) 就整体作废。
    if (m_staticUnderlayTop != stopIdx || m_staticUnderlayDoc != m_document.data()) {
        invalidateStaticUnderlay();
        m_staticUnderlayTop = stopIdx;
        m_staticUnderlayDoc = m_document.data();
    }
    if (!m_staticUnderlay) {
        m_staticUnderlay = new KisPaintDevice(m_document->colorSpace());
    }

    const QRect docRect(0, 0, m_document->width(), m_document->height());
    const QRect clip = r.intersected(docRect);
    if (clip.isEmpty()) {
        return;
    }

    // 一行里连续缺失的宏瓦片并成一段一起合成; 碰到已烘焙的瓦片先把攒着的那段冲掉,
    // 否则段会跨过已烘焙的瓦片, 把它也清掉重算 (白算一遍)。
    const int tile = kStaticUnderlayTile;
    for (int ty = clip.top() / tile; ty <= clip.bottom() / tile; ++ty) {
        QRect rowSpan;
        for (int tx = clip.left() / tile; tx <= clip.right() / tile; ++tx) {
            const quint64 key = (quint64(quint32(ty)) << 32) | quint64(quint32(tx));
            if (m_staticUnderlayTiles.contains(key)) {
                if (!rowSpan.isEmpty()) {
                    m_staticUnderlay->clear(rowSpan);
                    compositeLayersRange(m_staticUnderlay, 0, stopIdx, rowSpan);
                    rowSpan = QRect();
                }
                continue;
            }
            m_staticUnderlayTiles.insert(key);
            const QRect tc = QRect(tx * tile, ty * tile, tile, tile).intersected(docRect);
            if (!tc.isEmpty()) {
                rowSpan = rowSpan.isEmpty() ? tc : rowSpan.united(tc);
            }
        }
        if (!rowSpan.isEmpty()) {
            m_staticUnderlay->clear(rowSpan);
            compositeLayersRange(m_staticUnderlay, 0, stopIdx, rowSpan);
        }
    }
}

void ReverieCore::compositeDirtyRegions(KisPaintDeviceSP out)
{
    if (!out || !m_document) {
        return;
    }
    const QRect docRect(0, 0, m_document->width(), m_document->height());
    const int stopIdx = strokeStaticUnderlayStop(*this);
    const int n = dirtyRegionCount(m_dirtyRects, m_dirtyRect);

    if (stopIdx > 0) {
        // [0, stopIdx) 由静态底图提供: COMPOSITE_COPY 覆盖 r 内全部像素 (不必先 clear), 再从
        // stopIdx 往上合成当前单元与上方可见层, 与从 0 全量合成逐像素一致。
        // COPY 的 painter 只在循环外建一次; bakeStaticUnderlay 会按需创建底图, 所以先烘焙再判空。
        // 每块必须"COPY 之后紧接着合成上层": 离散脏区合并后可能互相重叠, 若先把所有块 COPY 完
        // 再统一合成上层, 重叠处的上层会被叠两遍 (半透明/叠加模式下颜色错)。
        KisPainter copyPainter(out);
        copyPainter.setCompositeOpId(COMPOSITE_COPY);
        for (int i = 0; i < n; ++i) {
            const QRect r = dirtyRegionAt(m_dirtyRects, m_dirtyRect, i).intersected(docRect);
            if (r.isEmpty()) {
                continue;
            }
            bakeStaticUnderlay(r);
            if (m_staticUnderlay) {
                copyPainter.bitBlt(r.topLeft(), m_staticUnderlay, r);
                compositeLayersRange(out, stopIdx, m_layers.size(), r);
            } else {
                out->clear(r);
                compositeLayersRange(out, 0, m_layers.size(), r);
            }
        }
        copyPainter.end();
        return;
    }

    for (int i = 0; i < n; ++i) {
        const QRect r = dirtyRegionAt(m_dirtyRects, m_dirtyRect, i).intersected(docRect);
        if (r.isEmpty()) {
            continue;
        }
        out->clear(r);
        compositeLayersRange(out, 0, m_layers.size(), r);
    }
}

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
KisPaintDeviceSP ReverieCore::strokeMergeScratchDevice()
{
    if (!m_strokeMergeScratch) {
        m_strokeMergeScratch = new KisPaintDevice(m_document->colorSpace());
    }
    return m_strokeMergeScratch;
}

KisPaintDeviceSP ReverieCore::strokeMergeScratch(const QRect &r)
{
    KisPaintDeviceSP scratch = strokeMergeScratchDevice();
    scratch->clear(r);
    return scratch;
}

KisPaintDeviceSP ReverieCore::strokeOutScratch(const QRect &r)
{
    if (!m_strokeOutScratch || !m_document) {
        m_strokeOutScratch = new KisPaintDevice(m_document->colorSpace());
    }
    m_strokeOutScratch->clear(r);
    return m_strokeOutScratch;
}

KisPaintDeviceSP ReverieCore::borrowScratchDevice(const QRect &r)
{
    if (!m_document) return nullptr;
    if (m_scratchPoolIndex >= m_scratchPool.size()) {
        m_scratchPool.append(new KisPaintDevice(m_document->colorSpace()));
    }
    KisPaintDeviceSP dev = m_scratchPool[m_scratchPoolIndex++];
    dev->clear(r);
    return dev;
}

void ReverieCore::returnScratchDevice()
{
    if (m_scratchPoolIndex > 0) {
        --m_scratchPoolIndex;
    }
}

namespace {
struct ScratchDeviceGuard {
    ReverieCore *core;
    KisPaintDeviceSP dev;
    ScratchDeviceGuard(ReverieCore *c, const QRect &r)
        : core(c), dev(c ? c->borrowScratchDevice(r) : nullptr) {}
    ~ScratchDeviceGuard() {
        if (core) core->returnScratchDevice();
    }
    KisPaintDeviceSP device() const { return dev; }
};
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
        setDirtyFullFrame(QRect(0, 0, iw, ih));
    }

    // Solo mode is a pure render-time filter: composite only the keep layers
    // (soloed + ancestors + descendants + background) into a fresh device and
    // read from that instead of the full projection. No layer state is ever
    // modified, so closing solo restores the document exactly and solo can
    // never corrupt the canvas render or the undo stack.
    KisPaintDeviceSP proj;
    const bool wasAsyncBusy = !image->isIdle();
    if (wasAsyncBusy) {
        // If Krita's background async scheduler is actively updating projection (e.g. undo/redo,
        // committed layer opacity/blend/visibility), wait for it to settle first so projection tiles
        // are not concurrently written to by multiple threads (prevents KisTiledExtentManager SIGABRT).
        image->waitForDone();
    }

    if (m_soloedNode) {
        proj = compositeSoloProjection();
    } else {
        proj = image->projection();
        // If Krita's async merger just ran to completion (wasAsyncBusy), the projection device is already
        // fully composited by Krita's native merger and clean. We do NOT need to re-clear and re-composite.
        // Fast synchronous compositeLayersRange is used when the background scheduler is idle:
        // 1) Active in-stroke drawing (m_drawing)
        // 2) Visible stroke layers requiring real-time stroke synchronization
        // 3) Pen-up final stroke completion or direct slider drag previews (!m_dirtyRect.isEmpty())
        if (!wasAsyncBusy) {
            bool hasSpecialCompositeLayer = false;
            for (const LayerEntry &le : m_layers) {
                if (le.visible && (le.isStrokeLayer || le.nodeType == NodeTypeStroke || le.nodeType == NodeTypeTransparencyMask)) {
                    hasSpecialCompositeLayer = true;
                    break;
                }
            }
            if (m_drawing || hasSpecialCompositeLayer || !m_dirtyRect.isEmpty()) {
                // 逐块合成离散脏区, 不再先把几处笔迹并成一个大包围盒再整体合成。
                compositeDirtyRegions(proj);
            }
        }
    }
    if (!proj) {
        return false;
    }

    // 1:1 Native Resolution Rendering Path (Direct Krita GPU Engine Alignment)
    if (w == iw && h == ih) {
        // 预览基座(残影修复): 液化预览期间目标图层的像素由预览自己提供, 所以这里必须先把
        // "不含目标图层"的底图写回这块区域 —— 顺序在 blendLiquifyPreview / 覆盖层之前。
        // 少了这一步, 被形变搬走的原始像素会留在原地透出来(透明画布上就是残影)。
        // Phase 2A-2: 预览叠加 (只有在 debug 开关打开且预览有内容时才非空) —— 把低分辨率形变
        // 预览混进刚写好的缓冲区域, 于是旋转/缩放/平移都沿用画布自身的变换。
        // Phase 2B: 主机侧(AGSL)绘制时引擎不叠加, 否则会和 GPU 覆盖层叠两次。
        const bool blendPreview = !m_liquifyPreviewOut.isEmpty() && !liquifyPreviewHostDraw();
        // Solo mode always re-composites the full frame: the filtered
        // composite is rebuilt every call, so a dirty sub-region read would
        // only refresh part of the raw-mode switch and leave the rest stale
        if (m_soloedNode || !m_bitmapInited || m_dirtyRect == QRect(0, 0, iw, ih)) {
            // Full frame update: direct in-place read and SIMD conversion
            readLiquifyDisplayRegion(proj, buffer, QRect(0, 0, iw, ih));
            PixelAlpha::toDisplayRows(buffer, iw * 4, buffer, w * 4, iw, ih);
            m_bitmapInited = true;
            m_lastWrittenRect = QRect(0, 0, w, h);
            if (blendPreview) {
                blendLiquifyPreview(buffer, w, h, m_lastWrittenRect);
            }
        } else if (m_dirtyRect.isNull()) {
            // Nothing painted since the last render and the buffer already
            // holds a complete frame: skip the write and report a no-op so
            // the caller can drop the (identical) display flip instead of
            // re-drawing the canvas for unchanged pixels.
            clearDirtyRegions();
            m_lastWrittenRect = QRect();
            return false;
        } else {
            // 逐块增量更新, 每块按自己的精确像素边界读回并转换 (无取整缝隙/错位)。
            // 预览也逐块叠加: 并集里的空洞本帧没有重读, 在那里再叠一次会重复混合。
            // m_lastWrittenRect 是本帧实际写过的并集。
            const QRect imageRect(0, 0, iw, ih);
            QRect written;
            const int count = dirtyRegionCount(m_dirtyRects, m_dirtyRect);
            for (int i = 0; i < count; ++i) {
                const QRect r = dirtyRegionAt(m_dirtyRects, m_dirtyRect, i).intersected(imageRect);
                if (r.isEmpty()) {
                    continue;
                }
                const size_t req = size_t(r.width()) * r.height() * 4;
                if (size_t(m_subRegionBuffer.size()) < req) {
                    m_subRegionBuffer.resize(req);
                }
                readLiquifyDisplayRegion(proj, reinterpret_cast<quint8 *>(m_subRegionBuffer.data()), r);
                quint8 *dst = buffer + size_t(r.y()) * (w * 4) + size_t(r.x()) * 4;
                PixelAlpha::toDisplayRows(reinterpret_cast<const quint8 *>(m_subRegionBuffer.constData()), r.width() * 4,
                                   dst, w * 4, r.width(), r.height());
                if (blendPreview) {
                    blendLiquifyPreview(buffer, w, h, r);
                }
                written = written.isNull() ? r : written.united(r);
            }
            m_lastWrittenRect = written;
        }
        clearDirtyRegions();
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

    // 逐块缩放后贴回, 每块的两端都走同一条 round(edge*scale) 映射规则。
    // 预览逐块叠加 (理由同 1:1 路径); m_lastWrittenRect 是本帧实际写过的并集。
    const auto mapX = [&](int v) { return qRound(v * sx); };
    const auto mapY = [&](int v) { return qRound(v * sy); };
    const QRect imageRect(0, 0, iw, ih);
    QRect written;
    const int count = dirtyRegionCount(m_dirtyRects, m_dirtyRect);
    for (int i = 0; i < count; ++i) {
        const QRect r = dirtyRegionAt(m_dirtyRects, m_dirtyRect, i).intersected(imageRect);
        if (r.isEmpty()) {
            continue;
        }
        const int targetX = mapX(r.x());
        const int targetY = mapY(r.y());
        const int targetW = mapX(r.x() + r.width()) - targetX;
        const int targetH = mapY(r.y() + r.height()) - targetY;
        if (targetW <= 0 || targetH <= 0) {
            continue;
        }
        const size_t req = size_t(r.width()) * r.height() * 4;
        if (size_t(m_subRegionBuffer.size()) < req) {
            m_subRegionBuffer.resize(req);
        }
        quint8 *scratch = reinterpret_cast<quint8 *>(m_subRegionBuffer.data());
        readLiquifyDisplayRegion(proj, scratch, r);
        QImage subBgra(scratch, r.width(), r.height(), r.width() * 4, QImage::Format_RGBA8888_Premultiplied);
        PixelAlpha::toDisplayRows(scratch, r.width() * 4, scratch, r.width() * 4, r.width(), r.height());
        const QImage scaled = (subBgra.width() != targetW || subBgra.height() != targetH)
                ? subBgra.scaled(targetW, targetH, Qt::IgnoreAspectRatio, Qt::SmoothTransformation)
                : subBgra;
        if (scaled.isNull()) {
            continue;
        }
        const QRect vp(targetX, targetY, scaled.width(), scaled.height());
        const QRect clip = vp.intersected(QRect(0, 0, w, h));
        if (clip.isEmpty()) {
            continue;
        }
        for (int y = clip.top(); y <= clip.bottom(); ++y) {
            memcpy(buffer + size_t(y) * (w * 4) + clip.left() * 4,
                   scaled.constScanLine(y - vp.y()) + (clip.left() - vp.x()) * 4,
                   size_t(clip.width()) * 4);
        }
        if (!m_liquifyPreviewOut.isEmpty() && !liquifyPreviewHostDraw()) {
            blendLiquifyPreview(buffer, w, h, clip);
        }
        written = written.isNull() ? clip : written.united(clip);
    }
    m_lastWrittenRect = written;
    clearDirtyRegions();
    return true;
}

// True when a render was skipped because the async recomposite is still
// running but dirty content is waiting (the Kotlin side retries in ~8ms).
bool ReverieCore::renderPendingDirty() const
{
    return m_document && !m_dirtyRect.isNull();
}

// ============================================================
// 512 显示瓦片网格
//
// renderToBuffer 是"一整张缓冲"的契约: 每帧 lockPixels 的是一整幅画布, HWUI 也就必须
// 整张重传纹理 (4K 下 33 MB/帧)。下面两个入口把它拆成"一帧准备一次 + 每块瓦片各写各的":
// 投影与脏区在一帧内只算一次, 但 lockPixels 的目标缓冲只有 512x512, 于是未被脏区命中
// 的瓦片位图引用不变, HWUI 直接复用显存里的旧纹理。
// ============================================================

int ReverieCore::beginTileFrame(QRect *outRects, int cap)
{
    m_tileFrameProjection = KisPaintDeviceSP();
    if (!outRects || cap <= 0) {
        return 0;
    }
    KisImageSP image = m_document;
    if (!image) {
        return 0;
    }
    const int iw = image->width();
    const int ih = image->height();
    if (iw <= 0 || ih <= 0) {
        return 0;
    }

    // 与 renderToBuffer 同一套准备: 异步合并没落定就等, 落定了就不必再合成
    // (Krita 的 merger 已经把投影算干净了)。
    const bool wasAsyncBusy = !image->isIdle();
    if (wasAsyncBusy) {
        image->waitForDone();
    }

    KisPaintDeviceSP proj;
    if (m_soloedNode) {
        proj = compositeSoloProjection();
    } else {
        proj = image->projection();
        if (!wasAsyncBusy) {
            bool hasSpecialCompositeLayer = false;
            for (const LayerEntry &le : m_layers) {
                if (le.visible && (le.isStrokeLayer || le.nodeType == NodeTypeStroke ||
                                   le.nodeType == NodeTypeTransparencyMask)) {
                    hasSpecialCompositeLayer = true;
                    break;
                }
            }
            if (m_drawing || hasSpecialCompositeLayer || !m_dirtyRect.isEmpty()) {
                compositeDirtyRegions(proj);
            }
        }
    }
    if (!proj) {
        return 0;
    }
    m_tileFrameProjection = proj;

    int n = 0;
    if (m_soloedNode) {
        // solo 每次调用都重建整幅过滤投影, 说不上哪一块与上一帧相同: 只能整幅算脏。
        outRects[0] = QRect(0, 0, iw, ih);
        n = 1;
    } else {
        const QRect docRect(0, 0, iw, ih);
        const int count = dirtyRegionCount(m_dirtyRects, m_dirtyRect);
        for (int i = 0; i < count; ++i) {
            const QRect r = dirtyRegionAt(m_dirtyRects, m_dirtyRect, i).intersected(docRect);
            if (r.isEmpty()) {
                continue;
            }
            if (n < cap) {
                outRects[n++] = r;
            } else {
                // 块数超过调用方容量: 剩余部分并进最后一块, 不能丢脏区。
                outRects[cap - 1] = outRects[cap - 1].united(r);
            }
        }
    }
    // 脏区在这里一次性取走: 同一帧后面的每个瓦片都拿同一份快照, 不会因为先写的
    // 瓦片清了脏区而漏掉后面的。
    clearDirtyRegions();
    return n;
}

bool ReverieCore::renderTileToBuffer(quint8 *buffer, int bufW, int bufH, int docX, int docY,
                                     const QRect *dirtyRects, int dirtyCount, QRect *outWritten)
{
    if (outWritten) {
        *outWritten = QRect();
    }
    KisImageSP image = m_document;
    KisPaintDeviceSP proj = m_tileFrameProjection;
    if (!image || !proj || !buffer || bufW <= 0 || bufH <= 0) {
        return false;
    }
    if (dirtyCount > 0 && !dirtyRects) {
        return false;
    }
    const QRect docRect(0, 0, image->width(), image->height());
    const QRect tileRect = QRect(docX, docY, bufW, bufH).intersected(docRect);
    if (tileRect.isEmpty()) {
        return false;
    }

    const bool blendPreview = !m_liquifyPreviewOut.isEmpty() && !liquifyPreviewHostDraw();
    // dirtyCount <= 0 = 整块瓦片都要写 (新分配 / 刚从后备池取出, 内容已失效)。
    const int steps = (dirtyCount > 0) ? dirtyCount : 1;
    QRect writtenLocal;
    QRect writtenDoc;
    for (int i = 0; i < steps; ++i) {
        const QRect r = ((dirtyCount > 0) ? dirtyRects[i] : tileRect).intersected(tileRect);
        if (r.isEmpty()) {
            continue;
        }
        const int localX = r.x() - docX;
        const int localY = r.y() - docY;
        quint8 *dst = buffer + size_t(localY) * size_t(bufW) * 4 + size_t(localX) * 4;
        // 脏区宽度过半张瓦片时直接读整行带 (宽 = bufW, 行步长与瓦片缓冲一致) 并就地转码,
        // 省掉暂存缓冲的二次搬运。条件:
        //  - 只有内部瓦片 (tileRect 与瓦片同宽) 才能按 bufW 行步长读;
        //  - 液化预览叠加时不走整带: 带内脏区以外的像素会被改回未叠加的投影。
        const bool wholeBand = r.width() >= bufW / 2 && tileRect.width() == bufW && !blendPreview;
        QRect local(localX, localY, r.width(), r.height());
        if (wholeBand) {
            const QRect band(docX, r.y(), bufW, r.height());
            quint8 *bandDst = buffer + size_t(localY) * size_t(bufW) * 4;
            readLiquifyDisplayRegion(proj, bandDst, band);
            PixelAlpha::toDisplayRows(bandDst, bufW * 4, bandDst, bufW * 4, band.width(), band.height());
            local = QRect(0, localY, bufW, r.height());
        } else {
            // 转码暂存用线程局部缓冲, 不再依赖成员 m_subRegionBuffer (compositeLayersRange 的蒙版
            // 调制也用它): 瓦片转码不再和合成共用状态, 日后多线程转码互不踩踏。
            static thread_local QByteArray tlSubRegionBuf;
            const int need = r.width() * r.height() * 4;
            if (tlSubRegionBuf.size() < need) {
                tlSubRegionBuf.resize(need);
            }
            readLiquifyDisplayRegion(proj, reinterpret_cast<quint8 *>(tlSubRegionBuf.data()), r);
            PixelAlpha::toDisplayRows(reinterpret_cast<const quint8 *>(tlSubRegionBuf.constData()),
                                      r.width() * 4, dst, bufW * 4, r.width(), r.height());
        }
        if (blendPreview) {
            blendLiquifyPreviewScaled(buffer, bufW, bufH, docX, docY, 1.0, 1.0, local);
        }
        // outWritten 是瓦片局部坐标, Kotlin 侧再加上 tileLeft/tileTop 还原成文档坐标。
        writtenLocal = writtenLocal.isNull() ? local : writtenLocal.united(local);
        const QRect localDoc = local.translated(docX, docY);
        writtenDoc = writtenDoc.isNull() ? localDoc : writtenDoc.united(localDoc);
    }
    if (outWritten) {
        *outWritten = writtenLocal;
    }
    if (!writtenDoc.isNull()) {
        m_lastWrittenRect = writtenDoc;
    }
    return !writtenLocal.isNull();
}

void ReverieCore::floodFillAt(int x, int y, int tolerance, bool sampleMerged, int expand, int feather, int closeGap)
{
    ensureRgbU8DifferenceHook();
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

void ReverieCore::encloseAndFill(const QVector<QPoint> &points,
                                 int tolerance,
                                 bool sampleMerged,
                                 int expand,
                                 int feather,
                                 int closeGap,
                                 int regionSelectionMethod,
                                 quint32 regionColorArgb,
                                 bool includeContourRegions)
{
    ensureRgbU8DifferenceHook();
    KisImageSP image = m_document ? m_document : KisImageSP();
    if (!image) return;
    QPolygon poly;
    poly.reserve(points.size());
    for (const QPoint &p : points) {
        poly << p;
    }
    const QRect polyBounds = poly.boundingRect();
    if (polyBounds.isEmpty()) return;

    KisPaintDeviceSP targetDevice = currentPaintDevice();
    if (!targetDevice) return;

    KisPaintDeviceSP srcDevice;
    if (sampleMerged) {
        const int startIdx = (!m_layers.isEmpty() && m_layers[0].background) ? 1 : 0;
        if (startIdx < m_layers.size()) {
            KisPaintDeviceSP compDev = new KisPaintDevice(image->colorSpace());
            compositeLayersRange(compDev, startIdx, m_layers.size(), image->bounds());
            srcDevice = compDev;
        } else {
            srcDevice = targetDevice;
        }
    } else {
        srcDevice = targetDevice;
    }

    const int clampedTol = qBound(1, tolerance, 100);

    const bool traceFill = true; // Log unconditionally for diagnosis
    if (traceFill) {
        __android_log_print(ANDROID_LOG_INFO, "RP_FILL",
            "encloseAndFill START: pts=%d, tol=%d, exp=%d, fth=%d, gap=%d, method=%d, incContour=%d, merged=%d",
            (int)points.size(), clampedTol, expand, feather, closeGap, regionSelectionMethod, includeContourRegions ? 1 : 0, sampleMerged ? 1 : 0);
    }

    KisTransaction txn(kundo2_i18n("Enclose and Fill"), targetDevice);

    KisEncloseAndFillPainter painter(targetDevice, m_selection, image->size());
    painter.setWidth(image->width());
    painter.setHeight(image->height());
    painter.setCareForSelection(true);
    painter.setUseCompositing(true);
    painter.setOpacitySpread(100);
    painter.setAntiAlias(true);
    painter.setFillThreshold(clampedTol);
    painter.setSizemod(qBound(-32, expand, 64));
    painter.setFeather(qBound(0, feather, 32));
    painter.setCloseGap(qBound(0, closeGap, 64));

    painter.setRegionSelectionMethod(static_cast<KisEncloseAndFillPainter::RegionSelectionMethod>(regionSelectionMethod));
    if (regionColorArgb != 0) {
        QColor qc = QColor::fromRgba(regionColorArgb);
        painter.setRegionSelectionColor(KoColor(qc, image->colorSpace()));
    }
    painter.setRegionSelectionIncludeContourRegions(includeContourRegions);

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

    // Rasterize enclosing lasso polygon directly via QImage (Alpha8) + writeBytes,
    // avoiding KoColor(Qt::white, enclosingMask->colorSpace()) which crashes on unmanaged AlphaCs in mobile pigment
    KisPixelSelectionSP enclosingMask = new KisPixelSelection(new KisSelectionDefaultBounds(targetDevice));
    const int iw = image->width();
    const int ih = image->height();
    {
        QImage maskImg(iw, ih, QImage::Format_Alpha8);
        maskImg.fill(0);

        QPainter qp(&maskImg);
        qp.setRenderHint(QPainter::Antialiasing, false);
        qp.setBrush(Qt::white);
        qp.setPen(Qt::NoPen);

        qp.drawPolygon(poly);
        qp.end();

        const QVector<quint8> maskBytes = qimageAlpha8ToMask(maskImg);
        enclosingMask->writeBytes(maskBytes.constData(), 0, 0, iw, ih);
        enclosingMask->setDirty(QRect(0, 0, iw, ih));
    }

    const QRect maskRect = enclosingMask->selectedExactRect();
    __android_log_print(ANDROID_LOG_INFO, "RP_FILL",
        "encloseAndFill enclosingMask rect=(%d,%d,%d,%d)",
        maskRect.x(), maskRect.y(), maskRect.width(), maskRect.height());

    painter.encloseAndFillColor(enclosingMask, srcDevice);

    targetDevice->setDirty();
    recompositeProjection();
    markDirty();
    txn.commit(image->undoAdapter());
    m_redoCount = 0;

    const QRect afterBounds = targetDevice->exactBounds();
    __android_log_print(ANDROID_LOG_INFO, "RP_FILL",
        "encloseAndFill END: targetAfter=(%d,%d,%d,%d)",
        afterBounds.x(), afterBounds.y(), afterBounds.width(), afterBounds.height());
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
namespace {
// 组是否处于穿透混合模式 (Pass-through)。
// 穿透组不产生自己的合成表面: 子层直接按各自混合模式混入父级上下文,
// 组自身的混合模式与不透明度被忽略。Krita 原生 merger 走 kis_projection_leaf
// 的 checkPassThrough(); 本工程的同步合成路径必须显式复刻这一语义。
static inline bool nodeIsPassThroughGroup(KisNodeWSP node)
{
    const KisGroupLayer *grp = dynamic_cast<const KisGroupLayer *>(node.data());
    return grp && grp->passThroughMode();
}
} // namespace

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
            // 穿透组: 子层直接合成到 out, 不套用组自身的混合模式/不透明度
            if (nodeIsPassThroughGroup(KisNodeWSP(e.node))) {
                compositeSoloRange(out, i + 1, j, full);
                i = j;
                continue;
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

void ReverieCore::compositeLayersRange(KisPaintDeviceSP out, int startIdx, int endIdx, const QRect &r,
                                       int excludeIdx)
{
    if (!out || r.isEmpty() || !m_document) {
        return;
    }

    auto applyAdjustment = [&](KisPaintDeviceSP target, const LayerEntry &adjEntry) {
        KisAdjustmentLayer *adj = dynamic_cast<KisAdjustmentLayer *>(adjEntry.node);
        const quint8 op = adjEntry.node->opacity();
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
                    target->readBytes(img.bits(), work.x(), work.y(), work.width(), work.height());

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
                        target->writeBytes(img.constBits(), work.x(), work.y(), work.width(), work.height());
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
                            target->writeBytes(cropped.constBits(), targetR.x(), targetR.y(), cropped.width(), cropped.height());
                        }
                    }
                }
            }
        }
    };

    auto maskDeviceFor = [&](int mIdx) -> KisPaintDeviceSP {
        if (mIdx < 0 || mIdx >= m_layers.size()) return nullptr;
        const LayerEntry &me = m_layers[mIdx];
        if (!me.visible || !me.node) return nullptr;
        KisPaintLayer *mpl = dynamic_cast<KisPaintLayer *>(me.node);
        if (!mpl) return nullptr;
        KisPaintDeviceSP mDev = mpl->paintDevice();
        if (!mDev) return nullptr;
        if (mpl->hasTemporaryTarget()) {
            KisPaintDeviceSP mTemp = mpl->temporaryTarget();
            if (mTemp) {
                KisPaintDeviceSP mScratch = strokeOutScratch(r);
                KisPainter bp(mScratch);
                bp.setCompositeOpId(QStringLiteral("normal"));
                bp.bitBlt(r.topLeft(), mDev, r);
                bp.end();

                KisPainter tp(mScratch);
                mpl->setupTemporaryPainter(&tp);
                if (tp.compositeOpId().isEmpty()) tp.setCompositeOpId(QStringLiteral("normal"));
                if (m_toolMode == ToolEraser && mIdx == m_currentLayer) tp.setCompositeOpId(QStringLiteral("erase"));
                tp.bitBlt(r.topLeft(), mTemp, r);
                tp.end();
                return mScratch;
            }
        }
        return mDev;
    };

    auto compositePaintLayer = [&](KisPaintDeviceSP target, const LayerEntry &le, int leIdx, bool lockAlpha, qreal overrideOpacity, const QString &overrideOp, int maskIdx = -1) {
        KisPaintDeviceSP dev = layerPaintDeviceFor(le);
        if (!dev) return;

        KisPaintDeviceSP src = nullptr;
        if (KisPaintLayer *plOnion = dynamic_cast<KisPaintLayer *>(le.node)) {
            if (plOnion->onionSkinEnabled()) {
                if (!m_strokeOnionCacheExtent.contains(leIdx)) {
                    strokeOnionProjection(leIdx);
                }
                const QRect onionExt = strokeOnionExtent(leIdx);
                if (!onionExt.isEmpty() && onionExt.intersects(r)) {
                    src = strokeOnionProjection(leIdx);
                }
            }
        }

        KisPaintLayer *pl = dynamic_cast<KisPaintLayer *>(le.node);
        const bool hasTemp = pl && pl->hasTemporaryTarget();
        KisPaintDeviceSP maskDev = (maskIdx >= 0) ? maskDeviceFor(maskIdx) : nullptr;

        const qreal opacity = (overrideOpacity >= 0.0) ? overrideOpacity : (qreal(le.node->opacity()) / 255.0);
        const QString opId = (!overrideOp.isEmpty()) ? overrideOp : le.node->compositeOpId();

        QBitArray chanFlags;
        if (lockAlpha) {
            chanFlags = target->colorSpace()->channelFlags(true, false);
        }
        KisLayer *layer = dynamic_cast<KisLayer *>(le.node);
        if (layer && !layer->channelFlags().isEmpty()) {
            if (lockAlpha) {
                chanFlags &= layer->channelFlags();
            } else {
                chanFlags = layer->channelFlags();
            }
        }

        // Wash 模式 (间接绘制, hasTemp) 在 100% 不透明、normal、无 Alpha 锁的图层上走直通:
        //   Over(Over(out, dev, 1), temp, tempOpacity) == Over(out, Over(dev, temp, tempOpacity), 1)
        // 按次序把 dev 与临时目标直接合到 target, 省掉拼装暂存的整块写入与读出 (两次 bitBlt)。
        // 数学上等价; 8 位整数下两种结合次序的舍入可能差 1 LSB (只影响运笔中的显示, 抬笔后
        // 由 Krita 自己的合成接管)。洋葱皮 / 图层蒙版 / 半透明 / 非 normal 混合 / 通道锁 /
        // Alpha 锁 / 橡皮擦 / 非 normal 的临时算子任一成立就退回暂存路径。
        const bool canDirectTempOver = !src && hasTemp && pl && !maskDev && opacity >= 0.999 &&
            (opId.isEmpty() || opId == QLatin1String("normal")) && chanFlags.isEmpty() &&
            !pl->alphaLocked() && m_toolMode != ToolEraser &&
            (m_indirectTempCompositeOp.isEmpty() || m_indirectTempCompositeOp == QLatin1String("normal"));

        if (!src && !hasTemp && !maskDev) {
            const QRect devExt = dev->extent();
            const KoColorSpace *cs = dev->colorSpace();
            const bool isDefaultTransparent = !cs || cs->opacityU8(dev->defaultPixel().data()) == 0;
            if (isDefaultTransparent && (devExt.isEmpty() || !devExt.intersects(r))) {
                if (opId != COMPOSITE_COPY && opId != COMPOSITE_CLEAR) {
                    return;
                }
            }

            KisPainter painter(target);
            painter.setOpacityF(opacity);
            painter.setCompositeOpId(opId);
            if (!chanFlags.isEmpty()) {
                painter.setChannelFlags(chanFlags);
            }
            painter.bitBlt(r.topLeft(), dev, r);
            painter.end();
        } else if (canDirectTempOver) {
            KisPainter devPainter(target);
            devPainter.setCompositeOpId(QStringLiteral("normal"));
            devPainter.setOpacityToUnit();
            devPainter.bitBlt(r.topLeft(), dev, r);
            devPainter.end();
            // 临时目标的合成设置与暂存路径完全一致 (setupTemporaryPainter + 同样的覆盖), 只是直接落在 target 上
            if (KisPaintDeviceSP tempTarget = pl->temporaryTarget()) {
                KisPainter tempPainter(target);
                pl->setupTemporaryPainter(&tempPainter);
                if (tempPainter.compositeOpId().isEmpty()) {
                    tempPainter.setCompositeOpId(QStringLiteral("normal"));
                }
                if (m_selection) {
                    tempPainter.setSelection(m_selection);
                }
                tempPainter.bitBlt(r.topLeft(), tempTarget, r);
                tempPainter.end();
            }
        } else {
            // 没有洋葱皮时本层直接 COMPOSITE_COPY 进暂存 (覆盖 r 内全部像素), 与"先 clear 再 normal
            // 叠到全透明上"结果相同, 省掉一次 clear(r) 遍历。
            KisPaintDeviceSP scratch = src ? strokeMergeScratch(r) : strokeMergeScratchDevice();
            if (src) {
                KisPainter onionPainter(scratch);
                onionPainter.setCompositeOpId(QStringLiteral("behind"));
                onionPainter.bitBlt(r.topLeft(), src, r);
                onionPainter.end();
            }
            {
                KisPainter basePainter(scratch);
                basePainter.setCompositeOpId(src ? QStringLiteral("normal") : QString(COMPOSITE_COPY));
                basePainter.bitBlt(r.topLeft(), dev, r);
                basePainter.end();
            }
            if (hasTemp) {
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
            if (maskDev) {
                const size_t pixelCount = size_t(r.width()) * r.height();
                const size_t byteCount = pixelCount * 4;
                if (size_t(m_subRegionBuffer.size()) < byteCount * 2) {
                    m_subRegionBuffer.resize(byteCount * 2);
                }
                quint8 *lBuf = reinterpret_cast<quint8 *>(m_subRegionBuffer.data());
                quint8 *mBuf = lBuf + byteCount;
                scratch->readBytes(lBuf, r.x(), r.y(), r.width(), r.height());
                maskDev->readBytes(mBuf, r.x(), r.y(), r.width(), r.height());
                PixelAlpha::applyMaskModulation(lBuf, mBuf, pixelCount);
                scratch->writeBytes(lBuf, r.x(), r.y(), r.width(), r.height());
            }
            KisPainter painter(target);
            painter.setOpacityF(opacity);
            painter.setCompositeOpId(opId);
            if (!chanFlags.isEmpty()) {
                painter.setChannelFlags(chanFlags);
            }
            painter.bitBlt(r.topLeft(), scratch, r);
            painter.end();
        }
    };

    int i = startIdx;
    while (i < endIdx) {
        if (i < 0 || i >= m_layers.size()) break;
        const LayerEntry &e = m_layers[i];
        if (i == excludeIdx || !e.visible || !e.node) {
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

        // 独立蒙版图层 (透明度蒙版、局部选区蒙版、变换蒙版、滤镜蒙版): 不作为普通图层直接输出到画布
        if (e.nodeType >= NodeTypeTransparencyMask && e.nodeType <= NodeTypeSelectionMask) {
            ++i;
            continue;
        }

        // 剪切蒙版孤立层 (下方的基底被隐藏/被排除/不存在): 跳过绘制
        if (e.clipped) {
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

        // 计算当前图层及其子树结束位置 (包含其下的子图层与蒙版)
        int eEnd = i + 1;
        while (eEnd < endIdx && m_layers[eEnd].depth > e.depth) {
            ++eEnd;
        }

        // 查找直接挂载在当前图层下的可见透明度蒙版 (若有)
        int maskEntryIdx = -1;
        for (int mi = i + 1; mi < eEnd; ++mi) {
            if (m_layers[mi].depth == e.depth + 1 &&
                m_layers[mi].nodeType == NodeTypeTransparencyMask &&
                m_layers[mi].visible && m_layers[mi].node) {
                maskEntryIdx = mi;
                break;
            }
        }

        // 检查上方紧邻且同深度的剪切蒙版层链
        int clipEnd = eEnd;
        while (clipEnd < endIdx && m_layers[clipEnd].depth == e.depth && m_layers[clipEnd].clipped) {
            if (m_layers[clipEnd].isGroup) {
                int gEnd = clipEnd + 1;
                while (gEnd < endIdx && m_layers[gEnd].depth > e.depth) {
                    ++gEnd;
                }
                clipEnd = gEnd;
            } else {
                ++clipEnd;
            }
        }

        // 分支 A: 无剪切蒙版层, 走快速既有路径
        if (clipEnd == eEnd) {
            if (e.isGroup) {
                // 穿透混合模式: 子层各自按自身混合模式直接合成到父级上下文,
                // 组自身的混合模式/不透明度被忽略 (与 Krita 原生 merger 语义一致)。
                // 此处不能走 tmp 中转, 否则组会退化成隔离组。
                if (nodeIsPassThroughGroup(KisNodeWSP(e.node))) {
                    compositeLayersRange(out, i + 1, eEnd, r, excludeIdx);
                } else {
                    ScratchDeviceGuard tmpGuard(this, r);
                    KisPaintDeviceSP tmp = tmpGuard.device();
                    compositeLayersRange(tmp, i + 1, eEnd, r, excludeIdx);
                    KisPainter painter(out);
                    painter.setOpacityF(qreal(e.node->opacity()) / 255.0);
                    painter.setCompositeOpId(e.node->compositeOpId());
                    KisLayer *layer = dynamic_cast<KisLayer *>(e.node);
                    if (layer && !layer->channelFlags().isEmpty()) {
                        painter.setChannelFlags(layer->channelFlags());
                    }
                    painter.bitBlt(r.topLeft(), tmp, r);
                    painter.end();
                }
            } else if (e.nodeType == NodeTypeAdjustment) {
                applyAdjustment(out, e);
            } else if (e.isStrokeLayer || e.nodeType == NodeTypeStroke) {
                compositeStrokeLayer(out, e, r);
            } else {
                compositePaintLayer(out, e, i, false, -1.0, QString(), maskEntryIdx);
            }
            i = eEnd;
            continue;
        }

        // 分支 B: 存在剪切蒙版链 (Base Layer + 1..N Clipped Layers)
        ScratchDeviceGuard clipGuard(this, r);
        KisPaintDeviceSP clipScratch = clipGuard.device();

        // 1. 渲染 Base Layer 内容到 clipScratch (以 100% 不透明度与 Normal 混合, 自身混合模式/不透明度在最终合入 out 时应用)
        // 穿透组作为剪切基底: 其子层直接合成到 clipScratch, 仍由第 3 步按组的属性合入 out,
        // 保证"被剪切的层"能正确透过基底内容显示
        if (e.isGroup && nodeIsPassThroughGroup(KisNodeWSP(e.node))) {
            compositeLayersRange(clipScratch, i + 1, eEnd, r, excludeIdx);
        } else if (e.isGroup) {
            compositeLayersRange(clipScratch, i + 1, eEnd, r, excludeIdx);
        } else if (e.nodeType == NodeTypeAdjustment) {
            applyAdjustment(clipScratch, e);
        } else if (e.isStrokeLayer || e.nodeType == NodeTypeStroke) {
            compositeStrokeLayer(clipScratch, e, r);
        } else {
            compositePaintLayer(clipScratch, e, i, false, 1.0, QStringLiteral("normal"), maskEntryIdx);
        }

        // 2. 依次渲染剪切蒙版层链到 clipScratch (锁定 Alpha 通道)
        int cIdx = eEnd;
        while (cIdx < clipEnd) {
            const LayerEntry &c = m_layers[cIdx];
            if (!c.visible || !c.node || cIdx == excludeIdx) {
                if (c.isGroup) {
                    int gEnd = cIdx + 1;
                    while (gEnd < clipEnd && m_layers[gEnd].depth > c.depth) ++gEnd;
                    cIdx = gEnd;
                } else {
                    ++cIdx;
                }
                continue;
            }

            if (c.isGroup) {
                int gEnd = cIdx + 1;
                while (gEnd < clipEnd && m_layers[gEnd].depth > c.depth) ++gEnd;
                // 穿透组作为被剪切层: 子层直接合成到 clipScratch, 跳过组的属性包裹,
                // 否则组会退化成隔离组, 剪切成片丢失
                if (nodeIsPassThroughGroup(KisNodeWSP(c.node))) {
                    compositeLayersRange(clipScratch, cIdx + 1, gEnd, r, excludeIdx);
                } else {
                    ScratchDeviceGuard tmpGuard(this, r);
                    KisPaintDeviceSP tmp = tmpGuard.device();
                    compositeLayersRange(tmp, cIdx + 1, gEnd, r, excludeIdx);

                    KisPainter cPainter(clipScratch);
                    cPainter.setOpacityF(qreal(c.node->opacity()) / 255.0);
                    cPainter.setCompositeOpId(c.node->compositeOpId());
                    QBitArray cFlags = clipScratch->colorSpace()->channelFlags(true, false);
                    KisLayer *cLayer = dynamic_cast<KisLayer *>(c.node);
                    if (cLayer && !cLayer->channelFlags().isEmpty()) {
                        cFlags &= cLayer->channelFlags();
                    }
                    cPainter.setChannelFlags(cFlags);
                    cPainter.bitBlt(r.topLeft(), tmp, r);
                    cPainter.end();
                }
                cIdx = gEnd;
            } else if (c.nodeType == NodeTypeAdjustment) {
                applyAdjustment(clipScratch, c);
                ++cIdx;
            } else if (c.isStrokeLayer || c.nodeType == NodeTypeStroke) {
                ScratchDeviceGuard sGuard(this, r);
                KisPaintDeviceSP sScratch = sGuard.device();
                compositeStrokeLayer(sScratch, c, r);

                KisPainter cPainter(clipScratch);
                cPainter.setOpacityF(qreal(c.node->opacity()) / 255.0);
                cPainter.setCompositeOpId(c.node->compositeOpId());
                QBitArray cFlags = clipScratch->colorSpace()->channelFlags(true, false);
                KisLayer *cLayer = dynamic_cast<KisLayer *>(c.node);
                if (cLayer && !cLayer->channelFlags().isEmpty()) {
                    cFlags &= cLayer->channelFlags();
                }
                cPainter.setChannelFlags(cFlags);
                cPainter.bitBlt(r.topLeft(), sScratch, r);
                cPainter.end();
                ++cIdx;
            } else {
                compositePaintLayer(clipScratch, c, cIdx, true, -1.0, QString());
                ++cIdx;
            }
        }

        // 3. 将组装完成的基底 + 剪切蒙版整体通过基底图层的属性叠入 out
        KisPainter painter(out);
        painter.setOpacityF(qreal(e.node->opacity()) / 255.0);
        painter.setCompositeOpId(e.node->compositeOpId());
        KisLayer *layer = dynamic_cast<KisLayer *>(e.node);
        if (layer && !layer->channelFlags().isEmpty()) {
            painter.setChannelFlags(layer->channelFlags());
        }
        painter.bitBlt(r.topLeft(), clipScratch, r);
        painter.end();

        i = clipEnd;
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
