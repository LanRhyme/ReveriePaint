/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/* ============================================================
 * ReverieCoreMiscTools.cpp - Misc tools: crop, draw text, liquify
 * (part of the ReverieCore module split; shared helpers live in
 * ReverieCoreInternal.h, public API in ReverieCore.h)
 * ============================================================ */
#include "ReverieCoreInternal.h"
#include "kis_liquify_transform_worker.h"
#include <QtConcurrent/QtConcurrentMap>

void ReverieCore::cropCanvas(int x, int y, int w, int h)
{
    KisImageSP image = m_document;
    if (!image || w <= 0 || h <= 0) {
        return;
    }
    const QRect crop(qMax(0, x), qMax(0, y), w, h);
    image->resizeImage(crop);
    // resizeImage goes through KisProcessingApplicator (async stroke) - the
    // document size changes only after the stroke lands, so wait or the
    // size mirrors below read stale values (crop crash / wrong viewport)
    image->waitForDone();
    syncLayersFromImage();
    // Document size changed: keep the viewport/cache mirrors in sync or the
    // render pipeline reads stale dimensions (the crop crash)
    m_docWidth = image->width();
    m_docHeight = image->height();
    m_renderBufW = -1;
    m_renderBufH = -1;
    m_dirtyRect = QRect(0, 0, m_docWidth, m_docHeight);
    m_bitmapInited = false;
    m_lastDirty = QRect();
    recompositeProjection();
    markDirty();
}

void ReverieCore::drawText(int x, int y, const QString &text, qreal fontSize)
{
    // Qt headless font engine is unavailable on Android; text is rendered via Android Canvas and stamped using stampBitmap.
    Q_UNUSED(x);
    Q_UNUSED(y);
    Q_UNUSED(text);
    Q_UNUSED(fontSize);
}

void ReverieCore::stampBitmap(int x, int y, int bw, int bh, const void *rgbaPixels)
{
    KisImageSP image = m_document ? m_document : KisImageSP();
    if (!image || !rgbaPixels || bw <= 0 || bh <= 0) {
        return;
    }
    KisPaintDeviceSP device = currentPaintDevice();
    if (!device) {
        return;
    }

    KisTransaction txn(kundo2_i18n("Stamp"), device);
    const int w = image->width();
    const int h = image->height();
    const QRect stampRect(x, y, bw, bh);
    const QRect docRect(0, 0, w, h);
    const QRect region = stampRect.intersected(docRect);
    if (region.isEmpty()) {
        return;
    }

    const int rw = region.width();
    const int rh = region.height();
    QVector<quint8> dst(size_t(rw) * rh * 4);
    device->readBytes(dst.data(), region.x(), region.y(), rw, rh);

    const quint8 *src = static_cast<const quint8 *>(rgbaPixels);
    for (int row = 0; row < rh; ++row) {
        const int srcRow = (region.y() - y) + row;
        const int srcColStart = region.x() - x;
        const quint8 *srcLine = src + (size_t(srcRow) * bw + srcColStart) * 4;
        quint8 *dstLine = dst.data() + (size_t(row) * rw) * 4;
        for (int col = 0; col < rw; ++col) {
            const quint8 sa = srcLine[col * 4 + 3];
            if (sa == 0) continue;
            if (sa == 255) {
                dstLine[col * 4 + 0] = srcLine[col * 4 + 0];
                dstLine[col * 4 + 1] = srcLine[col * 4 + 1];
                dstLine[col * 4 + 2] = srcLine[col * 4 + 2];
                dstLine[col * 4 + 3] = 255;
            } else {
                const quint8 sr = srcLine[col * 4 + 0];
                const quint8 sg = srcLine[col * 4 + 1];
                const quint8 sb = srcLine[col * 4 + 2];
                const quint8 da = dstLine[col * 4 + 3];
                const quint8 dr = dstLine[col * 4 + 0];
                const quint8 dg = dstLine[col * 4 + 1];
                const quint8 db = dstLine[col * 4 + 2];

                const int outA = sa + da - (sa * da) / 255;
                if (outA > 0) {
                    dstLine[col * 4 + 0] = static_cast<quint8>((sr * sa + dr * da * (255 - sa) / 255) / outA);
                    dstLine[col * 4 + 1] = static_cast<quint8>((sg * sa + dg * da * (255 - sa) / 255) / outA);
                    dstLine[col * 4 + 2] = static_cast<quint8>((sb * sa + db * da * (255 - sa) / 255) / outA);
                    dstLine[col * 4 + 3] = static_cast<quint8>(outA);
                }
            }
        }
    }

    device->writeBytes(dst.constData(), region.x(), region.y(), rw, rh);
    device->setDirty();
    markDirty();
    txn.commit(image->undoAdapter());
    m_redoCount = 0;
}

// Liquify via Krita's own KisLiquifyTransformWorker (libkritaimage, the same
// worker the transform tool's liquify mode drives through kis_liquify_paintop).
// Architecture mirrors Krita's: ONE persistent grid worker accumulates every
// dab's displacement (build-up mode), and each update re-transforms the
// PRISTINE source copy. Warping the already-warped layer per move instead
// (the original approach) resampled the same pixels over and over, which
// pulled seams and blank lines through the content.
//
// MULTI-LAYER: when several layers are targeted (multi-select), every target
// keeps its own pristine copy + grid worker; the SAME displacement ops go to
// every worker (grid ops are content-independent), and the whole gesture is
// committed as ONE composite undo command.
//
// SELECTION: the writeback is constrained by the active selection, so an
// existing selection freezes everything outside it (see liquifyApplyLocked).
//
// PERFORMANCE: run() clears dst and fast-copies the ENTIRE complement of the
// strokes sub-grid. The worker is therefore constructed over a LOCAL rect
// around the brush (src clone is local too), rebased when the brush wanders
// out (flushing the accumulated warp into the layer first), and the
// run+writeback is throttled adaptively and covers only the DELTA region.
namespace
{
// min writeback interval; adapts upward if a single apply is slower
const qint64 LIQUIFY_APPLY_MIN_INTERVAL_MS = 20;
// 累积脏区面积上限 (像素)。大笔刷 (单 dab 影响半径 infl = 3.2×size, 200px 笔刷就是
// ~1300² ≈ 1.7M px) 叠加快速拖动时, 若一直 united() 累积区会退化成"整条轨迹的包围盒",
// 单次 apply 的 warp + 回写 + 投影合成面积无界增长 —— 真机表现就是"笔刷/强度调大后
// 严重卡顿, 极端时闪退"。这里设上限并按下一次 apply 的实测耗时自适应。
const qint64 LIQUIFY_DELTA_BUDGET_MIN_PX = 256 * 1024;     // ≈512×512
const qint64 LIQUIFY_DELTA_BUDGET_MAX_PX = 2 * 1024 * 1024; // ≈1448×1448
const qint64 LIQUIFY_DELTA_BUDGET_DEFAULT_PX = 768 * 1024;
std::atomic<qint64> s_liquifyDeltaBudgetPx{LIQUIFY_DELTA_BUDGET_DEFAULT_PX};
// 多目标 warp 并行开关 (一键回退): 真机上若怀疑并行引起异常, 置 false 重编译
// 即可回到逐层串行, 其余行为逐像素一致。
const bool kLiquifyParallelTargets = true;

// 单次 apply 的四段耗时(ms)与规模, 供性能标尺在真机上回答"液化到底卡在哪一段":
// 形变(Krita 网格 run) / 补洞(内存流量) / 回写(bitBlt+setDirty) / 合成(投影重组合)。
// 纯诊断: 每次 apply 8 个 relaxed 原子写, 不改变任何行为; 读取方是引擎线程(标尺每秒取一次)。
enum LiquifyStat {
    LqTotal = 0,
    LqWarp,
    LqSeed,
    LqBlit,
    LqComposite,
    LqAreaPx,
    LqTargets,
    LqCount,
    LqPrecision,
    LqCells,
    LiquifyStatCount
};
std::atomic<qint64> s_liquifyStats[LiquifyStatCount];

// 诊断: 强制网格精度 (`setprop debug.reverie.lqprec 4|8|16|32`)。用来在真机上量
// "网格单元数 → 耗时 / 形变边缘质量"的曲线, 不需要重编译; 只接受 2 的幂的合法档,
// 其它值一律忽略。0/未设 = 走自动档位(+ 分辨率保底)。
int liquifyForcedPrecision()
{
#if defined(Q_OS_ANDROID)
    char value[PROP_VALUE_MAX] = {0};
    if (__system_property_get("debug.reverie.lqprec", value) > 0 && value[0]) {
        const int v = QByteArray(value).toInt();
        if (v == 4 || v == 8 || v == 16 || v == 32) return v;
    }
#else
    const QByteArray env = qgetenv("REVERIE_LQPREC");
    if (!env.isEmpty()) {
        const int v = env.toInt();
        if (v == 4 || v == 8 || v == 16 || v == 32) return v;
    }
#endif
    return 0;
}

// 前向声明: GPU 开关的定义在下面(预览开关要先问它, 只开 GPU 开关也算开了预览)
bool liquifyPreviewGpuRequested();

// Phase 2A-2 预览开关: `setprop debug.reverie.liquifyPreview 1` 时, 手势期间只更新网格并生成
// 低分辨率预览(不 run()、不写图层、不触投影); 抬笔/rebase 仍走完整 Krita 路径 materialize。
// 默认 0 = 现有行为, 因此这条路径对正式版与普通 debug 使用完全不可见。
bool liquifyPreviewRequested()
{
    // Phase 2B 的 GPU 开关本身就意味着"要预览": 只开 debug.reverie.liquifyPreviewGpu 也生效,
    // 免得真机上必须同时设两个 property 才能试。
    if (liquifyPreviewGpuRequested()) return true;
#if defined(Q_OS_ANDROID)
    char value[PROP_VALUE_MAX] = {0};
    if (__system_property_get("debug.reverie.liquifyPreview", value) > 0 && value[0]) {
        return QByteArray(value).toInt() != 0;
    }
#else
    const QByteArray env = qgetenv("REVERIE_LQ_PREVIEW");
    if (!env.isEmpty()) return env.toInt() != 0;
#endif
    return false;
}

// Phase 2B: `setprop debug.reverie.liquifyPreviewGpu 1` 时, 位移采样交给 Android 侧的
// AGSL RuntimeShader 完成 —— 引擎只交出"未形变的 bounds 裁剪 + 网格", 不生成也不叠加 CPU 预览。
// 默认 0 = 继续走 Phase 2A-2 的引擎侧 CPU 预览(AGSL 不可用时也自动回到这里)。
bool liquifyPreviewGpuRequested()
{
#if defined(Q_OS_ANDROID)
    char value[PROP_VALUE_MAX] = {0};
    if (__system_property_get("debug.reverie.liquifyPreviewGpu", value) > 0 && value[0]) {
        return QByteArray(value).toInt() != 0;
    }
#else
    const QByteArray env = qgetenv("REVERIE_LQ_PREVIEW_GPU");
    if (!env.isEmpty()) return env.toInt() != 0;
#endif
    return false;
}

// 预览图最长边(像素): 只用来验证几何/方向/手感, 不追求画质
const int LIQUIFY_PREVIEW_MAX_EDGE = 192;
// 主机侧绘制的源裁剪上限(像素): 超大笔刷的 bounds 会到几千万像素, 复制+上传会得不偿失 ——
// 超过就放弃源裁剪, 由调用方看到 cropW = 0 后自行回退到引擎侧 CPU 预览。
const qint64 LIQUIFY_HOST_DRAW_MAX_PX = 4 * 1024 * 1024;

void publishLiquifyStats(qint64 totalMs, qint64 warpMs, qint64 seedMs, qint64 blitMs,
                         qint64 compositeMs, qint64 areaPx, qint64 targets,
                         qint64 precision, qint64 cells)
{
    s_liquifyStats[LqTotal].store(totalMs, std::memory_order_relaxed);
    s_liquifyStats[LqWarp].store(warpMs, std::memory_order_relaxed);
    s_liquifyStats[LqSeed].store(seedMs, std::memory_order_relaxed);
    s_liquifyStats[LqBlit].store(blitMs, std::memory_order_relaxed);
    s_liquifyStats[LqComposite].store(compositeMs, std::memory_order_relaxed);
    s_liquifyStats[LqAreaPx].store(areaPx, std::memory_order_relaxed);
    s_liquifyStats[LqTargets].store(targets, std::memory_order_relaxed);
    s_liquifyStats[LqPrecision].store(precision, std::memory_order_relaxed);
    s_liquifyStats[LqCells].store(cells, std::memory_order_relaxed);
    s_liquifyStats[LqCount].fetch_add(1, std::memory_order_relaxed);
}
}

// ---------------------------------------------------------------------------
// Phase 2B: 主机侧(AGSL)绘制的开关判定
//
// 引擎不碰 GPU: 这里只决定"位移采样由谁做"。property 是默认来源, Kotlin 侧在 AGSL 初始化失败时
// 用 setLiquifyPreviewHostDrawMode(0) 覆盖它, 于是自动回落到 Phase 2A-2 的引擎侧 CPU 预览。
// ---------------------------------------------------------------------------
bool ReverieCore::liquifyPreviewHostDraw() const
{
    if (m_liquifyPreviewHostDrawMode >= 0) return m_liquifyPreviewHostDrawMode != 0;
    return liquifyPreviewGpuRequested();
}

void ReverieCore::setLiquifyPreviewHostDrawMode(int mode)
{
    m_liquifyPreviewHostDrawMode = mode < 0 ? -1 : (mode > 0 ? 1 : 0);
}

void ReverieCore::resetLiquifyWorker()
{
    for (LiquifyTarget &t : m_liquifyTargets) {
        delete t.worker;
        t.worker = nullptr;
        t.src.clear();
        t.dst.clear();
    }
    m_liquifyWorkerBounds = QRect();
    m_liquifyPendingDelta = QRect();
    m_liquifyApplyIntervalMs = LIQUIFY_APPLY_MIN_INTERVAL_MS;
    m_liquifyPrecision = 16;
    // 预览态随 worker 一起失效(seq 不归零, 只靠 w = 0 通知调用方"预览已结束")
    m_liquifyPreview = false;
    m_liquifyPreviewW = 0;
    m_liquifyPreviewH = 0;
    // 释放预览缓冲: bounds 可能上百万像素, 不 squeeze 就会把容量留到下一次手势
    m_liquifyPreviewSrc = QVector<quint8>();
    m_liquifyPreviewOut = QVector<quint8>();
    m_liquifyPreviewSrcRgba = QVector<quint8>();
    s_liquifyDeltaBudgetPx.store(LIQUIFY_DELTA_BUDGET_DEFAULT_PX);
}

// Re-run the accumulated warp and write back only the DELTA region: older
// grid displacements never change afterwards (build-up), so output pixels
// only change within the newest dabs' influence. Writing back the whole
// accumulated strokes region instead made every apply cost (and recomposite)
// grow linearly with drag length.
// 回写前补洞: worker 的 run() 只覆盖它实际遍历到的瓦片, 其余保持 clear() 后的透明,
// 而回写用 COMPOSITE_COPY 会把这些透明像素**擦进图层** —— 透明处露出画布白底, 就是
// 用户看到的"液化白线 + 白色矩形轮廓"(沿瓦片边界与回写区边界)。这里把 area 内 alpha=0
// 的目标像素补回 src 原内容: 已映射的像素逐像素不变, 只消除"假透明"。
// thread_local 复用缓冲: 拖动中每几十毫秒调用一次, 不允许每帧分配。
// 注: 参数按值传 QSharedPointer 而不是 const 引用 —— const 引用下 QSharedPointer 的
// operator-> 给出的是 const 指针, 无法调用非 const 的 writeBytes (KisPaintDevice 如此)。
void seedTransparentFromSource(KisPaintDeviceSP dst, KisPaintDeviceSP src,
                               const QRect &area)
{
    if (!dst || !src || area.isEmpty()) return;
    const KoColorSpace *cs = dst->colorSpace();
    if (!cs) return;
    const int ps = cs->pixelSize();
    if (ps <= 0) return;
    // 文档色彩空间为 8bit RGBA/BGRA ⇒ alpha 在 ps 范围内的某个字节位置;
    // 取不到有效位置就整体放弃 (宁可漏补也不写坏像素)
    const int alphaPos = int(cs->alphaPos());
    if (alphaPos < 0 || alphaPos >= ps) return;

    const int w = area.width();
    const int h = area.height();
    const size_t count = size_t(w) * size_t(h);
    const size_t bytes = count * size_t(ps);
    thread_local QByteArray dstBuf;
    thread_local QByteArray srcBuf;
    if (size_t(dstBuf.size()) < bytes) {
        dstBuf.resize(int(bytes));
        srcBuf.resize(int(bytes));
    }
    quint8 *d = reinterpret_cast<quint8 *>(dstBuf.data());
    quint8 *s = reinterpret_cast<quint8 *>(srcBuf.data());
    dst->readBytes(d, area.x(), area.y(), w, h);
    src->readBytes(s, area.x(), area.y(), w, h);

    bool patched = false;
    for (size_t i = 0; i < count; ++i) {
        const size_t off = i * size_t(ps);
        if (d[off + size_t(alphaPos)] == 0) {
            memcpy(d + off, s + off, size_t(ps));
            patched = true;
        }
    }
    if (patched) {
        dst->writeBytes(d, area.x(), area.y(), w, h);
    }
}

void ReverieCore::liquifyApplyLocked(const QRect &deltaRect)
{
    if (m_liquifyTargets.isEmpty() || !m_document) {
        return;
    }
    const qint64 t0 = QDateTime::currentMSecsSinceEpoch();
    QRect dirtyUnion;
    // 分段计时 (纯诊断, 见 s_liquifyStats)
    qint64 tWarpMs = 0;
    qint64 tSeedMs = 0;
    qint64 tBlitMs = 0;
    qint64 tCompositeMs = 0;
    // An active selection is a freeze mask: the grid warp itself still runs
    // over the whole worker bounds (pixels may be pulled IN from outside,
    // like Krita's transform tool), but only selected pixels are written
    // back - the same KisPainter-level constraint every other paint path
    // here uses. The exact rect additionally keeps the writeback (and the
    // recomposite it triggers) off untouched areas.
    QRect clipRect(0, 0, m_document->width(), m_document->height());
    if (m_selection) {
        clipRect &= m_selection->selectedExactRect();
    }
    // 阶段 1: 各目标的 clear + grid warp。
    // 目标之间完全独立 (各自的 src/dst/worker), 却原先只用一个核 —— 多图层液化时
    // 单次 apply 的墙钟时间随图层数线性增长, 拖长一次拖动就是成倍的卡顿与发热。
    // 并行只覆盖这步纯计算; 写回必须留在渲染线程串行 (KisPainter + 脏区标记)。
    const qint64 tw0 = QDateTime::currentMSecsSinceEpoch();
    QVector<LiquifyTarget *> warped;
    warped.reserve(m_liquifyTargets.size());
    for (LiquifyTarget &t : m_liquifyTargets) {
        if (t.worker) warped.append(&t);
    }
    if (warped.size() > 1 && kLiquifyParallelTargets) {
        // 引擎专用池 (不借全局池, 避免与 Krita 内部并行争池); 元素是裸指针数组,
        // 并行期间不触碰 m_liquifyTargets 的容器本身
        QtConcurrent::blockingMap(reverieBackgroundPool(), warped,
                                  [](LiquifyTarget *t) {
                                      // run() 内部本来就会 dst->clear() (见
                                      // KisLiquifyTransformWorker::run), 这里不再重复清一遍 ——
                                      // 每次 apply 每目标少清一整块 bounds (200px 时 0.58M px)
                                      t->worker->run(t->src, t->dst);
                                  });
    } else {
        for (LiquifyTarget *t : warped) {
            t->worker->run(t->src, t->dst);
        }
    }
    tWarpMs = QDateTime::currentMSecsSinceEpoch() - tw0;

    // 阶段 2: 串行写回 (选区/透明像素锁/脏区标记语义与改动前完全一致)
    for (LiquifyTarget &t : m_liquifyTargets) {
        if (!t.worker) continue;
        const QRect area = deltaRect.intersected(t.bounds).intersected(clipRect);
        if (!area.isEmpty()) {
            // 先补掉 warp 未覆盖的透明像素, 否则 COMPOSITE_COPY 会把它们擦进图层
            const qint64 ts0 = QDateTime::currentMSecsSinceEpoch();
            seedTransparentFromSource(t.dst, t.src, area);
            tSeedMs += QDateTime::currentMSecsSinceEpoch() - ts0;
            const qint64 tb0 = QDateTime::currentMSecsSinceEpoch();
            KisPainter p(t.device);
            p.setCompositeOpId(COMPOSITE_COPY);
            if (m_selection) {
                p.setSelection(m_selection);
            }
            // Alpha-locked layer keeps its silhouette: only the colour
            // channels follow the warp (same flags the stroke path uses)
            p.setChannelFlags(t.layer && t.layer->alphaLocked() ? t.layer->channelLockFlags()
                                                                : QBitArray());
            p.bitBlt(area.topLeft(), t.dst, area);
            p.end();
            t.device->setDirty(area);
            tBlitMs += QDateTime::currentMSecsSinceEpoch() - tb0;
            dirtyUnion = dirtyUnion.isNull() ? area : dirtyUnion.united(area);
        }
    }
    if (!dirtyUnion.isEmpty()) {
        const qint64 tc0 = QDateTime::currentMSecsSinceEpoch();
        markRegionDirty(dirtyUnion);
        // 立刻把投影的这块区域同步合成掉: Krita 后台调度器未必已经处理刚标记的脏区,
        // 渲染路径此时读投影就会拿到半更新像素 (白线/撕裂的第二个成因)。放在这里做,
        // 重活就落在"按 20~64ms 节流的 apply"上, 而不是每个输入事件一次的渲染路径上 ——
        // 大笔刷下这是数量级的差别 (200px 笔刷单次脏区可达数百万像素)。
        if (!m_soloedNode && m_document) {
            KisPaintDeviceSP proj = m_document->projection();
            const QRect c = dirtyUnion.intersected(
                QRect(0, 0, m_document->width(), m_document->height()));
            if (proj && !c.isEmpty()) {
                proj->clear(c);
                compositeLayersRange(proj, 0, m_layers.size(), c);
            }
        }
        tCompositeMs = QDateTime::currentMSecsSinceEpoch() - tc0;
    }
    m_liquifyLastApplyMs = QDateTime::currentMSecsSinceEpoch();
    const qint64 elapsed = m_liquifyLastApplyMs - t0;
    // Adaptive pacing: if one apply exceeds the frame budget, back off (up
    // to ~15fps) so the render thread always keeps serving input
    // 节流上限回到 64ms: 放宽到 120ms 会让一次 apply 前累积更多 dab, 网格形变更剧烈、
    // 更容易走 Krita 内部的退化路径 (真机 68px 闪退)。宁可多做一些 apply 也不冒险。
    m_liquifyApplyIntervalMs =
        qBound<qint64>(LIQUIFY_APPLY_MIN_INTERVAL_MS,
                       qMax<qint64>(elapsed * 2, LIQUIFY_APPLY_MIN_INTERVAL_MS),
                       64);
    // 脏区预算同步自适应: 单次 apply 超帧预算就收小累积区, 很快时再逐步放回。
    // 目标始终是"一次 apply 的 warp + 回写 + 同步合成"贴着帧预算, 而不是随拖动越来越大。
    {
        const qint64 budget = s_liquifyDeltaBudgetPx.load();
        if (elapsed > 32) {
            s_liquifyDeltaBudgetPx.store(qMax(LIQUIFY_DELTA_BUDGET_MIN_PX, budget / 2));
        } else if (elapsed < 12) {
            s_liquifyDeltaBudgetPx.store(qMin(LIQUIFY_DELTA_BUDGET_MAX_PX, budget * 2));
        }
    }
    const qint64 lqPrec = qMax(1, m_liquifyPrecision);
    const qint64 lqCells = qint64(m_liquifyWorkerBounds.width() / lqPrec + 2) *
                           qint64(m_liquifyWorkerBounds.height() / lqPrec + 2);
    publishLiquifyStats(elapsed, tWarpMs, tSeedMs, tBlitMs, tCompositeMs,
                        qint64(dirtyUnion.width()) * qint64(dirtyUnion.height()),
                        m_liquifyTargets.size(), m_liquifyPrecision, lqCells);
    RPC_TRACE("liquify apply total=%dms (warp=%d seed=%d blit=%d comp=%d) targets=%d "
              "area=%dx%d bounds=%dx%d int=%d",
              int(elapsed), int(tWarpMs), int(tSeedMs), int(tBlitMs), int(tCompositeMs),
              int(m_liquifyTargets.size()),
              dirtyUnion.width(), dirtyUnion.height(),
              m_liquifyWorkerBounds.width(), m_liquifyWorkerBounds.height(),
              int(m_liquifyApplyIntervalMs));
}

void ReverieCore::liquifyBegin(const QVector<int> &layers)
{
    if (!m_document || m_liquifyTxnActive) {
        return;
    }
    resetLiquifyWorker();
    m_liquifyTargets.clear();

    // Resolve the target set: explicit list (multi-select), else the
    // current layer. Duplicate and out-of-range entries are skipped, group
    // layers too (no direct paint device).
    QVector<int> targets = layers;
    if (targets.isEmpty()) {
        targets << m_currentLayer;
    }
    QVector<KisPaintDeviceSP> seen;
    for (int idx : targets) {
        if (idx < 0 || idx >= m_layers.size()) continue;
        KisPaintDeviceSP dev = layerPaintDeviceFor(m_layers[idx]);
        if (!dev) continue;
        bool dup = false;
        for (const KisPaintDeviceSP &d : seen) {
            if (d.data() == dev.data()) {
                dup = true;
                break;
            }
        }
        if (dup) continue;
        seen.append(dev);
        LiquifyTarget t;
        t.device = dev;
        t.layer = dynamic_cast<KisPaintLayer *>(m_layers[idx].node);
        t.txn = new KisTransaction(kundo2_i18n("Liquify"), dev, nullptr, -1, nullptr);
        m_liquifyTargets.append(t);
    }
    RPC_TRACE("liquifyBegin req=%d targets=%d cur=%d layerN=%d active=%d",
              int(layers.size()), int(m_liquifyTargets.size()),
              m_currentLayer, int(m_layers.size()), int(m_liquifyTxnActive));
    if (m_liquifyTargets.isEmpty()) {
        return;
    }
    m_liquifyTxnActive = true;
    // The grid workers need the dab position - they are created lazily by
    // the first liquify() call (and rebased whenever the brush wanders out)
}

void ReverieCore::liquifyEnd()
{
    // 预览模式: 拖动期间刻意没写文档, 网格里累积的位移要在抬笔时按整个 worker bounds 一次性
    // materialize —— 这是"预览近似、提交精确"的收口点(缺失它就会丢形变)。
    const bool previewWasOn = m_liquifyPreview;
    const QRect previewBounds = m_liquifyWorkerBounds;
    if (previewWasOn && !m_liquifyTargets.isEmpty() && m_liquifyTargets[0].worker) {
        m_liquifyPendingDelta = m_liquifyWorkerBounds;
    }
    // Flush any grid displacement that is still under the apply throttle
    if (!m_liquifyTargets.isEmpty() && !m_liquifyPendingDelta.isNull()) {
        liquifyApplyLocked(m_liquifyPendingDelta);
        m_liquifyPendingDelta = QRect();
    }
    // 预览收口: 摘掉 overlay, 并让这块区域按真实文档重读一遍 —— 渲染循环只在"脏区"里重读,
    // 若不标脏, 预览像素会永远留在显示缓冲里(看起来像形变没提交, 其实只是没刷新)。
    if (previewWasOn) {
        m_liquifyPreview = false;
        m_liquifyPreviewOut = QVector<quint8>();
        if (m_document && !previewBounds.isEmpty()) {
            markRegionDirty(previewBounds.intersected(QRect(0, 0, m_document->width(), m_document->height())));
        }
    }
    // Collect the per-target transactions as ONE composite undo step
    // (Krita's adapter pushes every addCommand separately)
    QVector<KUndo2Command *> children;
    for (LiquifyTarget &t : m_liquifyTargets) {
        if (t.txn) {
            children << t.txn->endAndTake();
            delete t.txn;
            t.txn = nullptr;
        }
    }
    if (!children.isEmpty()) {
        if (m_undoCaptureEnabled && m_document) {
            m_document->undoAdapter()->addCommand(
                new ReverieCompositeCommand(kundo2_i18n("Liquify"), children));
            m_redoCount = 0;
        } else {
            // Replay mode: keep the pixels, drop the undo command
            qDeleteAll(children);
        }
    }
    m_liquifyTargets.clear();
    m_liquifyTxnActive = false;
    resetLiquifyWorker();
}

void ReverieCore::liquifyCancel()
{
    bool any = false;
    for (LiquifyTarget &t : m_liquifyTargets) {
        if (t.txn) {
            // Roll the whole drag back like touchStrokeCancel does for strokes
            t.txn->revert();
            delete t.txn;
            t.txn = nullptr;
            if (t.device) t.device->setDirty();
            any = true;
        }
    }
    if (any && m_document) {
        recompositeProjection();
        markDirty();
    }
    m_liquifyTargets.clear();
    m_liquifyTxnActive = false;
    resetLiquifyWorker();
}

void ReverieCore::liquifyStats(qint64 *out)
{
    if (!out) return;
    for (int i = 0; i < LiquifyStatCount; ++i) {
        out[i] = s_liquifyStats[i].load(std::memory_order_relaxed);
    }
}

// 导出当前液化网格状态(只读)。数据来源是 worker 自己的 originalPoints/transformedPoints ——
// 也就是 run() 做分段线性 warping 用的那一份网格, 所以"预览用的几何"与"最终提交的几何"同源。
// 注意 transformedPoints() 是非 const 访问器, 因此本函数不能是 const。
ReverieCore::LiquifyGridExport ReverieCore::liquifyGridExport()
{
    LiquifyGridExport out;
    if (m_liquifyTargets.isEmpty()) return out;
    KisLiquifyTransformWorker *w = m_liquifyTargets[0].worker;
    if (!w) return out;

    const QSize gs = w->gridSize();
    const QVector<QPointF> &orig = w->originalPoints();
    QVector<QPointF> &dst = w->transformedPoints();
    const int n = qMin(orig.size(), dst.size());
    // 数据不完整就整体放弃: 宁可没有预览, 也不给上层一份错位的位移场
    if (gs.width() <= 0 || gs.height() <= 0 || n <= 0 || n != gs.width() * gs.height()) {
        return out;
    }

    out.bounds = m_liquifyWorkerBounds;
    out.columns = gs.width();
    out.rows = gs.height();
    out.precision = m_liquifyPrecision;
    out.count = n;
    out.original.reserve(n);
    out.offset.reserve(n);
    for (int i = 0; i < n; ++i) {
        out.original.append(orig[i]);
        out.offset.append(dst[i] - orig[i]);
    }
    return out;
}

// ---------------------------------------------------------------------------
// Phase 2A-2: 交互态低分辨率预览 (debug only, `setprop debug.reverie.liquifyPreview 1`)
//
// 目的只有一个: 证明"把 worker 的网格状态拿出来, 在显示层独立预览"这条路成立 —— 手势期间
// 不 run()、不写图层、不触投影, 也能得到几何与方向正确的形变画面。**不追求画质与速度**。
//
// 预览用反向采样: dst(p) = src(p - offset(p)), 与 Krita 的前向多边形填充在纯平移下等价;
// 位移场由网格点双线性插值得到(网格点就是 run() 用的同一批, 所以几何一致, 只有采样核是近似)。
// ---------------------------------------------------------------------------
void ReverieCore::liquifyPreviewCaptureLocked()
{
    if (m_liquifyTargets.isEmpty()) {
        m_liquifyPreview = false;
        return;
    }
    KisPaintDeviceSP src = m_liquifyTargets[0].src;
    const QRect b = m_liquifyWorkerBounds;
    if (!src || b.isEmpty() || b.width() <= 0 || b.height() <= 0) {
        m_liquifyPreview = false;
        return;
    }
    const KoColorSpace *cs = src->colorSpace();
    const int ps = cs ? cs->pixelSize() : 0;
    // 只支持 8bit BGRA 文档(4 字节/像素): 采样与通道交换都按这个假设写, 其它一律放弃预览
    // (ps != 4 时按 4 通道读会越过缓冲末尾 —— 这是必须挡住的一条边界)。
    if (ps != 4) {
        m_liquifyPreview = false;
        return;
    }
    m_liquifyPreviewSrc.resize(b.width() * b.height() * ps);
    src->readBytes(m_liquifyPreviewSrc.data(), b.x(), b.y(), b.width(), b.height());

    // Phase 2B: 主机侧绘制要的是"未形变"的源裁剪(RGBA8888, 1 像素 = 1 文档像素), 它在 rebase 时
    // 才需要重建, 因此整段手势只上传一次纹理。超过预算就放弃, 由调用方回退到 CPU 预览。
    const qint64 px = qint64(b.width()) * qint64(b.height());
    if (liquifyPreviewHostDraw() && px <= LIQUIFY_HOST_DRAW_MAX_PX) {
        m_liquifyPreviewSrcRgba.resize(int(px) * 4);
        const quint8 *s = m_liquifyPreviewSrc.constData();
        quint8 *d = m_liquifyPreviewSrcRgba.data();
        for (qint64 i = 0; i < px; ++i) {
            d[i * 4 + 0] = s[i * 4 + 2]; // BGRA -> RGBA (与 1:1 渲染路径同一假设)
            d[i * 4 + 1] = s[i * 4 + 1];
            d[i * 4 + 2] = s[i * 4 + 0];
            d[i * 4 + 3] = s[i * 4 + 3];
        }
        // 主机侧绘制时引擎不生成预览像素: 明确把 CPU 预览尺寸归零, 免得两套叠加同时生效
        m_liquifyPreviewW = 0;
        m_liquifyPreviewH = 0;
        m_liquifyPreviewOut = QVector<quint8>();
        return;
    }
    m_liquifyPreviewSrcRgba = QVector<quint8>();

    const int maxEdge = qMax(b.width(), b.height());
    const qreal k = maxEdge > LIQUIFY_PREVIEW_MAX_EDGE
                        ? qreal(LIQUIFY_PREVIEW_MAX_EDGE) / qreal(maxEdge)
                        : 1.0;
    m_liquifyPreviewW = qMax(1, qRound(b.width() * k));
    m_liquifyPreviewH = qMax(1, qRound(b.height() * k));
    m_liquifyPreviewOut.resize(m_liquifyPreviewW * m_liquifyPreviewH * 4);
    m_liquifyPreviewOut.fill(0);
}

void ReverieCore::liquifyPreviewBuildLocked()
{
    if (!m_liquifyPreview || m_liquifyTargets.isEmpty()) return;
    KisLiquifyTransformWorker *w = m_liquifyTargets[0].worker;
    if (!w) return;

    const QSize gs = w->gridSize();
    const QVector<QPointF> &orig = w->originalPoints();
    QVector<QPointF> &dst = w->transformedPoints();
    const int n = qMin(orig.size(), dst.size());
    const QRect b = m_liquifyWorkerBounds;
    const int cols = gs.width();
    const int rows = gs.height();
    const int pw = m_liquifyPreviewW;
    const int ph = m_liquifyPreviewH;
    if (n <= 0 || cols < 2 || rows < 2 || n != cols * rows || pw <= 0 || ph <= 0) return;
    if (m_liquifyPreviewSrc.isEmpty() || m_liquifyPreviewOut.isEmpty()) return;

    const int ps = m_liquifyPreviewSrc.size() / qMax(1, b.width() * b.height());
    if (ps <= 0) return;
    const int prec = qMax(1, m_liquifyPrecision);

    // 网格点 -> 位移: 权重按 original 坐标算, 这样末尾被吸附到边界的格子也不会错位
    const auto gridOffset = [&](qreal x, qreal y, qreal *ox, qreal *oy) {
        int c0 = int((x - b.left()) / prec);
        int r0 = int((y - b.top()) / prec);
        c0 = qBound(0, c0, cols - 2);
        r0 = qBound(0, r0, rows - 2);
        const int c1 = c0 + 1;
        const int r1 = r0 + 1;
        const QPointF &p00 = orig[r0 * cols + c0];
        const QPointF &p10 = orig[r0 * cols + c1];
        const QPointF &p01 = orig[r1 * cols + c0];
        const QPointF &p11 = orig[r1 * cols + c1];
        qreal fx = (x - p00.x()) / qMax<qreal>(1.0, p10.x() - p00.x());
        qreal fy = (y - p00.y()) / qMax<qreal>(1.0, p01.y() - p00.y());
        fx = qBound<qreal>(0.0, fx, 1.0);
        fy = qBound<qreal>(0.0, fy, 1.0);
        const QPointF d00 = dst[r0 * cols + c0] - p00;
        const QPointF d10 = dst[r0 * cols + c1] - p10;
        const QPointF d01 = dst[r1 * cols + c0] - p01;
        const QPointF d11 = dst[r1 * cols + c1] - p11;
        const qreal ax = d00.x() * (1 - fx) + d10.x() * fx;
        const qreal bx = d01.x() * (1 - fx) + d11.x() * fx;
        const qreal ay = d00.y() * (1 - fx) + d10.y() * fx;
        const qreal by = d01.y() * (1 - fx) + d11.y() * fx;
        *ox = ax * (1 - fy) + bx * fy;
        *oy = ay * (1 - fy) + by * fy;
    };

    const quint8 *src = m_liquifyPreviewSrc.constData();
    quint8 *out = m_liquifyPreviewOut.data();
    const qreal stepX = qreal(b.width()) / pw;
    const qreal stepY = qreal(b.height()) / ph;
    const int bw = b.width();
    const int bh = b.height();

    for (int py = 0; py < ph; ++py) {
        const qreal docY = b.top() + (py + 0.5) * stepY;
        for (int px = 0; px < pw; ++px) {
            const qreal docX = b.left() + (px + 0.5) * stepX;
            qreal ox = 0.0;
            qreal oy = 0.0;
            gridOffset(docX, docY, &ox, &oy);
            // 反向采样: 源位置 = 目标位置 - 位移(与 Krita 的前向搬运在纯平移下等价)
            const qreal u = qBound<qreal>(0.0, docX - ox - b.left(), bw - 1.0);
            const qreal v = qBound<qreal>(0.0, docY - oy - b.top(), bh - 1.0);
            const int x0 = int(u);
            const int y0 = int(v);
            const int x1 = qMin(x0 + 1, bw - 1);
            const int y1 = qMin(y0 + 1, bh - 1);
            const qreal fx = u - x0;
            const qreal fy = v - y0;
            const quint8 *p00 = src + (size_t(y0) * bw + x0) * ps;
            const quint8 *p10 = src + (size_t(y0) * bw + x1) * ps;
            const quint8 *p01 = src + (size_t(y1) * bw + x0) * ps;
            const quint8 *p11 = src + (size_t(y1) * bw + x1) * ps;
            quint8 *o = out + (size_t(py) * pw + px) * 4;
            // 文档色彩空间是 8bit BGRA(与 1:1 渲染路径同一假设): 采样后交换 R/B 输出 RGBA
            for (int ch = 0; ch < 4; ++ch) {
                const qreal top = p00[ch] * (1 - fx) + p10[ch] * fx;
                const qreal bot = p01[ch] * (1 - fx) + p11[ch] * fx;
                const quint8 val = quint8(qBound<qreal>(0.0, top * (1 - fy) + bot * fy, 255.0));
                if (ch == 0) {
                    o[2] = val;
                } else if (ch == 2) {
                    o[0] = val;
                } else {
                    o[ch] = val;
                }
            }
        }
    }
    // 预览区必须进入脏区: renderToBuffer 的增量路径只重读脏区, 不标脏就永远叠不上去。
    // (1:1 路径的整帧分支与缓冲重置分支本来就全量重读, 这里只是把增量分支补齐。)
    if (m_document) {
        m_dirtyRect = m_dirtyRect.isNull()
                ? b.intersected(QRect(0, 0, m_document->width(), m_document->height()))
                : m_dirtyRect.united(b.intersected(QRect(0, 0, m_document->width(), m_document->height())));
    }
    ++m_liquifyPreviewSeq;
}

void ReverieCore::liquifyPreviewMeta(int *out)
{
    if (!out) return;
    out[0] = (m_liquifyPreview && !m_liquifyPreviewOut.isEmpty()) ? m_liquifyPreviewW : 0;
    out[1] = m_liquifyPreviewH;
    out[2] = m_liquifyWorkerBounds.x();
    out[3] = m_liquifyWorkerBounds.y();
    out[4] = m_liquifyWorkerBounds.width();
    out[5] = m_liquifyWorkerBounds.height();
    out[6] = int(m_liquifyPreviewSeq);
}

void ReverieCore::liquifyPreviewPixels(quint8 *out)
{
    if (!out || m_liquifyPreviewOut.isEmpty()) return;
    memcpy(out, m_liquifyPreviewOut.constData(), size_t(m_liquifyPreviewOut.size()));
}

void ReverieCore::liquifyPreviewSourceMeta(int *out)
{
    if (!out) return;
    const bool has = m_liquifyPreview && !m_liquifyPreviewSrcRgba.isEmpty();
    out[0] = has ? m_liquifyWorkerBounds.width() : 0;
    out[1] = has ? m_liquifyWorkerBounds.height() : 0;
    out[2] = m_liquifyWorkerBounds.x();
    out[3] = m_liquifyWorkerBounds.y();
    out[4] = m_liquifyWorkerBounds.width();
    out[5] = m_liquifyWorkerBounds.height();
    out[6] = int(m_liquifyPreviewSeq);
}

void ReverieCore::liquifyPreviewSourcePixels(quint8 *out)
{
    if (!out || m_liquifyPreviewSrcRgba.isEmpty()) return;
    memcpy(out, m_liquifyPreviewSrcRgba.constData(), size_t(m_liquifyPreviewSrcRgba.size()));
}

// 把低分辨率预览混合进显示缓冲。buffer 是 RGBA8888 的整帧/整视口缓冲(w×h), 文档坐标 →
// 缓冲像素坐标按 sx = w/docW, sy = h/docH(1:1 路径下两者都是 1)。written 是本帧刚写过的
// 缓冲区域, 只有它与预览矩形的交集需要处理, 所以增量刷新时叠加成本与脏区同阶。
//
// 语义是"近似"而非"等价": 预览像素本身已经做过反向采样, 这里直接 source-over 叠上去,
// 与最终 materialize 的前向多边形填充并不逐像素一致。这条路径的用途只是验证交互几何与
// 手感 —— 抬笔后仍由 Krita 精确重算并覆盖, 因此近似是可接受的。
void ReverieCore::blendLiquifyPreview(quint8 *buffer, int w, int h, const QRect &written)
{
    if (!buffer || w <= 0 || h <= 0 || m_liquifyPreviewOut.isEmpty()) return;
    const int pw = m_liquifyPreviewW;
    const int ph = m_liquifyPreviewH;
    const QRect b = m_liquifyWorkerBounds;
    if (pw <= 0 || ph <= 0 || b.isEmpty()) return;

    const qreal sx = qreal(w) / qreal(qMax(1, m_docWidth));
    const qreal sy = qreal(h) / qreal(qMax(1, m_docHeight));
    // 两端都走 round(edge*scale), 与缩放路径的 mapX/mapY 同一规则, 避免相邻帧漂移 1px
    const int bx0 = qBound(0, qRound(b.left() * sx), w);
    const int by0 = qBound(0, qRound(b.top() * sy), h);
    const int bx1 = qBound(0, qRound((b.left() + b.width()) * sx), w);
    const int by1 = qBound(0, qRound((b.top() + b.height()) * sy), h);
    const QRect mapRect(bx0, by0, bx1 - bx0, by1 - by0);
    const QRect area = mapRect.intersected(written).intersected(QRect(0, 0, w, h));
    if (area.isEmpty()) return;

    const qreal invSpanX = qreal(pw) / qMax<qreal>(1.0, qreal(mapRect.width()));
    const qreal invSpanY = qreal(ph) / qMax<qreal>(1.0, qreal(mapRect.height()));
    const quint8 *prev = m_liquifyPreviewOut.constData();
    for (int y = area.top(); y <= area.bottom(); ++y) {
        const int py = qBound(0, int((y - mapRect.top()) * invSpanY), ph - 1);
        const quint8 *prow = prev + size_t(py) * size_t(pw) * 4;
        quint8 *drow = buffer + size_t(y) * size_t(w) * 4 + size_t(area.left()) * 4;
        for (int x = area.left(); x <= area.right(); ++x, drow += 4) {
            const int px = qBound(0, int((x - mapRect.left()) * invSpanX), pw - 1);
            const quint8 *s = prow + size_t(px) * 4;
            const int sa = s[3];
            if (sa == 0) continue; // 预览完全透明 = 无内容, 保留缓冲原像素
            if (sa == 255) {
                drow[0] = s[0];
                drow[1] = s[1];
                drow[2] = s[2];
                drow[3] = 255;
                continue;
            }
            // source-over (straight alpha, 与 1:1 路径同为非预乘)
            const int ia = 255 - sa;
            drow[0] = quint8((s[0] * sa + drow[0] * ia + 127) / 255);
            drow[1] = quint8((s[1] * sa + drow[1] * ia + 127) / 255);
            drow[2] = quint8((s[2] * sa + drow[2] * ia + 127) / 255);
            drow[3] = quint8(qMin(255, sa + (drow[3] * ia + 127) / 255));
        }
    }
}

void ReverieCore::liquify(int fx, int fy, int tx, int ty, qreal strength, int mode)
{
    KisImageSP image = m_document ? m_document : KisImageSP();
    if (!image) return;

    // Standalone calls (old recordings replayed without begin/end brackets)
    // get an implicit one-shot bracket around this dab. NOTE: the workers
    // are created lazily by the first dab, so worker state must NOT be part
    // of this test - doing so made EVERY dab of a bracketed drag end its own
    // transaction (frame-by-frame undo + per-move commit cost)
    const bool ownBracket = !m_liquifyTxnActive;
    if (!m_liquifyTxnActive) {
        liquifyBegin();
        if (!m_liquifyTxnActive) {
            return;
        }
    }

    const qreal s = qBound<qreal>(0.05, strength, 2.0);
    // KisLiquifyPaintop passes the brush diameter as sigma (gaussian falloff)
    const qreal size = qMax<qreal>(8.0, m_liquifyBrushSize);

    // Local grid: rebase when the brush is about to leave the inner margin
    // (the worker's run() copies the whole bounds complement, so the bounds
    // must stay local or every dab costs a full-bounds copy)
    bool needRebase = m_liquifyTargets.isEmpty() || m_liquifyTargets[0].worker == nullptr;
    if (!needRebase) {
        const int margin = qMax(40, qRound(size * 0.7));
        const QRect inner = m_liquifyWorkerBounds.adjusted(margin, margin, -margin, -margin);
        if (!inner.contains(QPoint(tx, ty))) {
            needRebase = true;
        }
    }
    if (needRebase) {
        if (m_liquifyTargets[0].worker) {
            liquifyApplyLocked(m_liquifyPendingDelta.isNull() ? m_liquifyWorkerBounds
                                                              : m_liquifyPendingDelta);
            m_liquifyPendingDelta = QRect();
        }
        // Tight bounds: only the brush neighbourhood + the gaussian influence
        // radius (3 sigma) must fit; anything larger only adds copy cost
        // 影响半径保持 1.9σ: 试过压到 1.6σ 省面积, 但 bounds 变小后强位移会更频繁地
        // 需要 bounds 之外的像素 / 让网格退化, 真机上反而更不稳 (68px 就闪退)。稳妥优先。
        const int R = qMax<int>(192, qRound(size * 1.9));
        QRect bounds(tx - R, ty - R, 2 * R, 2 * R);
        bounds = bounds.intersected(QRect(0, 0, image->width(), image->height()));
        if (bounds.isEmpty()) {
            if (ownBracket) liquifyEnd();
            return;
        }
        m_liquifyWorkerBounds = bounds;
        for (LiquifyTarget &t : m_liquifyTargets) {
            t.src = new KisPaintDevice(t.device->colorSpace());
            t.src->makeCloneFrom(t.device, bounds);
            t.dst = new KisPaintDevice(t.device->colorSpace());
            delete t.worker;
            // pixelPrecision is the grid cell size: the warp is piecewise
            // linear WITHIN a cell, so the gaussian bump must be resolved by
            // several cells or its curvature degenerates into flat facets -
            // visible as jagged/stair-stepped edges on the warped content.
            // A fixed 16 resolved a 20px brush with barely one cell. Scale
            // the grid with the brush instead (~8 cells across the radius),
            // clamped so tiny brushes stay affordable and huge brushes keep
            // the coarse grid the throttling budget was tuned for.
            // 网格精度必须是 **2 的幂**(判据不是"16 的倍数"): Krita 用
            // `alignmentMask = ~(pixelPrecision - 1)` 对网格边界做位掩码对齐
            // (kis_grid_interpolation_tools.h:33 calcGridDimension), 非 2 的幂算出
            // 的网格尺寸与网格点容器容量不一致 ⇒ 越界访问 (真机表现就是闪退)。
            // 因此合法档为 {4,8,16,32,...}; 上游 qBound(4, size/8, 16) 对 68px 得 9、
            // 78px 得 10 —— 都是非法值 (68px 必崩, 本分支修掉)。
            // 大笔刷用 32: run() 的成本里"每个网格单元一次多边形填充 + 瓦片读写"占大头
            // (单元数 = (bounds/精度)²), 760² 的 bounds 从 47×47=2209 个单元降到 24×24=576,
            // 约 4 倍提速。
            const int rawPrecision = qBound<int>(4, qRound(size / 8.0), 32);
            int precision = rawPrecision > 16 ? 32
                            : (rawPrecision > 12 ? 16 : (rawPrecision > 6 ? 8 : 4));
            // 分辨率保底 (对齐上游注释里"每半径约 8 个单元"的设计意图): 单元内位移是分段
            // 线性的, 单元过粗时高斯曲率会被折成平面 ⇒ 形变边缘出现硬折/台阶。32 分钟值在
            // 132~134px 上只剩 251/32 ≈ 7.8 单元/半径, 显式下压到满足 ≥8 单元的档: 32→16
            // 只发生在这一窄区间, ≥135px 仍是 32 (即那 4 倍提速的档)。
            const int resolutionFloor = qMax<int>(16, R / 8);
            while (precision > resolutionFloor && precision > 4) {
                precision /= 2;
            }
            // 诊断覆盖: setprop debug.reverie.lqprec <4|8|16|32> 强制档位(跳过上面的保底), 用来在
            // 真机上量"单元数 → 耗时/形变边缘"的曲线。
            const int forcedPrecision = liquifyForcedPrecision();
            if (forcedPrecision > 0) {
                precision = forcedPrecision;
            }
            m_liquifyPrecision = precision;
            t.worker = new KisLiquifyTransformWorker(bounds, nullptr, precision);
            t.bounds = bounds;
        }
        // Phase 2A-2: 预览模式下 rebase 后缓存一份 bounds 的原始像素(整段手势只读这一次),
        // 之后每次 dab 只做 CPU 位移采样 —— 拖动期间不再碰 Krita device。
        if (!ownBracket && liquifyPreviewRequested()) {
            m_liquifyPreview = true;
            liquifyPreviewCaptureLocked();
        }
    }

    const QPointF base(fx, fy);
    const qreal dist = QLineF(QPointF(fx, fy), QPointF(tx, ty)).length();
    // Effect magnitude follows how far the finger moved this dab: holding
    // still applies nothing, faster strokes apply stronger deformation
    const qreal rate = qBound<qreal>(0.0, dist / size, 1.0);
    const qreal amp = 0.2 + 0.8 * rate;

    for (LiquifyTarget &t : m_liquifyTargets) {
        if (!t.worker) continue;
        switch (mode) {
        case 1:
            // 膨胀: grid points move away from the brush center
            t.worker->scalePoints(base, 0.35 * s * amp, size, false, 1.0);
            break;
        case 2:
            // 收缩: grid points move toward the brush center
            t.worker->scalePoints(base, -0.35 * s * amp, size, false, 1.0);
            break;
        case 3:
            // 顺时针
            t.worker->rotatePoints(base, 0.6 * s * amp, size, false, 1.0);
            break;
        case 4:
            // 逆时针
            t.worker->rotatePoints(base, -0.6 * s * amp, size, false, 1.0);
            break;
        default:
            // 推拉: pixels follow the finger delta
            t.worker->translatePoints(
                base, QPointF((tx - fx) * s, (ty - fy) * s), size, false, 1.0);
            break;
        }
    }

    // Accumulate the delta region of dabs not yet written back (build-up
    // displacements never change once applied, so only new dabs' influence
    // needs the re-transformed pixels)
    const int infl = qRound(size * 3.2) + 8;
    const QRect dab(qMin(fx, tx) - infl, qMin(fy, ty) - infl,
                    qAbs(tx - fx) + 2 * infl, qAbs(ty - fy) + 2 * infl);
    // 累积前先看预算: 超预算或新 dab 与已累积区脱开就先 flush 再重新累积。
    // build-up 语义下旧位移不会再变, 提前回写与最终结果逐像素一致。
    if (!m_liquifyPendingDelta.isNull()) {
        const QRect merged = m_liquifyPendingDelta.united(dab);
        const qint64 mergedPx = qint64(merged.width()) * qint64(merged.height());
        // 预算至少要能容下 2 个 dab: 大笔刷单 dab 就有 1.7M px (size=200 时 infl=648),
        // 若预算小于它, 就会退化成"每个 dab 都 flush 一次" —— 等于把一次节流 apply
        // 变成每个输入事件多次 apply, 反而更卡。
        const qint64 dabPx = qint64(dab.width()) * qint64(dab.height());
        const qint64 budget = qMax(s_liquifyDeltaBudgetPx.load(), dabPx * 2);
        if (mergedPx > budget || !m_liquifyPendingDelta.intersects(dab)) {
            // 预览模式下不落盘: 位移只留在 worker 网格里, 由 liquifyPreviewBuildLocked 生成预览
            if (!m_liquifyPreview) {
                liquifyApplyLocked(m_liquifyPendingDelta);
            }
            m_liquifyPendingDelta = QRect();
        }
    }
    m_liquifyPendingDelta =
        m_liquifyPendingDelta.isNull() ? dab : m_liquifyPendingDelta.united(dab);

    // Throttled writeback: the grid update is cheap, the re-transform +
    // layer copy is not - pace it adaptively and flush once at gesture end
    const qint64 now = QDateTime::currentMSecsSinceEpoch();
    if (m_liquifyPreview) {
        if (liquifyPreviewHostDraw()) {
            // Phase 2B: 采样在 GPU 上做 —— 引擎完全不碰像素, 只让调用方知道"网格变了"
            if (!m_liquifyPreviewSrcRgba.isEmpty()) ++m_liquifyPreviewSeq;
        } else {
            // Phase 2A-2: 预览态 —— 只重建低分辨率预览; 不 run()、不写图层、不触投影合成
            liquifyPreviewBuildLocked();
        }
    } else if (ownBracket || now - m_liquifyLastApplyMs >= m_liquifyApplyIntervalMs) {
        liquifyApplyLocked(m_liquifyPendingDelta);
        m_liquifyPendingDelta = QRect();
    }

    if (ownBracket) {
        liquifyEnd();
    }
}
