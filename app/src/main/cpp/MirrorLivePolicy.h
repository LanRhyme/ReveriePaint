/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * MirrorLivePolicy.h - 对称镜像分支实时出墨的判定 (纯函数, 无 Qt / Krita 依赖)
 *
 * s12: 仿 Krita KisToolMultihand / KisFreehandStrokeInfo, 每个镜像分支有自己的
 * KisPainter + KisPaintOp, 与主笔画在同一个设备、同一个 KisTransaction 里。
 * 这里只放"这一批镜像点该怎么处理"的决策, 便于宿主机 g++ 单测
 * (app/src/test/cpp/MirrorLivePolicyTest.cpp)。
 *
 * 关键不变式: 一笔里只要有任何一支镜像分支已经实时出过墨 (liveStarted),
 * 之后绝不再向 Kotlin 报"拒绝"。拒绝会让 Kotlin 在抬笔时重放整支镜像, 而已经
 * 实时画进图层的那部分墨就会被画第二遍。
 */
#pragma once

namespace reverie {

enum class MirrorBatchAction {
    Paint,          // 分支已有 paintop: 直接画
    CreateAndPaint, // 分支还没有 paintop 且本笔可实时: 建 painter + paintop 再画
    DropAccepted,   // 不能画, 但本笔已有实时镜像墨: 丢弃这一批并报"接受" (不可回退)
    Reject,         // 不能画, 本笔还没有任何实时镜像墨: 报"拒绝", Kotlin 改走抬笔重放
    Stale,          // s12-F1: 这批点不属于引擎当前打开的笔画 (笔画号不符 / 笔触已关): 丢弃,
                    // 不改 Kotlin 状态 (快速连笔时上一笔收尾 drain 不会把下一笔的点画进来)
};

/**
 * @param strokeOpen   引擎里有打开的笔触 (m_drawing && m_strokeBatchOpen)
 * @param seqMatches   这批点的笔画号 > 0 且等于引擎当前笔画号 (m_mirrorStrokeSeq)
 * @param branchHasOp  该分支已经建好 paintop
 * @param eligible     主笔当前状态允许新建分支 paintop (真 Krita 引擎, 非快速路径,
 *                     非双笔刷蒙版, 非"引擎绕过选区且有选区")
 * @param liveStarted  本笔已有任一分支建过 paintop
 */
inline MirrorBatchAction decideMirrorBatch(bool strokeOpen, bool seqMatches, bool branchHasOp, bool eligible,
                                           bool liveStarted)
{
    if (!strokeOpen || !seqMatches) {
        return MirrorBatchAction::Stale;
    }
    if (branchHasOp) {
        return MirrorBatchAction::Paint;
    }
    if (eligible) {
        return MirrorBatchAction::CreateAndPaint;
    }
    return liveStarted ? MirrorBatchAction::DropAccepted : MirrorBatchAction::Reject;
}

/** 接受 (实时出墨, 含不可回退的 DropAccepted)。Reject / Stale 都不算接受。 */
inline bool mirrorBatchAccepted(MirrorBatchAction a)
{
    return a == MirrorBatchAction::Paint || a == MirrorBatchAction::CreateAndPaint ||
        a == MirrorBatchAction::DropAccepted;
}

/** 返回给 JNI / Kotlin 的码: 1 = 接受, 0 = 拒绝 (走重放), -1 = 过期 (丢弃, 不改状态)。
 *  与 PaintViewModel.MIRROR_BATCH_ACCEPTED / REJECTED / STALE 一致。 */
inline int mirrorBatchResultCode(MirrorBatchAction a)
{
    if (a == MirrorBatchAction::Stale) {
        return -1;
    }
    return mirrorBatchAccepted(a) ? 1 : 0;
}

/**
 * 与主笔 flushStrokeBatch 的 ADR-0002/0005 防护同一判据: 引擎回报的精确脏区
 * 为空, 或并集比 max(4, 0.25 * 笔径) 还窄 (笔径 >= 6), 就需要按样本线段补标脏区。
 */
inline bool mirrorDirtyNeedsFallback(bool exactEmpty, int unionW, int unionH, double brushSize)
{
    if (exactEmpty) {
        return true;
    }
    if (brushSize < 6.0) {
        return false;
    }
    int minSpan = int(brushSize * 0.25);
    if (minSpan < 4) {
        minSpan = 4;
    }
    return unionW < minSpan || unionH < minSpan;
}

} // namespace reverie
