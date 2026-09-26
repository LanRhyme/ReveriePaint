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
#include <chrono>

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

// Phase 3 埋点 (docs/LIQUIFY-REBASE-INVESTIGATION.md §8): rebase / materialize 生命周期。
// **纯诊断** —— 每次触发若干 relaxed 原子写, 不改变任何执行路径; 读取方是引擎线程(标尺每秒取一次)。
// 独立于 s_liquifyStats, 这样既有 liquifyStats 的 10 元契约完全不动。
enum LiquifyRebaseReason {
    LqRebaseNone = 0,
    LqRebaseFirstDab = 1,     // worker 尚未创建(首个 dab / 重建之后)
    LqRebaseLeftInnerBox = 2, // 笔尖走出 bounds 内缩 margin 后的内框
};
enum LiquifyRebaseStat {
    LqrCount = 0,        // rebase 次数
    LqrReason,           // 最近一次 rebase 的原因(LastValue)
    LqrFlushMs,          // rebase 前 flush pendingDelta 的累计耗时 —— 这一项就是"被 rebase 拖出来的物化"
    LqrFlushMaxMs,       // 上述耗时的峰值
    LqrCloneMs,          // rebase 里"重建 src/dst + worker"的累计耗时
    LqrOldAreaPx,        // 旧 bounds 面积累计(用于验证"每次 0.58M px")
    LqrNewAreaPx,        // 新 bounds 面积累计
    LqrInnerOverflowPx,  // 触发时越出内框的最大像素数(验证"锚点 240px"判据)
    LqrGridPoints,       // 最近一次 rebase 后的网格点数(LastValue)
    LqrThrottleCount,    // 节流那条 apply 边的次数(与 rebase 边分开, 避免误读)
    LqrThrottleMs,
    LqrThrottleMaxMs,
    // Phase 3 · Commit 1b: 拖动热路径的**单位成本** —— 一次 `liquify()` 调用的墙钟时间(µs)。
    // 这是"拖一笔到底在原生侧花了多少"的直接读数; 与 liquifyStats 的"单次 apply 四段拆分"互补
    // (后者在 AGSL 预览模式下拖动期间基本不触发, 因此**不能**用来判断拖动是否卡)。
    LqrCallCount,     // liquify() 调用次数(= 提交的补点数)
    LqrCallUs,        // 累计 µs
    LqrCallMaxUs,     // 单次峰值 µs
    LiquifyRebaseStatCount
};
std::atomic<qint64> s_liquifyRebaseStats[LiquifyRebaseStatCount];

void addRebaseStat(int idx, qint64 delta)
{
    if (delta == 0) return;
    s_liquifyRebaseStats[idx].fetch_add(delta, std::memory_order_relaxed);
}

void setRebaseStat(int idx, qint64 value)
{
    s_liquifyRebaseStats[idx].store(value, std::memory_order_relaxed);
}

/** 只保留峰值的写入(读-改-写用 CAS 循环, 避免与其它线程的 max 更新互相覆盖)。 */
void maxRebaseStat(int idx, qint64 value)
{
    qint64 cur = s_liquifyRebaseStats[idx].load(std::memory_order_relaxed);
    while (value > cur &&
           !s_liquifyRebaseStats[idx].compare_exchange_weak(
               cur, value, std::memory_order_relaxed)) {
    }
}

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

// ---------------------------------------------------------------------------
// Phase 3 · Commit 2: 拖动期不因 rebase 物化
//
// 真机证据(docs/LIQUIFY-REBASE-INVESTIGATION.md §11): 拖动中节流那条 apply 边**不跑**
// (见 liquify() 里的 `if (m_liquifyPreview)`), 因此拖动期 100% 的 run() 都来自 rebase 的 flush ——
// 单次 liquify() 调用峰值 71.7ms, 其中形变 37ms, 触发原因恒为"笔尖越出内框"。
//
// 做法(路线 B+C): 把窗口**扩**到"旧窗口 ∪ 新邻域", 重建网格后用待落盘补点**重放**位移 ——
// 不跑 run()、不回写、不触发同步合成。抬笔(或超上限)才做唯一一次物化。
// `setprop debug.reverie.liquifyNoRebase 0` 一键回退到"rebase 即物化"的旧行为。
// ---------------------------------------------------------------------------
// 窗口扩大的面积上限(像素): 必须留在 LIQUIFY_HOST_DRAW_MAX_PX(4M)之下, 否则 AGSL 覆盖层会因
// "源裁剪超预算"退回引擎侧 CPU 预览(见 liquifyPreviewCaptureLocked)。
const qint64 LIQUIFY_GROW_MAX_PX = 3 * 1024 * 1024;
// 待重放补点数上限: 超过就退回"物化 + 收紧窗口", 免得重放本身变成新的瓶颈
const int LIQUIFY_GROW_MAX_DABS = 512;

bool liquifyRebaseNoFlush()
{
#if defined(Q_OS_ANDROID)
    char value[PROP_VALUE_MAX] = {0};
    if (__system_property_get("debug.reverie.liquifyNoRebase", value) > 0 && value[0]) {
        return QByteArray(value).toInt() != 0;
    }
#else
    const QByteArray env = qgetenv("REVERIE_LQ_NOREBASE");
    if (!env.isEmpty()) return env.toInt() != 0;
#endif
    // 默认**关闭**(即保留"rebase 时就地落盘 + 收紧窗口"的原始行为)。
    //
    // 为什么默认关掉(真机数据):
    //  1) "扩窗口 + 补点重放"把形变**以位移场**跨窗口搬运 ⇒ 每次重锚定都对位移场重采样一次,
    //      误差逐次累积 ⇒ 真机表现"越涂越糊 / 割裂成大面积像素块"(原始实现是**以像素**搬运:
    //      落盘后新窗口重新克隆像素, 场不累积, 所以不会糊);
    //  2) 300px 笔刷下扩窗口很快撞到 3M px 上限 ⇒ 一秒 102 次 rebase, 每次"重建"29.5ms
    //      (src 克隆 + dst 分配 + 最多 512 个补点的重放) ⇒ 引擎线程 3.0s/s, 预览落后于手指;
    //  3) Commit 3 的 `warpFromGrid()` 已把"落盘"本身降到原来的 1/10 量级(1M px ≈ 35ms),
    //     所以"拖动期不落盘"这个前提已无必要。
    // 需要时 `setprop debug.reverie.liquifyNoRebase 1` 仍可打开, 用于对照实验。
    return false;
}

/** 把一个 dab 施加到 worker 网格上 —— 实时路径与 rebase 重放共用, 保证两者语义严格一致。 */
void applyLiquifyDab(KisLiquifyTransformWorker *w, qreal size, qreal s, qreal amp,
                     qreal fx, qreal fy, qreal tx, qreal ty, int mode)
{
    if (!w) return;
    const QPointF base(fx, fy);
    switch (mode) {
    case 1:
        // 膨胀: grid points move away from the brush center
        w->scalePoints(base, 0.35 * s * amp, size, false, 1.0);
        break;
    case 2:
        // 收缩: grid points move toward the brush center
        w->scalePoints(base, -0.35 * s * amp, size, false, 1.0);
        break;
    case 3:
        w->rotatePoints(base, 0.6 * s * amp, size, false, 1.0);
        break;
    case 4:
        w->rotatePoints(base, -0.6 * s * amp, size, false, 1.0);
        break;
    default:
        // 推拉: pixels follow the finger delta
        w->translatePoints(base, QPointF((tx - fx) * s, (ty - fy) * s), size, false, 1.0);
        break;
    }
}

/** 把"尚未落盘"的补点重放到新窗口的网格上(每 6 个 float 一个补点)。 */
void replayLiquifyDabs(KisLiquifyTransformWorker *w, qreal size, const QVector<float> &dabs)
{
    if (!w) return;
    for (int i = 0; i + 5 < dabs.size(); i += 6) {
        const qreal fx = dabs[i];
        const qreal fy = dabs[i + 1];
        const qreal tx = dabs[i + 2];
        const qreal ty = dabs[i + 3];
        const qreal s = dabs[i + 4];
        const int mode = int(dabs[i + 5]);
        // 与 liquify() 里同一套幅度公式: 只有 size 不变时重放才与实时施加逐点等价
        // (拖动期间笔刷尺寸不会变 —— 面板在手势中不可用)
        const qreal dist = QLineF(QPointF(fx, fy), QPointF(tx, ty)).length();
        const qreal rate = qBound<qreal>(0.0, dist / size, 1.0);
        applyLiquifyDab(w, size, s, 0.2 + 0.8 * rate, fx, fy, tx, ty, mode);
    }
}

// ---------------------------------------------------------------------------
// Phase 3 · Commit 3: 用"网格位移场 + 反向双线性采样"生成 dst, 替代 worker->run()
//
// 真机数据(docs/LIQUIFY-REBASE-INVESTIGATION.md §11): 998K px 的窗口上 run() 要 1459ms,
// 单次 liquify() 调用峰值 3110ms —— 它每格做多边形填充 + 补集拷贝, 成本随面积非线性膨胀。
// 本函数是每像素"网格双线性插值 + 源双线性采样", 同面积应在几十 ms 量级。
//
// 语义: 采样核为双线性, 与已验收的交互态预览(liquifyPreviewBuildLocked / AGSL 版)是**同一套数学**
//   ⇒ 所见即所得; 与 Krita 的前向多边形填充不再逐像素一致。越界像素写 alpha=0, 由调用方的
//   seedTransparentFromSource 用未形变源像素补洞(与既有"白线修复"同一语义)。
// 只处理 area(真正要回写的区域), 比 run() 的"整块 bounds"更省。
// `setprop debug.reverie.lqfastwarp 0` 可整体回退到 run()。
// ---------------------------------------------------------------------------
bool liquifyFastWarp()
{
#if defined(Q_OS_ANDROID)
    char value[PROP_VALUE_MAX] = {0};
    if (__system_property_get("debug.reverie.lqfastwarp", value) > 0 && value[0]) {
        return QByteArray(value).toInt() != 0;
    }
#else
    const QByteArray env = qgetenv("REVERIE_LQ_FASTWARP");
    if (!env.isEmpty()) return env.toInt() != 0;
#endif
    return true;
}

bool warpFromGrid(KisLiquifyTransformWorker *w, KisPaintDeviceSP src, KisPaintDeviceSP dst,
                  const QRect &area, const QRect &srcBounds)
{
    if (!w || !src || !dst || area.isEmpty() || srcBounds.isEmpty()) return false;
    const QSize gs = w->gridSize();
    const QVector<QPointF> &orig = w->originalPoints();
    QVector<QPointF> &trans = w->transformedPoints();
    const int cols = gs.width();
    const int rows = gs.height();
    const int n = qMin(orig.size(), trans.size());
    if (cols < 2 || rows < 2 || n != cols * rows) return false;
    const KoColorSpace *cs = src->colorSpace();
    const int ps = cs ? cs->pixelSize() : 0;
    // 与预览同一约束: 只支持 8bit BGRA(ps == 4)文档; 其它色彩空间退回 run()
    if (ps != 4) return false;

    const int bw = area.width();
    const int bh = area.height();
    // 源区域必须按 |最大位移| 外扩: 目标像素 p 的源位置是 p - offset(p), 位移稍大时源就落到
    // area 之外。若只读 area, 这些像素会被写成 alpha=0, 随后补洞又用**未形变**像素填回 ⇒
    // 形变区里嵌进大块"未形变补丁", 真机表现就是"画面割裂成大面积像素块"(Commit 3 的回归)。
    // 补足源区域后, 空洞只会出现在窗口自身的边界(= 位移把源拉出了窗口, 属设计内取舍)。
    // 原点/步长取自**真实网格点**(与 AGSL 版 LiquifyGpuPreview.applyGridLocked 同一口径)
    const qreal gx0 = orig[0].x();
    const qreal gy0 = orig[0].y();
    const qreal stepX = qMax<qreal>(1.0, orig[1].x() - gx0);
    const qreal stepY = qMax<qreal>(1.0, orig[cols].y() - gy0);

    // 源区域按"**写区附近**网格点的最大位移"外扩, 而不是"全网格最大位移"。
    // 后者过于保守: 一次快拖里远端网格点早已被推走几百像素, 会把读区放大到写区的 2~3 倍,
    // 而落盘成本里 `warpFromGrid` 的反向采样正是大头(真机: 每次 rebase 落盘 ≈21ms)。
    // 双线性支撑只有一格, 所以离写区超过一格的网格点影响不到这里的像素, 直接跳过。
    qreal maxOff = 0.0;
    for (int i = 0; i < n; ++i) {
        const QPointF &o = orig[i];
        if (o.x() < area.left() - stepX || o.x() > area.right() + stepX ||
            o.y() < area.top() - stepY || o.y() > area.bottom() + stepY) {
            continue;
        }
        const QPointF off = trans[i] - o;
        maxOff = qMax(maxOff, qMax(qAbs(off.x()), qAbs(off.y())));
    }
    const int pad = int(maxOff) + 2;
    const QRect need = area.adjusted(-pad, -pad, pad, pad).intersected(srcBounds);
    if (need.isEmpty()) return false;
    const int nw = need.width();
    const int nh = need.height();
    const size_t bytes = size_t(nw) * size_t(nh) * size_t(ps);
    thread_local QByteArray srcBuf;
    thread_local QByteArray dstBuf;
    if (size_t(srcBuf.size()) < bytes) {
        srcBuf.resize(int(bytes));
        dstBuf.resize(int(bytes));
    }
    src->readBytes(reinterpret_cast<quint8 *>(srcBuf.data()), need.x(), need.y(), nw, nh);
    const quint8 *s = reinterpret_cast<const quint8 *>(srcBuf.constData());
    quint8 *d = reinterpret_cast<quint8 *>(dstBuf.data());

    for (int py = 0; py < bh; ++py) {
        const qreal docY = qreal(area.top() + py) + 0.5;
        for (int px = 0; px < bw; ++px) {
            const qreal docX = qreal(area.left() + px) + 0.5;
            // 位移场双线性插值(权重按 original 坐标算)
            int c0 = int((docX - gx0) / stepX);
            int r0 = int((docY - gy0) / stepY);
            c0 = qBound(0, c0, cols - 2);
            r0 = qBound(0, r0, rows - 2);
            const int c1 = c0 + 1;
            const int r1 = r0 + 1;
            const QPointF &p00 = orig[r0 * cols + c0];
            const QPointF &p10 = orig[r0 * cols + c1];
            const QPointF &p01 = orig[r1 * cols + c0];
            const QPointF &p11 = orig[r1 * cols + c1];
            qreal fx = (docX - p00.x()) / qMax<qreal>(1.0, p10.x() - p00.x());
            qreal fy = (docY - p00.y()) / qMax<qreal>(1.0, p01.y() - p00.y());
            fx = qBound<qreal>(0.0, fx, 1.0);
            fy = qBound<qreal>(0.0, fy, 1.0);
            const QPointF d00 = trans[r0 * cols + c0] - p00;
            const QPointF d10 = trans[r0 * cols + c1] - p10;
            const QPointF d01 = trans[r1 * cols + c0] - p01;
            const QPointF d11 = trans[r1 * cols + c1] - p11;
            const qreal ax = d00.x() * (1 - fx) + d10.x() * fx;
            const qreal bx = d01.x() * (1 - fx) + d11.x() * fx;
            const qreal ay = d00.y() * (1 - fx) + d10.y() * fx;
            const qreal by = d01.y() * (1 - fx) + d11.y() * fx;
            const qreal ox = ax * (1 - fy) + bx * fy;
            const qreal oy = ay * (1 - fy) + by * fy;

            // 反向采样: src(p - offset), 坐标换算到 need 坐标系
            qreal u = qreal(px) + qreal(area.left() - need.left()) - ox;
            qreal v = qreal(py) + qreal(area.top() - need.top()) - oy;
            // 越界处**夹紧到边缘**(与 AGSL 预览的 CLAMP 采样一致), 而不是留 alpha=0。
            // 留 alpha=0 会让补洞逻辑用"未形变"像素填回 ⇒ 形变区里嵌进大块未形变矩形,
            // 真机表现就是"割裂成大面积像素块"(越界越多越明显, 位移 300px 时尤其刺眼)。
            // 夹紧后与已验收的预览是同一套语义 ⇒ 提交与所见一致, 且不再需要补洞兜底。
            u = qBound<qreal>(0.0, u, qreal(nw - 1));
            v = qBound<qreal>(0.0, v, qreal(nh - 1));
            quint8 *o = d + (size_t(py) * bw + px) * ps;
            const int x0 = int(u);
            const int y0 = int(v);
            const int x1 = qMin(x0 + 1, nw - 1);
            const int y1 = qMin(y0 + 1, nh - 1);
            const qreal ufx = u - x0;
            const qreal ufy = v - y0;
            const quint8 *q00 = s + (size_t(y0) * nw + x0) * ps;
            const quint8 *q10 = s + (size_t(y0) * nw + x1) * ps;
            const quint8 *q01 = s + (size_t(y1) * nw + x0) * ps;
            const quint8 *q11 = s + (size_t(y1) * nw + x1) * ps;
            // 源与目标是同一色彩空间(都是文档的 8bit BGRA) ⇒ 逐通道拷贝, 不做通道交换
            for (int ch = 0; ch < 4; ++ch) {
                const qreal top = q00[ch] * (1 - ufx) + q10[ch] * ufx;
                const qreal bot = q01[ch] * (1 - ufx) + q11[ch] * ufx;
                o[ch] = quint8(qBound<qreal>(0.0, top * (1 - ufy) + bot * ufy, 255.0));
            }
        }
    }
    dst->writeBytes(d, area.x(), area.y(), bw, bh);
    return true;
}

// Phase 3 · Test B (docs/LIQUIFY-REBASE-INVESTIGATION.md §11): `setprop debug.reverie.lqnodeform 1`
// 时**不做形变** —— 不碰网格、不 apply、不生成预览; 但输入 → 补点 → JNI → 失效 → 绘制的整条链路
// 照常运行。用来把"卡在形变"与"卡在呈现"一刀切开: 开了它若变丝滑, 主因就在原生形变。
bool liquifyDeformSkipRequested()
{
#if defined(Q_OS_ANDROID)
    char value[PROP_VALUE_MAX] = {0};
    if (__system_property_get("debug.reverie.lqnodeform", value) > 0 && value[0]) {
        return QByteArray(value).toInt() != 0;
    }
#else
    const QByteArray env = qgetenv("REVERIE_LQ_NODEFORM");
    if (!env.isEmpty()) return env.toInt() != 0;
#endif
    return false;
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

bool ReverieCore::liquifyPreviewWanted() const
{
    // Kotlin 侧显式写了绘制模式(>= 0)就等于"这次手势要预览": 没有数据线时靠构建档位
    // (app/build.gradle.kts 的 -PlqTestProfile)也能测, 不必先设 property。
    // 0 = 引擎侧 CPU 叠加, 1 = 主机侧(AGSL)绘制。
    if (m_liquifyPreviewHostDrawMode >= 0) return true;
    return liquifyPreviewRequested();
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
    m_liquifyPendingDabs.clear();
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
                                  [&](LiquifyTarget *t) {
                                      // Phase 3 · Commit 3: 优先自己用位移场反向采样生成 dst,
                                      // 只覆盖真正要回写的 delta 区(run() 在同面积上要 1.4s);
                                      // 前提不满足(非 8bit BGRA / 网格不完整)时退回 run()。
                                      const QRect a =
                                          deltaRect.intersected(t->bounds).intersected(clipRect);
                                      if (!(liquifyFastWarp()
                                            && warpFromGrid(t->worker, t->src, t->dst, a,
                                                            t->bounds))) {
                                          // run() 内部本来就会 dst->clear(), 不必重复清
                                          t->worker->run(t->src, t->dst);
                                      }
                                  });
    } else {
        for (LiquifyTarget *t : warped) {
            const QRect a = deltaRect.intersected(t->bounds).intersected(clipRect);
            if (!(liquifyFastWarp()
                  && warpFromGrid(t->worker, t->src, t->dst, a, t->bounds))) {
                t->worker->run(t->src, t->dst);
            }
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
    // Phase 5 · C3-2: 场通路随手势结束复位(像素已由 liquifyFieldCommit 写回, 或本就没提交)
    m_liquifyFieldMode = false;
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
    // Phase 5 · C3-2: 取消 = 图层从未被改写(拖动期零引擎解算), 只需复位场通路标记
    m_liquifyFieldMode = false;
    m_liquifyPreview = false;
    m_liquifyTargets.clear();
    m_liquifyTxnActive = false;
    resetLiquifyWorker();
}

// ---------------------------------------------------------------------------
// Phase 5 · C3-2: GPU 常驻位移场的引擎侧收口 (docs/LIQUIFY-C3-FIELD-PLAN.md §3)
//
// 拖动期**零解算**: 位移全部留在 GPU 的浮点场里(Kotlin 侧逐 dab 累加), 引擎只在这里做两件事:
//   ① 手势开始/需要更大范围时, 把目标图层的**未形变**像素交给覆盖层当源纹理;
//   ② 抬笔时把 GPU 已经算好的像素结果**一次性**写回图层。
// 于是拖动期的 `调用 / rebase / 物化` 三格归零(真机实测: 200px 笔刷原本拖动期要 1.07s/s,
// 其中 rebase 物化 517ms、逐 dab 网格形变 550ms)。
//
// 写回语义与 liquifyApplyLocked 逐条一致(选区冻结 / Alpha 锁只动颜色 / 脏区 + 立即投影合成 /
// 一条撤销) —— 那是所有液化路径的公共底线, 这里刻意复制而不改既有函数(AGENTS.md §5 扩而不改)。
// ---------------------------------------------------------------------------

bool ReverieCore::liquifyFieldSource(int x, int y, int w, int h)
{
    if (!m_document || m_liquifyTargets.isEmpty()) return false;
    const QRect docRect(0, 0, m_document->width(), m_document->height());
    const QRect b = QRect(x, y, w, h).intersected(docRect);
    if (b.isEmpty() || b.width() <= 0 || b.height() <= 0) return false;
    const qint64 px = qint64(b.width()) * qint64(b.height());
    // 与主机侧绘制的源裁剪同一预算口径: 超大范围的复制 + 上传得不偿失, 宁可不做(调用方回退)
    if (px > LIQUIFY_HOST_DRAW_MAX_PX) return false;
    KisPaintDeviceSP src = m_liquifyTargets[0].device;
    if (!src) return false;
    const KoColorSpace *cs = src->colorSpace();
    const int ps = cs ? cs->pixelSize() : 0;
    if (ps != 4) return false;

    m_liquifyPreviewSrc.resize(int(px) * ps);
    src->readBytes(m_liquifyPreviewSrc.data(), b.x(), b.y(), b.width(), b.height());
    m_liquifyPreviewSrcRgba.resize(int(px) * 4);
    const quint8 *s = m_liquifyPreviewSrc.constData();
    quint8 *d = m_liquifyPreviewSrcRgba.data();
    for (qint64 i = 0; i < px; ++i) {
        d[i * 4 + 0] = s[i * 4 + 2]; // BGRA -> RGBA(预乘; 与既有主机侧绘制同一假设)
        d[i * 4 + 1] = s[i * 4 + 1];
        d[i * 4 + 2] = s[i * 4 + 0];
        d[i * 4 + 3] = s[i * 4 + 3];
    }
    m_liquifyWorkerBounds = b;
    // 让既有链路原样工作: Kotlin 侧照旧走 liquifyPreviewSourceMeta / Pixels 取数;
    // 引擎自己不生成 CPU 预览像素(W/H = 0), 也从不进入网格/物化分支。
    m_liquifyPreview = true;
    m_liquifyPreviewW = 0;
    m_liquifyPreviewH = 0;
    m_liquifyPreviewOut = QVector<quint8>();
    ++m_liquifyPreviewSeq;
    m_liquifyFieldMode = true;
    RPC_TRACE("liquify fieldSource %dx%d@(%d,%d) px=%d",
              b.width(), b.height(), b.x(), b.y(), int(px));
    return true;
}

void ReverieCore::liquifyFieldCommit(int x, int y, int w, int h, const QVector<quint8> &rgba,
                                     bool bottomUp)
{
    if (!m_document || m_liquifyTargets.isEmpty() || w <= 0 || h <= 0) return;
    if (rgba.size() < qint64(w) * qint64(h) * 4) return;
    const QRect docRect(0, 0, m_document->width(), m_document->height());
    const QRect area = QRect(x, y, w, h).intersected(docRect);
    if (area.isEmpty()) return;

    const qint64 t0 = QDateTime::currentMSecsSinceEpoch();
    QRect clipRect = docRect;
    if (m_selection) {
        clipRect &= m_selection->selectedExactRect();
    }
    QRect dirtyUnion;
    qint64 tBlitMs = 0;
    qint64 tCompositeMs = 0;
    const int ps = 4;
    thread_local QVector<quint8> rowBuf;
    const qint64 areaPx = qint64(area.width()) * qint64(area.height());

    for (LiquifyTarget &t : m_liquifyTargets) {
        if (!t.device) continue;
        const QRect a = area.intersected(clipRect);
        if (a.isEmpty()) continue;
        if (!t.dst) t.dst = new KisPaintDevice(t.device->colorSpace());
        // RGBA(预乘) -> 设备字节序(BGRA, 也是预乘): 逐行转换后写进 dst, 再按选区分块回写。
        // bottomUp = GL 读回的原始行序(首行是矩形最后一行), 在这里翻正 —— 免得 Kotlin 侧
        // 再走一遍整块像素。
        const int rowBytes = a.width() * ps;
        if (rowBuf.size() < rowBytes) rowBuf.resize(rowBytes);
        for (int r = 0; r < a.height(); ++r) {
            const int dstRow = a.y() + r;
            const int relRow = dstRow - y;
            const int srcRow = bottomUp ? (h - 1 - relRow) : relRow;
            if (srcRow < 0 || srcRow >= h) continue;
            const quint8 *srow = rgba.constData() + (qint64(srcRow) * w + (a.x() - x)) * ps;
            quint8 *drow = rowBuf.data();
            for (int c = 0; c < a.width(); ++c) {
                drow[c * 4 + 0] = srow[c * 4 + 2];
                drow[c * 4 + 1] = srow[c * 4 + 1];
                drow[c * 4 + 2] = srow[c * 4 + 0];
                drow[c * 4 + 3] = srow[c * 4 + 3];
            }
            t.dst->writeBytes(drow, a.x(), dstRow, a.width(), 1);
        }
        const qint64 tb0 = QDateTime::currentMSecsSinceEpoch();
        KisPainter p(t.device);
        p.setCompositeOpId(COMPOSITE_COPY);
        if (m_selection) {
            p.setSelection(m_selection);
        }
        // Alpha 锁图层只跟颜色通道走, 保持轮廓不变(与 liquifyApplyLocked / 笔画路径同一 flags)
        p.setChannelFlags(t.layer && t.layer->alphaLocked() ? t.layer->channelLockFlags() : QBitArray());
        p.bitBlt(a.topLeft(), t.dst, a);
        p.end();
        t.device->setDirty(a);
        tBlitMs += QDateTime::currentMSecsSinceEpoch() - tb0;
        dirtyUnion = dirtyUnion.isNull() ? a : dirtyUnion.united(a);
    }

    if (!dirtyUnion.isEmpty()) {
        const qint64 tc0 = QDateTime::currentMSecsSinceEpoch();
        markRegionDirty(dirtyUnion);
        // 与 liquifyApplyLocked 逐行同义: 让后台调度器之外的那次同步合成立刻发生, 否则渲染
        // 路径此刻读投影会拿到半更新像素(白线/撕裂的第二个成因)
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

    const qint64 elapsed = QDateTime::currentMSecsSinceEpoch() - t0;
    // 复用既有的分段读数: 形变段为 0(GPU 已经算完), 回写/合成段照旧上报 ⇒ 标尺的"液化"一行
    // 在抬笔时会显示这一次 bulk 提交的真实成本。
    const qint64 lqPrec = qMax(1, m_liquifyPrecision);
    const qint64 lqCells = qint64(area.width() / lqPrec + 2) * qint64(area.height() / lqPrec + 2);
    publishLiquifyStats(elapsed, 0, 0, tBlitMs, tCompositeMs, areaPx,
                        m_liquifyTargets.size(), m_liquifyPrecision, lqCells);
    RPC_TRACE("liquify fieldCommit %dx%d@(%d,%d) total=%dms blit=%d comp=%d targets=%d",
              area.width(), area.height(), area.x(), area.y(),
              int(elapsed), int(tBlitMs), int(tCompositeMs), int(m_liquifyTargets.size()));
}

void ReverieCore::liquifyStats(qint64 *out)
{
    if (!out) return;
    for (int i = 0; i < LiquifyStatCount; ++i) {
        out[i] = s_liquifyStats[i].load(std::memory_order_relaxed);
    }
}

// Phase 3 埋点 (docs/LIQUIFY-REBASE-INVESTIGATION.md §8): rebase / materialize 生命周期读数。
// 独立入口, 免得改动既有 liquifyStats 的返回长度与调用方索引。
void ReverieCore::liquifyRebaseStats(qint64 *out)
{
    if (!out) return;
    for (int i = 0; i < LiquifyRebaseStatCount; ++i) {
        out[i] = s_liquifyRebaseStats[i].load(std::memory_order_relaxed);
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

    // Phase 3 · Commit 1b 埋点: 一次 liquify() 调用的墙钟成本(= 拖动热路径的真实单位成本)
    const auto lqCallT0 = std::chrono::steady_clock::now();

    // Phase 3 · Test B: 只跳过形变本身, 其余链路全跑(见 liquifyDeformSkipRequested 的说明)
    if (liquifyDeformSkipRequested()) {
        const qint64 skipUs = std::chrono::duration_cast<std::chrono::microseconds>(
                                  std::chrono::steady_clock::now() - lqCallT0).count();
        addRebaseStat(LqrCallCount, 1);
        addRebaseStat(LqrCallUs, skipUs);
        maxRebaseStat(LqrCallMaxUs, skipUs);
        if (ownBracket) liquifyEnd();
        return;
    }

    const qreal s = qBound<qreal>(0.05, strength, 2.0);
    // KisLiquifyPaintop passes the brush diameter as sigma (gaussian falloff)
    const qreal size = qMax<qreal>(8.0, m_liquifyBrushSize);

    // Local grid: rebase when the brush is about to leave the inner margin
    // (the worker's run() copies the whole bounds complement, so the bounds
    // must stay local or every dab costs a full-bounds copy)
    // Phase 3 埋点: 这一次是否需要 rebase、为什么(纯诊断, 不影响判定逻辑)
    int rebaseReason = LqRebaseNone;
    qint64 rebaseInnerOverflowPx = 0;
    bool needRebase = m_liquifyTargets.isEmpty() || m_liquifyTargets[0].worker == nullptr;
    if (needRebase) {
        rebaseReason = LqRebaseFirstDab;
    } else {
        const int margin = qMax(40, qRound(size * 0.7));
        const QRect inner = m_liquifyWorkerBounds.adjusted(margin, margin, -margin, -margin);
        if (!inner.contains(QPoint(tx, ty))) {
            needRebase = true;
            rebaseReason = LqRebaseLeftInnerBox;
            // 越出内框多少像素(四边取最大) —— 用来真机验证"锚点 R-margin"这条判据
            rebaseInnerOverflowPx =
                qMax(qMax<qint64>(qint64(inner.left()) - tx, qint64(tx) - inner.right()),
                     qMax<qint64>(qint64(inner.top()) - ty, qint64(ty) - inner.bottom()));
        }
    }
    if (needRebase) {
        const QRect rebaseOldBounds = m_liquifyWorkerBounds;
        const qint64 rebaseT0 = QDateTime::currentMSecsSinceEpoch();
        qint64 rebaseFlushMs = 0;
        const QRect docRect(0, 0, image->width(), image->height());
        // Tight bounds: only the brush neighbourhood + the gaussian influence
        // radius (3 sigma) must fit; anything larger only adds copy cost
        // 影响半径保持 1.9σ: 试过压到 1.6σ 省面积, 但 bounds 变小后强位移会更频繁地
        // 需要 bounds 之外的像素 / 让网格退化, 真机上反而更不稳 (68px 就闪退)。稳妥优先。
        const int R = qMax<int>(192, qRound(size * 1.9));
        QRect bounds = QRect(tx - R, ty - R, 2 * R, 2 * R).intersected(docRect);
        if (bounds.isEmpty()) {
            if (ownBracket) liquifyEnd();
            return;
        }
        // ---- Phase 3 · Commit 2: 交互期(预览态)不为 rebase 物化, 改为扩窗口 + 重放待落盘补点 ----
        // 只有"有网格 + 有预览 + 有未落盘补点 + 未超上限"时才扩; 其余一律走旧路径(flush + 收紧窗口)。
        bool replayPending = false;
        if (liquifyRebaseNoFlush() && !ownBracket && m_liquifyPreview && rebaseOldBounds.isValid()
            && m_liquifyTargets[0].worker && !m_liquifyPendingDabs.isEmpty()
            && m_liquifyPendingDabs.size() / 6 <= LIQUIFY_GROW_MAX_DABS) {
            const QRect grown = bounds.united(rebaseOldBounds).intersected(docRect);
            const qint64 grownArea = qint64(grown.width()) * qint64(grown.height());
            if (!grown.isEmpty() && grownArea <= LIQUIFY_GROW_MAX_PX) {
                bounds = grown;
                replayPending = true;
            }
        }
        if (!replayPending && m_liquifyTargets[0].worker) {
            const qint64 flushT0 = QDateTime::currentMSecsSinceEpoch();
            liquifyApplyLocked(m_liquifyPendingDelta.isNull() ? m_liquifyWorkerBounds
                                                              : m_liquifyPendingDelta);
            rebaseFlushMs = QDateTime::currentMSecsSinceEpoch() - flushT0;
            m_liquifyPendingDelta = QRect();
            // 落盘了才能丢弃重放列表
            m_liquifyPendingDabs.clear();
        }
        // ---- 网格精度(2 的幂 + 分辨率保底 + 诊断覆盖)提到循环外: 所有目标共用同一档 ----
        //
        // 除数从 8 改成 16(网格加密一倍): **老的 size/8 是为 `run()` 调的** —— 它的成本 ∝ 网格单元数
        // (bounds/精度)², 所以大笔刷必须用粗网格(历史结论: 32 档约 4× 提速)。
        // 但 `warpFromGrid()` 已经取代了 `run()`: 它按"写区像素"反向采样、每像素 4 次网格取值,
        // **与网格密度无关** ⇒ 精度现在只决定"位移场的分辨率", 加密几乎不花钱, 而 30px 级折线台阶直接减半。
        // 真机若嫌慢: `setprop debug.reverie.lqprec 32` 一键回到粗档。
        const int rawPrecision = qBound<int>(4, qRound(size / 16.0), 32);
        int precision = rawPrecision > 16 ? 32
                        : (rawPrecision > 12 ? 16 : (rawPrecision > 6 ? 8 : 4));
        const int resolutionFloor = qMax<int>(16, R / 8);
        while (precision > resolutionFloor && precision > 4) {
            precision /= 2;
        }
        const int forcedPrecision = liquifyForcedPrecision();
        if (forcedPrecision > 0) {
            precision = forcedPrecision;
        }
        // ---- 网格原点对齐(修"越涂越糊 / 马赛克") ----
        // 网格原点 = bounds 的左上角。若每次重锚定都用不对齐的原点, 同一个位移场在新旧网格上就落在
        // 不同格点上 —— "重放"等于对位移场反复重采样, 误差逐次累积 ⇒ 真机表现就是"自动糊成马赛克"。
        // 把 left/top 向下吸附到 precision 的整数倍, 让所有窗口共享同一格点阵: 重叠区位移逐点一致。
        // 只向下取整 left/top(不会越出文档左/上边界), 右/下边界保持不变。
        {
            const int ax = (bounds.x() / precision) * precision;
            const int ay = (bounds.y() / precision) * precision;
            bounds = QRect(ax, ay, bounds.right() - ax + 1, bounds.bottom() - ay + 1);
        }
        m_liquifyPrecision = precision;
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
            // 精度已在循环外算好(见上方"网格精度"与"网格原点对齐"两段) —— 所有目标共用同一档
            t.worker = new KisLiquifyTransformWorker(bounds, nullptr, precision);
            t.bounds = bounds;
            // 扩窗口时把"尚未落盘"的位移重放进来 —— 这一步替代了旧的 flush + run(),
            // 是拖动期不再出现 40~70ms 尖峰的关键(重放只做网格点运算, 成本 µs 级)
            if (replayPending) {
                replayLiquifyDabs(t.worker, size, m_liquifyPendingDabs);
            }
        }
        // Phase 3 埋点: 记下一次 rebase 的构成(纯诊断)。cloneMs 用"整段减去 flush 段"近似,
        // 它覆盖 makeCloneFrom + dst 分配 + worker 重建 —— 正是 §4.6.4 提到的"设备重建抖动"。
        {
            const qint64 rebaseT1 = QDateTime::currentMSecsSinceEpoch();
            const qint64 rebaseCloneMs =
                qMax<qint64>(0, (rebaseT1 - rebaseT0) - rebaseFlushMs);
            addRebaseStat(LqrCount, 1);
            setRebaseStat(LqrReason, rebaseReason);
            addRebaseStat(LqrFlushMs, rebaseFlushMs);
            maxRebaseStat(LqrFlushMaxMs, rebaseFlushMs);
            addRebaseStat(LqrCloneMs, rebaseCloneMs);
            addRebaseStat(LqrOldAreaPx,
                          qint64(rebaseOldBounds.width()) * qint64(rebaseOldBounds.height()));
            addRebaseStat(LqrNewAreaPx, qint64(m_liquifyWorkerBounds.width()) *
                                            qint64(m_liquifyWorkerBounds.height()));
            maxRebaseStat(LqrInnerOverflowPx, rebaseInnerOverflowPx);
            const QSize rebaseGrid =
                m_liquifyTargets[0].worker ? m_liquifyTargets[0].worker->gridSize() : QSize();
            setRebaseStat(LqrGridPoints,
                          qint64(rebaseGrid.width()) * qint64(rebaseGrid.height()));
            RPC_TRACE("liquify rebase #%d reason=%d flush=%dms clone=%dms old=%dx%d new=%dx%d "
                      "overflow=%d grid=%dx%d",
                      int(s_liquifyRebaseStats[LqrCount].load(std::memory_order_relaxed)),
                      rebaseReason, int(rebaseFlushMs), int(rebaseCloneMs),
                      rebaseOldBounds.width(), rebaseOldBounds.height(),
                      m_liquifyWorkerBounds.width(), m_liquifyWorkerBounds.height(),
                      int(rebaseInnerOverflowPx), rebaseGrid.width(), rebaseGrid.height());
        }
        // Phase 2A-2: 预览模式下 rebase 后缓存一份 bounds 的原始像素(整段手势只读这一次),
        // 之后每次 dab 只做 CPU 位移采样 —— 拖动期间不再碰 Krita device。
        if (!ownBracket && liquifyPreviewWanted()) {
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
        applyLiquifyDab(t.worker, size, s, amp, fx, fy, tx, ty, mode);
    }
    // Phase 3 · Commit 2: 记下这个补点, 供 rebase 时"不物化"的重放使用(每 6 个 float 一组)
    if (!m_liquifyTargets.isEmpty() && m_liquifyTargets[0].worker) {
        m_liquifyPendingDabs << float(fx) << float(fy) << float(tx) << float(ty)
                             << float(s) << float(mode);
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
                // 真的落盘了才能丢弃重放列表; 预览模式下位移仍在网格里, 必须留着
                m_liquifyPendingDabs.clear();
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
        // Phase 3 埋点: 这条边是**节流**触发的物化, 与 rebase 边分开计 —— 否则会把两者混为一谈
        const qint64 throttleT0 = QDateTime::currentMSecsSinceEpoch();
        liquifyApplyLocked(m_liquifyPendingDelta);
        const qint64 throttleMs = QDateTime::currentMSecsSinceEpoch() - throttleT0;
        addRebaseStat(LqrThrottleCount, 1);
        addRebaseStat(LqrThrottleMs, throttleMs);
        maxRebaseStat(LqrThrottleMaxMs, throttleMs);
        m_liquifyPendingDelta = QRect();
        m_liquifyPendingDabs.clear();
    }

    // Phase 3 · Commit 1b 埋点: 记下这一次调用的成本。刻意放在 liquifyEnd() **之前** ——
    // 收口那次物化属于"手势结束", 不该算进"每个 dab 的单位成本"。
    {
        const qint64 callUs = std::chrono::duration_cast<std::chrono::microseconds>(
                                  std::chrono::steady_clock::now() - lqCallT0).count();
        addRebaseStat(LqrCallCount, 1);
        addRebaseStat(LqrCallUs, callUs);
        maxRebaseStat(LqrCallMaxUs, callUs);
    }

    if (ownBracket) {
        liquifyEnd();
    }
}
