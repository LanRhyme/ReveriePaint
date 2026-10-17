/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * MirrorLivePolicyTest.cpp - 宿主机单测 (无 Qt / 无 Android / 无 Krita)
 *
 *   g++ -std=c++17 -Wall -Wextra -I app/src/main/cpp \
 *       app/src/test/cpp/MirrorLivePolicyTest.cpp -o /tmp/mirror_policy_test && /tmp/mirror_policy_test
 */
#include "MirrorLivePolicy.h"

#include <cstdio>

using reverie::MirrorBatchAction;
using reverie::decideMirrorBatch;
using reverie::mirrorBatchAccepted;
using reverie::mirrorBatchResultCode;
using reverie::mirrorDirtyNeedsFallback;

static int g_pass = 0;
static int g_fail = 0;

#define CHECK(cond)                                                     \
    do {                                                                \
        if (cond) {                                                     \
            ++g_pass;                                                   \
        } else {                                                        \
            ++g_fail;                                                   \
            std::printf("FAIL %s:%d: %s\n", __FILE__, __LINE__, #cond); \
        }                                                               \
    } while (0)

int main()
{
    // 参数: (strokeOpen, seqMatches, branchHasOp, eligible, liveStarted)
    // 已有 paintop 的分支: 只要笔触开着且笔画号对就画, 不看 eligible (主笔中途切到快速路径也继续)
    CHECK(decideMirrorBatch(true, true, true, true, true) == MirrorBatchAction::Paint);
    CHECK(decideMirrorBatch(true, true, true, false, true) == MirrorBatchAction::Paint);
    // 第一次: 可实时 -> 建 paintop
    CHECK(decideMirrorBatch(true, true, false, true, false) == MirrorBatchAction::CreateAndPaint);
    CHECK(decideMirrorBatch(true, true, false, true, true) == MirrorBatchAction::CreateAndPaint);
    // 不可实时且本笔还没实时墨 -> 拒绝 (Kotlin 重放, 不会双画)
    CHECK(decideMirrorBatch(true, true, false, false, false) == MirrorBatchAction::Reject);
    CHECK(!mirrorBatchAccepted(MirrorBatchAction::Reject));
    // 不可实时但本笔已有实时墨 -> 只能丢弃并报接受, 绝不报拒绝
    CHECK(decideMirrorBatch(true, true, false, false, true) == MirrorBatchAction::DropAccepted);
    CHECK(mirrorBatchAccepted(MirrorBatchAction::DropAccepted));
    CHECK(mirrorBatchAccepted(MirrorBatchAction::Paint));
    CHECK(mirrorBatchAccepted(MirrorBatchAction::CreateAndPaint));

    // s12-F1: 笔触已关 / 笔画号不符 -> 过期 (丢弃, 不改 Kotlin 状态), 与其它输入无关
    CHECK(decideMirrorBatch(false, true, true, true, false) == MirrorBatchAction::Stale);
    CHECK(decideMirrorBatch(false, true, true, true, true) == MirrorBatchAction::Stale);
    CHECK(decideMirrorBatch(true, false, true, true, true) == MirrorBatchAction::Stale);
    CHECK(decideMirrorBatch(true, false, false, true, false) == MirrorBatchAction::Stale);
    CHECK(!mirrorBatchAccepted(MirrorBatchAction::Stale));

    // JNI 返回码
    CHECK(mirrorBatchResultCode(MirrorBatchAction::Paint) == 1);
    CHECK(mirrorBatchResultCode(MirrorBatchAction::CreateAndPaint) == 1);
    CHECK(mirrorBatchResultCode(MirrorBatchAction::DropAccepted) == 1);
    CHECK(mirrorBatchResultCode(MirrorBatchAction::Reject) == 0);
    CHECK(mirrorBatchResultCode(MirrorBatchAction::Stale) == -1);

    // 不变式穷举: liveStarted 时任何输入都不会 Reject; 笔画号不符时一律 Stale
    bool neverReject = true;
    bool mismatchAlwaysStale = true;
    for (int m = 0; m < 32; ++m) {
        const bool open = m & 1, seq = m & 2, hasOp = m & 4, elig = m & 8, live = m & 16;
        const MirrorBatchAction a = decideMirrorBatch(open, seq, hasOp, elig, live);
        if (live && a == MirrorBatchAction::Reject) {
            neverReject = false;
        }
        if ((!seq || !open) && a != MirrorBatchAction::Stale) {
            mismatchAlwaysStale = false;
        }
    }
    CHECK(neverReject);
    CHECK(mismatchAlwaysStale);

    // 脏区补标判据 (与主笔 ADR-0002/0005 一致)
    CHECK(mirrorDirtyNeedsFallback(true, 100, 100, 50.0));
    CHECK(!mirrorDirtyNeedsFallback(false, 20, 20, 60.0));   // minSpan = 15
    CHECK(mirrorDirtyNeedsFallback(false, 14, 40, 60.0));
    CHECK(mirrorDirtyNeedsFallback(false, 40, 3, 10.0));     // minSpan = max(4, 2) = 4
    CHECK(!mirrorDirtyNeedsFallback(false, 4, 4, 10.0));
    CHECK(!mirrorDirtyNeedsFallback(false, 1, 1, 5.0));      // 小笔径不判

    std::printf("MirrorLivePolicyTest: %d passed, %d failed\n", g_pass, g_fail);
    return g_fail == 0 ? 0 : 1;
}
