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
#include "PaintOpTraits.h"
#include "StrokeTailFlush.h"
#include "StrokeTimeContract.h"
#include <strokes/KisMaskingBrushRenderer.h>
#include <strokes/KisMaskedFreehandStrokePainter.h>
#include <KisFreehandStrokeInfo.h>
#include <KoCompositeOpRegistry.h>
#include <filter/kis_filter_registry.h>
#include <QtConcurrent/QtConcurrentMap>
#include <cmath>
#include <string_view>

namespace {

std::string_view opView(const QByteArray &idBytes)
{
    return std::string_view(idBytes.constData(), size_t(idBytes.size()));
}

// doAsynchronousUpdate 把大笔刷的单个 dab 切成多条 KisStrokeJobData::CONCURRENT
// 水平条带。原先逐条串行跑, 大直径湿笔 (colorsmudge) 单批耗时随直径平方增长。
// 这里按 sequentiality() 严格保序: 连续 CONCURRENT 条带 >= 3 条时在
// reverieBackgroundPool() 上 fork-join; 直径 >= 96px 时单条已够重, 2 条也并行。
// SEQUENTIAL / UNIQUELY_CONCURRENT / BARRIER 仍在当前线程按序执行。
// 同时用于 ParallelStrokeJobsExecutor (paintop 经 runnable 接口投递的任务): 那里拿到的是
// const 列表, 所以并行段拷一份切片给 blockingMap。跑完即释放, 与 Krita 的假执行器语义一致。
template <typename Job>
void runStrokeJobsBatch(const QVector<Job *> &jobs, qreal brushSize = 0.0)
{
    const int n = jobs.size();
    int i = 0;
    while (i < n) {
        Job *first = jobs[i];
        if (!first) {
            ++i;
            continue;
        }
        if (first->sequentiality() != KisStrokeJobData::CONCURRENT) {
            first->run();
            delete first;
            ++i;
            continue;
        }
        int j = i + 1;
        while (j < n && jobs[j] && jobs[j]->sequentiality() == KisStrokeJobData::CONCURRENT) {
            ++j;
        }
        const int count = j - i;
        if (count >= 3 || (count == 2 && brushSize >= 96.0)) {
            const QVector<Job *> slice = jobs.mid(i, count);
            QtConcurrent::blockingMap(reverieBackgroundPool(), slice, [](Job *job) { job->run(); });
        } else {
            for (int k = i; k < j; ++k) {
                jobs[k]->run();
            }
        }
        for (int k = i; k < j; ++k) {
            delete jobs[k];
        }
        i = j;
    }
}

} // namespace

// paintop 经 KisRunnableStrokeJobsInterface 投递的任务 (例如大笔刷 dab 的条带) 原先由
// KisFakeRunnableStrokeJobsExecutor 逐个串行跑; 这里改走同一套保序的 fork-join。
// 直径未知, 按默认门槛 (>= 3 条才并行)。
void ReverieCore::ParallelStrokeJobsExecutor::addRunnableJobs(const QVector<KisRunnableStrokeJobDataBase *> &list)
{
    runStrokeJobsBatch(list);
}

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

    m_strokeUsedQuickPath = false;
    m_strokeQuickPlanned = false;
    // 分支 painter/paintop 已在上面的 endStrokeBatch() 里释放, 这里只复位游标与标志
    for (int i = 0; i < 8; ++i) {
        m_mirrorCursor[i].hasLast = false;
        m_mirrorCursor[i].distance = KisDistanceInformation();
    }
    m_mirrorLiveStarted = false;
    m_mirrorDropLogged = false;

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
    m_lastFlushHadInk = false;
    m_strokeTimer.restart();
    m_strokeCounter++;
    const int strokeSeed = (m_replaySeed >= 0) ? m_replaySeed : int(quint32(m_strokeCounter) * 1664525u + 1013904223u);
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

void ReverieCore::setMirrorStrokeSeq(int strokeSeq)
{
    m_mirrorStrokeSeq = strokeSeq > 0 ? strokeSeq : 0;
}

KisPaintOpSP ReverieCore::createStrokePaintOp(KisPainter *painter, KisImageSP image, int layerIndex, bool *fellBack)
{
    if (fellBack) {
        *fellBack = false;
    }
    if (!painter || !m_brushPreset || layerIndex < 0 || layerIndex >= m_layers.size()) {
        return KisPaintOpSP();
    }
    const bool isColorSmudge = (m_toolMode == ToolSmudge) ||
        (m_brushPreset->paintOp().id() == QStringLiteral("colorsmudge"));
    KisPaintOpFactory *smudgeFactory =
        isColorSmudge ? KisPaintOpRegistry::instance()->value(QStringLiteral("colorsmudge")) : nullptr;
    painter->setRunnableStrokeJobsInterface(&m_fakeExecutor);
    KisPaintOpSP op;
    if (smudgeFactory) {
        op = smudgeFactory->createOp(m_brushPreset->settings(), painter,
                                     KisNodeSP(m_layers[layerIndex].node), image);
    }
    if (!op) {
        op = KisPaintOpRegistry::instance()->paintOp(
            m_brushPreset, painter, KisNodeSP(m_layers[layerIndex].node), image);
    }
    if (!op) {
        // 最后一档兜底: 引擎未注册时退化成 KisBrushOp (调用方负责打警告日志)
        if (fellBack) {
            *fellBack = true;
        }
        op = new KisBrushOp(m_brushPreset->settings(), painter,
                            KisNodeSP(m_layers[layerIndex].node), image);
    }
    return op;
}

void ReverieCore::releaseMirrorBranchOps()
{
    // paintop 持有 painter 的裸指针: 先放 paintop 再删 painter。
    for (int i = 0; i < 8; ++i) {
        m_mirrorCursor[i].op = nullptr;
        delete m_mirrorCursor[i].painter;
        m_mirrorCursor[i].painter = nullptr;
    }
}

reverie::MirrorBatchAction ReverieCore::paintMirrorPolyline(const float *samples, int count, int stride,
                                                            int branchIndex, int strokeSeq)
{
    // 返回值语义 (s12 / s12-F1, 见 MirrorLivePolicy.h::mirrorBatchResultCode):
    //  Paint / CreateAndPaint / DropAccepted = 接受 (本笔镜像实时出墨, Kotlin 不重放);
    //  Reject = 拒绝 (本笔镜像还没有任何实时墨, Kotlin 改走抬笔重放);
    //  Stale  = 这批点不属于引擎当前打开的笔画 (笔画号不符或笔触已关), 丢弃, 不改 Kotlin 状态。
    // 一旦 m_mirrorLiveStarted, 绝不返回 Reject。
    const bool strokeOpen = m_drawing && m_strokeBatchOpen && m_document;
    const bool seqMatches = strokeSeq > 0 && strokeSeq == m_mirrorStrokeSeq;
    if (!strokeOpen || !seqMatches) {
        return reverie::MirrorBatchAction::Stale;
    }
    if (!samples || count < 1 || stride < 7 || branchIndex < 0 || branchIndex >= 8) {
        return m_mirrorLiveStarted ? reverie::MirrorBatchAction::DropAccepted : reverie::MirrorBatchAction::Reject;
    }
    MirrorBranchCursor &cursor = m_mirrorCursor[branchIndex];

    // 主笔这一笔还没完成 strokeSetup (第一次 flush 之前就收到镜像点): 先让主笔把已入队
    // 的样本画掉, 顺带建好 m_strokePainter / m_strokeOp。没移动过时与 touchStrokeKickIdle
    // 同语义 (置 m_idleKickPainted, 抬笔不会再补一个起笔点)。
    if (strokeOpen && !cursor.op && !m_strokePainter && !m_maskedStrokePainter &&
        !m_strokeSamples.isEmpty()) {
        if (!touchStrokeKickIdle()) {
            flushStrokeBatch();
        }
    }

    const QString opId = (m_toolMode == ToolSmudge)
        ? QStringLiteral("colorsmudge")
        : (m_brushPreset ? m_brushPreset->paintOp().id() : QString());
    const QByteArray mirrorIdBytes = opId.toLatin1();
    const std::string_view mirrorId = opView(mirrorIdBytes);
    const bool engineBypassesSelection = !reverie::PaintOpTraits::honorsPainterSelection(mirrorId);
    // 只有真 Krita 引擎链路能给分支各建一个 paintop。快速路径 (QuickRoundBrush 的跨批状态只有
    // 一份)、双笔刷蒙版 (KisMaskedFreehandStrokePainter)、以及"引擎绕过选区且有选区"
    // (主笔靠整批读回/写回裁剪) 都退回抬笔重放。
    // s12-G1: 主笔本笔已判定会走快速路径 (m_strokeQuickPlanned, 每次 flush 在点按分支之前算)
    // 时也不建分支 op: 否则起笔 kick 先用真引擎建了镜像 op, 主笔随后改走快速路径, 两侧笔迹不一致。
    const bool eligible = strokeOpen && m_brushPreset && m_strokeOp && m_strokePainter &&
        !m_maskedStrokePainter && !m_strokeQuickPlanned && !m_strokeUsedQuickPath &&
        !(m_selection && engineBypassesSelection);

    const reverie::MirrorBatchAction action =
        reverie::decideMirrorBatch(strokeOpen, seqMatches, bool(cursor.op), eligible, m_mirrorLiveStarted);
    if (action == reverie::MirrorBatchAction::Reject || action == reverie::MirrorBatchAction::Stale) {
        if (action == reverie::MirrorBatchAction::Reject && !m_mirrorDropLogged) {
            m_mirrorDropLogged = true;
            RPC_LOG("RPC mirrorReject branch=%d quickPlanned=%d quick=%d masked=%d -> replay fallback",
                    branchIndex, m_strokeQuickPlanned ? 1 : 0, m_strokeUsedQuickPath ? 1 : 0,
                    m_maskedStrokePainter ? 1 : 0);
        }
        return action;
    }
    if (action == reverie::MirrorBatchAction::DropAccepted) {
        if (!m_mirrorDropLogged) {
            m_mirrorDropLogged = true;
            RPC_LOG("RPC mirrorDrop branch=%d open=%d quick=%d masked=%d (live already started, batch dropped)",
                    branchIndex, strokeOpen ? 1 : 0, m_strokeUsedQuickPath ? 1 : 0, m_maskedStrokePainter ? 1 : 0);
        }
        return action;
    }

    KisImageSP image = m_document;
    if (action == reverie::MirrorBatchAction::CreateAndPaint) {
        // 与 Krita KisPainterBasedStrokeStrategy::initPainters 同构: 每个 stroke info 一个
        // KisPainter, begin 到同一个目标设备, 各自 setPaintOpPreset 出独立的 paintop。
        // 画笔状态必须在建 op **之前**复制全: KisColorSmudgeOp 构造时就把
        // painter->paintColor() / gradient() 固化进 m_paintColor / m_gradient, 其涂抹策略的
        // initializePainting 也读 painter 的选区 / 合成; 建完再同步颜色对它无效。
        KisPainter *bp = new KisPainter(m_strokePainter->device());
        bp->setFillStyle(m_strokePainter->fillStyle());
        bp->setStrokeStyle(m_strokePainter->strokeStyle());
        bp->setChannelFlags(m_strokePainter->channelFlags());
        bp->setCompositeOpId(m_strokePainter->compositeOpId());
        bp->setOpacityF(m_strokePainter->opacityF());
        bp->setPaintColor(m_strokePainter->paintColor());
        bp->setBackgroundColor(m_strokePainter->backgroundColor());
        bp->setSelection(m_strokePainter->selection());
        if (m_strokePainter->gradient()) {
            bp->setGradient(m_strokePainter->gradient());
        }
        const int layerIndex = qBound(0, m_currentLayer, m_layers.size() - 1);
        bool fellBack = false;
        KisPaintOpSP op = createStrokePaintOp(bp, image, layerIndex, &fellBack);
        if (!op) {
            delete bp;
            if (m_mirrorLiveStarted) {
                return reverie::MirrorBatchAction::DropAccepted;
            }
            RPC_LOG("RPC mirrorOp create failed branch=%d -> replay fallback", branchIndex);
            return reverie::MirrorBatchAction::Reject;
        }
        cursor.painter = bp;
        cursor.op = op;
        m_mirrorLiveStarted = true;
        const KisPaintOp &createdOp = *cursor.op; // 先取引用: typeid(*智能指针) 会触发 -Wpotentially-evaluated-expression
        RPC_LOG("RPC mirrorOp created: branch=%d seq=%d presetId=%s actualOp=%s fellBack=%d",
                branchIndex, strokeSeq, opId.isEmpty() ? "none" : opId.toUtf8().constData(),
                typeid(createdOp).name(), fellBack ? 1 : 0);
    }

    KisPainter *bp = cursor.painter;
    // 每批从主笔 painter 同步 (主笔 flushStrokeBatch 每批也会重设这些, 中途改混合模式/
    // 颜色/选区时两边一致)。
    if (m_strokePainter) {
        bp->setCompositeOpId(m_strokePainter->compositeOpId());
        bp->setOpacityF(m_strokePainter->opacityF());
        bp->setPaintColor(m_strokePainter->paintColor());
        bp->setBackgroundColor(m_strokePainter->backgroundColor());
        bp->setSelection(m_strokePainter->selection());
    }

    QRectF batchBounds;
    if (cursor.hasLast) {
        batchBounds = QRectF(cursor.lastPos, QSizeF(1.0, 1.0));
    }
    bool paintedAny = false;
    for (int i = 0; i < count; ++i) {
        const float *s = samples + i * stride;
        const qreal x = s[0];
        const qreal y = s[1];
        if (std::isnan(x) || std::isnan(y) || !std::isfinite(x) || !std::isfinite(y)) {
            continue;
        }
        const qreal pressure = qBound<qreal>(0.0, qreal(s[2]), 1.0);
        const qreal tiltX = std::isfinite(s[3]) ? qreal(s[3]) : 0.0;
        const qreal tiltY = std::isfinite(s[4]) ? qreal(s[4]) : 0.0;
        const qreal rotation = std::isfinite(s[5]) ? qreal(s[5]) : 0.0;
        const qreal time = qMax<qreal>(0.0, qreal(s[6]));
        const QPointF pos(x, y);
        // 整笔包围盒兜底 (touchStrokeEnd 少报时整框补标) 也要认得镜像点; 不写入 m_strokeSamples。
        m_accumulatedStrokeBounds = m_accumulatedStrokeBounds.united(QRectF(x, y, 1.0, 1.0));
        const QRectF pr(x, y, 1.0, 1.0);
        batchBounds = batchBounds.isNull() ? pr : batchBounds.united(pr);

        if (!cursor.hasLast) {
            // 与主笔 m_strokeDistance = KisDistanceInformation(start, 0.0) 同构 (Krita
            // createPainters 也用起笔点初始化每个分支的 dragDistance)
            cursor.distance = KisDistanceInformation(pos, 0.0);
            KisPaintInformation info(pos, pressure, tiltX, tiltY, rotation, 0.0, 1.0,
                                     reverie::StrokeTimeContract::toKritaMs(time), 0.0);
            if (m_randomSource) info.setRandomSource(m_randomSource);
            if (m_perStrokeRandomSource) info.setPerStrokeRandomSource(m_perStrokeRandomSource);
            cursor.op->paintAt(info, &cursor.distance);
        } else {
            const qreal dist = QLineF(cursor.lastPos, pos).length();
            const qreal speed = reverie::StrokeTimeContract::speedPxPerMs(dist, cursor.lastTime, time);
            const qreal t0Ms = reverie::StrokeTimeContract::toKritaMs(cursor.lastTime);
            const qreal t1Ms = reverie::StrokeTimeContract::toKritaMs(time);
            KisPaintInformation infoLast(cursor.lastPos, cursor.lastPressure, cursor.lastTiltX,
                                         cursor.lastTiltY, cursor.lastRotation, 0.0, 1.0,
                                         t0Ms, speed);
            KisPaintInformation infoCur(pos, pressure, tiltX, tiltY, rotation, 0.0, 1.0, t1Ms, speed);
            if (m_randomSource) {
                infoLast.setRandomSource(m_randomSource);
                infoCur.setRandomSource(m_randomSource);
            }
            if (m_perStrokeRandomSource) {
                infoLast.setPerStrokeRandomSource(m_perStrokeRandomSource);
                infoCur.setPerStrokeRandomSource(m_perStrokeRandomSource);
            }
            cursor.op->paintLine(infoLast, infoCur, &cursor.distance);
        }

        cursor.hasLast = true;
        cursor.lastPos = pos;
        cursor.lastPressure = pressure;
        cursor.lastTiltX = tiltX;
        cursor.lastTiltY = tiltY;
        cursor.lastRotation = rotation;
        cursor.lastTime = time;
        paintedAny = true;
    }
    if (!paintedAny) {
        return action;
    }

    while (true) {
        QVector<KisRunnableStrokeJobData *> jobs;
        auto result = cursor.op->doAsynchronousUpdate(jobs);
        for (auto *j : jobs) {
            j->run();
            delete j;
        }
        if (jobs.isEmpty() || !result.second) {
            break;
        }
    }

    // 脏区: 与主笔 flushStrokeBatch 同规则 —— 逐块标脏 + 描边层外扩 + 少报/路径引擎兜底。
    const bool strokeLayer =
        (m_currentLayer >= 0 && m_currentLayer < m_layers.size() && m_layers[m_currentLayer].isStrokeLayer);
    const int strokeExtra = strokeLayer ? (m_layers[m_currentLayer].strokeSize + 4) : 0;
    bool hadInk = false;
    auto markBranchDirty = [&](const QRect &r) {
        if (r.isEmpty()) {
            return;
        }
        const QRect d = strokeExtra > 0 ? r.adjusted(-strokeExtra, -strokeExtra, strokeExtra, strokeExtra) : r;
        if (d.isEmpty()) {
            return;
        }
        markRegionDirty(d);
        hadInk = true;
    };
    const QVector<QRect> exactDirty = bp->takeDirtyRegion();
    QRect exactUnion;
    for (const QRect &r : exactDirty) {
        markBranchDirty(r);
        if (!r.isEmpty()) {
            exactUnion = exactUnion.isNull() ? r : exactUnion.united(r);
        }
    }
    const QByteArray branchIdBytes = opId.toLatin1();
    const bool isPathEngine = reverie::PaintOpTraits::isPathEngine(opView(branchIdBytes));
    const int w = int(m_brushSize) + 2;
    if (isPathEngine) {
        const int margin = int(m_brushSize) + 8;
        markBranchDirty(batchBounds.toAlignedRect().adjusted(-margin, -margin, margin, margin));
    } else if (reverie::mirrorDirtyNeedsFallback(exactUnion.isEmpty(), exactUnion.width(),
                                                 exactUnion.height(), m_brushSize)) {
        markBranchDirty(batchBounds.toAlignedRect().adjusted(-w, -w, w, w));
    }
    if (hadInk && m_currentLayer >= 0 && m_currentLayer < m_layers.size()) {
        bumpLayerThumbGen(m_layers[m_currentLayer].node);
    }
    return action;
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
        flushExperimentTail();
        endStrokeBatch();
        m_strokeBatchOpen = false;
    }

    // Finalize Indirect Painting (for Wash mode / Shape_fill / experimentbrush):
    // Blend the completed temporary scratch target onto the actual layer device
    // inside a single undo transaction.
    KisPaintLayer *pl = (m_currentLayer >= 0 && m_currentLayer < m_layers.size())
        ? dynamic_cast<KisPaintLayer *>(m_layers[m_currentLayer].node)
        : nullptr;
    bool committedTempTarget = false;
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
            // F-03 之后，间接绘制的笔画 painter 不带选区。这里是合并时的唯一一次裁剪。
            // setupTemporaryPainter 已经按 setTemporarySelection 设过一次；KisPainter::setSelection
            // 是替换不是叠加。保留这行，是为了笔画中途选区变化时合并用最新的 m_selection。
            if (m_selection) {
                gc.setSelection(m_selection);
            }
            gc.setChannelFlags(pl->alphaLocked() ? pl->channelLockFlags() : QBitArray());
            gc.bitBlt(ext.topLeft(), tempTarget, ext);
            gc.end();
            const int strokeMargin = strokeLayerMargin();
            markStrokeDirty(ext.adjusted(-strokeMargin, -strokeMargin, strokeMargin, strokeMargin));
            committedTempTarget = true;
            bumpLayerThumbGen(pl);
        }
        pl->setTemporaryTarget(nullptr);
        pl->setTemporaryChannelFlags(QBitArray());
        m_indirectTempCompositeOp.clear();
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
    if (endDev && m_document && !committedTempTarget) {
        const int margin = qMax(int(m_brushSize * 2.0), 32) + 16 + strokeLayerMargin();
        const QRect totalDirty = m_accumulatedStrokeBounds.toAlignedRect().adjusted(
            -margin, -margin, margin, margin).intersected(
            QRect(0, 0, m_document->width(), m_document->height()));
        if (!totalDirty.isEmpty()) {
            markStrokeDirty(totalDirty);
        }
    }

    // s12-F1/H3: 笔画已收尾, 迟到的镜像批一律按过期丢弃
    m_mirrorStrokeSeq = 0;
    m_mirrorLiveStarted = false;
    m_drawing = false;
}

void ReverieCore::touchStrokeCancel()
{
    // s12-F1/H3: 取消后迟到的镜像批一律按过期丢弃
    m_mirrorStrokeSeq = 0;
    m_mirrorLiveStarted = false;
    if (!m_document || !m_strokeBatchOpen) {
        m_drawing = false;
        m_strokeSamples.clear();
        m_strokeBatchOpen = false;
        releaseMirrorBranchOps(); // s12-C4: 早退路径也释放分支 painter/paintop
        return;
    }

    // 顺序: 先在图像仍被撤销宏锁住时回滚图层设备 (此时没有 worker), 再关宏, 最后才
    // setDirty + recompositeProjection。关宏 (endMacro -> unlock) 会立刻派发锁住期间
    // 入队的任务; 若先关宏, worker 会和 revert 并发读写同一图层设备。setDirty 也放到
    // 回滚之后, 免得未锁时 (非对称笔画) 合并任务和 revert 并发。
    // 锁住时 recompositeProjection 不会等 (会卡死), 所以它必须在关宏之后 (见 RenderLockGate.h)。
    // revert 不产生撤销命令, 关宏前后宏里的内容不变。
    QRect cancelTempDirty;
    bool cancelReverted = false;

    KisPaintLayer *pl = (m_currentLayer >= 0 && m_currentLayer < m_layers.size())
        ? dynamic_cast<KisPaintLayer *>(m_layers[m_currentLayer].node)
        : nullptr;
    if (pl && pl->hasTemporaryTarget()) {
        KisPaintDeviceSP tempTarget = pl->temporaryTarget();
        const QRect ext = tempTarget->exactBounds();
        pl->setTemporaryTarget(nullptr);
        m_indirectTempCompositeOp.clear();
        tempTarget->clear();
        if (!ext.isEmpty()) {
            cancelTempDirty = ext;
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
        cancelReverted = true;
    }

    while (m_macroDepth > 0) {
        if (m_document && m_document->undoAdapter()) {
            m_document->undoAdapter()->endMacro();
        }
        m_macroDepth--;
    }

    if (!cancelTempDirty.isEmpty() && pl) {
        pl->setDirty(cancelTempDirty);
        markRegionDirty(cancelTempDirty);
    }
    // The reverted tiles changed the device back: recomposite so the
    // ghost stroke disappears from the projection/display too
    if (cancelReverted && m_document) {
        if (KisPaintDeviceSP target = currentPaintDevice()) {
            target->setDirty();
        }
    }
    if (!cancelTempDirty.isEmpty() || cancelReverted) {
        recompositeProjection();
        markDirty();
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
    const QByteArray spacingIdBytes = opId.toLatin1();
    const bool isPathEngine = reverie::PaintOpTraits::usesFineSpacing(opView(spacingIdBytes));
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
    } else if (m_replayPixelsPerSecond > 0.0) {
        double lastTime = m_strokeSamples.isEmpty() ? 0.0 : m_strokeSamples.last().time;
        QPointF lastPos = m_strokeSamples.isEmpty() ? m_strokeStartImg : m_strokeSamples.last().imgPos;
        double dist = std::hypot(imgPos.x() - lastPos.x(), imgPos.y() - lastPos.y());
        s.time = lastTime + dist / m_replayPixelsPerSecond;
    } else {
        s.time = m_strokeTimer.elapsed() / 1000.0;
    }
    m_strokeSamples.append(s);
    // 实时绘制由 JNI touchStrokeMoveBatch 在本批样本全部入队后统一 flush 一次; 这里只在单批
    // 攒到 32 个样本时分段防溢出。原先另有 ">= 4ms 提前 flush": 批内首个样本常常单独被冲掉,
    // 一帧里 flush 两次 (第二次只有剩下的几个点), 白白多一轮 dab 渲染与脏区上报。
    // 回放模式按记录的仿真相对时间步进 (>8ms 或 32 样本)，确保 1x/32x/seek 分批完全确定性对齐。
    const bool shouldFlush = (timeSeconds >= 0.0)
        ? ((s.time - m_lastSimulatedFlushTime >= 0.008) || m_strokeSamples.size() >= 32)
        : (m_strokeSamples.size() >= 32);
    if (shouldFlush) {
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
            m_indirectTempCompositeOp = effectiveOp;
            pl->setTemporaryOpacity(qBound<qreal>(0.0, m_strokeOpacity, 1.0));
            pl->setTemporarySelection(m_selection);
            pl->setTemporaryChannelFlags(pl->alphaLocked() ? pl->channelLockFlags() : QBitArray());
        }
        target = pl->temporaryTarget();
    } else {
        target = currentPaintDevice();
    }
    // One declaration for the rest of this flush. Indirect paint leaves the stroke
    // painter unclipped; preview and pen-up merge each clip once.
    const bool tempTargetClips = needsIndirect && pl && pl->hasTemporaryTarget();

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
        // interstrokeData (湿笔颜料厚度高度图) 跨笔保留 (见 endStrokeBatch), 所以换成
        // 非 colorsmudge 笔刷时在这里丢弃: 留着会让下次切回湿笔时按陈旧高度图累积厚度。
        // 换图层天然安全: 高度图挂在各自的 KisPaintDevice 上。
        if (!isSmudgeOp && target->interstrokeData()) {
            KUndo2Command *cmd = target->createChangeInterstrokeDataCommand(KisInterstrokeDataSP());
            if (cmd) {
                cmd->redo();
                delete cmd;
            }
        }
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
            // 只有 colorsmudge 实现了 createInterstrokeDataFactory (其余引擎的基类实现
            // 返回 nullptr), 不再对所有笔刷在落笔首帧查一次注册表、构造临时配置。
            if (m_brushPreset && isColorSmudge) {
                KisPaintOpFactory *f = KisPaintOpRegistry::instance()->value(QStringLiteral("colorsmudge"));
                if (f) {
                    interstrokeDataFactory =
                        f->createInterstrokeDataFactory(m_brushPreset->settings(), m_brushPreset->resourcesInterface());
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

            // Direct paint clips here. Indirect paint clips later, once.
            if (m_selection && !tempTargetClips) {
                m_strokePainter->setSelection(m_selection);
            } else {
                m_strokePainter->setSelection(KisSelectionSP());
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
                if (factory && !target->interstrokeData()) {
                    // 同一图层连续用同一支湿笔时复用既有高度图; 不兼容时的替换由上面
                    // KisInterstrokeDataTransactionWrapperFactory 的兼容性判定负责。
                    KUndo2Command *cmd = target->createChangeInterstrokeDataCommand(toQShared(factory->create(target)));
                    if (cmd) {
                        cmd->redo();
                        delete cmd;
                    }
                }
                // s12: 建 op 的三档顺序 (colorsmudge 工厂 -> 注册表 -> KisBrushOp 兜底) 抽成
                // createStrokePaintOp, 镜像分支用同一函数各建一个独立实例。
                // 最后一档兜底: 引擎未注册时静默退化成 KisBrushOp 会让笔迹完全不对
                // 且毫无提示 (本机 mypaintbrush / dynabrush 就是这种情况)。这里显式
                // 打一条警告, 让"这支笔为什么画得不对"可以直接从 logcat 看出来。
                bool engineFellBack = false;
                m_strokeOp = createStrokePaintOp(m_strokePainter, image, layerIndex, &engineFellBack);
                if (engineFellBack) {
                    RPC_LOG("RPC strokeOp UNSUPPORTED engine=%s -> fell back to KisBrushOp (engine not registered in this build)",
                            m_brushPreset->paintOp().id().toUtf8().constData());
                }
                const KisPaintOp *actualOp = m_strokeOp.data();
                const char *actualOpName = actualOp ? typeid(*actualOp).name() : "null";
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
        if (m_selection && !tempTargetClips) {
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
            KisPaintInformation info(p, pressure, first.tiltX, first.tiltY, first.rotation, 0.0, 1.0,
                                     reverie::StrokeTimeContract::toKritaMs(first.time), 0.0);
            if (m_randomSource) info.setRandomSource(m_randomSource);
            if (m_perStrokeRandomSource) info.setPerStrokeRandomSource(m_perStrokeRandomSource);
            m_maskedStrokePainter->paintAt(info);
            while (true) {
                QVector<KisRunnableStrokeJobData *> jobs;
                auto result = m_maskedStrokePainter->doAsynchronousUpdate(jobs);
                runStrokeJobsBatch(jobs, m_brushSize);
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
            KisPaintInformation info(p, pressure, first.tiltX, first.tiltY, first.rotation, 0.0, 1.0,
                                     reverie::StrokeTimeContract::toKritaMs(first.time), 0.0);
            if (m_randomSource) info.setRandomSource(m_randomSource);
            if (m_perStrokeRandomSource) info.setPerStrokeRandomSource(m_perStrokeRandomSource);
            m_strokeOp->paintAt(info, m_strokeDistance);
            while (true) {
                QVector<KisRunnableStrokeJobData *> jobs;
                auto result = m_strokeOp->doAsynchronousUpdate(jobs);
                runStrokeJobsBatch(jobs, m_brushSize);
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
        markStrokeDirty(strokeDirty.isNull() ? tr : strokeDirty);
        bumpLayerThumbGen(m_layers[m_currentLayer].node);
        m_strokeCarryCount = 1;
        return true;
    }

    QRect strokeDirty;
    // 本批每个 dab 的脏矩形先在本地有界列表里合并 (上限 8 块, 规则见 DirtyRectList.h),
    // 批末逐块并入引擎脏区, 不再先合并成一个总包围盒。strokeDirty 仍是本批并集,
    // 只用来判断本批是否落墨。
    reverie::DirtyRectList batchDirty;
    const auto addBatchDirty = [&](const QRect &r) {
        strokeDirty = strokeDirty.isNull() ? r : strokeDirty.united(r);
        reverie::appendOrCoalesce(batchDirty, reverie::DirtyRect{r.x(), r.y(), r.width(), r.height()});
    };
    if (m_maskedStrokePainter) {
        const int firstNewSegment = qBound(1, m_strokeCarryCount, qMax(1, m_strokeSamples.size()));
        for (int i = firstNewSegment; i < m_strokeSamples.size(); ++i) {
            const StrokeSample &a = m_strokeSamples[i - 1];
            const StrokeSample &b = m_strokeSamples[i];
            const qreal dist = QLineF(a.imgPos, b.imgPos).length();
            // Speed in px/ms to align with Krita's sensor scale. Sample times stay in seconds.
            const qreal speed = reverie::StrokeTimeContract::speedPxPerMs(dist, a.time, b.time);
            KisPaintInformation infoA(a.imgPos, a.pressure, a.tiltX, a.tiltY, a.rotation, 0.0, 1.0,
                                      reverie::StrokeTimeContract::toKritaMs(a.time), speed);
            KisPaintInformation infoB(b.imgPos, b.pressure, b.tiltX, b.tiltY, b.rotation, 0.0, 1.0,
                                      reverie::StrokeTimeContract::toKritaMs(b.time), speed);
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
            runStrokeJobsBatch(jobs, m_brushSize);
            if (jobs.isEmpty() || !result.second) {
                break;
            }
        }
        if (m_maskingBrushRenderer) {
            const QVector<QRect> exactDirty = m_maskedStrokePainter->takeDirtyRegion();
            for (const QRect &r : exactDirty) {
                m_maskingBrushRenderer->updateProjection(r);
                addBatchDirty(r);
            }
        }
    } else if (m_brushPreset && m_strokeOp && m_strokePainter) {
        // ---- Real Krita brush engine ----
        KisPainter &painter = *m_strokePainter;
        const QString opId = (m_toolMode == ToolSmudge) ? QStringLiteral("colorsmudge") : m_brushPreset->paintOp().id();
        const QByteArray idBytes = opId.toLatin1();
        const std::string_view id = opView(idBytes);
        const bool isPathEngine = reverie::PaintOpTraits::isPathEngine(id);
        const bool engineBypassesSelection = !reverie::PaintOpTraits::honorsPainterSelection(id);
        QByteArray selClipBefore;
        QRect selClipBox;
        const bool needsSelSnapshot = m_selection && !tempTargetClips && engineBypassesSelection;
        if (needsSelSnapshot) {
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
            // Speed in px/ms to align with Krita's sensor scale. Sample times stay in seconds.
            const qreal speed = reverie::StrokeTimeContract::speedPxPerMs(dist, a.time, b.time);
            KisPaintInformation infoA(a.imgPos, a.pressure, a.tiltX, a.tiltY, a.rotation, 0.0, 1.0,
                                      reverie::StrokeTimeContract::toKritaMs(a.time), speed);
            KisPaintInformation infoB(b.imgPos, b.pressure, b.tiltX, b.tiltY, b.rotation, 0.0, 1.0,
                                      reverie::StrokeTimeContract::toKritaMs(b.time), speed);
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
            runStrokeJobsBatch(jobs, m_brushSize);
            if (jobs.isEmpty() || !result.second) {
                break;
            }
        }
        // Restore the pixels outside the selection for engines that bypass
        // KisPainter's selection clipping
        if (needsSelSnapshot && !selClipBox.isEmpty() &&
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
        if (isPathEngine && !reverie::PaintOpTraits::reportsExactDirty(id)) {
            const int margin = int(m_brushSize) + 8;
            const QRect pathDirty = m_accumulatedStrokeBounds.toAlignedRect().adjusted(-margin, -margin, margin, margin);
            addBatchDirty(pathDirty);
        }
        const QVector<QRect> exactDirty = painter.takeDirtyRegion();
        QRect exactUnion;
        for (const QRect &r : exactDirty) {
            addBatchDirty(r);
            if (!r.isEmpty()) {
                exactUnion = exactUnion.isNull() ? r : exactUnion.united(r);
            }
        }
        // 部分引擎 (鬃毛/散布类) 的 takeDirtyRegion() 不为空, 却只回报几像素见方的中心块:
        // 按它标脏, 运笔期间画布上缺墨, 要等抬笔整笔刷新才出现。并集明显小于笔径时
        // 视为低报, 和"引擎不回报"同样按样本段兜底标脏。
        const int minExpectedSpan = qMax(4, int(m_brushSize * 0.25));
        const bool exactUnderReported = !exactDirty.isEmpty() && m_brushSize >= 6.0 &&
            (exactUnion.width() < minExpectedSpan || exactUnion.height() < minExpectedSpan);
        if (!isPathEngine && (engineBypassesSelection || exactDirty.isEmpty() || exactUnderReported)) {
            // 逐段 (相邻两个样本) 各标一块, 不并成整批包围盒: 斜向长笔画的包围盒
            // 远大于实际落墨, 而离散列表本身会按上限合并相邻块。
            const int startIndex = qMax(0, firstNewSegment - 1);
            const int w = int(m_brushSize) + 2;
            if (startIndex + 1 < m_strokeSamples.size()) {
                for (int si = startIndex + 1; si < m_strokeSamples.size(); ++si) {
                    const QPointF &pa = m_strokeSamples[si - 1].imgPos;
                    const QPointF &pb = m_strokeSamples[si].imgPos;
                    const int minX = int(qMin(pa.x(), pb.x()));
                    const int maxX = int(qMax(pa.x(), pb.x()));
                    const int minY = int(qMin(pa.y(), pb.y()));
                    const int maxY = int(qMax(pa.y(), pb.y()));
                    addBatchDirty(QRect(minX - w, minY - w, (maxX - minX) + 2 * w, (maxY - minY) + 2 * w));
                }
            } else if (startIndex < m_strokeSamples.size()) {
                const QPointF &p0 = m_strokeSamples[startIndex].imgPos;
                addBatchDirty(QRect(int(p0.x()) - w, int(p0.y()) - w, 2 * w, 2 * w));
            }
        }
    } else if (m_strokePainter) {
        // ---- Fallback: classic round-dab loop (no preset loaded) ----
        KisPainter &painter = *m_strokePainter;
        const auto addDab = [&](const QPointF &p, qreal w) {
            painter.paintEllipse(QRectF(p.x() - w / 2.0, p.y() - w / 2.0, w, w));
            const QRect r(int(p.x()) - int(w) - 1, int(p.y()) - int(w) - 1,
                          2 * int(w) + 2, 2 * int(w) + 2);
            addBatchDirty(r);
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
        const bool strokeLayer = m_currentLayer >= 0 && m_currentLayer < m_layers.size() &&
                                 m_layers[m_currentLayer].isStrokeLayer;
        const int extra = strokeLayer ? m_layers[m_currentLayer].strokeSize + 4 : 0;
        const QRect docRect(0, 0, m_docWidth, m_docHeight);
        for (int i = 0; i < batchDirty.count; ++i) {
            const reverie::DirtyRect &d = batchDirty.rects[i];
            const QRect r = QRect(d.x, d.y, d.w, d.h).adjusted(-extra, -extra, extra, extra).intersected(docRect);
            if (!r.isEmpty()) {
                m_dirtyRect = m_dirtyRect.isNull() ? r : m_dirtyRect.united(r);
                addDirtyRegion(r);
            }
        }
        if (m_dirtyCb) m_dirtyCb(m_dirtyCtx);
        // gpu-render-v2: 本批落墨则记录 (原实现为 if (strokeHadInk) m_lastFlushHadInk = true,
        // 本实现的 strokeHadInk 等价于 strokeDirty 非空), 供 Stash 事务恢复判断
        m_lastFlushHadInk = true;
        bumpLayerThumbGen(m_layers[m_currentLayer].node);
    }
    return !strokeDirty.isNull();
}

int ReverieCore::strokeLayerMargin() const
{
    if (m_currentLayer >= 0 && m_currentLayer < m_layers.size()
        && m_layers[m_currentLayer].isStrokeLayer) {
        return m_layers[m_currentLayer].strokeSize + 4;
    }
    return 0;
}

namespace {

void paintTailSegment(KisPaintOp *op, KisDistanceInformation *distance,
                      const QPointF &pos, qreal pressure, qreal tiltX, qreal tiltY,
                      qreal rotation, qreal timeSeconds,
                      KisRandomSourceSP randomSource,
                      KisPerStrokeRandomSourceSP perStrokeRandom)
{
    if (!op || !distance) {
        return;
    }
    const qreal t0 = reverie::StrokeTimeContract::toKritaMs(timeSeconds);
    const qreal t1 = t0 + 41.0;
    KisPaintInformation a(pos, pressure, tiltX, tiltY, rotation, 0.0, 1.0, t0, 0.0);
    KisPaintInformation b(pos, pressure, tiltX, tiltY, rotation, 0.0, 1.0, t1, 0.0);
    if (randomSource) {
        a.setRandomSource(randomSource);
        b.setRandomSource(randomSource);
    }
    if (perStrokeRandom) {
        a.setPerStrokeRandomSource(perStrokeRandom);
        b.setPerStrokeRandomSource(perStrokeRandom);
    }
    op->paintLine(a, b, distance);
}

} // namespace

void ReverieCore::flushExperimentTail()
{
    // Smudge swaps the live op to colorsmudge while the preset id stays experimentbrush.
    // A tail flush would paint an extra +41ms segment the main path never drew.
    if (m_toolMode == ToolSmudge || !m_strokeOp || !m_strokePainter || m_maskedStrokePainter
        || m_strokeUsedQuickPath || m_strokeSamples.isEmpty()
        || !m_brushPreset || !m_brushPreset->settings()) {
        return;
    }
    const QByteArray idBytes = m_brushPreset->paintOp().id().toLatin1();
    const bool displacementEnabled = m_brushPreset->settings()->getBool(
        QStringLiteral("Experiment/displacementEnabled"), false);
    const bool speedEnabled = m_brushPreset->settings()->getBool(
        QStringLiteral("Experiment/speedEnabled"), false);
    if (!reverie::StrokeTailFlush::allowed(
            m_fillTailFlushEnabled,
            std::string_view(idBytes.constData(), size_t(idBytes.size())),
            displacementEnabled, speedEnabled)) {
        return;
    }
    const StrokeSample &last = m_strokeSamples.last();
    paintTailSegment(m_strokeOp.data(), m_strokeDistance, last.imgPos, last.pressure,
                     last.tiltX, last.tiltY, last.rotation, last.time,
                     m_randomSource, m_perStrokeRandomSource);
    while (true) {
        QVector<KisRunnableStrokeJobData *> jobs;
        auto result = m_strokeOp->doAsynchronousUpdate(jobs);
        runStrokeJobsBatch(jobs, m_brushSize);
        if (jobs.isEmpty() || !result.second) {
            break;
        }
    }
    for (MirrorBranchCursor &cursor : m_mirrorCursor) {
        if (!cursor.op || !cursor.hasLast) {
            continue;
        }
        paintTailSegment(cursor.op.data(), &cursor.distance, cursor.lastPos, cursor.lastPressure,
                         cursor.lastTiltX, cursor.lastTiltY, cursor.lastRotation, cursor.lastTime,
                         m_randomSource, m_perStrokeRandomSource);
        while (true) {
            QVector<KisRunnableStrokeJobData *> jobs;
            auto result = cursor.op->doAsynchronousUpdate(jobs);
            runStrokeJobsBatch(jobs, m_brushSize);
            if (jobs.isEmpty() || !result.second) {
                break;
            }
        }
    }
}

void ReverieCore::endStrokeBatch()
{
    // 不再在每笔结束时清空 interstrokeData: 原先同一图层连续画湿笔时每笔都要
    // "销毁旧高度图 -> 分配新高度图", 既白付重建开销, 又破坏跨笔颜料厚度混合。
    // 与 Krita 一致, 只在换笔刷引擎 (flushStrokeBatch 的笔画准备段) 或不兼容
    // (KisInterstrokeDataTransactionWrapperFactory) 时丢弃。
    delete m_maskedStrokePainter;
    m_maskedStrokePainter = nullptr;
    delete m_strokeInfo;
    m_strokeInfo = nullptr;
    delete m_maskInfo;
    m_maskInfo = nullptr;
    delete m_maskingBrushRenderer;
    m_maskingBrushRenderer = nullptr;

    // s12: 镜像分支 painter/paintop 指向同一目标设备, 必须和主笔 painter 同生共死
    // (切设备重建、结束、取消、换文档都走这里)。
    releaseMirrorBranchOps();

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

int ReverieCore::undoCount() const
{
    return m_undoStore ? m_undoStore->index() : 0;
}

int ReverieCore::redoCount() const
{
    return m_undoStore ? m_undoStore->count() - m_undoStore->index() : 0;
}

void ReverieCore::prepareHistoryReplay()
{
    if (m_strokeBatchOpen) {
        touchStrokeCancel();
    }
    // touchStrokeCancel only closes the macro when a stroke batch is open.
    // A macro left open otherwise makes KUndo2QStack refuse the replay and
    // keeps the image lock held.
    while (m_macroDepth > 0) {
        endUndoMacro();
    }
}

void ReverieCore::replayHistoryCommand(bool redoOp)
{
    // KisLegacyUndoAdapter locks the image only when pushing a command.
    // Replaying without the barrier lets KisTransactionData::setDirty run
    // the async merger against the same tile mementos commit() is installing.
    const bool takeLock = m_document && !m_document->locked();
    if (takeLock) {
        m_document->barrierLock();
    }
    if (redoOp) {
        m_undoStore->redo();
        --m_redoCount;
    } else {
        m_undoStore->undo();
        ++m_redoCount;
    }
    if (takeLock) {
        m_document->unlock();
    }
    // RenderLockGate: waitForDone spins forever while the image is locked.
    if (m_document && !m_document->locked()) {
        m_document->waitForDone();
    }
}

void ReverieCore::undo()
{
    prepareHistoryReplay();
    if (!m_undoStore || !m_document || !canUndo()) {
        return;
    }
    // An open restore transaction leaves the paint device's memento index
    // dirty. rollforward requires a clean index; drop the uncommitted patch
    // only once we know this call will actually replay.
    if (m_openRestoreTransaction) {
        revertOpenRestorePatch();
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

    {
        std::lock_guard<std::mutex> lock(m_asyncUpdateMutex);
        m_lastAsyncUpdateRect = QRect();
    }
    replayHistoryCommand(false);
    syncLayersFromImage();

    const int newW = m_document->width();
    const int newH = m_document->height();
    const bool sizeChanged = (newW != m_docWidth || newH != m_docHeight);
    if (sizeChanged) {
        m_docWidth = newW;
        m_docHeight = newH;
        m_renderBufW = -1;
        m_renderBufH = -1;
        setDirtyFullFrame();
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
    for (const auto &e : m_layers) {
        if (e.node) {
            auto dev = e.node->paintDevice();
            if (dev && dev->interstrokeData()) {
                KUndo2Command *cmd = dev->createChangeInterstrokeDataCommand(KisInterstrokeDataSP());
                if (cmd) {
                    cmd->redo();
                    delete cmd;
                }
            }
        }
    }

    m_nativeProjectionValid = true;
    QRect updateRect;
    {
        std::lock_guard<std::mutex> lock(m_asyncUpdateMutex);
        updateRect = m_lastAsyncUpdateRect;
        m_lastAsyncUpdateRect = QRect();
    }
    if (!updateRect.isEmpty() && !structureChanged) {
        const QRect r = updateRect.intersected(QRect(0, 0, m_docWidth, m_docHeight));
        invalidateStaticUnderlayRect(r);
        markRegionDirty(r);
        for (auto it = m_thumbCache.begin(); it != m_thumbCache.end(); ++it) {
            ++it->gen;
        }
    } else {
        markDirty();
    }
    m_snapshotPending = false;
}

void ReverieCore::redo()
{
    prepareHistoryReplay();
    if (!m_undoStore || !m_document || !canRedo()) {
        return;
    }
    if (m_openRestoreTransaction) {
        revertOpenRestorePatch();
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

    {
        std::lock_guard<std::mutex> lock(m_asyncUpdateMutex);
        m_lastAsyncUpdateRect = QRect();
    }
    replayHistoryCommand(true);
    syncLayersFromImage();

    const int newW = m_document->width();
    const int newH = m_document->height();
    const bool sizeChanged = (newW != m_docWidth || newH != m_docHeight);
    if (sizeChanged) {
        m_docWidth = newW;
        m_docHeight = newH;
        m_renderBufW = -1;
        m_renderBufH = -1;
        setDirtyFullFrame();
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
    for (const auto &e : m_layers) {
        if (e.node) {
            auto dev = e.node->paintDevice();
            if (dev && dev->interstrokeData()) {
                KUndo2Command *cmd = dev->createChangeInterstrokeDataCommand(KisInterstrokeDataSP());
                if (cmd) {
                    cmd->redo();
                    delete cmd;
                }
            }
        }
    }

    m_nativeProjectionValid = true;
    QRect updateRect;
    {
        std::lock_guard<std::mutex> lock(m_asyncUpdateMutex);
        updateRect = m_lastAsyncUpdateRect;
        m_lastAsyncUpdateRect = QRect();
    }
    if (!updateRect.isEmpty() && !structureChanged) {
        const QRect r = updateRect.intersected(QRect(0, 0, m_docWidth, m_docHeight));
        invalidateStaticUnderlayRect(r);
        markRegionDirty(r);
        for (auto it = m_thumbCache.begin(); it != m_thumbCache.end(); ++it) {
            ++it->gen;
        }
    } else {
        markDirty();
    }
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
    // elapsed() is already milliseconds. Do not pass it through toKritaMs.
    const qreal tickMs = qMax<qreal>(0.0, qreal(m_strokeTimer.elapsed()));
    KisPaintInformation info(p, pressure, tiltX, tiltY, rotation, 0.0, 1.0, tickMs, 0.0);
    if (m_randomSource) info.setRandomSource(m_randomSource);
    if (m_perStrokeRandomSource) info.setPerStrokeRandomSource(m_perStrokeRandomSource);

    QRect tickDirty;
    if (m_maskedStrokePainter) {
        m_maskedStrokePainter->paintAt(info);
        while (true) {
            QVector<KisRunnableStrokeJobData *> jobs;
            auto result = m_maskedStrokePainter->doAsynchronousUpdate(jobs);
            runStrokeJobsBatch(jobs, m_brushSize);
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
            runStrokeJobsBatch(jobs, m_brushSize);
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
        markStrokeDirty(tickDirty);
    }
    return true;
}

void ReverieCore::setStrokeReplayClock(double pixelsPerSecond)
{
    m_replayPixelsPerSecond = (pixelsPerSecond > 0.0) ? pixelsPerSecond : 0.0;
}

void ReverieCore::setStrokeReplaySeed(int seed)
{
    m_replaySeed = seed;
}

int ReverieCore::currentStrokeSeed()
{
    return (m_replaySeed >= 0) ? m_replaySeed : int(quint32(m_strokeCounter) * 1664525u + 1013904223u);
}

bool ReverieCore::stashOpenStrokePatch()
{
    if (!m_document) return false;
    KisPaintLayer *pl = (m_currentLayer >= 0 && m_currentLayer < m_layers.size())
        ? dynamic_cast<KisPaintLayer *>(m_layers[m_currentLayer].node)
        : nullptr;
    if (!pl) return false;

    KisPaintDeviceSP srcDevice = nullptr;
    bool isIndirect = false;
    if (pl->hasTemporaryTarget() && pl->temporaryTarget() && !pl->temporaryTarget()->exactBounds().isEmpty()) {
        srcDevice = pl->temporaryTarget();
        isIndirect = true;
    } else {
        srcDevice = pl->paintDevice();
        isIndirect = false;
    }
    if (!srcDevice) return false;

    const int margin = qMax(int(m_brushSize * 2.0), 32) + 16;
    QRect strokeRect = m_accumulatedStrokeBounds.toAlignedRect().adjusted(-margin, -margin, margin, margin);
    QRect docRect(0, 0, m_document->width(), m_document->height());
    QRect cropRect = strokeRect.intersected(docRect);

    if (cropRect.isEmpty() || cropRect.width() <= 0 || cropRect.height() <= 0) {
        return false;
    }

    qint64 pixelCount = qint64(cropRect.width()) * qint64(cropRect.height());
    if (pixelCount > 16000000) {
        return false;
    }

    const int pixelSize = srcDevice->pixelSize();
    qint64 byteCount = pixelCount * pixelSize;
    m_strokeStash.bounds = cropRect;
    m_strokeStash.bytes.resize(byteCount);
    srcDevice->readBytes(reinterpret_cast<quint8 *>(m_strokeStash.bytes.data()), cropRect);
    m_strokeStash.valid = true;
    m_strokeStash.wasIndirect = isIndirect;
    return true;
}

bool ReverieCore::restoreStashedStrokePatchOpen()
{
    if (!m_strokeStash.valid || m_strokeStash.bounds.isEmpty() || m_strokeStash.bytes.isEmpty()) {
        return false;
    }
    KisPaintLayer *pl = (m_currentLayer >= 0 && m_currentLayer < m_layers.size())
        ? dynamic_cast<KisPaintLayer *>(m_layers[m_currentLayer].node)
        : nullptr;
    if (!pl || !pl->paintDevice()) return false;

    if (m_openRestoreTransaction) {
        m_openRestoreTransaction->revert();
        delete m_openRestoreTransaction;
        m_openRestoreTransaction = nullptr;
    }

    m_openRestoreTransaction = new KisTransaction(pl->paintDevice());
    if (m_strokeStash.wasIndirect) {
        KisPaintDeviceSP tempDevice = new KisPaintDevice(pl->colorSpace());
        tempDevice->writeBytes(reinterpret_cast<const quint8 *>(m_strokeStash.bytes.constData()), m_strokeStash.bounds);
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
        gc.bitBlt(m_strokeStash.bounds.topLeft(), tempDevice, m_strokeStash.bounds);
        gc.end();
    } else {
        pl->paintDevice()->writeBytes(reinterpret_cast<const quint8 *>(m_strokeStash.bytes.constData()), m_strokeStash.bounds);
    }
    pl->paintDevice()->setDirty(m_strokeStash.bounds);
    markRegionDirty(m_strokeStash.bounds);
    recompositeProjection();
    markDirty();
    return true;
}

void ReverieCore::commitStashedStrokePatch()
{
    if (m_openRestoreTransaction) {
        if (m_undoCaptureEnabled && m_document && m_document->undoAdapter()) {
            m_openRestoreTransaction->commit(m_document->undoAdapter());
        } else {
            KUndo2Command *cmd = m_openRestoreTransaction->endAndTake();
            delete cmd;
        }
        delete m_openRestoreTransaction;
        m_openRestoreTransaction = nullptr;
    }
    m_strokeStash.valid = false;
    m_strokeStash.wasIndirect = false;
    m_strokeStash.bytes.clear();
    m_strokeStash.bounds = QRect();
}

void ReverieCore::revertOpenRestorePatch()
{
    if (m_openRestoreTransaction) {
        m_openRestoreTransaction->revert();
        delete m_openRestoreTransaction;
        m_openRestoreTransaction = nullptr;
        if (KisPaintDeviceSP target = currentPaintDevice()) {
            target->setDirty();
        }
        recompositeProjection();
        markDirty();
    }
}

void ReverieCore::discardStashedStrokePatch()
{
    revertOpenRestorePatch();
    m_strokeStash.valid = false;
    m_strokeStash.wasIndirect = false;
    m_strokeStash.bytes.clear();
    m_strokeStash.bounds = QRect();
}

// Fast ARM NEON SIMD blitter to convert Krita's native BGRA/ARGB32 projection bytes
// to Android Bitmap RGBA_8888 byte format with hardware vector acceleration.

