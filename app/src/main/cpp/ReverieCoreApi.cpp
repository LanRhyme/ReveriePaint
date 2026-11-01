/*
 * ReverieCore C ABI 实现 (P0: 只读图层快照)
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * 快照的内容必须与 Kotlin 侧 syncLayersFromNative() 逐字段等价 —— 那是 P0 的验收标准
 * (docs/plans/CORE-SPLIT.md §4)。因此这里刻意逐项复用引擎既有访问器, 不另建一套查询。
 *
 * 线程: 快照生成假设调用方在同一线程 (UI 线程) 上、且此刻没有引擎写入。当前所有
 * syncLayersFromNative() 调用点都满足这个前提; 后续引入并发写时需要在外层加锁。
 */

#include "reverie_core.h"

#include "ReverieCore.h"
#include "reverie_jni_common.h"

#include <QString>
#include <QVector>

#include <atomic>
#include <new>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <vector>

namespace {

std::atomic<uint32_t> g_snapshotRev{0};
char g_lastError[256] = {0};
std::mutex g_snapshotMutex;

void setError(const char *msg)
{
    std::snprintf(g_lastError, sizeof(g_lastError), "%s", msg ? msg : "");
}

void copyString(char *dst, size_t cap, const QString &src)
{
    if (cap == 0) return;
    const QByteArray utf8 = src.toUtf8();
    const size_t n = qMin<size_t>(static_cast<size_t>(utf8.size()), cap - 1);
    if (n) std::memcpy(dst, utf8.constData(), n);
    dst[n] = '\0';
}

uint32_t layerFlags(ReverieCore *core, int index, bool soloShadowed)
{
    uint32_t flags = 0;
    if (core->layerVisible(index)) flags |= RP_LAYER_VISIBLE;
    if (core->layerLocked(index)) flags |= RP_LAYER_LOCKED;
    if (core->layerAlphaLocked(index)) flags |= RP_LAYER_ALPHA_LOCKED;
    if (core->layerIsGroup(index)) flags |= RP_LAYER_GROUP;
    if (core->isLayerStroke(index)) flags |= RP_LAYER_STROKE;
    if (core->layerBackground(index)) flags |= RP_LAYER_BACKGROUND;
    if (core->layerSoloed(index)) flags |= RP_LAYER_SOLOED;
    if (core->layerClipped(index)) flags |= RP_LAYER_CLIPPED;
    if (core->layerAlphaInherited(index)) flags |= RP_LAYER_ALPHA_INHERITED;
    if (core->layerIsGroup(index) && core->groupPassThrough(index)) flags |= RP_LAYER_PASS_THROUGH;
    if (soloShadowed) flags |= RP_LAYER_SOLO_SHADOWED;
    return flags;
}

} // namespace

struct rp_snapshot {
    std::atomic<int> refCount{1};
    uint32_t rev = 0;
    rp_doc_info info{};
    std::vector<rp_layer_snapshot> layers;
};

extern "C" {

uint32_t rp_abi_version(void)
{
    return RP_ABI_VERSION;
}

rp_doc *rp_doc_get(void)
{
    return reinterpret_cast<rp_doc *>(core());
}

const rp_snapshot *rp_doc_snapshot(rp_doc *doc)
{
    auto *corePtr = reinterpret_cast<ReverieCore *>(doc);
    if (!corePtr) {
        setError("engine not ready");
        return nullptr;
    }

    std::lock_guard<std::mutex> lock(g_snapshotMutex);
    // 快照在主线程读, 图层表由核心线程重建; 两把锁缺一不可:
    // g_snapshotMutex 串行化多次快照, layerMirrorMutex 串行化"重建 vs 读取"。
    // m_layers 里是裸节点指针, 节点已被移出图层树但尚未重建时靠 m_layerMirrorRefs 保活。
    std::lock_guard<std::mutex> mirrorLock(corePtr->layerMirrorMutex());

    auto *snap = new (std::nothrow) rp_snapshot();
    if (!snap) {
        setError("out of memory");
        return nullptr;
    }

    const int count = corePtr->layerCount();
    const bool soloActive = corePtr->soloActive();
    QVector<int> soloKeep;
    if (soloActive) soloKeep = corePtr->soloKeepIndices();

    snap->rev = ++g_snapshotRev;
    snap->info.layer_count = static_cast<uint32_t>(count);
    snap->info.current_layer = corePtr->currentLayerIndex();
    snap->info.solo_active = soloActive ? 1u : 0u;
    snap->layers.resize(static_cast<size_t>(qMax(0, count)));

    for (int i = 0; i < count; ++i) {
        rp_layer_snapshot &out = snap->layers[static_cast<size_t>(i)];
        std::memset(&out, 0, sizeof(out));

        out.id = corePtr->layerId(i);
        out.index = i;
        out.node_type = corePtr->layerNodeType(i);
        out.depth = corePtr->layerDepth(i);
        out.color_label = corePtr->layerColorLabel(i);
        out.opacity = corePtr->layerOpacity(i);
        copyString(out.name, RP_NAME_CAP, corePtr->layerName(i));
        copyString(out.blend_mode, RP_BLEND_CAP, corePtr->layerBlendMode(i));

        int strokeSize = 6, strokePos = 0, strokeOpacity = 100;
        quint32 strokeColor = 0xFF000000u;
        corePtr->getLayerStrokeParams(i, strokeSize, strokeColor, strokePos, strokeOpacity);
        out.stroke_size = strokeSize;
        out.stroke_color = strokeColor;
        out.stroke_position = strokePos;
        out.stroke_opacity = strokeOpacity;
        out.fill_color = corePtr->getFillLayerColor(i);

        const bool soloShadowed = soloActive && !soloKeep.contains(i);
        out.flags = layerFlags(corePtr, i, soloShadowed);
    }

    return snap;
}

void rp_snapshot_release(const rp_snapshot *snap)
{
    if (!snap) return;
    auto *mutableSnap = const_cast<rp_snapshot *>(snap);
    if (--mutableSnap->refCount == 0) {
        delete mutableSnap;
    }
}

uint32_t rp_snapshot_rev(const rp_snapshot *snap)
{
    return snap ? snap->rev : 0;
}

int rp_snapshot_info(const rp_snapshot *snap, rp_doc_info *out)
{
    if (!snap || !out) return 0;
    *out = snap->info;
    return 1;
}

int rp_snapshot_layer_count(const rp_snapshot *snap)
{
    return snap ? static_cast<int>(snap->layers.size()) : 0;
}

int rp_snapshot_layer(const rp_snapshot *snap, int index, rp_layer_snapshot *out)
{
    if (!snap || !out) return 0;
    if (index < 0 || index >= static_cast<int>(snap->layers.size())) return 0;
    *out = snap->layers[static_cast<size_t>(index)];
    return 1;
}

const char *rp_last_error(void)
{
    return g_lastError;
}

} // extern "C"
